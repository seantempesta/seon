"""Probe whether an unchanged caller's analysis depends on a changed callee.

Run from the repository root with Python 3 and native clj-kondo on PATH.
Uses no JVM, writes only an owned temporary directory beneath tmp/, and
removes it on exit. This is dependency evidence, not a canonical Seon test.
"""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time


def analyze(paths):
    started = time.monotonic_ns()
    result = subprocess.run(
        ["clj-kondo", "--cache", "false", "--config",
         '{:output {:format :json} :analysis {:var-usages true}}',
         "--lint", *map(str, paths)],
        capture_output=True, text=True, timeout=30, check=False,
    )
    if result.returncode not in (0, 2, 3):
        raise RuntimeError(result.stderr)
    report = json.loads(result.stdout)
    return report, (time.monotonic_ns() - started) // 1_000_000


Path("tmp").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="publication-analysis-input-", dir="tmp") as root:
    directory = Path(root) / "publication_probe"
    directory.mkdir()
    callee = directory / "callee.clj"
    caller = directory / "caller.clj"
    callee.write_text("(ns publication-probe.callee)\n(defn f [x] x)\n")
    caller.write_text("(ns publication-probe.caller (:require [publication-probe.callee :as c]))\n(defn g [] (c/f 1))\n")
    caller_digest = hashlib.sha256(caller.read_bytes()).hexdigest()
    before, before_ms = analyze([callee, caller])
    callee.write_text("(ns publication-probe.callee)\n(defn f [x y] (+ x y))\n")
    changed_only, changed_ms = analyze([callee])
    complete, complete_ms = analyze([callee, caller])
    invalid = lambda report: [f for f in report["findings"] if f["type"] == "invalid-arity"]
    evidence = {
        "caller_digest_unchanged": caller_digest == hashlib.sha256(caller.read_bytes()).hexdigest(),
        "before_invalid_arities": len(invalid(before)),
        "changed_only_invalid_arities": len(invalid(changed_only)),
        "complete_invalid_arities": len(invalid(complete)),
        "complete_messages": [f["message"] for f in invalid(complete)],
        "elapsed_ms": {"before": before_ms, "changed_only": changed_ms, "complete": complete_ms},
    }
    assert evidence["caller_digest_unchanged"]
    assert evidence["before_invalid_arities"] == 0
    assert evidence["changed_only_invalid_arities"] == 0
    assert evidence["complete_invalid_arities"] == 1
    print(json.dumps(evidence, indent=2))
