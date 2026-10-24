import capabilityRegistryFixture from "../registries/v1/capabilities.json" with { type: "json" };
import { canonicalBytes, sha256B64Url } from "./encoding.js";

export type CapabilityFilterSchema = "none" | "capability_specific_v1";
export type CapabilityRisk = "L0" | "L1" | "L2" | "L3" | "L4";
export type CapabilitySensitivity = "metadata" | "content";
export type CapabilityOfflinePolicy = "WAIT_READ" | "FAIL_OFFLINE";
export type CapabilityApproval = "continuous" | "per_operation" | "per_operation_on_device";

export interface CapabilityDefinition {
  readonly scope: string;
  readonly filterSchema: CapabilityFilterSchema;
  readonly riskFloor: CapabilityRisk;
  readonly sensitivity: CapabilitySensitivity;
  readonly offlinePolicy: CapabilityOfflinePolicy;
  readonly minimumApproval: CapabilityApproval;
  readonly allowedBackends: readonly string[];
  readonly invariantIds: readonly string[];
}

export interface CapabilityRegistryDocument {
  readonly $schema: string;
  readonly registryId: string;
  readonly protocolVersion: string;
  readonly registryVersion: string;
  readonly capabilities: readonly CapabilityDefinition[];
}

export interface CapabilityManifestEntry {
  readonly scope: string;
  readonly schemaSupported: boolean;
  readonly backendAvailable: boolean;
  readonly systemPermission: boolean;
  readonly localConsent: boolean;
  readonly agentScopeAllowed: boolean;
}

export interface CapabilityManifest {
  readonly registryVersion: string;
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly capabilities: readonly CapabilityManifestEntry[];
}

export interface CapabilityBinding {
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
}

export type CapabilityFilterValidation =
  | { readonly ok: true; readonly filter: unknown }
  | { readonly ok: false; readonly error: "SCHEMA_INVALID" | "SCOPE_DENIED" };

const REGISTRY_ID = "urn:open-android-intelligence:protocol:v1:registry:capabilities";
const REGISTRY_SCHEMA = "urn:open-android-intelligence:protocol:v1:capability-registry";
const VERSION = "1.0";
const FORBIDDEN_SCOPES = new Set(["shell.root", "shell.exec", "exec", "generic.exec", "script"]);
const SCOPE_PATTERN = /^[a-z][a-z0-9._<>-]{0,127}$/;
const RISK = new Set<CapabilityRisk>(["L0", "L1", "L2", "L3", "L4"]);
const SENSITIVITY = new Set<CapabilitySensitivity>(["metadata", "content"]);
const OFFLINE = new Set<CapabilityOfflinePolicy>(["WAIT_READ", "FAIL_OFFLINE"]);
const APPROVAL = new Set<CapabilityApproval>(["continuous", "per_operation", "per_operation_on_device"]);
const definitionKeys = ["scope", "filterSchema", "riskFloor", "sensitivity", "offlinePolicy", "minimumApproval", "allowedBackends", "invariantIds"];
const documentKeys = ["$schema", "registryId", "protocolVersion", "registryVersion", "capabilities"];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const exactKeys = (value: Record<string, unknown>, expected: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const sorted = [...expected].sort();
  return actual.length === sorted.length && actual.every((key, index) => key === sorted[index]);
};

const codePointCompare = (left: string, right: string): number => {
  const a = Array.from(left);
  const b = Array.from(right);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) {
    const leftPoint = a[index]?.codePointAt(0) ?? 0;
    const rightPoint = b[index]?.codePointAt(0) ?? 0;
    if (leftPoint !== rightPoint) return leftPoint - rightPoint;
  }
  return a.length - b.length;
};

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

const copyDefinition = (entry: CapabilityDefinition): CapabilityDefinition => Object.freeze({
  scope: entry.scope,
  filterSchema: entry.filterSchema,
  riskFloor: entry.riskFloor,
  sensitivity: entry.sensitivity,
  offlinePolicy: entry.offlinePolicy,
  minimumApproval: entry.minimumApproval,
  allowedBackends: Object.freeze([...entry.allowedBackends]),
  invariantIds: Object.freeze([...entry.invariantIds]),
});

const registryDocument = (input: unknown): CapabilityRegistryDocument => {
  if (!isRecord(input) || !exactKeys(input, documentKeys)
    || input.$schema !== REGISTRY_SCHEMA || input.registryId !== REGISTRY_ID
    || input.protocolVersion !== VERSION || input.registryVersion !== VERSION
    || !Array.isArray(input.capabilities) || input.capabilities.length === 0) {
    throw new Error("CAPABILITY_REGISTRY_INVALID");
  }
  const seen = new Set<string>();
  const capabilities = input.capabilities.map((value): CapabilityDefinition => {
    if (!isRecord(value) || !exactKeys(value, definitionKeys)
      || typeof value.scope !== "string" || !SCOPE_PATTERN.test(value.scope)
      || FORBIDDEN_SCOPES.has(value.scope) || value.scope.startsWith("shell.root.")
      || seen.has(value.scope) || (value.filterSchema !== "none" && value.filterSchema !== "capability_specific_v1")
      || typeof value.riskFloor !== "string" || !RISK.has(value.riskFloor as CapabilityRisk)
      || typeof value.sensitivity !== "string" || !SENSITIVITY.has(value.sensitivity as CapabilitySensitivity)
      || typeof value.offlinePolicy !== "string" || !OFFLINE.has(value.offlinePolicy as CapabilityOfflinePolicy)
      || typeof value.minimumApproval !== "string" || !APPROVAL.has(value.minimumApproval as CapabilityApproval)
      || !Array.isArray(value.allowedBackends) || !value.allowedBackends.every((item) => typeof item === "string")
      || !Array.isArray(value.invariantIds) || !value.invariantIds.every((item) => typeof item === "string")) {
      throw new Error("CAPABILITY_REGISTRY_INVALID");
    }
    if (value.scope.startsWith("notifications.") && value.filterSchema !== "capability_specific_v1") {
      throw new Error("CAPABILITY_REGISTRY_INVALID");
    }
    seen.add(value.scope);
    return copyDefinition(value as unknown as CapabilityDefinition);
  });
  return deepFreeze({
    $schema: REGISTRY_SCHEMA,
    registryId: REGISTRY_ID,
    protocolVersion: VERSION,
    registryVersion: VERSION,
    capabilities: Object.freeze(capabilities),
  });
};

const patternMatches = (pattern: string, scope: string): boolean => {
  const escaped = pattern.replace(/[.*+?^{}()|[\]\\]/g, "\\$&").replace(/<[^>]+>/g, "[^.]+");
  return new RegExp("^" + escaped + "$").test(scope);
};

export class CapabilityRegistry {
  readonly document: CapabilityRegistryDocument;
  readonly #entries: readonly CapabilityDefinition[];

  constructor(document: unknown = capabilityRegistryFixture) {
    this.document = registryDocument(document);
    this.#entries = this.document.capabilities;
    Object.freeze(this);
  }

  resolve(scope: string): CapabilityDefinition | null {
    const exact = this.#entries.find((entry) => entry.scope === scope);
    if (exact) return exact;
    return this.#entries.find((entry) => entry.scope.includes("<") && patternMatches(entry.scope, scope)) ?? null;
  }

  validateScopeCeiling(scopes: readonly string[]): { readonly ok: true } | { readonly ok: false; readonly error: "SCOPE_DENIED" } {
    const seen = new Set<string>();
    for (const scope of scopes) {
      if (typeof scope !== "string" || seen.has(scope) || this.resolve(scope) === null) return { ok: false, error: "SCOPE_DENIED" };
      seen.add(scope);
    }
    return { ok: true };
  }
}

let defaultRegistry: CapabilityRegistry | undefined;
export function loadCapabilityRegistry(document?: unknown): CapabilityRegistry {
  if (document !== undefined) return new CapabilityRegistry(document);
  defaultRegistry ??= new CapabilityRegistry();
  return defaultRegistry;
}

export function resolveCapability(scope: string, registry: CapabilityRegistry = loadCapabilityRegistry()): CapabilityDefinition | null {
  return registry.resolve(scope);
}

const sortedUniqueStrings = (value: unknown): value is readonly string[] => {
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) return false;
  for (let index = 1; index < value.length; index += 1) {
    const previous = value[index - 1];
    const current = value[index];
    if (previous === undefined || current === undefined || codePointCompare(previous, current) >= 0) return false;
  }
  return true;
};

const validateNotificationFilter = (scope: string, filter: unknown): boolean => {
  if (!isRecord(filter) || !exactKeys(filter, ["packages", "fields"].filter((key) => Object.hasOwn(filter, key)))) return false;
  const hasPackages = Object.hasOwn(filter, "packages");
  const hasFields = Object.hasOwn(filter, "fields");
  if (!hasPackages && !hasFields) return false;
  if (Object.hasOwn(filter, "packages")) {
    if (!sortedUniqueStrings(filter.packages) || filter.packages.length < 1
      || !filter.packages.every((pkg) => /^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$/.test(pkg))) return false;
  }
  if (Object.hasOwn(filter, "fields")) {
    if (!sortedUniqueStrings(filter.fields) || filter.fields.length < 1 || filter.fields.length > 2
      || !filter.fields.every((field) => field === "metadata" || field === "content")) return false;
    if (scope === "notifications.metadata" && filter.fields.includes("content")) return false;
  }
  return true;
};

export function validateCapabilityFilter(
  scope: string,
  filter: unknown,
  registry: CapabilityRegistry = loadCapabilityRegistry(),
): CapabilityFilterValidation {
  const capability = registry.resolve(scope);
  if (!capability) return { ok: false, error: "SCOPE_DENIED" };
  if (capability.filterSchema === "none") return filter === undefined ? { ok: true, filter } : { ok: false, error: "SCHEMA_INVALID" };
  return validateNotificationFilter(scope, filter)
    ? { ok: true, filter }
    : { ok: false, error: "SCHEMA_INVALID" };
}

export function capabilityFilterHash(scope: string, filter: unknown, registry?: CapabilityRegistry): string;
export function capabilityFilterHash(filter: unknown): string;
export function capabilityFilterHash(scopeOrFilter: string | unknown, maybeFilter?: unknown, registry?: CapabilityRegistry): string {
  const hasScope = typeof scopeOrFilter === "string" && maybeFilter !== undefined;
  const scope = hasScope ? scopeOrFilter as string : "notifications.content";
  const filter = hasScope ? maybeFilter : scopeOrFilter;
  const decision = validateCapabilityFilter(scope, filter, registry ?? loadCapabilityRegistry());
  if (!decision.ok) throw new Error(decision.error);
  return sha256B64Url(canonicalBytes(decision.filter));
}

export function effectiveCapabilities(
  manifest: CapabilityManifest,
  binding: CapabilityBinding,
  registry: CapabilityRegistry = loadCapabilityRegistry(),
): readonly string[] {
  if (manifest.registryVersion !== registry.document.registryVersion
    || manifest.tenantId !== binding.tenantId
    || manifest.humanPrincipalId !== binding.humanPrincipalId
    || manifest.deviceId !== binding.deviceId
    || !Array.isArray(manifest.capabilities)) return Object.freeze([]);
  const allowed: string[] = [];
  for (const entry of manifest.capabilities) {
    if (entry.schemaSupported && entry.backendAvailable && entry.systemPermission && entry.localConsent && entry.agentScopeAllowed
      && registry.resolve(entry.scope) !== null) allowed.push(entry.scope);
  }
  return Object.freeze(allowed.sort(codePointCompare));
}

export const CAPABILITY_REGISTRY_VERSION = VERSION;
