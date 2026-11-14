#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { BRIDGE_PREFIX } from './bridge.mjs';

const id = z.string().min(1).max(256);
const requestId = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/u);
const fixed = { hostId: id, backend: z.literal('harness'), workspaceId: z.literal('phone-capabilities') };
const task = { ...fixed, taskSessionId: id };
const server = new McpServer({ name: 'oai-android-mcp-server', version: '1.0.0' });
const base = new URL(process.env.OAI_ANDROID_BRIDGE_URL ?? 'http://127.0.0.1:0');
if (base.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(base.hostname) || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw new Error('BRIDGE_LOOPBACK_URL_REQUIRED');
const bearer = process.env.OAI_ANDROID_BRIDGE_BEARER;
if (!bearer) throw new Error('BRIDGE_BEARER_REQUIRED');
async function bridge(operation, args) {
  const response = await fetch(new URL(`${BRIDGE_PREFIX}/${operation}`, base), { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(60000), headers: { 'content-type': 'application/json', authorization: `Bearer ${bearer}` }, body: JSON.stringify(args) });
  if (!response.ok) throw new Error('BRIDGE_AUTHORIZATION_REJECTED');
  const result = await response.json(); if (!result.ok) throw new Error(result.error?.code ?? 'BRIDGE_CALL_FAILED'); return result.value;
}
function register(name, title, description, schema, operation, map = value => value, readOnly = true) {
  server.registerTool(name, { title, description, inputSchema: schema.strict(), annotations: { readOnlyHint: readOnly, destructiveHint: !readOnly, idempotentHint: readOnly, openWorldHint: true } }, async args => {
    try { const output = await bridge(operation, map(args)); return { content: [{ type: 'text', text: JSON.stringify(output) }], structuredContent: output }; }
    catch (error) { return { isError: true, content: [{ type: 'text', text: `手机工具失败：${error instanceof Error ? error.message : 'UNKNOWN'}。请查询原 request_id，勿自动重发副作用。` }] }; }
  });
}
register('android_bridge_status', '手机桥状态', '读取当前 DSH 本机手机桥是否可用。', z.object({}), 'status');
register('android_list_devices', '列出手机', '列出当前账号已授权且公开 phone-capabilities 工作区的手机。', z.object({}), 'list_devices');
register('android_list_capabilities', '手机能力状态', '读取指定手机可用性；执行时仍由手机已验证能力和本机授权裁决。', z.object(fixed), 'capabilities');
register('android_request_status', '查询请求回执', '从当前 control-bus 查询指定原任务的最小持久回执；不会恢复旧任务。', z.object({ ...task, request_id: requestId, targetRequestId: requestId }), 'request_status');
register('android_request_result', '读取当前任务结果', '读取当前进程仍存在的原任务结构化结果；正文失效时不会恢复或重放请求。', z.object({ ...task, targetRequestId: requestId }), 'request_result');
register('android_cancel_request', '取消指定请求', '仅取消原 taskSessionId 内的 targetRequestId；不会取消同任务其他请求。', z.object({ ...task, request_id: requestId, targetRequestId: requestId }), 'cancel', undefined, false);
register('android_read_artifact', '读取任务产物', '经原生受限文件面下载同一任务的 artifactId。', z.object({ ...task, artifactId: z.string().regex(/^[A-Za-z0-9_-]+$/u) }), 'read_artifact');
const snapshot = z.string().min(1);
function control(name, title, op, args, optionalTask = false, readOnly = false) {
  register(name, title, `${title}。后续调用必须保留明确的 taskSessionId 与 snapshotId，本机确认不会由远端自动回答。`,
    z.object({ ...fixed, taskSessionId: optionalTask ? id.optional() : id, request_id: requestId, args }), 'start',
    ({ request_id, args, ...target }) => ({ ...target, request: { request_id, op, args } }), readOnly);
}
control('android_get_ui_tree', '读取手机语义树', 'ui.dump_tree', z.object({ operation: z.enum(['snapshot', 'search', 'state']).optional(), maxNodes: z.number().int().min(1).max(512).optional(), maxDepth: z.number().int().min(0).max(32).optional(), maxBytes: z.number().int().min(4096).max(48000).optional(), text: id.optional(), resourceId: id.optional(), description: id.optional(), previousHash: id.optional() }).strict(), true, true);
control('android_capture_som_screenshot', '读取标注截图', 'ui.capture_som', z.object({}).strict(), true, true);
const point = { snapshotId: snapshot, nodeId: id.optional(), x: z.number().int().nonnegative().optional(), y: z.number().int().nonnegative().optional() };
control('android_tap', '点击手机', 'ui.tap', z.object(point).strict());
control('android_long_press', '长按手机', 'ui.long_press', z.object({ ...point, durationMs: z.number().int().min(50).max(3000).optional() }).strict());
for (const [name, op, title] of [['android_swipe', 'ui.swipe', '滑动手机'], ['android_drag', 'ui.drag', '拖动手机']]) control(name, title, op, z.object({ snapshotId: snapshot, x: z.number().int().nonnegative(), y: z.number().int().nonnegative(), endX: z.number().int().nonnegative(), endY: z.number().int().nonnegative(), durationMs: z.number().int().min(50).max(3000).optional() }).strict());
control('android_input_text', '输入手机文本', 'ui.input_text', z.object({ snapshotId: snapshot, nodeId: id, text: z.string().max(4096) }).strict());
control('android_scroll', '滚动手机节点', 'ui.scroll', z.object({ snapshotId: snapshot, nodeId: id, direction: z.enum(['forward', 'backward', 'up', 'down', 'left', 'right']) }).strict());
control('android_system_nav', '手机系统导航', 'ui.global_action', z.object({ snapshotId: snapshot, globalAction: z.enum(['back', 'home', 'recents', 'notifications', 'quickSettings']) }).strict());
control('android_launch_app', '启动手机应用', 'ui.launch_app', z.object({ snapshotId: snapshot, packageName: id }).strict());
control('android_invoke_capability', '调用已验证手机能力', 'plugin.invoke', z.object({ pluginId: id, capabilityId: id, method: id, params: z.record(z.string(), z.unknown()) }).strict());
for (const [name, plugin, capability, title, readOnly] of [
  ['android_query_sms', 'sms', 'sms.query', '查询手机短信', true],
  ['android_read_notifications', 'notifications', 'notifications.query', '读取手机通知', true],
  ['android_query_call_log', 'call-log', 'call-log.query', '查询手机通话记录', true],
  ['android_send_sms', 'sms', 'sms.send', '请求手机发送短信', false],
]) register(name, title, `${title}。参数严格由手机已验证 schema 校验；缺少真实能力时明确不可用。`,
  z.object({ ...task, request_id: requestId, params: z.record(z.string(), z.unknown()) }), 'start', ({ request_id, params, ...target }) => ({ ...target,
    request: { request_id, op: 'plugin.invoke', args: { pluginId: `org.openandroidintelligence.${plugin}`,
      capabilityId: `org.openandroidintelligence.${capability}@1.0.0`, method: capability.split('.').at(-1), params } } }), readOnly);
await server.connect(new StdioServerTransport());
