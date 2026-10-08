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
    os.environ.update({key: value for key, value in env.items() if value is not None})
    try:
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = gate.main()
        return code, buffer.getvalue()
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def test_fails_closed_without_a_plugin_checkout():
    code, output = _run({}, {gate.PLUGIN_ENV: None, "ALLOW_SKIP_PIN_GATE": None})

    assert code == 1
    assert gate.PLUGIN_ENV in output


def test_skip_requires_an_explicit_opt_in():
    code, _ = _run({}, {gate.PLUGIN_ENV: None, "ALLOW_SKIP_PIN_GATE": "1"})

    assert code == 0


def test_fails_when_the_pinned_commit_is_absent_locally():
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "a" * 40)

        code, output = _run(
            {}, {gate.PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
        )

    assert code == 1
    assert "fetch" in output


def test_fails_when_the_pin_names_another_repository():
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "b" * 40, repository="https://github.com/someone/else.git")

        code, output = _run(
            {}, {gate.PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
        )

    assert code == 1
    assert "不是本仓库" in output


def test_fails_when_the_pin_is_not_a_full_commit():
    with tempfile.TemporaryDirectory() as raw:
        plugin_root = Path(raw)
        _pin(plugin_root, "abc123")

        code, output = _run(
            {}, {gate.PLUGIN_ENV: str(plugin_root), "ALLOW_SKIP_PIN_GATE": None},
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


def test_matches_reports_none_for_an_unknown_commit():
    assert gate.contract_matches("c" * 40) is None


def test_detects_a_contract_change_against_the_pinned_commit():
    revision = gate.head_revision()
    matches = gate.contract_matches(revision)

    assert matches is True, "当前工作区的契约应与本仓 HEAD 一致"


