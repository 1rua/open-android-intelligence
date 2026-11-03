"""Extend the existing process fixture with isolated synthetic history rows."""

import json
import multiprocessing
from pathlib import Path

import communication_process_fixture as upstream


def _entry(role, directory, connection, host_url, port, conversation):
    if role == "adapter":
        original_host = upstream._host
        original_backend = upstream.SessionBackend
        captured = []

        class CapturedBackend(original_backend):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                captured.append(self)

        async def host_with_history(root, existing):
            upstream.SessionBackend = CapturedBackend
            try:
                result = await original_host(root, existing)
            finally:
                upstream.SessionBackend = original_backend
            if len(captured) != 1:
                raise AssertionError("expected exactly one synthetic backend")

            def read_history(owner, session):
                if owner != upstream.OWNER:
                    return []
                history = Path(root) / "fixture-history.json"
                rows = json.loads(history.read_text(encoding="utf-8")) if history.exists() else {}
                messages = rows.get(session, [])
                upstream._record(Path(root) / "history-observations.jsonl", "history.read",
                                 conversationId=session, count=len(messages))
                return messages

            captured[0].history_reader = read_history
            return result

        upstream._host = host_with_history
    upstream._entry(role, directory, connection, host_url, port, conversation)


class SyntheticProcess(upstream.CommunicationProcess):
    def __init__(self, role, directory, *, host_url=None, port=0, conversation=None):
        super().__init__(role, directory, host_url=host_url, port=port, conversation=conversation)
        context = multiprocessing.get_context("spawn")
        self.process = context.Process(target=_entry,
            args=(role, str(self.directory), self._child_connection, host_url, port, conversation),
            name=f"local-first-{role}", daemon=True)
