# Assistant Audio Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tested text-plus-AAC/M4A assistant backend path that moves audio through the existing artifact lifecycle and paired Bridge to Hermes/OpenClaw without adding UI or device speech processing.

**Architecture:** Extend the closed `assistant-chat:v1` wire contract with an opaque committed artifact reference, an audio attachment discriminator, and ordered reply events. Extend the TypeScript artifact, Bridge, and common adapter contracts with the same audio limits and authorization fences, then add SDK-independent Kotlin value objects for the future Android assistant client.

**Tech Stack:** TypeScript 7, Vitest 4, JSON Schema 2020-12, Kotlin 2.1.20, Android library/JUnit tests, existing `OperationDispatcher` and paired Bridge contracts.

## Global Constraints

- Audio is `audio/mp4` (AAC/M4A) only.
- One audio attachment is at most `120000` ms and `10485760` bytes.
- Audio counts toward the existing maximum of 4 attachments and 50 MiB per message.
- Audio is referenced by a Bridge-issued opaque `artifact_id`; no Base64, URI, path, URL, provider handle, or upload URL crosses the chat wire.
- The phone does not transcribe audio and this plan adds no TTS, microphone permission, recorder, Compose, animation, or default-assistant UI code.
- Bridge assistant requests require a bound session, current pairing/policy fence, current zero-retention evidence, and a committed artifact resolver result for every attachment.
- Unknown JSON properties and model-supplied identity fields remain rejected.
- Existing image/file assistant behavior remains valid after adding `artifact_id`; existing tests must be updated with committed artifact fixtures rather than weakening validation.
- Preserve all unrelated worktree changes; stage only files belonging to the current task when committing.

## File Map

- `mvp-contract/schemas/v1/assistant-chat.schema.json`: closed request, response, and ordered reply-event schema.
- `mvp-contract/src/wire-codec.ts`: only snake-case encoder/validator for assistant attachments and events.
- `mvp-contract/test/mvp-contract.test.ts`: schema and exact-wire behavior tests.
- `artifact-contract/src/artifact-ticket.ts`: media, audio-duration, and committed artifact ticket rules.
- `artifact-contract/test/artifact-ticket.test.ts`: artifact limit, proof, interruption, and commit tests.
- `artifact-contract/README.md`: artifact contract documentation for audio.
- `integrations/shared/adapter.ts`: common Hermes/OpenClaw attachment and assistant input validation.
- `integrations/shared/adapter-contract.test.ts`: shared adapter audio behavior tests.
- `integrations/hermes/adapter.test.ts`: Hermes audio normalization/profile regression test.
- `integrations/openclaw/adapter.test.ts`: OpenClaw audio normalization/profile regression test.
- `bridge-contract/src/assistant-reply-events.ts`: ordered reply-event types and replayable event-store port.
- `bridge-contract/src/assistant-chat-service.ts`: committed-artifact validation, audio attachment validation, and streamed reply emission.
- `bridge-contract/src/index.ts`: exports the reply-event contract.
- `bridge-contract/test/service-contract.test.ts`: Bridge authorization, resolver, idempotency, audio, and event tests.
- `apps/android/artifact-ports/src/main/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPorts.kt`: Android audio media and bounded duration metadata.
- `apps/android/artifact-ports/src/test/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPortsTest.kt`: Android artifact value validation tests.
- `apps/android/artifact-ports/build.gradle.kts`: JUnit dependency for the new JVM contract tests.
- `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/AssistantAudioContracts.kt`: SDK-independent audio attachment and reply-event values.
- `apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/AssistantAudioContractsTest.kt`: Kotlin audio/event validation tests.
- `apps/android/tools/test_assistant_audio_backend_static.py`: SDK-free source boundary checks for the Android backend port.
- `docs/mvp/mvp-vertical-slice-contract.md`: replace the text-only assistant description with the approved audio/event boundary.

---

### Task 1: Extend the closed assistant wire contract

**Files:**
- Modify: `mvp-contract/schemas/v1/assistant-chat.schema.json`
- Modify: `mvp-contract/src/wire-codec.ts`
- Test: `mvp-contract/test/mvp-contract.test.ts`

**Interfaces:**
- Consumes: existing `RuntimeAssistantAttachment`, `encodeAssistantRequest`, `encodeAssistantResponse`, and `validateWireAssistantMessage`.
- Produces: `RuntimeAssistantAttachment` union with `artifactId` on every attachment and `durationMs` on audio; `encodeAssistantEvent(input)`; validation for `request`, `response`, and `event` wire records.

- [ ] **Step 1: Write the failing wire tests.** Add tests that use literal expected objects and cover the exact break:

```ts
it("encodes a committed audio artifact with a bounded duration", () => {
  const wire = encodeAssistantRequest({
    operationId: "op-audio",
    text: "please transcribe",
    attachments: [{
      kind: "audio",
      artifactId: "artifact-audio-1",
      filename: "voice.m4a",
      mimeType: "audio/mp4",
      sizeBytes: 10485760,
      sha256: "a".repeat(64),
      durationMs: 120000,
    }],
  });
  expect(wire.attachments).toEqual([{
    kind: "audio", artifact_id: "artifact-audio-1", media_type: "audio/mp4",
    byte_length: 10485760, sha256: "a".repeat(64), display_name: "voice.m4a", duration_ms: 120000,
  }]);
  expect(validateWireAssistantMessage(wire)).toBe(true);
});

it("rejects audio outside the closed size, duration, and field rules", () => {
  const valid = {
    kind: "audio", artifact_id: "artifact-audio-1", media_type: "audio/mp4",
    byte_length: 1, sha256: "a".repeat(64), display_name: "voice.m4a", duration_ms: 1,
  };
  expect(validateWireAssistantMessage({
    kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, duration_ms: 120001 }],
  })).toBe(false);
  expect(validateWireAssistantMessage({
    kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, byte_length: 10485761 }],
  })).toBe(false);
  expect(validateWireAssistantMessage({
    kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, uri: "content://forbidden" }],
  })).toBe(false);
});

it("encodes ordered delta, complete, and failed reply events", () => {
  expect(encodeAssistantEvent({ operationId: "op", messageId: "m", sequence: 1n, event: "delta", text: "hel" }))
    .toEqual({ kind: "event", operation_id: "op", message_id: "m", sequence: "1", event: "delta", text: "hel" });
  expect(validateWireAssistantMessage({
    kind: "event", operation_id: "op", message_id: "m", sequence: "2", event: "failed", text: "", error: "CONNECTION_FENCED",
  })).toBe(true);
  expect(validateWireAssistantMessage({
    kind: "event", operation_id: "op", message_id: "m", sequence: "0", event: "delta", text: "x",
  })).toBe(false);
});
```

- [ ] **Step 2: Run the focused test and verify it fails for missing audio/event behavior.**

Run: `./tools/run-node24 npm test -- mvp-contract/test/mvp-contract.test.ts`

Expected: FAIL because the current attachment type has no `artifactId`/`durationMs`, the schema allows neither audio nor `artifact_id`, and `encodeAssistantEvent` is absent.

- [ ] **Step 3: Implement the minimum closed schema and codec changes.**

Use these exact rules:

```ts
const AUDIO_MAX_BYTES = 10 * 1024 * 1024;
const AUDIO_MAX_DURATION_MS = 120000;
const ARTIFACT_ID = /^[A-Za-z0-9._~-]{1,128}$/;

type RuntimeAssistantAttachment =
  | Readonly<{ kind: "image" | "file"; artifactId: string; filename: string; mimeType: string; sizeBytes: number; sha256: string }>
  | Readonly<{ kind: "audio"; artifactId: string; filename: string; mimeType: "audio/mp4"; sizeBytes: number; sha256: string; durationMs: number }>;
```

The JSON schema must use a closed `oneOf` for image/file/audio so audio requires `duration_ms`, `artifact_id`, `media_type: audio/mp4`, and `byte_length <= 10485760`, while image/file retain their current limits and require `artifact_id`. Add an `event` branch with `operation_id`, `message_id`, positive decimal-string `sequence`, `event` in `delta|complete|failed`, bounded `text`, and a non-empty `error` only for `failed`.

`encodeAssistantRequest` must emit `artifact_id` and conditional `duration_ms`; `encodeAssistantEvent` must convert a positive `bigint` sequence to a decimal string and validate the result. The validator must reject arbitrary keys, invalid artifact IDs, missing audio duration, duration on non-audio attachments, image/audio kind mismatches, and `byte_length > 10485760` for audio.

- [ ] **Step 4: Run the focused test and typecheck.**

Run: `./tools/run-node24 npm test -- mvp-contract/test/mvp-contract.test.ts && ./tools/run-node24 npx tsc --noEmit --strict --target ES2024 --module NodeNext --moduleResolution NodeNext mvp-contract/src/wire-codec.ts`

Expected: the focused tests pass and TypeScript reports no errors.

- [ ] **Step 5: Commit only the protocol files.**

```sh
git add mvp-contract/schemas/v1/assistant-chat.schema.json mvp-contract/src/wire-codec.ts mvp-contract/test/mvp-contract.test.ts
git commit -m "feat(protocol): add assistant audio and reply events"
```

### Task 2: Extend the artifact ticket contract for audio

**Files:**
- Modify: `artifact-contract/src/artifact-ticket.ts`
- Modify: `artifact-contract/test/artifact-ticket.test.ts`
- Modify: `artifact-contract/README.md`

**Interfaces:**
- Consumes: existing `issueArtifactTicket`, `verifyArtifactProof`, `commitArtifactMessage`, `interruptArtifactTicket`, and `reclaimOrphanTicket`.
- Produces: `ARTIFACT_LIMITS.maxAudioBytes`, `ARTIFACT_LIMITS.maxAudioDurationMs`, `ArtifactTicket.durationMs`, and a committed ticket `artifactId` equal to its Bridge-issued opaque `ticketId`.

- [ ] **Step 1: Write failing audio ticket tests.** Add tests with `mediaType: "audio/mp4"` and literal expected values:

```ts
it("accepts audio only within the 10 MiB and 120 second limits", () => {
  const ticket = issueArtifactTicket(ticketInput({
    mediaType: "audio/mp4", byteSize: 10485760, durationMs: 120000,
  }), 1000, ticketIds("audio-ticket"));
  expect(ticket).toMatchObject({ mediaType: "audio/mp4", byteSize: 10485760, durationMs: 120000 });
  expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", byteSize: 10485761, durationMs: 1 }), 1000, ticketIds("too-large")))
    .toThrowError(new ArtifactContractError("ARTIFACT_TOO_LARGE"));
  expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", byteSize: 1, durationMs: 120001 }), 1000, ticketIds("too-long")))
    .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
});

it("requires audio duration and rejects duration metadata on non-audio artifacts", () => {
  expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4" }), 1000, ticketIds("missing-duration")))
    .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
  expect(() => issueArtifactTicket(ticketInput({ mediaType: "image/png", durationMs: 1000 }), 1000, ticketIds("image-duration")))
    .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
});

it("mints an opaque artifact id only on message commit", async () => {
  const issued = issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", durationMs: 25 }), 1000, ticketIds("ticket-a"));
  const verified = await verifyArtifactProof(issued, { ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32) }, { verify: async () => "verified" });
  expect(verified.artifactId).toBeUndefined();
  const receipt = commitArtifactMessage("message-a", [verified], 1500);
  expect(receipt.tickets[0]).toMatchObject({ status: "message_committed", artifactId: "ticket-a", durationMs: 25 });
});
```

- [ ] **Step 2: Run the artifact test and verify it fails.**

Run: `./tools/run-node24 npm test -- artifact-contract/test/artifact-ticket.test.ts`

Expected: FAIL because the media set excludes `audio/mp4`, the input parser has no duration field, and committed tickets have no artifact ID.

- [ ] **Step 3: Implement the audio-specific parser and ticket fields.**

Keep `maxSingleBytes` at 25 MiB for existing media and apply `maxAudioBytes` only to audio. Permit the optional `durationMs` input key, require an integer from `1..120000` for audio, reject it for all other media, store it on the ticket, and reject `audio/mp4` when the duration is absent. Preserve the existing exact-key, path/URL rejection, digest normalization, proof brand, interruption, aggregate-size, and orphan-reclaim behavior. When `commitArtifactMessage` creates each committed ticket, set `artifactId` to the original `ticketId` so the value is opaque and cannot be used before commit.

- [ ] **Step 4: Update artifact documentation and run tests/typecheck.**

Document `AUDIO_MP4`, the two audio limits, and the commit-only artifact ID in `artifact-contract/README.md`.

Run: `./tools/run-node24 npm test -- artifact-contract/test/artifact-ticket.test.ts && (cd artifact-contract && ../tools/run-node24 npm run typecheck)`

Expected: all artifact tests and package typecheck pass.

- [ ] **Step 5: Commit only the artifact files.**

```sh
git add artifact-contract/src/artifact-ticket.ts artifact-contract/test/artifact-ticket.test.ts artifact-contract/README.md
git commit -m "feat(artifact): support bounded audio tickets"
```

### Task 3: Normalize audio in the Hermes/OpenClaw adapter contract

**Files:**
- Modify: `integrations/shared/adapter.ts`
- Modify: `integrations/shared/adapter-contract.test.ts`
- Modify: `integrations/hermes/adapter.test.ts`
- Modify: `integrations/openclaw/adapter.test.ts`

**Interfaces:**
- Consumes: the protocol attachment shape from Task 1 and existing `FakeAdapter.sendAssistantMessage`.
- Produces: discriminated `AssistantAttachment` with an audio branch containing `durationMs`; `ASSISTANT_ATTACHMENT_LIMITS.maxAudioBytes` and `.maxAudioDurationMs`; normalized metadata exposed by `assistantMetadata()`.

- [ ] **Step 1: Write failing shared adapter tests.** Add a real `createFakeAdapter` test that sends an audio attachment and asserts the normalized result retains only the exact metadata, plus invalid-limit tests:

```ts
it("passes bounded audio metadata to the common agent adapter", async () => {
  const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
  await adapter.pair(fixtureBinding());
  await expect(adapter.sendAssistantMessage({
    messageId: "voice-1", text: "analyze this",
    attachments: [{ kind: "audio", artifactId: "artifact-audio", filename: "voice.m4a", mimeType: "audio/mp4", sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000 }],
  })).resolves.toMatchObject({ status: "accepted" });
  expect(adapter.assistantMetadata()?.attachments).toEqual([{
    kind: "audio", artifactId: "artifact-audio", filename: "voice.m4a", mimeType: "audio/mp4", sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000,
  }]);
});

it.each([
  [10485761, 1, "ATTACHMENT_INVALID"],
  [1, 120001, "ATTACHMENT_INVALID"],
] as const)("rejects audio size/duration %s/%s", async (sizeBytes, durationMs, code) => {
  const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
  await adapter.pair(fixtureBinding());
  await expect(adapter.sendAssistantMessage({
    messageId: `bad-${sizeBytes}-${durationMs}`, text: "x",
    attachments: [{ kind: "audio", artifactId: "artifact-audio", filename: "voice.m4a", mimeType: "audio/mp4", sizeBytes, sha256: "c".repeat(64), durationMs }],
  })).rejects.toMatchObject({ code });
});
```

- [ ] **Step 2: Run the shared and provider tests to verify failure.**

Run: `./tools/run-node24 npm test -- integrations/shared/adapter-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts`

Expected: FAIL because the current type union and exact-key validator reject `kind: "audio"` and `durationMs`.

- [ ] **Step 3: Implement the discriminated audio branch without adding provider-specific behavior.**

Add `kind: "audio"`, `mimeType: "audio/mp4"`, and required integer `durationMs` to the shared type. Use `artifactId` as the only binary reference. Validate the audio-specific size and duration before updating `lastMetadata`; retain the current no-body/no-byte diagnostics. Keep Hermes and OpenClaw factories unchanged except for tests proving their existing profile wrappers accept the shared audio input.

- [ ] **Step 4: Run package tests and typecheck.**

Run: `./tools/run-node24 npm test -- integrations/shared/adapter-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts && (cd integrations && ../tools/run-node24 npm run typecheck)`

Expected: all focused tests pass and integration TypeScript is clean.

- [ ] **Step 5: Commit only the integration files.**

```sh
git add integrations/shared/adapter.ts integrations/shared/adapter-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
git commit -m "feat(integrations): normalize assistant audio attachments"
```

### Task 4: Add Bridge artifact fencing and ordered assistant reply events

**Files:**
- Create: `bridge-contract/src/assistant-reply-events.ts`
- Modify: `bridge-contract/src/index.ts`
- Modify: `bridge-contract/src/assistant-chat-service.ts`
- Modify: `bridge-contract/test/service-contract.test.ts`

**Interfaces:**
- Consumes: `BridgeSessionIdentity`, `equalIdentity`, `OperationDispatcherPort`, Task 2 committed artifact metadata, and Task 3 adapter attachment shape.
- Produces: `AssistantReplyEvent`, `AssistantReplyEventStore`, `InMemoryAssistantReplyEventStore`, `AssistantArtifactResolver`, `AssistantChatService.stream(request, sink)`, and audio-aware `AssistantChatService.send(request)`. The service option `boundConnectionGeneration: bigint` binds resolver commitments to the currently opened Bridge connection without changing the existing shared session identity type.

- [ ] **Step 1: Write failing Bridge tests for committed-artifact validation and ordered events.** Add tests for:

  - a resolver returning an exact committed audio record allows `send` and forwards the audio metadata to `respond`;
  - a missing resolver result rejects with `ARTIFACT_NOT_COMMITTED` before `respond` runs;
  - a resolver record with the wrong session, digest, duration, or policy revision rejects with `ARTIFACT_FENCE_MISMATCH`;
  - `stream` emits `delta` sequence `1`, `complete` sequence `2`, and `replay(operationId, 0n)` returns both events in order;
  - a failed stream emits one `failed` event with a bounded error code and no partial artifact data;
  - duplicate operation parameters remain idempotent and changed parameters remain rejected.

Use this concrete resolver fixture shape in the tests:

```ts
const committedAudio = (session: BridgeSessionIdentity) => ({
  artifactId: "artifact-audio", status: "message_committed" as const,
  session, pairingGeneration: session.pairingGeneration,
  connectionGeneration: 1n, policyRevision: session.policyAttestationRevision,
  kind: "audio" as const, mimeType: "audio/mp4" as const,
  sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000,
});
```

- [ ] **Step 2: Run the focused Bridge tests and verify they fail.**

Run: `./tools/run-node24 npm test -- bridge-contract/test/service-contract.test.ts`

Expected: FAIL because Bridge currently accepts only image/file metadata, has no artifact resolver, has no audio duration, and has no reply-event store or `stream` method.

- [ ] **Step 3: Implement the reply-event contract.** Create `assistant-reply-events.ts` with:

```ts
import { BridgeServiceError } from "./service-types.js";

export type AssistantReplyEvent = Readonly<{
  kind: "delta" | "complete" | "failed";
  operationId: string;
  messageId: string;
  sequence: bigint;
  text: string;
  error?: string;
}>;

export interface AssistantReplyEventStore {
  append(event: AssistantReplyEvent): Promise<void> | void;
  replay(operationId: string, afterSequence: bigint): Promise<readonly AssistantReplyEvent[]> | readonly AssistantReplyEvent[];
}

export class InMemoryAssistantReplyEventStore implements AssistantReplyEventStore {
  readonly #events = new Map<string, AssistantReplyEvent[]>();

  append(event: AssistantReplyEvent): void {
    const previous = this.#events.get(event.operationId) ?? [];
    const expected = BigInt(previous.length + 1);
    if (event.sequence !== expected) throw new BridgeServiceError("ASSISTANT_EVENT_SEQUENCE_INVALID");
    previous.push(Object.freeze({ ...event }));
    this.#events.set(event.operationId, previous);
  }

  replay(operationId: string, afterSequence: bigint): readonly AssistantReplyEvent[] {
    return Object.freeze((this.#events.get(operationId) ?? []).filter((event) => event.sequence > afterSequence).map((event) => Object.freeze({ ...event })));
  }
}
```

The implementation must reject non-positive or non-contiguous sequences, reject text over 50,000 characters, require a non-empty error only for `failed`, and return frozen copies in `replay`. The event store remains an in-memory contract fixture; production persistence is represented by the injectable `AssistantReplyEventStore` boundary and is not silently claimed by this package.

- [ ] **Step 4: Extend `AssistantChatService` with audio and the resolver.**

Use a discriminated `AssistantAttachment` union. Add `resolveArtifact?: AssistantArtifactResolver` to options and require a non-null committed record for each attachment. Compare resolver output against the requested `artifactId`, `session`, pairing generation, policy revision, kind, MIME, size, digest, and audio duration. Use `10485760` and `120000` only for audio; retain the 25 MiB image/file and 50 MiB aggregate limits.

Define the resolver boundary with these fields:

```ts
export type AssistantArtifactCommitment = Readonly<{
  artifactId: string;
  status: "message_committed";
  session: BridgeSessionIdentity;
  pairingGeneration: bigint;
  connectionGeneration: bigint;
  policyRevision: bigint;
  kind: AssistantAttachment["kind"];
  mimeType: AssistantAttachment["mimeType"];
  sizeBytes: number;
  sha256: string;
  durationMs?: number;
}>;

export type AssistantArtifactResolver = (input: Readonly<{
  attachment: AssistantAttachment;
  session: BridgeSessionIdentity;
}>) => Promise<AssistantArtifactCommitment | null> | AssistantArtifactCommitment | null;
```

`AssistantChatServiceOptions` also gains `eventStore?: AssistantReplyEventStore` and `boundConnectionGeneration?: bigint`; any request with attachments must have both a resolver and a bound connection generation. The service compares the commitment's connection generation with the bound value before dispatch.

Add `respondStream?: (text: string, attachments: readonly AssistantAttachment[]) => AsyncIterable<string> | Promise<AsyncIterable<string>>` and:

```ts
async stream(request: AssistantMessageRequest, sink: (event: AssistantReplyEvent) => Promise<void> | void): Promise<AssistantMessageResult>
```

`stream` must re-use the existing session, zero-retention, authorization, resolver, and operation claim checks. It appends each emitted event to the configured event store before calling `sink`, starts at sequence `1n`, rejects a cumulative reply above 50,000 characters, emits `complete` after the final delta, and emits `failed` with a closed error code when the responder throws. If no `respondStream` is supplied, emit one `complete` event from the existing `respond` result. `send` keeps its existing complete-result API and uses the same validation path.

- [ ] **Step 5: Run Bridge tests and typecheck.**

Run: `./tools/run-node24 npm test -- bridge-contract/test/service-contract.test.ts && (cd bridge-contract && ../tools/run-node24 npm run typecheck)`

Expected: all Bridge tests pass and the contract package typechecks.

- [ ] **Step 6: Commit only the Bridge files.**

```sh
git add bridge-contract/src/assistant-reply-events.ts bridge-contract/src/index.ts bridge-contract/src/assistant-chat-service.ts bridge-contract/test/service-contract.test.ts
git commit -m "feat(bridge): fence assistant audio and stream replies"
```

### Task 5: Add Android SDK-independent audio and reply-event ports

**Files:**
- Modify: `apps/android/artifact-ports/build.gradle.kts`
- Modify: `apps/android/artifact-ports/src/main/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPorts.kt`
- Create: `apps/android/artifact-ports/src/test/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPortsTest.kt`
- Create: `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/AssistantAudioContracts.kt`
- Create: `apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/AssistantAudioContractsTest.kt`
- Create: `apps/android/tools/test_assistant_audio_backend_static.py`

**Interfaces:**
- Consumes: existing `ArtifactDigest`, `ArtifactSummary`, `ArtifactTicket`, `AssistantHandoffRequest`, and the paired Bridge transport seam.
- Produces: `ArtifactMediaType.AUDIO_MP4`, `MAX_AUDIO_ARTIFACT_BYTES`, `MAX_AUDIO_DURATION_MS`, audio duration and commit-only `artifactId` on `ArtifactSummary`/`ArtifactTicket`, `AssistantAudioAttachment`, and `AssistantReplyEvent` Kotlin value objects.

- [ ] **Step 1: Write failing Kotlin tests and the SDK-free static test.** Add JUnit tests:

```kotlin
@Test
fun audio_summary_accepts_the_closed_limits() {
    val summary = ArtifactSummary(
        selection = selection(),
        mediaType = ArtifactMediaType.AUDIO_MP4,
        digest = ArtifactDigest("a".repeat(64), 10L * 1024L * 1024L),
        durationMs = 120_000L,
    )
    assertEquals(120_000L, summary.durationMs)
}

private fun selection() = GrantedArtifactSelection(
    selectionId = "selection-audio",
    source = ArtifactSelectionSource.SAF,
    readGrant = ArtifactReadGrant("grant-audio"),
)

@Test
fun audio_attachment_rejects_duration_over_two_minutes() {
    assertThrows(IllegalArgumentException::class.java) {
        AssistantAudioAttachment("artifact-audio", "voice.m4a", 1L, "a".repeat(64), 120_001L)
    }
}
```

The static test must require the new constants and types, and reject Android imports, `Uri`, paths, URLs, raw bytes, sockets, `ProcessBuilder`, `Runtime.getRuntime`, VPN surfaces, and recorder APIs in the new backend source files.

- [ ] **Step 2: Run the Android focused tests before implementation.**

Run: `python3 -m unittest apps/android/tools/test_assistant_audio_backend_static.py` and `cd apps/android && ./gradlew --no-daemon :artifact-ports:test :core-model:test`

Expected: FAIL because the new static test files/types do not exist and the audio fields are absent.

- [ ] **Step 3: Implement the closed Kotlin value objects.**

Add `AUDIO_MP4("audio/mp4")`, `MAX_AUDIO_ARTIFACT_BYTES = 10L * 1024L * 1024L`, and `MAX_AUDIO_DURATION_MS = 120_000L`. Extend the existing artifact summary/ticket with nullable `durationMs` and nullable commit-only `artifactId`; require duration exactly for `AUDIO_MP4`, reject it for other media, require `artifactId` only after `MESSAGE_COMMITTED`, and apply the audio byte limit before the existing aggregate check.

Create `AssistantAudioContracts.kt` with no Android imports:

```kotlin
const val MAX_ASSISTANT_AUDIO_BYTES: Long = 10L * 1024L * 1024L
const val MAX_ASSISTANT_AUDIO_DURATION_MS: Long = 120_000L

data class AssistantAudioAttachment(
    val artifactId: String,
    val displayName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val durationMs: Long,
) {
    init {
        require(artifactId.matches(Regex("^[A-Za-z0-9._~-]{1,128}$")))
        require(displayName.isNotBlank() && !displayName.contains('/') && !displayName.contains('\\'))
        require(byteSize in 0L..MAX_ASSISTANT_AUDIO_BYTES)
        require(sha256Hex.matches(Regex("^[A-Fa-f0-9]{64}$")))
        require(durationMs in 1L..MAX_ASSISTANT_AUDIO_DURATION_MS)
    }
}

enum class AssistantReplyEventKind { DELTA, COMPLETE, FAILED }

data class AssistantReplyEvent(
    val operationId: String,
    val messageId: String,
    val sequence: ULong,
    val kind: AssistantReplyEventKind,
    val text: String,
    val errorCode: String? = null,
) {
    init {
        require(operationId.isNotBlank() && messageId.isNotBlank())
        require(sequence > 0uL)
        require(text.length <= 50_000)
        if (kind == AssistantReplyEventKind.FAILED) require(!errorCode.isNullOrBlank())
        else require(errorCode == null)
    }
}
```

Use constructor validation to reject blank IDs, path separators, non-64-hex digests, byte size over 10 MiB, duration outside `1..120000`, zero sequence, text over 50,000 characters, error on non-failed events, and missing error on failed events.

- [ ] **Step 4: Run focused Android tests and source gates.**

Run: `python3 -m unittest apps/android/tools/test_assistant_audio_backend_static.py apps/android/tools/test_assistant_handoff_static.py apps/android/tools/test_artifact_ports_static.py` and `cd apps/android && ./gradlew --no-daemon :artifact-ports:test :core-model:test`

Expected: all static and JVM tests pass; no UI, recorder, or network surface is introduced.

- [ ] **Step 5: Commit only the Android backend-port files.**

```sh
git add apps/android/artifact-ports/build.gradle.kts apps/android/artifact-ports/src/main/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPorts.kt apps/android/artifact-ports/src/test/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPortsTest.kt apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/AssistantAudioContracts.kt apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/AssistantAudioContractsTest.kt apps/android/tools/test_assistant_audio_backend_static.py
git commit -m "feat(android): add assistant audio backend ports"
```

### Task 6: Update the MVP contract documentation and run the complete verification gate

**Files:**
- Modify: `docs/mvp/mvp-vertical-slice-contract.md`
- Test: `mvp-contract/test/mvp-contract.test.ts`, all package tests from Tasks 1–5

**Interfaces:**
- Consumes: all completed wire, artifact, Bridge, adapter, and Android port contracts.
- Produces: source-backed documentation that no longer describes assistant chat as text-only and a verification record from fresh commands.

- [ ] **Step 1: Update the documentation from the implemented contract.**

Describe the text-plus-audio request, opaque committed artifact metadata, audio limits, agent-side processing, and ordered `delta/complete/failed` reply events. Do not claim an Android recorder, UI, animation, TTS, or provider integration that is not implemented by these tasks.

- [ ] **Step 2: Run the complete fresh verification set.**

Run:

```sh
./tools/run-node24 npm test
./tools/run-node24 npm run typecheck
./tools/run-node24 npx tsc --noEmit --strict --target ES2024 --module NodeNext --moduleResolution NodeNext mvp-contract/src/wire-codec.ts
(cd artifact-contract && ../tools/run-node24 npm test && ../tools/run-node24 npm run typecheck)
(cd bridge-contract && ../tools/run-node24 npm test && ../tools/run-node24 npm run typecheck)
(cd integrations && ../tools/run-node24 npm test && ../tools/run-node24 npm run typecheck)
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
(cd apps/android && ./gradlew --no-daemon check)
```

Expected: every command exits 0. If the Android SDK/AAR is unavailable, report the exact Gradle dependency failure and still retain the passing SDK-free gates; do not claim the Android build passes.

- [ ] **Step 3: Inspect the final diff and commit documentation only.**

Run: `git diff --check`, `git status --short`, and `git diff --stat HEAD~6..HEAD` after confirming the commits contain only the task files. Then:

```sh
git add docs/mvp/mvp-vertical-slice-contract.md mvp-contract/test/mvp-contract.test.ts
git commit -m "docs(mvp): describe assistant audio backend"
```

## Handoff

After implementation, verify that no files under Android `src/main` add Compose, animation, microphone, TTS, recorder, or UI code. The next separate plan may consume `AssistantAudioAttachment`, `AssistantReplyEvent`, the committed artifact resolver, and the ordered reply-event store to build the Gemini-like half-screen surface.
