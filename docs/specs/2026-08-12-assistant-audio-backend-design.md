# Assistant Audio Backend Design

> **2026-09-23 附件策略更新：** 本文的 10 MiB 音频、50 MiB 消息附件字节限制只保留为历史设计背景；Gateway 传输策略已由 ADR-0051 取代，格式与处理大小由 Agent 负责。Agent 自有音频处理策略不由该 ADR 改写。


**Status:** Approved design, backend implementation pending

**Date:** 2026-08-12

## Goal

Extend the existing assistant chat backend so the future Android default-assistant
surface can send text and user-recorded AAC/M4A audio to Hermes or OpenClaw through
the paired Bridge. The phone must not transcribe or synthesize speech. UI layout,
Gemini-like interaction, animation, and recording controls are explicitly deferred.

## Product decisions

- The assistant experience uses the existing `assistant.chat` operation rather than
  a second voice-only operation.
- Audio is an artifact. Chat messages carry only a Bridge-issued opaque artifact ID
  and verified metadata; audio bytes never appear in JSON chat payloads or logs.
- The Android recorder will later produce `audio/mp4` (AAC/M4A) input.
- One audio attachment is at most 2 minutes (`120000` ms) and 10 MiB
  (`10485760` bytes).
- Audio counts toward the existing limits of four attachments per message and
  50 MiB total attachment bytes.
- Agent-side adapters receive the committed artifact reference and are responsible
  for audio decoding, transcription, multimodal analysis, or other processing.
- Text responses expose a durable ordered event shape with `delta`, `complete`,
  and `failed` states so a later Android UI can render streaming text.

## Alternatives considered

### Unified assistant chat with audio artifacts (selected)

This extends the existing attachment, authorization, operation-id, pairing-fence,
and zero-retention paths. It keeps text and audio message ordering identical and
lets the existing encrypted artifact lifecycle protect both media types.

### Separate `assistant.voice` operation

This would make the protocol name explicit, but would duplicate session binding,
authorization, replay/idempotency, error mapping, and adapter dispatch. It also
would make a future message containing both text and audio harder to represent.

### Device-side speech-to-text

This would reduce upload size but violates the product decision that the agent
receives the original audio for processing and would make device model/provider
configuration part of this project.

## Architecture

The implementation has four connected boundaries:

1. **Versioned protocol boundary**

   `assistant-chat:v1` gains a discriminated audio attachment. Audio metadata
   includes `artifact_id`, `audio/mp4`, byte length, SHA-256, display name, and
   duration. The closed validator rejects unknown fields, locations, unsupported
   media, invalid digests, and out-of-range limits.

2. **Artifact boundary**

   The TypeScript artifact contract and Android artifact ports add `AUDIO_MP4`.
   Duration is included in the ticket and proof-bound summary so a valid digest
   cannot be paired with a different duration. Ticket authorization continues to
   carry pairing generation, connection generation, and policy revision.

3. **Bridge and adapter boundary**

   `AssistantChatService` validates committed artifact metadata before dispatch,
   checks the authenticated session and current zero-retention evidence, and
   forwards a typed attachment to the common adapter contract. Hermes and OpenClaw
   keep their existing authoritative profile mappings and accept the same normalized
   audio attachment shape. Adapter diagnostics retain only non-content metadata.

4. **Android backend port**

   Core-model value objects represent an audio attachment and bounded assistant
   reply events without Android UI types or provider locations. The future Activity
   and `VoiceInteractionSession` will depend on these ports; this phase does not
   add recording, permissions, IPC, or UI code.

## Request flow

```text
typed Android audio result
  -> validate MIME, duration, size
  -> calculate digest while holding user grant
  -> stage encrypted scratch copy
  -> issue ticket and verify Bridge proof
  -> commit artifact and obtain opaque artifact_id
  -> send assistant.chat with text + metadata-only attachment
  -> Bridge revalidates session, ticket ownership, digest and policy fence
  -> Hermes/OpenClaw adapter receives the artifact reference
  -> ordered assistant reply events reach the paired session
```

The assistant request is not accepted until the artifact commit has succeeded.
An interrupted upload invalidates the ticket and requires a new ticket; it cannot
be retried with the same proof. A committed artifact is deleted only after the
message commit receipt or the existing orphan-reclaim policy applies.

## Wire contract

The request attachment is a closed discriminated union:

- image: existing image media types and existing size rules;
- file: existing non-image media types and existing size rules;
- audio: `audio/mp4`, `artifact_id`, `display_name`, `byte_length`, `sha256`, and
  `duration_ms`.

The wire representation never accepts a URI, filesystem path, URL, upload URL,
provider handle, session identity, agent identity, or model-supplied invocation
context. `artifact_id` is opaque and is minted by the Bridge artifact flow.

Reply events use the authenticated operation and message identity from the runtime
context, not model-provided identity. Each event has a strictly increasing sequence
within one operation. `delta` contains bounded text only; `complete` carries the
final bounded reply; `failed` carries a closed error code and no partial artifact
data.

## Authorization and failure behavior

- Fresh install and absent local handoff remain deny-first; this backend does not
  grant assistant access by receiving an agent request.
- The Bridge rejects unbound or stale sessions with `CONNECTION_FENCED`.
- A stale pairing, connection generation, or policy revision rejects the request
  before agent dispatch.
- Missing, expired, mismatched, or uncommitted audio artifacts fail closed.
- Zero-retention evidence is required and must be current; provider retention,
  logging, training, or human-review flags fail closed.
- Duplicate operation IDs return the original durable result only when all bound
  request parameters match. A parameter mismatch is rejected.
- Adapter errors are normalized to the existing closed error model; raw provider
  errors and audio content do not cross the device boundary.

## Testing strategy

Tests are written first for each boundary:

- schema and wire codec: accept valid audio, reject duration/size/MIME/field/path
  violations, and round-trip the exact snake-case representation;
- artifact contract: issue and proof-bind audio duration, enforce the 10 MiB and
  120-second limits, and preserve existing image/file behavior;
- Bridge service: authorize and dispatch audio metadata, reject stale or uncommitted
  artifacts, preserve idempotency, and avoid retaining body/audio bytes;
- Hermes/OpenClaw shared adapter: normalize audio attachments identically and reject
  unsupported or malformed metadata;
- Android core-model: validate bounded audio attachment and reply-event value
  objects without Android dependencies;
- host-side Android static gate: ensure the backend port adds no socket, VPN,
  arbitrary IPC, provider URI, or command-execution surface.

No test will depend on a Gemini screenshot or UI implementation in this phase.

## Scope boundary for the next phase

The next implementation phase may modify protocol schemas/codecs, artifact
contracts, Bridge services, integration adapters, Android core-model/backend ports,
and their tests. It must not add Compose layouts, animation definitions, audio
recording APIs, TTS, microphone permission handling, or default-assistant visual
surfaces until this backend contract is verified.
