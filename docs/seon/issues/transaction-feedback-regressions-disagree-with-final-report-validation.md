---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, database, schema, test]
---

# Transaction feedback regressions disagree with final-report validation

The isolated message-wake review at base `8dff32220` runs the canonical
armed harness and observes twelve failures in three unchanged tests:

- `bad-value-type`: five assertions expect a supplied projection to refuse
  `:my.plan.item/title "short"`; the transaction commits instead.
- `raw-write-maps-select-only-their-asserted-required-identity`: six assertions
  fail. The reverse-ref fixture creates `:seon.turn/id "reverse-turn"` without
  its required `:seon.turn/agent`, so the final report refuses it; later reads
  see no seeded facts. The incomplete-evaluation diagnostic names entity
  44712 while the assertion expects submitted position 0.
- `missing-required-identity-member`: the diagnostic path is
  `[44712 :my.plan.item/title]`, expected `[0 :my.plan.item/title]`.

The owning `src/seon/db.clj` and schema/evaluation files are concurrently
held by other lanes; this slice deletes only an unrelated duplicate
uniqueness test from the namespace. No baseline run establishes cause.
The [older reverse-ref admission note](archive/raw-write-validation-refuses-reverse-refs-and-partial-entity-maps.md)
is superseded; this observation concerns the current final-report boundary
and does not reopen that earlier attribution.

Verify the current writer authority and correct either behavior or stale
fixture/diagnostic expectations in place, preserving canonical fixtures and
armed contracts. Do not weaken required entity validation to admit the
incomplete turn. Evidence: [landing note and exact log](../../prds/steward-platform/research/message-wake-model-2026-09-17.md).
