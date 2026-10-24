import profileSchema from "../schemas/v1/profile.schema.json" with { type: "json" };
import commonSchema from "../schemas/v1/common.schema.json" with { type: "json" };
import enrollmentSchema from "../schemas/v1/enrollment.schema.json" with { type: "json" };
import connectSchema from "../schemas/v1/connect.schema.json" with { type: "json" };
import messagesRegistrySchema from "../schemas/v1/messages-registry.schema.json" with { type: "json" };
import versionsRegistrySchema from "../schemas/v1/versions-registry.schema.json" with { type: "json" };
import controlEnvelopeSchema from "../schemas/v1/control-envelope.schema.json" with { type: "json" };
import keyRotationSchema from "../schemas/v1/key-rotation.schema.json" with { type: "json" };
import operationSchema from "../schemas/v1/operation.schema.json" with { type: "json" };
import receiptSchema from "../schemas/v1/receipt.schema.json" with { type: "json" };
import migrationReceiptSchema from "../schemas/v1/migration-receipt.schema.json" with { type: "json" };
import errorResponseSchema from "../schemas/v1/error-response.schema.json" with { type: "json" };
import errorsRegistrySchema from "../schemas/v1/errors-registry.schema.json" with { type: "json" };
import eventSchema from "../schemas/v1/event.schema.json" with { type: "json" };

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

export const PROTOCOL_SCHEMA_DOCUMENTS = deepFreeze([
  profileSchema,
  commonSchema,
  enrollmentSchema,
  connectSchema,
  messagesRegistrySchema,
  versionsRegistrySchema,
  controlEnvelopeSchema,
  keyRotationSchema,
  operationSchema,
  receiptSchema,
  migrationReceiptSchema,
  errorResponseSchema,
  errorsRegistrySchema,
  eventSchema,
] as const);

export const REQUIRED_PROTOCOL_SCHEMA_IDS = Object.freeze([
  "urn:open-android-intelligence:protocol:v1:profile",
  "urn:open-android-intelligence:protocol:v1:common",
  "urn:open-android-intelligence:protocol:v1:enrollment",
  "urn:open-android-intelligence:protocol:v1:connect",
  "urn:open-android-intelligence:protocol:v1:messages-registry",
  "urn:open-android-intelligence:protocol:v1:versions-registry",
  "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge",
  "urn:open-android-intelligence:protocol:v1:message:enrollment_response",
  "urn:open-android-intelligence:protocol:v1:message:enrollment_complete",
  "urn:open-android-intelligence:protocol:v1:message:enrollment_error",
  "urn:open-android-intelligence:protocol:v1:message:connect_hello",
  "urn:open-android-intelligence:protocol:v1:message:connect_welcome",
  "urn:open-android-intelligence:protocol:v1:header:enrollment_app_to_bridge",
  "urn:open-android-intelligence:protocol:v1:header:enrollment_bridge_to_app",
  "urn:open-android-intelligence:protocol:v1:header:connect_hello",
  "urn:open-android-intelligence:protocol:v1:header:connect_welcome",
  "urn:open-android-intelligence:protocol:v1:envelope:enrollment_app_to_bridge",
  "urn:open-android-intelligence:protocol:v1:envelope:enrollment_bridge_to_app",
  "urn:open-android-intelligence:protocol:v1:envelope:connect_hello",
  "urn:open-android-intelligence:protocol:v1:envelope:connect_welcome",
  "urn:open-android-intelligence:protocol:v1:control-envelope",
  "urn:open-android-intelligence:protocol:v1:key-rotation",
  "urn:open-android-intelligence:protocol:v1:operation",
  "urn:open-android-intelligence:protocol:v1:receipt",
  "urn:open-android-intelligence:protocol:v1:migration-receipt",
  "urn:open-android-intelligence:protocol:v1:error-response",
  "urn:open-android-intelligence:protocol:v1:errors-registry",
  "urn:open-android-intelligence:protocol:v1:event",
  "urn:open-android-intelligence:protocol:v1:message:device_event",
  "urn:open-android-intelligence:protocol:v1:message:event_ack",
  "urn:open-android-intelligence:protocol:v1:header:device_event",
  "urn:open-android-intelligence:protocol:v1:header:event_ack",
  "urn:open-android-intelligence:protocol:v1:envelope:device_event",
  "urn:open-android-intelligence:protocol:v1:envelope:event_ack",
  "urn:open-android-intelligence:protocol:v1:message:device_ping",
  "urn:open-android-intelligence:protocol:v1:message:bridge_ping",
  "urn:open-android-intelligence:protocol:v1:message:device_presence",
  "urn:open-android-intelligence:protocol:v1:message:device_key_rotation",
  "urn:open-android-intelligence:protocol:v1:message:device_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation",
  "urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation",
  "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:header:device_ping",
  "urn:open-android-intelligence:protocol:v1:header:bridge_ping",
  "urn:open-android-intelligence:protocol:v1:header:device_presence",
  "urn:open-android-intelligence:protocol:v1:header:device_key_rotation",
  "urn:open-android-intelligence:protocol:v1:header:device_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:header:bridge_key_rotation",
  "urn:open-android-intelligence:protocol:v1:header:bridge_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:header:adapter_key_rotation",
  "urn:open-android-intelligence:protocol:v1:header:adapter_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:envelope:device_ping",
  "urn:open-android-intelligence:protocol:v1:envelope:bridge_ping",
  "urn:open-android-intelligence:protocol:v1:envelope:device_presence",
  "urn:open-android-intelligence:protocol:v1:envelope:device_key_rotation",
  "urn:open-android-intelligence:protocol:v1:envelope:device_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:envelope:bridge_key_rotation",
  "urn:open-android-intelligence:protocol:v1:envelope:bridge_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:envelope:adapter_key_rotation",
  "urn:open-android-intelligence:protocol:v1:envelope:adapter_key_rotation_ack",
  "urn:open-android-intelligence:protocol:v1:message:operation_submit",
  "urn:open-android-intelligence:protocol:v1:message:operation_get",
  "urn:open-android-intelligence:protocol:v1:message:operation_wait",
  "urn:open-android-intelligence:protocol:v1:message:operation_cancel",
  "urn:open-android-intelligence:protocol:v1:message:operation_reconcile",
  "urn:open-android-intelligence:protocol:v1:message:operation_command",
  "urn:open-android-intelligence:protocol:v1:message:operation_snapshot",
  "urn:open-android-intelligence:protocol:v1:message:operation_receipt",
  "urn:open-android-intelligence:protocol:v1:message:operation_receipt_ack",
  "urn:open-android-intelligence:protocol:v1:message:receipt_replay",
  "urn:open-android-intelligence:protocol:v1:message:device_protocol_error",
  "urn:open-android-intelligence:protocol:v1:message:bridge_protocol_error",
  "urn:open-android-intelligence:protocol:v1:message:adapter_protocol_error",
  "urn:open-android-intelligence:protocol:v1:header:operation_submit",
  "urn:open-android-intelligence:protocol:v1:header:operation_get",
  "urn:open-android-intelligence:protocol:v1:header:operation_wait",
  "urn:open-android-intelligence:protocol:v1:header:operation_cancel",
  "urn:open-android-intelligence:protocol:v1:header:operation_reconcile",
  "urn:open-android-intelligence:protocol:v1:header:operation_command",
  "urn:open-android-intelligence:protocol:v1:header:operation_snapshot",
  "urn:open-android-intelligence:protocol:v1:header:operation_receipt",
  "urn:open-android-intelligence:protocol:v1:header:operation_receipt_ack",
  "urn:open-android-intelligence:protocol:v1:header:receipt_replay",
  "urn:open-android-intelligence:protocol:v1:header:device_protocol_error",
  "urn:open-android-intelligence:protocol:v1:header:bridge_protocol_error",
  "urn:open-android-intelligence:protocol:v1:header:adapter_protocol_error",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_submit",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_get",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_wait",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_cancel",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_reconcile",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_command",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_snapshot",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_receipt",
  "urn:open-android-intelligence:protocol:v1:envelope:operation_receipt_ack",
  "urn:open-android-intelligence:protocol:v1:envelope:receipt_replay",
  "urn:open-android-intelligence:protocol:v1:envelope:device_protocol_error",
  "urn:open-android-intelligence:protocol:v1:envelope:bridge_protocol_error",
  "urn:open-android-intelligence:protocol:v1:envelope:adapter_protocol_error",
] as const);
