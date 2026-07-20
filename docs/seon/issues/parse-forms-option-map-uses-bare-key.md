---
type: issue
status: open
severity: cleanup
tags: [issue, agent, schema]
---

# parse-forms option map uses a bare key

## Problem

`seon.repl.internal/parse-forms` accepts the public option map
`{:strip-fences? boolean}`. The bare `:strip-fences?` key conflicts with the
maintained rule that every Clojure map key is fully namespaced, even though the
function's emitted parse-entry maps are already corrected.

## Evidence

[[../../prds/source-cleanup/research/parse-forms-entry-boundary-2026-07-20]]
(`84b35090`) re-audited the current parser and callers. The function schema,
destructuring, diffusion retrieval/oracle callers, worker validator, and tests
all preserve the bare option key. This is independent of the historical
parse-entry issue, whose emitted entry keys and precise union are complete.

## Owner

`seon.repl.internal/parse-forms` owns the option contract. Migrate the producer,
function schema, every maintained caller, tests, and current documentation
atomically to `:seon.repl/strip-fences?`; do not add a compatibility key or a
second options normalizer.

## Acceptance

- The public option map uses only `:seon.repl/strip-fences?`.
- Every maintained caller and test migrates in the same commit.
- No legacy bare-key branch or compatibility adapter remains.
- `bin/test-parser` and the focused CLJS parser namespace pass unchanged in
  behavior, followed by the relevant full CLJS checkpoint.
