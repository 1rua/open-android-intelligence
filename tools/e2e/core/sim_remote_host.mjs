// E2E 本地隔离仿真主机：只读校验固定上游夹具，完成 Noise IK 握手与 Harness V3 协议回环
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  diffieHellman,
  generateKeyPairSync,
  createPrivateKey,
  createPublicKey,
  randomBytes,
  randomUUID,
} from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createRequire, stripTypeScriptTypes } from 'node:module';
import { dirname, join, posix, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { SourceTextModule, SyntheticModule } from 'node:vm';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, '../../..');

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
 * 按相对路径与主仓库通用目录收集候选 node_modules 路径（绝不硬编码本机路径）。
 */
function collectNodeModulesDirs() {
  const candidates = [
    resolve(__dirname, '../../remote-server/node_modules'),
    resolve(__dirname, '../../remote-interop/node_modules'),
    resolve(__dirname, '../../../integrations/oai-android-mcp/node_modules'),
    resolve(__dirname, '../../../node_modules'),
  ];
  try {
    const commonGit = execFileSync('git', ['-C', repoRoot, 'rev-parse', '--git-common-dir'], {
      encoding: 'utf8',
      timeout: 3000,
    }).trim();
    if (commonGit) {
      const mainRepo = resolve(repoRoot, commonGit, '..');
      candidates.push(
        join(mainRepo, 'tools/remote-server/node_modules'),
        join(mainRepo, 'tools/remote-interop/node_modules'),
        join(mainRepo, 'integrations/oai-android-mcp/node_modules'),
        join(mainRepo, 'node_modules'),
      );
    }
  } catch {
    // 非 git 环境或无父仓库时忽略
  }
  const envExtra = (process.env.OAI_NODE_FALLBACK_MODULES || '').split(':').filter(Boolean);
  candidates.push(...envExtra);
  return [...new Set(candidates.filter((dir) => existsSync(dir)))];
}

const requireFromHere = createRequire(import.meta.url);
const nodeModulesDirs = collectNodeModulesDirs();

function tryResolvePackageEntry(specifier) {
  for (const dir of nodeModulesDirs) {
    try {
      return requireFromHere.resolve(specifier, { paths: [dir] });
    } catch {
      // 继续尝试下一个候选目录
    }
  }
  try {
    return requireFromHere.resolve(specifier);
  } catch {
    return undefined;
  }
}

async function tryImportOptionalPackage(specifier) {
  const resolved = tryResolvePackageEntry(specifier);
  if (resolved) {
    return import(pathToFileURL(resolved).href);
  }
  try {
    return await import(specifier);
  } catch {
    return undefined;
  }
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
 * 只读校验固定上游夹具目录的 source.json SHA-256，不修改锁定目录任何文件。
 */
async function verifyFixtureCatalog(fixtureUrl) {
  const manifest = JSON.parse(await readFile(new URL('source.json', fixtureUrl), 'utf8'));
  const sources = new Map();
  for (const entry of manifest.files) {
    const relPath = entry.path ?? entry.fixture;
    if (!relPath) continue;
    const bytes = await readFile(new URL(relPath, fixtureUrl));
    const digest = createHash('sha256').update(bytes).digest('hex');
    if (digest !== entry.sha256) {
      throw new Error(`固定上游文件摘要不匹配: ${relPath}`);
    }
    if (relPath.endsWith('.ts')) {
      sources.set(relPath, bytes.toString('utf8'));
    }
  }
  return { manifest, sources };
}

// ---------- Base64URL（无填充）与 X25519 密钥辅助 ----------

function toBase64Url(bytes) {
  return Buffer.from(bytes).toString('base64url');
}

function fromBase64Url(value) {
  return new Uint8Array(Buffer.from(value, 'base64url'));
}

// X25519 PKCS8 / SPKI DER 固定头部（RFC 8410）
const X25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b656e04220420', 'hex');
const X25519_SPKI_PREFIX = Buffer.from('302a300506032b656e032100', 'hex');

function createX25519KeyObjectPrivate(rawPriv) {
  return createPrivateKey({
    key: Buffer.concat([X25519_PKCS8_PREFIX, Buffer.from(rawPriv)]),
    format: 'der',
    type: 'pkcs8',
  });
}

function createX25519KeyObjectPublic(rawPub) {
  return createPublicKey({
    key: Buffer.concat([X25519_SPKI_PREFIX, Buffer.from(rawPub)]),
    format: 'der',
    type: 'spki',
  });
}

function nativeGenerateX25519Pair(fixedPrivBytes) {
  if (fixedPrivBytes) {
    const privKeyObj = createX25519KeyObjectPrivate(fixedPrivBytes);
    const pubDer = createPublicKey(privKeyObj).export({ format: 'der', type: 'spki' });
    const pubRaw = pubDer.subarray(X25519_SPKI_PREFIX.length);
    return {
      privateBytes: new Uint8Array(fixedPrivBytes),
      publicBytes: new Uint8Array(pubRaw),
      privateKey: toBase64Url(fixedPrivBytes),
      publicKey: toBase64Url(pubRaw),
    };
  }
  const { publicKey, privateKey } = generateKeyPairSync('x25519');
  const privDer = privateKey.export({ format: 'der', type: 'pkcs8' });
  const pubDer = publicKey.export({ format: 'der', type: 'spki' });
  const privRaw = new Uint8Array(privDer.subarray(X25519_PKCS8_PREFIX.length));
  const pubRaw = new Uint8Array(pubDer.subarray(X25519_SPKI_PREFIX.length));
  return {
    privateBytes: privRaw,
    publicBytes: pubRaw,
    privateKey: toBase64Url(privRaw),
    publicKey: toBase64Url(pubRaw),
  };
}

function x25519Dh(privRaw, pubRaw) {
  const privObj = createX25519KeyObjectPrivate(privRaw);
  const pubObj = createX25519KeyObjectPublic(pubRaw);
  return new Uint8Array(diffieHellman({ privateKey: privObj, publicKey: pubObj }));
}

// ---------- Noise_IK_25519_ChaChaPoly_SHA256 纯 Node 原生兜底实现 ----------

const NOISE_PROTOCOL_NAME = Buffer.from('Noise_IK_25519_ChaChaPoly_SHA256', 'ascii'); // 刚好 32 字节

function sha256Bytes(...chunks) {
  const h = createHash('sha256');
  for (const c of chunks) h.update(c);
  return new Uint8Array(h.digest());
}

function hmacSha256(key, data) {
  return new Uint8Array(createHmac('sha256', key).update(data).digest());
}

function noiseHkdf2(chainingKey, ikm) {
  const tempKey = hmacSha256(chainingKey, ikm);
  const out1 = hmacSha256(tempKey, Uint8Array.of(0x01));
  const out2 = hmacSha256(tempKey, Buffer.concat([out1, Uint8Array.of(0x02)]));
  return [out1, out2];
}

function noiseNonce12(counter) {
  const nonce = Buffer.alloc(12);
  nonce.writeBigUInt64LE(BigInt(counter), 4);
  return nonce;
}

function aeadEncrypt(key, counter, ad, plaintext) {
  const cipher = createCipheriv('chacha20-poly1305', key, noiseNonce12(counter), {
    authTagLength: 16,
  });
  if (ad && ad.byteLength > 0) cipher.setAAD(ad);
  const body = cipher.update(plaintext);
  const tail = cipher.final();
  const tag = cipher.getAuthTag();
  return new Uint8Array(Buffer.concat([body, tail, tag]));
}

function aeadDecrypt(key, counter, ad, ciphertextWithTag) {
  if (ciphertextWithTag.byteLength < 16) {
    throw new Error('NOISE_AUTHENTICATION_FAILED');
  }
  const ct = ciphertextWithTag.subarray(0, ciphertextWithTag.byteLength - 16);
  const tag = ciphertextWithTag.subarray(ciphertextWithTag.byteLength - 16);
  const decipher = createDecipheriv('chacha20-poly1305', key, noiseNonce12(counter), {
    authTagLength: 16,
  });
  if (ad && ad.byteLength > 0) decipher.setAAD(ad);
  decipher.setAuthTag(tag);
  const p1 = decipher.update(ct);
  const p2 = decipher.final();
  return new Uint8Array(Buffer.concat([p1, p2]));
}

class NativeCipherState {
  constructor(key = null) {
    this.key = key;
    this.nonce = 0n;
  }
  hasKey() {
    return this.key !== null;
  }
  initializeKey(key) {
    this.key = key ? new Uint8Array(key) : null;
    this.nonce = 0n;
  }
  encryptWithAd(ad, plaintext) {
    if (!this.key) return new Uint8Array(plaintext);
    const out = aeadEncrypt(this.key, this.nonce, ad, plaintext);
    this.nonce += 1n;
    return out;
  }
  decryptWithAd(ad, ciphertext) {
    if (!this.key) return new Uint8Array(ciphertext);
    const out = aeadDecrypt(this.key, this.nonce, ad, ciphertext);
    this.nonce += 1n;
    return out;
  }
}

class NativeNoiseIkSession {
  constructor(options) {
    this.role = options.role; // 'initiator' | 'responder'
    this.s = nativeGenerateX25519Pair(fromBase64Url(options.localPrivateKey));
    this.expectedRemoteStatic = fromBase64Url(options.remotePublicKey);
    this.rs = this.role === 'initiator' ? new Uint8Array(this.expectedRemoteStatic) : null;
    this.e = null;
    this.re = null;
    this.fixedEphemeral = options.fixedEphemeralPrivateKey
      ? fromBase64Url(options.fixedEphemeralPrivateKey)
      : null;

    // InitializeSymmetric("Noise_IK_25519_ChaChaPoly_SHA256")
    this.h = new Uint8Array(NOISE_PROTOCOL_NAME);
    this.ck = new Uint8Array(NOISE_PROTOCOL_NAME);
    this.cipher = new NativeCipherState(null);

    // MixHash(prologue)
    this.mixHash(options.prologue);
    // IK 预消息: <- s （混合 responder 的静态公钥）
    if (this.role === 'initiator') {
      this.mixHash(this.rs);
    } else {
      this.mixHash(this.s.publicBytes);
    }

    this.step = 0; // 0: 等待 msg1, 1: 等待 msg2, 2: 完成
    this.sendCipher = null;
    this.recvCipher = null;
    this.destroyed = false;
  }

  get complete() {
    return this.step === 2 && !this.destroyed;
  }

  mixHash(data) {
    this.h = sha256Bytes(this.h, data);
  }

  mixKey(ikm) {
    const [newCk, tempK] = noiseHkdf2(this.ck, ikm);
    this.ck = newCk;
    this.cipher.initializeKey(tempK);
  }

  encryptAndHash(plaintext) {
    const ct = this.cipher.encryptWithAd(this.h, plaintext);
    this.mixHash(ct);
    return ct;
  }

  decryptAndHash(ciphertext) {
    const pt = this.cipher.decryptWithAd(this.h, ciphertext);
    this.mixHash(ciphertext);
    return pt;
  }

  split() {
    const [c1, c2] = noiseHkdf2(this.ck, new Uint8Array(0));
    const cs1 = new NativeCipherState(c1);
    const cs2 = new NativeCipherState(c2);
    if (this.role === 'initiator') {
      this.sendCipher = cs1;
      this.recvCipher = cs2;
    } else {
      this.sendCipher = cs2;
      this.recvCipher = cs1;
    }
    this.step = 2;
  }

  writeHandshake(payload = new Uint8Array()) {
    if (this.destroyed || this.complete) throw new Error('NOISE_STATE_INVALID');
    if (this.role === 'initiator' && this.step === 0) {
      this.e = nativeGenerateX25519Pair(this.fixedEphemeral);
      this.mixHash(this.e.publicBytes);
      this.mixKey(x25519Dh(this.e.privateBytes, this.rs));
      const encS = this.encryptAndHash(this.s.publicBytes);
      this.mixKey(x25519Dh(this.s.privateBytes, this.rs));
      const encPayload = this.encryptAndHash(payload);
      this.step = 1;
      return new Uint8Array(Buffer.concat([this.e.publicBytes, encS, encPayload]));
    }
    if (this.role === 'responder' && this.step === 1) {
      this.e = nativeGenerateX25519Pair(this.fixedEphemeral);
      this.mixHash(this.e.publicBytes);
      this.mixKey(x25519Dh(this.e.privateBytes, this.re));
      this.mixKey(x25519Dh(this.e.privateBytes, this.rs));
      const encPayload = this.encryptAndHash(payload);
      this.split();
      return new Uint8Array(Buffer.concat([this.e.publicBytes, encPayload]));
    }
    throw new Error('NOISE_HANDSHAKE_ORDER');
  }

  readHandshake(message) {
    if (this.destroyed || this.complete) throw new Error('NOISE_STATE_INVALID');
    if (this.role === 'responder' && this.step === 0) {
      if (message.byteLength < 32 + 48 + 16) throw new Error('NOISE_FRAME_INVALID');
      this.re = message.subarray(0, 32);
      this.mixHash(this.re);
      this.mixKey(x25519Dh(this.s.privateBytes, this.re));
      const encS = message.subarray(32, 80);
      this.rs = this.decryptAndHash(encS);
      if (!constantTimeEqual(this.rs, this.expectedRemoteStatic)) {
        this.destroy();
        throw new Error('PEER_IDENTITY_MISMATCH');
      }
      this.mixKey(x25519Dh(this.s.privateBytes, this.rs));
      const payload = this.decryptAndHash(message.subarray(80));
      this.step = 1;
      return payload;
    }
    if (this.role === 'initiator' && this.step === 1) {
      if (message.byteLength < 32 + 16) throw new Error('NOISE_FRAME_INVALID');
      this.re = message.subarray(0, 32);
      this.mixHash(this.re);
      this.mixKey(x25519Dh(this.e.privateBytes, this.re));
      this.mixKey(x25519Dh(this.s.privateBytes, this.re));
      const payload = this.decryptAndHash(message.subarray(32));
      this.split();
      return payload;
    }
    throw new Error('NOISE_HANDSHAKE_ORDER');
  }

  encrypt(plaintext) {
    if (!this.complete || !this.sendCipher) throw new Error('NOISE_NOT_READY');
    return this.sendCipher.encryptWithAd(null, plaintext);
  }

  decrypt(ciphertext) {
    if (!this.complete || !this.recvCipher) throw new Error('NOISE_NOT_READY');
    return this.recvCipher.decryptWithAd(null, ciphertext);
  }

  sendingCounter() {
    return this.sendCipher ? this.sendCipher.nonce : 0n;
  }

  receivingCounter() {
    return this.recvCipher ? this.recvCipher.nonce : 0n;
  }

  destroy() {
    this.destroyed = true;
    this.sendCipher = null;
    this.recvCipher = null;
  }
}

function constantTimeEqual(left, right) {
  if (!left || !right || left.byteLength !== right.byteLength) return false;
  let diff = 0;
  for (let i = 0; i < left.byteLength; i += 1) diff |= left[i] ^ right[i];
  return diff === 0;
}

function createNoisePrologue(connectionId, hostDeviceId, clientDeviceId) {
  return new TextEncoder().encode(
    `DSH-REMOTE\0v=1\0connection=${connectionId}\0host=${hostDeviceId}\0client=${clientDeviceId}`,
  );
}

// ---------- DSHF 分块编解码（与固定上游 protocol 100% 对齐） ----------

const SECURE_FRAGMENT_MAGIC = Uint8Array.of(0x44, 0x53, 0x48, 0x46); // DSHF
const SECURE_FRAGMENT_VERSION = 1;
const SECURE_FRAGMENT_HEADER_BYTES = 17;
const SECURE_FRAGMENT_CHUNK_BYTES = 48 * 1024;
const MAX_SECURE_MESSAGE_BYTES = 4 * 1024 * 1024;

class NativeSecureMessageCodec {
  constructor() {
    this.nextMessageId = 1;
    this.assemblies = new Map();
  }

  encode(message) {
    if (message.byteLength > MAX_SECURE_MESSAGE_BYTES) {
      throw new Error('SECURE_MESSAGE_TOO_LARGE');
    }
    if (message.byteLength <= SECURE_FRAGMENT_CHUNK_BYTES) {
      return [message];
    }
    const id = this.nextMessageId;
    this.nextMessageId = id === 0xffff_ffff ? 1 : id + 1;
    const total = Math.ceil(message.byteLength / SECURE_FRAGMENT_CHUNK_BYTES);
    const frames = [];
    for (let index = 0; index < total; index += 1) {
      const start = index * SECURE_FRAGMENT_CHUNK_BYTES;
      const chunk = message.subarray(
        start,
        Math.min(message.byteLength, start + SECURE_FRAGMENT_CHUNK_BYTES),
      );
      const frame = new Uint8Array(SECURE_FRAGMENT_HEADER_BYTES + chunk.byteLength);
      frame.set(SECURE_FRAGMENT_MAGIC);
      frame[4] = SECURE_FRAGMENT_VERSION;
      const view = new DataView(frame.buffer);
      view.setUint32(5, id);
      view.setUint16(9, index);
      view.setUint16(11, total);
      view.setUint32(13, message.byteLength);
      frame.set(chunk, SECURE_FRAGMENT_HEADER_BYTES);
      frames.push(frame);
    }
    return frames;
  }

  decode(frame) {
    if (
      frame.byteLength < SECURE_FRAGMENT_MAGIC.byteLength ||
      frame[0] !== 0x44 ||
      frame[1] !== 0x53 ||
      frame[2] !== 0x48 ||
      frame[3] !== 0x46
    ) {
      return frame;
    }
    if (frame.byteLength < SECURE_FRAGMENT_HEADER_BYTES || frame[4] !== SECURE_FRAGMENT_VERSION) {
      throw new Error('SECURE_FRAGMENT_HEADER_INVALID');
    }
    const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
    const id = view.getUint32(5);
    const index = view.getUint16(9);
    const total = view.getUint16(11);
    const totalBytes = view.getUint32(13);
    const expectedLen = Math.min(
      SECURE_FRAGMENT_CHUNK_BYTES,
      totalBytes - index * SECURE_FRAGMENT_CHUNK_BYTES,
    );
    const chunk = frame.subarray(SECURE_FRAGMENT_HEADER_BYTES);
    if (chunk.byteLength !== expectedLen) {
      throw new Error('SECURE_FRAGMENT_LENGTH_INVALID');
    }
    let entry = this.assemblies.get(id);
    if (!entry) {
      entry = { total, totalBytes, receivedBytes: 0, chunks: [] };
      this.assemblies.set(id, entry);
    }
    entry.chunks.push(new Uint8Array(chunk));
    entry.receivedBytes += chunk.byteLength;
    if (entry.chunks.length < total) return undefined;
    this.assemblies.delete(id);
    const merged = new Uint8Array(totalBytes);
    let offset = 0;
    for (const part of entry.chunks) {
      merged.set(part, offset);
      offset += part.byteLength;
    }
    return merged;
  }
}

/**
 * 加载并校验固定上游 protocol 与可选加密模块；当子包尚未安装时自动启用原生等价实现。
 */
export async function loadRuntimeDependencies() {
  const serverFixtureUrl = new URL('../../remote-server/fixtures/', import.meta.url);
  const interopFixtureUrl = new URL('../../remote-interop/fixtures/', import.meta.url);

  const [{ sources: serverSources }, { sources: interopSources }] = await Promise.all([
    verifyFixtureCatalog(serverFixtureUrl),
    verifyFixtureCatalog(interopFixtureUrl),
  ]);

  const wsMod = await tryImportOptionalPackage('ws');
  const WsCtor = wsMod?.WebSocket || wsMod?.default || globalThis.WebSocket;
  if (!WsCtor) {
    throw new Error('未找到可用的 WebSocket 客户端实现');
  }

  const zodMod = await tryImportOptionalPackage('zod');
  const clatterMod = await tryImportOptionalPackage('@lukeburns/clatterjs');
  const nobleCiphers = await tryImportOptionalPackage('@noble/ciphers/chacha');
  const nobleWebCrypto = await tryImportOptionalPackage('@noble/ciphers/webcrypto');
  const nobleCurves = await tryImportOptionalPackage('@noble/curves/ed25519');
  const nobleHashesSha = await tryImportOptionalPackage('@noble/hashes/sha256');
  const nobleHashesHkdf = await tryImportOptionalPackage('@noble/hashes/hkdf');

  let upstreamCrypto = null;
  if (
    clatterMod &&
    nobleCiphers &&
    nobleWebCrypto &&
    nobleCurves &&
    nobleHashesSha &&
    nobleHashesHkdf
  ) {
    const ext = new Map([
      ['@lukeburns/clatterjs', clatterMod],
      ['@noble/ciphers/chacha', nobleCiphers],
      ['@noble/ciphers/webcrypto', nobleWebCrypto],
      ['@noble/curves/ed25519', nobleCurves],
      ['@noble/hashes/sha256', nobleHashesSha],
      ['@noble/hashes/hkdf', nobleHashesHkdf],
    ]);
    const compiled = new Map();
    for (const [id, code] of interopSources.entries()) {
      if (id.startsWith('packages/crypto/src/')) {
        compiled.set(
          id,
          new SourceTextModule(stripTypeScriptTypes(code, { mode: 'transform' }), {
            identifier: id,
          }),
        );
      }
    }
    const synths = new Map();
    const entry = compiled.get('packages/crypto/src/index.ts');
    await entry.link((specifier, referencing) => {
      if (specifier.startsWith('.')) {
        const target = posix.normalize(
          posix.join(posix.dirname(referencing.identifier), specifier.replace(/\.js$/, '.ts')),
        );
        return compiled.get(target);
      }
      if (!synths.has(specifier)) {
        const values = ext.get(specifier);
        synths.set(
          specifier,
          new SyntheticModule(
            Object.keys(values),
            function () {
              for (const [k, v] of Object.entries(values)) this.setExport(k, v);
            },
            { identifier: specifier },
          ),
        );
      }
      return synths.get(specifier);
    });
    await entry.evaluate();
    upstreamCrypto = entry.namespace;
  }

  let upstreamProtocol = null;
  if (zodMod?.z) {
    const compiled = new Map();
    for (const [id, code] of serverSources.entries()) {
      if (id.startsWith('packages/protocol/src/')) {
        compiled.set(
          id,
          new SourceTextModule(stripTypeScriptTypes(code, { mode: 'transform' }), {
            identifier: id,
          }),
        );
      }
    }
    const zodSynth = new SyntheticModule(
      ['z'],
      function () {
        this.setExport('z', zodMod.z);
      },
      { identifier: 'zod' },
    );
    const entry = compiled.get('packages/protocol/src/index.ts');
    await entry.link((specifier, referencing) => {
      if (specifier === 'zod') return zodSynth;
      if (specifier.startsWith('.')) {
        const target = posix.normalize(
          posix.join(posix.dirname(referencing.identifier), specifier.replace(/\.js$/, '.ts')),
        );
        return compiled.get(target);
      }
      throw new Error(`protocol 未登记依赖: ${specifier}`);
    });
    await entry.evaluate();
    upstreamProtocol = entry.namespace;
  }

  return {
    WebSocket: WsCtor,
    NoiseIkSession: upstreamCrypto?.NoiseIkSession || NativeNoiseIkSession,
    createNoisePrologue: upstreamCrypto?.createNoisePrologue || createNoisePrologue,
    generateKeyPair: upstreamCrypto?.generateKeyPair || (() => {
      const pair = nativeGenerateX25519Pair();
      return { publicKey: pair.publicKey, privateKey: pair.privateKey };
    }),
    SecureMessageCodec: upstreamProtocol?.SecureMessageCodec || NativeSecureMessageCodec,
    usingUpstreamCrypto: Boolean(upstreamCrypto),
    usingUpstreamProtocol: Boolean(upstreamProtocol),
  };
}

// 导出纯原生实现以便单元测试进行交叉握手比对
export {
  NativeNoiseIkSession,
  NativeSecureMessageCodec,
  createNoisePrologue,
  nativeGenerateX25519Pair,
  toBase64Url,
  fromBase64Url,
};

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
