import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";
import { describe, expect, it } from "vitest";

import catalogDocument from "../schemas/command-catalog.schema.json" with { type: "json" };
import envelopeDocument from "../schemas/envelope.schema.json" with { type: "json" };
import eventDocument from "../schemas/event.schema.json" with { type: "json" };
import registry from "../core-dispatched-schemas.json" with { type: "json" };
import { createGatewayDispatchedValidator, type GatewaySubschemaCatalogEntry, type VerifiedSchemaBindingSet } from "../src/dispatched-schema-validator.js";

const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;
const ajv = addFormats(new Ajv2020({ strict: true }));
ajv.addSchema(envelopeDocument);
ajv.addSchema(eventDocument);
ajv.addSchema(catalogDocument);

describe("generation-scoped conversation events", () => {
  const validator = createGatewayDispatchedValidator(
    registry.catalogEntries as GatewaySubschemaCatalogEntry[],
    { core: registry.bindings as VerifiedSchemaBindingSet["core"], device: [] },
  );
  const message = {
    conversationId: "conv_a", messageId: "msg_a", sender: "assistant",
    parts: [{ type: "text", text: "Working" }], text: "Working",
    timestamp: 1791936000000, revision: 1,
  };

  for (const eventType of ["conversation.message.delta", "conversation.message.completed"]) {
    it(`${eventType} accepts an optional generation and rejects malformed identities`, () => {
      const frame = (payload: object) => ({
        correlationId: "corr_a", occurredAt: "2026-10-15T00:00:00.000Z", payload,
      });
      expect(validator.validate({ kind: "event", eventType }, frame(message))).toEqual({ ok: true });
      expect(validator.validate({ kind: "event", eventType }, frame({ ...message, generationId: "gen_a" }))).toEqual({ ok: true });
      for (const generationId of ["", "other/thread", 42, "a".repeat(129)]) {
        expect(validator.validate({ kind: "event", eventType }, frame({ ...message, generationId })).ok).toBe(false);
      }
      expect(validator.validate({ kind: "event", eventType }, frame({ ...message, unknownField: true })).ok).toBe(false);
    });
  }

  it("keeps normative and dispatched status payloads consistent for terminal generations", () => {
    const validate = ajv.getSchema(`${eventDocument.$id}#/$defs/messageStatusPayload`)!;
    for (const status of ["queued", "delivered", "completed", "failed"]) {
      const payload = {
        conversationId: "conv_a", messageId: "msg_a", clientMessageId: "client_a",
        generationId: "gen_a", status, revision: 2,
        errorCode: status === "failed" ? "AGENT_UNAVAILABLE" : null,
      };
      expect(validate(payload)).toBe(true);
      expect(validator.validate({ kind: "event", eventType: "conversation.message.status" }, {
        correlationId: "corr_a", occurredAt: "2026-10-15T00:00:00.000Z", payload,
      })).toEqual({ ok: true });
      expect(validate({ ...payload, generationId: "bad/id" })).toBe(false);
    }
  });
});

describe("host command catalog discovery response", () => {
  const validate = ajv.getSchema(`${catalogDocument.$id}#/$defs/catalog`)!;
  const catalog = {
    format: "agent-command-catalog-1.0", catalogVersion: "cmdcat_a", languageCode: "zh-CN",
    commands: [{ id: "host-custom", invocation: "/host-custom", title: "Host custom",
      description: "A command registered by this host", acceptsArguments: true,
      availability: "available", argumentHint: "<target>" }],
  };

  it("validates the shape consumed by Android without synthetic commands or aliases", () => {
    expect(validate(catalog)).toBe(true);
    expect(validate({ ...catalog, commands: [] })).toBe(true);
    expect(validate({ ...catalog, version: "made-up-alias" })).toBe(false);
    expect(validate({ ...catalog, commands: [{ ...catalog.commands[0], invocation: "host-custom" }] })).toBe(false);
    expect(validate({ ...catalog, commands: [{ ...catalog.commands[0], command: "/host-custom" }] })).toBe(false);
  });
});
