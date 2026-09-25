#!/usr/bin/env python3
"""Validate completed harness evidence and print grouped, duration-weighted summaries."""
import argparse
import datetime
import json
from collections import defaultdict
from pathlib import Path


def summarize(path):
    report = json.loads(path.read_text())
    if not report.get("complete") or report["environment"].get("failure"):
        raise ValueError("run is incomplete or failed")
    if report.get("maxClockDivergenceMillis", float("inf")) > 2000:
        raise ValueError("measurement clocks are missing or invalid")
    if abs(report["wallElapsedSeconds"] - report["elapsedSeconds"]) > 2:
        raise ValueError("run elapsed clocks disagree")
    env = report["environment"]
    wall_duration = (datetime.datetime.fromisoformat(env["finishedAt"].replace("Z", "+00:00"))
                     - datetime.datetime.fromisoformat(env["startedAt"].replace("Z", "+00:00"))).total_seconds()
    if abs(wall_duration - report["elapsedSeconds"]) > 2:
        raise ValueError("run timestamps disagree with monotonic duration")
    rows = report["measurements"]
    if not rows:
        raise ValueError("no measurement rows")
    groups = defaultdict(list)
    for row in rows + ([env["warmup"]] if "warmup" in env else []):
        if abs(row["wallMeasuredSeconds"] - row["measuredSeconds"]) > 2:
            raise ValueError("phase clocks disagree")
        if not row["requestedSeconds"] <= row["measuredSeconds"] <= row["requestedSeconds"] + 5:
            raise ValueError("phase duration outside requested interval")
        if row["retainedObservationSamples"] != 0 or row["batchWorkersAfterIdle"] != 2:
            raise ValueError("observation retention or SDK worker accumulation")
        if row["loadWorkersAfterIdle"] != env["prestartedWorkerThreads"]:
            raise ValueError("worker pool is not stable")
        if row["recording"] != "none":
            accounted = sum(row[k] for k in ("acceptedIncludingDrain", "failedSpansIncludingDrain", "queueDropsIncludingDrain"))
            if accounted != row["operations"]:
                raise ValueError("export accounting does not match generated observations")
            if row["receiver"] == "NORMAL" and (row["failedSpansIncludingDrain"] or row["queueDropsIncludingDrain"]):
                raise ValueError("normal receiver lost spans")
        if row in rows:
            groups[(row["recording"], row["receiver"], row["concurrency"])].append(row)
    summary = []
    for (recording, receiver, concurrency), samples in groups.items():
        ops = sum(r["operations"] for r in samples)
        seconds = sum(r["measuredSeconds"] for r in samples)
        summary.append({
            "recording": recording, "receiver": receiver, "concurrency": concurrency,
            "samples": len(samples), "operations": ops, "measuredSeconds": seconds,
            "opsPerSecond": ops / seconds,
            "p99MicrosRange": [min(r["p99Nanos"] for r in samples) / 1000, max(r["p99Nanos"] for r in samples) / 1000],
            "producerBytesPerOp": (sum(r["producerAllocatedBytesPerOp"] * r["operations"] for r in samples) / ops
                                  if all(r["producerAllocatedBytesPerOp"] is not None for r in samples) else None),
            "postGcHeapBytesRange": [min(r["postGcLiveHeapBytes"] for r in samples), max(r["postGcLiveHeapBytes"] for r in samples)],
            "threadCountRange": [min(r["threadsAfterIdle"] for r in samples), max(r["threadsAfterIdle"] for r in samples)],
            "queueDrops": sum(r["queueDropsIncludingDrain"] for r in samples),
            "failedExportSpans": sum(r["failedSpansIncludingDrain"] for r in samples),
        })
    requested = sum(r["requestedSeconds"] for r in rows)
    warmup = env.get("warmup", {}).get("requestedSeconds", 0)
    return {"file": str(path), "clockChecksPassed": True, "groups": summary,
            "measurementSecondsRequested": requested, "warmupSecondsRequested": warmup,
            "twoHourProfileCompleted": env["profile"] == "soak" and warmup >= 600 and requested >= 7200,
            "note": "p99 range is across windows, not a merged percentile. Allocation is producer-thread only."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.report), indent=2))
