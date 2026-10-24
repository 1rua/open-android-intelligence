/// <reference types="node" />

import { timingSafeEqual } from "node:crypto";
import versionsFixture from "../registries/v1/versions.json" with { type: "json" };
import { canonicalBytes, sha256B64Url } from "./encoding.js";
import type { P256PublicJwk, VerifiedConnectHello, VerifiedConnectWelcome } from "./message-registry.js";
import { validateSchema } from "./schema-validator.js";

const UINT64_MAX = 18_446_744_073_709_551_615n;

export interface ProtocolVersion {
  readonly major: bigint;
  readonly minor: bigint;
  readonly canonical: string;
}

export interface VersionRegistryEntry {
  readonly version: string;
  readonly negotiable: boolean;
  readonly fixture_owner?: "Task7";
}

declare const lockedVersionRegistryBrand: unique symbol;
export type LockedVersionRegistry = Readonly<{
  readonly $schema: "urn:open-android-intelligence:protocol:v1:versions-registry";
  readonly registry_id: "urn:open-android-intelligence:protocol:v1:registry:versions";
  readonly protocol_version: "1.0";
  readonly versions: readonly VersionRegistryEntry[];
  readonly [lockedVersionRegistryBrand]: true;
}>;

export interface VersionSelection {
  readonly selected: string;
  readonly clientOfferDigest: string;
}

export interface VerifiedNegotiation {
  readonly selected: string;
  readonly clientOfferDigest: string;
  readonly clientNonce: string;
  readonly bridgeNonce: string;
  readonly bridgeTime: string;
  readonly commandKeySet: Readonly<{ current: P256PublicJwk; next: P256PublicJwk | null }>;
  readonly connectionGeneration: string;
}

export function parseProtocolVersion(input: string): ProtocolVersion {
  const match = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.exec(input);
  if (!match?.[1] || !match[2]) throw new Error("VERSION_UNSUPPORTED");
  const major = BigInt(match[1]);
  const minor = BigInt(match[2]);
  if (major > UINT64_MAX || minor > UINT64_MAX) throw new Error("VERSION_UNSUPPORTED");
  return Object.freeze({ major, minor, canonical: input });
}

export function compareProtocolVersions(a: ProtocolVersion, b: ProtocolVersion): -1 | 0 | 1 {
  if (a.major < b.major || (a.major === b.major && a.minor < b.minor)) return -1;
  if (a.major > b.major || (a.major === b.major && a.minor > b.minor)) return 1;
  return 0;
}

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

validateSchema("urn:open-android-intelligence:protocol:v1:versions-registry", versionsFixture);
const lockedRegistry = deepFreeze(versionsFixture) as unknown as LockedVersionRegistry;

export function loadVersionRegistry(): LockedVersionRegistry {
  return lockedRegistry;
}

const highestCommon = (offers: readonly string[], registry: LockedVersionRegistry): string => {
  const offered = new Set(offers.map((version) => parseProtocolVersion(version).canonical));
  const candidates = registry.versions
    .filter((entry) => entry.negotiable && offered.has(entry.version))
    .map((entry) => parseProtocolVersion(entry.version))
    .sort((left, right) => compareProtocolVersions(right, left));
  const selected = candidates[0];
  if (!selected) throw new Error("VERSION_UNSUPPORTED");
  return selected.canonical;
};

export function selectHighestCommonVersion(
  hello: VerifiedConnectHello,
  productionRegistry: LockedVersionRegistry,
): VersionSelection {
  if (productionRegistry !== lockedRegistry) throw new Error("VERSION_UNSUPPORTED");
  return deepFreeze({
    selected: highestCommon(hello.payload.supported_versions, productionRegistry),
    clientOfferDigest: sha256B64Url(canonicalBytes(hello.payload)),
  });
}

const equalText = (left: string, right: string): boolean => {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.byteLength === b.byteLength && timingSafeEqual(a, b);
};

export function verifyWelcome(
  welcome: VerifiedConnectWelcome,
  hello: VerifiedConnectHello,
  productionRegistry: LockedVersionRegistry,
): VerifiedNegotiation {
  const selection = selectHighestCommonVersion(hello, productionRegistry);
  if (!equalText(welcome.payload.client_offer_digest, selection.clientOfferDigest)) throw new Error("INTEGRITY_FAILED");
  if (!equalText(welcome.payload.client_nonce, hello.payload.client_nonce)) throw new Error("AUTH_BINDING_MISMATCH");
  if (welcome.payload.selected_protocol !== selection.selected) throw new Error("VERSION_UNSUPPORTED");
  return deepFreeze({
    selected: selection.selected,
    clientOfferDigest: selection.clientOfferDigest,
    clientNonce: hello.payload.client_nonce,
    bridgeNonce: welcome.payload.bridge_nonce,
    bridgeTime: welcome.payload.bridge_time,
    commandKeySet: welcome.payload.command_key_set,
    connectionGeneration: welcome.payload.connection_generation,
  });
}
