---
type: issue
status: resolved
severity: friction
tags: [issue, agent, test, tooling]
---

# Parser corpus Babashka gate misses its Malli dependency

## Resolution

The parser corpus had one transport-specific assertion using
`seon.db.protocol/ordinary-wire-value?`. Its unconditional namespace require
pulled `seon.schema` and Malli into the otherwise pure Babashka parser gate.

The protocol require and that single assertion are now CLJS-only reader
conditionals. Babashka still runs the same maintained parser corpus; the
authoritative CLJS selector retains the transport-boundary assertion. No
protocol schemas, parser tests, or parser implementations were duplicated.

Proof on 2026-07-20:

- `bin/test-parser`: 46 tests, 368 assertions, zero failures or errors.
- `bin/test-cljs --test=seon.repl.internal-test`: 46 tests, 369 assertions,
  zero failures or errors and zero compiler warnings.

## Original problem

`bin/test-parser` failed before running the parser corpus because
`test/seon/repl/internal_test.cljc` unconditionally required
`seon.db.protocol`. That namespace reached `seon.schema`, whose `malli.core`
dependency was unavailable on the script's Babashka classpath.
