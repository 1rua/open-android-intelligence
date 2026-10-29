"""Configuration saves preserve the last usable deployment on failure."""
from __future__ import annotations

import json
import os
from pathlib import Path
import runpy

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
HELPER = runpy.run_path(str(REPOSITORY / 'scripts' / 'core_setup.py'))


@pytest.fixture(autouse=True)
def isolated_hosts(tmp_path, monkeypatch):
    home = tmp_path / 'home'
    home.mkdir()
    for name in ('.agents', '.claude', '.codex', '.hermes', '.codebuddy', '.trae', '.dsh-cline', '.dsh'):
        (home / name).write_text('Unrelated skill deployment is outside this fixture.\n')
    monkeypatch.setenv('HOME', str(home))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'absent-hermes'))
    monkeypatch.setenv('DSH_HOME', str(tmp_path / 'absent-dsh'))
    for name in ('OAI_HERMES_SESSION_TOKEN', 'OAI_DSH_ADAPTER_TOKEN', 'OAI_INIT_ADMIN_PASSWORD'):
        monkeypatch.delenv(name, raising=False)


def test_missing_new_account_password_leaves_no_configuration_or_key(tmp_path):
    root = tmp_path / 'deployment'
    root.mkdir()
    with pytest.raises(ValueError, match='password'):
        HELPER['configure'](root, '127.0.0.1', 8045, 'alice', [], None)
    assert not (root / 'core.json').exists()
    assert not (root / 'runtime/secrets/adapter-tokens.env').exists()
    assert not (root / 'runtime/secrets/core-master-key').exists()
    assert not (root / 'start-core.sh').exists()


@pytest.fixture
def deployment(tmp_path, monkeypatch):
    root = tmp_path / 'deployment'
    root.mkdir()
    monkeypatch.setenv('OAI_INIT_ADMIN_PASSWORD', 'synthetic-original-password')
    HELPER['configure'](root, '127.0.0.1', 8045, 'alice', ['codex'], None)
    return root


def deployment_hashes(root):
    from hashlib import sha256
    names = ('core.json', 'start-core.sh', 'runtime/secrets/adapter-tokens.env',
             'runtime/secrets/core-master-key')
    return {name: sha256((root / name).read_bytes()).hexdigest() for name in names}


@pytest.mark.parametrize('operation', ['load_config', 'create_core'])
def test_core_preparation_failure_preserves_old_files(deployment, monkeypatch, operation):
    from unittest.mock import patch
    original = deployment_hashes(deployment)
    with patch('oai_core.server.' + operation, side_effect=ValueError('synthetic preparation failure')):
        with pytest.raises(ValueError):
            HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    assert deployment_hashes(deployment) == original


@pytest.mark.parametrize('target', ['core.json', 'runtime/secrets/adapter-tokens.env', 'start-core.sh'])
def test_failed_file_replacement_restores_the_complete_old_configuration(deployment, monkeypatch, target):
    original = deployment_hashes(deployment)
    replace = os.replace
    rejected = False

    def reject_once(source, destination):
        nonlocal rejected
        if Path(destination) == deployment / target and not rejected:
            rejected = True
            raise PermissionError('synthetic replace failure')
        return replace(source, destination)

    monkeypatch.setattr(os, 'replace', reject_once)
    with pytest.raises(PermissionError):
        HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    assert rejected
    assert deployment_hashes(deployment) == original
    journal = json.loads((deployment / 'runtime/configuration-journal.json').read_text())
    assert journal['status'] == 'rolled_back'


def interrupt_save(root):
    import subprocess
    import sys
    source = '''import os, runpy, sys
from pathlib import Path
helper = runpy.run_path('scripts/core_setup.py')
root = Path(sys.argv[1])
replace = os.replace
def interrupt(source, target):
    result = replace(source, target)
    if Path(target) == root / 'core.json':
        os._exit(86)
    return result
os.replace = interrupt
helper['configure'](root, '127.0.0.1', 9045, 'alice', ['dsh'], None)
'''
    result = subprocess.run([sys.executable, '-c', source, str(root)], cwd=REPOSITORY,
                            env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode == 86, result.stderr


def test_interrupted_save_recovers_before_a_failed_retry(deployment):
    from unittest.mock import patch
    original = deployment_hashes(deployment)
    interrupt_save(deployment)
    journal = json.loads((deployment / 'runtime/configuration-journal.json').read_text())
    assert journal['status'] == 'applying'
    with patch('oai_core.server.load_config', side_effect=ValueError('synthetic retry failure')):
        with pytest.raises(ValueError):
            HELPER['configure'](deployment, '127.0.0.1', 10045, 'alice', [], None)
    assert deployment_hashes(deployment) == original
    HELPER['configure'](deployment, '127.0.0.1', 10045, 'alice', [], None)
    assert json.loads((deployment / 'core.json').read_text())['listen']['port'] == 10045
    from contextlib import closing
    from oai_core.server import create_core, load_config
    with closing(create_core(load_config(deployment / 'core.json')).open_gateway_account('alice')) as account:
        assert account.credentials.verify_password('synthetic-original-password')


def test_corrupt_recovery_backup_fails_closed_without_more_writes(deployment):
    interrupt_save(deployment)
    journal = json.loads((deployment / 'runtime/configuration-journal.json').read_text())
    entry = next(entry for entry in journal['entries'] if entry['before'] is not None)
    (deployment / entry['backup']).write_bytes(b'synthetic corrupted backup')
    interrupted = deployment_hashes(deployment)
    with pytest.raises(ValueError, match='backup'):
        HELPER['configure'](deployment, '127.0.0.1', 10045, 'alice', [], None)
    assert deployment_hashes(deployment) == interrupted


@pytest.mark.parametrize('name', ['.env', '.credentials.yaml'])
def test_unreadable_dsh_configuration_is_preserved(deployment, tmp_path, monkeypatch, name):
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    (dsh / '.env').write_text('UNRELATED_SETTING=synthetic-preserved-value\n')
    (dsh / '.credentials.yaml').write_text('version: 1\nrefs:\n  UNRELATED_TOKEN: synthetic-preserved-value\n')
    original = deployment_hashes(deployment)
    contents = {path: path.read_bytes() for path in (dsh / '.env', dsh / '.credentials.yaml')}
    monkeypatch.setenv('DSH_HOME', str(dsh))
    unreadable = dsh / name
    unreadable.chmod(0o000)
    try:
        with pytest.raises(PermissionError):
            HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    finally:
        unreadable.chmod(0o600)
    assert deployment_hashes(deployment) == original
    assert all(path.read_bytes() == content for path, content in contents.items())


def test_reconfigure_syncs_preserved_mappings_port_and_host_root(deployment, tmp_path, monkeypatch):
    hermes = tmp_path / 'hermes'
    hermes.mkdir()
    custom_root = hermes / 'custom accounts'
    (hermes / '.env').write_text('OAI_HERMES_GATEWAY_ROOT=' + json.dumps(str(custom_root)) + '\n')
    monkeypatch.setenv('HERMES_HOME', str(hermes))
    HELPER['configure'](deployment, '127.0.0.1', 8045, 'alice', ['hermes'], 'native-alice')
    config = json.loads((deployment / 'core.json').read_text())
    entry = next(a for a in config['agents'] if a['type'] == 'hermes')
    entry['accounts']['bob'] = 'native-bob'
    entry['url'] = 'http://127.0.0.1:9792'
    (deployment / 'core.json').write_text(json.dumps(config))
    HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', [], None)
    values = dict(line.split('=', 1) for line in (hermes / '.env').read_text().splitlines() if '=' in line)
    assert json.loads(values['OAI_HERMES_ACCOUNTS']) == ['native-alice', 'native-bob']
    assert values['OAI_HERMES_SESSION_PORT'] == '9792'
    assert json.loads(values['OAI_HERMES_GATEWAY_ROOT']) == str(custom_root)


@pytest.mark.parametrize('script', ['setup.sh', 'install.sh'])
def test_shell_save_failure_restores_old_files_and_never_reports_success(deployment, tmp_path, monkeypatch, script):
    import shlex
    import subprocess
    import sys
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    (dsh / '.env').write_text('UNRELATED_SETTING=synthetic-preserved-value\n')
    (dsh / '.credentials.yaml').write_text('version: 1\nrefs:\n  UNRELATED_TOKEN: synthetic-preserved-value\n')
    monkeypatch.setenv('DSH_HOME', str(dsh))
    original = deployment_hashes(deployment)
    host_original = {path: path.read_bytes() for path in (dsh / '.env', dsh / '.credentials.yaml')}
    bootstrap = tmp_path / 'fail_replace.py'
    bootstrap.write_text('''import os, runpy, sys
from pathlib import Path
helper = runpy.run_path(sys.argv[1])
sys.argv = sys.argv[1:]
replace = os.replace
def reject(source, target):
    if Path(target).resolve() == (Path(os.environ['DSH_HOME']) / '.credentials.yaml').resolve():
        raise PermissionError('synthetic host credential replace failure')
    return replace(source, target)
os.replace = reject
helper['main']()
''')
    binary = deployment / '.venv/bin'
    binary.mkdir(parents=True, exist_ok=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(bootstrap)) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    source = f'''source scripts/{script}
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON="$TARGET_DIR/.venv/bin/python"
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=1
INSTALL_CODEX=0
INSTALL_HERMES_PLUGIN=0
INSTALL_DSH_PLUGIN=1
INSTALL_CODEX_CONFIG=0
prompt_read() {{ printf -v "$2" '%s' "${{3:-}}"; }}
prompt_password() {{ printf -v "$2" '%s' synthetic-original-password; }}
install_selected_agent_plugins() {{ printf 'UNEXPECTED_PLUGIN_INSTALL\\n'; }}
'''
    source += 'save_configuration_and_account\n' if script == 'setup.sh' else 'guide_initial_configuration\n'
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode != 0
    assert '配置与启动脚本已保存' not in result.stdout
    assert 'UNEXPECTED_PLUGIN_INSTALL' not in result.stdout
    assert deployment_hashes(deployment) == original
    assert all(path.read_bytes() == content for path, content in host_original.items())


def test_menu_account_selection_reaches_the_saved_configuration(deployment, tmp_path, monkeypatch):
    import shlex
    import subprocess
    import sys
    hermes = tmp_path / 'hermes'
    hermes.mkdir()
    monkeypatch.setenv('HERMES_HOME', str(hermes))
    source = f'''source scripts/setup.sh
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON={shlex.quote(sys.executable)}
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=0
INSTALL_CODEX=0
DETECTED_HERMES=1
DETECTED_DSH=0
DETECTED_CODEX=0
prompt_read() {{
    if [ "$2" = answer ]; then printf -v "$2" '%s' y
    elif [ "$2" = HERMES_ACCOUNT ]; then printf -v "$2" '%s' native-selected-in-menu
    else printf -v "$2" '%s' "${{3:-}}"; fi
}}
install_selected_agent_plugins() {{ return 0; }}
configure_agent_integrations
save_configuration_and_account
'''
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode in (0, 2), result.stderr
    config = json.loads((deployment / 'core.json').read_text())
    assert next(a for a in config['agents'] if a['type'] == 'hermes')['accounts'] == {'alice': 'native-selected-in-menu'}


def test_menu_no_keeps_existing_agent_and_describes_retention(deployment):
    import shlex
    import subprocess
    import sys
    source = f'''source scripts/setup.sh
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON={shlex.quote(sys.executable)}
INSTALL_CODEX=0
load_existing_config
show_menu
'''
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr
    assert 'Codex: 保留现有' in result.stdout
    config = json.loads((deployment / 'core.json').read_text())
    assert [a['type'] for a in config['agents']] == ['codex']


def test_optional_install_failure_is_reported_as_partial_completion(deployment):
    import shlex
    import subprocess
    import sys
    source = f'''source scripts/setup.sh
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON={shlex.quote(sys.executable)}
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=0
INSTALL_CODEX=0
configure_network() {{ return 0; }}
configure_account() {{ return 0; }}
configure_agent_integrations() {{ return 0; }}
configure_autostart() {{ return 0; }}
install_cli_wrapper() {{ return 0; }}
install_selected_agent_plugins() {{ return 2; }}
run_full_wizard
'''
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode == 2
    assert '部分完成' in result.stdout
    assert '完整配置向导已全部完成' not in result.stdout
    assert json.loads((deployment / 'core.json').read_text())['listen']['port'] == 9045


def test_interrupted_configuration_cannot_start_until_recovered(deployment):
    import shlex
    import subprocess
    import sys
    binary = deployment / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    (binary / 'oai').write_text('#!/usr/bin/env bash\nprintf "UNEXPECTED_SERVICE_START\\n"\n')
    (binary / 'oai').chmod(0o700)
    interrupt_save(deployment)
    result = subprocess.run([str(deployment / 'start-core.sh')], cwd=REPOSITORY,
                            env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode != 0
    assert 'UNEXPECTED_SERVICE_START' not in result.stdout
    assert 'journal' in result.stderr


def test_recovery_refuses_an_external_writer_after_interruption(deployment):
    interrupt_save(deployment)
    config = json.loads((deployment / 'core.json').read_text())
    config['external_setting'] = 'synthetic-external-writer'
    (deployment / 'core.json').write_text(json.dumps(config))
    externally_modified = deployment_hashes(deployment)
    with pytest.raises(ValueError, match='changed after interruption'):
        HELPER['configure'](deployment, '127.0.0.1', 10045, 'alice', [], None)
    assert deployment_hashes(deployment) == externally_modified


def test_configuration_lock_rejects_a_competing_wizard(deployment):
    import subprocess
    import sys
    original = deployment_hashes(deployment)
    with HELPER['configuration_lock'](deployment):
        result = subprocess.run([sys.executable, 'scripts/core_setup.py', '--root', str(deployment),
                                 '--host', '127.0.0.1', '--port', '9045', '--username', 'alice'],
                                cwd=REPOSITORY, env=os.environ.copy(), capture_output=True, text=True, timeout=20)
    assert result.returncode == 1
    assert '配置保存失败' in result.stderr
    assert deployment_hashes(deployment) == original


def test_existing_master_key_change_during_preparation_is_detected(deployment, monkeypatch):
    replace = os.replace
    key = deployment / 'runtime/secrets/core-master-key'
    original = deployment_hashes(deployment)
    changed = False

    def concurrent_key_writer(source, destination):
        nonlocal changed
        # Simulate a separate administrator replacing the key while candidates
        # are being prepared. The wizard must not commit against a stale key.
        if Path(destination).name == 'validate-config.json' and not changed:
            key.write_bytes(b'X' * 32)
            changed = True
        return replace(source, destination)

    monkeypatch.setattr(os, 'replace', concurrent_key_writer)
    with pytest.raises(ValueError, match='changed during preparation'):
        HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', [], None)
    assert changed
    assert (deployment / 'core.json').read_bytes()
    current = deployment_hashes(deployment)
    assert all(current[name] == value for name, value in original.items() if name != 'runtime/secrets/core-master-key')
    assert key.read_bytes() == b'X' * 32


@pytest.mark.parametrize('agents', [
    [{'id': 'duplicate', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/codex'},
     {'id': 'duplicate', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/other-codex'}],
    [{'id': 'unknown', 'type': 'bug09-uninstalled-type'}],
    [{'id': 'hermes', 'type': 'hermes', 'url': 'http://127.0.0.1:8792', 'tokenEnv': 'OAI_HERMES_SESSION_TOKEN',
      'accounts': {'alice': 'same-native-account', 'bob': 'same-native-account'}}],
    [{'id': 'hermes', 'type': 'hermes', 'url': 'http://127.0.0.1:8792', 'accounts': {'alice': 'native-alice'}}],
    [{'id': 'dsh', 'type': 'dsh', 'url': 'file:///synthetic-invalid-origin', 'tokenEnv': 'OAI_DSH_ADAPTER_TOKEN'}],
    [{'id': 'bad/id', 'type': 'codex', 'command': ['codex', 'app-server'], 'codexHome': 'runtime/codex'}],
], ids=['duplicate-id', 'unknown-type', 'non-one-to-one', 'missing-token-env', 'invalid-url', 'invalid-id'])
def test_real_startup_registry_rejects_invalid_agent_settings_before_commit(deployment, agents, monkeypatch):
    monkeypatch.setenv('OAI_HERMES_SESSION_TOKEN', 'synthetic-validation-token-123456789012345')
    monkeypatch.setenv('OAI_DSH_ADAPTER_TOKEN', 'synthetic-validation-token-123456789012345')
    config = json.loads((deployment / 'core.json').read_text())
    config['agents'] = agents
    (deployment / 'core.json').write_text(json.dumps(config))
    original = deployment_hashes(deployment)
    with pytest.raises(ValueError):
        HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', [], None)
    assert deployment_hashes(deployment) == original


@pytest.mark.parametrize('host', ['bad host', '127.0.0.1 ', '999.1.1.1', 'http://localhost', 'bad..host', '-bad-host'])
def test_invalid_listener_address_never_changes_active_files(deployment, host):
    original = deployment_hashes(deployment)
    with pytest.raises(ValueError):
        HELPER['configure'](deployment, host, 9045, 'alice', [], None)
    assert deployment_hashes(deployment) == original


def test_startup_registry_validation_uses_staged_tokens_without_starting_agent(deployment, monkeypatch):
    import asyncio
    async def no_process(*args, **kwargs):
        raise AssertionError('Configuration validation must not start an Agent')
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', no_process)
    monkeypatch.setenv('OAI_DSH_ADAPTER_TOKEN', 'synthetic-new-adapter-token-1234567890123')
    HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    assert os.environ['OAI_DSH_ADAPTER_TOKEN'] == 'synthetic-new-adapter-token-1234567890123'
    assert json.loads((deployment / 'core.json').read_text())['listen']['port'] == 9045


@pytest.mark.parametrize('phase', ['commit', 'rollback'])
def test_each_file_replace_preserves_a_writer_during_temporary_fsync(tmp_path, monkeypatch, phase):
    root = tmp_path / 'transaction'
    root.mkdir()
    target = root / 'settings.env'
    target.write_bytes(b'SETTING=old\n')
    external = b'SETTING=external-administrator-update\n'
    with HELPER['configuration_lock'](root):
        transaction = HELPER['ConfigurationTransaction'](root)
        transaction.add_file(target, b'SETTING=new\n')
        if phase == 'rollback':
            replace = os.replace
            def interrupt_after_replace(source, destination):
                replace(source, destination)
                if Path(destination) == target:
                    raise KeyboardInterrupt('synthetic interruption after replace')
            with monkeypatch.context() as interrupted:
                interrupted.setattr(os, 'replace', interrupt_after_replace)
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
        assert injected
        assert target.read_bytes() == external
        journal_before_retry = transaction.journal.read_bytes()
        state = json.loads(journal_before_retry)
        assert state['status'] == 'applying'
        assert len(state['entries']) == 1
        entry = state['entries'][0]
        assert (root / entry['backup']).read_bytes() == b'SETTING=old\n'
        assert (root / entry['staged']).read_bytes() == b'SETTING=new\n'
        with pytest.raises(ValueError, match='changed after interruption'):
            HELPER['ConfigurationTransaction'].recover(root)
        assert target.read_bytes() == external
        assert transaction.journal.read_bytes() == journal_before_retry


@pytest.mark.parametrize('target_kind', ['existing-file', 'new-file', 'new-account'])
def test_rollback_rechecks_each_target_after_restoring_another(tmp_path, monkeypatch, target_kind):
    root = tmp_path / 'transaction'
    root.mkdir()
    first, second = root / 'first', root / 'second.env'
    second.write_bytes(b'SECOND=old\n')
    if target_kind == 'existing-file':
        first.write_bytes(b'FIRST=old\n')
    with HELPER['configuration_lock'](root):
        transaction = HELPER['ConfigurationTransaction'](root)
        if target_kind == 'new-account':
            staged = root / 'staged-account'
            staged.mkdir()
            (staged / 'gateway.sqlite').write_bytes(b'synthetic-new-account')
            transaction.add_account(staged, first)
        else:
            transaction.add_file(first, b'FIRST=new\n')
        transaction.add_file(second, b'SECOND=new\n')
        replace = os.replace
        def interrupt_after_last_replace(source, destination):
            replace(source, destination)
            if Path(destination) == second:
                raise KeyboardInterrupt('synthetic interruption after last replace')
        with monkeypatch.context() as interrupted:
            interrupted.setattr(os, 'replace', interrupt_after_last_replace)
            with pytest.raises(KeyboardInterrupt):
                transaction.commit()
        external = b'synthetic-external-update'
        injected = False
        def external_writer_after_first_restore(source, destination):
            nonlocal injected
            replace(source, destination)
            if Path(destination) == second and not injected:
                (first / 'gateway.sqlite' if target_kind == 'new-account' else first).write_bytes(external)
                injected = True
        monkeypatch.setattr(os, 'replace', external_writer_after_first_restore)
        with pytest.raises(ValueError, match='changed after interruption'):
            HELPER['ConfigurationTransaction'].recover(root)
        assert injected
        assert (first / 'gateway.sqlite' if target_kind == 'new-account' else first).read_bytes() == external
        assert second.read_bytes() == b'SECOND=old\n'
        state = json.loads(transaction.journal.read_text())
        assert state['status'] == 'applying'
        assert len(state['entries']) == 2
        for entry in state['entries']:
            if entry['backup'] is not None:
                assert HELPER['path_hash'](root / entry['backup']) == entry['before']
        journal_before_retry = transaction.journal.read_bytes()
        with pytest.raises(ValueError, match='changed after interruption'):
            HELPER['ConfigurationTransaction'].recover(root)
        assert transaction.journal.read_bytes() == journal_before_retry


def test_new_account_replace_rechecks_a_directory_created_after_preflight(tmp_path, monkeypatch):
    root = tmp_path / 'transaction'
    root.mkdir()
    staged, target = root / 'staged-account', root / 'account'
    staged.mkdir()
    (staged / 'gateway.sqlite').write_bytes(b'synthetic-new-account')
    with HELPER['configuration_lock'](root):
        transaction = HELPER['ConfigurationTransaction'](root)
        transaction.add_account(staged, target)
        persist = transaction.persist
        def external_writer_after_preflight():
            persist()
            if transaction.state['status'] == 'applying':
                target.mkdir()
        monkeypatch.setattr(transaction, 'persist', external_writer_after_preflight)
        with pytest.raises(ValueError, match='changed'):
            transaction.commit()
        assert target.is_dir() and not list(target.iterdir())
        assert (staged / 'gateway.sqlite').read_bytes() == b'synthetic-new-account'
        assert json.loads(transaction.journal.read_text())['status'] == 'applying'


@pytest.mark.parametrize('credentials', [
    'version: 1\nrefs: {UNRELATED_TOKEN: synthetic-preserved-value}\nmetadata: {enabled: true}\n',
    'version: 1\nrefs: # existing credentials\n  UNRELATED_TOKEN: synthetic-preserved-value\nmetadata: {enabled: true}\n',
    'version: 1\nrefs:\n  UNRELATED_TOKEN: synthetic-preserved-value\nmetadata: {enabled: true}\n',
    '{"version": 1, "refs": {"UNRELATED_TOKEN": "synthetic-preserved-value"}, "metadata": {"enabled": true}}\n',
    'version: 1\nmetadata: &shared {UNRELATED_TOKEN: synthetic-preserved-value}\nrefs: *shared\n',
], ids=['flow-mapping', 'inline-comment', 'block-mapping', 'json-mapping', 'aliased-refs'])
def test_dsh_credentials_update_preserves_the_parsed_document(deployment, tmp_path, monkeypatch, credentials):
    yaml = pytest.importorskip('yaml')
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    (dsh / '.env').write_text('UNRELATED_SETTING=synthetic-preserved-value\n')
    credential_path = dsh / '.credentials.yaml'
    credential_path.write_text(credentials)
    expected = yaml.safe_load(credentials)
    monkeypatch.setenv('DSH_HOME', str(dsh))
    key = (deployment / 'runtime/secrets/core-master-key').read_bytes()
    from oai_core.gateway.account_paths import account_paths
    database = account_paths(deployment / 'runtime/accounts', 'alice').database
    account_before = database.read_bytes()
    HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    saved = yaml.safe_load(credential_path.read_text())
    token = HELPER['read_tokens'](deployment / 'runtime/secrets/adapter-tokens.env')['OAI_DSH_ADAPTER_TOKEN']
    expected['refs'] = {**expected['refs'], 'OAI_DSH_ADAPTER_TOKEN': token}
    assert saved == expected
    assert 'UNRELATED_SETTING=synthetic-preserved-value' in (dsh / '.env').read_text()
    assert (deployment / 'runtime/secrets/core-master-key').read_bytes() == key
    assert database.read_bytes() == account_before
    # Repeating the same configuration must not create duplicate sections or drift.
    HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', [], None)
    assert yaml.safe_load(credential_path.read_text()) == expected


@pytest.mark.parametrize('credentials', [
    'version: 1\nrefs: {FIRST: synthetic-first}\nrefs: {SECOND: synthetic-second}\n',
    'version: 1\nrefs: {UNRELATED_TOKEN: synthetic-first, UNRELATED_TOKEN: synthetic-second}\n',
    'version: 1\nrefs: []\n',
    'version: 1\nrefs: [\n',
    'version: 1\nrefs: {UNRELATED_TOKEN: !unsupported synthetic-value}\n',
    'version: 1\nrefs: {}\nmetadata: {owner: synthetic-first, owner: synthetic-second}\n',
], ids=['duplicate-root', 'duplicate-ref', 'non-mapping-refs', 'malformed-yaml', 'unsupported-tag', 'duplicate-unrelated-key'])
def test_unsafe_dsh_yaml_is_rejected_before_any_active_file_changes(deployment, tmp_path, monkeypatch, credentials):
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    (dsh / '.env').write_text('UNRELATED_SETTING=synthetic-preserved-value\n')
    credential_path = dsh / '.credentials.yaml'
    credential_path.write_text(credentials)
    monkeypatch.setenv('DSH_HOME', str(dsh))
    original = deployment_hashes(deployment)
    host_before = {path: path.read_bytes() for path in dsh.iterdir()}
    with pytest.raises(ValueError, match='DSH credential'):
        HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    assert deployment_hashes(deployment) == original
    assert all(path.read_bytes() == content for path, content in host_before.items())
    assert json.loads((deployment / 'runtime/configuration-journal.json').read_text())['status'] == 'rolled_back'


def test_existing_dsh_yaml_without_a_parser_is_preserved(deployment, tmp_path, monkeypatch):
    import sys
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    credential_path = dsh / '.credentials.yaml'
    credential_path.write_text('version: 1\nrefs: {UNRELATED_TOKEN: synthetic-preserved-value}\n')
    original = deployment_hashes(deployment)
    content = credential_path.read_bytes()
    monkeypatch.setenv('DSH_HOME', str(dsh))
    monkeypatch.setitem(sys.modules, 'yaml', None)
    with pytest.raises(ValueError, match='DSH credential.*PyYAML'):
        HELPER['configure'](deployment, '127.0.0.1', 9045, 'alice', ['dsh'], None)
    assert credential_path.read_bytes() == content
    assert not (dsh / '.env').exists()
    assert deployment_hashes(deployment) == original


@pytest.mark.parametrize('downloader', ['curl', 'wget'])
@pytest.mark.parametrize('download', ['failure-empty', 'failure-stale', 'success-empty', 'success-noop'])
def test_remote_helper_must_download_and_commit_before_shell_success(deployment, downloader, download):
    import shlex
    import subprocess
    import sys
    original = deployment_hashes(deployment)
    helper = deployment / '.tmp_core_setup.py'
    if download == 'failure-stale':
        helper.write_text('print("UNEXPECTED_STALE_HELPER_EXECUTION")\n')
    payload = 'printf \'# downloaded but does not configure anything\\n\' > "$output"' if download == 'success-noop' else ': > "$output"'
    if download == 'failure-stale':
        payload = ':'
    code = 23 if download.startswith('failure') else 0
    source = f'''source scripts/setup.sh
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
OAI_SETUP_SCRIPT_DIR=""
VENV_PYTHON={shlex.quote(sys.executable)}
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=0
INSTALL_CODEX=0
curl() {{ local output="$4"; {payload}; return {code}; }}
wget() {{ local output="$2"; {payload}; return {code}; }}
command() {{
    if [ "$1" = -v ] && [ "$2" = curl ] && [ {shlex.quote(downloader)} = wget ]; then return 1; fi
    builtin command "$@"
}}
trash-put() {{ return 0; }}
install_selected_agent_plugins() {{ printf 'UNEXPECTED_PLUGIN_INSTALL\\n'; return 0; }}
save_configuration_and_account
'''
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(),
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == 1, result.stdout + result.stderr
    assert '配置与启动脚本已保存' not in result.stdout
    assert 'UNEXPECTED_STALE_HELPER_EXECUTION' not in result.stdout
    assert 'UNEXPECTED_PLUGIN_INSTALL' not in result.stdout
    assert deployment_hashes(deployment) == original


@pytest.mark.parametrize('script', ['setup.sh', 'install.sh'])
@pytest.mark.parametrize('helper_result', ['noop', 'incomplete-record', 'changed-target', 'changed-permissions', 'rolled-back'])
def test_shell_requires_a_fresh_complete_transaction(deployment, script, helper_result):
    import shlex
    import subprocess
    import sys
    helper = deployment / 'scripts/core_setup.py'
    helper.parent.mkdir()
    if helper_result == 'noop':
        helper.write_text('# valid Python that does not save any configuration\n')
    else:
        helper.write_text(f'''import runpy, sys
from pathlib import Path
api = runpy.run_path('scripts/core_setup.py')
globals().update({{name: value for name, value in api.items() if not name.startswith('__')}})
if __name__ == '__main__':
    status = 0
    try:
        api['main']()
    except SystemExit as exc:
        status = exc.code
    if status not in (0, 2):
        raise SystemExit(status)
    root = Path(sys.argv[sys.argv.index('--root') + 1])
    journal = root / 'runtime/configuration-journal.json'
    state = json.loads(journal.read_text())
    if {helper_result!r} == 'incomplete-record':
        state['entries'] = [entry for entry in state['entries'] if entry['target'] != 'start-core.sh']
    elif {helper_result!r} == 'changed-target':
        (root / 'start-core.sh').write_text('# synthetic changed startup script')
    elif {helper_result!r} == 'changed-permissions':
        (root / 'runtime/secrets/adapter-tokens.env').chmod(0o644)
    else:
        state['status'] = 'rolled_back'
    journal.write_text(json.dumps(state))
    raise SystemExit(status)
''')
    binary = deployment / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    original = deployment_hashes(deployment)
    journal_before = (deployment / 'runtime/configuration-journal.json').read_bytes()
    source = f'''source scripts/{script}
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON={shlex.quote(sys.executable)}
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=0
INSTALL_CODEX=0
INSTALL_HERMES_PLUGIN=0
INSTALL_DSH_PLUGIN=0
INSTALL_CODEX_CONFIG=0
prompt_read() {{ printf -v "$2" '%s' "${{3:-}}"; }}
prompt_password() {{ printf -v "$2" '%s' synthetic-original-password; }}
install_selected_agent_plugins() {{ printf 'UNEXPECTED_PLUGIN_INSTALL\\n'; return 0; }}
'''
    source += 'save_configuration_and_account\n' if script == 'setup.sh' else 'guide_initial_configuration\n'
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(),
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == 1, result.stdout + result.stderr
    assert '配置与启动脚本已保存' not in result.stdout
    assert 'UNEXPECTED_PLUGIN_INSTALL' not in result.stdout
    if helper_result == 'noop':
        assert deployment_hashes(deployment) == original
        assert (deployment / 'runtime/configuration-journal.json').read_bytes() == journal_before
    else:
        assert json.loads((deployment / 'core.json').read_text())['listen']['port'] == 9045
        assert deployment_hashes(deployment)['runtime/secrets/core-master-key'] == original['runtime/secrets/core-master-key']


def test_successfully_downloaded_real_helper_commits_the_requested_configuration(deployment):
    import shlex
    import subprocess
    import sys
    original = deployment_hashes(deployment)
    old_id = json.loads((deployment / 'runtime/configuration-journal.json').read_text())['id']
    source = f'''source scripts/setup.sh
TARGET_DIR={shlex.quote(str(deployment))}
CONFIG_FILE="$TARGET_DIR/core.json"
OAI_SETUP_SCRIPT_DIR=""
VENV_PYTHON={shlex.quote(sys.executable)}
CFG_HOST=127.0.0.1
CFG_PORT=9045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-original-password
INSTALL_HERMES=0
INSTALL_DSH=0
INSTALL_CODEX=0
curl() {{ cp scripts/core_setup.py "$4"; }}
trash-put() {{ return 0; }}
install_selected_agent_plugins() {{ return 0; }}
save_configuration_and_account
'''
    result = subprocess.run(['bash', '-c', source], cwd=REPOSITORY, env=os.environ.copy(),
                            capture_output=True, text=True, timeout=20)
    assert result.returncode in (0, 2), result.stdout + result.stderr
    assert json.loads((deployment / 'core.json').read_text())['listen']['port'] == 9045
    state = json.loads((deployment / 'runtime/configuration-journal.json').read_text())
    assert state['status'] == 'committed' and state['id'] != old_id
    assert deployment_hashes(deployment)['runtime/secrets/core-master-key'] == original['runtime/secrets/core-master-key']



def test_missing_dsh_parser_cli_explains_remedy_without_printing_credentials(deployment, tmp_path, monkeypatch, capsys):
    import shlex
    import sys
    dsh = tmp_path / 'dsh'
    dsh.mkdir()
    credential_path = dsh / '.credentials.yaml'
    private_value = 'synthetic-private-credential-must-not-be-printed'
    credential_path.write_text('version: 1\nrefs: {UNRELATED_TOKEN: ' + private_value + '}\n')
    token = 'synthetic-adapter-token-must-not-be-printed-1234567890'
    monkeypatch.setenv('DSH_HOME', str(dsh))
    monkeypatch.setenv('OAI_DSH_ADAPTER_TOKEN', token)
    monkeypatch.setitem(sys.modules, 'yaml', None)
    monkeypatch.setattr(sys, 'argv', ['core_setup.py', '--root', str(deployment), '--host', '127.0.0.1',
                                    '--port', '9045', '--username', 'alice', '--agents', 'dsh'])
    original = deployment_hashes(deployment)
    content = credential_path.read_bytes()
    with pytest.raises(SystemExit) as result:
        HELPER['main']()
    assert result.value.code == 1
    output = capsys.readouterr()
    assert 'DSH_YAML_PARSER_REQUIRED' in output.err
    assert shlex.quote(sys.executable) + ' -m pip install PyYAML==6.0.3' in output.err
    assert '重试' in output.err
    assert private_value not in output.out + output.err
    assert token not in output.out + output.err
    assert 'synthetic-original-password' not in output.out + output.err
    assert credential_path.read_bytes() == content
    assert deployment_hashes(deployment) == original
    assert not (dsh / '.env').exists()
