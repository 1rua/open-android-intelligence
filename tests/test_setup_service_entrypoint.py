"""The real setup entrypoint confirms and audits service mutations."""
from contextlib import contextmanager
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
import sys
import termios
import time

import pytest

from oai_core import configuration
from oai_core.gateway.account_paths import account_paths
from oai_core.identity import IdentityStore, issue_bootstrap_proof

REPOSITORY = Path(__file__).resolve().parents[1]
PASSWORD = 'synthetic-setup-service-password'


@pytest.fixture
def service_deployment(tmp_path, monkeypatch):
    home = tmp_path / 'home'
    home.mkdir()
    # Prevent optional skill synchronization from writing outside this fixture.
    for name in ('.agents', '.claude', '.codex', '.hermes', '.codebuddy', '.trae', '.dsh-cline', '.dsh'):
        (home / name).write_text('Unrelated host configuration.\n')
    monkeypatch.setenv('HOME', str(home))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'absent-hermes'))
    monkeypatch.setenv('DSH_HOME', str(tmp_path / 'absent-dsh'))
    for name in ('OAI_HERMES_SESSION_TOKEN', 'OAI_DSH_ADAPTER_TOKEN', 'OAI_INIT_ADMIN_PASSWORD'):
        monkeypatch.delenv(name, raising=False)

    root = tmp_path / 'deployment'
    root.mkdir()
    database = root / 'runtime/security/identity.sqlite'
    storage = root / 'runtime/accounts'
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
    try:
        account_id = store.bootstrap('alice', PASSWORD, confirmed=True)['accountId']
        handle = store.begin_management('alice', PASSWORD)
        configuration.configure(root, '127.0.0.1', 8045, 'alice', [], None,
                                store=store, handle=handle)
        store.revoke_management(handle)
    finally:
        store.close()

    metadata = root / 'oai-core/pyproject.toml'
    metadata.parent.mkdir(parents=True)
    shutil.copy2(REPOSITORY / 'oai-core/pyproject.toml', metadata)
    shutil.copytree(REPOSITORY / 'oai-core/src', root / 'oai-core/src',
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    (root / 'scripts').mkdir()
    shutil.copy2(REPOSITORY / 'scripts/installer.py', root / 'scripts/installer.py')
    binary = root / '.venv/bin'
    binary.mkdir(parents=True)
    python = binary / 'python'
    python.write_text('#!/usr/bin/env bash\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
    python.chmod(0o700)

    unit = home / '.config/systemd/user/oai-core.service'
    unit.parent.mkdir(parents=True)
    unit.write_text('[Service]\nWorkingDirectory=' + str(root) + '\nExecStart=' + str(root / 'start-core.sh') + '\n')
    commands = root / 'fake-commands'
    commands.mkdir()
    events = root / 'systemctl-events'
    command = commands / 'systemctl'
    command.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> ' + shlex.quote(str(events)) + '\n'
                       + 'if [ "$2" = show ]; then\n'
                       + '  printf "LoadState=loaded\\nFragmentPath=%s\\nWorkingDirectory=%s\\nActiveState=active\\nSubState=running\\n" '
                       + shlex.quote(str(unit)) + ' ' + shlex.quote(str(root)) + '\nfi\n')
    command.chmod(0o700)
    environment = os.environ.copy()
    environment['PATH'] = str(commands) + os.pathsep + environment['PATH']
    return root, account_id, events, environment


@contextmanager
def terminal_process(root, environment):
    master, slave = pty.openpty()
    attributes = termios.tcgetattr(slave)
    attributes[3] |= termios.ECHO | termios.ICANON
    termios.tcsetattr(slave, termios.TCSANOW, attributes)
    pid = os.fork()
    if pid == 0:
        try:
            os.close(master)
            os.login_tty(slave)
            os.execvpe('bash', ['bash', str(REPOSITORY / 'scripts/setup.sh'), '--dir', str(root)], environment)
        finally:
            os._exit(127)
    output = bytearray()
    cursor = 0
    status = None

    def respond(prompt, reply, *, secret=False, before_reply=None):
        nonlocal cursor, status
        expected = prompt.encode()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            position = output.find(expected, cursor)
            if position >= 0 and (not secret or not termios.tcgetattr(slave)[3] & termios.ECHO):
                if before_reply is not None:
                    before_reply()
                cursor = position + len(expected)
                os.write(master, reply.encode() + b'\n')
                return
            if select.select([master], [], [], 0.05)[0]:
                output.extend(os.read(master, 4096))
            waited, child_status = os.waitpid(pid, os.WNOHANG)
            if waited:
                status = child_status
                pytest.fail(f'入口提前退出，未显示 {prompt!r}: {bytes(output)!r}')
        pytest.fail(f'入口未显示 {prompt!r}: {bytes(output)!r}')

    def finish():
        nonlocal status
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if select.select([master], [], [], 0.05)[0]:
                try:
                    output.extend(os.read(master, 4096))
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
            waited, child_status = os.waitpid(pid, os.WNOHANG)
            if waited:
                status = child_status
                assert termios.tcgetattr(slave)[3] & termios.ECHO
                return os.waitstatus_to_exitcode(status), bytes(output)
        pytest.fail(f'入口未退出: {bytes(output)!r}')

    try:
        yield respond, finish
    finally:
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        os.close(master)
        os.close(slave)


def service_audit(root):
    store = IdentityStore(root / 'runtime/security/identity.sqlite')
    try:
        rows = store.database.execute("SELECT action, actor, metadata FROM audit_events WHERE action LIKE 'service.%' ORDER BY id").fetchall()
        return [(row['action'], row['actor'], json.loads(row['metadata'])) for row in rows]
    finally:
        store.close()


@pytest.mark.parametrize('confirmed', [True, False], ids=['confirmed', 'cancelled'])
def test_setup_shell_service_restart_requires_confirmation_and_audits(service_deployment, confirmed):
    root, account_id, events, environment = service_deployment
    original_config = (root / 'core.json').read_bytes()
    original_key = (root / 'runtime/secrets/core-master-key').read_bytes()
    with terminal_process(root, environment) as (respond, finish):
        respond('管理员账号', 'alice')
        respond('管理员密码', PASSWORD, secret=True)
        respond('请选择', '4')
        respond('请选择', '3')
        # Check after the confirmation prompt appears, but before sending a
        # response: no service mutation or audit intent may happen early.
        def assert_not_changed():
            before = events.read_text().splitlines() if events.exists() else []
            assert before and all(command.startswith('--user show oai-core.service ') for command in before)
            assert service_audit(root) == []
        respond('确认服务操作', 'restart' if confirmed else 'cancel', before_reply=assert_not_changed)
        respond('请选择', '5')
        code, output = finish()

    commands = events.read_text().splitlines()
    assert code == 0, output
    assert PASSWORD.encode() not in output
    assert (root / 'core.json').read_bytes() == original_config
    assert (root / 'runtime/secrets/core-master-key').read_bytes() == original_key
    assert stat.S_IMODE((root / 'core.json').stat().st_mode) == 0o600
    assert not account_paths(root / 'runtime/accounts', account_id).database.exists()
    if confirmed:
        assert commands[-1] == '--user restart oai-core.service'
        assert all(command.startswith('--user show oai-core.service ') for command in commands[:-1])
        assert '服务操作 restart 已执行'.encode() in output
        audit = service_audit(root)
        assert [item[0] for item in audit] == ['service.intent', 'service.result']
        assert all(item[1] == account_id for item in audit)
        assert audit[0][2]['operationId'] == audit[1][2]['operationId']
        assert audit[-1][2]['status'] == 'completed'
    else:
        assert all(command.startswith('--user show oai-core.service ') for command in commands)
        assert '已取消'.encode() in output
        assert service_audit(root) == []
