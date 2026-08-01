#!/usr/bin/env python3
import csv
import pathlib
import re
import statistics
import sys


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    position = fraction * (len(ordered) - 1)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def field(line, name):
    match = re.search(
        rf"(?::jvm-tuning/|:){re.escape(name)} ([^,}}]+)", line)
    return match.group(1).strip() if match else None


def summarize(case_dir):
    stdout_path = case_dir / "stdout.edn"
    gc_path = case_dir / "gc.log"
    rss_path = case_dir / "rss.csv"
    lines = stdout_path.read_text(errors="replace").splitlines()
    phases = [line for line in lines
              if ":jvm-tuning/phase" in line
              or "#:jvm-tuning{:phase" in line]
    workload_lines = [line for line in phases if field(line, "workload-ms")]
    workload_ms = (float(field(workload_lines[-1], "workload-ms"))
                   if workload_lines else None)
    final_line = phases[-1] if phases else ""
    heap_committed = field(final_line, "heap-committed-bytes")
    heap_used = field(final_line, "heap-used-bytes")

    pauses = []
    uncommit_lines = 0
    if gc_path.exists():
        for line in gc_path.read_text(errors="replace").splitlines():
            match = re.search(r"\] GC\(\d+\).* Pause .*? ([0-9.]+)ms$", line)
            if match:
                pauses.append(float(match.group(1)))
            if re.search(r"uncommit", line, re.IGNORECASE):
                uncommit_lines += 1

    rss_values = []
    if rss_path.exists():
        with rss_path.open(newline="") as stream:
            rss_values = [int(row["rss_kib"])
                          for row in csv.DictReader(stream)]

    return {
        "case": case_dir.name,
        "workload_ms": workload_ms,
        "pause_count": len(pauses),
        "pause_p50_ms": percentile(pauses, 0.50),
        "pause_p95_ms": percentile(pauses, 0.95),
        "pause_p99_ms": percentile(pauses, 0.99),
        "pause_max_ms": max(pauses) if pauses else None,
        "rss_peak_mib": max(rss_values) / 1024.0 if rss_values else None,
        "rss_final_mib": rss_values[-1] / 1024.0 if rss_values else None,
        "heap_used_final_mib": int(heap_used) / 1048576.0 if heap_used else None,
        "heap_committed_final_mib": int(heap_committed) / 1048576.0
        if heap_committed else None,
        "uncommit_log_lines": uncommit_lines,
    }


result_root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1
                           else "tmp/jvm-tuning/results")
rows = [summarize(path) for path in sorted(result_root.iterdir())
        if path.is_dir() and (path / "complete").exists()
        and (path / "stdout.edn").exists()]
writer = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]) if rows else [])
if rows:
    writer.writeheader()
    writer.writerows(rows)
