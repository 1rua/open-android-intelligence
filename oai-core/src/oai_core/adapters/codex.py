"""Codex's native app-server protocol, restricted to an explicitly selected home.

No conversation database is maintained by OAI. All IDs, history and mutations
belong to the configured Codex app-server. Its native approvals remain in force;
this adapter declines interactive approvals until an approval UI is supported.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import re
import tomllib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncIterator

from ..sessions import AdapterError, Principal


SOURCE_KINDS = [
    "cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview",
    "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown",
]

# Model credentials are an explicit deployment choice. In particular, no OAI
# login, tool credential, or unrelated service token crosses this boundary.
RUNTIME_ENV = {
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "TMP", "TEMP",
    "LANG", "LANGUAGE", "TZ", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
}
PROVIDER_ENV = {
    "OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID", "OPENAI_PROJECT_ID",
    "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL",
    "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_API_VERSION",
    "GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_CLOUD_PROJECT", "GOOGLE_CLOUD_LOCATION",
    "GOOGLE_APPLICATION_CREDENTIALS", "AWS_PROFILE", "AWS_REGION", "AWS_DEFAULT_REGION",
    "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
}


def _runtime_variable(name: str) -> bool:
    return name in RUNTIME_ENV or re.fullmatch(r"LC_[A-Z_]+", name) is not None


def _timestamp(value: Any) -> str | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")
    return value if isinstance(value, str) else None


def _summary(thread: dict[str, Any], archived: bool = False) -> dict[str, Any]:
    return {
        "id": str(thread["id"]),
        "title": thread.get("name") or thread.get("preview") or str(thread["id"]),
        "updatedAt": _timestamp(thread.get("updatedAt")),
        "archived": archived,
    }


@dataclass
class _Execution:
    """Volatile native turn ownership, not a conversation or recovery store."""

    started: asyncio.Future
    terminal: asyncio.Future
    turn_id: str | None = None
    task: asyncio.Task | None = None
    early_terminals: dict[str, dict[str, Any]] = field(default_factory=dict)


class CodexSessionAdapter:
    def __init__(
        self, command: list[str], codex_home: Path, *, adapter_id: str = "codex",
        name: str = "Codex", request_timeout: float = 30, turn_timeout: float = 300,
        env: dict[str, str] | None = None,
    ) -> None:
        if not command or any(not isinstance(arg, str) or not arg for arg in command):
            raise ValueError("Codex requires an explicit command argument list")
        if not str(codex_home).strip():
            raise ValueError("Codex requires an explicit codex_home")
        if request_timeout <= 0 or turn_timeout <= 0:
            raise ValueError("Codex timeouts must be positive")
        if env is not None and (not isinstance(env, dict) or any(
            not isinstance(key, str) or (not _runtime_variable(key) and key not in PROVIDER_ENV)
            or not isinstance(value, str) or "\x00" in value
            for key, value in env.items()
        )):
            raise ValueError("Codex env only accepts explicit runtime and model-provider variables")
        self.command = list(command)
        self.codex_home = Path(codex_home).resolve()
        self.adapter_id, self.name = adapter_id, name
        self.request_timeout, self.turn_timeout = request_timeout, turn_timeout
        self.env = dict(env or {})
        self._process: asyncio.subprocess.Process | None = None
        self._reader: asyncio.Task | None = None
        self._start_lock, self._write_lock = asyncio.Lock(), asyncio.Lock()
        self._pending: dict[int, asyncio.Future] = {}
        self._streams: dict[str, asyncio.Queue] = {}
        self._executions: dict[str, _Execution] = {}
        self._turns: dict[str, str] = {}
        self._sequence = 0

    def descriptor(self) -> dict[str, Any]:
        return {
            "id": self.adapter_id, "name": self.name, "kind": "codex",
            "sessionScope": "deployment", "deviceTools": False,
            "capabilities": {
                **{key: key != "delete" for key in (
                    "list", "history", "create", "send", "rename", "archive",
                    "delete", "cancel", "stream",
                )},
                "generationRecovery": False, "idempotentSend": False,
            },
        }

    async def status(self) -> bool:
        try:
            await self._rpc("thread/list", {"limit": 1, "modelProviders": [], "sourceKinds": SOURCE_KINDS})
            return True
        except (AdapterError, OSError):
            return False

    async def _ensure_started(self) -> None:
        async with self._start_lock:
            if self._process is not None and self._process.returncode is None:
                return
            if self._process is not None:
                await self.close()
            if not self.codex_home.is_dir():
                raise AdapterError("AGENT_OFFLINE", 503)
            environment = {key: value for key, value in os.environ.items() if _runtime_variable(key)}
            environment.update(self.env)
            environment["CODEX_HOME"] = str(self.codex_home)
            self._assert_no_device_credentials(environment)
            try:
                self._process = await asyncio.create_subprocess_exec(
                    *self.command, env=environment,
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    # Native diagnostics may contain private transcript fragments.
                    # Do not forward them into Core's global logs.
                    stderr=asyncio.subprocess.DEVNULL, limit=8 * 1024 * 1024,
                )
                self._reader = asyncio.create_task(self._read_loop(self._process))
                await self._rpc_raw("initialize", {
                    "clientInfo": {"name": "oai-core", "version": "0.1.0"},
                    "capabilities": {"experimentalApi": False},
                })
                await self._write({"method": "initialized", "params": {}})
            except asyncio.CancelledError:
                await self.close()
                raise
            except (OSError, AdapterError):
                await self.close()
                raise AdapterError("AGENT_OFFLINE", 503) from None

    def _assert_no_device_credentials(self, environment: dict[str, str]) -> None:
        # Check only presence, never read an OAI account's credential file.
        # Filesystem permissions still require a dedicated OS service identity.
        home = Path(environment["HOME"]) if environment.get("HOME") else Path.home()
        credential = home / ".config" / "oai" / "client.json"
        if credential.exists() or credential.is_symlink():
            raise AdapterError("AGENT_UNSAFE_DEVICE_CREDENTIALS", 503)
        config_file = self.codex_home / "config.toml"
        if not config_file.is_file():
            return
        try:
            with config_file.open("rb") as stream:
                config = tomllib.load(stream)
        except (OSError, ValueError):
            raise AdapterError("INVALID_AGENT_CONFIG", 503) from None
        servers = config.get("mcp_servers", {})
        if not isinstance(servers, dict):
            raise AdapterError("INVALID_AGENT_CONFIG", 503)
        for server in servers.values():
            if not isinstance(server, dict):
                raise AdapterError("INVALID_AGENT_CONFIG", 503)
            command = str(server.get("command", ""))
            args = server.get("args", [])
            environment = server.get("env", {})
            tokens = [command, *(args if isinstance(args, list) else [])]
            invokes_oai = any(isinstance(token, str) and (
                re.search(r"(?:^|[\s/\\])oai(?:\.exe)?(?:$|\s)", token) is not None
                or token in {"oai_core.cli", "oai_core.mcp"}
            ) for token in tokens)
            fixed_oai_secret = isinstance(environment, dict) and any(
                str(key).upper().startswith("OAI_") for key in environment
            )
            if invokes_oai or fixed_oai_secret:
                raise AdapterError("AGENT_UNSAFE_DEVICE_CREDENTIALS", 503)

    async def _write(self, payload: dict[str, Any]) -> None:
        async with self._write_lock:
            process = self._process
            if process is None or process.returncode is not None or process.stdin is None:
                raise AdapterError("AGENT_OFFLINE", 503)
            try:
                process.stdin.write((json.dumps(payload, separators=(",", ":")) + "\n").encode())
                await process.stdin.drain()
            except (BrokenPipeError, ConnectionError):
                raise AdapterError("AGENT_OFFLINE", 503) from None

    async def _rpc(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        await self._ensure_started()
        return await self._rpc_raw(method, params)

    async def _rpc_raw(
        self, method: str, params: dict[str, Any], *, wait_for_response: bool = False,
    ) -> dict[str, Any]:
        self._sequence += 1
        identifier = self._sequence
        future = asyncio.get_running_loop().create_future()
        self._pending[identifier] = future
        try:
            await self._write({"id": identifier, "method": method, "params": params})
            try:
                # The service owns turn/start until its actual response (or
                # transport loss). An observer deadline must not discard a late
                # acceptance and make the native turn impossible to cancel.
                message = await future if wait_for_response else await asyncio.wait_for(future, self.request_timeout)
            except TimeoutError:
                # Never retry a timed-out native mutation: its outcome may be unknown.
                raise AdapterError("AGENT_TIMEOUT", 504) from None
            if "error" in message:
                code = message["error"].get("code")
                raise AdapterError("UNSUPPORTED_CAPABILITY" if code == -32601 else "NATIVE_AGENT_ERROR", 405 if code == -32601 else 502)
            result = message.get("result")
            if not isinstance(result, dict):
                raise AdapterError("INVALID_AGENT_RESPONSE", 502)
            return result
        finally:
            self._pending.pop(identifier, None)

    def _emit(self, thread_id: str, message: dict[str, Any]) -> None:
        execution = self._executions.get(thread_id)
        method, params = message.get("method"), message.get("params", {})
        if execution is not None and not execution.terminal.done():
            turn = params.get("turn")
            if method == "turn/completed" and isinstance(turn, dict) and turn.get("status") in {"completed", "interrupted", "failed"}:
                turn_id = turn.get("id")
                if isinstance(turn_id, str) and turn_id:
                    if execution.turn_id == turn_id:
                        execution.terminal.set_result({"id": turn_id, "status": turn["status"]})
                    elif execution.turn_id is None:
                        # Notifications may precede turn/start's RPC response.
                        # Keep only lifecycle evidence, independently of deltas.
                        if len(execution.early_terminals) >= 128:
                            execution.early_terminals.pop(next(iter(execution.early_terminals)))
                        execution.early_terminals[turn_id] = {"id": turn_id, "status": turn["status"]}
            elif method == "oai/error" and params.get("code") == "AGENT_OFFLINE":
                execution.terminal.set_exception(AdapterError("AGENT_OFFLINE", 503))
        queue = self._streams.get(thread_id)
        if queue is None:
            return
        try:
            queue.put_nowait(message)
        except asyncio.QueueFull:
            # End this observer once; keep consuming native lifecycle evidence.
            # Slow output never blocks RPC responses or discards turn ownership.
            self._streams.pop(thread_id, None)
            while not queue.empty():
                queue.get_nowait()
            queue.put_nowait({"method": "oai/error", "params": {"code": "STREAM_OVERFLOW"}})

    async def _read_loop(self, process: asyncio.subprocess.Process) -> None:
        try:
            assert process.stdout is not None
            while line := await process.stdout.readline():
                message = json.loads(line)
                if not isinstance(message, dict):
                    raise ValueError("invalid native frame")
                method = message.get("method")
                if method is None and "id" in message:
                    future = self._pending.get(message["id"])
                    if future is not None and not future.done():
                        future.set_result(message)
                    continue
                params = message.get("params") or {}
                if not isinstance(params, dict):
                    raise ValueError("invalid native parameters")
                thread_id = params.get("threadId")
                if "id" in message:
                    # Never turn OAI login into native shell/file approval.
                    if method in ("item/commandExecution/requestApproval", "item/fileChange/requestApproval"):
                        reply = {"id": message["id"], "result": {"decision": "decline"}}
                    else:
                        reply = {"id": message["id"], "error": {"code": -32601, "message": "Interactive native request is not supported"}}
                    await self._write(reply)
                    if thread_id:
                        self._emit(thread_id, {"method": "oai/error", "params": {"code": "NATIVE_APPROVAL_UNSUPPORTED"}})
                elif thread_id:
                    self._emit(thread_id, message)
        except (ValueError, TypeError, OSError, AdapterError):
            pass
        finally:
            for future in list(self._pending.values()):
                if not future.done():
                    future.set_exception(AdapterError("AGENT_OFFLINE", 503))
            for thread_id in set(self._streams) | set(self._executions):
                self._emit(thread_id, {"method": "oai/error", "params": {"code": "AGENT_OFFLINE"}})
            if process.returncode is None:
                with contextlib.suppress(ProcessLookupError):
                    process.terminate()

    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]:
        sessions: list[dict[str, Any]] = []
        for archived in (False, True):
            cursor: str | None = None
            seen: set[str] = set()
            while True:
                result = await self._rpc("thread/list", {
                    "limit": 100, "cursor": cursor, "archived": archived,
                    "modelProviders": [], "sourceKinds": SOURCE_KINDS,
                })
                sessions.extend(_summary(thread, archived) for thread in result.get("data", []))
                cursor = result.get("nextCursor")
                if not cursor:
                    break
                if cursor in seen:
                    raise AdapterError("INVALID_AGENT_PAGINATION", 502)
                seen.add(cursor)
        return sessions

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        result = await self._rpc("thread/read", {"threadId": session_id, "includeTurns": False})
        if result.get("thread", {}).get("historyMode") == "paginated":
            turns: list[dict[str, Any]] = []
            cursor: str | None = None
            seen: set[str] = set()
            while True:
                page = await self._rpc("thread/turns/list", {
                    "threadId": session_id, "itemsView": "full", "sortDirection": "asc",
                    "limit": 100, "cursor": cursor,
                })
                turns.extend(page.get("data", []))
                cursor = page.get("nextCursor")
                if not cursor:
                    break
                if cursor in seen:
                    raise AdapterError("INVALID_AGENT_PAGINATION", 502)
                seen.add(cursor)
        else:
            result = await self._rpc("thread/read", {"threadId": session_id, "includeTurns": True})
            turns = result.get("thread", {}).get("turns", [])
        messages: list[dict[str, Any]] = []
        for turn in turns:
            for item in turn.get("items", []):
                kind = item.get("type")
                if kind == "userMessage":
                    text = "\n".join(part["text"] for part in item.get("content", []) if part.get("type") == "text")
                    role = "user"
                elif kind == "agentMessage":
                    text, role = item.get("text", ""), "assistant"
                else:
                    continue
                messages.append({
                    "id": item["id"], "role": role, "text": text,
                    "createdAt": _timestamp(turn.get("startedAt")),
                })
        return messages

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        result = await self._rpc("thread/start", {"ephemeral": False})
        thread = result["thread"]
        if title:
            await self._rpc("thread/name/set", {"threadId": thread["id"], "name": title})
            thread = {**thread, "name": title}
        return _summary(thread)

    async def _execute(self, session_id: str, text: str, execution: _Execution) -> None:
        try:
            await self._rpc_raw("thread/resume", {"threadId": session_id})
            result = await self._rpc_raw("turn/start", {
                "threadId": session_id,
                "input": [{"type": "text", "text": text, "text_elements": []}],
            }, wait_for_response=True)
            del text
            turn = result.get("turn")
            turn_id = turn.get("id") if isinstance(turn, dict) else None
            if not isinstance(turn_id, str) or not turn_id:
                execution.started.set_exception(AdapterError("OUTCOME_UNKNOWN", 409))
                # No trustworthy turn identity: retain the reservation until
                # transport loss. Never replay or cancel an arbitrary turn.
                await execution.terminal
                return
            execution.turn_id = turn_id
            self._turns[session_id] = turn_id
            execution.started.set_result(turn_id)
            early = execution.early_terminals.get(turn_id)
            if early is not None and not execution.terminal.done():
                execution.terminal.set_result(early)
            execution.early_terminals.clear()
            await execution.terminal
        except AdapterError as exc:
            if not execution.started.done():
                execution.started.set_exception(exc)
        except asyncio.CancelledError:
            if not execution.started.done():
                execution.started.set_exception(AdapterError("AGENT_OFFLINE", 503))
            raise
        finally:
            # Only this service task, driven by native terminal/transport loss,
            # releases execution ownership. Closing send() cannot reach here.
            if self._executions.get(session_id) is execution:
                self._executions.pop(session_id, None)
                self._turns.pop(session_id, None)
            execution.early_terminals.clear()

    async def send(self, principal: Principal, session_id: str, text: str, *,
                   client_message_id: str | None = None) -> AsyncIterator[dict[str, Any]]:
        # client_message_id remains Core's correlation identity. Codex has no
        # negotiated idempotency/recovery contract for turn/start; do not invent
        # a native user item ID or send undocumented RPC parameters.
        await self._ensure_started()
        if session_id in self._executions:
            raise AdapterError("SESSION_BUSY", 409)
        loop = asyncio.get_running_loop()
        execution = _Execution(loop.create_future(), loop.create_future())
        for future in (execution.started, execution.terminal):
            future.add_done_callback(lambda result: result.exception() if not result.cancelled() else None)
        queue: asyncio.Queue = asyncio.Queue(maxsize=128)
        self._executions[session_id] = execution
        self._streams[session_id] = queue
        execution.task = asyncio.create_task(self._execute(session_id, text, execution))
        try:
            try:
                turn_id = await asyncio.wait_for(asyncio.shield(execution.started), self.request_timeout)
            except TimeoutError:
                raise AdapterError("AGENT_TIMEOUT", 504) from None
            yield {"type": "generation", "generationId": turn_id, "state": "running",
                   **({"clientMessageId": client_message_id} if client_message_id is not None else {})}
            streamed_items: set[str] = set()
            assistant_message_id: str | None = None
            deadline = loop.time() + self.turn_timeout
            while True:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    raise AdapterError("AGENT_TIMEOUT", 504)
                try:
                    event = await asyncio.wait_for(queue.get(), remaining)
                except TimeoutError:
                    raise AdapterError("AGENT_TIMEOUT", 504) from None
                method, params = event.get("method"), event.get("params", {})
                if method == "oai/error":
                    yield {"type": "error", "code": params["code"], "state": "unknown"}
                    return
                if method == "item/agentMessage/delta" and params.get("turnId") == turn_id:
                    assistant_message_id = params.get("itemId")
                    streamed_items.add(assistant_message_id or "")
                    yield {"type": "delta", "text": params.get("delta", ""),
                           **({"messageId": assistant_message_id} if assistant_message_id else {})}
                elif method == "item/completed" and params.get("turnId") == turn_id:
                    item = params.get("item", {})
                    if item.get("type") == "agentMessage":
                        assistant_message_id = item.get("id")
                        if assistant_message_id not in streamed_items:
                            yield {"type": "delta", "text": item.get("text", ""),
                                   **({"messageId": assistant_message_id} if assistant_message_id else {})}
                elif method == "turn/completed" and isinstance(params.get("turn"), dict) and params["turn"].get("id") == turn_id:
                    status = params["turn"].get("status")
                    if status == "completed":
                        yield {"type": "done", "generationId": turn_id, **({"messageId": assistant_message_id} if assistant_message_id else {})}
                    elif status in {"interrupted", "failed"}:
                        yield {"type": "error", "code": "TURN_INTERRUPTED" if status == "interrupted" else "NATIVE_TURN_FAILED"}
                    else:
                        yield {"type": "error", "code": "OUTCOME_UNKNOWN", "state": "unknown"}
                    return
        finally:
            if self._streams.get(session_id) is queue:
                self._streams.pop(session_id, None)
            # This queue is only an observer buffer; release its transcript now.
            while not queue.empty():
                queue.get_nowait()

    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        if action == "rename":
            if not isinstance(value, str) or not value.strip():
                raise AdapterError("INVALID_TITLE", 400)
            await self._rpc("thread/name/set", {"threadId": session_id, "name": value})
        elif action == "archive":
            if value is not None and type(value) is not bool:
                raise AdapterError("INVALID_ACTION", 400)
            method = "thread/unarchive" if value is False else "thread/archive"
            await self._rpc(method, {"threadId": session_id})
        elif action == "cancel":
            requested_turn = None
            if value is not None:
                if (not isinstance(value, dict) or set(value) != {"generationId"}
                        or not isinstance(value["generationId"], str) or not value["generationId"]):
                    raise AdapterError("INVALID_ACTION", 400)
                requested_turn = value["generationId"]
            execution = self._executions.get(session_id)
            turn_id = self._turns.get(session_id)
            if turn_id is None:
                if execution is not None or requested_turn is None:
                    raise AdapterError("OUTCOME_UNKNOWN" if execution is not None else "NO_ACTIVE_TURN", 409)
                # A completed service task has no local receipt cache. Read the
                # exact native turn in this thread; never interrupt a new turn.
                result = await self._rpc("thread/read", {"threadId": session_id, "includeTurns": True})
                thread = result.get("thread", {})
                terminal = next((turn for turn in thread.get("turns", [])
                                 if turn.get("id") == requested_turn), None) if thread.get("id") == session_id else None
                if terminal is None:
                    raise AdapterError("GENERATION_NOT_FOUND", 404)
                turn_id = requested_turn
            else:
                if requested_turn is not None and requested_turn != turn_id:
                    raise AdapterError("GENERATION_NOT_FOUND", 404)
                terminal = None
                if execution is not None:
                    # Hold this execution's future across native cleanup. RPC
                    # success acknowledges interrupt, not its execution result.
                    with contextlib.suppress(AdapterError):
                        if not execution.terminal.done():
                            await self._rpc_raw("turn/interrupt", {"threadId": session_id, "turnId": turn_id})
                    with contextlib.suppress(AdapterError, TimeoutError):
                        terminal = await asyncio.wait_for(asyncio.shield(execution.terminal), self.request_timeout)
            outcome = {"interrupted": "cancelled", "completed": "already_completed"}.get(
                terminal.get("status") if terminal is not None and terminal.get("id") == turn_id else None, "unknown")
            return {"id": session_id, "action": action, "generationId": turn_id, "outcome": outcome}

        else:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 405)
        return {"id": session_id, "action": action}

    async def close(self) -> None:
        process, reader = self._process, self._reader
        if process is not None and process.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                process.terminate()
            try:
                await asyncio.wait_for(process.wait(), 2)
            except TimeoutError:
                with contextlib.suppress(ProcessLookupError):
                    process.kill()
                await process.wait()
        if reader is not None and reader is not asyncio.current_task():
            reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await reader
        tasks = [execution.task for execution in list(self._executions.values()) if execution.task is not None]
        for task in tasks:
            if not task.done():
                task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._streams.clear()
        self._turns.clear()
        self._process, self._reader = None, None
