#!/usr/bin/env python3
"""Android 视觉留痕端到端测试单指令 CLI 入口。"""

from __future__ import annotations

import argparse
import os
import sys
import time
import traceback
from pathlib import Path
from typing import Sequence

# 保证以 `python3 tools/e2e/run.py` 方式直接执行时能解析 `tools.e2e.*` 包
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.e2e.core import (
    MAX_REPORT_BYTES,
    AdbAndroidDriver,
    AuthEnvironmentManager,
    E2ERuntimeContext,
    EvidenceRecorder,
    IsolatedAuthEndpoint,
    SmartWait,
    resolve_repo_root,
    write_html_report,
)
from tools.e2e.scenarios import SCENARIOS


def _parse_scenarios(raw_items: Sequence[str]) -> list[str]:
    """解析逗号分隔或多参数传入的场景名称并校验合法性。"""
    names: list[str] = []
    for item in raw_items:
        for part in item.split(","):
            name = part.strip()
            if name and name not in names:
                names.append(name)
    if not names:
        raise ValueError("必须至少指定一个 --scenario 场景名称。")
    unknown = [name for name in names if name not in SCENARIOS]
    if unknown:
        available = ", ".join(sorted(SCENARIOS))
        raise ValueError(f"未知 E2E 场景: {', '.join(unknown)}；可选场景: {available}")
    return names


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="运行 Android 视觉留痕 E2E 测试场景并输出单行摘要与 report.html。",
    )
    parser.add_argument(
        "--scenario",
        nargs="+",
        required=True,
        help=f"要执行的场景名（可选: {', '.join(sorted(SCENARIOS))}）",
    )
    parser.add_argument(
        "--device",
        default=None,
        help="目标 Android 设备序列号（默认自动选择唯一在线设备）",
    )
    parser.add_argument(
        "--auth-mode",
        choices=("auto", "snapshot", "local-sim"),
        default="auto",
        help="免密与环境隔离策略（默认 auto）",
    )
    parser.add_argument(
        "--avd",
        default=None,
        help="AVD 名称（用于 snapshot 模式回放）",
    )
    parser.add_argument(
        "--snapshot",
        default="oai_authenticated",
        help="AVD 黄金快照名称（默认 oai_authenticated）",
    )
    parser.add_argument(
        "--report-dir",
        default="build/reports/e2e",
        help="图文报告输出目录（默认相对仓库根目录 build/reports/e2e）",
    )
    parser.add_argument(
        "--expect-reply",
        default="E2E_PONG",
        help="本地仿真或验证期望的助手回复关键字（默认 E2E_PONG）",
    )
    parser.add_argument(
        "--clear-app-state",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="启动前是否清理应用状态（默认 local-sim 清理、snapshot 保留）",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="输出详细执行日志与异常堆栈（默认关闭以保持单行输出）",
    )
    return parser


def _format_rel_path(path: Path, repo_root: Path) -> str:
    """将路径格式化为相对仓库根目录的便携路径。"""
    try:
        return Path(os.path.relpath(path, repo_root)).as_posix()
    except ValueError:
        return path.as_posix()


def _run_single_scenario(
    scenario_name: str,
    args: argparse.Namespace,
    repo_root: Path,
    report_dir: Path,
) -> int:
    """执行单个 E2E 场景，确保无论成败都在 finally 中写报告并释放隔离环境。"""
    scenario_module = SCENARIOS[scenario_name]
    driver = AdbAndroidDriver(serial=args.device, repo_root=repo_root)
    wait = SmartWait(driver)
    evidence = EvidenceRecorder(driver=driver)
    auth_mgr = AuthEnvironmentManager(
        mode=args.auth_mode,
        driver=driver,
        repo_root=repo_root,
        snapshot_name=args.snapshot,
        avd_name=args.avd,
    )

    started = time.monotonic()
    auth: IsolatedAuthEndpoint | None = None
    passed = False
    failure_exc: Exception | None = None
    report_path: Path | None = None

    try:
        if args.verbose:
            print(f"[E2E] 启动隔离认证环境 (mode={args.auth_mode}) ...", file=sys.stderr)
        auth = auth_mgr.start()
        driver.ensure_device_connected()

        clear_app_state = (
            bool(args.clear_app_state)
            if args.clear_app_state is not None
            else (auth.mode != "snapshot")
        )
        ctx = E2ERuntimeContext(
            driver=driver,
            wait=wait,
            evidence=evidence,
            auth=auth,
            repo_root=repo_root,
            report_dir=report_dir,
            expect_reply=args.expect_reply,
            clear_app_state=clear_app_state,
            verbose=args.verbose,
        )
        if args.verbose:
            print(
                f"[E2E] 开始执行场景 {scenario_name} (auth_mode={auth.mode}, clear={clear_app_state}) ...",
                file=sys.stderr,
            )
        scenario_module.run(ctx)
        passed = True
    except Exception as exc:
        failure_exc = exc
        evidence.capture_failure_step(f"场景 {scenario_name} 执行失败", exc)
        if args.verbose:
            traceback.print_exc(file=sys.stderr)
    finally:
        duration_sec = time.monotonic() - started
        metadata = {
            "scenario": scenario_name,
            "auth_mode": auth.mode if auth is not None else args.auth_mode,
            "origin": auth.origin if auth is not None else "",
            "host_name": auth.host_name if auth is not None else "",
            "snapshot": args.snapshot,
            "device": getattr(driver, "serial", None) or args.device or "auto",
            "expect_reply": args.expect_reply,
            "duration_seconds": round(duration_sec, 3),
            "step_count": len(evidence.steps),
            "max_report_bytes": MAX_REPORT_BYTES,
        }
        try:
            report_path = write_html_report(
                report_dir,
                scenario_name,
                passed,
                evidence.steps,
                metadata=metadata,
            )
        except Exception as report_exc:
            if failure_exc is None:
                failure_exc = report_exc
                passed = False
            if args.verbose:
                traceback.print_exc(file=sys.stderr)
        finally:
            try:
                auth_mgr.close()
            except Exception:
                if args.verbose:
                    traceback.print_exc(file=sys.stderr)

    duration_sec = time.monotonic() - started
    steps_count = len(evidence.steps)
    if report_path is not None and report_path.is_file():
        size_mb = report_path.stat().st_size / (1024 * 1024)
        rel_report = _format_rel_path(report_path, repo_root)
    else:
        size_mb = 0.0
        rel_report = _format_rel_path(report_dir / "report.html", repo_root)

    if passed:
        print(
            f"PASS | scenario={scenario_name} | steps={steps_count} | "
            f"duration={duration_sec:.2f}s | report_size={size_mb:.2f}MB | report={rel_report}"
        )
        return 0

    err_summary = " ".join(str(failure_exc or "未知异常").splitlines())[:160]
    print(
        f"FAIL | scenario={scenario_name} | steps={steps_count} | "
        f"duration={duration_sec:.2f}s | report_size={size_mb:.2f}MB | "
        f"report={rel_report} | error={err_summary}"
    )
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    """CLI 主入口，执行指定场景并返回进程退出码。"""
    parser = _build_parser()
    args = parser.parse_args(argv)
    scenario_names = _parse_scenarios(args.scenario)

    repo_root = resolve_repo_root()
    raw_report_dir = Path(args.report_dir)
    base_report_dir = (
        raw_report_dir if raw_report_dir.is_absolute() else (repo_root / raw_report_dir)
    )

    overall_exit = 0
    for scenario_name in scenario_names:
        target_dir = (
            base_report_dir
            if len(scenario_names) == 1
            else (base_report_dir / scenario_name)
        )
        rc = _run_single_scenario(scenario_name, args, repo_root, target_dir)
        if rc != 0:
            overall_exit = rc
    return overall_exit


if __name__ == "__main__":
    sys.exit(main())
