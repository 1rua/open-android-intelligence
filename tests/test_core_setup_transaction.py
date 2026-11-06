"""Authenticated configuration transactions preserve the last usable deployment.

Migration map: the removed Shell guide/save helpers are exercised through
``configuration.configure`` and ``configuration.run_setup`` instead.  The old
host dotenv/YAML auto-sync is replaced by protected ``secretFile`` migration;
Core configuration no longer creates accounts or guesses Agent instances.
"""
from __future__ import annotations

from contextlib import closing
from hashlib import sha256
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

import pytest

from oai_core import configuration
from oai_core.gateway.account_paths import account_paths
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.server import create_core, load_config

REPOSITORY = Path(__file__).resolve().parents[1]
PASSWORD = 'synthetic-original-password'
TOKEN = 'synthetic-bridge-token-12345678901234567890'


@pytest.fixture(autouse=True)
def isolated_hosts(tmp_path, monkeypatch):
    home = tmp_path / 'home'
    home.mkdir()
    # A regular file prevents optional skill deployment from touching a host.
    for name in ('.agents', '.claude', '.codex', '.hermes', '.codebuddy', '.trae', '.dsh-cline', '.dsh'):
        (home / name).write_text('Unrelated host configuration.\n')
    monkeypatch.setenv('HOME', str(home))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'absent-hermes'))
    monkeypatch.setenv('DSH_HOME', str(tmp_path / 'absent-dsh'))
    for name in ('OAI_HERMES_SESSION_TOKEN', 'OAI_DSH_ADAPTER_TOKEN', 'OAI_INIT_ADMIN_PASSWORD'):
        monkeypatch.delenv(name, raising=False)


def bootstrap(root):
    root.mkdir(parents=True, exist_ok=True)
    storage = root / 'runtime/accounts'
    database = root / 'runtime/security/identity.sqlite'
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, b'bootstrap\n')
        os.close(write_fd)
        write_fd = -1
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
        if write_fd >= 0:
            os.close(write_fd)
    store = IdentityStore(database, create=True, bootstrap_proof=proof)
    account = store.bootstrap('alice', PASSWORD, confirmed=True)
    return store, store.begin_management('alice', PASSWORD), account['accountId']


def configure(root, store, handle, port=9045, selected=()):
    return configuration.configure(root, '127.0.0.1', port, 'alice', list(selected), None,
                                   store=store, handle=handle)


@pytest.fixture
def deployment(tmp_path):
    root = tmp_path / 'deployment'
    store, handle, account_id = bootstrap(root)
    configure(root, store, handle, port=8045, selected=('codex',))
    core = create_core(load_config(root / 'core.json'))
    with closing(core.open_gateway_account(account_id)) as account:
        # A real, nonempty account database must remain intact through saves.
        assert account.store is not None
    core.identity_store.close()
    yield root, store, handle, account_id
    store.close()


def hashes(root, account_id):
    paths = [root / 'core.json', root / 'start-core.sh', root / 'runtime/secrets/core-master-key',
             account_paths(root / 'runtime/accounts', account_id).database]
    return {str(path.relative_to(root)): sha256(path.read_bytes()).hexdigest() for path in paths}


def assert_identity(store, account_id):
    assert store.authenticate('alice', PASSWORD)['accountId'] == account_id


def legacy_agent(root, *, kind='hermes', token=TOKEN, source='tokenEnv'):
    config_path = root / 'core.json'
    config = json.loads(config_path.read_text())
    name = 'OAI_HERMES_SESSION_TOKEN' if kind == 'hermes' else 'OAI_DSH_ADAPTER_TOKEN'
    agent = {'id': kind, 'type': kind, 'url': 'http://127.0.0.1:8792',
             'accounts': {'alice': 'native-alice'}}
    agent[source] = name if source == 'tokenEnv' else token
    config['agents'].append(agent)
    config_path.write_text(json.dumps(config))
    if source == 'tokenEnv' and token is not None:
        configuration.private_write(root / 'runtime/secrets/adapter-tokens.env', name + '=' + shlex.quote(token) + '\n')
    return agent


def test_missing_administrator_authorization_does_not_write(tmp_path):
    root = tmp_path / 'deployment'
    root.mkdir()
    with pytest.raises(PermissionError, match='authorization'):
        configuration.configure(root, '127.0.0.1', 8045, 'alice', [], None)
    assert list(root.iterdir()) == []


@pytest.mark.parametrize('operation', ['load_config', 'create_core'])
def test_core_preparation_failure_preserves_old_files(deployment, monkeypatch, operation):
    root, store, handle, account_id = deployment
    original = hashes(root, account_id)
    def fail(*args, **kwargs):
        raise ValueError('synthetic preparation failure')
    monkeypatch.setattr('oai_core.server.' + operation, fail)
    with pytest.raises(ValueError, match='synthetic preparation failure'):
        configure(root, store, handle)
    assert hashes(root, account_id) == original
    assert_identity(store, account_id)
    assert json.loads((root / 'runtime/configuration-journal.json').read_text())['status'] == 'rolled_back'


@pytest.mark.parametrize('target', ['core.json', 'start-core.sh', 'runtime/accounts/.agent-secrets/agent-hermes.secret'])
def test_failed_file_replacement_restores_complete_old_configuration(deployment, monkeypatch, target):
    root, store, handle, account_id = deployment
    legacy_agent(root)
    if target == 'start-core.sh':
        (root / 'start-core.sh').write_text('#!/bin/sh\n# previous launcher\n')
    original = hashes(root, account_id)
    replace = os.replace
    rejected = False
    def reject_once(source, destination):
        nonlocal rejected
        if Path(destination) == root / target and not rejected:
            rejected = True
            raise PermissionError('synthetic replace failure')
        return replace(source, destination)
    monkeypatch.setattr(os, 'replace', reject_once)
    with pytest.raises(PermissionError, match='synthetic replace failure'):
        configure(root, store, handle)
    assert rejected
    assert hashes(root, account_id) == original
    assert_identity(store, account_id)
    assert json.loads((root / 'runtime/configuration-journal.json').read_text())['status'] == 'rolled_back'
    assert not (root / 'runtime/accounts/.agent-secrets/agent-hermes.secret').exists()


@pytest.mark.parametrize('phase', ['file', 'directory'])
def test_fsync_failure_restores_configuration_and_original_account(deployment, monkeypatch, phase):
    root, store, handle, account_id = deployment
    before = hashes(root, account_id)
    injected = False
    if phase == 'file':
        fsync = os.fsync
        def fail_file(descriptor):
            nonlocal injected
            inode = os.fstat(descriptor).st_ino
            if not injected and any(path.stat().st_ino == inode for path in root.glob('core.json.*.tmp')):
                injected = True
                raise OSError('synthetic file fsync failure')
            return fsync(descriptor)
        monkeypatch.setattr(os, 'fsync', fail_file)
    else:
        fsync_directory = configuration.fsync_directory
        def fail_directory(path):
            nonlocal injected
            if path == root and not injected and json.loads((root / 'core.json').read_text())['listen']['port'] == 9045:
                injected = True
                raise OSError('synthetic directory fsync failure')
            return fsync_directory(path)
        monkeypatch.setattr(configuration, 'fsync_directory', fail_directory)
    with pytest.raises(OSError, match='synthetic .* fsync failure'):
        configure(root, store, handle)
    assert injected and hashes(root, account_id) == before
    assert json.loads((root / 'runtime/configuration-journal.json').read_text())['status'] == 'rolled_back'
    assert_identity(store, account_id)


def test_insufficient_management_scope_cannot_write_or_create_journal(deployment):
    root, store, _, account_id = deployment
    handle = store.begin_management('alice', PASSWORD, scopes={'accounts.read'})
    before = hashes(root, account_id)
    journal = (root / 'runtime/configuration-journal.json').read_bytes()
    with pytest.raises(Exception) as failure:
        configure(root, store, handle)
    assert getattr(failure.value, 'code', None) in {'MANAGEMENT_REQUIRED', 'ADMIN_REQUIRED'}
    assert hashes(root, account_id) == before
    assert (root / 'runtime/configuration-journal.json').read_bytes() == journal


def interrupt_save(root):
    # Reauthenticate in the child using the real identity database.  The password
    # is synthetic test data, not a production CLI or environment credential.
    source = '''import os, sys
from pathlib import Path
from oai_core.configuration import configure
from oai_core.identity import IdentityStore
root = Path(sys.argv[1])
store = IdentityStore(root / 'runtime/security/identity.sqlite')
handle = store.begin_management('alice', 'synthetic-original-password')
replace = os.replace
def interrupt(source, target):
    result = replace(source, target)
    if Path(target) == root / 'core.json':
        os._exit(86)
    return result
os.replace = interrupt
configure(root, '127.0.0.1', 9045, 'alice', [], None, store=store, handle=handle)
'''
    result = subprocess.run([sys.executable, '-c', source, str(root)], cwd=REPOSITORY,
                            env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode == 86, result.stderr + result.stdout


def test_interrupted_save_recovers_before_a_failed_retry(deployment, monkeypatch):
    root, store, handle, account_id = deployment
    original = hashes(root, account_id)
    interrupt_save(root)
    assert json.loads((root / 'runtime/configuration-journal.json').read_text())['status'] == 'applying'
    with monkeypatch.context() as patch:
        def fail(*args, **kwargs):
            raise ValueError('synthetic retry failure')
        patch.setattr('oai_core.server.load_config', fail)
        with pytest.raises(ValueError, match='synthetic retry failure'):
            configure(root, store, handle, port=10045)
    assert hashes(root, account_id) == original
    configure(root, store, handle, port=10045)
    assert json.loads((root / 'core.json').read_text())['listen']['port'] == 10045
    assert_identity(store, account_id)


@pytest.mark.parametrize('modification', ['corrupt', 'third-party-change'])
def test_authenticated_recovery_rejects_damaged_or_changed_journal(deployment, modification):
    root, store, handle, account_id = deployment
    interrupt_save(root)
    journal_path = root / 'runtime/configuration-journal.json'
    state = json.loads(journal_path.read_text())
    if modification == 'corrupt':
        state['version'] = 999
        journal_path.write_text(json.dumps(state))
    else:
        (root / 'core.json').write_text('{"external":"new-value"}')
    before = hashes(root, account_id)
    journal = journal_path.read_bytes()
    with pytest.raises((ValueError, PermissionError)):
        configuration.ConfigurationTransaction.recover(root, store=store, handle=handle)
    assert hashes(root, account_id) == before
    assert journal_path.read_bytes() == journal


def test_unauthorized_recovery_preserves_interrupted_state(deployment):
    root, store, _, account_id = deployment
    interrupt_save(root)
    before = hashes(root, account_id)
    journal = (root / 'runtime/configuration-journal.json').read_bytes()
    with pytest.raises(PermissionError, match='authorization'):
        configuration.ConfigurationTransaction.recover(root)
    assert hashes(root, account_id) == before
    assert (root / 'runtime/configuration-journal.json').read_bytes() == journal
    assert_identity(store, account_id)


def test_corrupt_recovery_backup_fails_closed(deployment):
    root, store, handle, account_id = deployment
    interrupt_save(root)
    journal = json.loads((root / 'runtime/configuration-journal.json').read_text())
    entry = next(entry for entry in journal['entries'] if entry['before'] is not None)
    (root / entry['backup']).write_bytes(b'synthetic corrupted backup')
    interrupted = hashes(root, account_id)
    with pytest.raises(ValueError, match='backup'):
        configure(root, store, handle, port=10045)
    assert hashes(root, account_id) == interrupted


def test_interrupted_configuration_cannot_start_until_recovered(deployment):
    root, _, _, _ = deployment
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    (binary / 'oai').write_text('#!/usr/bin/env bash\nprintf "UNEXPECTED_SERVICE_START\\n"\n')
    (binary / 'oai').chmod(0o700)
    interrupt_save(root)
    result = subprocess.run([str(root / 'start-core.sh')], cwd=REPOSITORY, env=os.environ.copy(),
                            capture_output=True, text=True, timeout=20)
    assert result.returncode != 0
    assert 'UNEXPECTED_SERVICE_START' not in result.stdout
    assert 'journal' in result.stderr


def test_recovery_refuses_external_writer_after_interruption(deployment):
    root, store, handle, account_id = deployment
    interrupt_save(root)
    config = json.loads((root / 'core.json').read_text())
    config['external_setting'] = 'synthetic-external-writer'
    (root / 'core.json').write_text(json.dumps(config))
    external = hashes(root, account_id)
    with pytest.raises(ValueError, match='changed after interruption'):
        configure(root, store, handle, port=10045)
    assert hashes(root, account_id) == external


def test_configuration_lock_rejects_competing_writer(deployment):
    root, store, handle, account_id = deployment
    before = hashes(root, account_id)
    with configuration.configuration_lock(root):
        with pytest.raises((BlockingIOError, OSError)):
            configure(root, store, handle)
    assert hashes(root, account_id) == before


def test_existing_master_key_change_during_preparation_is_detected(deployment, monkeypatch):
    root, store, handle, account_id = deployment
    key = root / 'runtime/secrets/core-master-key'
    original = hashes(root, account_id)
    replace = os.replace
    changed = False
    def concurrent_key_writer(source, destination):
        nonlocal changed
        if Path(destination).name == 'validate-config.json' and not changed:
            key.write_bytes(b'X' * 32)
            changed = True
        return replace(source, destination)
    monkeypatch.setattr(os, 'replace', concurrent_key_writer)
    with pytest.raises(ValueError, match='changed during preparation'):
        configure(root, store, handle)
    assert changed
    current = hashes(root, account_id)
    assert all(current[name] == value for name, value in original.items() if name != 'runtime/secrets/core-master-key')
    assert key.read_bytes() == b'X' * 32


@pytest.mark.parametrize('agents', [
    [{'id': 'duplicate', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/codex'},
     {'id': 'duplicate', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/other'}],
    [{'id': 'unknown', 'type': 'bug09-uninstalled-type'}],
    [{'id': 'hermes', 'type': 'hermes', 'url': 'http://127.0.0.1:8792', 'accounts': {'alice': 'native-alice'}}],
    [{'id': 'hermes', 'type': 'hermes', 'url': 'http://127.0.0.1:8792', 'token': TOKEN,
      'accounts': {'alice': 'same-native-account', 'bob': 'same-native-account'}}],
    [{'id': 'dsh', 'type': 'dsh', 'url': 'file:///synthetic-invalid-origin', 'token': TOKEN}],
    [{'id': 'bad/id', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/codex'}],
], ids=['duplicate-id', 'unknown-type', 'missing-secret', 'non-one-to-one', 'invalid-url', 'invalid-id'])
def test_real_registry_rejects_invalid_agents_before_commit(deployment, agents):
    root, store, handle, account_id = deployment
    config = json.loads((root / 'core.json').read_text())
    config['agents'] = agents
    (root / 'core.json').write_text(json.dumps(config))
    original = hashes(root, account_id)
    with pytest.raises(ValueError):
        configure(root, store, handle)
    assert hashes(root, account_id) == original


@pytest.mark.parametrize('host', ['bad host', '127.0.0.1 ', '999.1.1.1', 'http://localhost', 'bad..host', '-bad-host'])
def test_invalid_listener_never_changes_active_files(deployment, host):
    root, store, handle, account_id = deployment
    original = hashes(root, account_id)
    with pytest.raises(ValueError):
        configuration.configure(root, host, 9045, 'alice', [], None, store=store, handle=handle)
    assert hashes(root, account_id) == original


def test_validation_uses_staged_secret_without_starting_agent(deployment, monkeypatch):
    import asyncio
    root, store, handle, account_id = deployment
    legacy_agent(root)
    async def no_process(*args, **kwargs):
        raise AssertionError('Configuration validation must not start an Agent')
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', no_process)
    before = hashes(root, account_id)
    configure(root, store, handle)
    config = json.loads((root / 'core.json').read_text())
    agent = next(entry for entry in config['agents'] if entry['id'] == 'hermes')
    assert 'tokenEnv' not in agent and 'token' not in agent
    secret = root / 'runtime/accounts' / agent['secretFile']
    assert secret.read_text().strip() == TOKEN
    assert secret.stat().st_mode & 0o777 == 0o600
    assert hashes(root, account_id)['runtime/secrets/core-master-key'] == before['runtime/secrets/core-master-key']
    assert_identity(store, account_id)


@pytest.mark.parametrize('phase', ['commit', 'rollback'])
def test_each_replace_preserves_writer_during_temporary_fsync(deployment, monkeypatch, phase):
    root, store, handle, _ = deployment
    target = root / 'settings.env'
    target.write_bytes(b'SETTING=old\n')
    external = b'SETTING=external-administrator-update\n'
    with configuration.configuration_lock(root):
        transaction = configuration.ConfigurationTransaction(root)
        transaction.add_file(target, b'SETTING=new\n')
        if phase == 'rollback':
            replace = os.replace
            def interrupt(source, destination):
                replace(source, destination)
                if Path(destination) == target:
                    raise KeyboardInterrupt('synthetic interruption')
            with monkeypatch.context() as patch:
                patch.setattr(os, 'replace', interrupt)
                with pytest.raises(KeyboardInterrupt):
                    transaction.commit()
        fsync = os.fsync
        injected = False
        def write_during_fsync(descriptor):
            nonlocal injected
            fsync(descriptor)
            inode = os.fstat(descriptor).st_ino
            if not injected and any(path.stat().st_ino == inode for path in root.glob('settings.env.*.tmp')):
                target.write_bytes(external)
                injected = True
        monkeypatch.setattr(os, 'fsync', write_during_fsync)
        with pytest.raises(ValueError, match='changed'):
            getattr(transaction, phase)()
        assert injected and target.read_bytes() == external
        journal = transaction.journal.read_bytes()
        entry = json.loads(journal)['entries'][0]
        assert (root / entry['backup']).read_bytes() == b'SETTING=old\n'
        assert (root / entry['staged']).read_bytes() == b'SETTING=new\n'
        with pytest.raises(ValueError, match='changed after interruption'):
            configuration.ConfigurationTransaction.recover(root, store=store, handle=handle)
        assert target.read_bytes() == external and transaction.journal.read_bytes() == journal


@pytest.mark.parametrize('target_kind', ['existing-file', 'new-file', 'new-account'])
def test_rollback_rechecks_each_target_after_restoring_another(deployment, monkeypatch, target_kind):
    root, store, handle, _ = deployment
    first, second = root / 'first', root / 'second.env'
    second.write_bytes(b'SECOND=old\n')
    if target_kind == 'existing-file':
        first.write_bytes(b'FIRST=old\n')
    with configuration.configuration_lock(root):
        transaction = configuration.ConfigurationTransaction(root)
        if target_kind == 'new-account':
            staged = root / 'staged-account'
            staged.mkdir()
            (staged / 'gateway.sqlite').write_bytes(b'synthetic-new-account')
            transaction.add_account(staged, first)
        else:
            transaction.add_file(first, b'FIRST=new\n')
        transaction.add_file(second, b'SECOND=new\n')
        replace = os.replace
        def interrupt(source, destination):
            replace(source, destination)
            if Path(destination) == second:
                raise KeyboardInterrupt('synthetic interruption')
        with monkeypatch.context() as patch:
            patch.setattr(os, 'replace', interrupt)
            with pytest.raises(KeyboardInterrupt):
                transaction.commit()
        external = b'synthetic-external-update'
        injected = False
        def external_writer(source, destination):
            nonlocal injected
            replace(source, destination)
            if Path(destination) == second and not injected:
                (first / 'gateway.sqlite' if target_kind == 'new-account' else first).write_bytes(external)
                injected = True
        monkeypatch.setattr(os, 'replace', external_writer)
        with pytest.raises(ValueError, match='changed after interruption'):
            configuration.ConfigurationTransaction.recover(root, store=store, handle=handle)
        assert injected
        assert (first / 'gateway.sqlite' if target_kind == 'new-account' else first).read_bytes() == external
        assert second.read_bytes() == b'SECOND=old\n'
        journal = transaction.journal.read_bytes()
        state = json.loads(journal)
        assert state['status'] == 'applying' and len(state['entries']) == 2
        for entry in state['entries']:
            if entry['backup'] is not None:
                assert configuration.path_hash(root / entry['backup']) == entry['before']
        with pytest.raises(ValueError, match='changed after interruption'):
            configuration.ConfigurationTransaction.recover(root, store=store, handle=handle)
        assert transaction.journal.read_bytes() == journal


def test_new_account_replace_rechecks_directory_created_after_preflight(deployment, monkeypatch):
    root, _, _, _ = deployment
    staged, target = root / 'staged-account', root / 'account'
    staged.mkdir()
    (staged / 'gateway.sqlite').write_bytes(b'synthetic-new-account')
    with configuration.configuration_lock(root):
        transaction = configuration.ConfigurationTransaction(root)
        transaction.add_account(staged, target)
        persist = transaction.persist
        def external_writer():
            persist()
            if transaction.state['status'] == 'applying':
                target.mkdir()
        monkeypatch.setattr(transaction, 'persist', external_writer)
        with pytest.raises(ValueError, match='changed'):
            transaction.commit()
        assert target.is_dir() and not list(target.iterdir())
        assert (staged / 'gateway.sqlite').read_bytes() == b'synthetic-new-account'
        assert json.loads(transaction.journal.read_text())['status'] == 'applying'


def test_noop_preserves_journal_account_key_and_configuration(deployment, capsys):
    root, store, handle, account_id = deployment
    before = hashes(root, account_id)
    journal = (root / 'runtime/configuration-journal.json').read_bytes()
    assert configure(root, store, handle, port=8045, selected=('codex',)) is True
    assert '未更改' in capsys.readouterr().out
    assert hashes(root, account_id) == before
    assert (root / 'runtime/configuration-journal.json').read_bytes() == journal
    assert_identity(store, account_id)


@pytest.mark.parametrize('source', ['tokenEnv', 'token'])
@pytest.mark.parametrize('kind', ['hermes', 'dsh'])
def test_legacy_token_migration_preserves_host_files_and_account(deployment, tmp_path, monkeypatch, source, kind):
    root, store, handle, account_id = deployment
    legacy_agent(root, kind=kind, source=source)
    host = tmp_path / kind
    host.mkdir()
    host_file = host / '.env'
    host_file.write_text('UNRELATED=synthetic-preserved\n')
    monkeypatch.setenv('HERMES_HOME' if kind == 'hermes' else 'DSH_HOME', str(host))
    before = hashes(root, account_id)
    configure(root, store, handle)
    config = json.loads((root / 'core.json').read_text())
    agent = next(entry for entry in config['agents'] if entry['id'] == kind)
    assert agent['accounts'] == {'alice': 'native-alice'}
    assert 'token' not in agent and 'tokenEnv' not in agent
    secret = root / 'runtime/accounts' / agent['secretFile']
    assert secret.read_text() == TOKEN + '\n'
    assert host_file.read_text() == 'UNRELATED=synthetic-preserved\n'
    after = hashes(root, account_id)
    assert after['runtime/secrets/core-master-key'] == before['runtime/secrets/core-master-key']
    account_name = str(account_paths(root / 'runtime/accounts', account_id).database.relative_to(root))
    assert after[account_name] == before[account_name]
    assert_identity(store, account_id)


@pytest.mark.parametrize('contents', [
    'version: 1\nrefs: {UNRELATED_TOKEN: synthetic-preserved-value}\n',
    'version: 1\nrefs: # comment\n  UNRELATED_TOKEN: synthetic-preserved-value\n',
    'version: 1\nrefs: []\n',
    'version: 1\nrefs: [\n',
    'version: 1\nrefs: {UNRELATED_TOKEN: !unsupported synthetic-value}\n',
    'version: 1\nrefs: {A: first}\nrefs: {B: second}\n',
    'version: 1\nmetadata: &shared {UNRELATED_TOKEN: synthetic-preserved-value}\nrefs: *shared\n',
], ids=['flow', 'comment', 'non-mapping', 'malformed', 'unsupported-tag', 'duplicate', 'alias'])
def test_dsh_host_credentials_are_never_rewritten_by_core_configuration(deployment, tmp_path, monkeypatch, contents):
    root, store, handle, account_id = deployment
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    credential = dsh / '.credentials.yaml'
    credential.write_text(contents)
    env = dsh / '.env'
    env.write_text('UNRELATED_SETTING=synthetic-preserved-value\n')
    monkeypatch.setenv('DSH_HOME', str(dsh))
    legacy_agent(root, kind='dsh')
    original = hashes(root, account_id)
    configure(root, store, handle)
    assert credential.read_text() == contents
    assert env.read_text() == 'UNRELATED_SETTING=synthetic-preserved-value\n'
    agent = next(entry for entry in json.loads((root / 'core.json').read_text())['agents'] if entry['id'] == 'dsh')
    assert (root / 'runtime/accounts' / agent['secretFile']).read_text() == TOKEN + '\n'
    assert hashes(root, account_id)['runtime/secrets/core-master-key'] == original['runtime/secrets/core-master-key']
    assert_identity(store, account_id)


@pytest.mark.parametrize('name', ['.env', '.credentials.yaml'])
def test_unreadable_host_files_remain_untouched(deployment, tmp_path, monkeypatch, name):
    root, store, handle, _ = deployment
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    host = dsh / name
    host.write_text('synthetic-private-host-content\n')
    host.chmod(0o000)
    monkeypatch.setenv('DSH_HOME', str(dsh))
    legacy_agent(root, kind='dsh')
    try:
        configure(root, store, handle)
        assert host.stat().st_mode & 0o777 == 0
    finally:
        host.chmod(0o600)
    assert host.read_text() == 'synthetic-private-host-content\n'


def test_legacy_secret_file_requires_private_permissions_before_commit(deployment):
    root, store, handle, account_id = deployment
    legacy_agent(root)
    secret = root / 'runtime/secrets/adapter-tokens.env'
    secret.chmod(0o644)
    before = hashes(root, account_id)
    with pytest.raises(ValueError, match='0600'):
        configure(root, store, handle)
    assert hashes(root, account_id) == before
    assert secret.read_text().strip().endswith(TOKEN)
    assert json.loads((root / 'runtime/configuration-journal.json').read_text())['status'] == 'rolled_back'


def test_transaction_audit_records_intent_and_result(deployment):
    root, store, handle, account_id = deployment
    configure(root, store, handle)
    journal = json.loads((root / 'runtime/configuration-journal.json').read_text())
    rows = store.database.execute("SELECT action, actor, metadata FROM audit_events WHERE action LIKE 'configuration.%' ORDER BY id").fetchall()
    relevant = [(row['action'], row['actor'], json.loads(row['metadata'])) for row in rows if journal['id'] in row['metadata']]
    assert [row[0] for row in relevant] == ['configuration.intent', 'configuration.result']
    assert all(row[1] == account_id for row in relevant)
    assert relevant[-1][2]['status'] == 'committed'


def test_setup_service_menu_checks_instance_and_audits_restart(deployment, monkeypatch, capsys):
    root, store, handle, account_id = deployment
    unit = Path.home() / '.config/systemd/user/oai-core.service'
    observed = []
    def systemctl(args, **kwargs):
        observed.append(args)
        if 'show' in args:
            output = (f'LoadState=loaded\nFragmentPath={unit}\nWorkingDirectory={root}\n'
                      'ActiveState=active\nSubState=running\n')
        else:
            output = ''
        return subprocess.CompletedProcess(args, 0, stdout=output, stderr='')
    answers = iter(['4', '3', 'restart', '5'])
    monkeypatch.setattr(configuration.sys, 'platform', 'linux')
    monkeypatch.setattr(configuration, '_terminal_input', lambda prompt: next(answers))
    monkeypatch.setattr(configuration.subprocess, 'run', systemctl)
    configuration.run_setup({'configPath': str(root / 'core.json')}, store, handle)
    assert [args[2] for args in observed] == ['show', 'show', 'restart']
    assert '服务操作 restart 已执行' in capsys.readouterr().out
    rows = store.database.execute("SELECT action,actor,metadata FROM audit_events WHERE action LIKE 'service.%' ORDER BY id").fetchall()
    assert [row['action'] for row in rows] == ['service.intent', 'service.result']
    assert all(row['actor'] == account_id for row in rows)
    assert json.loads(rows[-1]['metadata'])['status'] == 'completed'


def test_setup_menu_keeps_existing_agent_and_saves_network(deployment, monkeypatch, capsys):
    root, store, handle, account_id = deployment
    answers = iter(['1', '127.0.0.1', '9045', '5'])
    monkeypatch.setattr(configuration, '_terminal_input', lambda prompt: next(answers))
    before = hashes(root, account_id)
    configuration.run_setup({'configPath': str(root / 'core.json')}, store, handle)
    output = capsys.readouterr().out
    assert '网络设置' in output and 'Agent 接入 [codex]' in output
    config = json.loads((root / 'core.json').read_text())
    assert config['listen']['port'] == 9045
    assert [agent['type'] for agent in config['agents']] == ['codex']
    after = hashes(root, account_id)
    assert after['runtime/secrets/core-master-key'] == before['runtime/secrets/core-master-key']
    assert_identity(store, account_id)


def test_setup_menu_supports_default_confirm_and_recovers_from_invalid_input(deployment, monkeypatch, capsys):
    root, store, handle, _ = deployment
    unit = Path.home() / '.config/systemd/user/oai-core.service'
    observed = []
    def systemctl(args, **kwargs):
        observed.append(args)
        if 'show' in args:
            output = (f'LoadState=loaded\nFragmentPath={unit}\nWorkingDirectory={root}\n'
                      'ActiveState=active\nSubState=running\n')
        else:
            output = ''
        return subprocess.CompletedProcess(args, 0, stdout=output, stderr='')
    # 1 -> invalid port 99999 (caught by run_setup) -> 4 -> 3 (restart) -> '' (default Enter confirm) -> 5 (exit)
    answers = iter(['1', '127.0.0.1', '99999', '4', '3', '', '5'])
    monkeypatch.setattr(configuration.sys, 'platform', 'linux')
    monkeypatch.setattr(configuration, '_terminal_input', lambda prompt: next(answers))
    monkeypatch.setattr(configuration.subprocess, 'run', systemctl)
    configuration.run_setup({'configPath': str(root / 'core.json')}, store, handle)
    output = capsys.readouterr().out
    assert '[提示]' in output
    assert '服务操作 restart 已执行' in output
    assert [args[2] for args in observed] == ['show', 'show', 'restart']

