#!/usr/bin/env python3
"""Collect draw evidence on the dedicated AVD without clearing app data."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time


ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / "docs/reviews/evidence/2026-11-03-android-local-first-sync"
TOOLS = Path(__file__).resolve().parent
SERIAL = "127.0.0.1:5563"
ADB = ["adb", "-P", "5038", "-s", SERIAL]
ENV = {**os.environ, "ANDROID_ADB_SERVER_PORT": "5038", "ADB_SERVER_PORT": "5038"}
WANTED = {"timeline.opened", "timeline.local_read", "timeline.first_draw", "timeline.remote_synced"}


def run(*command, timeout=30):
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout,
                          check=True, env=ENV).stdout.strip()


def adb(*arguments, timeout=30):
    return run(*ADB, *arguments, timeout=timeout)


def layout():
    for _ in range(3):
        try:
            result = run("android", "layout", "--device", SERIAL, "--flat", "--pretty", "--no-idle", timeout=20)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            time.sleep(1)
            continue
        try:
            value = json.loads(result)
            if isinstance(value, list):
                return value
        except ValueError:
            pass
        time.sleep(1)
    raise RuntimeError("Android CLI did not return a valid layout")


def center(node):
    values = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not values:
        raise RuntimeError("target has no visible bounds")
    x1, y1, x2, y2 = map(int, values.groups())
    if x1 >= x2 or y1 >= y2:
        raise RuntimeError("target has empty bounds")
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def target(nodes, *, text=None, description=None, classname=None):
    matches = [node for node in nodes if not node.get("off-screen")
               and (text is None or node.get("text") == text)
               and (description is None or node.get("content-desc") == description)
               and (classname is None or node.get("class") == classname)]
    if len(matches) != 1:
        raise RuntimeError(f"expected one visible target, found {len(matches)}: {text or description or classname}")
    return matches[0]


def tap(node):
    adb("shell", "input", "tap", *center(node))


def session_target(nodes, title):
    target(nodes, description="管理会话 " + title)
    return target(nodes, text=title, classname="android.widget.TextView")


def wait_for_target(timeout=30, **criteria):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return target(layout(), **criteria)
        except RuntimeError:
            time.sleep(0.5)
    raise RuntimeError(f"target did not appear before timeout: {criteria}")


def select(title):
    tap(wait_for_target(description="打开会话抽屉"))
    nodes = layout()
    search = target(nodes, classname="android.widget.EditText")
    tap(search)
    nodes = layout()
    if "FOCUSED" not in target(nodes, classname="android.widget.EditText").get("state", []):
        raise RuntimeError("search did not gain focus")
    clear = [node for node in nodes if node.get("content-desc") == "清除搜索"]
    if clear:
        tap(clear[0])
        tap(target(layout(), classname="android.widget.EditText"))
        if "FOCUSED" not in target(layout(), classname="android.widget.EditText").get("state", []):
            raise RuntimeError("search lost focus after clearing")
    adb("shell", "input", "text", title.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    nodes = layout()
    if target(nodes, classname="android.widget.EditText").get("text") != title:
        raise RuntimeError("search text does not match requested fixture")
    tap(session_target(nodes, title))


def wait_for_text(text, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(node.get("text") == text for node in layout()):
            return
        time.sleep(0.5)
    raise RuntimeError(f"synthetic message not visible: {text}")


def collect_log(stream, destination):
    for line in stream:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if isinstance(record, dict) and record.get("type") in WANTED:
            destination.write(json.dumps(record, ensure_ascii=False) + "\n")
            destination.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("memory", "local", "remote"), required=True)
    parser.add_argument("--group", choices=("baseline", "candidate"), required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--count", type=int, default=20)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.label) or not re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256):
        parser.error("invalid label or APK digest")
    if not 1 <= args.count <= 23:
        parser.error("count must be between 1 and 23")
    if args.group == "baseline" and args.mode == "memory":
        parser.error("the unmodified baseline has no per-conversation memory snapshot path")
    if adb("shell", "getprop", "ro.boot.qemu.avd_name") != "oai-local-first-20261103":
        raise SystemExit("Refusing to touch a different device")
    if adb("shell", "getprop", "ro.build.version.sdk") != "35":
        raise SystemExit("Expected the isolated API 35 emulator")
    installed = adb("shell", "pm", "path", "com.openandroidintelligence.mobile")
    if not installed.startswith("package:") or "\n" in installed:
        raise SystemExit("Could not identify exactly one installed APK")
    actual = adb("shell", "sha256sum", installed.removeprefix("package:")).split()[0]
    if actual != args.apk_sha256:
        raise SystemExit(f"Installed APK digest mismatch: {actual}")

    journal = TOOLS / "build/synthetic-core-agent/adapter/history-observations.jsonl"
    fixture_path = TOOLS / "build/synthetic-core-agent/adapter/fixture-history.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    remote_ids = {rows[0]["text"].removeprefix("synthetic-history-"): identity
                  for identity, rows in fixture.items() if len(rows) == 1}
    output = EVIDENCE / f"terra-{args.label}-first-draw.jsonl"
    with output.open("x", encoding="utf-8") as destination:
        process = subprocess.Popen([*ADB, "logcat", "-v", "raw", "-T", "1", "-s", "OaiE2E:D", "*:S"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=ENV)
        reader = threading.Thread(target=collect_log, args=(process.stdout, destination), daemon=True)
        reader.start()
        try:
            if args.mode == "memory":
                select("native-alice")
                wait_for_text("baseline-synthetic-message-001")
            for index in range(args.count):
                if args.mode == "local":
                    adb("shell", "am", "force-stop", "com.openandroidintelligence.mobile")
                    adb("shell", "am", "start", "-W", "-n", "com.openandroidintelligence.mobile/.MainActivityClassic")
                if args.mode == "remote":
                    title = f"remote-{args.group}-{index:02d}"
                    identity = remote_ids.get(title)
                    if identity is None:
                        raise RuntimeError(f"missing remote fixture: {title}")
                    observed = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines()] if journal.exists() else []
                    if any(row.get("conversationId") == identity for row in observed):
                        raise RuntimeError(f"remote fixture already read: {title}")
                    expected = "synthetic-history-" + title
                else:
                    title = "Synthetic second" if index % 2 == 0 or args.mode == "local" else "native-alice"
                    expected = "baseline-draft-retain-002" if title == "Synthetic second" else "baseline-synthetic-message-001"
                select(title)
                wait_for_text(expected)
                if args.mode == "remote":
                    observed = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines()]
                    if not any(row.get("conversationId") == identity and row.get("count") == 1 for row in observed):
                        raise RuntimeError(f"remote history was not observed: {title}")
                print(json.dumps({"sample": index + 1, "mode": args.mode, "title": title,
                                  "visible": True}), flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=5)
    print(json.dumps({"event": "collection_finished", "output": str(output.relative_to(ROOT))}), flush=True)


if __name__ == "__main__":
    main()
