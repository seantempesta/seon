(ns seon.cluster.loop
  "The run loop: one proc, one wake, one turn at a time.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — N3,
  package 2, from n3-plan §4, §9 and the 2026-07-27 rulings). Nothing
  here is implemented: every body throws `awaits implementation`.

  AN ORDINARY `flow/process`, NOT A CUSTOM LAUNCHER. The wake arrives
  through `::flow/in-ports` — real channel objects returned in initial
  state, which Flow adds to the proc's own read set
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:219-232`)
  — so Flow's control priority, addressed pause/resume/ping, error
  reporting and continue-with-pre-step-state come free instead of being
  reimplemented. `seon.flow/fault-committer-proc` is the in-house
  precedent and the same shape. Consequence, to be landed with this:
  `seon.flow/database-proc` and its helpers become dead.

  BUILT WITH A VAR — `(flow/process #'step {:workload :io})` — so
  re-evaluating the transform updates a running proc with no graph
  rebuild. An anonymous `flow/map->step` result does NOT reload; that
  limit is measured and every N3 launcher inherits it.

  `:workload :io` IS LOAD-BEARING. The loop blocks on the model call; a
  `:compute` proc would occupy a bounded platform thread for its whole
  duration. The eval is the compute half and reaches `:compute` through
  `seon.flow/submit!!`, which is backpressure (a fixed-buffer channel)
  and parallelism (the bounded executor) as two mechanisms rather than
  the one Semaphore that used to conflate them.

  ONE WAKE, ONE PASS, SELF-REWAKE. The transform pins ONE database
  value, derives one piece of work, runs it, and — if more remains —
  `offer!`s a wake into its own in-port. It cannot recurse unboundedly:
  `offer!` on a `(sliding-buffer 1)` coalesces, and the pass is only
  re-entered after the transform returns.

  TURNS ARE SERIAL WITHIN A CLUSTER, deliberately for N3. The extension
  point is named so it is not invented later: submit each turn to a
  bounded `:io` class on the same work launcher, concurrency a config
  fact. Do not build it here; measure the ceiling at the review and
  decide against a number.

  THE TERMINAL TRANSACTION IS ONE COMMIT carrying the receipt AND the
  interpreted disposition. Splitting them reintroduces a torn window
  the quarry already closed (`driver.clj:289-297`). A rejected terminal
  transaction is followed by a terminal ERROR receipt carrying no agent
  value — under `store/transact!` that is a branch on a returned value,
  not a catch, which is strictly better.

  NOTHING RETRIES A PAID CALL, and recovery is not a code path: the
  loop only ever asks `seon.cluster.work/next-work` what to do, and a
  crashed run reaches it as ordinary facts. `interruption` is settled
  with no reply, and the agent's next prompt carries the one warning.

  Crash walk (n3-plan §9.3 rows 1-12): every row is a state
  `next-work`/`interruption` already answer, so this namespace's own
  crash contract is short — it holds no durable state of its own, its
  channel contents are discarded on stop (`flow/impl.clj:174-183`), and
  a killed pass leaves exactly the facts its last committed transaction
  wrote. The sealed suite drives the rows as kill positions in a
  state-machine property."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/loop.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The pure turn
;;; ---------------------------------------------------------------------------

(defn committed-attributes
  "Every attribute the loop's own transactions assert.
  Computed from the transitions this namespace commits, never a
  reviewed list — it exists so the wake/commit disjointness property
  (C2) has two computed sets to compare rather than one list to
  believe."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (throw (ex-info "awaits implementation" {::fn `committed-attributes})))

(defn disposition
  "The disposition an admitted eval value carries, or nil.
  The loop reads `my.run`'s two values out of the LAST form's admitted
  result. Anything else — a number, a map that merely looks similar, an
  error value — is not a disposition, and a run whose plan ends without
  one simply stays open for the next wake."
  {:malli/schema [:=> [:cat :any] [:maybe :my.run/value]]}
  [value]
  (throw (ex-info "awaits implementation" {::fn `disposition})))

(defn terminal-tx
  "The ONE transaction ending a form: its receipt AND the disposition.
  Pure tx-data. When the admitted value carries a disposition, that
  disposition's own facts (close + completion message, or release) ride
  in this same commit — one transaction, no torn window. When it does
  not, this is the receipt alone."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [:seon.cluster.run/id :seon.cluster.run/id]
                             [:seon.cluster.run/process
                              :seon.cluster.run/process]
                             [:seon.cluster.run/claim-epoch
                              :seon.cluster.run/claim-epoch]
                             [:seon.cluster.run.form/ordinal
                              :seon.cluster.run.form/ordinal]
                             [:seon.cluster.eval/status
                              :seon.cluster.eval/status]
                             [:seon.cluster.eval/result-edn {:optional true}
                              :seon.cluster.eval/result-edn]
                             [:seon.cluster.eval/error {:optional true}
                              :seon.cluster.eval/error]
                             [:my.run/value {:optional true} :my.run/value]]]
                  [:vector :any]]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `terminal-tx})))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(defn step
  "The run-loop transform, in Flow's four arities.
  `()` describes: `:workload :io`, no `:ins` (the wake is an in-port),
  one `::turn-report` out for observation only, and a `:ping-map-fn`
  exposing turn count and current run.
  `(args)` returns initial state carrying `::flow/in-ports {::wake ch}`
  and the cluster handle captured at `create-flow`.
  `(state transition)` unlistens on `::flow/stop`.
  `(state input-id message)` runs ONE pass: pin a database value, derive
  work, do it, rewake if more remains."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat [:map]] [:map]]
                  [:=> [:cat [:map] :keyword] [:map]]
                  [:=> [:cat [:map] :keyword :any] [:tuple [:map] [:maybe [:map]]]]]}
  ([] (throw (ex-info "awaits implementation" {::fn `step})))
  ([_args] (throw (ex-info "awaits implementation" {::fn `step})))
  ([_state _transition] (throw (ex-info "awaits implementation" {::fn `step})))
  ([_state _input-id _message]
   (throw (ex-info "awaits implementation" {::fn `step}))))

(defn turn
  "Run one turn to its next durable boundary; returns the turn report.
  The sequence is the contract: claim → derive prompt → model (`:io`)
  → split reply → freeze plan → reduce over ordered forms (running
  receipt → guarded eval at the previous step's `:db-after` → terminal
  receipt + disposition in ONE transaction) → close or release.
  Every failure inside it is a VALUE: a model error, an unreadable
  reply, and a refused transaction each end the turn with facts the
  agent reads on its next wake. Nothing throws into the loop."
  {:malli/schema [:=> [:cat [:map]] [:map]]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `turn})))
