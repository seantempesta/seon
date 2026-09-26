---
type: landing
status: P1 and P4 loaded in pid 17853 (15e6a31c4); P1b committed 2f2f3f4af (RESET NEEDED)
created: 2026-09-26
lane: shrink-db-opus
tags: [shrink, performance, datahike, db]
---

# shrink-db-opus: P1 and P4

The plan is [shrink-plan-db-2026-09-26.md](../shrink-plan-db-2026-09-26.md) (99e33cc90). Root authorized P1 and P4, then a
stop. P2 and P3 wait for Astra's review of the derive-once design.

## Broken or unproven first

- **Neither slice is loaded yet.** The JVM runs a committed archive, so both need a restart.
- **P4's REPL equality proof did not run.** Default's prepl became unreachable after pid 12212 advertised: MCP reported
  "advertisement missing", and `bin/seon status` failed with a babashka classpath error in `seon.error.refusal`.
- **What proves P4 for now is static.** Datahike's `attr->properties` (`reference-code/datahike/src/datahike/db/utils.cljc:314-316`)
  maps every `:db/unique :db.unique/identity` attribute into `rschema`'s `:db.unique/identity` set (`:380-394`). Every DB
  shape answers `-rschema` from its origin (`db.cljc:336,514,583,648,714`). The probe to run once the JVM is back:
  `(= (seon.db/identity-attributes db) <old scan>)` on a current value, a speculative value that installs a new
  identity attribute, an as-of view and a history view.

## Slice P1: 6460d7bfb

**Change.** The arity gate's call edges are now two plain joins, one per caller family, instead of one `or` join.

- **Deleted.** `call-edges-query`, which was the `(or [?c :seon.fn/sym ?s] [?c :seon.test/sym ?s])` join over about 102k
  `:seon.fn/call-arities` datoms.
- **Replaced by.** `call-edges [query-fn database]`: two plain queries, unioned, stopping at the first read refusal.
- **Callers converted.** All three in one slice: the `arity-base` derivation, the whole-compare path of
  `report-arity-comparison`, and `arity-mismatches-with` (which goes through `seon.db/q`). Also the seeded-base
  regression in `db_test.clj`.

**Confirmed as Astra's #1.** The trace's hot frame, `db.clj:3845` (362 of 988 first-defn JFR samples, 870 MB
allocation), is `(set (d/q call-edges-query database))`, the query this slice replaces.

**REPL proof** (pid 12212, cold speculative values, each built with `d/with`):

| Query | ms | Edges | Equal to the `or` result |
|---|---:|---:|---|
| `or` (before) | 790 | 102,177 | — |
| two plain joins through `d/q` | 80 | 102,177 | true |
| two plain joins through `seon.db/q` | 186 | 102,177 | true |

An earlier session on pid 89341 measured 1,208 ms against 87 ms.

**First contracted defn on a fresh branch, before** (pid 12212, branch `shrink-db-p1-before3`, `seon.profile` growth):

| Call | ms |
|---|---:|
| `evaluate-source!` | 1,829 |
| `db/with` (via `turn/settled-value`) | 1,271 |
| `report-arity-comparison` | 1,220 |
| `arity-base` (2 calls) | 1,186 |
| `transact!` (5 calls) | 741 |

**After:** needs the restart. The expected drop is about 700–1,100 ms of `arity-base`, which is the or-query share.
Root: repeat the same probe on a fresh branch, i.e. `(defn f "Probe." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))`
through MCP SCI with a branch, reading `seon.profile/explain` around it.

**Size.** db.clj +24/−9 (the new function carries its contract), test +1/−1. Net +15 lines, and the arity code is 2–13×
faster cold.

## Slice P4: 15e6a31c4

**Change.** `identity-attributes` now reads `(:db.unique/identity (dbi/-rschema (schema-database db)))`, Datahike's own
set. It no longer scans all 2,518 schema entries on every call, which happened 5–6 times per checked write and on
every render that labels entities.

**The planned plan-lookup narrowing is dropped.** The pid 12212 measurements showed the 763 per-write plan lookups
were a one-time miss:

- The whole-schema plan map rebuilds in 5.4 ms, and only when the projection changes.
- Otherwise it is a cache hit.
- A warm one-datom `db/with` costs 2.1 ms.

The plan doc carries this correction.

**Also in this commit:** the db.clj hunk of `tmp/commit-floor-research-opus/patches/subject-marks.patch`, which marks
`write-owned-values-error` with `:seon/tasks #{"4bb788513862"}`.

**Size.** db.clj +6/−6, net 0.


## Where one defn's ~600 ms goes (pid 17853, 15e6a31c4)

**Root's after-probe.** P1 saved 100–170 ms per defn: 1,266 / 642 / 569 / 590 ms, against 1,433 / 693 / 706 / 661
before. The 700–1,100 ms I expected came from the pre-P1 JVM's cold `or` query on a first write; the steady defns now
pay much less arity work.

**Method.** I measured one contracted defn on a fresh branch (`shrink-db-settle-1`, the second defn there, so steady
state), using `seon.profile` cell deltas and a JFR recording bounded to one further defn. Of JFR's 30 execution
samples, 10 fell on the writer thread; there were 58 file-force events.

**Totals.** `evaluate-source!` 643 ms → `settle-batch!` 423 → `blob/with-publication!` 374 → `transact!` 365. Inside
`transact!`, `validate-write-report` took only **53 ms**. The rest, by JFR timeline (wall, one defn):

| Phase | ms | Owner | What |
|---|---:|---|---|
| Evaluation before the write | ~215 | sci/eval, schema, fn.analyzer | kondo analysis; `projection-with-function-contract` builds a candidate projection; the turn's reads |
| Writer: checked final report | ~130 | db.clj, but driven by schema | a **new projection per contracted defn**: `projection-from-rows` / `projection-build` / `declaration-content-key` on the speculative value, then every per-projection memo rebuilt (`write-attribute-plans`, `write-entity-schemas`); `arity-base` content key; the touched-caller `or` query (fixed in P1b) |
| Durable commit | ~90 | Datahike / konserve | 58 fsyncs of `.ksv.new` files, run in parallel |
| After the commit | ~150 | sci/eval, call-preparation | `install-evaluated-rows!` builds `projection-with-function-contract` again; `call-preparation/contract-arities` recomputes on the new projection |

**Ranked causes and fixes.**

1. **The contracted defn makes a new schema projection.** `:seon.fn/spec` is a projection input, and that costs about
   250–300 ms of the defn, spread across all three Seon phases above. Fix: take contracts out of the projection, which
   is the design under Astra review (`contracts-out-of-projection-design-2026-09-27.md`), or P3 at minimum. This is not
   db.clj alone.
2. **Durable commit I/O, about 90 ms.** Fix: commit-floor §1 (drive flushes), which is on the fork and pending adoption.
3. **Arity gate on a warm write, about 15–30 ms.** The touched-caller `or` query was 12 ms against 1.3 ms and is fixed
   in P1b (below). The content key now costs about 1 ms (trace: 4 calls, 4 ms). **P2 is not in the top causes, so I
   did not ship it.** The probe shows the validator's `:db-after` revision is a random uuid. The next write on the same
   connection sees that same uuid as its `:db-before` revision, while a fresh branch sees commit-derived revisions.
   Revision keys would therefore stop the cross-branch content hits the current key gives. P2's remaining value is
   memory (task 0caf995ddc22).
4. **Arity base miss on a branch's first write: 278 ms** (call-edges 67, comparison 57, grouping the rest). Fix: stop
   deriving the whole program, and read callers and callees from the indexes (task 0caf995ddc22). That needs a design.
5. **Final-owning-value validation: 40 ms over 4 writes here.** Branch agents are young, so this is small now; it grows
   with every turn (commit-floor §2, task 4bb788513862). It needs a design (see Next).

## Slice P1b: 2f2f3f4af

**Change.** `report-arity-comparison` read a report's touched callers with the `or` join, bound to their symbols.
`call-edges` gains a bound arity: one bound plain join per family.

**Measured** on a speculative value of default, one caller:

| Query | ms |
|---|---:|
| `or`, bound | 11.98 |
| two plain joins, bound | 1.34 |

Rows are identical: 40 for one caller, and 64 over a mixed set of function, test and absent symbols. The query runs
twice per defn, once in `db/with` and once in `transact!`.

**Checks.** db.clj +31/−23. clj-kondo shows 0 errors; `contracts-compile-test/check` over db.clj returns `[]`.

## P4 proven at the REPL (pid 17853)

`identity-attributes` equals the old scan on a current value, a speculative value that installs a new identity
attribute (the new attribute is included), an as-of view and a history view. It covers 49 identity attributes and
costs 8 µs against 120 µs.

**Branches retired:** `shrink-db-p1-before`, `-before2`, `-before3`, `root-p1-after` and `shrink-db-settle-1`.

## Red triage for db-test and owned-value-test (f8534c501)

**Before.** The store's latest runs had 13 of 94 db-test members red (listed below), plus owned-value
`a-transaction-entity-root-reaches-the-arity-gate`. A before run on the current archive was not possible:
`test-check` refused `:seon.test/program-mismatch`, then default restarted twice.

**Fixed:** 259bd59d9 and 4998d3fa1. Each test now follows the commit that changed its rule.

| Test | Cause | Change |
|---|---|---|
| `one-owner-digests-a-definition-with-its-stored-namespace` | 9b6df1ff4: the digest keeps only the resolver context the definition uses | The definition now uses the refer; also asserts that an unused refer leaves the digest unchanged |
| `a-namespace-row-change-re-derives-its-declarations-digests` | 9b6df1ff4 | `f` uses `clojure.set`; asserts that `g`, which uses nothing, keeps its digest |
| `durable-pull-digests-replay-without-retaining-read-results` | b96d6e684: stored evidence holds no request, result or digest | Digests asserted on retained evidence; stored evidence asserted to carry none |
| `wildcard-pull-digests-cover-empty-large-and-canonical-results` | b96d6e684 | Same; the stored-digest codec round trip is removed, because the stored value no longer exists |
| `every-database-value-reader-answers-for-all-four-view-shapes` | b96d6e684: since and history views have no revision, so only replay proves them current, and replay needs retained evidence | `current?` asserted on retained evidence |
| `an-entity-pull-is-checked-only-on-the-attributes-that-changed` | b96d6e684 (it counts replays) | Retained evidence |
| `a-pattern-over-an-uninstalled-attribute-witnesses-no-change` | `test-support/revision` writes only the attributes it changes, so `:seon.fn/sym` never moved | A new function row moves `:seon.fn/sym` |
| `a-write-naming-another-clusters-branch-is-refused-naming-both` | Since 07ed79283 the elided arity answers `::db/tx` / `::db/datoms`, never `:db-after` | Asserts `(int? (::db/tx elided))` |
| `an-unknown-read-operation-refuses-naming-the-attribute` | Member is top-level since 9bf22ecd9; the producer lacked `:seon.error/offending` | Producer adds offending (db.clj +1); the test reads the flat refusal |
| `render-declarations-require-their-final-function-row` | e6b559711: the final value's projection refuses a renderer with no contract first (`render-contract-refusal!`, probed) | Asserts the renderer the refusal names |
| `uncarried-read-reports-one-projection-fallback-per-call` | A value that declares its program derives its projection with nothing handed (probed on default: rows, no WARN) | Asserts that rule, plus a bare in-memory store with no declaration rows refusing once per call (probed: valid refusal, one WARN line per call) |
| owned-value `a-transaction-entity-root-reaches-the-arity-gate` | A function root requires `:seon.program/definition-digest` (probed refusal) | The fixture root carries a digest |

**Not fixed:**

- `nested-native-reports-admit-bounded-reference-identities`. Admission turns the report's db values into
  `#{::opaque ::projection-error}`, which comes from `seon.sci.admit/semantic-leaf`: the print node failed. A
  `d/with` report from default admits correctly at the REPL (identity maps with commit ids), so only the test world
  reproduces it. The owner is sci/admit or print (shrink-eval lane). It needs the failure's
  `::projection-error` message from a test run.
- `a-function-body-write-reads-its-basis-projection` is TIMEBOUND: one sample, 12.84 ms against an in-test 10 ms
  bound. It is a first `carried-projection` call on a speculative value, including builder resolution. The
  identity assertion passes. Root decides whether the bound or the path moves.

**After-run.** Pid 36107 runs b32060df4, which includes 4998d3fa1.

| Run | Executed | Result |
|---|---:|---|
| Root's db-test, run c83dfcafa26a | 91 | pass 599, fail 4, error 1; **2 red of 94** (was 13) |
| My db-test, run faa27a672207 | 2 (92 reused) | the same 2 reds |

The 2 remaining reds:

1. `nested-native-reports-admit-bounded-reference-identities`, open as described above. 931d02212 makes its
   assertions print the admitted value, so the next run shows the `::projection-error` message.
2. `transaction-success-and-refusal-shapes-are-declared` (NEW in this run). `seon.fn/contract-findings` refuses its
   own return value:

   > refused return value at [2778 :seon.fn.contract/form]: expected must be a parseable, EDN-readable Malli form,
   > got a lookup-ref vector

   The call comes from `call_preparation.clj:1066`. The producer and its contract are in fn.clj (perf-boot lane), not
   db.clj.

**owned-value-test was still red** after 4998d3fa1: a function root requires more keys than the digest (analyzed-source
digest, admission source, namespace, and an arity with order and arity text). 04fc0af02 copies a stored row's own facts
and first arity onto the transaction entity. Probed on pid 36107: the walk answers `#{:seon.fn/sym}`. It needs the next
restart.

## Checks

- `clj-kondo --lint src script test --cache false`: 0 errors, after each slice.
- `seon.contracts-compile-test/check` over db.clj and db_test.clj (P1): `[]` in 91 ms. For P4 it was unavailable
  (no live JVM), but no contract changed.

## Totals

| File | Before | After |
|---|---:|---:|
| `src/seon/db.clj` | 5,015 | 5,030 |
| `test/seon/db_test.clj` | 2,869 | 2,869 |

## RESET NEEDED

Root: restart default once, then run these:

- `bin/test-check default --test seon.db-test/a-write-seeds-its-final-values-arity-base`
- `bin/test-check default --test seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`
- `bin/test-check default --test seon.db-test/a-report-touching-no-function-reuses-the-committed-arity-comparison`
- `bin/test-check default --ns seon.owned-value-test`
- (reds) `bin/test-check default --ns seon.db-test` again after 259bd59d9 / 4998d3fa1: expect at most 2 reds (nested-native admission, the 10 ms timebound)
- `bin/test-check default --ns seon.test-support-test` (asserts `identity-attributes` and `populated-identity-attributes`)
- `bin/test-check default --ns seon.db-test` (P1b touches the report path of the arity gate)
- The first-defn after-probe (root's one-commit-defn-probe) on a fresh branch.

## Next (queued; root said stop here)

**Commit-floor §2** (task 4bb788513862). `write-owned-values-error` expands the runtime's whole owning value, every turn
in `:seon.runtime/turns`, costing about 37 µs per turn per form. Validating only the changed children is not a small edit:

- The root's entity schema validates the complete map, component vectors included.
- Partial validation needs a rule for what stands in for the unchanged children, for example validating the root
  with its component attributes elided plus each changed child against its component schema.
- That changes what "Validate final owning values" guarantees. The alternative, deleting the `runtime → turns` edge,
  is a schema and owner decision.

I recommend a short design with three priced options before any edit.

**Not filed.** There is no new task EDN for P1, because the defect is fixed in 6460d7bfb. Whole-program derivation of
the arity base on a first write remains, at about 80 ms after P1, and task `0caf995ddc22` (arity caches) and P2 cover it.
