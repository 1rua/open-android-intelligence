#!/usr/bin/env python3
"""采集 Android App 回归的墙钟、资源和测试身份，避免用累计时间冒充阶段时间。"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET

SCHEMA_VERSION = 1
EXPECTED_FULL_SKIP = (
    "com.openandroidintelligence.mobile.plugins.BuiltInCapabilitiesTest",
    "aChannelWithoutPluginRuntimesReportsEveryCapabilityAsUnavailableWithAReason",
)
UNKNOWN = {"status": "unknown"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_proc_stat(text: str) -> dict:
    """comm 可含空格和括号；第 22 字段 starttime 用于识别 PID 复用。"""
    close = text.rfind(")")
    if close < 0 or "(" not in text[:close]:
        raise ValueError("进程 stat 缺少名称边界")
    fields = text[close + 1:].split()
    if len(fields) < 22:
        raise ValueError("进程 stat 字段不足")
    return {
        "pid": int(text.split("(", 1)[0].strip()),
        "ppid": int(fields[1]),
        "cpu_ticks": int(fields[11]) + int(fields[12]),
        "starttime_ticks": int(fields[19]),
        "rss_pages": int(fields[21]),
    }


def parse_meminfo(text: str) -> dict:
    result = {}
    for line in text.splitlines():
        match = re.match(r"(\w+):\s+(\d+)(?:\s+(kB))?", line)
        if match:
            result[match[1]] = int(match[2]) * (1024 if match[3] else 1)
    return result


def parse_pressure(text: str) -> dict:
    result = {}
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        values = {}
        for part in parts[1:]:
            key, value = part.split("=", 1)
            values[key] = int(value) if key == "total" else float(value)
        result[parts[0]] = values
    return result


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except (OSError, PermissionError):
        return None


class ResourceSampler:
    def __init__(self, output: Path, working_directory: Path, interval: float = 1.0):
        self.output = output
        self.working_directory = working_directory.resolve()
        self.interval = interval
        self.stop = threading.Event()
        self.previous_cpu = None
        self.previous_processes = {}
        self.clock_ticks = os.sysconf("SC_CLK_TCK")
        self.page_size = os.sysconf("SC_PAGE_SIZE")
        self.peaks = {}
        self.samples = 0
        self.errors = []

    def snapshot(self) -> dict:
        sample = {"timestamp": utc_now(), "monotonic_seconds": time.monotonic(), "host": {}, "test_processes": []}
        cpu_text = read_text(Path("/proc/stat"))
        if cpu_text:
            cpu = next((line.split()[1:] for line in cpu_text.splitlines() if line.startswith("cpu ")), None)
            if cpu:
                values = [int(value) for value in cpu[:8]]
                total = sum(values)
                idle = values[3] + (values[4] if len(values) > 4 else 0)
                sample["host"]["cpu_ticks"] = total
                if self.previous_cpu:
                    elapsed = total - self.previous_cpu[0]
                    sample["host"]["cpu_busy_percent"] = 100 * (elapsed - (idle - self.previous_cpu[1])) / elapsed if elapsed > 0 else None
                self.previous_cpu = (total, idle)
        mem_text = read_text(Path("/proc/meminfo"))
        if mem_text:
            memory = parse_meminfo(mem_text)
            sample["host"]["memory"] = {name: memory.get(name) for name in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree")}
        sample["host"]["pressure"] = {}
        for kind in ("cpu", "memory", "io"):
            text = read_text(Path("/proc/pressure") / kind)
            if text:
                sample["host"]["pressure"][kind] = parse_pressure(text)
        try:
            paths = Path("/proc").iterdir()
            for path in paths:
                if not path.name.isdigit():
                    continue
                try:
                    command = (path / "cmdline").read_bytes()
                    if b"Gradle Test Executor" not in command or b"GradleWorkerMain" not in command:
                        continue
                    cwd = (path / "cwd").resolve(strict=True)
                    if not cwd.is_relative_to(self.working_directory):
                        continue
                    stat = parse_proc_stat((path / "stat").read_text())
                    status = parse_meminfo((path / "status").read_text())
                except (OSError, ValueError):
                    continue
                identity = (stat["pid"], stat["starttime_ticks"])
                process = {
                    "pid": stat["pid"], "starttime_ticks": stat["starttime_ticks"],
                    "cpu_seconds": stat["cpu_ticks"] / self.clock_ticks,
                    "rss_bytes": stat["rss_pages"] * self.page_size,
                    "peak_rss_bytes": status.get("VmHWM"),
                }
                previous = self.previous_processes.get(identity)
                if previous:
                    elapsed = sample["monotonic_seconds"] - previous[0]
                    process["cpu_percent_one_core"] = 100 * (process["cpu_seconds"] - previous[1]) / elapsed if elapsed > 0 else None
                self.previous_processes[identity] = (sample["monotonic_seconds"], process["cpu_seconds"])
                peak = self.peaks.setdefault(identity, dict(process))
                for name in ("cpu_seconds", "rss_bytes", "peak_rss_bytes"):
                    if process.get(name) is not None:
                        peak[name] = max(peak.get(name) or 0, process[name])
                sample["test_processes"].append(process)
        except OSError as error:
            self.errors.append(type(error).__name__)
        return sample

    def collect(self) -> None:
        try:
            with self.output.open("w", encoding="utf-8") as stream:
                while True:
                    stream.write(json.dumps(self.snapshot(), ensure_ascii=False) + "\n")
                    stream.flush()
                    self.samples += 1
                    if self.stop.wait(self.interval):
                        break
        except Exception as error:
            self.errors.append(f"{type(error).__name__}: {error}")


def checkout_metadata() -> dict:
    """只记录版本和脏状态，不记录凭据、环境或无关进程参数。"""
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        status = subprocess.run(["git", "status", "--porcelain", "--untracked-files=normal"], capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return {"status": "unknown", "source_hash": None}
    return {"status": "measured", "commit_sha": commit, "working_tree_dirty": bool(status), "source_hash": None, "limits": "记录提交和脏状态；尚未计算全部工作区源码内容哈希，脏工作区不能按提交相同认定输入相同。"}


def profile_report_from_stdout(line: str, working_directory: Path) -> str | None:
    """只信任本次 Gradle 输出的 file URI，不用修改时间猜报告归属。"""
    plain = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line)
    match = re.search(r"See the profiling report at:\s*(file:.*)", plain)
    if not match:
        return None
    uri = urlparse(match[1].strip())
    if uri.scheme != "file" or uri.netloc not in ("", "localhost") or not uri.path:
        return None
    path = Path(unquote(uri.path))
    if not path.is_absolute():
        path = working_directory / path
    return os.path.relpath(path, working_directory)


def run_command(output: Path, command: list[str]) -> int:
    if not command:
        raise ValueError("-- 后必须提供 Gradle 命令")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("采集目录已有结果，请使用新的目录保存本次证据")
    memory = parse_meminfo(read_text(Path("/proc/meminfo")) or "")
    record = {"schema_version": SCHEMA_VERSION, "started_at": utc_now(), "status": "running", "command": command, "profile_reports": [], "git": checkout_metadata(), "host": {"logical_cpus": os.cpu_count(), "affinity_cpus": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None, "mem_total_bytes": memory.get("MemTotal")}}
    write_json(output / "run.json", record)
    sampler = ResourceSampler(output / "metrics.jsonl", Path.cwd())
    thread = threading.Thread(target=sampler.collect, name="android-perf-sampler", daemon=True)
    started = time.monotonic()
    thread.start()
    child = None
    old_handlers = {}
    try:
        with (output / "gradle.log").open("w", encoding="utf-8") as log:
            try:
                child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1, start_new_session=True)
            except OSError as error:
                record["error"] = str(error)
                record["exit_code"] = 127
            else:
                def forward(signum, _frame):
                    try:
                        os.killpg(child.pid, signum)
                    except ProcessLookupError:
                        pass
                for sig in (signal.SIGINT, signal.SIGTERM):
                    old_handlers[sig] = signal.signal(sig, forward)
                for line in child.stdout:
                    stamped = f"{utc_now()} {line.rstrip()}\n"
                    log.write(stamped)
                    log.flush()
                    report = profile_report_from_stdout(line, Path.cwd())
                    if report is not None and report not in record["profile_reports"]:
                        record["profile_reports"].append(report)
                    if re.search(r"> Task |BUILD |failed|\berror\b|exception", line, re.I):
                        print(stamped, end="", flush=True)
                return_code = child.wait()
                record["command_returncode"] = return_code
                record["exit_code"] = return_code if return_code >= 0 else 128 - return_code
    finally:
        sampler.stop.set()
        thread.join(timeout=5)
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        record.update({"finished_at": utc_now(), "wall_seconds": time.monotonic() - started, "status": "completed", "sample_count": sampler.samples, "sampling_errors": sampler.errors, "test_process_peaks": list(sampler.peaks.values())})
        write_json(output / "run.json", record)
    return record["exit_code"]


def parse_xml_results(results: Path) -> dict:
    paths = [results] if results.is_file() else sorted(results.rglob("TEST-*.xml")) if results.exists() else []
    if not paths:
        raise ValueError("没有找到测试 XML，无法证明回归实际执行")
    identities, classes, methods, duplicates = [], [], [], []
    seen = set()
    for path in paths:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in suites:
            cases = []
            for case in suite.findall("testcase"):
                classname, name = case.get("classname"), case.get("name")
                if not classname or not name:
                    raise ValueError("测试 XML 缺少 classname 或 name，不能比较用例身份")
                identity = (classname, name)
                if identity in seen:
                    duplicates.append({"classname": classname, "name": name})
                seen.add(identity)
                status = "failed" if case.find("failure") is not None else "error" if case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
                duration = float(case.get("time", "0"))
                if duration < 0 or not math.isfinite(duration):
                    raise ValueError("测试 XML 的耗时无效")
                row = {"classname": classname, "name": name, "status": status, "seconds": duration}
                cases.append(row)
                methods.append(row)
                identities.append({key: row[key] for key in ("classname", "name", "status")})
            declared = suite.get("tests")
            if declared is not None and int(declared) != len(cases):
                raise ValueError(f"测试 XML 声明用例数与实际事件不符：{suite.get('name', '')}")
            for attribute, states in (("skipped", {"skipped"}), ("failures", {"failed"}), ("errors", {"error"})):
                if suite.get(attribute) is not None and int(suite.get(attribute)) != sum(case["status"] in states for case in cases):
                    raise ValueError(f"测试 XML 的 {attribute} 声明与实际事件不符")
            if cases:
                case_sum = sum(case["seconds"] for case in cases)
                classes.append({"classname": suite.get("name", cases[0]["classname"]), "cases": len(cases), "seconds": float(suite.get("time", case_sum)), "case_seconds_sum": case_sum, "first_method": cases[0]["name"], "first_method_seconds": cases[0]["seconds"], "first_method_share": cases[0]["seconds"] / case_sum if case_sum else 0})
    if not identities:
        raise ValueError("测试 XML 没有 testcase，无法证明回归实际执行")
    identities.sort(key=lambda row: (row["classname"], row["name"]))
    encoded = json.dumps(identities, ensure_ascii=False, separators=(",", ":")).encode()
    skipped = [row for row in identities if row["status"] == "skipped"]
    failures = [row for row in identities if row["status"] in ("failed", "error")]
    return {"manifest": {"count": len(identities), "unique_count": len(seen), "sha256": hashlib.sha256(encoded).hexdigest(), "identities": identities, "skipped": skipped, "failures": failures, "duplicates": duplicates}, "summary": {"xml_files": len(paths), "classes": len(classes), "cases": len(identities), "skipped": len(skipped), "failures": len(failures), "suite_seconds_sum": sum(row["seconds"] for row in classes), "case_seconds_sum": sum(row["seconds"] for row in methods), "first_method_seconds_sum": sum(row["first_method_seconds"] for row in classes)}, "rankings": {"classes": sorted(classes, key=lambda row: row["seconds"], reverse=True), "methods": sorted(methods, key=lambda row: row["seconds"], reverse=True)}}


class ProfileParser(HTMLParser):
    """按 Gradle 原生 profile 的标题和表格读取，不依赖 CSS 排列。"""
    def __init__(self):
        super().__init__()
        self.heading = ""
        self.section = ""
        self.in_heading = False
        self.cell = None
        self.row = None
        self.rows = []

    def handle_starttag(self, tag, attrs):
        if tag in ("h2", "h3"):
            self.in_heading = True
            self.heading = ""
        elif tag == "tr":
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = ""

    def handle_data(self, data):
        if self.in_heading:
            self.heading += data
        if self.cell is not None:
            self.cell += data

    def handle_endtag(self, tag):
        if tag in ("h2", "h3"):
            self.in_heading = False
            self.section = self.heading.strip()
        elif tag in ("td", "th") and self.cell is not None:
            self.row.append(" ".join(self.cell.split()))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            self.rows.append((self.section, self.row))
            self.row = None


def parse_duration(value: str) -> float:
    units = {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": .001}
    parts = re.findall(r"([\d,.]+)\s*(ms|d|h|m|s)", value)
    if not parts:
        raise ValueError(f"无法解析 Gradle 时间：{value}")
    return sum(float(number.replace(",", "")) * units[unit] for number, unit in parts)


def parse_gradle_profile(profile: Path | None, run: dict | None = None) -> dict:
    if profile and profile.is_file():
        paths = [profile]
        selection = "explicit_file"
    elif profile and profile.is_dir():
        recorded = run.get("profile_reports", []) if run else []
        if not isinstance(recorded, list) or any(not isinstance(path, str) for path in recorded) or len(set(recorded)) != 1:
            return {**UNKNOWN, "reason": "run.json 没有唯一的本次 profile 路径，禁止混入目录中的历史报告"}
        path = profile / Path(recorded[0]).name
        if not path.is_file():
            return {**UNKNOWN, "reason": "本次记录的 profile 文件不在指定目录中", "recorded_relative_path": recorded[0]}
        paths = [path]
        selection = "run_stdout"
    else:
        return {**UNKNOWN, "reason": "没有 Gradle --profile HTML"}
    reports = []
    for path in paths:
        parser = ProfileParser()
        parser.feed(path.read_text(encoding="utf-8"))
        summary, configuration, tasks = {}, [], []
        project = ""
        for section, cells in parser.rows:
            if len(cells) < 2:
                continue
            try:
                seconds = parse_duration(cells[1])
            except ValueError:
                continue
            label = cells[0]
            if section == "Summary":
                summary[label] = seconds
            elif section == "Configuration":
                configuration.append({"project": label, "seconds": seconds})
            elif section == "Task Execution":
                if len(cells) > 2 and cells[2] == "(total)":
                    project = label
                elif label.startswith(":"):
                    task = label
                    if len(cells) > 2:
                        tasks.append({"task": task, "seconds": seconds, "result": cells[2]})
                    else:
                        project = label
                elif len(cells) > 2 and project:
                    task = f"{project.rstrip(':')}:{label}"
                    tasks.append({"task": task, "seconds": seconds, "result": cells[2]})
        reports.append({"file": path.name, "summary_seconds": summary, "configuration": configuration, "tasks": tasks})
    return {"status": "measured" if any(r["tasks"] or r["summary_seconds"] for r in reports) else "unknown", "selection": selection, "reports": reports, "limits": "时间精度以 Gradle HTML 的显示精度为限；并行任务累计时间不能当构建墙钟。"}


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def parse_executor_bootstrap(log: Path) -> dict:
    text = read_text(log)
    if not text:
        return {**UNKNOWN, "reason": "没有 --info 进程事件日志"}
    starts, events = {}, []
    fork_requests = 0
    for line in text.splitlines():
        stamp, _, body = line.partition(" ")
        try:
            timestamp = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        except ValueError:
            continue
        start = re.search(r"Starting process 'Gradle Test Executor (\d+)'", body)
        finish = re.search(r"Gradle Test Executor (\d+) started executing tests", body)
        if start:
            fork_requests += 1
            starts[start[1]] = timestamp
        if finish and finish[1] in starts:
            seconds = (timestamp - starts.pop(finish[1])).total_seconds()
            if seconds >= 0:
                events.append({"executor": int(finish[1]), "seconds": seconds})
    return {"status": "measured" if events else "unknown", "meaning": "fork_and_worker_bootstrap", "fork_requests": fork_requests, "workers_started_tests": len(events), "unmatched_requests": len(starts), "events": events, "limits": "这是 fork 请求到 worker 开始执行测试的时间，包含 worker 引导与调度，不能称为纯 JVM 启动。"}


def parse_gc_logs(metrics: Path) -> dict:
    """只汇总 JDK GC 日志明确标为 Pause 的停顿，不把并发阶段重复相加。"""
    paths = sorted(metrics.glob("gc-*.log"))
    if not paths:
        return {**UNKNOWN, "files": []}
    pauses = []
    recognized = False
    for path in paths:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "Using " in line or "GC(" in line:
                recognized = True
            match = re.search(r"GC\((\d+)\) (Pause.*?) ([\d.]+)(ms|s)\s*$", line)
            if match:
                pauses.append({"file": path.name, "gc_id": int(match[1]), "kind": match[2], "seconds": float(match[3]) / (1000 if match[4] == "ms" else 1)})
    return {"status": "measured" if recognized else "unknown", "files": [path.name for path in paths], "pause_count": len(pauses), "pause_seconds_sum": sum(row["seconds"] for row in pauses), "pause_seconds_max": max((row["seconds"] for row in pauses), default=None), "pauses": pauses, "limits": "仅 JDK 日志记录的 GC 停顿；并发 GC 阶段不加入停顿合计，不能用它推算测试主体时间。"}


def parse_metrics(metrics: Path | None) -> dict:
    if not metrics or not metrics.exists():
        return {"host_metrics": UNKNOWN, "executor_bootstrap": UNKNOWN, "robolectric": UNKNOWN, "run": {**UNKNOWN, "record_present": False}, "gc": UNKNOWN}
    run_path, samples_path = metrics / "run.json", metrics / "metrics.jsonl"
    run = json.loads(run_path.read_text()) if run_path.exists() else {**UNKNOWN, "record_present": False}
    if not isinstance(run, dict):
        raise ValueError("run.json 必须是运行记录对象")
    if run_path.exists():
        run["record_present"] = True
    samples = read_jsonl(samples_path) if samples_path.exists() else []
    cpu = [sample["host"]["cpu_busy_percent"] for sample in samples if sample.get("host", {}).get("cpu_busy_percent") is not None]
    available = [sample["host"]["memory"]["MemAvailable"] for sample in samples if sample.get("host", {}).get("memory", {}).get("MemAvailable") is not None]
    robo_rows = [row for path in sorted(metrics.glob("robolectric-*.jsonl")) for row in read_jsonl(path)]
    initialization = [metric for row in robo_rows if row.get("event") == "robolectric" for metric in row.get("metrics", []) if metric.get("name") == "initialization"]
    report_count = sum(row.get("event") == "robolectric" for row in robo_rows)
    robo = {"status": "measured" if report_count else "unknown", "report_count": report_count, "reporter_created_count": sum(row.get("event") == "reporter-created" for row in robo_rows), "initialization": {"status": "measured", "elapsed_ns_sum": sum(metric["elapsed_ns"] for metric in initialization), "count": sum(metric.get("count", 1) for metric in initialization)} if initialization else UNKNOWN, "metrics_by_name": {}, "gc": UNKNOWN, "limits": "指标可能嵌套，不可将各指标相加；公开 reporter 没有测试身份，不能与 XML 方法伪关联；initialization 是 Robolectric 初始化范围，测试主体和其它准备成本未知。"}
    for row in robo_rows:
        for metric in row.get("metrics", []):
            aggregate = robo["metrics_by_name"].setdefault(metric["name"], {"elapsed_ns_sum": 0, "count": 0})
            aggregate["elapsed_ns_sum"] += metric["elapsed_ns"]
            aggregate["count"] += metric.get("count", 1)
    gc_rows = [row for row in robo_rows if row.get("gc_count") is not None and row.get("gc_ms") is not None]
    if gc_rows:
        by_pid = {}
        for row in gc_rows:
            current = by_pid.setdefault(row["pid"], {"gc_count": 0, "gc_ms": 0})
            for key in ("gc_count", "gc_ms"):
                current[key] = max(current[key], row[key])
        robo["gc"] = {"status": "measured", "meaning": "JVM 累计 GC 计数和耗时，每 PID 取最大报告值", "by_pid": by_pid}
    return {"run": run, "host_metrics": {"status": "measured" if samples and any(sample.get("host") for sample in samples) else "unknown", "sample_count": len(samples), "host_cpu_busy_percent_peak": max(cpu) if cpu else None, "host_cpu_busy_percent_mean": sum(cpu) / len(cpu) if cpu else None, "host_mem_available_bytes_min": min(available) if available else None, "test_process_peaks": run.get("test_process_peaks", []), "limits": "每秒采样可能遗漏短进程和峰值；压力原值保存在 metrics.jsonl；CPU 百分比均值按采样点计算。"}, "executor_bootstrap": parse_executor_bootstrap(metrics / "gradle.log"), "robolectric": robo, "gc": parse_gc_logs(metrics)}


def run_failure_reason(run: dict | None) -> str | None:
    """有运行记录时必须有明确完成和零退出码；缺记录只允许历史身份统计。"""
    if run is None:
        return None
    if not isinstance(run, dict):
        return "运行记录不是对象"
    if run.get("record_present") is False and run.get("status") == "unknown" and set(run) <= {"status", "record_present"}:
        return None
    if run.get("status") != "completed":
        return "运行记录未明确完成"
    if type(run.get("exit_code")) is not int or run["exit_code"] != 0:
        return "运行记录缺少明确的零退出码"
    return None


def compare_manifests(current: dict, baseline: dict) -> dict:
    if baseline.get("status") == "failed":
        raise ValueError("基线已明确失败，不能作为验收依据")
    baseline_run_failure = run_failure_reason(baseline.get("run"))
    if baseline_run_failure:
        raise ValueError(f"基线{baseline_run_failure}，不能作为验收依据")
    old_manifest = baseline.get("test_manifest", {})
    old_rows = old_manifest.get("identities", [])
    new_rows = current["identities"]
    if not old_rows:
        raise ValueError("基线没有完整的测试身份清单")
    old_keys = [(row["classname"], row["name"]) for row in old_rows]
    if len(old_keys) != len(set(old_keys)) or old_manifest.get("duplicates"):
        raise ValueError("基线包含重复测试事件，不能作为验收依据")
    if old_manifest.get("failures") or any(row["status"] in ("failed", "error") for row in old_rows):
        raise ValueError("基线包含失败测试，不能作为验收依据")
    old = {key: row["status"] for key, row in zip(old_keys, old_rows)}
    new = {(row["classname"], row["name"]): row["status"] for row in new_rows}
    missing = sorted(set(old) - set(new))
    added = sorted(set(new) - set(old))
    new_skips = sorted(key for key, status in new.items() if status == "skipped" and old.get(key) != "skipped")
    previous_skips = sorted(key for key, status in old.items() if status == "skipped")
    unexpected_prior_skips = [key for key in previous_skips if key != EXPECTED_FULL_SKIP]
    skip_state_changed = [key for key in previous_skips if new.get(key) != "skipped"]
    passed = not (missing or added or new_skips or unexpected_prior_skips or skip_state_changed or current["duplicates"] or current["failures"])
    return {"status": "passed" if passed else "failed", "missing": missing, "added": added, "new_skips": new_skips, "unexpected_baseline_skips": unexpected_prior_skips, "expected_skip_changed": skip_state_changed}


def build_summary(results: Path, profile: Path | None, metrics: Path | None, compare: Path | None = None) -> tuple[dict, int]:
    parsed = parse_xml_results(results)
    manifest = parsed["manifest"]
    measurements = parse_metrics(metrics)
    result = {"schema_version": SCHEMA_VERSION, "generated_at": utc_now(), "test_manifest": manifest, "xml_summary": parsed["summary"], "rankings": parsed["rankings"], "gradle_profile": parse_gradle_profile(profile, measurements["run"]), **measurements, "unknown": {"pure_jvm_startup_seconds": None, "test_body_seconds": None, "unattributed_overhead_seconds": None}, "limits": ["XML 是 JUnit 类和方法累计时间，不能作为精确 Test task 墙钟。", "first_method_seconds 包含有效测试和准备成本，不能等同 JVM 或 Robolectric 初始化。"]}
    failed = bool(manifest["duplicates"] or manifest["failures"])
    unexpected_skips = [row for row in manifest["skipped"] if (row["classname"], row["name"]) != EXPECTED_FULL_SKIP]
    if unexpected_skips:
        result["unexpected_skips"] = unexpected_skips
        failed = True
    if compare:
        result["comparison"] = compare_manifests(manifest, json.loads(compare.read_text(encoding="utf-8")))
        failed |= result["comparison"]["status"] == "failed"
    run_failure = run_failure_reason(result["run"])
    if run_failure:
        result["run_validation"] = {"status": "failed", "reason": run_failure}
        failed = True
    result["status"] = "failed" if failed else "passed"
    measured_fields = ("gradle_profile", "host_metrics", "executor_bootstrap", "robolectric")
    measured_count = sum(result[key].get("status") == "measured" for key in measured_fields)
    run_confirmed = result["run"].get("record_present") and run_failure is None
    result["measurement_status"] = "unknown" if not run_confirmed else "measured" if measured_count == len(measured_fields) else "partial" if measured_count else "unknown"
    result["validation_scope"] = "status 只判断命令与用例完整性，不代表缺失的性能观测已通过；性能观测状态单列于 measurement_status。"
    return result, 1 if failed else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    run = sub.add_parser("run", help="运行并每秒采集资源")
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("command", nargs=argparse.REMAINDER)
    summary = sub.add_parser("summary", help="汇总 XML、Gradle profile 与采样")
    summary.add_argument("--results", type=Path, required=True)
    summary.add_argument("--profile", type=Path)
    summary.add_argument("--metrics", type=Path)
    summary.add_argument("--output", type=Path, required=True)
    summary.add_argument("--compare", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.operation == "run":
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            return run_command(args.output, command)
        result, status = build_summary(args.results, args.profile, args.metrics, args.compare)
        write_json(args.output, result)
        print(f"性能汇总：{result['status']}，{result['xml_summary']['cases']} 个用例；完整证据见 {args.output}")
        return status
    except (OSError, ValueError, ET.ParseError, KeyError) as error:
        if args.operation == "summary":
            write_json(args.output, {"schema_version": SCHEMA_VERSION, "status": "failed", "error": str(error)})
        print(f"性能采集失败：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
