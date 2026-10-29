"""The ``oai`` entry point: a daemon command and thin authenticated clients."""

from __future__ import annotations

import argparse
import getpass
import json
import math
from pathlib import Path
import sys
import time

from .client import CoreClient, CoreClientError, default_client_config, save_client_config


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="oai")
    parser.add_argument("--client-config", type=Path, default=default_client_config())
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve", help="Run the single OAI Core listener")
    serve.add_argument("--config", type=Path, required=True)
    account = commands.add_parser("account", help="Manage Core accounts locally on the server")
    accounts = account.add_subparsers(dest="account_command", required=True)
    account_create = accounts.add_parser("create")
    account_create.add_argument("--config", type=Path, required=True)
    account_create.add_argument("--username", required=True)
    policy = commands.add_parser("policy", help="Retired device policy commands; manage capabilities on Android")
    policies = policy.add_subparsers(dest="policy_command", required=True)
    policy_set = policies.add_parser("set")
    policy_set.add_argument("--config", type=Path, required=True)
    policy_set.add_argument("--account", required=True)
    policy_set.add_argument("--capability", required=True)
    switch = policy_set.add_mutually_exclusive_group(required=True)
    switch.add_argument("--enabled", action="store_true", dest="enabled")
    switch.add_argument("--disabled", action="store_false", dest="enabled")
    login = commands.add_parser("login", help="Authenticate and select your paired Android device")
    login.add_argument("--url", default="http://127.0.0.1:8045")
    login.add_argument("--username", required=True)
    login.add_argument("--device", required=True)
    login.add_argument("--allow-http", action="store_true", help="Explicitly permit unencrypted remote HTTP")
    commands.add_parser("logout", help="Revoke the current Core credential and remove it locally")
    commands.add_parser("devices", help="List your account's paired Android devices")
    commands.add_parser("capabilities", help="Read schemas and device authorization")
    invoke = commands.add_parser("invoke", help="Submit an operation through Core")
    invoke.add_argument("capability_id")
    invoke.add_argument("--version", required=True)
    invoke.add_argument("--parameters", default="{}", help="JSON object, or @relative/file.json")
    invoke.add_argument("--idempotency-key")
    invoke.add_argument("--wait", type=float, default=0, metavar="SECONDS",
                        help="Poll Core up to this many seconds; use cancel to stop the request")
    for command in ("status", "cancel", "ack"):
        operation = commands.add_parser(command)
        operation.add_argument("request_id")
    commands.add_parser("mcp", help="Run the official MCP stdio server using your scoped Core credential")
    skill = commands.add_parser("skill", help="Manage and deploy OAI skills for AI agents")
    skills = skill.add_subparsers(dest="skill_command", required=True)
    skill_install = skills.add_parser("install", help="Install oai-client skill to agent directories")
    skill_install.add_argument("--dest", action="append", type=Path, help="Explicit destination directory")
    return parser


def _parameters(value: str) -> dict:
    if value.startswith("@"):
        value = Path(value[1:]).read_text(encoding="utf-8")
    result = json.loads(value)
    if not isinstance(result, dict):
        raise CoreClientError("PARAMETERS_INVALID")
    return result


def _wait(client: CoreClient, request: dict, seconds: float) -> dict:
    deadline = time.monotonic() + seconds
    terminal = {"succeeded", "failed", "denied", "cancelled", "expired", "outcome_unknown"}
    while request.get("state") not in terminal:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            # Stopping a local wait does not change the Core-owned operation.
            return {**request, "clientWaitExpired": True}
        time.sleep(min(0.25, remaining))
        request = client.status(request["requestId"])
    return request


def _local_admin(args: argparse.Namespace) -> dict:
    """Local operator commands never enter the remote invocation surface."""
    from .gateway.core import GatewayError
    from .server import create_core, load_config

    if args.command == "policy":
        raise CoreClientError("DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID")
    try:
        core = create_core(load_config(args.config))
        if args.command == "account":
            if core.account_exists(args.username):
                raise CoreClientError("ACCOUNT_EXISTS")
            password = getpass.getpass("New OAI password: ")
            if not password or password != getpass.getpass("Confirm OAI password: "):
                raise CoreClientError("PASSWORD_CONFIRMATION_FAILED")
            account = core.open_gateway_account(args.username)
            try:
                account.credentials.create_password(password)
            finally:
                account.close()
            return {"accountId": args.username, "created": True}

    except GatewayError as error:
        raise CoreClientError(error.code) from error


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "serve":
            from .server import run
            run(args.config)
            return 0
        if args.command == "skill":
            from .skills_installer import install_skills_to_hosts
            installed = install_skills_to_hosts(target_dirs=args.dest)
            print(json.dumps({"skill": "oai-client", "installed": [str(p) for p in installed]}, ensure_ascii=False))
            return 0
        if args.command in {"account", "policy"}:
            print(json.dumps(_local_admin(args), ensure_ascii=False))
            return 0
        if args.command == "login":
            client = CoreClient(args.url, allow_http=args.allow_http)
            result = client.login(args.username, getpass.getpass("OAI password: "), args.device)
            if not isinstance(result.get("token"), str) or not result["token"]:
                raise CoreClientError("CORE_RESPONSE_INVALID")
            save_client_config(args.client_config, {
                "url": client.url, "token": result["token"], "allowHttp": args.allow_http,
                "expiresAt": result.get("expiresAt"),
            })
            print(json.dumps({"authenticated": True, "expiresAt": result.get("expiresAt")}))
            return 0
        client = CoreClient.from_config(args.client_config)
        if args.command == "logout":
            client.logout()
            args.client_config.unlink(missing_ok=True)
            print(json.dumps({"authenticated": False}))
            return 0
        if args.command == "mcp":
            from .mcp import run
            run(client)
            return 0
        if args.command == "devices":
            result = client.devices()
        elif args.command == "capabilities":
            result = client.capabilities()
        elif args.command == "invoke":
            if args.wait < 0 or not math.isfinite(args.wait):
                raise CoreClientError("WAIT_INVALID")
            result = client.invoke(args.capability_id, args.version, _parameters(args.parameters), args.idempotency_key)
            if args.wait:
                result = _wait(client, result, args.wait)
        elif args.command == "status":
            result = client.status(args.request_id)
        elif args.command == "cancel":
            result = client.cancel(args.request_id)
        else:
            result = client.acknowledge(args.request_id)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (CoreClientError, OSError, ValueError) as error:
        code = error.code if isinstance(error, CoreClientError) else "CLIENT_INPUT_INVALID"
        print(json.dumps({"error": {"code": code}}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
