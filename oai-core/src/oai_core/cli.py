"""The ``oai`` entry point: a daemon command and thin authenticated clients."""

from __future__ import annotations

import argparse
import getpass
import json
import math
from pathlib import Path
import sys
import time
import uuid
import warnings

from .client import (CoreClient, CoreClientError, _credential_bundle, client_config_lock,
                     default_client_config, load_client_config, save_client_config)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="oai")
    parser.add_argument("--client-config", type=Path, default=default_client_config())
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve", help="Run the single OAI Core listener")
    serve.add_argument("--config", type=Path)
    account = commands.add_parser("account", help="Manage Core accounts locally on the server")
    accounts = account.add_subparsers(dest="account_command", required=True)
    for action in ("list", "create", "delete", "passwd"):
        operation = accounts.add_parser(action)
        operation.add_argument("--config", type=Path)
        if action != "list":
            operation.add_argument("username", nargs="?")
            # Preserve the old spelling while supporting the positional form.
            operation.add_argument("--username", dest="username_option")
    recovery = commands.add_parser("recover", help="Recover an administrator locally with an independent recovery code")
    recovery.add_argument("username")
    recovery.add_argument("--config", type=Path)
    for action in ("config", "setup"):
        operation = commands.add_parser(action, help="Authenticate as an administrator and manage this Core")
        operation.add_argument("--config", type=Path)
    policy = commands.add_parser("policy", help="Retired device policy commands; manage capabilities on Android")
    policies = policy.add_subparsers(dest="policy_command", required=True)
    policy_set = policies.add_parser("set")
    policy_set.add_argument("--config", type=Path, required=True)
    policy_set.add_argument("--account", required=True)
    policy_set.add_argument("--capability", required=True)
    switch = policy_set.add_mutually_exclusive_group(required=True)
    switch.add_argument("--enabled", action="store_true", dest="enabled")
    switch.add_argument("--disabled", action="store_false", dest="enabled")
    login = commands.add_parser("login", help="Authenticate to OAI Core")
    login.add_argument("--url")
    login.add_argument("--username")
    login.add_argument("--device", help="Use an existing device-bound credential for compatibility")
    login.add_argument("--allow-http", action="store_true", help="Explicitly permit unencrypted remote HTTP")
    commands.add_parser("logout", help="Revoke the current Core credential and exit locally")
    devices = commands.add_parser("devices", help="List your account's paired Android devices")
    default = devices.add_mutually_exclusive_group()
    default.add_argument("--default", dest="default_device")
    default.add_argument("--clear-default", action="store_true")
    capabilities = commands.add_parser("capabilities", help="Read schemas and device authorization")
    capabilities.add_argument("--device")
    invoke = commands.add_parser("invoke", help="Submit an operation through Core")
    invoke.add_argument("capability_id")
    invoke.add_argument("--version", required=True)
    invoke.add_argument("--parameters", default="{}", help="JSON object, or @relative/file.json")
    invoke.add_argument("--idempotency-key")
    invoke.add_argument("--agent-name")
    invoke.add_argument("--purpose")
    invoke.add_argument("--device")
    invoke.add_argument("--wait", type=float, default=0, metavar="SECONDS",
                        help="Poll Core up to this many seconds; use cancel to stop the request")
    for command in ("status", "cancel", "ack"):
        operation = commands.add_parser(command)
        operation.add_argument("request_id")
        if command == "ack":
            operation.add_argument("--result-digest")
    endpoint = commands.add_parser("endpoint", help="Register and consume an authenticated push route")
    endpoints = endpoint.add_subparsers(dest="endpoint_command", required=True)
    registration = endpoints.add_parser("register")
    registration.add_argument("--registration-key", required=True)
    registration.add_argument("--label", required=True)
    registration.add_argument("--ttl-seconds", type=int, default=3600)
    endpoints.add_parser("list")
    for action in ("unregister", "receive", "ack"):
        operation = endpoints.add_parser(action)
        operation.add_argument("endpoint_id")
        if action == "ack":
            operation.add_argument("delivery_id")
            operation.add_argument("event_id")
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
            return {**request, "clientWaitExpired": True}
        time.sleep(min(0.25, remaining))
        request = client.status(request["requestId"])
    return request


def _terminal_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise CoreClientError("TERMINAL_REQUIRED")
    return input(prompt).strip()


def _secret_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise CoreClientError("TERMINAL_REQUIRED")
    # getpass falls back to echoing input when it cannot control the terminal;
    # treating its warning as an error prevents exposing credentials.
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning as error:
            raise CoreClientError("TERMINAL_REQUIRED") from error


def _challenge_answer(error: CoreClientError) -> tuple[str, str, str]:
    details = error.details
    challenge = details.get("challengeId")
    methods = details.get("methods")
    if not isinstance(challenge, str) or not challenge or not isinstance(methods, list):
        raise error
    supported = [method for method in ("totp", "recovery-code") if method in methods]
    if not supported:
        raise error
    if len(supported) == 1:
        method = supported[0]
    else:
        choice = _terminal_input("Verification method (1: authenticator code, 2: recovery code): ")
        if choice not in {"1", "2"}:
            raise CoreClientError("MFA_CANCELLED")
        method = supported[int(choice) - 1]
    secret = _secret_input("OAI verification code: " if method == "totp" else "OAI recovery code: ")
    if not secret:
        raise CoreClientError("MFA_CANCELLED")
    return challenge, method, secret


def _enroll_mfa(client: CoreClient, challenge: str, client_id: str, client_proof: str | None) -> dict:
    # Enrollment secrets and recovery codes must never go to redirected output.
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise CoreClientError("TERMINAL_REQUIRED")
    enrollment = client.begin_mfa_enrollment(challenge)
    enrollment_id = enrollment.get("enrollmentId")
    uri = enrollment.get("provisioningUri")
    secret = enrollment.get("secret")
    if (not isinstance(enrollment_id, str) or not enrollment_id
            or not isinstance(uri, str) or not uri
            or secret is not None and (not isinstance(secret, str) or not secret)):
        raise CoreClientError("CORE_RESPONSE_INVALID")
    print(f"Authenticator setup URI: {uri}")
    if secret:
        print(f"Authenticator secret: {secret}")
    otp = _secret_input("OAI verification code: ")
    if not otp:
        raise CoreClientError("MFA_CANCELLED")
    try:
        result = client.confirm_mfa_enrollment(challenge, enrollment_id, otp)
    finally:
        otp = ""
        secret = ""
        uri = ""
    codes = result.get("recoveryCodes")
    if not isinstance(codes, list) or not codes or any(not isinstance(code, str) or not code for code in codes):
        raise CoreClientError("CORE_RESPONSE_INVALID")
    print("Save these recovery codes in a safe place:")
    for code in codes:
        print(code)
    if _terminal_input("Type saved after saving the recovery codes: ").lower() != "saved":
        raise CoreClientError("MFA_CANCELLED")
    return client.complete_login(challenge, client_id, client_proof=client_proof)


def _saved_config(path: Path) -> dict | None:
    try:
        return load_client_config(path)
    except CoreClientError as error:
        if error.code == "LOGIN_REQUIRED":
            return None
        raise


def _login(args: argparse.Namespace) -> dict:
    # Login, refresh, requests and logout share one lock, so none can overwrite
    # another process's new credentials or bypass a local logout barrier.
    with client_config_lock(args.client_config):
        previous = _saved_config(args.client_config)
        if previous and previous.get("remoteRevocationPending") is True:
            raise CoreClientError("REMOTE_REVOCATION_PENDING", details={"action": "Run oai logout to retry revocation before logging in."})
        url = args.url
        if not url:
            try:
                from .instance import discover_instance
            except ImportError:
                instance = None
            else:
                try:
                    instance = discover_instance()
                except Exception as error:
                    if getattr(error, "code", None) == "INSTANCE_NOT_FOUND":
                        instance = None
                    elif isinstance(getattr(error, "code", None), str):
                        raise CoreClientError(error.code, details=getattr(error, "details", None)) from error
                    else:
                        raise
            url = instance.get("url") if isinstance(instance, dict) else None
        if not url and previous:
            url = previous.get("url")
        if not url:
            url = _terminal_input("OAI Core URL: ")
        username = args.username or _terminal_input("OAI username: ")
        if not username:
            raise CoreClientError("USERNAME_REQUIRED")
        allow_http = args.allow_http or bool(previous and previous.get("url") == url and previous.get("allowHttp") is True)
        client = CoreClient(url, allow_http=allow_http)
        same_user = previous and previous.get("url") == client.url and previous.get("username") == username
        client_id = previous.get("clientId") if same_user else None
        client_proof = previous.get("clientProof") if same_user else None
        # A new random ID binds the first MFA challenge; an existing ID may
        # only be reclaimed together with its proof. Legacy --device login
        # omits both fields for compatibility with older Core versions.
        if args.device:
            client_id, client_proof = None, None
        elif (not isinstance(client_id, str) or not client_id
                or not isinstance(client_proof, str) or not client_proof):
            client_id, client_proof = str(uuid.uuid4()), None
        password = _secret_input("OAI password: ")
        challenge_client = None
        try:
            try:
                result = client.login(username, password, args.device, client_id=client_id, client_proof=client_proof)
            except CoreClientError as error:
                if error.code not in {"MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED"}:
                    raise
                challenge = error.details.get("challengeId")
                challenge_client = error.details.get("clientId") or client_id
                if (not isinstance(challenge, str) or not challenge
                        or not isinstance(challenge_client, str) or not challenge_client
                        or client_id and challenge_client != client_id):
                    raise CoreClientError("CORE_RESPONSE_INVALID") from error
                if error.code == "MFA_ENROLLMENT_REQUIRED":
                    result = _enroll_mfa(client, challenge, challenge_client, client_proof)
                else:
                    _, method, secret = _challenge_answer(error)
                    try:
                        result = client.complete_login(challenge, challenge_client, client_proof=client_proof,
                            otp=secret if method == "totp" else None,
                            recovery_code=secret if method == "recovery-code" else None)
                    finally:
                        secret = ""
        finally:
            password = ""
        bundle = _credential_bundle(result)
        if same_user and client_proof and bundle.get("clientId") == client_id:
            if bundle.get("clientProof", client_proof) != client_proof:
                raise CoreClientError("CORE_RESPONSE_INVALID")
            bundle.setdefault("clientProof", client_proof)
        if bundle.get("refreshCredential") and (not bundle.get("clientProof")
                or client_id is not None and bundle.get("clientId") != client_id
                or challenge_client is not None and bundle.get("clientId") != challenge_client):
            raise CoreClientError("CORE_RESPONSE_INVALID")
        value = {"url": client.url, "allowHttp": allow_http, "username": username, **bundle}
        # Carry an unresolved call only when the server confirmed the very same
        # authenticated client scope. A fresh scope would risk a duplicate.
        if (previous and client_proof and previous.get("accountId")
                and previous.get("accountId") == bundle.get("accountId")
                and bundle.get("clientId") == client_id):
            if isinstance(previous.get("pendingInvocations"), dict):
                value["pendingInvocations"] = previous["pendingInvocations"]
        save_client_config(args.client_config, value)
        return {"authenticated": True, "expiresAt": result.get("expiresAt")}


def _local_admin(args: argparse.Namespace) -> dict:
    """Authenticate locally before entering any management or configuration operation."""
    if args.command == "policy":
        raise CoreClientError("DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID")
    try:
        from .identity import IdentityStore
        from .instance import discover_instance
    except ImportError as error:
        raise CoreClientError("MANAGEMENT_UNAVAILABLE") from error
    try:
        instance = discover_instance(args.config)
        if not isinstance(instance, dict) or not instance.get("configPath") or not instance.get("identityDatabase"):
            raise CoreClientError("INSTANCE_REQUIRED", details={"action": "Select a local instance with --config."})
        # Constructing this provider never creates or rotates a missing master key.
        # It is accessed only if the server's policy actually requires MFA.
        from .gateway.local_keys import LocalMasterKeyStore
        settings = json.loads(Path(instance["configPath"]).read_text(encoding="utf-8"))
        key = settings.get("masterKeyFile") if isinstance(settings, dict) else None
        key_store = LocalMasterKeyStore(Path(instance["configPath"]).parent / key) if isinstance(key, str) and key else None
        store = IdentityStore(Path(instance["identityDatabase"]), key_store=key_store, create=False)
        try:
            admin = _terminal_input("OAI administrator username: ")
            password = _secret_input("OAI administrator password: ")
            try:
                try:
                    handle = store.begin_management(admin, password)
                except Exception as error:
                    if getattr(error, "code", None) != "MFA_REQUIRED":
                        raise
                    challenge = CoreClientError(error.code, details=getattr(error, "details", None))
                    challenge_id, method, secret = _challenge_answer(challenge)
                    try:
                        handle = store.begin_management(admin, password, challenge_id=challenge_id,
                            otp=secret if method == "totp" else None,
                            recovery_code=secret if method == "recovery-code" else None)
                    finally:
                        secret = ""
            finally:
                password = ""
            try:
                if args.command in {"setup", "config"}:
                    from .configuration import run_config, run_setup
                    runner = run_setup if args.command == "setup" else run_config
                    result = runner(instance, store, handle)
                    return result if isinstance(result, dict) else {"completed": True}
                name = getattr(args, "username", None)
                alternative = getattr(args, "username_option", None)
                if name and alternative and name != alternative:
                    raise CoreClientError("USERNAME_CONFLICT")
                name = name or alternative
                if args.account_command == "list":
                    return store.list_accounts(handle)
                if not name:
                    raise CoreClientError("USERNAME_REQUIRED")
                if args.account_command in {"create", "passwd"}:
                    password = _secret_input("New OAI password: ")
                    if not password or password != _secret_input("Confirm OAI password: "):
                        raise CoreClientError("PASSWORD_CONFIRMATION_FAILED")
                    try:
                        if args.account_command == "create":
                            return store.create_account(handle, name, password)
                        if _terminal_input(f"Reset password for {name} and revoke their sessions? Type the username to confirm: ") != name:
                            raise CoreClientError("CONFIRMATION_REQUIRED")
                        result = store.reset_password(handle, name, password)
                        return result if isinstance(result, dict) else {"updated": True}
                    finally:
                        password = ""
                if _terminal_input(f"Delete account {name}? Type the username to confirm: ") != name:
                    raise CoreClientError("CONFIRMATION_REQUIRED")
                result = store.delete_account(handle, name)
                return result if isinstance(result, dict) else {"deleted": True}
            finally:
                store.revoke_management(handle)
        finally:
            store.close()
    except CoreClientError:
        raise
    except Exception as error:
        if isinstance(getattr(error, "code", None), str):
            raise CoreClientError(error.code, details=getattr(error, "details", None)) from error
        raise


def _local_recovery(args: argparse.Namespace) -> dict:
    from .identity import IdentityStore
    from .instance import discover_instance
    from .management import LocalRecovery
    instance = discover_instance(args.config)
    if not instance.get("identityDatabase") or not instance.get("configPath"):
        raise CoreClientError("INSTANCE_REQUIRED")
    store = IdentityStore(Path(instance["identityDatabase"]))
    try:
        code = _secret_input("Independent local administrator recovery code: ")
        password = _secret_input("New OAI password: ")
        if not password or password != _secret_input("Confirm new OAI password: "):
            raise CoreClientError("PASSWORD_CONFIRMATION_FAILED")
        if _terminal_input(f"Recover administrator {args.username} and revoke existing sessions? Type the username: ") != args.username:
            raise CoreClientError("CONFIRMATION_REQUIRED")
        result = LocalRecovery(store).reset_password(args.username, code, password, confirmed=True)
        return {"recovered": True, "accountId": result["accountId"]}
    except Exception as error:
        if isinstance(getattr(error, "code", None), str):
            raise CoreClientError(error.code) from error
        raise
    finally:
        code = password = ""
        store.close()


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "serve":
            from .server import run
            from .instance import discover_instance
            run(args.config or discover_instance()["configPath"])
            return 0
        if args.command == "skill":
            from .skills_installer import install_skills_to_hosts
            installed = install_skills_to_hosts(target_dirs=args.dest)
            print(json.dumps({"skill": "oai-client", "installed": [str(p) for p in installed]}, ensure_ascii=False))
            return 0
        if args.command == "recover":
            print(json.dumps(_local_recovery(args), ensure_ascii=False))
            return 0
        if args.command in {"account", "policy", "config", "setup"}:
            print(json.dumps(_local_admin(args), ensure_ascii=False))
            return 0
        if args.command == "login":
            print(json.dumps(_login(args), ensure_ascii=False))
            return 0
        client = CoreClient.from_config(args.client_config, allow_logged_out=args.command == "logout")
        if args.command == "logout":
            result = client.logout()
            print(json.dumps(result, ensure_ascii=False))
            return 1 if result.get("remoteRevocationPending") else 0
        if args.command == "mcp":
            from .mcp import run
            run(client)
            return 0
        if args.command == "devices":
            if args.default_device is not None or args.clear_default:
                result = client.set_default_device(args.default_device)
            else:
                result = client.devices()
        elif args.command == "capabilities":
            result = client.capabilities(args.device)
        elif args.command == "invoke":
            if args.wait < 0 or not math.isfinite(args.wait):
                raise CoreClientError("WAIT_INVALID")
            result = client.invoke(args.capability_id, args.version, _parameters(args.parameters), args.idempotency_key,
                                   agent_name=args.agent_name, purpose=args.purpose, device_id=args.device)
            if args.wait:
                result = _wait(client, result, args.wait)
        elif args.command == "status":
            result = client.status(args.request_id)
        elif args.command == "cancel":
            result = client.cancel(args.request_id)
        elif args.command == "endpoint":
            if args.endpoint_command == "register":
                result = client.register_endpoint(args.registration_key, args.label, args.ttl_seconds)
            elif args.endpoint_command == "list":
                result = client.endpoints()
            elif args.endpoint_command == "unregister":
                result = client.unregister_endpoint(args.endpoint_id)
            elif args.endpoint_command == "receive":
                result = client.receive_deliveries(args.endpoint_id)
            else:
                result = client.acknowledge_delivery(args.endpoint_id, args.delivery_id, args.event_id)
        else:
            result = client.acknowledge(args.request_id, args.result_digest)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (CoreClientError, OSError, ValueError, EOFError, KeyboardInterrupt) as error:
        failure = error.as_error() if isinstance(error, CoreClientError) else {"code": "CLIENT_INPUT_INVALID"}
        print(json.dumps({"error": failure}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
