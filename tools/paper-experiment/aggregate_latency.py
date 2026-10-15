#!/usr/bin/env python3
"""Join sender and Relayer JSONL records and generate latency CSV files."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

METRIC_PREFIX = "EXPERIMENT_METRIC "
EVENTS = {"SOURCE_ACCEPTED", "TARGET_SUBMITTING", "TARGET_CONFIRMED", "TARGET_FAILED"}
LATENCY_FIELDS = ("Ts", "To", "Tt", "Ttotal")


def read_jsonl(path: Path, relayer: bool = False) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        lines = handle.readlines()
        for line_number, raw in enumerate(lines, 1):
            text = raw.strip()
            if not text:
                continue
            if relayer:
                marker = text.find(METRIC_PREFIX)
                if marker < 0:
                    continue
                text = text[marker + len(METRIC_PREFIX):]
            try:
                record = json.loads(text)
            except json.JSONDecodeError as exc:
                if line_number == len(lines) and not raw.endswith("\n"):
                    # The Relayer may still be appending its last event while we poll.
                    continue
                raise ValueError(f"invalid JSON at {path}:{line_number}: {exc}") from exc
            if not relayer or record.get("event") in EVENTS:
                records.append(record)
    return records


def _source_key(record: dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(record.get("sourceProduct", "")),
        str(record.get("sourceBlockchainId", "")),
        str(record.get("sourceTxHashKey", "")).lower().removeprefix("0x"),
    )


def _earliest(records: Iterable[dict[str, Any]], event: str) -> dict[str, Any] | None:
    candidates = [item for item in records if item.get("event") == event]
    if not candidates:
        return None
    return min(candidates, key=lambda item: int(item.get("eventTimeEpochMs", 0)))


def aggregate(sender_records: list[dict[str, Any]], relayer_records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    accepted_by_source: dict[tuple[str, str, str], dict[str, Any]] = {}
    by_ucp: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in relayer_records:
        ucp_id = str(record.get("ucpId", ""))
        if ucp_id:
            by_ucp[ucp_id].append(record)
        if record.get("event") == "SOURCE_ACCEPTED":
            key = _source_key(record)
            previous = accepted_by_source.get(key)
            if previous is None or int(record["eventTimeEpochMs"]) < int(previous["eventTimeEpochMs"]):
                accepted_by_source[key] = record

    rows: list[dict[str, Any]] = []
    for sender in sender_records:
        row = dict(sender)
        row.update({"ucpId": "", "targetTxHash": "", "status": "", "reason": ""})
        for field in ("t1EpochMs", "t2EpochMs", "t3EpochMs", *LATENCY_FIELDS):
            row[field] = ""

        if sender.get("sendStatus") != "SENT":
            row["status"] = "SEND_FAILED"
            row["reason"] = sender.get("error", "")
            rows.append(row)
            continue

        accepted = accepted_by_source.get(_source_key(sender))
        if accepted is None:
            row["status"] = "MISSING_T1"
            rows.append(row)
            continue

        ucp_id = str(accepted.get("ucpId", ""))
        row["ucpId"] = ucp_id
        events = by_ucp.get(ucp_id, [])
        submitting = _earliest(events, "TARGET_SUBMITTING")
        confirmed = _earliest(events, "TARGET_CONFIRMED")
        failed = _earliest(events, "TARGET_FAILED")
        t0 = int(sender["t0EpochMs"])
        t1 = int(accepted["eventTimeEpochMs"])
        row["t1EpochMs"] = t1
        if submitting is None:
            row["status"] = "MISSING_T2"
            rows.append(row)
            continue
        t2 = int(submitting["eventTimeEpochMs"])
        row["t2EpochMs"] = t2
        if confirmed is None:
            row["status"] = "TARGET_FAILED" if failed is not None else "MISSING_T3"
            if failed is not None:
                row["reason"] = failed.get("reason", "")
                row["targetTxHash"] = failed.get("targetTxHash", "")
            rows.append(row)
            continue
        t3 = int(confirmed["eventTimeEpochMs"])
        row["t3EpochMs"] = t3
        row["targetTxHash"] = confirmed.get("targetTxHash", "")
        values = {"Ts": t1 - t0, "To": t2 - t1, "Tt": t3 - t2, "Ttotal": t3 - t0}
        row.update(values)
        if any(value < 0 for value in values.values()) or values["Ts"] + values["To"] + values["Tt"] != values["Ttotal"]:
            row["status"] = "NEGATIVE_OR_INCONSISTENT_LATENCY"
        else:
            row["status"] = "OK"
        rows.append(row)
    return rows


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summaries(rows: list[dict[str, Any]], expected_samples: int) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[(
            str(row.get("experimentId", "")),
            str(row.get("scheme", "")),
            str(row.get("route", "")),
            str(row.get("round", "")),
        )].append(row)

    output: list[dict[str, Any]] = []
    for key, group in sorted(groups.items()):
        successful = [row for row in group if row.get("status") == "OK"]
        summary: dict[str, Any] = {
            "experimentId": key[0],
            "scheme": key[1],
            "route": key[2],
            "round": key[3],
            "expectedSamples": expected_samples,
            "observedSamples": len(group),
            "successfulSamples": len(successful),
            "failedSamples": sum(1 for row in group if row.get("status") in {"SEND_FAILED", "TARGET_FAILED"}),
            "missingSamples": sum(1 for row in group if str(row.get("status", "")).startswith("MISSING_")),
            "invalidLatencySamples": sum(1 for row in group if row.get("status") == "NEGATIVE_OR_INCONSISTENT_LATENCY"),
            "lateSamples": sum(1 for row in group if int(row.get("scheduleLatenessMs", 0)) > 200),
        }
        summary["validRun"] = (
            len(group) == expected_samples
            and len(successful) == expected_samples
            and summary["lateSamples"] == 0
        )
        for field in LATENCY_FIELDS:
            values = [float(row[field]) for row in successful]
            summary[f"{field}MeanMs"] = statistics.mean(values) if values else ""
            summary[f"{field}StddevMs"] = statistics.stdev(values) if len(values) > 1 else (0.0 if values else "")
            summary[f"{field}P50Ms"] = percentile(values, 0.50) if values else ""
            summary[f"{field}P95Ms"] = percentile(values, 0.95) if values else ""
        output.append(summary)
    return output


def pooled_summaries(rows: list[dict[str, Any]], round_summaries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    summary_groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[(
            str(row.get("experimentId", "")),
            str(row.get("scheme", "")),
            str(row.get("route", "")),
        )].append(row)
    for summary in round_summaries:
        summary_groups[(
            str(summary.get("experimentId", "")),
            str(summary.get("scheme", "")),
            str(summary.get("route", "")),
        )].append(summary)

    output: list[dict[str, Any]] = []
    for key, group in sorted(groups.items()):
        successful = [row for row in group if row.get("status") == "OK"]
        per_round = summary_groups[key]
        summary: dict[str, Any] = {
            "experimentId": key[0],
            "scheme": key[1],
            "route": key[2],
            "rounds": len(per_round),
            "validRounds": sum(1 for item in per_round if item.get("validRun")),
            "observedSamples": len(group),
            "successfulSamples": len(successful),
        }
        for field in LATENCY_FIELDS:
            values = [float(row[field]) for row in successful]
            round_means = [
                float(item[f"{field}MeanMs"])
                for item in per_round
                if item.get("validRun") and item.get(f"{field}MeanMs") != ""
            ]
            summary[f"{field}MeanOfRoundMeansMs"] = statistics.mean(round_means) if round_means else ""
            summary[f"{field}PooledMeanMs"] = statistics.mean(values) if values else ""
            summary[f"{field}PooledStddevMs"] = statistics.stdev(values) if len(values) > 1 else (0.0 if values else "")
            summary[f"{field}PooledP50Ms"] = percentile(values, 0.50) if values else ""
            summary[f"{field}PooledP95Ms"] = percentile(values, 0.95) if values else ""
        output.append(summary)
    return output


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        raise ValueError(f"refusing to write empty CSV: {path}")
    fieldnames: list[str] = []
    for row in rows:
        for key in row:
            if key not in fieldnames:
                fieldnames.append(key)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def load_and_aggregate(
        sender_paths: list[Path],
        relayer_paths: list[Path],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    sender_records: list[dict[str, Any]] = []
    for path in sender_paths:
        sender_records.extend(read_jsonl(path))
    relayer_records: list[dict[str, Any]] = []
    for path in relayer_paths:
        relayer_records.extend(read_jsonl(path, relayer=True))
    return sender_records, relayer_records, aggregate(sender_records, relayer_records)


def wait_for_terminal_records(
        sender_paths: list[Path],
        relayer_paths: list[Path],
        wait_seconds: float,
        poll_seconds: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    if wait_seconds < 0 or poll_seconds <= 0:
        raise ValueError("wait-seconds must be non-negative and poll-seconds must be positive")
    deadline = time.monotonic() + wait_seconds
    while True:
        loaded = load_and_aggregate(sender_paths, relayer_paths)
        rows = loaded[2]
        terminal = all(
            row.get("status") not in {"MISSING_T1", "MISSING_T2", "MISSING_T3"}
            for row in rows
        )
        if terminal or time.monotonic() >= deadline:
            return loaded
        time.sleep(min(poll_seconds, max(0.0, deadline - time.monotonic())))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sender", type=Path, nargs="+", required=True)
    parser.add_argument("--relayer", type=Path, nargs="+", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-samples", type=int, default=150)
    parser.add_argument("--wait-seconds", type=float, default=0.0)
    parser.add_argument("--poll-seconds", type=float, default=5.0)
    args = parser.parse_args()

    sender_records, relayer_records, rows = wait_for_terminal_records(
        args.sender, args.relayer, args.wait_seconds, args.poll_seconds
    )
    summary_rows = summaries(rows, args.expected_samples)
    pooled_rows = pooled_summaries(rows, summary_rows)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(args.output_dir / "sender.jsonl", sender_records)
    write_jsonl(args.output_dir / "relayer.jsonl", relayer_records)
    write_csv(args.output_dir / "transactions.csv", rows)
    write_csv(args.output_dir / "summary.csv", summary_rows)
    write_csv(args.output_dir / "pooled-summary.csv", pooled_rows)


if __name__ == "__main__":
    main()
