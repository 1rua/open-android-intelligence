#!/usr/bin/env python3
"""Run an isolated real Core and controllable synthetic native Adapter."""

import argparse
import asyncio
from contextlib import AsyncExitStack
import json
import os
from pathlib import Path
import re
import signal
import sys


TOOLS = Path(__file__).resolve().parent
WORKTREE = TOOLS.parents[3]
BASELINE = TOOLS / "build" / "baseline-733c6a30"

parser = argparse.ArgumentParser()
parser.add_argument("--run-id", required=True, help="Unique name for the retained fixture state")
parser.add_argument("--source", choices=("baseline", "worktree"), default="baseline")
parser.add_argument("--core-port", type=int, default=18855)
parser.add_argument("--adapter-port", type=int, default=18856)
parser.add_argument("--conversation-id", help="Existing fixture conversation when resuming state")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_id):
    parser.error("run-id must contain only letters, digits, underscores or hyphens")
if args.conversation_id and not re.fullmatch(r"[A-Za-z0-9_-]+", args.conversation_id):
    parser.error("invalid conversation-id")
if not (0 < args.core_port < 65536 and 0 < args.adapter_port < 65536):
    parser.error("ports must be between 1 and 65535")
if args.core_port == args.adapter_port:
    parser.error("Core and Adapter ports must differ")

source = BASELINE if args.source == "baseline" else WORKTREE
for directory in reversed((
    source / "oai-core" / "src",
    source / "integrations" / "hermes-session-adapter" / "src",
    source / "oai-core" / "tests",
)):
    sys.path.insert(0, str(directory))
sys.dont_write_bytecode = True

from synthetic_process import SyntheticProcess as CommunicationProcess


def report(**fields):
    print(json.dumps(fields, sort_keys=True), flush=True)


async def main():
    root = TOOLS / "build" / args.run_id
    if root.exists() and any(root.iterdir()) and not args.conversation_id:
        raise SystemExit("Fixture state already exists; supply its conversation ID or choose a new run-id")
    if args.conversation_id and not (root / "adapter").is_dir():
        raise SystemExit("Cannot resume a conversation without its retained Adapter state")
    root.mkdir(parents=True, exist_ok=True)

    commands = asyncio.Queue()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, commands.put_nowait, "stop")
    loop.add_signal_handler(signal.SIGUSR1, commands.put_nowait, "toggle-adapter")

    async with AsyncExitStack() as stack:
        adapter = await CommunicationProcess(
            "adapter", root / "adapter", port=args.adapter_port,
            conversation=args.conversation_id,
        ).start()
        stack.push_async_callback(adapter.close)
        conversation = adapter.ready["conversationId"]
        core = await CommunicationProcess(
            "core", root / "core", host_url=adapter.url, port=args.core_port,
        ).start()
        stack.push_async_callback(core.close)
        report(event="ready", source=args.source, run_id=args.run_id,
               harness_pid=os.getpid(),
               core_port=core.port, adapter_port=adapter.port,
               core_pid=core.process.pid, adapter_pid=adapter.process.pid,
               synthetic_conversation_id=conversation)

        while (command := await commands.get()) != "stop":
            if adapter is not None:
                await adapter.close()
                adapter = None
                report(event="adapter_offline", core_pid=core.process.pid,
                       synthetic_conversation_id=conversation)
            else:
                adapter = await CommunicationProcess(
                    "adapter", root / "adapter", port=args.adapter_port,
                    conversation=conversation,
                ).start()
                stack.push_async_callback(adapter.close)
                report(event="adapter_online", core_pid=core.process.pid,
                       adapter_pid=adapter.process.pid,
                       synthetic_conversation_id=conversation)
    report(event="stopped", run_id=args.run_id)


if __name__ == "__main__":
    asyncio.run(main())
