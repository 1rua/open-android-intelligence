#!/usr/bin/env python3
"""Record one controlled tap on the dedicated emulator without clearing state."""

import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time


TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("draw_collector", TOOLS / "collect-first-draw.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--title", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--apk-sha256", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.label) or not re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256):
        parser.error("invalid label or APK digest")
    if collector.adb("shell", "getprop", "ro.boot.qemu.avd_name") != "oai-local-first-20261103":
        raise SystemExit("Refusing to touch a different device")
    if collector.adb("shell", "getprop", "ro.build.version.sdk") != "35":
        raise SystemExit("Expected the isolated API 35 emulator")
    installed = collector.adb("shell", "pm", "path", "com.openandroidintelligence.mobile")
    if not installed.startswith("package:") or "\n" in installed:
        raise SystemExit("Could not identify exactly one installed APK")
    actual = collector.adb("shell", "sha256sum", installed.removeprefix("package:")).split()[0]
    if actual != args.apk_sha256:
        raise SystemExit(f"Installed APK digest mismatch: {actual}")
    node = collector.session_target(collector.layout(), args.title)
    x, y = collector.center(node)
    output = collector.EVIDENCE / f"terra-{args.label}.mp4"
    if output.exists():
        raise SystemExit("Output already exists; refusing to overwrite it")
    device_file = f"/sdcard/Movies/terra-{args.label}.mp4"
    if subprocess.run([*collector.ADB, "shell", "test", "-e", device_file],
                      env=collector.ENV).returncode == 0:
        raise SystemExit("Device output already exists; refusing to overwrite it")

    process = subprocess.Popen([*collector.ADB, "shell", "screenrecord", "--time-limit", "12",
                                "--bugreport", device_file], env=collector.ENV,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        time.sleep(2)
        if process.poll() is not None:
            raise RuntimeError("screenrecord exited before the tap")
        tap_started = time.monotonic_ns()
        collector.adb("shell", "input", "tap", x, y)
        tap_finished = time.monotonic_ns()
        stdout, stderr = process.communicate(timeout=20)
        if process.returncode != 0:
            raise RuntimeError(f"screenrecord failed: {stderr or stdout}")
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    collector.adb("pull", device_file, str(output))
    print(json.dumps({"event": "recorded", "output": str(output.relative_to(collector.ROOT)),
                      "target": args.title, "tapHostMonotonicStartNs": tap_started,
                      "tapHostMonotonicEndNs": tap_finished, "apkSha256": actual}), flush=True)


if __name__ == "__main__":
    main()
