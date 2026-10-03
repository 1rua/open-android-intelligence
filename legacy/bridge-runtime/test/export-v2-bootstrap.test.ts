import { chmod, lstat, mkdir, mkdtemp, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  describeV2BootstrapImport,
  exportV2Bootstrap,
  projectV1BootstrapExport,
  validateV1BootstrapExport,
  V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION,
  type V1BootstrapExportDocument,
  type V1GatewayRecord,
  type V1PluginRecord,
} from "../tools/export-v2-bootstrap.js";

const HOME_FINGERPRINT = `sha256:${"a".repeat(64)}`;
const SMS_AUTHOR_KEY = `sha256:${"b".repeat(64)}`;

/** 真实的 v1 gateway 记录：白名单外的字段全部是必须被丢弃的秘密或本地状态。 */
const v1GatewayRecord = (overrides: Readonly<Record<string, unknown>> = {}): V1GatewayRecord =>
  Object.freeze({
    gatewayId: "gw-1",
    displayName: "Home",
    baseUrl: "https://gw.example",
    tlsSpkiSha256: HOME_FINGERPRINT,
    username: "owner",
    password: "correct-horse-battery-staple",
    refreshCredential: "refresh-credential-value",
    pairingPrivateKey: "ed25519-private-key-material",
    devicePrivateKey: "device-private-key-material",
    masterKey: "master-key-material",
    queue: Object.freeze({ pending: Object.freeze(["op-1", "op-2"]) }),
    pendingOperations: Object.freeze([Object.freeze({ id: "op-1", body: "send sms to +1" })]),
    databasePath: "/var/lib/open-android-intelligence/bridge.sqlite",
    ...overrides,
  });

/** 真实的 v1 插件记录：正文、附件、签名私钥与安装路径都必须被丢弃。 */
const v1PluginRecord = (overrides: Readonly<Record<string, unknown>> = {}): V1PluginRecord =>
  Object.freeze({
    id: "org.openandroidintelligence.sms",
    authorKeyId: SMS_AUTHOR_KEY,
    signingPrivateKey: "plugin-signing-private-key",
    authorPrivateKey: "author-private-key-material",
    installPath: "/var/lib/open-android-intelligence/plugins/org.openandroidintelligence.sms",
    capabilityBodies: Object.freeze([Object.freeze({ id: "mobile.sms.query", body: "SELECT * FROM sms" })]),
    attachments: Object.freeze([Object.freeze({ id: "att-1", path: "/var/lib/open-android-intelligence/attachments/att-1" })]),
    ...overrides,
  });

let root: string;
let dataDir: string;
let outFile: string;

const writeDataDir = async (
  gateways: readonly V1GatewayRecord[],
  plugins: readonly V1PluginRecord[],
  extraFiles: Readonly<Record<string, string>> = {},
): Promise<string> => {
  const dir = join(root, "v1-data");
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, "gateways.json"), `${JSON.stringify({ schemaVersion: "1.0", gateways }, null, 2)}\n`);
  await writeFile(join(dir, "plugins.json"), `${JSON.stringify({ schemaVersion: "1.0", plugins }, null, 2)}\n`);
  for (const [name, content] of Object.entries(extraFiles)) await writeFile(join(dir, name), content);
  return dir;
};

const modeOf = async (path: string): Promise<number> => (await stat(path)).mode & 0o777;

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), "open-android-intelligence-v1-bootstrap-"));
  outFile = join(root, "v2-bootstrap.json");
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
});

describe("v1 → v2 引导导出白名单", () => {
  it("只导出 Gateway 显示名、非秘密地址、证书指纹与插件 ID/作者 key ID", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });

    expect(exported).toEqual({
      schemaVersion: "1.0",
      gateways: [{ displayName: "Home", baseUrl: "https://gw.example", tlsSpkiSha256: HOME_FINGERPRINT }],
      plugins: [{ id: "org.openandroidintelligence.sms", authorKeyId: SMS_AUTHOR_KEY }],
    } satisfies V1BootstrapExportDocument);
  });

  it("序列化结果不含任何秘密字段字样", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });

    expect(JSON.stringify(exported)).not.toMatch(/private|password|token|queue|body/i);
  });

  it("不读取数据目录里的凭据、数据库与附件文件", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()], {
      "credentials.json": '{ "password": "SENTINEL-CANARY-VALUE" }',
      "bridge.sqlite": "SENTINEL-CANARY-VALUE",
      "master.key": "SENTINEL-CANARY-VALUE",
    });

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });

    expect(JSON.stringify(exported)).not.toContain("SENTINEL-CANARY-VALUE");
  });

  it("即使白名单字段的值里出现秘密字样也失败关闭", async () => {
    dataDir = await writeDataDir([v1GatewayRecord({ displayName: "Home password notebook" })], [v1PluginRecord()]);

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_SECRET_MATERIAL" });
  });

  it("纯函数投影不触碰文件系统", () => {
    const exported = projectV1BootstrapExport({ gateways: [v1GatewayRecord()], plugins: [v1PluginRecord()] });

    expect(exported.schemaVersion).toBe(V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION);
    expect(Object.keys(exported.gateways[0]!)).toEqual(["displayName", "baseUrl", "tlsSpkiSha256"]);
    expect(Object.keys(exported.plugins[0]!)).toEqual(["id", "authorKeyId"]);
  });
});

describe("引导导出 Schema 拒绝未知字段", () => {
  const valid: V1BootstrapExportDocument = Object.freeze({
    schemaVersion: "1.0",
    gateways: Object.freeze([Object.freeze({
      displayName: "Home",
      baseUrl: "https://gw.example",
      tlsSpkiSha256: HOME_FINGERPRINT,
    })]),
    plugins: Object.freeze([Object.freeze({ id: "org.openandroidintelligence.sms", authorKeyId: SMS_AUTHOR_KEY })]),
  });

  it("接受完全符合 Schema 的文档", () => {
    expect(() => validateV1BootstrapExport(valid)).not.toThrow();
  });

  it("拒绝顶层未知字段", () => {
    expect(() => validateV1BootstrapExport({ ...valid, pairingPrivateKey: "x" }))
      .toThrow(/V1_BOOTSTRAP_SCHEMA_VIOLATION/);
  });

  it("拒绝 Gateway 上的 Schema 外字段", () => {
    expect(() => validateV1BootstrapExport({
      ...valid,
      gateways: [{ ...valid.gateways[0]!, password: "x" }],
    })).toThrow(/V1_BOOTSTRAP_SCHEMA_VIOLATION/);
  });

  it("拒绝插件上的 Schema 外字段", () => {
    expect(() => validateV1BootstrapExport({
      ...valid,
      plugins: [{ ...valid.plugins[0]!, signingPrivateKey: "x" }],
    })).toThrow(/V1_BOOTSTRAP_SCHEMA_VIOLATION/);
  });

  it("拒绝夹带凭据的 Gateway 地址与非 https 地址", async () => {
    for (const baseUrl of ["https://user:pass@gw.example", "http://gw.example", "https://gw.example?token=1"]) {
      const dir = join(root, `bad-${Buffer.from(baseUrl).toString("hex")}`);
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, "gateways.json"), JSON.stringify({
        schemaVersion: "1.0",
        gateways: [v1GatewayRecord({ baseUrl })],
      }));
      await writeFile(join(dir, "plugins.json"), JSON.stringify({ schemaVersion: "1.0", plugins: [] }));
      await expect(exportV2Bootstrap({ dataDir: dir, out: join(root, `out-${Buffer.from(baseUrl).toString("hex")}.json`) }))
        .rejects.toMatchObject({ code: "V1_BOOTSTRAP_SCHEMA_VIOLATION" });
    }
  });

  it("拒绝形状错误的证书指纹与插件 ID", async () => {
    for (const tlsSpkiSha256 of ["sha256:AAAA", `sha512:${"a".repeat(64)}`, "a".repeat(64)]) {
      const dir = join(root, `fp-${Buffer.from(tlsSpkiSha256).toString("hex")}`);
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, "gateways.json"), JSON.stringify({
        schemaVersion: "1.0",
        gateways: [v1GatewayRecord({ tlsSpkiSha256 })],
      }));
      await writeFile(join(dir, "plugins.json"), JSON.stringify({ schemaVersion: "1.0", plugins: [] }));
      await expect(exportV2Bootstrap({ dataDir: dir, out: join(root, `fp-out-${Buffer.from(tlsSpkiSha256).toString("hex")}.json`) }))
        .rejects.toMatchObject({ code: "V1_BOOTSTRAP_SCHEMA_VIOLATION" });
    }
  });
});

describe("导出文件写入边界", () => {
  it("以 owner-only 权限写入并可在磁盘上重新读出", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });

    expect(JSON.parse(await readFile(outFile, "utf8"))).toEqual(exported);
    expect(await modeOf(outFile)).toBe(0o600);
  });

  it("拒绝覆盖已存在的输出文件", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    await writeFile(outFile, "existing", { mode: 0o644 });

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_OUTPUT_EXISTS" });
    expect(await readFile(outFile, "utf8")).toBe("existing");
  });

  it("拒绝把输出写到符号链接上", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    const target = join(root, "target.json");
    await writeFile(target, "existing", { mode: 0o644 });
    const link = join(root, "link.json");
    await symlink(target, link);

    await expect(exportV2Bootstrap({ dataDir, out: link }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_OUTPUT_SYMLINK" });
    expect(await readFile(target, "utf8")).toBe("existing");
  });

  it("拒绝输出到符号链接目录", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    const realDir = join(root, "real-out");
    await mkdir(realDir, { recursive: true });
    const linkDir = join(root, "link-out");
    await symlink(realDir, linkDir);

    await expect(exportV2Bootstrap({ dataDir, out: join(linkDir, "bootstrap.json") }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_OUTPUT_DIRECTORY_INVALID" });
  });

  it("拒绝输出到不存在的目录", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    await expect(exportV2Bootstrap({ dataDir, out: join(root, "missing", "bootstrap.json") }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_OUTPUT_DIRECTORY_INVALID" });
  });
});

describe("v1 数据目录只读端口", () => {
  it("拒绝符号链接的数据目录", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    const linkDir = join(root, "link-data");
    await symlink(dataDir, linkDir);

    await expect(exportV2Bootstrap({ dataDir: linkDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_DATA_DIR_SYMLINK" });
  });

  it("拒绝缺失的数据目录", async () => {
    await expect(exportV2Bootstrap({ dataDir: join(root, "missing"), out: outFile }))
      .rejects.toMatchObject({ code: "V1_DATA_DIR_INVALID" });
  });

  it("拒绝符号链接的索引文件", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    const elsewhere = join(root, "elsewhere.json");
    await writeFile(elsewhere, JSON.stringify({ schemaVersion: "1.0", gateways: [] }));
    await rm(join(dataDir, "gateways.json"));
    await symlink(elsewhere, join(dataDir, "gateways.json"));

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_BOOTSTRAP_INDEX_SYMLINK" });
  });

  it("拒绝结构损坏的索引文件", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    await writeFile(join(dataDir, "plugins.json"), "{ not json");

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_PLUGIN_INDEX_INVALID" });
  });

  it("拒绝非对象的 Gateway 记录", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    await writeFile(join(dataDir, "gateways.json"), JSON.stringify({ schemaVersion: "1.0", gateways: ["nope"] }));

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_GATEWAY_INDEX_INVALID" });
  });

  it("拒绝缺少白名单字段的记录而不是导出空值", async () => {
    dataDir = await writeDataDir([v1GatewayRecord({ tlsSpkiSha256: undefined })], [v1PluginRecord()]);

    await expect(exportV2Bootstrap({ dataDir, out: outFile }))
      .rejects.toMatchObject({ code: "V1_GATEWAY_FIELD_INVALID" });
  });
});

describe("v2 导入侧只创建本地 profile 与插件发现清单", () => {
  it("产物里没有凭据，并明确要求重新配对与重新授权", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });
    const plan = describeV2BootstrapImport(exported);

    expect(plan.profiles).toEqual([{
      displayName: "Home",
      baseUrl: "https://gw.example",
      tlsSpkiSha256: HOME_FINGERPRINT,
      pairingRequired: true,
    }]);
    expect(plan.pluginDiscovery).toEqual([{
      id: "org.openandroidintelligence.sms",
      authorKeyId: SMS_AUTHOR_KEY,
      authorizationRequired: true,
    }]);
    expect(plan.pairingRequired).toBe(true);
    expect(plan.authorizationRequired).toBe(true);
    expect(plan.notice).toContain("需要重新配对");
    expect(JSON.stringify(plan)).not.toMatch(/private|password|token|queue|body/i);
  });

  it("导入计划不引用数据库、队列或未确认操作", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);

    const exported = await exportV2Bootstrap({ dataDir, out: outFile });
    const plan = describeV2BootstrapImport(exported);

    expect(Object.keys(plan).sort()).toEqual(["authorizationRequired", "notice", "pairingRequired", "pluginDiscovery", "profiles", "schemaVersion"]);
    expect(JSON.stringify(plan)).not.toContain("bridge.sqlite");
    expect(JSON.stringify(plan)).not.toContain("op-1");
  });

  it("拒绝为空导出之外的非法文档生成导入计划", () => {
    expect(() => describeV2BootstrapImport({
      schemaVersion: "1.0",
      gateways: [{ displayName: "Home", baseUrl: "https://gw.example", tlsSpkiSha256: HOME_FINGERPRINT, password: "x" } as never],
      plugins: [],
    })).toThrow(/V1_BOOTSTRAP_SCHEMA_VIOLATION/);
  });
});

describe("输出文件权限的回归防护", () => {
  it("写入前不会跟随已存在的输出符号链接目标", async () => {
    dataDir = await writeDataDir([v1GatewayRecord()], [v1PluginRecord()]);
    const target = join(root, "owned-by-someone-else.json");
    await writeFile(target, "keep", { mode: 0o644 });
    await chmod(target, 0o644);
    await chmod(dirname(target), 0o755);
    const link = join(root, "dangling-or-live.json");
    await symlink(target, link);
    expect((await lstat(link)).isSymbolicLink()).toBe(true);

    await expect(exportV2Bootstrap({ dataDir, out: link })).rejects.toMatchObject({ code: "V1_BOOTSTRAP_OUTPUT_SYMLINK" });
    expect(await readFile(target, "utf8")).toBe("keep");
    expect(await modeOf(target)).toBe(0o644);
  });
});
