"""Project native tool identities without parsing assistant or tool prose."""
from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Any


def project_execution(rows: list[dict[str, Any]], generation_for_client: Callable[[str], str | None]) -> dict[int, dict[str, Any]]:
    """Keep tools inside the native user turn whose client identity is recorded.

    A result establishes completion, not success inferred from its output. Rows
    without an explicit accepted client/generation binding retain ordinary text.
    """
    projected: dict[int, dict[str, Any]] = {}
    generation: str | None = None
    calls: dict[str, tuple[int, dict[str, Any]]] = {}
    for row in rows:
        if row.get("role") == "user":
            client = row.get("platform_message_id")
            generation = (generation_for_client(client)
                          if not row.get("_compressed_summary") and isinstance(client, str) and client else None)
            calls = {}
        if generation is None or row.get("active", 1) in (0, False) and not row.get("compacted", 0):
            continue
        row_id = row.get("id")
        if type(row_id) is not int:
            continue
        if row.get("role") == "assistant":
            entry: dict[str, Any] = {"generationId": generation}
            steps = []
            native_calls = row.get("tool_calls")
            for call in native_calls if isinstance(native_calls, list) else []:
                if not isinstance(call, Mapping):
                    continue
                step_id, function = call.get("id"), call.get("function")
                if not isinstance(step_id, str) or not step_id or not isinstance(function, Mapping):
                    continue
                name, arguments = function.get("name"), function.get("arguments")
                if not isinstance(name, str) or not name or not isinstance(arguments, str) or step_id in calls:
                    continue
                step = {"stepId": step_id, "eventId": str(row_id), "toolName": name,
                        "arguments": arguments, "output": None, "status": "unknown", "revision": row_id}
                steps.append(step)
                calls[step_id] = (row_id, step)
            if steps:
                entry["executionSteps"] = steps
            projected[row_id] = entry
        elif (row.get("role") == "tool" and isinstance(row.get("tool_call_id"), str)
              and row["tool_call_id"] in calls):
            _, step = calls[row["tool_call_id"]]
            content = row.get("content")
            if isinstance(content, list):
                content = "".join(part.get("text", "") for part in content
                                  if isinstance(part, Mapping) and part.get("type") == "text" and isinstance(part.get("text"), str))
            step.update(eventId=str(row_id), revision=row_id, output=content if isinstance(content, str) else None,
                        status="failed" if row.get("is_error") is True else "completed")
    return projected
