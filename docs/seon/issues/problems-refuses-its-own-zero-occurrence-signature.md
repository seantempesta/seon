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

## Cause found (2026-09-16 02:10Z, read-only probe on default)

Error entity 43542 (signature `9aee2b65…`, kind `:seon.turn/refused`)
received its whole fact — `/at`, `/message`, `/data-edn`, `/agent`,
`/capped?`, `/data-size`, `/basis-t` — in one transaction (t 536871134)
with NO `:seon.error/occurrences` child. That attribute set matches neither
`seon.error/recording`'s `identity-row` nor `commit-call`'s `error-row`: a
THIRD writer records refused-transition errors as a flat fact and never
mints an occurrence, so the membership query (`[?error
:seon.error/signature]`) and the count (sum of
`:seon.error.occurrence/count`) disagree. The class fix is one error
writer (AGENTS.md §2.5: one fact family, one owner), not a count
adjustment. Fix deferred: src/seon/problems.clj and src/seon/error.clj are
held by another session's lanes.
