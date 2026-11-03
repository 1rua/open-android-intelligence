#!/usr/bin/env python3
"""Seed never-opened, nonempty synthetic Native history for draw measurements."""

import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import re
import socket
import sys


TOOLS = Path(__file__).resolve().parent
BASELINE = TOOLS / "build" / "baseline-733c6a30"
parser = argparse.ArgumentParser()
parser.add_argument("--run-id", required=True)
parser.add_argument("--adapter-port", type=int, default=18856)
parser.add_argument("--count", type=int, default=24)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_id):
    parser.error("invalid run-id")
if not 0 < args.adapter_port < 65536 or not 20 <= args.count <= 100:
    parser.error("invalid port or sample count")

root = TOOLS / "build" / args.run_id / "adapter"
if not (root / "host-master").is_file() or not (root / "host-data").is_dir():
    raise SystemExit("Existing fixture state is required; refusing to create a new key")
with socket.socket() as probe:
    probe.settimeout(1)
    if probe.connect_ex(("127.0.0.1", args.adapter_port)) == 0:
        raise SystemExit("Adapter is listening; stop it before modifying fixture state")

history_path = root / "fixture-history.json"
if history_path.exists():
    raise SystemExit("History fixture already exists; refusing to overwrite it")
sys.dont_write_bytecode = True
sys.path.insert(0, str(BASELINE / "oai-core" / "src"))
from oai_core.server import create_core

os.chdir(root)
core = create_core({"storageRoot": "host-data", "masterKeyFile": "host-master",
                    "contractRoot": os.path.relpath(BASELINE / "gateway-contract", root)})
owner = "native-alice"
if not core.account_exists(owner):
    raise SystemExit("Synthetic account is missing; refusing to create one")

history = {}
groups = {}
with closing(core.open_gateway_account(owner)) as account:
    for group in ("baseline", "candidate"):
        groups[group] = []
        for index in range(args.count):
            marker = f"remote-{group}-{index:02d}"
            conversation = account.conversations.create(marker, marker, "local-first-seed")["conversationId"]
            account.agent_sessions.record(conversation, "conversation-create")
            binding = account.agent_sessions.lookup(conversation)
            native_id = "native-" + marker
            if not binding["agentSessionId"]:
                account.agent_sessions.attach(conversation, native_id, "key-" + marker)
                binding = account.agent_sessions.lookup(conversation)
            if binding["agentSessionId"] != native_id:
                raise SystemExit("Existing session binding differs; refusing to replace it")
            history[conversation] = [
                {"messageId": "msg-" + marker, "sender": "assistant",
                 "text": "synthetic-history-" + marker,
                 "parts": [{"type": "text", "text": "synthetic-history-" + marker}],
                 "createdAt": "2026-11-03T00:00:00.000Z", "state": "completed"}
            ]
            groups[group].append({"conversationId": conversation, "title": marker})
with history_path.open("x", encoding="utf-8") as stream:
    json.dump(history, stream, ensure_ascii=False, sort_keys=True)
print(json.dumps({"event": "remote_sessions_ready", "run_id": args.run_id,
                  "countPerGroup": args.count, "groups": groups}, sort_keys=True), flush=True)
