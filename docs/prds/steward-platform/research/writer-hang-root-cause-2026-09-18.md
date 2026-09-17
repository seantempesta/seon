---
type: research
status: open
created: 2026-09-18
tags: [datahike, writer, hang, performance]
---

# Writer waits: expensive publication and a lost completion

**The supplied historical evidence cannot establish a permanent writer wedge.** Its writer stack is incomplete. The
new isolated population completes: the large transaction spends **26,014.335 ms applying**, including **11,438.038 ms
in Seon's final validator**, then **7,254.573 ms committing**. Separately, a throwing Datahike listener reproducibly
strands an already committed transaction's promise while the writer continues. That is a source defect, not an
attribution to the historical timeout. Evidence and exact measurement boundaries follow.

## Scope and dependency ledger

Read AGENTS.md §§0–5 and `.agents/skills/datahike/SKILL.md` end to end; read the named stale-fixture and boot/load
investigations, reset-batch plan, and roadmap entry end to end, plus program-facts §1r and the working-edge 04:50Z
record. Applied the datahike, repl, data-oriented-clojure and clojure-testing skills. No production code changed.

Measurements use detached Seon `47f0bc9c10db9d34113cdfe9dc0001f9010f5ed4`, excluding the shared tree's concurrent
function/test edits. Source line citations below refer to that snapshot. Gitlinks: Datahike
`73afe78271a289861da236c5ac3457e64349653f`; Konserve `07377c27c8288b7484f0aa7b82e8158b415985be`. Path abbreviations in
citations:

| Name | Exact source |
|---|---|
| writer | `reference-code/datahike/src/datahike/writer.cljc` |
| transaction | `reference-code/datahike/src/datahike/db/transaction.cljc` |
| writing | `reference-code/datahike/src/datahike/writing.cljc` |
| connector | `reference-code/datahike/src/datahike/connector.cljc` |
| api/impl.cljc | `reference-code/datahike/src/datahike/api/impl.cljc` |
| tools.cljc | `reference-code/datahike/src/datahike/tools.cljc` |
| kc | `reference-code/konserve/src/konserve/core.cljc` |
| kd | `reference-code/konserve/src/konserve/impl/defaults.cljc` |
| kf | `reference-code/konserve/src/konserve/filestore.clj` |

These are the actual seams used by `src/seon/db.clj:3796` and `src/seon/cluster.clj:1630`, not inferred dependency
behavior.

## What the historical evidence establishes

Let **H** denote `tmp/orchestrator/post-reset-stale-fixtures-2-stdout.log`. I inspected every captured stack block and
its capture command. H contains filtered excerpts, **not the full dump requested in the assignment**.

| Evidence | Observation and limit |
|---|---|
| H:5992–6027, PID 63939 | `main` waits in `CompletableFuture` → `datahike.api.impl/transact`. Header CPU **61,194.85 ms**, elapsed **134.61 s**. Elapsed is thread age, not a measured park duration. This establishes an unanswered call, not why. |
| H:6214,6251–6267 | Capture used `rg -A 30 -B 4 …writer…`. Retained writer frames start at `AFn/applyTo`, end in `create_thread`; the thread header, state and active callee are missing. Adjacent idle `async-mixed-5/6` stacks are different threads. Cannot choose validator, lock, I/O or lost completion from this tail. |
| H:6474–6475 | Follow-up writer search returned no stack. Absence is not evidence the writer was idle. |
| H:13357–13400, PID 74121 | A different JVM's `main` is RUNNABLE in `canonical-value-string` → `inst?` protocol lookup; CPU **44,283.05 ms**, elapsed **96.86 s**. CPU work is observed on the caller, not the writer. No same-thread pair establishes a progress rate. |
| H:15952–15996, PID 81853 | Another JVM's `main` waits for fixture-base acquisition; no constructor/writer stack accompanies it. |
| `tmp/orchestrator/gate-results/predicate-revert-gate.log:625` | **30,007 ms** waiting against **30,000 ms**, explicitly “outcome is unknown.” No writer dump at this timeout. Retained `tmp/test-runs/run.puLgjn` was not operated or deleted. |

The [boot/load note](boot-and-load-sequence-2026-09-17.md) R4–R6 correctly locates an unbounded caller deref and
expensive canonicalization, but its “wedged” wording does not establish a deadlock. R5's **111–136 s** is reported
publication-monitor occupancy, a separate caller-level serialization (`boot-and-load-sequence-2026-09-17.md:221–245`).
Current acquisition uses `ReentrantLock.tryLock` and reports the holder; that bounds the waiter, not the holder's work
(`src/seon/cluster.clj:1761–1824`, landed `c8776fafe`).

The supplied **75,941.046542 → 41,644.180417 ms** is **whole publication-call time with a prebuilt manifest**, not
isolated validator time. The original measurement explicitly says so ([reset writer-cost
note](../../context-generation/research/reset-writer-cost-2026-09-17.md):60–77,125–134). Its **3,972 / 24,168**
validator-stack samples are samples, not milliseconds. The existing [validator
issue](../../../seon/issues/final-report-validation-runs-unbounded-on-the-writer-thread.md) contains a separate
complete validator stack and a later completing run; neither proves the state of H's PID 63939. Verdict for that
original wait: **unknown**.

## What runs where; callback and connection audit

1. **Caller preparation:** schema rows/projection, index compilation, submitted shape validation and encoding precede
   queue submission (`src/seon/cluster.clj:1598–1649,1676–1709`; `src/seon/db.clj:3774–3798`). Canonicalization is not
   intrinsically writer work. `dc63e6ebf` removed the old protocol lookup by handling collections before direct
   Date/Instant checks (`src/seon/schema.clj:625–655`).
2. **Serial application:** writer:131–148 calls `writing/transact!`; writing:872–890 invokes `core/with`. Transaction
   functions, datom application, persistent database finalization, then the final-report callback all occur before
   this operation returns (transaction:1218–1276). **Yes: Seon's final validator runs on the writer's processing
   thread.** A rejection leaves the old basis; current processing/fatal-error paths deliver failures and drain pending
   work (writer:147–225), unlike the listener path below.
3. **Seon final validation:** attempted scalar assertions; deletion obligations; owning ancestors from both
   before/after; complete component expansion; whole-root and component contracts; renderer targets; prepared call
   arities (`src/seon/db.clj:3574–3634`). Owner discovery seeks AVET separately for each component attribute and
   entity (`:3251–3262`); roots and children are both checked (`:3323–3337`). Existing caches are real (`:3583`,
   `:3242`), but arity checking still queries the whole final program for every nonempty affected set (`:3617`). The
   node-count bound is not a time guarantee (`:3236`).
4. **Commit:** a separate loop batches completed reports, calls `writing/commit!`, updates the connection, then
   resolves dispatch callbacks (writer:234–298). Index flush/serialization and immutable objects precede the mutable
   branch-head write (writing:478–535). This phase is distinct from validation, though both are inside the caller's
   wait.
5. **Notification/result:** another `go` awaits dispatch, invokes listeners, then delivers the public promise
   (writer:393–417). Neither listener latency nor listener exceptions belong to the serial validator call stack.

The validator closure captures the projection, **not a connection** (`src/seon/db.clj:3636–3650`). EAVT/AVET reads,
deletion, arity and render checks receive report database values (`:3144–3159,3259,3557–3572,3618`). Retention
transaction functions also receive before/after database values and return transaction forms, never a nested
`transact!` (`:3742–3755`). The probe observed **zero** calls to Seon's public `q`, `pull` or `transact!` while inside
the validator. This covers the measured successful population; source inspection also covers the refusal and component
branches. No self-queued write was found.

Even dereferencing a local streaming connection reads its atom, not the writer queue (connector:82–97). A nonstreaming
connection may read storage. Thus a connection read alone is not the hypothesized self-deadlock; a synchronous nested
write from a transaction function would be. Do not move authority to a possibly older connection value as an
optimization.

Inspected Seon listener registrations: call preparation reads `:db-after` and updates its cache
(`src/seon/call_preparation.clj:493–514`); wake reads the report and offers notifications with Throwable containment
(`src/seon/cluster/wake.clj:507–563`); schedule offers a kick (`src/seon/schedule.clj:727–730`); evaluation drive
probes `:db-after` and offers a result (`src/seon/eval/drive.clj:63–66`). None submits a nested write in its callback
body. This is a dated source audit, not a claim that arbitrary callbacks are safe; the dependency warns against
synchronous callback writes (`reference-code/datahike/src/datahike/api/specification.cljc:1148`).

## Isolated measurements

Canonical armed `bin/test-fast seon.writer-hang-probe`, real file store via
`test/seon/cluster/source_test.clj:84–110`, full canonical `source-manifest` via `test/seon/test_support.clj:226–246`,
actual `cluster/populate-source!`. The source-test activation helper is used; this is publication timing, **not
cluster boot, SCI acquisition or a cold gate**. Manifest building is outside the publication timer. Wrappers retain
the entering functions/contracts and add wall-clock timing; nested timings overlap. Both successful runs passed **1
test / 4 assertions / 0 failures / 0 errors**. The expected listener exception appears separately on the async
executor.

Raw evidence: `tmp/writer-hang-evidence/probe-1.log:10–13,28–33` (PID 11383), `probe.log:10–13,28–33` (PID 14353).
Actual run timestamps are **2026-09-17 UTC**; the note date follows the owner's requested 2026-09-18 filing date. This
host was contended: `host.txt:1–4` records **39.31 / 41.05 / 44.72** load averages at 20:27:39Z. These are wall times
on that host, not a throughput guarantee.

| Phase | First run ms | Detailed run ms |
|---|---:|---:|
| Entire publication call | 95,299.767 | 86,534.362 |
| All final validators, 9 callbacks | 13,100.801 | 16,994.679 |
| All transaction application, including validators | 27,113.539 | 34,738.387 |
| All commit calls, including storage | 8,244.651 | 8,572.733 |
| Konserve multi-assoc, within commit | 8,156.642 | 8,346.185 |
| Canonical schema-row construction, caller | 1,107.573 | 918.978 |
| Projection fingerprints, 8 calls | 179.406 | 230.570 |
| Submitted-data validation (`write-error`), caller | not instrumented | 6,259.065 |

Detailed population transaction, basis **536870917**: **1,304,168 attempted**, **419,043 effective datoms**, **32,669
affected entities**. Its application takes **26,014.335 ms**, containing **11,438.038 ms** final validation; commit
takes **7,254.573 ms**. Within validation: owned-value checks **9,683.007 ms**, arity checks **688.018 ms**, deletion
**237.255 ms**, renderer targets **41.173 ms**. Owned checks include **46,620** entity-value calls / **326.518 ms**
and **32,668** entity-validator calls / **650.491 ms**. The large residual within owned checks is traversal/owner
discovery/assembly and enclosing overhead; no subphase timer isolates each of those, so do not label it all Malli
time.

Application minus final validation is **14,576.297 ms**, including Datahike application **and Seon's retention
transaction functions**; it is not a pure Datahike CPU measurement. Publication's remaining time includes caller index
construction/projection work. The first run's complete `stack-5.txt` catches caller prevalidation; `stack-6.txt`
catches RUNNABLE `async-mixed-6` in `write-owned-values-error` under `validate-report`/`writer/create-thread`, while
`main` parks; `stack-7.txt` has `main` resumed in post-publication projection construction. Completion and these phase
transitions prove **slow, progressing work in this reproduction**, not an indefinitely wedged writer.

The detailed probe adds phase instrumentation, changing its own indexed source: the first population has **418,992**
effective datoms versus **419,043** later. These are two completing observations, not an optimization A/B result.

## The reproducible lost completion, and storage locks

After publication, the probe uses the same store's separate main connection, registers a throwing listener, waits for
its invocation, removes it, and commits another transaction. Both runs report `first-realized false`, `next-committed
true`, basis **536870912 → 536870914**. The exception escapes at writer:415; control never reaches delivery at :416.
This finite witness plus the source establishes the lost completion; it does not measure an infinite duration.
`merge-db!` has the same defect (:440–443). N3 already measured this in [its probe
index](../../sci-execution-runtime/research/n3-plan-2026-07-27.md):815–821. Recorded as [a dependency
issue](../../../seon/issues/a-datahike-listener-exception-strands-a-committed-transaction-promise.md).

Konserve's ordinary key locks are **per store object/key**, not global across JVMs or unrelated directories
(kc:151–176). Same-file blob locks may serialize readers/writers (`kd:262–340`; `kf:432,578`); OS lock acquisition
itself can block, so a retry-count cap does not bound each attempt (`kd:232–260`). However the Datahike file commit
uses ordered `multi-assoc`, which calls the batch backend without that ordinary key-lock wrapper (`kc:480–510`;
`kd:655–689`). The backend writes unique staged files, forces blobs, atomically renames, then forces the directory in
sequence (`kf:91–155`). Readers do not take a universal store lock against that batch. Disk contention is possible;
these dumps do not prove it caused the historical wait. No cross-JVM global fsync mutex exists in these paths.
Datahike's separate GC reachability gate is in-process and keyed by store ID
(`reference-code/datahike/src/datahike/gc_guard.cljc:48–75`; writing:447–455), not a lock joining independent scratch
stores. No Konserve change is justified by this evidence; removing forces would change durability, not fix validation.

## Exactly three source options, simplest first

1. **Recommendation — settle the committed result independently of listeners in Datahike. Small:** change the existing
   transaction and merge completion functions, delivering committed outcomes before notification and containing each
   callback's Throwable with an observable diagnostic. Guarantee: a thrown or stalled callback cannot suppress that
   already delivered result. Give up any assumed listener-finished-before-return ordering; blocking callbacks still
   consume shared executor capacity, so this is not arbitrary-listener isolation. Regression: throwing and
   latch-blocked callbacks, both APIs, result available before latch release, subsequent write succeeds, other
   listeners/fault observation survive. Extend the existing
   `reference-code/datahike/test/datahike/test/writer_error_test.clj:96,237,290`; recheck Seon's basis-derived call
   preparation and wake behavior. Fixes the proven lost completion, not population cost.
2. **Optimize Seon's existing admission work in place. Medium:** reuse the already carried compiled attribute plans in
   submitted-data validation (`src/seon/db.clj:3002–3118`), then reduce repeated component-owner seeks and derive a
   complete affected arity set at the final-report seam (`:3251–3262,3363–3415`). First prototype against the
   immutable reports; the timing does not prove an alternative algorithm faster. Guarantee: retain attempted
   assertions, sweeps, complete owned values and final-basis authority. Give up a hard transaction-latency promise.
   Regression: equivalence with the current full validator over canonical fixtures, invalid-then-repaired transaction
   functions, idempotent invalid assertions, required-ref sweeps, child-only edits, cycles/multiple owners,
   declaration/default changes and absent subjects; repeat phase measurements under armed contracts.
3. **Move candidate construction/validation off the serial writer only with an exact-basis admission protocol. Large,
   cross-owner:** hand Datahike a validated expanded candidate and its expected basis; writer admits that exact
   candidate or refuses staleness. Existing basis checks (writing:872–888) are grounding, not an implementation of
   this protocol. Guarantee: no stale prevalidation can authorize a different final database; never replay an
   effectful transaction function implicitly. Give up simplicity and cheap uncontended writes; conflicts require
   explicit rederivation. Regression: competing writers, stale candidates, expanded/swept invalid entities, aborts,
   crash boundaries and unchanged final-validator semantics. Merely moving the callback to another thread while the
   writer waits does not remove serialization.

## Bounds, verification boundary and reproduction

A write wait limit is **containment, not a source fix**. Datahike's synchronous API dereferences without a deadline
(`api/impl.cljc:44–46`); its bounded promise arity throws a wrapped timeout rather than returning the sentinel
(`tools.cljc:103–107`). Seon handles that explicitly and reports unknown outcome (`src/seon/db.clj:3798–3840`,
`eb8db3503`). It does not cancel application, commit, or callbacks, and excludes caller preparation. Retrying
unknown-outcome data requires establishing whether it committed or proving replay idempotent.

[Program-facts §1r](../plan/program-facts-are-the-runtime-prd-2026-09-17.md):518–542 rules root/system **no per-write
bound**, an enclosing lifecycle deadline, and bounded agent writes selected from transaction provenance. The measured
snapshot still takes the minimum global configured write bound (`src/seon/db.clj:3775–3787`), default **600,000 ms**
(`resources/seon/schemas/seon.config.db.edn:2`); provenance selection is not implemented there. Preserve the operation
watchdog and capture complete stacks when it fires; neither a larger timeout nor removal of it repairs a lost promise.

No default reset/restart, manual publication, foreign-session operation or cold gate. Initial probe-only mistakes were
an API spelling compile failure and a boolean `:seon.test/long` refused before population; corrected to the declared
string. Both successful runs used the canonical armed fast harness. Documentation hooks reported **33 existing
cross-document lint errors**, including stale citations in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md:226`. No foreign production failure blocked the
isolated measurement. The scratch store was released/deleted by the fixture, the JVMs exited, and the detached
worktree was removed. Raw evidence remains under `tmp/writer-hang-evidence/`. Cold/platform and default live proofs
belong to the orchestrator if a fix is selected.
Targeted Markdown validation passes for this note and both issue notes; path-limited `git diff --check` is clean.

Exact detailed probe follows. Place at `test/seon/writer_hang_probe.clj` in an isolated checkout of the recorded HEAD,
link the recorded `reference-code`, and run `bin/test-fast seon.writer-hang-probe`. It intentionally demonstrates the
current broken listener behavior; it is not a regression asserting the desired fix.

```clojure
(ns seon.writer-hang-probe
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [datahike.api :as d]
            [datahike.writing :as writing]
            [datahike.db.transaction :as tx]
            [konserve.core :as k]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as st]
            [seon.test-support :as ts]))
(def stats (atom {}))
(def events (atom []))
(def validator-threads (atom #{}))
(def nested (atom {}))
(defn record! [sym start]
  (let [ms (/ (- (System/nanoTime) start) 1e6)]
    (swap! stats update sym (fn [[n total]] [(inc (or n 0)) (+ (or total 0.0) ms)])) ))
(defn timed [v]
  (let [f @v sym (symbol v)]
    (fn [& args]
      (let [start (System/nanoTime)]
        (try
          (let [value (apply f args)]
            (when (= sym 'datahike.db.transaction/transact-tx-data)
              (swap! events conj {:probe/phase sym
                                 :probe/elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                                 :probe/basis (:max-tx (:db-after value))
                                 :probe/effective (count (:tx-data value))}))
            value)
          (finally (record! sym start)))))))
(defn asynchronous [v]
  (let [f @v sym (symbol v)]
    (fn [& args]
      (let [start (System/nanoTime) result (apply f args)]
        (if (satisfies? ap/ReadPort result)
          (let [out (a/promise-chan)]
            (a/take! result (fn [value]
                             (record! sym start)
                             (when (= sym 'datahike.writing/commit!)
                               (swap! events conj {:probe/phase sym
                                                  :probe/basis (:max-tx (first args))
                                                  :probe/elapsed-ms (/ (- (System/nanoTime) start) 1e6)}))
                             (if (nil? value) (a/close! out) (a/put! out value))))
            out)
          (do (record! sym start) result))))))
(defn validator [f]
  (fn [projection report]
    (let [thread (.threadId (Thread/currentThread)) start (System/nanoTime) entering @stats]
      (swap! validator-threads conj thread)
      (try (f projection report)
           (finally
             (swap! events conj
                    {:probe/phase :validator :probe/basis (:max-tx (:db-after report))
                     :probe/subphases (into {} (map (fn [[k [n ms]]]
                                                    (let [[n0 ms0] (get entering k [0 0.0])]
                                                      [k [(- n n0) (- ms ms0)]]))) @stats)
                     :probe/elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                     :probe/thread (.getName (Thread/currentThread))
                     :probe/attempted (count (:datahike/attempted-tx-data report))
                     :probe/effective (count (:tx-data report))
                     :probe/entities (count (set (map :e (concat (:datahike/attempted-tx-data report) (:tx-data report)))) )})
             (swap! validator-threads disj thread))))))
(defn observe [v]
  (let [f @v sym (symbol v)]
    (fn [& args]
      (when (@validator-threads (.threadId (Thread/currentThread)))
        (swap! nested update sym (fnil inc 0)))
      (apply f args))))
(deftest ^{:seon.test/long "Full population timing under armed contracts" :seon.test/long-ms 600000} population-and-listener
  (println "PROBE pid" (.pid (java.lang.ProcessHandle/current)))
  (let [manifest @ts/source-manifest
        sync-vars [#'tx/transact-tx-data #'db/write-error #'db/write-owned-values-error
                   #'db/write-entity-value #'db/write-entity-error
                   #'db/write-deletion-error #'db/write-render-target-error
                   #'db/arity-mismatches-with #'schema/canonical-schema-rows
                   #'schema/projection-fingerprint #'writing/db->stored]
        wraps (merge (into {} (map (juxt identity timed)) sync-vars)
                     (into {} (map (juxt identity asynchronous)) [#'writing/commit! #'k/multi-assoc])
                     (into {} (map (juxt identity observe)) [#'db/q #'db/pull #'db/transact!])
                     {#'db/write-report-error (validator @#'db/write-report-error)})]
    (with-redefs-fn wraps
      (fn []
        (#'st/with-store
         (fn [opened]
           (let [start (System/nanoTime)
                 result (#'st/publish opened (apply str (repeat 64 "d"))
                          'seon.cluster/populate-source! {:seon.fn/manifest manifest})
                 elapsed (/ (- (System/nanoTime) start) 1e6)]
             (println "PROBE publication-ms" elapsed)
             (println "PROBE stats" (pr-str @stats))
             (println "PROBE validators" (pr-str @events))
             (println "PROBE validator-seon-reentry" (pr-str @nested))
             (is (some? (:seon.source/commit-id result)))
             (let [conn (:seon.store/connection-object opened)
                   entered (promise) before (:max-tx @conn)]
               (d/listen conn ::throwing
                          (fn [_] (deliver entered true)
                            (throw (ex-info "writer-hang research listener failure" {}))))
               (let [pending (d/transact! conn {:tx-data []})]
                 (is (= true (deref entered 10000 :timeout)))
                 (d/unlisten conn ::throwing)
                 (let [next-result (deref (d/transact! conn {:tx-data []}) 10000 :timeout)]
                   (println "PROBE listener" (pr-str {:probe/first-realized (realized? pending)
                       :probe/before before :probe/after (:max-tx @conn)
                       :probe/next-committed (map? next-result)}))
                   (is (map? next-result))
                   (is (not (realized? pending)))))))))))))
```
