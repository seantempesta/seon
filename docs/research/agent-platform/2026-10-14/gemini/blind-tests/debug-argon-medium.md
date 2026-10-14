# Blind Debugging Report — Run 18 `:prove` Live-Lock (`0c684db75^`)

## 1. Root Cause: Exact Function, Clause/Condition, and Why It Never Becomes True

### Exact Function and Clause Answering `:prove`
- In `seon.turn/next-agent-work` (`src/seon/turn.clj:2311-2312`), when no turn is currently open (`(nil? run)`), the `cond` arm that answers `{:seon.turn.work/situation :prove :seon.agent/id agent-id}` is:
  ```clojure
  (and (string? held) (attempt-running? db held) (not (proof-run-recorded? db agent-id)))
  {:seon.turn.work/situation :prove :seon.agent/id agent-id}
  ```
- `seon.turn/proof-run-recorded?` (`src/seon/turn.clj:5264-5270`) is:
  ```clojure
  (some? (first-proof-run-t database agent-id))
  ```
- `seon.turn/first-proof-run-t` (`src/seon/turn.clj:5242-5262`) computes `proofs` via `(step-tests database agent-id)` (`src/seon/turn.clj:5211-5225`), which unions `(task-proofs database agent-id)` with, for a `:prove` step, the `:seon.task/product` tests whose `:seon.test/sym` entity currently exists in `database`:
  ```clojure
  (= :prove (:seon.task/step owed))
  (into proofs (filter #(seq (db/datoms database :avet :seon.test/sym %))) (:seon.task/product owed))
  ```
  When `proofs` is non-empty, `first-proof-run-t` runs the `:else` query (`src/seon/turn.clj:5257-5262`):
  ```clojure
  (db/q '[:find (min ?t) . :in $ ?id [?proof ...]
          :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?attempt]
          (not-join [?g ?attempt] [?b :seon.task/agent ?g] [?b :seon.task/assigned _ ?later] [(> ?later ?attempt)])
          [?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]]
        database agent-id (vec proofs))
  ```

### Why It Transitions to `false` After the 4th Model Call and Never Becomes `true`
1. **Before the 4th model call (`proofs` empty):** The `:prove` step has no own `:seon.task/proofs` (`#{}`), and before the 4th model call its product test (`:seon.task/product`) did not yet exist in `database` (`:seon.test/sym`). Thus `(step-tests database agent-id)` returned `#{}`. In `first-proof-run-t`, `(empty? proofs)` was `true`, returning `(max ?attempt)` (the attempt transaction `t`), so `(proof-run-recorded? db agent-id)` was `true` across turns 1–4.
2. **On the 4th model call (`proofs` becomes non-empty, run recorded without `:seon.test.run/identities`):** The agent's eval defined the product test table on its branch, creating the `:seon.test/sym` entity in `database`. From that commit onward, `(step-tests database agent-id)` returns the non-empty product test set.
   - At the end of that pass, `seon.turn/test-step` (`src/seon/turn.clj:5486-5504`) ran. Because `test-step` checks `proofs?` via `(seq (task-proofs ...))` (which only reads `:seon.task/proofs`, still `#{}` for this `:prove` step) rather than `step-tests`, `test-step` skipped `run-proofs` and ran the product test through `run-reaching` (`src/seon/turn.clj:5316-5329`).
   - `run-reaching` called `seon.test/run` with `{:seon.test/policy :named, :seon.test/changed changed}` and **no** `:seon.test/identities`.
   - In `seon.test/select` (`src/seon/test.clj:1211-1234`), `:seon.test.run/identities` is only attached to the run entity when `(seq identities)` is supplied in the request. The reaching test run therefore admitted `[?run :seon.test.run/members ?member]` and `[?member :seon.test.member/symbol ?proof ?t]` (with `?t > ?attempt`) and recorded the 3 red rows, **without** asserting `[?run :seon.test.run/identities ?proof]`.
3. **On the next pass and every pass thereafter (`prove-turn` -> `run-proofs` is a no-op write):**
   - `first-proof-run-t` takes the `:else` branch (since `step-tests` is now non-empty) and queries `[?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]`, which returns `nil`. Thus `proof-run-recorded?` is `false` and `next-agent-work` answers `:prove`.
   - `prove-turn` (`src/seon/turn.clj:5272-5284`) calls `run-proofs` (`src/seon/turn.clj:5227-5240`), which calls `seon.test/run` with `{:seon.test/policy :named, :seon.test/identities proofs}`.
   - Inside `seon.test/select` (`src/seon/test.clj:1139-1193`), `latest` indexes all completed members on the agent's branch regardless of `:seon.test.run/identities`. It finds the completed red `:seon.test.member` recorded by `run-reaching` at the exact same `:seon.test.member/reach-digest` (since no program entity changed after the 4th model call), puts the product test into `reused` (`:seon.test.selection/unchanged`), and removes it from `reasons` (`reasons (apply dissoc reasons (keys reused))`, line 1193).
   - Consequently, `(:seon.test.run/members selection)` is `[]`. In `seon.test/run-request` (`src/seon/test.clj:2678-2936`), `selected`, `runnable`, `ordered`, and `batches` (`(vec (partition-all limit ordered))`) are all `[]`. The batch loop (`(if-let [members (get batches index)] ...)`, line 2920) exits immediately at `index = 0` without calling `admit!`.
   - `seon.test/run` returns a valid `:seon.test/run-result` (`:seon.test/reused-count` = 1, `:seon.test/executed-count` = 0) having written **zero datoms** to `connection`. Because no `:seon.test.run` entity with `:seon.test.run/identities` is ever transacted, `first-proof-run-t` remains `nil` and `(not (proof-run-recorded? db agent-id))` remains `true` forever.

---

## 2. Why the Loop Does Not End or Slow Down by Itself

1. **No write refusal (`write-refusal-bound` never increments):** `seon.test/run` returns a normal `:seon.test/run-result` map (no `:seon.error/at`), so `prove-turn` returns `(report :closed 0)` with no refusal. In `seon.turn/step` (`src/seon/turn.clj:5688-5703`), `refusal` is `nil`, `refusals` resets to `0`, and `parked?` is `false`.
2. **No episode turn is spent (`max-episode-runs` never decrements):** `prove-turn` makes no model call and writes no `:seon.turn/id` entity, so `turns-left` (`src/seon/turn.clj:2166`, `4785`) never changes and `episode-exhausted!` never fires.
3. **No turn backstop timeout:** Each pass of `step` finishes in ~4 ms (acquiring context, running `select` in memory under `schema/call-with-projection-state`, and returning), well within `:seon.config.agent/turn-completion-backstop-ms`, and republishes `:seon.agent/ready` in `finally`.
4. **No task-end transition:** `(= :closed (:seon.turn.loop/outcome report))` triggers `(end-task! cluster agent-id)` (`src/seon/turn.clj:5727-5728`), which checks `task-end` -> `seon.context/step-verdict`, sees `:seon.task/status :working`, and returns `nil` without closing admission.
5. **Immediate unconditional self-rewake:** At the end of `step` (`src/seon/turn.clj:5766-5768`):
   ```clojure
   (when-not parked?
     (async/offer!
      (:seon.cluster.wake/channel cluster) :seon.agent/wake))
   ```
   Because `parked?` is `false`, `step` immediately offers `:seon.agent/wake` into the agent's own sliding-1 mailbox channel. The Flow proc immediately re-enters `step` on the next turn of its executor loop with no sleep, backoff, or blocking I/O, spinning at ~1 CPU core until root changes the task's `:seon.task/execution` to `:held` (making `(attempt-running? db held)` `false`).

---

## 3. Smallest Correct Fix at the Owner

The owner of "has a proof of the held task's step been run on the agent's branch after the attempt began?" is `seon.turn/first-proof-run-t` (`src/seon/turn.clj:5242-5262`).

Every admitted test run on a branch—whether selected by `:seon.test/identities` (`run-proofs`), `:seon.test/changed` (`run-reaching`), `:seon.test/namespaces`, or `:incremental`—asserts `[?run :seon.test.run/members ?member]` with `[?member :seon.test.member/symbol ?proof ?t]` (which is also `:avet`-indexed via `:seon.db/index true` in `resources/seon/schemas/seon.test.member.edn`).

In `seon.turn/first-proof-run-t` (`src/seon/turn.clj:5257-5262`), query `:seon.test.member/symbol` instead of `:seon.test.run/identities`:

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
              [?member :seon.test.member/symbol ?proof ?t] [(> ?t ?attempt)]]
            database agent-id (vec proofs)))))
```

*(Note: `step-due?` at `src/seon/turn.clj:5194` and `test-step` at `src/seon/turn.clj:5487` also call `task-proofs` rather than `step-tests`, which is why `test-step` ran the newly defined product test only via `run-reaching`; changing `first-proof-run-t` to match `[?member :seon.test.member/symbol ?proof ?t]` directly makes `proof-run-recorded?` and `proved-unanswered?` true whenever any admitted run on the branch after `?attempt` has executed a proof or product test.)*

---

## 4. Red Regression `deftest`

In `test/seon/turn_test.clj` (right beside `a-predecessors-proof-run-does-not-count-as-the-attempts` at line 2060):

```clojure
(deftest a-reaching-run-of-a-prove-steps-product-counts-as-its-proof-run
  (support/with-database
   (fn [connection]
     (task/index-tasks! {:seon.db/connection connection
                         :seon.task/files {"resources/seon/tasks/tq.reachprove.edn"
                                           (str "[#:seon.task{:name \"prove\" :step :prove :intent \"Prove a.\""
                                                "             :product #{tq.reachprove-test/proves-a}}"
                                                " #:seon.task{:name \"implement\" :intent \"Implement a.\""
                                                "             :needs #{tq.reachprove/prove}}]")}})
     (let [agent-id "tq-reachprove-agent"
           handle (task/task-handle 'tq.reachprove "prove")]
       (doseq [id ["root" agent-id]]
         (support/transacted! connection (support/agent-tx (db/db connection) id)))
       (support/transacted! connection [[:db/add [:seon.task/id handle] :seon.task/execution :running]])
       (support/transacted! connection (task/attempt-tx (db/db connection) handle agent-id :tq-reachprove))
       ;; During the attempt, the agent defines the product test and test-step's
       ;; reaching run admits and records it via :seon.test/changed (no :seon.test.run/identities).
       (support/declare-proof! connection 'tq.reachprove-test 'proves-a 'tq.reachprove/a)
       (let [database (db/db connection)
             provenance (runner/provenance database)]
         (support/transacted!
          connection [[:db.fn/call seon.test/admit-run
                       {:seon.test.run/provenance provenance
                        :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name (support/execution-handle connection))]
                        :seon.test.run/input-digest (db/q '[:find ?d . :where [_ :seon.source/test-input-digest ?d]] database)
                        :seon.test.run/policy :named :seon.test.run/include-long? false
                        :seon.test.run/members [{:seon.test.member/symbol 'tq.reachprove-test/proves-a
                                                 :seon.test.member/reasons #{:reaches-changed}}]}]]))
       (is (true? (turn/proof-run-recorded? (db/db connection) agent-id))
           "a reaching run of the Prove step's product test after the attempt row satisfies proof-run-recorded?")
       (is (= {:seon.turn.work/situation :open :seon.agent/id agent-id}
              (turn/next-agent-work (db/db connection) {:seon.agent/id agent-id}))
           "next-agent-work opens the next model turn instead of looping in :prove")))))
```

---

## Five-Line Summary

1. **Root cause (`seon.turn/next-agent-work` -> `proof-run-recorded?` -> `first-proof-run-t`):** After the 4th model call defined the `:prove` step's product test, `(step-tests db agent-id)` became non-empty, switching `first-proof-run-t` from `(empty? proofs)` to its `:else` query `[?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t] [(> ?t ?attempt)]`.
2. **Why the condition stayed `false`:** `test-step` ran the new product test via `run-reaching` (`:seon.test/changed`, which records `:seon.test.run/members` / `:seon.test.member/symbol` without `:seon.test.run/identities`), and every subsequent `prove-turn` -> `run-proofs` (`:seon.test/identities`) reused that recorded red member in `seon.test/select`, leaving `batches` empty and transacting nothing.
3. **Why the loop never slowed or ended:** `prove-turn` returned `(report :closed 0)` with no error or write refusal (`refusals = 0`, `parked? = false`), spent no episode turn, saw `:working` in `end-task!`, and unconditionally offered `:seon.agent/wake` to the agent's own mailbox at the end of every `seon.turn/step`.
4. **Smallest fix at the owner:** In `seon.turn/first-proof-run-t` (`src/seon/turn.clj:5261`), replace `[?run :seon.test.run/identities ?proof] [?run :seon.test.run/identities _ ?t]` with `[?member :seon.test.member/symbol ?proof ?t]`, matching any admitted test run of `step-tests` after `?attempt`.
5. **Regression proof:** Assert in `a-reaching-run-of-a-prove-steps-product-counts-as-its-proof-run` that after a `:running` `:prove` attempt defines its product test and records a `:reaches-changed` run (with `:seon.test.run/members` and no `:seon.test.run/identities`), `proof-run-recorded?` is `true` and `next-agent-work` answers `:open` rather than `:prove`.
