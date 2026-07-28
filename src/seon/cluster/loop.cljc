(ns seon.cluster.loop
  "The run loop: one proc, one wake, one turn at a time.

  This contract layer is fully implemented and live-proven.

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
  (:require [clojure.core.async :as async]
            [sci.core :as sci]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.reply :as reply]
            [seon.cluster.run :as run]
            [seon.cluster.store :as store]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.error :as error]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Date]))

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
  ;; COMPUTED from the DECLARED ENTITIES this loop writes — the run,
  ;; its forms, and its receipts — plus the agent pointer a close
  ;; retracts. Reading the entity maps rather than filtering the
  ;; registry by namespace keeps out the things that live in those
  ;; namespaces without being attributes: the entity maps themselves,
  ;; and derived values like `:seon.cluster.run/missing-results`.
  ;;
  ;; Note what this set can and cannot prove. It is the right input for
  ;; the wake/commit disjointness property, but it CANNOT by itself
  ;; catch an attribute the boot path fails to install — a missing
  ;; entity map removes the attribute from this set and from the
  ;; installable set at once. The test that catches that class is the
  ;; one that transacts these rows into a database built the way boot
  ;; builds it.
  (into #{:seon.cluster.agent/run}
        (comp (mapcat (fn [entity]
                        (drop 2 (schema/schema-definition entity))))
              (map first))
        [:seon.cluster.run/run
         :seon.cluster.run.form/form
         :seon.cluster.eval/receipt
         ;; every model attempt is a durable row this loop writes, so it
         ;; belongs in the declared write set — and the class-killer
         ;; that asserts this set is installable is exactly what catches
         ;; a new entity family the boot path never learned about
         :seon.ai/attempt]))

(defn disposition
  "The disposition an admitted eval value carries, or nil.
  The loop reads `my.run`'s two values out of the LAST form's admitted
  result. Anything else — a number, a map that merely looks similar, an
  error value — is not a disposition, and a run whose plan ends without
  one simply stays open for the next wake."
  {:malli/schema [:=> [:cat :any] [:maybe :my.run/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.run/value value)
    value))

(defn terminal-tx
  "The ONE transaction ending a form: its receipt AND the disposition.
  Pure tx-data. When the admitted value carries a disposition, that
  disposition's own facts (close + completion message, or release) ride
  in this same commit — one transaction, no torn window. When it does
  not, this is the receipt alone."
  {:malli/schema [:=> [:cat :seon.cluster.loop/terminal-request :inst]
                  [:vector :some]]}
  [{:keys [:seon.cluster.run/id :seon.cluster.run/process
           :seon.cluster.run/claim-epoch :seon.cluster.run.form/ordinal
           :seon.cluster.eval/status :seon.cluster.eval/result-edn
           :seon.cluster.eval/error :seon.cluster.eval/output
           :my.run/value]}
   now]
  (let [receipt (cond-> {:seon.cluster.run/id id
                         :seon.cluster.run/claim-epoch claim-epoch
                         :seon.cluster.eval/ordinal ordinal
                         :seon.cluster.eval/status status}
                  result-edn (assoc :seon.cluster.eval/result-edn result-edn)
                  error (assoc :seon.cluster.eval/error error)
                  ;; what the form printed is evidence, and evidence is
                  ;; durable or it is nothing
                  output (assoc :seon.cluster.eval/output output))]
    (into (run/receipt-settle-tx receipt)
          ;; ONE transaction: the disposition's own transition rides
          ;; here, so the receipt and what it means are never two
          ;; commits with a window between them.
          (case (:my.run/disposition value)
            :completed (run/close-tx {:seon.cluster.run/id id
                                      :seon.cluster.run/process process
                                      :seon.cluster.run/claim-epoch claim-epoch
                                      :seon.cluster.run/closed-at now
                                      :seon.cluster.run/now now})
            :wait (run/release-tx {:seon.cluster.run/id id
                                   :seon.cluster.run/process process
                                   :seon.cluster.run/claim-epoch claim-epoch
                                   :seon.cluster.run/now now})
            nil))))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(declare turn settle-interruption!)

(defn- digest
  "The plan digest: SHA-256 over the ordered sources, so the same reply
  freezes to the same plan and N2's absent-to-digest fence is exact."
  [sources]
  (schema/sha-256 [(.getBytes (pr-str sources) "UTF-8")]))

(defn- error-tx
  "Transaction data recording one failure VALUE as a durable error fact.
  Pure over a database value — `seon.error/commit-tx` does the work and
  this is only the assembly of the dials the recorder needs. It exists
  because two callers need that assembly (a refused transition and a
  failed model attempt) and a second copy of it is how one of them
  quietly stops escalating.

  Attribution is passed in, never derived here: an `:open` that REFUSED
  has no run to point at, and a lookup ref to a run that does not exist
  would fail the very transaction that records the failure."
  [cluster db failure now attribution]
  (error/commit-tx
   db
   (merge {:seon.error/source failure
           :seon.error/id (str (random-uuid))
           :seon.error/at now
           :seon.error/process (:seon.cluster.run/process cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.error/basis-t (:max-tx db)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)}
          (when-let [escalate-to (:seon.config.error/escalate-to cluster)]
            {:seon.config.error/escalate-to escalate-to})
          attribution)))

(defn- refused!
  "Record one refused transaction as a durable error, and say it refused.
  Returns true when `outcome` was a refusal, so a call site reads
  `(if (refused! …) :error :released)` and the recording is not a
  second branch to keep in sync.

  THIS IS THE HOLE D3 NAMED. `store/transact!` preserves a transition's
  own rule verbatim — the exact CAS fence, the exact schema violation —
  and four of these five branches threw it away one line later, reducing
  it to the keyword `:error` in a turn report that goes to an out
  documented \"for observation only\". The `:call` branch had already
  learned the lesson the expensive way (the live drive sat
  claimed-with-no-plan for two minutes because a model error evaporated);
  this is that fix applied to the other four, now that the error owner
  those values belong to exists.

  Attribution is passed in, never derived here: an `:open` that REFUSED
  has no run to point at, and a lookup ref to a run that does not exist
  would fail the very transaction that records the failure.

  The recording's own outcome is deliberately ignored. This is the
  recursion fence: if the database refuses the error fact too, the
  answer is not to record THAT — `store/transact!` never throws, the
  loop keeps its pass, and the visible symptom stays the original
  refusal rather than an infinite regress of them."
  [cluster outcome now attribution]
  (boolean
   (when-let [kind (:seon.error/kind outcome)]
     (let [connection (:seon.store/branch-connection cluster)
           db @connection]
       (store/transact!
        connection
        (error-tx cluster db outcome now attribution)))
     kind)))

(defn- attempt-id
  "One model attempt's identity: derived, so nothing allocates a uuid.
  `<run-id>-attempt-<ordinal>` — the same (run, ordinal) idiom receipts
  use, and the reason a re-entered `:call` pass appends to the chain
  instead of overwriting its first row."
  [run-id ordinal]
  (str run-id "-attempt-" ordinal))

(defn- attempts
  "How many model attempts this run has already recorded.
  DERIVED at the start of a `:call` pass so the next ordinal continues
  the chain. A run whose plan transaction refused stays claimed and
  reaches `:call` again; without this its second call would reuse
  ordinal 0 and upsert away the first attempt's evidence.

  LONG, not `count`'s Integer. Datahike's `:db.type/long` validator is
  `(= (class %) java.lang.Long)` exactly, so an Integer ordinal refuses
  the WHOLE transaction — taking the error fact down with the attempt
  row. Coerced here, where the number is born, rather than at the call
  sites that would each have to remember."
  [db run-id]
  (long
   (count (d/q '[:find ?attempt
                 :in $ ?run-id
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?attempt :seon.ai.attempt/run ?run]]
               db run-id))))

;;; The transport-phase evidence the leaf recorded, carried onto the
;;; attempt row under THE PRODUCER'S OWN KEYS. Selected rather than
;;; re-keyed one by one: a `cond->` per field is five chances to drop
;;; one silently, and `false` is a meaningful value here that a
;;; truthiness test would eat.
(def ^:private evidence-attributes
  [:seon.ai/error-class :seon.ai/http-status :seon.ai/request-transmitted?
   :seon.ai/response-started? :seon.ai/output-observed?])

(defn- record-attempt!
  "Commit ONE model attempt — and its error fact when it failed.
  Returns the COMMITTED error fact on failure, nil otherwise.

  The error fact and the attempt row ride ONE transaction, with the
  attempt's `:seon.ai.attempt/error` pointing at the fact through the
  shared tempid. That is not tidiness: the caller may only build the
  backup's context from a fact that is already durable, and one
  transaction is what makes \"already durable\" true with no window.

  Returning nil after a REFUSED transaction is therefore load-bearing
  too — it means the story could not be recorded, and the caller
  correctly refuses to make a second paid call it would be unable to
  explain."
  [cluster request now]
  (let [{target :seon.ai/target
         failure :seon.error/value
         disposition :seon.ai/disposition
         run-id :seon.cluster.run/id
         agent-id :seon.cluster.agent/id
         ordinal :seon.ai.attempt/ordinal
         delay-ms :seon.ai.attempt/delay-ms
         failover-from :seon.ai.attempt/failover-from} request
        connection (:seon.store/branch-connection cluster)
        db @connection
        commit (when failure
                 (error-tx cluster db failure now
                           {:seon.cluster.agent/id agent-id
                            :seon.cluster.run/id run-id}))
        row (cond-> (merge
                     {:seon.ai.attempt/id (attempt-id run-id ordinal)
                      :seon.ai.attempt/run [:seon.cluster.run/id run-id]
                      :seon.ai.attempt/ordinal ordinal
                      :seon.ai.attempt/at now
                      :seon.ai/endpoint (:seon.ai/endpoint target)
                      :seon.ai/model (:seon.ai/model target)
                      :seon.ai.attempt/outcome (if failure :error :success)}
                     (select-keys (:seon.error/data failure)
                                  evidence-attributes))
              ;; the fact is created by THIS transaction, so the ref is
              ;; its tempid — a lookup ref to something the same
              ;; transaction is still creating is not a bet to take
              commit (assoc :seon.ai.attempt/error (:db/id (first commit)))
              disposition (assoc :seon.ai/disposition disposition)
              ;; ROLE BY CONNECTION: only the backup points back, so a
              ;; reader can tell a failover from a retry without a stamp
              failover-from (assoc :seon.ai.attempt/failover-from
                                   [:seon.ai.attempt/id failover-from])
              delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms))
        outcome (store/transact! connection (conj (vec commit) row))]
    (when-not (:seon.error/kind outcome)
      (some-> commit first (dissoc :db/id)))))

(defn- form-source
  "The source of one form of a run, by ordinal."
  [db run-id ordinal]
  (d/q '[:find ?source .
         :in $ ?run-id ?ordinal
         :where
         [?run :seon.cluster.run/id ?run-id]
         [?form :seon.cluster.run.form/run ?run]
         [?form :seon.cluster.run.form/ordinal ?ordinal]
         [?form :seon.cluster.run.form/source ?source]]
       db run-id ordinal))

(defn step
  "The run-loop transform, in Flow's four arities.
  `()` describes: `:workload :io`, no `:ins` (the wake is an in-port),
  NO outs of its own, and a `:ping-map-fn` exposing the turn count.
  The turn report goes out on `::flow/report` — flow's OWN observation
  channel — rather than a declared out of ours. That is not a
  preference: an out nobody connects makes `send-outputs` throw
  `can't resolve channel with io-id` on every completed turn, and
  because that throw lands on the error channel, it was invisible for
  as long as nothing read it. Reports are observation, flow already
  owns an observation channel with a sliding buffer and a monitor tap,
  and using it means there is no out to leave unconnected.
  `(args)` returns initial state carrying `::flow/in-ports {::wake ch}`
  and the cluster handle captured at `create-flow`.
  `(state transition)` unlistens on `::flow/stop`.
  `(state input-id message)` runs ONE pass: pin a database value, derive
  work, do it, rewake if more remains."
  ;; The zero-arity return is core.async.flow's OWN descriptor shape —
  ;; the one genuinely third-party map here, so it stays open. Every
  ;; shape that is ours is named and closed.
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :seon.cluster.loop/cluster]
                   :seon.cluster.loop/state]
                  [:=> [:cat :seon.cluster.loop/state :keyword]
                   :seon.cluster.loop/state]
                  [:=> [:cat :seon.cluster.loop/state :keyword :any]
                   [:tuple :seon.cluster.loop/state
                    [:maybe [:map-of :keyword [:vector :some]]]]]]}
  ([]
   {:workload :io
    :params {:seon.cluster.loop/cluster "the cluster this loop drives"}
    :ins {}
    :outs {}
    :ping-map-fn (fn [state]
                   (select-keys state [:seon.cluster.loop/turns]))})

  ([cluster]
   ;; the wake channel is created by the caller and handed over as an
   ;; in-port: Flow adds it to this proc's own read set, which is what
   ;; makes a custom launcher unnecessary
   {::flow/in-ports {:seon.cluster.wake/wake
                     (:seon.cluster.wake/channel cluster)}
    :seon.cluster.loop/cluster cluster
    :seon.cluster.loop/turns 0})

  ([state transition]
   (when (= ::flow/stop transition)
     (let [cluster (:seon.cluster.loop/cluster state)]
       (wake/unlisten! {:seon.cluster.wake/connection
                        (:seon.store/branch-connection cluster)
                        :seon.cluster.wake/key ::wake})))
   state)

  ([state _input-id _message]
   (let [cluster (:seon.cluster.loop/cluster state)
         connection (:seon.store/branch-connection cluster)
         now (Date.)
         request {:seon.cluster.run/process
                  (:seon.cluster.run/process cluster)
                  :seon.cluster.work/now now}
         ;; SETTLE BEFORE DERIVING. An orphaned run keeps its agent
         ;; busy, so a pass that derived work first would find nothing
         ;; to do for that agent and leave it wedged forever — which is
         ;; exactly what the crash drill measured.
         _ (doseq [orphan (work/interruptions @connection)]
             (settle-interruption! cluster
                                   (:seon.cluster.run/id orphan)
                                   now))
         ;; ONE database value for the derivation: everything this pass
         ;; decides after settling, it decides against one basis
         work (work/next-work @connection request)]
     (if (nil? work)
       [state nil]
       (let [report (turn {:seon.cluster.loop/cluster cluster
                           :seon.cluster.work/next work}
                          now)]
         ;; self-rewake, coalescing on the (sliding-buffer 1) in-port:
         ;; it cannot recurse, because the pass is only re-entered after
         ;; this transform returns
         (when (work/more-work? @connection request)
           (async/offer! (:seon.cluster.wake/channel cluster) ::wake))
         [(update state :seon.cluster.loop/turns inc)
          ;; flow's own report channel: observation, never a dependency.
          ;; A dropped report costs nothing and no consumer can
          ;; backpressure the loop.
          {::flow/report [report]}])))))

(defn settle-interruption!
  "Bury one orphaned run so its agent stops being busy.
  A run whose process died before its plan existed lost a paid model
  call that NOTHING re-calls, so there is no work to resume — only an
  agent held busy by a run nobody owns. Settling is claim-then-close
  through the ordinary transitions: a survivor cannot close a run it
  does not hold (`close-call` refuses `::not-the-holder`), so it takes
  custody by the takeover path first and closes as the holder.

  Boot recovery released the dead custody; this releases the AGENT.
  The two are deliberately separate: recovery states who no longer
  holds what, and settlement decides what to do about it — and only
  the loop is entitled to decide that.

  Settle-only for N3. The explanation an agent reads is derived from
  the settled run's own shape (no plan, closed) by
  `seon.cluster.prompt`; when a richer reason is wanted, this
  transaction is where it would ride."
  {:malli/schema [:=> [:cat :seon.cluster.loop/cluster
                       :seon.cluster.run/id :inst]
                  :boolean]}
  [cluster run-id now]
  (let [connection (:seon.store/branch-connection cluster)
        process (:seon.cluster.run/process cluster)
        claimed (store/transact!
                 connection
                 (run/claim-tx {:seon.cluster.run/id run-id
                                :seon.cluster.run/process process
                                :seon.cluster.run/lease-until
                                (Date. (+ (inst-ms now) 60000))
                                :seon.cluster.run/now now}))]
    (if (:seon.error/kind claimed)
      false
      (let [run (d/pull @connection '[*] [:seon.cluster.run/id run-id])
            closed (store/transact!
                    connection
                    (run/close-tx {:seon.cluster.run/id run-id
                                   :seon.cluster.run/process process
                                   :seon.cluster.run/claim-epoch
                                   (:seon.cluster.run/claim-epoch run)
                                   ;; the pass's ONE clock, not a second
                                   ;; reading of it — review-caught, and
                                   ;; the reason a state-machine property
                                   ;; over settlements can be exact
                                   :seon.cluster.run/closed-at now
                                   :seon.cluster.run/now now}))]
        (not (:seon.error/kind closed))))))

(defn turn
  "Run one turn to its next durable boundary; returns the turn report.
  The sequence is the contract: claim → derive prompt → model (`:io`)
  → split reply → freeze plan → reduce over ordered forms (running
  receipt → guarded eval at the previous step's `:db-after` → terminal
  receipt + disposition in ONE transaction) → close or release.
  Every failure inside it is a VALUE: a model error, an unreadable
  reply, and a refused transaction each end the turn with facts the
  agent reads on its next wake. Nothing throws into the loop."
  {:malli/schema [:=> [:cat :seon.cluster.loop/turn-request :inst]
                  :seon.cluster.loop/turn-report]}
  [{:keys [:seon.cluster.loop/cluster] work :seon.cluster.work/next}
   now]
  (let [connection (:seon.store/branch-connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)
        report (fn [outcome forms-run]
                 (cond-> {:seon.cluster.agent/id agent-id
                          :seon.cluster.work/situation
                          (:seon.cluster.work/situation work)
                          :seon.cluster.loop/forms-run forms-run
                          :seon.cluster.loop/outcome outcome}
                   run-id (assoc :seon.cluster.run/id run-id)))]
    (case (:seon.cluster.work/situation work)
      ;; OPEN + CLAIM FIRST, model second. The busy fence has to exist
      ;; before the expensive part, and the trigger rides as tx-meta so
      ;; answeredness needs no flag.
      :open
      (let [id (str (random-uuid))
            outcome (store/transact!
                     connection
                     {:tx-data
                      (into (run/open-tx {:seon.cluster.run/id id
                                          :seon.cluster.run/agent
                                          [:seon.cluster.agent/id agent-id]
                                          :seon.cluster.run/opened-at now})
                            (run/claim-tx {:seon.cluster.run/id id
                                           :seon.cluster.run/process process
                                           :seon.cluster.run/lease-until
                                           (Date. (+ (inst-ms now) 60000))
                                           :seon.cluster.run/now now}))
                      :tx-meta {:seon.db/trigger
                                [:seon.cluster.message/id
                                 (:seon.cluster.message/id work)]}})]
        ;; a REFUSED open has no run to attribute to — the run is what
        ;; failed to exist
        (report (if (refused! cluster outcome now
                              {:seon.cluster.agent/id agent-id})
                  :error
                  :released)
                0))

      ;; THE PAID CALL, and the ONE place a second one is ever made.
      ;;
      ;; NOTHING RE-CALLS A REQUEST THAT MAY HAVE BEEN TRANSMITTED. That
      ;; is not a rule this branch remembers to follow — `ai/disposition`
      ;; is the choke point, computed from the phase evidence the leaf
      ;; recorded, and every path out of a failure here goes through it.
      ;; The branch itself only reduces over its three ordinary values:
      ;;
      ;; - `:failover-now` — a conclusively unpaid failure WITH a backup
      ;;   configured. The primary's error fact commits FIRST, and the
      ;;   backup's system segment is `error/ai-prose` over that
      ;;   committed fact: the projection every other consumer gets, not
      ;;   a notice written here;
      ;; - `:backoff` — a conclusively unpaid TRANSIENT failure with no
      ;;   backup. The schedule is derived once, is EMPTY whenever a
      ;;   backup exists, and each wait is one more attempt row;
      ;;   the no-backup path is therefore the backoff path by
      ;;   construction rather than by a second condition;
      ;; - `:fail` — the run closes with the error, and the step-2
      ;;   delivery machinery does the rest.
      ;;
      ;; Every attempt, successful or not, leaves one `:seon.ai/attempt`
      ;; row. That is what makes "exactly two calls" and "exactly one
      ;; call" queryable facts rather than claims.
      :call
      (let [fail! (fn [failure]
                    ;; ONE transaction: the run closes and WHY it closed
                    ;; lands with it. Before this the error value
                    ;; evaporated — the drive sat claimed-with-no-plan
                    ;; for two minutes and the operator had to reproduce
                    ;; the call by hand to learn it was a missing key.
                    (let [run (d/pull @connection '[*]
                                      [:seon.cluster.run/id run-id])]
                      (store/transact!
                       connection
                       (into [[:db/add [:seon.cluster.run/id run-id]
                               :seon.cluster.run/error
                               (:seon.error/message failure)]]
                             (run/close-tx
                              {:seon.cluster.run/id run-id
                               :seon.cluster.run/process process
                               :seon.cluster.run/claim-epoch
                               (:seon.cluster.run/claim-epoch run)
                               :seon.cluster.run/closed-at now
                               :seon.cluster.run/now now})))
                      (report :error 0)))
            freeze!
            (fn [completion]
              ;; the reply became a plan, or it did not: unchanged, and
              ;; deliberately outside the attempt reduce — WHICH target
              ;; answered stops mattering the moment one did
              (let [sources (reply/sources (:seon.ai/text completion))]
                (if (:seon.error/kind sources)
                  (fail! sources)
                  (let [run (d/pull @connection '[*]
                                    [:seon.cluster.run/id run-id])
                        outcome (store/transact!
                                 connection
                                 (run/plan-tx
                                  {:seon.cluster.run/id run-id
                                   :seon.cluster.run/process process
                                   :seon.cluster.run/claim-epoch
                                   (:seon.cluster.run/claim-epoch run)
                                   :seon.cluster.run/plan-digest
                                   (digest sources)
                                   :seon.cluster.run/sources sources
                                   :seon.cluster.run/now now}))]
                    (report (if (refused! cluster outcome now
                                          {:seon.cluster.agent/id agent-id
                                           :seon.cluster.run/id run-id})
                              :error
                              :released)
                            0)))))
            text (prompt/prompt @connection
                                {:seon.cluster.agent/id agent-id
                                 :seon.cluster.message/id
                                 (first (map :seon.cluster.message/id
                                             (work/unanswered-triggers
                                              @connection agent-id)))})
            backup (:seon.ai/backup cluster)
            ;; DERIVED ONCE, and EMPTY whenever a backup exists. That is
            ;; the whole "backoff only on the no-backup path" rule, held
            ;; by the data instead of by a condition inside the reduce.
            schedule (if backup
                       []
                       (ai/delays (:seon.ai.retry/strategy cluster) rand))]
        (loop [target (:seon.ai/primary cluster)
               ordinal (attempts @connection run-id)
               ;; ABSENT on the primary and on every backoff retry;
               ;; present only on the backup, where it is both the role
               ;; and the proof of which failure supplied its context
               failover-from nil
               delay-ms nil
               waits schedule
               system nil]
          (let [completion (ai/complete
                            (cond-> (assoc target :seon.ai/prompt text)
                              system (assoc :seon.ai/system system)))
                failure (when (:seon.error/kind completion) completion)
                ;; a backup is only ever a target ONCE: the attempt that
                ;; already failed over cannot fail over again, and that
                ;; is what bounds a failover at exactly two calls
                disposition (when failure
                              (ai/disposition
                               {:seon.error/value failure
                                :seon.ai/backup? (and (some? backup)
                                                      (nil? failover-from))}))
                fact (record-attempt! cluster
                                      (cond-> {:seon.ai/target target
                                               :seon.cluster.run/id run-id
                                               :seon.cluster.agent/id agent-id
                                               :seon.ai.attempt/ordinal ordinal}
                                        failure (assoc :seon.error/value failure)
                                        disposition
                                        (assoc :seon.ai/disposition disposition)
                                        failover-from
                                        (assoc :seon.ai.attempt/failover-from
                                               failover-from)
                                        delay-ms
                                        (assoc :seon.ai.attempt/delay-ms
                                               delay-ms))
                                      now)]
            (cond
              (nil? failure) (freeze! completion)

              ;; THE RECORD REFUSED. Nothing else here is safe: a second
              ;; paid call whose reason could not be committed is a call
              ;; nobody could explain afterwards, and the backup's own
              ;; context would have no fact to project.
              (nil? fact) (fail! failure)

              (= :failover-now disposition)
              (recur backup
                     (inc ordinal)
                     (attempt-id run-id ordinal)
                     nil
                     waits
                     ;; THE PROJECTION, over the fact that is now
                     ;; durable — never a notice written at this call
                     ;; site. The backup reads exactly what the agent,
                     ;; the escalation owner and the log read.
                     (error/ai-prose
                      (error/notice {:seon.error/fact fact
                                     :seon.error/reason :failover})))

              (and (= :backoff disposition) (seq waits))
              (do
                ;; `:workload :io` is load-bearing here as well as at
                ;; the model call: this proc may block, and the wait is
                ;; bounded by a finite schedule rather than a loop
                ;; condition
                (Thread/sleep (long (first waits)))
                (recur target
                       (inc ordinal)
                       nil
                       (first waits)
                       (rest waits)
                       system))

              ;; `:fail`, and an exhausted schedule reaches the same
              ;; place: the run closes with the error, and step 2's
              ;; delivery machinery does the rest
              :else (fail! failure)))))

      ;; THE FOLD, in one turn, over ONE ctx. sci's fork copies the env,
      ;; so a ctx per FORM would lose every def between forms — the
      ;; State A defect. One fork per run is what makes a plan read like
      ;; a REPL session: form 2 sees what form 1 defined.
      ;;
      ;; A kill mid-fold loses the ctx, so a resumed fold starts fresh
      ;; and later forms may no longer resolve earlier defs. That is the
      ;; crash model working as designed — nothing re-executes, and the
      ;; agent's next prompt carries the interrupted warning — not a
      ;; case to paper over with a persisted context.
      :resume
      (let [run (d/pull @connection '[*] [:seon.cluster.run/id run-id])
            epoch (:seon.cluster.run/claim-epoch run)
            evaluate (requiring-resolve
                      (:seon.cluster.loop/evaluate cluster))
            ctx (sci/fork (sci.eval/base))]
        (loop [ordinal (:seon.cluster.run.form/ordinal work)
               ran 0]
          (let [started (store/transact!
                         connection
                         (run/receipt-start-tx
                          {:seon.cluster.run/id run-id
                           :seon.cluster.run/claim-epoch epoch
                           :seon.cluster.eval/ordinal ordinal
                           :seon.cluster.eval/at now}))]
            (if (refused! cluster started now
                          {:seon.cluster.agent/id agent-id
                           :seon.cluster.run/id run-id})
              (report :error ran)
              (let [source (form-source @connection run-id ordinal)
                    evaluation (evaluate
                                {:seon.cluster.run.form/source source
                                 :seon.sci.admit/caps
                                 (:seon.sci.admit/caps cluster)
                                 :seon.sci.eval/ctx ctx
                                 :seon.cluster.agent/id agent-id
                                 :seon.sci.eval/time-limit-ms
                                 (:seon.config.eval/time-limit-ms cluster)
                                 :seon.config/on-core-error
                                 (:seon.config/on-core-error cluster)})
                    settled (disposition (:seon.sci.admit/value evaluation))
                    outcome (store/transact!
                             connection
                             (terminal-tx
                              (cond-> {:seon.cluster.run/id run-id
                                       :seon.cluster.run/process process
                                       :seon.cluster.run/claim-epoch epoch
                                       :seon.cluster.run.form/ordinal ordinal
                                       :seon.cluster.eval/status
                                       (:seon.cluster.eval/status evaluation)}
                                (:seon.cluster.eval/result-edn evaluation)
                                (assoc :seon.cluster.eval/result-edn
                                       (:seon.cluster.eval/result-edn evaluation))
                                (:seon.cluster.eval/error evaluation)
                                (assoc :seon.cluster.eval/error
                                       (:seon.cluster.eval/error evaluation))
                                (:seon.cluster.eval/output evaluation)
                                (assoc :seon.cluster.eval/output
                                       (:seon.cluster.eval/output evaluation))
                                settled
                                (assoc :my.run/value settled))
                              now))
                    ran (inc ran)
                    next-ordinal
                    (when-not (or settled (:seon.error/kind outcome))
                      (:seon.cluster.run.form/ordinal
                       (work/next-work @connection
                                       {:seon.cluster.run/process process
                                        :seon.cluster.work/now now})))]
                (cond
                  (refused! cluster outcome now
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id run-id})
                  (report :error ran)

                  settled (report (if (= :completed
                                         (:my.run/disposition settled))
                                    :closed
                                    :released)
                                  ran)
                  next-ordinal (recur next-ordinal ran)
                  :else (report :released ran)))))))

      ;; the fold is done and nothing said otherwise: close it, so the
      ;; agent stops being busy
      :close
      (let [run (d/pull @connection '[*] [:seon.cluster.run/id run-id])
            outcome (store/transact!
                     connection
                     (run/close-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/claim-epoch
                                    (:seon.cluster.run/claim-epoch run)
                                    :seon.cluster.run/closed-at now
                                    :seon.cluster.run/now now}))]
        (report (if (refused! cluster outcome now
                              {:seon.cluster.agent/id agent-id
                               :seon.cluster.run/id run-id})
                  :error
                  :closed)
                0)))))
