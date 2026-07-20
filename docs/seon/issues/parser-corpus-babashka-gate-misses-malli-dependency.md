---
type: issue
status: open
severity: friction
tags: [issue, agent, test, tooling]
---

# Parser corpus Babashka gate misses its Malli dependency

## Problem

`bin/test-parser` fails before running the parser corpus. The maintained
`test/seon/repl/internal_test.cljc` requires `seon.db.protocol`; that namespace
reaches `seon.schema`, whose `malli.core` dependency is unavailable on the
script's Babashka classpath.

Observed on 2026-07-20:

```text
Could not locate malli/core.bb, malli/core.clj or malli/core.cljc on classpath.

```

This dependency existed before the precise parser-schema unit. The isolated
ClojureScript selector remains healthy: 46 tests and 369 assertions pass.

## Acceptance criteria

- `bin/test-parser` loads the maintained CLJC corpus and runs it to completion.
- The repair preserves `seon.repl.internal` as a bare-Babashka-loadable pure
  parser namespace.
- The gate does not gain a second parser corpus or duplicate protocol schema
  definitions merely to avoid its declared test dependency.

## Owner

`bin/test-parser` and the CLJC parser-corpus dependency seam in
`test/seon/repl/internal_test.cljc`.
