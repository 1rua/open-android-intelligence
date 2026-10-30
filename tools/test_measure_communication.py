"""验证测量器的公开统计、隐私和独立进程命令行边界。"""
from copy import deepcopy
from dataclasses import dataclass
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

import pytest


SPEC = importlib.util.spec_from_file_location(
    "communication_measurement", Path(__file__).with_name("measure-communication.py"))
probe = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = probe
SPEC.loader.exec_module(probe)


@dataclass(frozen=True)
class SourceRepository:
    root: Path
    head: str
    git_env: dict

    def git(self, *args, input=None):
        result = subprocess.run(["git", "-C", str(self.root), *args], input=input,
                                env=self.git_env, text=True, capture_output=True, timeout=15, check=True)
        return result.stdout.strip()

    @property
    def source_argument(self):
        return os.path.relpath(self.root, probe.ROOT)


@pytest.fixture(scope="session")
def fixture_directory():
    # 全部 fixture 留在授权 evidence 内供复核，不调用 pytest 的自动删除临时目录。
    path = probe.EVIDENCE / ("validation-fixtures-" + uuid.uuid4().hex + ".tmp")
    path.mkdir(parents=True)
    return path


@pytest.fixture(scope="session")
def repository_factory(fixture_directory):
    def create(contents):
        root = fixture_directory / ("source-" + uuid.uuid4().hex)
        root.mkdir()
        for relative, content in contents.items():
            path = root / relative
            assert path.resolve().is_relative_to(root.resolve())
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content if isinstance(content, bytes) else content.encode())
        env = {k: v for k, v in os.environ.items() if k in {"PATH", "LANG", "LC_ALL", "TZ", "SYSTEMROOT"}}
        env.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull})
        repo = SourceRepository(root, "", env)
        repo.git("init", "--quiet", "--initial-branch=fixture", "--template=")
        repo.git("add", "--", *contents)
        tree = repo.git("write-tree")
        # 只在 fixture 内构造最小 Git 对象；不提交业务仓库，也不调用 git commit。
        commit = (f"tree {tree}\n"
                  "author Fixture <fixture@example.invalid> 1 +0000\n"
                  "committer Fixture <fixture@example.invalid> 1 +0000\n\n"
                  "Controlled measurement fixture\n")
        head = repo.git("hash-object", "-w", "-t", "commit", "--stdin", input=commit)
        repo.git("update-ref", "HEAD", head)
        return SourceRepository(root, head, env)
    return create


@pytest.fixture
def source_repository(repository_factory):
    return repository_factory({
        "oai-core/src/oai_core/__init__.py": "",
        "oai-core/src/oai_core/auth.py": "fixture_origin = 'selected'\n",
        "oai-core/src/oai_core/sessions.py": "fixture_origin = 'selected'\n",
        "oai-core/src/oai_core/adapters/remote.py": "fixture_origin = 'selected'\n",
        "integrations/hermes-session-adapter/src/oai_hermes_adapter/__init__.py": "",
        "integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py": "fixture_origin = 'selected'\n",
        "docs/fixture-note.md": "controlled clean documentation\n",
        ".gitignore": ".ignored/\n",
    })


@pytest.fixture(scope="session")
def runtime_contents():
    # 从本 checkout 复制真实路由与契约 JSON，不依赖祖先目录、分支或 dirty 状态。
    contents = {"docs/fixture-note.md": b"controlled clean documentation\n"}
    excluded = {"node_modules", "build", ".gradle", "dist", ".toolchains", "__pycache__", ".pytest_cache", ".git"}
    for directory in (*probe.SOURCE_DIRS.values(), Path("gateway-contract")):
        for current, directories, files in os.walk(probe.ROOT / directory):
            directories[:] = [name for name in directories if name not in excluded]
            for name in files:
                path = Path(current) / name
                if directory == Path("gateway-contract") and path.suffix != ".json":
                    continue
                if path.suffix not in {".pyc", ".pyo"}:
                    contents[path.relative_to(probe.ROOT).as_posix()] = path.read_bytes()
    return contents


@pytest.fixture(scope="session")
def runtime_repository(repository_factory, runtime_contents):
    return repository_factory(runtime_contents)


def validation_output(name):
    return probe.EVIDENCE / ("validation-" + name + "-" + uuid.uuid4().hex)


def test_empirical_percentile_uses_nearest_rank_and_keeps_small_p99_null():
    assert probe.percentile(list(range(1, 1001)), .99) == 990
    small = probe.quantiles(list(range(1, 31)))
    assert small["n"] == 30 and small["max"] == 30
    assert small["p99"] is None and small["p99_unavailable_reason"]


def test_null_is_not_zero_and_invalid_measurements_are_rejected():
    q = probe.quantiles([None, 0, 2, None])
    assert q["n"] == 2 and q["p50"] == 0 and q["p95"] == 2
    assert probe.quantiles([None])["p50"] is None
    for invalid in (-1, float("nan"), float("inf")):
        with pytest.raises(ValueError):
            probe.quantiles([invalid])


def row(success, elapsed):
    return {"mode": "direct", "scenario": "send", "role": "standard",
            "success": success, "consistent": success, "host_completed": success,
            "fault_injected": not success, "failure": None if success else "HOST_INTERRUPTED",
            "latency_ms": {"submit_to_finished": elapsed, "submit_to_final": elapsed if success else None}}


def test_failure_denominator_keeps_all_attempts_and_failed_durations():
    summary = probe.summarize([row(True, 10), row(False, 100), row(False, 200)])["direct/send"]
    assert summary["attempts"] == 3 and summary["failures"] == 2
    assert summary["failure_rate"] == 2 / 3
    assert summary["failure_classes"] == {"HOST_INTERRUPTED": 2}
    assert summary["latency_all_attempts_ms"]["submit_to_finished"]["n"] == 3
    assert summary["latency_successful_attempts_ms"]["submit_to_final"]["n"] == 1


def test_comparison_rejects_different_conditions():
    baseline = {"format": "test", "parameters": {"samples": 1000}, "environment": {"python": "test"},
                "measurement_script_sha256": "same"}
    for key in baseline:
        candidate = deepcopy(baseline)
        candidate[key] = "different"
        with pytest.raises(ValueError):
            probe.validate_comparable(baseline, candidate)


def test_source_manifest_hashes_controlled_repository_and_imports_in_fresh_process(source_repository):
    source = source_repository.root
    identity = probe.source_identity(source)
    assert identity["head"] == source_repository.head
    assert identity["dirty_files_sha256"] == {}
    assert identity["source_root_relative_to_meter"] == source_repository.source_argument
    assert "oai-core/src/oai_core/sessions.py" in identity["files_sha256"]
    script = """
import importlib.util, json, pathlib, sys
s=importlib.util.spec_from_file_location('meter', pathlib.Path('tools/measure-communication.py'))
m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)
source=pathlib.Path(sys.argv[1]).resolve();identity=m.source_identity(source)
api=m.load_source_api(source,identity)
assert all(module.fixture_origin == 'selected' for module in (api.auth,api.sessions,api.remote,api.bridge))
print(json.dumps(m.verify_imports(source,identity)))
"""
    result = subprocess.run([sys.executable, "-I", "-B", "-c", script, source_repository.source_argument],
                            cwd=probe.ROOT, text=True, capture_output=True, timeout=15)
    assert result.returncode == 0, result.stderr
    imported = json.loads(result.stdout)
    assert imported["oai_core.sessions"]["source_path"] == "oai-core/src/oai_core/sessions.py"
    assert imported["oai_hermes_adapter.bridge"]["sha256"] == identity["files_sha256"][
        "integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py"]


@pytest.mark.parametrize("staged", [False, True])
def test_manifest_covers_dirty_tracked_and_untracked_files_outside_selected_sources(source_repository, staged):
    source = source_repository.root
    before = probe.source_identity(source)
    tracked, untracked = "docs/fixture-note.md", "notes/new-note.txt"
    changed = {tracked: b"controlled changed documentation\n", untracked: b"controlled new note\n"}
    for name, content in changed.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    if staged:
        source_repository.git("add", "--", tracked, untracked)
    after = probe.source_identity(source)
    assert after["source_sha256"] == before["source_sha256"]
    assert after["head"] == before["head"]
    assert after["dirty_files_sha256"] == {name: hashlib.sha256(content).hexdigest() for name, content in changed.items()}
    assert after["dirty_source_files"] == []
    assert before != after  # worker 比较完整身份，不只比较 source_sha256。
    (source / tracked).write_bytes(b"controlled second change\n")
    later = probe.source_identity(source)
    assert later["dirty_files_sha256"][tracked] == hashlib.sha256(b"controlled second change\n").hexdigest()
    assert later != after


def test_manifest_detects_dirty_selected_source_content(source_repository):
    source = source_repository.root
    before = probe.source_identity(source)
    name = "oai-core/src/oai_core/sessions.py"
    (source / name).write_text("fixture_origin = 'changed'\n")
    after = probe.source_identity(source)
    digest = hashlib.sha256(b"fixture_origin = 'changed'\n").hexdigest()
    assert after["source_sha256"] != before["source_sha256"]
    assert after["files_sha256"][name] == after["dirty_files_sha256"][name] == digest
    assert after["dirty_source_files"] == [name]


def test_manifest_excludes_declared_evidence_ignored_files_and_other_repository_roots(source_repository, repository_factory):
    source = source_repository.root
    before = probe.source_identity(source)
    other = repository_factory({"docs/unrelated.md": "another controlled repository\n"})
    (other.root / "docs/unrelated.md").write_text("unrelated dirty content\n")
    assert probe.source_identity(other.root)["dirty_files_sha256"]
    evidence = "docs/reviews/evidence/2026-10-30-communication-reliability/measurements/fixture/result.json"
    for name in (evidence, ".ignored/cache.txt"):
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("controlled generated content\n")
    after = probe.source_identity(source)
    assert after == before
    assert after["dirty_hash_exclusions"] == ["docs/reviews/evidence/2026-10-30-communication-reliability/measurements/**"]


def test_safe_native_metadata_has_no_raw_identifiers_bodies_or_credentials():
    import json
    data = {"stage": "T2", "clientMessageId": "known_attempt", "requestId": "PRIVATE-TOKEN",
            "accountId": "PRIVATE-ACCOUNT", "conversationId": "PRIVATE-ACCOUNT", "text": "PRIVATE-BODY",
            "state": "PRIVATE-BODY", "eventType": "PRIVATE-TOKEN", "monotonicNs": 123,
            "elapsedMs": 1.5, "revision": 2, "Authorization": "PRIVATE-TOKEN"}
    safe = probe.safe_native_record(data, {"known_attempt"})
    assert safe["attempt_id"] == "known_attempt" and safe["stage"] == "T2"
    assert safe["elapsedMs"] == 1.5
    assert "PRIVATE" not in json.dumps(safe)
    assert probe.safe_native_record({"stage": "PRIVATE-BODY"}, set()) is None
    assert probe.safe_native_record([], set()) is None


def test_network_guard_rejects_nonloopback_before_any_connection():
    import subprocess
    script = """
import importlib.util, pathlib, socket, sys
s=importlib.util.spec_from_file_location('meter', pathlib.Path('tools/measure-communication.py'))
m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)
m.install_network_guard()
try:
    socket.create_connection(('198.51.100.1', 443), timeout=.1)
except PermissionError:
    print('BLOCKED')
"""
    result = subprocess.run([sys.executable, "-I", "-B", "-c", script], cwd=probe.ROOT,
                            text=True, capture_output=True, timeout=5)
    assert result.returncode == 0 and result.stdout.strip() == "BLOCKED"


@pytest.mark.parametrize("mode,scenario", [(mode, scenario) for mode in ("direct", "bridge")
    for scenario in ("create", "send", "create_disconnect", "send_disconnect", "bridge_disconnect")
    if mode == "bridge" or scenario != "bridge_disconnect"])
def test_real_loopback_socket_scenarios_preserve_failure_and_missing_boundaries(mode, scenario, runtime_repository):
    import json
    import subprocess
    script = """
import asyncio, importlib.util, json, pathlib, sys
s=importlib.util.spec_from_file_location('meter', pathlib.Path('tools/measure-communication.py'))
m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)
source=pathlib.Path(sys.argv[3]).resolve();api=m.load_source_api(source,m.source_identity(source))
m.install_network_guard()
async def run():
    async with m.Harness(api, sys.argv[1], 2) as h:
        o=m.Observation(sys.argv[2],sys.argv[1],0,.001)
        row=await m.measure_one(o,h)
        print(json.dumps({'row':row,'trace':h.trace.records}))
asyncio.run(run())
"""
    result = subprocess.run([sys.executable, "-I", "-B", "-c", script, mode, scenario, runtime_repository.source_argument],
                            cwd=probe.ROOT, text=True, capture_output=True, timeout=10)
    assert result.returncode == 0, result.stderr
    value = json.loads(result.stdout)
    row = value["row"]
    assert row["host_calls"] == 1
    assert row["success"] == (row["host_completed"] and row["consistent"])
    if scenario in {"create", "send"}:
        assert row["success"], row
    if scenario.endswith("disconnect"):
        assert row["fault_injected"], row
    if not row["success"]:
        assert row["failure"] and row["latency_ms"]["submit_to_finished"] is not None
    for name in probe.MISSING:
        assert row["latency_ms"][name] is None and row["missing_latency_reasons"][name]
    assert all(word not in result.stdout for word in ("synthetic request", "synthetic response", "synthetic-account", "Bearer "))
    assert result.stderr == ""


def test_trace_filter_rejects_nested_values_in_whitelisted_fields():
    assert probe.safe_native_record({"stage": ["PRIVATE"]}, set()) is None
    record = probe.safe_native_record({"stage": "T2", "state": ["PRIVATE"], "eventType": {"secret": "PRIVATE"}}, set())
    assert record == {"stage": "T2"}


def test_close_supports_old_sync_and_new_async_registry_or_ledger():
    import asyncio
    closed = []
    class Sync:
        def close(self):
            closed.append("sync")
    class Async:
        async def close(self):
            closed.append("async")
    async def run():
        await probe.close_maybe_async(Sync())
        await probe.close_maybe_async(Async())
    asyncio.run(run())
    assert closed == ["sync", "async"]


def test_public_cli_runs_all_scenarios_in_child_and_records_concurrency_resources_and_privacy(runtime_repository):
    import json
    import subprocess
    import uuid
    output = validation_output("controlled-cli")
    command = [sys.executable, "-B", "tools/measure-communication.py", "--source-root", runtime_repository.source_argument, "--output",
               str(output.relative_to(probe.ROOT)), "--label", "validation", "--samples", "16",
               "--delay-ms", "1", "--slow-delay-ms", "30", "--arrival-interval-ms", "10",
               "--resource-interval-ms", "1", "--warmup", "0", "--allow-small-validation-run"]
    result = subprocess.run(command, cwd=probe.ROOT, text=True, capture_output=True, timeout=30)
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert result.stderr == ""
    report = json.loads((output / "summary.json").read_text())
    assert len(report["summary"]) == 13
    assert report["source_verified_unchanged"] is True
    assert report["source"]["source_root_relative_to_meter"] == runtime_repository.source_argument
    assert report["source"]["head"] == runtime_repository.head
    assert report["source"]["dirty_files_sha256"] == {}
    import gzip
    with gzip.open(output / "samples.jsonl.gz", "rt", encoding="utf-8") as raw:
        rows = [json.loads(line) for line in raw]
    assert len(rows) == 13 * 16
    for summary in report["summary"].values():
        assert summary["attempts"] == 16
        assert summary["latency_all_attempts_ms"]["submit_to_finished"]["n"] == 16
        assert summary["latency_all_attempts_ms"]["submit_to_finished"]["p99"] is None
    for mode in ("direct", "bridge"):
        workload = report["workloads"][mode + "/concurrent_send"]
        assert workload["max_in_flight"] > 1
        isolation = report["summary"][mode + "/slow_adapter_isolation"]
        assert isolation["roles"]["slow"]["attempts"] == 2
        fast_rows = [r for r in rows if r["mode"] == mode and r["scenario"] == "slow_adapter_isolation" and r["role"] == "fast"]
        assert isolation["fast_attempts"] == len(fast_rows) == 14
        # CI 调度延迟不是测量器缺陷；验证统计忠于原始记录，不要求所有快请求都赶在固定毫秒数内。
        assert isolation["fast_finished_before_slow"] == sum(r["finished_before_slow"] for r in fast_rows)
        assert report["workloads"][mode + "/slow_adapter_isolation"]["slow_adapter_count"] == 1
        assert isolation["failures"] == 0
        resources = report["resources"][mode + "/slow_adapter_isolation"]
        assert resources["process_cpu_seconds"] > 0
        assert resources["event_loop_lag_ms"]["n"] > 0
        assert resources["rss_bytes"]["max"] > 0 and resources["fd_count"]["max"] > 0
    for path in output.iterdir():
        text = gzip.open(path, "rt", encoding="utf-8").read() if path.suffix == ".gz" else path.read_text()
        assert all(secret not in text for secret in ("synthetic request", "synthetic response", "synthetic-account", "Bearer ", "Authorization", str(probe.ROOT)))


def test_worker_rejects_non_source_dirty_change_before_claiming_verified(repository_factory, runtime_contents):
    repo = repository_factory(runtime_contents)
    before = probe.source_identity(repo.root)
    output = validation_output("manifest-mutation")
    script = """
import importlib.util, pathlib, sys
s=importlib.util.spec_from_file_location('meter', pathlib.Path('tools/measure-communication.py'))
m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)
original=m.execute_scenario
async def change_non_source(harness,scenario,args,count,start_index=0):
    value=await original(harness,scenario,args,count,start_index)
    if count:
        (args.source_root/'docs/fixture-note.md').write_text('controlled mid-run change\\n')
    return value
m.execute_scenario=change_non_source
raise SystemExit(m.main(['--worker','--source-root',sys.argv[1],'--output',sys.argv[2],
    '--label','validation','--samples','1','--modes','direct','--scenarios','create',
    '--delay-ms','1','--slow-delay-ms','30','--warmup','0','--allow-small-validation-run']))
"""
    result = subprocess.run([sys.executable, "-I", "-B", "-c", script, repo.source_argument,
                             str(output.relative_to(probe.ROOT))], cwd=probe.ROOT,
                            text=True, capture_output=True, timeout=15)
    assert result.returncode == 2, (result.stdout, result.stderr)
    assert result.stderr == ""
    messages = [json.loads(line) for line in result.stdout.splitlines()]
    assert messages[-1] == {"error_code": "SOURCE_OR_METER_CHANGED_DURING_MEASUREMENT"}
    assert not (output / "summary.json").exists()
    invalid = json.loads((output / "invalid.json").read_text())
    assert invalid["reason"] == "SOURCE_OR_METER_CHANGED_DURING_MEASUREMENT"
    after = probe.source_identity(repo.root)
    assert before["source_sha256"] == after["source_sha256"]
    assert before["dirty_files_sha256"] == {}
    assert after["dirty_files_sha256"] == {
        "docs/fixture-note.md": hashlib.sha256(b"controlled mid-run change\n").hexdigest()}
