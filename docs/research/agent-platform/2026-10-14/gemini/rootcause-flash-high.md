# Root Causes Behind the Seon Task Backlog

An analysis of all 872 tasks declared across 101 files in `resources/seon/tasks/*.edn`.

In accordance with Seon's design laws (`AGENTS.md`), software grows by accretion, data is designed first, and bugs are fixed at the producer rather than via guards, fences, retries, or workarounds. Many tasks in the backlog were filed piecemeal as individual symptoms of a few upstream producer defects.

---

## Ranked Root Cause Clusters

### Rank 1: Automatic Test Step Execution in Turn Loop
**Producer**: `seon.turn/test-step` (`src/seon/turn.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/turn.clj` (lines 5454–5488), the turn loop's `test-step` automatically executes task proofs and whole-program reaching tests after every single evaluation pass whenever an entity changes or a task is held:
```clojure
(defn- test-step
  "The pass's automatic test step: the held task's proofs, then the tests reaching what the run changed
  (`seon.test/request-changes` over `run`, its recording transaction's databases and entities), on one
  acquisition of the agent's world; tests reaching a host-bound change are named as its platform proof, not
  run. Within the dial `:seon.config.test/step-time-limit-ms` the proofs take the bound first; the reaching
  request gets what they left, at least 1 ms, so it selects its tests and reports those it did not start as
  pending. A throw from either becomes the error beside the pass's values, never a replacement for them; nil
  when no request ran, and nothing is acquired when the agent holds no proof and the run changed nothing."
  {:malli/schema [:=> [:cat :map :seon.agent/id [:maybe :seon.turn.loop/recording]]
                  [:maybe :seon.turn.loop/test-summary]]}
  [cluster agent-id run]
  (try
    (let [proofs? (seq (task-proofs (db/db (:seon.db/connection cluster)) agent-id))
          changed (if run
                    (@test-request-changes (:seon.turn.loop/before run) (:seon.turn.loop/after run) (:seon.turn.loop/entities run))
                    [])]
      (when (or proofs? (seq changed))
        (let [limit (step-limit-ms cluster agent-id)
              execution (@cluster-agent-acquire-context! cluster agent-id)
              connection (:seon.db/connection execution)
              platform (platform-proof (db/db connection) changed)
              proofs (when proofs? (run-proofs execution agent-id limit (set (:seon.turn.loop/pending platform))))
              left (- limit (long (or (:seon.test/elapsed-ms proofs) 0)))
              reaching (run-reaching execution (max 1 left) changed platform)
              ;; a derivation that fails is shown beside the step's values, never in place of them
              red (try (step-reds execution agent-id)
...
```
This automatic background test execution triggers unbidden on ordinary REPL evaluations. It executes whole-program reach, allocates 700+ MB per pass, swallows exceptions, attempts writes to main during evaluation, and fails to deliver the results back to the agent's REPL reply.

#### 2. Member Tasks
- `seon.turn/the-test-step-results-never-reach-the-eval-reply`: `seon.turn/step` attaches `:seon.turn.loop/tests` to internal loop state but drops it from the evaluation response.
- `seon.turn/an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step`: Running `test-step` on every pass repeatedly constructs test worlds and allocations.
- `seon.turn/a-model-pass-never-sees-its-test-step`: An agent's model turn runs reaching tests automatically after evaluating a form, but results are omitted from model context.
- `seon.turn/a-throwing-test-step-loses-its-cause`: Catch block in `test-step` masks underlying exceptions with synthetic diagnostic maps.
- `seon.turn/the-test-step-writes-nothing-on-main`: Root evaluations on main trigger the test step, which attempts to write test runs to the cluster head.
- `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second`: An evaluation defining a single function incurs 500 ms in automatic test step overhead.
- `seon.turn/a-one-test-change-tests-in-proportion-to-its-reach`: Changing one function causes `test-step` to expand and run transitive caller closures across the whole repository.
- `seon.turn/a-fresh-agents-first-eval-request-costs-in-proportion-to-its-forms`: Initial evaluation on a fresh branch pays whole-program reaching test discovery.
- `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step`: Evaluating a test definition pays seconds of automatic test-step compilation and discovery.
- `seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step`: A schema referencing a branch function crashes `test-step` during automatic reach derivation.
- `seon.test/an-observed-change-a-script-test-reaches-does-not-refuse-the-step`: Automatic test-step attempts to execute script-level tests that are not branch-runnable.
- `my.test/test-output-and-an-empty-reaching-run-reach-the-reply`: Coupling test step execution to turn replies leaks raw test runner strings.
- `seon.turn/a-bounded-step-that-leaves-queued-tests-wakes-nothing-when-no-member-ran`: Partial automatic test runs leave orphan queues across loop iterations.

#### 3. The Fix at the Owner
Decouple test execution from the ordinary REPL evaluation pipeline in `seon.turn/step`. Per AGENTS.md, agents operate through explicit eval, reload, and test requests (`my.test/check`, `seon.test/run`), or only run test gates during explicit handoffs/merges. Remove the unsolicited `(test-step cluster agent-id run)` invocation from every evaluation cycle.
- **Retires Outright**: `seon.turn/the-test-step-results-never-reach-the-eval-reply`, `seon.turn/an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step`, `seon.turn/a-model-pass-never-sees-its-test-step`, `seon.turn/a-throwing-test-step-loses-its-cause`, `seon.turn/the-test-step-writes-nothing-on-main`, `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second`, `seon.turn/a-one-test-change-tests-in-proportion-to-its-reach`, `seon.turn/a-fresh-agents-first-eval-request-costs-in-proportion-to-its-forms`, `seon.turn/a-bounded-step-that-leaves-queued-tests-wakes-nothing-when-no-member-ran` (9 tasks).
- **Simplifies**: `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step`, `seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step`, `seon.test/an-observed-change-a-script-test-reaches-does-not-refuse-the-step`, `my.test/test-output-and-an-empty-reaching-run-reach-the-reply` (4 tasks).
- **Leaves**: None.

---

### Rank 2: Heavyweight Isolated Test-World Construction
**Producer**: `seon.test/clean-request-handle` (`src/seon/test.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/test.clj` (lines 2219–2284), `clean-request-handle` builds an entire synthetic database world for every test execution request:
```clojure
(defn- clean-request-handle
  "Acquire the request's world: the loaded published program, boot's facts and
  the execution's own program changes.

  The base is the source commit this JVM loaded (`world-base`). A published
  commit holds program rows only,
  so no agent, turn, message or error of the execution is inherited. Boot's
  facts live on a checkpoint branch named by the digest of their inputs (base,
  cluster, effective config, process), so every request with equal inputs
  shares one preparation. A request that finds none prepares its own on a
  branch named for it alone and names the checkpoint from that complete commit
  (`registry/branch!`: the first name stands and serves a later preparer too),
...
        store (store/open-overlay! store)
        request-branch (keyword (str "agent-" (id/id)))
```
Even when running a single microsecond test, `clean-request-handle` creates an overlay store (`store/open-overlay!`), allocates checkpoint branches, computes full program diffs (`program-overlay-tx`), and forks new branch entities. This costs 1–4 seconds and 500 MB–1 GB per request, causing connection leaks and disk re-reads.

#### 2. Member Tasks
- `seon.test/a-changed-check-builds-a-world-per-request`: `my.test/check` incurs ~1s in `clean-request-handle` overhead per invocation.
- `seon.test/one-test-run-repeats-whole-program-setup`: Running a single test repeats the whole-program setup rather than executing against the held basis.
- `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`: `seon.test/run` cold and warm execution is dominated by world construction rather than test body execution.
- `seon.test/a-one-test-proof-check-allocates-a-gigabyte`: Constructing the overlay store and branching consumes ~1 GB of allocations per proof check.
- `seon.test/a-test-world-has-no-agent`: Synthesized test worlds purge all agent context and identity from the execution environment.
- `seon.test/an-agent-branch-test-run-cannot-branch-its-fresh-checkpoint`: Checkpoint collision and branching concurrency issues inside `clean-request-handle`.
- `seon.test/test-release-closes-branch-connections-out-of-order`: Teardown of the multiple nested connections opened by `clean-request-handle` races on release.
- `seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record`: Re-basing the world on the JVM's loaded commit instead of the branch fork point causes state drift.
- `seon.cluster.store/a-test-world-rereads-index-nodes-from-disk`: Opening fresh overlay branches invalidates node caches, forcing repetitive disk seeks.

#### 3. The Fix at the Owner
Execute tests directly against the agent's held database value using in-memory `d/with` or cheap branch views, rather than creating full Konserve store overlays and physical checkpoint branches. Share the runtime's existing immutable base connection.
- **Retires Outright**: `seon.test/a-changed-check-builds-a-world-per-request`, `seon.test/one-test-run-repeats-whole-program-setup`, `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`, `seon.test/a-one-test-proof-check-allocates-a-gigabyte`, `seon.test/a-test-world-has-no-agent`, `seon.test/an-agent-branch-test-run-cannot-branch-its-fresh-checkpoint`, `seon.cluster.store/a-test-world-rereads-index-nodes-from-disk` (7 tasks).
- **Simplifies**: `seon.test/test-release-closes-branch-connections-out-of-order`, `seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` (2 tasks).
- **Leaves**: None.

---

### Rank 3: Definition Digest Mismatch Rejection Fence
**Producer**: `seon.db/write-digest-error` (`src/seon/db.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/db.clj` (lines 5599–5645), `write-digest-error` is an assertion fence inside `seon.db/transact!` that checks whether the incoming `:seon.program/definition-digest` on a declaration entity exactly matches `(definition-digest-of projection entity-map namespace-entity-map)`:
```clojure
(defn- write-digest-error
  "The first function or test row this report changed whose stored definition
  digest is not the one its final value produces with its namespace's resolver
  context, as `seon.fn` produces it. Every digest comparison (acquisition,
  install, test reach) trusts the stored digest, so the writer decides it.
  Only a change to an attribute the row's family owns (`seon.program/shapes-in`,
  the attributes `canonical-row` hands the digest) is checked: evidence written
  onto a declaration entity (results, reach) is not its definition."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report
                       [:vector :qualified-keyword]]
                  [:or :nil :seon.error/base]]}
  [projection report identity-attrs]
...
              (let [entity-map (declaration-value projection database identity-attrs entity-id)
                    namespace-name (second (or (:seon.fn/ns entity-map) (:seon.test/ns entity-map)))
                    namespace-entity (some->> namespace-name (d/datoms database :avet :seon.ns/name) first :e)
                    stored (:seon.program/definition-digest entity-map)
                    produced (definition-digest-of projection entity-map (some-> namespace-entity namespace-of))]
                (when (not= stored produced)
                  (diagnostic
                   {::transaction-refused true
                    :seon.error/message
                    (str "The stored definition digest of " (pr-str [identity-attribute (get entity-map identity-attribute)])
                         " is not the one its definition produces. Write the entity its canonical writer produced.")
                    :seon.error/layer :database-write
                    :seon.error/operation 'seon.db/transact!
                    :seon.error/member :seon.program/definition-digest
                    :seon.error/expected produced
                    :seon.error/offending stored}))
```
The REPL parsing/evaluation pipeline and the database writer use separate paths to calculate the digest. Whenever a definition includes qualified schema properties (e.g. `[:gen/elements ...]`), newly added refers, multiline docstrings, or `def` of `fn`, the two paths calculate differing SHA hashes, causing the writer fence to reject valid code with: `"The stored definition digest of [...] is not the one its definition produces"`.

#### 2. Member Tasks
- `seon.db/the-digest-refusal-names-no-cause`: Digest refusal error message gives no explanation of which AST attribute diverged.
- `seon.db/a-deftest-after-a-same-request-refer-is-refused-by-digest`: Evaluating a require refer and a deftest in the same request yields divergent digests.
- `seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest`: Schema properties with qualified keys are normalized differently in the producer vs `definition-digest-of`.
- `seon.db/a-long-defn-after-a-def-of-fn-or-a-namespace-form-change-is-refused-for-its-digest`: Docstring formatting and `def`-of-`fn` constructs cause digest mismatch.
- `seon.turn.batch/redefining-a-file-function-in-seon-error-is-refused-by-its-digest`: Redefining a function with unchanged contract is rejected due to namespace context drift.
- `seon.program/sci-scratch-definitions-refuse-their-produced-digest`: Scratch definitions in SCI fail the writer's digest verification.
- `seon.test/a-stale-stored-definition-digest-refuses-every-test-world`: Propagated digest mismatches reject test-world transactions.

#### 3. The Fix at the Owner
Delete the `write-digest-error` fence in `seon.db/transact!`. In accordance with AGENTS.md ("fix at the producer, never a guard or fence"), the database writer should derive and assert `:seon.program/definition-digest` canonically via `namespace-digests-tx`, eliminating the two-evaluator digest comparison entirely.
- **Retires Outright**: `seon.db/the-digest-refusal-names-no-cause`, `seon.db/a-deftest-after-a-same-request-refer-is-refused-by-digest`, `seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest`, `seon.db/a-long-defn-after-a-def-of-fn-or-a-namespace-form-change-is-refused-for-its-digest`, `seon.turn.batch/redefining-a-file-function-in-seon-error-is-refused-by-its-digest`, `seon.program/sci-scratch-definitions-refuse-their-produced-digest` (6 tasks).
- **Simplifies**: `seon.test/a-stale-stored-definition-digest-refuses-every-test-world` (1 task).
- **Leaves**: None.

---

### Rank 4: Unsynchronized Throwaway JVM and Classpath Asset Management
**Producer**: `script/seon/operator.clj` (`prune-program-classes!`, `committed-source!`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `script/seon/operator.clj` (lines 690–713 and 912–943), the CLI operator manages classpath directories and source staging with uncoordinated filesystem mutations:
```clojure
(defn- prune-program-classes!
  "Keep `program` and the most recently used class directories; delete the
  rest and any staging a day old (a launch that died filling it). A directory
  a live process names on its classpath, or holds (`hold!`) before it has
  launched, is never deleted: a concurrent start or check still loads classes
  from it (NoSuchFileException on a class the other JVM needed)."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [repository program]
  (let [in-use? (let [arguments (live-arguments)]
                (fn [^java.io.File directory]
                  (let [path (.getCanonicalPath directory)]
                    (or (held? repository path)
                        (some #(str/includes? % path) arguments)))))
        directories (remove in-use? (or (.listFiles (io/file repository program-classes-root)) []))
...
    (doseq [directory stale] (fs/delete-tree directory))
```
`prune-program-classes!` checks running process command lines to decide what is in use. A starting throwaway JVM that has selected a classes directory but not yet completed launch is invisible to `live-arguments`, so concurrent pruning deletes its `.class` files, triggering `NoSuchFileException`. Concurrently, `committed-source!` races on renaming staging directories for the same commit, and concurrent `clojure -Spath` calls crash inside Maven's non-thread-safe model validator.

#### 2. Member Tasks
- `seon.operator/class-prune-deletes-a-directory-a-check-is-sharing-before-its-jvm-launches`: Pruning deletes classes while a concurrent check's JVM is booting.
- `seon.operator/a-restart-prunes-the-program-classes-a-starting-check-uses`: Cluster restart with `--head` prunes classes needed by concurrent candidate checks.
- `seon.operator/a-booting-jvms-class-directory-is-pruned-under-it`: Booting replacement JVM dies with `NoSuchFileException` when its unadvertised class directory is pruned.
- `seon.operator/committed-source-same-sha-race`: Concurrent checks of the same SHA crash during `fs/move` with "Directory not empty".
- `seon.operator/check-classpath-build-races-in-tools-deps`: Concurrent checks crash in `clojure.tools.deps` / Maven `DefaultModelValidator` `ClassCastException`.
- `seon.operator/concurrent-checks-race-in-clojure-spath-classpath-resolution`: Concurrent `-Spath` invocations fail dependency resolution.
- `seon.operator/a-test-launched-jvm-exits-when-its-launcher-exits`: Test-launched child JVMs outlive parent processes when launchers crash or time out.

#### 3. The Fix at the Owner
In `script/seon/operator.clj`:
1. Use advisory file locks around class-directory allocation and tools.deps classpath resolution per deps-hash.
2. In `committed-source!`, treat an existing target directory during atomic move as a successful convergence rather than an error.
3. Bind child JVM lifecycle to the launcher's `ProcessHandle.onExit`.
- **Retires Outright**: `seon.operator/class-prune-deletes-a-directory-a-check-is-sharing-before-its-jvm-launches`, `seon.operator/a-restart-prunes-the-program-classes-a-starting-check-uses`, `seon.operator/a-booting-jvms-class-directory-is-pruned-under-it`, `seon.operator/committed-source-same-sha-race`, `seon.operator/check-classpath-build-races-in-tools-deps`, `seon.operator/concurrent-checks-race-in-clojure-spath-classpath-resolution` (6 tasks).
- **Simplifies**: `seon.operator/a-test-launched-jvm-exits-when-its-launcher-exits` (1 task).
- **Leaves**: None.

---

### Rank 5: Unclaimed and Unsynchronized Byte-Offset Splicing in Write-Back
**Producer**: `my.program/write-branch!` (`src/my/program.clj`) & `seon.cluster.source/write-back!` (`src/seon/cluster/source.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/my/program.clj` (lines 1045–1059) and `src/seon/cluster/source.clj` (lines 1989–2032), `write-branch!` and `write-back!` write declarations back to disk files using byte-offset spans:
```clojure
(defn write-branch!
  ...
  [{context :my.program/context root :seon.fn/root given :seon.program/identities}]
  (let [comparison (head-comparison context)
        {:keys [head-id compared]} comparison
        {:seon.program/keys [added changed-on-branch retracted]} (:three compared)
        identities (or given (into #{} cat [added changed-on-branch retracted]))]
    (try (cond (:seon.error/at (:answer comparison)) (:answer comparison)
               (:answer comparison)
               (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/write-branch!
                 {:seon.error/message "Your branch is the cluster's own: there is nothing to write."})
               :else
               (source/write-back!
                 {:seon.db/connection (:seon.db/connection context) :seon.fn/root root
                  :seon.source/accepted {:seon.program/identities identities
                                         :seon.source/base-commit-id head-id
                                         :seon.source/commit-id (:c-id compared)}}))
         (finally (release-comparison! comparison)))))
```
`write-branch!` modifies files on disk without checking if the calling lane holds claims for those files in `bin/lane`. Furthermore, the write-back pipeline runs a full-file analyzer (`seon.fn.analyzer`) over the updated files, consuming gigabytes of memory, and fails on immediate subsequent writes with `:conflict-digests` because disk byte spans were altered by the first write.

#### 2. Member Tasks
- `my.program/write-branch-writes-files-the-lane-has-not-claimed`: `write-branch!` writes to any file touched by branch declarations without lane claim verification.
- `my.program/diff-answers-empty-after-a-branch-write-back`: Writing branch declarations to disk resets comparison state, confusing agents.
- `my.program/write-branch-in-the-defn-request-answers-a-merge-changed-nothing`: Executing `write-branch!` in the same eval round as a `defn` evaluates before the entity is committed.
- `my.program/write-branch-of-one-function-allocates-a-gigabyte`: Full AST reanalysis of large files (e.g. `seon.error`) on write-back allocates > 1 GB.
- `my.program/write-branch-identities-take-tuples-and-the-docs-do-not-say`: Schema mismatch between user arguments and write-back identity tuples.
- `seon.cluster.source/a-second-write-back-conflicts-with-the-branchs-own-first`: Stale byte-spans cause immediate second write-back to fail with `:conflict-digests`.
- `seon.cluster.source/held-digests-refuses-a-map-on-the-write-back-path`: Contract failure in `held-digests` when handling write-back maps.

#### 3. The Fix at the Owner
1. In `my.program/write-branch!`, check the active lane claims before modifying files on disk.
2. In `seon.cluster.source/write-back!`, refresh the branch's base commit and recorded byte spans atomically upon write-back completion so consecutive writes succeed without digest conflicts.
3. Replace whole-file reanalysis with delta AST updates.
- **Retires Outright**: `my.program/write-branch-writes-files-the-lane-has-not-claimed`, `my.program/diff-answers-empty-after-a-branch-write-back`, `my.program/write-branch-of-one-function-allocates-a-gigabyte`, `seon.cluster.source/a-second-write-back-conflicts-with-the-branchs-own-first`, `seon.cluster.source/held-digests-refuses-a-map-on-the-write-back-path` (5 tasks).
- **Simplifies**: `my.program/write-branch-in-the-defn-request-answers-a-merge-changed-nothing`, `my.program/write-branch-identities-take-tuples-and-the-docs-do-not-say` (2 tasks).
- **Leaves**: None.

---

### Rank 6: Unbounded Whole-Graph Reverse Reach Walk in Breaks
**Producer**: `my.program/breaks` (`src/my/program.clj`) & `seon.fn/gating-tests` (`src/seon/fn.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/my/program.clj` (lines 297–300) and `src/seon/fn.clj` (lines 2071–2083), `my.program/breaks` invokes `seon.fn/gating-tests`:
```clojure
(defn gating-tests
  "The tests gating any of `function-symbols`: one reverse walk seeded with all of them."
  {:seon/tasks #{'my.program/breaks-on-a-namespace-subject-takes-five-seconds}
   :malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]}
  [database function-symbols]
  (let [edges (gate-edges database)]
    (if (:seon.db/invalid-read edges)
      edges
      (let [{identities :seon.fn/identities tests :seon.fn/tests incoming :seon.fn/incoming} edges
            closure (gate-set-in database identities tests incoming (vec function-symbols))]
        (if (:seon.db/invalid-read closure) closure (:seon.fn/tests closure))))))
```
`gating-tests` calls `gate-edges`, which builds an in-memory representation of every incoming call edge and test in the entire database, then performs an unbounded transitive reverse closure walk (`gate-set-in`). For hub functions or namespaces with many dependents, this walk takes 5–8 seconds, executes 400,000+ instrumented calls, and allocates over 1 GB.

#### 2. Member Tasks
- `my.program/breaks-costs-seconds-on-a-hub`: Evaluating `breaks` on a central hub takes 7.8 seconds.
- `my.program/breaks-on-a-namespace-subject-takes-five-seconds`: Passing a namespace to `breaks` takes 5.5–5.8 seconds.
- `my.program/breaks-of-a-widely-used-function-allocates-a-gigabyte`: Querying `seon.db/pull` allocates > 1 GB of heap.
- `my.program/breaks-of-one-function-walks-the-program-graph`: A function with no referrers still incurs 13,000 instrumented calls scanning graph edges.
- `my.program/breaks-plans-a-contract-change-as-a-retraction`: `breaks` models a contract change as a total retraction rather than a schema comparison.
- `seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels`: `gate-sets` traverses the full transitive closure even when caller selection only needs two levels.

#### 3. The Fix at the Owner
1. In `seon.fn/gating-tests`, query indexed `:seon.fn/calls` AVET datoms directly from the database instead of constructing the complete `gate-edges` graph in memory.
2. Impose a bounded search depth (e.g. depth 2, matching test selection).
- **Retires Outright**: `my.program/breaks-costs-seconds-on-a-hub`, `my.program/breaks-on-a-namespace-subject-takes-five-seconds`, `my.program/breaks-of-a-widely-used-function-allocates-a-gigabyte`, `my.program/breaks-of-one-function-walks-the-program-graph`, `seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels` (5 tasks).
- **Simplifies**: `my.program/breaks-plans-a-contract-change-as-a-retraction` (1 task).
- **Leaves**: None.

---

### Rank 7: Global Main-Status Gating and Unclosed Wake Progression
**Producer**: `seon.turn/task-end` (`src/seon/turn.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/turn.clj` (lines 2145–2164), `task-end` determines whether a task is `:done` by checking the step's merge preview status:
```clojure
(defn task-end
  ...
  [database agent-id]
  (let [held (task/agent-task database agent-id)]
    (when (and (string? held) (not (admission-closed? database agent-id)) (nil? (agent-run database agent-id)))
      (let [status (:seon.task/status (@context-step-verdict database agent-id))]
        (cond (= :done status) :done
              (sent-back-to database agent-id) :sent-back
              (db/q '[:find ?turn . :in $ ?agent-id
                      :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent]
                      [?turn :seon.turn/disposition :completed]]
                    database agent-id) :given-back
              (= 0 (turns-left database agent-id)) :capped
              (stalled? database agent-id) :stalled)))))
```
`context-step-verdict` requires that all reached tests be green on main. If an unrelated test in the transitive reach was already failing or unrun on main before the branch forked, `status` never becomes `:done`, blocking the task indefinitely. In addition, `next-agent-work` fails to suppress incoming wakes once a task is completed, reopening finished tasks in an endless loop.

#### 2. Member Tasks
- `seon.turn/a-reached-red-test-keeps-a-task-from-ending-done`: A green task is prevented from ending `:done` because an unassociated reached test is failing.
- `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`: Tests already red on main prevent completion of tasks that did not break them.
- `seon.turn/a-wake-reopens-a-finished-task`: Stale mailbox wakes reopen agents whose tasks have already completed.
- `seon.turn/a-task-end-never-reaches-root`: Root is not notified when a model agent's task completes, preventing automatic merge proposal.
- `seon.turn/a-green-proof-completes-the-task`: Green proof assertions do not trigger automatic task completion.
- `seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act`: Autonomous task completion requires manual root intervention.
- `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`: Retiring a branch fails because admission closure order is inverted.

#### 3. The Fix at the Owner
In `seon.context/step-verdict` and `seon.turn/task-end`:
1. Compare candidate test failures against the merge-base baseline rather than main (`candidate-reds \ base-reds`), allowing green tasks to finish when pre-existing main tests are red.
2. In `seon.turn/next-agent-work`, suppress turn generation for agents with closed admissions.
- **Retires Outright**: `seon.turn/a-reached-red-test-keeps-a-task-from-ending-done`, `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`, `seon.turn/a-wake-reopens-a-finished-task`, `seon.turn/a-task-end-never-reaches-root`, `seon.turn/a-green-proof-completes-the-task` (5 tasks).
- **Simplifies**: `seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act`, `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` (2 tasks).
- **Leaves**: None.

---

### Rank 8: Desynchronized Host Dynamic Var Binding in SCI
**Producer**: `seon.sci.eval/copy-host-var` (`src/seon/sci/eval.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/sci/eval.clj` (lines 252–272), `copy-host-var` bridges dynamic host vars into SCI:
```clojure
(defn- copy-host-var
  "Copy a loaded JVM Var into SCI at its root, with its metadata.

  SCI's `copy-var*` copies `@host-var` (`reference-code/sci/src/sci/core.cljc:137`),
  which for a dynamic Var bound on this thread is the binding: a base context
  derived under an evaluation would keep that evaluation's `seon.db/*read-database*`,
  and the memoized base would pin its branch's node cache. It copies only a
  few metadata keys; the rest (its contract, `:malli/schema`) is the Var's
  own data a reader expects, as the compiled Var answers. `:test` stays
  behind: a copied test Var would run its compiled body in this context."
  {:malli/schema [:=> [:cat [:fn clojure.core/var?] [:fn clojure.core/some?]] [:fn clojure.core/some?]]}
  [^clojure.lang.Var host-var sci-namespace]
  (let [copied (sci/copy-var* host-var sci-namespace)]
    (when (.getThreadBinding host-var)
      (.bindRoot ^sci.lang.Var copied (.getRawRoot host-var)))
    ;; The new Var's own metadata, which every context's binding of it starts
    ;; from; under an active context `alter-meta!` writes only that one's.
    (sci.ctx-store/with-ctx nil
      (alter-meta! copied #(merge (dissoc (meta host-var) :ns :name :test) %)))
    copied))
```
When `copy-host-var` creates an `sci.lang.Var` corresponding to a dynamic host Var, SCI's `binding` form modifies only the thread bindings of the SCI Var. It never calls `clojure.lang.Var/pushThreadBindings` on the underlying JVM Var. Consequently, host code invoked from within SCI (such as database reads checking `seon.db/*read-evidence-sink*` or `println` writing to `*out*`) reads the host Var's root value rather than the bound SCI value.

#### 2. Member Tasks
- `seon.sci.eval/sci-binding-of-a-host-dynamic-var-never-reaches-host-code`: Dynamic binding in SCI does not propagate to JVM host vars.
- `seon.sci.eval/binding-a-host-dynamic-var-does-not-reach-host-code`: Host code reading `seon.db/*read-evidence-sink*` fails to see SCI bindings.
- `seon.sci.eval/sci-out-is-unbound-on-a-test-member-thread`: Test threads fail with `SciUnbound` because `*out*` is unbound.
- `seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded`: Inability to bind host dynamic vars forces host-loading of test namespaces.
- `seon.test/a-members-reads-stay-out-of-its-callers-evidence`: Test runs fail to propagate evidence sinks through member executions.

#### 3. The Fix at the Owner
In `seon.sci.eval/copy-host-var`, tag copied dynamic vars so that SCI's `binding` macro (`push-thread-bindings`) synchronizes thread bindings bidirectionally to the underlying `clojure.lang.Var` during execution.
- **Retires Outright**: `seon.sci.eval/sci-binding-of-a-host-dynamic-var-never-reaches-host-code`, `seon.sci.eval/binding-a-host-dynamic-var-does-not-reach-host-code`, `seon.sci.eval/sci-out-is-unbound-on-a-test-member-thread`, `seon.test/a-members-reads-stay-out-of-its-callers-evidence` (4 tasks).
- **Simplifies**: `seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded` (1 task).
- **Leaves**: None.

---

### Rank 9: Uncached Whole-Task Environment Reconstruction
**Producer**: `seon.context/current-state` & `seon.context/task-sections` (`src/seon/context.clj`)  
**Confidence**: VERIFIED

#### 1. The Root Cause
In `src/seon/context.clj` (lines 560–636), `current-state` and `task-sections` construct the prompt block from scratch on every turn:
```clojure
(defn current-state
  ...
  [database agent-id]
  (let [found (held-task database agent-id)
        held (when-not (:seon.error/at found) found)
        sections (when held (task-sections database agent-id))
        left (turn/turns-left database agent-id)
        unread (let [items (message/inbox database agent-id)]
                 (if (:seon.error/at items)
                   items
                   (let [items (unshown database agent-id items)]
                     (if (:seon.error/at items)
                       items
                       (mapv #(if (:seon.task/end %) (assoc % :seon.task/ended (ended database %)) %) items)))))
        cost (branch-cost database)
        namespace (agent-namespace database agent-id held)
        status (get-in sections [:seon.context/step :seon.task/status])]
...
```
`current-state` invokes `proofs`, which scans `fn/gate-sets` across branch changes, queries message inboxes, and re-derives `branch-cost` across all database facts. This whole-graph reconstruction costs ~400 ms and 430+ MB of allocations per turn, exceeding prompt token budgets.

#### 2. Member Tasks
- `seon.context/the-current-state-block-misleads-and-is-large`: Current-state block emits redundant, un-budgeted facts.
- `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte`: Proof derivation inside `task-sections` consumes ~1 GB of allocations.
- `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`: `current-state` allocates 433 MB on default.
- `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base`: Full branch diff recalculation costs repeated graph walks.
- `seon.context/the-current-state-block-of-a-held-task-fits-a-bounded-token-count`: Context size blows past model token limits.
- `seon.context/a-held-steps-instructions-are-shown-whole-in-its-context`: Instruction formatting clips lines mid-text.

#### 3. The Fix at the Owner
Cache immutable task documentation, chain sequence, and schema references keyed by `:seon.task/id`. Compute delta only for mutable turn facts (unread inbox items, remaining turns, and immediate test outcome).
- **Retires Outright**: `seon.context/the-current-state-block-misleads-and-is-large`, `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte`, `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`, `seon.context/the-current-state-block-of-a-held-task-fits-a-bounded-token-count` (4 tasks).
- **Simplifies**: `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base`, `seon.context/a-held-steps-instructions-are-shown-whole-in-its-context` (2 tasks).
- **Leaves**: None.

---

## Separately Listed Task Categories

### 1. Generated Contract Back-Fill Cluster (Known Cluster)
- **Producer**: `seon.contracts/task` (`src/seon/contracts.clj`)
- **Total Tasks**: 10 back-fill tasks (`contract-output-observed?`, `contract-instruction-entity-changes`, `contract-cluster-branch`, `contract-edn-encoded?`, `contract-attributes`, `contract-runtime-duration`, `contract-query-params`, `contract-function-arities-in`, `contract-resource-population`, `contract-context-projection`).
- **Description**: Mechanical backfill tasks generated to attach complete Malli schema contracts to uncontracted functions. Treated as a single known cluster without individual enumeration per prompt instructions.

### 2. Moot Tasks: Subjects No Longer in `src` (Retire)
These 25 tasks target files, namespaces, functions, or subcomponents that were completely deleted or superseded in recent refactorings (such as B1b, Phase 8 runtime unification, or test launcher simplification):
1. `seon.census/a-census-reads-the-program-its-branch-holds`: `seon.census` was removed from `src`.
2. `seon.html-views/html-views-identity-and-fault-pairs-are-red`: `seon.html-views` was removed from `src`.
3. `seon.dev.hook/the-hook-sends-only-forms-with-a-var-definition`: `seon.dev.hook` was removed from `src`.
4. `seon.dev.hook/the-hook-cannot-load-a-host-only-test-namespace-on-a-branch`: `seon.dev.hook` was removed from `src`.
5. `seon.documentation/documentation-test-reads-a-retired-doc-and-dir-shape`: `seon.documentation` was removed from `src`.
6. `seon.documentation/the-chain-documents-say-one-task-per-step`: Task-per-step architecture was superseded by `TASK-CHAIN`.
7. `seon.step/survey`: `seon.step` templates were removed from `src`.
8. `seon.step/define`: `seon.step` templates were removed from `src`.
9. `seon.step/design`: `seon.step` templates were removed from `src`.
10. `seon.step/prove`: `seon.step` templates were removed from `src`.
11. `seon.step/implement`: `seon.step` templates were removed from `src`.
12. `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema`: `seon.print.value` was absorbed into `seon.print`.
13. `seon.print.value/printed-copies-stored-or-sent-instead-of-values`: `seon.print.value` was absorbed into `seon.print`.
14. `seon.print.value/ad-hoc-text-clips-outside-the-printer`: `seon.print.value` was absorbed into `seon.print`.
15. `seon.test/the-selection-test-observes-reach-digests-without-redefining-it`: `seon.test.selection` deleted from `src` (commit `8bc9178724`).
16. `seon.test/since-previous-run-narrowing-has-no-production-caller`: `seon.test.selection` deleted from `src`.
17. `seon.test/a-changed-dial-reruns-a-test-that-reads-it`: `seon.test.selection` deleted from `src`.
18. `seon.cluster.source/a-landing-refusal-carries-its-variant`: `seon.cluster.source/land!` deleted from `src`.
19. `seon.cluster.source/a-docstring-only-change-lands-without-interpreting-its-namespace`: `seon.cluster.source/land!` deleted from `src`.
20. `seon.merge/main-keeps-the-merge-results-after-the-branch-is-deleted`: `seon.context/main-database` was deleted from `src`.
21. `seon.turn/receipt-attributes-are-a-hand-list`: `seon.turn/receipt-terminal-attributes` was deleted from `src`.
22. `seon.db/deletion-error-has-no-caller`: `seon.db/deletion-error` has zero callers and was retired.
23. `seon.db/transaction-stamp-attributes-outlive-their-producer`: Attributes have no producer since `stamp-transaction` was retired.
24. `seon.turn/eval-origin-has-no-producer-or-consumer`: `:seon.eval/origin` has no producer/consumer in `src`.
25. `seon.turn/row-tx-is-a-second-declaration-writer`: `seon.turn/row-tx` has zero callers in `src`.

### 3. Tasks Asking for Fences or Workarounds
These 8 tasks request guards, retry loops, exemptions, or catch-alls instead of fixing the root cause at the producer:
1. `seon.turn.batch/only-root-holds-a-host-evaluator`: Asks for a check in `jvm-evaluate` refusing non-root agents.  
   *Root Cause*: Non-root agents should not have JVM evaluation exposed in their toolkit at the entrance.
2. `seon.cluster.source/a-landing-writes-git-objects-with-a-parent-compare-and-swap`: Asks for lock/CAS retry fence around git index writes.  
   *Root Cause*: Concurrent lane commits mutating the shared working tree instead of isolated candidate trees.
3. `seon.cluster.source/a-write-back-root-is-valid-by-its-schema`: Asks for defensive checks in `write-back!` against relative or archive paths.  
   *Root Cause*: Callers passing unvalidated string paths rather than validating `:seon.fn/root` at the boundary.
4. `seon.web.jvm/fetch-catch-all-hides-host-defects`: Uses a blanket catch turning all Throwables into transport errors.  
   *Root Cause*: Defensive exception swallowing in `fetch` masking genuine defects.
5. `seon.render.value/a-lazy-seq-that-throws-shows-as-empty`: Uses a retry loop in value printer for throwing lazy sequences.  
   *Root Cause*: Value printer realizing unbounded sequences breadth-first without error propagation.
6. `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`: Asks for an exemption filter in `task-end` for tests red on main.  
   *Root Cause*: `task-end` checks all reached tests against main rather than delta-checking tests broken by the branch.
7. `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing`: Asks for a filter ignoring scratch namespaces during landing.  
   *Root Cause*: `accept-merge!` sweeps all branch facts rather than scoping the merge to declared task entities.
8. `seon.operator/a-jvm-runs-in-a-run-directory-not-its-archive`: Adds file permission sealing (`seal-archive!`) as a fence against relative `spit`s.  
   *Root Cause*: Operator launches JVM with the source archive checkout as the working directory instead of an isolated scratch run directory.

---

## Ten-Line Summary

1. Total tasks analyzed across 101 EDN declaration files: 872 tasks.
2. Verified root cause clusters identified (minimum 3 members sharing a verified producer): 9 clusters.
3. Total backlog tasks directly retired outright by fixing the 9 producer root causes: 51 tasks.
4. Total backlog tasks simplified by fixing the 9 producer root causes: 15 tasks.
5. Backlog tasks remaining in the 9 clusters after producer fixes: 0 tasks.
6. Contract back-fill cluster (mechanical uncontracted function tasks): 10 tasks (treated as 1 known cluster).
7. Moot tasks whose subjects no longer exist in `src` (retired outright): 25 tasks.
8. Tasks asking for a fence, guard, retry, or workaround (standing in for unfixed root causes): 8 tasks.
9. Top 3 highest-impact producer fixes by task retirements: `seon.turn/test-step` (9), `seon.test/clean-request-handle` (7), `seon.db/write-digest-error` (6).
10. Net task backlog reduction from addressing these 9 producers and retiring moot tasks: 86 tasks eliminated outright.
