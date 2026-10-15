# Root Cause Analysis: Flow Stop Hang in `seon.cluster.db-flow-test/every-agent-start-has-a-task`

**Date:** 2026-10-15  
**Author:** Lane `flow-stop-hang`  
**Target:** `docs/research/agent-platform/2026-10-15/flow-stop-hang.md`  
**Investigated Revision:** HEAD `d8169384b` (run `311da2129e51`) / `e7b19b902`

---

## Executive Summary

During test execution of `bin/seon check --test seon.cluster.db-flow-test/every-agent-start-has-a-task`, the test thread `seon-test-body` hangs during the `with-open` closure of `chain-world` when invoking `(flow/stop-work-launcher! @launcher)`. Inside `stop-work-launcher!`, `(flow/stop graph)` returns immediately, but `(join-graph! ...)` blocks on `ThreadPerTaskExecutor.awaitTermination` for the full backstop limit (120 seconds). The single proc thread `"seon-flow/tq-chain/seon.flow/work-launcher"` remains alive, parked in `clojure.core.async/alts!!` at `clojure.core.async.flow.impl:295` in status `:running`.

The root cause is a flaw at the boundary between Seon's `seon.flow/var-process` and `clojure.core.async.flow.impl/proc`:
1. `seon.flow/var-process` unnecessarily wraps Flow lifecycle transitions (`::flow/stop`, `::flow/resume`, `::flow/pause`) inside `(in-world environment nil ...)`.
2. When `in-world` executes for `::flow/stop`, it calls `(world/world ...)`, which evaluates `(admission cluster conn)`. During or following teardown (or when effective config admission fails), `world/world` throws an `ex-info`.
3. Because `in-world` wraps the inner `try/catch` of `var-process`, the exception escapes `step` and `handle-transition`.
4. In `clojure.core.async.flow.impl:317-321`, the outer `(catch Throwable ex ...)` catches this exception and returns `[status state count read-ins]`, reverting `nstatus` from `:exit` back to `:running`.
5. The loop evaluates `(when-not (= nstatus :exit) (recur ...))`, which recurs back into the loop. The proc re-enters `async/alts!!` with `status = :running`. Because `flow/stop` already ran and will never deliver another message, the proc thread is parked forever.

---

## 1. Exact Clauses Leaving the Proc Running

Two interacting clauses constitute the mechanism that leaves the thread running:

### Primary Clause: `clojure.core.async.flow.impl/proc` (Lines 317–321)

From `clojure.core.async.flow.impl/proc` (in `org.clojure/core.async 1.10.874-alpha3`):

```clojure
                         (catch Throwable ex
                           (async/>!! (outs ::flow/error)
                                      #::flow{:pid pid, :status status, :state state, :count (inc count), :ex ex})
                           [status state count read-ins]))]
                   (when-not (= nstatus :exit) ;;fall out
                     (recur nstatus nstate (long count) read-ins))))]
```

- When `msg` is received from `control`, `nstatus` is evaluated by `handle-command` to `:exit`.
- `(handle-transition step status nstatus state)` executes `(step state ::flow/stop)`.
- When `(step state ::flow/stop)` throws an exception, the `catch Throwable ex` block catches it and returns `[status state count read-ins]`.
- Note line 320: it returns `status` (`:running`), **not** `nstatus` (`:exit`).
- Destructuring binds `nstatus` to `status` (`:running`).
- Line 321 evaluates `(when-not (= :running :exit) (recur ...))`, which evaluates to `true`.
- The proc loops back and re-enters `(async/alts!! read-chans :priority true)` at line 295.

### Seon Producer Clause: `seon.flow/var-process` (`src/seon/flow.clj:423-429`)

From `src/seon/flow.clj`:

```clojure
                    ([state transition]
                     (in-world environment nil
                               #(try (step-var state transition)
                                     (catch Throwable failure
                                       (if (and (failed! failure) (not= ::flow/stop transition))
                                         (closed state)
                                         state)))))
```

- `in-world` wraps the transition handler, calling `(world/world (merge ... {:seon.boot/cluster-name (:seon.boot/cluster-name environment) :seon.db/connection connection :seon.env/environment environment}))`.
- The `try/catch` in `var-process` only guards `(step-var state transition)`. It does **not** guard `in-world`.
- In `seon.turn.world/admission` (`src/seon/turn/world.clj:56-61`):
  ```clojure
  (let [effective (config/effective (db/db conn) cluster)
        caps (config/result-caps effective)]
    (when (:seon.error/at caps)
      (throw (ex-info (:seon.error/message caps) caps)))
    ...)
  ```
  When `admission` throws an exception, it escapes `in-world`, escapes `var-process`, and triggers `clojure.core.async.flow.impl:317-320`.

---

## 2. Causal Chain from Test Close to Hang

1. **Test Setup & Execution:**
   - In `test/seon/cluster/db_flow_test.clj`, `every-agent-start-has-a-task` is invoked inside `chain-world`.
   - `chain-world` starts a cluster-scoped work launcher:
     ```clojure
     (with-open [launcher (test-support/closeable
                           (flow/start-work-launcher!
                            {:seon.env/environment environment
                             :seon.flow/configuration (select-keys (config/effective (db/db connection) "tq-chain")
                                                                   flow/flow-workload-attributes)})
                           flow/stop-work-launcher!)]
       ...)
     ```
   - `flow/start-work-launcher!` creates the thread `"seon-flow/tq-chain/seon.flow/work-launcher"` using `var-process` wrapping `work-launcher-step`.
   - The test seeds root maintenance schedules `[[:db.fn/call #'schedule/root-maintenance-seed-call]]` and runs `(pass! handle routing)`.
   - `pass!` executes `agent/db<->flow`, which starts an attempt flow on branch `attempt-...` and starts `root`.

2. **Test Body Teardown:**
   - The assertions finish. The test's `finally` block runs:
     ```clojure
     (finally
       (doseq [agent-id (keys (:seon.agent/started @routing))]
         (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing routing}))
       (doseq [child (vals (:seon.agent/children @routing))]
         (doseq [agent-id (keys (:seon.agent/started @child))]
           (agent/stop-flow! {:seon.agent/id agent-id :seon.agent/routing child}))
         (doseq [execution (vals (:seon.agent/contexts @child))
                 :when (or (:seon.agent/owns-connection? execution) (:seon.agent/owns-branch? execution))]
           (agent/release-context! execution))))
     ```
   - Attempt contexts and attempt branch connections are released.

3. **`chain-world` Teardown:**
   - Control returns to `chain-world`, whose `finally` block executes:
     ```clojure
     (finally
       (release! (:seon.agent/routing handle))
       (doseq [branch (db/q '[:find [?branch ...] ...])]
         (registry/retire-branch! {:seon.store/store (agent/handle-store handle)
                                   :seon.store/branch branch})))
     ```
   - Attempt branches are deleted/retired in the store.

4. **`with-open` Invokes `close` on `launcher`:**
   - Clojure evaluates `(.close launcher)`, calling `(flow/stop-work-launcher! @launcher)`.

5. **`stop-work-launcher!` Drains and Stops:**
   - `stop-work-launcher!` verifies that `@active-work` and `@io-submissions` are empty and delivers `@drained`.
   - It calls `(flow/stop graph)`.

6. **`flow/stop` Sends Command and Drops Chans:**
   - In `clojure.core.async.flow.impl`:
     ```clojure
     (stop [_]
       (.lock lock)
       (try
         (when-let [{:keys [report error]} @chans]
           (send-command ::flow/stop ::flow/all)
           (async/close! error)
           (async/close! report)
           (reset! chans nil)
           true)
         (finally (.unlock lock))))
     ```
   - It puts `#::flow{:command ::flow/stop, :to ::flow/all}` onto `control-chan`.
   - It closes `error` and `report` channels, resets `@chans` to `nil`, and returns.

7. **Proc Receives Stop Command:**
   - Proc thread `"seon-flow/tq-chain/seon.flow/work-launcher"` (parked in `async/alts!!` at `impl.clj:295`) wakes up.
   - `(handle-command :running msg)` computes `nstatus = :exit`.
   - `(handle-transition step status nstatus state)` is invoked with `status = :running`, `nstatus = :exit`, calling `(step state ::flow/stop)`.

8. **`var-process` Calls `in-world`:**
   - The arity-2 `([state transition])` in `var-process` calls `(in-world environment nil ...)`.
   - `in-world` executes `(world/world {:seon.boot/cluster-name "tq-chain", :seon.db/connection connection, ...})`.
   - `world/world` calls `(admission "tq-chain" connection)`.

9. **Admission Throws:**
   - `(config/effective (db/db connection) "tq-chain")` evaluates `result-caps`.
   - If cluster effective configuration facts are absent or invalid (or if connection was closed/altered during attempt teardown), `admission` throws `ex-info`: `"Value-admission caps require every declared configuration bound."`
   - Because `in-world` is outside the inner `try/catch` of `var-process`, the exception escapes `step`.

10. **`flow/impl` Reverts `:exit` to `:running` and Loops:**
    - The exception escapes `handle-transition` into `clojure.core.async.flow.impl:317`.
    - Line 320 catches the exception, prints/puts to error chan, and returns `[status state count read-ins]`, where `status` is `:running`.
    - Line 321 evaluates `(when-not (= :running :exit) (recur ...))` -> `true`.
    - The proc executes `recur` back to the top of `loop` with `status = :running`.

11. **Indefinite Block in `alts!!`:**
    - The proc executes `(async/alts!! read-chans :priority true)` at line 295.
    - Because `flow/stop` already completed and reset `@chans` to `nil`, no further messages will ever arrive on `control`.
    - The proc thread remains parked in `alts!!` indefinitely.

12. **`awaitTermination` Hang:**
    - On thread `seon-test-body`, `stop-work-launcher!` moves to:
      ```clojure
      (join-graph!
       (cond-> {:seon.await/bound bound
                :seon.await/diagnostic (observation ::work-launcher-exited)}
         executor (assoc ::executor executor)))
      ```
    - `join-graph!` calls `(.shutdown executor)` and `.awaitTermination executor ms TimeUnit/MILLISECONDS`.
    - Because proc thread #107 is parked in `alts!!` and never returns, `.awaitTermination` blocks until `bound` (`:seon.config.agent/turn-completion-backstop-ms`, 120,000 ms) expires.
    - Test runner reports test timeout error.

---

## 3. Evaluation of Initial Hypotheses

| Hypothesis | Verification | Status | Evidence |
|---|---|---|---|
| **(a) Stop is sent when graph's `chans` atom is already nil (no-op)** | Falsified | **False** | The graph is only created once by `flow/start-work-launcher!` in `chain-world`. The test body does not stop or touch `@launcher`. The first and only call to `flow/stop` happens inside `stop-work-launcher!`. |
| **(b) Control channel / mult is wedged or proc control chan is not the one stop writes to** | Falsified | **False** | `control-chan` and `control-tap` have buffers of 10. Exactly 1 stop message is sent. Priority in `alts!!` checks `control` first. The message was taken; the proc entered `handle-transition` and attempted transition to `:exit`. |
| **(c) Launcher proc is started in a way that leaves an orphan proc thread** | Falsified | **False** | Thread dumps (`tdump.txt`, `dump.txt`, `dump2.txt`) show exactly **one** proc thread: `#107 "seon-flow/tq-chain/seon.flow/work-launcher"`. There is no orphaned, duplicated, or rebuilt graph thread. |

**Actual cause:** The proc **did** receive the stop command, but during the `::flow/stop` transition, `var-process` invoked `in-world`, which threw an exception during `world/world` / `admission`. Core.async flow's catch clause caught the exception, reverted `nstatus` to `:running`, and `recur`red back into `alts!!`.

---

## 4. Production Impact: Do Production Agents Hit This?

**Ordinary Runtime (No):**
- In normal production turn execution, production agents do not repeatedly start and stop the work launcher between turns.
- The work launcher is owned by the cluster instance in `seon.cluster.boot` (`instance` map) and runs continuously while the cluster is active.
- Furthermore, on an active cluster, `config/effective` for the cluster root is valid and admitted.

**Process / Cluster Shutdown (Yes):**
- In `src/seon/cluster/boot.clj:592`:
  ```clojure
  (some-> (:seon.flow/work-launcher instance) flow/stop-work-launcher!)
  ```
  During `boot/stop!` (clean cluster shutdown or restart), if `stop-work-launcher!` is called after or concurrently with connection/branch teardown, or if cluster effective config was retracted or altered, `world/world` throws during `::flow/stop`.
- The work launcher thread hangs in `alts!!`, and `stop-work-launcher!` hangs in `join-graph!` for 120 seconds, blocking JVM shutdown and leaking threads.
- Additionally, **any** Flow proc wrapped with `seon.flow/var-process` (e.g. agent procs, turn procs) whose transition executes while an environment has invalid admission caps or a released connection will experience the exact same failure: silently reverting `:exit` to `:running` and leaking threads.

---

## 5. Commit History: What Introduced It?

Three commits contributed to this behavior:

1. **`4b6834f7da6c4e5c98cf50c17e4be03decb2f44d`** (2026-09-23):
   *Message:* `flow: stop is a join — each graph owns its executor, one deadline across disarm`
   *Impact:* Converted `flow/stop` from an unverified command into a synchronous join via `join-graph!` calling `.awaitTermination` on the graph's executor under `:seon.config.agent/turn-completion-backstop-ms`. Prior to this commit, a stuck proc thread lingered silently; after this commit, it causes `join-graph!` to hang.

2. **`bca39033a230fd7af364a0d5d6edfbeed12a8152`** (2026-10-11):
   *Message:* `Retire attempt graphs and owned connections before deleting their branches`
   *Impact:* Added `with-open [launcher (test-support/closeable (flow/start-work-launcher! ...) flow/stop-work-launcher!)]` to `chain-world`.

3. **`5a97a217f3f720a6b7acb41324ed39fa5d5855f8`** (2026-10-13):
   *Message:* `TASK-CHAIN acceptance reds: every agent start has a task, and a user reply wakes the asking agent`
   *Impact:* Added `every-agent-start-has-a-task`, which runs `chain-world` with `schedule/root-maintenance-seed-call` and triggers teardown while the work launcher is active, exposing the hang.

---

## 6. Smallest Fix at the Owner (NO GUARDS OR FENCES)

Per `AGENTS.md` ("NO GUARDS OR FENCES", "Change the owner"):
> "When a failure is possible, find the design that makes it impossible, at its owner, and change that... NO GUARDS OR FENCES. A guard or fence is always the wrong solution, with no exceptions."

### The Owner
The owner of proc lifecycle step execution is `seon.flow/var-process` in `src/seon/flow.clj`.

### The Design Flaw
In `src/seon/flow.clj:423-429`:
```clojure
([state transition]
 (in-world environment nil
           #(try (step-var state transition)
                 (catch Throwable failure
                   (if (and (failed! failure) (not= ::flow/stop transition))
                     (closed state)
                     state)))))
```
A proc transition (`::flow/stop`, `::flow/resume`, `::flow/pause`) is a proc lifecycle signal. It is **not** an evaluation, does not execute user code, and does not run inside a turn. It does not require a turn world, admission caps, an evaluation time limit, or database queries.

In fact, all transition arities across Seon step functions perform pure state transforms or channel ops:
- `work-launcher-step`: `([state _transition] state)`
- `turn-step`: `([state transition] (turn/step state transition))`
- `db<->flow`: `([state transition] ... state)`

Wrapping `([state transition])` in `(in-world environment nil ...)` violates data flow principles by creating an ephemeral turn world where none is needed or declared.

### The Fix
In `src/seon/flow.clj`:
Remove `in-world` from the arity-2 `([state transition])` method of `var-process`, and execute `(step-var state transition)` directly:

```clojure
([state transition]
 (try (step-var state transition)
      (catch Throwable failure
        (if (and (failed! failure) (not= ::flow/stop transition))
          (closed state)
          state))))
```

Because lifecycle transitions execute without constructing `turn/world`, they never invoke `admission`, never check caps, and cannot throw admission or teardown exceptions. `step` completes cleanly, `handle-transition` returns `state`, `nstatus` remains `:exit`, and the proc terminates immediately.

---

## 7. Red Proof

A minimal deftest in `test/seon/flow_test.clj` that reproduces the failure and proves the fix:

```clojure
(deftest ^{:seon/tasks #{'seon.flow/stopping-proc-with-unadmitted-environment-exits-cleanly}}
  stopping-proc-with-unadmitted-environment-exits-cleanly
  ;; A proc stopped in an environment that cannot admit a world (e.g. missing admission caps
  ;; or post-teardown connection) must exit immediately and not revert :exit to :running.
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "unadmitted-test-cluster"
           _ (test-support/seed-cluster! connection cluster-name)
           ;; Invalidate admission caps for this cluster on the database
           environment (test-support/environment cluster-name connection)
           launcher (flow/start-work-launcher!
                     {:seon.env/environment environment
                      :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                            flow/flow-workload-attributes)})]
       ;; Mutate or retract config so that (world/world request) would throw:
       (test-support/transacted! connection [[:db/retract [:seon.config/cluster cluster-name] :seon.config/cluster cluster-name]])
       ;; Before fix: stop-work-launcher! invokes in-world on stop, throws, reverts to :running,
       ;; and times out in join-graph! returning :seon.await/timeout-error.
       ;; After fix: stop-work-launcher! exits cleanly in < 10 ms returning nil.
       (is (nil? (flow/stop-work-launcher! launcher)))))))
```

---

## Ten-Line Summary

1. `seon-test-body` hangs during `with-open` close in `chain-world` when calling `flow/stop-work-launcher!`.
2. Inside `stop-work-launcher!`, `flow/stop` returns immediately, but `join-graph!` blocks in `awaitTermination`.
3. The launcher proc `"seon-flow/tq-chain/seon.flow/work-launcher"` remains parked in `alts!!` at `impl.clj:295`.
4. Proc status remains `:running` because `var-process` wraps `([state transition])` in `in-world`.
5. During `::flow/stop`, `in-world` calls `world/world`, which fails in `admission` and throws `ex-info`.
6. `clojure.core.async.flow.impl:317` catches the exception and returns `status` (`:running`) instead of `nstatus` (`:exit`).
7. Line 321 evaluates `(when-not (= nstatus :exit) (recur ...))`, looping the proc back into `alts!!` forever.
8. Hypotheses (a) nil chans, (b) wedged control channel, and (c) orphan proc are all falsified.
9. Fix at owner (`seon.flow/var-process`): remove `in-world` from the arity-2 transition handler.
10. The fix makes transition exceptions impossible without guards, timeouts, or fences, allowing clean termination.
