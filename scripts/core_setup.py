"""Shared installer configuration with private credentials and preserved state."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import stat


TOKEN_NAMES = {"OAI_DSH_ADAPTER_TOKEN", "OAI_HERMES_SESSION_TOKEN"}


def private_directory(path: Path) -> None:
    if path.is_symlink():
        raise ValueError(f"Private directory must not be a symlink: {path}")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.stat().st_uid != os.geteuid():
        raise ValueError(f"Private directory must belong to the current user: {path}")
    path.chmod(0o700)


def private_read(path: Path) -> str:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
            raise ValueError(f"Credential file requires current ownership and mode 0600: {path}")
        return source.read()


def private_write(path: Path, text: str, *, executable: bool = False) -> None:
    if path.is_symlink():
        raise ValueError(f"Configuration file must not be a symlink: {path}")
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as target:
        target.write(text)
        target.flush()
        os.fsync(target.fileno())
    if executable:
        temporary.chmod(0o700)
    os.replace(temporary, path)


def read_tokens(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    tokens = {}
    for line in private_read(path).splitlines():
        pieces = shlex.split(line)
        if len(pieces) != 1 or "=" not in pieces[0]:
            raise ValueError("Invalid adapter credential file")
        name, value = pieces[0].split("=", 1)
        if name not in TOKEN_NAMES or name in tokens:
            raise ValueError("Invalid adapter credential name")
        if len(value) < 32 or any(character in value for character in "\r\n\0"):
            raise ValueError("Invalid adapter credential value")
        tokens[name] = value
    return tokens


def legacy_start_tokens(path: Path) -> dict[str, str]:
    """Migrate only the old installer's literal exports, never evaluate shell."""
    if not path.exists():
        return {}
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
            raise ValueError("Existing startup script must belong to the current user")
        contents = source.read()
    tokens = {}
    for line in contents.splitlines():
        match = re.fullmatch(r'export (OAI_(?:DSH_ADAPTER|HERMES_SESSION)_TOKEN)="\$\{\1:-([A-Za-z0-9_-]{32,})\}"', line.strip())
        if match:
            if match[1] in tokens:
                raise ValueError("Ambiguous credentials in the existing startup script")
            tokens[match[1]] = match[2]
    return tokens


def write_start_script(root: Path) -> None:
    # The executable contains no credential value. The private parent directory
    # and ownership/mode check protect the sourced environment file.
    private_write(root / "start-core.sh", '''#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"
PYTHON="${DIR}/.venv/bin/python"
OAI="${DIR}/.venv/bin/oai"
ENV_FILE="${DIR}/runtime/secrets/adapter-tokens.env"
if [ -f "${ENV_FILE}" ]; then
    "${PYTHON}" - "${ENV_FILE}" <<'CHECK'
import os
from pathlib import Path
import stat
import sys
path = Path(sys.argv[1])
metadata = path.lstat()
parent = path.parent.lstat()
if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid()
        or metadata.st_mode & 0o077 or not stat.S_ISDIR(parent.st_mode)
        or parent.st_uid != os.geteuid() or parent.st_mode & 0o077):
    raise SystemExit("Adapter credential file requires owner-only access")
CHECK
    set -a
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    set +a
fi
exec "${OAI}" serve --config "${DIR}/core.json"
''', executable=True)


def configure(root: Path, host: str, port: int, username: str, selected: list[str], hermes_account: str | None) -> None:
    if not 1 <= port <= 65535 or not host or any(character in host for character in "\r\n\0"):
        raise ValueError("Invalid listener address or port")
    root = root.resolve()
    config_path = root / "core.json"
    if config_path.is_symlink():
        raise ValueError("Core configuration must not be a symlink")
    previous = config_path.read_text() if config_path.exists() else None
    config = json.loads(previous) if previous is not None else {
        "storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/core-master-key",
    }
    if not isinstance(config, dict):
        raise ValueError("Core configuration must be an object")
    if previous is not None:
        storage = root / config["storageRoot"]
        key = root / config["masterKeyFile"]
        if any(storage.glob("*/gateway.sqlite")) and not key.exists():
            raise ValueError("Existing account data requires its original master key; restore the key before configuring")
    config["listen"] = {**config.get("listen", {}), "host": host, "port": port}
    config["allowInsecureHttp"] = True
    existing_entries = config.get("agents", [])
    if not isinstance(existing_entries, list) or any(not isinstance(entry, dict) for entry in existing_entries):
        raise ValueError("Core agents must be an array of objects")
    existing = {entry["type"]: entry for entry in existing_entries if "type" in entry}
    secret_directory = root / "runtime" / "secrets"
    private_directory(secret_directory)
    token_path = secret_directory / "adapter-tokens.env"
    tokens = read_tokens(token_path)
    legacy_tokens = legacy_start_tokens(root / "start-core.sh")
    # Re-running a wizard never drops previously configured host instances.
    # Removing an integration remains an explicit edit of the agents array.
    agents = list(existing_entries)
    selected_or_existing = list(dict.fromkeys([*selected, *(kind for kind in existing if kind in {"codex", "dsh", "hermes"})]))
    for kind in selected_or_existing:
        if kind == "codex":
            if kind not in existing:
                agents.append({"id": "codex", "name": "Codex", "type": "codex",
                               "command": ["codex", "app-server"], "codexHome": "runtime/codex"})
            continue
        name, port_number, token_name = (
            ("DSH", 8791, "OAI_DSH_ADAPTER_TOKEN") if kind == "dsh"
            else ("Hermes", 8792, "OAI_HERMES_SESSION_TOKEN")
        )
        entry = existing.get(kind, {"id": kind, "name": name, "type": kind,
                                    "url": f"http://127.0.0.1:{port_number}", "tokenEnv": token_name})
        if entry.get("tokenEnv") == token_name:
            token = os.environ.get(token_name) or tokens.get(token_name) or legacy_tokens.get(token_name)
            if token is None and kind in existing:
                raise ValueError(f"Provide the existing {token_name} in the environment; credentials will not be silently rotated")
            token = token or secrets.token_urlsafe(32)
            if len(token) < 32 or any(character in token for character in "\r\n\0"):
                raise ValueError(f"Invalid {token_name}")
            tokens[token_name] = token
        if kind == "hermes" and "accounts" not in entry:
            if not hermes_account:
                raise ValueError("Hermes requires an explicit native account mapping")
            entry["accounts"] = {username: hermes_account}
        if kind not in existing:
            agents.append(entry)
    config["agents"] = agents
    # Back up the previous JSON without changing paths, credentials or tokens.
    # Native Hermes directories are never opened or merged by the Core installer.
    if previous is not None:
        backup_directory = root / "runtime" / "configuration-backups"
        private_directory(backup_directory)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        private_write(backup_directory / f"core-{stamp}.json", previous)
    private_write(token_path, "".join(f"{name}={shlex.quote(value)}\n" for name, value in sorted(tokens.items())))
    private_write(config_path, json.dumps(config, ensure_ascii=False, indent=2) + "\n")

    from oai_core.server import create_core, load_config

    core = create_core(load_config(config_path))
    if core.account_exists(username):
        print(f"[INFO] 账号 {username} 已存在，保留原密码、配对和授权。")
    else:
        password = os.environ.get("OAI_INIT_ADMIN_PASSWORD", "")
        if not password:
            raise ValueError("New account requires a password")
        account = core.open_gateway_account(username)
        try:
            account.credentials.create_password(password)
        finally:
            account.close()
        print(f"[SUCCESS] 账号 {username} 已创建。")
    write_start_script(root)

    # 当启用 Hermes 适配器且宿主存在时，自动配置 Hermes 侧所需环境
    if "hermes" in selected_or_existing and "OAI_HERMES_SESSION_TOKEN" in tokens:
        sync_hermes_adapter_env(tokens["OAI_HERMES_SESSION_TOKEN"], hermes_account or username)


def sync_hermes_adapter_env(token: str, account_id: str) -> None:
    hermes_home = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
    if not hermes_home.is_dir():
        return
    env_file = hermes_home / ".env"
    existing_lines = []
    if env_file.exists():
        try:
            existing_lines = env_file.read_text(encoding="utf-8").splitlines()
        except OSError:
            return

    keys_to_update = {
        "OAI_HERMES_SESSION_TOKEN": token,
        "OAI_HERMES_GATEWAY_ROOT": str(hermes_home / "open-android-intelligence-gateway" / "accounts"),
        "OAI_HERMES_ACCOUNTS": json.dumps([account_id]),
        "OAI_HERMES_SESSION_PORT": "8792",
    }

    updated_lines = []
    seen = set()
    for line in existing_lines:
        matched_key = None
        for k in keys_to_update:
            if line.startswith(f"{k}=") or line.startswith(f"export {k}="):
                matched_key = k
                break
        if matched_key:
            updated_lines.append(f"{matched_key}={keys_to_update[matched_key]}")
            seen.add(matched_key)
        else:
            updated_lines.append(line)

    for k, v in keys_to_update.items():
        if k not in seen:
            updated_lines.append(f"{k}={v}")

    try:
        private_write(env_file, "\n".join(updated_lines) + "\n")
        print(f"[INFO] 已自动将 OAI 桥接配置同步至 Hermes 环境文件: {env_file}")
    except Exception as exc:
        print(f"[WARN] 尝试写入 Hermes 环境文件失败 ({exc})，请手动添加桥接变量。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--agents", default="")
    parser.add_argument("--hermes-account")
    arguments = parser.parse_args()
    selected = arguments.agents.split(",") if arguments.agents else []
    if len(set(selected)) != len(selected) or set(selected) - {"dsh", "hermes", "codex"}:
        parser.error("Invalid Agent selection")
    configure(arguments.root, arguments.host, arguments.port, arguments.username, selected, arguments.hermes_account)


if __name__ == "__main__":
    main()
