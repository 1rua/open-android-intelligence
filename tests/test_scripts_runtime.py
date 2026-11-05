"""Installer integration tests: isolated HOME, synthetic accounts, no real pip/services."""
from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
import sys

import pytest

from oai_core import scripts_runtime as runtime
from oai_core.gateway.account_paths import sha256_hex
from oai_core.gateway.credentials import hash_password
from oai_core.gateway.core import GatewayError
from oai_core.identity import IdentityStore
from oai_core.management import LocalRecovery

REPO = Path(__file__).resolve().parents[1]


@pytest.fixture
def workspace(monkeypatch):
    base = REPO / '.worktrees' / 'installer-tests' / secrets.token_hex(12)
    home = base / 'home'
    root = base / 'core'
    home.mkdir(parents=True, mode=0o700)
    root.mkdir()
    venv_python = root / '.venv/bin/python'
    venv_python.parent.mkdir(parents=True)
    venv_python.write_text('# synthetic interpreter\n')
    venv_python.chmod(0o700)
    monkeypatch.setenv('HOME', str(home))
    monkeypatch.setenv('HERMES_HOME', str(home / 'absent-hermes'))
    monkeypatch.setenv('DSH_HOME', str(home / 'absent-dsh'))
    return root


def answers(monkeypatch, inputs, passwords):
    normal, hidden = iter(inputs), iter(passwords)
    monkeypatch.setattr(runtime, '_input', lambda prompt: next(normal))
    monkeypatch.setattr(runtime, 'secret_input', lambda prompt: next(hidden))
    monkeypatch.setattr(runtime.sys.stdin, 'isatty', lambda: True)
    monkeypatch.setattr(runtime.sys.stdout, 'isatty', lambda: True)


def test_new_install_and_repeat_authenticate_before_any_pip(monkeypatch, workspace):
    root = workspace
    calls = []
    monkeypatch.setattr(runtime.subprocess, 'run', lambda argv, **kw: calls.append(argv))
    answers(monkeypatch, ['admin', 'bootstrap', 'SAVED', 'n'], ['pw-synthetic', 'pw-synthetic'])
    assert runtime.install(root) == 0
    config = json.loads((root / 'core.json').read_text())
    assert config['listen'] == {'host': '0.0.0.0', 'port': 11451}
    assert config['allowInsecureHttp'] is True
    assert json.loads((root / 'runtime/installer-journal.json').read_text())['status'] == 'complete'
    assert (root / 'runtime/secrets/core-master-key').is_file()
    assert (root / 'start-core.sh').is_file()
    assert Path(root / calls[0][-1]).resolve() == Path(runtime.__file__).resolve().parents[2]
    assert calls[1][-1] == 'check'
    initial = (root / 'core.json').read_bytes(), (root / 'runtime/secrets/core-master-key').read_bytes()
    calls.clear()
    answers(monkeypatch, ['admin'], ['incorrect'])
    with pytest.raises(GatewayError):
        runtime.install(root)
    assert not calls
    assert initial == ((root / 'core.json').read_bytes(), (root / 'runtime/secrets/core-master-key').read_bytes())
    answers(monkeypatch, ['admin', 'n'], ['pw-synthetic'])
    assert runtime.install(root) == 0
    assert len(calls) == 2
    assert initial == ((root / 'core.json').read_bytes(), (root / 'runtime/secrets/core-master-key').read_bytes())
    # Simulate keep-config uninstall with preserved evidence, not permanent deletion.
    (root / '.venv').rename(root / '.venv-preserved')
    (root / 'start-core.sh').rename(root / 'start-core-preserved.sh')
    calls.clear()
    answers(monkeypatch, ['admin'], ['incorrect'])
    with pytest.raises(GatewayError):
        runtime.install(root)
    assert not calls and not (root / '.venv').exists()
    answers(monkeypatch, ['admin', 'n'], ['pw-synthetic'])
    assert runtime.install(root) == 0
    assert calls[0][1:3] == ['-m', 'venv']
    assert (root / 'start-core.sh').is_file()
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == initial[1]


def test_second_factor_only_after_actual_policy_challenge(monkeypatch, workspace):
    root = workspace
    monkeypatch.setattr(runtime.subprocess, 'run', lambda *a, **kw: None)
    answers(monkeypatch, ['admin', 'bootstrap', 'SAVED', 'n'], ['password', 'password'])
    assert runtime.install(root) == 0
    _, key, identity = runtime._paths(root)
    store = IdentityStore(identity, key_store=runtime.LocalMasterKeyStore(key))
    try:
        handle = store.begin_management('admin', 'password')
        proof = store.authenticate('admin', 'password', purpose='enroll')['enrollmentProof']
        account = store.require_management(handle, 'security')['accountId']
        enrollment = store.begin_mfa_enrollment(account, proof)
        import base64
        import time
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.twofactor.totp import TOTP
        secret = base64.b32decode(enrollment['secret'] + '=' * (-len(enrollment['secret']) % 8))
        otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
        result = store.confirm_mfa_enrollment(account, proof, otp, enrollment_id=enrollment['enrollmentId'])
        handle = store.begin_management('admin', 'password')
        LocalRecovery(store).register(handle, confirmed=True)
        store.set_mfa_required(handle, True)
        prompts = []
        normal = iter(['admin', '2'])
        hidden = iter(['password', result['recoveryCodes'][0]])
        monkeypatch.setattr(runtime, '_input', lambda prompt: (prompts.append(prompt), next(normal))[1])
        monkeypatch.setattr(runtime, 'secret_input', lambda prompt: (prompts.append(prompt), next(hidden))[1])
        authenticated = runtime.authenticate(store, {'install'})
        assert store.require_management(authenticated, 'install')['accountId'] == account
        assert any('二次验证' in prompt for prompt in prompts)
        store.revoke_management(authenticated)
    finally:
        store.close()


def test_new_install_rejects_mismatched_password_without_journal(monkeypatch, workspace):
    answers(monkeypatch, ['admin'], ['first', 'different'])
    with pytest.raises(ValueError):
        runtime.install(workspace)
    assert not (workspace / 'runtime').exists()


def test_legacy_authentication_precedes_migration_writes(monkeypatch, workspace):
    root = workspace
    storage = root / 'runtime/accounts'
    account_dir = storage / sha256_hex('old-admin')
    account_dir.mkdir(parents=True, mode=0o700)
    (root / 'runtime').chmod(0o700)
    storage.chmod(0o700)
    database = account_dir / 'gateway.sqlite'
    db = sqlite3.connect(database)
    db.executescript('CREATE TABLE account_metadata(key TEXT PRIMARY KEY,value TEXT);'
                     'CREATE TABLE account_credentials(credential_id TEXT PRIMARY KEY,password_hash TEXT,updated_at TEXT);')
    db.execute('INSERT INTO account_metadata VALUES (?,?)', ('gateway_account_id', 'old-admin'))
    db.execute('INSERT INTO account_credentials VALUES (?,?,?)', ('password', hash_password('old-password'), 'now'))
    db.commit()
    db.close()
    database.chmod(0o600)
    config = {'storageRoot': 'runtime/accounts', 'masterKeyFile': 'runtime/secrets/key', 'agents': []}
    (root / 'core.json').write_text(json.dumps(config))
    (root / 'start-core.sh').write_text('# legacy start\n')
    before = database.read_bytes()
    calls = []
    monkeypatch.setattr(runtime.subprocess, 'run', lambda argv, **kw: calls.append(argv))
    answers(monkeypatch, ['old-admin'], ['incorrect'])
    with pytest.raises(PermissionError):
        runtime.install(root)
    assert not calls and database.read_bytes() == before
    assert not (root / 'runtime/security').exists()
    assert not (root / 'runtime/identity-migrations').exists()
    # The actual migration implementation is tested here; only host-process
    # inspection and package commands are substituted for isolation.
    original_key = root / 'runtime/secrets/key'
    original_key.parent.mkdir(parents=True, mode=0o700)
    original_key.write_bytes(b'k' * 32)
    original_key.chmod(0o600)
    monkeypatch.setattr(runtime, '_old_writers_stopped', lambda path: True)
    answers(monkeypatch, ['old-admin', 'STOP', 'SAVED', 'n'], ['old-password'])
    assert runtime.install(root) == 0
    assert len(calls) == 2
    assert original_key.read_bytes() == b'k' * 32
    store = IdentityStore(root / 'runtime/security/identity.sqlite')
    try:
        assert store.authenticate('old-admin', 'old-password')['role'] == 'admin'
    finally:
        store.close()
    old = sqlite3.connect(database)
    try:
        with pytest.raises(sqlite3.OperationalError):
            old.execute("UPDATE account_credentials SET password_hash='changed'")
    finally:
        old.close()


def test_uninstall_yes_cannot_bypass_authentication(monkeypatch, workspace):
    root = workspace
    (root / 'oai-core').mkdir()
    (root / 'oai-core/pyproject.toml').write_text('[project]\nname = "open-android-intelligence-core"\n')
    (root / 'scripts').mkdir()
    (root / 'scripts/install.sh').write_text('# marker\n')
    (root / '.venv').mkdir(exist_ok=True)
    (root / '.venv/bin/python').write_text('#!/usr/bin/env bash\nexec ' + __import__('shlex').quote(sys.executable) + ' "$@"\n')
    (root / '.venv/bin/python').chmod(0o700)
    (root / 'start-core.sh').write_text('# start\n')
    (root / 'core.json').write_text(json.dumps({'storageRoot': 'runtime/accounts', 'masterKeyFile': 'runtime/secrets/key'}))
    result = subprocess.run(['bash', str(REPO / 'scripts/uninstall.sh'), '--yes', '--dir', str(root)],
                            stdin=subprocess.DEVNULL, start_new_session=True, env=os.environ.copy(),
                            capture_output=True, text=True, timeout=10)
    assert result.returncode != 0
    assert (root / '.venv').is_dir()
    assert (root / 'start-core.sh').is_file()
    assert '认证' in result.stderr
