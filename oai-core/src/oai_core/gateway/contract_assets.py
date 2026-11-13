"""Resolve shipped Gateway contracts locally; service startup never downloads code."""
from __future__ import annotations

import os
from pathlib import Path
from typing import NamedTuple

CONTRACT_DIRECTORY_NAME = "gateway-contract"
CONTRACT_ROOT_ENV = "OPEN_ANDROID_GATEWAY_CONTRACT_ROOT"
DEFAULT_PROBES = (
    "core-dispatched-schemas.json",
    "schemas/envelope.schema.json",
    "vectors/vector-set-1.0.0.schema.json",
)


class ContractResolution(NamedTuple):
    root: Path | None
    pinned_ref: str | None
    source: str
    reason: str | None


def resolve_contract_root(contract_root: str | Path | None = None) -> ContractResolution:
    """Prefer an explicit override, then wheel assets or the editable source tree.

    An invalid explicit path is an error. Falling back would hide a deployment
    mistake and negotiate against a different Schema hash than the operator set.
    """
    configured = contract_root if contract_root is not None else os.environ.get(CONTRACT_ROOT_ENV)
    if configured:
        given = Path(configured).expanduser().resolve()
        candidates = (given, given / CONTRACT_DIRECTORY_NAME)
        source = "explicit"
    else:
        package = Path(__file__).resolve().parent
        # This fixed relative layout supports an editable development install.
        # Published wheels and sdists embed the exact same source assets.
        candidates = (package / "contracts", package.parents[3] / CONTRACT_DIRECTORY_NAME)
        source = "bundled"
    for candidate in candidates:
        if all((candidate / probe).is_file() for probe in DEFAULT_PROBES):
            return ContractResolution(candidate, None, source, None)
    return ContractResolution(None, None, "unavailable", "gateway-contract assets unavailable")
