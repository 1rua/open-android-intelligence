"""Hermes conformance runner.

Consumes the shared ``gateway-contract/vectors/*.json`` documents through the
Hermes Python Gateway Core and emits standard JSONL plus a manifest. The
OpenClaw side is a separate TypeScript process; the two runners share no
runtime binary.

The Gateway itself lives in its own repository (``1rua/hermes-gateway-plugin``)
and is consumed here as an installed package. This repository stays the
authoritative source of the contract, so the runner passes that directory in
explicitly instead of letting the plugin resolve its own fetched copy: the
vectors exercised here must be the ones this commit ships.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
CONTRACT_ROOT = TOOLS_DIR.parent
REPO_ROOT = CONTRACT_ROOT.parent

try:
    from open_android_intelligence_gateway.core import create_gateway_core
except ModuleNotFoundError as exc:  # fail closed with an actionable message
    raise SystemExit(
        "缺少 Hermes 网关插件包，无法运行一致性校验。\n"
        "请先安装：pip install open-android-intelligence-hermes-gateway\n"
        "（源码仓库：https://github.com/1rua/hermes-gateway-plugin）"
    ) from exc

HERMES_IMPLEMENTATION = "hermes-python"

CONFORMANCE_VECTOR_FILE_NAMES = (
    "request-signatures.json",
    "protocol-negotiation.json",
    "auth-sessions.json",
    "attachments.json",
    "sse-events.json",
    "device-requests.json",
)
CONFORMANCE_FIXTURE_FILE_NAMES = (
    "dispatched-schema-fixtures.json",
    "dispatched-schema-fixtures-1.0.0.schema.json",
)
MANIFEST_FORMAT_VERSION = "1.0.0"


def input_digests(contract_root: Path) -> dict[str, str]:
    import hashlib

    digests: dict[str, str] = {}
    vectors = contract_root / "vectors"
    for name in CONFORMANCE_VECTOR_FILE_NAMES + CONFORMANCE_FIXTURE_FILE_NAMES:
        digest = hashlib.sha256((vectors / name).read_bytes()).hexdigest()
        digests[f"vectors/{name}"] = f"sha256:{digest}"
    return digests


def artifact_directory() -> Path:
    configured = os.environ.get("OPEN_ANDROID_INTELLIGENCE_CONFORMANCE_DIR")
    if configured:
        return Path(configured).resolve()
    return CONTRACT_ROOT / ".artifacts" / "conformance"


def main() -> int:
    directory = artifact_directory()
    directory.mkdir(parents=True, exist_ok=True)

    core = create_gateway_core(contract_root=CONTRACT_ROOT)
    results = core.run_shared_vectors(CONTRACT_ROOT)

    records_path = directory / f"{HERMES_IMPLEMENTATION}.jsonl"
    manifest_path = directory / f"{HERMES_IMPLEMENTATION}.manifest.json"

    with records_path.open("w", encoding="utf-8") as handle:
        for result in results:
            handle.write(
                json.dumps(
                    {
                        "vectorId": result["vectorId"],
                        "operation": result["operation"],
                        "implementation": result["implementation"],
                        "status": result["status"],
                        "resultHash": result["resultHash"],
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            )

    import hashlib

    records_digest = hashlib.sha256(records_path.read_bytes()).hexdigest()
    manifest = {
        "formatVersion": MANIFEST_FORMAT_VERSION,
        "implementation": HERMES_IMPLEMENTATION,
        "caseCount": len(results),
        "vectorDigests": input_digests(CONTRACT_ROOT),
        "recordsDigest": f"sha256:{records_digest}",
    }
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    failed = [item for item in results if item["status"] != "pass"]
    sys.stdout.write(f"{HERMES_IMPLEMENTATION}: {len(results) - len(failed)}/{len(results)} pass\n")
    for result in results:
        sys.stdout.write(
            f"{result['status']}\t{result['vectorId']}\t{result['operation']}\t{result['resultHash']}\n"
        )
    if failed:
        sys.stderr.write(f"{HERMES_IMPLEMENTATION}: {len(failed)} vector case(s) failed\n")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
