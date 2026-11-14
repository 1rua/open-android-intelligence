import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, symlink, writeFile } from 'node:fs/promises';
import { createRequire, stripTypeScriptTypes } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, normalize, resolve } from 'node:path';
import { test } from 'node:test';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import { pathToFileURL } from 'node:url';

// 复用 MCP 集成已锁定的 zod 依赖，不在探针中维护第二份依赖版本。
const integrationRequire = createRequire(new URL('../../integrations/oai-android-mcp/package.json', import.meta.url));

const fixtureRoot = new URL('./fixtures/media-upstream/', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('source.json', fixtureRoot), 'utf8'));
assert.equal(manifest.commit, 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7');
const sources = new Map();
for (const entry of manifest.files) {
  const bytes = await readFile(new URL(entry.path, fixtureRoot));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, entry.path);
  if (entry.gitBlob) assert.equal(createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'), entry.gitBlob, entry.path);
  sources.set(entry.path, bytes.toString('utf8'));
}

// 只替换未调用的终端与网络边界；文件 scope、路径、stat、分块、图片编码和客户端方法执行上游原文。
async function originalModules() {
  class TerminalState {}
  class TerminalController { closeAll() {} }
  class GatewayError extends Error { constructor(code, message) { super(message); this.code = code; } }
  class Gateway {
    constructor(core) { this.core = core; }
    call(endpoint, payload, signal) { return this.core.call(endpoint, payload, signal); }
  }
  const doubles = new Map([
    ['packages/plugin/src/remote-terminal-controller.ts', { RemoteTerminalController: TerminalController, RemoteTerminalState: TerminalState }],
    ['packages/plugin/src/remote-terminal-provider.ts', { pipeTerminalSpawner: () => { throw new Error('媒体探针不得启动终端'); } }],
    ['packages/plugin/src/terminal-policy.ts', { TerminalPolicy: class {} }],
    ['packages/client-core/src/remote-gateway.ts', { RemoteGatewayError: GatewayError, RemoteTypertGateway: Gateway, createRemoteId: () => 'probe-request' }],
    ['packages/client-core/src/harness-questions.ts', { HarnessQuestions: class {} }],
    ['@dsh-remote/protocol', { AGENT_ACP_TRANSFER_CHUNK_BYTES: 512 * 1024, MAX_AGENT_ACP_TRANSFER_BYTES: 288 * 1024 * 1024, REMOTE_COMPACTION_CAPABILITY: 'session.compact.v1' }],
  ]);
  const cache = new Map();
  function synthetic(id, values) {
    return new SyntheticModule(Object.keys(values), function () {
      for (const [name, value] of Object.entries(values)) this.setExport(name, value);
    }, { identifier: id });
  }
  async function module(id) {
    if (cache.has(id)) return cache.get(id);
    let result;
    if (doubles.has(id)) result = synthetic(id, doubles.get(id));
    else if (id.startsWith('node:')) result = synthetic(id, await import(id));
    else if (id === 'zod') result = synthetic(id, await import(pathToFileURL(integrationRequire.resolve('zod')).href));
    else if (id === '@dsh-remote/client-core') {
      const acp = await load('packages/client-core/src/acp-client.ts');
      result = synthetic(id, { AgentAcpClient: acp.AgentAcpClient, RemoteTypertGateway: Gateway });
    } else {
      assert.ok(sources.has(id), `缺少已登记上游原文：${id}`);
      result = new SourceTextModule(stripTypeScriptTypes(sources.get(id), { mode: 'transform', sourceUrl: `fixed-upstream/${id}` }), { identifier: id });
    }
    cache.set(id, result);
    return result;
  }
  async function load(id) {
    const root = await module(id);
    if (root.status === 'unlinked') await root.link((specifier, referencing) => {
      const target = specifier.startsWith('.') ? normalize(join(dirname(referencing.identifier), specifier)).replace(/\.js$/u, '.ts') : specifier;
      return module(target);
    });
    if (root.status === 'linked') await root.evaluate();
    return root.namespace;
  }
  return { load };
}
const original = await originalModules();

async function workspaceFiles() {
  // 测试现场留在临时目录供复核，不调用永久删除命令。
  const root = await mkdtemp(join(tmpdir(), 'oai-media-original-'));
  const outside = await mkdtemp(join(tmpdir(), 'oai-media-outside-'));
  const bytes = Buffer.alloc(600003);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  await writeFile(join(root, 'report.bin'), bytes);
  await writeFile(join(outside, 'private.bin'), '不能跨工作区读取');
  await symlink(join(outside, 'private.bin'), join(root, 'escape.bin'));
  return { root, outside, bytes };
}
function identity(value, root, bytes) {
  assert.equal(value.absolutePath, join(root, 'report.bin'));
  assert.equal(typeof value.version, 'string');
  assert.notEqual(value.version, '');
  assert.equal(value.bytes, bytes.length);
}

// Codex 的文件面实际由公开 Harness Gateway 转到 CodexWorkspaceBridge，不能虚构 codex.app 文件方法。
test('固定Codex工作区实现真实stat分块形状且每次重新核对Session工作区', async () => {
  const { CodexWorkspaceBridge } = await original.load('packages/plugin/src/codex-workspace-bridge.ts');
  const fixture = await workspaceFiles();
  let cwd = fixture.root;
  const seen = [];
  const bridge = new CodexWorkspaceBridge(async native => { seen.push(native); return cwd; }, () => false, undefined, () => { throw new Error('不应启动终端'); });
  const call = async (method, extra = {}) => {
    const result = await bridge.call(`workspaceFiles/${method}`, { args: { workspaceFileScopeId: 'codex:thread-one', path: 'report.bin', ...extra } }, new AbortController().signal);
    assert.equal(result.ok, true);
    return result.value;
  };
  const before = await call('stat');
  identity(before, fixture.root, fixture.bytes);
  const chunks = [];
  for (let offset = 0; offset < before.bytes;) {
    const length = Math.min(256 * 1024, before.bytes - offset);
    const chunk = await call('readBytes', { range: { offset, length } });
    identity(chunk, fixture.root, fixture.bytes);
    assert.equal(chunk.version, before.version);
    assert.equal(chunk.offset, offset);
    const bytes = Buffer.from(chunk.data, 'base64');
    assert.equal(bytes.toString('base64'), chunk.data);
    assert.equal(bytes.length, length);
    offset += bytes.length;
    assert.equal(chunk.eof, offset === before.bytes);
    chunks.push(bytes);
  }
  assert.deepEqual(Buffer.concat(chunks), fixture.bytes);
  assert.deepEqual(await call('stat'), before);
  assert.ok(seen.every(value => value === 'thread-one'));
  assert.equal(seen.length, 5);
  await assert.rejects(() => bridge.call('workspaceFiles/readBytes', { args: { workspaceFileScopeId: 'codex:thread-one', path: '../private.bin', range: { offset: 0, length: 1 } } }, new AbortController().signal), error => error.code === 'CODEX_WORKSPACE_PATH_DENIED');
  await assert.rejects(() => bridge.call('workspaceFiles/readBytes', { args: { workspaceFileScopeId: 'codex:thread-one', path: 'escape.bin', range: { offset: 0, length: 1 } } }, new AbortController().signal), error => error.code === 'CODEX_WORKSPACE_PATH_DENIED');
  cwd = fixture.outside;
  await assert.rejects(call('stat'), error => error.code === 'CODEX_WORKSPACE_PATH_DENIED');
  await bridge.closeAll();
});

test('原始ACP客户端工具链通过dsh/toolCall绑定backend及公开Session scope', async () => {
  const { WorkspaceBridge } = await original.load('packages/plugin/src/workspace-bridge.ts');
  const { AcpWorkspaceTools } = await original.load('packages/plugin/src/acp/workspace-tools.ts');
  const { AgentAcpClient } = await original.load('packages/client-core/src/acp-client.ts');
  const { AcpSessionTools } = await original.load('apps/android/src/services/session-tools.ts');
  const fixture = await workspaceFiles();
  for (const backend of ['hermes', 'antigravity']) {
    const prefix = backend === 'antigravity' ? 'acp' : backend;
    const bridge = new WorkspaceBridge(async native => { assert.equal(native, 'session-one'); return fixture.root; }, () => false, undefined,
      () => { throw new Error('不应启动终端'); }, { parse: value => typeof value === 'string' && value.startsWith(`${prefix}:`)
        ? { sessionId: value, nativeId: value.slice(prefix.length + 1) } : undefined, domain: 'ACP', owns: value => typeof value === 'string' && value.startsWith(`${prefix}:`) });
    const host = new AcpWorkspaceTools(bridge, undefined);
    const requests = [];
    const client = new AgentAcpClient({ async rpc(method, envelope) {
      requests.push({ method, envelope });
      assert.equal(method, 'agent.acp.call');
      assert.equal(envelope.method, 'dsh/toolCall');
      const params = envelope.params;
      assert.equal(params.backend, backend);
      assert.equal(params.sessionId, 'session-one');
      assert.equal(params.args.workspaceFileScopeId, `${prefix}:session-one`);
      return host.call(params.sessionId, params.backend, params.endpoint, params.args);
    } });
    const tools = new AcpSessionTools(client, backend);
    const stat = await tools.statFile(`${prefix}:session-one`, 'report.bin');
    identity(stat, fixture.root, fixture.bytes);
    const chunk = await tools.readBytes(`${prefix}:session-one`, 'report.bin', 0, 262144);
    identity(chunk, fixture.root, fixture.bytes);
    assert.equal(chunk.offset, 0); assert.equal(chunk.eof, false);
    assert.deepEqual(Buffer.from(chunk.data, 'base64'), fixture.bytes.subarray(0, 262144));
    assert.equal(requests.length, 2);
    await assert.rejects(host.call('session-one', backend, 'workspaceFiles/stat', { workspaceFileScopeId: `${prefix}:other`, path: 'report.bin' }), error => error.code === 'PERMISSION_DENIED');
    await assert.rejects(tools.statFile(`${prefix}:session-one`, 'escape.bin'), error => error.code === 'ACP_WORKSPACE_PATH_DENIED');
    await host.close();
  }
});

test('原始Harness客户端历史附件方法保留真实attachment目录引用和会话request', async () => {
  const { HarnessAlphaClient } = await original.load('packages/client-core/src/harness-alpha-client.ts');
  const calls = [];
  const attachment = { attachmentId: 'image-original', mediaType: 'image/png', bytes: 3, directory: 'authoritative-directory' };
  const client = new HarnessAlphaClient({ async call(endpoint, payload) {
    calls.push({ endpoint, payload });
    return { attachment, data: 'AQID' };
  } });
  assert.deepEqual(await client.sessionAttachment('session-one', attachment), { attachment, data: 'AQID' });
  assert.deepEqual(calls, [{ endpoint: 'session/attachment', payload: { args: { request: { sessionId: 'session-one', attachmentId: 'image-original', attachment } } } }]);
});

test('原始Codex图片编码器给出稳定attachment身份及metadata而不假造文件路径', async () => {
  const { contentBlocks } = await original.load('packages/plugin/src/codex/codex-image-codec.ts');
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7ZdhkAAAAASUVORK5CYII=';
  const block = contentBlocks([{ type: 'image', mediaType: 'image/png', data: png, name: '原图.png' }], 'thread:message').at(0);
  assert.equal(block.type, 'image');
  assert.match(block.attachment.attachmentId, /^codex-image:/u);
  assert.equal(block.attachment.mediaType, 'image/png');
  assert.equal(block.attachment.bytes, Buffer.from(png, 'base64').length);
  assert.equal(block.data, png);
  assert.equal(block.path, undefined);
  assert.deepEqual(contentBlocks([{ type: 'image', mediaType: 'image/png', data: png, name: '原图.png' }], 'thread:message').at(0).attachment, block.attachment);
});
