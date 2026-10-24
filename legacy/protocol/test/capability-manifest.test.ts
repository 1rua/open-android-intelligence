import { describe, expect, it } from "vitest";
import {
  CapabilityRegistry,
  capabilityFilterHash,
  effectiveCapabilities,
  loadCapabilityRegistry,
  resolveCapability,
  validateCapabilityFilter,
  type CapabilityRegistryDocument,
} from "../src/capability-manifest.js";

describe("locked capability registry and capability-specific filters", () => {
  it("resolves the approved notification scopes and rejects an unknown scope", () => {
    expect(resolveCapability("notifications.metadata")).toMatchObject({
      scope: "notifications.metadata",
      filterSchema: "capability_specific_v1",
    });
    expect(resolveCapability("notifications.content")).toMatchObject({
      scope: "notifications.content",
      filterSchema: "capability_specific_v1",
    });
    expect(resolveCapability("notifications.secret")).toBeNull();
  });

  it("validates closed notification package/field filters without rewriting hash input", () => {
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    expect(validateCapabilityFilter("notifications.metadata", filter)).toEqual({ ok: true, filter });
    expect(capabilityFilterHash("notifications.metadata", filter)).toBe(
      "xVB7KzDMkMKa9ed9BoQ5wyBabLZcxuplfSvc_xEOGVQ",
    );
    expect(validateCapabilityFilter("notifications.metadata", { fields: ["content"] })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.metadata", { packages: ["com.z", "com.a"] })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.metadata", { packages: ["com.a", "com.a"] })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.metadata", {})).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.metadata", { packages: [] })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.metadata", { fields: [] })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
    expect(validateCapabilityFilter("notifications.content", { fields: ["content", "metadata"] })).toMatchObject({ ok: true });
    expect(validateCapabilityFilter("notifications.metadata", { fields: ["metadata"], extra: true })).toMatchObject({ ok: false, error: "SCHEMA_INVALID" });
  });

  it("fails startup closed for unavailable, wrong-version, duplicate or forbidden registry entries", () => {
    const base = loadCapabilityRegistry().document;
    const mutate = (change: Partial<CapabilityRegistryDocument>): CapabilityRegistryDocument => ({ ...base, ...change });
    expect(() => loadCapabilityRegistry(undefined)).not.toThrow();
    expect(() => loadCapabilityRegistry(mutate({ registryVersion: "2.0" }))).toThrowError("CAPABILITY_REGISTRY_INVALID");
    expect(() => loadCapabilityRegistry(mutate({ capabilities: [...base.capabilities, base.capabilities[0]!] }))).toThrowError("CAPABILITY_REGISTRY_INVALID");
    expect(() => loadCapabilityRegistry(mutate({ capabilities: [...base.capabilities, { ...base.capabilities[0]!, scope: "shell.root" }] }))).toThrowError("CAPABILITY_REGISTRY_INVALID");
  });

  it("returns no effective capability when manifest registry or binding authority mismatches", () => {
    const manifest = {
      registryVersion: "1.0",
      tenantId: "tenant",
      humanPrincipalId: "human",
      deviceId: "device",
      capabilities: [{
        scope: "notifications.metadata", schemaSupported: true, backendAvailable: true,
        systemPermission: true, localConsent: true, agentScopeAllowed: true,
      }],
    } as const;
    expect(effectiveCapabilities(manifest, { tenantId: "tenant", humanPrincipalId: "human", deviceId: "device" })).toEqual(["notifications.metadata"]);
    expect(effectiveCapabilities({ ...manifest, registryVersion: "2.0" }, { tenantId: "tenant", humanPrincipalId: "human", deviceId: "device" })).toEqual([]);
    expect(effectiveCapabilities(manifest, { tenantId: "tenant", humanPrincipalId: "other", deviceId: "device" })).toEqual([]);
  });
});

void new CapabilityRegistry;
