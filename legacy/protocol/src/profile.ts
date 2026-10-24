import profileFixture from "../profile/v1.json" with { type: "json" };
import { validateSchema } from "./schema-validator.js";

export const PROFILE_SCHEMA_ID = "urn:open-android-intelligence:protocol:v1:profile";

validateSchema(PROFILE_SCHEMA_ID, profileFixture);

export type ProtocolProfile = Omit<Readonly<typeof profileFixture>, "signature_domains"> & {
  readonly signature_domains: readonly string[];
};

Object.freeze(profileFixture.signature_domains);
const profile: ProtocolProfile = Object.freeze(profileFixture);

export function loadProtocolProfile(): ProtocolProfile {
  return profile;
}

declare const signatureDomainBrand: unique symbol;
export type SignatureDomain = string & { readonly [signatureDomainBrand]: "SignatureDomain" };

const signatureDomainSet: ReadonlySet<string> = new Set(profile.signature_domains);

export function parseSignatureDomain(value: string): SignatureDomain {
  if (!signatureDomainSet.has(value)) throw new Error("UNKNOWN_SIGNATURE_DOMAIN");
  return value as SignatureDomain;
}

export const SIGNATURE_DOMAINS: readonly SignatureDomain[] = Object.freeze(
  profile.signature_domains.map(parseSignatureDomain),
);

export const CRYPTO_PROFILE = {
  profileId: profile.profile_id,
  canonicalization: profile.canonicalization,
  digest: profile.digest,
  signature: profile.signature,
  curve: profile.curve,
  signatureEncoding: profile.signature_encoding,
  maxEnvelopeBytes: Number(profile.max_envelope_bytes),
} as const;
