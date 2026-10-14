# S5 Live Chain Path Pre-Mortem (`seon.task/chain!` → Design → Prove → Implement → Merge)

**Date:** 2026-10-14  
**Subject:** `seon.error/an-unresolved-alias-name-offers-its-nearest-core-name` (`:seon.task/id "f1f6dc8e6652"`)  
**Authority:** [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md), [s5-run-plan](s5-run-plan.md), [s5-run-1](s5-run-1.md), [s5-run-2](s5-run-2.md)

---

## 1. End-to-End Trace of the Live Chain Path on HEAD

1. **Chain creation (`(seon.task/chain! context (seon.task/from-filed database filed))`):**
   - `seon.task/from-filed` (`src/seon/task.clj:832-854`) reads the three template tasks (`seon.step/design`, `seon.step/prove`, `seon.step/implement`) via `seon.task/template` (`src/seon/task.clj:818-830`), merges `(dissoc filed :seon.task/proofs)` into the Design task (`:seon.task/id "f1f6dc8e6652"`), prepends `"## Problem\n\n"` to `:seon.task/plan` when the filed plan has no `"problem"` section, and generates fresh tasks (`"a115c95c1372"` for Prove, `"1c475f9f6353"` for Implement).
   - `seon.task/chain!` (`src/seon/task.clj:996-1025`) creates branch `:chain-f1f6dc8e6652` off `main`, calls `seon.task/adopt-tasks-tx` (`src/seon/task.clj:873-911`) on the branch to link `Design → Prove → Implement` via `:seon.task/next` and `:seon.task/chain "f1f6dc8e6652"`, sets Design `:seon.task/execution :running` and Prove/Implement `:seon.task/execution :held`, and records `:seon.task/branch :chain-f1f6dc8e6652` and `:seon.task/execution :held` on the head entity on `main`.
2. **Cluster pass (`seon.cluster.agent/run-pass!`, `src/seon/cluster/agent.clj:2607-2680`):**
   - `chain-facts` (`src/seon/cluster/agent.clj:1778-1797`) opens each `:chain-*` branch via `chain-read` (`src/seon/cluster/agent.clj:1731-1776`). On `:chain-f1f6dc8e6652`, `chain-Head` finds the first task whose latest attempt is not `:done` (`"f1f6dc8e6652"`), sees `execution = :running` and no open attempt (`starts? = true`), and emits `{:seon.task/id "f1f6dc8e6652" :seon.chain/branch :chain-f1f6dc8e6652}` in `:seon.chain/starts`.
   - `advance-chains!` (`src/seon/cluster/agent.clj:2103-2125`), `merge-chains!` (`src/seon/cluster/agent.clj:2154-2185`), and `finish-chains!` (`src/seon/cluster/agent.clj:2127-2152`) run before the plan phase.
   - `write-chain-attempt!` (`src/seon/cluster/agent.clj:2079-2093`) mints attempt key `<k>` and agent id `"attempt/<k>"` and writes the attempt entity **only on `:chain-f1f6dc8e6652`**.
   - `db<->flow` (`src/seon/cluster/agent.clj:2526-2605`) merges `(:seon.flow.actions/open chains)` into `actions` (`:start`) and starts `"attempt/<k>"` inside `:chain-f1f6dc8e6652`'s child routing (`chain-routing!`, `src/seon/cluster/agent.clj:2434-2478`).
3. **Agent start & prove pass (`seon.cluster.agent/next-agent-work`, `src/seon/cluster/agent.clj:1463-1554`):**
   - `attempt-running?` (`src/seon/cluster/agent.clj:1379-1391`) checks that the attempt has no `:seon.task.attempt/end` and `:seon.task/execution` is `:running`.
   - On the first pass (`turns = 0`), `proved-unanswered?` (`src/seon/turn.clj:5565-5586`) checks whether all task proofs already have a current result recorded after the latest model response; if not, `next-agent-work` returns `:seon.agent.work/prove`.
   - `run-prove-pass!` (`src/seon/cluster/agent.clj:1393-1431`) calls `seon.turn/run-proofs` (`src/seon/turn.clj:5484-5513`) → `seon.test/run-suite!` with `:seon.test/task {:seon.task/id (:seon.task/id task) :seon.test/branch database}`.
   - Inside the test run, `seon.test/task` (`src/seon/test.clj:1596-1607`) pulls `[:* {:seon.task/next [:seon.task/id :seon.task/proofs]}]` for the task on the branch.
   - After `run-prove-pass!`, if `(turn/step-proved? (db/db connection) task)` (`src/seon/turn.clj:5543-5563`) is already true (e.g., on re-entry to an already-satisfied step), `run-prove-pass!` writes `(turn/task-end ... :done)` immediately without a model call. Otherwise, `next-agent-work` sees `proved-unanswered? = true` and returns `:seon.agent.work/step`.
4. **Model turn (`seon.turn/execute-step`, `src/seon/turn.clj:5848-5908`) & Context (`seon.context/current-state`, `src/seon/context.clj:1006-1039`):**
   - `seon.context/current-state` renders `:seon.context/task`, `:seon.context/step` (via `step-section`, `src/seon/context.clj:951-974`, calling `seon.cluster.source/done-refusals` on `step-observation`), `:seon.context/proofs` (via `proofs-section`, `src/seon/context.clj:901-949`), `:seon.context/references`, `:seon.context/namespace`, `:seon.context/history`, and `:seon.context/turn`.
   - When the model evaluates Clojure forms, `seon.turn/eval-step` evaluates them in the branch's SCI context and settles definitions/transactions on `:chain-f1f6dc8e6652`.
   - `seon.turn/test-step` (`src/seon/turn.clj:5515-5541`) checks `(test/stale branch proofs)` and automatically runs `run-proofs` when stale or when the agent called `my.turn/wait`.
   - `seon.turn/finish-step` (`src/seon/turn.clj:5758-5803`) checks `step-proved?`: when all proofs in `(:seon.task/proofs task)` have a green result in `(runner/current-results database proofs (:seon.task/sym task))` and the agent called `(my.turn/wait)` (or all proofs were already green at `run-prove-pass!`), it writes `[:db.fn/call #'task/attempt-end-tx attempt-key :done commit-id]`.
5. **Advance (`seon.cluster.agent/advance-chains!`, `src/seon/cluster/agent.clj:2103-2125`):**
   - `chain-ends` (`src/seon/cluster/agent.clj:1799-1825`) finds ended `:done` attempts on `:chain-f1f6dc8e6652` where the task is still `:seon.task/execution :running`.
   - For non-terminal steps (`next? = true`: Design and Prove), `advance-chains!` opens `:chain-f1f6dc8e6652`, constructs `at-end = (db/commit-database (db/db on-chain) commit)`, checks `(empty? (source/done-refusals at-end (done-judged database at-end branch h)))`, and transacts `[[:db.fn/call #'task/advance-tx key]]` (`src/seon/task.clj:929-951`), setting the current task `:held` and the `:seon.task/next` task `:running`.
   - When Design advances to Prove, Prove runs `seon.step.prove-test/handoff` (`test/seon/step/prove_test.clj:12-30`), which calls `(prove/handoff branch task)` (`src/seon/step/prove.clj:19-45`). The Prove agent writes its deftest in `seon.error-test` and registers it on the Implement task (`"1c475f9f6353"`) via `(my.task/update! "1c475f9f6353" {:proofs ['seon.error-test/...]} "...")`.
   - When Prove advances to Implement, Implement's `:seon.task/proofs` is the deftest Prove registered on `"1c475f9f6353"`. Implement edits `seon.error/nearest-names` until that proof passes and calls `(my.turn/wait)`.
6. **Final merge & retirement (`seon.cluster.agent/merge-chains!` & `finish-chains!`, `src/seon/cluster/agent.clj:2127-2185`):**
   - When Implement (`next? = false`) ends `:done`, `merge-chains!` checks `(empty? (source/done-refusals at-end (done-judged database at-end branch h)))`, then calls `(merge/merge! context {:seon.merge/source-branch branch :seon.merge/attempt key})`.
   - `seon.merge/merge!` (`src/seon/merge.clj:376-403`) runs `source/prepare-merge!` (`src/seon/cluster/source.clj:1098-1155`) on a temporary `:merge-<id>` branch (checking `done-refusals` and `candidate-gate!` on the terminal Implement task) and `source/land-merge!` (`src/seon/cluster/source.clj:1157-1208`), which calls `d/merge!` into `main` along with `task/complete-chain-tx` (`src/seon/task.clj:953-980`, marking all chain tasks `:closed` / `:done` and recording `:seon.task.attempt/merged-t`).
   - On the next `run-pass!`, `finish-chains!` (`src/seon/cluster/agent.clj:2127-2152`) sees `:seon.chain/done? true` (`:seon.task/outcome :done` on `main`), writes back changed files (`publish-landed!`), stops the child routing, and calls `(task/delete-branch! store branches branch)`.

---

## 2. Findings (Ranked by Severity)

### Finding 1 — `seon.task/from-filed` prepends `"## Problem\n\n"` after the filed task's initial transaction, causing `seon.step.design/handoff` to permanently refuse `:problem-changed`

- **Severity:** `blocks run`
- **Status:** `REPRODUCED` on `default` (`result/ec43ee0888a32`, `result/ea302b48c4884`; also hit live in `s5r3` on `:chain-f1f6dc8e6652` at commit `#uuid "6acf6b0a-ee0f-5de1-b248-00daef787845"`, where attempt `"9107c5cedfe0"` passed all other checks in `seon.step.design-test/handoff`, failed `:problem-changed` across 13 test runs, and terminated `:capped` after 53 model calls).
- **Step:** Design step handoff (`seon.step.design/handoff` inside `seon.step.design-test/handoff`) when chaining a filed task from `resources/seon/tasks/*.edn`.
- **Symbols:** `seon.task/from-filed` vs `seon.step.design/created-problem` / `seon.step.design/handoff`.
- **Exact Excerpts:**
  - `seon.task/from-filed` (`src/seon/task.clj:843-853`):
    ```clojure
    (let [[design prove implement] (mapv #(template database %) [:design :prove :implement])
          plan (:seon.task/plan filed)
          problem? (contains? (step/sections (or plan "")) "problem")
          fresh #(let [id (id/hex-id 48)]
                   (assoc % :seon.task/id id :seon.task/sym (symbol (name (ns- of (:seon.task/sym filed))) id)))
          prove (fresh prove)
          implement (fresh implement)]
      [(cond-> (merge design (dissoc filed :seon.task/proofs))
         (and plan (not problem?)) (assoc :seon.task/plan (str "## Problem\n\n" plan)))
       prove implement])
    ```
  - `seon.step.design/created-problem` (`src/seon/step/design.clj:9-17`) and `seon.step.design/handoff` (`src/seon/step/design.clj:46`):
    ```clojure
    (defn- created-problem
      "The Problem section of `task` at the earliest transaction that asserted its `:seon.task/plan`."
      {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :string]}
      [database task]
      (let [rows (db/q '[:find ?tx ?plan :in $ ?id :where [?e :seon.task/id ?id] [?e :seon.task/plan ?plan ?tx true]]
                       (db/history database) (:seon.task/id task))
            [_ plan] (first (sort-by first rows))]
        (if (map? rows) rows (str/trim (get (step/sections (or plan "")) "problem" "")))))
    ```
    ```clojure
    (not= (created-problem database task) (str/trim (get sections "problem" ""))) (conj (refuse :problem-changed "problem"))
    ```
- **Why it breaks the run:**
  The filed task `seon.error/an-unresolved-alias-name-offers-its-nearest-core-name` (`:seon.task/id "f1f6dc8e6652"`) is seeded on `main` at boot (tx `536870914`) from `resources/seon/tasks/seon.error.edn` with a plain `:seon.task/plan` string that has **no** `## Problem` heading. When `chain!` forks `:chain-f1f6dc8e6652` from `main` and transacts `from-filed`'s first task (which reuses `:seon.task/id "f1f6dc8e6652"`), the earliest `[?e :seon.task/plan ?plan ?tx true]` datom in `(db/history database)` is tx `536870914` (without `## Problem`), while the current `:seon.task/plan` at tx `536872094` has `"## Problem\n\n"` prepended by `from-filed`.
  Consequently:
  - `(created-problem database task)` evaluates `(get (step/sections plan-at-536870914) "problem" "")` → `""`.
  - `(str/trim (get sections "problem" ""))` evaluates to `"An agent wrote `(str/subs s 1)`..."`.
  - `(not= "" "An agent wrote...")` is `true`, so `seon.step.design/handoff` refuses `{:seon.step.design/check :problem-changed, :seon.error/offending "problem"}` on every turn when the agent obeys the prompt (`"Keep Problem as written"`). The only way the agent could make `(created-problem database task)` equal `(get sections "problem" "")` is to delete the entire body of `## Problem`!
- **Smallest fix at owner:**
  In `seon.step.design/created-problem` (`src/seon/step/design.clj:16`), fall back to the full initial `plan` when the initial `:seon.task/plan` had no `"problem"` section heading:
  ```clojure
  (let [sections (step/sections (or plan ""))]
    (if (map? rows) rows (str/trim (get sections "problem" (or plan "")))))
  ```

---

### Finding 2 — `seon.test.runner/task-identity` hashes connection-local `:datahike.cache/connection-id` and `:datahike.cache/generation`, invalidating task-reading proof digests across connections, `as-of` snapshots, and `advance-chains!` / `merge-chains!`

- **Severity:** `blocks run`
- **Status:** `REPRODUCED` on `default` (`result/ec3b941b81f76`, `result/eee836cbbf386`, `result/e4e2c251e440a`, `result/e636eab9d8957`).
- **Step:**
  1. `seon.cluster.agent/advance-chains!` (`src/seon/cluster/agent.clj:2103-2125`) and `seon.cluster.agent/merge-chains!` (`src/seon/cluster/agent.clj:2154-2185`) calling `done-judged` (`src/seon/cluster/agent.clj:1827-1865`) → `seon.context/current-results` → `seon.test.runner/current-results`.
  2. `seon.test.runner/record-latest-tx` (`src/seon/test/runner.clj:1992`) when `carried-digests` is absent (`tested = (db/as-of database (:seon.test.run/basis-t run))`).
  3. Any function or namespace declaration edit by the agent during Design or Prove.
- **Symbols:** `seon.test.runner/task-identity` / `seon.test.runner/program-revisions` vs `seon.cluster.agent/advance-chains!` / `seon.cluster.agent/done-judged` / `seon.test.runner/current-results`.
- **Exact Excerpts:**
  - `seon.test.runner/task-identity` (`src/seon/test/runner.clj:1342-1350`) and `seon.test.runner/program-revisions` (`src/seon/test/runner.clj:1483-1506`):
    ```clojure
    (defn- task-identity
      "Content identity of `task` on `database`: its own revision, the sorted proof symbols of its `:seon.task/next`
      task, and the branch's `program-revisions-digest` so declaration changes on the branch move the digest."
      {:malli/schema [:=> [:cat :map] :any]}
      [{task :seon.test/task database :seon.test/branch}]
      [((requiring-resolve 'seon.task/task-revision) database (:seon.task/id task))
       (vec (sort (some->> (:seon.task/next task) (db/pull database [:seon.task/proofs]) :seon.task/proofs)))
       (program-revisions-digest database)])
    ```
    ```clojure
    (defn program-revisions
      {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :any]]}
      [database]
      (let [context (:cache-context database)]
        (when (and (:datahike.cache/connection-id context)
                   (:datahike.cache/generation context)
                   (:datahike.cache/conservative-revision context))
          (let [projection (db/carried-projection database)]
            [(:datahike.cache/connection-id context) (:datahike.cache/generation context)
             (:datahike.cache/conservative-revision context)
             (select-keys (:datahike.cache/attribute-revisions context) ...)]))))
    ```
  - `seon.cluster.agent/advance-chains!` (`src/seon/cluster/agent.clj:2114-2123`) and `seon.cluster.agent/done-judged` (`src/seon/cluster/agent.clj:1847`):
    ```clojure
    (on-branch store branch
               (fn [on-chain]
                 (let [at-end (db/commit-database (db/db on-chain) commit)]
                   ...
                   (when (empty? (source/done-refusals at-end (done-judged database at-end branch h)))
                     (write! on-chain [[:db.fn/call #'task/advance-tx key]])))))
    ```
    ```clojure
    results (when members (context/current-results at-end members (:seon.task/sym task)))
    ```
- **Why it breaks the run:**
  `program-revisions` hashes `(:datahike.cache/connection-id context)` and `(:datahike.cache/generation context)` (a random per-connection UUID minted when a Datahike connection is opened in `store/open-branch!`), plus `(:datahike.cache/conservative-revision context)` (the transaction `t` of the last non-carried-attribute transaction on that connection):
  1. When the Design or Prove agent runs `seon.step.design-test/handoff` or `seon.step.prove-test/handoff` on its branch connection, `record-tx` stores `:seon.test.member/reach-digest` computed with that connection's `[:connection-id :generation :conservative-revision]`.
  2. If the branch connection is ever reopened by `on-branch` (or inspected via `(db/commit-database (db/db main-conn) commit)` / `(db/branch-database (db/db main-conn) branch)`), `:datahike.cache/generation` (and/or `:connection-id`) differs (`"3e5ca544..."` vs `"ea64b853..."` in `result/eee836cbbf386`), so `(runner/current-results at-end proofs (:seon.task/sym task))` returns `[]` (`result/ec3b941b81f76`)! When `results` is `[]`, `source/done-refusals` returns `:seon.source/test-evidence-error` (`"Every proof needs a current recorded result"`), and `advance-chains!` silently refuses to advance the chain!
  3. Similarly, in `record-latest-tx` (`src/seon/test/runner.clj:1992`), when `carried-digests` is nil, `tested` is `(db/as-of database (:seon.test.run/basis-t run))`. `db/as-of` strips `:cache-context` (`(:cache-context (db/as-of db t))` is `nil`, `result/e636eab9d8957`), so `(program-revisions-digest tested)` is `nil` during `record-latest-tx` while non-nil during `current-results`!
  4. Furthermore, even on the same connection during an agent's step, any transaction that touches a non-evidence attribute (or any function/schema declaration edit) increments `:datahike.cache/conservative-revision`, which changes `task-identity` and invalidates the proof digest of `seon.step.design-test/handoff` and `seon.step.prove-test/handoff`.
- **Smallest fix at owner:**
  In `seon.test.runner/task-identity` (`src/seon/test/runner.clj:1342-1350`), replace connection-local `(program-revisions-digest database)` with content-addressed program declaration state:
  ```clojure
  (defn- task-identity [{task :seon.test/task database :seon.test/branch}]
    [((requiring-resolve 'seon.task/task-revision) database (:seon.task/id task))
     (vec (sort (some->> (:seon.task/next task) (db/pull database [:seon.task/proofs]) :seon.task/proofs)))
     (id/digest 64 [(program/digest-map database {:seon.program/max-datoms 100000})])])
  ```
  `program/digest-map` is a pure function of the database value's declarations (`[declaration-identity definition-digest]`), identical across connections, `commit-database`, `branch-database`, and `as-of`, and changes if and only if a program declaration (`seon.fn`, `seon.ns`, `seon.test`, `seon.schema`) changes.

---

### Finding 3 — `seon.step.prove/handoff` refuses `:unreached` because (A) `seon.fn/tests-reaching` stops its reverse call walk at depth 2 when closer tests exist, and (B) `seon.step/cited-fns` requires every function mentioned in `## Approach` to be reached by the new proof

- **Severity:** `blocks run`
- **Status:** `REPRODUCED` on `default` (`result/e9b582a1d5a5b`, `result/e48d4b69bf437`, `result/e50bc195d6718`, `result/efa6815567ea1`).
- **Step:** Prove step handoff (`seon.step.prove/handoff` inside `seon.step.prove-test/handoff`).
- **Symbols:** `seon.step.prove/handoff` vs `seon.fn/tests-reaching` / `seon.fn/closure-walk` and `seon.step/cited-fns`.
- **Exact Excerpts:**
  - `seon.step.prove/handoff` (`src/seon/step/prove.clj:29-35,43`):
    ```clojure
    approach (get (step/sections (:seon.task/plan task "")) "approach" "")
    targets (into (step/changed database :seon.fn/sym) (step/cited-fns database approach))
    unreached (vec (sort (remove #(some (set proofs) (fn/tests-reaching database %)) targets)))
    ...
    (and (seq proofs) (seq unreached)) (conj (refuse :unreached unreached))
    ```
  - `seon.fn/closure-walk` (`src/seon/fn.clj:1004-1017`) and `seon.fn/reaching-tests` (`src/seon/fn.clj:1022-1025`):
    ```clojure
    (if (or (empty? frontier)
            (and depth (<= depth (count levels)) (seq (tests-of levels))))
      {:levels levels :visited visited}
      (let [next-frontier (into #{} (comp (mapcat #(direct-callers database %)) (remove visited)) frontier)]
        (if (empty? next-frontier)
          {:levels levels :visited visited}
          (recur (conj levels (step-level database next-frontier))
                 (into visited next-frontier)
                 next-frontier))))
    ...
    (defn reaching-tests
      {:malli/schema [:=> [:cat :seon.fn/closure-request] [:vector :qualified-symbol]]}
      [{database :seon.db/db seeds :seon.fn/seeds}]
      (let [{:keys [levels]} (closure-walk database seeds 2)]
        (vec (sort (tests-of levels)))))
    ```
- **Why it breaks the run:**
  Two independent mechanisms in `seon.step.prove/handoff` cause `:unreached` refusals on this task:
  - **3A (Depth-2 cutoff in `seon.fn/tests-reaching` vs the task's required SCI evaluation test):**
    The filed task's problem statement explicitly requires: *"proven by a deftest over the SCI evaluation of `(str/subs \"abc\" 1)`"*.
    In the call graph of `seon.error/nearest-names` (`result/e9b582a1d5a5b`):
    - Level 1 callers: `[my.program/nearest seon.error/nearest-calls]`
    - Level 2 callers: `[my.program/not-found seon.error/reply-value]`
    - Level 3+ callers: `seon.sci.eval/shown-failure` → `seon.sci.eval/eval-string` → `seon.repl/...`
    Because `seon.error-test/reply-value-attaches-nearest-vars-for-unresolved-symbol` already calls `seon.error/reply-value` at level 2, `(seq (tests-of levels))` is non-empty at `count levels == 2`, so `(closure-walk database #{'seon.error/nearest-names} 2)` **terminates at depth 2**!
    Any deftest that evaluates `"(str/subs \"abc\" 1)"` through SCI (`seon.sci.eval/eval-string` or `seon.sci.eval/shown-failure`) is at caller depth ≥ 3 from `seon.error/nearest-names` (`result/e50bc195d6718`), so `(fn/tests-reaching database 'seon.error/nearest-names)` **does not contain the new proof**, and `seon.step.prove/handoff` refuses `{:seon.step.prove/check :unreached, :seon.error/offending [seon.error/nearest-names]}` unless the proof also directly calls `seon.error/nearest-names`, `seon.error/nearest-calls`, or `seon.error/reply-value`!
  - **3B (`step/cited-fns` on `## Approach` requires every function mentioned in `## Approach` to be reached by `proofs`):**
    `targets` is `(into (step/changed database :seon.fn/sym) (step/cited-fns database approach))`, and `unreached` requires **every** symbol in `targets` to be reached by `proofs`. The filed task says: *"Read `seon.error/nearest-names` and `seon.sci.eval/interpretation-error`."* (`seon.sci.eval/interpretation-error` is `install-row!`'s refusal builder and is not in the call chain of `seon.error/nearest-names` at all, `result/efa6815567ea1`). If the Design agent mentions `seon.sci.eval/interpretation-error` (or any existing helper or contrast function) in `## Approach` — or defines a scratch function on the branch, as `s5r3`'s Design agent did by defining `seon.error/plan-now` and `seon.error/plan-text` — `seon.step.prove/handoff` demands that the new proof reach every one of them, failing with `:unreached`.
- **Smallest fix at owner:**
  1. In `seon.step.prove/handoff` (`src/seon/step/prove.clj:31`), use `seon.test.runner/tests-reaching` (`src/seon/test/runner.clj:1427`, which computes the full forward call-graph closure from `proofs` without a depth-2 cutoff) instead of `seon.fn/tests-reaching`:
     ```clojure
     unreached (vec (sort (remove #(seq (runner/tests-reaching database proofs %)) targets)))
     ```
  2. If `targets` has `cited-fns` from `## Approach`, require the proofs to reach at least the changed functions (if any) and at least one cited function in `## Approach` (or only functions in the target namespace), rather than refusing when `## Approach` mentions a caller or contrast function in another namespace.

---

### Finding 4 — `seon.cluster.agent/lifecycle-facts` only queries `main`, so ended attempts on `:chain-*` branches are never stopped, relaunched on `:capped`/`:stalled`, or handled on `:sent-back`

- **Severity:** `blocks run` (on any `:capped`, `:stalled`, or `:sent-back` chain attempt; leaks running Flow graphs on every `:done` chain step)
- **Status:** `REPRODUCED` on `default` (`s5r3` live state on `:chain-f1f6dc8e6652`, where `"attempt/9107c5cedfe0"` ended `:capped` at tx `536872155` with `:seon.task/outcome :none` and `:seon.task/execution :running`, permanently wedging the chain with `starts? = false` and no relaunch or stop).
- **Step:** `seon.cluster.agent/run-pass!` (`src/seon/cluster/agent.clj:2616-2639`) → `lifecycle-facts`, `advance-chains!`, and `db<->flow`.
- **Symbols:** `seon.cluster.agent/lifecycle-facts` vs `seon.cluster.agent/write-chain-attempt!` and `seon.cluster.agent/chain-read` / `seon.cluster.agent/advance-chains!`.
- **Exact Excerpts:**
  - `seon.cluster.agent/lifecycle-facts` (`src/seon/cluster/agent.clj:2365-2372`):
    ```clojure
    [database store branches {started :seon.flow.actions/started}]
    (let [rows (db/q '[:find ?a ?k ?h ?b ?id
                       :where [?a :seon.task/assignment ?k] [?a :seon.task/assigned ?h] [?a :seon.task/branch ?b]
                       [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                     database)
    ```
  - `seon.cluster.agent/chain-read` (`src/seon/cluster/agent.clj:1761-1767`):
    ```clojure
    latest (last (sort-by second rows))
    starts? (and (= :running execution) (not (#{:done :merged} (nth latest 3 nil)))
                 (not-any? #(= :none (nth % 3)) rows))
    open (for [[k _ id outcome end] (sort rows) :when (= :none outcome end)]
           {:seon.task/assigned handle :seon.task/assignment k :seon.agent/id id :seon.task/branch branch
            :seon.task/execution execution :seon.chain/branch branch})
    ```
  - `seon.cluster.agent/chain-ends` (`src/seon/cluster/agent.clj:1816-1820`):
    ```clojure
    rows (db/q '[:find ?h ?k ?id ?c ?t :where
                 [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end :done]
                 [?a :seon.task.attempt/end-commit ?c] [?a :seon.task/assigned ?h]
                 [?h :seon.task/id ?id] [?h :seon.task/execution :running]
                 [?a :seon.task.attempt/end :done ?t true]]
               on-chain)
    ```
- **Why it breaks the run:**
  `write-chain-attempt!` (`src/seon/cluster/agent.clj:2079-2093`) writes chain attempt entities **only on the `:chain-*` branch**, never on `main` (`database`), until the final merge.
  1. `lifecycle-facts` only queries `database` (`main`), so it never sees any chain attempt.
  2. `chain-read` only includes attempts in `:seon.flow.actions/open` `:when (= :none outcome end)`. Once an attempt sets `:seon.task.attempt/end` (to `:done`, `:capped`, `:stalled`, `:sent-back`, etc.), it disappears from `open`, and because `lifecycle-facts` never sees it, **no `:stop` action is ever emitted for it**. Furthermore, `db<->flow` (`src/seon/cluster/agent.clj:2589`) runs `:stop` against the root `routing`, not the chain's child routing (`:seon.agent/chains`), so finished Design and Prove Flow graphs remain alive in the chain's child routing until `finish-chains!` retires the entire branch!
  3. Worse, `chain-ends` (`src/seon/cluster/agent.clj:1816`) only queries `[?a :seon.task.attempt/end :done]`. When a chain attempt ends with:
     - `:sent-back` (via `(my.task/send-back! id reason)`, which TASK-CHAIN §2.3 explicitly gives Prove and Implement to return a flawed step), or
     - `:capped` / `:stalled` (as happened in `s5r3` on `"attempt/9107c5cedfe0"`),
     nothing ever sets `:seon.task/outcome` on that attempt (it remains `:none`). In `chain-read`, `(not-any? #(= :none (nth % 3)) rows)` is `false` because `(nth % 3)` is `outcome` (`:none`), so `starts?` is permanently `false`! The chain is completely wedged: the ended attempt is never closed, never relaunched, and never advances or returns to the target step.
- **Smallest fix at owner:**
  In `seon.cluster.agent/chain-read` and `advance-chains!` (`src/seon/cluster/agent.clj:1731-1825, 2103-2125`), handle non-`:done` ended chain attempts on the chain branch: stop ended agent ids in the chain's child routing (`chain-routing!`), and for `:sent-back` / `:capped` / `:stalled` close the attempt (`:seon.task/outcome :closed`) so `:sent-back` wakes the target task and `:capped`/`:stalled` either relaunches (up to the budget) or halts the chain task cleanly.

---

### Finding 5 — `seon.test/current-result?` calls 2-arity `(runner/reach-digests database [test-symbol])` without `:seon.test/task` or `:seon.test.member/dynamic-calls`, so `seon.test/stale` reports `seon.step.design-test/handoff` and `seon.step.prove-test/handoff` stale on every turn

- **Severity:** `costs hand act` / `degrades context` (causes `seon.turn/test-step` to re-run the handoff test suite on every single model turn, even read-only turns, and makes `runner/red-at-current-reach?` return `false` for task-reading or dynamically-reaching tests)
- **Status:** `REPRODUCED` on `default` (`result/ea6e1eec4ab31`).
- **Step:** `seon.turn/test-step` (`src/seon/turn.clj:5515-5541`) → `seon.test/stale` (`src/seon/test.clj:1662-1673`) → `seon.test/current-result?` (`src/seon/test.clj:1644-1660`), and `seon.test.runner/red-at-current-reach?` (`src/seon/test/runner.clj:2647-2656`).
- **Symbols:** `seon.test/current-result?` / `seon.test.runner/red-at-current-reach?` vs `seon.test.runner/current-results` / `seon.test.runner/reach-digests`.
- **Exact Excerpts:**
  - `seon.test/current-result?` (`src/seon/test.clj:1644-1660`):
    ```clojure
    (defn current-result?
      "True when `test-symbol` has a stored result in `database` whose `:seon.test/reach-digest` matches its current reach
      digest and `:seon.test/reach-unknown` is absent."
      {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] :boolean]}
      [database test-symbol]
      (let [entity (db/pull database [:seon.test/reach-digest :seon.test/reach-unknown :seon.test/run-basis-t]
                            [:seon.test/sym test-symbol])
            digests (when (and (string? (:seon.test/reach-digest entity))
                               (nil? (:seon.test/reach-unknown entity)))
                      (runner/reach-digests database [test-symbol]))]
        (boolean (and (map? digests)
                      (= (:seon.test/reach-digest entity) (get digests test-symbol))))))
    ```
  - `seon.test.runner/current-results` (`src/seon/test/runner.clj:2536-2544`):
    ```clojure
    extra-roots (into {} (keep (fn [[sym member]]
                                 (when-let [calls (seq (:seon.test.member/dynamic-calls member))]
                                   [sym (vec calls)])))
                      latest)
    task-in (when task {:seon.test/task (db/pull database [:*] [:seon.task/sym task]) :seon.test/branch database})
    digests (reach-digests database symbols extra-roots task-in)
    ```
- **Why it breaks the run:**
  When `seon.step.design-test/handoff` or `seon.step.prove-test/handoff` runs via `seon.turn/run-proofs`, `record-tx` computes `:seon.test/reach-digest` using 4-arity `(reach-digests database symbols extra-roots task)` where `extra-roots` includes `:seon.test.member/dynamic-calls` (`[seon.program/edn-symbol-text?]`) and `task` is the active task map.
  When `seon.turn/test-step` checks `(test/stale branch proofs)` at the end of every model turn, `test/stale` calls `(test/current-result? database sym)`, which calls 2-arity `(runner/reach-digests database [test-symbol])` with `extra-roots = {}` and `task = nil`.
  As reproduced in `result/ea6e1eec4ab31`:
  - Recorded `:seon.test/reach-digest` on `seon.step.design-test/handoff`: `"c6bb07877292fc387fb216b8779207b5d83b13ab14bd6df31ff5df28f7822871"`
  - 2-arity `(runner/reach-digests at-end '[seon.step.design-test/handoff])`: `"a08365be0e8fa7fcbbebdc6e853749e13d1c952efcd6e004628d57b887a0865c"`
  - `(seon.test/current-result? at-end 'seon.step.design-test/handoff)` → `false`!
  Because `current-result?` is always `false`, `(seon.test/stale branch proofs)` returns `[seon.step.design-test/handoff]` on **every single turn** (which is why `s5r3` executed and recorded 13 full test runs across read-only search/inspect turns). Even when the proof has dynamic calls on Implement (if the proof uses `eval-string`), `current-result?` and `red-at-current-reach?` (`src/seon/test/runner.clj:2647-2656`, which also calls 2-arity `reach-digests` without `extra-roots`) will mismatch the recorded `:reach-digest` whenever `:seon.test.member/dynamic-calls` is non-empty!
- **Smallest fix at owner:**
  1. In `seon.test.runner/red-at-current-reach?` (`src/seon/test/runner.clj:2653`), pass `extra-roots` from `:seon.test.member/dynamic-calls` into `(reach-digests database symbols extra-roots)`, matching `current-results` (`src/seon/test/runner.clj:2536-2541`).
  2. In `seon.turn/test-step` (`src/seon/turn.clj:5523`), check currency via `(runner/current-results branch proofs (:seon.task/sym task))` (which supplies both `extra-roots` and `task-in`) instead of `(test/stale branch proofs)`.

---

### Finding 6 — `seon.context/step-section` hides proof failures from `:seon.context/step`, while `seon.context/proofs-section` truncates `:seon.test.failure/actual` to 240 chars (`:seon.context/max-failure-chars`)

- **Severity:** `degrades context`
- **Status:** `REPRODUCED` on `default` (`result/ec43ee0888a32`, `steps/264/output.txt` lines 64-90).
- **Step:** Agent context assembly (`seon.context/current-state` → `seon.context/step-section` and `seon.context/proofs-section`).
- **Symbols:** `seon.context/step-section` (`src/seon/context.clj:951-974`) and `seon.context/proof-entry` (`src/seon/context.clj:901-927`) vs `seon.step.design/handoff` (`src/seon/step/design.clj:19-49`).
- **Exact Excerpts:**
  - `seon.context/step-section` (`src/seon/context.clj:957-971`):
    ```clojure
    refusals (->> (source/done-refusals database (step-observation database task))
                  (remove #(and (seq (:seon.task/proofs task))
                                (= :seon.source/test-evidence-error (:seon.error/type %))))
                  vec)
    ```
  - `seon.context/proof-entry` (`src/seon/context.clj:907-912`):
    ```clojure
    failure (first (:seon.test.member/failures result))
    ```
    with `:seon.context/max-failure-chars 240` (`src/seon/context.clj:378`).
- **Why it breaks the run:**
  On turn 1 of Design (and Prove), `seon.step.design/handoff` returns multiple refusal maps in a single vector (on turn 1: `:sections`, `:problem-changed`, `:producer-not-cited`, `:data-not-modeled`).
  Because `seon.step.design-test/handoff` asserts `(is (= [] (design/handoff branch task)) "the task is satisfied")`, all refusals are serialized into a single `:seon.test.failure/actual` string `(not (= [] [{:seon.error/at ...} {:seon.error/at ...} ...]))` (1,179 chars on turn 1).
  `proof-entry` truncates `:seon.test.failure/actual` to 240 characters (`:seon.context/max-failure-chars`), which shows **only the first refusal map** (and cuts off before the second refusal's `:seon.step.design/check` key appears!). The agent must either fix refusals one by one across extra turns or manually run `(seon.step.design/handoff ...)` at the REPL to see the rest.
- **Smallest fix at owner:**
  In `seon.step.design-test/handoff` (`test/seon/step/design_test.clj:21-22`) and `seon.step.prove-test/handoff` (`test/seon/step/prove_test.clj:19-20`), compare `(mapv (juxt :seon.step.design/check :seon.error/offending) (design/handoff branch task))` (and `:seon.step.prove/check` in `prove_test`) against `[]`, so the failure's `:actual` is compact (e.g. `[[:sections ["what-exists" "approach"]] [:producer-not-cited [:what-exists-function :what-exists-test :approach-function]] ...]`, < 200 chars total) and every refusal check and offending value fits inside `:seon.context/max-failure-chars` on turn 1.

---

## 3. Five-Line Summary

1. **Blocker 1 (`REPRODUCED`, killed `s5r3`):** `seon.task/from-filed` prepends `"## Problem\n\n"` after the filed task's initial tx on `main`, while `seon.step.design/created-problem` parses `"problem"` from the earliest tx in history (returning `""`), so `seon.step.design/handoff` permanently refuses `:problem-changed` when the agent keeps `## Problem` unchanged.
2. **Blocker 2 (`REPRODUCED`):** `seon.test.runner/task-identity` hashes `program-revisions-digest` (which embeds connection-local `:datahike.cache/connection-id`, `:datahike.cache/generation`, and `:datahike.cache/conservative-revision`), so `current-results` returns `[]` whenever a branch connection is reopened or inspected via `commit-database` in `advance-chains!` / `merge-chains!` or `as-of` in `record-latest-tx`.
3. **Blocker 3 (`REPRODUCED`):** `seon.step.prove/handoff` checks reach via `seon.fn/tests-reaching` (which stops walking callers at depth 2 because `seon.error-test` already has level-2 tests on `seon.error/reply-value`), so a deftest evaluating `(str/subs "abc" 1)` through SCI (depth ≥ 3 from `seon.error/nearest-names`) is rejected as `:unreached [seon.error/nearest-names]`, and any extra function cited in `## Approach` (such as `seon.sci.eval/interpretation-error`) is also required to be reached.
4. **Blocker 4 (`REPRODUCED` on `s5r3`):** `seon.cluster.agent/lifecycle-facts` only queries `main` (`database`), so chain attempts on `:chain-*` branches are never stopped when `:done`, and never closed, relaunched, or routed when ending `:capped`, `:stalled`, or `:sent-back` (permanently wedging the chain).
5. **Context/Cost (`REPRODUCED`):** `seon.test/current-result?` and `seon.test.runner/red-at-current-reach?` call 2-arity `reach-digests` without `task` or `dynamic-calls` (causing `seon.turn/test-step` to re-run handoff tests on every turn), and `seon.context/proof-entry`'s 240-char truncation of `design/handoff`'s full error maps hides all but the first refusal on turn 1.
