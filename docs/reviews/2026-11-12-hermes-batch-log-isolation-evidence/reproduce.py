"""在真实 HTTP/SSE 用例结束前启动第二个夹具，复现旧固定日志被截断的时序。"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import select
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--expected-exit", type=int, choices=(0, 1), required=True)
    args = parser.parse_args()
    root = args.repository_root.resolve()
    env = dict(os.environ)
    env["OAI_REQUIRE_NATIVE_HERMES"] = "1"
    for name in ("HERMES_PLUGIN_ROOT", "HERMES_SDK_ROOT", "OPEN_ANDROID_GATEWAY_CONTRACT_ROOT"):
        source = Path(env[name])
        env[name] = str(source.resolve() if source.is_absolute() else (root / source).resolve())
    fixture = Path(env["HERMES_PLUGIN_ROOT"]) / "tests/android_gateway_fixture.py"
    directory = root / "tmp/conversation-bugfix"
    directory.mkdir(parents=True, exist_ok=True)
    evidence = root / "tmp/hermes-log-isolation-probe"
    evidence.mkdir(parents=True, exist_ok=True)
    init = evidence / "rerun.init.gradle"
    init.write_text('''allprojects {
    tasks.withType(Test).configureEach {
        if (name == 'testDebugUnitTest') {
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}
''')
    libc = ctypes.CDLL(None)
    fd = libc.inotify_init1(os.O_NONBLOCK)
    if fd < 0 or libc.inotify_add_watch(fd, os.fsencode(directory), 0x2 | 0x100) < 0:
        raise RuntimeError("无法观察真实夹具日志目录")
    started = time.monotonic()
    started_ns = time.time_ns()
    competitor = None
    triggered = False
    try:
        with (evidence / "gradle.log").open("w") as output:
            build = subprocess.Popen([
                "./gradlew", "-I", str(init), ":conversation-data:testDebugUnitTest", "--tests",
                "com.openandroidintelligence.conversation.data.HermesHttpInteropTest.batchAcceptanceDispatchReplyAndReplayUseTheRealHttpAndSseBoundary",
            ], cwd=root / "apps/android", env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                while build.poll() is None and time.monotonic() - started < 90:
                    readable, _, _ = select.select([fd], [], [], 0.1)
                    if not readable:
                        continue
                    os.read(fd, 65536)
                    for log in directory.glob("hermes-batch-interop*.txt"):
                        if triggered or log.stat().st_mtime_ns < started_ns:
                            continue
                        if '"clientMessageId": "cm_after_batch"' not in log.read_text():
                            continue
                        # 与旧版第二个测试进程的 redirectError 相同：打开并截断旧共享路径。
                        # 修复后，首实例继续读自己的唯一日志，不受这个真实夹具启动影响。
                        with (directory / "hermes-batch-interop.txt").open("wb") as competitor_log:
                            competitor = subprocess.Popen([
                                "python3", str(fixture), "--echo-agent", "--contract-root",
                                env["OPEN_ANDROID_GATEWAY_CONTRACT_ROOT"],
                            ], cwd=fixture.parents[1], env=env, stdout=subprocess.PIPE, stderr=competitor_log)
                        triggered = True
                        print(json.dumps({"检查": "第二个真实夹具在第二次宿主执行后启动", "原实例日志": log.name}, ensure_ascii=False), flush=True)
                result = build.wait(timeout=10)
                if not triggered:
                    raise AssertionError("没有触发第二个真实夹具，不能作为并发红绿证据")
                # 第二个进程须运行到真实 HTTP 监听点，不能用只截断文件的假进程替代。
                ready, _, _ = select.select([competitor.stdout], [], [], 15)
                if not ready:
                    raise AssertionError("第二个真实夹具未在期限内启动")
                base_url = competitor.stdout.readline().decode().strip()
                if not base_url.startswith("http://127.0.0.1:"):
                    raise AssertionError("第二个真实夹具未提供本机 HTTP 入口")
                print(json.dumps({"检查": "第二个真实夹具已就绪", "原测试退出码": result,
                                  "预期退出码": args.expected_exit}, ensure_ascii=False), flush=True)
                if result != args.expected_exit:
                    raise AssertionError("原测试退出码与本轮红绿预期不符")
                if result == 1 and "批次重放不能再次执行宿主回合 expected:<2> but was:<0>" not in (evidence / "gradle.log").read_text():
                    raise AssertionError("红测没有复现原始宿主计数症状")
            finally:
                if build.poll() is None:
                    build.terminate()
                    build.wait(timeout=10)
    finally:
        if competitor is not None and competitor.poll() is None:
            competitor.terminate()
            competitor.wait(timeout=10)
        os.close(fd)


if __name__ == "__main__":
    main()
