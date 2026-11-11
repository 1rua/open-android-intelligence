"""验证性能证据的身份完整性、真实计时边界与命令失败传播。"""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest

SCRIPT = Path(__file__).parent / "ci" / "android_app_performance.py"
spec = importlib.util.spec_from_file_location("android_app_performance", SCRIPT)
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


def xml_results(directory, cases, *, tests=None):
    directory.mkdir(parents=True, exist_ok=True)
    count = len(cases) if tests is None else tests
    body = "".join(f'<testcase classname="{classname}" name="{name}" time="1">{state}</testcase>' for classname, name, state in cases)
    (directory / "TEST-cases.xml").write_text(f'<testsuite name="suite" tests="{count}" time="3">{body}</testsuite>')
    return directory


def test_empty_xml_and_declared_missing_events_fail(tmp_path):
    with pytest.raises(ValueError, match="XML"):
        perf.parse_xml_results(tmp_path)
    xml_results(tmp_path, [("a", "first", "")], tests=2)
    with pytest.raises(ValueError, match="事件不符"):
        perf.parse_xml_results(tmp_path)


def test_duplicates_fail_even_when_count_looks_correct(tmp_path):
    results = xml_results(tmp_path, [("a", "same", ""), ("a", "same", "")])
    summary, code = perf.build_summary(results, None, None)
    assert code == 1
    assert summary["test_manifest"]["duplicates"] == [{"classname": "a", "name": "same"}]


def test_existing_full_skip_is_preserved_and_new_skip_fails(tmp_path):
    expected = (*perf.EXPECTED_FULL_SKIP, "<skipped/>")
    baseline_results = xml_results(tmp_path / "old", [("a", "first", ""), expected])
    baseline, code = perf.build_summary(baseline_results, None, None)
    assert code == 0
    baseline_path = tmp_path / "baseline.json"
    perf.write_json(baseline_path, baseline)
    same, code = perf.build_summary(baseline_results, None, None, baseline_path)
    assert code == 0
    assert same["test_manifest"]["sha256"] == baseline["test_manifest"]["sha256"]
    changed = xml_results(tmp_path / "new", [("a", "first", "<skipped/>"), expected])
    result, code = perf.build_summary(changed, None, None, baseline_path)
    assert code == 1
    assert result["comparison"]["new_skips"] == [("a", "first")]


def test_missing_identity_fails_and_extra_suite_does_not_add_identity(tmp_path):
    old = xml_results(tmp_path / "old", [("a", "first", ""), ("a", "second", "")])
    baseline, _ = perf.build_summary(old, None, None)
    path = tmp_path / "baseline.json"
    perf.write_json(path, baseline)
    new = xml_results(tmp_path / "new", [("a", "first", "")])
    (new / "TEST-wrapper.xml").write_text('<testsuite name="NewSuite" tests="0" time="0"/>')
    result, code = perf.build_summary(new, None, None, path)
    assert code == 1
    assert result["comparison"]["missing"] == [("a", "second")]
    assert result["test_manifest"]["count"] == 1


def test_case_failure_is_never_a_valid_baseline(tmp_path):
    results = xml_results(tmp_path, [("a", "first", "<failure message='失败'/>" )])
    result, code = perf.build_summary(results, None, None)
    assert code == 1
    assert result["xml_summary"]["failures"] == 1
    assert result["host_metrics"]["status"] == "unknown"


def test_proc_stat_handles_spaces_parentheses_and_pid_identity():
    fields = ["S", "12"] + ["0"] * 20
    fields[11], fields[12], fields[19], fields[21] = "8", "3", "401", "21"
    result = perf.parse_proc_stat("123 (java (测试) worker) " + " ".join(fields))
    assert result == {"pid": 123, "ppid": 12, "cpu_ticks": 11, "starttime_ticks": 401, "rss_pages": 21}
    assert perf.parse_meminfo("MemAvailable: 123 kB\nVmHWM: 7 kB")["VmHWM"] == 7168
    assert perf.parse_pressure("some avg10=1.23 avg60=2.0 avg300=3.0 total=17")["some"]["total"] == 17


def test_command_exit_and_timestamp_log_are_preserved(tmp_path):
    output = tmp_path / "metrics"
    result = subprocess.run([sys.executable, str(SCRIPT), "run", "--output", str(output), "--", sys.executable, "-c", "print('全部日志'); raise SystemExit(7)"], capture_output=True, text=True)
    assert result.returncode == 7
    record = json.loads((output / "run.json").read_text())
    assert record["exit_code"] == 7
    assert record["sample_count"] >= 1
    assert record["command"][-1] == "print('全部日志'); raise SystemExit(7)"
    assert record["git"]["commit_sha"]
    assert record["host"]["logical_cpus"] >= 1
    assert "environment" not in record
    assert "全部日志" in (output / "gradle.log").read_text()
    assert "全部日志" not in result.stdout
    again = subprocess.run([sys.executable, str(SCRIPT), "run", "--output", str(output), "--", sys.executable, "-c", "pass"], capture_output=True)
    assert again.returncode == 2
    assert json.loads((output / "run.json").read_text())["exit_code"] == 7


def test_missing_executable_propagates_127(tmp_path):
    assert perf.run_command(tmp_path, ["./不存在的命令"]) == 127


def test_profile_uses_reported_task_and_configuration_durations(tmp_path):
    profile = tmp_path / "profile-test.html"
    profile.write_text('''<h2>Summary</h2><table><tr><td>Total Build Time</td><td>1m2.500s</td></tr></table>
    <h2>Configuration</h2><table><tr><td>All projects</td><td>0.200s</td></tr></table>
    <h2>Task Execution</h2><table><tr><td>:app</td><td>1m0.0s</td><td>(total)</td></tr><tr><td>:app:testFullDebugUnitTest</td><td>59.500s</td><td></td></tr></table>''')
    result = perf.parse_gradle_profile(profile)
    report = result["reports"][0]
    assert report["summary_seconds"]["Total Build Time"] == 62.5
    assert report["configuration"][0]["seconds"] == .2
    assert len(report["tasks"]) == 1
    assert report["tasks"][0]["task"] == ":app:testFullDebugUnitTest"
    assert report["tasks"][0]["seconds"] == 59.5


def test_bootstrap_is_fork_and_worker_range_not_jvm_startup(tmp_path):
    log = tmp_path / "gradle.log"
    log.write_text("2026-11-11T01:00:00.000+00:00 Starting process 'Gradle Test Executor 2'\n2026-11-11T01:00:01.250+00:00 Gradle Test Executor 2 started executing tests.\n")
    result = perf.parse_executor_bootstrap(log)
    assert result["meaning"] == "fork_and_worker_bootstrap"
    assert result["events"] == [{"executor": 2, "seconds": 1.25}]


def test_robolectric_nested_metrics_and_gc_are_kept_separate(tmp_path):
    row = {"event": "robolectric", "pid": 20, "gc_count": 3, "gc_ms": 8, "metrics": [{"name": "initialization", "elapsed_ns": 100, "count": 1}, {"name": "nested", "elapsed_ns": 50, "count": 1}]}
    (tmp_path / "robolectric-20.jsonl").write_text(json.dumps(row) + "\n")
    result = perf.parse_metrics(tmp_path)
    assert result["robolectric"]["initialization"]["elapsed_ns_sum"] == 100
    assert result["robolectric"]["metrics_by_name"]["nested"]["elapsed_ns_sum"] == 50
    assert result["robolectric"]["gc"]["by_pid"][20]["gc_ms"] == 8
    assert result["host_metrics"]["status"] == "unknown"


def test_failed_baseline_and_missing_measurements_are_explicit(tmp_path):
    results = xml_results(tmp_path / "current", [("a", "first", "")])
    result, code = perf.build_summary(results, None, None)
    assert code == 0
    assert result["measurement_status"] == "unknown"
    baseline = {"test_manifest": {"identities": [{"classname": "a", "name": "first", "status": "failed"}]}}
    baseline_path = tmp_path / "bad-baseline.json"
    perf.write_json(baseline_path, baseline)
    with pytest.raises(ValueError, match="失败测试"):
        perf.build_summary(results, None, None, baseline_path)


def test_gc_pauses_are_not_added_to_concurrent_cycle_duration(tmp_path):
    (tmp_path / "gc-12.log").write_text("[0.007s] Using G1\n[0.638s] GC(0) Pause Young (Normal) 33M->16M(503M) 4.648ms\n[1.000s] GC(1) Concurrent Mark Cycle 20.0ms\n[1.001s] GC(1) Pause Remark 16M->16M(503M) 1.000ms\n")
    result = perf.parse_gc_logs(tmp_path)
    assert result["status"] == "measured"
    assert result["pause_count"] == 2
    assert result["pause_seconds_sum"] == pytest.approx(.005648)
    assert result["pause_seconds_max"] == pytest.approx(.004648)


@pytest.mark.parametrize("artifact", ["gc-1.log", "robolectric-1.jsonl"])
def test_run_rejects_preexisting_reporter_artifacts(tmp_path, artifact):
    (tmp_path / artifact).write_text("旧采集证据\n")
    with pytest.raises(ValueError, match="已有"):
        perf.run_command(tmp_path, [sys.executable, "-c", "raise SystemExit(99)"])
    assert not (tmp_path / "run.json").exists()
    assert (tmp_path / artifact).read_text() == "旧采集证据\n"


def test_run_allows_an_existing_empty_directory(tmp_path):
    assert perf.run_command(tmp_path, [sys.executable, "-c", "pass"]) == 0


def test_additional_test_identity_fails_strict_same_source_comparison(tmp_path):
    old = xml_results(tmp_path / "old", [("a", "first", "")])
    baseline, _ = perf.build_summary(old, None, None)
    baseline_path = tmp_path / "baseline.json"
    perf.write_json(baseline_path, baseline)
    new = xml_results(tmp_path / "new", [("a", "first", ""), ("a", "unexpected", "")])
    result, code = perf.build_summary(new, None, None, baseline_path)
    assert code == 1
    assert result["comparison"]["added"] == [("a", "unexpected")]
    assert result["comparison"]["status"] == "failed"


@pytest.mark.parametrize("run_record", [
    {"status": "running"},
    {"status": "completed"},
    {"status": "interrupted", "exit_code": 0},
    {"status": "completed", "exit_code": 7},
    {},
])
def test_existing_run_record_must_prove_success(tmp_path, run_record):
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    metrics = tmp_path / "metrics"
    perf.write_json(metrics / "run.json", run_record)
    result, code = perf.build_summary(results, None, metrics)
    assert code != 0
    assert result["status"] == "failed"
    assert result["measurement_status"] != "measured"


@pytest.mark.parametrize("baseline_change", [
    {"status": "failed"},
    {"run": {"status": "running"}},
    {"run": {"status": "completed"}},
    {"run": {"status": "completed", "exit_code": 7}},
])
def test_failed_or_incomplete_baseline_cannot_pass_comparison(tmp_path, baseline_change):
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    baseline, _ = perf.build_summary(results, None, None)
    baseline.update(baseline_change)
    baseline_path = tmp_path / "baseline.json"
    perf.write_json(baseline_path, baseline)
    with pytest.raises(ValueError, match="基线"):
        perf.build_summary(results, None, None, baseline_path)


def test_history_without_run_is_identity_only(tmp_path):
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    baseline, _ = perf.build_summary(results, None, None)
    baseline_path = tmp_path / "baseline.json"
    perf.write_json(baseline_path, baseline)
    result, code = perf.build_summary(results, None, None, baseline_path)
    assert code == 0
    assert result["comparison"]["status"] == "passed"
    assert result["measurement_status"] == "unknown"


def make_profile(path, seconds=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f'<h2>Summary</h2><table><tr><td>Total Build Time</td><td>{seconds}s</td></tr></table>')


def test_reporter_creation_without_reports_is_not_measured(tmp_path):
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    metrics = tmp_path / "metrics"
    perf.write_json(metrics / "run.json", {"status": "completed", "exit_code": 0})
    (metrics / "metrics.jsonl").write_text(json.dumps({"host": {"cpu_busy_percent": 20, "memory": {"MemAvailable": 1024}}}) + "\n")
    (metrics / "robolectric-1.jsonl").write_text(json.dumps({"event": "reporter-created", "pid": 1, "metrics": []}) + "\n")
    (metrics / "gradle.log").write_text("2026-11-11T01:00:00+00:00 Starting process 'Gradle Test Executor 2'\n2026-11-11T01:00:01+00:00 Gradle Test Executor 2 started executing tests.\n")
    profile = tmp_path / "profile.html"
    make_profile(profile)
    result, code = perf.build_summary(results, profile, metrics)
    assert code == 0
    assert result["robolectric"]["report_count"] == 0
    assert result["robolectric"]["status"] == "unknown"
    assert result["measurement_status"] != "measured"


def test_run_records_decoded_profile_relative_to_gradle_cwd(tmp_path, monkeypatch):
    gradle_cwd = tmp_path / "安卓 项目"
    gradle_cwd.mkdir()
    profile = gradle_cwd / "build" / "reports" / "profile" / "profile-本次 报告.html"
    make_profile(profile)
    monkeypatch.chdir(gradle_cwd)
    command = [sys.executable, "-c", f"print('See the profiling report at: {profile.as_uri()}')"]
    output = tmp_path / "metrics"
    assert perf.run_command(output, command) == 0
    record = json.loads((output / "run.json").read_text())
    assert record["profile_reports"] == ["build/reports/profile/profile-本次 报告.html"]


def test_profile_directory_requires_unique_current_run_reference(tmp_path):
    profiles = tmp_path / "profiles"
    make_profile(profiles / "profile-old.html", 999)
    make_profile(profiles / "profile-current.html", 5)
    metrics = tmp_path / "metrics"
    perf.write_json(metrics / "run.json", {"status": "completed", "exit_code": 0, "profile_reports": ["build/reports/profile/profile-current.html"]})
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    result, _ = perf.build_summary(results, profiles, metrics)
    assert result["gradle_profile"]["status"] == "measured"
    assert len(result["gradle_profile"]["reports"]) == 1
    assert result["gradle_profile"]["reports"][0]["file"] == "profile-current.html"
    assert result["gradle_profile"]["reports"][0]["summary_seconds"]["Total Build Time"] == 5


@pytest.mark.parametrize("recorded", [[], ["build/reports/profile/absent.html"], ["build/reports/profile/profile-one.html", "build/reports/profile/profile-two.html"]])
def test_unassociated_or_ambiguous_profile_directory_is_unknown(tmp_path, recorded):
    profiles = tmp_path / "profiles"
    make_profile(profiles / "profile-one.html")
    make_profile(profiles / "profile-two.html")
    metrics = tmp_path / "metrics"
    perf.write_json(metrics / "run.json", {"status": "completed", "exit_code": 0, "profile_reports": recorded})
    results = xml_results(tmp_path / "xml", [("a", "first", "")])
    result, _ = perf.build_summary(results, profiles, metrics)
    assert result["gradle_profile"]["status"] == "unknown"
