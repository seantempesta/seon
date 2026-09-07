---
type: defect
status: open
severity: blocker
tags: [schema, reconciliation, boot, testing, class/p1]
---

# Schema row reconciliation never converges on `:seon.render/units`

`bin/test --all` cannot reach its bulk tier. The platform tier fails
`seon.test-support-test/a-canonical-database-is-the-production-source-population`
at its last assertion — "clock-free schema reconciliation is idempotent" — so
`bin/test` stops with `PLATFORM TIER RED` and runs nothing else.

Measured 2026-09-07 at `4f8cd788f` (before the evaluation-merge step) and at
`2cf1c00fc` (after it): the same test, the same assertion, `6 tests / 92
assertions / 1 failure`. Inherited, not introduced.

## The cause, probed live

`seon.cluster/accrete-schema-population!` re-emits exactly one canonical
schema row on every converged reopen, forever:

```clojure
;; on a canonical test database, immediately after with-database built it
(schema-row-changes @conn forms)
;; => one row: #:seon.schema{:key :seon.cluster.agent/agent}
```

The desired row and the stored row differ in ONE attribute, and the
difference is ORDER:

```
desired :seon.render/units [:seon.cluster.agent/id :seon.cluster.agent/namespace
                            :seon.cluster.agent/cluster :seon.cluster.agent/run
                            :my.plan/steps :my.plan/current-step …]
stored  :seon.render/units [:my.plan/current-step :my.plan/steps
                            :seon.cluster.agent/cluster :seon.cluster.agent/id …]
```

`:seon.render/units` is declared in `resources/seon/schemas/seon.cluster.agent.edn`
in reading order; Datahike stores it as a cardinality-many value and reads it
back sorted. `seon.cluster/schema-row-changes` compares with
`(= desired (select-keys current (keys desired)))` — a VECTOR equality over a
value the store does not preserve the order of. The row can never equal
itself, so every reopen commits one more transaction and the idempotence
assertion can never hold.

This is the AGENTS.md §3 defect class exactly: an unordered collection
driving a tied decision. It is also a live cost, not only a test failure —
every branch open transacts a row it did not need to.

## What would fix it

Either compare `:seon.render/units` as a SET (the store's actual semantics)
in `schema-row-changes`, or stop storing a declared ORDER in a
cardinality-many attribute — the PRD's own direction, since
`:seon.render/units` is slated for deletion in favour of the component-ref
entries of the entity schema in declared order
([PRD §3](../../prds/context-generation/plan/agent-record-and-repl-response-prd-2026-09-07.md)).
The second dissolves the mechanism; the first unblocks the gate today.

Found by the `evaluation-merge` program step while running `bin/test --all`;
outside its owned paths (`src/seon/cluster.clj`).
