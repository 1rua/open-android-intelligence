import { describe, expect, it } from "vitest";
import {
  validateGatewayValue,
  type GatewaySchemaName,
} from "../src/schema-registry.js";
import {
  nextGenerationState,
  joinMessageBatch,
  type GenerationState,
  type GenerationEvent,
} from "../src/state-machines.js";

const hexDigest = "a".repeat(64);
const prefixedDigest = `sha256:${hexDigest}`;

describe("Gateway v2.1 conversation UI contract", () => {
  it("validates negotiate.request with conversationUi features", () => {
    const validReq = {
      negotiationId: "neg_1",
      protocol: { major: 2, minor: 1 },
      client: {
        installationId: "install_1",
        appVersion: "2.1.0",
        platform: "android",
        platformApi: 35,
      },
      features: {
        auth: ["password"],
        messages: ["chat-v1"],
        attachments: ["staged-sha256-v1"],
        events: ["sse-cursor-v1"],
        deviceRequests: ["risk-queue-v1"],
        conversationUi: [
          "agent-command-catalog-v1",
          "message-batches-v1",
          "newline-v1",
          "generation-cancel-v1",
          "conversation-mirror-v1",
          "attachment-status-v1",
        ],
      },
      schemaHashes: { core: prefixedDigest },
    };
    expect(validateGatewayValue("negotiate.request", validReq)).toEqual({ ok: true });
  });

  it("validates conversation.commandCatalog schema", () => {
    const validCatalog = {
      commands: [
        {
          command: "/new",
          description: "Start a new conversation thread",
          argumentHint: "[title]",
        },
        {
          command: "/clear",
          description: "Clear current conversation",
        },
      ],
    };
    expect(validateGatewayValue("conversation.commandCatalog", validCatalog)).toEqual({ ok: true });
  });

  it("validates conversation.generationCancel schema", () => {
    expect(
      validateGatewayValue("conversation.generationCancel", { clientMessageId: "msg_123" }),
    ).toEqual({ ok: true });
  });

  it("validates conversation.mirrorSync schema", () => {
    expect(
      validateGatewayValue("conversation.mirrorSync", { conversationId: "conv_456", sinceSeq: 10 }),
    ).toEqual({ ok: true });
  });

  it("validates attachment.status schema", () => {
    expect(
      validateGatewayValue("attachment.status", {
        attachmentId: "att_789",
        status: "uploaded",
        sizeBytes: 1024,
        sha256: hexDigest,
      }),
    ).toEqual({ ok: true });
  });

  it("joins members with one U+000A and no trailing LF", () => {
    expect(
      joinMessageBatch([
        { clientMessageId: "msg_a", text: "甲\n" },
        { clientMessageId: "msg_b", text: "\n乙" },
        { clientMessageId: "msg_c", text: "丙" },
      ]),
    ).toBe("甲\n乙\n丙");
  });

  it("correctly handles generation state transitions", () => {
    let state: GenerationState = "idle";
    state = nextGenerationState(state, "start");
    expect(state).toBe("streaming");
    state = nextGenerationState(state, "chunk");
    expect(state).toBe("streaming");
    state = nextGenerationState(state, "request_cancel");
    expect(state).toBe("cancel_requested");
    state = nextGenerationState(state, "cancelled");
    expect(state).toBe("cancelled");
  });

  it("does not overwrite outcome_unknown with completed", () => {
    expect(() =>
      nextGenerationState("outcome_unknown", "complete" as GenerationEvent),
    ).toThrow("INVALID_STATE_TRANSITION");
  });
});
