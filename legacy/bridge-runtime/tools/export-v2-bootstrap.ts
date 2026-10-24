import { randomBytes } from "node:crypto";
import { chmod, lstat, open, readFile, rename, rm } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import v1BootstrapExportSchema from "../../../gateway-contract/schemas/v1-bootstrap-export.schema.json" with { type: "json" };

/**
 * v1 → v2 安全引导导出（Task 16）。
 *
 * 这是一次"只读取、只白名单、只本地"的迁移读取：
 *
 * - 只通过 `V1BootstrapSourcePort` 读取 v1 数据目录里的两个索引文件，
 *   永远不打开数据库、密钥、队列、附件或正文文件；
 * - 只把 Gateway 显示名、非秘密地址、证书指纹与插件 ID/作者 key ID
 *   写进产物，其余字段在投影阶段就被丢弃，从不被读取；
 * - 产物必须同时通过 Schema 校验与秘密字样扫描才允许落盘；
 * - 落盘时拒绝符号链接与已存在文件，权限固定为 owner-only；
 * - 导入侧只能得到本地 profile 与插件发现清单，必须重新配对并重新授权
 *   （ADR 0038：不复制配对私钥、队列或数据库身份）。
 *
 * 本工具不提供任何 v1 网络兼容层，也不导出可以绕过重新配对的凭据。
 */

export const V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION = "1.0" as const;
export const V1_BOOTSTRAP_SOURCE_PORT = "open-android-intelligence.v1-bootstrap-source.v1" as const;

const GATEWAY_INDEX_FILE = "gateways.json" as const;
const PLUGIN_INDEX_FILE = "plugins.json" as const;
const OWNER_ONLY_MODE = 0o600;
const V1_BOOTSTRAP_INDEX_MAX_BYTES = 8 * 1024 * 1024;

/** 白名单：Gateway 记录里唯一允许进入产物的字段。 */
export const GATEWAY_EXPORT_FIELDS = ["displayName", "baseUrl", "tlsSpkiSha256"] as const;
/** 白名单：插件记录里唯一允许进入产物的字段。 */
export const PLUGIN_EXPORT_FIELDS = ["id", "authorKeyId"] as const;

const NOTICE = "需要重新配对/授权：本次导出不含凭据、刷新凭据、配对私钥、队列、未确认操作、正文、附件、数据库与主密钥，导入端必须重新配对并重新授权。" as const;

/**
 * 秘密字样扫描。白名单字段名与合法取值都不会命中它；一旦某个白名单字段的
 * 取值里出现这些字样，说明上游把秘密塞进了本不该有秘密的位置，失败关闭。
 */
const SECRET_MATERIAL_PATTERN =
  /private|password|passwd|secret|token|refresh|credential|queue|body|attachment|master|apikey|cookie|bearer|signing|session/iu;

export class V1BootstrapExportError extends BridgeServiceError {
  readonly violations: readonly string[];

  constructor(code: string, violations: readonly string[] = []) {
    super(code);
    this.violations = Object.freeze([...violations]);
  }
}

export type V1GatewayRecord = Readonly<Record<string, unknown>>;
export type V1PluginRecord = Readonly<Record<string, unknown>>;

export type V1BootstrapGatewayProfile = Readonly<{
  displayName: string;
  baseUrl: string;
  tlsSpkiSha256: string;
}>;

export type V1BootstrapPluginEntry = Readonly<{
  id: string;
  authorKeyId: string;
}>;

export type V1BootstrapExportDocument = Readonly<{
  schemaVersion: typeof V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION;
  gateways: readonly V1BootstrapGatewayProfile[];
  plugins: readonly V1BootstrapPluginEntry[];
}>;

/**
 * v1 数据的只读端口。实现只能返回原始 v1 记录；如何裁剪由投影层决定，
 * 这样"秘密是否被丢弃"才是可以被独立测试的属性，而不是端口的巧合。
 */
export interface V1BootstrapSourcePort {
  readonly port: typeof V1_BOOTSTRAP_SOURCE_PORT;
  readGatewayProfiles(): Promise<readonly V1GatewayRecord[]>;
  readPluginRecords(): Promise<readonly V1PluginRecord[]>;
}

export type V2BootstrapProfileSeed = Readonly<{
  displayName: string;
  baseUrl: string;
  tlsSpkiSha256: string;
  pairingRequired: true;
}>;

export type V2BootstrapPluginSeed = Readonly<{
  id: string;
  authorKeyId: string;
  authorizationRequired: true;
}>;

export type V2BootstrapImportPlan = Readonly<{
  schemaVersion: typeof V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION;
  profiles: readonly V2BootstrapProfileSeed[];
  pluginDiscovery: readonly V2BootstrapPluginSeed[];
  pairingRequired: true;
  authorizationRequired: true;
  notice: string;
}>;

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const codePointLength = (value: string): number => [...value].length;

const requiredString = (record: Readonly<Record<string, unknown>>, field: string, code: string): string => {
  const value = record[field];
  if (typeof value !== "string" || value.length === 0) throw new V1BootstrapExportError(code);
  return value;
};

// ---------------------------------------------------------------------------
// Schema 校验：以 `gateway-contract/schemas/v1-bootstrap-export.schema.json`
// 为唯一权威，只支持一个刻意收窄的关键字子集。遇到任何不认识的关键字就抛错，
// 因此后续有人往 Schema 里加关键字时这里会失败关闭，而不是静默放行。
// ---------------------------------------------------------------------------

type SchemaNode = Readonly<Record<string, unknown>>;

const SUBSCHEMA_KEYWORDS = new Set([
  "$ref", "type", "properties", "required", "additionalProperties", "items",
  "const", "pattern", "minLength", "maxLength", "minItems", "maxItems",
  "title", "description",
]);

const ROOT_KEYWORDS = new Set([...SUBSCHEMA_KEYWORDS, "$schema", "$id", "$defs"]);

const schemaDocument = v1BootstrapExportSchema as unknown as SchemaNode;

const integerKeyword = (node: SchemaNode, key: string): number | undefined => {
  const value = node[key];
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isSafeInteger(value)) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED");
  }
  return value;
};

const typeMatches = (expected: string, value: unknown): boolean => {
  switch (expected) {
    case "object": return isPlainObject(value);
    case "array": return Array.isArray(value);
    case "string": return typeof value === "string";
    case "boolean": return typeof value === "boolean";
    case "number": return typeof value === "number" && Number.isFinite(value);
    case "integer": return typeof value === "number" && Number.isSafeInteger(value);
    case "null": return value === null;
    default: throw new V1BootstrapExportError(`V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:type=${expected}`);
  }
};

const definitionFor = (root: SchemaNode, ref: string): SchemaNode => {
  const prefix = "#/$defs/";
  if (!ref.startsWith(prefix)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:$ref");
  const definitions = root["$defs"];
  if (!isPlainObject(definitions)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:$defs");
  const target = definitions[ref.slice(prefix.length)];
  if (!isPlainObject(target)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNRESOLVED_REF");
  return target as SchemaNode;
};

const validateNode = (
  node: SchemaNode,
  value: unknown,
  path: string,
  root: SchemaNode,
  violations: string[],
  keywords: ReadonlySet<string>,
): void => {
  for (const key of Object.keys(node)) {
    if (!keywords.has(key)) throw new V1BootstrapExportError(`V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:${key}`);
  }

  const ref = node["$ref"];
  if (ref !== undefined) {
    if (typeof ref !== "string") throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:$ref");
    if (Object.keys(node).length !== 1) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:$ref-siblings");
    validateNode(definitionFor(root, ref), value, path, root, violations, SUBSCHEMA_KEYWORDS);
    return;
  }

  const type = node["type"];
  if (type !== undefined) {
    if (typeof type !== "string") throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:type");
    if (!typeMatches(type, value)) {
      violations.push(`${path}\ttype\texpected=${type}`);
      return;
    }
  }

  if (typeof value === "string") {
    const pattern = node["pattern"];
    if (pattern !== undefined) {
      if (typeof pattern !== "string") throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:pattern");
      let compiled: RegExp;
      try {
        compiled = new RegExp(pattern, "u");
      } catch {
        throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:pattern");
      }
      if (!compiled.test(value)) violations.push(`${path}\tpattern\t${JSON.stringify(pattern)}`);
    }
    const minLength = integerKeyword(node, "minLength");
    if (minLength !== undefined && codePointLength(value) < minLength) {
      violations.push(`${path}\tminLength\t${minLength}`);
    }
    const maxLength = integerKeyword(node, "maxLength");
    if (maxLength !== undefined && codePointLength(value) > maxLength) {
      violations.push(`${path}\tmaxLength\t${maxLength}`);
    }
  }

  if (Array.isArray(value)) {
    const minItems = integerKeyword(node, "minItems");
    if (minItems !== undefined && value.length < minItems) violations.push(`${path}\tminItems\t${minItems}`);
    const maxItems = integerKeyword(node, "maxItems");
    if (maxItems !== undefined && value.length > maxItems) violations.push(`${path}\tmaxItems\t${maxItems}`);
    const items = node["items"];
    if (items !== undefined) {
      if (!isPlainObject(items)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:items");
      value.forEach((entry, index) => {
        validateNode(items as SchemaNode, entry, `${path}/${index}`, root, violations, SUBSCHEMA_KEYWORDS);
      });
    }
  }

  if (isPlainObject(value)) {
    const rawProperties = node["properties"];
    let properties: Record<string, unknown> | undefined;
    if (rawProperties !== undefined) {
      if (!isPlainObject(rawProperties)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:properties");
      properties = rawProperties;
    }
    const required = node["required"];
    if (required !== undefined) {
      if (!Array.isArray(required) || required.some((entry) => typeof entry !== "string")) {
        throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:required");
      }
      for (const key of required as readonly string[]) {
        if (!Object.hasOwn(value, key)) violations.push(`${path}\trequired\t${key}`);
      }
    }
    // 产物里的每个对象都必须是闭合的：未知的 Schema 外字段一律拒绝。
    if (node["additionalProperties"] !== false) {
      throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:additionalProperties");
    }
    for (const key of Object.keys(value)) {
      const child = properties?.[key];
      if (child === undefined) {
        violations.push(`${path}\tadditionalProperties\t${key}`);
        continue;
      }
      if (!isPlainObject(child)) throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_UNSUPPORTED:properties");
      validateNode(child as SchemaNode, value[key], `${path}/${key}`, root, violations, SUBSCHEMA_KEYWORDS);
    }
  }

  const constant = node["const"];
  if (constant !== undefined && JSON.stringify(value) !== JSON.stringify(constant)) {
    violations.push(`${path}\tconst\t${JSON.stringify(constant)}`);
  }
};

/** 用权威 Schema 校验引导导出文档；任何 Schema 外字段都会失败关闭。 */
export const validateV1BootstrapExport = (document: unknown): void => {
  const violations: string[] = [];
  validateNode(schemaDocument, document, "", schemaDocument, violations, ROOT_KEYWORDS);
  if (violations.length > 0) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_SCHEMA_VIOLATION", violations);
  }
};

const assertNoSecretMaterial = (document: unknown): void => {
  const match = SECRET_MATERIAL_PATTERN.exec(JSON.stringify(document));
  if (match !== null) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_SECRET_MATERIAL", [match[0]]);
  }
};

// ---------------------------------------------------------------------------
// 投影：只读白名单字段，其余字段永远不会被取值
// ---------------------------------------------------------------------------

export type V1BootstrapProjectionInput = Readonly<{
  gateways: readonly V1GatewayRecord[];
  plugins: readonly V1PluginRecord[];
}>;

export const projectV1BootstrapExport = (input: V1BootstrapProjectionInput): V1BootstrapExportDocument => {
  if (!isPlainObject(input) || !Array.isArray(input.gateways) || !Array.isArray(input.plugins)) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_PROJECTION_INVALID");
  }
  const gateways = input.gateways.map((record) => {
    if (!isPlainObject(record)) throw new V1BootstrapExportError("V1_GATEWAY_INDEX_INVALID");
    return Object.freeze({
      [GATEWAY_EXPORT_FIELDS[0]]: requiredString(record, "displayName", "V1_GATEWAY_FIELD_INVALID"),
      [GATEWAY_EXPORT_FIELDS[1]]: requiredString(record, "baseUrl", "V1_GATEWAY_FIELD_INVALID"),
      [GATEWAY_EXPORT_FIELDS[2]]: requiredString(record, "tlsSpkiSha256", "V1_GATEWAY_FIELD_INVALID"),
    }) as V1BootstrapGatewayProfile;
  });
  const plugins = input.plugins.map((record) => {
    if (!isPlainObject(record)) throw new V1BootstrapExportError("V1_PLUGIN_INDEX_INVALID");
    return Object.freeze({
      [PLUGIN_EXPORT_FIELDS[0]]: requiredString(record, "id", "V1_PLUGIN_FIELD_INVALID"),
      [PLUGIN_EXPORT_FIELDS[1]]: requiredString(record, "authorKeyId", "V1_PLUGIN_FIELD_INVALID"),
    }) as V1BootstrapPluginEntry;
  });
  const document: V1BootstrapExportDocument = Object.freeze({
    schemaVersion: V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION,
    gateways: Object.freeze(gateways),
    plugins: Object.freeze(plugins),
  });
  validateV1BootstrapExport(document);
  assertNoSecretMaterial(document);
  return document;
};

/**
 * 导入侧计划：只创建本地 profile 与插件发现清单，不携带任何凭据、队列或
 * 数据库身份，因此必须重新配对并重新授权。
 */
export const describeV2BootstrapImport = (document: V1BootstrapExportDocument): V2BootstrapImportPlan => {
  validateV1BootstrapExport(document);
  assertNoSecretMaterial(document);
  return Object.freeze({
    schemaVersion: V1_BOOTSTRAP_EXPORT_SCHEMA_VERSION,
    profiles: Object.freeze(document.gateways.map((gateway) => Object.freeze({
      displayName: gateway.displayName,
      baseUrl: gateway.baseUrl,
      tlsSpkiSha256: gateway.tlsSpkiSha256,
      pairingRequired: true as const,
    }))),
    pluginDiscovery: Object.freeze(document.plugins.map((plugin) => Object.freeze({
      id: plugin.id,
      authorKeyId: plugin.authorKeyId,
      authorizationRequired: true as const,
    }))),
    pairingRequired: true as const,
    authorizationRequired: true as const,
    notice: NOTICE,
  });
};

// ---------------------------------------------------------------------------
// 只读 v1 数据目录端口
// ---------------------------------------------------------------------------

const readIndexFile = async (path: string, arrayKey: string, code: string): Promise<readonly Record<string, unknown>[]> => {
  let stats;
  try {
    // lstat：符号链接必须先被拒绝，否则"只读"就可能变成"读取任意路径"。
    stats = await lstat(path);
  } catch {
    throw new V1BootstrapExportError(code);
  }
  if (stats.isSymbolicLink()) throw new V1BootstrapExportError("V1_BOOTSTRAP_INDEX_SYMLINK");
  if (!stats.isFile()) throw new V1BootstrapExportError(code);
  if (stats.size > V1_BOOTSTRAP_INDEX_MAX_BYTES) throw new V1BootstrapExportError("V1_BOOTSTRAP_INDEX_TOO_LARGE");
  let parsed: unknown;
  try {
    parsed = JSON.parse(await readFile(path, "utf8"));
  } catch {
    throw new V1BootstrapExportError(code);
  }
  if (!isPlainObject(parsed) || !Array.isArray(parsed[arrayKey])) throw new V1BootstrapExportError(code);
  return (parsed[arrayKey] as readonly unknown[]).map((entry) => {
    if (!isPlainObject(entry)) throw new V1BootstrapExportError(code);
    return Object.freeze({ ...entry });
  });
};

/**
 * 打开 v1 数据目录的只读端口。
 *
 * 只读取 `gateways.json` 与 `plugins.json` 两个索引文件；目录里的数据库、
 * 密钥、队列、附件与正文文件永远不会被打开，因此按构造就不可能进入产物。
 */
export const openV1BootstrapDataDirectory = async (dataDir: string): Promise<V1BootstrapSourcePort> => {
  if (typeof dataDir !== "string" || dataDir.length === 0) throw new V1BootstrapExportError("V1_DATA_DIR_INVALID");
  const resolved = resolve(dataDir);
  let stats;
  try {
    stats = await lstat(resolved);
  } catch {
    throw new V1BootstrapExportError("V1_DATA_DIR_INVALID");
  }
  if (stats.isSymbolicLink()) throw new V1BootstrapExportError("V1_DATA_DIR_SYMLINK");
  if (!stats.isDirectory()) throw new V1BootstrapExportError("V1_DATA_DIR_INVALID");
  return Object.freeze({
    port: V1_BOOTSTRAP_SOURCE_PORT,
    readGatewayProfiles: async () =>
      (await readIndexFile(join(resolved, GATEWAY_INDEX_FILE), "gateways", "V1_GATEWAY_INDEX_INVALID")) as readonly V1GatewayRecord[],
    readPluginRecords: async () =>
      (await readIndexFile(join(resolved, PLUGIN_INDEX_FILE), "plugins", "V1_PLUGIN_INDEX_INVALID")) as readonly V1PluginRecord[],
  });
};

// ---------------------------------------------------------------------------
// 输出写入：拒绝符号链接、拒绝覆盖、owner-only
// ---------------------------------------------------------------------------

export const writeV1BootstrapExport = async (out: string, document: V1BootstrapExportDocument): Promise<string> => {
  if (typeof out !== "string" || out.length === 0) throw new V1BootstrapExportError("V1_BOOTSTRAP_OUTPUT_INVALID");
  validateV1BootstrapExport(document);
  assertNoSecretMaterial(document);
  const resolved = resolve(out);
  const parent = dirname(resolved);
  let parentStats;
  try {
    parentStats = await lstat(parent);
  } catch {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_OUTPUT_DIRECTORY_INVALID");
  }
  if (parentStats.isSymbolicLink() || !parentStats.isDirectory()) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_OUTPUT_DIRECTORY_INVALID");
  }
  let existing;
  try {
    existing = await lstat(resolved);
  } catch {
    existing = undefined;
  }
  if (existing !== undefined) {
    throw new V1BootstrapExportError(existing.isSymbolicLink()
      ? "V1_BOOTSTRAP_OUTPUT_SYMLINK"
      : "V1_BOOTSTRAP_OUTPUT_EXISTS");
  }
  const temporary = join(parent, `.${basename(resolved)}.tmp-${randomBytes(8).toString("hex")}`);
  const handle = await open(temporary, "wx", OWNER_ONLY_MODE);
  try {
    await handle.writeFile(`${JSON.stringify(document, null, 2)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  try {
    await rename(temporary, resolved);
    // rename 保留临时文件的权限；这里再显式收紧一次，避免 umask 影响。
    await chmod(resolved, OWNER_ONLY_MODE);
  } catch (caught) {
    await rm(temporary, { force: true }).catch(() => undefined);
    throw caught;
  }
  return resolved;
};

// ---------------------------------------------------------------------------
// 编排与命令行入口
// ---------------------------------------------------------------------------

export type ExportV2BootstrapOptions = Readonly<{
  out: string;
  dataDir?: string;
  source?: V1BootstrapSourcePort;
}>;

export const exportV2Bootstrap = async (options: ExportV2BootstrapOptions): Promise<V1BootstrapExportDocument> => {
  if (!isPlainObject(options)) throw new V1BootstrapExportError("V1_BOOTSTRAP_OPTIONS_INVALID");
  if (typeof options.out !== "string" || options.out.length === 0) {
    throw new V1BootstrapExportError("V1_BOOTSTRAP_OUTPUT_INVALID");
  }
  const source = options.source ?? await openV1BootstrapDataDirectory(
    typeof options.dataDir === "string" ? options.dataDir : "",
  );
  if (source.port !== V1_BOOTSTRAP_SOURCE_PORT) throw new V1BootstrapExportError("V1_BOOTSTRAP_SOURCE_INVALID");
  const document = projectV1BootstrapExport({
    gateways: await source.readGatewayProfiles(),
    plugins: await source.readPluginRecords(),
  });
  await writeV1BootstrapExport(options.out, document);
  return document;
};

const USAGE = "usage: export-v2-bootstrap --data-dir <v1> --out <json>" as const;

const argument = (argv: readonly string[], name: string): string | null => {
  const index = argv.indexOf(name);
  if (index === -1) return null;
  const value = argv[index + 1];
  return typeof value === "string" && value.length > 0 && !value.startsWith("--") ? value : null;
};

export const runExportV2BootstrapCli = async (argv: readonly string[]): Promise<number> => {
  if (argv.includes("--help") || argv.includes("-h")) {
    console.log(USAGE);
    return 0;
  }
  const dataDir = argument(argv, "--data-dir");
  const out = argument(argv, "--out");
  if (dataDir === null || out === null) {
    console.error(USAGE);
    return 1;
  }
  try {
    const document = await exportV2Bootstrap({ dataDir, out });
    const plan = describeV2BootstrapImport(document);
    console.log(`V1_BOOTSTRAP_EXPORT_OK out=${out} gateways=${document.gateways.length} plugins=${document.plugins.length} schema=${document.schemaVersion}`);
    console.log(plan.notice);
    return 0;
  } catch (caught) {
    const code = caught instanceof BridgeServiceError ? caught.code : "V1_BOOTSTRAP_EXPORT_FAILED";
    const detail = caught instanceof V1BootstrapExportError && caught.violations.length > 0
      ? ` violations=${caught.violations.join("|")}`
      : "";
    console.error(`V1_BOOTSTRAP_EXPORT_FAIL code=${code}${detail}`);
    return 1;
  }
};

const invokedDirectly = (): boolean => {
  const entry = process.argv[1];
  if (entry === undefined) return false;
  try {
    return import.meta.url === pathToFileURL(entry).href;
  } catch {
    return false;
  }
};

if (invokedDirectly()) {
  process.exitCode = await runExportV2BootstrapCli(process.argv.slice(2));
}
