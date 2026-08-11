---
type: issue
status: resolved
severity: friction
tags: [issue, render, agent, live-drive]
---

# Keep nested render selection explicit or schema-declared

## Problem

Top-level render selection admits contract fit, while a value inside another
value reaches only an explicit producer or a producer declared by a registered
schema. That difference needed an owner ruling before the nested path could be
described or repaired.

The concrete red regression was
`seon.render-simplification-test/nested-values-render-their-declared-faces`:
a native Datahike transaction report nested inside a result fell through to
the generic printed-value floor even though `:seon.db/transaction-report`
declared both render producers.

## Resolution

The owner ruled NESTED-1 on 2026-08-11 in
[`repl-transcript-context-prd-2026-08-10.md`](../../../prds/sci-execution-runtime/plan/repl-transcript-context-prd-2026-08-10.md):
nested selection stays explicit-or-declared-schema. Contract fit remains a
top-level mechanism, and the proposed acquired-candidates mechanism is
dropped.

Commit `2a19869c7` repairs the existing declared-schema path. The matcher was
already reached at nested nodes; the declaration still described the removed
bounded transaction projection after commit `fb78d3027` unified
`seon.db/transact!` on Datahike's native report. The declaration and its two
render producers now consume that surviving report shape. A nested ambiguity
also renders the existing loud, deterministically sorted refusal instead of
silently returning the generic node.

## Evidence

- `seon.render-simplification-test/nested-values-render-their-declared-faces`
  covers a schema-declared transaction face at depth in AI and HTML and an
  ambiguous nested match in both targets.
- Focused gate: 10 tests, 126 assertions, zero failures and errors.
- The required changed-files gate completed its platform tier and main bulk
  pass but was blocked by the separately recorded test-runner cleanup failure.
