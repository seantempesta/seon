"""Summarize canonical test-fast logs without copying large rendered values.

Usage: python3 this_file.py COMMIT=LOG_PATH [COMMIT=LOG_PATH ...]
The landing note supplies the unchanged canonical command used at each commit.
"""

import collections
import hashlib
import json
import pathlib
import sys


def summarize(commit, path):
    raw = pathlib.Path(path).read_bytes()
    counts = {}
    failures = collections.Counter()
    namespace = None
    summary = []
    for line in raw.decode().splitlines():
        if line.startswith("Testing "):
            namespace = line.removeprefix("Testing ")
            counts[namespace] = {"failures": 0, "errors": 0}
        elif line.startswith(("FAIL in (", "ERROR in (")):
            category = "failures" if line.startswith("FAIL") else "errors"
            counts[namespace][category] += 1
            # Ignore source line shifts while preserving test name and outcome.
            test_name = line.split("(", 1)[1].split(")", 1)[0]
            failures[f"{namespace}/{test_name}:{category}"] += 1
        elif line.startswith("Ran ") or " failures, " in line:
            summary.append(line)
    if len(summary) != 2:
        raise ValueError(f"{commit}: no complete canonical summary in {path}")
    return {
        "commit": commit,
        "log_sha256": hashlib.sha256(raw).hexdigest(),
        "summary": summary,
        "namespaces": counts,
        "failure_occurrences": dict(sorted(failures.items())),
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Expected at least one COMMIT=LOG_PATH argument")
    print(json.dumps([summarize(*arg.split("=", 1)) for arg in sys.argv[1:]],
                     indent=2))
