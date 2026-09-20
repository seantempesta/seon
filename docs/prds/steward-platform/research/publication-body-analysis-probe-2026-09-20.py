"""Show caller findings changing after a body-only edit; no JVM or database.

Run from the checkout with Python 3 and native clj-kondo. All scratch files
are beneath tmp/ and removed on exit. This is dependency evidence, not an
armed Seon regression or a publication benchmark.
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
         '{:output {:format :json} :analysis {:var-definitions {:meta true}} '
         ':linters {:type-mismatch {:level :warning}}}',
         "--lint", *map(str, paths)],
        capture_output=True, text=True, timeout=30, check=False,
    )
    if result.returncode not in (0, 2, 3):
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout), (time.monotonic_ns() - started) // 1_000_000


def declaration(report):
    row = next(row for row in report["analysis"]["var-definitions"]
               if row["ns"] == "publication-body-probe.callee" and row["name"] == "f")
    return {key: row.get(key) for key in
            ("ns", "name", "fixed-arities", "varargs-min-arity", "meta", "private", "macro")}


Path("tmp").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="publication-body-analysis-", dir="tmp") as root:
    directory = Path(root) / "publication_body_probe"
    directory.mkdir()
    callee, caller = directory / "callee.clj", directory / "caller.clj"
    prefix = ('(ns publication-body-probe.callee)\n'
              '(defn f {:malli/schema [:=> [:cat] [:or :int :string]]} [] ')
    callee.write_text(prefix + '1)\n')
    caller.write_text('(ns publication-body-probe.caller '
                      '(:require [publication-body-probe.callee :as c]))\n'
                      '(defn g [] (inc (c/f)))\n')
    caller_digest = hashlib.sha256(caller.read_bytes()).hexdigest()
    before, before_ms = analyze([callee, caller])
    callee.write_text(prefix + '"text")\n')
    changed, changed_ms = analyze([callee])
    complete, complete_ms = analyze([callee, caller])
    findings = lambda report: [row for row in report["findings"]
                               if row["type"] == "type-mismatch"]
    evidence = {
        "caller_digest_unchanged": caller_digest == hashlib.sha256(caller.read_bytes()).hexdigest(),
        "declaration_unchanged": declaration(before) == declaration(complete),
        "declaration": declaration(complete),
        "before_type_findings": len(findings(before)),
        "changed_only_type_findings": len(findings(changed)),
        "complete_type_findings": len(findings(complete)),
        "complete_messages": [row["message"] for row in findings(complete)],
        "elapsed_ms": {"before": before_ms, "changed_only": changed_ms, "complete": complete_ms},
    }
    assert all(row["level"] != "error" for report in (before, changed, complete)
               for row in report["findings"]), (before["findings"], changed["findings"], complete["findings"])
    assert evidence["caller_digest_unchanged"] and evidence["declaration_unchanged"]
    assert evidence["before_type_findings"] == evidence["changed_only_type_findings"] == 0
    assert evidence["complete_type_findings"] == 1
    print(json.dumps(evidence, indent=2))
