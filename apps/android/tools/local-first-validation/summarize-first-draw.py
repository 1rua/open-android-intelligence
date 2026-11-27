#!/usr/bin/env python3
"""Summarize explicitly correlated Compose draw events from a saved logcat."""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import statistics


KINDS = ("memory", "local", "remote")
TYPES = {"timeline.opened", "timeline.local_read", "timeline.first_draw", "timeline.remote_synced"}


def summarize(lines, minimum=20, history_ids=None):
    groups = defaultdict(lambda: defaultdict(list))
    errors = Counter()
    for line in lines:
        try:
            record = json.loads(line)
        except (ValueError, TypeError):
            continue
        if not isinstance(record, dict) or record.get("type") not in TYPES:
            continue
        boot = record.get("bootId")
        conversation = record.get("conversationId")
        epoch = record.get("epoch")
        if not isinstance(boot, str) or not boot or not isinstance(conversation, str) or not conversation \
                or type(epoch) is not int or epoch <= 0:
            errors["missing_identity"] += 1
            continue
        groups[(boot, conversation, epoch)][record["type"]].append(record)

    values = {kind: [] for kind in KINDS}
    for records in groups.values():
        opened = records["timeline.opened"]
        draws = records["timeline.first_draw"]
        if not draws:
            errors["no_draw"] += 1
            continue
        if len(opened) != 1 or len(draws) != 1:
            errors["ambiguous_open_or_draw"] += 1
            continue
        opened, draw = opened[0], draws[0]
        source, elapsed = draw.get("source"), draw.get("elapsedNs")
        clicked, logged = opened.get("clickedAtNs"), draw.get("monotonicNs")
        if source not in KINDS or type(elapsed) is not int or elapsed < 0 \
                or type(clicked) is not int or type(logged) is not int \
                or logged < clicked or elapsed > logged - clicked \
                or logged - clicked - elapsed > 500_000_000:
            errors["invalid_timing_or_source"] += 1
            continue
        if source == "memory" and opened.get("source") != "memory":
            errors["memory_source_mismatch"] += 1
            continue
        if source != "memory" and opened.get("source") != "pending":
            errors["pending_source_mismatch"] += 1
            continue
        reads = records["timeline.local_read"]
        if source == "local" and (len(reads) != 1 or reads[0].get("found") is not True):
            errors["local_provenance_uncertain"] += 1
            continue
        if source == "remote" and (len(reads) != 1 or reads[0].get("found") is not False):
            errors["remote_provenance_uncertain"] += 1
            continue
        if source == "remote":
            synced = records["timeline.remote_synced"]
            if len(synced) != 1 or synced[0].get("success") is not True \
                    or type(synced[0].get("messageCount")) is not int or synced[0]["messageCount"] < 1 \
                    or history_ids is None or draw["conversationId"] not in history_ids:
                errors["remote_history_unverified"] += 1
                continue
        values[source].append(elapsed / 1_000_000)

    result = {}
    for kind, samples in values.items():
        ordered = sorted(samples)
        result[kind] = {
            "count": len(samples),
            "samplesMs": samples,
            "sufficient": len(samples) >= minimum,
            "medianMs": statistics.median(ordered) if ordered else None,
            "p95Ms": ordered[math.ceil(0.95 * len(ordered)) - 1] if ordered else None,
            "lowSampleWarning": len(samples) < minimum,
        }
    return {"minimumPerSource": minimum, "method": "Compose draw completion, nearest-rank P95",
            "sources": result, "rejected": dict(errors)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path, help="Saved logcat -v raw output")
    parser.add_argument("--history-journal", type=Path, help="Synthetic Adapter history-read journal")
    parser.add_argument("--minimum", type=int, default=20)
    args = parser.parse_args()
    if args.minimum < 1:
        parser.error("minimum must be positive")
    history_ids = None
    if args.history_journal:
        with args.history_journal.open(encoding="utf-8") as history:
            history_ids = {row.get("conversationId") for line in history
                           if (row := json.loads(line)).get("event") == "history.read" and row.get("count", 0) > 0}
    with args.log.open(encoding="utf-8") as stream:
        print(json.dumps(summarize(stream, args.minimum, history_ids), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
