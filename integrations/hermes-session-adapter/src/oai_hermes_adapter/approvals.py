"""Hermes native approval runtime, outside the device Core."""
import logging
logger=logging.getLogger(__name__)

def resolve_host_approval(session_key: str, choice: str, request_id: str | None = None) -> int | None:
    """Unblock the Agent thread waiting on one approval.

    Returns the number of host waits this call released, or `None` when this
    process cannot reach the host approval runtime at all. `None` is a fact the
    caller must surface: it is never the same as "resolved", and a Gateway that
    cannot reach the runtime must not advertise approval cards.
    """
    try:
        from tools.approval import resolve_gateway_approval
    except Exception:
        return None
    try:
        return int(resolve_gateway_approval(session_key, choice, request_id=request_id))
    except Exception as exc:  # pragma: no cover - host runtime detail
        logger.warning("[open_android] Host approval resolution failed: %s", exc)
        return None
