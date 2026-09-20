"""Verify a namespace dependency absent from function call/reference edges.

Native clj-kondo only; temporary files under tmp/ are removed on exit.
This measures dependency semantics, not an armed publication regression.
"""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time


def analyze(paths):
    config = ('{:output {:format :json} :analysis {:var-usages true} :linters {'
              ':type-mismatch {:level :off} :redundant-str-call {:level :off} '
              ':is-message-not-string {:level :off} '
              ':redundant-primitive-coercion {:level :off} :equals-float {:level :off} '
              ':not-empty? {:level :off} :constant-condition {:level :off}}}')
    started = time.monotonic_ns()
    result = subprocess.run(
        ["clj-kondo", "--cache", "false", "--config", config,
         "--lint", *map(str, paths)],
        capture_output=True, text=True, timeout=30, check=False,
    )
    if result.returncode not in (0, 2, 3):
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout), (time.monotonic_ns() - started) // 1_000_000


Path("tmp").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="publication-namespace-analysis-", dir="tmp") as root:
    directory = Path(root)
    callee, caller = directory / "callee.clj", directory / "caller.clj"
    function_source = '(defn f [] 1)\n'
    callee.write_text('(ns callee)\n' + function_source)
    caller.write_text('(ns caller (:require [callee :as c]))\n(defn g [] 1)\n')
    caller_digest = hashlib.sha256(caller.read_bytes()).hexdigest()
    before, before_ms = analyze([callee, caller])
    callee.write_text('(ns ^{:deprecated "now"} callee)\n' + function_source)
    changed, changed_ms = analyze([callee])
    complete, complete_ms = analyze([callee, caller])
    count = lambda report: sum(row['type'] == 'deprecated-namespace'
                               for row in report['findings'])
    uses = [row for row in before['analysis']['var-usages']
            if row.get('from') == 'caller' and row.get('to') == 'callee']
    evidence = {
        'caller_digest_unchanged': caller_digest == hashlib.sha256(caller.read_bytes()).hexdigest(),
        'function_source_unchanged': callee.read_text().endswith(function_source),
        'published_function_usages_to_callee': uses,
        'before_deprecated_namespace_findings': count(before),
        'changed_only_deprecated_namespace_findings': count(changed),
        'complete_deprecated_namespace_findings': count(complete),
        'elapsed_ms': {'before': before_ms, 'changed_only': changed_ms, 'complete': complete_ms},
    }
    assert evidence['caller_digest_unchanged'] and evidence['function_source_unchanged']
    assert not uses
    assert count(before) == count(changed) == 0 and count(complete) == 1
    assert all(row['level'] != 'error' for report in (before, changed, complete)
               for row in report['findings'])
    print(json.dumps(evidence, indent=2))
