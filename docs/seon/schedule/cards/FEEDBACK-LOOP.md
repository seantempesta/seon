---
title: "Card FEEDBACK-LOOP: honest cost, latest values and the diff to main in every reply"
type: design
status: active
created: 2026-10-10
---

# FEEDBACK-LOOP

Design (chain stages 2–3) for plan §4 and §5 step 2 as one piece: every reply says what the request cost, live and
at once; each function and test has a cost entity whose values are replaced in place and declared without history;
every reply and the agent's context compare the request's live numbers with main's stored values; a schema's
generation and validation cost is measured once per schema digest, when its contract is written. Then "which paths
are slow" is one query and every change shows its cost delta. Facts below were checked on default (pid 20363, archive
ffe62e1c3, JVM mode, read-only) on 2026-10-10; sizes are `wc -l` at 4ad66021f. Lane fable-design. Owner rulings
2026-10-10: entities, attributes and values (no "rows"); the agent sees its own request's live numbers immediately;
schema cost once per digest at contract-writing time; the fork commit change lands now.

## 1. Reuse and simplify: the inventory

| Piece | Lines | Verdict | Why |
|---|---:|---|---|
| `seon.profile` cells (`cell`, `timed`, `exited`): per-callable histogram, total, self, max, entries, throws | ~330 of 737 | **reuse as is** | the two clock reads per call are the whole per-function meter; self time already excludes callees |
| `seon.batch/sink`, `merge-sink!`, `calls`, `top`: per-world `[sym digest]` → calls, nanos, self nanos (+2 mark slots) | ~110 of 859 | **reuse as is: this is the live number** | each request's own calls, inclusive and self time per definition, available the instant the step ends; test bodies already fold into it |
| `seon.profile/transaction-observation` + `seon.db/stamp-transaction`: the sink's delta since the world's previous transaction, written on that transaction | ~65 + 40 | **simplify: becomes the cost-entity writer** | it already runs on every transaction of a world with the delta in hand; its outputs `:seon.db.transaction/{ms,calls,hot,repeated}` (43 stamped transactions on default) have no reader (`rg` finds only the producer) and are deleted |
| `seon.profile.measurement` (`significant-tx`, buckets, hysteresis, p50/p99) + schema + test + 2 call sites | 203 + 37 + 90 + ~10 | **delete; keep one idea** | per `[sym digest]` bucket entities (767 on default) whose only reader is `seon.health/definition-comparisons`; `hysteresis` (half a log2 bucket, 1.41×) moves to the entity writer as the one "moved meaningfully" rule for the stored value |
| `seon.health/definition-comparisons`, `regressions`' definition branch, schema `definition-comparison`/`definition-regression`, `health_test` 169–232, `health_storage_test` | ~50 + ~80 + 64 + 31 | **delete** | traffic-driven p50 per digest moves with callers, not code (COST-REG, fable-simplify); the live-against-main comparison replaces it |
| `seon.health/profile-unit` (`:seon.profile/over-bound-warning` over live cells) | ~25 | **simplify: read the cost entities** | cells reset at restart; entities survive it; one indexed scan instead of 3,059 cell reads |
| `seon.batch/request-cost`, `jvm-window`, `jvm-counters`, `arm-measures` | ~70 | **reuse; drop two labels** | `:seon.cost/arm-scope :evaluation-arms-only` and `:seon.cost/causal-attribution :unavailable` go when step cost lands; `jvm-window` stays as the outer truth |
| `seon.test/measured-body` + `:seon.test.member/{calls,alloc-bytes,run-ms,top}` (COST-REG slice 1; 290 members carry cost) | ~25 + 4 attrs | **reuse as is: the member IS the test's cost entity** | the latest member of a test (`seon.test.runner/latest-results`, AVET on symbol, 3.9 ms for 14 tests) holds its latest values on that branch; main's latest member is main's value; no second entity |
| `seon.schema` entity: `:seon.schema/key`, `:seon.program/definition-digest`, `:seon.schema/generatable?` | existing | **reuse: schema cost attaches here** | the schema entity already has the digest the cost is keyed by; `seon.test.accretion/generatable?` already asks Malli for the generator |
| `seon.config.test.cost` dials | 32 | **simplify: keep `top`** | the other 7 configure code that was never written |
| `seon.profile/measure`, `calls-of` | ~75 | **reuse as is** | the proof meter (counts at one real size, R3); its `:seon.cost/jvm-window` stays |
| `seon.profile/begin`, `explain`, `explain-slow` (JVM-wide snapshots) | ~110 | **delete after S8** | task `seon.profile/slow-explanation-blames-the-agent-for-platform-time`: step cost + the request's own sink answer "what was slow" per request |
| `seon.cache/status` (Caffeine `recordStats`) | 25 | **reuse as is** | shown in health; no per-request share wanted |
| Hand-read `ThreadMXBean` in 11 test files (17 sites) | ~40 | **simplify → `seon.profile/measure`** | one meter |
| Reply render: `seon.mcp/eval-clj` prints `{:seon.repl/request cost}` through the one printer; `seon.turn/step` offers the reply with `:seon.cost/counts` before the step exits | — | **reuse the shape; move delivery** | the reply map gains keys; delivery moves after accounting (plan §4) |

Net: about 600 lines deleted, one stored-fact family with no reader removed, no new registry, runner, cache or dial.

## 2. The data flow

The agent never waits for a stored value. What it sees in a reply is the request's own live measurement, from the
sink and counters of the step that just ran, compared with main's stored values. Storing is a side effect of the same
transaction; main's values change only when a merge lands, so the comparison is a handful of pulls by identity.

1. **Step** (Flow proc thread, platform after stage 1): `seon.flow/var-process` brackets the step with
   `getCurrentThreadAllocatedBytes`/`getCurrentThreadCpuTime` → `:seon.cost/step` → in the reply
   (`:seon.repl/request`). Cost: two counter pairs, ~1 µs.
2. **Functions** (every armed call, `seon.profile/bracket`): calls, inclusive ns, self ns into the world's sink, and
   again into the counter's cold half when the call is one of the definition's first `:seon.config.fn.cost/cold-calls`
   (1) calls in this process, by its profile cell's call order (`:seon.profile/ordinal`; no clock, no restart stamp;
   once past them the ordinal is only read). The reply reads the sink directly for the request's live per-function
   values. At the world's next transaction `seon.db/stamp-transaction` runs `seon.profile/transaction-observation`,
   which returns the cost-entity upserts (`:seon.fn.cost/*`, one per symbol and scope, a cold and a warm sample) for
   the samples that moved against the stored sample of their own kind → the branch's stored values, replaced in place
   → read by the diff (main's side), health's slow unit (warm) and any query. **Owner ruling 2026-10-10: false
   regressions are the bug.** A number shown or stored is like-for-like or not surfaced: warm beside warm, cold beside
   cold, the same scope (interpreted or compiled), and a ratio only when a warm sample holds `min-calls` calls on both
   sides; otherwise the comparison has no ratio and `:seon.cost.comparison/unknown` says why. After a restart the
   first calls are cold and compare with the stored cold sample, so they read as startup cost, never a regression.
3. **Tests** (`seon.test/measured-body` on the body thread): the member, as today → the reply reads the members the
   step just ran (live); main's `latest-results` for the same tests is the comparison.
4. **Writer and commit**: `:write-fn-map` wrappers put `:datahike/processing` on each tx report; the fork puts
   `:datahike/commit` on finalized reports → `seon.db/transact!` sums them into the world → `:seon.cost/writer`,
   `:seon.cost/commit` in the reply.
5. **Schemas**, at contract-writing time: when a form in a request registers or changes a schema or a function
   contract (the specify stage), the step generates `n` values from each new or changed schema (`malli.generator/sample`,
   fixed seed), validates them, and records `:seon.schema.cost/{gen-us,check-us,samples}` on the schema entity, once
   per `:seon.program/definition-digest`. Unchanged schemas are never re-measured. A generator or validator over its
   budget (dial `:seon.config.schema.cost/max-us`) is a finding in that same reply. Measured today: 20 samples of a
   three-key map generate in 1.2 ms and validate (first compile included) in 25 ms.
6. **Shared**: JVM window − attributed parts, plus GC CPU and JIT ms → `:seon.cost/shared`, labelled, never apportioned.
7. **Comparison with main**: at reply time, for the request's subjects (definitions it defined, its top `top` executed
   definitions by self time, the tests its step ran, the schemas it measured), the live value beside main's stored
   value, pulled by identity from main's db (`:seon.turn.loop/cluster`'s connection; 0.013 ms per pull measured) →
   `:seon.cost/diff` (≤ `top` entries).

## 3. The data model

**Reused**: `:seon.cost/{ms,stages,calls,reads,transactions,alloc-mb,cpu-ms,jvm-window,counts,request}`;
`:seon.test.member/{symbol,calls,alloc-bytes,run-ms,top,completed-tx}`; `:seon.batch/sink`, `:seon.batch/executed-key`;
`:seon.schema/key`, `:seon.program/definition-digest`, `:seon.schema/generatable?`; `:seon.profile/scope`
(`:seon.profile/host` | `:seon.profile/context`); `:seon.config.test.cost/top` (10); `:seon.db/no-history?`.

**New attributes** (producer → consumers); schema files `seon.cost.edn` (extended), new `seon.fn.cost.edn`,
`seon.schema.cost.edn`, `seon.config.schema.cost.edn`:

| Attribute | Producer | Consumers |
|---|---|---|
| `:seon.cost/step` `{alloc-mb cpu-ms}` | `seon.flow/var-process` | reply, `a-redefinitions-reply-accounts-for-the-window` |
| `:seon.cost/writer` `{alloc-mb cpu-ms transactions}` | `seon.db/transact!` (sum of `:datahike/processing`) | reply |
| `:seon.cost/commit` `{alloc-mb cpu-ms batch-size basis}` | fork commit loop → `seon.db/transact!` | reply |
| `:seon.cost/tests` `{alloc-mb cpu-ms count}` | `seon.turn/test-step` (sum of member cost) | reply |
| `:seon.cost/shared` `{alloc-mb cpu-ms gc-ms jit-ms}` | `seon.flow/var-process` | reply |
| `:seon.cost/diff` `[:seon.cost/comparison]` | `seon.batch/cost-diff` | reply, current-state block |
| `:seon.cost/comparison` `[subject measure live main ×ratio & flags]` | `seon.batch/cost-diff` | the value printer (AI face) |
| **Function cost entity**: `:seon.fn.cost/key` (identity, `[sym scope]`), a warm sample `:seon.fn.cost/warm-{digest,calls,self-us,total-us}` and a cold sample `:seon.fn.cost/cold-{digest,calls,self-us,total-us}`; map `:seon.fn.cost/entity` (`:seon.data` partition); the per-symbol `sym`/`digest`/`scope`/`calls`/`self-us`/`total-us` are retired (no producer) | `seon.profile/transaction-observation` via `seon.db/stamp-transaction` | `seon.batch/cost-diff` (main's side), `seon.health/profile-unit` (warm), queries |
| **Schema cost, on the schema entity**: `:seon.schema.cost/gen-us`, `:seon.schema.cost/check-us`, `:seon.schema.cost/samples`, `:seon.schema.cost/digest` | `seon.schema.cost/measure!` in the turn step | reply finding, `seon.batch/cost-diff`, queries |
| `:seon.config.schema.cost/samples` (20), `:seon.config.schema.cost/max-us` (10,000) | dials | `seon.schema.cost/measure!` |

**The cost entities.** One entity per *symbol and scope* (`:seon.fn.cost/key`, a Datahike tuple identity), each of
its two samples (cold, warm) carrying the definition digest beside its values, so a read on the key answers "did the
code change" and "did the cost change" at once, and an interpreted value never meets a compiled one. Each sample is
replaced only from its own kind: a cold value never overwrites the warm one nor the reverse. Every value attribute is `:seon.db/no-history? true`
(verified: two upserts via `d/with` on default leave one history datom, the current value; the fork skips the temporal
indexes in `datahike.db.transaction/with-datom-upsert` when `dbu/no-history?`). Values are per-call means of the
request's own work (`self-us` = self ns / calls); a stored value is replaced only when the live value differs from it
by at least half a log2 bucket (1.41×, `hysteresis`), the digest changed, or none is stored; a warm sample of fewer
than `min-calls` (20) warm calls, or a sample with less than `min-total-us` (1,000 µs) of self time in that work
(calls × self-us), stores nothing, and the
stored set is the `top` by that total self time, ties by symbol (`seon.profile/selected`): ranking by calls filled it
with sub-microsecond predicates. This rule decides only when the branch writes; it never delays or alters what the
agent sees. The upserts ride the transaction the world already writes: `stamp-transaction` appends
`[:db.fn/call #'seon.db/cost-upserts sink]` after the request's data, so the writer decides on its mid-transaction
value, where a function defined and called in the same request (counted before its digest was derived, armed at its
write by `seon.sci.arm/arm!`) resolves its digest from the row that transaction writes: zero extra transactions. A test's cost entity is its latest member; a schema's cost lives on the schema entity. Main's values are
the same attributes on main, from main's own traffic (cost entities are data and never merge as program).

**The comparison.** Subjects: definitions the request defined, its top 10 executed definitions by self time, tests the
step ran, schemas it measured (≤ ~30 pulls on main: under 1 ms). If it is unchanged it is unchanged: only (a) definitions whose digest differs from main's or that main lacks (`:changed`, `:new`) and (b) tests whose reach differs from main's latest member's (or main has none) are entries, plus a schema over budget
(`:over-budget`); a `:seon.profile/context`-scope function is flagged `:interpreted` and compared only with main's
interpreted sample of it (an interpreted body costs up to 280× its compiled self, G4: never a ratio across scopes);
the compiled merge check is where a compiled regression is judged. Each function entry compares one sample kind
(`:self-us` warm, `:cold-self-us` cold) with main's of the same kind; a ratio needs `min-calls` warm calls on both
sides, else `:unknown :few-calls`. A test entry is shown only when its member's reach digest differs from main's
latest member's (or main has none), and never with a ratio: a member is one run of unknown warmth, and on a branch
its changed reach runs interpreted where main's ran compiled (`:unknown :single-run`). Each
entry is a tuple of about 15 tokens; a typical reply carries 0–3 (≤ 50 tokens), a redefinition's at most `top`.

**Slow paths are one query**:
`(seon.db/q '[:find ?k ?us :where [?e :seon.fn.cost/warm-self-us ?us] [(< 1000 ?us)] [?e :seon.fn.cost/key ?k]] db)`;
slow to start: the same over `:seon.fn.cost/cold-self-us`;
expensive schemas: `[?e :seon.schema.cost/check-us ?us] [(< 10000 ?us)] [?e :seon.schema/key ?k]`.

## 4. The interface (stubs; contracts by schema name)

```clojure
;; seon.flow — the one accounting point (plan §4)
(defn var-process "…brackets the step with current-thread counters; delivers ::replies after accounting."
  {:malli/schema [:function [:=> [:cat ::step-var [:enum :io :compute] :map] ::launcher]
                            [:=> [:cat ::step-var [:enum :io :compute] :map :map] ::launcher]]} …)
(defn graph-executor {:malli/schema [:=> [:cat :seon.flow/graph-name] :seon.flow/executor]} …) ; Thread/ofPlatform

;; seon.profile — the cost-entity writer (replaces transaction-observation's stamps)
(defn transaction-observation "Cost-entity upserts for the world's work since its previous transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :map]] :seon.store/transaction-data]} …)
(defn moved? {:malli/schema [:=> [:cat [:maybe :seon.fn.cost/entity] :seon.fn.cost/entity] :boolean]} …)
(defn live-costs "The request's own per-definition values from its sink: what the reply shows."
  {:malli/schema [:=> [:cat :seon.batch/sink :seon.config.test.cost/top] [:vector :seon.fn.cost/entity]]} …)

;; seon.schema.cost — once per schema digest, at contract-writing time
(defn measure! "Generate samples from each schema, validate them, record cost on the schema entity; findings over budget."
  {:malli/schema [:=> [:cat :seon.db/connection [:set :seon.schema/key] :seon.config.schema.cost/samples :seon.config.schema.cost/max-us]
                  [:map [:seon.store/transaction-data :seon.store/transaction-data] [:seon.cost/findings [:vector :seon.cost/comparison]]]]} …)

;; seon.batch — cost assembly
(defn request-cost {:malli/schema [:=> [:cat :int [:map-of :keyword number?] [:maybe :seon.cost/counts]] :seon.cost/request]} …)
(defn cost-diff "Live values beside main's stored values for the request's subjects."
  {:malli/schema [:=> [:cat :seon.cost/live :seon.db/database-value :seon.cost/subjects] [:vector :seon.cost/comparison]]} …)

;; seon.db — writer cost on the report
(defn transact! … ) ; reads :datahike/processing and :datahike/commit off the report into the world
;; seon.cluster.store — :write-fn-map {'transact! measured 'merge! measured}
;; seon.context — the block
(defn current-state … ) ; adds :seon.context/cost (the branch's stored values against main for the agent's changed definitions)
```

## 5. The proofs (deftests in the subject's test namespace, red today, asserting data)

- `seon.request-cost-test/a-redefinitions-reply-accounts-for-the-window`: through `seon.mcp/call` on a disposable
  agent, step + writer + commit + tests ≥ 90% of the outer `jvm-counters` window for a redefinition (today 28 MB of
  2,118 MB).
- `seon.flow-test/a-proc-thread-is-a-platform-thread`: `(not (.isVirtual (Thread/currentThread)))` inside a step.
- `seon.db-test/a-transaction-report-carries-its-processing-cost`: one `transact!` on a disposable branch → positive
  `:seon.cost/writer` bytes.
- `seon.request-cost-test/the-reply-is-offered-after-accounting`: the value on the reply port already has `:seon.cost/step`.
- `seon.request-cost-test/a-reply-shows-its-live-cost-against-main-at-once`: redefine `tiny` on the branch to do 100×
  the work; the very same reply's `:seon.cost/diff` has an entry for `tiny` with the live value, main's value and
  `:interpreted`; `tiny-test`'s entry shows member `alloc-bytes` live vs main. Nothing stored is read for the live side.
- `seon.profile-test/a-stamped-transaction-replaces-the-values-that-moved`: an `are` table over a world's sink: no
  entity → written; same work again → nothing; 2× self → replaced; new digest → replaced with the digest; the stored
  value's history holds one datom.
- `seon.schema.cost-test/a-new-schema-is-measured-once-per-digest`: registering a schema records `gen-us`, `check-us`,
  `samples` on its entity; the same form again records nothing; a changed form records again; a schema whose
  validator exceeds `max-us` (a `:fn` that sleeps) is an `:over-budget` entry in that reply.
- `seon.health-test/the-slowest-definitions-come-from-the-cost-entities`: an entity with `self-us` over the bound
  appears in the profile unit; the three definition-regression tests are deleted.
- **Budget** (`is` on counts): the same trivial form twice → `:seon.cost/transactions` equal (zero extra
  transactions), `:seon.profile/calls` of the request equal (counter reads are not armed calls), 0 entities written on
  the second; a redefinition of one function with one test writes ≤ K entities where K = definitions defined + tests
  run + `top` = 1 + 1 + 10 = 12; a request registering one schema measures exactly one.

## 6. Slicing: stages of about fifteen minutes

Each is one change, one or two red proofs, independently landable. Sonnet specifies and proves (stages 3–4), Opus
implements platform code; ordinary code may go to DeepSeek-flash. Precondition: root restarts default onto HEAD so
cdce8a37e's `:seon.cost/jvm-window` shows (absent from default's replies today).

| # | Change | Depends on | Proof | Deletes | Better by |
|---|---|---|---|---|---|
| 1 | `graph-executor`: `Thread/ofVirtual` → `Thread/ofPlatform`, named by graph and proc (Opus, host) | — | `a-proc-thread-is-a-platform-thread` | — | every proc's counters exact; procs visible in thread dumps |
| 2 | Crude step cost: `var-process` records step-start counters in the proc world; `turn/step` reads the delta when it builds its reply → `:seon.cost/step` (Opus) | 1 | window test at ≥ 60% (crude) | `:seon.cost/arm-scope`, `:seon.cost/causal-attribution` | reply reads ~600 MB not 28 MB on a redefinition |
| 3 | Fix `arm-measures` in JVM mode (`:unmeasured :no-form` after evaluating a form; `jvm-run`'s entries lack `:seon.batch/evaluation`) (Sonnet) | — | `a-jvm-reply-carries-its-request-cost` gains a row | — | JVM replies stop saying "no form" |
| 4 | Live comparison in the reply, functions only: `seon.profile/live-costs` from the request's sink, `seon.batch/cost-diff` against main's stored values (none yet → every entry `:new`), `:seon.cost/diff` in `:seon.repl/request` (Sonnet) | — | `a-reply-shows-its-live-cost-against-main-at-once` (function entries) | — | the agent sees its own request's per-function cost immediately (the cycle works, crudely) |
| 5 | Cost entities: schema `seon.fn.cost.edn`; `transaction-observation` returns upserts with `moved?`; `stamp-transaction` conj's them (Opus, schema → `--test`) | — | `a-stamped-transaction-replaces-the-values-that-moved` + budget | `:seon.db.transaction/{ms,calls,hot,repeated}` | slow paths: one query; main's side of the comparison fills as main runs; zero extra transactions |
| 6 | Delete `seon.profile.measurement` + 2 call sites, `definition-comparisons`, its schema and tests (Sonnet) | 5 | existing health boot tests stay green; `bin/seon check` | ~560 lines, 767 stale entities' producer | one fewer per-request upsert path; settle shorter (same probe before/after) |
| 7 | Tests in the comparison: the step's members (live) beside main's `latest-results`; `:seon.cost/tests` sum (Sonnet) | 4 | the comparison test's `tiny-test` entry | — | a test that got heavier is visible beside main at once |
| 8 | Health slow unit reads cost entities; delete `begin`/`explain`/`explain-slow` and their 3 tests (Sonnet) | 5 | `the-slowest-definitions-come-from-the-cost-entities` | ~110 lines + JVM-wide snapshots | slow paths survive restart; no more "blames the agent for platform time" |
| 9 | Comparison in the agent's block: `current-state` adds `:seon.context/cost` (the branch's stored values whose digest ≠ main's) (Sonnet) | 4, 5 | `seon.context-test/the-block-shows-the-agents-cost-against-main` | — | an agent sees its branch's cost before its next form |
| 10 | Writer cost: `:write-fn-map` in `seon.cluster.store`; `transact!` folds `:datahike/processing` (Opus, host, publish/adopt on a live-store branch) | 2 | `a-transaction-report-carries-its-processing-cost` | the `:unavailable` label for writer work | writer work attributed per transaction |
| 11 | **Exception (~1 h, fork + fork test; owner: land now)**: commit runs `commit!` synchronously on one `async/thread`; `:datahike/commit` on every finalized report (Opus; upstream-shaped after `listen-commits!`) | 10 | fork writer tests + a batch-of-N attribution test | JFR as the only way to see commit cost | konserve commit writes (~53 MB/redefinition) attributed |
| 12 | `future-call` delta + `:seon.cost/shared` (GC via `getTotalGcCpuTime`, JIT ms) (Sonnet) | 2 | `seon.eval-alloc-test` row; window test flips to ≥ 90% | `:seon.cost/unmeasured` for step work | the headline proof green |
| 13 | Reduced (root, 2026-10-10): replies stay offered at the end of `seon.turn/step`, which reads the step's cost last before the offer; only two wake offers and the next-state build (microseconds) follow it, so no restructuring into Flow outputs | 2 | `the-reply-is-offered-after-accounting` | — | the reply's `:seon.cost/step` is the step's cost to within microseconds |
| 14 | Schema cost, measure: `seon.schema.cost/measure!` (sample, validate, time; `seon.schema.cost.edn` + two dials); called from the turn step for the request's new or changed schema digests; upserts ride the settle transaction (Opus, schema → `--test`) | 5 | `a-new-schema-is-measured-once-per-digest` (first three rows) | — | every schema's generation and validation cost known at the moment it is written, never re-measured |
| 15 | Schema cost, shown: `:over-budget` findings and `:schema` entries in `:seon.cost/diff`; contracts measured through their input/output schemas (Sonnet) | 4, 14 | the fourth row (`:over-budget` in the reply) | — | an expensive generator or validator is flagged in the reply that wrote it |
| 16 | Delete 7 `seon.config.test.cost` dials; retire COST-REG slices 2–3 in place (Sonnet) | — | `bin/seon check` | 28 lines, 7 unread dials | fewer dials that configure nothing |
| 17 | Hand-read `ThreadMXBean` in 11 test files → `seon.profile/measure` (Sonnet) | — | the touched tests green | ~40 lines | one meter |

The cycle "change → see live cost against main → query slow paths" works after stages 1, 2, 4 and 5 (about an hour of
lane time) and sharpens through 7–15.

## 7. What the agent sees

A redefinition of `tiny` on its branch, after the work (numbers from one-system warm run 3, 842 MB window). The
`:diff` values are this request's live measurements against main's stored values; nothing waited to be stored:

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
             :diff [[request.cost.measure/tiny :cold-self-us 41 :new :interpreted]
                    [request.cost.measure/tiny-test :alloc-bytes 14300000 900000 :single-run :changed]]}}
```

About 190 tokens. A request that registers a schema with a slow validator adds one entry,
`[:my.ns/thing :check-us 48000 nil :new :over-budget]`, about 20 tokens. Attributed here:
(614 + 0.3 + 70 + 14) / 842 = 83% because this run's `shared` still holds listener and cache-derivation work on
`async-mixed`; stage 11's synchronous commit moves the konserve writes into `:commit` (one-system closed 98.7% that
way). The agent reads: its own function's first call (cold, interpreted; main has no interpreted sample, so no ratio) and the
allocation of the test reaching it, beside main's, without a ratio (one run of another reach). `gate-set`, unchanged,
is no entry.

## 8. Questions for the owner

Answered 2026-10-10 and folded in above: one entity per symbol with the digest beside it; the agent sees live numbers
at once and the 1.41× rule only gates the branch's stored value; schema cost once per digest at contract-writing time
(generate, validate, flag over budget), no per-call metering; the fork commit change lands now. Remaining:

1. **Interpreted entries**: show flagged `:interpreted` (recommended), hide context-scope functions, or store compiled
   values only. Recommend show: the agent sees its own cost; the merge check judges.
2. **Schema sample size and budget**: `samples` 20 and `max-us` 10,000 as shipped defaults (recommended; 20 samples of
   a small map measured at 1.2 ms generate, 25 ms first validation on default) or stricter.

## 9. Findings outside this assignment (for root's task files)

- JVM-mode replies answer `:seon.cost/unmeasured {:alloc-mb :no-form :cpu-ms :no-form}` after evaluating a form
  (three probes today): `seon.batch/arm-measures` gets no `:seon.batch/evaluation` from `jvm-run`'s entries. Home:
  `resources/seon/tasks/seon.batch.edn`; stage 3 fixes it.
- `:seon.db.transaction/{ms,calls,hot,repeated}` have no reader; their schema comment names a health query that does not
  exist. Stage 5 deletes them.
- Default runs ffe62e1c3 and lacks `:seon.cost/jvm-window` (cdce8a37e is on HEAD): the restart precondition.
