---
title: "Card FEEDBACK-LOOP: honest cost, latest numbers and the diff to main in every reply"
type: design
status: active
created: 2026-10-10
---

# FEEDBACK-LOOP

Design (chain stages 2–3) for plan §4 and §5 step 2 as one piece: every reply says what the request cost, the
latest numbers of each function, test and schema it touched are kept as rolling rows, and the reply and the agent's
context carry a compact diff of those rows against main. Then "which paths are slow" is one query and every change
shows its cost delta. Facts below were checked on default (pid 20363, archive ffe62e1c3, JVM mode, read-only) on
2026-10-10; sizes are `wc -l` at 4ad66021f. Lane fable-design.

## 1. Reuse and simplify: the inventory

| Piece | Lines | Verdict | Why |
|---|---:|---|---|
| `seon.profile` cells (`cell`, `timed`, `exited`): per-callable histogram, total, self, max, entries, throws | ~330 of 737 | **reuse as is** | the two clock reads per call are the whole per-function meter; self time already excludes callees |
| `seon.batch/sink`, `merge-sink!`, `calls`, `top`: per-world `[sym digest]` → calls, nanos, self nanos (+2 mark slots) | ~110 of 859 | **reuse as is** | this is the per-request, per-function number; test bodies already fold into it |
| `seon.profile/transaction-observation` + `seon.db/stamp-transaction`: the sink's delta since the world's previous transaction, written on that transaction | ~65 + 40 | **simplify: becomes the row writer** | it already runs on every transaction of a world with the delta in hand; its outputs `:seon.db.transaction/{ms,calls,hot,repeated}` (43 stamped tx on default) have no reader (`rg` finds only the producer) and are deleted |
| `seon.profile.measurement` (`significant-tx`, buckets, hysteresis, p50/p99) + schema + test + 2 call sites | 203 + 37 + 90 + ~10 | **delete; keep one idea** | per `[sym digest]` bucket rows (767 on default) whose only reader is `seon.health/definition-comparisons`; `hysteresis` (half a log2 bucket, 1.41×) moves to the row writer as the one "moved meaningfully" rule |
| `seon.health/definition-comparisons`, `regressions`' definition branch, schema `definition-comparison`/`definition-regression`, `health_test` 169–232, `health_storage_test` | ~50 + ~80 + 64 + 31 | **delete** | traffic-driven p50 per digest moves with callers, not code (COST-REG, fable-simplify); the diff to main replaces it |
| `seon.health/profile-unit` (`:seon.profile/over-bound-warning` over live cells) | ~25 | **simplify: read the rows** | cells reset at restart; rows survive it; one indexed scan instead of 3,059 cell reads |
| `seon.batch/request-cost`, `jvm-window`, `jvm-counters`, `arm-measures` | ~70 | **reuse; drop two labels** | `:seon.cost/arm-scope :evaluation-arms-only` and `:seon.cost/causal-attribution :unavailable` go when step cost lands; `jvm-window` stays as the outer truth |
| `seon.test/measured-body` + `:seon.test.member/{calls,alloc-bytes,run-ms,top}` (COST-REG slice 1; 290 members carry cost) | ~25 + 4 attrs | **reuse as is: this IS the per-test row** | the latest member of a test (`seon.test.runner/latest-results`, AVET on symbol, 3.9 ms for 14 tests) is its latest numbers on that branch; no second row |
| `seon.config.test.cost` dials | 32 | **simplify: keep `top`** | the other 7 configure code that was never written |
| `seon.profile/measure`, `calls-of` | ~75 | **reuse as is** | the proof meter (counts at one real size, R3); its `:seon.cost/jvm-window` stays |
| `seon.profile/begin`, `explain`, `explain-slow` (JVM-wide snapshots) | ~110 | **delete after S9** | task `seon.profile/slow-explanation-blames-the-agent-for-platform-time`: step cost + rows answer "what was slow" per request |
| `seon.cache/status` (Caffeine `recordStats`) | 25 | **reuse as is** | shown in health; no per-request share wanted |
| Hand-read `ThreadMXBean` in 11 test files (17 sites) | ~40 | **simplify → `seon.profile/measure`** | one meter |
| Reply render: `seon.mcp/eval-clj` prints `{:seon.repl/request cost}` through the one printer; `seon.turn/step` offers the reply with `:seon.cost/counts` before the step exits | — | **reuse the shape; move delivery** | the reply map gains keys; delivery moves after accounting (plan §4) |

Net: about 600 lines deleted, one stored-fact family with no reader removed, no new registry, runner, cache or dial.

## 2. The data flow

Where each number is produced → stored → shown:

1. **Step** (Flow proc thread, platform after S1): `seon.flow/var-process` brackets the step with
   `getCurrentThreadAllocatedBytes`/`getCurrentThreadCpuTime` → `:seon.cost/step` → in the reply (`:seon.repl/request`)
   and nowhere else; the JVM window beside it. Cost: two counter pairs, ~1 µs.
2. **Functions** (every armed call, `seon.profile/timed`): calls, inclusive ns, self ns into the world's sink →
   at the world's next transaction `seon.db/stamp-transaction` runs `seon.profile/transaction-observation`, which
   now returns row upserts `:seon.fn.cost/row` for the delta's definitions whose value moved → stored as noHistory
   rows on the world's branch → shown in the reply's `:seon.cost/diff`, the agent's current-state block, health's
   slow unit, and any query.
3. **Tests** (`seon.test/measured-body` on the body thread): the member row, as today → `latest-results` on the
   branch and on main → the diff's `:test` rows.
4. **Writer and commit**: `:write-fn-map` wrappers put `:datahike/processing` on each tx report; the fork puts
   `:datahike/commit` on finalized reports → `seon.db/transact!` sums them into the world → `:seon.cost/writer`,
   `:seon.cost/commit` in the reply.
5. **Schema validation**: (a) per contract, the armed wrapper's validation share = wrapper elapsed − body elapsed
   (two more clock reads, same thread) → `:seon.fn.cost/check-us` on the function's row; (b) per schema key, the
   writer's owning-value check and the pulled-read check (`seon.schema/projection-validator` call sites in `seon.db`)
   count into the world's `:seon.batch/checks` → `:seon.schema.cost/row` via the same stamp.
6. **Shared**: JVM window − attributed parts, plus GC CPU and JIT ms → `:seon.cost/shared`, labelled, never apportioned.
7. **Diff to main**: at reply time, for the request's subjects (definitions it defined, its top `top` executed
   definitions by self time, the tests its step ran, schema keys it checked), pull the row by identity on the
   branch db and on main's db (`:seon.turn.loop/cluster`'s connection) → `:seon.cost/diff` (≤ `top` rows).

## 3. The data model

**Reused keys**: `:seon.cost/{ms,stages,calls,reads,transactions,alloc-mb,cpu-ms,jvm-window,counts,request}`;
`:seon.test.member/{symbol,calls,alloc-bytes,run-ms,top,completed-tx}`; `:seon.batch/sink`, `:seon.batch/executed-key`;
`:seon.program/definition-digest`; `:seon.profile/scope` (`:seon.profile/host` | `:seon.profile/context`);
`:seon.config.test.cost/top` (10); `:seon.db/no-history?`; `:seon.cache/status`.

**New keys** (producer → consumers), schema file `resources/seon/schemas/seon.cost.edn` and new `seon.fn.cost.edn`,
`seon.schema.cost.edn`:

| Key | Producer | Consumers |
|---|---|---|
| `:seon.cost/step` `{alloc-mb cpu-ms}` | `seon.flow/var-process` | reply, `a-redefinitions-reply-accounts-for-the-window` |
| `:seon.cost/writer` `{alloc-mb cpu-ms transactions}` | `seon.db/transact!` (sum of `:datahike/processing`) | reply |
| `:seon.cost/commit` `{alloc-mb cpu-ms batch-size basis}` | fork commit loop → `seon.db/transact!` | reply |
| `:seon.cost/tests` `{alloc-mb cpu-ms count}` | `seon.turn/test-step` (sum of member cost) | reply |
| `:seon.cost/shared` `{alloc-mb cpu-ms gc-ms jit-ms}` | `seon.flow/var-process` | reply |
| `:seon.cost/diff` `[:seon.cost/diff-row]` | `seon.batch/cost-diff` | reply, current-state block |
| `:seon.cost/diff-row` `[subject measure branch main ratio & flags]` | `seon.batch/cost-diff` | the value printer (AI face) |
| `:seon.fn.cost/sym` identity, `:seon.fn.cost/digest`, `:seon.fn.cost/scope`, `:seon.fn.cost/calls`, `:seon.fn.cost/self-us`, `:seon.fn.cost/total-us`, `:seon.fn.cost/check-us`; map `:seon.fn.cost/row` (`:seon.data` partition) | `seon.profile/transaction-observation` via `seon.db/stamp-transaction` | `seon.batch/cost-diff`, `seon.health/profile-unit`, queries |
| `:seon.schema.cost/key` identity, `:seon.schema.cost/checks`, `:seon.schema.cost/check-us`; map `:seon.schema.cost/row` | same stamp, from `:seon.batch/checks` | `seon.batch/cost-diff`, queries |
| `:seon.batch/checks` (world member: `ConcurrentHashMap` key → `[checks nanos]`) | `seon.schema/checked` | `transaction-observation` |

**The latest-numbers rows.** One row per *symbol* (not per `[sym digest]`): the digest sits beside the numbers, so a
join on the name answers "did the code change" and "did the cost change" in one read. Every value attribute is
`:seon.db/no-history? true` (verified: two upserts via `d/with` on default leave one history datom, the current value;
the fork skips the temporal indexes in `datahike.db.transaction/with-datom-upsert` when `dbu/no-history?`). Values are
the last transaction window's per-call means (`self-us` = self ns / calls, `total-us` likewise, `calls` = calls in that
window), written only when a number moved by at least half a log2 bucket (1.41×, `hysteresis`) from the stored row, or
the digest changed, or no row exists; a window under `min-calls` (20) is skipped. A row is written inside the
transaction the world already writes: `stamp-transaction` conj's the upserts onto its `tx-data`; zero extra
transactions. Per test the row is the latest member (no new attribute). Per schema the row is as above.
Main's rows are the same attributes on main's branch; they come from main's own traffic (rows are data and never merge).

**The compact diff.** Subjects: definitions the request defined, its top 10 executed definitions by self time, tests
the step ran, schema keys it checked (≤ ~30 pulls per db at 0.013 ms each: under 1 ms). A row shows when the branch
value is ≥ 1.41× or ≤ 1/1.41 of main's, when main has no row (`:new`), or when the digest differs (`:changed`);
a `:seon.profile/context` scope row is flagged `:interpreted` (an interpreted body costs up to 280× its compiled self,
G4), so it is guidance, and the compiled merge check is where a regression is judged. Rendered as tuples
`[subject measure branch main ×ratio flags]`, about 15 tokens each; a typical reply carries 0–3 rows (≤ 50 tokens),
a redefinition's at most `top` (≤ 150 tokens).

**Slow paths are one query**: `(seon.db/q '[:find ?s ?us :where [?r :seon.fn.cost/self-us ?us] [(< 1000 ?us)] [?r :seon.fn.cost/sym ?s]] db)`.

## 4. The interface (stubs; contracts by schema name)

```clojure
;; seon.flow — the one accounting point (plan §4)
(defn var-process "…brackets the step with current-thread counters; delivers ::replies after accounting."
  {:malli/schema [:function [:=> [:cat ::step-var [:enum :io :compute] :map] ::launcher]
                            [:=> [:cat ::step-var [:enum :io :compute] :map :map] ::launcher]]} …)
(defn graph-executor {:malli/schema [:=> [:cat :seon.flow/graph-name] :seon.flow/executor]} …) ; Thread/ofPlatform

;; seon.profile — the row writer (replaces transaction-observation's stamps)
(defn transaction-observation "Row upserts for the world's delta since its previous transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :map]] :seon.store/transaction-data]} …)
(defn moved? {:malli/schema [:=> [:cat [:maybe :seon.fn.cost/row] :seon.fn.cost/row] :boolean]} …)

;; seon.schema — the one timed validator call
(defn checked "Validate value under schema-key, counting the check into the world."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :seon.schema/value] :boolean]} …)

;; seon.batch — cost assembly
(defn request-cost {:malli/schema [:=> [:cat :int [:map-of :keyword number?] [:maybe :seon.cost/counts]] :seon.cost/request]} …)
(defn cost-diff "Branch rows beside main's for the request's subjects."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.cost/subjects] [:vector :seon.cost/diff-row]]} …)

;; seon.db — writer cost on the report
(defn transact! … ) ; reads :datahike/processing and :datahike/commit off the report into the world
;; seon.cluster.store — :write-fn-map {'transact! measured 'merge! measured}
;; seon.context — the block
(defn current-state … ) ; adds :seon.context/cost (:seon.cost/diff for the agent's changed definitions)
```

## 5. The proofs (deftests in the subject's test namespace, red today, asserting data)

- `seon.request-cost-test/a-redefinitions-reply-accounts-for-the-window`: through `seon.mcp/call` on a disposable
  agent, step + writer + commit + tests ≥ 90% of the outer `jvm-counters` window for a redefinition (today 28 MB
  of 2,118 MB).
- `seon.flow-test/a-proc-thread-is-a-platform-thread`: `(not (.isVirtual (Thread/currentThread)))` inside a step.
- `seon.db-test/a-transaction-report-carries-its-processing-cost`: one `transact!` on a disposable branch → positive
  `:seon.cost/writer` bytes.
- `seon.request-cost-test/the-reply-is-offered-after-accounting`: the value on the reply port already has `:seon.cost/step`.
- `seon.profile-test/a-stamped-transaction-upserts-the-rows-that-moved`: an `are` table over a world's sink deltas:
  no row → written; same window again → nothing; 2× self → written; new digest → written with the digest.
- `seon.request-cost-test/a-reply-carries-its-diff-to-main`: redefine `tiny` on the branch to do 100× the work; the
  reply's `:seon.cost/diff` has a row for `tiny` with branch and main values and `:interpreted`; `tiny-test`'s row
  shows member `alloc-bytes` branch vs main.
- `seon.schema-test/a-checked-schema-has-a-cost-row`: after one write of an owning value, `:seon.schema.cost/checks` ≥ 1
  for its schema key; `seon.instrument-test/a-contracts-check-time-is-separate-from-its-body`: `check-us` > 0 and
  `total-us` ≥ `check-us`.
- `seon.health-test/the-slowest-definitions-come-from-the-rows`: a row with `self-us` over the bound appears in the
  profile unit; the three definition-regression tests are deleted.
- **Budget** (`is` on counts, in `a-stamped-transaction-upserts-the-rows-that-moved` and the window test): the same
  trivial form twice → `:seon.cost/transactions` equal (zero extra transactions), `:seon.profile/calls` of the
  request equal (counter reads are not armed calls), 0 rows written on the second; a redefinition of one function with
  one test writes ≤ K rows where K = definitions defined + tests run + `top` = 1 + 1 + 10 = 12 (movers are capped by
  the subjects the request names).

## 6. Slicing: stages of about fifteen minutes

Each is one change, one or two red proofs, independently landable. Sonnet specifies and proves (stages 3–4),
Opus implements platform code; ordinary code may go to DeepSeek-flash. Precondition: root restarts default onto HEAD
so cdce8a37e's `:seon.cost/jvm-window` shows (it is absent from default's replies today).

| # | Change | Depends on | Proof | Deletes | Better by |
|---|---|---|---|---|---|
| 1 | `graph-executor`: `Thread/ofVirtual` → `Thread/ofPlatform`, named by graph and proc (Opus, host) | — | `a-proc-thread-is-a-platform-thread` | — | every proc's counters exact; procs visible in thread dumps |
| 2 | Crude step cost: `var-process` records step-start counters in the proc world; `turn/step` reads the delta when it builds its reply → `:seon.cost/step` (Opus) | 1 | window test at ≥ 60% (crude) | `:seon.cost/arm-scope`, `:seon.cost/causal-attribution` | reply reads ~600 MB not 28 MB on a redefinition |
| 3 | Fix `arm-measures` in JVM mode (`:unmeasured :no-form` after evaluating a form; entries lack `:seon.batch/evaluation`) (Sonnet) | — | `a-jvm-reply-carries-its-request-cost` gains a row | — | JVM replies stop lying about "no form" |
| 4 | Rows: schema `seon.fn.cost.edn`; `transaction-observation` returns row upserts with `moved?`; `stamp-transaction` conj's them (Opus, schema → `--test`) | — | `a-stamped-transaction-upserts-the-rows-that-moved` + budget | `:seon.db.transaction/{ms,calls,hot,repeated}` | slow paths: one query; zero extra transactions |
| 5 | Delete `seon.profile.measurement` + 2 call sites, `definition-comparisons`, its schema and tests (Sonnet) | 4 | existing health boot tests stay green; `bin/seon check` | ~560 lines, 767 stale rows' producer | one fewer per-request upsert path; settle shorter (measure before/after with the same probe) |
| 6 | Diff to main in the reply: `seon.batch/cost-diff`, `:seon.cost/diff` in `:seon.repl/request` (Sonnet) | 4 | `a-reply-carries-its-diff-to-main` (fn rows) | — | every change shows its cost delta (the cycle works, crudely) |
| 7 | Tests in the diff: `latest-results` on both dbs for the step's tests; `:seon.cost/tests` sum (Sonnet) | 6 | the diff test's `tiny-test` row | — | a test that got heavier is visible beside main |
| 8 | Health slow unit reads rows; delete `begin`/`explain`/`explain-slow` and their 3 tests (Sonnet) | 4 | `the-slowest-definitions-come-from-the-rows` | ~110 lines + JVM-wide snapshots | slow paths survive restart; no more "blames the agent for platform time" |
| 9 | Diff in the agent's block: `current-state` adds `:seon.context/cost` (branch rows whose digest ≠ main's) (Sonnet) | 6 | `seon.context-test/the-block-shows-the-agents-cost-against-main` | — | an agent sees its branch's cost before its next form |
| 10 | Writer cost: `:write-fn-map` in `seon.cluster.store`; `transact!` folds `:datahike/processing` (Opus, host, publish/adopt on a live-store branch) | 2 | `a-transaction-report-carries-its-processing-cost` | the `:unavailable` label for writer work | writer work attributed per transaction |
| 11 | **Exception (~1 h, fork + fork test)**: commit runs `commit!` synchronously on one `async/thread`; `:datahike/commit` on every finalized report (Opus; upstream-shaped after `listen-commits!`) | 10 | fork writer tests + a batch-of-N attribution test | JFR as the only way to see commit cost | konserve commit writes (~53 MB/redefinition) attributed |
| 12 | `future-call` delta + `:seon.cost/shared` (GC via `getTotalGcCpuTime`, JIT ms) (Sonnet) | 2 | `seon.eval-alloc-test` row; window test flips to ≥ 90% | `:seon.cost/unmeasured` for step work | the headline proof green |
| 13 | Delivery after accounting: `turn/step` returns `::replies`; `var-process` assoc's cost and offers; the three `async/offer!` sites go (Opus) | 2, 12 | `the-reply-is-offered-after-accounting` | the early-offer race, the crude start-mark of stage 2 | reply cost exact to the step's end |
| 14 | Per-contract check share: wrap `f` handed to `m/-instrument` with two clock reads → cell body counter → `:seon.fn.cost/check-us` (Opus, arm path) | 4 | `a-contracts-check-time-is-separate-from-its-body` | — | expensive contracts visible per function |
| 15 | Per-schema-key rows: `seon.schema/checked` at the writer's and pulled reads' validator sites; `:seon.batch/checks`; `seon.schema.cost.edn`; `:schema` rows in the diff (Opus, schema) | 4, 6 | `a-checked-schema-has-a-cost-row` | — | the schema whose validation dominates a write is named |
| 16 | Delete 7 `seon.config.test.cost` dials; retire COST-REG slices 2–3 in place (Sonnet) | — | `bin/seon check` | 28 lines, 7 unread dials | fewer dials that configure nothing |
| 17 | Hand-read `ThreadMXBean` in 11 test files → `seon.profile/measure` (Sonnet) | — | the touched tests green | ~40 lines | one meter |

The cycle "change → honest cost and diff → query slow paths" works after stages 1, 2, 4 and 6 (about an hour of lane
time), and sharpens through 7–15.

## 7. What the agent sees

A redefinition of `tiny` on its branch, after the work (numbers from one-system warm run 3, 842 MB window):

```clojure
{:seon.repl/request
 #:seon.cost{:ms 612.4
             :step {:alloc-mb 614.0 :cpu-ms 333.0}
             :writer {:alloc-mb 0.3 :cpu-ms 2.2 :transactions 3}
             :commit {:alloc-mb 70.1 :cpu-ms 55.0 :batch-size 1}
             :tests {:alloc-mb 14.0 :cpu-ms 9.8 :count 1}
             :shared {:alloc-mb 143.6 :cpu-ms 615.0 :gc-ms 2 :jit-ms 257}
             :jvm-window {:alloc-mb 842.0 :cpu-ms 1015.0}
             :calls 1805 :reads 9 :transactions 3
             :stages {:custody 0.3 :fork 12.1 :read 0.2 :evaluate 27.7 :settle 38.8 :test-step 466.0 :print 2.0 :unattributed 65.3}
             :diff [[request.cost.measure/tiny :self-us 41 0.3 ×137 :changed :interpreted]
                    [request.cost.measure/tiny-test :alloc-bytes 14300000 900000 ×15.9]
                    [seon.fn/gate-set :self-us 2100 1400 ×1.5]]}}
```

About 190 tokens. Attributed: (614 + 0.3 + 70 + 14) / 842 = 83% here because this run's `shared` still holds listener
and cache-derivation work on `async-mixed`; stage 11's synchronous commit moves the konserve writes into `:commit`
(one-system closed 98.7% that way). The agent reads: its own function is the cost (interpreted, so guidance), its test
allocates 16× main's (cold cache or its loop), and `gate-set` moved 1.5× for reasons outside its change.

## 8. Questions for the owner

1. **Row identity: per symbol (digest beside) or per `[sym digest]` as today?** Options: (a) per symbol; (b) per
   `[sym digest]`. Recommend (a): the diff is a join on the name, a new digest is `:changed`, and 5,874 rows bound the table.
2. **"Rolling" value: last window with hysteresis, or an exponential mean?** Options: (a) last window's mean,
   written when it moves ≥ 1.41×; (b) EMA with α = 0.5. Recommend (a): no α dial, the hysteresis already smooths, and the
   stored value is always a real measurement.
3. **Show interpreted rows in the diff, or hide them?** Options: (a) show, flagged `:interpreted`; (b) hide context-scope
   rows; (c) store compiled rows only. Recommend (a): the agent sees its own cost; the merge check judges.
4. **Schema cost granularity.** Options: (a) per contract (`check-us`) plus per schema key at the writer and reads
   (stages 14–15), no fork; (b) per registry key inside Malli's validators (fork). Recommend (a); (b) only if a chain run
   shows a key-level question (a) cannot answer.
5. **The fork commit change (stage 11, the one hour-long stage).** Options: (a) land it now for exact commit cost;
   (b) keep commit as wall time labelled shared until the upstream rebase. Recommend (a): without it ~70 MB per
   redefinition stays unattributed and the 90% proof depends on the writer being idle.

## 9. Findings outside this assignment (for root's task files)

- JVM-mode replies answer `:seon.cost/unmeasured {:alloc-mb :no-form :cpu-ms :no-form}` after evaluating a form
  (three probes today, e.g. `(let [db …] {…})` → `:ms 22`, unmeasured `:no-form`): `seon.batch/arm-measures` gets no
  `:seon.batch/evaluation` from `jvm-run`'s entries. Home: `resources/seon/tasks/seon.batch.edn`; stage 3 fixes it.
- `:seon.db.transaction/{ms,calls,hot,repeated}` have no reader; their schema comment names a health query that does not
  exist. Stage 4 deletes them.
- Default runs ffe62e1c3 and lacks `:seon.cost/jvm-window` (cdce8a37e is on HEAD): the restart precondition.
