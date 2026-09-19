---
type: research
status: active
created: 2026-09-19
tags: [testing, selection, lane-a1]
---

# A1 — database test selection

## Boundary and grounding

Read AGENTS.md sections 0–5, the stage-1 review (including H1–H15 and
L0–L6), both test-system authorities, selection-efficiency research, and
the A0 and stage-1 landing notes end to end; read namespace-agents plan
sections 7–8. D7 and D9 supersede the older execution-policy wording.
Applied data-oriented-clojure, clojure-testing, repl, datahike and
data-modeling skills. No delegation or lifecycle operation.

Entry branch: `steward-platform`, HEAD `d4686f495`.
Inherited selector/test/runner/schema WIP is owned by this assignment;
the dirty `bin/test` is preserved and excluded from commits.
One read-only MCP JVM probe observed default alive, basis `536871554`,
loaded `seon.test/select`, and publication input digest
`a58de6d9c5fdc48f5179895f06df972cebb4948d9338ab83530735ecf3cb00bc`.
This is an inherited-system observation, not proof of later source adoption.

Dependency ledger:

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1153`
  passes the writer's mid-transaction database to `:db.fn/call`.
- `reference-code/datahike/src/datahike/db.cljc:142` includes the as-of
  basis; `:149` excludes it for since.
- `reference-code/datahike/src/datahike/db/search.cljc:140` chooses EAVT
  for bound entities and AVET for indexed attribute/value pairs.
- `src/seon/fn.clj`, `gate-sets` request arity, owns the shared frontier.
- `src/seon/test/cache.clj`, `input-roots` and root-arity
  `test-input-digest`, own external input evidence (commit `40ddbfd87`).

## First convergence slice

The cold log `tmp/orchestrator/gates/a0-stage1-cold-4-2026-09-19.log`
reports two admission fixture refusals and one missing-count assertion.
The fixtures supplied an invented digest instead of the publication's
observed input digest; admission correctly compares those values at the
writer. They now query the actual input fact.

The SCI result's runtime-only `:seon.test.run/terminated?` entered
`record-latest-tx`'s test-row map. The cold log records the exact schema
refusal before the missing counts. The writer now excludes this request
observation from the stored test row; admitted member termination remains
owned by `complete-members`.

Checkpoint `23f1f4975` lands this slice. The first fast snapshot preceded
the fixes, reproduced all three failures, and was stopped deliberately
(exit 143) after that evidence; no full tally is claimed for it.
Its older published fixture lacked the input fact entirely, so the fixture
now establishes the source input observation before deriving admission.
The second fast snapshot passes all three targeted regressions, including
an explicit wrong-input refusal: **39 tests, 367 assertions, zero failures,
zero errors** (`tmp/a1-fast-2.log`, exit 0).

## Selection measurements

The committed regression's measurement form takes the canonical database
after establishing terminal selection evidence and uses immutable `d/with`
snapshots changing the specs of the first 1, 10, and 100 sorted function
symbols. It times the complete `select` call, with armed contracts. It
does not execute or modify those function bodies. These are individual
observations under current machine load, not latency guarantees.

Before indexed acquisition, `tmp/a1-fast-2.log`:

| Changed definitions | Full selector ms | Selected members |
|---|---:|---:|
| 1 | 4449.082208 | 309 |
| 10 | 4768.752833 | 314 |
| 100 | 5008.789583 | 1641 |

The replacement bounds run/member reads by the selected cluster, reads
history membership only for those runs, and acquires candidate test,
namespace and file eligibility through entity-bound queries. It removes
the every-function analysis sweep; analyzed provenance is checked on the
selected tests, with publication owning whole-population completeness.
After measurements and verification remain pending.

## Integration boundaries observed during the next slice

The third fast snapshot, at `d549c42a6`, repeatedly refused canonical fixture
construction before test bodies. It was stopped deliberately (exit 143),
without a final tally. The exact duplicate is
`[:seon.lint/id "29330d0581c3"]`: two `:redundant-declare` findings for
`facets` and `facet-keys`, both `src/seon/error.clj:1611:1`. The error owner
records its later repair in
[error-family-1a-2026-09-19.md](error-family-1a-2026-09-19.md).
That owner's next commit, `bc8152438`, removes `error/error?`; its external
consumer sweep is still required before the shared tree loads. A1 does not
restore the predicate or edit its held owners.

The shared schema edit hook separately refuses an additive request key with
unregistered `seon.cluster/cluster-name?`. Recorded in
[the schema admission issue](../../../seon/issues/schema-edit-admission-cannot-load-after-error-predicate-retirement.md).
The third launcher also printed a 19,961,455-byte complete manifest line;
recorded in
[the overlay output issue](../../../seon/issues/fast-overlay-admission-prints-the-complete-program-manifest.md).
Neither launcher nor schema admission owner is edited by A1.

The fourth fast iteration uses a disposable worktree at `23f1f4975`, the
previously loadable baseline, plus only A1's owned source/schema/test changes.
Its explicit boundary excludes both intervening error-owner changes. It is
an iteration proof, not current-HEAD integration or live adoption.

## Integration owed

The orchestrator owns the cold selected gate, platform proof, a successful
bare request, and a second unchanged bare request executing zero tests.
A0 still refuses bare requests lacking the real selection-authority
handoff; A1 alone cannot certify that A4 launcher integration.
