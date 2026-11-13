"""Real shell-entry tests with a synthetic HOME, TTY and fake pip commands."""
from __future__ import annotations

import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import secrets
import shutil
import signal
import sys
import termios
import time

import pytest

REPO = Path(__file__).resolve().parents[1]


@pytest.fixture
def case():
    root = REPO / '.worktrees' / 'installer-tests' / secrets.token_hex(12)
    root.mkdir(parents=True, mode=0o700)
    home = root / 'home'
    home.mkdir(mode=0o700)
    installation = root / 'installation'
    installation.mkdir(mode=0o700)
    for name in ('oai-core/src', 'gateway-contract', 'integrations/skills/oai-client'):
        target = installation / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(REPO / name, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copy2(REPO / 'oai-core/pyproject.toml', installation / 'oai-core/pyproject.toml')
    shutil.copytree(REPO / 'scripts', installation / 'scripts', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    bindir = installation / '.venv/bin'
    bindir.mkdir(parents=True)
    fake = bindir / 'python'
    fake.write_text(f'''#!{sys.executable}
import json, os, sys
if sys.argv[1:3] == ['-m', 'pip']:
    with open(os.environ['TEST_COMMANDS'], 'a') as stream:
        stream.write(json.dumps([sys.argv[1:], list(os.environ)]) + '\\n')
    raise SystemExit(0)
os.execv({sys.executable!r}, [{sys.executable!r}, *sys.argv[1:]])
''')
    fake.chmod(0o700)
    env = dict(os.environ, HOME=str(home), HERMES_HOME=str(home / 'no-hermes'), DSH_HOME=str(home / 'no-dsh'),
               TEST_COMMANDS=str(root / 'commands.jsonl'), PYTHONDONTWRITEBYTECODE='1')
    env.pop('OAI_DIR', None)
    return installation, env


def tty_run(argv, env, responses):
    master, slave = pty.openpty()
    pid = os.fork()
    if pid == 0:
        try:
            os.close(master)
            os.login_tty(slave)
            os.execvpe('bash', ['bash', *map(str, argv)], env)
        finally:
            os._exit(127)
    output = bytearray()
    index = 0
    status = None
    deadline = time.monotonic() + 20
    try:
        while time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], .05)
            if ready:
                try:
                    output.extend(os.read(master, 65536))
                except OSError as exc:
                    if exc.errno != errno.EIO:
                        raise
            if index < len(responses):
                prompt, value, secret = responses[index]
                if prompt.encode() in output and (not secret or not termios.tcgetattr(slave)[3] & termios.ECHO):
                    os.write(master, (value + '\n').encode())
                    index += 1
            waited, code = os.waitpid(pid, os.WNOHANG)
            if waited:
                status = code
                break
        if status is None:
            pytest.fail(f'entry point timed out: {output.decode(errors="replace")}')
        assert index == len(responses), output.decode(errors='replace')
        return os.waitstatus_to_exitcode(status), output.decode(errors='replace')
    finally:
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        os.close(master)
        os.close(slave)


def install_through_tty(root, env, secret):
    return tty_run([root / 'scripts/install.sh'], env, [('初始管理员账号', 'admin', False),
        ('设置管理员密码', secret, True), ('再次输入管理员密码', secret, True),
        ('确认创建首个管理员', 'bootstrap', False), ('保存后请输入 SAVED', 'SAVED', False), ('是否配置开机自启', 'n', False)])


def test_real_install_and_setup_entrypoints(case):
    root, env = case
    install = root / 'scripts/install.sh'
    secret = 'synthetic-shell-secret-41'
    code, output = install_through_tty(root, env, secret)
    assert code == 0, output
    assert secret not in output
    assert json.loads((root / 'core.json').read_text())['listen'] == {'host': '0.0.0.0', 'port': 11451}
    commands = (root.parent / 'commands.jsonl').read_text()
    assert secret not in commands
    (root.parent / 'commands.jsonl').rename(root.parent / 'initial-commands.jsonl')
    code, output = tty_run([install], env, [('管理员账号', 'admin', False), ('管理员密码', 'wrong-secret', True)])
    assert code != 0 and 'wrong-secret' not in output
    assert not (root.parent / 'commands.jsonl').exists()
    code, output = tty_run([root / 'scripts/setup.sh'], env, [('管理员账号', 'admin', False),
        ('管理员密码', secret, True), ('请选择', '5', False)])
    assert code == 0, output
    assert '网络设置' in output and '服务管理' in output
    assert '二次验证' not in output and secret not in output


def test_real_uninstall_authenticates_and_retains_data(case):
    root, env = case
    secret = 'synthetic-shell-secret-42'
    code, output = install_through_tty(root, env, secret)
    assert code == 0, output
    identity = root / 'runtime/security/identity.sqlite'
    original_key = (root / 'runtime/secrets/core-master-key').read_bytes()
    command = root / 'scripts/uninstall.sh'
    code, output = tty_run([command, '--yes', '--dir', root], env,
                           [('管理员账号', 'admin', False), ('管理员密码', 'wrong', True)])
    assert code != 0 and (root / '.venv').is_dir()
    code, output = tty_run([command, '--yes', '--dir', root], env,
                           [('管理员账号', 'admin', False), ('管理员密码', secret, True)])
    assert code == 0, output
    assert not (root / '.venv').exists()
    assert identity.exists() and (root / 'runtime/secrets/core-master-key').read_bytes() == original_key
    assert secret not in output
    # Preserve synthetic recycled files inside the workspace for manual review.
    paths = [line.partition(': ')[2] for line in output.splitlines() if line.startswith('回收目录: ')]
    assert len(paths) == 1
    shutil.move(paths[0], root.parent / 'uninstall-evidence')
