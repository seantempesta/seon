---
type: issue
status: open
severity: friction
tags: [issue, schema, runtime]
---

# A predicate schema's contract violation says only "unknown error"

## 2026-09-21 bridge recurrence

The canonical armed bridge request `adf54170ac72` reports one failure in
`every-predicate-schema-declares-what-it-accepts`: the new
`:seon.effect/result-validator` declaration in
`resources/seon/schemas/seon.effect.edn:189` carries `:gen/schema` but no
`:error/message`. This is the same class and existing recurring check.
The effect owner holds that resource; bridge step 2 does not change it.
Evidence: `tmp/bridge-step2-retirement-construction.log:388`.

## Problem

`seon.instrument/violation` reports a contract violation through
`malli.error/humanize` (`src/seon/instrument.clj:161-163`). Malli has nothing
to say about a bare `[:fn pred]` schema, so it emits the literal string
`"unknown error"`. The violation message then names the function and the arm
correctly and describes the offending key as `unknown error` — which tells the
reader neither what was expected nor what was wrong.

Measured 2026-08-03 over `resources/seon/schemas/*.edn`: **36** registrations
are `[:fn …]` predicates and only **2** carry an `:error/message` property. The
other 34 therefore produce this reportless report whenever they fail.

## Evidence

Calling `seon.sci.kernel/invoke` with a database value that is itself a flat
error value produced, through MCP `eval_clj`:

```text
seon.sci.kernel/invoke violated its contract (invalid-input):
  [{:seon.db/db ["unknown error"]}]
```

`:seon.db/database-value` is `[:fn #:gen{…} seon.db/database-value?]`
(`resources/seon/schemas/seon.db.edn:5-8`). Nothing in the message, the
`:seon.instrument/schema` field, or the elided `:seon.instrument/args` says
that a database VALUE was expected where a connection or an error value was
supplied. Three probe round-trips were spent guessing.

This matters more than ordinary diagnostic polish because these envelopes are
what an AGENT reads when its own call is refused: an agent told "unknown
error" cannot repair its call, and the repair loop that ruling #52 builds on
depends on the error saying what to fix.

## Owner

`resources/seon/schemas/*.edn` for the declarations, and
`seon.instrument/violation` for the fallback.

## Acceptance

- Every `[:fn …]` registration carries an explicit `:error/message` Malli
  property saying what the predicate accepts. This is the ruling #47 shape —
  an explicit declared property, not a name-derived or hand-listed rule — and
  Malli reads arbitrary schema properties already
  (`reference-code/malli/src/malli/core.cljc:39`).
- One recurring proof QUERIES the registry for predicate registrations lacking
  `:error/message` and fails when the set is non-empty, so a new bare
  predicate cannot reintroduce the hole.
- `violation` never emits `"unknown error"`: if humanize yields it, the report
  falls back to the schema form, which at least names the predicate.

## Provenance

Found while merging the two guarded SCI entrances (2026-08-03), as consumer
friction rather than by audit.

## Resolution

Commit `fef44a5a8` gives the remaining two predicate declarations explicit
`:error/message` properties, adds the registry-wide query proof, and makes the
instrumentation humanizer omit Malli's invented `unknown error` string while
retaining the bounded schema form as diagnostic expectation.

## 2026-09-17 regression caught a new declaration

Reset G5 fast 10, after rebasing the adoption-progress work, found exactly one
new bare registered predicate: `:seon.source/progress!` from `be9c90e2f`.
The existing registry-wide regression refused `[:fn clojure.core/ifn?]`.
The reset slice adds "must be a callable publication progress reporter" to that
resource declaration; accepted values and callback behavior are unchanged.
The reset landing note records the correcting schema rerun. The class remains
covered by its existing recurring proof; no second predicate inventory is added.
