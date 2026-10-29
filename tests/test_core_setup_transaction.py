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
