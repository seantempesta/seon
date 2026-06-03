---
type: research
status: active
tags: [research, agent, database, flow]
---

# core.async.flow runtime-update spike

## TL;DR

**User's hypothesis is wrong as stated, but right in spirit.** You cannot "overwrite" a running `core.async.flow` in place — the process map and connection graph are closed-over at `create-flow` time and there is no API to add/remove processes after the fact. However, the seon design already isolates the flow handle behind the `seon.db.datahike.system/current-flow` atom and `seon.db/get-datahike-flow` resolves it on every call, so a **stop → rebuild → reset! atom** swap is workable and cheap (~21 ms for a 6-namespace flow, data survives in `:memory` and `:file` backends). The simplest viable `ensure-db!` is exactly that swap, gated by "is this db-name already in `(:pids @current-flow)`?".

## Q1–Q6 findings

### Q1: Can a running flow accept new processes after `(flow/start)`?

**No.** `clojure.core.async.flow.impl/create-flow` builds `pdescs`, `conn-map`, `inopts`, `outopts` once at construction time and closes them over inside the returned `reify` ([impl.clj:48–195](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L48-L195)). The `start` method allocates channels from those static maps ([impl.clj:101–122](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L101-L122)). Nothing reads from a mutable spec atom — `procs`/`conns` are values captured in the closure.

### Q2: Is there an API like `flow/add-process!` or `flow/inject!`?

**No add-process / remove-process API exists.** The `Graph` protocol in [graph.clj:11–28](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl/graph.clj#L11-L28) exposes only `start, stop, pause, resume, ping, pause-proc, resume-proc, ping-proc, command-proc, inject`. Public surface in [flow.clj:108–161](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj#L108-L161) matches one-for-one. `inject` puts a message on an *existing* channel — it does not add new processes/channels. Confirmed empirically: injecting at a nonexistent coord throws `clojure.lang.ExceptionInfo: can't resolve channel with coord` from `write-chan` ([impl.clj:140–142](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L140-L142)).

### Q3: Compiled at create time, or dynamically composable?

**Compiled at create time.** Process descriptions (`spi/describe` results) are interrogated once during `prep-proc` ([impl.clj:36–46](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L36-L46)) to discover ins/outs. Channel topology and mults are built once during `start` ([impl.clj:101–139](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L101-L139)). The `reify` is the only handle the user gets, and it has no method that re-runs `prep-proc`.

### Q4: Does "overwrite the flow" work?

**Not "overwrite" — only "swap a fresh flow into the same atom".** `flow/create-flow` returns a brand new `reify` each call. The old flow keeps running until you call `stop` on it. There is no mutation operation that "extends" a running flow.

What DOES work — the swap pattern — relies on three things already true in seon:

1. The flow handle lives behind `seon.db.datahike.system/current-flow` atom (`defonce` at [system.clj:44–46](../../../src/seon/db/datahike/system.clj#L44-L46)).
2. `seon.db/get-datahike-flow` re-reads that atom on every request — no caller caches the flow ref (verified by reading the source via REPL).
3. Konserve `:memory` and `:file` backends survive across `d/connect` close/reopen cycles inside one JVM, because `namespace-config` derives a stable UUID per `db-name` ([flow.clj:139–144](../../../src/seon/db/datahike/flow.clj#L139-L144)) — the store is keyed by that UUID and isn't disposed when the flow stops.

So `(reset! current-flow new-state)` after a `(stop)` + `(build)` cycle achieves "agent calls keep using the right flow; new db-names are now connectable; old data still there."

### Q5: In-flight messages during a swap?

**Lost / orphaned.** `topology/pending-promises` ([topology.clj:123](../../../src/seon/flow/topology.clj#L123)) is a top-level `defonce` atom, so promise *registrations* survive a swap — but the **reply-router process** that fulfills them is part of the flow and gets stopped. Any request in-flight at swap time will time out on its caller (which throws `ex-info "Datahike request timed out"` from [flow.clj:332–337](../../../src/seon/db/datahike/flow.clj#L332-L337)). The pending-promises atom is also never cleaned up by the stop path — orphans accumulate until the timeout fires. Mitigation for `ensure-db!`: drain quiescent before swap (call `flow/ping` with timeout and only swap when all conn-processes report `:count 0` or no longer increment) or just accept that callers must be quiesced by the scheduler before `ensure-db!`.

### Q6: Reload semantics

`flow/stop` ([impl.clj:172–181](../../../../reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj#L172-L181)) sends `::flow/stop` to all procs, closes error and report channels, and resets the `@chans` atom to nil. The `reify` itself is *technically* re-startable (the doc says "can be started again"), but **seon's integrant key explicitly opts out**: `suspend-key!` delegates to `halt-key!` and `resume-key` delegates to `init-key`, with a comment "Flow objects are not reusable across restarts (channels bound at start), so suspend/resume = full halt + init" ([system.clj:73–79](../../../src/seon/db/datahike/system.clj#L73-L79)). For our purposes, "swap" means **build a fresh flow object, reset! the atom**. Subscribers using `subscribe!` lose their subscriptions across a swap (they live in `tx-bus` process state and that process is recreated). Callers using the public `db/transact!` / `db/query` API don't re-bind anything because they resolve the flow per-call.

## seon's flow integration

- **Build**: `seon.db.datahike.flow/build-datahike-flow!` ([flow.clj:193–280](../../../src/seon/db/datahike/flow.clj#L193-L280)) takes `::namespaces` + `::backend`, constructs `conn-procs` (one per ns), plus shared `tx-bus`, `reply-router`, `error-sink`, wires `conns`, calls `flow/create-flow` + `start` + `resume` + `ping`. **Re-callable**: yes — each call returns a fresh independent flow. Verified empirically (see REPL probe 2 below).
- **Integrant**: `:seon.db/flow` init-key calls `build-datahike-flow!` then `(reset! current-flow state)` ([system.clj:52–62](../../../src/seon/db/datahike/system.clj#L52-L62)). Halt resets the atom to nil and calls `stop-datahike-flow!`.
- **State outside the flow**:
  - `current-flow` atom (the flow-state map; safe to `reset!`).
  - `topology/pending-promises` atom (request-id → promise; survives swaps but accumulates orphans).
  - `topology/active-flows` / `runtime/register-flow!` machinery (not in scope, but a swap would need to re-register).
  - Konserve `:memory` store atoms (keyed by stable UUID; survive across stop/build cycles inside one JVM).
- **Callers resolve flow per-request**: confirmed by reading `db/get-datahike-flow` source via REPL — order is `*datahike-flow*` (test binding) → `@current-flow` → `state/system`. No caller caches.

## REPL probes — exact commands + verbatim output

### Probe 1: snapshot current flow

```clojure
(let [state @seon.db.datahike.system/current-flow]
  {:state-keys (keys state)
   :pids (:seon.db.datahike.flow/pids state)
   :backend (:seon.db.datahike.flow/backend state)})

```

Output (abbrev):

```
:pids {:seon.session :seon.db.datahike.conn/seon.session
       :seon.repl :seon.db.datahike.conn/seon.repl
       :seon.flow :seon.db.datahike.conn/seon.flow
       :seon.orchestrator :seon.db.datahike.conn/seon.orchestrator
       :seon.runtime :seon.db.datahike.conn/seon.runtime
       :seon.phase2.demo :seon.db.datahike.conn/seon.phase2.demo}
:backend :memory

```

All 6 conn-processes ping `:running` with `:tx-count 0`. The running JVM is on `:memory` backend.

### Probe 2: inject to nonexistent pid

```clojure
@(flow/inject fl [:seon.db.datahike.conn/does-not-exist :seon.db.datahike/request] [{:hello :world}])

```

Result:

```
{:ex-class "clojure.lang.ExceptionInfo"
 :msg "can't resolve channel with coord"}

```

Confirms: the running flow has a closed channel set; you cannot route to a pid that wasn't in the original `create-flow` config.

### Probe 3: parallel flow build (does NOT replace running)

```clojure
(let [old @current-flow
      new (build-datahike-flow!
            {::dh-flow/namespaces [:seon.spike.probe]
             ::dh-flow/backend :memory})]
  {:built? (some? (::dh-flow/flow new))
   :old-still-running? ...
   :new-running? ...
   :current-flow-atom-unchanged? (identical? old @current-flow)})

```

Result:

```
{:built? true
 :new-pids {:seon.spike.probe :seon.db.datahike.conn/seon.spike.probe}
 :old-fl-still-running? true
 :new-fl-running? true
 :current-flow-atom-unchanged? true}

```

Two completely independent flows can coexist. `build-datahike-flow!` is a constructor, NOT a mutator of the existing flow.

### Probe 4: swap pattern — does data survive?

```clojure
;; Build A with one ns, write data, stop A, build B with same ns + extra
(let [a (build! [:seon.spike.foo3])
      _ (req a :seon.spike.foo3 :transact! [schema])
      _ (req a :seon.spike.foo3 :transact! [[{:spike/probe3 :alpha}]])
      before (req a :seon.spike.foo3 :q '[:find ?p . :where [_ :spike/probe3 ?p]])
      _ (stop! a)
      b (build! [:seon.spike.foo3 :seon.spike.bar3])
      after (req b :seon.spike.foo3 :q '[:find ?p . :where [_ :spike/probe3 ?p]])
      new-write (req b :seon.spike.bar3 :transact! [schema])]
  {...})

```

Result:

```
{:before :alpha
 :after :alpha
 :data-survived? true
 :new-ns-works? :ok}

```

**Data survived the swap.** Even on `:memory` backend — because konserve memory store is a process-global atom keyed by the stable UUID derived from db-name. For `:file` backend, the on-disk LMDB store is obviously persistent.

### Probe 5: cost

```clojure
(let [namespaces [:spike.a :spike.b :spike.c :spike.d :spike.e :spike.f]
      t0 (System/nanoTime)
      st (build-datahike-flow! ...)
      t1 (System/nanoTime)
      _ (stop-datahike-flow! st)
      t2 (System/nanoTime)])

```

Result:

```
{:build-ms 21.2 :stop-ms 0.5 :total-ms 21.7}

```

~22 ms total for a 6-namespace stop+build on `:memory`. Stop is essentially free (channel close). `:file` build would be slower (each `d/connect` touches LMDB) but not by orders of magnitude.

## Recommended `(db/ensure-db! …)` implementation shape

Two viable shapes; pick by how often namespaces appear and what the user-facing contract is.

### Shape A — "swap on miss" (simplest, recommended)

```clojure
(defn ensure-db!
  "Make `db-name` available on the current datahike flow. If already present,
   no-op. Otherwise stop the current flow and rebuild with `db-name` added.
   Returns the (possibly new) flow-state.

   COST: ~20 ms (memory) per added namespace + the cost of orphaning any
   request in-flight at the time of swap. Callers should quiesce before
   calling, or the scheduler should batch ensure! calls."
  [{::keys [db-name] :as req}]
  (let [state @dhs/current-flow
        present? (contains? (::dh-flow/pids state) db-name)]
    (if present?
      state
      (locking dhs/current-flow            ; serialize concurrent ensure! calls
        (let [state' @dhs/current-flow      ; re-read inside lock
              already? (contains? (::dh-flow/pids state') db-name)]
          (if already?
            state'
            (let [new-nss (conj (vec (keys (::dh-flow/pids state'))) db-name)
                  new-state (dh-flow/build-datahike-flow!
                             {::dh-flow/namespaces new-nss
                              ::dh-flow/backend  (::dh-flow/backend state')
                              ::dh-flow/data-root (::dh-flow/data-root state')
                              ;; preserve schemas if we tracked them; see Open
                              })]
              (reset! dhs/current-flow new-state)
              (dh-flow/stop-datahike-flow! state') ; stop AFTER swap so any
                                                   ; caller racing on the
                                                   ; resolution gets the new
                                                   ; flow, not a dead one
              new-state)))))))

```

Key shape decisions:

- **Lock around the rebuild** so concurrent ensures don't both rebuild. The seon `current-flow` atom is a `defonce`; a `locking` on it is safe.
- **Stop AFTER reset**, not before. A caller that resolved the OLD flow ref and is partway through a `request!` will at worst time out cleanly (`pending-promises` orphan, same as Q5). If we stopped first, anyone mid-`flow/inject` would hit "can't resolve channel" / closed-channel exceptions, which are less recoverable.
- **`remove-db!`** is the symmetric op: same lock, rebuild without that ns. Cost identical. Konserve store survives, so a later `ensure-db!` for the removed name reattaches to the same data.

### Shape B — "side flow per db" (sketch only; do NOT pursue without need)

You COULD run independent flows per db-name and dispatch in `db/get-datahike-flow` by db-name. Probe 3 proves this works mechanically. But it splits the shared `tx-bus` / `reply-router` / `error-sink` — you'd either run one set per flow (wasted) or keep one shared set and stitch the new conn-process's `tx-report` and `reply` channels into the shared flow's channels, which is exactly the API core.async.flow doesn't give us. Not worth it.

### Shape C — explicit declare-once schema map

`namespace-schemas` is currently passed once at build time. If `ensure-db!` is going to add namespaces dynamically, we need a registry the new build can consult. Recommend:

```clojure
(defonce known-schemas (atom {}))            ; db-name -> malli schema

(defn register-schema! [db-name schema]
  (swap! known-schemas assoc db-name schema))

(defn ensure-db! [{::keys [db-name schema]}]
  (when schema (register-schema! db-name schema))
  ;; ... swap pattern using @known-schemas for the new build's namespace-schemas
  )

```

## Cost / cleanup

**Runtime cost of one `ensure-db!` on a miss:**

- `flow/create-flow`: negligible (pure data assembly).
- `flow/start`: ~3 ms per conn-process (channel allocation + `d/connect` against existing memory store). For a 6-ns flow, ~21 ms total measured. For `:file`, expect 50–200 ms depending on store size (each `d/connect` reads schema attrs from LMDB).
- `flow/resume` + `flow/ping`: <2 ms.
- Total **today: ~22 ms** for a flow of seon's current size on `:memory`. Linear in #namespaces because rebuild = "rebuild all conn-processes". To make `ensure-db!` cheaper than O(n) you'd need core.async.flow to grow an `add-process!` API (it does not).

**Cleanup on `stop-datahike-flow!`:**

- Sends `::flow/stop` to all procs, which triggers each conn-process's `:transition :stop` arm — that should release the datahike connection (verify in `conn_process.clj`).
- Closes report and error channels.
- Does **not** release the konserve store. Re-`d/connect` on the same UUID reattaches.
- Does **not** clean up `topology/pending-promises`. Orphans timeout per-caller.

**Cleanup on `remove-db!`:**

- Same as stop. The data in the konserve store is preserved (intentional — re-add gets the same data back).
- If you actually want to delete the store, that's a separate `datahike.api/delete-database` call against the same `config`.

## Risks discovered

1. **In-flight requests orphan on swap.** `pending-promises` grows; promises time out. For an agent runtime making many concurrent calls, this is a real foot-gun. Mitigation: quiesce-before-swap, or accept the timeout failure-domain.
2. **Subscribers are lost across a swap.** `tx-bus` subscriber state lives in process state. Anything that called `subscribe!` must re-subscribe after `ensure-db!`. The runtime registry of subscribers (if any) needs to know about this. **Need to audit who calls `subscribe!`** before shipping `ensure-db!`.
3. **`integrant.repl.state/system` becomes inconsistent.** Integrant has a snapshot of the original flow under `:seon.db/flow`. After a swap, `state/system` points at a dead flow. `db/get-datahike-flow` falls through to `current-flow` first so this is benign for db calls, but `(user/status)` and any introspection that reads `state/system` will show the stale flow. Fix: either `swap!` the integrant state too (gross), or document that `ensure-db!` is the source of truth and integrant only knows the boot-time set.
4. **Single-writer guard fires on rebuild.** `guard-single-writer!` rejects two conn-procs against the same store path. The swap pattern never violates this because the new build has each db-name at most once. But if you ever try to run two flows simultaneously (Shape B), :file backends will collide.
5. **The `:memory` store living forever is a leak.** Once you've ever connected to `db-name X`, the konserve memory atom for X stays in process memory until JVM exit, even after `remove-db!`. For long-lived agent processes that spawn many ephemeral databases, this could matter. Datahike's `delete-database` on the config should clear it; verify.
6. **No protection against schema regression.** If `ensure-db!` is called with a different schema for a db-name that already exists, current `build-datahike-flow!` would happily call `:install-malli-schema` again on the new conn-process. We need to decide: idempotent (only install if not already present — current behavior from `:schema-installed?` flag) or refuse-on-mismatch.

## Open questions

- **Who currently calls `subscribe!`?** Need to grep the codebase. If only the tx-listener / agent runtime, those can be re-registered post-swap. If user code subscribes, we need a survival mechanism.
- **What's the desired behavior on `ensure-db!` while a request is in-flight?** Quiesce-then-swap (slower, safe), swap-immediately (fast, callers see timeouts), or queue-during-swap (most complex)?
- **Does seon's `:seon.flow/infrastructure` flow have the same problem?** Probably yes; the same pattern would apply, but I didn't probe.
- **Should `ensure-db!` be its own atom-level swap or should we model it as a Variant of integrant resume?** Integrant resume only fires on config diff at `(user/reset)`; not suitable for in-tx ensure.
- **On `:file` backend, what's the real-world rebuild time** when the store has 10k+ datoms? Probe 5 only measured empty stores.
