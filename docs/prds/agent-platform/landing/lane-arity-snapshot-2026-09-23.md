---
type: evidence
status: landed in call_preparation.clj; db.clj and sci/eval.clj hunks handed to their holders
---
# Lane arity-snapshot (#24s): 2026-09-23

Schedule #24s. The call-preparation snapshot is now a function of the program
rows it reads. It is memoized by their identity, so an arity refusal or an
unrelated commit reads the snapshot already held. The defect comes from the
writer-cost landing ("Refusal path", `lane-writer-cost-2026-09-23.md`), the
flow audit's D3, and invalidation census row A5 (160 ms after an unrelated
commit, 1,483 ms on a fresh branch).

Owned paths:

- `src/seon/call_preparation.clj`
- `test/seon/call_preparation_test.clj`
- this note

## What changed (call_preparation.clj)

- **`snapshot` is a memo over `derive-snapshot`.** A committed value keys by
  its connection generation, its conservative revision and its Datahike
  attribute revisions, restricted to `snapshot-read-attributes`. A miss then
  keys by commit id, and only then derives.
  - The read set is computed from the snapshot's own query forms by
    `datahike.api/query-attribute-dependencies`
    (`reference-code/datahike/src/datahike/query.cljc:2923`). It holds 21
    attributes, so it is not a hand-written list.
  - The validity rule is Datahike's own query-cache rule:
    `advance-query-cache-context` (query.cljc:2568) and
    `source-context-unchanged?` (query.cljc:2963).
  - The memo is a core.cache LRU of 16 entries, held by the projection
    itself (`schema/projection-cache-value`). Compiled validators therefore
    live and die with the projection that compiled them.
  - `checked-through-t` names one database value, so it is added after the
    lookup. Speculative, as-of and history values have no
    `committed-value-identity` and still derive.
- **`report-snapshot` (new, public).** It gives the snapshot at a report's
  `:db-after`. When the report's final datoms name no read attribute, it reads
  the committed `:db-before`'s memo. It is the writer-side arity gate's entry,
  but its caller hunk is not landed (see below).
- **`newest-row-transaction`.** This is now one AEVT range per row
  attribute, where it was a variable-attribute `(max ?tx)` history query.
  - Measured on the same value: 220-274 ms before, 1-5 ms after, same answer.
  - An index refusal now surfaces as the error value. Before, it fell through
    to `0`.
- **`contract-transaction`** (inside `plan-for`). This is now an AVET plus
  EAVT range for one function entity, where it was `[?function _ _ ?tx]`.
  - Measured: 254-800 ms before, and `plan-for` for `seon.id/id` went from
    252-390 ms to 63-69 ms.
  - It now has a complete contract.
- **Swallowed catch fixed.** `supply`'s `(catch Throwable _ nil)` on
  `requiring-resolve` now keeps the Throwable. The class goes in
  `:seon.error/exception-class` and the whole Throwable in
  `:seon.error/offending`, and the message names both. This is the same shape
  as the supplier-threw case.

## Regressions (call_preparation_test.clj)

- `an-unrelated-commit-reads-the-held-snapshot`: after an unrelated commit,
  every program member is `identical?` and `checked-through-t` advances. A
  supplied-default row change derives a new snapshot.
- `a-new-branch-at-a-derived-commit-reads-the-held-snapshot`: a sibling
  fixture branch at the same commit reads the held members through the commit
  tier.
- `a-report-changing-no-read-attribute-reads-its-committed-parent`: an
  unrelated report reads its parent's members, and they equal a derivation at
  the report value. A report that writes a row derives.
- `supplier-failures-...` gains two assertions: the failed resolution's
  exception class and Throwable ride the refusal.

## Proof

**Method.** Everything below ran in default. The first pass was pid 9104 (pre-move);
every row was then re-taken on pid 24492 (HEAD `ad41853a0`, host armed by C1).
- HEAD's `call_preparation.clj` and the working file were loaded as namespace copies, `tmp.arity-snapshot.head` and
  `tmp.arity-snapshot.new` (`tmp/arity-snapshot/harness.clj`). `seon.call-preparation` itself was never
  replaced, redefined or reloaded.
- The probes ran on branches `:arity-snapshot-probe` and `:arity-snapshot-probe-2`, created off default's head and
  retired afterwards (`tmp/arity-snapshot/measure.clj`). Default's own branch was never written.

**Identical results.** On the same inputs, parent and new return identical
refusal-path answers: the snapshot minus projection and checked-through, the
validator key set, refusals without `:seon.error/at`, and prepared arities
(`:refusal-identical true`). The same holds after an unrelated commit, on a
fresh branch, and for `report-snapshot` versus a derivation at the report
value. `checked-through-t` equals the probed value's basis in every case.

**Unrelated commit.** The probe committed an issue row. It advanced only
`:seon.issue/*` revisions, and the conservative revision did not move.

**Test copies (`tmp/arity-snapshot/test_copy.clj`).** The working test file was
loaded against each copy of `call_preparation`. It ran under an isolated
handle from `seon.cluster.agent/acquire-context!` and
`db/call-with-custody`, on pid 24492.

- The four changed tests are green on the new copy: 5/5, 2/2, 4/4 and 11/11
  assertions.
- They are red on the parent copy: 1 fail, 1 fail, unloadable (no
  `report-snapshot`), and 2 fails.
- Every other test in the namespace has identical pass/fail/error counts on
  both copies. Their reds are pre-existing:
  - the fixture digest refusal, already filed as
    `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`;
  - copy-namespace probe identities, where the hook keys plans by
    `seon.call-preparation-test/*` but the copies' Vars live elsewhere. This
    is a harness artifact.

**Contracts.** All 27 contracts in `call_preparation.clj` compile against
`schema/build-projection` over `schema.edn/packaged-forms` (777 ms). The
hook's checker `seon.contracts-compile-test/check` reports no findings on both
files (674 ms).

**Lint.** `clj-kondo` reports 0 errors on both files.

### TIMINGS (pid 24492 unless marked; parent → this lane)

| operation | parent | new | note |
|---|---|---|---|
| snapshot after an unrelated commit (committed value) | 211-237 ms | 0.07-0.09 ms | revision tier hit, identical |
| snapshot on a fresh branch at a derived commit | 1,163-1,395 ms (pid 9104: 1,869) | 0.05-0.17 ms | commit tier hit, identical |
| arity refusal path (`snapshot` + `plan-for` + `prepared-arities` on the report value) | 682-1,801 ms | 261-329 ms | see justification below |
| of which snapshot on the speculative value | 287-1,378 ms | 157-188 ms | derives: no committed identity |
| of which `plan-for seon.id/id` | 252-390 ms | 63-69 ms | `contract-transaction` fix |
| `report-snapshot` for a report changing no read attribute | (n/a; parent derives, 287+ ms) | 0.01-0.72 ms | not wired in until the db.clj hunk lands |
| `newest-row-transaction` alone | 151-274 ms | 0.6-5 ms | |
| hook held path, 100k `current-snapshot` calls | 449 ms | 464 ms | +3%, same code path; pid 9104: 19.0 → 16.8 ms unarmed |
| live `transact!` arity refusal (parent code in the writer) | 816-1,440 ms | not measurable until the db.clj hunk lands | |
| probe run `measure/run` (branch, 6 refusal paths, 2 live refusals, commit, second branch) | 9.2-9.7 s total | | the parent's refusals are most of it |
| test copies, both copies, 20 tests each | 78.6 s total, ~39 s per copy | | over 10 s, filed (see issues) |

Every row over 1 s is justified or filed:

- **Parent rows over 1 s.** This is the defect this lane removes.
- **New refusal path, 261-329 ms.** This is under 1 s, but it is proportional
  to the whole program: prepared-symbols joins 474 functions' argument
  addresses on a speculative value that has no query cache. Once db.clj calls
  `report-snapshot`, a refusal whose report changes no read attribute costs
  0.01-0.72 ms plus `plan-for` (63-69 ms).
- **Probe run, 9.2-9.7 s.** The parent's refusal paths account for most of
  it.
- **Test copy run, 78.6 s.** The per-test `(projection)` helper rebuilds a
  full declaration projection: 55 calls, 18.8 s armed. Fixture acquisition
  under armed contracts accounts for the rest. Filed as
  `docs/seon/issues/call-preparation-test-rebuilds-a-projection-per-call.md`.

Cache hits and misses: every "new" row above is a memo hit except the
speculative ones. The first derivation on each probe branch was a miss:
111-136 ms warm-new at the branch head, then 0.1-0.15 ms.

## Hunks for other holders (not landed)

1. **`src/seon/db.clj` (free in the ledger, not mine): wire the refusal path to the report.**
   - add `(defonce ^:private call-preparation-report-snapshot (delay (requiring-resolve 'seon.call-preparation/report-snapshot)))`;
   - `arity-verdict` takes `report` after `projection`. Its snapshot becomes
     `(@call-preparation-report-snapshot report projection)` instead of
     `(@call-preparation-snapshot database projection)` (db.clj:3700);
   - the call site at db.clj:4047 becomes `(arity-verdict database projection report comparison)`;
   - `arity-mismatches-with` (db.clj:3753) runs on a whole database value and keeps `snapshot`, which is now memoized.
2. **`src/seon/sci/eval.clj` (held by wrapper-profiling): D3, delete the listener.**
   - Remove both `(call-preparation/watch! (get ctx call-preparation/carrier) connection projection)` forms
     (eval.clj:2699 and :2770) and their `when connection` wrappers.
   - `current-snapshot` stays the correctness boundary. Its behind-basis
     branch is now a memo hit for any commit that does not touch a read
     attribute, so the eager listener no longer buys anything, and today it
     derives on the writer thread.
   - In the same slice, `watch!` leaves `call_preparation.clj` (one mechanism).
     This lane can make that edit when eval.clj is released.

## Residue

- `plan-for` still treats a non-number `contract-transaction` as "no
  function" (nil plan). An index refusal is therefore silent there. This is
  pre-existing, and I did not widen the output contract; the case belongs to
  the owner of a plan-contract change.
- `snapshot-compile` catch (`derive-snapshot`, "this cluster's projection
  cannot compile its value schema"): the incoherent-row refusal names the key
  and the `ex-message`, but not the class, ex-data or cause chain. That
  refusal is a declared stored error schema (`:seon.db/attributes true`), so
  adding the Throwable needs a schema decision. It is listed, not changed.
- The speculative-value derivation (157-188 ms) stays proportional to the program for reports that do change a
  read attribute (a declaration write). An incremental derivation from `:db-before` would be the next step.

RESET NEEDED: no. Probe branches retired, and the one leaked probe connection
was released (`datahike.connections/*connections*`, verified absent). Copy
namespaces `tmp.arity-snapshot.*` remain loaded in default, pid 24492: they
are inert, and no production code references them.
