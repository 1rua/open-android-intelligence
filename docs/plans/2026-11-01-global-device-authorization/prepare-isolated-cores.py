#!/usr/bin/env python3
"""Prepare two local test Cores without starting a listener or using user accounts."""

from __future__ import annotations

import argparse
from contextlib import closing, contextmanager
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import subprocess
import sys
import tarfile
from datetime import datetime, timedelta, timezone

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


sys.dont_write_bytecode = True
PLAN = Path(__file__).resolve().parent
ROOT = PLAN.parents[2]
RUNTIME = PLAN / "runtime"
SNAPSHOT = RUNTIME / "bootstrap-source"
INSTANCES = {"core-a": 19841, "core-b": 19842}
ACCOUNTS = ("terra_primary", "terra_secondary")


def fail(message: str) -> None:
    raise RuntimeError(message)


def run(*args: str, cwd: Path = ROOT) -> str:
    result = subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True, timeout=30)
    return result.stdout.strip()


def private_file(path: Path, contents: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(contents)
        stream.flush()
        os.fsync(stream.fileno())


def json_file(path: Path, value: object) -> None:
    private_file(path, (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode())


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        fail(f"invalid object: {path.name}")
    return value


def private_dir(path: Path) -> None:
    path.mkdir(mode=0o700)


@contextmanager
def isolated_environment(directory: Path):
    values = {
        "HOME": str(directory / "home"),
        "TMPDIR": str(directory / "tmp"),
        "OAI_HISTORY_MEDIA_ROOT": str(directory / "history-media"),
        "OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256": None,
    }
    original = {name: os.environ.get(name) for name in values}
    try:
        for name, value in values.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        yield
    finally:
        for name, value in original.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def capture_committed_core() -> str:
    revision = run("git", "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        fail("invalid bootstrap revision")
    archive = subprocess.run(
        ["git", "archive", "--format=tar", revision, "oai-core/src", "gateway-contract"],
        cwd=ROOT, check=True, capture_output=True, timeout=60,
    ).stdout
    private_dir(SNAPSHOT)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        members = tar.getmembers()
        if not members or any(not (item.isfile() or item.isdir()) for item in members):
            fail("unexpected bootstrap archive entry")
        tar.extractall(SNAPSHOT, members=members, filter="data")
    return revision


def import_bootstrap():
    source = SNAPSHOT / "oai-core/src"
    sys.path.insert(0, str(source))
    import oai_core
    from oai_core.gateway.credentials import verify_password
    from oai_core.server import create_core

    if not Path(oai_core.__file__).resolve().is_relative_to(source.resolve()):
        fail("bootstrap Core did not come from the committed snapshot")
    return create_core, verify_password


def create_certificates(directory: Path, label: str) -> None:
    now = datetime.now(timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    server_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"OAI isolated test CA {label}")])
    server_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    ca = (
        x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
        .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                     data_encipherment=False, key_agreement=False, key_cert_sign=True,
                                     crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    server = (
        x509.CertificateBuilder().subject_name(server_name).issuer_name(ca_name)
        .public_key(server_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                     data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                     crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(server_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                        x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    for name, value in (("ca-key.pem", ca_key), ("server-key.pem", server_key)):
        private_file(directory / name, value.private_bytes(serialization.Encoding.PEM,
                     serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    for name, value in (("ca-cert.pem", ca), ("server-cert.pem", server)):
        private_file(directory / name, value.public_bytes(serialization.Encoding.PEM))


def config_for(instance: str, port: int) -> dict:
    directory = RUNTIME / instance
    return {
        "storageRoot": "accounts",
        "masterKeyFile": "secrets/master-key",
        "contractRoot": os.path.relpath(ROOT / "gateway-contract", directory),
        "listen": {"host": "127.0.0.1", "port": port},
        "tls": {"certFile": "tls/v2/server-cert.pem", "keyFile": "tls/v2/server-key.pem"},
        "agents": [],
    }


def prepare() -> None:
    if RUNTIME.exists():
        if (RUNTIME / "prepared.json").is_file():
            verify()
            return
        if any(RUNTIME.iterdir()):
            fail("runtime is incomplete; preserve it for manual review, do not overwrite")
    else:
        private_dir(RUNTIME)
    if RUNTIME.is_symlink() or (RUNTIME.stat().st_mode & 0o077):
        fail("runtime must be a private, non-symlink directory")
    if run("git", "check-ignore", "-q", "--", str((RUNTIME / "secrets-probe").relative_to(ROOT))) != "":
        fail("unexpected git check-ignore result")

    revision = capture_committed_core()
    create_core, _ = import_bootstrap()
    passwords = {}
    for instance, port in INSTANCES.items():
        directory = RUNTIME / instance
        private_dir(directory)
        private_dir(directory / "tls")
        private_dir(directory / "tls/v2")
        for name in ("home", "tmp", "history-media"):
            private_dir(directory / name)
        create_certificates(directory / "tls/v2", instance)
        json_file(directory / "core.json", config_for(instance, port))
        settings = {
            "storageRoot": str(directory / "accounts"),
            "masterKeyFile": str(directory / "secrets/master-key"),
            "contractRoot": str(SNAPSHOT / "gateway-contract"),
        }
        passwords[instance] = {}
        with isolated_environment(directory):
            core = create_core(settings)
            for username in ACCOUNTS:
                password = secrets.token_urlsafe(24)
                passwords[instance][username] = password
                if core.account_exists(username):
                    fail("refusing to replace an existing account")
                account = core.open_gateway_account(username)
                try:
                    account.credentials.create_password(password)
                    if not account.credentials.verify_password(password):
                        fail("offline account verification failed")
                finally:
                    account.close()
    json_file(RUNTIME / "test-credentials.json", passwords)
    json_file(RUNTIME / "sample-plan.json", {
        "note": "Synthetic sample definitions only; no device data has been created or read.",
        "sms": [{"sender": "+12025550101", "body": "OAI-TEST-SMS-A"},
                {"sender": "+12025550102", "body": "OAI-TEST-SMS-B-OUTSIDE-SCOPE"}],
        "calls": [{"number": "+12025550101"}, {"number": "+12025550102"}],
        "notifications": [{"title": "OAI TEST A", "body": "OAI-TEST-NOTIFICATION-A"},
                          {"title": "OAI TEST B", "body": "OAI-TEST-NOTIFICATION-B-OUTSIDE-SCOPE"}],
        "screen": {"text": "OAI TEST SCREEN", "action": "test-only-button"},
    })
    json_file(RUNTIME / "prepared.json", {"bootstrapCommit": revision, "listenerStartedDuringPrepare": False})
    verify()


def verify() -> None:
    if RUNTIME.is_symlink() or (RUNTIME.stat().st_mode & 0o077):
        fail("runtime is not private")
    run("git", "check-ignore", "-q", "--", str((RUNTIME / "test-credentials.json").relative_to(ROOT)))
    if run("git", "ls-files", "--", str(RUNTIME.relative_to(ROOT))):
        fail("runtime contains tracked files")
    assert_no_listeners()
    marker = read_json(RUNTIME / "prepared.json")
    if not re.fullmatch(r"[0-9a-f]{40}", marker.get("bootstrapCommit", "")):
        fail("invalid bootstrap provenance")
    _, verify_password = import_bootstrap()
    credentials = read_json(RUNTIME / "test-credentials.json")
    all_passwords = [credentials[instance][username] for instance in INSTANCES for username in ACCOUNTS]
    if len(set(all_passwords)) != len(all_passwords):
        fail("test passwords must be unique")
    master_digests, cert_digests, ca_digests = set(), set(), set()
    for instance, port in INSTANCES.items():
        directory = RUNTIME / instance
        if read_json(directory / "core.json") != config_for(instance, port):
            fail(f"{instance}: configuration differs; refusing to replace it")
        for name in ("home", "tmp", "history-media"):
            path = directory / name
            if path.is_symlink() or not path.is_dir() or path.stat().st_mode & 0o077:
                fail(f"{instance}: isolated {name} directory is missing or unsafe")
        for file in [RUNTIME / "test-credentials.json", directory / "secrets/master-key",
                     directory / "tls/v2/ca-key.pem", directory / "tls/v2/server-key.pem"]:
            if file.is_symlink() or file.stat().st_mode & 0o077:
                fail(f"{instance}: private material has unsafe permissions")
        master_digests.add(hashlib.sha256((directory / "secrets/master-key").read_bytes()).digest())
        cert = x509.load_pem_x509_certificate((directory / "tls/v2/server-cert.pem").read_bytes())
        cert_digests.add(cert.fingerprint(hashes.SHA256()))
        ca = x509.load_pem_x509_certificate((directory / "tls/v2/ca-cert.pem").read_bytes())
        ca_digests.add(ca.fingerprint(hashes.SHA256()))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(directory / "tls/v2/server-cert.pem", directory / "tls/v2/server-key.pem")
        run("openssl", "verify", "-x509_strict", "-CAfile", str(directory / "tls/v2/ca-cert.pem"),
            "-verify_ip", "127.0.0.1", str(directory / "tls/v2/server-cert.pem"))
        for username in ACCOUNTS:
            account_hash = hashlib.sha256(username.encode()).hexdigest()
            database = directory / "accounts" / account_hash / "gateway.sqlite"
            if not database.is_file():
                fail(f"{instance}: account database is missing")
            with closing(sqlite3.connect(database.as_uri() + "?mode=ro&immutable=1", uri=True)) as connection:
                row = connection.execute("SELECT password_hash FROM account_credentials WHERE credential_id = ?",
                                         ("password",)).fetchone()
            if not row or not verify_password(credentials[instance][username], row[0]):
                fail(f"{instance}: account password verification failed")
            other = all_passwords[(all_passwords.index(credentials[instance][username]) + 1) % len(all_passwords)]
            if verify_password(other, row[0]):
                fail(f"{instance}: account password isolation failed")
    if len(master_digests) != 2 or len(cert_digests) != 2 or len(ca_digests) != 2:
        fail("Core instances must have distinct master keys, certificates and CAs")
    print("PASS: isolated configs, four accounts, private keys, distinct CAs and TLS certificates")
    print("PENDING: latest Core startup, Android TLS trust, login and device operations")


def assert_no_listeners() -> None:
    listeners = run("ss", "-ltn").splitlines()[1:]
    for line in listeners:
        fields = line.split()
        if len(fields) > 3 and fields[3].rsplit(":", 1)[-1] in {str(p) for p in INSTANCES.values()}:
            fail("a requested Core port is already listening; do not use a shared service")


def reverse(serial: str) -> None:
    verify()
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        fail("reverse is restricted to an explicitly named emulator")
    if run("adb", "-s", serial, "get-state") != "device":
        fail("selected emulator is not authorized and online")
    if run("adb", "-s", serial, "shell", "getprop", "ro.kernel.qemu") != "1":
        fail("selected device did not confirm it is an emulator")
    assert_no_listeners()
    existing = {}
    for line in run("adb", "-s", serial, "reverse", "--list").splitlines():
        fields = line.split()
        if len(fields) >= 2:
            existing[fields[-2]] = fields[-1]
    for port in INSTANCES.values():
        endpoint = f"tcp:{port}"
        if endpoint in existing and existing[endpoint] != endpoint:
            fail(f"{endpoint} already maps elsewhere; refusing to replace it")
    for port in INSTANCES.values():
        endpoint = f"tcp:{port}"
        if endpoint not in existing:
            run("adb", "-s", serial, "reverse", "--no-rebind", endpoint, endpoint)
        print(f"PASS: emulator reverse {endpoint} -> host {endpoint}")


def main() -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "verify", "reverse"))
    parser.add_argument("--serial", help="explicit emulator serial, required for reverse")
    args = parser.parse_args()
    if args.action == "reverse" and not args.serial:
        parser.error("reverse requires --serial")
    try:
        if args.action == "prepare":
            prepare()
        elif args.action == "verify":
            verify()
        else:
            reverse(args.serial)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, sqlite3.Error) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
