---
type: issue
status: open
severity: friction
tags: [issue, runtime, schema, mcp]
created: 2026-09-16
---

# `problems` refuses its own zero-occurrence signature

## Problem

`mcp__seon__runtime_status` on `default` (reforked 01:36Z) fails with
`seon.problems/problems refused return value at
[:seon.problems/error-signatures 2 :seon.problems/occurrences]: expected an
integer, got an integer.` The third signature (kind `:seon.turn/refused`,
rule `agent-already-running`) carries `:seon.problems/occurrences 0` while
the declared schema is `[:int {:min 1}]`. Two defects:

1. The producer derives a signature row whose occurrence count is zero — a
   signature exists only because an error fact exists, so the count
   derivation disagrees with the membership query (`src/seon/problems.clj`
   `error-signatures`).
2. The refusal grammar dropped the constraint: "expected an integer, got an
   integer" names neither `{:min 1}` nor the value `0`. The humaniser must
   render the constraint and the offending value when the type matches.

## Wanted

One derivation: occurrences counted from the same rows that make the
signature, so zero cannot occur; the grammar renders `expected an integer of
at least 1, got 0`. Regressions on the canonical harness for both.
