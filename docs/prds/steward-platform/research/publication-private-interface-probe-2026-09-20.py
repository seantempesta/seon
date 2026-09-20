"""Measure a private declaration dependency through an existing Var reference.

Native clj-kondo dependency evidence only, not an armed publication proof.
The fixture is project-local and removed before exit; no JVM is launched.
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
with tempfile.TemporaryDirectory(prefix="publication-private-interface-", dir="tmp") as root:
    directory = Path(root)
    callee, caller = directory / "callee.clj", directory / "caller.clj"
    declaration = '(defn- %sf {:malli/schema [:=> [:cat :int] :int]} [x] x)\n'
    callee.write_text('(ns callee)\n' + declaration % '')
    caller.write_text('(ns caller (:require [callee]))\n'
                      '(defn g {:malli/schema [:=> [:cat] :int]} [] (#\'callee/f 1))\n')
    caller_digest = hashlib.sha256(caller.read_bytes()).hexdigest()
    before, before_ms = analyze([callee, caller])
    callee.write_text('(ns callee)\n' + declaration % '^{:deprecated "now"} ')
    changed, changed_ms = analyze([callee])
    complete, complete_ms = analyze([callee, caller])
    count = lambda report: sum(row['type'] == 'deprecated-var'
                               for row in report['findings'])
    uses = [row for row in before['analysis']['var-usages']
            if row.get('from') == 'caller' and row.get('to') == 'callee']
    evidence = {
        'caller_digest_unchanged': caller_digest == hashlib.sha256(caller.read_bytes()).hexdigest(),
        'namespace_form_unchanged': '(ns callee)',
        'callee_public_declarations_before_and_after': [],
        'private_reference': [{key: row[key] for key in
                               ('from', 'from-var', 'to', 'name', 'private')}
                              for row in uses],
        'before_deprecated_var_findings': count(before),
        'changed_only_deprecated_var_findings': count(changed),
        'complete_deprecated_var_findings': count(complete),
        'elapsed_ms': {'before': before_ms, 'changed_only': changed_ms, 'complete': complete_ms},
    }
    assert evidence['caller_digest_unchanged']
    assert uses and all(row.get('private') is True for row in uses)
    assert count(before) == count(changed) == 0 and count(complete) == 1
    assert all(row['level'] != 'error' for report in (before, changed, complete)
               for row in report['findings'])
    print(json.dumps(evidence, indent=2))
