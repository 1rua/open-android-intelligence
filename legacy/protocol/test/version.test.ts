import { describe, expect, it } from "vitest";
import { PROTOCOL_VERSION, SCHEMA_LINE } from "../src/version.js";

describe("protocol baseline", () => {
  it("freezes the approved P0a line", () => {
    expect(PROTOCOL_VERSION).toBe("1.0");
    expect(SCHEMA_LINE).toBe("v1");
  });
});
