"""Installer regression tests using real administrator identity and safe host isolation.

Migration map: deleted Shell guide/menu/plugin/autostart helpers are covered by
scripts_runtime and configuration module behavior; shell tests invoke the actual
entrypoints.  Legacy adapter envs are migration inputs, never startup secrets.
"""
from contextlib import closing
import errno
import json
import os
from pathlib import Path
import pty
import select
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import termios
import time

import pytest

from oai_core import configuration, scripts_runtime
from oai_core.gateway.account_paths import account_paths
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.management import LocalRecovery
from oai_core.server import create_core, load_config

REPOSITORY = Path(__file__).resolve().parents[1]
PASSWORD = 'synthetic-installer-password'
TOKEN = 'synthetic-bridge-token-contains-quote-and-dollar-1234567890'


@pytest.fixture(autouse=True)
def isolated_host(tmp_path, monkeypatch):
    home = tmp_path / 'home'
    home.mkdir()
    for name in ('.agents', '.claude', '.codex', '.hermes', '.codebuddy', '.trae', '.dsh-cline', '.dsh'):
        (home / name).write_text('Unrelated host configuration.\n')
    monkeypatch.setenv('HOME', str(home))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'absent-hermes'))
    monkeypatch.setenv('DSH_HOME', str(tmp_path / 'absent-dsh'))
    for name in ('OAI_HERMES_SESSION_TOKEN', 'OAI_DSH_ADAPTER_TOKEN', 'OAI_INIT_ADMIN_PASSWORD'):
        monkeypatch.delenv(name, raising=False)


def bootstrap(root):
    root.mkdir(parents=True, exist_ok=True)
    database, storage = root / 'runtime/security/identity.sqlite', root / 'runtime/accounts'
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
    handle = store.begin_management('alice', PASSWORD)
    LocalRecovery(store).register(handle, confirmed=True)
    return store, handle, account['accountId']


def configure(root, store, handle, port=8045, selected=()):
    return configuration.configure(root, '127.0.0.1', port, 'alice', list(selected), None,
                                   store=store, handle=handle)


@pytest.fixture
def deployment(tmp_path):
    root = tmp_path / 'deployment'
    store, handle, account_id = bootstrap(root)
    configure(root, store, handle, selected=('codex',))
    core = create_core(load_config(root / 'core.json'))
    with closing(core.open_gateway_account(account_id)) as account:
        assert account.store is not None
    core.identity_store.close()
    yield root, store, handle, account_id
    store.close()


def legacy_agent(root, token=TOKEN, *, source='tokenEnv'):
    path = root / 'core.json'
    config = json.loads(path.read_text())
    agent = {'id': 'hermes', 'type': 'hermes', 'url': 'http://127.0.0.1:8792',
             'accounts': {'alice': 'native-alice'}, source: 'OAI_HERMES_SESSION_TOKEN' if source == 'tokenEnv' else token}
    config['agents'].append(agent)
    path.write_text(json.dumps(config))
    if source == 'tokenEnv' and token is not None:
        configuration.private_write(root / 'runtime/secrets/adapter-tokens.env',
                                    'OAI_HERMES_SESSION_TOKEN=' + shlex.quote(token) + '\n')
    return config


@pytest.mark.parametrize('name', ['install.sh', 'setup.sh'])
def test_script_syntax(name):
    result = subprocess.run(['bash', '-n', str(REPOSITORY / 'scripts' / name)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_install_script_header_contains_curl_command():
    content = (REPOSITORY / 'scripts/install.sh').read_text(encoding='utf-8')
    assert 'curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash' in content


def prepare_source_root(root):
    metadata = root / 'oai-core/pyproject.toml'
    metadata.parent.mkdir(parents=True)
    shutil.copy2(REPOSITORY / 'oai-core/pyproject.toml', metadata)
    shutil.copytree(REPOSITORY / 'oai-core/src', root / 'oai-core/src',
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    scripts = root / 'scripts'
    scripts.mkdir()
    shutil.copy2(REPOSITORY / 'scripts/installer.py', scripts / 'installer.py')


@pytest.mark.parametrize('name', ['install.sh', 'setup.sh'])
def test_entrypoint_no_terminal_aborts_without_writes(name, tmp_path, monkeypatch):
    root = tmp_path / 'no-terminal'
    root.mkdir()
    prepare_source_root(root)
    monkeypatch.setenv('OAI_DIR', str(root))
    result = subprocess.run(['bash', str(REPOSITORY / 'scripts' / name), '--dir', str(root)],
                            stdin=subprocess.DEVNULL, start_new_session=True, capture_output=True, text=True, timeout=5)
    assert result.returncode != 0
    assert '终端' in result.stderr
    assert not (root / 'core.json').exists()
    assert not (root / 'runtime').exists()
    assert not (root / '.venv').exists()


def test_setup_refuses_untrusted_missing_installer(tmp_path):
    script = tmp_path / 'setup.sh'
    script.write_bytes((REPOSITORY / 'scripts/setup.sh').read_bytes())
    result = subprocess.run(['bash', str(script)], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=5)
    assert result.returncode != 0
    assert '未找到可信的已安装管理入口' in result.stderr
    assert {item.name for item in tmp_path.iterdir()} == {'home', 'setup.sh'}


def test_existing_install_without_terminal_fails_before_recovery_writes(tmp_path):
    root = tmp_path / 'legacy'
    root.mkdir()
    config = root / 'core.json'
    config.write_text('{"legacy":"sentinel"}')
    result = subprocess.run(['bash', str(REPOSITORY / 'scripts/install.sh'), '--dir', str(root)],
                            stdin=subprocess.DEVNULL, start_new_session=True, capture_output=True, text=True, timeout=5)
    assert result.returncode != 0
    assert '终端' in result.stderr
    assert not (root / 'oai-core').exists()
    assert config.read_text() == '{"legacy":"sentinel"}'
    assert not (root / '.venv').exists()


def test_reconfigure_preserves_account_original_key_custom_paths_and_mappings(deployment):
    root, store, handle, account_id = deployment
    initial = legacy_agent(root)
    initial['unrelatedAdminSetting'] = {'enabled': True}
    initial['tlsSpkiSha256'] = 'sha256:' + '1' * 64
    (root / 'core.json').write_text(json.dumps(initial))
    key = root / initial['masterKeyFile']
    key_before = key.read_bytes()
    account = account_paths(root / initial['storageRoot'], account_id).database
    account_before = account.read_bytes()
    configure(root, store, handle, port=9045)
    saved = json.loads((root / 'core.json').read_text())
    assert saved['storageRoot'] == initial['storageRoot']
    assert saved['masterKeyFile'] == initial['masterKeyFile']
    assert saved['tlsSpkiSha256'] == initial['tlsSpkiSha256']
    assert saved['unrelatedAdminSetting'] == {'enabled': True}
    agent = next(entry for entry in saved['agents'] if entry['id'] == 'hermes')
    assert agent['accounts'] == {'alice': 'native-alice'}
    assert (root / saved['storageRoot'] / agent['secretFile']).read_text() == TOKEN + '\n'
    assert key.read_bytes() == key_before and account.read_bytes() == account_before
    assert any(json.loads(path.read_text()) == initial for path in (root / 'runtime/configuration-backups').glob('*.json'))
    assert store.authenticate('alice', PASSWORD)['accountId'] == account_id


def test_start_script_does_not_source_legacy_secrets(deployment):
    root, _, _, _ = deployment
    private = root / 'runtime/secrets/adapter-tokens.env'
    sentinel = root / 'must-not-execute'
    configuration.private_write(private, 'touch ' + shlex.quote(str(sentinel)) + '\n')
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    (binary / 'oai').write_text('#!/usr/bin/env python3\nimport json,os,sys\nprint(json.dumps({"token":os.environ.get("OAI_HERMES_SESSION_TOKEN"),"args":sys.argv[1:]}))\n')
    (binary / 'oai').chmod(0o700)
    result = subprocess.run([str(root / 'start-core.sh')], env=os.environ.copy(), capture_output=True, text=True, check=True)
    assert json.loads(result.stdout) == {'token': None, 'args': ['serve', '--config', str(root / 'core.json')]}
    assert not sentinel.exists()
    assert stat.S_IMODE((root / 'start-core.sh').stat().st_mode) == 0o700
    assert stat.S_IMODE((root / 'core.json').stat().st_mode) == 0o600


def test_insecure_and_symlink_legacy_secret_files_are_rejected(tmp_path):
    external = tmp_path / 'external'
    external.write_text('OAI_HERMES_SESSION_TOKEN=' + TOKEN + '\n')
    external.chmod(0o644)
    with pytest.raises(ValueError, match='0600'):
        configuration.read_tokens(external)
    external.chmod(0o600)
    linked = tmp_path / 'linked'
    try:
        linked.symlink_to(external)
    except (NotImplementedError, PermissionError, FileNotFoundError):
        # Some managed filesystems cannot create links. Test the real O_NOFOLLOW
        # rejection against the existing interpreter link without writing to it.
        linked = Path(sys.base_prefix) / 'bin' / 'python3'
        assert linked.is_symlink()
    with pytest.raises(OSError) as failure:
        configuration.read_tokens(linked)
    assert failure.value.errno == errno.ELOOP
    with pytest.raises(ValueError, match='symlink'):
        configuration.private_write(linked, 'ignored')
    assert external.read_text() == 'OAI_HERMES_SESSION_TOKEN=' + TOKEN + '\n'


def test_existing_accounts_with_missing_key_are_not_rekeyed(deployment):
    root, store, handle, account_id = deployment
    config_path = root / 'core.json'
    config = json.loads(config_path.read_text())
    config['masterKeyFile'] = 'runtime/secrets/missing-key'
    config_path.write_text(json.dumps(config))
    original = config_path.read_bytes()
    account = account_paths(root / config['storageRoot'], account_id).database
    account_before = account.read_bytes()
    with pytest.raises(ValueError, match='original master key'):
        configure(root, store, handle, port=9045)
    assert config_path.read_bytes() == original and account.read_bytes() == account_before
    assert not (root / config['masterKeyFile']).exists()


def test_core_only_does_not_guess_agents_or_create_bridge_secret(tmp_path):
    root = tmp_path / 'core-only'
    store, handle, _ = bootstrap(root)
    try:
        configure(root, store, handle)
        assert json.loads((root / 'core.json').read_text())['agents'] == []
        assert not (root / 'runtime/secrets/adapter-tokens.env').exists()
        assert not (root / 'runtime/accounts/.agent-secrets').exists()
    finally:
        store.close()


def test_legacy_start_token_migrates_without_execution_or_rotation(deployment):
    root, store, handle, account_id = deployment
    legacy_agent(root, token=None)
    sentinel = root / 'must-not-be-executed'
    (root / 'start-core.sh').write_text('export OAI_HERMES_SESSION_TOKEN="${OAI_HERMES_SESSION_TOKEN:-' + TOKEN + '}"\n'
                                        + 'touch ' + shlex.quote(str(sentinel)) + '\n')
    original_key = (root / 'runtime/secrets/core-master-key').read_bytes()
    configure(root, store, handle, port=9045)
    config = json.loads((root / 'core.json').read_text())
    agent = next(entry for entry in config['agents'] if entry['id'] == 'hermes')
    assert (root / config['storageRoot'] / agent['secretFile']).read_text() == TOKEN + '\n'
    assert TOKEN not in (root / 'start-core.sh').read_text() and not sentinel.exists()
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == original_key
    assert store.authenticate('alice', PASSWORD)['accountId'] == account_id


def test_bridge_token_is_not_taken_from_process_environment(deployment, monkeypatch):
    root, store, handle, account_id = deployment
    legacy_agent(root, token=None)
    monkeypatch.setenv('OAI_HERMES_SESSION_TOKEN', TOKEN)
    before = (root / 'core.json').read_bytes()
    with pytest.raises(ValueError, match='will not be silently rotated'):
        configure(root, store, handle, port=9045)
    assert (root / 'core.json').read_bytes() == before
    assert not (root / 'runtime/accounts/.agent-secrets/agent-hermes.secret').exists()
    assert store.authenticate('alice', PASSWORD)['accountId'] == account_id


def test_unrecoverable_legacy_token_is_not_rotated(deployment):
    root, store, handle, _ = deployment
    legacy_agent(root, token=None)
    config = (root / 'core.json').read_bytes()
    with pytest.raises(ValueError, match='will not be silently rotated'):
        configure(root, store, handle, port=9045)
    assert (root / 'core.json').read_bytes() == config
    assert not (root / 'runtime/accounts/.agent-secrets/agent-hermes.secret').exists()


def test_unregistered_agent_cannot_be_added_by_type(deployment):
    root, store, handle, _ = deployment
    original = (root / 'core.json').read_bytes()
    with pytest.raises(ValueError, match='discovered and registered'):
        configure(root, store, handle, port=9045, selected=('hermes',))
    assert (root / 'core.json').read_bytes() == original


def test_plugin_install_only_for_selected_agents(deployment, monkeypatch):
    root, store, handle, _ = deployment
    calls = []
    monkeypatch.setattr(scripts_runtime.shutil, 'which', lambda name: '/synthetic/' + name)
    monkeypatch.setattr(scripts_runtime.subprocess, 'run', lambda args, **kwargs: calls.append((args, kwargs)))
    assert scripts_runtime._install_agents(root, store, handle) is True
    assert calls == []
    config = json.loads((root / 'core.json').read_text())
    config['agents'].append({'id': 'hermes', 'type': 'hermes'})
    (root / 'core.json').write_text(json.dumps(config))
    assert scripts_runtime._install_agents(root, store, handle) is True
    assert calls[0][0] == ['hermes', 'plugins', 'install', './integrations/hermes-session-adapter', '--enable']
    assert calls[0][1]['cwd'] == root


def test_dsh_plugin_install_uses_profile_confirmation_instead_of_fake_cli(deployment, monkeypatch, capsys):
    root, store, handle, _ = deployment
    config = json.loads((root / 'core.json').read_text())
    config['agents'].append({'id': 'dsh', 'type': 'dsh'})
    (root / 'core.json').write_text(json.dumps(config))
    monkeypatch.setattr(scripts_runtime.shutil, 'which', lambda _: '/synthetic/command')
    monkeypatch.setattr(scripts_runtime.subprocess, 'run', lambda *_args, **_kwargs: pytest.fail('DSH requires an explicit profile before installation'))
    assert scripts_runtime._install_agents(root, store, handle) is False
    assert 'setup' in capsys.readouterr().out


def test_missing_selected_plugin_reports_partial(deployment, monkeypatch, capsys):
    root, store, handle, _ = deployment
    config = json.loads((root / 'core.json').read_text())
    config['agents'].append({'id': 'hermes', 'type': 'hermes'})
    (root / 'core.json').write_text(json.dumps(config))
    monkeypatch.setattr(scripts_runtime.shutil, 'which', lambda name: None)
    assert scripts_runtime._install_agents(root, store, handle) is False
    assert '尚未连接' in capsys.readouterr().out


@pytest.mark.parametrize('platform', ['linux', 'darwin', 'win32'])
def test_autostart_configuration_uses_real_admin_and_platform_files(deployment, tmp_path, monkeypatch, platform):
    root, store, handle, _ = deployment
    home = Path(os.environ['HOME'])
    monkeypatch.setattr(scripts_runtime.sys, 'platform', platform)
    monkeypatch.setenv('APPDATA', str(tmp_path / 'appdata'))
    monkeypatch.setattr(scripts_runtime.shutil, 'which', lambda name: None)
    scripts_runtime._configure_service(root, store, handle)
    if platform == 'linux':
        files = [home / '.config/systemd/user/oai-core.service', home / '.config/autostart/oai-core.desktop']
        assert f'WorkingDirectory={root}\n' in files[0].read_text()
        assert f'WorkingDirectory="{root}"' not in files[0].read_text()
    elif platform == 'darwin':
        files = [home / 'Library/LaunchAgents/com.openandroidintelligence.core.plist']
    else:
        files = [tmp_path / 'appdata/Microsoft/Windows/Start Menu/Programs/Startup/oai-core.vbs']
    assert all(path.is_file() and str(root / 'start-core.sh') in path.read_text() for path in files)


def test_cli_wrapper_is_owned_and_does_not_rewrite_shell_profiles(deployment):
    root, store, handle, _ = deployment
    home = Path(os.environ['HOME'])
    profile = home / '.bashrc'
    profile.write_text('# existing profile\n')
    scripts_runtime._integrate_cli(root, store, handle)
    wrapper = home / '.local/bin/oai'
    assert wrapper.is_file() and stat.S_IMODE(wrapper.stat().st_mode) == 0o755
    assert os.path.relpath(root, wrapper.parent) in wrapper.read_text() and 'start-core.sh' in wrapper.read_text()
    assert str(root) not in wrapper.read_text()
    assert profile.read_text() == '# existing profile\n'


def test_authentication_occurs_before_pip_or_host_changes(deployment, monkeypatch):
    root, store, _, _ = deployment
    key_before = (root / 'runtime/secrets/core-master-key').read_bytes()
    config_before = (root / 'core.json').read_bytes()
    calls = []
    monkeypatch.setattr(scripts_runtime, '_input', lambda prompt: 'alice')
    monkeypatch.setattr(scripts_runtime, 'secret_input', lambda prompt: 'synthetic-wrong-password')
    monkeypatch.setattr(scripts_runtime.subprocess, 'run', lambda *args, **kwargs: calls.append(args))
    with pytest.raises(Exception) as failure:
        scripts_runtime.install(root)
    assert getattr(failure.value, 'code', '') in {'AUTHENTICATION_FAILED', 'AUTH_FAILED', 'INVALID_CREDENTIALS'}
    assert calls == []
    assert (root / 'core.json').read_bytes() == config_before
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == key_before
    assert not (Path(os.environ['HOME']) / '.local/bin/oai').exists()
    assert store.authenticate('alice', PASSWORD)


def test_installer_reauthenticates_before_pip_and_integrates_cli(deployment, monkeypatch):
    root, store, _, account_id = deployment
    root_python = root / '.venv/bin/python'
    root_python.parent.mkdir(parents=True)
    root_python.write_text('#!/bin/sh\\nexit 1\\n')
    root_python.chmod(0o700)
    calls = []
    answers = iter(['alice', 'n'])
    monkeypatch.setattr(scripts_runtime, '_input', lambda prompt: next(answers))
    monkeypatch.setattr(scripts_runtime, 'secret_input', lambda prompt: PASSWORD)
    monkeypatch.setattr(scripts_runtime.subprocess, 'run', lambda args, **kwargs: calls.append((args, kwargs)))
    original_key = (root / 'runtime/secrets/core-master-key').read_bytes()
    assert scripts_runtime.install(root) == 0
    assert [entry[0][1:3] for entry in calls] == [['-m', 'pip'], ['-m', 'pip']]
    assert all(entry[1]['cwd'] == root and entry[1]['check'] is True for entry in calls)
    assert (Path(os.environ['HOME']) / '.local/bin/oai').is_file()
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == original_key
    assert store.authenticate('alice', PASSWORD)['accountId'] == account_id


def test_python_selector_uses_eligible_interpreter(tmp_path, monkeypatch):
    launchers = tmp_path / 'commands with spaces'
    launchers.mkdir()
    calls = tmp_path / 'calls'
    for name in ('python3', 'python'):
        path = launchers / name
        if name == 'python3':
            path.write_text('#!/bin/sh\nexit 1\n')
        else:
            path.write_text('#!/bin/sh\nprintf "selected\\n" >> ' + shlex.quote(str(calls)) + '\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
        path.chmod(0o700)
    monkeypatch.setenv('PATH', str(launchers) + os.pathsep + os.environ['PATH'])
    script = 'source ' + shlex.quote(str(REPOSITORY / 'scripts/install.sh')) + '\nselect_python\nprintf "%s\\n" "$PYTHON_BIN"\n'
    result = subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == 'python'
    assert calls.read_text().splitlines() == ['selected']


def run_in_terminal(argv, replies, *, stdin_pipe=False, timeout=12):
    """Drive the real entrypoint over a controlling PTY without logging secrets."""
    master, slave = pty.openpty()
    attrs = termios.tcgetattr(slave)
    attrs[3] |= termios.ECHO | termios.ICANON
    termios.tcsetattr(slave, termios.TCSANOW, attrs)
    read_fd, write_fd = os.pipe() if stdin_pipe else (None, None)
    pid = os.fork()
    if pid == 0:
        try:
            os.close(master)
            os.login_tty(slave)
            if stdin_pipe:
                os.close(write_fd)
                os.dup2(read_fd, 0)
                os.close(read_fd)
            os.execvpe(argv[0], argv, os.environ.copy())
        finally:
            os._exit(127)
    output = bytearray()
    status = None
    index = 0
    cursor = 0
    try:
        if stdin_pipe:
            os.close(read_fd)
            read_fd = None
            os.close(write_fd)
            write_fd = None
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if select.select([master], [], [], 0.05)[0]:
                try:
                    output.extend(os.read(master, 4096))
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
            if index < len(replies):
                prompt, reply, secret = replies[index]
                position = output.find(prompt, cursor)
                if position >= 0 and (not secret or not termios.tcgetattr(slave)[3] & termios.ECHO):
                    os.write(master, reply)
                    cursor = position + len(prompt)
                    index += 1
            waited, child_status = os.waitpid(pid, os.WNOHANG)
            if waited:
                status = child_status
                break
        if status is None:
            pytest.fail('交互入口超过时限未退出')
        assert index == len(replies), bytes(output)
        assert termios.tcgetattr(slave)[3] & termios.ECHO
        return os.waitstatus_to_exitcode(status), bytes(output)
    finally:
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        for descriptor in (read_fd, write_fd, master, slave):
            if descriptor is not None:
                os.close(descriptor)


def test_password_eof_aborts_real_setup_and_restores_echo(deployment):
    root, _, _, _ = deployment
    prepare_source_root(root)
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    before = (root / 'core.json').read_bytes()
    code, output = run_in_terminal(['bash', str(REPOSITORY / 'scripts/setup.sh'), '--dir', str(root)],
                                   [('管理员账号'.encode(), b'alice\n', False),
                                    ('管理员密码'.encode(), b'\x04', True)])
    assert code != 0 and PASSWORD.encode() not in output
    assert (root / 'core.json').read_bytes() == before


def test_shell_install_rejects_wrong_password_before_pip(deployment):
    root, _, _, account_id = deployment
    prepare_source_root(root)
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    python = binary / 'python'
    python.write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    python.chmod(0o700)
    config_before = (root / 'core.json').read_bytes()
    key_before = (root / 'runtime/secrets/core-master-key').read_bytes()
    code, output = run_in_terminal(['bash', str(REPOSITORY / 'scripts/install.sh'), '--dir', str(root)],
                                   [('管理员账号'.encode(), b'alice\n', False),
                                    ('管理员密码'.encode(), b'synthetic-wrong-password\n', True)])
    assert code != 0
    assert b'synthetic-wrong-password' not in output
    assert (root / 'core.json').read_bytes() == config_before
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == key_before
    assert account_paths(root / 'runtime/accounts', account_id).database.is_file()
    assert not (Path(os.environ['HOME']) / '.local/bin/oai').exists()


def test_shell_setup_service_restart_requires_confirmation_and_audits(deployment, monkeypatch):
    """The public entrypoint must use the same audited service menu as Core."""
    root, _, _, account_id = deployment
    prepare_source_root(root)
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    python = binary / 'python'
    python.write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    python.chmod(0o700)
    unit = Path.home() / '.config/systemd/user/oai-core.service'
    commands = root / 'fake-commands'
    commands.mkdir()
    events = root / 'systemctl-events'
    systemctl = commands / 'systemctl'
    systemctl.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> ' + shlex.quote(str(events)) + '\n'
                         + 'if [ "$2" = show ]; then\n'
                         + '  printf "LoadState=loaded\\nFragmentPath=%s\\nWorkingDirectory=%s\\nActiveState=active\\nSubState=running\\n" '
                         + shlex.quote(str(unit)) + ' ' + shlex.quote(str(root)) + '\nfi\n')
    systemctl.chmod(0o700)
    monkeypatch.setenv('PATH', str(commands) + os.pathsep + os.environ['PATH'])
    code, output = run_in_terminal(['bash', str(REPOSITORY / 'scripts/setup.sh'), '--dir', str(root)],
                                   [('管理员账号'.encode(), b'alice\n', False),
                                    ('管理员密码'.encode(), (PASSWORD + '\n').encode(), True),
                                    ('请选择'.encode(), b'4\n', False),
                                    ('3. 重启'.encode(), b'3\n', False),
                                    ('确认服务操作'.encode(), b'restart\n', False),
                                    ('请选择'.encode(), b'5\n', False)])
    assert code == 0, output
    assert PASSWORD.encode() not in output
    assert '服务操作 restart 已执行'.encode() in output
    commands_run = events.read_text().splitlines()
    assert commands_run[-1] == '--user restart oai-core.service'
    assert all(command.startswith('--user show oai-core.service ') for command in commands_run[:-1])
    assert account_paths(root / 'runtime/accounts', account_id).database.is_file()
    store = IdentityStore(root / 'runtime/security/identity.sqlite')
    try:
        rows = store.database.execute("SELECT action,actor,metadata FROM audit_events WHERE action LIKE 'service.%' ORDER BY id").fetchall()
        assert [row['action'] for row in rows] == ['service.intent', 'service.result']
        assert all(row['actor'] == account_id for row in rows)
        assert json.loads(rows[-1]['metadata'])['status'] == 'completed'
    finally:
        store.close()


def test_setup_reads_controlling_tty_when_stdin_is_pipe(deployment):
    root, _, _, account_id = deployment
    prepare_source_root(root)
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    (binary / 'python').write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / 'python').chmod(0o700)
    code, output = run_in_terminal(['bash', str(REPOSITORY / 'scripts/setup.sh'), '--dir', str(root)],
                                   [('管理员账号'.encode(), b'alice\n', False),
                                    ('管理员密码'.encode(), (PASSWORD + '\n').encode(), True),
                                    ('请选择'.encode(), b'5\n', False)], stdin_pipe=True)
    assert code == 0, output
    assert PASSWORD.encode() not in output
    assert '网络设置'.encode() in output
    assert account_paths(root / 'runtime/accounts', account_id).database.is_file()


@pytest.mark.parametrize('user_input,expected', [
    (b'\n', 'ACCEPTED'),
    (b'y\n', 'ACCEPTED'),
    (b'Y\n', 'ACCEPTED'),
    (b'yes\n', 'ACCEPTED'),
    (b'\x1b[B\n', 'REJECTED'),
    (b'\x1b[B\x1b[A\n', 'ACCEPTED'),
    (b'n\n', 'REJECTED'),
    (b'INSTALL\n', 'REJECTED'),
    (b'no\n', 'REJECTED'),
])
def test_install_sh_read_terminal_default_yes_and_yn(user_input, expected):
    runner = [
        'bash', '-c',
        'source ' + shlex.quote(str(REPOSITORY / 'scripts/install.sh')) + '\n'
        'if read_terminal "确认？(y/n) [y]: "; then echo ACCEPTED; else echo REJECTED; fi\n'
    ]
    code, output = run_in_terminal(runner, [('确认？'.encode(), user_input, False)])
    assert code == 0
    assert expected.encode() in output


def test_shell_setup_supports_arrow_key_navigation(deployment, monkeypatch):
    """Setup menus and confirmations must support ↑/↓ arrow keys without typing digits."""
    root, _, _, account_id = deployment
    prepare_source_root(root)
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    python = binary / 'python'
    python.write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    python.chmod(0o700)
    unit = Path.home() / '.config/systemd/user/oai-core.service'
    commands = root / 'fake-commands'
    commands.mkdir()
    events = root / 'systemctl-events'
    systemctl = commands / 'systemctl'
    systemctl.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> ' + shlex.quote(str(events)) + '\n'
                         + 'if [ "$2" = show ]; then\n'
                         + '  printf "LoadState=loaded\\nFragmentPath=%s\\nWorkingDirectory=%s\\nActiveState=active\\nSubState=running\\n" '
                         + shlex.quote(str(unit)) + ' ' + shlex.quote(str(root)) + '\nfi\n')
    systemctl.chmod(0o700)
    monkeypatch.setenv('PATH', str(commands) + os.pathsep + os.environ['PATH'])
    code, output = run_in_terminal(
        ['bash', str(REPOSITORY / 'scripts/setup.sh'), '--dir', str(root)],
        [
            ('管理员账号'.encode(), b'alice\n', False),
            ('管理员密码'.encode(), (PASSWORD + '\n').encode(), True),
            # 主菜单：连按 3 次 ↓ 切换到“4. 服务管理”并回车
            ('请选择'.encode(), b'\x1b[B\x1b[B\x1b[B\n', False),
            # 服务子菜单：连按 2 次 ↓ 切换到“3. 重启”并回车
            ('3. 重启'.encode(), b'\x1b[B\x1b[B\n', False),
            # 确认服务操作：默认停留在第 1 项“是，执行重启”，直接回车确认
            ('确认服务操作'.encode(), b'\n', False),
            # 回到主菜单：按 1 次 ↑ 循环切换到末尾“5. 退出”并回车
            ('请选择'.encode(), b'\x1b[A\n', False),
        ],
    )
    assert code == 0, output
    assert '服务操作 restart 已执行'.encode() in output
    assert events.read_text().splitlines()[-1] == '--user restart oai-core.service'
    assert account_paths(root / 'runtime/accounts', account_id).database.is_file()


@pytest.mark.parametrize('user_input,should_cancel', [
    ('', False),
    ('y', False),
    ('Y', False),
    ('yes', False),
    ('n', True),
    ('INSTALL', True),
    ('other', True),
])
def test_installer_py_confirmation_accepts_default_and_yn(tmp_path, monkeypatch, user_input, should_cancel):
    import importlib.util
    root = tmp_path / 'deployment'
    root.mkdir()
    (root / 'oai-core').mkdir()
    (root / 'oai-core/pyproject.toml').write_text('[project]\nname="oai-core"\n')
    scripts = root / 'scripts'
    scripts.mkdir()
    shutil.copy2(REPOSITORY / 'scripts/installer.py', scripts / 'installer.py')
    core_src = root / 'oai-core/src/oai_core'
    core_src.mkdir(parents=True)
    (core_src / 'scripts_runtime.py').write_text('# stub\n')

    monkeypatch.setattr('sys.argv', [str(scripts / 'installer.py'), 'install', '--dir', str(root)])
    monkeypatch.setattr('sys.stdin.isatty', lambda: True)
    monkeypatch.setattr('builtins.input', lambda prompt: user_input)

    subprocesses = []
    monkeypatch.setattr(subprocess, 'run', lambda argv, **kw: subprocesses.append(argv))

    spec = importlib.util.spec_from_file_location("installer_mod", str(scripts / 'installer.py'))
    installer_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(installer_mod)

    if should_cancel:
        with pytest.raises(SystemExit) as exc_info:
            installer_mod.main()
        assert exc_info.value.code != 0
        assert len(subprocesses) == 0
    else:
        execve_called = []
        monkeypatch.setattr(os, 'execve', lambda path, argv, env: execve_called.append((path, argv)))
        installer_mod.main()
        assert len(subprocesses) == 3  # venv, pip install, pip check
        assert len(execve_called) == 1

