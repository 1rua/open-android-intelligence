"""Run the real uninstaller against synthetic installations and fake host tools."""
from __future__ import annotations

import base64
import errno
import fcntl
import json
import os
from pathlib import Path
import plistlib
import pty
import re
import select
import shlex
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import termios
import time

import pytest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP

from oai_core.gateway.local_keys import LocalMasterKeyStore
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.management import LocalRecovery
from oai_core import mfa


REPOSITORY = Path(__file__).resolve().parents[1]
SCRIPT = REPOSITORY / 'scripts' / 'uninstall.sh'
STATE = REPOSITORY / '.worktrees' / 'uninstall-tests'
ADMIN = 'synthetic-admin'
PASSWORD = 'synthetic-admin-password'
MASTER_KEY = 'synthetic-private-master-key-123'


def bootstrap_identity(root):
    storage = root / 'runtime/accounts'
    database = root / 'runtime/security/identity.sqlite'
    key = root / 'runtime/secrets/core-master-key'
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
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    try:
        store.bootstrap(ADMIN, PASSWORD, confirmed=True)
    finally:
        store.close()


def snapshot_data(path):
    # Authentication necessarily changes the identity DB (management handle and
    # audit trail).  Compare its protected business data separately below.
    return {name: value for name, value in snapshot(path).items()
            if not name.endswith('runtime/security/identity.sqlite')
            and name != 'security/identity.sqlite'}


def identity_data(path):
    database = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)
    try:
        return (database.execute('SELECT * FROM accounts ORDER BY account_id').fetchall(),
                database.execute('SELECT * FROM metadata ORDER BY key').fetchall())
    finally:
        database.close()


def enable_mfa(root):
    database = root / 'runtime/security/identity.sqlite'
    key = root / 'runtime/secrets/core-master-key'
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key))
    try:
        enrollment = store.authenticate(ADMIN, PASSWORD, purpose='enroll')
        account, proof = enrollment['accountId'], enrollment['enrollmentProof']
        setup = store.begin_mfa_enrollment(account, proof)
        secret = base64.b32decode(setup['secret'] + '=' * (-len(setup['secret']) % 8))
        otp = TOTP(secret, mfa.DIGITS, hashes.SHA1(), mfa.PERIOD).generate(time.time()).decode()
        recovery_codes = store.confirm_mfa_enrollment(account, proof, otp, enrollment_id=setup['enrollmentId'])['recoveryCodes']
        handle = store.begin_management(ADMIN, PASSWORD, scopes={'security'})
        LocalRecovery(store).register(handle, confirmed=True)
        store.set_mfa_required(handle, True)
        return recovery_codes
    finally:
        store.close()


class Installation:
    def __init__(self):
        STATE.mkdir(parents=True, exist_ok=True)
        self.base = Path(tempfile.mkdtemp(prefix='case-', dir=STATE))
        self.home = self.base / 'home'
        self.root = self.home / 'Core with spaces'
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.root.mkdir(parents=True)
        self.env = dict(os.environ, HOME=str(self.home), OAI_DIR=str(self.root),
                        PATH=os.pathsep.join((str(self.bin), str(Path(sys.executable).parent), '/usr/bin', '/bin')),
                        TEST_CASE=str(self.base), TEST_ROOT=str(self.root), PYTHONDONTWRITEBYTECODE='1')
        self.env.pop('APPDATA', None)
        self.env.pop('PYTHONPATH', None)
        # Shell and host commands go through the real interpreter; only the
        # dedicated move/mount fault tests enable their installed Python hook.
        self.put(self.bin / 'python3', f'#!/bin/bash\nexec {shlex.quote(sys.executable)} "$@"\n', 0o700)
        self.put(self.root / 'oai-core/pyproject.toml', '[project]\nname = "open-android-intelligence-core"\n')
        shutil.copytree(REPOSITORY / 'oai-core/src/oai_core', self.root / 'oai-core/src/oai_core',
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        self.put(self.root / 'scripts/install.sh', '# synthetic installer marker\n')
        self.put(self.root / '.git/config', '[core]\nrepositoryformatversion = 0\n')
        self.put(self.root / '.venv/pyvenv.cfg', 'include-system-site-packages = false\n')
        self.put(self.root / '.venv/bin/python',
                 f'#!/bin/bash\nexec {shlex.quote(sys.executable)} "$@"\n', 0o700)
        self.put(self.root / '.venv/bin/oai', '# synthetic executable\n', 0o700)
        self.put(self.root / 'start-core.sh', '# synthetic startup\n', 0o700)
        self.put(self.root / 'core.json', json.dumps({'storageRoot': 'runtime/accounts',
                 'masterKeyFile': 'runtime/secrets/core-master-key',
                 'agents': [{'type': 'codex', 'codexHome': 'runtime/codex'}]}), 0o600)
        self.put(self.root / 'runtime/secrets/core-master-key', MASTER_KEY, 0o600)
        bootstrap_identity(self.root)
        self.put(self.root / 'runtime/secrets/adapter-tokens.env', 'TOKEN=synthetic-private-token\n', 0o600)
        self.put(self.root / 'runtime/accounts/account/gateway.sqlite', 'synthetic-account-and-history', 0o600)
        self.put(self.root / 'runtime/codex/session.json', 'synthetic-codex-session', 0o600)
        self.put(self.root / 'runtime/configuration.lock', '', 0o600)
        self.put(self.home / '.hermes/.env', 'KEEP=synthetic-hermes-state\n', 0o600)
        self.put(self.home / '.dsh-cline/.credentials.yaml', 'keep: synthetic-dsh-state\n', 0o600)
        self.put(self.home / '.bashrc', 'export PATH="$HOME/.local/bin:$PATH"\n')
        self.fake('systemctl', '''
if 'show' in sys.argv:
    print('LoadState=loaded')
    print('FragmentPath=' + str(Path(os.environ['HOME']) / '.config/systemd/user/oai-core.service'))
    print('WorkingDirectory=' + os.environ.get('TEST_LOADED_ROOT', os.environ['TEST_ROOT']))
if 'stop' in sys.argv and os.environ.get('TEST_STOP_FAIL'):
    raise SystemExit(7)
''')
        self.fake('crontab', '''
cron = base / 'crontab.txt'
if sys.argv[1:] == ['-l']:
    if cron.exists():
        print(cron.read_text(), end='')
else:
    cron.write_text(sys.stdin.read())
''')
        self.fake('launchctl', '')

    @staticmethod
    def put(path, content, mode=0o644):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(mode)
        return path

    def fake(self, name, body):
        header = f'#!{sys.executable}\nimport json, os, sys\nfrom pathlib import Path\nbase = Path(os.environ["TEST_CASE"])\n'
        logger = 'with (base / "commands.jsonl").open("a") as out:\n    out.write(json.dumps([Path(sys.argv[0]).name, *sys.argv[1:]]) + "\\n")\n'
        self.put(self.bin / name, header + logger + body, 0o700)

    def startup(self, target=None):
        target = target or self.root
        self.put(self.home / '.local/bin/oai', '#!/bin/bash\nOAI_DIR=' + shlex.quote(str(target)) + '\n')
        self.put(self.home / '.config/systemd/user/oai-core.service',
                 f'[Service]\nWorkingDirectory={target}\nExecStart={target}/start-core.sh\nRestart=always\n')
        link = self.home / '.config/systemd/user/default.target.wants/oai-core.service'
        link.parent.mkdir(parents=True)
        try:
            link.symlink_to('../oai-core.service')
        except OSError as error:
            if error.errno not in {errno.ENOENT, errno.EPERM, errno.EOPNOTSUPP}:
                raise
            # Some developer filesystems cannot create links; CI still covers them.
            assert link.parent.is_dir()
        self.put(self.home / '.config/autostart/oai-core.desktop', f'[Desktop Entry]\nExec={target}/start-core.sh\n')

    def inject_python_fault(self, code):
        # -I intentionally ignores PYTHONPATH. A test-only interpreter wrapper
        # applies the requested fault, then executes the untouched program.
        self.put(self.base / 'fault_hooks' / 'fault.py', code)
        wrapper = self.put(self.base / 'fault_hooks' / 'interpreter.py', '''
import runpy, sys
from pathlib import Path
sys.dont_write_bytecode = True
hook = Path(__file__).with_name('fault.py')
exec(compile(hook.read_text(), str(hook), 'exec'))
while sys.argv[1:2] and sys.argv[1] in ('-I', '-B'):
    del sys.argv[1]
if sys.argv[1:2] == ['-c']:
    code = sys.argv[2]
    sys.argv = ['-c', *sys.argv[3:]]
    exec(compile(code, '<uninstall-fault-test>', 'exec'), {'__name__': '__main__'})
else:
    script = sys.argv[1]
    sys.argv = sys.argv[1:]
    runpy.run_path(script, run_name='__main__')
''')
        launch = f'#!/bin/bash\nexec {shlex.quote(sys.executable)} -I {shlex.quote(str(wrapper))} "$@"\n'
        self.put(self.bin / 'python3', launch, 0o700)
        self.put(self.root / '.venv/bin/python', launch, 0o700)

    def run(self, *arguments, pipe=False, terminal=True, confirmation='y',
            username=ADMIN, password=PASSWORD, recovery_code=None):
        args = ['bash', '-s', '--', *arguments] if pipe else ['bash', str(SCRIPT), *arguments]
        if not terminal:
            return subprocess.run(args, input=SCRIPT.read_text() if pipe else None,
                                  cwd=self.base, env=self.env, capture_output=True, text=True,
                                  start_new_session=True, timeout=25)

        # The uninstaller consumes stdin as its Python program and deliberately
        # reopens /dev/tty for getpass. Send passwords only after echo is off.
        master, slave = pty.openpty()
        slave_name = os.ttyname(slave)
        process = subprocess.Popen(
            args, stdin=subprocess.PIPE if pipe else slave, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=self.base, env=self.env, start_new_session=True,
            # Open the slave after setsid so it becomes the controlling terminal.
            preexec_fn=lambda: os.open(slave_name, os.O_RDWR),
        )
        if pipe:
            process.stdin.write(SCRIPT.read_bytes())
            process.stdin.close()
        stdout_fd, stderr_fd = process.stdout.fileno(), process.stderr.fileno()
        streams = {master: bytearray(), stdout_fd: bytearray(), stderr_fd: bytearray()}
        open_streams = set(streams)
        prompts = [] if '--yes' in arguments else [('其他输入取消', confirmation, False)]
        prompts.extend([('管理员账号:', username, False), ('管理员密码:', password, True)])
        if recovery_code is not None:
            prompts.extend([('二次验证方式', '2', False), ('恢复码:', recovery_code, True)])
        responses = iter(prompts)
        pending = next(responses, None)
        try:
            deadline = time.monotonic() + 25
            while open_streams and time.monotonic() < deadline:
                if process.poll() is not None and master in open_streams:
                    # The parent keeps the slave open to inspect echo state.
                    # Once the child exits it can no longer produce prompts.
                    open_streams.remove(master)
                if not open_streams:
                    break
                readable, _, _ = select.select(list(open_streams), [], [], 0.05)
                for descriptor in readable:
                    try:
                        chunk = os.read(descriptor, 4096)
                    except OSError as error:
                        if descriptor != master or error.errno != errno.EIO:
                            raise
                        chunk = b''
                    if chunk:
                        streams[descriptor].extend(chunk)
                    else:
                        open_streams.remove(descriptor)
                output = b''.join(streams.values())
                if pending is not None and pending[0].encode() in output:
                    if pending[2]:
                        assert not termios.tcgetattr(slave)[3] & termios.ECHO
                    os.write(master, (pending[1] + '\n').encode())
                    pending = next(responses, None)
            if open_streams:
                pytest.fail('卸载脚本交互超时: ' + output.decode(errors='replace'))
            process.wait(timeout=3)
        finally:
            os.close(master)
            os.close(slave)
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
            process.stdout.close()
            process.stderr.close()
        self.terminal_output = streams[master].decode(errors='replace')
        result = subprocess.CompletedProcess(args, process.returncode,
                                             streams[stdout_fd].decode(),
                                             streams[stderr_fd].decode())
        output = self.terminal_output + result.stdout + result.stderr
        assert password not in output
        if recovery_code is not None:
            assert recovery_code not in output
        return result

    def commands(self):
        path = self.base / 'commands.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def manifest(self, result):
        lines = recycle_directories(result)
        assert len(lines) == 1, result.stdout + result.stderr
        batch = Path(lines[0])
        manifest = json.loads((batch / 'manifest.json').read_text())
        assert manifest['status'] == 'completed'
        assert stat.S_IMODE(batch.stat().st_mode) == 0o700
        assert stat.S_IMODE((batch / 'manifest.json').stat().st_mode) == 0o600
        # Keep synthetic test evidence in the worktree instead of discarding it.
        evidence = self.base / 'recycle-evidence'
        shutil.move(str(batch), evidence)
        for entry in manifest['moves']:
            entry['destination'] = str(evidence / Path(entry['destination']).name)
        return manifest, evidence


@pytest.fixture
def installation():
    return Installation()


def snapshot(path):
    return {str(file.relative_to(path)): (file.read_bytes(), stat.S_IMODE(file.stat().st_mode))
            for file in path.rglob('*') if file.is_file() and not file.is_symlink()}


def success(result):
    assert result.returncode == 0, result.stdout + result.stderr


def recycle_directories(result):
    # An input prompt may precede the announcement on the same stdout line.
    # Do not confuse it with the earlier "将移入回收目录" preview entries.
    return re.findall(r'(?:^|(?<= ))回收目录: ([^\r\n]+)', result.stdout, re.MULTILINE)


def test_syntax_and_help_do_not_require_an_installation(installation):
    result = subprocess.run(['bash', '-n', str(SCRIPT)], capture_output=True, text=True)
    success(result)
    result = installation.run('--dir', str(installation.base / 'missing'), '--help', terminal=False)
    success(result)
    assert all(option in result.stdout for option in ('--keep-config', '--purge', '--dir', '--yes', '--dry-run'))
    assert not installation.commands()


def test_uninstall_uses_the_installations_python_for_authentication(installation):
    # Emulate a copied venv interpreter (without symlinks) with cryptography
    # installed. The subprocess runs its real authentication through this venv.
    import _cffi_backend
    import cryptography

    python = installation.root / '.venv/bin/python'
    shutil.copy2(sys._base_executable, python)
    installation.put(installation.root / '.venv/pyvenv.cfg',
                     'home = ' + str(Path(sys._base_executable).resolve().parent)
                     + '\ninclude-system-site-packages = false\n')
    version = f'python{sys.version_info.major}.{sys.version_info.minor}'
    site_packages = python.parent.parent / 'lib' / version / 'site-packages'
    shutil.copytree(Path(cryptography.__file__).parent, site_packages / 'cryptography',
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copy2(_cffi_backend.__file__, site_packages)
    probe = subprocess.run([str(python), '-I', '-B', '-c',
                            'import sys, cryptography.hazmat.bindings._rust; print(sys.prefix)'],
                           capture_output=True, text=True, check=True)
    assert Path(probe.stdout.strip()) == python.parent.parent
    installation.env['OAI_DIR'] = str(installation.base / 'wrong-default')
    result = installation.run('--dir=' + str(installation.root), '--yes')
    success(result)
    installation.manifest(result)
    assert not (installation.root / '.venv').exists()


@pytest.mark.parametrize('mode', ['--keep-config', '--purge'])
@pytest.mark.parametrize('pipe', [False, True])
@pytest.mark.parametrize('yes', [False, True])
def test_no_terminal_never_executes_installations_interpreter(installation, mode, pipe, yes):
    marker = installation.base / 'original-interpreter-ran'
    installation.put(installation.root / '.venv/bin/python',
                     '#!/bin/sh\nprintf executed > ' + shlex.quote(str(marker)) + '\nexit 42\n', 0o700)
    installation.startup()
    before = snapshot(installation.home)
    arguments = [mode] + (['--yes'] if yes else [])
    result = installation.run(*arguments, pipe=pipe, terminal=False)
    assert result.returncode not in (0, 42), result.stdout + result.stderr
    assert '交互终端' in result.stderr
    assert not marker.exists(), result.stdout + result.stderr
    assert snapshot(installation.home) == before
    assert all(command == ['crontab', '-l'] for command in installation.commands())
    assert not recycle_directories(result)


def test_uninstall_refuses_unusable_original_interpreter(installation):
    installation.put(installation.root / '.venv/bin/python', '#!/bin/sh\nexit 42\n', 0o700)
    installation.startup()
    before = snapshot(installation.home)
    result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert result.returncode == 42 or '原部署解释器不可用' in result.stderr
    assert snapshot(installation.home) == before
    assert all(command == ['crontab', '-l'] for command in installation.commands())
    assert not recycle_directories(result)


@pytest.mark.parametrize('arguments', [('--help',), ('--dry-run', '--yes'), ('--yes',)])
def test_uninstall_ignores_tampered_argument_target_before_validation(installation, arguments):
    outside = installation.base / 'unrecognized-target'
    marker = installation.base / 'untrusted-python-ran'
    installation.put(outside / '.venv/bin/python',
                     '#!/bin/sh\nprintf executed > ' + shlex.quote(str(marker)) + '\nexit 1\n', 0o700)
    result = installation.run('--dir', str(outside), *arguments, terminal=False)
    if arguments == ('--help',):
        success(result)
    else:
        assert result.returncode != 0
        assert '受支持的 OAI Core 源码安装' in result.stderr
    assert not marker.exists(), result.stdout + result.stderr


@pytest.mark.parametrize('arguments', [('--unknown',), ('--keep-config', '--purge'), ('--dir',)])
def test_invalid_arguments_change_nothing(installation, arguments):
    before = snapshot(installation.home)
    assert installation.run(*arguments).returncode != 0
    assert snapshot(installation.home) == before
    assert not installation.commands()


@pytest.mark.parametrize('mode', ['--keep-config', '--purge'])
def test_preview_is_read_only_and_works_through_a_pipe(installation, mode):
    installation.startup()
    before = snapshot(installation.home)
    result = installation.run(mode, '--dry-run', pipe=True)
    success(result)
    assert snapshot(installation.home) == before
    assert not any('stop' in command or 'daemon-reload' in command for command in installation.commands())
    assert not recycle_directories(result)


def test_default_mode_requires_confirmation_without_a_terminal(installation):
    before = snapshot(installation.home)
    result = installation.run(terminal=False)
    assert result.returncode != 0
    assert '--yes' in result.stderr
    assert snapshot(installation.home) == before


@pytest.mark.parametrize('pipe', [False, True])
def test_yes_does_not_bypass_authentication_without_a_terminal(installation, pipe):
    installation.startup()
    before = snapshot(installation.home)
    result = installation.run('--yes', pipe=pipe, terminal=False)
    assert result.returncode != 0
    assert '--yes 不能代替认证' in result.stderr
    assert snapshot(installation.home) == before
    assert not any(command[0] == 'systemctl' for command in installation.commands())
    assert not recycle_directories(result)


@pytest.mark.parametrize('account', ['incorrect-password', 'non-admin'])
def test_yes_rejects_invalid_credentials_before_side_effects(installation, account):
    installation.startup()
    username, password = ADMIN, 'incorrect-synthetic-password'
    if account == 'non-admin':
        store = IdentityStore(installation.root / 'runtime/security/identity.sqlite')
        try:
            handle = store.begin_management(ADMIN, PASSWORD, scopes={'accounts.write'})
            store.create_account(handle, 'synthetic-user', PASSWORD)
            store.revoke_management(handle)
        finally:
            store.close()
        username, password = 'synthetic-user', PASSWORD
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    result = installation.run('--purge', '--yes', username=username, password=password)
    assert result.returncode != 0
    assert ('AUTHENTICATION_FAILED' if account == 'incorrect-password' else 'ADMIN_REQUIRED') in result.stderr
    assert '管理员密码:' in installation.terminal_output
    assert snapshot_data(installation.home) == before
    assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    assert not any(command[0] == 'systemctl' for command in installation.commands())
    assert not recycle_directories(result)


@pytest.mark.parametrize('valid', [False, True])
def test_uninstall_requires_valid_mfa_when_enabled(installation, valid):
    installation.startup()
    codes = enable_mfa(installation.root)
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    code = codes[0] if valid else 'synthetic-invalid-recovery-code'
    result = installation.run('--yes', recovery_code=code)
    assert '二次验证方式' in result.stdout
    assert '恢复码:' in installation.terminal_output
    if valid:
        success(result)
        installation.manifest(result)
        assert not (installation.root / '.venv').exists()
        assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    else:
        assert result.returncode != 0
        assert 'AUTHENTICATION_FAILED' in result.stderr
        assert snapshot_data(installation.home) == before
        assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
        assert not any(command[0] == 'systemctl' for command in installation.commands())
        assert not recycle_directories(result)


def test_keep_configuration_preserves_keys_history_sources_and_shared_hosts(installation):
    installation.startup()
    before_runtime = snapshot_data(installation.root / 'runtime')
    identity = installation.root / 'runtime/security/identity.sqlite'
    before_identity = identity_data(identity)
    before_config = (installation.root / 'core.json').read_bytes()
    result = installation.run('--yes')
    success(result)
    manifest, _ = installation.manifest(result)
    assert manifest['mode'] == 'keep-config'
    assert snapshot_data(installation.root / 'runtime') == before_runtime
    assert identity_data(identity) == before_identity
    assert (installation.root / 'core.json').read_bytes() == before_config
    assert (installation.root / 'scripts/install.sh').exists()
    assert (installation.root / '.git/config').exists()
    assert not (installation.root / '.venv').exists()
    assert not (installation.root / 'start-core.sh').exists()
    assert not (installation.home / '.local/bin/oai').exists()
    assert not (installation.home / '.config/autostart/oai-core.desktop').exists()
    link = installation.home / '.config/systemd/user/default.target.wants/oai-core.service'
    assert not link.is_symlink()
    assert any(command[1:] == ['--user', 'stop', 'oai-core.service'] for command in installation.commands())
    assert not any('disable' in command for command in installation.commands())
    assert (installation.home / '.hermes/.env').read_text() == 'KEEP=synthetic-hermes-state\n'
    assert (installation.home / '.dsh-cline/.credentials.yaml').exists()
    assert 'synthetic-private' not in result.stdout + result.stderr
    assert all(Path(item['destination']).exists() or Path(item['destination']).is_symlink() for item in manifest['moves'])
    repeat = installation.run('--yes')
    success(repeat)
    assert not recycle_directories(repeat)
    assert snapshot_data(installation.root / 'runtime') == before_runtime
    assert identity_data(identity) == before_identity


def test_purge_moves_the_complete_installation_and_can_be_repeated(installation):
    installation.startup()
    before = snapshot_data(installation.root)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    result = installation.run('--purge', '--yes')
    success(result)
    manifest, _ = installation.manifest(result)
    assert manifest['mode'] == 'purge'
    assert not installation.root.exists()
    entry = next(item for item in manifest['moves'] if item['source'] == str(installation.root))
    destination = Path(entry['destination'])
    assert snapshot_data(destination) == before
    assert identity_data(destination / 'runtime/security/identity.sqlite') == before_identity
    assert (installation.home / '.hermes/.env').exists()
    assert (installation.home / '.bashrc').exists()
    repeat = installation.run('--purge', '--yes')
    success(repeat)
    assert not recycle_directories(repeat)


@pytest.mark.parametrize('mode', ['--keep-config', '--purge'])
def test_external_data_and_custom_installation_paths_are_preserved(installation, mode):
    external = installation.base / 'external-data'
    secret = installation.put(external / 'original-key', 'synthetic-external-key-123456789', 0o600)
    installation.put(external / 'accounts/history', 'synthetic-external-history', 0o600)
    config = {'storageRoot': str(external / 'accounts'), 'masterKeyFile': str(secret),
              'identityDatabase': 'runtime/security/identity.sqlite',
              'agents': [{'type': 'codex', 'codexHome': str(external / 'codex')}],
              'tls': {'certFile': str(external / 'cert.pem'), 'keyFile': str(external / 'tls-key')}}
    installation.put(installation.root / 'core.json', json.dumps(config), 0o600)
    installation.env['OAI_DIR'] = str(installation.base / 'wrong-default')
    before = snapshot(external)
    result = installation.run('--dir', str(installation.root), mode, '--yes')
    success(result)
    installation.manifest(result)
    assert snapshot(external) == before
    assert str(secret) in result.stdout
    assert 'synthetic-external-key' not in result.stdout + result.stderr


@pytest.mark.parametrize('target', ['/', 'HOME', 'unknown', 'symlink'])
def test_dangerous_or_unrecognized_directories_are_rejected(installation, target):
    if target == 'HOME':
        path = installation.home
    elif target == 'unknown':
        path = installation.base / 'unknown'
        path.mkdir()
    elif target == 'symlink':
        path = installation.base / 'install-link'
        try:
            path.symlink_to(installation.root, target_is_directory=True)
        except OSError as error:
            if error.errno not in {errno.ENOENT, errno.EPERM, errno.EOPNOTSUPP}:
                raise
            # The shared workspace filesystem can reject symlink creation. Use
            # an existing directory symlink rather than skipping the protection.
            path = Path('/dev/fd' if Path('/dev/fd').is_symlink() else '/var')
            assert path.is_symlink()
    else:
        path = Path(target)
    before = snapshot(installation.home)
    result = installation.run('--dir', str(path), '--purge', '--yes')
    assert result.returncode != 0
    if target == 'symlink':
        assert '不能包含符号链接' in result.stderr
    assert snapshot(installation.home) == before
    assert not installation.commands()


def test_another_installations_startup_entries_are_not_touched(installation):
    installation.startup(installation.base / 'another-installation')
    startup_before = snapshot(installation.home / '.config')
    wrapper_before = (installation.home / '.local/bin/oai').read_bytes()
    result = installation.run('--yes')
    success(result)
    installation.manifest(result)
    assert snapshot(installation.home / '.config') == startup_before
    assert (installation.home / '.local/bin/oai').read_bytes() == wrapper_before
    assert not any(command[0] == 'systemctl' for command in installation.commands())


@pytest.mark.parametrize('failure', ['stop-failed', 'loaded-elsewhere'])
def test_service_failure_aborts_before_moving_files(installation, failure):
    installation.startup()
    if failure == 'stop-failed':
        installation.env['TEST_STOP_FAIL'] = '1'
    else:
        installation.env['TEST_LOADED_ROOT'] = str(installation.base / 'another-installation')
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert snapshot_data(installation.home) == before
    assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    assert not recycle_directories(result)
    assert any('show' in command for command in installation.commands())
    if failure == 'loaded-elsewhere':
        assert '服务与此安装不匹配' in result.stderr
        assert not any('stop' in command for command in installation.commands())
    else:
        assert '操作失败' in result.stderr
        assert any('stop' in command for command in installation.commands())


def test_cron_removal_matches_the_complete_line_and_preserves_other_jobs(installation):
    cron = installation.base / 'crontab.txt'
    unrelated = '@reboot /somewhere/else/start-core.sh >/dev/null 2>&1 &\n0 2 * * * echo keep\n'
    original = f'@reboot {installation.root}/start-core.sh >/dev/null 2>&1 &\n' + unrelated
    cron.write_text(original)
    result = installation.run('--yes')
    success(result)
    _, evidence = installation.manifest(result)
    assert cron.read_text() == unrelated
    assert (evidence / 'crontab.before').read_text() == original
    assert stat.S_IMODE((evidence / 'crontab.before').stat().st_mode) == 0o600


def test_mac_launch_agent_is_matched_and_stopped(installation):
    path = installation.home / 'Library/LaunchAgents/com.openandroidintelligence.core.plist'
    settings = {'Label': 'com.openandroidintelligence.core',
                'ProgramArguments': [str(installation.root / 'start-core.sh')]}
    installation.put(path, plistlib.dumps(settings).decode())
    installation.fake('launchctl', """
import plistlib
if sys.argv[1:] == ['list']:
    print('123 0 com.openandroidintelligence.core')
elif '-x' in sys.argv:
    print(plistlib.dumps({'ProgramArguments': [str(Path(os.environ['TEST_ROOT']) / 'start-core.sh')]}).decode())
""")
    result = installation.run('--yes')
    success(result)
    installation.manifest(result)
    assert not path.exists()
    assert ['launchctl', 'unload', str(path)] in installation.commands()


@pytest.mark.parametrize('journal', ['{broken', '{"version": 1, "status": "preparing"}'])
def test_unfinished_configuration_journal_is_preserved(installation, journal):
    installation.put(installation.root / 'runtime/configuration-journal.json', journal, 0o600)
    before = snapshot(installation.home)
    result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert snapshot(installation.home) == before
    assert not installation.commands()


def test_configuration_lock_blocks_a_concurrent_uninstall(installation):
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    with (installation.root / 'runtime/configuration.lock').open('r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert '管理员密码:' in installation.terminal_output
    assert '[Errno 11]' in result.stderr
    assert snapshot_data(installation.home) == before
    assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    assert not recycle_directories(result)


def test_keep_configuration_refuses_data_inside_the_virtual_environment(installation):
    config = json.loads((installation.root / 'core.json').read_text())
    config['storageRoot'] = '.venv/accounts'
    installation.put(installation.root / 'core.json', json.dumps(config), 0o600)
    result = installation.run('--yes')
    assert result.returncode != 0
    assert (installation.root / '.venv').exists()
    assert not recycle_directories(result)


def test_purge_refuses_git_worktrees(installation):
    shutil.move(str(installation.root / '.git'), installation.base / 'git-metadata-evidence')
    installation.put(installation.root / '.git', 'gitdir: ../shared.git\n')
    result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert (installation.root / '.venv').exists()
    assert not installation.commands()


def launch_synthetic_core(installation, binary=None):
    binary = binary or installation.root / '.venv/bin/oai'
    installation.put(binary, 'import signal\nfrom pathlib import Path\nPath("runtime/ready").write_text("ready")\nsignal.pause()\n', 0o700)
    process = subprocess.Popen([sys.executable, str(binary), 'serve', '--config', 'core.json'],
                               cwd=installation.root, env=installation.env, start_new_session=True)
    deadline = time.monotonic() + 5
    while not (installation.root / 'runtime/ready').exists():
        assert process.poll() is None
        assert time.monotonic() < deadline
        time.sleep(0.02)
    return process


def test_only_this_installations_running_core_is_stopped(installation):
    other = Installation()
    own_process = launch_synthetic_core(installation)
    other_process = launch_synthetic_core(other)
    try:
        result = installation.run('--yes')
        success(result)
        installation.manifest(result)
        own_process.wait(timeout=3)
        assert other_process.poll() is None
    finally:
        for process in (own_process, other_process):
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=3)


@pytest.mark.parametrize('mode,answer,expected_success', [('--keep-config', 'y', True),
                                                           ('--purge', 'PURGE', True),
                                                           ('--purge', 'cancel', False)])
def test_terminal_confirmation_accepts_or_cancels(installation, mode, answer, expected_success):
    result = installation.run(mode, confirmation=answer)
    assert (result.returncode == 0) == expected_success, result.stdout + result.stderr
    assert '其他输入取消' in installation.terminal_output
    if expected_success:
        assert '管理员账号:' in result.stdout
        installation.manifest(result)
        assert not (installation.root / '.venv').exists()
    else:
        assert '管理员账号:' not in result.stdout
        assert (installation.root / '.venv').exists()


def test_configuration_change_during_service_stop_aborts_before_moving_data(installation):
    installation.startup()
    installation.fake('systemctl', '''
if 'show' in sys.argv:
    root = Path(os.environ['TEST_ROOT'])
    config = json.loads((root / 'core.json').read_text())
    config['masterKeyFile'] = '.venv/newly-selected-key'
    (root / 'core.json').write_text(json.dumps(config))
    print('LoadState=loaded')
    print('FragmentPath=' + str(Path(os.environ['HOME']) / '.config/systemd/user/oai-core.service'))
    print('WorkingDirectory=' + str(root))
''')
    installation.put(installation.root / '.venv/newly-selected-key', 'synthetic-racing-key', 0o600)
    result = installation.run('--yes')
    assert result.returncode != 0
    assert '停止服务期间配置发生变化' in result.stderr
    assert any(command[1:] == ['--user', 'stop', 'oai-core.service'] for command in installation.commands())
    assert (installation.root / '.venv/newly-selected-key').read_text() == 'synthetic-racing-key'
    assert (installation.root / 'runtime/secrets/core-master-key').exists()
    assert not recycle_directories(result)


def test_partial_move_failure_retains_manifest_and_every_piece_of_data(installation):
    installation.inject_python_fault('''
import os
from pathlib import Path
import shutil
original_move = shutil.move
calls = 0
def interrupted_move(source, destination, *args, **kwargs):
    global calls
    calls += 1
    if calls == 2:
        raise PermissionError('synthetic interrupted move')
    return original_move(source, destination, *args, **kwargs)
shutil.move = interrupted_move
''')
    result = installation.run('--yes')
    assert result.returncode != 0
    batches = recycle_directories(result)
    assert len(batches) == 1, result.stdout + result.stderr
    batch = Path(batches[0])
    manifest = json.loads((batch / 'manifest.json').read_text())
    assert manifest['status'] == 'failed'
    assert [entry['completed'] for entry in manifest['moves']] == [True, False]
    assert Path(manifest['moves'][0]['destination']).exists()
    assert Path(manifest['moves'][1]['source']).exists()
    assert (installation.root / 'runtime/secrets/core-master-key').read_text() == MASTER_KEY
    assert (installation.root / 'runtime/accounts/account/gateway.sqlite').exists()
    shutil.move(str(batch), installation.base / 'interrupted-recycle-evidence')


def test_mac_loaded_service_for_another_installation_is_not_stopped(installation):
    path = installation.home / 'Library/LaunchAgents/com.openandroidintelligence.core.plist'
    installation.put(path, plistlib.dumps({'Label': 'com.openandroidintelligence.core',
                                         'ProgramArguments': [str(installation.root / 'start-core.sh')]}).decode())
    installation.fake('launchctl', """
import plistlib
if sys.argv[1:] == ['list']:
    print('123 0 com.openandroidintelligence.core')
elif '-x' in sys.argv:
    print(plistlib.dumps({'ProgramArguments': ['/another-installation/start-core.sh']}).decode())
""")
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    result = installation.run('--purge', '--yes')
    assert result.returncode != 0
    assert '属于另一安装' in result.stderr
    assert snapshot_data(installation.home) == before
    assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    assert ['launchctl', 'list'] in installation.commands()
    assert not any(command[0] == 'launchctl' and 'unload' in command for command in installation.commands())


def implementation():
    code = SCRIPT.read_text().split("<<'PYTHON'\n", 1)[1].rsplit('\nPYTHON', 1)[0]
    namespace = {'__name__': 'uninstall_regression'}
    exec(compile(code, str(SCRIPT), 'exec'), namespace)
    return namespace


@pytest.mark.parametrize('mode', ['--keep-config', '--purge'])
def test_nested_mounts_are_refused_before_any_directory_migration(installation, mode):
    mount = installation.root / '.venv/shared-host-data'
    encoded = str(mount).replace(' ', '\\040')
    mountinfo = f'99 25 0:32 / {encoded} rw - ext4 /dev/synthetic rw\n'
    installation.inject_python_fault('''
from pathlib import Path
original = Path.read_text
def mountinfo_override(path, *args, **kwargs):
    if str(path) == '/proc/self/mountinfo':
        return ''' + repr(mountinfo) + '''
    return original(path, *args, **kwargs)
Path.read_text = mountinfo_override
''')
    before = snapshot(installation.home)
    result = installation.run(mode, '--yes')
    assert result.returncode != 0
    assert '挂载点' in result.stderr
    assert snapshot(installation.home) == before
    assert not installation.commands()


def test_failed_manifest_replacement_keeps_the_previous_complete_record(installation, monkeypatch):
    api = implementation()
    manifest = installation.base / 'atomic-manifest.json'
    api['write_private'](manifest, '{"moves": ["original-path-map"]}\n')
    original = manifest.read_bytes()
    def unavailable_storage(descriptor):
        raise OSError('synthetic storage failure before atomic replacement')
    monkeypatch.setattr(api['os'], 'fsync', unavailable_storage)
    with pytest.raises(OSError):
        api['write_private'](manifest, '{"moves": ["replacement"]}\n')
    assert manifest.read_bytes() == original
    assert len(list(manifest.parent.glob('atomic-manifest.json.*.next'))) == 1


def test_a_foreign_script_named_oai_is_not_stopped_or_uninstalled(installation):
    foreign_binary = installation.base / 'unrelated-program/bin/oai'
    process = launch_synthetic_core(installation, binary=foreign_binary)
    before = snapshot_data(installation.home)
    before_identity = identity_data(installation.root / 'runtime/security/identity.sqlite')
    try:
        result = installation.run('--purge', '--yes')
        assert result.returncode != 0
        assert '程序不属于' in result.stderr
        assert process.poll() is None
        assert snapshot_data(installation.home) == before
        assert identity_data(installation.root / 'runtime/security/identity.sqlite') == before_identity
    finally:
        process.terminate()
        process.wait(timeout=3)


def test_resolved_data_dependency_on_a_linked_environment_is_preserved(installation, monkeypatch):
    api = implementation()
    environment = installation.root / '.venv'
    external = installation.base / 'external-environment'
    key = installation.put(external / 'original-master-key', 'synthetic-linked-key', 0o600)
    original_resolve = Path.resolve
    def resolve_environment(path, *args, **kwargs):
        if path == environment:
            return external
        return original_resolve(path, *args, **kwargs)
    monkeypatch.setattr(Path, 'resolve', resolve_environment)
    with pytest.raises(api['UninstallError'], match='配置数据依赖'):
        api['check_retained_data']([key], [environment])
    assert key.read_text() == 'synthetic-linked-key'


def test_unstructured_process_arguments_with_spaces_require_a_manual_stop(installation):
    api = implementation()
    raw = f'{sys.executable} {installation.root}/.venv/bin/oai serve --config {installation.root}/core.json'
    api['command'] = lambda arguments: subprocess.CompletedProcess(arguments, 0, f'2147483647 {os.getuid()} {raw}\n', '')
    with pytest.raises(api['UninstallError'], match='手动停止'):
        api['core_processes'](installation.root)
