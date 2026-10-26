"""Context for one native conversation turn; never device authorization."""
from contextvars import ContextVar
trusted_turn = ContextVar("oai_hermes_turn", default=None)
