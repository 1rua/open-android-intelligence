#!/usr/bin/env python3
"""Add one idempotent session to an existing, stopped synthetic Adapter."""

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
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_id):
    parser.error("invalid run-id")
if not 0 < args.adapter_port < 65536:
    parser.error("invalid Adapter port")

root = TOOLS / "build" / args.run_id / "adapter"
if not (root / "host-master").is_file() or not (root / "host-data").is_dir():
    raise SystemExit("Existing fixture state is required; refusing to create a new key")
with socket.socket() as probe:
    probe.settimeout(1)
    if probe.connect_ex(("127.0.0.1", args.adapter_port)) == 0:
        raise SystemExit("Adapter is listening; stop it before modifying fixture state")

sys.dont_write_bytecode = True
sys.path.insert(0, str(BASELINE / "oai-core" / "src"))
from oai_core.server import create_core

os.chdir(root)
core = create_core({"storageRoot": "host-data", "masterKeyFile": "host-master",
                    "contractRoot": os.path.relpath(BASELINE / "gateway-contract", root)})
owner = "native-alice"
if not core.account_exists(owner):
    raise SystemExit("Synthetic account is missing; refusing to create one")
with closing(core.open_gateway_account(owner)) as account:
    conversation = account.conversations.create(
        "local-first-second", "Synthetic second", "local-first-seed"
    )["conversationId"]
    account.agent_sessions.record(conversation, "conversation-create")
    binding = account.agent_sessions.lookup(conversation)
    if not binding["agentSessionId"]:
        account.agent_sessions.attach(conversation, "native-session-second", "native-key-second-test-only")
        binding = account.agent_sessions.lookup(conversation)
    if binding["agentSessionId"] != "native-session-second":
        raise SystemExit("Existing session binding differs; refusing to replace it")
    print(json.dumps({"event": "second_session_ready", "run_id": args.run_id,
                      "conversation_id": conversation, "title": "Synthetic second"}), flush=True)
