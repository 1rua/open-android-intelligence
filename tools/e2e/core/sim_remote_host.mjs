// E2E 本地隔离仿真主机：专注账号注册、WS 事件、独立中继附着与 Harness V3 RPC 响应
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  fromBase64Url,
  loadRuntimeDependencies,
  toBase64Url,
} from './sim_remote_crypto.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * 解析命令行参数（同时兼容位置参数 origin 与环境变量回退）。
 */
function parseArgs(argv) {
  const opts = {
    origin: process.env.OAI_SIM_ORIGIN || '',
    account: process.env.OAI_SIM_ACCOUNT || 'fixture@example.test',
    password: process.env.OAI_SIM_PASSWORD || 'fixture-local-password',
    hostName: process.env.OAI_SIM_HOST_NAME || 'E2E Sim Host',
    expectPrefix: process.env.OAI_SIM_REPLY_PREFIX || 'E2E_PONG: ',
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--origin' && i + 1 < argv.length) {
      opts.origin = argv[++i];
    } else if (arg === '--account' && i + 1 < argv.length) {
      opts.account = argv[++i];
    } else if (arg === '--password' && i + 1 < argv.length) {
      opts.password = argv[++i];
    } else if (arg === '--host-name' && i + 1 < argv.length) {
      opts.hostName = argv[++i];
    } else if (arg === '--reply-prefix' && i + 1 < argv.length) {
      opts.expectPrefix = argv[++i];
    } else if (!arg.startsWith('--') && !opts.origin) {
      opts.origin = arg;
    }
  }
  if (!opts.origin) {
    throw new Error('缺少 --origin 参数（需传入本地 fixed-server 地址，例如 http://127.0.0.1:<port>）');
  }
  return opts;
}

/**
 * 读取 apps/android/app/version.properties 中的客户端版本，确保满足 Gateway 现代版本校验。
 */
function readClientVersion() {
  try {
    const props = readFileSync(resolve(__dirname, '../../../apps/android/app/version.properties'), 'utf8');
    const match = /^versionName=(.+)$/m.exec(props);
    if (match && match[1]) {
      return `${match[1].trim()}+oai.android`;
    }
  } catch {
    // 忽略读取异常
  }
  return '0.6.5+oai.android';
}

/**
 * 启动并运行本地 E2E 仿真 Host，完成协议注册、Noise IK 握手与 Harness V3 回环响应。
 */
async function runSimHost(opts) {
  const deps = await loadRuntimeDependencies();
  const { WebSocket, NoiseIkSession, SecureMessageCodec } = deps;
  const identity = {
    deviceId: randomUUID(),
    name: opts.hostName,
    ...deps.generateKeyPair(),
  };
  const clientVersion = readClientVersion();

  async function apiPost(path, body, bearer) {
    const res = await fetch(`${opts.origin}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`HTTP ${res.status} ${path}: ${text}`);
    }
    return res.json();
  }

  const login = await apiPost('/api/v1/auth/login', {
    email: opts.account,
    password: opts.password,
  });
  const tokens = await apiPost(
    '/api/v1/devices/register',
    {
      v: 1,
      device: {
        deviceId: identity.deviceId,
        name: identity.name,
        role: 'host',
        platform: 'linux',
        identityKey: identity.publicKey,
        clientVersion,
        harnessVersion: '0.2.2-rc.1',
      },
    },
    login.token,
  );

  // 仿真内存状态：一个工作区 + 初始默认会话
  const state = {
    workspace: {
      workspaceId: 'e2e-sim-ws',
      path: '/e2e-sim-workspace',
      title: 'E2E Sim Workspace',
      sessionIds: ['e2e-session-1'],
    },
    sessions: new Map([
      [
        'e2e-session-1',
        {
          sessionId: 'e2e-session-1',
          title: 'E2E Sim Session',
          createdAt: Date.now(),
          updatedAt: Date.now(),
          nextSeq: 0,
          turnCounter: 0,
          records: [],
        },
      ],
    ]),
    selectedModel: { provider: 'sim', model: 'e2e-sim-model' },
    sessionCounter: 1,
  };

  const wsUrl = opts.origin.replace(/^http/i, 'ws') + '/ws/v1/connect';
  const controlWs = new WebSocket(wsUrl);
  const tunnels = new Map();
  let shuttingDown = false;
  let readyEmitted = false;

  function sendControl(type, payload) {
    if (controlWs.readyState !== 1) return;
    controlWs.send(
      JSON.stringify({
        v: 1,
        id: randomUUID(),
        type,
        timestamp: Date.now(),
        payload,
      }),
    );
  }

  function closeAll() {
    if (shuttingDown) return;
    shuttingDown = true;
    for (const tunnel of tunnels.values()) {
      try {
        tunnel.noise?.destroy();
        tunnel.dataWs?.close();
      } catch {
        // 忽略关闭期异常
      }
    }
    tunnels.clear();
    try {
      if (controlWs.readyState === 1 || controlWs.readyState === 0) {
        controlWs.close();
      }
    } catch {
      // 忽略关闭异常
    }
  }

  const stdinReader = createInterface({ input: process.stdin });
  stdinReader.once('close', () => {
    closeAll();
    process.exit(0);
  });
  process.on('SIGINT', () => {
    closeAll();
    process.exit(0);
  });
  process.on('SIGTERM', () => {
    closeAll();
    process.exit(0);
  });

  function sendEncryptedMessage(tunnel, messageObj) {
    tunnel.sendChain = tunnel.sendChain.then(() => {
      if (!tunnel.noise || !tunnel.noise.complete || shuttingDown) return;
      const bytes = new TextEncoder().encode(JSON.stringify(messageObj));
      for (const plainChunk of tunnel.outgoingCodec.encode(bytes)) {
        const cipher = tunnel.noise.encrypt(plainChunk);
        const payload = {
          connectionId: tunnel.connectionId,
          targetDeviceId: tunnel.clientDeviceId,
          counter: tunnel.outgoingCounter++,
          ciphertext: toBase64Url(cipher),
        };
        if (tunnel.dataWs && tunnel.dataWs.readyState === 1) {
          tunnel.dataWs.send(
            JSON.stringify({
              v: 1,
              id: randomUUID(),
              type: 'relay',
              timestamp: Date.now(),
              payload,
            }),
          );
        } else {
          sendControl('relay', payload);
        }
      }
    }).catch((err) => {
      process.stderr.write(`[sim_remote_host] 发送加密报文异常: ${err.message}\n`);
    });
    return tunnel.sendChain;
  }

  function emitTunnelEvent(tunnel, eventName, data) {
    const seq = ++tunnel.eventSeq;
    return sendEncryptedMessage(tunnel, {
      v: 1,
      id: randomUUID(),
      type: 'event',
      timestamp: Date.now(),
      payload: {
        seq,
        event: eventName,
        data,
      },
    });
  }

  function emitHarnessFrame(tunnel, streamId, value) {
    return emitTunnelEvent(tunnel, 'harness.remote.frame', {
      streamId,
      hasValue: true,
      value,
    });
  }

  function getOrCreateSession(sessionId) {
    let sess = state.sessions.get(sessionId);
    if (!sess) {
      const now = Date.now();
      sess = {
        sessionId,
        title: `Session ${sessionId}`,
        createdAt: now,
        updatedAt: now,
        nextSeq: 0,
        turnCounter: 0,
        records: [],
      };
      state.sessions.set(sessionId, sess);
      if (!state.workspace.sessionIds.includes(sessionId)) {
        state.workspace.sessionIds.push(sessionId);
      }
    }
    return sess;
  }

  function unwrapCarrierArgs(params) {
    const payload = params?.payload;
    if (payload && typeof payload === 'object' && payload.args && typeof payload.args === 'object') {
      return payload.args;
    }
    return payload && typeof payload === 'object' ? payload : {};
  }

  function extractSessionIdFromArgs(args) {
    const req = args.request && typeof args.request === 'object' ? args.request : args;
    if (typeof req.sessionId === 'string' && req.sessionId) return req.sessionId;
    if (req.address && typeof req.address === 'object' && typeof req.address.sessionId === 'string') {
      return req.address.sessionId;
    }
    if (typeof args.agentId === 'string' && args.agentId) return args.agentId;
    return 'e2e-session-1';
  }

  async function handleHarnessCall(tunnel, endpoint, payload) {
    const args = payload && typeof payload === 'object' && payload.args && typeof payload.args === 'object'
      ? payload.args
      : (payload || {});
    const req = args.request && typeof args.request === 'object' ? args.request : args;

    switch (endpoint) {
      case 'workspace/list':
        return {
          ok: true,
          value: {
            items: [{ ...state.workspace }],
            archivedSessionIds: [],
            pinnedSessionIds: [],
          },
        };
      case 'session/list': {
        const items = Array.from(state.sessions.values()).map((s) => ({
          sessionId: s.sessionId,
          title: s.title,
          createdAt: s.createdAt,
          updatedAt: s.updatedAt,
          archived: false,
          running: false,
          blank: s.records.length === 0,
        }));
        return { ok: true, value: { items } };
      }
      case 'session/create': {
        state.sessionCounter += 1;
        const newId = `e2e-session-${state.sessionCounter}`;
        getOrCreateSession(newId);
        return { ok: true, value: { sessionId: newId } };
      }
      case 'session/page':
      case 'session/history': {
        const sessionId = extractSessionIdFromArgs(args);
        const sess = getOrCreateSession(sessionId);
        return {
          ok: true,
          value: {
            cursor: sess.nextSeq,
            records: sess.records,
            hasMore: false,
            activeTurnId: null,
          },
        };
      }
      case 'session/models':
        return {
          ok: true,
          value: {
            current: state.selectedModel,
            routable: true,
            groups: [
              {
                id: 'sim',
                name: 'E2E Sim Provider',
                models: [{ id: 'e2e-sim-model', name: 'E2E Sim Model' }],
              },
            ],
            failures: [],
          },
        };
      case 'session/selectModel': {
        state.selectedModel = {
          provider: req.provider || 'sim',
          model: req.model || 'e2e-sim-model',
          ...(req.reasoningEffort ? { reasoningEffort: req.reasoningEffort } : {}),
        };
        return { ok: true, value: { selected: state.selectedModel } };
      }
      case 'commands/list':
        return { ok: true, value: { catalogVersion: '1', items: [] } };
      case 'session/rename': {
        const sessionId = extractSessionIdFromArgs(args);
        const sess = getOrCreateSession(sessionId);
        const newTitle = typeof req.title === 'string' ? req.title : sess.title;
        sess.title = newTitle;
        sess.updatedAt = Date.now();
        return { ok: true, value: { title: newTitle, seq: sess.nextSeq } };
      }
      case 'session/cancel':
        return { ok: true, value: { accepted: true } };
      case '$events/result':
        return { ok: true, value: { ok: true } };
      case 'session/prompt': {
        const sessionId = extractSessionIdFromArgs(args);
        const sess = getOrCreateSession(sessionId);
        const requestId = typeof req.requestId === 'string' && req.requestId ? req.requestId : randomUUID();
        const content = Array.isArray(req.content) ? req.content : [];
        const userText = content
          .filter((part) => part && part.type === 'text' && typeof part.text === 'string')
          .map((part) => part.text)
          .join('\n');

        sess.turnCounter += 1;
        const turnNo = sess.turnCounter;
        const turnId = `turn-${turnNo}`;
        const now = Date.now();

        const turnStartRec = {
          type: 'event',
          event: {
            type: 'turn/start',
            seq: ++sess.nextSeq,
            time: now,
            data: { turnId, turn: turnNo },
          },
        };
        const userMsgRec = {
          type: 'event',
          event: {
            type: 'user/message',
            seq: ++sess.nextSeq,
            time: now + 1,
            data: {
              turnId,
              turn: turnNo,
              message: {
                id: `user-${sess.sessionId}-${turnNo}`,
                role: 'user',
                source: { rpcId: requestId },
                content: [{ type: 'text', text: userText }],
              },
            },
          },
        };
        const assistantMsgRec = {
          type: 'event',
          event: {
            type: 'assistant/message',
            seq: ++sess.nextSeq,
            time: now + 2,
            data: {
              turnId,
              turn: turnNo,
              message: {
                id: `assistant-${sess.sessionId}-${turnNo}`,
                role: 'assistant',
                content: [{ type: 'text', text: `${opts.expectPrefix}${userText}` }],
              },
            },
          },
        };
        const turnEndRec = {
          type: 'event',
          event: {
            type: 'turn/end',
            seq: ++sess.nextSeq,
            time: now + 3,
            data: {
              turnId,
              turn: turnNo,
              reason: { kind: 'completed' },
            },
          },
        };

        sess.records.push(turnStartRec, userMsgRec, assistantMsgRec, turnEndRec);
        sess.updatedAt = now + 3;

        // 在 rpc.response 发送后再顺序向当前跟踪该会话的 session/follow 流推送 4 帧生命周期事件
        setImmediate(() => {
          for (const [streamId, sub] of tunnel.streams.entries()) {
            if (sub.endpoint === 'session/follow' && sub.sessionId === sess.sessionId) {
              void emitHarnessFrame(tunnel, streamId, turnStartRec);
              void emitHarnessFrame(tunnel, streamId, userMsgRec);
              void emitHarnessFrame(tunnel, streamId, assistantMsgRec);
              void emitHarnessFrame(tunnel, streamId, turnEndRec);
            }
          }
        });

        return {
          ok: true,
          value: {
            accepted: true,
            requestId,
            turn: { id: turnId },
            generationId: turnId,
          },
        };
      }
      default:
        return {
          ok: false,
          error: { code: 'METHOD_NOT_FOUND', message: `Unsupported endpoint: ${endpoint}`, details: {} },
        };
    }
  }

  async function handleRpcRequest(tunnel, msg) {
    const requestId = msg.id;
    const method = msg.payload?.method;
    const params = msg.payload?.params || {};
    try {
      let result;
      if (method === 'harness.transport.describe') {
        result = {
          capabilities: ['harness.remote.v3'],
          workspaceTypes: [
            {
              id: 'harness',
              name: state.workspace.title,
              capability: 'harness.remote.v3',
              available: true,
            },
          ],
        };
      } else if (method === 'harness.remote.call') {
        result = await handleHarnessCall(tunnel, params.endpoint, params.payload);
      } else if (method === 'harness.remote.stream.open') {
        const streamId = params.streamId;
        const endpoint = params.endpoint;
        const args = unwrapCarrierArgs(params);
        const sessionId = endpoint === 'session/follow' ? extractSessionIdFromArgs(args) : undefined;
        tunnel.streams.set(streamId, { endpoint, sessionId });
        await sendEncryptedMessage(tunnel, {
          v: 1,
          id: randomUUID(),
          type: 'rpc.response',
          timestamp: Date.now(),
          payload: { requestId, result: {} },
        });
        if (endpoint === 'workspace/follow') {
          await emitHarnessFrame(tunnel, streamId, {
            type: 'baseline',
            value: {
              items: [{ ...state.workspace }],
              archivedSessionIds: [],
              pinnedSessionIds: [],
            },
          });
        } else if (endpoint === 'session/follow') {
          const sess = getOrCreateSession(sessionId);
          await emitHarnessFrame(tunnel, streamId, {
            type: 'snapshot',
            cursor: sess.nextSeq,
            hasMore: false,
            records: sess.records,
            projections: { asOfSeq: sess.nextSeq, values: {} },
          });
        } else if (endpoint === '$events') {
          await emitHarnessFrame(tunnel, streamId, {
            type: 'ready',
            clientId: 'e2e-sim-client-1',
            host: { home: state.workspace.path },
          });
        } else if (endpoint === 'session/control') {
          await emitHarnessFrame(tunnel, streamId, {
            type: 'baseline',
            value: { projections: {} },
          });
        }
        return;
      } else if (method === 'harness.remote.stream.close') {
        tunnel.streams.delete(params.streamId);
        result = {};
      } else {
        await sendEncryptedMessage(tunnel, {
          v: 1,
          id: randomUUID(),
          type: 'rpc.error',
          timestamp: Date.now(),
          payload: {
            requestId,
            code: 'METHOD_NOT_FOUND',
            message: `Unknown method: ${method}`,
          },
        });
        return;
      }

      await sendEncryptedMessage(tunnel, {
        v: 1,
        id: randomUUID(),
        type: 'rpc.response',
        timestamp: Date.now(),
        payload: { requestId, result },
      });
    } catch (err) {
      await sendEncryptedMessage(tunnel, {
        v: 1,
        id: randomUUID(),
        type: 'rpc.error',
        timestamp: Date.now(),
        payload: {
          requestId,
          code: 'RPC_FAILED',
          message: err instanceof Error ? err.message : String(err),
        },
      });
    }
  }

  function handleRelayCiphertext(tunnel, payload) {
    if (
      payload.connectionId !== tunnel.connectionId ||
      payload.targetDeviceId !== identity.deviceId ||
      payload.counter !== tunnel.incomingCounter
    ) {
      throw new Error('RELAY_BINDING_OR_COUNTER_MISMATCH');
    }
    tunnel.incomingCounter += 1;
    const cipher = fromBase64Url(payload.ciphertext);
    const plainChunk = tunnel.noise.decrypt(cipher);
    const messageBytes = tunnel.incomingCodec.decode(plainChunk);
    if (!messageBytes) return;
    const msg = JSON.parse(new TextDecoder().decode(messageBytes));
    if (msg.type === 'rpc.request') {
      void handleRpcRequest(tunnel, msg);
    }
  }

  function attachIndependentRelay(tunnel, relayEndpoint, relayTicket) {
    const dataWs = new WebSocket(relayEndpoint);
    tunnel.dataWs = dataWs;
    const onOpen = () => {
      dataWs.send(
        JSON.stringify({
          v: 1,
          id: randomUUID(),
          type: 'relay.attach',
          timestamp: Date.now(),
          payload: {
            connectionId: tunnel.connectionId,
            deviceId: identity.deviceId,
            peerDeviceId: tunnel.clientDeviceId,
            ticket: relayTicket,
          },
        }),
      );
    };
    const onMessage = (rawData) => {
      const text = typeof rawData === 'string'
        ? rawData
        : Buffer.from(rawData.data ?? rawData).toString('utf8');
      const frame = JSON.parse(text);
      if (frame.type === 'relay.ready') return;
      if (frame.type === 'ping') {
        dataWs.send(
          JSON.stringify({
            v: 1,
            id: randomUUID(),
            type: 'pong',
            timestamp: Date.now(),
            payload: frame.payload,
          }),
        );
        return;
      }
      if (frame.type === 'relay') {
        handleRelayCiphertext(tunnel, frame.payload);
      } else if (frame.type === 'secure.handshake') {
        handleSecureHandshake(tunnel, frame.payload);
      }
    };
    if (typeof dataWs.on === 'function') {
      dataWs.on('open', onOpen);
      dataWs.on('message', (bytes) => onMessage(bytes.toString()));
    } else {
      dataWs.onopen = onOpen;
      dataWs.onmessage = (ev) => onMessage(ev.data);
    }
  }

  function handleSecureHandshake(tunnel, payload) {
    if (payload.step !== 1 || payload.targetDeviceId !== identity.deviceId) {
      throw new Error('HANDSHAKE_BINDING_MISMATCH');
    }
    tunnel.noise.readHandshake(fromBase64Url(payload.data));
    const replyBytes = tunnel.noise.writeHandshake(new Uint8Array(0));
    sendControl('secure.handshake', {
      connectionId: tunnel.connectionId,
      targetDeviceId: tunnel.clientDeviceId,
      step: 2,
      data: toBase64Url(replyBytes),
    });
  }

  function onControlMessage(rawText) {
    const frame = JSON.parse(rawText);
    const p = frame.payload || {};
    switch (frame.type) {
      case 'hello.ack': {
        if (!readyEmitted) {
          readyEmitted = true;
          process.stdout.write(
            `${JSON.stringify({ status: 'READY', hostId: identity.deviceId, hostName: identity.name })}\n`,
          );
        }
        break;
      }
      case 'ping':
        sendControl('pong', p);
        break;
      case 'connect.incoming': {
        const connectionId = p.connectionId;
        const clientDeviceId = p.clientDeviceId;
        const prologue = deps.createNoisePrologue(connectionId, identity.deviceId, clientDeviceId);
        const noise = new NoiseIkSession({
          role: 'responder',
          localPrivateKey: identity.privateKey,
          localPublicKey: identity.publicKey,
          remotePublicKey: p.clientIdentityKey,
          prologue,
        });
        const tunnel = {
          connectionId,
          clientDeviceId,
          noise,
          incomingCodec: new SecureMessageCodec(),
          outgoingCodec: new SecureMessageCodec(),
          incomingCounter: 0,
          outgoingCounter: 0,
          eventSeq: 0,
          streams: new Map(),
          sendChain: Promise.resolve(),
          dataWs: null,
        };
        tunnels.set(connectionId, tunnel);
        sendControl('connect.accepted', { connectionId });
        if (typeof p.relayEndpoint === 'string' && typeof p.relayTicket === 'string') {
          attachIndependentRelay(tunnel, p.relayEndpoint, p.relayTicket);
        }
        break;
      }
      case 'transport.selected':
        break;
      case 'secure.handshake': {
        const tunnel = tunnels.get(p.connectionId);
        if (tunnel) handleSecureHandshake(tunnel, p);
        break;
      }
      case 'relay': {
        const tunnel = tunnels.get(p.connectionId);
        if (tunnel) handleRelayCiphertext(tunnel, p);
        break;
      }
      case 'error':
        if (p.connectionId && tunnels.has(p.connectionId)) {
          const dead = tunnels.get(p.connectionId);
          tunnels.delete(p.connectionId);
          dead?.noise?.destroy();
        }
        break;
      default:
        break;
    }
  }

  const onWsOpen = () => {
    sendControl('hello', {
      role: 'host',
      deviceId: identity.deviceId,
      accessToken: tokens.accessToken,
      protocols: [1],
      capabilities: [
        'transport.lan',
        'transport.p2p',
        'transport.turn',
        'transport.relay',
        'transport.relay.attach',
      ],
      clientVersion,
      harnessVersion: '0.2.2-rc.1',
    });
  };
  const onWsClose = () => {
    if (!shuttingDown) {
      closeAll();
      process.exit(0);
    }
  };
  const onWsError = (err) => {
    if (!shuttingDown) {
      process.stderr.write(`[sim_remote_host] 控制连接错误: ${err?.message || err}\n`);
    }
  };

  if (typeof controlWs.on === 'function') {
    controlWs.on('open', onWsOpen);
    controlWs.on('message', (bytes) => onControlMessage(bytes.toString('utf8')));
    controlWs.on('close', onWsClose);
    controlWs.on('error', onWsError);
  } else {
    controlWs.onopen = onWsOpen;
    controlWs.onmessage = (ev) => onControlMessage(String(ev.data));
    controlWs.onclose = onWsClose;
    controlWs.onerror = onWsError;
  }
}

const isEntrypoint = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isEntrypoint) {
  runSimHost(parseArgs(process.argv.slice(2))).catch((err) => {
    process.stderr.write(`[sim_remote_host] 启动失败: ${err instanceof Error ? err.stack || err.message : err}\n`);
    process.exit(1);
  });
}
