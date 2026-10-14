# Blind Debugging Report: Run 18 Prove Step Live-Lock (F53)

Lane: `debug-flash-high`  
Target Commit: `0c684db75^` (parent of `0c684db75`)  

---

## 1. Root Cause

### Exact Function and Clause

The function that keeps answering `:prove` is **`seon.turn/next-agent-work`** (around line 2308 in `src/seon/turn.clj`):

```clojure
(and (string? held) (attempt-running? db held) (not (proof-run-recorded? db agent-id)))
{:seon.turn.work/situation :prove :seon.agent/id agent-id}
```

The exact condition that keeps holding is:
```clojure
(not (proof-run-recorded? db agent-id))
```

This condition delegates to **`seon.turn/proof-run-recorded?`**, which calls **`seon.turn/first-proof-run-t`** (lines 5242–5263 in `src/seon/turn.clj`):

```clojure
(defn- first-proof-run-t
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int :seon.error/value]]}
  [database agent-id]
  (let [proofs (step-tests database agent-id)]
    (cond
      (:seon.error/at proofs) proofs
      (empty? proofs)
      (db/q '[:find (max ?attempt) . :in $ ?id
              :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]]
            database agent-id)
      :else
      (db/q '[:find (min ?t) . :in $ ?id [?proof ...]
              :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]
              (not-join [?g ?attempt] [?b :seon.task/agent ?g] [?b :seon.task/assigned _ ?later] [(> ?later ?attempt)])
              [?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]]
            database agent-id (vec proofs)))))
```

### Why the Condition Never Becomes True

1. **Product tests as step proofs:** Under `seon.turn/step-tests`, a `:step :prove` task judges its proofs by:
   ```clojure
   (into proofs (filter #(seq (db/datoms database :avet :seon.test/sym %))) (:seon.task/product owed))
   ```
   At fork, neither product test existed on the branch, so `proofs` was empty `#{}`. The `(empty? proofs)` branch returned `(max ?attempt)`, and `(proof-run-recorded? db agent-id)` returned `true`, allowing calls 1–4 to proceed.
2. **Product test created via eval:** On call 4, the agent evaluated a form that defined the table `deftest`. The branch now held an entity for `'seon.render.block-test/surface-id-escapes-...`. Consequently, `step-tests` became `#{table}` (non-empty).
3. **Post-eval test step runs by reached changes, not identities:** When call 4 finished evaluating, `seon.turn/step` invoked `test-step` -> `run-reaching`. Because the form changed code reach, it ran the newly reaching test `table`. In `seon.test/run-request` and `admit-run`, runs triggered by `:seon.test/changed` record member entities (`:seon.test.member/symbol` on `:seon.test.run/members`), but **do not assert `:seon.test.run/identities`** on `?run` (only `:named` requests with explicit `:seon.test/identities` assert that optional attribute).
4. **`first-proof-run-t` strictly requires `:seon.test.run/identities`:** With `step-tests` now returning `#{table}`, `first-proof-run-t` executed its `:else` branch, querying for:
   ```clojure
   [?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]
   ```
   Since the post-eval test run did not transact `:seon.test.run/identities`, this query answered `nil`.
5. **`prove-turn` reuses the existing result without transacting:** When `next-agent-work` derived `:prove`, the turn proc ran `prove-turn`, which called `run-proofs` with `{:seon.test/policy :named :seon.test/identities #{table}}`. However, `seon.test/select` saw that `table` was already executed on this branch with an identical reach digest. Under Seon's test reuse law, it marked `table` as `:seon.test/unchanged`, removed it from reasons, and admitted zero batches. `run-request` returned the reused result with no execution and **no database transaction**.
6. **Permanent contradiction:** No datom `[?run :seon.test.run/identities table]` was ever committed after `?attempt`. `first-proof-run-t` answered `nil`, `proof-run-recorded?` remained `false`, and `next-agent-work` answered `:prove` on every turn pass.

---

## 2. Why the Loop Does Not End or Slow Down

The turn proc executed in an unthrottled, zero-latency tight loop across `next-agent-work` -> `prove-turn` -> `step`:

1. **Sub-millisecond execution:** In `prove-turn`, `run-proofs` reused the cached test result immediately without spawning test processes, running code, or committing transactions. `prove-turn` completed instantly and returned `(report :closed 0)`.
2. **No post-turn test step:** `prove-turn` evaluates no user code form, so `step-due?` evaluated to `false`. No secondary test step was executed.
3. **No task termination:** In `seon.turn/step`, `end-task!` called `task-end`. For a Prove step, `seon.context/step-status` requires all declared product tests (both the table and the law) to exist and be red for the verdict to be `:done`. With only 1 of 2 product tests defined, the status remained `:working` (neither `:done` nor `:satisfied`). No turn disposition was `:completed` (`:given-back`), `sent-back-to` was `nil`, turns were not exhausted (`:capped`), and `stalled?` was false because `prove-turn` was not a model turn. Thus `task-end` returned `nil`.
4. **No parking:** `prove-turn` succeeded without refusal, so `refusals` was 0 and `parked?` was `false`. The write-refusal bound is only checked when `refusal` is non-nil.
5. **Immediate self-rewake:** Because `parked?` was false, `seon.turn/step` immediately offered a wake token:
   ```clojure
   (when-not parked?
     (async/offer! (:seon.cluster.wake/channel cluster) :seon.agent/wake))
   ```
6. **Zero backoff or queueing delay:** The `core.async.flow` proc picked up `:seon.agent/wake` immediately. `more-agent-work?` is `(some? (next-agent-work db request))`, which was continuously true because `next-agent-work` answered `:prove`.
7. **Pure CPU burn:** With no I/O, no network latency, no locks, and no sleep/timer, the proc repeatedly ran Datalog queries and schema validations (`seon.schema/call-with-projection-state` reached 311,767 calls and 1,264s CPU; `seon.db/commit-database` reached 155,883 calls), spinning at 100% of one CPU core for 23 minutes until root manually transacted `[:seon.task/execution :held]`.

---

## 3. The Smallest Correct Fix at the Owner

### Owner
The owner is **`seon.turn`**, specifically **`seon.turn/first-proof-run-t`**.

### Principle
Per Seon design laws, no guards, counters, retry limits, or backoff sleeps should be introduced to mask the condition. Instead, the condition must correctly reflect reality: **the work has in fact happened**. 

In Seon's test architecture, every admitted test execution asserts a member entity with attribute `[:seon.test.member/symbol ?proof]` (via `seon.test/admit-run`). In contrast, `:seon.test.run/identities` is an optional request filter on `:named` requests that is never asserted when a test is selected by reach or when its execution is reused.

### Smallest Correct Fix
In `src/seon/turn.clj`, update `first-proof-run-t` to check for test run evidence via the canonical test member symbol `[?member :seon.test.member/symbol ?proof]` (or matching either `:identities` or member symbol via `or-join`):

```clojure
(defn- first-proof-run-t
  "The transaction of the first run of the proofs of the task `agent-id` holds recorded on `database`, the agent's
  branch, after its attempt's row: the attempt's own proving, never a run its value inherited from a predecessor;
  for a task naming no proof, the transaction of the attempt's row itself (nothing is left to prove, so the
  attempt has proved first); nil when none ran, the proofs' read refusal when they cannot be read. The proofs are
  the tests its step is judged by (`step-tests`), a Prove step's held product included."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int :seon.error/value]]}
  [database agent-id]
  (let [proofs (step-tests database agent-id)]
    (cond
      (:seon.error/at proofs) proofs
      (empty? proofs)
      (db/q '[:find (max ?attempt) . :in $ ?id
              :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]]
            database agent-id)
      :else
      (db/q '[:find (min ?t) . :in $ ?id [?proof ...]
              :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]
              (not-join [?g ?attempt] [?b :seon.task/agent ?g] [?b :seon.task/assigned _ ?later] [(> ?later ?attempt)])
              (or-join [?t ?proof]
                (and [?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t])
                (and [?member :seon.test.member/symbol ?proof] [?member :seon.test.member/symbol _ ?t]))
              [(> ?t ?attempt)]]
            database agent-id (vec proofs)))))
```

*(Alternatively, querying `[?member :seon.test.member/symbol ?proof] [?member :seon.test.member/symbol _ ?t] [(> ?t ?attempt)]` directly, as every admitted run creates member entities).*

When the post-eval test step runs `table`, `[?member :seon.test.member/symbol table]` is recorded in transaction `?t > ?attempt`. `first-proof-run-t` returns `?t`, `proof-run-recorded?` evaluates to `true`, and `next-agent-work` derives `:open` (since `proved-unanswered?` and `continuing-reply?` hold), opening call 5 for the agent to write the law.

---

## 4. Red Regression Deftest

To be added to `test/seon/turn_test.clj`:

```clojure
(deftest a-prove-attempt-that-defines-its-first-product-test-derives-next-work
  (support/with-database
   (fn [connection]
     (task/index-tasks! {:seon.db/connection connection
                         :seon.task/files {"resources/seon/tasks/tq.prove.edn"
                                           "[{:seon.task/name \"surface-id\"
                                              :seon.task/step :prove
                                              :seon.task/product #{tq.prove-test/table tq.prove-test/law}
                                              :seon.task/intent \"Prove surface-id.\"}]"}})
     (let [agent-id "tq-prove-agent"
           handle (task/task-handle 'tq.prove "surface-id")]
       (doseq [id ["root" agent-id]]
         (support/transacted! connection (support/agent-tx (db/db connection) id)))
       (support/transacted! connection [[:db/add [:seon.task/id handle] :seon.task/execution :running]])
       (support/transacted! connection (task/attempt-tx (db/db connection) handle agent-id :tq-prove))
       ;; 1. Fork point: neither product test exists, so step-tests is empty; attempt proved first.
       (is (true? (turn/proof-run-recorded? (db/db connection) agent-id)))
       (is (= :open (:seon.turn.work/situation (turn/next-agent-work (db/db connection) {:seon.agent/id agent-id}))))
       ;; 2. Prove agent defines the table deftest in an eval request.
       (support/transacted! connection [[:db/add "table-entity" :seon.test/sym 'tq.prove-test/table]])
       ;; 3. Automatic test run triggered by reaches-changed records member results without :identities.
       (let [database (db/db connection)
             provenance (runner/provenance database)]
         (support/transacted!
          connection [[:db.fn/call seon.test/admit-run
                       {:seon.test.run/provenance provenance
                        :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name (support/execution-handle connection))]
                        :seon.test.run/input-digest (db/q '[:find ?d . :where [_ :seon.source/test-input-digest ?d]] database)
                        :seon.test.run/policy :incremental :seon.test.run/include-long? false
                        :seon.test.run/members [{:seon.test.member/symbol 'tq.prove-test/table
                                                 :seon.test.member/reasons #{:reaches-changed}}]}]]))
       ;; 4. Assertions:
       ;; On 0c684db75^: proof-run-recorded? is false and next-agent-work answers :prove forever.
       ;; With fix: proof-run-recorded? is true and next-agent-work answers :open.
       (is (true? (turn/proof-run-recorded? (db/db connection) agent-id))
           "a product test run recorded via members after the attempt began counts as proved")
       (is (= :open (:seon.turn.work/situation (turn/next-agent-work (db/db connection) {:seon.agent/id agent-id})))
           "the attempt derives :open to continue rather than repeating :prove")))))
```

---

## Five-Line Summary

1. `seon.turn/first-proof-run-t` looked for test runs strictly matching `[?run :seon.test.run/identities ?proof]`, but post-eval runs triggered by changed reach record member entities without that optional attribute.
2. When `next-agent-work` derived `:prove`, `prove-turn` -> `run-proofs` reused the unchanged test result from call 4, admitting zero batches and writing no new transaction.
3. Because no transaction with `:seon.test.run/identities` was ever written, `proof-run-recorded?` remained false indefinitely.
4. `prove-turn` evaluated no forms, had 0 write refusals, and did not satisfy the multi-test Prove step, causing `step` to immediately self-rewake in a zero-latency CPU live-lock.
5. The fix at owner `seon.turn/first-proof-run-t` queries canonical test execution evidence via member symbol `[?member :seon.test.member/symbol ?proof]` rather than request identities.
