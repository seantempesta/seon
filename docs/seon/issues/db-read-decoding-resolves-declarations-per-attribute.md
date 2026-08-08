---
type: issue
status: open
severity: friction
tags: [issue, database, schema, isolation, performance]
---

# `seon.db` read decoding resolves the declaration population per attribute

## Problem

Every `seon.db` read path that may contain an EDN-backed attribute asks
`seon.schema.datahike/edn-encoded-attr?` **once per attribute**, and that
function resolves the declaration population from scratch each time. When no
population is supplied on the calling thread — a hand-built fixture, or any
work that crossed a raw or virtual thread and dropped its bindings —
`seon.schema/registered-schemas` falls through to
`seon.schema.edn/packaged-forms`, which re-reads and re-validates all 151
schema resources from the classpath. Measured at **14.3 ms per attribute**.

This is the read-side half of the defect that wedged the bare suite on
2026-08-07. The write-side half is fixed: `encode-transaction` now resolves the
population exactly once per transaction and delegates to the explicit-projection
`encode-transaction-in`
([research](../../prds/sci-execution-runtime/research/parallel-turns-hang-cause-2026-08-07.md)).
The read side was left alone deliberately, to keep that repair surgical.

It is the audit's Defect I: the fallback is not merely semantically wrong under
two environments, it is thousands of times slower than the supplied path.

## Evidence

Measured after the write-side fix (`tmp/repro/remaining_cost.clj`, median of 5,
in-memory store, no population supplied):

```
registered-schemas ms: 14.24
one transact! ms:      15.40   ; one resolution — correct
one pull ms:           28.69   ; two attributes, two resolutions
one q ms:               0.087  ; no decode, no resolution
```

The per-attribute call sites, all in `src/seon/db.clj`:

- `:351` — `decode-attribute-maps`, per key of every decoded map;
- `:415` — `query-find-attributes`, per find element;
- `:524` and `:528` — the pull decoder, per pulled key;
- `:785` — `datom->data`, per datom.

Each reaches `src/seon/schema/datahike.clj:331-337`, which builds a one-key
projection map from `schema/registered-schemas` on every call, then
`src/seon/schema.clj:2287-2292` → `candidate-forms` (`:590-599`) →
`packaged-forms` (`:587`) → `src/seon/schema/edn.clj:315-341`.

The residual cost is visible in the suite: `seon.cluster.work-test`'s
`situation-totality-property` takes ~60 s for 200 trials after the write-side
fix, essentially all of it here.

## Two suite wedges attributed here, 2026-08-07 evening

Found by the write-side sibling lane while proving its own change; recorded
here because this issue owns them. Both are the 300 s liveness backstop
firing, and both dumps put `main` in `seon.db` read decoding with no frame
from any function that lane changed:

- `seon.reconcile-test/reconciliation-uses-current-provenance-without-history`
  — `reconcile/plan` → `db/pull` → `decode-pull-entity` (`db.clj:528`) →
  `edn-encoded-attr?` → the complete resource population, per pulled key. Its
  `with-non-temporal-database` fixture is hand-built and supplies no
  population — the same class (c) shape as `work-test`'s. Every other test in
  that namespace finished in 0.5-1.5 s.
- `seon.config-application-test/applied-values-shape-the-running-system`
  (a `:seon.test/long` test, so a bare run does not reach it) — same stack,
  same frame.

Both are therefore reproducible acceptance tests for this repair: they should
run to completion with no change to the test.

A third, measured on a live cluster the same evening (isolated operator root,
cluster `declpop2`): one `seon.config/effective` call performs **84,664
schema resource reads**, every one of them inside `db/pull '[*]` decoding the
65-key config row. The caller resolves its own population exactly once; all
84,664 come from this issue.

## Owner

`seon.db`, with `seon.schema.datahike` supplying the already-correct
explicit-projection functions (`edn-encoded-attr-in?`,
`validate-logical-slot-in!`). The five walkers are recursive, so the projection
threads through them the same way `encode-entity-in` / `encode-value-in`
already thread it on the write side — the pattern is written and proven.

Note the sequencing: `seon.db`'s ambient elision internals and its named
readers are already inside the seon.env Phase 3 deletion boundary
([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)). Once
the environment is a value, the single resolution point becomes an environment
read rather than a `registered-schemas` fallback. Fixing this issue before
Phase 1 is worthwhile only for the velocity win; the resolve-once shape is the
same either way and is not wasted work.

## Acceptance criteria

- Each public `seon.db` read operation resolves the declaration population at
  most once, regardless of how many attributes, datoms, or pulled keys the
  result contains.
- A class regression counts resolutions through `with-redefs` on
  `schema/registered-schemas` and asserts one per read operation, in the shape
  of `seon.schema.datahike-test/encode-transaction-resolves-the-declaration-population-once`.
- `seon.cluster.work-test/situation-totality-property` completes in
  substantially under its current ~60 s with no change to the test.
- No new process-global cache of declaration facts is introduced; the fix is
  resolve-once-and-pass, per the PRD's mutable-reference rule.
