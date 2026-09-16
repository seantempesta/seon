#!/usr/bin/env python3
"""Falsify R6: a valid dependency cache is a hit while a peer holds the rebuild lock.

Holds `target/dev-dependency-cache.lock` with the same advisory fcntl lock
`java.nio.channels.FileChannel.lock` takes, then runs `ensure-cache` under a
bound. A hit that still blocks reports its own failure rather than timing out
silently. Read-only apart from the cache owner's ordinary writes.
"""

import fcntl
import subprocess
import sys
import time
from pathlib import Path

BOUND_SECONDS = 90

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
lock_path = root / "target" / "dev-dependency-cache.lock"
if not lock_path.is_file():
    raise SystemExit(f"No rebuild lock at {lock_path}; run a gate first.")

with open(lock_path, "r+b") as held:
    fcntl.lockf(held, fcntl.LOCK_EX)
    started = time.monotonic()
    try:
        completed = subprocess.run(
            ["clojure", "-T:dev-cache", "ensure-cache", ":test?", "true",
             ":pid", str(subprocess.os.getpid())],
            cwd=root, capture_output=True, text=True, timeout=BOUND_SECONDS)
    except subprocess.TimeoutExpired:
        raise SystemExit(
            f"BLOCKED: ensure-cache did not return within {BOUND_SECONDS}s "
            "while the rebuild lock was held.")
    elapsed_ms = round((time.monotonic() - started) * 1000)
    tail = [line for line in completed.stdout.splitlines() if line.strip()]
    print(f"rebuild-lock-held=yes exit={completed.returncode} elapsed-ms={elapsed_ms}")
    for line in tail[-3:]:
        print(line[:400])
    if completed.returncode != 0:
        print(completed.stderr[-2000:], file=sys.stderr)
        raise SystemExit("ensure-cache refused while the rebuild lock was held.")
    if ":status :current" not in completed.stdout:
        raise SystemExit("ensure-cache did not report a cache hit.")
