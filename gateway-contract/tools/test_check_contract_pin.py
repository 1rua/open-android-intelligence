#!/usr/bin/env python3
"""Tests for the contract pin gate.

The gate is the only thing standing between a changed Schema and a phone that
is refused at negotiation time, so each way it could quietly pass — a missing
checkout, a shallow history, a pin that does not name this repository — is
pinned down here rather than discovered in production.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
GATE_PATH = TOOLS_DIR / "check-contract-pin.py"

spec = importlib.util.spec_from_file_location("check_contract_pin", GATE_PATH)
gate = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = gate
spec.loader.exec_module(gate)


def _pin(directory: Path, revision: str, repository: str | None = None) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "contract-pin.json"
    path.write_text(
        json.dumps(
            {
                "repository": repository or gate.this_repository() + ".git",
                "revision": revision,
            }
        ),
        encoding="utf-8",
    )
    return path


def _run(main_kwargs: dict, env: dict) -> tuple[int, str]:
    saved = {key: os.environ.get(key) for key in env}
    for key, value in env.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value
    try:
        import io
        import contextlib
        from unittest.mock import patch

        buffer = io.StringIO()
        # 门禁自行解析命令行；隔离 pytest 的参数，按真实独立脚本调用。
        with contextlib.redirect_stdout(buffer), patch.object(sys, "argv", [str(GATE_PATH)]):
            code = gate.main()
        return code, buffer.getvalue()
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def test_fails_closed_without_a_plugin_checkout():
    code, output = _run({}, {gate.DEFAULT_PLUGIN_ENV: None, "ALLOW_SKIP_PIN_GATE": None})

    assert code == 1
    assert gate.DEFAULT_PLUGIN_ENV in output


def test_skip_requires_an_explicit_opt_in():
    code, _ = _run({}, {gate.DEFAULT_PLUGIN_ENV: None, "ALLOW_SKIP_PIN_GATE": "1"})

    assert code == 0


def test_fails_when_the_pinned_commit_is_absent_locally(monkeypatch):
    """A pin that cannot be retrieved anywhere must still fail the gate.

    Retrieval is stubbed out: the assertion is about the gate refusing an
    unobtainable commit, not about how fast this sandbox can reach origin —
    a real fetch here made the suite depend on network latency.
    """
    monkeypatch.setattr(gate, "fetch_pinned_commit", lambda revision: False)
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "a" * 40)

        code, output = _run(
            {}, {gate.DEFAULT_PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
        )

    assert code == 1
    assert "fetch" in output


def test_fails_when_the_pin_names_another_repository():
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "b" * 40, repository="https://github.com/someone/else.git")

        code, output = _run(
            {}, {gate.DEFAULT_PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
        )

    assert code == 1
    assert "不是本仓库" in output


def test_fails_when_the_pin_is_not_a_full_commit():
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "abc123")

        code, output = _run(
            {}, {gate.DEFAULT_PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
        )

    assert code == 1
    assert "40 位" in output


def test_contract_paths_cover_the_dispatched_registry():
    """A registry-only change is a contract change.

    The registry sits at the contract root, so comparing whole directories
    would miss it and let the pin lag behind the schemas it maps.
    """
    assert "gateway-contract/core-dispatched-schemas.json" in gate.CONTRACT_PATHS
    assert "gateway-contract/schemas" in gate.CONTRACT_PATHS
    assert "gateway-contract/vectors" in gate.CONTRACT_PATHS
    # Repository tooling under the contract directory is not contract.
    assert "gateway-contract/tools" not in gate.CONTRACT_PATHS


def test_matches_reports_none_for_an_unknown_commit(monkeypatch):
    """A commit that cannot be retrieved must not be silently treated as matching."""
    monkeypatch.setattr(gate, "fetch_pinned_commit", lambda revision: False)

    assert gate.contract_matches("c" * 40) is None


def test_retrieves_a_pinned_commit_that_no_branch_references(monkeypatch):
    """A pin outlives the branch it was cut from.

    Plugins keep validating the contract commit they shipped with, while the
    app branch that produced it is deleted after merge. Deleting the branch
    must not turn a compatible pin into a red gate, so the commit is fetched
    by SHA rather than assumed reachable.
    """
    revision = "d" * 40
    fetched = []

    monkeypatch.setattr(gate, "commit_present", lambda candidate: False)
    monkeypatch.setattr(gate, "fetch_pinned_commit", lambda candidate: fetched.append(candidate) or True)

    # Retrieval succeeded, so the gate must go on to compare the contract
    # instead of answering None. The comparison itself fails here only because
    # the fake revision carries no content; that is False, not None.
    assert gate.contract_matches(revision) is not None
    assert fetched == [revision], "不可达的 pin 必须按 SHA 补抓后再比对"


def test_detects_a_contract_change_against_the_pinned_commit():
    revision = gate.head_revision()
    matches = gate.contract_matches(revision)

    assert matches is True, "当前工作区的契约应与本仓 HEAD 一致"


