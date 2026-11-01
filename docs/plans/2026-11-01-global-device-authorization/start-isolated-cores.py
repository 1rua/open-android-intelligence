#!/usr/bin/env python3
"""Start isolated test Cores from one consistent snapshot of the current worktree."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from datetime import datetime, timezone


PLAN = Path(__file__).resolve().parent
ROOT = PLAN.parents[2]
RUNTIME = PLAN / "runtime"


def output(*args: str) -> str:
    return subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=True, timeout=30).stdout.strip()


def write_new(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)


def read_sources() -> dict[str, bytes]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "oai-core/src", "gateway-contract"],
        cwd=ROOT, capture_output=True, check=True, timeout=30,
    )
    files = {}
    for name in sorted(set(os.fsdecode(item) for item in result.stdout.split(b"\0") if item)):
        path = ROOT / name
        if not path.exists():
            continue
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(ROOT):
            raise ValueError(f"unsafe source entry: {name}")
        files[name] = path.read_bytes()
    if not files or "oai-core/src/oai_core/server.py" not in files:
        raise ValueError("Core source is incomplete")
    return files


def main() -> int:
    os.umask(0o077)
    spec = importlib.util.spec_from_file_location("prepare_isolated_cores", PLAN / "prepare-isolated-cores.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify()
    before = read_sources()
    after = read_sources()
    if before != after:
        raise ValueError("Core source changed while snapshotting; no service was started")

    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in before.items()}
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    digest = hashlib.sha256(manifest_bytes).hexdigest()
    launch_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    snapshots = RUNTIME / "source-snapshots"
    snapshots.mkdir(mode=0o700, exist_ok=True)
    snapshot = snapshots / launch_id
    snapshot.mkdir(mode=0o700)
    for name, data in before.items():
        dest = snapshot / name
        dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        write_new(dest, data)
    metadata = {"gitHead": output("git", "rev-parse", "HEAD"), "sourceDigestSha256": digest,
                "digestFormat": "sha256(sorted compact JSON mapping relative path to file sha256)",
                "files": manifest}
    write_new(snapshot / "source-manifest.json", (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode())

    processes = {}
    for instance, port in module.INSTANCES.items():
        directory = RUNTIME / instance
        config = json.loads((directory / "core.json").read_text())
        config["contractRoot"] = os.path.relpath(snapshot / "gateway-contract", directory)
        config_name = f"launch-{launch_id}.json"
        write_new(directory / config_name, (json.dumps(config, indent=2) + "\n").encode())
        log = directory / f"service-{launch_id}.log"
        env = {"PATH": os.environ.get("PATH", os.defpath), "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
               "HOME": os.path.relpath(directory / "home", ROOT),
               "TMPDIR": os.path.relpath(directory / "tmp", ROOT),
               "OAI_HISTORY_MEDIA_ROOT": os.path.relpath(directory / "history-media", ROOT),
               "PYTHONPATH": os.path.relpath(snapshot / "oai-core/src", ROOT), "PYTHONDONTWRITEBYTECODE": "1"}
        command = [sys.executable, "-m", "oai_core.cli", "serve", "--config", os.path.relpath(directory / config_name, ROOT)]
        with log.open("xb") as stream:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                       stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        processes[instance] = {"pid": process.pid, "port": port,
                               "config": os.path.relpath(directory / config_name, ROOT),
                               "log": os.path.relpath(log, ROOT)}
        write_new(snapshot / f"{instance}-process.json", (json.dumps(processes[instance], indent=2) + "\n").encode())
        print(f"STARTED {instance}: pid={process.pid} port={port}", flush=True)
    write_new(snapshot / "processes.json", (json.dumps(processes, indent=2) + "\n").encode())
    print(f"SOURCE sha256={digest} files={len(manifest)}", flush=True)
    print(f"SNAPSHOT {os.path.relpath(snapshot, ROOT)}", flush=True)
    time.sleep(1)
    for instance, details in processes.items():
        try:
            os.kill(details["pid"], 0)
        except ProcessLookupError as error:
            raise RuntimeError(f"{instance} exited; inspect {details['log']}") from error
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        raise SystemExit(1)
