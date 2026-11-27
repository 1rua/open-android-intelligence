// E2E 仿真主机只读夹具校验、依赖解析与 Noise IK / DSHF 加密模块
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
} from 'node:crypto';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createRequire, stripTypeScriptTypes } from 'node:module';
import { delimiter, dirname, join, posix, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, '../../..');

/**
 * 按相对路径与主仓库通用目录收集候选 node_modules 路径（绝不硬编码本机路径）。
 */
export function collectNodeModulesDirs() {
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
  const envExtra = (process.env.OAI_NODE_FALLBACK_MODULES || '').split(delimiter).filter(Boolean);
  candidates.push(...envExtra);
  return [...new Set(candidates.filter((dir) => existsSync(dir)))];
}

const requireFromHere = createRequire(import.meta.url);

function getNodeModulesDirs() {
  return collectNodeModulesDirs();
}

function tryResolvePackageEntry(specifier) {
  for (const dir of getNodeModulesDirs()) {
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
 * 只读校验固定上游夹具目录的 source.json SHA-256，不修改锁定目录任何文件。
 */
export async function verifyFixtureCatalog(fixtureUrl) {
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

export function toBase64Url(bytes) {
  return Buffer.from(bytes).toString('base64url');
}

export function fromBase64Url(value) {
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

export function nativeGenerateX25519Pair(fixedPrivBytes) {
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

export class NativeNoiseIkSession {
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

export function createNoisePrologue(connectionId, hostDeviceId, clientDeviceId) {
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

export class NativeSecureMessageCodec {
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

  const vmMod = await import('node:vm');
  const { SourceTextModule, SyntheticModule } = vmMod;
  const hasVmModules =
    typeof SourceTextModule === 'function' && typeof SyntheticModule === 'function';

  let upstreamCrypto = null;
  if (
    hasVmModules &&
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
  if (hasVmModules && zodMod?.z) {
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
