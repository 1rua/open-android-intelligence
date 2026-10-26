"""HTTP attachment ingress and durable SSE/WebSocket device transport."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any, Dict, Mapping, Optional
from urllib.parse import parse_qs, unquote, urlsplit

from aiohttp import WSMsgType, web
from .core import GatewayError

logger = logging.getLogger(__name__)

EVENT_STREAM_PATH = "/open-android-intelligence/v2/events"


EVENT_STREAM_WS_PATH = "/open-android-intelligence/v2/events/ws"


SSE_HEARTBEAT = b": ping\n\n"


SSE_HEARTBEAT_SECONDS = 15.0


SSE_QUEUE_SIZE = 100


def _sse_frame(event: Mapping[str, Any]) -> bytes:
    """One SSE frame in the shape contract section 9 defines.

    The `event:` line carries the event type, so `data:` holds only the
    correlation id, the timestamp and the payload the phone decodes.
    """
    data = {
        "correlationId": event.get("correlationId"),
        "occurredAt": event.get("occurredAt"),
        "payload": event.get("payload") or {},
    }
    return (
        f"id: {event.get('eventId')}\n"
        f"event: {event.get('eventType')}\n"
        f"data: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"
    ).encode("utf-8")


def _ws_event_frame(event: Mapping[str, Any]) -> str:
    """One WebSocket event frame carrying durable cursor and JSON-encoded data."""
    data = {
        "correlationId": event.get("correlationId"),
        "occurredAt": event.get("occurredAt"),
        "payload": event.get("payload") or {},
    }
    return json.dumps(
        {
            "id": event.get("eventId"),
            "event": event.get("eventType"),
            "data": json.dumps(data, ensure_ascii=False),
        },
        ensure_ascii=False,
    )


def _extract_event_id_from_item(item: Any) -> Optional[str]:
    """Extract eventId from an item queued for SSE/WS subscribers."""
    if isinstance(item, Mapping):
        val = item.get("eventId") or item.get("id")
        return str(val).strip() if val else None
    if isinstance(item, (bytes, bytearray, memoryview)):
        text = bytes(item).decode("utf-8", errors="replace")
    elif isinstance(item, str):
        text = item
    else:
        return None

    lines = [line.rstrip("\r") for line in text.splitlines()]
    non_empty = [l for l in lines if l]
    if non_empty and all(l.startswith(":") for l in non_empty):
        return None
    if not non_empty:
        return None

    event_id: Optional[str] = None
    has_sse_field = False
    for line in lines:
        if line.startswith(":"):
            continue
        if line.startswith("id:"):
            has_sse_field = True
            val = line[3:]
            if val.startswith(" "):
                val = val[1:]
            val = val.strip()
            if val:
                event_id = val
        elif line == "id":
            has_sse_field = True
            event_id = None
        elif line.startswith("event:") or line == "event" or line.startswith("data:") or line == "data":
            has_sse_field = True

    if has_sse_field:
        return event_id

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            val = parsed.get("eventId") or parsed.get("id")
            return str(val).strip() if val else None
    except Exception:
        pass

    return None


def _ws_message_from_queue_item(item: Any) -> Optional[str]:
    """Convert an item popped from an active subscriber queue into a WebSocket message."""
    if isinstance(item, (bytes, bytearray, memoryview)):
        text = bytes(item).decode("utf-8", errors="replace")
    elif isinstance(item, str):
        text = item
    elif isinstance(item, Mapping):
        return _ws_event_frame(item)
    else:
        return None

    lines = [line.rstrip("\r") for line in text.splitlines()]
    non_empty = [l for l in lines if l]
    if non_empty and all(l.startswith(":") for l in non_empty):
        return None
    if not non_empty:
        return None

    event_id: Optional[str] = None
    event_type: Optional[str] = None
    data_lines: list[str] = []
    is_sse = False
    for line in lines:
        if line.startswith(":"):
            continue
        if line.startswith("id:"):
            is_sse = True
            val = line[3:]
            if val.startswith(" "):
                val = val[1:]
            event_id = val.strip()
        elif line == "id":
            is_sse = True
            event_id = ""
        elif line.startswith("event:"):
            is_sse = True
            val = line[6:]
            if val.startswith(" "):
                val = val[1:]
            event_type = val.strip()
        elif line == "event":
            is_sse = True
            event_type = ""
        elif line.startswith("data:"):
            is_sse = True
            val = line[5:]
            if val.startswith(" "):
                val = val[1:]
            data_lines.append(val)
        elif line == "data":
            is_sse = True
            data_lines.append("")

    if is_sse and (event_id is not None or event_type is not None or data_lines):
        data_str = "\n".join(data_lines) if data_lines else "{}"
        return json.dumps(
            {
                "id": event_id,
                "event": event_type,
                "data": data_str,
            },
            ensure_ascii=False,
        )

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict) and ("id" in parsed or "event" in parsed):
            return text
        if isinstance(parsed, dict):
            return _ws_event_frame(parsed)
    except Exception:
        pass

    return text


def _header_value(headers: Mapping[str, Any], name: str) -> Optional[str]:
    """One header, matched case-insensitively and never guessed."""
    wanted = name.lower()
    for key, value in headers.items():
        if str(key).lower() == wanted:
            text = str(value).strip()
            return text or None
    return None


class GatewayTransport:
    """One host-free transport sharing its caller's device runtime and verifier."""

    def __init__(self, services: Any):
        self.services = services
        self._running = False
        self._loop: asyncio.AbstractEventLoop | None = None
        self._event_subscribers: dict[str, set[asyncio.Queue]] = {}
        self._maintenance_task: asyncio.Task | None = None
        self._event_sink = self._publish_event

    def _publish_event(self, account_id: str, event: Mapping[str, Any]) -> None:
        loop = self._loop
        if loop is None or loop.is_closed():
            return
        try:
            loop.call_soon_threadsafe(self._enqueue_frame, account_id, _sse_frame(event))
        except RuntimeError:
            # The committed event is recovered through the durable cursor.
            return

    def _raw_request(self, request: web.Request, body: bytes) -> Dict[str, Any]:
        """The origin-form target the client actually signed, query included."""
        raw_headers = getattr(request, "raw_headers", ()) or ()
        if raw_headers:
            raw_header_pairs = tuple(
                (
                    name.decode("latin-1") if isinstance(name, bytes) else str(name),
                    value.decode("latin-1") if isinstance(value, bytes) else str(value),
                )
                for name, value in raw_headers
            )
        else:
            raw_header_pairs = tuple((k, v) for k, v in request.headers.items())
        return {
            "method": request.method,
            # `url` is the origin-form target the client signed: the boundary
            # reads `url` first, and a target that lost its query would make
            # every signed request with a query fail verification.
            "url": request.path_qs,
            "target": request.path_qs,
            "headers": dict(request.headers),
            "rawHeaders": raw_header_pairs,
            "remoteAddress": getattr(request, "remote", None),
            "body": body,
        }


    async def _handle_attachment_content_stream(self, request: web.Request) -> web.Response:
        """Verify the signed body digest first, then spool AEAD chunks as they arrive."""
        target = request.path_qs
        match = re.fullmatch(
            r"/open-android-intelligence/v2/attachments/([^/]+)/content", request.path,
        )
        route = next(
            (item for item in self.services.exposure.routes
             if item.match == "prefix" and request.path.startswith(item.path)),
            None,
        )
        if match is None or route is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)
        verifier = getattr(route._services, "verify_request", None)
        verify_stream = getattr(verifier, "verify_streaming_headers", None)
        if not callable(verify_stream):
            logger.error("[open_android] Streaming attachment verifier is unavailable")
            failed = route.failure_response(None, None, "ATTACHMENT_STORAGE_UNAVAILABLE")
            return web.json_response(failed["body"], status=failed["statusCode"])

        raw_request = self._raw_request(request, b"")
        raw_request["method"] = "PUT"
        raw_request["target"] = target
        ticket = verify_stream(raw_request)
        if ticket is None:
            failed = route.failure_response(None, None, "AUTHENTICATION_REQUIRED")
            return web.json_response(failed["body"], status=failed["statusCode"])

        core = getattr(self.services, "core", None)
        if core is None:
            failed = route.failure_response(
                ticket.context.request_id, ticket.context.correlation_id,
                "ATTACHMENT_STORAGE_UNAVAILABLE",
            )
            return web.json_response(failed["body"], status=failed["statusCode"])

        account = None
        upload = None
        staged = None
        try:
            account = core.open_gateway_account(ticket.context.account_id)
            upload = account.attachments.begin_content_upload_stream(
                match.group(1), ticket.content_length, ticket.body_sha256,
                now=ticket.now, content_type=ticket.content_type,
            )
            async for chunk in request.content.iter_chunked(account.attachments.stream_chunk_bytes):
                upload.write(chunk)
            staged = upload.finish()
            verified_request = ticket.complete(staged)
            result = route.handle_verified_request(verified_request)
            headers = {k: v for k, v in result["headers"].items() if k.lower() != "content-type"}
            return web.json_response(
                result["body"], status=result["statusCode"], headers=headers,
            )
        except GatewayError as exc:
            failed = route.failure_response(
                ticket.context.request_id, ticket.context.correlation_id, exc.code,
            )
            return web.json_response(
                failed["body"], status=failed["statusCode"],
                headers={k: v for k, v in failed["headers"].items() if k.lower() != "content-type"},
            )
        except Exception as exc:
            logger.warning("[open_android] Attachment stream failed: %s", exc, exc_info=True)
            failed = route.failure_response(
                ticket.context.request_id, ticket.context.correlation_id,
                "ATTACHMENT_STORAGE_UNAVAILABLE" if isinstance(exc, OSError) else "INTERNAL_ERROR",
            )
            return web.json_response(
                failed["body"], status=failed["statusCode"],
                headers={k: v for k, v in failed["headers"].items() if k.lower() != "content-type"},
            )
        finally:
            if upload is not None:
                upload.abort()
            if staged is not None:
                staged.discard()
            if account is not None:
                account.close()


    async def _handle_sse_stream(
        self, request: web.Request, raw_req: Mapping[str, Any],
    ) -> web.StreamResponse:
        """Authenticated SSE stream with durable, cursor-authoritative recovery.

        The route performs the handshake (signature, cursor, expiry) and returns
        the events to replay; this method only writes them, so there is exactly
        one place that decides who may read an account's stream.
        """
        route = next(
            (item for item in self.services.exposure.routes if item.path == EVENT_STREAM_PATH),
            None,
        )
        if route is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
        registered_account_id: Optional[str] = None

        try:
            handshake = route.event_backlog(raw_req)
            status = int(handshake.get("statusCode", 200))
            if status != 200:
                return web.json_response(handshake.get("body", {}), status=status)
            account_id = str(handshake["accountId"])
            authorized = lambda: self.services.core.is_event_session_active(handshake["verifiedContext"])
            if not authorized():
                failure = route.failure_response(None, None, "SESSION_REVOKED")
                return web.json_response(failure["body"], status=failure["statusCode"])
            registered_account_id = account_id
            self.services.core.set_device_online(handshake["verifiedContext"],True)
            self._event_subscribers.setdefault(account_id, set()).add(queue)

            response = web.StreamResponse(
                status=200,
                reason="OK",
                headers={
                    "Content-Type": "text/event-stream",
                    "Cache-Control": "no-store",
                    "Connection": "keep-alive",
                },
            )
            await response.prepare(request)
            if not authorized():
                return response
            await response.write(SSE_HEARTBEAT)

            seen_event_ids: dict[str, None] = {}
            headers = raw_req.get("headers") or {}
            last_event_id = headers.get("last-event-id") or headers.get("Last-Event-Id")
            if last_event_id:
                seen_event_ids[str(last_event_id)] = None
            query = parse_qs(urlsplit(raw_req.get("url") or raw_req.get("target") or "").query)
            for c in query.get("cursor", ()):
                seen_event_ids[unquote(c)] = None

            for event in handshake.get("events", []):
                if not authorized():
                    return response
                eid = event.get("eventId") or event.get("id")
                if eid:
                    seen_event_ids[str(eid)] = None
                await response.write(_sse_frame(event))

            while self._running:
                if not authorized():
                    break
                try:
                    frame = await asyncio.wait_for(queue.get(), timeout=min(1.0, SSE_HEARTBEAT_SECONDS))
                except asyncio.TimeoutError:
                    if not self._running or not authorized():
                        break
                    await response.write(SSE_HEARTBEAT)
                    continue
                if not self._running or not authorized():
                    break
                eid = _extract_event_id_from_item(frame)
                if eid is not None and eid in seen_event_ids:
                    continue
                if eid is not None:
                    seen_event_ids[eid] = None
                    if len(seen_event_ids) > 5000:
                        for k in list(seen_event_ids.keys())[:2500]:
                            seen_event_ids.pop(k, None)
                if isinstance(frame, Mapping):
                    frame = _sse_frame(frame)
                elif isinstance(frame, str):
                    frame = frame.encode("utf-8")
                await response.write(frame)
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            if registered_account_id:
                self.services.core.set_device_online(handshake["verifiedContext"],False)
                subscribers = self._event_subscribers.get(registered_account_id)
                if subscribers:
                    subscribers.discard(queue)
                    if not subscribers:
                        self._event_subscribers.pop(registered_account_id, None)

        return response


    async def _handle_ws_stream(
        self, request: web.Request, raw_req: Mapping[str, Any],
    ) -> web.StreamResponse:
        """Authenticated WebSocket event stream with durable, cursor-authoritative recovery.

        The route performs the handshake (signature, cursor, expiry) and returns
        the events to replay; this method sets up the WebSocket connection, replays
        the backlog, and subscribes to real-time events while handling incoming
        pings and connection lifecycle events.
        """
        route = next(
            (item for item in self.services.exposure.routes if item.path == EVENT_STREAM_PATH),
            None,
        )
        if route is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
        registered_account_id: Optional[str] = None

        try:
            handshake = route.event_backlog(raw_req)
            status = int(handshake.get("statusCode", 200))
            if status != 200:
                return web.json_response(handshake.get("body", {}), status=status)
            account_id = str(handshake["accountId"])
            authorized = lambda: self.services.core.is_event_session_active(handshake["verifiedContext"])
            if not authorized():
                failure = route.failure_response(None, None, "SESSION_REVOKED")
                return web.json_response(failure["body"], status=failure["statusCode"])
            registered_account_id = account_id
            self.services.core.set_device_online(handshake["verifiedContext"],True)
            self._event_subscribers.setdefault(account_id, set()).add(queue)

            ws = web.WebSocketResponse(heartbeat=15.0)
            await ws.prepare(request)

            seen_event_ids: dict[str, None] = {}
            headers = raw_req.get("headers") or {}
            last_event_id = headers.get("last-event-id") or headers.get("Last-Event-Id")
            if last_event_id:
                seen_event_ids[str(last_event_id)] = None
            query = parse_qs(urlsplit(raw_req.get("url") or raw_req.get("target") or "").query)
            for c in query.get("cursor", ()):
                seen_event_ids[unquote(c)] = None

            for event in handshake.get("events", []):
                if not authorized():
                    await ws.close(code=1008)
                    return ws
                eid = event.get("eventId") or event.get("id")
                if eid:
                    seen_event_ids[str(eid)] = None
                await ws.send_str(_ws_event_frame(event))

            async def send_loop() -> None:
                while self._running and not ws.closed:
                    if not authorized():
                        await ws.close(code=1008)
                        break
                    try:
                        item = await asyncio.wait_for(queue.get(), timeout=1.0)
                    except asyncio.TimeoutError:
                        if not self._running:
                            if not ws.closed:
                                try:
                                    await ws.close()
                                except Exception:
                                    pass
                            break
                        continue
                    except asyncio.CancelledError:
                        break
                    except Exception:
                        break

                    if not self._running or not authorized():
                        if not ws.closed:
                            try:
                                await ws.close()
                            except Exception:
                                pass
                        break

                    eid = _extract_event_id_from_item(item)
                    if eid is not None and eid in seen_event_ids:
                        continue
                    if eid is not None:
                        seen_event_ids[eid] = None
                        if len(seen_event_ids) > 5000:
                            for k in list(seen_event_ids.keys())[:2500]:
                                seen_event_ids.pop(k, None)

                    msg = _ws_message_from_queue_item(item)
                    if msg is not None and not ws.closed:
                        try:
                            await ws.send_str(msg)
                        except (asyncio.CancelledError, ConnectionResetError):
                            break
                        except Exception as send_err:
                            logger.debug("[open_android] WebSocket send_str error: %s", send_err)
                            break

            async def receive_loop() -> None:
                while self._running and not ws.closed:
                    try:
                        msg = await ws.receive()
                    except (asyncio.CancelledError, ConnectionResetError):
                        break
                    if msg.type == WSMsgType.TEXT:
                        text_stripped = msg.data.strip().lower()
                        if text_stripped == "ping":
                            if not ws.closed:
                                try:
                                    await ws.send_str("pong")
                                except (asyncio.CancelledError, ConnectionResetError):
                                    break
                        elif text_stripped == "close":
                            if not ws.closed:
                                await ws.close()
                            break
                    elif msg.type == WSMsgType.PING:
                        if not ws.closed:
                            try:
                                await ws.pong(msg.data)
                            except (asyncio.CancelledError, ConnectionResetError):
                                break
                    elif msg.type == WSMsgType.PONG:
                        pass
                    elif msg.type in (
                        WSMsgType.CLOSE,
                        WSMsgType.CLOSING,
                        WSMsgType.CLOSED,
                        WSMsgType.ERROR,
                    ):
                        break

            send_task = asyncio.create_task(send_loop())
            receive_task = asyncio.create_task(receive_loop())
            done, pending = await asyncio.wait(
                [send_task, receive_task],
                return_when=asyncio.FIRST_COMPLETED,
            )
            for t in pending:
                t.cancel()
                try:
                    await t
                except asyncio.CancelledError:
                    pass
            for t in done:
                exc = t.exception()
                if exc and not isinstance(exc, (asyncio.CancelledError, ConnectionResetError)):
                    logger.debug("[open_android] WebSocket task completed with error: %s", exc)
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            if registered_account_id:
                self.services.core.set_device_online(handshake["verifiedContext"],False)
                subscribers = self._event_subscribers.get(registered_account_id)
                if subscribers:
                    subscribers.discard(queue)
                    if not subscribers:
                        self._event_subscribers.pop(registered_account_id, None)

        return ws


    def _enqueue_frame(self, account_id: str, frame: bytes) -> None:
        """Hand one persisted frame to the subscribers of that account.

        A subscriber whose queue is full is skipped rather than blocking the
        agent turn: every frame carries a durable event id, so the phone resumes
        from its last complete frame and replays the gap instead of losing it.
        """
        for queue in list(self._event_subscribers.get(account_id, ())):
            try:
                queue.put_nowait(frame)
            except asyncio.QueueFull:
                logger.warning(
                    "[open_android] Event subscriber queue is full; client will resume from cursor"
                )
