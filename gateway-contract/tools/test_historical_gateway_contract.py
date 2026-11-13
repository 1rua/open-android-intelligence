"""历史夹具与锁定契约自洽门禁的失败保护。"""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).with_name('verify-historical-gateway-contract.py')
VECTOR_FILES = (
    'request-signatures.json',
    'protocol-negotiation.json',
    'auth-sessions.json',
    'attachments.json',
    'sse-events.json',
    'device-requests.json',
)
VECTOR_IDS = (
    'request-signatures-minimal',
    'protocol-negotiation-minimal',
    'auth-sessions-minimal',
    'attachments-minimal',
    'sse-events-minimal',
    'device-requests-minimal',
)

@pytest.fixture
def gate():
    spec = importlib.util.spec_from_file_location('historical_contract', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module

@pytest.fixture
def retained_path():
    path = ROOT / '.worktrees/legacy-contract-gate-tests' / secrets.token_hex(10)
    path.mkdir(parents=True)
    return path


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def commit(root):
    git(root, 'add', '.')
    git(root, '-c', 'user.name=合成门禁测试', '-c', 'user.email=contract-test@invalid.example', 'commit', '-qm', '合成测试提交')
    return git(root, 'rev-parse', 'HEAD')


def repository(root):
    root.mkdir()
    git(root, 'init', '-q')
    git(root, 'remote', 'add', 'origin', 'https://github.com/1rua/open-android-intelligence.git')
    return root


def contract(root):
    for relative in ['schemas/envelope.schema.json', 'vectors/vector-set-1.0.0.schema.json', 'core-dispatched-schemas.json', 'src/schema-registry.ts']:
        p = root / 'gateway-contract' / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('{}\n')
    for filename, vector_id in zip(VECTOR_FILES, VECTOR_IDS):
        path = root / 'gateway-contract' / 'vectors' / filename
        path.write_text(json.dumps({'cases': [{'id': vector_id}]}))
    return commit(root)


def plugin_fixture(root, revision):
    repository(root)
    git(root, 'remote', 'set-url', 'origin', 'https://github.com/1rua/hermes-android-gateway.git')
    (root / 'contract-pin.json').write_text(json.dumps({'repository':'https://github.com/1rua/open-android-intelligence.git','revision':revision}))
    package = root / 'open_android_intelligence_gateway'
    package.mkdir()
    (package / '__init__.py').write_text('# 合成包\n')
    return commit(root)


def test_fixed_historical_pin_is_not_forced_to_current_contract(gate, retained_path):
    repo = repository(retained_path / 'repo')
    old = contract(repo)
    (repo / 'gateway-contract/core-dispatched-schemas.json').write_text('{"new":true}\n')
    commit(repo)
    exported = retained_path / 'export'
    files = gate.export_contract(repo, old, exported)
    assert (exported / 'core-dispatched-schemas.json').read_text() == '{}\n'
    gate.verify_export(repo, old, exported, files)
    # 当前契约确实不同；历史导出并没有修改当前工作区或绕过字节校验。
    assert git(repo, 'diff', old, 'HEAD', '--', 'gateway-contract/core-dispatched-schemas.json')
    assert (repo / 'gateway-contract/core-dispatched-schemas.json').read_text() == '{"new":true}\n'


def test_plugin_head_or_pin_drift_is_rejected(gate, retained_path):
    plugin = retained_path / 'plugin'
    head = plugin_fixture(plugin, 'a' * 40)
    spec = {'revision':head, 'repository':'https://github.com/1rua/hermes-android-gateway.git', 'contractRepository':'https://github.com/1rua/open-android-intelligence.git', 'contractRevision':'a' * 40}
    gate.verify_plugin(plugin, spec)
    with pytest.raises(gate.GateError, match='历史插件提交'):
        gate.verify_plugin(plugin, {**spec, 'revision':'b' * 40})
    with pytest.raises(gate.GateError, match='锁定契约'):
        gate.verify_plugin(plugin, {**spec, 'contractRevision':'b' * 40})
    git(plugin, 'remote', 'set-url', 'origin', 'https://github.com/1rua/hermes-gateway-plugin.git')
    gate.verify_plugin(plugin, {**spec, 'repositoryAliases':['https://github.com/1rua/hermes-gateway-plugin.git']})
    with pytest.raises(gate.GateError, match='插件仓库'):
        gate.verify_plugin(plugin, spec)


def test_dirty_plugin_source_is_rejected(gate, retained_path):
    plugin = retained_path / 'plugin'
    head = plugin_fixture(plugin, 'a' * 40)
    (plugin / 'open_android_intelligence_gateway/__init__.py').write_text('# 源码已变化\n')
    spec = {'revision':head, 'repository':'https://github.com/1rua/hermes-android-gateway.git', 'contractRepository':'https://github.com/1rua/open-android-intelligence.git','contractRevision':'a' * 40}
    with pytest.raises(gate.GateError, match='未提交'):
        gate.verify_plugin(plugin, spec)


@pytest.mark.parametrize('path', ['../outside', '/absolute', 'gateway-contract/../../outside', 'gateway-contract/..\\outside', 'other/schemas/a.json'])
def test_export_rejects_path_escape(gate, path):
    with pytest.raises(gate.GateError, match='导出路径'):
        gate.export_relative_path(path)


def test_missing_or_changed_export_cannot_pass(gate, retained_path):
    repo = repository(retained_path / 'repo'); revision = contract(repo)
    output = retained_path / 'export'; files = gate.export_contract(repo, revision, output)
    (output / 'schemas/envelope.schema.json').write_text('{"changed":true}')
    with pytest.raises(gate.GateError, match='导出内容'):
        gate.verify_export(repo, revision, output, files)
    # 安全移动模拟缺文件，不永久删除。
    (output / 'schemas/envelope.schema.json').rename(retained_path / 'retained-envelope.json')
    with pytest.raises(gate.GateError, match='导出内容'):
        gate.verify_export(repo, revision, output, files)


def test_failed_or_empty_runtime_vectors_remain_red(gate):
    with pytest.raises(gate.GateError, match='vectors'):
        gate.validate_results([{'vectorId':'bad','status':'fail','resultHash':'sha256:'+'a'*64}])
    with pytest.raises(gate.GateError, match='vectors'):
        gate.validate_results([])
    assert gate.validate_results([{'vectorId':'ok','status':'pass','resultHash':'sha256:'+'a'*64}]) == 1
    with pytest.raises(gate.GateError, match='vectors'):
        gate.validate_results([{'vectorId':'ok','status':'pass'}], {'ok', 'missing'})
    with pytest.raises(gate.GateError, match='vectors'):
        gate.validate_results([{'vectorId':'ok','status':'pass'}, {'vectorId':'ok','status':'pass'}])


def test_workflow_keeps_current_contract_checks_and_exact_fixture(gate):
    data = json.loads((ROOT / 'gateway-contract/historical-hermes-gateway.json').read_text())
    workflow = (ROOT / '.github/workflows/ci.yml').read_text()
    assert data['revision'] in workflow
    assert 'verify-historical-gateway-contract.py' in workflow
    assert 'check-contract-pin.py --plugin-root-env OPENCLAW_PLUGIN_ROOT' in workflow
    assert 'npm run gateway:v2:conformance' in workflow
    assert 'OPEN_ANDROID_GATEWAY_CONTRACT_ROOT: ${{ github.workspace }}/gateway-contract' in workflow


def test_runtime_vector_failure_rejects_complete_command(gate, retained_path, monkeypatch):
    repo = repository(retained_path / 'repo'); revision = contract(repo)
    plugin = retained_path / 'plugin'; head = plugin_fixture(plugin, revision)
    spec_path = retained_path / 'fixture.json'
    spec_path.write_text(json.dumps({'revision':head, 'repository':'https://github.com/1rua/hermes-android-gateway.git',
        'contractRepository':'https://github.com/1rua/open-android-intelligence.git', 'contractRevision':revision}))
    monkeypatch.setattr(gate, 'REPO_ROOT', repo)
    monkeypatch.setattr(gate, 'FIXTURE_SPEC', spec_path)
    monkeypatch.setenv('HERMES_PLUGIN_ROOT', str(plugin))
    output = retained_path / 'execution'
    monkeypatch.setattr(sys, 'argv', [str(SCRIPT), '--output', str(output)])
    actual = subprocess.run
    invoked = []
    def run(command, **kwargs):
        if command[1:3] == ['-c', gate.RUNTIME_PROBE]:
            invoked.append(command)
            return subprocess.CompletedProcess(command, 0, json.dumps([{'vectorId':'broken','status':'fail'}]), '')
        return actual(command, **kwargs)
    monkeypatch.setattr(gate.subprocess, 'run', run)
    assert gate.main() == 1
    assert len(invoked) == 1
    assert not (output / 'historical-contract-result.json').exists()


def run_complete_command(gate, retained_path, monkeypatch, rows, output):
    repo = repository(retained_path / 'repo')
    revision = contract(repo)
    plugin = retained_path / 'plugin'
    head = plugin_fixture(plugin, revision)
    spec_path = retained_path / 'fixture.json'
    spec_path.write_text(json.dumps({
        'revision': head,
        'repository': 'https://github.com/1rua/hermes-android-gateway.git',
        'contractRepository': 'https://github.com/1rua/open-android-intelligence.git',
        'contractRevision': revision,
    }))
    monkeypatch.setattr(gate, 'REPO_ROOT', repo)
    monkeypatch.setattr(gate, 'FIXTURE_SPEC', spec_path)
    monkeypatch.setenv('HERMES_PLUGIN_ROOT', str(plugin))
    actual = subprocess.run

    def run(command, **kwargs):
        if len(command) >= 3 and command[1] == '-c' and command[2] == gate.RUNTIME_PROBE:
            return subprocess.CompletedProcess(command, 0, json.dumps(rows), '')
        return actual(command, **kwargs)

    monkeypatch.setattr(gate.subprocess, 'run', run)
    monkeypatch.setattr(sys, 'argv', [str(SCRIPT), '--output', str(output)])
    return gate.main()


def test_complete_command_all_pass_produces_success_receipt(gate, retained_path, monkeypatch):
    output = retained_path / 'all-pass'
    rows = [{'vectorId': vector_id, 'status': 'pass'} for vector_id in VECTOR_IDS]
    assert run_complete_command(gate, retained_path, monkeypatch, rows, output) == 0
    report = json.loads((output / 'historical-contract-result.json').read_text())
    assert report['passed'] == len(VECTOR_IDS)
    assert report['failed'] == 0


def test_complete_command_failed_vector_reports_id(gate, retained_path, monkeypatch, capsys):
    output = retained_path / 'failed'
    rows = [{'vectorId': 'protocol-negotiation-minimal', 'status': 'fail'}]
    assert run_complete_command(gate, retained_path, monkeypatch, rows, output) == 1
    assert 'protocol-negotiation-minimal' in capsys.readouterr().err
    assert not (output / 'historical-contract-result.json').exists()


def test_complete_command_missing_vector_reports_missing_id(gate, retained_path, monkeypatch, capsys):
    output = retained_path / 'missing'
    rows = [{'vectorId': vector_id, 'status': 'pass'} for vector_id in VECTOR_IDS[:-1]]
    assert run_complete_command(gate, retained_path, monkeypatch, rows, output) == 1
    error = capsys.readouterr().err
    assert '缺少 device-requests-minimal' in error
    assert not (output / 'historical-contract-result.json').exists()


def test_complete_command_duplicate_vector_reports_id(gate, retained_path, monkeypatch, capsys):
    output = retained_path / 'duplicate'
    rows = [{'vectorId': vector_id, 'status': 'pass'} for vector_id in VECTOR_IDS]
    rows[-1] = rows[0]
    assert run_complete_command(gate, retained_path, monkeypatch, rows, output) == 1
    assert '出现重复：request-signatures-minimal' in capsys.readouterr().err
    assert not (output / 'historical-contract-result.json').exists()
