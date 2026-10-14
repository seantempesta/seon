# Blind Debugging Report — Run 18 `:prove` Live-Lock (`0c684db75^`)

## 1. Root Cause

- **Exact function and clause answering `:prove`:**
  In [`seon.turn/next-agent-work`](file:///Users/sean/src/seon/src/seon/turn.clj#L2311-L2312):
  ```clojure
  (and (string? held) (attempt-running? db held) (not (proof-run-recorded? db agent-id)))
  {:seon.turn.work/situation :prove :seon.agent/id agent-id}
  ```
  where [`seon.turn/proof-run-recorded?`](file:///Users/sean/src/seon/src/seon/turn.clj#L5264-L5270) delegates to [`seon.turn/first-proof-run-t`](file:///Users/sean/src/seon/src/seon/turn.clj#L5242-L5262):
  ```clojure
  (db/q '[:find (min ?t) . :in $ ?id [?proof ...]
          :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]
          (not-join [?g ?attempt] [?b :seon.task/agent ?g] [?b :seon.task/assigned _ ?later] [(> ?later ?attempt)])
          [?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]]
        database agent-id (vec proofs))
  ```

- **Why it never becomes true:**
  1. Before the 4th model call, the `:prove` step has no own `:seon.task/proofs` (`task-proofs` is `#{}`) and its product test (`:seon.task/product`) is not yet defined on `database`, so [`seon.turn/step-tests`](file:///Users/sean/src/seon/src/seon/turn.clj#L5211-L5225) returns `#{}`. In `first-proof-run-t`, `(empty? proofs)` is true and returns the attempt's `:t`, so `proof-run-recorded?` is `true`.
  2. On the 4th model call, the agent defines the product test table `P`. Now `P` exists in `database` (`[?e :seon.test/sym P]`), so `(step-tests database agent-id)` becomes `#{P}` (non-empty) and `first-proof-run-t` switches to the `:else` Datalog query above.
  3. At the end of the 4th model call, [`seon.turn/test-step`](file:///Users/sean/src/seon/src/seon/turn.clj#L5485-L5498) checks `proofs? (seq (task-proofs ...))` (which is `nil` for a `:prove` step with no own proofs) rather than `step-tests`, so `run-proofs` does not run; instead, [`seon.turn/run-reaching`](file:///Users/sean/src/seon/src/seon/turn.clj#L5309-L5325) runs `seon.test/run` with `:seon.test/policy :named` and `:seon.test/changed changed` (without `:seon.test/identities`).
  4. In [`seon.test/select`](file:///Users/sean/src/seon/src/seon/test.clj#L1234), `:seon.test.run/identities` is only attached when `(seq identities)` is non-empty on the request. The reaching test run records the execution on a member entity (`[?run :seon.test.run/members ?m]` and `[?m :seon.test.member/symbol P]`), **not** `[?run :seon.test.run/identities P]`.
  5. When `next-agent-work` then answers `:prove` and [`seon.turn/prove-turn`](file:///Users/sean/src/seon/src/seon/turn.clj#L5272-L5284) calls `run-proofs` with `:seon.test/identities #{P}`, [`seon.test/select`](file:///Users/sean/src/seon/src/seon/test.clj#L1110-L1212) finds `P`'s completed red member from the reaching run in `latest` (at the same `:seon.test.member/reach-digest`), classifies `P` into `reused` (`:seon.test.selection/unchanged`), and removes `P` from `reasons`, yielding `:seon.test.run/members []`.
  6. In [`seon.test/run-request`](file:///Users/sean/src/seon/src/seon/test.clj#L2656-L2912), `:seon.test.run/members []` produces `selected []`, `runnable []`, `ordered ()`, and `batches []`. The batch loop `(if-let [members (get batches index)] ...)` exits immediately at `index 0` without calling `admit!`, so **nothing is ever transacted to the branch**. `[?run :seon.test.run/identities ?proof]` never appears in `database`, and `first-proof-run-t` remains `nil` forever.

## 2. Why the Loop Does Not End or Slow Down by Itself

1. **`prove-turn` reports `:closed` with no refusal:** `seon.test/run` returns a valid map with the reused red result (`:seon.test/reused-count 1`, `:seon.test/executed-count 0`, no `:seon.error/at`), so [`seon.turn/prove-turn`](file:///Users/sean/src/seon/src/seon/turn.clj#L5282-L5284) returns `(report :closed 0)` with `refusal` = `nil`.
2. **No write-refusal counter or park fires:** In [`seon.turn/step`](file:///Users/sean/src/seon/src/seon/turn.clj#L5688-L5700), `refusal` is `nil`, so `refusals` resets to `0` and `parked?` is `false`.
3. **No episode turn budget is spent:** `:prove` transacts no `:seon.turn/id` entity, so `turns-left` / `episode-exhausted!` never decrements.
4. **No automatic `test-step` runs, and `end-task!` does not close the agent:** `(:seon.turn.loop/forms-run report)` is `0`, so `step-due?` is `false`; if the step verdict is still `:working` (or `task-end` is `nil`), `end-task!` is a no-op.
5. **Immediate self-rewake on every pass:** Because `parked?` is `false`, [`seon.turn/step`](file:///Users/sean/src/seon/src/seon/turn.clj#L5755-L5757) executes `(async/offer! (:seon.cluster.wake/channel cluster) :seon.agent/wake)` into the agent's sliding-1 mailbox before returning, immediately re-entering `step` -> `next-agent-work` -> `:prove` -> `prove-turn` -> `run-proofs` in a tight CPU loop (~311k `seon.schema/call-with-projection-state` calls across turns and reads).

## 3. Smallest Correct Fix at the Owner

The owner of "has a run of this step's tests been recorded on the branch after the attempt began?" is [`seon.turn/first-proof-run-t`](file:///Users/sean/src/seon/src/seon/turn.clj#L5242-L5262).

A test execution is recorded on a run's **member** (`:seon.test.member/symbol`), whereas `:seon.test.run/identities` is merely request-scope metadata present only when the caller passed `:seon.test/identities`. Querying `:seon.test.member/symbol` (or either `:seon.test.member/symbol` or `:seon.test.run/identities`) on `database` after `?attempt` makes `first-proof-run-t` true whenever the test has in fact run on the branch since the attempt was assigned, regardless of whether the run was selected by `:seon.test/identities` or by `:seon.test/changed` reach:

```clojure
(defn- first-proof-run-t
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
              (or-join [?proof ?t]
                (and [?m :seon.test.member/symbol ?proof ?t])
                (and [?run :seon.test.run/identities ?proof ?t]))
              [(> ?t ?attempt)]]
            database agent-id (vec proofs)))))
```
(Or simply `[?m :seon.test.member/symbol ?proof ?t] [(> ?t ?attempt)]`, since every admitted test run with members asserts `:seon.test.member/symbol` in its admission transaction.)

## 4. Red Regression `deftest`

- **Name:** `a-reaching-run-of-a-prove-steps-product-records-its-proof-run` (in `test/seon/turn_test.clj`)
- **Setup as data & assertion:**
```clojure
(deftest a-reaching-run-of-a-prove-steps-product-records-its-proof-run
  (support/with-database
   (fn [connection]
     (task/index-tasks!
      {:seon.db/connection connection
       :seon.task/files
       {"resources/seon/tasks/tq.prove-reach.edn"
        (pr-str [#:seon.task{:name "prove-a" :step :prove :intent "Prove a."
                             :product ['tq.prove-reach-test/a-test]}
                 #:seon.task{:name "impl-a" :step :implement :intent "Implement a."
                             :needs ['tq.prove-reach/prove-a]}])}})
     (let [handle (task/task-handle 'tq.prove-reach "prove-a")
           agent-id "tq-prove-reach-agent"]
       (doseq [id ["root" agent-id]]
         (support/transacted! connection (support/agent-tx (db/db connection) id)))
       (support/transacted! connection [[:db/add [:seon.task/id handle] :seon.task/execution :running]])
       (support/transacted! connection (task/attempt-tx (db/db connection) handle agent-id :tq-prove-reach))
       ;; The agent defines its product test during the attempt, and the automatic reaching
       ;; test run records the member without :seon.test.run/identities.
       (support/declare-proof! connection 'tq.prove-reach-test 'a-test 'tq.prove-reach/impl-a)
       (let [database (db/db connection)
             provenance (runner/provenance database)]
         (support/transacted!
          connection
          [[:db.fn/call seon.test/admit-run
            {:seon.test.run/provenance provenance
             :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name (support/execution-handle connection))]
             :seon.test.run/input-digest (db/q '[:find ?d . :where [_ :seon.source/test-input-digest ?d]] database)
             :seon.test.run/policy :named
             :seon.test.run/include-long? false
             :seon.test.run/members [{:seon.test.member/symbol 'tq.prove-reach-test/a-test
                                      :seon.test.member/reasons #{:reaches-changed}}]}]]))
       (is (true? (turn/proof-run-recorded? (db/db connection) agent-id))
           "a reaching run that admitted the product test after the attempt began satisfies proof-run-recorded?")
       (is (not= :prove (:seon.turn.work/situation
                         (turn/next-agent-work (db/db connection) {:seon.agent/id agent-id})))
           "next-agent-work does not re-enter :prove after a reaching run already ran the product test")))))
```

## Summary

1. **Root cause:** `seon.turn/next-agent-work` answers `:prove` when `(not (proof-run-recorded? db agent-id))` is true, and `seon.turn/first-proof-run-t` queries `[?run :seon.test.run/identities ?proof]` instead of the recorded member `[?m :seon.test.member/symbol ?proof]`.
2. **Why it never becomes true:** Defining the product test makes `step-tests` non-empty, `test-step` runs it via `run-reaching` (`:seon.test/changed`, which omits `:seon.test.run/identities`), and every subsequent `prove-turn` -> `run-proofs` call reuses that member result by reach digest (`:seon.test.run/members []`, `batches []`) without calling `admit!` or writing to the branch.
3. **Why the loop never stops:** `prove-turn` receives the reused result without error and reports `:closed` with `0` forms and no refusal, resetting `write-refusals` to `0`, spending no turn budget, and offering `:seon.agent/wake` to the agent's mailbox on every pass.
4. **Fix at the owner:** In `seon.turn/first-proof-run-t`, match the recorded test execution after `?attempt` on `[?m :seon.test.member/symbol ?proof ?t]` (or via `or-join` with `:seon.test.run/identities`) rather than requiring `:seon.test.run/identities`.
5. **Regression proof:** A `deftest` in `seon.turn-test` that starts a `:running` `:prove` attempt, declares its product test and admits a reaching run (`:seon.test.run/members` without `:seon.test.run/identities`), and asserts `(true? (turn/proof-run-recorded? ...))` and `(not= :prove (:seon.turn.work/situation (turn/next-agent-work ...)))`.
