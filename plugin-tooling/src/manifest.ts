/**
 * TypeScript shapes for the Open Android Intelligence device plugin package manifest.
 *
 * Keep in sync with `docs/contracts/device-plugin-package-v1.md`.
 */

export type RuntimeType = "protected-wasm" | "developer-native" | "companion";

export type PluginManifest = {
  schemaVersion: string;
  plugin: {
    id: string;
    version: string;
    name: string;
    description: string;
  };
  author: {
    algorithm: "Ed25519";
    publicKey: string; // base64url, 32 bytes
  };
  runtime:
    | {
        type: "protected-wasm";
        abiVersion: string;
        entrypoint: string;
        payload: string;
      }
    | {
        type: "developer-native";
        entrypointClass: string;
        payload?: string;
      }
    | {
        type: "companion";
        payload: string;
        packageName: string;
        certificateSha256: string;
        minVersionCode: number;
        ipcContract: string;
      };
  compatibility: {
    androidHost: string;
  };
  capabilities: {
    provides: CapabilityDeclaration[];
    depends: DependencyDeclaration[];
    kernelPrimitives: KernelPrimitiveDeclaration[];
  };
  security: {
    network: NetworkRule[];
    background: {
      requested: boolean;
      minimumIntervalSeconds: number | null;
    };
    resources: ResourceLimits;
  };
  ui: {
    settings: unknown[];
    cards: unknown[];
  };
  state: {
    schemaVersion: number;
    portableExport: boolean;
  };
};

export type CapabilityDeclaration = {
  id: string;
  version: string;
  schema: string;
};

export type DependencyDeclaration = {
  capability: string;
  version: string;
  required: boolean;
};

export type KernelPrimitiveDeclaration = {
  id: string;
  version: string;
  purpose: string;
};

export type NetworkRule = {
  scheme: string;
  host: string;
  port: number;
  methods: string[];
  pathPrefix: string;
  purpose: string;
};

export type ResourceLimits = {
  maxInvocationMillis: number;
  maxMemoryBytes: number;
  maxStorageBytes: number;
  maxConcurrentInvocations: number;
  maxDailyNetworkBytes: number;
};

export const MINIMAL_MANIFEST: PluginManifest = {
  schemaVersion: "1.0",
  plugin: {
    id: "org.example.notifications",
    version: "1.2.0",
    name: "Example Notifications",
    description: "Queries notifications after local authorization",
  },
  author: {
    algorithm: "Ed25519",
    publicKey: "dummy",
  },
  runtime: {
    type: "protected-wasm",
    abiVersion: "1.0",
    entrypoint: "open_android_intelligence_plugin_main",
    payload: "payload/plugin.wasm",
  },
  compatibility: {
    androidHost: ">=2.0.0 <3.0.0",
  },
  capabilities: {
    provides: [],
    depends: [],
    kernelPrimitives: [],
  },
  security: {
    network: [],
    background: {
      requested: false,
      minimumIntervalSeconds: null,
    },
    resources: {
      maxInvocationMillis: 5000,
      maxMemoryBytes: 16777216,
      maxStorageBytes: 10485760,
      maxConcurrentInvocations: 1,
      maxDailyNetworkBytes: 0,
    },
  },
  ui: {
    settings: [],
    cards: [],
  },
  state: {
    schemaVersion: 1,
    portableExport: false,
  },
};
