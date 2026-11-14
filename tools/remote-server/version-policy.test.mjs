import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { readFile } from 'node:fs/promises';
import { randomUUID, randomBytes } from 'node:crypto';
import { test } from 'node:test';
import WebSocket from 'ws';

// 直接执行未改固定Server，以有序ping/pong作为处理屏障，不用延时猜测selected是否已转发。
class Wire {
  frames = [];
  changed = new Set();
  constructor(socket) {
    this.socket = socket;
    socket.on('message', bytes => { this.frames.push(JSON.parse(bytes.toString())); for (const notify of this.changed) notify(); });
  }
  send(type, payload) { this.socket.send(JSON.stringify({ v: 1, id: randomUUID(), type, timestamp: Date.now(), payload })); }
  async next(type) {
    const timeout = AbortSignal.timeout(3000);
    while (true) {
      const index = this.frames.findIndex(frame => frame.type === type);
      if (index >= 0) return this.frames.splice(index, 1)[0].payload;
      if (timeout.aborted) throw Error(`未收到${type}`);
      await new Promise((resolve, reject) => {
        const done = () => { this.changed.delete(done); timeout.removeEventListener('abort', abort); resolve(); };
        const abort = () => { this.changed.delete(done); reject(Error(`未收到${type}`)); };
        this.changed.add(done); timeout.addEventListener('abort', abort, { once: true });
      });
    }
  }
  close() { this.socket.terminate(); }
}
test('固定Server只为合法OAI版本转发Relay选择并保留并存Client', async () => {
  const child = spawn(process.execPath, ['--experimental-vm-modules', new URL('./fixed-server.mjs', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'inherit'] });
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, 'line'); const server = JSON.parse(line); const wires = [];
  const versionFile = await readFile(new URL('../../apps/android/app/version.properties', import.meta.url), 'utf8');
  const version = `${/^versionName=(.+)$/m.exec(versionFile)[1]}+oai.android`;
  async function request(path, body, bearer) {
    const response = await fetch(server.origin + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) }, body: JSON.stringify(body) });
    assert.equal(response.status, 200); return response.json();
  }
  try {
    const login = await request('/api/v1/auth/login', { email: server.account, password: server.password });
    async function device(role, clientVersion) {
      const descriptor = { deviceId: randomUUID(), identityKey: randomBytes(32).toString('base64url'), name: 'isolated-version-probe', role, platform: 'android', clientVersion };
      const tokens = await request('/api/v1/devices/register', { v: 1, device: descriptor }, login.token);
      const socket = new WebSocket(server.origin.replace('http:', 'ws:') + '/ws/v1/connect'); const wire = new Wire(socket); wires.push(wire);
      await once(socket, 'open'); wire.send('hello', { role, deviceId: descriptor.deviceId, accessToken: tokens.accessToken, protocols: [1], capabilities: ['transport.relay'], clientVersion });
      await wire.next('hello.ack'); return { wire, descriptor };
    }
    for (const [clientVersion, modern] of [['oai-android', false], [version, true]]) {
      const host = await device('host', clientVersion); const first = await device('client', version);
      first.wire.send('connect.request', { hostDeviceId: host.descriptor.deviceId, preferredTransports: ['relay'] });
      const incoming = await host.wire.next('connect.incoming'); host.wire.send('connect.accepted', { connectionId: incoming.connectionId }); await first.wire.next('connect.accepted');
      first.wire.send('transport.selected', { connectionId: incoming.connectionId, targetDeviceId: host.descriptor.deviceId, transport: 'relay' });
      first.wire.send('ping', { nonce: 'selection-barrier' }); await first.wire.next('pong');
      host.wire.send('ping', { nonce: 'host-barrier' }); await host.wire.next('pong');
      assert.equal(host.wire.frames.some(frame => frame.type === 'transport.selected'), modern, clientVersion);
      const second = await device('client', version); second.wire.send('connect.request', { hostDeviceId: host.descriptor.deviceId, preferredTransports: ['relay'] });
      const next = await host.wire.next('connect.incoming'); host.wire.send('connect.accepted', { connectionId: next.connectionId }); await second.wire.next('connect.accepted');
      first.wire.send('ping', { nonce: 'replacement-barrier' }); await first.wire.next('pong');
      assert.equal(first.wire.frames.some(frame => frame.type === 'error' && frame.payload.code === 'CONNECTION_REPLACED'), !modern, clientVersion);
    }
  } finally {
    wires.forEach(wire => wire.close()); child.stdin.end(); await once(child, 'exit'); lines.close();
  }
});
