---
type: evidence
status: committed (commit 1 of the projection-as-a-read sweep); armed fixture acceptance blocked by a foreign publication defect
created: 2026-09-23
---

# Projection writer prerequisite

The owner's 2026-09-23 memoization ruling supersedes the initial candidate-carriage
proposal. Projection is now a function of the exact database value. The writer
publishes no projection and does not stamp transaction reports or callback databases.
No fork, single-flight constraint, ambient binding or publication repair was added.

## Implementation and dependency evidence

* `src/seon/db.clj:1213`: the declared policy retains eight committed projections,
  with its retention reason alongside the bound. `carried-projection` checks
  Datahike's committed-value identity and keys the LRU by the full `:cache-context`.
  A miss inserts a Clojure delay before forcing it; concurrent callers can share
  the winning delay rather than deriving before the cache insertion succeeds.
* `src/seon/schema.clj:2788`: `load-projection` keeps the existing row collection,
  admission/provenance checks and `projection-from-rows` constructor. Both existing
  `projection-from-database` arities use the memoized database function. The second
  argument stays compatible at the call boundary but no longer chooses another world.
* `transact-call` returns Datahike's report without projection-state attachment.
  `schema/datahike.clj:404` preserves the codec and recursion but removes the callback
  metadata stamp. `turn.clj:976,1202` keeps ordered callbacks and reads their exact
  intermediate database. The preceding-declaration marker and its special accessor
  are deleted; existing candidate operations still validate each declaration.
* The old turn test asserted that marker/reconstruction mechanism. It leaves with
  the mechanism. `test/seon/schema/projection_writer_test.clj` replaces it with
  ordering/rollback/value-identity and committed/speculative cache regressions.

Datahike pinned and checked out: `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`.
`reference-code/datahike/src/datahike/schema_cache.cljc:2,8` uses the same
`clojure.core.cache.wrapped` library and bounded LRU factory. The installed
core.cache 1.1.234 source, `clojure/core/cache/wrapped.clj:38–69`, supplies
`lookup-or-miss`; `:154–163` supplies its wrapped LRU. No second cache library.
`datahike/db.cljc:385–411` owns committed identity and clears speculative cache
contexts. `datahike/core.cljc:126–144` passes the cleared value into `with`.
`datahike/db/transaction.cljc:1153–1154,1324–1325` supplies each callback the
intermediate database and splices its operations before the next callback.

Inputs: one database value. Recompute event: a committed LRU miss, or each read of
an uncommitted value. Derivation scans that value's declaration ranges and builds
its complete projection. Hits do not scan declarations. The LRU bounds retained
compiled populations, not the cost of an uncommitted derivation. Existing constructor
state/stamp APIs outside this assignment remain for their separately assigned sweep;
this change no longer reads those stamps as projection authority.

## Live read-only probe

`bin/seon status` and MCP `runtime_status`: default PID 51528, all proc pings
answered, 14 existing errored receipts. MCP `eval_clj`: mode jvm, read_only true,
root `/Users/sean/src/seon`, cluster default, timeout 10000 ms:

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      w (:db-after (datahike.api/with d []))]
  {:committed-context-keys (keys (:cache-context d))
   :committed-identity (datahike.db/committed-value-identity d)
   :with-context (:cache-context w)
   :with-identity (datahike.db/committed-value-identity w)})
```

Returned in **2 ms**. Committed context has connection-id, generation, commit-id,
committed? and attribute-revisions. Its commit was
`6ab2dfa3-0166-52c3-9142-ca77af359491`. Both `with-context` and `with-identity`
were nil. Thus intermediate values derive **unmemoized**; no fabricated identity.
Default was never reloaded, published into, stopped or reset. Test recording uses
the installed recorder's current-src authority, as the normal fast runner does.

## Verification and exact limits

Shared-source require of `seon.turn`, `seon.db`, `seon.schema` and
`seon.schema.datahike` passed initially. Final command and log:

```sh
clojure -M -e "(require 'seon.turn 'seon.db 'seon.schema 'seon.schema.datahike)"
# tmp/projection-writer-producer/final-load.log
```

Focused `clj-kondo` reported no error-level findings; `git diff --check` was clean.
No schema resource changed, no boot/publication clock or platform gate was run.
Those remain the orchestrator's boundary, not implied passes here.

The first requested `bin/test-fast --paths` overlay included the foreign schema
partition validator without its foreign resource changes: fixture setup refused
`:my.note/note` for missing `:seon.program/partition`. Per the owner's recovery rule,
created `tmp/projection-writer-wt` at then-HEAD
`56bae6708cd13adefd9f21551d51329ff126f7a8`, linked `reference-code`, and applied only
owned changes. That temporary commit included these db hunks; its author later
amended them out, and they remain this lane's uncommitted work in the shared tree.
The snapshot did not include the foreign partition validator.

The snapshot launcher then lacked a published graph; it reused the existing
`target/test-published-bases` via a link. Its next attempt armed contracts but could
not record against the scratch root's absent current-src. Continued through the
**same installed** `seon.test.fast` entry point with explicit shared recording root:

```sh
clojure -J-Dseon.test.source-root=/Users/sean/src/seon \
  -J-Dseon.test.git-sha=56bae6708cd13adefd9f21551d51329ff126f7a8 \
  -M:test -m seon.test.fast seon.schema.projection-writer-test
```

Published fixture: `d73e0a6ce0420c376f5fbff73acdd18fb03740cdf299b981b2035932af50642e`,
31 commits behind that snapshot. Arming positively reported **1694 instrumented**,
1686 program-armable Vars, mode panic. Recorded run `da9cb6313278`:
**3 executed, 0 reused, 13 assertions, 0 failures, 1 error**. Both cache tests pass:
two committed reads cause one derivation and one hit; a new commit derives once;
two `with` reads derive twice. Ordered writer acceptance is unavailable: the
fixture declares seven arguments for `seon.db/validate-pulled-result`, while the
loaded function and caller have five. The
exact refusal is in `test-fast-5.log` under `:writer-existing`. It becomes an error
map where row-tx expected an existing declaration. No contract was weakened.
This is the existing issue class
[canonical fixture contracts](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).

A **separate unarmed diagnostic** executed the same three new tests on canonical
fixture branches: **26 assertions, zero failures/errors**, exit 0. It proves the
underlying declaration/transaction mechanics, not armed acceptance:

```sh
clojure -J-Dseon.test.source-root=/Users/sean/src/seon \
  -J-Dseon.test.git-sha=56bae6708cd13adefd9f21551d51329ff126f7a8 \
  -M:test -e "(require 'seon.schema.projection-writer-test) (clojure.test/run-tests 'seon.schema.projection-writer-test) (shutdown-agents)"
```

The proof calls are `[:db.fn/call #'seon.turn/row-tx {} row]`: declare A as `:int`,
then B as `[:vector A]` in one transaction. Returned after validates A and B;
before contains neither; the retained earlier projection is identical. Then C as
`:string` followed by `[:vector absent]` refuses, installs no C, and leaves the
connection cache-context and resulting projection identical. Exact assertions are
in the new regression. Log: `diagnostic-unarmed.log`.

Existing `seon.schema-usage-guard-test` ran through the armed fast authority:
run `5d944e703dde`, **12 executed, 37 assertions, 11 failures, 8 errors**. Three-question
classification (these are not green):

* Deleted marker/reconstruction machinery: removed its old turn test, replaced
  by the new behavior class above.
* Retired assumptions: schema fixtures omit required definition digests; their
  reference query still joins keyword-valued references as entity refs; some
  refusal expectations require schema details the returned wrapper does not expose.
  These precede successful owner probes and remain recorded fixture/test conversion
  work, not a reason to restore a retired production shape.
* Surviving behavior: declaration replacement/removal is blocked by the same
  five-versus-seven pull contract. `program-fn-row` setup also hits the existing
  helper's namespace-row missing definition digest. The forbidden test-support
  owner was not changed. One lifecycle body measured **5271.10 ms**, above its
  5000-ms bound; not excused as priming or relabelled green.

Raw evidence is under `tmp/projection-writer-producer/`: test-fast-1 through -5,
existing-declarations.log, diagnostic-unarmed.log, final-load.log and lint.log.
No loaded default code or browser behavior is claimed.

## Timing and memory

Exact form: `tmp/projection-writer-producer/measure.edn`, evaluated in the isolated
source JVM on a canonical fixture branch. Warm unarmed derivation after explicit
entry eviction: **277.038916 ms**. Next lookup: **0.043167 ms**, identical result.
3285 schema forms, 1796 function contracts; cache held 2 of its maximum 8 entries.
Heap before: 2,053,072,400 bytes; after: 685,225,448 bytes. GC occurred during the
interval, so this is observed heap occupancy, **not** a retained-size or allocation
estimate. Log: `measurement.log`.

## Landing boundary (superseded by the takeover below)

The predecessor held the slice because `schema.clj` and `db.clj` then held foreign
hunks. At takeover (HEAD `bfe3445f8`) those were committed by their lanes; every
remaining hunk in the five owned source/test paths is this lane's.

# Takeover and commit (Opus 5.5, 2026-09-22)

## Hunk ownership

`db.clj`, `schema.clj`, `schema/datahike.clj`, `turn.clj`, `turn_test.clj`: every
hunk is this lane's (checked against `tmp/projection-writer-producer/final-owned.patch`
and the predecessor log). `resources/seon/schemas/seon.source.edn`'s working-tree
hunks (`:seon.source/publication-error`) belonged to the publication-lock lane,
which committed them in `a102a8403`; this slice touches **no schema resource**.

## Three defects found in the uncommitted slice, fixed before commit

1. **Temporal views derived from themselves.** The memo dropped `schema-database`.
   Probe on default (JVM, read-only, 2 ms): `committed-value-identity` of
   `(d/as-of d …)` and `(d/history d)` is nil, while their `schema-database` origin
   carries the committed identity and the same `:cache-context`. A history view would
   have re-derived unmemoized from history datoms. `carried-projection` now reads the
   origin, as the installed schema does.
2. **Every commit missed.** The key was the whole `:cache-context`, whose commit id
   changes on every write, so each write paid a full derivation (277 ms warm,
   predecessor's measurement). The key is now the dependency slice of that context:
   connection id, generation, conservative revision and the attribute revisions of
   the six attributes `load-projection` reads. Seam: `reference-code/datahike`
   `41c79c1a7`, `src/datahike/query.cljc:2568-2590` `advance-query-cache-context`
   (one revision per changed attribute; conservative revision on schema change) and
   `:2963-2975` `source-context-unchanged?`, which compares exactly these members.
3. **Genesis refused.** From-zero boot of the working tree exited 1 after 38.69 s:
   `transact!` on the fresh scratch branch derived from a value lacking
   `:seon.schema/form` (`Bad entity attribute :seon.schema/form`). A value with no
   declaration rows cannot derive a projection. `carried-projection` now reads the
   construction projection its cold boundary supplied, else refuses by name via
   `projection-fallback`. `transact-call` likewise validates and encodes with the
   candidate a publication/genesis write supplies on its value, else the value's own.
   This is HEAD's writer behaviour for supplied candidates; the sweep's cold-owner
   table (spec §3) replaces that transport with explicit arguments.

Also removed: the now-dead `or` fallbacks after `carried-projection` in
`read-declarations` and `arity-mismatches`, and `row-tx`'s identical-branch `if`.

## Audit rows 2 and 9 (dependency-already-does-it audit)

* **Row 2, temporal views re-merge the stamp** (`db.clj` `database-view`,
  `vary-meta … merge (meta database)`): the projection no longer needs it —
  `carried-projection` reads the view's origin (fix 1). The merge still carries
  `:seon.sci.eval/projection-state`, read by `supplied-database` (`db.clj:1846`); it
  is deleted with row 1's remaining stamps in the sweep, not here.
* **Row 9, projection staleness by `basis-t`** (`env.clj:116` `advance-projection!`):
  untouched. Its callers (`cluster.clj:2074`, `sci/eval.clj:642`, `runner.clj:1500`)
  advance environment state, a transport the sweep deletes (B2/D1). The memo itself
  compares Datahike's per-attribute revisions, never `max-tx`.

## Proof

* Lint: `clj-kondo` on the owned files — no error in owned hunks (the one error,
  `db.clj:686 parser.type/->Variable`, is the stale dependency cache). `git diff
  --check` clean. Load: `clojure -M -e "(require 'seon.turn 'seon.db 'seon.schema
  'seon.schema.datahike)"` exit 0, 14.5 s wall.
* **From-zero boot, `git archive` of HEAD `bfe3445f8` plus exactly the owned patch**
  (`tmp/projection-writer-producer/owned-now.patch`), root `tmp/pwp-boot-root`:
  exit 0, `ready-ms 131170`, 148.98 s wall, one agent. Log:
  `from-zero-boot-3.log`, `from-zero-boot-3-seon.log`. Probe in that JVM (read-only):
  `carried-projection` of the committed head 0.091 ms (resident from boot), again
  0.040 ms `identical?`, `(d/history d)` 0.041 ms `identical?`; 3298 forms. The boot
  exercised genesis, publication and ordinary writes through the new code. Root
  stopped (`down`, 1.26 s) and deleted; snapshot deleted.
* A second working-tree boot (41.29 s) refused on a foreign uncommitted
  `:pos-int` contract in `src/seon/test.clj` (`seon.test/bounded-result`), not this slice.
* Regression rewritten: `committed-reads-derive-once-per-declaration-population` —
  one derivation, one hit, a history view hits, an unrelated commit hits, a
  declaring commit derives exactly once and contains the declaration. The ordering/
  rollback and speculative tests are unchanged.

## Verification limits — armed tests not run

* `bin/test-fast --paths <owned> -- seon.schema.projection-writer-test` (run
  `58dbaa0a25a8`, 27.4 s): 3 errors, all fixture setup refusing `:my.note/note` for a
  missing `:seon.program/partition`. Pure HEAD fails identically
  (`bin/test-fast --paths test/seon/schema/projection_acquisition_test.clj --
  seon.schema.projection-acquisition-test`): the only published base is 44 commits old.
* `bin/test --paths <owned> -- seon.schema.projection-writer-test` (107.4 s): HEAD
  base preparation refused — incremental publication keeps a deleted callee's caller
  edge. Filed: [incremental publication refuses a deletion whose unchanged caller edge
  survives](../../../seon/issues/incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md).
* The unarmed `clojure.test` diagnostic now refuses by design: the canonical fixture
  requires `seon.test/run`'s executing handle.
* Consequently the new regressions have **no armed run at this commit**; the
  predecessor's unarmed run (26 assertions, 0 failures) predates fixes 1-3.
* Cost left in place, ruled: each ordered declaration inside a transaction derives
  unmemoized from Datahike's cleared intermediate value (~277 ms warm per declaration).
* Default (pid 51528) was never stopped, reset, reloaded or adopted.
  **RESET NEEDED** (default) is the likely unblock for test-base preparation, pending
  the publication fix above.

## Timings (operations over 1 s)

| operation | wall |
|---|---|
| owned-namespace require | 14.5 s |
| from-zero boot, working tree, pre-fix (exit 1) | 38.69 s |
| from-zero boot, working tree, foreign refusal (exit 1) | 41.29 s |
| from-zero boot, HEAD archive + patch (exit 0, ready 131.2 s) | 148.98 s |
| `bin/test-fast` owned run `58dbaa0a25a8` | 27.4 s |
| `bin/test-fast` HEAD baseline | not timed |
| `bin/test --paths` gate (base preparation refused) | 107.4 s |
| unarmed diagnostic JVM | 12.3 s |
| scratch `down` | 1.26 s |

Every row over 10 s is a defect; boot rows are appended to
[from-zero boot takes minutes](../../../seon/issues/from-zero-boot-takes-minutes.md).

# Review follow-up (Astra review `docs/research/agent-platform/review-projection-writer-producer-2026-09-23.md`)

Verified then fixed on HEAD `5b7e4436d`. Owned paths only: `src/seon/db.clj`,
`src/seon/schema.clj`, `src/seon/turn.clj`, `test/seon/schema/projection_writer_test.clj`.

* **P1 as-of reads the future — confirmed live.** Default (pid 43581, loaded code
  = committed `9b8c5b405`, `seon.schema/projection-attributes` unresolved), read-only
  MCP probe: key `:inst`, committed form `inst?`; `d/with` replacing it by `:string`,
  then `(d/as-of next (:max-tx raw))` → committed accessor returned `string`
  (198 ms). Fix: an `AsOfDB` derives from its own declaration datoms, unmemoized;
  `read-declarations` passes the view, not its origin. Same live value through the new
  branch's derivation `(seon.schema/load-projection view)` → `inst?`, 182.5 ms.
  History and since keep the origin's current population (docstring states why).
  Regression `an-as-of-view-before-a-declaration-change-reads-the-older-population`.
* **P1 writer trusts a stamp — confirmed** (`transact-call` read metadata first).
  Fix: the writer reads `(carried-projection database)` only. Regression
  `the-writer-derives-from-its-database-never-a-stale-stamp` injects a stale
  stamp through `resolve-database-value` after a committed declaration and asserts
  `write-error` receives the value's derived projection. Remaining cold read: a value
  with **no declaration rows** still takes its construction projection
  (`construction-projection`, one private fn, named refusal when absent). Removing it
  needs explicit arguments at the cold writers outside this lane's files — see below.
* **P2 dependency set handwritten — fixed.** `schema/projection-ranges` drives
  `load-projection`'s scans; `schema/projection-attributes` derives from it and keys
  the memo. Probe (HEAD archive load): 6 attributes
  `[:seon.schema.admission/source :seon.schema/key :seon.schema/form :seon.fn/sym
  :seon.fn/spec :seon.fn/source]`. The key still mirrors Datahike's comparison members
  (`query.cljc:2568`, `:2963`, private); a public fork seam would remove that copy.
* **P2 contracts — fixed.** `load-projection` takes `:seon.db/database-value`; the
  key declares connection-id `[:tuple :uuid :keyword]`, generation `:uuid`, optional
  conservative revision, revisions `[:map-of :qualified-keyword :uuid]` (types probed on
  default); `row-tx` gains `[:=> [:cat :seon.db/database-value :map :map]
  :seon.store/transaction-data]` (row is admitted inside by `program/declaration-row`).
* **P2 ordered writes pay whole-population work — not changed**; ruled unmemoized.
  Measured on default now: one speculative derivation 196.6 ms. Existing issue
  `docs/seon/issues/class-local-updates-recompute-global-projections.md`.

## Required change outside this lane's files (reported, not made)

Pass the cold candidate explicitly so `construction-projection` can be deleted:
give `seon.db/transact!` a third arity `(transact! connection transaction projection)`
used only by writes to a value with no declaration rows — `cluster/source.clj`
scratch schema transaction, `cluster.clj` `accrete-schema-population!` (declarations,
process rows, schema rows), `populate-source!` instruction rows, and `fn.clj`
`commit-index-phase!`/`index!` population writes — each passing the projection it
already holds locally. Until then, a complete publication onto an existing store
validates index-population writes against the value's old rows rather than the
candidate; a new encoded attribute written before its declaration row would refuse
loudly, never store silently.

## Proof and limits

* HEAD-archive load with exactly the owned patch
  (`tmp/projection-writer-producer/review-fix.patch`): exit 0, 19.6 s.
* Armed runs `e901f56b808b`, `f30c07b62191` (5 tests): all 5 refuse in fixture
  base construction, `:seon.config/entity` missing `:seon.program/partition`. Both
  available bases predate the partition declarations (`d73e0a…` 94 commits old;
  `d9c552…` exported by default's pre-reset JVM and left unready by bin/test's
  `reference-code/datahike` input check — checkout `fbd1ad2` vs gitlink `41c79c1`).
  Existing issue: `test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`.
  **No armed pass and no reverse-mutation sensitivity run for these regressions.**
* `bin/test --paths` (47 s): published-base preparation refused on the datahike
  checkout drift.
* No scratch boot. Default was never stopped, reset, reloaded or adopted by this lane.
