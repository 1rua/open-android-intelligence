export type SignerRole = "device" | "bridge-command" | "adapter";

export interface Clock {
  wallNow(): Date;
  monotonicNowMs(): bigint;
}

export interface NonceSource {
  randomBytes(byteLength: number): Uint8Array;
}

export interface Signer {
  readonly keyId: string;
  readonly role: SignerRole;
  sign(preimage: Uint8Array): Promise<string>;
}

export interface Verifier {
  verify(role: SignerRole, keyId: string, preimage: Uint8Array, signature: string): Promise<boolean>;
}
