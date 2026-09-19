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
The later observations below measure the complete selector after that change.

Fifth iteration, after removing the file/batch digest from definition identity:

| Changed definitions | Full selector ms | Selected members |
|---|---:|---:|
| 1 | 3944.419458 | 310 |
| 10 | 3280.804875 | 315 |
| 100 | 4368.880667 | 1643 |

`tmp/a1-fast-5.log` was interrupted by the orchestrator pause; it has no
final tally. These observations likewise do not isolate acquisition cost.

Fourth iteration, indexed acquisition plus D7 reuse and D9 content comparison,
`tmp/a1-fast-4.log`:

| Changed definitions | Full selector ms | Selected members |
|---|---:|---:|
| 1 | 5589.583500 | 310 |
| 10 | 5013.303125 | 315 |
| 100 | 6158.332709 | 1643 |

These observations do **not** show an end-to-end latency improvement. They
include the added provenance/reuse decisions and a changed test population;
they do not isolate the cost of fact acquisition. The structural change is
bounded indexed acquisition, not a demonstrated speedup.

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
[the resolved schema admission issue](../../../seon/issues/archive/schema-edit-admission-cannot-load-after-error-predicate-retirement.md).
The third launcher also printed a 19,961,455-byte complete manifest line;
recorded in
[the overlay output issue](../../../seon/issues/fast-overlay-admission-prints-the-complete-program-manifest.md).
Neither launcher nor schema admission owner is edited by A1.

The fourth fast iteration uses a disposable worktree at `23f1f4975`, the
previously loadable baseline, plus only A1's owned source/schema/test changes.
Its explicit boundary excludes both intervening error-owner changes. It is
an iteration proof, not current-HEAD integration or live adoption.

The fourth iteration completed **41 tests, 353 assertions, 7 failures,
0 errors**. The actual host/SCI custody regression passed. Two selection
assertions exposed an overly broad definition key and an obsolete execution
expectation; five assertions followed the new check fixture's unavailable
SCI acquisition. Those causes are corrected for the fifth iteration:

- `seon.fn` stores the input file/batch digest on
  `:seon.program/analyzed-source-digest` (`src/seon/fn.clj:620`, `:654`,
  `:1006`). It proves analysis, not individual definition equality. D9's key
  hashes that definition's source, contract and declared edge/eligibility
  facts through `seon.id/digest`, excluding entity IDs and the batch digest.
  The branch regression now varies surrounding whitespace as well as IDs.
- Creating and retracting an unused file leaves the same final program.
  Widening eligibility can therefore reuse matching green evidence under D7.
- Adding a fixture namespace is a program change outside an individual
  function installation. The check fixture now acquires the complete
  program through `sci.eval/acquire!` and positively compares its digest
  before execution.

## Policy and admission slice

Checkpoint `f07d4eb64` lands this slice and clears its source/test WIP.
A clean checkout of that commit prints `:loads` after requiring `seon.fn`,
`seon.cluster`, `seon.turn`, `seon.plan`, and `seon.test`
(`tmp/a1-load-f07d4eb64.log`). The earlier isolated fourth/fifth iterations
are the behavior evidence available at this checkpoint, not a green HEAD gate.

The selector reports matching admitted green members as
`:seon.test.selection/unchanged`, each with tested basis, program digest and
input digest. Named, all, full and platform policies select eligibility;
matching evidence discharges execution. A supplied comparison basis must
match reused evidence. Current admitted member verdicts own this decision;
no second result registry is introduced.

`check-admission` hands changed identities and file-derived symbols to this
same selector. `check` reserves runnable members; declared long, destructive,
and deferred choices are separate owned `:seon.test.run/exclusions`
components. An execution bound or red platform member still leaves its
already-admitted remainder outstanding, rather than claiming it ran.
The real check regression requires one passing SCI execution, recorded
exclusion of the long test, and zero executions on its unchanged second call.

Admission lives at `seon.test/admit-run`; the runner forwarding transaction
function is removed and every source/test caller is migrated. The design
and AGENTS section 5 policy sentences change with this behavior.

Selection-level set reuse is implemented here. A5 still owns integration of
the older single-test `runner/reusable-result` / `run-owned` records with the
complete input-digest confidence protocol; A1 does not represent those older
records as admitted membership evidence.

## Resumed D12 integration

The predicate caller sweep preserved A1's pending diff; it was verified before
merging the isolated candidate. The normal schema hook admitted the selection
and run schemas after that sweep. During the following facet edit a concurrent
`src/seon/db.clj:371` edit had an unmatched delimiter, so A1 continued in
`tmp/a1-current-wt` at `487d7e4eb`, with only owned overlays. The database file
and error/instrumentation owners remain untouched.

Sixth iteration (`tmp/a1-fast-6.log`): **41 tests, 294 assertions, 1 failure,
16 errors**. The branch-content regression and real host/SCI selection plus
recorded execution passed. The resolution regression recorded its counts;
its sole failure was the old `:seon.error/kind` refusal assertion. Current
owned fixes use complete base observations and exact selection, admission,
resolution and execution facets. Further iteration is required: refusal
handling reaches instrumentation stack overflow; database diagnostic creation
still omits `:seon.error/at` at the snapshot's `src/seon/db.clj:174`; the check
fixture reports an SCI install source mismatch before its second-check proof.
The selector's broad exception catch is narrowed to its declared facets so an
instrumentation error is no longer reported as a selector refusal.
The foreign refusal boundary is recorded in
[the projection-acquisition issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).

Checkpoint `74608b99c` lands the owned D12 producer, contract and assertion
changes. A loader probe prints `:loads` (`tmp/a1-load-facets.log`); the normal
schema admission hook also accepted the combined resource/consumer edit.
The seventh iteration was stopped (exit 143, no tally) after the canonical
fixture rejected a boolean-only error facet. The provenance facet now carries
the concrete failure. Eighth iteration: **41 tests, 300 assertions, 2 failures,
16 errors** (`tmp/a1-fast-8.log`). Resolution now passes, including the exact
refusal assertion. Complete population selection cannot obtain its program
digest because a compiled error-predicate schema is noncanonical. The check
fixture reaches `seon.sci.eval/acquisition-refusal` at line 1580, which still
passes no base observation to `error/diagnostic`; its zero-execution proof is
therefore still owed, not claimed.

The ninth iteration uses current HEAD `a931e68b8`, including the newly landed
database fixes: **41 tests, 306 assertions, 7 failures, 15 errors**
(`tmp/a1-fast-9.log`). The retained exception data identifies
`seon.error/config-expectation-present?` and its instantiated generator as the
noncanonical compiled schema. The same admission stack overflow remains.

A subsequent D7 correction reports recorded members even on a bare request
with no executable reasons. It derives those identities from scoped run
members, so admitting another zero-member request cannot erase their green
evidence. Its regression checks both the immediate bare response and that
later response; the exact confidence fields remain on every reused member.
Checkpoint `04d044538` lands that correction and preserves provenance exception
data so the noncanonical predicate is visible instead of only its message.
A clean detached checkout of that commit requires `seon.fn`, `seon.cluster`,
`seon.turn`, `seon.plan`, and `seon.test`, prints `:loads`, and exits 0
(`tmp/a1-load-04d044538.log`). The disposable load worktree is removed.

The tenth and final fast snapshot has the exact source/test content of
`04d044538`: **41 tests, 306 assertions, 7 failures, 15 errors**, exit 1
(`tmp/a1-fast-10.log`). It reproduces the ninth run's foreign boundaries.
Definition-content agreement across branches, admitted-source resolution, and
the real host/SCI custody regression pass. The new bare-response assertions
are not reached after the complete selector's provenance failure; the check
fixture's zero-execution assertion is also not reached. This is a recorded
verification limit, not a green result. All A1 test/load processes have exited;
its disposable worktrees and merge scratch files are removed. Logs remain.

## Files and verification scope

The coherent commits touch `src/seon/test.clj`,
`src/seon/test/selection.clj`, `src/seon/test/runner.clj`,
`resources/seon/schemas/seon.test.edn`, `seon.test.selection.edn` and
`seon.test.run.edn` in that same directory, and
`test/seon/test/selection_test.clj`, `test/seon/test/runner_test.clj`,
`test/seon/test_test.clj`. Policy authority changes are in `AGENTS.md` section 5
and `docs/prds/steward-platform/plan/test-system-stage1-3-design-2026-09-17.md`.
This note and the linked issue notes retain the measured verification boundaries.
No held error, instrumentation, cache, database, SCI acquisition or foreign
render file was edited. A1's source/test WIP is committed.

The branch-content and actual host/SCI custody regressions pass in the resumed
iterations. The complete population selection and second-check proof remain
unverified at current HEAD because their recorded foreign boundaries occur
before the assertions. Earlier green evidence is dated to its snapshot and
does not certify the current error-family integration.

## Integration owed

The orchestrator owns the cold selected gate, platform proof, a successful
bare request, and a second unchanged bare request executing zero tests.
A0 still refuses bare requests lacking the real selection-authority
handoff; A1 alone cannot certify that A4 launcher integration.

Exact selected cold gate owed to the orchestrator:

```sh
bin/test --paths src/seon/test.clj src/seon/test/selection.clj src/seon/test/runner.clj resources/seon/schemas/seon.test.edn resources/seon/schemas/seon.test.selection.edn resources/seon/schemas/seon.test.run.edn test/seon/test/selection_test.clj test/seon/test/runner_test.clj test/seon/test_test.clj -- seon.test.selection-test seon.test-test seon.test.runner-test
```

After the foreign refusal boundaries and A4 handoff converge, verify the
platform gate and a bare `bin/test` selection, then a second bare `bin/test`
that executes zero and reports recorded basis, program digest and input digest.
No lane cold gate or default lifecycle command was run.
