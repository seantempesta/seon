(ns seon.turn
  "The per-agent turn proc, work derivation, and writer-owned transitions.

  Open means no closed-tx. Boot closes unfinished work; execution never
  resumes across a JVM restart. The agent graph advances open, call,
  evaluations, and close from database facts and rewakes only for more work."
  (:require [seon.error.refusal]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.blob :as blob]
            [seon.cluster.message :as message]
            [seon.cluster.wake :as wake]
            [seon.await :as await]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fault :as fault]
            [seon.flow :as seon.flow]
            [seon.id :as id]
            [seon.plan :as plan]
            [seon.profile.measurement :as measurement]
            [seon.program :as program]
            [seon.print.value :as print.value]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            [sci.core :as sci]
            [seon.batch]
            [seon.sci.admit :as admit]
            [seon.sci.batch :as sci.batch]
            [seon.sci.eval :as sci.eval]
            [seon.sci.forgive :as forgive]
            [seon.sci.kernel :as kernel]
            [seon.sci.observe :as observe]
            [seon.turn.batch :as turn.batch]
            [seon.sci.reader :as reader])
  (:import [java.util Date]
           [java.util.concurrent Executor]))

(schema.edn/load! {})

;;; `planned-sources` is defined below its first caller; this is a forward
;;; reference inside one namespace, not a load cycle.
(declare planned-sources)

;;; LOAD-CYCLE BOUNDARIES. Each of these namespaces requires `seon.turn`
;;; transitively, so this namespace cannot require them back. One resolution
;;; per var, realized at first use, instead of a `requiring-resolve` on every
;;; call (AGENTS §2.1: never fetch an input at call time).
(defonce ^:private cluster-agent-acquire-context!
  (delay (requiring-resolve 'seon.cluster.agent/acquire-context!)))
(defonce ^:private cluster-agent-evaluate-source!
  (delay (requiring-resolve 'seon.cluster.agent/evaluate-source!)))
(defonce ^:private cluster-prompt-prompt
  (delay (requiring-resolve 'seon.cluster.prompt/prompt)))
(defonce ^:private context-capture-tx
  (delay (requiring-resolve 'seon.context/capture-tx)))
(defonce ^:private problems-assignment-value
  (delay (requiring-resolve 'seon.problems/assignment-value)))
(defonce ^:private problems-form-problem
  (delay (requiring-resolve 'seon.problems/form-problem)))


(defn- result-blob-threshold
  [db]
  (db/q '[:find ?threshold .
          :where [_ :seon.config.eval.result/blob-threshold ?threshold]]
        db))

(defn stage-reply!
  "Stage exact submitted source using the run reply storage contract."
  {:malli/schema
   [:=> [:cat :seon.db/connection :string]
    [:map
     [:seon.turn/reply-size :seon.turn/reply-size]
     [:seon.turn/reply {:optional true} :seon.turn/reply]
     [:seon.turn/reply-blob {:optional true} :seon.turn/reply-blob]
     [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]}
  [connection text]
  (let [threshold (result-blob-threshold (db/db connection))
        size (long (count text))
        staged (when (and threshold (> size threshold))
                 (blob/stage! connection text))]
    (cond-> {::reply-size size
             :seon.blob/staged-writes (cond-> [] staged (conj staged))}
      staged (assoc ::reply-blob (:seon.blob/digest staged))
      (nil? staged) (assoc ::reply text))))

(defn evaluation-facts
  "Project a completed evaluation into its existing settlement facts."
  {:malli/schema [:=> [:cat :seon.turn/evaluation-facts-request]
                  :seon.cluster.eval/settle-request]}
  [{:keys [:seon.turn/id
           :seon.cluster.eval/ordinal :seon.sci.eval/evaluation
           :seon.problems/form-problem :my.turn/value]
    settlement-evaluation :seon.turn.loop/settlement-evaluation}]
  ;; NOTHING AFTER EVALUATION WALKS THE LIVE VALUE (task d1efccd3ab7e): the
  ;; value's only walk is the bounded print inside the evaluation's arm. An
  ;; unbound Var's value is a value, as in Clojure, shown `#object[...]`.
  (let [error (or (:seon.cluster.eval/error evaluation)
                  (:seon.cluster.eval/error form-problem)
                  ;; A record whose outcome is an error stores that error's
                  ;; shown text, so every reader sees the failure.
                  (when (= :error (get-in evaluation [:seon.sci.admit/record :seon.eval/outcome]))
                    (:seon.eval/shown evaluation)))]
    (cond-> {:seon.turn/id id
             :seon.cluster.eval/ordinal ordinal}
      (:seon.eval/origin settlement-evaluation)
      (assoc :seon.eval/origin (:seon.eval/origin settlement-evaluation))
      (:seon.eval/renderer settlement-evaluation)
      (assoc :seon.eval/renderer (:seon.eval/renderer settlement-evaluation)
             :seon.eval/renderer-fn [:seon.fn/sym (:seon.eval/renderer settlement-evaluation)])
      (:seon.eval/shown settlement-evaluation)
      (assoc :seon.eval/shown
             (:seon.eval/shown settlement-evaluation))
      error (assoc :seon.cluster.eval/error error)
      (:seon.cluster.eval/diagnostics settlement-evaluation)
      (assoc :seon.cluster.eval/diagnostics
             (:seon.cluster.eval/diagnostics settlement-evaluation))
      (:seon.cluster.eval/interrupted-at evaluation)
      (assoc :seon.cluster.eval/interrupted-at
             (:seon.cluster.eval/interrupted-at evaluation))
      (:seon.cluster.eval/output evaluation)
      (assoc :seon.cluster.eval/output
             (:seon.cluster.eval/output evaluation))
      (seq (:seon.cluster.eval/read-evidence evaluation))
      (assoc :seon.cluster.eval/read-evidence
             (:seon.cluster.eval/read-evidence evaluation))
      (:seon.cluster.eval/read-basis-transaction evaluation)
      (assoc :seon.cluster.eval/read-basis-transaction
             (:seon.cluster.eval/read-basis-transaction evaluation))
      (seq (:seon.cluster.eval/executed evaluation))
      (assoc :seon.cluster.eval/executed (:seon.cluster.eval/executed evaluation))
      (:seon.cluster.eval/code-evidence evaluation)
      (assoc :seon.cluster.eval/code-evidence (:seon.cluster.eval/code-evidence evaluation))
      (:seon.cluster.eval/call-count evaluation)
      (assoc :seon.cluster.eval/call-count (:seon.cluster.eval/call-count evaluation))
      (seq (:seon.cluster.eval/repeated-calls evaluation))
      (assoc :seon.cluster.eval/repeated-calls (:seon.cluster.eval/repeated-calls evaluation))
      (:seon.cluster.eval/ns evaluation)
      (assoc :seon.cluster.eval/ns (:seon.cluster.eval/ns evaluation))
      (:seon.sci.eval/ending-ns evaluation)
      (assoc :seon.sci.eval/ending-ns
             (:seon.sci.eval/ending-ns evaluation))
      ;; HOW LONG THE FORM TOOK IS A FACT THE KERNEL ALREADY MEASURED.
      ;; The evaluation carries it under the one spelling every reader uses
      ;; — the page's in-memory render included — and settlement stopped it
      ;; here, so `:seon.repl/ms` was in the grammar and in no response ever
      ;; emitted (audit F3).
      (int? (:seon.eval/duration-ms evaluation))
      (assoc :seon.eval/duration-ms (:seon.eval/duration-ms evaluation))
      ;; The rest of the evaluation's recorded cost (queryable, shown only
      ;; when a declared bound in :seon.config.eval/bounds is exceeded).
      (int? (:seon.eval/acquisition-ms evaluation))
      (assoc :seon.eval/acquisition-ms (:seon.eval/acquisition-ms evaluation))
      (int? (:seon.eval/allocated-bytes evaluation))
      (assoc :seon.eval/allocated-bytes (:seon.eval/allocated-bytes evaluation))
      ;; The shown value's size (whole estimate, shown, cuts, full opt-in).
      :always (merge (select-keys evaluation [:seon.eval/value-tokens :seon.eval/shown-tokens
                                              :seon.eval/elisions :seon.eval/full
                                              :seon.eval/unstored :seon.eval/value-class
                                              :seon.eval/replaced]))
      ;; A FORM'S OWN `set!` OF *print-length* / *print-level*. Captured
      ;; while sci's binding is still installed (`sci/eval.clj` print-options
      ;; volatile), it is what the response must print the value under.
      (int? (get-in evaluation [:seon.print/options :seon.print/length]))
      (assoc :seon.print/length
             (get-in evaluation [:seon.print/options :seon.print/length]))
      (int? (get-in evaluation [:seon.print/options :seon.print/level]))
      (assoc :seon.print/level
             (get-in evaluation [:seon.print/options :seon.print/level]))
      (:seon.eval/value-digest settlement-evaluation)
      (assoc :seon.eval/value-digest (:seon.eval/value-digest settlement-evaluation))
      value (assoc :my.turn/value value))))

(defn settlement-projection
  "Preserve evaluation evidence and stage a cut value's artifact blob."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]] :seon.turn.loop/evaluation]
    [:tuple :map :map [:vector :seon.blob/staged-write]]]}
  [cluster evaluation]
  ;; Shown text is already projected; settlement preserves its exact bytes.
  (let [receipt evaluation
        ;; A cut value's artifact (`seon.sci.eval/shown-result`) settles into
        ;; the blob tier under the same publication permit as its evaluation.
        receipt
        (if-let [value-edn (:seon.eval/value-edn receipt)]
          (let [staged (blob/stage! (:seon.db/connection cluster) value-edn)]
            (-> receipt
                (dissoc :seon.eval/value-edn)
                (assoc :seon.eval/value-digest (:seon.blob/digest staged))
                (update :seon.blob/staged-writes (fnil conj []) staged)))
          receipt)]
    [(dissoc receipt :seon.blob/staged-writes)
     evaluation
     (vec (:seon.blob/staged-writes receipt))]))

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn open?
  "True when the run has not closed."
  {:malli/schema [:=> [:cat
                       [:map
                        [::id ::id]
                        [::closed-tx {:optional true} ::closed-tx]]]
                  :boolean]}
  [run]
  (not (contains? run ::closed-tx)))

(defn terminal?
  "An evaluation settles with shown text, an error, or an interruption fact."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean (or (:seon.eval/shown receipt)
               (:seon.cluster.eval/error receipt)
               (:seon.cluster.eval/interrupted-at receipt))))

(defn interrupted-warning
  "Derive the ONE interrupted warning for a run, or nil when clean.
  Non-nil exactly when an evaluation carrying
  `:seon.cluster.eval/interrupted-at` exists among the supplied
  evaluations:
  {:seon.cluster.eval/ordinal first-interrupted-ordinal
   :seon.turn/missing-results count-at-or-after-it}.
  The caller supplies one run's evaluations and knows which run they
  belong to. This is the whole resume presentation — never per-eval
  markers."
  {:malli/schema [:=> [:cat
                       [:sequential
                        [:map
                         [:seon.cluster.eval/ordinal
                          :seon.cluster.eval/ordinal]
                         [:seon.cluster.eval/interrupted-at
                          {:optional true}
                          :seon.cluster.eval/interrupted-at]]]]
                  [:maybe [:map
                           [:seon.cluster.eval/ordinal
                            :seon.cluster.eval/ordinal]
                           [::missing-results ::missing-results]]]]}
  [evaluations]
  (when-let [ordinal
             (->> evaluations
                  (filter :seon.cluster.eval/interrupted-at)
                  (map :seon.cluster.eval/ordinal)
                  sort
                  first)]
    {:seon.cluster.eval/ordinal ordinal
     ::missing-results
     (count
      (filter #(>= (:seon.cluster.eval/ordinal %) ordinal)
              evaluations))}))

(defn opening-db
  "The database value this run opened on.

  The opening transaction is derived from the identity datom. Datahike
  `as-of` includes that transaction, so facts asserted with the opening are
  visible and every later transaction is absent by construction."
  {:malli/schema [:=> [:cat :seon.db/database-value ::id]
                  [:or :seon.db/database-value :seon.db/invalid-read-error :seon.turn/missing-opening-datom-error]]}
  [database id]
  (let [opening-tx
        (db/q '[:find ?tx .
                :in $ ?id
                :where
                [?run :seon.turn/id ?id ?tx]]
              database id)]
    (cond
      (:seon.db/invalid-read opening-tx) opening-tx
      opening-tx (db/as-of database opening-tx)
      :else (seon.error.refusal/diagnostic (Date.) :seon.turn/evaluation 'seon.turn/opening-db
             {:seon.error/message "The run has no opening datom."
       :seon.error/data {::id id} :seon.turn/missing-opening-datom true}))))

;;; ---------------------------------------------------------------------------
;;; Transitions — pure functions OF THE MID-TRANSACTION DATABASE VALUE,
;;; each invoked as [:db.fn/call f request] on the one serial writer.
;;;
;;; Shared contract, every `*-call`:
;;; - reads the current run/agent state from `db` (datahike.api/pull or
;;;   entity over the db value it is handed);
;;; - Throws ex-info carrying the refused transition and its violated rule.
;;;   naming the violated rule when the transition is ineligible — the
;;;   writer aborts the whole transaction atomically;
;;; - otherwise returns plain tx-data (the serial writer makes the read
;;;   atomic with the write; no nested CAS is needed or wanted).
;;;
;;; `require-open-run` checks existence and closure inside the serial writer.
;;; ---------------------------------------------------------------------------

(defn- refuse!
  "Abort the whole transaction, naming the rule the request violated."
  {:malli/schema [:=> [:cat :qualified-symbol :qualified-keyword :map]
                  :seon.turn/refused-error]}
  [transition rule request]
  (let [message (str "run transition refused: " (name rule))]
    (throw (ex-info message
                    (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) ::transition transition
                     {:seon.error/message message
                     ::rule rule
                     :seon.error/data {::request request}}) assoc :seon.error/declared-schema :seon.turn/refused-error)))))

(defn- refuse-closed-admission!
  "Refuse, inside the writer, a run for an agent whose admission is closed."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol :int :map] :nil]}
  [db transition agent-eid request]
  (when (:seon.agent/admission-closed-tx (db/pull db [:seon.agent/admission-closed-tx] agent-eid))
    (refuse! transition ::admission-closed request)))

(defn- current-run
  "The run's current facts on `db`, or nil when no such run exists.
  The mid-transaction pull IS the eligibility read: a missing lookup ref
  pulls to nil rather than throwing, so absence is an ordinary value."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]
                       ::id]
                  [:or :nil [:map [::id ::id]] :seon.error/value]]}
  [db id]
  (db/pull db '[*] [::id id]))

(defn- require-open-run
  "Read and require an existing open turn inside the serial writer."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]
                       :qualified-symbol
                       [:map [::id ::id]]]
                  [:map [::id ::id]]]}
  [database operation request]
  (let [turn (current-run database (::id request))]
    (cond
      (and (map? turn)
           (contains? turn :seon.error/at)
           (contains? turn :seon.error/layer)
           (contains? turn :seon.error/operation))
      (throw (ex-info (:seon.error/message turn) turn))

      (nil? turn) (refuse! operation ::no-such-run request)
      (not (open? turn)) (refuse! operation ::run-closed request)
      :else turn)))

(defn- running-receipts
  "Every receipt of run entity `run-eid` carrying no terminal fact,
  read from the mid-transaction database value — the read and the
  stamp share one transaction, so a settled receipt can never be
  stamped from a stale basis (custody revision, Revision 4)."
  [db run-eid]
  (->> (db/q '[:find [?receipt ...]
              :in $ ?run
              :where [?receipt :seon.cluster.eval/run ?run]]
            db run-eid)
       (map #(db/pull db '[*] %))
       (remove terminal?)))

(defn- interrupt-stamps
  "Interrupt unfinished evaluations at the writer's current database value."
  [db run-eid now]
  (mapv (fn [evaluation]
          [:db/add (:db/id evaluation) :seon.cluster.eval/interrupted-at now])
        (running-receipts db run-eid)))

;; The *-tx wrappers reference their *-call VARS (#'f): datahike applies
;; the var, so redefining a transition against the running system updates
;; behavior immediately — the flow-dynamics live-update pattern.
(declare close-call plan-call
         open-call receipt-start-call receipt-settle-call
         recover-call)



(defn open-for-agent
  "Read the agent's open turn id from its owning ref and absence of closed-tx."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/ref]
                  [:or [:maybe :seon.turn/id] :seon.error/value]]}
  [database agent-ref]
  (let [row (db/pull database [:db/id] agent-ref)]
    (if (or (:seon.db/invalid-read row) (:seon.schema/expected-value row))
      row
      (when-let [agent-eid (:db/id row)]
        (db/q '[:find ?id .
                :in $ ?agent
                :where [?turn :seon.turn/agent ?agent]
                [?turn :seon.turn/id ?id]
                (not [?turn :seon.turn/closed-tx])]
              database agent-eid)))))

(defn open-call
  "Open one turn inside the writer; an already open turn is ordinary state.
  The agent's owning ref and closed-tx facts supply the decision in the
  writer's database. No current-turn pointer is stored on the agent."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]
                        [::agent ::agent]
                        [::trigger {:optional true} ::trigger]
                        [::starting-ns {:optional true} ::starting-ns]
                        [:seon.turn.work/situation
                         {:optional true}
                         :seon.turn.work/situation]
                        [::opened-tx ::opened-tx]]]
                  :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id agent trigger starting-ns]
         situation :seon.turn.work/situation} request
        agent-eid (:db/id (db/pull db [:db/id] agent))
        run-tempid (str "seon.turn/" id)]
    (cond
      (nil? agent-eid) (refuse! `open-call ::no-such-agent request)
      (some? (open-for-agent db agent-eid)) []
      (some? (current-run db id))
      (if (= agent-eid (get-in (current-run db id) [::agent :db/id]))
        []
        (refuse! `open-call ::run-exists request))

      :else [(cond-> {:db/id run-tempid ::id id ::agent agent-eid :seon.turn.work/situation (or situation :call) ::opened-tx "datomic.tx"}
               trigger (assoc ::trigger trigger)
               starting-ns (assoc ::starting-ns starting-ns))
             {:seon.agent/id (:seon.agent/id (db/pull db [:seon.agent/id] agent-eid))
              :seon.agent/runtime
              (cond-> {:seon.runtime/agent agent-eid
                       :seon.runtime/turns [run-tempid]}
                trigger (assoc :seon.runtime/trigger trigger))}])))

(defn close-tx
  "Transaction data closing an open turn."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]

]]
                  :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'close-call request]])

(defn close-call
  "Close the turn and claim its covered messages at the writer.
  Routing datoms remain intact. Claims record handling, never wake answering."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map [::id ::id]]]
                  :seon.store/transaction-data]}
  [database request]
  (let [turn (require-open-run database `close-call request)
        turn-eid (:db/id turn)
        covered (db/q '[:find [?message ...]
                        :in $ ?turn
                        :where
                        [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/id _ ?opened]
                        [?turn :seon.turn/reply-size _ ?replied]
                        [(> ?replied ?opened)]
                        [?message :seon.message/to ?agent ?sent]
                        [(<= ?sent ?opened)]
                        (not [_ :seon.turn/handled ?message])]
                      database turn-eid)]
    (into [[:db/add turn-eid ::closed-tx "datomic.tx"]]
          (map (fn [message] [:db/add turn-eid ::handled message]))
          covered)))

(defn open-run-tx-call
  "Emit run-dependent transaction data only while its run is present and open.

  THE TERMINAL WRITER'S REFUSAL ARM MAY NOT REPEAT WHAT THE REFUSAL DENIED.
  Its two run-dependent components — the receipt settlement and the close —
  each assert through `receipt-run`/`require-open-run` that the run exists
  and is open. When the first terminal commit refused for exactly that
  reason (`::no-such-run`, `::run-closed`), handing them back unchanged makes
  the second refusal IDENTICAL TO THE FIRST BY CONSTRUCTION, and `settle!`'s
  last-resort throw then reports the RECORDING instead of the outcome.

  Measured on `default` (pid 30138) at 2026-09-16T15:33:01Z: agent juniper's
  turn `3f81dfc4c014` opened at `:t` 536870999 and its entity was retracted
  whole at 536871000, one transaction later; `close-call` refused
  `::no-such-run`, the refusal settlement re-issued the same close, refused
  the same way, and the loop committed a
  `:seon.turn.loop/terminal-refusal-settlement-refused` core fault instead of
  the turn's real outcome.

  The decision is the WRITER'S, not the caller's pre-read: a run that is gone,
  or already closed, needs neither settlement nor close — the post-condition
  the refusal arm wants already holds — and the error recording, which depends
  on no run, commits either way. The ordinary `close-tx`/`receipt-settle-tx`
  paths keep their strict refusals; the one-open-turn fence is untouched."
  {:malli/schema [:=> [:cat :seon.db/database-value ::id
                       :seon.store/transaction-data]
                  :seon.store/transaction-data]}
  [database run-id tx-data]
  (let [run (current-run database run-id)]
    (if (and run (open? run)) (vec tx-data) [])))

(defn- plan-tx-for-author
  [author request]
  [[:db.fn/call #'plan-call
    (assoc request :seon.cluster.eval/author author)]])

(defn plan-tx
  "Transaction data freezing one agent-authored form plan on the open turn."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]


                             [::reply {:optional true} ::reply]
                             [::reply-blob {:optional true} ::reply-blob]
                             [::reply-size {:optional true} ::reply-size]
                             [::starting-ns {:optional true} ::starting-ns]
                             [:seon.cluster.eval/at {:optional true}
                              :seon.cluster.eval/at]
                             [::sources :seon.cluster.reply/sources]]]
                  :seon.store/transaction-data]}
  [request]
  (plan-tx-for-author :agent request))

;;; ---------------------------------------------------------------------------
;;; The one identity a (run, ordinal) pair mints
;;; ---------------------------------------------------------------------------

;;; One turn and ordinal identify one evaluation. Message assignment
;;; correlation stores that evaluation identity independently of its subject.

(defn next-id
  "Identify the next turn from branch, agent, and its ordinal.

  The caller hands this identity to the writer. Compaction retains turns;
  competing openers derive the same identity and the writer admits one."
  {:malli/schema [:=> [:cat :seon.db/database-value :string
                       :seon.agent/id] ::id]}
  [database branch-id agent-id]
  (id/digest 12
             [::id branch-id agent-id
              (or (db/q '[:find (count ?turn) . :in $ ?agent-id
                          :where [?agent :seon.agent/id ?agent-id]
                          [?agent :seon.agent/runtime ?runtime]
                          [?runtime :seon.runtime/turns ?turn]]
                        database agent-id) 0)]))

(defn receipt-identity
  "The `:seon.cluster.eval/id` of one run's ordinal.
  Agent-facing: this is the problem identity an owner is asked to repair,
  and the one `seon.turn/problem-id` returns."
  {:malli/schema [:=> [:cat ::id :seon.cluster.eval/ordinal]
                  :seon.cluster.eval/id]}
  [run-id ordinal]
  (id/evaluation run-id ordinal))



(defn- resolve-namespace-name
  [db namespace-ref]
  (cond
    (vector? namespace-ref) (second namespace-ref)
    namespace-ref (:seon.ns/name
                   (db/pull db [:seon.ns/name] namespace-ref))))

(defn previous-ending-namespace
  "The namespace the agent's latest settled evaluation ended in, or nil.

  A REPL session keeps `*ns*` between inputs, so a run with no requested
  namespace starts where the agent's previous run ended."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] [:maybe :seon.ns/name]]}
  [db agent-eid]
  (when-let [evaluation (db/q '[:find (max ?evaluation) .
                                :in $ ?agent
                                :where
                                [?run :seon.turn/agent ?agent]
                                [?evaluation :seon.cluster.eval/run ?run]
                                [?evaluation :seon.sci.eval/ending-ns _]]
                              db agent-eid)]
    (:seon.sci.eval/ending-ns (db/pull db [:seon.sci.eval/ending-ns] evaluation))))

(declare receipt-row current-receipt)

(defn- current-transaction-instant
  "The transaction instant already allocated before a transaction call runs."
  [db]
  (:db/txInstant (db/pull db [:db/txInstant] (inc (db/basis-t db)))))

(defn- namespace-rows
  "One row per distinct namespace a plan's sources run in, keyed by the
  `namespace:` tempid. An existing namespace is only referenced; an absent
  one is its identity alone (the fold's facts declare its entity)."
  {:malli/schema [:=> [:cat :seon.ns/name [:sequential :map]] [:vector :map]]}
  [starting-namespace sources]
  (into []
        (comp (map #(or (:seon.ns/name %) starting-namespace)) (keep identity) (distinct)
              (map (fn [namespace-name]
                     {:seon.ns/name namespace-name :db/id (str "namespace:" namespace-name)})))
        (cons {:seon.ns/name starting-namespace} sources)))

(defn- evaluated-namespaces
  "One identity-only upsert per namespace the evaluations actually ran in or
  ended in, as SCI observed them, never as segmentation predicted: a nested
  `in-ns` moves the next form into a namespace no plan named (task a318b2d38).
  Placed before the evaluations that reference them, so each lookup ref
  resolves in the same transaction; an existing namespace is unchanged."
  {:malli/schema [:=> [:cat [:sequential :map]]
                  [:vector [:map [:seon.ns/name :seon.ns/name]]]]}
  [evaluations]
  (into []
        (comp (mapcat (fn [{namespace-ref :seon.cluster.eval/ns ending :seon.sci.eval/ending-ns}]
                        [(when (vector? namespace-ref) (second namespace-ref)) ending]))
              (filter symbol?)
              (distinct)
              (map (fn [namespace-name] {:seon.ns/name namespace-name})))
        evaluations))

(defn- source-rows
  "The shared namespace rows and the ordered evaluation entities of one plan.

  ONE ENTITY PER (run, ordinal): the frozen source, its author, its
  namespace and its comment are asserted here with NO terminal fact, and
  settlement accretes the terminal facts onto the same entity. The
  identity fence is the one `receipt-start-call` keeps — an ordinal that
  ever had an evaluation refuses forever, so nothing re-executes."
  [db id run-eid first-ordinal starting-namespace author at sources]
  (let [namespaces (namespace-rows starting-namespace sources)
          evaluations
          (into []
                (map-indexed
                 (fn [reply-ordinal form]
                   (let [ordinal (long (+ first-ordinal reply-ordinal))
                         namespace-name (or (:seon.ns/name form)
                                            starting-namespace)]
                     (when (some? (current-receipt db id ordinal))
                       (refuse! `plan-call ::receipt-exists
                                {::id id
                                 :seon.cluster.eval/ordinal ordinal}))
                     (receipt-row
                      run-eid
                      (cond-> {::id id
                               :seon.cluster.eval/ordinal ordinal
                               :seon.cluster.eval/at at
                               :seon.cluster.eval/author author
                               :seon.cluster.eval/source
                               (:seon.cluster.eval/source form)}
                        namespace-name
                        (assoc :seon.cluster.eval/ns
                               (str "namespace:" namespace-name))
                        (:seon.cluster.eval/comment form)
                        (assoc :seon.cluster.eval/comment
                               (:seon.cluster.eval/comment form)))))))
                sources)]
    (into namespaces evaluations)))

(defn plan-call
  "Store the reply and its ordered evaluations in one writer transaction.
  An open turn accepts one reply; existing reply bytes refuse a second."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]


                        [::reply {:optional true} ::reply]
                        [::reply-blob {:optional true} ::reply-blob]
                        [::reply-size {:optional true} ::reply-size]
                        [::starting-ns {:optional true} ::starting-ns]
                        [:seon.cluster.eval/author
                         :seon.cluster.eval/author]
                        [:seon.cluster.eval/at {:optional true}
                         :seon.cluster.eval/at]
                        [::sources :seon.cluster.reply/sources]]]
                  :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id reply reply-blob reply-size sources starting-ns]
         author :seon.cluster.eval/author} request
        ;; DERIVE AT THE AUTHORITY: the freeze instant is the instant this
        ;; transaction is committing at, which only the transaction knows.
        at (or (:seon.cluster.eval/at request)
               (current-transaction-instant db))
        run (require-open-run db `plan-call request)
        run-eid (:db/id run)
        agent-namespace
        (db/q '[:find ?namespace-name .
               :in $ ?agent
               :where
               [?agent :seon.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db (:db/id (::agent run)))
        starting-namespace
        (or (resolve-namespace-name db starting-ns)
            (previous-ending-namespace db (:db/id (::agent run)))
            agent-namespace)
        existing-form-count
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run
                    :where
                    [?form :seon.cluster.eval/run ?run]]
                  db run-eid)
             0))]
    (refuse-closed-admission! db `plan-call (:db/id (::agent run)) request)
    (when (some? (::reply-size run))
      (refuse! `plan-call ::plan-frozen request))
    (when-not (= :call (:seon.turn.work/situation run))
      (refuse! `plan-call ::not-call-situation request))
    (when-not starting-namespace
      (refuse! `plan-call ::starting-namespace-missing request))
    (into (cond-> [[:db/add run-eid ::reply-size (long (or reply-size (count (or reply (str/join "\n" (map :seon.cluster.eval/source sources))))))]
                    [:db/add run-eid ::starting-ns
                     (str "namespace:" starting-namespace)]]
            reply (conj [:db/add run-eid ::reply reply])
            reply-blob (conj [:db/add run-eid ::reply-blob reply-blob])
)
          (source-rows db id run-eid existing-form-count starting-namespace
                       author at sources))))

(defn open-tx
  "Transaction data opening one run for an agent."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::agent ::agent]
                             [::trigger {:optional true} ::trigger]
                             [::starting-ns {:optional true} ::starting-ns]
                             [:seon.turn.work/situation
                              {:optional true}
                              :seon.turn.work/situation]
                             [::opened-tx ::opened-tx]]]
                  :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'open-call request]])

(declare receipt-start-tx)

(defn- system-run-plan
  "The writer's decision on one system-authored run: its open and frozen
  source rows and the namespace it starts in, or the refusal thrown as
  `system-run-call` throws it. The agent's pass asks it once, on its value,
  before evaluating a submission; the writer asks again inside the
  transaction that opens the run."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/system-run-request]
                  [:map [::rows :seon.store/transaction-data] [:seon.ns/name :seon.ns/name]]]}
  [database request]
  (let [{agent-id :seon.agent/id
         ::keys [id starting-ns sources trigger]} request
        open-rows (open-call database
                                 (cond-> {::id id ::agent [:seon.agent/id agent-id] ::opened-tx "datomic.tx"}
                                   trigger (assoc ::trigger trigger)))
        _ (when (empty? open-rows)
            (refuse! `system-run-call ::agent-already-running request))
        opened (first open-rows)
        _ (refuse-closed-admission! database `system-run-call (::agent opened) request)
        agent-namespace (db/q '[:find ?name . :in $ ?agent
                                :where [?agent :seon.agent/namespace ?ns]
                                [?ns :seon.ns/name ?name]]
                              database (::agent opened))
        namespace-name (or (resolve-namespace-name database starting-ns)
                           (previous-ending-namespace database (::agent opened))
                           agent-namespace)]
    (when-not namespace-name
      (refuse! `system-run-call ::starting-namespace-missing request))
    {:seon.ns/name namespace-name
     ::rows
     (into [(merge opened
                   (select-keys request [::reply ::reply-blob ::reply-size])
                   {::starting-ns (str "namespace:" namespace-name)
                    ::reply-size (long (or (::reply-size request) (count (or (::reply request) (str/join "\n" (map :seon.cluster.eval/source sources))))))})]
           (concat (rest open-rows) (source-rows database id (:db/id opened) 0 namespace-name
                        :system (current-transaction-instant database) sources)))}))

(defn system-run-call
  "Open a system-authored turn and freeze its sources at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/system-run-request]
                  :seon.store/transaction-data]}
  [database request]
  (::rows (system-run-plan database request)))

(defn system-run-tx
  "Open, plan, and start every evaluation of one system-authored run.

  The caller owns the ordered sources. The ordinary run
  transaction functions retain every opening, plan, and evaluation
  fence. The entire execution intent is durable in this one transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/system-run-request]
                  :seon.store/transaction-data]}
  [_database request]
  [[:db.fn/call #'system-run-call request]])

(defn- submission-open-tx
  "A submitted run's open, written by whichever write reaches the writer first:
  a durable write the form makes (custody prepends it, `seon.db/*open*`) or the
  run's settlement. The writer applies it once (`seon.db/absent-call`)."
  {:malli/schema [:=> [:cat :seon.turn/system-run-request] :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'db/absent-call [::id (::id request)] [[:db.fn/call #'system-run-call request]]]])

(defn- system-run-if-admitted-call
  "`system-run-call`, or nothing when the writer refuses the open by one of its
  declared rules (admission closed at retirement, a run already open). A
  refusal settlement carries this form of the open: when the first commit was
  refused for the open itself, the refusal records its error and writes no run,
  rather than refusing identically a second time."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/system-run-request]
                  :seon.store/transaction-data]}
  [database request]
  (try (system-run-call database request)
       (catch clojure.lang.ExceptionInfo refused
         (if (::rule (ex-data refused)) [] (throw refused)))))

(defn append-generated-call
  "Append exactly one system-authored form to an open generated turn.

  The requested ordinal must equal the number of forms already present. For
  every noninitial append, the preceding ordinal must already have a terminal
  receipt. Initial and successor forms use the same `:generate` transition;
  those facts make prefix growth atomic and prevent both gaps and generation
  ahead of execution. A digest-backed run cannot enter this path."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/generated-form-request]
                  :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id]
         receipt-at :seon.cluster.eval/at
         ordinal :seon.cluster.eval/ordinal
         source :seon.cluster.eval/source
         comment :seon.cluster.eval/comment
         namespace-name :seon.ns/name} request
        held (require-open-run db `append-generated-call request)
        run-eid (:db/id held)
        expected
        (long (or (db/q '[:find (count ?form) .
                          :in $ ?run
                          :where [?form :seon.cluster.eval/run ?run]]
                        db run-eid)
                  0))
        prior-terminal?
        (or (zero? ordinal)
            (some?
             (db/q '[:find ?receipt .
                    :in $ ?run ?ordinal
                    :where
                    [?receipt :seon.cluster.eval/run ?run]
                    [?receipt :seon.cluster.eval/ordinal ?ordinal]
                    (or [?receipt :seon.eval/shown _]
                        [?receipt :seon.cluster.eval/error _]
                        [?receipt :seon.cluster.eval/interrupted-at _])]
                  db run-eid (dec ordinal))))]
    (when (some? (::reply-size held))
      (refuse! `append-generated-call ::plan-frozen request))
    (when-not (= :generate (:seon.turn.work/situation held))
      (refuse! `append-generated-call ::not-generate-situation request))
    (when-not (= expected ordinal)
      (refuse! `append-generated-call ::generated-ordinal request))
    (when-not prior-terminal?
      (refuse! `append-generated-call ::generated-prefix-unsettled request))
    (receipt-start-call
     db
     (cond-> {::id id
              :seon.cluster.eval/ordinal ordinal
              :seon.cluster.eval/at receipt-at
              :seon.cluster.eval/author :system
              :seon.cluster.eval/source source
              :seon.cluster.eval/ns [:seon.ns/name namespace-name]}
       (:seon.eval/origin request) (assoc :seon.eval/origin (:seon.eval/origin request))
       comment (assoc :seon.cluster.eval/comment comment)))))

(defn append-generated-tx
  "Transaction data appending one dependency-ready generated form."
  {:malli/schema [:=> [:cat :seon.turn/generated-form-request]
                  :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'append-generated-call request]])

(defn generated-run-tx
  "Open one zero-form generated system turn."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/generated-run-request]
                  :seon.store/transaction-data]}
  [_database request]
  (let [{agent-id :seon.agent/id
         run-id ::id
         opened-at ::opened-tx
         starting-ns ::starting-ns
         trigger ::trigger} request
        namespace-name (if (vector? starting-ns)
                         (second starting-ns)
                         starting-ns)]
    (open-tx
     (cond-> {::id run-id ::agent [:seon.agent/id agent-id] ::starting-ns [:seon.ns/name namespace-name] :seon.turn.work/situation :generate ::opened-tx "datomic.tx"}
       trigger (assoc ::trigger trigger)))))

(defn- current-receipt
  "The receipt identified by run and ordinal, or nil.
  Identity comes from `seon.id/evaluation` — AT MOST ONE ATTEMPT PER FORM,
  EVER, held by the identity itself: re-execution across any custody
  change is unrepresentable, strictly stronger than the epoch this
  replaced (custody revision 2026-07-28)."
  [db id ordinal]
  (db/pull db '[*] [:seon.cluster.eval/id (receipt-identity id ordinal)]))

(defn- receipt-run
  "The open run of a receipt request, or refuse."
  [db transition request]
  (let [{::keys [id]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! transition ::no-such-run request)
      (not (open? run)) (refuse! transition ::run-closed request)
      :else run)))

(defn receipt-start-tx
  "Transaction data starting one absent receipt.
  A started receipt carries no terminal fact — that absence IS running."
  {:malli/schema
   [:=> [:cat [:map
               [::id ::id]
               [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
               [:seon.cluster.eval/at :seon.cluster.eval/at]
               [:seon.cluster.eval/source {:optional true}
                :seon.cluster.eval/source]
               [:seon.cluster.eval/comment {:optional true}
                :seon.cluster.eval/comment]
               [:seon.cluster.eval/author {:optional true}
                :seon.cluster.eval/author]
               [:seon.cluster.eval/ns {:optional true}
                :seon.cluster.eval/ns]]]
    :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'receipt-start-call request]])

(defn- receipt-row
  [run-eid request]
  (let [{::keys [id]
         :seon.cluster.eval/keys [ordinal at source ns comment author]} request
        receipt-id (receipt-identity id ordinal)]
    (cond-> {:db/id receipt-id
             :seon.cluster.eval/id receipt-id
             :seon.cluster.eval/run run-eid
             :seon.cluster.eval/ordinal ordinal
             :seon.cluster.eval/at at}
      source (assoc :seon.cluster.eval/source source)
      (:seon.eval/origin request) (assoc :seon.eval/origin (:seon.eval/origin request))
      ;; The agent's prose is its own fact beside the source it introduces,
      ;; so a prompt line holds exactly the one form it prompts for.
      comment (assoc :seon.cluster.eval/comment comment)
      ;; WHO WROTE THIS FORM. Every site that starts an evaluation already
      ;; knows — a model reply freeze is `:agent`, a system run and a
      ;; generated append are `:system` — and each one dropped the fact on
      ;; the floor, so the attribute was declared and never written.
      author (assoc :seon.cluster.eval/author author)
      ns (assoc :seon.cluster.eval/ns ns))))

(defn receipt-start-call
  "Start one receipt, inside the transaction.
  The receipt must be absent. Identity derives from run and ordinal
  alone, so an ordinal that ever had a receipt refuses forever —
  nothing re-executes."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/at :seon.cluster.eval/at]
          [:seon.cluster.eval/source {:optional true}
           :seon.cluster.eval/source]
          [:seon.cluster.eval/comment {:optional true}
           :seon.cluster.eval/comment]
          [:seon.cluster.eval/author {:optional true}
           :seon.cluster.eval/author]
          [:seon.cluster.eval/ns {:optional true}
           :seon.cluster.eval/ns]]]
    :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id] :seon.cluster.eval/keys [ordinal]} request
        run (receipt-run db `receipt-start-call request)]
    (when (some? (current-receipt db id ordinal))
      (refuse! `receipt-start-call ::receipt-exists request))
    [(receipt-row (:db/id run) request)]))

(defn receipt-settle-tx
  "Transaction data settling one running receipt exactly once, at its writer
  authority. Settling IS asserting terminal facts: `result-edn`, `error`,
  and/or `interrupted-at` — at least one, and there is no status label.
  Program facts are the fold's (`seon.turn.batch/program-facts-call`), never
  an evaluation's."
  {:malli/schema [:=> [:cat :seon.cluster.eval/settle-request] :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'receipt-settle-call request]])

(defn receipt-settle-batch-call
  "Settle independent evaluations together, retaining each terminal fence."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:vector :seon.cluster.eval/settle-request]] :seon.store/transaction-data]}
  [database requests]
  (let [identities (mapv (juxt ::id :seon.cluster.eval/ordinal) requests)]
    (when-not (= (count identities) (count (set identities)))
      (refuse! `receipt-settle-batch-call ::receipt-terminal
               {::evaluations requests}))
    (into [] (mapcat #(receipt-settle-call database %)) requests)))

(defn receipt-settle-batch-tx
  "Transaction data settling an ordered turn batch in one commit: every
  evaluation in one writer call, after the namespaces they ran in."
  {:malli/schema
   [:=> [:cat [:vector :seon.cluster.eval/settle-request]] :seon.store/transaction-data]}
  [requests]
  (conj (evaluated-namespaces requests)
        [:db.fn/call #'receipt-settle-batch-call requests]))

(defn- affected-schema-attributes
  "Database attributes derived by the affected schema forms."
  {:malli/schema [:=> [:cat :seon.schema/projection [:set :keyword]]
                  [:set :qualified-keyword]]}
  [projection affected]
  (set
   (schema.datahike/database-attributes-core-in
    (update projection :seon.schema.projection/forms select-keys affected))))

(defn- schema-attribute-change-tx
  "Transaction data adopting an agent's schema change IN PLACE.

  The candidate projection's declarations are compared with the current
  ones, and every attribute whose storage declaration changed or whose form
  (directly or through a dependent schema) changed goes through THE one
  mechanism, `seon.cluster/attribute-change-tx`: the data behind a replaced
  or removed attribute is dropped, and a kept attribute drops the current
  values its new form refuses. `seon.cluster` requires this namespace, so the
  mechanism is resolved at the call."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       [:or :nil :seon.schema/projection] [:set :keyword]]
                  :seon.store/transaction-data]}
  [db current-projection candidate-projection schema-keys]
  (let [declarations-in
        (fn [projection]
          (if projection
            (into {}
                  (map (juxt :db/ident identity))
                  (schema.datahike/malli->datahike-schema-in
                   projection
                   (schema.datahike/database-attributes-in projection)))
            {}))
        current-declarations (declarations-in current-projection)
        candidate-declarations (declarations-in candidate-projection)
        changed
        (into #{}
              (filter #(not= (get current-declarations %)
                             (get candidate-declarations %)))
              (into (set (keys current-declarations))
                    (keys candidate-declarations)))
        affected
        (into #{}
              (filter #(contains? candidate-declarations %))
              (affected-schema-attributes
               current-projection
               (schema/dependent-schema-keys current-projection schema-keys)))]
    ((requiring-resolve 'seon.cluster/attribute-change-tx)
     db
     (sort (into changed affected))
     candidate-declarations
     candidate-projection)))

(defn- cardinality-many?
  [db attribute]
  (= :db.cardinality/many
     (get-in db [:schema attribute :db/cardinality])))

(defn- component-ref?
  [db attribute]
  (true? (get-in db [:schema attribute :db/isComponent])))

(defn- ref-attribute?
  [db attribute]
  (= :db.type/ref
     (get-in db [:schema attribute :db/valueType])))

(declare declared-map)

(defn- identity-ref
  [db value]
  (cond
    (and (vector? value) (= 2 (count value))) value
    (map? value) (identity-ref db (:db/id value))
    (number? value)
    (some (fn [identity-attribute]
            (when-some [identity-value
                        (get (db/pull db [identity-attribute] value)
                             identity-attribute)]
              [identity-attribute identity-value]))
          program/identity-attributes)
    :else value))

(defn- declared-one
  [db attribute value]
  (cond
    (component-ref? db attribute) (declared-map db value)
    (ref-attribute? db attribute) (identity-ref db value)
    :else value))

(defn- declared-value
  "One attribute's value as its declaration compares: a many value in order
   when it is a vector stored in cells, else as a set."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword :seon.schema/value] :seon.schema/value]}
  [db attribute value]
  (if (cardinality-many? db attribute)
    (db/stored-collection db attribute (map #(declared-one db attribute %) (or value [])))
    (declared-one db attribute value)))

(defn- declared-map
  [db value]
  (into {}
        (keep (fn [[attribute attribute-value]]
                (when (not= :db/id attribute)
                  [attribute (declared-value db attribute attribute-value)])))
        value))

(defn- declared-content
  [db value]
  ;; Contract arity rows are deterministic projections of the
  ;; declaration's `:seon.fn/spec`. Their component entity ids are database
  ;; mechanics, not declared content.
  (when-some [canonical (program/canonical-row value)]
    (declared-map db (dissoc canonical :seon.fn/arities))))

(defn- declaration-written-by-run?
  "True when the declaration identified by `identity-attribute`/`identity-value`
  and one receipt of `run-id` were asserted by the same terminal transaction.

  A run's own write is never a concurrent change, whichever declaration family
  it belongs to. The question is asked of the transaction rather than of one
  family's content attribute, so there is no per-family attribute to forget."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]
                       :qualified-keyword
                       :seon.schema/value
                       ::id]
                  [:or :boolean :seon.error/value]]}
  [db identity-attribute identity-value run-id]
  (let [history (db/history db)]
    (if (and (map? history)
             (contains? history :seon.error/at)
             (contains? history :seon.error/layer)
             (contains? history :seon.error/operation))
      history
      (let [receipt
            (db/q '[:find ?receipt .
                    :in $ ?identity-attribute ?identity-value ?run-id
                    :where
                    [?declaration ?identity-attribute ?identity-value]
                    [?declaration _ _ ?tx true]
                    [?receipt :seon.eval/shown _ ?tx]
                    [?receipt :seon.cluster.eval/run ?run]
                    [?run :seon.turn/id ?run-id]]
                  history identity-attribute identity-value run-id)]
        (if (and (map? receipt)
                 (contains? receipt :seon.error/at)
                 (contains? receipt :seon.error/layer)
                 (contains? receipt :seon.error/operation))
          receipt
          (boolean receipt))))))

(defn- declaration-diverged-since-open?
  "True when the current declaration differs from the one the request's run
  opened on and was not written by that run.

  Divergence is a claim ABOUT AN OPENING BASIS, so it is only measurable when
  the request names a run: a derivation with no run — a system-side or fixture
  caller building transaction data outside any run — opened on nothing and has
  no divergence to report. A request that DOES name a run whose opening
  database value cannot be read refuses loudly naming that missing basis,
  rather than reading the unreadable opening as an absent declaration and
  reporting a concurrent definition it never measured."
  [db request identity-attribute identity-value existing]
  (if-some [run-id (::id request)]
    (let [opening-database (opening-db db run-id)]
      (when (and (map? opening-database)
                 (contains? opening-database :seon.error/at)
                 (contains? opening-database :seon.error/layer)
                 (contains? opening-database :seon.error/operation))
        (throw (ex-info (:seon.error/message opening-database)
                        opening-database)))
      (let [opening-existing
            (db/pull opening-database '[*] [identity-attribute identity-value])]
        (if (and (map? opening-existing)
                 (contains? opening-existing :seon.error/at)
                 (contains? opening-existing :seon.error/layer)
                 (contains? opening-existing :seon.error/operation))
          (throw (ex-info (:seon.error/message opening-existing)
                          opening-existing))
          (when (not= (declared-content db opening-existing)
                      (declared-content db existing))
            (let [written?
                  (declaration-written-by-run?
                   db identity-attribute identity-value run-id)]
              (if (and (map? written?)
                       (contains? written? :seon.error/at)
                       (contains? written? :seon.error/layer)
                       (contains? written? :seon.error/operation))
                (throw (ex-info (:seon.error/message written?) written?))
                (not written?)))))))
    false))

(defn- row-tx
  "Validate and exact-upsert one reader-produced durable declaration."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :map]
                  :seon.store/transaction-data]}
  [db request row]
  (if-let [deleted-identities (:seon.program/delete-identities row)]
    (let [schema-keys
          (into #{}
                (keep (fn [[identity-attribute identity-value]]
                        (when (= :seon.schema/key identity-attribute)
                          identity-value)))
                deleted-identities)
          current-projection
          (when (seq schema-keys)
            (db/carried-projection db))
          candidate-projection
          (reduce schema/projection-without-schema
                  current-projection
                  (sort schema-keys))
          schema-tx
          (if (seq schema-keys)
            (schema-attribute-change-tx
             db current-projection candidate-projection schema-keys)
            [])
          declarations
          (into []
                (keep (fn [[identity-attribute identity-value]]
                        (when-let [declaration
                                   (db/pull db '[*]
                                           [identity-attribute identity-value])]
                          [identity-attribute identity-value declaration])))
                deleted-identities)]
      (into schema-tx
            (map
             (fn [[_ _ declaration]]
               [:db/retractEntity (:db/id declaration)]))
            declarations))
    (let [projection (db/carried-projection db)
          row (or (program/declaration-row projection row :all :agent)
                  (refuse! `receipt-settle-call
                           ::row-not-admitted request))
          [identity identity-value] (program/row-identity row)
          namespace-ref (or (:seon.fn/ns row)
                            (:seon.test/ns row))
          existing (when identity (db/pull db '[*] [identity identity-value]))
          ;; A namespace the declaration names exists whether or not a form
          ;; declared it: an absent one is asserted by identity alone.
          [namespace-name] (when (and (vector? namespace-ref)
                                      (not (:db/id (db/pull db [:db/id] namespace-ref))))
                             [(second namespace-ref)])
          row (cond-> row
                namespace-name (assoc (if (:seon.fn/ns row) :seon.fn/ns :seon.test/ns)
                                      (str "namespace:" namespace-name)))]
      (when (and namespace-ref (not (vector? namespace-ref))
                 (not (:db/id (db/pull db [:db/id] namespace-ref))))
        (refuse! `receipt-settle-call ::program-namespace-missing request))
      (let [current-projection
            (when (or (= :seon.schema/key identity)
                      (and (= :seon.fn/sym identity)
                           (:seon.fn/spec row)))
              projection)
            schema-redefinition?
            (and (= identity :seon.schema/key)
                 existing
                 (not= (:seon.schema/form existing)
                       (:seon.schema/form row)))
            ;; ONE decision path owns "may this declaration change". The
            ;; concurrency question — did the installed row diverge from the
            ;; basis this run opened on — is measured identically for every
            ;; declaration family. What differs is only what a legal change
            ;; then costs, and for a schema key that cost is the data the
            ;; in-place change drops (`schema-attribute-change-tx`).
            concurrent-declaration?
            (and (#{:seon.fn/sym :seon.schema/key} identity)
                 existing
                 (not= (declared-content db existing)
                       (declared-content db row))
                 (declaration-diverged-since-open?
                  db request identity identity-value existing))
            _ (when concurrent-declaration?
                (refuse! `receipt-settle-call
                         ::program-row-changed-after-open request))
            candidate-projection
            (case identity
              :seon.schema/key
              (schema/projection-with-schema
               current-projection identity-value
               (edn/read-string (:seon.schema/form row))
               {:seon.schema.admission/source :agent})

              :seon.fn/sym
              (when (:seon.fn/spec row)
              (schema/projection-with-function-contract
               current-projection identity-value
               (edn/read-string (:seon.fn/spec row))
               {:seon.schema.admission/source :agent}))

              nil)
            schema-declarations
            (if (= identity :seon.schema/key)
              (if schema-redefinition?
                (schema-attribute-change-tx
                 db current-projection candidate-projection #{identity-value})
                (let [current-attributes
                      (schema.datahike/database-attributes-in
                       current-projection)
                      candidate-attributes
                      (schema.datahike/database-attributes-in
                       candidate-projection)
                      required
                      (into []
                            (comp
                             (remove (set current-attributes))
                             (remove #(contains? (:schema db) %)))
                            (sort candidate-attributes))]
                  (schema.datahike/malli->datahike-schema-in
                   candidate-projection required)))
              [])]
        (into (cond-> schema-declarations
                namespace-name (conj {:db/id (str "namespace:" namespace-name) :seon.ns/name namespace-name}))
              (concat
               (cond
                 (nil? existing)
                 [(assoc row :db/id
                         (str (name identity) ":" identity-value))]

                 (= (declared-content db existing) (declared-content db row))
                 []

                 :else
                 (program/exact-replacement-tx existing row))
               ))))))

(def ^:private receipt-terminal-attributes
  [:seon.eval/shown
   :seon.eval/origin
   :seon.eval/renderer
   :seon.eval/renderer-fn


   :seon.cluster.eval/error
   :seon.cluster.eval/diagnostics
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/output
   :seon.cluster.eval/read-basis-transaction
   :seon.cluster.eval/executed
   :seon.cluster.eval/code-evidence
   :seon.cluster.eval/call-count
   :seon.cluster.eval/repeated-calls
   :seon.cluster.eval/ns
   :seon.sci.eval/ending-ns
   :seon.eval/duration-ms
   ;; The evaluation's recorded cost: queryable, shown only past a declared
   ;; bound (:seon.config.eval/bounds). Missing here, settlement dropped them.
   :seon.eval/acquisition-ms
   :seon.eval/allocated-bytes
   :seon.eval/value-tokens
   :seon.eval/shown-tokens
   :seon.eval/elisions
   :seon.eval/full
   :seon.eval/value-digest
   :seon.eval/unstored
   :seon.eval/value-class
   :seon.eval/replaced
   :seon.print/length
   :seon.print/level])

(defn- receipt-reference-assertions
  "One assertion per reference of the settlement window's faults, targeting
  `receipt`."
  [receipt request]
  (mapv (fn [reference] [:db/add (:db/id receipt) :seon.cluster.eval/faults reference])
        (:seon.cluster.eval/faults request)))

(defn- receipt-terminal-assertions
  "Terminal assertions present in `request`, targeting `receipt`. A set is a
  cardinality-many value: one assertion per element, and an empty set asserts
  nothing (a whole set in one `:db/add` is refused by the writer as a value
  of the element type)."
  [receipt request]
  (into
   []
   (mapcat (fn [attribute]
             (when-some [value (get request attribute)]
               (if (set? value)
                 (map (fn [element] [:db/add (:db/id receipt) attribute element]) value)
                 [[:db/add (:db/id receipt) attribute value]]))))
   receipt-terminal-attributes))

(defn- receipt-read-evidence-tx
  "Component read-evidence rows owned by one terminal receipt."
  [receipt request]
  (when-let [evidence (seq (:seon.cluster.eval/read-evidence request))]
    ;; `seon.db` wraps every `:db.fn/call` with
    ;; `schema.datahike/encode-call-output-in`, carrying the caller's handed
    ;; projection into Datahike's writer. Return ordinary transaction data and
    ;; let that one codec encode the function result. Rebuilding a projection
    ;; here paid 360--550 ms on every ordinary form settlement and duplicated
    ;; the outer authority.
    [{:db/id (:db/id receipt)
      :seon.cluster.eval/read-evidence
      (mapv (fn [ordinal entry]
              (assoc entry :db/id
                     (str "seon.cluster.eval/read-evidence/"
                          (:seon.cluster.eval/id receipt) "/" ordinal)))
            (range)
            evidence)}]))

(defn- recorded-evaluation
  "The immutable evaluation fields shared by saving and duplicate comparison.

  ONE ENTITY: the frozen source and author are compared here too, because
  the twin form entity that used to carry them is gone."
  [request]
  (cond-> (select-keys request
                       (into [:seon.cluster.eval/id :seon.cluster.eval/ordinal
                              :seon.cluster.eval/at :seon.cluster.eval/source
                              :seon.cluster.eval/author
                              :seon.cluster.eval/comment]
                             receipt-terminal-attributes))
    (seq (:seon.cluster.eval/read-evidence request))
    (assoc :seon.cluster.eval/read-evidence
           (set (map #(dissoc % :db/id)
                     (:seon.cluster.eval/read-evidence request))))))

(defn- recorded-run
  "The run fields a duplicate recording compares.

  The TRANSACTION REFS ARE NOT CONTENT: `opened-tx`/`closed-tx` enter as the
  writer's own \"datomic.tx\" tempid and come back as the resolved transaction
  entity, so comparing them made an identical re-record conflict with itself."
  [run]
  (dissoc run ::opened-tx ::closed-tx))

(defn- stored-record-content
  [database id]
  (let [run (current-run database id)
        evaluations
        (db/q '[:find [(pull ?evaluation
                            [* {:seon.eval/renderer-fn [:seon.fn/sym]}
                             {:seon.cluster.eval/ns [:seon.ns/name]}
                             {:seon.cluster.eval/read-evidence [*]}]) ...]
                :in $ ?run
                :where [?evaluation :seon.cluster.eval/run ?run]]
              database (:db/id run))]
    {::recorded-run
     (-> (select-keys run [::id ::agent ::starting-ns
                           ::reply ::reply-blob ::reply-size])
         (update ::agent :db/id)
         (update ::starting-ns #(resolve-namespace-name database (:db/id %))))
     ::evaluations
     (mapv (fn [evaluation]
             (cond-> (recorded-evaluation evaluation)
               (get-in evaluation [:seon.eval/renderer-fn :seon.fn/sym])
               (assoc :seon.eval/renderer-fn
                      [:seon.fn/sym (get-in evaluation [:seon.eval/renderer-fn :seon.fn/sym])])
               (:seon.cluster.eval/ns evaluation)
               (assoc :seon.cluster.eval/ns
                      [:seon.ns/name (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])])))
           (sort-by :seon.cluster.eval/ordinal evaluations))}))

(defn record-evaluated-call
  "Save completed evaluations atomically without acquiring execution custody.

  An identical cached identity is a no-op. Different saved content under that
  identity refuses; the same transaction can append its context contribution."
  {:malli/schema
   [:=> [:cat :seon.db/database-value ::record-evaluated-call-request]
    :seon.store/transaction-data]}
  [database request]
  (let [{::keys [id agent starting-ns sources evaluations]} request
        agent-data (db/pull database [:db/id :seon.agent/id] agent)
        agent-eid (when (:seon.agent/id agent-data) (:db/id agent-data))
        starting-namespace (resolve-namespace-name database starting-ns)
        run-eid (str "seon.turn/" id)
        run (assoc (select-keys request
                               [::id ::opened-tx ::closed-tx
                                ::reply ::reply-blob ::reply-size])
                   ::agent agent-eid
                   ::starting-ns starting-namespace
                   ::reply-size (long (or (::reply-size request)
                                          (count (or (::reply request)
                                                     (str/join "\n" (map :seon.cluster.eval/source sources)))))))
        prepared-evaluations
        (mapv (fn [ordinal source evaluation]
                (cond-> (assoc evaluation
                               :seon.cluster.eval/id
                               (receipt-identity id ordinal)
                               :seon.cluster.eval/author :system)
                  (nil? (:seon.cluster.eval/source evaluation))
                  (assoc :seon.cluster.eval/source
                         (:seon.cluster.eval/source source))))
              (range) sources evaluations)
        expected {::recorded-run (recorded-run run)
                  ::evaluations (mapv recorded-evaluation prepared-evaluations)}
        namespaces
        (namespace-rows starting-namespace (evaluated-namespaces evaluations))]
    (when-not agent-eid
      (refuse! `record-evaluated-call ::no-such-agent request))
    (when (db/q '[:find ?turn . :in $ ?agent
                  :where [?turn :seon.turn/agent ?agent]
                  (not [?turn :seon.turn/closed-tx _])]
                database agent-eid)
      (refuse! `record-evaluated-call ::agent-already-running request))
    (when-not starting-namespace
      (refuse! `record-evaluated-call ::starting-namespace-missing request))
    (when-not (and (seq sources)
                   (= (count sources) (count evaluations))
                   (= (vec (range (count sources)))
                      (mapv :seon.cluster.eval/ordinal evaluations)))
      (refuse! `record-evaluated-call ::receipt-ordinal-mismatch request))
    (when-not (every? terminal? evaluations)
      (refuse! `record-evaluated-call ::no-terminal-fact request))
    (if (current-run database id)
      (if (= expected (stored-record-content database id))
        []
        (refuse! `record-evaluated-call ::recorded-content-conflict request))
      (into [(assoc run :db/id run-eid
                    ::opened-tx "datomic.tx" ::closed-tx "datomic.tx"
                    ::starting-ns (str "namespace:" starting-namespace))
             {:seon.agent/id (:seon.agent/id (db/pull database [:seon.agent/id] agent-eid))
              :seon.agent/runtime {:seon.runtime/agent agent-eid
                                   :seon.runtime/turns [run-eid]}}]
            cat
            [namespaces
             (mapcat
              (fn [evaluation]
                (let [receipt (receipt-row run-eid evaluation)]
                  (into [receipt] cat
                        [(receipt-terminal-assertions receipt evaluation)
                         (receipt-read-evidence-tx receipt evaluation)])))
              prepared-evaluations)]))))

(defn record-evaluated-tx
  "Stage cached source/results and build their one completed recording call.

  This does not evaluate, install defs, deliver effects, or claim an agent.
  Publish the returned staged blobs around the transaction that saves these
  facts and adds the context contribution. The captured database supplies the
  opening commit even when the connection has advanced before Add to context."
  {:malli/schema
   [:=> [:cat ::record-evaluated-request]
    [:map
     [:seon.db/tx-data :seon.store/transaction-data]
     [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]}
  [{cluster :seon.turn.loop/cluster
    database :seon.db/db
    evaluated :seon.turn.loop/evaluated-sources
    :as request}]
  (let [id (::id request)
        staged-reply (stage-reply! (:seon.db/connection cluster) (::reply request))
        staged-evaluations
        (mapv (fn [{:keys [:seon.cluster.eval/ordinal
                          :seon.turn.loop/admitted-form
                          :seon.sci.eval/evaluation]}]
                (let [settled evaluation]
                  {:seon.cluster.eval/receipt
                   (cond-> (assoc (evaluation-facts
                                   {::id id
                                    :seon.cluster.eval/ordinal ordinal
                                    :seon.sci.eval/evaluation evaluation
                                    :seon.turn.loop/settlement-evaluation
                                    settled})
                                  :seon.cluster.eval/at
                                  (:seon.cluster.eval/at evaluation)
                                  :seon.cluster.eval/source
                                  (:seon.cluster.eval/source admitted-form)
                                  :seon.cluster.eval/ns
                                  (:seon.cluster.eval/ns admitted-form))
                     ;; THE COMMENT RIDES THE ADMITTED FORM, and this path
                     ;; dropped it, so an evaluation saved from the page lost
                     ;; the agent's own prose that a settled one keeps.
                     (:seon.cluster.eval/comment admitted-form)
                     (assoc :seon.cluster.eval/comment
                            (:seon.cluster.eval/comment admitted-form)))
                   :seon.blob/staged-writes (vec (:seon.blob/staged-writes settled))}))
              evaluated)
        prepared
        (merge (select-keys request [::id ::agent ::starting-ns ::opened-tx ::closed-tx])
               (dissoc staged-reply :seon.blob/staged-writes)
               {::sources (mapv (fn [item]
                                  (let [form (:seon.turn.loop/admitted-form item)]
                                    {:seon.cluster.eval/source (:seon.cluster.eval/source form)
                                     :seon.ns/name (resolve-namespace-name
                                                    database (:seon.cluster.eval/ns form))}))
                                evaluated)
                ::evaluations (mapv :seon.cluster.eval/receipt staged-evaluations)})]
    {:seon.db/tx-data [[:db.fn/call #'record-evaluated-call prepared]]
     :seon.blob/staged-writes
     (into (:seon.blob/staged-writes staged-reply)
           (mapcat :seon.blob/staged-writes) staged-evaluations)}))

(defn receipt-settle-call
  "Settle one running receipt, inside the transaction.
  The settle-once fence is PRESENCE: a receipt already carrying any
  terminal fact refuses `::receipt-terminal`, so a settled receipt
  never returns to running or changes outcome; a settle carrying no
  terminal fact refuses `::no-terminal-fact`, because \"settled with
  nothing settled\" is a caller bug."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.eval/shown {:optional true}
           :seon.eval/shown]


          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.cluster.eval/interrupted-at {:optional true}
           :seon.cluster.eval/interrupted-at]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]
          [:seon.cluster.eval/read-evidence {:optional true}
           :seon.cluster.eval/read-evidence]
          [:seon.cluster.eval/read-basis-transaction {:optional true}
           :seon.cluster.eval/read-basis-transaction]
          [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns]
          [:seon.sci.eval/ending-ns {:optional true}
           :seon.sci.eval/ending-ns]]]
    :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id]
         :seon.cluster.eval/keys [ordinal]} request
        run (receipt-run db `receipt-settle-call request)
        receipt (current-receipt db id ordinal)]
    (cond
      (nil? receipt)
      (refuse! `receipt-settle-call ::no-such-receipt request)

      (not= (:db/id run) (:db/id (:seon.cluster.eval/run receipt)))
      (refuse! `receipt-settle-call ::receipt-run-mismatch request)

      (not= ordinal (:seon.cluster.eval/ordinal receipt))
      (refuse! `receipt-settle-call ::receipt-ordinal-mismatch request)

      (terminal? receipt)
      (refuse! `receipt-settle-call ::receipt-terminal request)

      (not (terminal? request))
      (refuse! `receipt-settle-call ::no-terminal-fact request))
    (into [] cat
          [(receipt-read-evidence-tx receipt request)
           (receipt-reference-assertions receipt request)
           (receipt-terminal-assertions receipt request)])))

(defn recover-tx
  "Transaction data closing one interrupted turn at boot."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::now :inst]]]
                  :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'recover-call request]])

(defn recover-call
  "At boot, close every open turn and interrupt its unfinished evaluations
  and effects. The process root's lifetime lock excludes another JVM;
  a saved holder or a generated-source tag cannot exempt an open turn.
  Missing and already closed turns are no-ops. Completed evaluations
  remain unchanged, with eligibility decided inside the serial writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]
                        [::now :inst]]]
                  :seon.store/transaction-data]}
  [db request]
  (let [{::keys [id now]} request
        run (current-run db id)]
    (if (or (nil? run)
            (not (open? run)))
      []
      (let [interrupted
            (into (interrupt-stamps db (:db/id run) now)
                  (effect/interruption-stamps db (:db/id run) now))]
        (conj interrupted
              [:db/add (:db/id run) ::closed-tx "datomic.tx"])))))

;;; ---------------------------------------------------------------------------
;;; The family default renders — what a run, a form and a receipt LOOK
;;; LIKE to an agent reading its own neighbourhood.
;;;
;;; Declared on the registered entity maps in `resources/seon/schema.edn`
;;; (`:seon.render/ai` properties), which is `seon.schema`'s own idiom
;;; for `:seon.fn`, `:seon.ns` and `:seon.schema` — so a family declares
;;; its default where it declares everything else about itself, and
;;; `seon.render` finds it with no table.
;;;
;;; Each is a PLAIN FUNCTION: one unit map in, prose out. It reads the
;;; pulled entity it was handed and the database value riding beside it,
;;; and nothing else. `:seon.render/distance` is on the unit and these
;;; do not read it — a projection that ignores the budget is correct,
;;; and how far the walk goes is the walk's business.
;;;
;;; THEY CARRY THE DOCTRINE, and that is the point of writing them well.
;;; "Nothing was retried" used to be a sentence in one context block that
;;; only the prompt ever saw; here it belongs to the run, so every reader
;;; of a run — a prompt, a page, a debug view, another agent's
;;; neighbourhood — is told the same true thing by the same function.
;;; ---------------------------------------------------------------------------

(defn- run-receipts
  [db run-eid]
  (db/q '[:find [(pull ?receipt [*]) ...]
         :in $ ?run
         :where [?receipt :seon.cluster.eval/run ?run]]
       db run-eid))

(defn- receipt-value
  "The receipt's shown value, its shown text when that is not one EDN value,
  or nil when the receipt shows nothing."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.schema/value]]}
  [receipt]
  (some-> (:seon.eval/shown receipt) repl/shown-value))

(defn- unfinished-warning
  "The first form recovery closed before it started, and the missing count."
  [receipts]
  (let [terminal-ordinals
        (into #{}
              (comp (filter terminal?)
                    (map :seon.cluster.eval/ordinal))
              receipts)
        last-value (some->> receipts
                            (sort-by :seon.cluster.eval/ordinal)
                            last
                            receipt-value)
        missing
        (sort
         (remove terminal-ordinals
                 (map :seon.cluster.eval/ordinal receipts)))]
    ;; A completed/wait disposition deliberately closes the run and
    ;; leaves any later authored forms unstarted. That is not recovery.
    (when-let [ordinal (when-not (contains? #{:completed :wait}
                                            (:my.turn/disposition last-value))
                         (first missing))]
      {:seon.cluster.eval/ordinal ordinal
       ::missing-results (count missing)})))

(defn render-ai
  "`:seon.render/ai` — one run, as the agent's own history of it.

  STATE IS PRESENCE, read exactly as the model stores it: a turn with a `closed-tx` is over, one with an error
  never got a plan, and a cut fold is derived from its forms and
  receipts through the one `interrupted-warning`. There is no status
  attribute to restate and this invents none.

  The interruption sentences moved HERE from the retired `:interruption`
  context block: they are facts about a run, so they belong to the run's
  own lens and reach every consumer rather than one prompt. Both crash
  shapes say MAY, because rows 6 and 7 of the crash walk are
  indistinguishable from the facts and a confident claim would be a lie
  the agent then reasons from."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or [:maybe :string] :seon.db/error-result]]}
  [unit]
  (let [db (get unit :seon.db/db)
        id (get unit ::id)
        ;; a unit carrying the run's id but no entity id (a transacted run
        ;; map) reads its receipts through the run's identity
        receipts (when (and db id) (run-receipts db (or (:db/id unit) [::id id])))]
    (cond
      (not id) nil
      ;; a refused read is the answer, never receipts to derive from
      (db/read-refusal? receipts) receipts
      :else
      (let [;; A unit naming no opened transaction (a partial run map) says
            ;; nothing about when it opened; pulling nil is a refused read.
            opened-ref (let [ref (get unit ::opened-tx)] (if (map? ref) (:db/id ref) ref))
            opened (when (and db opened-ref)
                     (:db/txInstant (db/pull db [:db/txInstant] opened-ref)))
            cut (when db (interrupted-warning receipts))
            never-started
            (when (and db
                       (::closed-tx unit)
                       (::reply-size unit)
                       (nil? cut))
              (unfinished-warning receipts))
            ;; THE PAUSE NOTE IS A CONDITION OF THE RUN, which is why it
            ;; is read here and not only on the receipt: a run is one hop
            ;; from its agent and a receipt is two, so an agent asking
            ;; for the ordinary reach must still be handed the note it
            ;; left itself. The disposition IS the last form's admitted
            ;; value — already durable — so this reads it back and
            ;; stores nothing.
            note (some->> receipts
                          (sort-by :seon.cluster.eval/ordinal)
                          last
                          receipt-value
                          (#(when (and (map? %)
                                       (= :wait (:my.turn/disposition %)))
                              (:my.turn/note %))))
            state
            (cond
              cut (str "It was interrupted at form "
                       (:seon.cluster.eval/ordinal cut)
                       " — that form's effect may have happened, "
                       (::missing-results cut)
                       " result(s) are missing, and nothing was retried.")

              never-started
              (str "It was interrupted before form "
                   (:seon.cluster.eval/ordinal never-started)
                   " started — "
                   (::missing-results never-started)
                   " form(s) never ran, and nothing was retried.")

              (and db (some? (::closed-tx unit))
                   (not (db/q '[:find ?run . :in $ ?id
                                 :where [?run :seon.turn/id ?id]
                                 (or [?run :seon.turn/reply]
                                     [?run :seon.turn/reply-blob])]
                               db id))
                   (not (db/q '[:find ?attempt . :in $ ?id
                                 :where [?run :seon.turn/id ?id]
                                 [?run :seon.turn/attempts ?attempt]
                                 [?attempt :seon.ai.attempt/error]]
                               db id)))
              (str "It was interrupted before the reply arrived, and "
                   "nothing was retried.")

              note (str "It paused, leaving this note: " note)

              (some? (::closed-tx unit)) "It completed."
              (open? unit) "It is open in this JVM."
              :else "It is open.")]
        ;; `pr-str` and never the platform's `toString`: an inst printed
        ;; through the default formatter carries the RENDERING machine's
        ;; timezone and locale, so two derivations of one database value
        ;; would differ by where they ran — which equality suppression
        ;; and re-derivable capture both forbid. EDN is also the truth an
        ;; agent already reads.
        (str "Run " id (when opened (str ", opened " (pr-str opened))) ". "
             state)))))

(defn render-html
  "`:seon.render/html` — one run, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (render-ai unit)]
    [:article {:class "seon-family-entry seon-run-entry"}
     [:p text]]))

;; Debug controls enter higher execution layers only when called; those layers
;; depend on the writer transitions above during namespace loading.

(defn- source-events [source]
  (reader/read {:seon.sci.reader/text (:seon.cluster.eval/source source)
                :seon.sci.reader/ns (:seon.ns/name source)
                :seon.config.eval.result/max-source
                (count (:seon.cluster.eval/source source))}))

(defn- source-key [source]
  (let [events (source-events source)]
    [(:seon.ns/name source)
     (if (vector? events)
       (mapv :seon.sci.reader/form events)
       (:seon.cluster.eval/source source))]))

(defn- unit-read
  "What a rendered unit shows and the read that finds it again: its declared
  form face (`seon.render/render-form-value`: a schema's own requery, else the
  structural pull), as source text with its comment. The unit is an entity
  (`lookup`) or one attribute of one (`[entity attribute]`)."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.db/database-value :seon.agent/id :map]
                  [:map [:seon.cluster.eval/source :seon.cluster.eval/source]
                   [:seon.cluster.eval/comment {:optional true} :seon.cluster.eval/comment]]]}
  [handle database agent-id {lookup :seon.render.walk/lookup}]
  (let [[entity attribute] (if (vector? (first lookup)) lookup [lookup nil])
        value (if attribute
                (get (db/pull database [attribute] entity) attribute)
                (db/pull database '[*] entity))
        form (render/render-form-value
              (cond-> {:seon.db/db database
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                       :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                       :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)
                       :seon.agent/id agent-id
                       :seon.render/value value
                       :seon.render.value/root entity}
                attribute (assoc :seon.render.walk/attribute attribute)))
        entry (cond
                (:seon.repl/form form) form
                (and (map? form) (:seon.error/message form))
                ;; no form face could be selected: the structural read
                {:seon.repl/form (if attribute
                                   (list 'get (list 'seon.db/pull (list 'quote [attribute]) entity) attribute)
                                   (list 'seon.db/pull (list 'quote '[*]) entity))}
                :else {:seon.repl/form form})]
    (cond-> {:seon.cluster.eval/source (id/printed (:seon.repl/form entry))}
      (:seon.repl/comment entry) (assoc :seon.cluster.eval/comment (:seon.repl/comment entry)))))

(defn- declared-sources
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.db/database-value
                       :seon.agent/id :seon.ns/name]
                  [:or [:map [:seon.turn/forms :seon.cluster.reply/sources]
                         [:seon.render.walk/units :seon.render.walk/units]]
                   :seon.turn/system-refused-error]]}
  [handle database agent-id namespace-name]
  (let [calls (atom {})
        invocations (atom {})
        units (walk/neighborhood
               {:seon.db/db database
                :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                :seon.render.walk/lookup [:seon.agent/id agent-id]
                :seon.render/output :seon.render/ai
                :seon.render/captured-calls calls
                :seon.render/captured-invocations invocations
                :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)})]
    (or (some (fn [unit]
                (let [failure (:seon.error/value unit)]
                  (when-not (and (:seon.print/bound-by failure)
                                 (:seon.render.walk/limit failure)
                                 (:seon.render.walk/continuation-subject failure))
                    (when failure
                      (assoc failure :seon.turn/refused-system-agent agent-id))))) units)
        (let [sources (reduce
         (fn [sources unit]
           (if (:seon.cluster.reply/no-forms sources)
             (reduced sources)
           (let [lookup (:seon.render.walk/lookup unit)
                 call-id [:seon.render/ai lookup (:seon.render/distance unit)]
                 call (get @calls call-id)
                 blocks (or (:seon.render/source-blocks call)
                            (when-let [text (:seon.render.call/source call)]
                              [{:seon.render/source text}]))
                 data (:seon.render/output unit)]
             (if (and (empty? blocks) (some? data) (not (string? data)) (not (:seon.error/value unit)))
               ;; A DATA RENDER (renders return data, owner 313e81181): its AI
               ;; face is the unit's shown value, printed by the value printer
               ;; and never evaluated; its source is the read that finds the
               ;; data again, and its read evidence is the render's own.
               (conj sources
                     (merge
                     (unit-read handle database agent-id unit)
                     {:seon.ns/name namespace-name
                      :seon.render.walk/lookup lookup
                      :seon.turn/rendered
                      (cond-> {:seon.sci.admit/value data}
                        (seq (:seon.render.call/read-evidence call))
                        (assoc :seon.cluster.eval/read-evidence (:seon.render.call/read-evidence call)))}))
             (reduce
              (fn [sources block]
                (let [parsed (planned-sources
                              (:seon.render/source block) namespace-name
                              (get-in handle [:seon.sci.admit/caps
                                              :seon.config.eval.result/max-source]))]
                  (if (:seon.cluster.reply/no-forms parsed)
                    (reduced parsed)
                    (into sources
                          (map #(cond-> (assoc % :seon.render.walk/lookup lookup)
                                  (:seon.eval/origin block)
                                  (assoc :seon.eval/origin (:seon.eval/origin block))))
                          parsed))))
              sources blocks)))))
         [] units)]
          (if (:seon.cluster.reply/no-forms sources)
            (assoc sources :seon.turn/refused-system-agent agent-id)
            {:seon.turn/forms sources :seon.render.walk/units units})))))

(defn- latest-evaluations
  "The latest evaluation of each distinct source this agent evaluated, with
  its read evidence. Evidence is decoded for the kept evaluations only: every
  evaluation is folded with its evidence as references, then the kept ones'
  evidence is pulled whole. Decoding every stored plan of root's 146
  evaluations was 3.8 s of each system turn; the kept 23 take 0.5 s, and
  bounded plans (`seon.db/append-read-evidence!`) take the rest (default,
  2026-09-27)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:map-of [:tuple [:maybe :seon.ns/name] :seon.schema/value]
                   :seon.db/pulled-entity]]}
  [database agent-id]
  (let [latest
        (reduce
         (fn [latest [basis _ evaluation]]
           (let [source (assoc evaluation :seon.ns/name
                               (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])
                               :seon.turn/basis-t basis)
                 shown (repl/shown-value (:seon.eval/shown evaluation ""))
                 previous (get latest (source-key source))
                 shown (if (and previous (map? shown) (:seon.repl/changes shown)
                                (= :system (:seon.cluster.eval/author evaluation))
                                (not= :call (get-in evaluation [:seon.cluster.eval/run
                                                              :seon.turn.work/situation])))
                         (db/apply-diff (:seon.repl/shown-value previous)
                                        (:seon.repl/changes shown))
                         shown)
                 source (assoc source :seon.repl/shown-value shown)]
             (assoc latest (source-key source) source)))
         {}
         (sort-by (juxt first second)
                  (db/q '[:find ?t ?ordinal
                          (pull ?evaluation
                                [* {:seon.cluster.eval/ns [:seon.ns/name]}
                                 {:seon.cluster.eval/run [:db/id :seon.turn.work/situation]}
                                 {:seon.cluster.eval/read-evidence [:db/id]}])
                          :in $ ?id
                          :where [?agent :seon.agent/id ?id]
                          [?agent :seon.agent/runtime ?runtime]
                          [?runtime :seon.runtime/turns ?turn]
                          [?turn :seon.turn/id _ ?t]
                          [?evaluation :seon.cluster.eval/run ?turn]
                          [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                          [?evaluation :seon.cluster.eval/source]]
                        database agent-id)))
        evidence (into {}
                       (db/q '[:find ?evaluation
                               (pull ?evaluation [{:seon.cluster.eval/read-evidence [*]}])
                               :in $ [?evaluation ...]]
                             database
                             (into [] (comp (filter (comp seq :seon.cluster.eval/read-evidence))
                                            (map :db/id))
                                   (vals latest))))]
    (update-vals latest
                 (fn [evaluation]
                   (if-let [held (get-in evidence [(:db/id evaluation)
                                                   :seon.cluster.eval/read-evidence])]
                     (assoc evaluation :seon.cluster.eval/read-evidence held)
                     evaluation)))))

(defn- read-only-evaluation?
  "True when a successful evaluation only read this agent's database, so a
  system turn may evaluate it again. Every read must name this database's
  committed connection (`:datahike.cache/connection-id`, the identity Datahike
  keys its query cache on, datahike db.cljc:444): a read of another branch or
  of an uncommitted value is what a merge, a test run or a write-back does on
  its way to writing there, and this database holds no receipt for it (root's
  prepare-merge!, accept-merge! and write-back! evaluations were promoted,
  2026-09-26). Absent receipts and effects are necessary, not sufficient."
  [database evaluation]
  (let [events (source-events evaluation)
        evaluation-id (:db/id evaluation)
        run-id (get-in evaluation [:seon.cluster.eval/run :db/id])
        evidence (:seon.cluster.eval/read-evidence evaluation)
        own (:datahike.cache/connection-id (:cache-context database))]
    (and (nil? (:seon.cluster.eval/error evaluation))
         (seq evidence)
         (some? own)
         (every? #(= own (get-in % [:datahike.read/revision :datahike.cache/connection-id]))
                 evidence)
         (vector? events)
         (not-any? #(program/declaration-row (db/carried-projection database) % :all :agent) events)
         (nil? (db/q '[:find ?transaction . :in $ ?evaluation
                        :where [?transaction :seon.db/receipt ?evaluation]]
                      database evaluation-id))
         (nil? (db/q '[:find ?effect . :in $ ?turn ?ordinal
                        :where [?effect :seon.effect/run ?turn]
                        [?effect :seon.effect/form-ordinal ?ordinal]]
                      database run-id (:seon.cluster.eval/ordinal evaluation))))))

(declare generated-read-fault evaluate-sources phase)

(defn- code-currency
  "Each symbol the kept evaluations executed -> its current definition digest
  in `database` (absent: no such definition now), in one query."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.db/pulled-entity]]
                  [:map-of :qualified-symbol :seon.program/definition-digest]]}
  [database evaluations]
  (let [syms (into #{} (comp (mapcat :seon.cluster.eval/executed) (map first)) evaluations)]
    (if (empty? syms)
      {}
      (into {} (db/q '[:find ?sym ?digest :in $ [?sym ...]
                       :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])
                       [?e :seon.program/definition-digest ?digest]]
                     database syms)))))

(defn- code-current?
  "Whether a kept evaluation's code evidence is current: complete, and every
  definition it executed is still its symbol's current one. Unknown, pending or
  absent evidence (recorded before the one path) is not: the unit re-runs, and
  its re-run records its evidence."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol :seon.program/definition-digest] :seon.db/pulled-entity] :boolean]}
  [current previous]
  (and (= :complete (:seon.cluster.eval/code-evidence previous))
       (every? (fn [[sym digest]] (= digest (get current sym))) (:seon.cluster.eval/executed previous))))

(defn- system-plan
  "The opening's sources and the agent's retained reads, each with its status
  against its latest evaluation: none, unchanged (its read evidence is
  current) or changed."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.reply/sources
                       [:map-of [:tuple [:maybe :seon.ns/name] :seon.schema/value] :seon.db/pulled-entity]]
                  [:vector [:map [:seon.turn/status [:enum :none :unchanged :changed :pending]]]]]}
  [database declared latest]
  (let [current-digest (code-currency database (vec (vals latest)))]
  (into []
        (comp
         (filter #(let [previous (get latest (source-key %))]
                    (or (nil? previous)
                        (read-only-evaluation? database previous))))
         (map
          (fn [source]
            (let [previous (get latest (source-key source))
                  evidence (:seon.cluster.eval/read-evidence previous)
                  basis (:seon.cluster.eval/read-basis-transaction previous)
                  status (cond
                           (nil? previous) :none
                           ;; its own work has not exited: never a second run of it
                           (kernel/pending? (:seon.cluster.eval/id previous)) :pending
                           (not (db/read-evidence-current? database evidence)) :changed
                           (not (code-current? current-digest previous)) :changed
                           :else :unchanged)]
              (cond-> (assoc (select-keys source
                                         [:seon.cluster.eval/source
                                          :seon.cluster.eval/comment
                                          :seon.ns/name :seon.render.walk/lookup :seon.eval/origin
                                          :seon.turn/rendered])
                             :seon.turn/status status)
                basis (assoc :seon.cluster.eval/read-basis-transaction basis))))))
        (second
         (reduce (fn [[seen sources] source]
                   (let [source-identity (source-key source)]
                     (if (contains? seen source-identity)
                       [seen sources]
                       [(conj seen source-identity) (conj sources source)])))
                 [#{} []]
                 (concat declared
                         ;; Agent reads remain callable on demand. Only reads
                         ;; independent of turn-taking can become generated reads.
                         (filter #(and (read-only-evaluation? database %)
                                       (nil? (generated-read-fault
                                               database %
                                               (select-keys % [:seon.cluster.eval/read-evidence]))))
                                 (sort-by (juxt :seon.turn/basis-t
                                                :seon.cluster.eval/ordinal)
                                          (vals latest)))))))))

(defn- issue-origin-read? [database source]
  (when-let [origin (:seon.eval/origin source)]
    (some? (:seon.issue/agent
            (db/pull database [:seon.issue/agent] [:seon.issue/id origin])))))

(defn- generated-read-fault
  "Check read dependencies, independent of the evaluator or stored entity shape.
  Callers hand the captured evidence; reference expansion is not an input to this check."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.reply/form
                       [:map [:seon.cluster.eval/read-evidence {:optional true}
                              [:vector :seon.db/read-evidence]]]]
                  [:or :nil :seon.turn/generated-read-depends-on-turns-error]]}
  [database source dependencies]
  (let [inert (wake/inert-attributes database)
        identities (set (db/identity-attributes database))
        evidence (:seon.cluster.eval/read-evidence dependencies)
        offending (into (sorted-set)
                        (mapcat (fn [read]
                                  (let [position (:seon.db/source-argument-position read)
                                        source (some #(when (= position (:datahike.query.source/argument-position %)) %)
                                                     (get-in read [:datahike.read/dependency-plan
                                                                   :datahike.query.dependency/sources]))
                                        patterns (:seon.db/read-index-patterns source)
                                        ;; Freshness uses these complete patterns before
                                        ;; the conservative attribute revision as well.
                                        ;; A pattern bound to one existing entity sees only that
                                        ;; entity's datoms, and turn-taking asserts new entities
                                        ;; (turns, evaluations, attempts). Such a pattern names
                                        ;; turn-taking only through an attribute that entity can
                                        ;; change: a non-identity attribute it names, or, bound to
                                        ;; the entity alone, an attribute the entity holds (the
                                        ;; agent's `:my.agent/turns-left`). An identity names the
                                        ;; entity and never changes; resolving a message ref to
                                        ;; its identity probes every identity attribute (root's
                                        ;; `seon.error/for-agent` opening read, 2026-09-26).
                                        attributes (if (and patterns
                                                            (every? #(or (:seon.db/pattern-attribute %)
                                                                         (:seon.db/pattern-entity %))
                                                                    patterns))
                                                     (mapcat (fn [{entity :seon.db/pattern-entity
                                                                   attribute :seon.db/pattern-attribute}]
                                                               (cond
                                                                 (nil? entity) [attribute]
                                                                 (nil? attribute) (map :a (db/datoms database :eavt entity))
                                                                 (identities attribute) []
                                                                 :else [attribute]))
                                                             patterns)
                                                     (get-in read [:datahike.read/revision
                                                                   :datahike.read/attributes]))]
                                    ;; `:all` is the UNKNOWN attribute set of a
                                    ;; whole-database scan — a program-graph
                                    ;; cache refresh reading `[?e ?a]` over a
                                    ;; bound attribute collection states no
                                    ;; dependency on any particular attribute.
                                    ;; Answering it with the whole inert set
                                    ;; FABRICATED 68 dependencies the read never
                                    ;; had: `(my.issue/status …)` reaches
                                    ;; `seon.test/verified?` → the reach-digest
                                    ;; cache refresh, and the declared issue
                                    ;; block of every opening was refused for
                                    ;; attributes it never touched. A generated
                                    ;; read only depends on turn-taking when its
                                    ;; evidence NAMES a turn attribute; whether
                                    ;; an unnarrowed read must regenerate is
                                    ;; decided by its own authority — the
                                    ;; shown-value comparison in `system-turn`
                                    ;; and `db/read-evidence-current?`.
                                    (when-not (= :all attributes)
                                      (filter inert attributes)))))
                        evidence)]
    (when (and (seq offending) (not (issue-origin-read? database source)))
      (with-meta
       (seon.error.refusal/diagnostic (Date.) :seon.turn/evaluation `system-turn
       {:seon.turn/generated-read-attributes offending
        :seon.error/message "A generated context read depends on the agent's own turn-taking."
        :seon.error/member :seon.cluster.eval/source
        :seon.error/expected :seon.wake/context-inert
        :seon.error/offending (:seon.cluster.eval/source source)
        :seon.error/data (merge {:datahike.read/attributes offending} {:seon.error/layer :seon.turn})})
       {:seon.error/declared-schema :seon.turn/generated-read-depends-on-turns-error}))))

(defn system-turn
  "Project the declared opening and every distinct retained read form.
  Unchanged reads contribute no evaluation. With write? true, save the exact
  evaluated sources as one closed system turn with no provider attempt."
  {:malli/schema [:=> [:cat :seon.turn/system-request]
                  [:or :seon.turn/system-result :seon.turn/system-refused-error]]}
  [{handle :seon.turn.loop/cluster
    agent-id :seon.agent/id
    write? :seon.turn/write?}]
  (let [connection (:seon.db/connection handle)
        database (db/db connection)
        namespace-name (sci.eval/agent-namespace database agent-id)
        declared (when namespace-name
                   (declared-sources handle database agent-id namespace-name))]
    (cond
      (nil? namespace-name)
      (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.turn/system `system-turn
       {:seon.turn/rule ::agent-namespace-missing
        :seon.turn/refused-system-agent agent-id
        :seon.error/message "The system turn requires the agent's assigned namespace."
        :seon.error/member :seon.agent/id
        :seon.error/expected :seon.agent/id
        :seon.error/data {:seon.error/layer :seon.turn}}) assoc :seon.error/declared-schema :seon.turn/refused-error)

      (:seon.turn/refused-system-agent declared)
      (assoc declared :seon.turn/refused-system-agent agent-id)

      :else
      (let [latest (latest-evaluations database agent-id)
            plan (system-plan database (:seon.turn/forms declared) latest)
            ;; a unit whose own work has not exited is reported, never re-run
            selected (filterv #(#{:none :changed} (:seon.turn/status %)) plan)
            turn-id (when (and write? (seq selected))
                      (next-id database (:seon.cluster/name handle) agent-id))
            agent-ctx (when turn-id
                        (:seon.sci.eval/ctx
                         (@cluster-agent-acquire-context! handle agent-id)))
            ;; ONE FORK FOR EVERY PREVIEWED READ: the reads are read-only, so
            ;; they share one fork of the agent's context; a fork per read was
            ;; 58 ms each (root, default, 2026-09-28: 9 changed reads 526 ms)
            preview-ctx (delay (:seon.sci.eval/ctx
                                (sci.eval/fork-for-turn
                                 {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.db/db database
                                  :seon.db/connection connection :seon.agent/id agent-id})))
            previews (second
                      (reduce
                       (fn [[ordinal previews] source]
                         (let [preview (cond
                          ;; a data render's shown value, printed, never evaluated
                          (:seon.turn/rendered source)
                          {:seon.turn.loop/evaluated-sources
                           [{:seon.cluster.eval/ordinal ordinal
                             :seon.turn.loop/admitted-form
                             (assoc (dissoc source :seon.turn/rendered)
                                    :seon.cluster.eval/ns [:seon.ns/name (:seon.ns/name source)])
                             :seon.sci.eval/evaluation
                             (sci.eval/rendered-evaluation
                              (merge (:seon.turn/rendered source)
                                     {:seon.cluster.eval/ns [:seon.ns/name (:seon.ns/name source)]
                                      :seon.sci.eval/ctx (or agent-ctx (:seon.sci.eval/ctx handle))
                                      :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                                      :seon.db/db database
                                      :seon.agent/id agent-id}
                                     (when turn-id
                                       {:seon.repl/handle (admit/result-handle (receipt-identity turn-id ordinal))})))}]}
                          turn-id
                          {:seon.turn.loop/evaluated-sources
                           (:seon.turn.loop/evaluated-sources
                            (evaluate-sources
                            {:seon.turn.loop/cluster handle
                             :seon.db/db database
                             :seon.sci.eval/ctx agent-ctx
                             :seon.agent/id agent-id
                             :seon.turn/id turn-id
                             :seon.turn/write? true
                             :seon.cluster.eval/ordinal ordinal
                             :seon.ns/name (:seon.ns/name source)
                             :seon.cluster.reply/sources [source]}))}
                          :else
                          {:seon.turn.loop/evaluated-sources
                           (:seon.turn.loop/evaluated-sources
                            (evaluate-sources
                             {:seon.turn.loop/cluster handle
                              :seon.db/db database
                              :seon.sci.eval/ctx @preview-ctx
                              :seon.agent/id agent-id
                              :seon.cluster.eval/ordinal 0
                              :seon.ns/name (:seon.ns/name source)
                              :seon.cluster.reply/sources [source]}))})
                               previous (get latest (source-key source))
                               evaluation (get-in preview [:seon.turn.loop/evaluated-sources 0
                                                           :seon.sci.eval/evaluation])
                               unchanged? (and previous evaluation
                                               (= (:seon.repl/shown-value previous)
                                                  (repl/shown-value (:seon.eval/shown evaluation ""))))
                               preview (cond-> preview
                                         evaluation
                                         (assoc-in [:seon.turn.loop/evaluated-sources 0
                                                    :seon.cluster.eval/ordinal] ordinal)
                                         unchanged? (assoc :seon.turn/status :unchanged))]
                           [(if unchanged? ordinal (inc ordinal)) (conj previews preview)]))
                       [0 []] selected))]
        (or (some #(when (:seon.cluster.reply/no-forms %)
                     (assoc % :seon.turn/refused-system-agent agent-id)) previews)
            (let [;; A read whose fresh reads depend on turn-taking keeps its
                  ;; value with the fault beside it, never refusing the turn
                  ;; (root's loop parked, 2026-09-28); its stored evidence
                  ;; keeps it out of later openings.
                  previews (mapv (fn [source preview]
                                   (update preview :seon.turn.loop/evaluated-sources
                                           (partial mapv
                                                    (fn [evaluated]
                                                      (if-let [fault (generated-read-fault
                                                                      database source
                                                                      (select-keys (:seon.sci.eval/evaluation evaluated)
                                                                                   [:seon.cluster.eval/read-evidence]))]
                                                        (update-in evaluated [:seon.sci.eval/evaluation :seon.cluster.eval/diagnostics]
                                                                   #(str/join "\n" (remove nil? [% (print.value/bounded-text [fault])])))
                                                        evaluated)))))
                                 selected previews)silent (filterv #(= :unchanged (:seon.turn/status (second %)))
                                  (mapv vector selected previews))
                  refresh-tx
                  (into [] cat
                        (map (fn [[source preview]]
                               (let [previous (get latest (source-key source))
                                     evaluation (get-in preview [:seon.turn.loop/evaluated-sources 0
                                                                 :seon.sci.eval/evaluation])]
                                 (concat
                                  ;; The read-evidence vector below replaces the previous one whole.
                                  [[:db/add (:db/id previous) :seon.cluster.eval/read-basis-transaction
                                    (:seon.cluster.eval/read-basis-transaction evaluation)]]
                                  (when-let [origin (:seon.eval/origin source)]
                                    [[:db/add (:db/id previous) :seon.eval/origin origin]])
                                  (receipt-read-evidence-tx previous evaluation))))
                             silent))
                  emitted (filterv #(not= :unchanged (:seon.turn/status (second %)))
                                   (mapv vector selected previews))
                  selected (mapv first emitted)
                  previews (mapv second emitted)
                  evaluated (mapv (fn [source preview]
                                    (cond-> (first (:seon.turn.loop/evaluated-sources preview))
                                      (:seon.cluster.eval/comment source)
                                      (assoc-in [:seon.turn.loop/admitted-form
                                                 :seon.cluster.eval/comment]
                                                (:seon.cluster.eval/comment source))))
                                  selected previews)
                  evaluated
                  (mapv (fn [source item]
                          (if-let [previous (when-not (issue-origin-read? database source)
                                              (get latest (source-key source)))]
                            (let [evaluation (:seon.sci.eval/evaluation item)
                                  changed (db/diff
                                           {:seon.db.diff/before (:seon.repl/shown-value previous)
                                            :seon.db.diff/after
                                            (repl/shown-value (:seon.eval/shown evaluation ""))})]
                              (update item :seon.sci.eval/evaluation
                                      #(-> %
                                           (dissoc :seon.eval/renderer)
                                           (assoc :seon.eval/shown
                                                  (pr-str {:seon.repl/changes changed})))) )
                            item))
                        selected evaluated)
                  text-by-source
                  (into {} (map (fn [source item]
                                  [(source-key source)
                                   (repl/text
                                    (merge (:seon.turn.loop/admitted-form item)
                                           (:seon.sci.eval/evaluation item)
                                           (cond-> {:seon.ns/name (:seon.ns/name source)}
                                             (seq latest)
                                             (assoc :seon.repl/changed-since? true))))])
                                selected evaluated))
                  result {:seon.render.walk/units (:seon.render.walk/units declared)
                          :seon.turn/forms
                          (mapv #(if-let [text (get text-by-source (source-key %))]
                                   (assoc % :seon.turn/text text)
                                   (assoc % :seon.turn/status :unchanged)) plan)}]
              (if (and write? (or (seq evaluated) (seq refresh-tx)))
                (let [prepared (when (seq evaluated)
                                 (phase
                                  #(record-evaluated-tx
                                  {:seon.turn.loop/cluster handle :seon.db/db database :seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/reply (str/join "\n" (map :seon.cluster.eval/source selected)) :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.turn.loop/evaluated-sources evaluated})))
                      ;; A SYSTEM TURN RUNS NO ISSUE TESTS. It appends
                      ;; generated reads; it is not the agent answering, so
                      ;; nothing it settles can newly satisfy an issue.
                      ;; a recording its own contract refused is this turn's
                      ;; refusal as a value, never a throw to the caller
                      report (if (:seon.turn.loop/phase-failed prepared)
                              prepared
                             (blob/with-publication!
                              connection (vec (:seon.blob/staged-writes prepared))
                              #(db/transact!
                                connection
                                [[:db.fn/call
                                  (fn [current]
                                    ;; Compaction or another system pass may
                                    ;; have changed history during evaluation.
                                    ;; The writer admits this append only while idle,
                                    ;; against the history from which it was derived.
                                    (if (and (nil? (open-for-agent current [:seon.agent/id agent-id]))
                                             (= latest (latest-evaluations current agent-id)))
                                      (conj (into refresh-tx (:seon.db/tx-data prepared))
                                            [:db.fn/call #'plan/settle-call agent-id])
                                      []))]])))]
                  (if (or (:seon.turn.loop/phase-failed report) (:seon.db/transaction-refused report))
                    (assoc report :seon.turn/refused-system-agent agent-id)
                      (if (some #(and (= :seon.turn/id (:a %))
                                      (= turn-id (:v %))) (:tx-data report))
                        (assoc result :seon.turn/id turn-id)
                        result)))
                result)))))))

(defn compact-call
  "Retract one idle agent's evaluations at the database writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.store/transaction-data]}
  [database agent-id]
  (let [agent-eid (db/q '[:find ?agent . :in $ ?id
                         :where [?agent :seon.agent/id ?id]]
                       database agent-id)
        open-id (when agent-eid
                  (db/q '[:find ?id . :in $ ?agent
                          :where [?turn :seon.turn/agent ?agent]
                          [?turn :seon.turn/id ?id]
                          (not [?turn :seon.turn/closed-tx _])]
                        database agent-eid))]
    (when (or (nil? agent-eid) open-id)
      (let [message (if open-id
                      "Compaction requires the agent's current turn to close."
                      "Compaction requires an existing agent.")]
        (throw
         (ex-info message
                  (seon.error.refusal/diagnostic (Date.) :seon.turn/evaluation `compact!
                   {:seon.turn/compaction-agent-id agent-id
                    :seon.error/message message
                    :seon.error/member :seon.agent/id
                    :seon.error/expected :seon.agent/id
                    :seon.error/data {:seon.error/layer :seon.turn :seon.error/source (cond-> {} open-id (assoc :seon.turn/id open-id))}})))))
    (mapv (fn [evaluation] [:db.fn/retractEntity evaluation])
          (db/q '[:find [?evaluation ...] :in $ ?agent
                  :where [?turn :seon.turn/agent ?agent]
                  [?evaluation :seon.cluster.eval/run ?turn]]
                database agent-eid))))

(defn compact!
  "Retract an idle agent's evaluations; the next system walk is fresh.
  Answers the agent and the transaction that compacted it (its caller, the
  agent page, re-reads the database), or the writer's refusal."
  {:malli/schema [:=> [:cat :seon.turn/compaction-request]
                  [:or [:map [:seon.agent/id :seon.agent/id] [:seon.db/tx :seon.db/tx]]
                   :seon.db/transaction-refused-error]]}
  [{connection :seon.db/connection agent-id :seon.agent/id}]
  (let [report (db/transact! connection [[:db.fn/call #'compact-call agent-id]])]
    (if (:db-after report)
      {:seon.agent/id agent-id :seon.db/tx (db/basis-t (:db-after report))}
      report)))

(defn- await-durable!
  "Wait for `pending`, the commit of a settlement the writer answered when
  applied (`seon.db/transact-applied!`), under the cluster's turn-completion
  bound. A failed commit was already faulted by `settlement-lost!`; a timeout
  or failure escapes whole to the caller's fault route."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.await/blocking-deref] :nil]}
  [cluster pending]
  (let [attribute :seon.config.agent/turn-completion-backstop-ms
        outcome (await/await!
                 {:seon.await/bound {:seon.await/config-attribute attribute
                                     :seon.await/config-value
                                     (get (config/effective (db/db (:seon.db/connection cluster))
                                                            (:seon.cluster/name cluster))
                                          attribute)}
                  :seon.await/diagnostic {:seon.error/layer :seon.turn/settlement
                                          :seon.error/operation `await-durable!}
                  :seon.await/blocking-deref pending})]
    (when (or (:seon.await/config-attribute outcome) (instance? Throwable outcome))
      (throw (if (instance? Throwable outcome)
               outcome
               (ex-info (:seon.error/message outcome) outcome))))
    nil))

(defn virtual-turn!
  "Run a fixture reply as one ordinary run of the agent proc, without a
  provider (`seon.cluster.agent/evaluate-source!`). Returns its turn identity
  once the run has settled and committed, so a fixture reads it on the
  connection, or the pass's refusal (a run already open)."
  {:malli/schema [:=> [:cat :seon.turn/virtual-request]
                  [:or :seon.agent/source-submission-result
                   :seon.agent/source-submission-refused-error
                   :seon.await/timeout-error :seon.await/closed-error]]}
  [request]
  (let [result (@cluster-agent-evaluate-source!
                (update request :seon.cluster.reply/text #(or % "")))]
    (some->> (:seon.db/durable result) (await-durable! (:seon.turn.loop/cluster request)))
    result))



;;; ---------------------------------------------------------------------------
;;; Reading the facts
;;; ---------------------------------------------------------------------------

(defn- agent-run
  "The agent's open turn, derived from its owning ref and closed-tx."
  [database agent-id]
  (when-let [id (open-for-agent database [:seon.agent/id agent-id])]
    (db/pull database '[*] [:seon.turn/id id])))

(defn- evaluable-source?
  [source]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.config.eval.result/max-source (count source)
                             :seon.sci.reader/defer-auto-resolve? true})]
    (or (map? events) (seq events))))

(defn- next-ordinal
  "The first evaluable form with no terminal fact, or nil.
  Resume is a QUERY, never a cursor: an evaluation is terminal when it
  carries a terminal fact — `result-edn`, interruption evidence, `error`, or
  `interrupted-at` (the query twin of `run/terminal?`; there is no status to
  read) —
  and `recover-tx` has already stamped a dead process's dangling
  evaluations with `interrupted-at`, so an interrupted form is DONE being
  attempted and the fold moves past it. A comment-only source produces
  zero reader events, so it is durable input but never work. Nothing
  re-executes.

  ONE ENTITY PER (run, ordinal) makes this ONE query: the frozen source
  and the terminal facts are attributes of the same evaluation, so there
  is no second result set to join in Clojure."
  [db run-id]
  (->> (db/q '[:find ?ordinal ?source
               :in $ ?run-id
               :where
               [?run :seon.turn/id ?run-id]
               [?evaluation :seon.cluster.eval/run ?run]
               [?evaluation :seon.cluster.eval/ordinal ?ordinal]
               [?evaluation :seon.cluster.eval/source ?source]
               (not-join [?evaluation]
                         (or [?evaluation :seon.eval/shown _]
                             [?evaluation :seon.cluster.eval/error _]
                             [?evaluation
                              :seon.cluster.eval/interrupted-at _]))]
             db run-id)
       (keep (fn [[ordinal source]]
               (when (evaluable-source? source) ordinal)))
       sort
       first))

;;; ---------------------------------------------------------------------------
;;; Routed-problem settlement — derived, never stored
;;; ---------------------------------------------------------------------------


(defn close-folded-call
  "Close a replied run inside the settlement that gave its last form a terminal
  fact, decided at the writer on the value after the batch's receipts, so a
  trivial run commits twice rather than three times. A generated run (no reply
  size), a run with a form still to evaluate, and an agent holding an issue keep
  the separate close pass: the issue's tests must see the settled rows first."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [::id ::id] [:seon.agent/id :seon.agent/id]]]
                  :seon.store/transaction-data]}
  [database {run-id ::id agent-id :seon.agent/id}]
  (let [run (db/pull database [::reply-size ::closed-tx] [::id run-id])]
    (if (or (not (::reply-size run))
            (::closed-tx run)
            (next-ordinal database run-id)
            (db/q '[:find ?issue . :in $ ?id :where [?agent :seon.agent/id ?id]
                    [?issue :seon.issue/agent ?agent]] database agent-id))
      []
      (close-call database {::id run-id}))))
(defn problem-id
  "The receipt identity naming one form's derived problem."
  {:malli/schema [:=> [:cat :seon.turn/id
                       :seon.cluster.eval/ordinal]
                  :seon.problems/id]}
  [run-id ordinal]
  (receipt-identity run-id ordinal))

(defn planner-scoped-attempt?
  "True when `run-id` belongs to a goal's caused-by message chain.

  A planner attempt's recorded run trigger points at one member of the chain:
  either the depth-zero goal message itself or a later caused-by message.
  A triggerless historical run has no membership edge and fails closed."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/id]
                  :boolean]}
  [db run-id]
  (some? (message/trigger db run-id)))


(defn red-receipt?
  "True when a terminal receipt is red: error or interruption.

  Shown text is never read back to decide; an unbound Var's value is a value,
  as in Clojure, so it settles green."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean
   (or (:seon.cluster.eval/error receipt)
       (:seon.cluster.eval/interrupted-at receipt))))

(defn resume-artifact?
  "True when this ordinal's failure belongs to interrupted process history.
  A directly interrupted receipt and every later ordinal after an interrupted
  prefix are excluded from owner routing; neither says owner code is wrong."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/id
                       :seon.cluster.eval/ordinal :boolean]
                  :boolean]}
  [db run-id ordinal interrupted?]
  (boolean
   (or interrupted?
       (db/q '[:find ?receipt .
              :in $ ?run-id ?ordinal
              :where
              [?run :seon.turn/id ?run-id]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/ordinal ?prior]
              [(< ?prior ?ordinal)]
              [?receipt :seon.cluster.eval/interrupted-at _]]
            db run-id ordinal))))

(defn form-owner
  "The parse-time namespace owner, or the run author as the total fallback."
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  :seon.agent/id]}
  [db form]
  (let [form-eid (:db/id form)
        namespace-owner
        (when (contains? (:schema db) :seon.cluster.eval/ns)
          (db/q '[:find ?owner-id .
                 :in $ ?form
                 :where
                 [?form :seon.cluster.eval/ns ?namespace]
                 [?owner :seon.agent/namespace ?namespace]
                 [?owner :seon.agent/id ?owner-id]]
               db form-eid))]
    (or namespace-owner
        (db/q '[:find ?author-id .
               :in $ ?form
               :where
               [?form :seon.cluster.eval/run ?run]
               [?run :seon.turn/agent ?author]
               [?author :seon.agent/id ?author-id]]
             db form-eid))))

(defn- terminal-receipt?
  [receipt]
  (boolean
   (and receipt
        (or (:seon.eval/shown receipt)
            (:seon.cluster.eval/error receipt)
            (:seon.cluster.eval/interrupted-at receipt)))))

(defn- form-run-id
  [db form]
  (db/q '[:find ?run-id .
         :in $ ?form
         :where
         [?form :seon.cluster.eval/run ?run]
         [?run :seon.turn/id ?run-id]]
       db (:db/id form)))

(defn- assignment-facts
  [db evaluation owner-id]
  (let [evaluation-eid (:db/id evaluation)
        author-eid
        (db/q '[:find ?author .
               :in $ ?form
               :where
               [?form :seon.cluster.eval/run ?run]
               [?run :seon.turn/agent ?author]]
             db evaluation-eid)
        owner-eid
        (db/q '[:find ?owner .
               :in $ ?owner-id
               :where [?owner :seon.agent/id ?owner-id]]
             db owner-id)
        assignment?
        (boolean
         (and evaluation-eid owner-eid author-eid
              (db/q '[:find ?assignment .
                     :in $ ?problem ?author ?owner
                     :where
                     [?assignment :seon.message/assignment ?problem]
                     [?assignment :seon.message/from ?author]
                     [?assignment :seon.message/to ?owner]]
                   db (:seon.cluster.eval/id evaluation) author-eid owner-eid)))
        declination?
        (boolean
         (and assignment?
              (db/q '[:find ?declination .
                     :in $ ?problem ?author ?owner
                     :where
                     [?declination :seon.message/assignment ?problem]
                     [?declination :seon.message/from ?owner]
                     [?declination :seon.message/to ?author]
                     [?declination :my.message/reason _]]
                   db (:seon.cluster.eval/id evaluation) author-eid owner-eid)))]
    {:seon.turn.work/assignment? assignment?
     :seon.turn.work/declination? declination?}))

(defn form-settlement
  "One evaluation's exactly-one derived state at this database value.

  ONE ENTITY PER (run, ordinal): the frozen source and the terminal facts
  are the same entity, so `:unevaluated` is the absence of a start instant
  and `:running` is a started evaluation with no terminal fact. There is no
  twin to join and no pair that can disagree."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.eval/id]
                  :seon.turn.work/form-settlement]}
  [db form-id]
  (let [evaluation (db/pull db '[*] [:seon.cluster.eval/id form-id])
        owner-id (form-owner db evaluation)
        {:seon.turn.work/keys [assignment? declination?]}
        (assignment-facts db evaluation owner-id)
        started? (some? (:seon.cluster.eval/at evaluation))
        red? (and (terminal-receipt? evaluation) (red-receipt? evaluation))
        artifact? (and red?
                       (resume-artifact?
                        db
                        (form-run-id db evaluation)
                        (:seon.cluster.eval/ordinal evaluation)
                        (boolean (:seon.cluster.eval/interrupted-at
                                  evaluation))))
        [state settled?]
        (cond
          (not started?) [:unevaluated false]
          (not (terminal-receipt? evaluation)) [:running false]
          declination? [:owner-declared-cant true]
          artifact? [:unrouted-red false]
          (and red? assignment?) [:routed false]
          red? [:unrouted-red false]
          assignment? [:owner-fixed true]
          :else [:succeeded true])]
    (cond-> {:seon.cluster.eval/id (:seon.cluster.eval/id evaluation)
             :seon.cluster.eval/ordinal
             (:seon.cluster.eval/ordinal evaluation)
             :seon.agent/id owner-id
             :seon.turn.work/form-state state
             :seon.turn.work/settled? settled?}
      (:seon.problems/id evaluation)
      (assoc :seon.problems/id (:seon.problems/id evaluation)))))

(defn plan-settlement
  "Every form state and whether all forms of `run-id` are settled."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/id]
                  :seon.turn.work/plan-settlement]}
  [db run-id]
  (let [form-ids
        (db/q '[:find ?form-id ?ordinal
               :in $ ?run-id
               :where
               [?run :seon.turn/id ?run-id]
               [?form :seon.cluster.eval/run ?run]
               [?form :seon.cluster.eval/id ?form-id]
               [?form :seon.cluster.eval/ordinal ?ordinal]]
             db run-id)
        forms (mapv (fn [[form-id _]] (form-settlement db form-id))
                    (sort-by second form-ids))]
    {:seon.turn/id run-id
     :seon.turn.work/forms forms
     :seon.turn.work/settled?
     (every? :seon.turn.work/settled? forms)}))

;;; ---------------------------------------------------------------------------
;;; The derivations
;;; ---------------------------------------------------------------------------

(declare unanswered-wakes)

;;; ---------------------------------------------------------------------------
;;; The turn bound — derived from transaction :t, with zero stored counters
;;; ---------------------------------------------------------------------------

(defn- agent-eid
  "The entity id of `agent-id` on `db`, nil when no such agent, or THROWS
  the read's refusal whole.
  The one lookup the wake seeks need: every listened attribute is a ref
  whose value IS this entity, so the index seek is (attribute, value).
  A refused read is not an id and not \"no agent\": it escapes to the
  caller's fault route naming `seon.db/q`, never reaching a seek as a map."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :nil :int]]}
  [db agent-id]
  (let [eid (db/q '[:find ?agent .
                    :in $ ?agent-id
                    :where [?agent :seon.agent/id ?agent-id]]
                  db agent-id)]
    (if (:seon.db/invalid-read eid)
      (throw (ex-info (:seon.error/message eid) eid))
      eid)))

(defn- wake-attribute-set
  "The listened attributes one derivation binds.
  `:opening` — only those declared `:seon.wake/opens-turn? true`, which
  is what may cause a model call. `:listened` — every listened
  attribute, which is what a CONTEXT shows: a wake declared
  `opens-turn? false` (a schedule firing) must still reach the agent's
  next context and must still be able to anchor the turn bound. Binding
  the opening set in both modes made every firing invisible to every
  derivation — declared, routed, and consumed by nothing (verifier
  blocker 2)."
  [db attributes]
  (if (= :listened attributes)
    (wake/wake-attributes db)
    (wake/turn-opening-attributes db)))

(defn outside-wake-t
  "The transaction `:t` of the agent's latest wake from OUTSIDE it, or 0.

  The anchor of the turn bound. A human message and a schedule firing
  are outside; an agent-sent message, a fault routed to its steward, and
  an agent's own settled effect are inside. Zero when the agent has
  never been woken from outside, which counts EVERY turn: all of them
  are autonomous continuation, and a free pass here would void the bound
  for exactly the agent-spawned agents it most concerns.

  O(THE AGENT'S OWN WAKES), IN INDEX SEEKS. It walks
  `wake/agent-wake-datoms` — one `:avet` seek per listened attribute,
  newest first — and asks whether an entity is inside only for a
  candidate that would actually raise the answer, so the ordinary cost
  is one `:eavt` seek. The derivation this replaces asked
  `unanswered-wakes` for EVERY wake the agent had ever received and
  pulled each one: 47.5 ms per turn-proc pass at 2,008 lifetime wakes,
  against 0.163 ms for the trigger-anchored form before it, growing
  without bound in the agent's lifetime.

  THE WHOLE LISTENED SET, not the turn-opening one: a firing opens no
  turn and must still be able to refill the bound, which is exactly what
  `:seon.wake/opens-turn? false` means."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:int {:min 0}]]}
  [db agent-id]
  (if-let [eid (agent-eid db agent-id)]
    (let [inside (wake/inside-attributes db)]
      (reduce
       (fn [best [wake tx _attribute]]
         (if (and (> tx best) (not (wake/inside-wake? db wake inside)))
           tx
           best))
       0
       (wake/agent-wake-datoms (db/history db) eid (wake-attribute-set db :listened))))
    0))

(defn- spent-runs
  "`episode-runs` with the agent's outside-wake anchor when the caller already
  holds it; nil reads it here, and only when no issue assignment anchors the count."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:maybe [:int {:min 0}]]]
                  [:or :seon.turn.work/episode-runs :seon.error/value]]}
  [db agent-id outside-t]
  (let [issue-t (db/q '[:find ?t . :in $ ?agent-id :where
                         [?agent :seon.agent/id ?agent-id]
                         [?issue :seon.issue/agent ?agent ?t]] db agent-id)
        since (or issue-t outside-t (outside-wake-t db agent-id))
        replies (db/q '[:find [?run ...] :in $ ?agent-id ?since :where
                         [?agent :seon.agent/id ?agent-id]
                         [?run :seon.turn/agent ?agent]
                         [?run :seon.turn/id _ ?tx]
                         [(>= ?tx ?since)]
                         (or-join [?run ?tx]
                           [?run :seon.turn/attempts _]
                           (and [?run :seon.turn/reply-size _ ?reply-tx]
                                [(> ?reply-tx ?tx)]))] db agent-id since)
        closed (db/q '[:find [?run ...] :in $ ?agent-id ?since :where
                          [?agent :seon.agent/id ?agent-id]
                          [?run :seon.turn/agent ?agent]
                          [?run :seon.turn/id _ ?tx]
                          [(>= ?tx ?since)]
                          [?run :seon.turn.work/situation :call]
                          [?run :seon.turn/closed-tx ?closed]
                          [(> ?closed ?tx)]] db agent-id since)]
    (or (some #(when (db/read-refusal? %) %) [issue-t replies closed])
        (count (into (set replies) closed)))))

(defn episode-runs
  "Count ordinary turns since issue assignment, or the latest outside wake.
  Provider attempts and replies accepted after opening consume the bound.
  An agent also spends a turn when an ordinary call closes before a provider
  attempt. Generated openings and same-transaction system turns remain free.
  Issue budgets are total across resumes; messages do not refill them."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:or :seon.turn.work/episode-runs :seon.error/value]]}
  [db agent-id]
  (spent-runs db agent-id nil))

(defn- max-episode-runs
  "Read the issue budget, otherwise the agent override or cluster default."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]
                       :seon.agent/id]
                  [:or :nil :seon.config.run/max-episode-runs
                   :seon.error/value]]}
  [database agent-id]
  (let [issue-budget
        (db/q '[:find ?budget . :in $ ?id :where
                [?agent :seon.agent/id ?id] [?issue :seon.issue/agent ?agent]
                [?issue :seon.issue/budget ?budget]] database agent-id)]
    (if (db/read-refusal? issue-budget)
      issue-budget
      (or issue-budget
          (db/q '[:find ?value . :in $ ?id
                  :where [?agent :seon.agent/id ?id]
                         [?agent :seon.agent/settings ?settings]
                         [?settings :seon.config.run/max-episode-runs ?value]]
                database agent-id)
          (db/q '[:find ?value .
                  :where [?config :seon.config/cluster _]
                         [?config :seon.config.run/max-episode-runs ?value]]
                database)))))

(defn turns-left
  "The remaining turns under the same session bound that admits a turn."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :my.agent/turns-left :seon.error/value]]}
  [database agent-id]
  (let [limit (max-episode-runs database agent-id)
        spent (episode-runs database agent-id)]
    (or (some #(when (map? %) %) [limit spent])
        (long (max 0 (- (or limit 0) spent))))))

(defn- opening-deferred?
  "True when `agent-id` may open no turn at all: the turn count has
  reached the dial, the dial is absent, the wake declarations cannot
  carry a bound, or a provider refusal awaits a new outside wake.

  FAIL-CLOSED IN BOTH DIRECTIONS. The dial's absence was already
  fail-closed; the DECLARATIONS' absence was fail-open, and on the more
  expensive side — with no attribute declaring `:seon.wake/inside`,
  every wake reads as arriving from outside, the anchor jumps to the
  newest wake, and the bound REFILLS on a paid model loop (measured:
  verify-listened-attributes-2026-09-08 §10). One missing schema
  resource is not a licence to spend."
  {:malli/schema [:=> [:cat
                       [:or :seon.db/database-value :seon.error/value]
                       :seon.agent/id]
                  [:or :boolean :seon.error/value]]}
  [db agent-id]
  (let [limit (max-episode-runs db agent-id)]
    (cond
      (map? limit) limit
      (nil? limit) true
      :else
      (if-some [declarations-refusal (wake/declarations-refusal db)]
        declarations-refusal
        ;; ONE ANCHOR READ: the outside-wake anchor bounds the count (absent
        ;; an issue) and the failed-attempt window alike.
        (let [since (outside-wake-t db agent-id)
              spent (spent-runs db agent-id since)]
          (cond
            (map? spent) spent
            (>= spent limit) true
            :else
            ;; A terminal provider refusal leaves the wake unanswered, but
            ;; cannot itself authorize another attempt. A new outside wake
            ;; moves this basis and permits another turn.
            (let [failed-attempt
                  (db/q '[:find ?turn .
                          :in $ ?agent-id ?since
                          :where
                          [?agent :seon.agent/id ?agent-id]
                          [?turn :seon.turn/agent ?agent]
                          [?turn :seon.turn/id _ ?tx]
                          [(>= ?tx ?since)]
                          [?turn :seon.turn/closed-tx _]
                          (not [?turn :seon.turn/reply _])
                          [?turn :seon.turn/attempts ?attempt]
                          [?attempt :seon.ai.attempt/error _]]
                        db agent-id since)]
              (if (db/read-refusal? failed-attempt)
                failed-attempt
                (some? failed-attempt)))))))))

(defn deferred-triggers
  "The pending message wakes deferred by the turn bound or provider refusal.
  A closed turn with a failed attempt and no reply awaits a new outside
  wake. It does not answer the old wakes or authorize its own retry."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:vector [:map [:seon.message/id
                                  :seon.message/id]]]]}
  [db agent-id]
  (if (opening-deferred? db agent-id)
    (into []
          (comp (keep :seon.message/id)
                (map (fn [id] {:seon.message/id id})))
          (unanswered-wakes db agent-id {}))
    []))

(defn- fold-or-close
  "The instruction for a planned run: fold on, or close it.
  One place decides, so `:resume` always carries a real ordinal and
  `:close` never carries one."
  [db run agent-id]
  (let [run-id (:seon.turn/id run)]
    (if-let [ordinal (next-ordinal db run-id)]
      {:seon.turn.work/situation :resume
       :seon.turn/id run-id
       :seon.agent/id agent-id
       :seon.cluster.eval/ordinal ordinal}
      {:seon.turn.work/situation :close
       :seon.turn/id run-id
       :seon.agent/id agent-id})))

(defn- resume-or-generate
  [db run agent-id]
  (let [run-id (:seon.turn/id run)]
    (if-let [ordinal (next-ordinal db run-id)]
      {:seon.turn.work/situation :resume
       :seon.turn/id run-id
       :seon.agent/id agent-id
       :seon.cluster.eval/ordinal ordinal}
      {:seon.turn.work/situation :generate
       :seon.turn/id run-id
       :seon.agent/id agent-id})))

(defn- continuing-reply?
  "The latest closed turn accepted a provider reply and did not end the session."
  [database agent-id]
  (when-let [latest-t
             (db/q '[:find (max ?t) . :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id]
                     [?turn :seon.turn/agent ?agent]
                     [?turn :seon.turn/id _ ?t]
                     [?turn :seon.turn/closed-tx _]]
                   database agent-id)]
    (some?
     (db/q '[:find ?turn . :in $ ?agent-id ?latest-t
             :where [?agent :seon.agent/id ?agent-id]
             [?turn :seon.turn/agent ?agent]
             [?turn :seon.turn/id _ ?t]
             [(= ?t ?latest-t)]
             [?turn :seon.turn/closed-tx _]
             [?turn :seon.turn/reply-size _]
             [?turn :seon.turn/attempts ?attempt]
             (not [?attempt :seon.ai.attempt/error _])
             (not [?turn :seon.turn/disposition _])]
           database agent-id latest-t))))

(defn next-agent-work
  "The ONE thing to do next for `agent-id` on `db`, or nil when idle.
  Pure — the per-agent derivation every turn proc runs (F1 §5.2). The
  situations are ordered by what is already committed, not by
  preference: an open turn outranks a trigger, because finishing what is
  started is what makes the busy fence mean anything.
  `:resume` carries the ordinal the fold restarts at — the first form
  ordinal with no terminal receipt — so a turn never recomputes it;
  when no such ordinal remains the situation is `:close`. With no open
  turn, the `:open` arm admits unanswered wakes or continuation of the
  latest closed provider reply under the same turn bound. A reply continues
  unless its last evaluation returned a completed/wait disposition.
  Virtual and system replies never authorize continuation.
  A successful provider attempt answers its earlier wakes. A provider
  refusal leaves them unanswered and defers reopening until a new outside
  wake arrives. A deferred
  trigger simply derives no work — no consumer ever sees a decision to
  refuse."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn.work/agent-request]
                  [:or :nil :seon.turn.work/next :seon.error/value]]}
  [db {:keys [:seon.agent/id]}]
  (let [agent-id id
        run (agent-run db agent-id)
        issue (db/q '[:find (pull ?issue [:seon.issue/id :seon.issue/resolved-tx
                                         :seon.issue/budget-exhausted-tx]) .
                      :in $ ?agent-id :where [?agent :seon.agent/id ?agent-id]
                      [?issue :seon.issue/agent ?agent]] db agent-id)]
    (cond
      ;; an open turn outranks any trigger: finishing what
      ;; is started is what makes the busy fence mean anything
      (some? run)
      (cond
        (:seon.turn/reply-size run)
        (fold-or-close db run agent-id)

        (= :generate (:seon.turn.work/situation run))
        (resume-or-generate db run agent-id)

        (= :call (:seon.turn.work/situation run))
        {:seon.turn.work/situation :call
         :seon.turn/id (:seon.turn/id run)
         :seon.agent/id agent-id}

        :else nil)

      issue
      (let [remaining (turns-left db agent-id)]
        (if (and (map? remaining)
                 (contains? remaining :seon.error/at)
                 (contains? remaining :seon.error/layer)
                 (contains? remaining :seon.error/operation))
          remaining
          (when (and (not (:seon.issue/resolved-tx issue))
                     (not (:seon.issue/budget-exhausted-tx issue))
                     (pos? remaining))
            {:seon.turn.work/situation :open :seon.agent/id agent-id})))

      :else
      ;; ONE TURN FOR EVERY UNANSWERED WAKE. The turn's own transaction
      ;; answers all of them, so nothing is selected and nothing is
      ;; claimed; the wakes are named only so a consumer can say what it
      ;; is about to answer. Handling claims are recorded at settlement.
      (let [deferred (opening-deferred? db agent-id)]
        (if (and (map? deferred)
                 (contains? deferred :seon.error/at)
                 (contains? deferred :seon.error/layer)
                 (contains? deferred :seon.error/operation))
          deferred
          (when-not deferred
            (let [wakes (unanswered-wakes db agent-id {})]
              (when (or (seq wakes) (continuing-reply? db agent-id))
                (cond->
                 {:seon.turn.work/situation :open
                  :seon.agent/id agent-id}
                  (some :seon.message/id wakes)
                  (assoc :seon.message/id
                         (some :seon.message/id wakes)))))))))))

(defn more-agent-work?
  "True when another pass would find work for this agent.

  The turn proc's self-rewake predicate — exactly
  `(some? (next-agent-work db request))`, stated as its own contract
  because the rewake must never drift from the derivation it rewakes
  for."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn.work/agent-request]
                  :boolean]}
  [db request]
  (some? (next-agent-work db request)))

(defn latest-answering-turn-t
  "The opening transaction of the latest accepted ordinary reply, or zero.

  A successful model attempt qualifies directly. Virtual replies use the
  same writer protocol: the plan is frozen after opening, so its datom's
  transaction is later than the turn identity's transaction. Submitted system
  source freezes in the opening transaction and does not answer wakes.
  Merely opening, or failing before any reply, cannot qualify. No provider
  attempt is invented for the no-provider path."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:int {:min 0}]]}
  [database agent-id]
  (or (db/q '[:find (max ?tx) .
              :in $ ?agent-id
              :where
              [?agent :seon.agent/id ?agent-id]
              [?turn :seon.turn/agent ?agent]
              [?turn :seon.turn/id _ ?tx]
              (or-join [?turn ?tx]
                (and [?turn :seon.turn/attempts ?attempt]
                     (not [?attempt :seon.ai.attempt/error _]))
                (and [?turn :seon.turn/reply-size _ ?reply-t]
                     [(> ?reply-t ?tx)]))]
            database agent-id)
      0))

(defn unanswered-wakes
  "The agent's wakes no answering turn has covered, oldest first.

  ANSWERED IS DERIVED FROM `:t`, AND NOTHING IS STORED. A wake is a
  datom on an attribute declared `:seon.wake/listen true` whose value is
  this agent; every datom carries its transaction, and a turn's own
  transaction is the basis its context projected from. So a wake is
  answered exactly when an ANSWERING turn of that agent
  (`latest-answering-turn-t`) has `:t` at or after it — independent of handling claims, with no way for a turn
  to answer something its context never contained, or something no model
  ever saw.

  Two consequences the trigger reference could not express: two wakes in
  ONE transaction share a `:t` and are answered by ONE turn (they were
  paid for twice), and a wake asserted DURING a turn has `:t` greater
  than that turn's and opens the next one.

  TWO REQUEST KEYS, both declared. `:seon.turn.work/answered?` `:any`
  includes answered wakes; absent means unanswered only.
  `:seon.turn.work/attributes` `:listened` binds EVERY listened
  attribute — what a context shows — while the default `:opening` binds
  only those that may open a turn. Binding the opening set in both modes
  is what made a schedule firing invisible to every derivation.

  THE SEEK IS THE INDEX. One `:avet` seek per bound attribute through
  `wake/agent-wake-datoms`, filtered on the transaction each datom
  already carries; only the wakes that survive the filter are pulled, so
  the cost follows the agent's PENDING wakes rather than its lifetime
  ones.

  The rule the declarations state and every writer keeps: a wake datom
  is asserted ONCE and never retracted-and-reasserted, because a
  reassertion moves its `:t` forward and re-opens a paid turn."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id
                       :seon.turn.work/wake-request]
                  [:vector :seon.wake/unanswered]]}
  [db agent-id {answered? :seon.turn.work/answered?
                attributes :seon.turn.work/attributes}]
  (if-let [eid (agent-eid db agent-id)]
    (let [since (if (= :any answered?)
                  -1
                  (latest-answering-turn-t db agent-id))]
      (->> (wake/agent-wake-datoms db eid (wake-attribute-set db attributes))
           (filter (fn [[_wake tx _attribute]] (> tx since)))
           (sort-by (fn [[wake tx _attribute]] [tx wake]))
           (mapv (fn [[wake tx attribute]]
                   (let [pulled (db/pull db [:seon.message/id] wake)]
                     (cond-> {:db/id wake :seon.wake/attribute attribute :seon.wake/t tx}
                       (:seon.message/id pulled)
                       (assoc :seon.message/id (:seon.message/id pulled))))))))
    []))

(defn unanswered-triggers
  "The agent's unanswered MESSAGE wakes, oldest first.

  A projection of `unanswered-wakes` onto the message family, not a
  second derivation: answeredness is decided in exactly one place, by
  `:t`. It survives under this name for the callers that ask about
  messages specifically — the unread count, the concurrency proofs, the
  page."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:vector [:map [:seon.message/id
                                  :seon.message/id]]]]}
  [db agent-id]
  (->> (unanswered-wakes db agent-id {})
       (filter #(= :seon.message/to (:seon.wake/attribute %)))
       (sort-by (juxt :seon.wake/t :db/id))
       (mapv #(select-keys % [:seon.message/id]))))



(defn- segmented-sources
  "`text`'s forms as a run's ordered sources, found structurally by the
  forgiving stage in `mode` (`seon.sci.forgive/segments`): each form's one
  semantic read happens when it is evaluated, after the forms before it ran.
  An unreadable rest is one more source, whose read answers its error once;
  text with no form is the no-forms refusal."
  {:malli/schema [:=> [:cat :seon.cluster.reply/text :seon.ns/name :seon.batch/mode :string]
                  [:or :seon.cluster.reply/sources :seon.cluster.reply/no-forms-error]]}
  [text namespace-name mode no-form-message]
  (let [{segments :seon.batch/segments unread :seon.batch/unread} (forgive/segments text mode)
        sources (cond-> (mapv (fn [{source :seon.batch/source c :seon.batch/comment}]
                                (cond-> {:seon.cluster.eval/source source :seon.ns/name namespace-name}
                                  c (assoc :seon.cluster.eval/comment c)))
                              segments)
                  unread (conj {:seon.cluster.eval/source unread :seon.ns/name namespace-name}))]
    (if (seq sources)
      sources
      (vary-meta {:seon.cluster.reply/no-forms true :seon.cluster.reply/text text
                  :seon.error/at (Date.) :seon.error/layer :seon.cluster.reply/source
                  :seon.error/operation `segmented-sources :seon.error/message no-form-message
                  :seon.error/data {:seon.cluster.reply/text text} :seon.error/expected :seon.cluster.reply/sources}
                 assoc :seon.error/declared-schema :seon.cluster.reply/no-forms-error))))

(defn planned-sources
  "The ordered forms one ordinary source run executes from a model reply:
  prose becomes each form's comment, prompt markers go, delimiters are
  repaired where unambiguous (`segmented-sources` in `:reply` mode)."
  {:malli/schema
   [:=> [:catn
         [:text :seon.cluster.reply/text]
         [:namespace-name :seon.ns/name]
         [:max-source :seon.config.eval.result/max-source]]
    [:or :seon.cluster.reply/sources :seon.cluster.reply/no-forms-error]]}
  [text namespace-name _max-source]
  (segmented-sources text namespace-name :reply
                     (if (str/blank? text)
                       "Your reply began with a response; send a form."
                       "Your reply had no form; only comments/prose. Send a form.")))

(defn code-sources
  "The ordered forms of code (an outside agent's evaluation, a file, a
  generated form): every form is a form, nothing is prose (`segmented-sources`
  in `:code` mode). The MCP bridge's segmenter."
  {:malli/schema
   [:=> [:catn
         [:text :seon.cluster.reply/text]
         [:namespace-name :seon.ns/name]
         [:max-source :seon.config.eval.result/max-source]]
    [:or :seon.cluster.reply/sources :seon.cluster.reply/no-forms-error]]}
  [text namespace-name _max-source]
  (segmented-sources text namespace-name :code "Your evaluation had no form. Send a form."))

;;; ---------------------------------------------------------------------------
;;; The pure turn
;;; ---------------------------------------------------------------------------

(defn committed-attributes
  "Every attribute the loop's own transactions assert.
  Computed from the transitions this namespace commits, never a
  reviewed list — it exists so the wake/commit disjointness property
  (C2) has two computed sets to compare rather than one list to
  believe."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :keyword]]}
  [projection]
  ;; WHAT THIS SET IS NOT, since the messaging rung: it is the loop's
  ;; ROUTINE bookkeeping, not everything the loop can ever commit. A
  ;; turn that delivers an agent's message commits
  ;; `:seon.message/to` DELIBERATELY, and that commit wakes the
  ;; recipient — which is the whole transport, not a leak. The
  ;; invariant C2 states is the one that matters and is unchanged: no
  ;; ordinary turn wakes the loop as a side effect of recording itself,
  ;; so an idle cluster stays idle. A deliberate delivery is caused by
  ;; an agent, is bounded by `:seon.config.message/max-chain`, and is
  ;; asserted from the other direction in the messaging suite —
  ;; delivery MUST intersect the wake set or nothing would be woken.
  ;;
  ;; COMPUTED from the DECLARED ENTITIES this loop writes — the run,
  ;; its forms, and its receipts — plus the agent pointer a close
  ;; retracts. Reading the entity maps rather than filtering the
  ;; registry by namespace keeps out the things that live in those
  ;; namespaces without being attributes: the entity maps themselves,
  ;; and derived values like `:seon.turn/missing-results`.
  ;;
  ;; Note what this set can and cannot prove. It is the right input for
  ;; the wake/commit disjointness property, but it CANNOT by itself
  ;; catch an attribute the boot path fails to install — a missing
  ;; entity map removes the attribute from this set and from the
  ;; installable set at once. The test that catches that class is the
  ;; one that transacts these rows into a database built the way boot
  ;; builds it.
  (into #{}
        (comp (mapcat (fn [entity]
                        (internal/entity-entries
                         (mr/schema (:seon.schema.projection/registry projection) entity))))
              (filter vector?)
              (map first))
        [:seon.turn/turn
         :seon.cluster.eval/receipt
         ;; every model attempt is a durable row this loop writes, so it
         ;; belongs in the declared write set — and the class-killer
         ;; that asserts this set is installable is exactly what catches
         ;; a new entity family the boot path never learned about
         :seon.ai/attempt
         ;; the pre-provider context capture and its contribution rows
         ;; are turn-owned commits too (ruling 4, 2026-07-28)
         :seon.context.capture/capture
         :seon.context.contribution/contribution]))

(defn disposition
  "The disposition an admitted eval value carries, or nil.
  The loop reads `my.turn`'s two values out of the LAST form's admitted
  result. Anything else — a number, a map that merely looks similar, an
  error value — is not a disposition. An accepted provider reply ending
  without one permits another turn under the session bound."
  {:malli/schema [:=> [:cat :seon.schema/projection [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "The final SCI evaluation may return any value; this classifier returns a disposition only when the candidate satisfies my.turn/value."
                         :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe :my.turn/value]]}
  [projection value]
  ;; TOTAL OVER VALID CLOJURE VALUES. Malli's map validator looks each key
  ;; up (malli core.cljc:1276), and a sorted map whose comparator cannot
  ;; compare a keyword with its keys throws on that lookup: a symbol-keyed
  ;; `(sorted-map 'a 1)` wedged its run. Walking a map's entries never calls
  ;; its comparator, so only a map that names the discriminator is validated.
  ;; A disposition is a plain map: a record is never one, and a record's seq
  ;; need not be its entries (a Datahike DB seqs its datoms), so it is not
  ;; walked; any other map's seq is checked entry by entry.
  (when (and (map? value)
             (not (record? value))
             (some #(and (instance? java.util.Map$Entry %) (= :my.turn/disposition (key %))) value)
             (schema/valid-candidate-value? projection :my.turn/value value))
    value))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(declare turn resume-turn)

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
  [cluster db declared-schema failure now attribution]
  (error/recording
   db
   (merge {:seon.db/connection (:seon.db/connection cluster)
           :seon.error/declared-schema declared-schema
           :seon.error/source failure
           :seon.error/id (str (random-uuid))
           :seon.error/at now
           :seon.error/process (:seon.db.process/id cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.error/basis-t (db/basis-t db)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)
           :seon.config.error/max-evidence-bytes
           (:seon.config.error/max-evidence-bytes cluster)}
          ;; an agent's own delivery mistake names no escalation owner:
          ;; it is returned to that agent, never escalated (seon.error/commit-call)
          attribution)))

(defn- asked-value
  "The message-family value one completed evaluation asks to deliver."
  [{db :seon.db/db
    settled :seon.turn.loop/settled
    problem :seon.problems/form-problem
    agent-id :seon.agent/id
    trigger :seon.message/trigger}]
  (or (when (= :completed (:my.turn/disposition settled))
        (message/reply
         db
         (cond-> {:my.turn/result (:my.turn/result settled)
                  :seon.agent/id agent-id}
           trigger (assoc :seon.message/trigger trigger))))
      (when problem (@problems-assignment-value problem))))

(defn- delivery-rows
  "Delivery rows and refusal transaction data for one asked value."
  [{db :seon.db/db
    cluster :seon.turn.loop/cluster
    asked :seon.turn.loop/asked
    agent-id :seon.agent/id
    run-id :seon.turn/id
    ordinal :seon.cluster.eval/ordinal
    now :seon.turn.loop/now
    trigger :seon.message/trigger}]
  (let [delivery
        (when asked
          (message/delivery
           db
           (cond-> {:my.message/value asked :seon.agent/id agent-id :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain (:seon.config.message/max-chain cluster)}
             trigger (assoc :seon.message/trigger trigger))))
        recordings
        (mapv
            (fn [failure]
              (error-tx cluster db
                        (cond (:seon.message/no-limit failure) :seon.message/no-limit-error
                              (:seon.message/chain-limit failure) :seon.message/chain-limit-error
                              (:seon.message/unknown-recipient failure) :seon.message/unknown-recipient-error)
                        failure now
                        {:seon.agent/id agent-id
                         :seon.turn/id run-id}))
           (:seon.error/values delivery))]
    {:seon.message/rows
     (:seon.message/rows delivery)
     ;; a refused recording rides the transaction as before, so it refuses loudly
     :seon.error/values-tx (into [] (mapcat #(or (:seon.db/tx-data %) %)) recordings)
     :seon.blob/staged-writes (into [] (mapcat :seon.blob/staged-writes) recordings)}))

(defn- phase
  "Return one phase's value, translating its declared refusal carrier, an
  ExceptionInfo, to flat data. Every other throwable, the stop's interrupt
  included, propagates unchanged to `turn`, which closes the run before it
  reaches the step that owns it.

  Callback results are polymorphic and unchecked here; the callback's own
  contract owns them. This boundary does not promise a phase-failed member
  on callback results. Only a caught host failure gains that evidence."
  {:malli/schema
   [:=> [:cat [:=> [:cat] :seon.schema/value]]
    [:schema {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
          :seon.schema.admission/reason "The supplied zero-argument operation may return any value, including its own flat error; phase preserves that value and only translates thrown failures."}
     :seon.schema/value]]}
  [operation]
  (try
    (operation)
    (catch clojure.lang.ExceptionInfo failure
      (with-meta
       (merge {:seon.error/at (java.util.Date.)
              :seon.error/layer :seon.turn/phase
              :seon.error/operation 'seon.turn/phase
              :seon.error/message
              (or (ex-message failure) (.getName (class failure))) :seon.turn.loop/phase-failed true}
             (error/refusal failure)
             {:seon.turn.loop/failed-step
              (or (:seon.error/operation (error/refusal failure)) 'seon.turn/phase)})
       {:seon.error/declared-schema :seon.turn.loop/phase-failed-error}))))

(defn- closing-settlement?
  "True when this settlement closes the ordinary turn.
  Derived from the settled disposition and the undisposed agent form, the
  same two facts that emit `close-tx`, so the close and everything that
  belongs to it cannot disagree. A generated opening form settles without
  closing, which is why it costs no issue test run."
  [{undisposed? :seon.turn.loop/undisposed? settled :seon.turn.loop/settled}]
  (boolean (or undisposed?
               (contains? #{:completed :wait} (:my.turn/disposition settled)))))

(defn- final-shown
  "The one print owner's rendering of `value`, a value that replaced a printed
  result at settlement, in `cluster`'s world for `agent-id`."
  {:malli/schema [:function
                  [:=> [:cat :seon.turn.loop/cluster :seon.db/database-value :seon.agent/id
                        [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                               :seon.schema.admission/reason "Settlement prints any replacing value: a completed disposition or a refusal."
                               :gen/elements [nil false 0 "" :k [] {}]}]]
                   :seon.sci.eval/shown]
                  [:=> [:cat :seon.turn.loop/cluster :seon.db/database-value :seon.agent/id
                        [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                               :seon.schema.admission/reason "Settlement prints any replacing value: a completed disposition or a refusal."
                               :gen/elements [nil false 0 "" :k [] {}]}]
                        [:map [:seon.render.value/options {:optional true} :map]]]
                   :seon.sci.eval/shown]]}
  ([cluster database agent-id value]
   (final-shown cluster database agent-id value {}))
  ([cluster database agent-id value options]
  ;; The ctx, time limit and error dial are the evaluation path's render world:
  ;; without the ctx the value renderer selects no declared pair.
  (sci.eval/shown-result value (merge options {:seon.db/db database :seon.db/connection (:seon.db/connection cluster)
                                :seon.agent/id agent-id :seon.boot/cluster-name (:seon.cluster/name cluster)
                                :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                                :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)})
                         nil)))

(defn- with-diagnostic
  "`receipt` with `error` delivered beside its value: appended to its
  :seon.cluster.eval/diagnostics as bounded data (a later stage's failure
  never replaces the value)."
  {:malli/schema [:=> [:cat :map [:maybe [:or :seon.plan/refusal :seon.error/base]]] :map]}
  [receipt error]
  (cond-> receipt
    error (update :seon.cluster.eval/diagnostics
                  #(str/join "\n" (remove nil? [% (print.value/bounded-text [error])])))))

(defn- evaluation-terminal-data
  [{cluster :seon.turn.loop/cluster
    now :seon.turn.loop/now
    agent-id :seon.agent/id
    run-id :seon.turn/id
    process :seon.db.process/id
    ordinal :seon.cluster.eval/ordinal
    evaluation :seon.sci.eval/evaluation
    problem :seon.problems/form-problem
    trigger :seon.message/trigger
    batch? :seon.turn.loop/batch?}]
  (let [database (db/db (get cluster :seon.db/connection))
        ;; the disposition the fold's publish stage read, as data
        raw-settled (:my.turn/value evaluation)
        settled
        (cond-> raw-settled
          (= :completed (:my.turn/disposition raw-settled))
          (assoc :my.turn/delivered-to
                 (or (some->> trigger (message/sender database))
                     :outside)))
        evaluation
        (if (and settled (not= settled raw-settled))
          ;; The completed disposition replaces the printed value: its text is
          ;; rendered again, by the one owner, for the value that settles.
          (let [admitted (admit/admit
                          {:seon.sci.admit/value settled
                           :seon.sci.admit/interrupt-fn (constantly nil)
                           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                           :seon.schema/projection
                           (db/carried-projection database)})]
            (merge (dissoc evaluation :seon.eval/renderer) admitted
                   (select-keys (final-shown cluster database agent-id (:seon.sci.admit/value admitted))
                                [:seon.eval/shown :seon.eval/renderer])))
          evaluation)
        last-ordinal
        (db/q '[:find (max ?ordinal) .
                :in $ ?run-id
                :where
                [?run :seon.turn/id ?run-id]
                [?form :seon.cluster.eval/run ?run]
                [?form :seon.cluster.eval/ordinal ?ordinal]]
              database run-id)
        triggered-agent-form?
        (boolean
         (db/q '[:find ?form .
                 :in $ ?run-id ?ordinal
                 :where
                 [?run :seon.turn/id ?run-id]
                 [?run :seon.turn/trigger _]
                 [?form :seon.cluster.eval/run ?run]
                 [?form :seon.cluster.eval/ordinal ?ordinal]
                 [?form :seon.cluster.eval/author :agent]]
               database run-id ordinal))
        undisposed?
        (and (nil? settled)
             triggered-agent-form?
             (= ordinal last-ordinal)
             (nil? (:seon.cluster.eval/error evaluation))
             (nil? (:seon.cluster.eval/interrupted-at evaluation)))
        asked (asked-value
               (cond-> {:seon.db/db database
                        :seon.sci.eval/evaluation evaluation
                        :seon.turn.loop/settled settled
                        :seon.agent/id agent-id}
                 problem (assoc :seon.problems/form-problem problem)
                 trigger (assoc :seon.message/trigger trigger)))
        delivery
        (delivery-rows
         (cond-> {:seon.db/db database
                  :seon.turn.loop/cluster cluster
                  :seon.turn.loop/asked asked
                  :seon.agent/id agent-id
                  :seon.turn/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  :seon.turn.loop/now now}
           problem (assoc :seon.problems/form-problem problem)
           trigger (assoc :seon.message/trigger trigger)))
        [settlement-evaluation _ settlement-stages]
        (settlement-projection cluster evaluation)
        receipt
        (evaluation-facts
         (cond-> {:seon.turn/id run-id
                  :seon.db.process/id process
                  :seon.cluster.eval/ordinal ordinal
                  :seon.sci.eval/evaluation evaluation
                  :seon.turn.loop/settlement-evaluation settlement-evaluation}
           problem (assoc :seon.problems/form-problem problem)
           settled (assoc :my.turn/value settled)))
        closing? (closing-settlement? {:seon.turn.loop/undisposed? undisposed?
                                       :seon.turn.loop/settled settled})
        ;; ONLY THE CLOSE ASKS THE QUESTION. A mid-turn form settles without
        ;; the agent having replied, so rerunning its issue's tests there
        ;; cannot change the answer. A batch asks once for all its closes
        ;; (`settle-batch!`). A refusal is delivered beside the value.
        receipt (with-diagnostic receipt (when (and closing? (not batch?))
                                           (plan/run-issue-tests! cluster agent-id)))
        side-tx
        (concat
         (when settled
           [[:db/add [:seon.turn/id run-id]
             :seon.turn/disposition (:my.turn/disposition settled)]])
         ;; The completion's reply text is a fact beside its disposition, so a
         ;; reader never recovers it from the shown text.
         (when (= :completed (:my.turn/disposition settled))
           [[:db/add [:seon.turn/id run-id]
             :seon.turn/result (:my.turn/result settled)]])
         (when closing?
           (close-tx
            {:seon.turn/id run-id :seon.db.process/id process}))
         (:seon.message/rows delivery)
         (:seon.error/values-tx delivery))
        tx-data (if batch?
                  (vec side-tx)
                  (conj (into (receipt-settle-tx receipt) side-tx)
                        [:db.fn/call #'plan/settle-call agent-id]))]
    {:seon.turn.loop/settled settled
     :seon.turn.loop/undisposed? undisposed?
     :seon.turn.loop/evaluation evaluation
     :seon.turn.loop/receipt receipt
     :seon.blob/staged-writes (into (vec settlement-stages)
                                    (:seon.blob/staged-writes delivery))
     :seon.db/tx-data tx-data}))

(declare settle-batch-refusal! close-escaped-run!)

(defn- fault-window
  "The references of the errors written on `agent-id`'s branch in this
  settlement's window, (the agent's previous settlement, `database`], read
  once at the writer's basis (`seon.error/occurred-since`) and saved on the
  batch's last evaluation. The window opens at run `run-id`'s own opening
  when the agent has settled nothing before."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.turn/id]
                  [:vector [:tuple [:= :seon.error/signature] :seon.error/signature]]]}
  [database agent-id run-id]
  (let [since (or (db/q '[:find (max ?tx) . :in $ ?agent-id ?run-id
                          :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                          [?run :seon.turn/closed-tx ?tx] (not [?run :seon.turn/id ?run-id])]
                        database agent-id run-id)
                  (db/q '[:find ?tx . :in $ ?run-id :where [?run :seon.turn/id ?run-id] [?run :seon.turn/opened-tx ?tx]]
                        database run-id))
        found (when (int? since) (error/occurred-since database since))]
    (when (map? found)
      (throw (ex-info (str (:seon.error/message found "The settlement's fault window read refused.")) found)))
    (mapv #(vector :seon.error/signature (:seon.error/signature %)) found)))

(defn- with-fault-windows
  "`receipts` with each run's settlement window saved on its last receipt."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.turn.loop/settle-evaluation-request]
                       [:vector :seon.cluster.eval/settle-request]]
                  [:vector :seon.cluster.eval/settle-request]]}
  [database requests receipts]
  (let [last-index (into {} (map-indexed (fn [index request] [(:seon.turn/id request) index])) requests)]
    (vec (map-indexed
          (fn [index [request receipt]]
            (let [faults (when (= index (last-index (:seon.turn/id request)))
                           (fault-window database (:seon.agent/id request) (:seon.turn/id request)))]
              (cond-> receipt (seq faults) (assoc :seon.cluster.eval/faults faults))))
          (map vector requests receipts)))))

(defn- with-submission
  "`transaction` preceded by the pass's submitted run, when it carries one: the
  run's open and frozen sources (`system-run-tx`) and its staged reply, so a
  source run is written in the ONE transaction that settles it. The writer
  applies the open first, so every run-dependent call after it sees the run."
  {:malli/schema [:function
                  [:=> [:cat :seon.turn.loop/cluster
                        [:map [:seon.db/tx-data :seon.store/transaction-data]
                         [:seon.blob/staged-writes {:optional true} [:vector :seon.blob/staged-write]]]]
                   [:map [:seon.db/tx-data :seon.store/transaction-data]
                    [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]
                  [:=> [:cat :seon.turn.loop/cluster
                        [:map [:seon.db/tx-data :seon.store/transaction-data]
                         [:seon.blob/staged-writes {:optional true} [:vector :seon.blob/staged-write]]]
                        :boolean]
                   [:map [:seon.db/tx-data :seon.store/transaction-data]
                    [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]]}
  ([cluster transaction] (with-submission cluster transaction false))
  ([cluster transaction refusal?]
  (let [submission (:seon.turn.loop/submission cluster)
        run-request (:seon.agent/run-request submission)
        open (if (and refusal? run-request)
               [[:db.fn/call #'db/absent-call [::id (::id run-request)]
                 [[:db.fn/call #'system-run-if-admitted-call run-request]]]]
               (:seon.db/tx-data submission))]
    (assoc transaction
           :seon.db/tx-data (into (vec open) (:seon.db/tx-data transaction))
           :seon.blob/staged-writes (into (vec (:seon.blob/staged-writes submission))
                                          (:seon.blob/staged-writes transaction))))))

(defn- settlement-lost!
  "The durable commit of an applied settlement failed: the writer is stopped
  (Datahike's terminal commit failure), so the fault names the runs whose
  shown results were lost and goes to `seon.fault/fault!`, which cannot store
  it and so runs this agent's stop and throws."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster [:vector :seon.turn.loop/settle-evaluation-request]
                       :seon.error/throwable]
                  :nil]}
  [cluster requests failure]
  (let [runs (into [] (comp (map :seon.turn/id) (distinct)) requests)
        agent-id (:seon.agent/id (first requests))]
    (fault/fault! (env/of cluster)
                  (ex-info (str "The durable commit of applied settlement(s) for run(s) " (pr-str runs)
                                " failed; their shown results are lost and the writer has stopped.")
                           {:seon.turn/id (first runs) :seon.agent/id agent-id} failure)
                  (cond-> {:seon.error/layer :seon.turn/settlement
                           :seon.error/operation `settle-batch!
                           :seon.db.process/id (:seon.db.process/id cluster)
                           :seon.agent/id agent-id}
                    (:seon.fault/stop-request! cluster)
                    (assoc :seon.fault/stop-request! (:seon.fault/stop-request! cluster))))
    nil))

(defn- settle-batch!
  "Settle every evaluated form, the batch's folded program and all turn side
  effects in one transaction. The program's facts are derived at the writer on
  its head (`seon.turn.batch/program-facts-call`)."
  {:malli/schema
   [:=> [:cat :seon.turn.loop/cluster [:vector :seon.turn.loop/settle-evaluation-request]
         [:map [:seon.batch/request :seon.batch/request] [:seon.batch/program :map]
          [:seon.batch/changes [:vector [:map [:seon.batch/ordinal :seon.cluster.eval/ordinal] [:seon.batch/change :seon.batch/change]]]]]]
    [:map
     [:prepared [:vector [:map
                          [:seon.turn.loop/receipt :seon.turn.loop/terminal-request]
                          [:seon.db/tx-data :seon.store/transaction-data]
                          [:seon.blob/staged-writes [:vector :seon.blob/staged-write]]]]]
     ;; a refused refusal settlement answers its own refusal here
     [:outcome [:or :seon.db/transaction-report :seon.error/value]]
     [:refused-outcome {:optional true} :seon.error/value]]]}
  [cluster requests {program-request :seon.batch/request program :seon.batch/program changes :seon.batch/changes}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        prepared (mapv #(evaluation-terminal-data
                         (assoc % :seon.turn.loop/batch? true
                                  :seon.db.process/id process))
                       requests)
        ;; The batch settles every generated opening form as well as the
        ;; agent's own. Only the settlement that CLOSES the turn asks whether
        ;; the issue is done, so an opening pass runs no issue tests at all.
        issue-refusals (into {}
                             (keep (fn [agent-id]
                                     (when-let [refusal (plan/run-issue-tests! cluster agent-id)]
                                       [agent-id refusal])))
                             (into #{}
                                   (keep identity)
                                   (map (fn [request entry]
                                          (when (closing-settlement? entry)
                                            (:seon.agent/id request)))
                                        requests prepared)))
        ;; each refusal is delivered beside the value of its agent's closing form
        prepared (mapv (fn [request entry]
                         (if-let [refusal (and (closing-settlement? entry)
                                               (get issue-refusals (:seon.agent/id request)))]
                           (update entry :seon.turn.loop/receipt with-diagnostic refusal)
                           entry))
                       requests prepared)
        ;; The definitions these forms executed whose measurement moved,
        ;; once for every attempt: measuring advances their cells' marks.
        measured (measurement/significant-tx
                  (db/db connection)
                  (into #{} (mapcat #(get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/executed])) requests))
        ;; a measuring failure is delivered beside the values, never fails the settlement
        prepared (if (:seon.error/at measured)
                   (mapv #(update % :seon.turn.loop/receipt with-diagnostic measured) prepared)
                   prepared)
        measured (if (:seon.error/at measured) [] measured)
        ;; Built inside the committed phase: a refused read of the fault
        ;; window is a refused settlement the arm below closes, never an escape.
        transaction
        (fn [program prepared]
         (hash-map
          :seon.blob/staged-writes
         (into [] (mapcat :seon.blob/staged-writes) prepared)
         :seon.db/tx-data
         (-> (cond-> [] (seq program) (conj [:db.fn/call #'turn.batch/program-facts-call program-request program]))
             (into (receipt-settle-batch-tx
                    (with-fault-windows (db/db connection) requests (mapv :seon.turn.loop/receipt prepared))))
             (into (mapcat :seon.db/tx-data) prepared)
             (into measured)
             ;; The close precedes plan/settle-call, as in close-turn; a
             ;; settlement that already closes the turn needs no fold.
             (cond-> (not (some closing-settlement? prepared))
               (into (map (fn [[agent-id run-id]]
                            [:db.fn/call #'close-folded-call {::id run-id :seon.agent/id agent-id}]))
                     (distinct (map (juxt :seon.agent/id :seon.turn/id) requests))))
             (into (map (fn [agent-id] [:db.fn/call #'plan/settle-call agent-id]))
                   (distinct (map :seon.agent/id requests))))))
        ;; `with-publication!` IS TOTAL OVER AN EMPTY VECTOR — it calls the
        ;; commit directly — so the caller has no branch to get wrong. The
        ;; branch this replaced handed `(seq …)`, a `ChunkedSeq`, where the
        ;; declared input is `[:vector :seon.blob/staged-write]`: every turn
        ;; that staged a blob (an agent `def` over the blob threshold)
        ;; violated the contract, and nothing closed the run.
        ;;
        ;; `phase` is what makes that class survivable rather than terminal.
        ;; A HOST FAILURE IN THE COMMIT IS A REFUSED PHASE, NOT AN ESCAPE:
        ;; it becomes a flat value the refusal arm settles, so the run
        ;; closes and the agent takes its next turn. A failure to record a
        ;; fault may never leave a run open.
        commit
        (fn [program prepared]
        (phase
         #(let [transaction (with-submission cluster (transaction program prepared))]
            (blob/with-publication!
             connection (:seon.blob/staged-writes transaction)
             ;; THE AGENT'S FORM IS ANSWERED ONCE ITS SETTLEMENT IS APPLIED;
             ;; durability follows (AGENTS "Recover facts, not execution").
             ;; Every reader of the run uses the answer's own value
             ;; (`:seon.turn.loop/basis`), never a later connection read. A
             ;; failed commit stops the writer; `settlement-lost!` names the
             ;; runs it lost and stops this agent loudly.
             ;; the repeats the run's faults folded ride this commit
             ;; (`seon.fault/folded-tx`, task e33c4c026538); a refused
             ;; settlement hands them back for the next commit
             (fn [] (let [folded (seon.fault/folded-tx connection)
                          outcome (db/transact-applied!
                                   connection {:tx-data (into (vec (:seon.db/tx-data transaction)) folded)}
                                   (partial settlement-lost! cluster requests))]
                      (when-not (:db-after outcome) (seon.fault/unfold! connection folded))
                      outcome))))))
        first-outcome (commit program prepared)
        ;; A REFUSED SETTLEMENT IS ANSWERED FORM BY FORM (OQ1): the last form's
        ;; facts were never validated before the writer (a form's facts are
        ;; validated when a later form reads them), so a refusal revalidates
        ;; each form's change on the head; the forms it now refuses lose their
        ;; facts, logged as their errors, and the rest settle once more.
        [prepared outcome]
        (if (or (:db-after first-outcome) (empty? program))
          [prepared first-outcome]
          (let [[_ program' entries] (turn.batch/revalidate program-request (db/db connection) (vec changes))
                refused (into {} (keep (fn [e] (when-let [fe (:seon.batch/facts-error e)]
                                                 [(:seon.batch/ordinal e) (:seon.error/message fe)])))
                              entries)]
            (if (empty? refused)
              [prepared first-outcome]
              (let [prepared' (mapv (fn [request p]
                                      (if-let [message (refused (:seon.cluster.eval/ordinal request))]
                                        (update-in p [:seon.turn.loop/receipt :seon.cluster.eval/error]
                                                   #(or % (str "Its program facts were not proposed: " message)))
                                        p))
                                    requests prepared)]
                [prepared' (commit program' prepared')]))))]
    ;; SUCCESS IS THE WRITER'S TRANSACTION REPORT (`db/transact!` answers
    ;; `:seon.db/transaction-result` or its refused error); any other value,
    ;; a refused phase included, is the refusal the arm below settles.
    (if (:db-after outcome)
      {:prepared prepared :outcome outcome}
      (settle-batch-refusal! cluster requests prepared outcome changes))))

(defn- settle-evaluated!
  "`settle-batch!`, total (AGENTS \"The path is total\"): a throw anywhere in
  the settlement, its own input refusal included, is refused like a refused
  commit (`settle-batch-refusal!`), so every form still shows the value it
  evaluated with the failure beside it; it never replaces the values."
  {:malli/schema
   [:=> [:cat :seon.turn.loop/cluster [:vector :seon.turn.loop/settle-evaluation-request] :map]
    [:map [:prepared [:vector :map]] [:outcome [:or :seon.db/transaction-report :seon.error/value]]
     [:refused-outcome {:optional true} :seon.error/value]]]}
  [cluster requests program]
  (try
    (settle-batch! cluster requests program)
    (catch Throwable failure
      (settle-batch-refusal!
       cluster requests
       (mapv (fn [{run-id :seon.turn/id ordinal :seon.cluster.eval/ordinal evaluation :seon.sci.eval/evaluation}]
               {:seon.turn.loop/receipt
                (cond-> {:seon.turn/id run-id :seon.cluster.eval/ordinal ordinal
                         :seon.eval/shown (:seon.eval/shown evaluation "")}
                  (:seon.cluster.eval/error evaluation)
                  (assoc :seon.cluster.eval/error (:seon.cluster.eval/error evaluation)))})
             requests)
       ;; a refusal carrier is the refused phase its commit would have been
       (if (instance? clojure.lang.ExceptionInfo failure)
         (phase #(throw failure))
         (seon.error.refusal/diagnostic (Date.) :seon.turn/settlement 'seon.turn/settle-batch!
                                        {:seon.error/throwable failure}))
       (:seon.batch/changes program [])))))

;;; A REFUSED PHASE ESCALATES THROUGH `seon.error/commit-tx`, LIKE EVERY OTHER
;;; FAILURE. This site used to `dissoc` the escalation dial — silencing the one
;;; designed owner — and then hand-roll its own `"A run phase failed: …"`
;;; message: unbounded, and addressed without ever asking who had failed.
;;; `error-tx`'s own docstring names that hazard: "a second copy of it is how
;;; one of them quietly stops escalating."
;;;
;;; What the second copy cost, measured on cluster `default`, 2026-08-08:
;;; delivery is the wake attribute (`wake-attributes` is
;;; `#{:seon.message/to}`), `:seon.config.error/escalate-to` named root,
;;; and root was the only agent — so every refused phase of root's mailed root
;;; about root, woke root, met the same unfixed cause, and mailed root again.
;;; Nine paid provider calls in twenty minutes with no external stimulus.
;;;
;;; The surviving owner cannot write that cycle. A phase failure is a VALUE, not
;;; a Throwable, so no `:your-run` message is sent at all; the escalation owner
;;; hears once per signature per process, at the recurrence limit and never
;;; after it; and a recurrence escalation to the attributed agent is skipped, so
;;; the failing agent is structurally unmailable about its own refusal. The
;;; interim `(not= escalate-to agent-id)` guard this function grew on 2026-08-08
;;; went with the copy it was guarding.
(defn- refusal-terminal-data
  {:malli/schema
   [:=> [:cat :seon.turn.loop/cluster :seon.db/database-value :inst
         :seon.agent/id [:or :nil :seon.turn/id] :seon.db.process/id
         [:or :nil :seon.cluster.eval/ordinal]
         [:or :nil :seon.turn.loop/terminal-request] :seon.error/source]
    [:map [:seon.error/value :seon.error/value]
     [:seon.db/tx-data :seon.store/transaction-data]]]}
  [cluster database now agent-id run-id process ordinal receipt source]
  (let [recording
        (error/recording cluster database
                  (or (:seon.error/declared-schema source)
                      (:seon.error/declared-schema (meta source)))
                  source now
                  (cond-> {:seon.agent/id agent-id}
                    run-id (assoc :seon.turn/id run-id)))
        value (:seon.error/value recording)
        structural {:seon.render.value/options {:seon.render.value/structural? true}}
        ;; A form that returned a value keeps it: the refusal of its
        ;; settlement is a diagnostic beside it, never its replacement.
        evaluated? (and (:seon.eval/shown receipt) (not (:seon.cluster.eval/error receipt)))
        receipt-tx
        (when ordinal
          (receipt-settle-tx
           (cond-> {:seon.turn/id run-id
                    :seon.cluster.eval/ordinal ordinal
                    :seon.cluster.eval/error (:seon.error/message value)}
             evaluated?
             (assoc :seon.eval/shown (:seon.eval/shown receipt)
                    :seon.cluster.eval/diagnostics
                    (:seon.eval/shown (final-shown cluster database agent-id [value] structural)))
             (not evaluated?)
             (assoc :seon.eval/shown
                    (:seon.eval/shown (final-shown cluster database agent-id
                                                   (error/reply-value value) structural))))))]
    {:seon.error/value value
     :seon.blob/staged-writes (:seon.blob/staged-writes recording)
     :seon.db/tx-data
     (into [] cat
           [;; The run-dependent half is DECIDED AT THE WRITER
            ;; (`open-run-tx-call`), not pre-read here: when the first
            ;; terminal commit refused because the run is gone or already
            ;; closed, re-issuing its settlement and close guarantees an
            ;; identical second refusal. The error recording below depends on
            ;; no run and always commits, so the fault says what actually
            ;; happened instead of that the recording failed.
            (when run-id
              [[:db.fn/call #'open-run-tx-call run-id
                (into (vec receipt-tx)
                      (close-tx
                       {:seon.turn/id run-id :seon.db.process/id process
                        :seon.turn/closed-tx "datomic.tx"}))]])
            (:seon.db/tx-data recording)])}))

(defn- deletion-proposals
  "The deletions a refused run settlement's writer named, each associated with
  the one form whose fold change proposed it, else with why none is.

  AN ASSOCIATION, NEVER A CAUSE. The writer reports broken final relations
  (removed identity, surviving referrer), not which operation removed what,
  so an identity is associated only when exactly one settled row deletes it
  and no settled row declares it; otherwise its reason is named. Only the
  producer's declared deletion refusal (`:seon.db.write/validator-schema`) is
  read, after the raw refusal validates against that named schema; another
  refusal family associates nothing (nil). Pure over values in hand: no
  replay, no writer trial, O(named referrers + row identities)."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.error/value
                       [:vector [:map [:seon.batch/ordinal :seon.cluster.eval/ordinal]
                                 [:seon.batch/change :seon.batch/change]]]]
                  [:maybe :seon.turn/deletion-proposals]]}
  [projection refusal changes]
  (let [named (:seon.db.write/validator-schema refusal)]
    (cond
      (nil? named) nil
      (nil? (find (:seon.schema.projection/forms projection) named))
      [{:seon.turn/unattributed :unknown-validator-schema}]
      (not= :seon.program/deletion-refused-error named) nil
      (not (schema/valid-candidate-value? projection named refusal))
      [{:seon.turn/unattributed :invalid-deletion-evidence}]
      :else
      (let [;; a removed symbol names its declaration's identity: a qualified
            ;; one a function or test, an unqualified one a namespace
            identities (fn [removed]
                         (if (qualified-symbol? removed)
                           [[:seon.fn/sym removed] [:seon.test/sym removed]]
                           [[:seon.ns/name removed]]))
            deleting (reduce (fn [deleting {ordinal :seon.batch/ordinal change :seon.batch/change}]
                               (reduce #(update %1 %2 (fnil conj (sorted-set)) ordinal)
                                       deleting (mapcat identities (:seon.batch/removals change))))
                             {} changes)
            declared (into #{} (comp (mapcat (comp :seon.batch/entities :seon.batch/change))
                                     (keep program/row-identity))
                           changes)]
        (->> (group-by :seon.program/identity (:seon.program/referrers refusal))
             (sort-by (comp pr-str key))
             (mapv (fn [[identity referrers]]
                     (let [ordinals (get deleting identity)]
                       (cond
                         (< 1 (count ordinals)) {:seon.program/identity identity :seon.turn/unattributed :several-deletions}
                         (contains? declared identity) {:seon.program/identity identity :seon.turn/unattributed :declared-in-run}
                         (seq ordinals) {:seon.program/identity identity :seon.cluster.eval/ordinal (first ordinals)
                                         :seon.program/referrers (vec referrers)}
                         :else {:seon.program/identity identity :seon.turn/unattributed :no-deleting-form})))))))))

(defn- proposal-text
  "One sentence per proposal, rendered from the association value."
  {:malli/schema [:=> [:cat :seon.turn/deletion-proposal] :string]}
  [{identity :seon.program/identity ordinal :seon.cluster.eval/ordinal
    referrers :seon.program/referrers reason :seon.turn/unattributed}]
  (if ordinal
    (str "Evaluation " ordinal " proposed deleting " (pr-str (second identity))
         ", which the final program still references from "
         (str/join ", " (map #(pr-str (:seon.program/referrer %)) referrers)) ".")
    (str "No single evaluation is associated"
         (when identity (str " with deleting " (pr-str (second identity))))
         ": " (name reason) ".")))

(defn- settle-batch-refusal!
  "Settle every begun ordinal after the atomic turn settlement is refused.
  When this settlement is refused too, the run is closed, one core fault is
  recorded, and the answer is the original refusal carrying that fault's
  `:seon.fault/recorded` receipt, with the second refusal as `:outcome`."
  {:malli/schema
   [:=> [:cat :seon.turn.loop/cluster [:vector :seon.turn.loop/settle-evaluation-request]
         [:vector [:map [:seon.turn.loop/receipt :seon.turn.loop/terminal-request]]]
         :seon.error/value
         [:vector [:map [:seon.batch/ordinal :seon.cluster.eval/ordinal] [:seon.batch/change :seon.batch/change]]]]
    [:map
     [:prepared [:vector [:map [:seon.turn.loop/receipt :seon.turn.loop/terminal-request]]]]
     [:outcome [:or :seon.db/transaction-report :seon.error/value]]
     [:refused-outcome :seon.error/value]]]}
  [cluster requests prepared refusal changes]
  (let [connection (:seon.db/connection cluster)
        {now :seon.turn.loop/now
         agent-id :seon.agent/id
         run-id :seon.turn/id}
        (first requests)
        process (:seon.db.process/id cluster)
        proposals (deletion-proposals (db/carried-projection (db/db connection)) refusal changes)
        ;; the association is recorded on the refused write's error occurrence
        ;; (printed whole, as its declared -edn member), and the receipts'
        ;; text is rendered from that same value
        refusal (cond-> refusal
                  proposals (assoc :seon.turn/deletion-proposals-edn
                                   (binding [*print-length* nil *print-level* nil] (pr-str proposals))))
        associated (group-by :seon.cluster.eval/ordinal (filter :seon.cluster.eval/ordinal proposals))
        unassociated (str/join " " (map proposal-text (remove :seon.cluster.eval/ordinal proposals)))
        recording (error/recording cluster (db/db connection)
                            (:seon.error/declared-schema (meta refusal))
                            refusal now
                            {:seon.agent/id agent-id
                             :seon.turn/id run-id})
        value (:seon.error/value recording)
        ;; EVALUATED VALUES ARE NOT PUBLICATION STATUS. Every form keeps the
        ;; shown text of the value it evaluated; the refused commit is the
        ;; batch's one publication outcome, so each receipt names it as its
        ;; error (a form that failed keeps its own). No form is claimed to
        ;; have returned the refusal, and no failing form is guessed by
        ;; re-applying rows: the recorded refusal names what it refused.
        ;; Only identity and text settle: the refused batch may have been
        ;; refused for any other value its receipts carried (dogfood #4).
        unpublished (str "Not settled: this run's settlement was refused: "
                         (:seon.error/message value))
        ;; The refusal as data, printed once by the floor, beside every value
        ;; the batch evaluated (a form that failed keeps its own error).
        diagnostics (:seon.eval/shown
                     (final-shown cluster (db/db connection) agent-id [value]
                                  {:seon.render.value/options {:seon.render.value/structural? true}}))
        receipts
        (mapv
         (fn [{receipt :seon.turn.loop/receipt}]
           (let [settled (select-keys receipt [:seon.turn/id :seon.db.process/id :seon.cluster.eval/ordinal
                                               :seon.cluster.eval/ns :seon.sci.eval/ending-ns
                                               :seon.eval/shown :seon.cluster.eval/error])]
             ;; A form that failed keeps its own error, and the refused
             ;; publication is still delivered beside it: nothing of this
             ;; run settled, whatever each form did.
             (if (:seon.cluster.eval/error settled)
               (assoc settled :seon.cluster.eval/diagnostics diagnostics)
               (assoc settled
                      :seon.cluster.eval/error
                      (str/join " " (remove str/blank?
                                            (concat (map proposal-text (get associated (:seon.cluster.eval/ordinal settled)))
                                                    [unpublished unassociated])))
                      :seon.cluster.eval/diagnostics diagnostics))))
         prepared)
        transaction
        ;; Same writer decision as `refusal-terminal-data`: the batch's
        ;; settlements and close each require the run to exist and be open,
        ;; which is what the refused batch commit may itself have denied.
        (with-submission
         cluster
         {:seon.db/tx-data
          (into (if run-id
                  [[:db.fn/call #'open-run-tx-call run-id
                    (into (vec (receipt-settle-batch-tx receipts))
                          (close-tx {:seon.turn/id run-id
                                     :seon.db.process/id process
                                     :seon.turn/closed-tx "datomic.tx"}))]]
                  (vec (receipt-settle-batch-tx receipts)))
                (:seon.db/tx-data recording))
          :seon.blob/staged-writes (vec (:seon.blob/staged-writes recording))}
         true)
        outcome (blob/with-publication!
                 connection (:seon.blob/staged-writes transaction)
                 #(db/transact! connection {:tx-data (:seon.db/tx-data transaction)}))]
    (if (:db-after outcome)
      {:prepared prepared
       :outcome outcome
       :refused-outcome refusal}
      ;; A REFUSED REFUSAL SETTLEMENT IS A VALUE ON THE REPLY, never a throw
      ;; past it (sweep 2026-09-27 item 1: the caller saw "awaited channel
      ;; closed"). The run closes as for any escaped terminal
      ;; (`close-escaped-run!`), the core fault is recorded here, and the
      ;; refusal the agent caused returns with that fault's receipt.
      (let [failure (close-escaped-run!
                     cluster agent-id
                     (ex-info "Batch refusal settlement was refused."
                              {:seon.turn.loop/settlement outcome
                               :seon.turn.loop/refused-outcome refusal}))
            receipt (fault/fault! (env/of cluster) failure
                                  {:seon.error/layer :seon.turn/settlement
                                   :seon.error/operation `settle-batch-refusal!
                                   :seon.db.process/id process
                                   :seon.agent/id agent-id})]
        {:prepared prepared
         :outcome outcome
         :refused-outcome (assoc refusal :seon.fault/recorded receipt)}))))

(defn settle!
  "The sole terminal writer for one run.

  Evaluation settlement commits its receipt, disposition, deliveries, the
  agent's defs, and close together. An agent evaluation error stays in that receipt;
  it never enters the durable core-fault family. A phase failure before
  evaluation has no ordinal and therefore commits zero receipts. A gate failure
  after evaluation carries the started receipt's ordinal and settles that
  receipt through the failure arm; it is never presented as an evaluation. A
  refused terminal transaction takes one bounded refusal branch; success is the
  returned transaction report, never a value constructed before commit."
  {:malli/schema
   [:=>
    [:cat :seon.turn.loop/settle-request]
    :seon.turn.loop/settlement]}
  [{cluster :seon.turn.loop/cluster
    now :seon.turn.loop/now
    agent-id :seon.agent/id
    run-id :seon.turn/id
    ordinal :seon.cluster.eval/ordinal
    evaluation :seon.sci.eval/evaluation
    failure :seon.error/value
    :as request}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        prepared
        (if evaluation
          (phase #(evaluation-terminal-data
                   (assoc request :seon.db.process/id process)))
          failure)
        prepared
        (if (or failure (:seon.turn.loop/phase-failed prepared))
          (refusal-terminal-data cluster (db/db connection) now agent-id run-id
                                 process ordinal nil prepared)
          prepared)
        ;; The same total commit as `settle-batch!`: one vector of staged
        ;; writes (absent means none, never nil into the contract) and one
        ;; `phase`, so a host failure lands in the refusal arm below rather
        ;; than escaping with the run still open.
        commit
        (fn [transaction refusal?]
          (let [transaction (with-submission cluster transaction refusal?)]
            (phase
             #(blob/with-publication!
               connection (:seon.blob/staged-writes transaction)
               (fn [] (db/transact!
                       connection
                       {:tx-data (:seon.db/tx-data transaction)}))))))
        outcome (commit prepared false)]
    (if (:db-after outcome)
      (assoc prepared :seon.turn.loop/outcome outcome)
      (let [refusal
            (refusal-terminal-data
             cluster (db/db connection) now agent-id run-id process ordinal
             (:seon.turn.loop/receipt prepared) outcome)
            refused (commit refusal true)]
        (when-not (:db-after refused)
          (throw
           (ex-info "Terminal refusal settlement was refused."
                    {:seon.turn.loop/terminal-refusal-settlement-refused
                     (:seon.turn/rule outcome)
                     :seon.turn.loop/settlement refused
                     :seon.turn.loop/refused-outcome outcome})))
        (assoc refusal
               :seon.turn.loop/outcome refused
               :seon.turn.loop/refused-outcome outcome)))))

(defn- attempt-id
  "One model attempt's identity, derived from its turn and ordinal."
  [run-id ordinal]
  (id/digest 12 [:seon.ai.attempt/id run-id ordinal]))

(defn- attempts
  "How many model attempts this run has already recorded.
  DERIVED at the start of a `:call` pass so the next ordinal continues
  the chain. A run whose plan transaction refused stays open and
  reaches `:call` again; without this its second call would reuse
  ordinal 0 and upsert away the first attempt's evidence.

  LONG, not `count`'s Integer. Datahike's `:db.type/long` validator is
  `(= (class %) java.lang.Long)` exactly, so an Integer ordinal refuses
  the WHOLE transaction — taking the error fact down with the attempt
  row. Coerced here, where the number is born, rather than at the call
  sites that would each have to remember."
  [db run-id]
  (long
   (count (db/q '[:find ?attempt
                 :in $ ?run-id
                 :where
                 [?run :seon.turn/id ?run-id]
                 [?run :seon.turn/attempts ?attempt]]
               db run-id))))

;;; The transport-phase evidence the leaf recorded, carried onto the
;;; attempt row under THE PRODUCER'S OWN KEYS. Selected rather than
;;; re-keyed one by one: a `cond->` per field is four chances to drop
;;; one silently, and `false` is a meaningful value here that a
;;; truthiness test would eat. OBSERVATIONS ONLY (owner ruling
;;; 2026-07-28): the error class and the disposition are pure functions
;;; of this evidence (`seon.ai/status-class`, `seon.ai/disposition`
;;; over the error fact's data-edn), derived at read, never stored
;;; beside the facts they restate.
(def ^:private evidence-attributes
  [:seon.ai/http-status :seon.ai/request-transmitted?
   :seon.ai/response-started? :seon.ai/output-observed?])

(defn- attempt-evidence
  "Provider evidence projected from one completion or failure value."
  {:malli/schema
   [:=> [:cat [:map [:seon.ai/completion :seon.ai/completion]]]
    [:map
     [:seon.ai.model/last-latency-ms {:optional true} :seon.ai.model/last-latency-ms]
     [:seon.ai/usage {:optional true} :seon.ai/usage]
     [:seon.ai/reasoning-content {:optional true} :seon.ai/reasoning-content]
     [:seon.ai/finish-reason {:optional true} :seon.ai/finish-reason]
     [:seon.ai/truncation {:optional true} :seon.ai/stream-truncated-error]]]}
  [{completion :seon.ai/completion}]
  (let [truncation (or (:seon.ai/truncation completion)
                       (when (:seon.ai/interrupted-text-count completion)
                         completion))]
    (cond-> {}
      (:seon.ai.model/last-latency-ms completion)
      (assoc :seon.ai.model/last-latency-ms
             (:seon.ai.model/last-latency-ms completion))
      (or (:seon.ai/usage completion)
          (get-in completion [:seon.error/data :seon.ai/usage]))
      (assoc :seon.ai/usage
             (or (:seon.ai/usage completion)
                 (get-in completion [:seon.error/data :seon.ai/usage])))
      (or (:seon.ai/reasoning-content completion)
          (get-in completion [:seon.error/data :seon.ai/reasoning-content]))
      (assoc :seon.ai/reasoning-content
             (or (:seon.ai/reasoning-content completion)
                 (get-in completion
                         [:seon.error/data :seon.ai/reasoning-content])))
      (or (:seon.ai/finish-reason completion)
          (get-in completion [:seon.error/data :seon.ai/finish-reason]))
      (assoc :seon.ai/finish-reason
             (or (:seon.ai/finish-reason completion)
                 (get-in completion
                         [:seon.error/data :seon.ai/finish-reason])))
      truncation (assoc :seon.ai/truncation truncation))))

(defn- attempt-request
  "One record-attempt request assembled from target, evidence, and provenance."
  [{:keys [:seon.ai/target :seon.ai/settings
           :seon.ai.attempt/ordinal :seon.error/value
           :seon.ai.attempt/failover-from :seon.ai.attempt/delay-ms]
    run-id :seon.turn/id
    agent-id :seon.agent/id
    evidence :seon.turn.loop/attempt-evidence}]
  (cond-> (merge {:seon.ai/target target
                  :seon.ai/settings settings
                  :seon.turn/id run-id
                  :seon.agent/id agent-id
                  :seon.ai.attempt/ordinal ordinal}
                 evidence)
    value (assoc :seon.error/value value)
    failover-from (assoc :seon.ai.attempt/failover-from failover-from)
    delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms)))

(defn- provider-targets
  "Resolved provider targets, settings, and finite schedule for one turn."
  [{db :seon.db/db
    cluster-name :seon.cluster/name
    agent-id :seon.agent/id}]
  (let [settings (ai/settings (config/effective db cluster-name)
                              (ai/agent-overlay db agent-id))
        targets (ai/targets (db/carried-projection db) db settings)
        primary (:seon.ai/primary targets)
        backup (:seon.ai/backup targets)
        strategy (ai/retry-strategy settings)]
    {:seon.ai/primary primary
     :seon.ai/backup backup
     :seon.ai/settings settings
     :seon.turn.loop/schedule (if backup [] (ai/delays strategy rand))}))

(defn- record-attempt!
  "Commit ONE model attempt and its error or truncation facts.
  Returns the error owner's prepared diagnostic after commit, nil on success,
  or a typed transaction refusal.

  The error fact and the attempt row ride ONE transaction, with the
  attempt's `:seon.ai.attempt/error` pointing at the fact through the
  shared tempid. That is not tidiness: the caller may only build the
  backup's context from a fact that is already durable, and one
  transaction is what makes \"already durable\" true with no window.

  A refused transaction returns its diagnostic so the caller cannot freeze
  a successful reply or make another paid call without durable evidence."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.db/connection :seon.db/connection]
                [:seon.db.process/id {:optional true} :seon.db.process/id]
                [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps]
                [:seon.config.error/recurrence-limit {:optional true}
                 :seon.config.error/recurrence-limit]
                [:seon.config.error/max-evidence-bytes {:optional true}
                 :seon.config.error/max-evidence-bytes]
                [:seon.config.error/escalate-to {:optional true}
                 :seon.config.error/escalate-to]]
         [:map
          [:seon.ai/target :seon.ai/target]
          [:seon.ai/settings :seon.ai/settings]
          [:seon.turn/id :seon.turn/id]
          [:seon.agent/id :seon.agent/id]
          [:seon.ai.attempt/ordinal :seon.ai.attempt/ordinal]
          [:seon.error/value {:optional true} :seon.error/value]
          [:seon.ai/usage {:optional true} :seon.ai/usage]
          [:seon.ai.model/last-latency-ms {:optional true} :seon.ai.model/last-latency-ms]
          [:seon.ai/reasoning-content {:optional true} :seon.ai/reasoning-content]
          [:seon.ai/finish-reason {:optional true} :seon.ai/finish-reason]
          [:seon.ai/truncation {:optional true} :seon.ai/stream-truncated-error]
          [:seon.ai.attempt/delay-ms {:optional true} :seon.ai.attempt/delay-ms]
          [:seon.ai.attempt/failover-from {:optional true} :seon.ai.attempt/id]]
         :inst]
    [:or :nil :seon.error/fact :seon.db/error-result]]}
  [cluster request now]
  (let [{target :seon.ai/target
         failure :seon.error/value
         run-id :seon.turn/id
         agent-id :seon.agent/id
         ordinal :seon.ai.attempt/ordinal
         usage :seon.ai/usage
         latency-ms :seon.ai.model/last-latency-ms
         settings :seon.ai/settings
         reasoning-content :seon.ai/reasoning-content
         finish-reason :seon.ai/finish-reason
         truncation :seon.ai/truncation
         delay-ms :seon.ai.attempt/delay-ms
         failover-from :seon.ai.attempt/failover-from} request
        retain-reasoning? (true? (:seon.config.ai/retain-reasoning settings))
        reasoning-content (when retain-reasoning? reasoning-content)
        failure (if retain-reasoning? failure
                    (cond-> (dissoc failure :seon.ai/reasoning-content)
                      (:seon.error/data failure)
                      (update :seon.error/data dissoc :seon.ai/reasoning-content)))
        truncation (if retain-reasoning? truncation
                       (cond-> (dissoc truncation :seon.ai/reasoning-content)
                         (:seon.error/data truncation)
                         (update :seon.error/data dissoc :seon.ai/reasoning-content)))
        connection (:seon.db/connection cluster)
        db (db/db connection)
        settings-ref (or (db/q '[:find ?settings . :in $ ?agent-id
                                 :where [?agent :seon.agent/id ?agent-id]
                                        [?agent :seon.agent/settings ?settings]]
                               db agent-id)
                         (db/q '[:find ?config .
                                 :where [?config :seon.config/cluster]] db))
        reasoning-size (when (seq reasoning-content)
                         (long (count reasoning-content)))
        threshold (db/q '[:find ?threshold .
                          :where
                          [_ :seon.config.eval.result/blob-threshold ?threshold]]
                        db)
        reasoning-stage (when (and reasoning-size threshold
                                   (> reasoning-size threshold))
                          (blob/stage! connection reasoning-content))
        reasoning-blob (:seon.blob/digest reasoning-stage)
        attribution {:seon.agent/id agent-id
                     :seon.turn/id run-id}
        failure-recording (when failure
                            (error/recording cluster db
                              (:seon.error/declared-schema (meta failure))
                              failure now attribution))
        truncation-recording
        (cond
          (nil? truncation) nil
          (= truncation failure) failure-recording
          :else (error/recording cluster db :seon.ai/stream-truncated-error truncation now attribution))
        recording (into (vec (:seon.db/tx-data failure-recording))
                        (when (not= truncation failure)
                          (:seon.db/tx-data truncation-recording)))
        row (cond-> (merge
                     {:db/id "attempt"
                      :seon.ai.attempt/id (attempt-id run-id ordinal)
                      :seon.ai.attempt/ordinal ordinal
                      :seon.ai.attempt/at now
                      :seon.ai/endpoint (:seon.ai/endpoint target)
                      :seon.ai/model (:seon.ai/model target)}
                     (select-keys (:seon.error/data failure)
                                  evidence-attributes))
              ;; the fact is created by THIS transaction, so the ref is
              ;; its tempid — a lookup ref to something the same
              ;; transaction is still creating is not a bet to take.
              ;; THE REF'S PRESENCE IS THE OUTCOME: an attempt failed
              ;; exactly when it points at an error fact, and there is
              ;; no stored :success/:error label restating that.
              failure-recording
              (assoc :seon.ai.attempt/error
                     (:db/id (first (:seon.db/tx-data failure-recording))))
              truncation-recording
              (assoc :seon.ai.attempt/truncation
                     (:db/id (first (:seon.db/tx-data truncation-recording))))
              settings-ref (assoc :seon.ai.attempt/settings settings-ref)
              settings
              (assoc :seon.ai.attempt/settings-edn (pr-str settings))
              usage (merge (ai/normalize-usage usage)
                           {:seon.ai.attempt/usage-edn (pr-str usage)})
              (and reasoning-size (nil? reasoning-blob))
              (assoc :seon.ai.attempt/reasoning reasoning-content)
              reasoning-blob
              (assoc :seon.ai.attempt/reasoning-blob reasoning-blob
                     :seon.ai.attempt/reasoning-size reasoning-size)
              finish-reason
              (assoc :seon.ai.attempt/finish-reason finish-reason)
              ;; ROLE BY CONNECTION: only the backup points back, so a
              ;; reader can tell a failover from a retry without a stamp
              failover-from (assoc :seon.ai.attempt/failover-from
                                   [:seon.ai.attempt/id failover-from])
              delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms))
        observation-tx
        (ai/model-observation-tx
         db
         (cond-> {:seon.ai.model/id (:seon.ai/model target)
                  :seon.ai.model/last-used-at now}
           (some? latency-ms)
           (assoc :seon.ai.model/last-latency-ms latency-ms)
           usage (assoc :seon.ai/usage usage)))
        outcome
        (blob/with-publication!
         connection (cond-> (into [] (mapcat :seon.blob/staged-writes)
                                  (distinct (keep identity [failure-recording truncation-recording])))
                      reasoning-stage (conj reasoning-stage))
         (fn []
           (db/transact! connection
                         (into (conj recording row
                                     [:db/add [:seon.turn/id run-id] :seon.turn/attempts "attempt"]) observation-tx))))]
    (if (:db-after outcome)
      (:seon.error/fact failure-recording)
      outcome)))

(defn- fold-evaluations
  "One run's evaluation entities, ordinal order, in ONE query.

  ONE ENTITY PER (run, ordinal) carries the frozen source, its parse-time
  namespace, and — once settled — the namespace its evaluation ended in.
  The resumed fold therefore reads the entities it is about to settle
  instead of joining a twin family per ordinal."
  [db run-id]
  (->> (db/q '[:find [(pull ?evaluation
                            [:seon.cluster.eval/ordinal
                             :seon.cluster.eval/source
                             :seon.eval/origin
                             :seon.sci.eval/ending-ns
                             {:seon.cluster.eval/ns [:seon.ns/name]}]) ...]
               :in $ ?run-id
               :where
               [?run :seon.turn/id ?run-id]
               [?evaluation :seon.cluster.eval/run ?run]]
             db run-id)
       (sort-by :seon.cluster.eval/ordinal)
       vec))

(defn- fold-source
  "One evaluation row projected back into the source the evaluator takes."
  [evaluation]
  (cond-> {:seon.cluster.eval/source (:seon.cluster.eval/source evaluation)}
    (:seon.eval/origin evaluation)
    (assoc :seon.eval/origin (:seon.eval/origin evaluation))
    (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])
    (assoc :seon.cluster.eval/ns
           [:seon.ns/name
            (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])])))

(defn- fold-namespace
  "The committed namespace in effect immediately before `ordinal`."
  [db run-id evaluations ordinal]
  (or (->> evaluations
           (filter #(< (:seon.cluster.eval/ordinal %) ordinal))
           (keep :seon.sci.eval/ending-ns)
           last)
      (db/q '[:find ?starting-ns .
              :in $ ?run-id
              :where
              [?run :seon.turn/id ?run-id]
              [?run :seon.turn/starting-ns ?namespace]
              [?namespace :seon.ns/name ?starting-ns]]
            db run-id)))

(defn- evaluation-request
  "One admitted form projected into the guarded evaluation request."
  [{form :seon.turn.loop/admitted-form
    evaluation-namespace :seon.turn.loop/evaluation-namespace
    cluster :seon.turn.loop/cluster
    ctx :seon.sci.eval/ctx
    agent-id :seon.agent/id
    run-id :seon.turn/id
    form-ordinal :seon.cluster.eval/ordinal}]
  (merge form
         (cond->
          {:seon.cluster.eval/ns [:seon.ns/name evaluation-namespace]
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.sci.eval/ctx ctx
           :seon.agent/id agent-id
           :seon.cluster.eval/ordinal form-ordinal
           :seon.boot/cluster-name (:seon.cluster/name cluster)
           :seon.sci.eval/time-limit-ms
           (:seon.config.eval/time-limit-ms cluster)}
           run-id (assoc :seon.turn/id run-id)
           (:seon.flow/work-launcher cluster)
           (assoc :seon.flow/work-launcher
                  (:seon.flow/work-launcher cluster)))))

(defn- open-turn
  "Open one turn before any paid provider call."
  {:malli/schema [:=> [:cat [:map
                            [:seon.turn.loop/cluster :seon.turn.loop/cluster]
                            [:seon.turn.loop/work :seon.turn.work/next]
                            [:seon.turn.loop/now :inst]
                            [:seon.turn.loop/report
                             [:function
                              [:=> [:cat :seon.turn.loop/outcome :int] :seon.turn.loop/turn-report]
                              [:=> [:cat :seon.turn.loop/outcome :int [:or :nil :seon.error/value]]
                               :seon.turn.loop/turn-report]]]]]
                  :seon.turn.loop/turn-report]}
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now report :seon.turn.loop/report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        agent-id (:seon.agent/id work)]
    ;; Open first, model second. The busy fence has to exist
    ;; before the expensive part.
    ;;
    ;; ANSWEREDNESS IS THIS TRANSACTION'S OWN `:t`. Nothing claims a
    ;; wake: every wake with `:t` at or before the opening transaction
    ;; is answered by the turn whose context contained it, so two wakes
    ;; in one commit are one paid call and a wake arriving mid-turn
    ;; opens the next one. The `:seon.turn.loop/trigger-already-answered` fence is
    ;; gone with the reference it guarded — `open-call`'s writer-side no-op stops two openers, and the
    ;; derivation is what stops a second turn for an answered wake.
    ;;
    ;; `:seon.turn/trigger` is retained as PROVENANCE ONLY — the
    ;; oldest message wake this turn opened for, which the page and the
    ;; context still name. It decides nothing.
    (let [refreshed (system-turn {:seon.turn.loop/cluster cluster
                                 :seon.agent/id agent-id
                                 :seon.turn/write? true})
          id (next-id (db/db connection) (:seon.cluster/name cluster) agent-id)
          open-request
          (cond->
           {:seon.turn/id id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
            (:seon.message/id work)
            (assoc
             :seon.turn/trigger
             [:seon.message/id
              (:seon.message/id work)]))
          outcome (if (:seon.turn/refused-system-agent refreshed)
                    refreshed
                    (db/transact!
                   connection
                   {:tx-data
                    [[:db.fn/call #'open-call open-request]]}))]
      (cond
        (or (:seon.db/transaction-refused outcome)
            (:seon.turn/refused-system-agent outcome))
        (do
          ;; The open transaction formed no run, so settlement records the
          ;; error and escalation with no run attribution or close.
          (settle! {:seon.turn.loop/cluster cluster
                    :seon.turn.loop/now now
                    :seon.agent/id agent-id
                    :seon.error/value outcome})
          (report :error 0 outcome))

        :else
        (report :released 0)))))

(defn- provider-wait-ms
  "The admitted attempt timeouts plus the already chosen finite retry delays."
  [{:seon.ai/keys [primary backup] schedule :seon.turn.loop/schedule}]
  (+ (* (inc (count schedule)) (:seon.ai/timeout-ms primary))
     (reduce + 0 schedule)
     (if backup (:seon.ai/timeout-ms backup) 0)))

(defn- await-turn-part!
  "Hand the existing completion observer the work admitted by this owner."
  [cluster expected work-ms]
  (when-let [await-part (:seon.turn.loop/await-part cluster)]
    (await-part expected work-ms)))

(defn- call-turn
  "Call the provider and freeze the returned plan."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.turn.loop/cluster :seon.turn.loop/cluster]
               [:seon.turn.loop/work :seon.turn.work/next]
               [:seon.turn.loop/now :inst]
               [:seon.turn.loop/report
                [:function
                 [:=> [:cat :seon.turn.loop/outcome :int] :seon.turn.loop/turn-report]
                 [:=> [:cat :seon.turn.loop/outcome :int [:or :nil :seon.error/value]]
                  :seon.turn.loop/turn-report]]]]]
    :seon.turn.loop/turn-report]}
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now report :seon.turn.loop/report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        agent-id (:seon.agent/id work)
        run-id (:seon.turn/id work)]
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
    ;;   backup's system segment is the notice's `:seon.render/ai`
    ;;   projection through the one router over that committed fact;
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
    (let [;; ONE TURN, ONE RESOLUTION. Both reads use this immutable
          ;; database value, and resolution stays outside the attempt
          ;; reduce so failover/backoff cannot change settings halfway
          ;; through a turn. Applying config or retracting/asserting an
          ;; agent override therefore changes the NEXT turn, without a
          ;; graph rebuild or a cached derived projection.
          db (db/db connection)
          providers (provider-targets
                     {:seon.db/db db
                      :seon.cluster/name (:seon.cluster/name cluster)
                      :seon.agent/id agent-id})
          settings (:seon.ai/settings providers)
          primary (:seon.ai/primary providers)
          backup (:seon.ai/backup providers)
          schedule (:seon.turn.loop/schedule providers)
          _ (when-not (:seon.config.ai/no-provider settings)
              (await-turn-part!
               cluster :seon.ai/completion
               (provider-wait-ms providers)))
          ;; STREAMING IS ON BY CONSTRUCTION (F2 §2.1): the sink is
          ;; one `offer!` of the run id plus the complete
          ;; `:seon.ai/partial` snapshot
          ;; onto the cluster's ONE sliding-1 stream conn — newest
          ;; wins, a slow render pass can never backpressure the
          ;; provider fold, and a streamed call and a one-shot call
          ;; return the same completion value. There is no dial; a
          ;; handle with no stream channel simply calls one-shot.
          stream-channel (:seon.turn.loop/stream-channel cluster)
          sink (when stream-channel
                 (fn [snapshot]
                   (async/offer! stream-channel
                                 {:seon.agent/id agent-id
                                  :seon.turn/id run-id
                                  :seon.ai/partial snapshot})))
          fail!
          ;; `refusal?` distinguishes a REFUSED DURABLE WRITE from a provider
          ;; or reader failure: only the former feeds the proc's consecutive
          ;; write-refusal bound, because only the former re-fires against a
          ;; writer that keeps flushing dirty index leaves.
          (fn fail!
            ([failure] (fail! failure false))
            ([failure refusal?]
             (settle! {:seon.turn.loop/cluster cluster
                       :seon.turn.loop/now now
                       :seon.agent/id agent-id
                       :seon.turn/id run-id
                       :seon.error/value failure})
             (report :error 0 (when refusal? failure))))
          freeze!
          (fn [completion]
            ;; ONE INTENT COMMIT. The raw reply and every result-less eval row
            ;; become durable together before any form runs. A later crash is
            ;; therefore exactly the set difference between intent rows and
            ;; terminal results; recovery never has to reconstruct or rerun it.
            (let [reply-text (:seon.ai/text completion)
                  database (db/db connection)
                  namespace-name (sci.eval/agent-namespace database agent-id)
                  max-source
                  (get-in cluster
                          [:seon.sci.admit/caps
                           :seon.config.eval.result/max-source])
                  prepared
                  (if (and (:seon.config.ai/no-provider settings)
                           (empty? reply-text))
                    []
                    (planned-sources reply-text namespace-name max-source))
                  no-forms? (:seon.cluster.reply/no-forms prepared)
                  sources (cond
                            (vector? prepared) prepared
                            ; Evaluation source is nonempty by contract. An empty
                            ; reply uses whitespace; the turn retains its exact bytes.
                            no-forms? [{:seon.cluster.eval/source
                                        (if (empty? reply-text) "\n" reply-text)
                                        :seon.ns/name namespace-name}]
                            :else [])
                  staged-reply (stage-reply! connection reply-text)
                  plan-request
                  (merge (dissoc staged-reply :seon.blob/staged-writes)
                         {:seon.turn/id run-id
                          :seon.db.process/id process
                          ;; ONE ENTITY PER (run, ordinal): freezing the plan
                          ;; IS minting the evaluations, source and author and
                          ;; comment and all, with no terminal fact. There is
                          ;; no twin form row for the ordinals to disagree
                          ;; about, and the first ordinal is derived inside
                          ;; the transaction rather than assumed out here.
                          :seon.cluster.eval/at now
                          :seon.turn/sources sources})
                  intent-tx (plan-tx plan-request)
                  outcome
                  (blob/with-publication!
                   connection (:seon.blob/staged-writes staged-reply)
                   #(db/transact! connection {:tx-data intent-tx}))]
              (cond
                (or (:seon.db.write.attempt/request-id outcome)
                    (:seon.db/invalid-read outcome)
                    (:seon.schema/expected-value outcome)) (fail! outcome true)
                (empty? sources) (report :released 0)
                :else
                (resume-turn
                 (cond-> {:seon.turn.loop/cluster cluster
                          :seon.turn.loop/work
                          (assoc work :seon.turn.work/situation :resume
                                      :seon.cluster.eval/ordinal 0)
                          :seon.turn.loop/now now
                          :seon.turn.loop/report report}
                   no-forms?
                   ; The ordinary evaluator handles this exactly like the
                   ; reader's fabricated-response event. No fault is submitted.
                   (assoc :seon.sci.eval/event
                          {:seon.sci.reader/error prepared}))))))
          ;; THE PROMPT REQUEST NAMES THE HELD RUN — `prompt` derives
          ;; the trigger from the run's own creating transaction
          ;; (`message/trigger`), never a re-asked queue: the recorded
          ;; cause is the prompt's cause. One derivation, one owner.
          ;; NOTHING THROWS INTO THE AGENT LOOP: the prompt owner
          ;; refuses by throwing (`:seon.turn.loop/no-trigger`, `:seon.turn.loop/missing-input`),
          ;; and this one call site turns that refusal into the flat
          ;; error value the loop already records — the same shape a
          ;; refused transaction takes through `db/transact!`.
          observed-db (db/db connection)
          prompt-db (opening-db observed-db run-id)
          rendered
          (when-not (:seon.config.ai/no-provider settings)
          (phase
           ;; The prompt takes the observed value and the run id: its
           ;; acquisition derives the opening itself (`render/acquire-context!`).
           ;; Handing it `prompt-db` nested a second as-of whose origin has no
           ;; committed identity, so every render node re-keyed the projection
           ;; from declaration datoms (~40 ms each, 36 s per prompt).
           #(@cluster-prompt-prompt observed-db
                           {:seon.turn/id run-id
                            :seon.agent/id agent-id
                            :seon.db/connection connection
                            :seon.sci.admit/caps
                            (:seon.sci.admit/caps cluster)
                            :seon.sci.eval/ctx
                            (:seon.sci.eval/ctx cluster)
                            :seon.sci.eval/time-limit-ms
                            (:seon.config.eval/time-limit-ms cluster)})))
          refusal
          (when (or (:seon.turn.loop/phase-failed rendered)
                    (:seon.cluster.prompt/error-agent-id rendered))
            rendered)
          ;; CAPTURE BEFORE THE PROVIDER (ruling 4, 2026-07-28): the
          ;; exact prompt text, the rendered basis and the ordered
          ;; contribution records commit in ONE turn-owned transaction
          ;; BEFORE the unobservable remote call. Writer ordering then
          ;; guarantees: no capture → the prompt was never derived;
          ;; capture with no attempt row → the call may never have
          ;; fired. Failover/backoff attempts inside this same pass
          ;; REUSE this one capture — the same prompt bytes go out,
          ;; and the backup's system segment is re-derivable from the
          ;; committed primary error fact, never re-captured.
          captured
          (when-not (:seon.config.ai/no-provider settings)
          (db/transact!
           connection
           (@context-capture-tx
            (if refusal
              {:seon.turn/id run-id
               ;; The immutable opening value the refused derivation used,
               ;; never a fresh connection deref after the fact.
               :seon.db/db (if (or (:seon.turn/missing-opening-datom prompt-db)
                                   (:seon.db/invalid-read prompt-db)
                                   (:seon.schema/expected-value prompt-db))
                             observed-db
                             prompt-db)
               :seon.error/value refusal}
              {:seon.turn/id run-id
               :seon.cluster.prompt/rendered-context rendered}))))
          ;; THE EXACT-TEXT HANDOFF: the loop extracts the rendered
          ;; text and alone places that string in `:seon.ai/prompt` —
          ;; the bytes the capture recorded are the bytes sent.
          text (:seon.cluster.prompt/text rendered)]
      (cond
        (:seon.config.ai/no-provider settings)
        (freeze! {:seon.ai/text ""})

        (or (:seon.db.write.attempt/request-id captured)
            (:seon.db/invalid-read captured)
            (:seon.schema/expected-value captured))
        ;; A refused prompt/capture closes this run and records the refusal.
        ;; The next pass derives correction from those facts below the ONE
        ;; episode cap; at the cap it derives no work. No provider call occurs
        ;; without durable prompt evidence.
        (fail! captured)

        refusal
        ;; The refusal capture is now durable. Close without crossing the
        ;; provider boundary; a diagnosis never depends on absent signal.
        (fail! refusal)

        :else
        (loop [target primary
               ordinal (attempts (db/db connection) run-id)
               ;; ABSENT on the primary and on every backoff retry;
               ;; present only on the backup, where it is both the role
               ;; and the proof of which failure supplied its context
               failover-from nil
               delay-ms nil
               waits schedule
               system nil]
          (let [completion (ai/complete
                            (db/carried-projection db)
                            (cond-> (assoc target :seon.ai/prompt text)
                              system (assoc :seon.ai/system system)
                              sink (assoc :seon.ai/stream? true
                                          :seon.ai/sink sink)))
                failure (when (or (:seon.ai/unreadable-response-member completion)
                                  (:seon.ai/unanswered-reasoning-count completion)
                                  (:seon.ai/exhausted-finish-reason completion)
                                  (:seon.ai/interrupted-text-count completion)
                                  (:seon.ai/provider-error completion)
                                  (:seon.ai/timeout completion)
                                  (:seon.ai/transport-failure completion)
                                  (:seon.ai/extra-body-edn completion)
                                  (:seon.ai/protected-keys completion)
                                  (:seon.ai/missing-credential-variable completion))
                          completion)
                evidence (attempt-evidence {:seon.ai/completion completion})
                ;; a backup is only ever a target ONCE: the attempt that
                ;; already failed over cannot fail over again, and that
                ;; is what bounds a failover at exactly two calls
                disposition (when failure
                              (ai/disposition
                               {:seon.error/value failure
                                :seon.ai/backup? (and (some? backup)
                                                      (nil? failover-from))}))
                fact (record-attempt! cluster
                                      (attempt-request
                                       (cond->
                                        {:seon.ai/target target
                                         :seon.ai/settings settings
                                         :seon.turn/id run-id
                                         :seon.agent/id agent-id
                                         :seon.ai.attempt/ordinal ordinal
                                         :seon.turn.loop/attempt-evidence evidence}
                                         failure
                                         (assoc :seon.error/value failure)
                                         failover-from
                                         (assoc :seon.ai.attempt/failover-from
                                                failover-from)
                                         delay-ms
                                         (assoc :seon.ai.attempt/delay-ms
                                                delay-ms)))
                                      now)]
            (cond
              (or (:seon.db.write.attempt/request-id fact)
                  (:seon.db/invalid-read fact)
                  (:seon.schema/expected-value fact)) (fail! fact)

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
                     ;; site: the notice's declared AI render as the
                     ;; system text the provider takes (render-ai answers
                     ;; data for the value printer, and without a block
                     ;; identity its refusal map became the system text)
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
              :else
              (let [closed (db/transact!
                            connection
                            (close-tx {:seon.turn/id run-id :seon.turn/closed-tx "datomic.tx"}))]
                ;; The attempt already owns the fault. Closing must not
                ;; turn that same occurrence into another fault entity.
                (if (or (:seon.db.write.attempt/request-id closed)
                        (:seon.db/invalid-read closed)
                        (:seon.schema/expected-value closed))
                  (fail! closed true)
                  (report :error 0))))))))))

(defn- evaluation-entity-id
  "The entity id of one already-transacted evaluation, or nil.

  Absence is the whole answer: an in-memory preview never froze an evaluation
  entity, and asking for one that is not there must say so rather than mint
  an identity nothing else can resolve."
  [database evaluation-id]
  (db/q '[:find ?evaluation .
          :in $ ?evaluation-id
          :where [?evaluation :seon.cluster.eval/id ?evaluation-id]]
        database evaluation-id))

(defn- early-disposition?
  "Whether `form`'s value `value` is a disposition, or `form` calls
  `(my.agent/done ...)`, anywhere but as the reply's last form: the one
  disposition rule, over the form the fold read (never its source again)."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.batch/form :seon.batch/value :boolean] :boolean]}
  [projection form value last?]
  (let [calls (tree-seq
               #(and (coll? %)
                     (not (and (seq? %)
                               (contains? #{'quote 'fn 'fn* 'defn 'defn-} (first %)))))
               seq form)
        done-calls (filter #(and (seq? %) (= 'my.agent/done (first %))) calls)]
    (boolean
     (or (and (seq done-calls)
              (not (and last? (= form (first done-calls)) (= 1 (count done-calls)))))
         (and (disposition projection value) (not last?))))))

(defn- turn-rows-state
  "The fold's read state over a run's pre-opened rows: each step reads one
  row's source with the live reader, once."
  {:malli/schema [:=> [:cat :seon.cluster.reply/sources] :seon.batch/read-state]}
  [rows]
  {:seon.batch/rows (vec rows) :seon.batch/version {:seon.batch/text "" :seon.batch/pos 0}
   :seon.batch/repairs []})

(defn- read-row
  "One read step: the next row's source read once by the forgiving stage's
  live reader as code, in the namespace the run is in now (so an alias a
  prior form required resolves). A row whose read fails is that form's
  reader error, answered once and never evaluated."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world current :seon.turn/namespace-cursor max-source :seon.turn/max-source}
   {[row & more] :seon.batch/rows :as state}]
  (if-not row
    {:seon.batch/eof true :seon.batch/version (:seon.batch/version state) :seon.batch/repairs []}
    (let [text (:seon.cluster.eval/source row)
          v (forgive/version text 0)
          ;; a reply the segmenter found no form in is carried as one row with
          ;; its reader event: that refusal is the row's read, never its prose read as code
          carried (get-in row [:seon.sci.eval/event :seon.sci.reader/error])
          r (cond
              carried {:seon.batch/reason (:seon.error/message carried)}
              (and max-source (> (count text) max-source))
              {:seon.batch/reason (str "This form is " (count text) " characters; the reader's bound is " max-source ".")}
              :else
              (sci/binding [sci/ns (sci/create-ns @current)]
                (forgive/next-form ctx (sci.batch/parse-options ctx) :code {:seon.batch/version v :seon.batch/repairs []})))
          next-state (assoc state :seon.batch/rows (vec more))]
      (if (contains? r :seon.batch/form)
        (assoc r :seon.batch/read-state next-state)
        (let [message (str (or (:seon.batch/reason r) "The row holds no form."))
              ;; the reader's own refusal of this form's text
              refusal (cond
                        carried carried
                        (:seon.batch/cause r) (#'reader/reader-error text (:seon.batch/cause r))
                        :else
                        (#'reader/error-value :seon.sci.reader/form message {:seon.sci.reader/text text}
                                              {:seon.sci.reader/unreadable-member :seon.sci.reader/form}))]
          {:seon.batch/read-error (ex-info message {:seon.batch/error-value refusal})
           :seon.batch/start 0 :seon.batch/end (count text) :seon.batch/version v :seon.batch/repairs []
           :seon.batch/read-state next-state})))))

(defn- evaluate-row
  "The turn's evaluate stage: the evaluation frame (`seon.sci.eval/evaluate`
  given the held form) under the batch's registration delta, with the run's
  receipt, handle, pending open and profile. Answers the frame's evaluation
  as the outcome; a value the frame kept past a later failure is a value whose
  facts are refused."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/form :seon.db/database-value] :seon.batch/outcome]}
  [{world :seon.batch/world delta :seon.sci.batch/delta current :seon.turn/namespace-cursor
    frame :seon.turn/frame-request :as _request} form working]
  (let [ordinal (:seon.batch/ordinal world)
        request (assoc (frame ordinal @current)
                       :seon.db/db working
                       :seon.sci.eval/form form
                       :seon.db/read-evidence-sink (:seon.batch/reads world))
        evaluation (schema/call-with-registration-delta
                    (assoc delta :seon.schema.delta/projection (db/carried-projection working))
                    #(sci.eval/evaluate request))
        value (:seon.sci.admit/value evaluation)]
    (vreset! current (:seon.sci.eval/ending-ns evaluation))
    (cond
      (:seon.sci.eval/untrusted evaluation)
      {:seon.batch/value value :seon.batch/evaluation evaluation
       :seon.batch/facts-refused (select-keys (:seon.sci.eval/untrusted evaluation) [:seon.error/message])}
      (:seon.cluster.eval/error evaluation)
      {:seon.batch/error (ex-info (:seon.cluster.eval/error evaluation) {:seon.batch/error-value value})
       :seon.batch/evaluation evaluation}
      :else {:seon.batch/value value :seon.batch/evaluation evaluation})))

(defn- show-row
  "The turn's show stage: the one printer, under the arm, with the turn's
  profile, viewer namespace and the form's handle; the whole shown map rides
  the entry."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/entry] :seon.batch/printed]}
  [{{ctx :seon.sci.eval/ctx ordinal :seon.batch/ordinal} :seon.batch/world working :seon.batch/working
    frame :seon.turn/frame-request limit :seon.sci.eval/time-limit-ms}
   {:seon.batch/keys [value error evaluation form ns-before source] :as entry}]
  (let [request (assoc (frame ordinal (or ns-before (second (:seon.cluster.eval/ns evaluation)) 'user))
                       :seon.cluster.eval/source (or source "")
                       ;; the form's working value: its render reads what it read
                       :seon.db/db working)
        record (:seon.sci.admit/record evaluation)
        shown (kernel/with-arm
               ctx limit
               (fn [armed]
                 (if (contains? entry :seon.batch/error)
                   (sci.eval/shown-failure (or (:seon.batch/error-value (ex-data error)) value) request
                                           (or record ((::kernel/record armed) :error)))
                   (let [head (some-> (render/request-projection request)
                                      (render/head-function ctx (or ns-before 'user) form))]
                     (sci.eval/shown-result value (cond-> request head (assoc :seon.render.call/function head))
                                            (or record ((::kernel/record armed) :ok)))))))]
    {:seon.batch/shown (:seon.eval/shown shown "") :seon.batch/shown-map shown}))

(defn- publish-row
  "The turn's last word on a published form, in its world: the disposition
  rule over the held form (an early `(my.agent/done ...)` or a disposition
  before the last row is replaced by its refusal, the refused value kept
  beside it), the one bind of the final value under its handle, and whether
  reading stops (a disposition ends the reply)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch :seon.batch/entry]
                  [:map [:seon.batch/entry :seon.batch/entry] [:seon.batch/stop? :boolean]]]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world working :seon.batch/working frame :seon.turn/frame-request handles :seon.turn/handles}
   batch {:seon.batch/keys [ordinal form value ns-before] :as entry}]
  (let [projection (db/carried-projection working)
        ;; a form that never ran (its read failed) settles through the one
        ;; constructor of an unrun evaluation
        evaluation (or (:seon.batch/evaluation entry)
                       (sci.eval/unrun-evaluation
                        {:seon.sci.admit/value (:seon.batch/error-value (ex-data (:seon.batch/error entry)))
                         :seon.cluster.eval/ns [:seon.ns/name (or ns-before 'user)]}))
        entry (assoc entry :seon.batch/evaluation evaluation)
        last? (empty? (get-in batch [:seon.batch/read-state :seon.batch/rows]))
        refused? (and (contains? entry :seon.batch/value)
                      (early-disposition? projection form value last?))
        entry (if-not refused?
                entry
                (let [refusal (seon.error.refusal/diagnostic
                               (Date.) :seon.turn/evaluation 'seon.turn/evaluate-sources
                               {:seon.turn/invalid-disposition-source (:seon.batch/source entry)
                                :seon.error/message "(my.agent/done) must be the last form of your reply; a disposition cannot precede another reply form."
                                :seon.error/data {:seon.cluster.eval/source (:seon.batch/source entry)}})
                      unrun (sci.eval/unrun-evaluation {:seon.sci.admit/value refusal
                                                        :seon.cluster.eval/ns [:seon.ns/name (or ns-before 'user)]})
                      shown (sci.eval/shown-failure refusal (assoc (frame ordinal (or ns-before 'user)) :seon.db/db working)
                                                    (:seon.sci.admit/record unrun))]
                  (-> entry
                      (dissoc :seon.batch/value)
                      (assoc :seon.batch/error (ex-info (:seon.error/message refusal) {:seon.batch/error-value refusal})
                             :seon.batch/refused-value value
                             :seon.batch/evaluation (merge evaluation unrun shown)
                             :seon.batch/shown (:seon.eval/shown shown)
                             :seon.batch/shown-map shown))))
        final (if (contains? entry :seon.batch/value)
                value
                (or (:seon.batch/error-value (ex-data (:seon.batch/error entry)))
                    (:seon.sci.admit/value (:seon.batch/evaluation entry))))
        ;; The disposition is read here once, inside the fold's guard, and
        ;; carried as data: nothing after the fold walks the agent's value.
        settled (when (and (not refused?) (contains? entry :seon.batch/value))
                  (disposition projection value))]
    (when-let [handle (get handles ordinal)]
      (sci.eval/bind-result! ctx handle final))
    {:seon.batch/entry (cond-> entry settled (assoc-in [:seon.batch/evaluation :my.turn/value] settled))
     :seon.batch/stop? (some? settled)}))

(defn- code-evidence
  "What a form executed, as stored evidence: the definitions entered with a
  known digest, and whether that is all of it. :pending while work the form
  owns has not exited (or it read after its freeze); :unknown when a
  definition's digest was not known where it ran (never repaired from its
  name); else :complete."
  {:malli/schema [:=> [:cat :seon.batch/entry]
                  [:map [:seon.cluster.eval/executed :seon.cluster.eval/executed]
                   [:seon.cluster.eval/code-evidence [:enum :complete :unknown :pending]]]]}
  [{:seon.batch/keys [executed evidence owned reads read-sink]}]
  (let [ks (keys executed)
        late? (or (pos? (.get ^java.util.concurrent.atomic.AtomicLong owned))
                  (not= (count reads) (count @read-sink)))]
    {:seon.cluster.eval/executed (into #{} (keep (fn [[s d]] (when d [s d]))) ks)
     :seon.cluster.eval/code-evidence (cond (or (= :pending evidence) late?) :pending
                                            (some (comp nil? second) ks) :unknown
                                            :else :complete)}))

(defn- call-profile-facts
  "A form's call profile as its evaluation's facts: its armed calls, and each
  function it called more than `seon.batch/repeated-calls` times with calls and
  milliseconds (the same in every entrant's reply)."
  {:malli/schema [:=> [:cat :seon.batch/entry]
                  [:map [:seon.cluster.eval/call-count {:optional true} :seon.cluster.eval/call-count]
                   [:seon.cluster.eval/repeated-calls {:optional true} :seon.cluster.eval/repeated-calls]]]}
  [{{n :seon.batch/call-count repeated :seon.batch/repeated} :seon.batch/call-profile}]
  (cond-> {}
    (pos-int? n) (assoc :seon.cluster.eval/call-count n)
    (seq repeated) (assoc :seon.cluster.eval/repeated-calls (set repeated))))

(defn- entry-bindings
  "The Vars a form bound, as the installation reads them: symbol -> its value and metadata."
  {:malli/schema [:=> [:cat :seon.batch/batch :seon.cluster.eval/ordinal] :seon.sci.eval/bindings]}
  [batch ordinal]
  (into {} (keep (fn [{op :seon.batch/op sym :seon.batch/sym the-var :seon.batch/var kind :seon.batch/kind n :seon.batch/ordinal}]
                   (when (and (= :bind op) (= :root kind) sym (= n ordinal))
                     [sym {:seon.sci.eval/value @the-var :seon.sci.eval/metadata (meta the-var)}])))
        (:seon.batch/writes batch)))

(defn evaluate-sources
  "Evaluate a run's ordered rows through the one REPL path
  (`seon.batch/evaluate-batch`) in one fork, settling nothing.

  Each row is read once by the live reader, evaluated once by the evaluation
  frame, printed once, published through the disposition rule and its handle,
  its evidence frozen, and its program change derived, validated onto the
  working value and folded. An explicit database is the batch's basis;
  otherwise the connection's current value. Answers each form's evaluation as
  the settlement reads it and the batch's program with the request that folds
  it, so the caller's one settlement commits both."
  {:malli/schema [:=> [:cat :seon.turn.loop/evaluate-sources-request]
                  :seon.turn.loop/evaluated-batch]}
  [{cluster :seon.turn.loop/cluster
    snapshot :seon.db/db
    ctx :seon.sci.eval/ctx
    agent-id :seon.agent/id
    run-id :seon.turn/id
    first-ordinal :seon.cluster.eval/ordinal
    sources :seon.cluster.reply/sources
    starting-namespace :seon.ns/name}]
  (let [connection (:seon.db/connection cluster)
        basis (or snapshot (db/db connection))
        overlay (ai/agent-overlay basis agent-id)
        _ (when (:seon.error/at overlay)
            (throw (ex-info (:seon.error/message overlay "Agent overlay read refused.") overlay)))
        cluster (merge cluster overlay)
        ;; ONE DERIVATION PER TURN: the render profile and the viewing
        ;; namespace are the turn's presentation policy, carried on every form.
        profile (render/request-profile
                 {:seon.db/db basis :seon.agent/id agent-id
                  :seon.boot/cluster-name (:seon.cluster/name cluster)})
        _ (when (:seon.render/refused-member profile)
            (throw (ex-info (:seon.error/message profile) profile)))
        viewing (let [assigned (sci.eval/agent-namespace basis agent-id)]
                  (when (symbol? assigned) assigned))
        limit (:seon.config.eval/time-limit-ms cluster)
        rows (into [] (map-indexed (fn [i source] (assoc source :seon.cluster.eval/ordinal (+ first-ordinal i)))) sources)
        handles (if run-id
                  (into {} (map (fn [{n :seon.cluster.eval/ordinal}]
                                  [n (admit/result-handle (receipt-identity run-id n))]))
                        rows)
                  {})
        frame (fn [ordinal namespace-name]
                (cond-> (assoc (evaluation-request
                                {:seon.turn.loop/admitted-form (dissoc (get rows (- ordinal first-ordinal) {}) :seon.cluster.eval/ordinal)
                                 :seon.turn.loop/evaluation-namespace namespace-name
                                 :seon.turn.loop/cluster cluster
                                 :seon.sci.eval/ctx ctx
                                 :seon.agent/id agent-id
                                 :seon.cluster.eval/ordinal ordinal
                                 :seon.turn/id run-id})
                               :seon.db/connection connection
                               :seon.render/profile profile)
                  (get handles ordinal) (assoc :seon.repl/handle (get handles ordinal))
                  viewing (assoc :seon.render/namespace viewing)
                  ;; the run's open rides the form's first durable write
                  (:seon.turn.loop/submission cluster)
                  (assoc :seon.db/pending-open
                         (get-in cluster [:seon.turn.loop/submission :seon.db/tx-data]))))
        world (cond-> {:seon.boot/cluster-name (:seon.cluster/name cluster)
                       :seon.db/connection connection :seon.db/writer db/transact!
                       :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms limit
                       :seon.render/profile profile :seon.batch/sink (seon.batch/sink)
                       :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))
                       :seon.agent/id agent-id}
                run-id (assoc :seon.turn/id run-id)
                (env/of cluster) (-> (assoc :seon.env/environment (env/of cluster))
                                     (merge (seon.batch/environment-members (env/of cluster))))
                (:seon.db.process/id cluster) (assoc :seon.db.process/id (:seon.db.process/id cluster)))
        request (-> (turn.batch/program-request
                     {:seon.batch/world world
                      ;; the batch starts from the value this turn read
                      :seon.batch/basis basis
                      :seon.batch/text ""
                      :seon.batch/start-ns starting-namespace
                      :seon.batch/first-ordinal first-ordinal
                      :seon.batch/read-state (fn [_] (turn-rows-state rows))
                      :seon.batch/read-next read-row
                      :seon.batch/evaluate (fn [req form working]
                                             (await-turn-part! cluster :seon.sci.eval/evaluation limit)
                                             (evaluate-row req form working))
                      :seon.batch/ns-name (fn [{current :seon.turn/namespace-cursor}] @current)
                      :seon.batch/show show-row
                      :seon.batch/publish publish-row
                      :seon.batch/observe observe/observe
                      :seon.turn.batch/row-tx row-tx
                      :seon.turn/namespace-cursor (volatile! starting-namespace)
                      :seon.turn/frame-request frame
                      :seon.turn/handles handles
                      :seon.turn/max-source (:seon.config.eval.result/max-source (:seon.sci.admit/caps cluster))
                      :seon.sci.eval/time-limit-ms limit})
                    (dissoc :seon.batch/settle))
        batch (seon.batch/evaluate-batch request)]
    {:seon.turn.loop/evaluated-sources
     (mapv (fn [{:seon.batch/keys [ordinal evaluation shown-map ns-before reads facts-error change] :as entry}]
             (let [row (get rows (- ordinal first-ordinal))
                   evaluation (cond-> (merge (select-keys row [:seon.eval/origin])
                                             evaluation shown-map
                                             (code-evidence entry)
                                             (call-profile-facts entry)
                                             {:seon.cluster.eval/at (Date.)
                                              :seon.cluster.eval/read-evidence (db/read-evidence reads)
                                              :seon.cluster.eval/read-basis-transaction (db/basis-t basis)})
                                (get handles ordinal) (assoc :seon.repl/handle (get handles ordinal))
                                (seq (entry-bindings batch ordinal)) (assoc :seon.sci.eval/bindings (entry-bindings batch ordinal))
                                facts-error (-> (update :seon.cluster.eval/error
                                                        #(or % (str "Its program facts were not proposed: " (:seon.error/message facts-error))))
                                                ;; the refusal whole beside the value: its offending
                                                ;; referrers are what the agent repairs from
                                                (with-diagnostic facts-error)))
                   ;; A generated read whose reads now depend on the agent's own
                   ;; turn-taking (`generated-read-fault`) keeps its value: the
                   ;; fault rides beside it as a diagnostic, and its stored
                   ;; evidence names the turn attributes, so `generated-sources`
                   ;; never regenerates it. Refusing the turn instead parked the
                   ;; agent's loop after three refusals (root, 2026-09-28).
                   fault (when-let [entity-id (when run-id (evaluation-entity-id basis (receipt-identity run-id ordinal)))]
                             (when (and (= :system (:seon.cluster.eval/author
                                                    (db/pull basis [:seon.cluster.eval/author] entity-id)))
                                        (= :generate (:seon.turn.work/situation
                                                      (db/pull basis [:seon.turn.work/situation] [:seon.turn/id run-id]))))
                               (generated-read-fault basis (assoc row :seon.cluster.eval/ns [:seon.ns/name ns-before])
                                                     (select-keys evaluation [:seon.cluster.eval/read-evidence]))))
                     evaluation (cond-> evaluation
                                  fault (update :seon.cluster.eval/diagnostics
                                                #(str/join "\n" (remove nil? [% (print.value/bounded-text [fault])]))))]
               (when (and run-id (= :pending (:seon.cluster.eval/code-evidence evaluation)))
                 (kernel/own-pending! (receipt-identity run-id ordinal) (:seon.batch/owned entry)))
               (cond-> {:seon.cluster.eval/ordinal ordinal
                        :seon.turn.loop/admitted-form (assoc (dissoc row :seon.cluster.eval/ordinal)
                                                             :seon.cluster.eval/ns [:seon.ns/name (or ns-before starting-namespace)])
                        :seon.sci.eval/evaluation evaluation}
                 change (assoc :seon.batch/change change))))
           (:seon.batch/entries batch))
     :seon.turn.loop/program {:seon.batch/request request :seon.batch/program (:seon.batch/program batch {})}}))

(defn preview-sources
  "Evaluate authored source once in the assigned agent's fork, persisting nothing.

  THE PAGE IS NOT A SECOND EVALUATOR. A preview is the same parse, the same
  fork, and the same `evaluate-sources` an ordinary turn runs; what it lacks
  is a run, so nothing settles, no evaluation entity exists, and no
  `result/eN` handle is bound (ruling 59c). The renderer that shows a preview
  calls this and renders the returned evaluations; it never forks or parses
  on its own."
  {:malli/schema [:=> [:cat :seon.turn.loop/preview-sources-request]
                  [:or :seon.turn.loop/preview :seon.error/value]]}
  [{cluster :seon.turn.loop/cluster
    database :seon.db/db
    base-ctx :seon.sci.eval/ctx
    agent-id :seon.agent/id
    namespace-name :seon.ns/name
    text :seon.cluster.reply/text
    caps :seon.sci.admit/caps}]
  (let [opened-at (Date.)
        forked (sci.eval/fork-for-turn
                {:seon.sci.eval/ctx base-ctx
                 :seon.db/db database
                 :seon.db/connection (:seon.db/connection cluster)
                 :seon.agent/id agent-id})]
    (let [sources (planned-sources
                     text namespace-name
                     (:seon.config.eval.result/max-source caps))]
        (if (:seon.cluster.reply/no-forms sources)
          sources
          {:seon.turn.loop/evaluated-sources (:seon.turn.loop/evaluated-sources (evaluate-sources
            {:seon.turn.loop/cluster cluster
             :seon.db/db database
             :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
             :seon.agent/id agent-id
             :seon.cluster.eval/ordinal 0
             :seon.ns/name namespace-name
             :seon.cluster.reply/sources sources})) :seon.agent/id agent-id :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.db/db database}))))

(defn- settled
  "`report` carrying the database value its terminal settlement left
  (`:seon.turn.loop/basis`): the run's callers read the run there, never on a
  later connection value."
  {:malli/schema [:=> [:cat :seon.turn.loop/settlement :seon.turn.loop/turn-report] :seon.turn.loop/turn-report]}
  [settlement report]
  (cond-> report
    (get-in settlement [:seon.turn.loop/outcome :db-after])
    (assoc :seon.turn.loop/basis (get-in settlement [:seon.turn.loop/outcome :db-after]))))

(defn- applied
  "`report` carrying the value its settlement left and, for a settlement the
  writer answered when applied (`seon.db/transact-applied!`), its pending
  commit: the pass holds it so its next pass starts after it lands."
  {:malli/schema [:=> [:cat :seon.turn.loop/turn-report :seon.db/transaction-report]
                  :seon.turn.loop/turn-report]}
  [report outcome]
  (cond-> (assoc report :seon.turn.loop/basis (:db-after outcome))
    (:seon.db/durable outcome) (assoc :seon.db/durable (:seon.db/durable outcome))))

(defn- resume-turn
  "Evaluate an intent-frozen turn in memory, then settle the whole batch once."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.turn.loop/cluster :seon.turn.loop/cluster]
               [:seon.turn.loop/work :seon.turn.work/next]
               [:seon.turn.loop/now :inst]
               [:seon.sci.eval/event {:optional true}
                [:map [:seon.sci.reader/error :seon.cluster.reply/no-forms-error]]]
               [:seon.turn.loop/report
                [:function
                 [:=> [:cat :seon.turn.loop/outcome :int] :seon.turn.loop/turn-report]
                 [:=> [:cat :seon.turn.loop/outcome :int [:or :nil :seon.error/value]]
                  :seon.turn.loop/turn-report]]]]]
    :seon.turn.loop/turn-report]}
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now
    report :seon.turn.loop/report reader-event :seon.sci.eval/event}]
  (let [connection (:seon.db/connection cluster)
        agent-id (:seon.agent/id work)
        run-id (:seon.turn/id work)
        base-ctx (:seon.sci.eval/ctx cluster)
        forked
        (phase #(sci.eval/fork-for-turn
                 (cond-> {:seon.sci.eval/ctx base-ctx
                  :seon.db/db (db/db connection)
                  :seon.db/connection connection
                  :seon.agent/id agent-id
                  :seon.turn/id run-id}
                   (:seon.sci.eval/agent-ctx cluster)
                   (assoc :seon.sci.eval/agent-ctx
                          (:seon.sci.eval/agent-ctx cluster)))))
        trigger (phase #(message/trigger (db/db connection) run-id))
        ;; handles a fresh fork could not bind: a core fault delivered to this
        ;; agent's next context; the turn continues without them
        _ (when-let [unbound (:seon.sci.eval/unbound-results forked)]
            (fault/fault! (env/of cluster) (ex-info (:seon.error/message unbound) unbound)
                          {:seon.error/layer :seon.sci.eval/fork
                           :seon.error/operation 'seon.sci.eval/fork-for-turn
                           :seon.db.process/id (:seon.db.process/id cluster)
                           :seon.agent/id agent-id}))]
    (if-let [failure (some #(when (:seon.turn.loop/phase-failed %) %)
                           [forked trigger])]
      (settled (settle! {:seon.turn.loop/cluster cluster
                         :seon.turn.loop/now now
                         :seon.agent/id agent-id
                         :seon.turn/id run-id
                         :seon.error/value failure})
               (report :error 0))
      (let [{ctx :seon.sci.eval/ctx} forked
            database (db/db connection)
            submission (:seon.turn.loop/submission cluster)
            first-ordinal (:seon.cluster.eval/ordinal work)
            ;; A submitted run's rows may not be written yet: its sources and
            ;; starting namespace are the submission's, as its open writes them.
            evaluations (if submission
                          (into [] (map-indexed
                                    (fn [ordinal form]
                                      {:seon.cluster.eval/ordinal ordinal
                                       :seon.cluster.eval/source (:seon.cluster.eval/source form)
                                       :seon.cluster.eval/ns {:seon.ns/name (or (:seon.ns/name form) (:seon.ns/name submission))}}))
                                (:seon.turn/sources submission))
                          (fold-evaluations database run-id))
            evaluated
            (phase
             #(evaluate-sources
               {:seon.turn.loop/cluster cluster
                :seon.sci.eval/ctx ctx
                :seon.agent/id agent-id
                :seon.turn/id run-id
                ;; a submitted run's evaluations exist only in its settlement:
                ;; its handles are minted now, as for any written run
                :seon.turn/write? (contains? cluster :seon.turn.loop/submission)
                :seon.cluster.eval/ordinal first-ordinal
                :seon.ns/name (or (:seon.ns/name submission)
                                  (fold-namespace database run-id evaluations
                                                  first-ordinal)
                                  (sci.eval/agent-namespace database agent-id))
                :seon.cluster.reply/sources
                (into []
                      (comp (filter (fn [evaluation]
                                      (<= first-ordinal
                                          (:seon.cluster.eval/ordinal
                                           evaluation))))
                            (map (fn [evaluation]
                                   (cond-> (fold-source evaluation)
                                     reader-event
                                     (assoc :seon.sci.eval/event reader-event)))))
                      evaluations)}))]
        ;; The fold derived each form's program change against the namespace
        ;; SCI evaluated it in (`seon.turn.batch/derive-form`); the
        ;; settlement writer re-derives its facts.
        (if (:seon.turn.loop/phase-failed evaluated)
          ;; THE REFUSAL BELONGS ON THE FORM THAT EARNED IT: `settle!`
          ;; carries the ordinal through `refusal-terminal-data`.
          (settled (settle! {:seon.turn.loop/cluster cluster
                             :seon.turn.loop/now now
                             :seon.agent/id agent-id
                             :seon.turn/id run-id
                             :seon.cluster.eval/ordinal first-ordinal
                             :seon.error/value evaluated})
                   (report :error (count evaluated)))
          (let [gated (:seon.turn.loop/evaluated-sources evaluated)
                program (:seon.turn.loop/program evaluated)
                requests
                (mapv
                 (fn [{ordinal :seon.cluster.eval/ordinal evaluation :seon.sci.eval/evaluation}]
                   (let [problem
                         ; A reply with no form has no program owner to notify.
                         ; Its reader error belongs only in the author's history.
                         (when-not reader-event
                           (phase
                            #(@problems-form-problem
                              database
                              {:seon.turn/id run-id
                               :seon.cluster.eval/ordinal ordinal
                               :seon.sci.eval/evaluation evaluation})))]
                     (cond->
                      {:seon.turn.loop/cluster cluster
                       :seon.turn.loop/now now
                       :seon.agent/id agent-id
                       :seon.turn/id run-id
                       :seon.cluster.eval/ordinal ordinal
                       :seon.sci.eval/evaluation evaluation}
                       ;; A run no message caused (a continuation) has no
                       ;; trigger: stored absence is no key, never nil.
                       trigger (assoc :seon.message/trigger trigger)
                       (and problem (not (:seon.turn.loop/phase-failed problem)))
                       (assoc :seon.problems/form-problem problem))))
                 gated)
                ;; the committed value the settlement is written on: the
                ;; program-change fast path proves no commit came between
                written-on (db/db connection)
                settlement (settle-evaluated! cluster requests
                                          (assoc program :seon.batch/changes
                                                 (into [] (keep (fn [{n :seon.cluster.eval/ordinal c :seon.batch/change}]
                                                                  (when c {:seon.batch/ordinal n :seon.batch/change c})))
                                                       gated)))
                outcome (:outcome settlement)
                prepared (:prepared settlement)
                last-prepared (peek prepared)]
            (if-let [refusal (:refused-outcome settlement)]
              ;; a refused settlement keeps every evaluated value (OQ1): the
              ;; live definitions stay private to this fork, nothing restores
              (assoc (report :error (count gated) refusal)
                     :seon.turn.loop/basis (:db-after outcome))
              (let [installations
                    ;; the batch's accepted entities with the Vars its forms
                    ;; bound: what this settlement committed
                    (into []
                          (mapcat
                           (fn [{evaluation :seon.sci.eval/evaluation change :seon.batch/change}]
                             (for [row (:seon.batch/entities change)
                                   :when (sci.eval/committed-row? (:db-after outcome) row)]
                               {:seon.program/row row
                                :seon.sci.eval/evaluation evaluation})))
                          gated)]
                ;; A batch that defined nothing installs nothing; a defining
                ;; batch's program check reads this settlement's own datoms.
                ;; The settlement is committed: an install failure is a fault
                ;; recorded beside it, never a throw out of the turn.
                (when (seq installations)
                  (try
                    (sci.eval/install-evaluated-rows!
                     {:seon.sci.eval/ctx base-ctx
                      :seon.sci.eval/agent-ctx ctx
                      :seon.db/db (:db-after outcome)
                      :seon.db/transaction-report outcome
                      :seon.sci.eval/written-on written-on
                      :seon.sci.eval/installations installations})
                    (catch Throwable failure
                      (fault/fault! (env/of cluster) failure
                                    {:seon.error/layer :seon.turn/settlement
                                     :seon.error/operation `sci.eval/install-evaluated-rows!
                                     :seon.db.process/id (:seon.db.process/id cluster)
                                     :seon.agent/id agent-id}))))
                (if (or (:seon.turn.loop/settled last-prepared)
                        (:seon.turn.loop/undisposed? last-prepared)
                        ;; a folded close (close-folded-call) closed it in the settlement
                        (::closed-tx (db/pull (:db-after outcome) [::closed-tx] [::id run-id])))
                  (applied (report :closed (count gated)) outcome)
                  (applied (report :released (count gated)) outcome))))))))))
(defn- close-turn
  {:malli/schema [:=> [:cat [:map
                            [:seon.turn.loop/cluster :seon.turn.loop/cluster]
                            [:seon.turn.loop/work :seon.turn.work/next]
                            [:seon.turn.loop/now :inst]
                            [:seon.turn.loop/report
                             [:function
                              [:=> [:cat :seon.turn.loop/outcome :int] :seon.turn.loop/turn-report]
                              [:=> [:cat :seon.turn.loop/outcome :int [:or :nil :seon.error/value]]
                               :seon.turn.loop/turn-report]]]]]
                  :seon.turn.loop/turn-report]}
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now report :seon.turn.loop/report}]
  (let [;; a plan refusal is the agent's to act on: the turn still closes
        issue-refusal (plan/run-issue-tests! cluster (:seon.agent/id work))
        outcome (db/transact!
                 (:seon.db/connection cluster)
                 (conj (close-tx {:seon.turn/id (:seon.turn/id work) :seon.turn/closed-tx "datomic.tx"})
                       [:db.fn/call #'plan/settle-call (:seon.agent/id work)]))]
    (if-not (:db-after outcome)
      (do (settle! {:seon.turn.loop/cluster cluster :seon.turn.loop/now now
                     :seon.agent/id (:seon.agent/id work)
                     :seon.error/value outcome})
          (report :error 0 outcome))
      (if issue-refusal
        (do (settle! {:seon.turn.loop/cluster cluster :seon.turn.loop/now now
                      :seon.agent/id (:seon.agent/id work)
                      :seon.error/value issue-refusal})
            (report :closed 0 issue-refusal))
        (report :closed 0)))))

(defn- generate-turn
  "Append one opening form from the shared system-turn source generator."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.turn.loop/cluster :seon.turn.loop/cluster]
               [:seon.turn.loop/work :seon.turn.work/next]
               [:seon.turn.loop/now :inst]
               [:seon.turn.loop/report
                [:function
                 [:=> [:cat :seon.turn.loop/outcome :int] :seon.turn.loop/turn-report]
                 [:=> [:cat :seon.turn.loop/outcome :int [:or :nil :seon.error/value]]
                  :seon.turn.loop/turn-report]]]]]
    :seon.turn.loop/turn-report]}
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now report :seon.turn.loop/report :as request}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        agent-id (:seon.agent/id work)
        run-id (:seon.turn/id work)
        ordinal
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?form :seon.cluster.eval/run ?run]]
                  (db/db connection) run-id)
             0))
        entry
        (phase
         #(let [database (db/db connection)
                namespace-name (sci.eval/agent-namespace
                                database agent-id)
                declared (declared-sources cluster database agent-id namespace-name)]
            (if (or (:seon.cluster.reply/no-forms declared)
          (:seon.render.walk/missing-lookup declared)
          (:seon.render/refused-member declared)
          (:seon.render/candidates declared)
          (:seon.render/invalid-output declared)
          (:seon.render.unknown/reason declared)
          (:seon.render.transcript/refused-member declared)
          (:seon.config/error-key declared)
          (:seon.db/invalid-read declared)
          (:seon.schema/expected-value declared))
              declared
              (nth (system-plan database (:seon.turn/forms declared) {}) ordinal nil))))]
    (cond
      (or (:seon.turn.loop/phase-failed entry) (or (:seon.cluster.reply/no-forms entry)
          (:seon.render.walk/missing-lookup entry)
          (:seon.render/refused-member entry)
          (:seon.render/candidates entry)
          (:seon.render/invalid-output entry)
          (:seon.render.unknown/reason entry)
          (:seon.render.transcript/refused-member entry)
          (:seon.config/error-key entry)
          (:seon.db/invalid-read entry)
          (:seon.schema/expected-value entry)))
      (do
        (settle! {:seon.turn.loop/cluster cluster
                  :seon.turn.loop/now now
                  :seon.agent/id agent-id
                  :seon.turn/id run-id
                  :seon.error/value entry})
        (report :error 0))

      ;; Creation and later system turns derive their sources from the same
      ;; record walk. resume-turn below enters evaluate-sources, which captures
      ;; read evidence before the ordinary settlement writer persists it.
      (nil? entry)
      (let [terminal
            (db/transact!
             connection
             (close-tx
              {:seon.turn/id run-id :seon.db.process/id process :seon.turn/closed-tx "datomic.tx"}))]
        (if (or (:seon.db.write.attempt/request-id terminal)
          (:seon.db/invalid-read terminal)
          (:seon.schema/expected-value terminal))
          (do
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value terminal})
            (report :error 0 terminal))
          (report :closed 0)))

      :else
      (let [appended
            (db/transact!
             connection
             (append-generated-tx
              (cond-> {:seon.turn/id run-id
                       :seon.db.process/id process
                       :seon.cluster.eval/at now
                       :seon.cluster.eval/ordinal ordinal
                       ;; THE COMMENT AND THE FORM ARE TWO FIELDS. A generated
                       ;; opening reads back through the one REPL grammar, so
                       ;; its prose sits above the prompt exactly like an
                       ;; agent's own.
                       :seon.cluster.eval/source
                       (:seon.cluster.eval/source entry)
                       :seon.ns/name
                       (sci.eval/agent-namespace (db/db connection) agent-id)}
                (:seon.eval/origin entry)
                (assoc :seon.eval/origin (:seon.eval/origin entry))
                (:seon.cluster.eval/comment entry)
                (assoc :seon.cluster.eval/comment
                       (:seon.cluster.eval/comment entry)))))]
        (if (or (:seon.db.write.attempt/request-id appended)
          (:seon.db/invalid-read appended)
          (:seon.schema/expected-value appended))
          (do
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value appended})
            (report :error 0 appended))
          (resume-turn
           (assoc request :seon.turn.loop/work
                  (assoc work
                         :seon.turn.work/situation :resume
                         :seon.cluster.eval/ordinal ordinal))))))))

(defn- episode-exhausted!
  "Store and deliver the fault of a pass that spent the agent's last episode run.

  The bound (`:seon.config.run/max-episode-runs`) defers every later opening
  (`opening-deferred?`), so this is the one pass that can say so: its wakes
  stay unanswered and the terminal event, a reply completing the episode
  (`seon.run/complete`), never arrived. Agent and run identity are untouched,
  so a new outside wake resumes the agent. `seon.fault/fault!` throws to the
  turn's caller only when the fault cannot be stored."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.agent/id :seon.turn.loop/turn-report] :nil]}
  [cluster agent-id result]
  (when-not (= :released (:seon.turn.loop/outcome result))
    (let [database (db/db (:seon.db/connection cluster))
          left (turns-left database agent-id)
          deferred (when (and (int? left) (zero? left))
                     (deferred-triggers database agent-id))]
      (when (seq deferred)
        (let [limit (max-episode-runs database agent-id)
              diagnostic
              (seon.error.refusal/diagnostic
               (Date.) :seon.turn/episode 'seon.turn/turn
               {:seon.error/message
                (str "Agent " (pr-str agent-id) " spent its " limit " episode runs; "
                     (count deferred) " wake(s) stay unanswered because the terminal "
                     "event, a reply completing the episode (seon.run/complete), never "
                     "arrived. A new outside wake resumes the agent.")
                :seon.error/expected 'seon.run/complete
                :seon.error/offending (mapv :seon.message/id deferred)
                :seon.error/data (cond-> {:seon.agent/id agent-id
                                          :seon.config.run/max-episode-runs limit}
                                   (:seon.turn/id result) (assoc :seon.turn/id (:seon.turn/id result)))})]
          (fault/fault! (env/of cluster) (ex-info (:seon.error/message diagnostic) diagnostic)
                        {:seon.error/layer :seon.turn/episode
                         :seon.error/operation 'seon.turn/turn
                         :seon.db.process/id (:seon.db.process/id cluster)
                         :seon.agent/id agent-id}))))
    nil))

(defn- close-escaped-run!
  "Close `agent-id`'s open run after `escaped` left the turn, and answer
  `escaped` to propagate whole.

  THE TURN BOUNDARY OWNS THE RUN'S TERMINAL. A Throwable leaving any stage
  (read, evaluate, gate, settle, record) used to leave the run open, so every
  later wake of the agent met `agent-already-running` (a symbol-keyed sorted
  map, 2026-09-26). The writer decides what was unfinished: `recover-call`
  closes the run and interrupts its unsettled receipts and effects, the same
  decision boot makes. The throwable itself is the one fault: it continues to
  the proc's fault route under the declared policy. A failure to close is
  suppressed onto it, never substituted for it."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :seon.agent/id :seon.error/throwable]
                  :seon.error/throwable]}
  [cluster agent-id escaped]
  (let [connection (:seon.db/connection cluster)
        suppress! #(.addSuppressed ^Throwable escaped
                                   (if (instance? Throwable %)
                                     %
                                     (ex-info (:seon.error/message % "The escaped run could not be closed.") %)))]
    (try
      (let [run-id (open-for-agent (db/db connection) [:seon.agent/id agent-id])]
        (if (string? run-id)
          (let [closed (db/transact! connection (recover-tx {::id run-id ::now (Date.)}))]
            (when-not (:db-after closed) (suppress! closed)))
          (when (map? run-id) (suppress! run-id))))
      (catch Throwable failure (suppress! failure)))
    escaped))

(defn turn
  "Run one turn to its next durable boundary; returns the turn report.
  The sequence is the contract: open → derive prompt → model (`:io`)
  → split reply → freeze plan → reduce over ordered forms (running
  receipt → guarded eval at the previous step's `:db-after` → terminal
  receipt + disposition in ONE transaction) → close or release.
  Every failure inside it is a VALUE: a model error, an unreadable
  reply, and a refused transaction each end the turn with facts the
  agent reads on its next wake. Nothing throws into the loop.

  The turn binds its own cluster's schema projection state for the whole
  pass. Every `seon.db` read and write the turn issues therefore RECEIVES
  the projection (§2.1) instead of rebuilding it from the database value it
  is reading — a rebuild recompiles every declared schema and function
  contract, and its cache is keyed on committed identity, so a turn's own
  commits invalidate it by construction (measured 2026-09-07: 507-670 ms
  per cold rebuild on `projection-lane`). The binding belongs here, at the
  transform that holds the handle, rather than in whichever executor
  happens to run the proc: an executor that does not bind it is a silent
  half-second-per-commit cliff with no signal. A handle without projection
  state leaves whatever the caller handed in place, so a fixture that binds
  its own projection keeps it."
  {:malli/schema [:=> [:cat :seon.turn.loop/turn-request :inst]
                  :seon.turn.loop/turn-report]}
  [{:keys [:seon.turn.loop/cluster] work :seon.turn.work/next}
   now]
  (let [agent-id (:seon.agent/id work)
        run-id (:seon.turn/id work)
        report (fn report
                 ([outcome forms-run] (report outcome forms-run nil))
                 ;; THE PASS NAMES ITS REFUSED WRITE. A turn whose durable
                 ;; transaction was refused reports the refusal value itself,
                 ;; because the proc's bound (`step`) counts CONSECUTIVE
                 ;; refused writes for this agent and must name the refusal in
                 ;; the one fault it commits. A provider or agent failure is
                 ;; not a write refusal and carries no refusal here.
                 ([outcome forms-run refusal]
                  (cond-> {:seon.agent/id agent-id
                           :seon.turn.work/situation
                           (:seon.turn.work/situation work)
                           :seon.turn.loop/forms-run forms-run
                           :seon.turn.loop/outcome outcome}
                    run-id (assoc :seon.turn/id run-id)
                    refusal
                    (assoc :seon.turn.loop/refusal refusal))))
        request {:seon.turn.loop/cluster cluster
                 :seon.turn.loop/work work
                 :seon.turn.loop/now now
                 :seon.turn.loop/report report}
        pass (fn []
               (let [request (update request :seon.turn.loop/cluster merge
                                     (ai/agent-overlay
                                      (db/db (:seon.db/connection cluster)) agent-id))]
                 (let [result (case (:seon.turn.work/situation work)
                                :open (open-turn request)
                                :call (call-turn request)
                                :generate (generate-turn request)
                                :resume (resume-turn request)
                                :close (close-turn request))
                       ;; A source submission spends no episode run
                       ;; (`episode-runs`), so its pass cannot be the one that
                       ;; spent the last: no budget reads on its reply path.
                       _ (when-not (:seon.turn.loop/submission cluster)
                           (episode-exhausted! cluster agent-id result))]
                   (if (and (= :closed (:seon.turn.loop/outcome result))
                              (not= :generate (:seon.turn.work/situation work))
                              (db/q '[:find ?issue . :in $ ?id :where
                                      [?agent :seon.agent/id ?id]
                                      [?issue :seon.issue/agent ?agent]]
                                    (db/db (:seon.db/connection cluster)) agent-id))
                     (let [refreshed (system-turn {:seon.turn.loop/cluster cluster
                                                  :seon.agent/id agent-id :seon.turn/write? true})]
                       (if (:seon.turn/refused-system-agent refreshed)
                         (report :error 0 refreshed)
                         result))
                     result))))]
    (try
      (if-let [projection-state (:seon.sci.eval/projection-state cluster)]
        (schema/call-with-projection-state projection-state pass)
        (pass))
      ;; a stop's interrupt is its own terminal: interrupted execution never
      ;; resumes, and disarm closes the run it interrupted with the same
      ;; writer decision (`seon.cluster.agent/record-interruption!`)
      (catch InterruptedException stop (throw stop))
      (catch Throwable escaped
        (throw (close-escaped-run! cluster agent-id escaped))))))

(defn turn-completion-error
  "Describe the turn completion event that did not arrive within its bound."
  {:malli/schema [:=> [:cat :seon.agent/id [:maybe :seon.turn/id]
                       [:int {:min 1}] :keyword :keyword [:vector :keyword]]
                  :seon.error/value]}
  [agent-id run-id timeout-ms operation expected events]
  (let [evidence
        (cond->
         {:seon.agent/id agent-id
          :seon.config.agent/turn-completion-backstop-ms timeout-ms
          :seon.agent/completion-events events}
          run-id (assoc :seon.turn/id run-id))
        diagnostic
        (cond->
          (seon.error.refusal/diagnostic (Date.) :seon.agent/agent-graph 'seon.turn/turn-completion-error
           {:seon.error/message (str "Agent " (pr-str agent-id)
                (if run-id
                  (str " run " (pr-str run-id))
                  " with no observable open turn")
                " did not publish "
                (case expected
                  :seon.ai/completion "provider response"
                  :seon.sci.eval/evaluation "evaluation completion"
                  :seon.agent/turn-permit "turn permit"
                  :seon.agent/turn-completed "proc stop acknowledgement"
                  "turn completion")
                " within " timeout-ms " ms.")
           :seon.agent/id agent-id
           :seon.agent/turn-completion-backstop agent-id
           :seon.config.agent/turn-completion-backstop-ms timeout-ms
           :seon.error/member :seon.turn.loop/completion
           :seon.error/expected expected
           :seon.error/offending evidence
           :seon.error/data (merge evidence {:seon.error/operation operation})})
           run-id (assoc :seon.turn/id run-id))]
    diagnostic))

(defn- turn-completion-backstop-failure
  [agent-id run-id timeout-ms operation expected events]
  (let [diagnostic (turn-completion-error agent-id run-id timeout-ms operation expected events)]
    (ex-info (:seon.error/message diagnostic) diagnostic)))

(defn- agent-dial
  "One agent dial: the agent's overlay, else the handle's carried value, else
  the cluster's effective config; nil only when none of them declares it.

  A refused overlay or config read is RETURNED, the declared error it is; it
  never falls through to the next source as if it were absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn.loop/cluster
                       :seon.agent/id :qualified-keyword]
                  [:or [:maybe :seon.schema/value] :seon.error/value]]}
  [database cluster agent-id dial]
  (let [overlay (ai/agent-overlay database agent-id)]
    (if (:seon.error/at overlay)
      overlay
      (or (get overlay dial)
          (get cluster dial)
          (let [effective (config/effective database (:seon.cluster/name cluster))]
            (if (:seon.error/at effective) effective (get effective dial)))))))

(defn- dial-absent
  "The declared refusal of a turn dial no source declares: the cluster names
  none, so the turn it bounds cannot run. Apply the config manifest."
  {:malli/schema [:=> [:cat [:maybe :seon.cluster/name] :seon.agent/id :qualified-keyword] :seon.error/value]}
  [cluster-name agent-id dial]
  (seon.error.refusal/diagnostic
   (Date.) :seon.turn/configuration `dial-absent
   {:seon.error/message (str "Cluster " (pr-str cluster-name) " declares no " dial
                             ", so agent " (pr-str agent-id) "'s turn has no bound. Apply the config manifest.")
    :seon.config/required-absent dial
    :seon.error/member dial
    :seon.error/expected dial}))


(defn- await-turn-permit!
  [state]
  (let [{connection :seon.db/connection
         cluster-name :seon.cluster/name
         process :seon.db.process/id
         completion :seon.turn.loop/completion
         executor :seon.flow/executor
         backstop-state :seon.agent/turn-backstop-state
         stop-request! :seon.fault/stop-request!}
        (:seon.turn.loop/cluster state)
        agent-id (:seon.agent/id state)
        database (db/db connection)
        run-id (open-for-agent database [:seon.agent/id agent-id])
        timeout-ms (or (agent-dial database (:seon.turn.loop/cluster state) agent-id
                                   :seon.config.agent/turn-completion-backstop-ms)
                       (dial-absent cluster-name agent-id :seon.config.agent/turn-completion-backstop-ms))
        [value selected]
        (when (int? timeout-ms)
          (async/alts!! [completion (async/timeout timeout-ms)] :priority true))]
    (cond
      ;; a dial the turn cannot run without, refused or absent, is the
      ;; step's declared answer: the proc parks on it (`step`)
      (not (int? timeout-ms)) timeout-ms
      (= selected completion)
      (cond->
      {:seon.agent/turn-permit value
       :seon.agent/connection connection
       :seon.agent/process process
       :seon.agent/executor executor
       ;; the world a firing bound records its fault in, carried, not a channel
       :seon.env/environment (env/of (:seon.turn.loop/cluster state))
       :seon.agent/backstop-state backstop-state
       :seon.agent/agent-id agent-id
       :seon.agent/timeout-ms timeout-ms
       ;; WHAT THE BOUND IS ARMED AGAINST, carried. The run this pass is
       ;; about is not known until the pass derives its work, so the
       ;; subject is published here and set there — never re-read when
       ;; the bound FIRES, which is the owner law's pre-read: custody is
       ;; legitimately released in between, so two firings described two
       ;; different worlds and a sliding-1 fault channel kept whichever
       ;; arrived last.
       :seon.agent/run-id (atom run-id)}
        ;; the execution owner's stop, requested before a panic records
        stop-request! (assoc :seon.fault/stop-request! stop-request!))
      :else
      (throw
       (turn-completion-backstop-failure
        agent-id run-id timeout-ms :seon.agent/turn-start :seon.agent/turn-permit
        [:seon.turn.loop/completion])))))

(defn write-refusal-bound
  "Consecutive refused turn writes this agent may make before the proc parks.

  A DECLARED dial (`:seon.config.agent/write-refusal-bound`), not a tuned
  constant: the observable event it stands in for is \"this agent's durable
  write is refused and re-firing cannot change that\". Resolution is exactly
  the turn completion allowance's: the agent's own overlay wins, then the
  value the cluster handle carries, then the cluster's effective config."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn.loop/cluster
                       :seon.agent/id]
                  [:or :seon.config.agent/write-refusal-bound :seon.error/value]]}
  [database cluster agent-id]
  ;; ABSENCE IS NEVER "no bound". A cluster whose config singleton predates
  ;; this dial would otherwise read as healthy and re-fire forever, which is
  ;; the exact failure class this bound exists to end: it answers the
  ;; declared absence, and the proc parks on it.
  (or (agent-dial database cluster agent-id :seon.config.agent/write-refusal-bound)
      (dial-absent (:seon.cluster/name cluster) agent-id :seon.config.agent/write-refusal-bound)))

(defn write-refusal-error
  "Describe the repeated write refusal that parked one agent's turn proc."
  {:malli/schema [:=> [:cat :seon.agent/id [:maybe :seon.turn/id]
                       :seon.error/value [:int {:min 1}]
                       :seon.config.agent/write-refusal-bound]
                  :seon.error/value]}
  [agent-id run-id refusal refusals bound]
  (cond->
    (seon.error.refusal/diagnostic (Date.) :seon.agent/agent-graph 'seon.turn/write-refusal-error
     {:seon.error/message (str "Agent " (pr-str agent-id)
          (if run-id (str " turn " (pr-str run-id)) " with no open turn")
          " had " refusals " consecutive turn writes refused (bound "
          bound "); the turn proc is parked and will not re-fire. Latest "
          "refusal: " (:seon.error/message refusal))
     :seon.agent/id agent-id
     :seon.turn.loop/write-refusals refusals
     :seon.config.agent/write-refusal-bound bound
     :seon.error/expected :seon.turn.loop/committed-write
     :seon.error/offending refusal
     :seon.error/data {:seon.error/operation :seon.turn.loop/step}})
     run-id (assoc :seon.turn/id run-id)))

(defn- record-write-refusal-fault!
  "Record ONE core fault for a bound that fired, on the turn proc's own thread.

  §2.3: the bound firing IS the bug report. It names the refusal, the agent,
  the consecutive count and the declared bound; the proc stops re-firing, so
  the storm that filled the store a gigabyte a minute becomes one durable
  fact on the problems surface. `seon.fault/fault!` stores and delivers it;
  a fault it cannot store throws to `var-process`, which stops the graph."
  [cluster agent-id run-id refusal refusals bound]
  (let [diagnostic (write-refusal-error agent-id run-id refusal refusals bound)]
    (fault/fault! (env/of cluster) (ex-info (:seon.error/message diagnostic) diagnostic)
                  {:seon.error/layer :seon.turn.loop/write-refusals-exhausted
                   :seon.error/operation 'seon.turn/step
                   :seon.db.process/id (:seon.db.process/id cluster)
                   :seon.agent/id agent-id})
    diagnostic))

(defn- record-turn-backstop-fault!
  "Record the fault of a turn whose completion bound fired, on the backstop's
  thread, and answer the terminal throwable disarm joins: the recorded
  failure, else the unrecorded fault's throw whole (its receipt, refused
  outcome and suppressed causes, caused by the failure), after the execution
  owner's stop request (`:seon.fault/stop-request!`)."
  [{:seon.agent/keys [agent-id timeout-ms expected process] armed-run :seon.agent/run-id :as bound}]
  (let [run-id @armed-run
        failure
        (turn-completion-backstop-failure
         agent-id run-id timeout-ms :seon.agent/turn-transform
         (or expected :seon.agent/turn-terminal)
         [:seon.turn.loop/completion])]
    (try (fault/fault! (env/of bound) failure
                       (cond-> {:seon.error/layer :seon.agent/turn-completion-backstop
                                :seon.error/operation 'seon.turn/arm-turn-completion-backstop!
                                :seon.db.process/id process
                                :seon.agent/id agent-id}
                         (:seon.fault/stop-request! bound)
                         (assoc :seon.fault/stop-request! (:seon.fault/stop-request! bound))))
         failure
         (catch Throwable unrecorded unrecorded))))

(defn- arm-turn-completion-backstop!
  "Arm the live bound after the ready permit is consumed.

  The step cancels it once the permit is republished, whether the pass
  returned or threw: an escaped transform's throw is its turn's one fault,
  surfaced to Flow, so the observer never re-reports it after the bound."
  {:malli/schema [:=> [:cat [:map [:seon.agent/executor :seon.flow/executor]
                            [:seon.agent/timeout-ms [:int {:min 0}]]]]
                  [:map [:seon.agent/cancel :seon.flow/channel]
                   [:seon.agent/failure-channel :seon.flow/channel]
                   [:seon.turn.loop/await-part [:=> [:cat :qualified-keyword :int] :boolean]]]]}
  [{:seon.agent/keys [executor timeout-ms backstop-state] :as turn-bound}]
  (when-not (instance? Executor executor)
    (throw
     (ex-info "An active agent turn requires its carried IO executor."
              {:seon.agent/id (:seon.agent/agent-id turn-bound)
               :seon.agent/turn-completion-backstop
               (:seon.agent/agent-id turn-bound)})))
  (let [cancel (async/chan 1)
        parts (async/chan (async/sliding-buffer 1))
        failure-channel (async/promise-chan)
        backstop {:seon.agent/cancel cancel
                  :seon.agent/failure-channel failure-channel
                  :seon.turn.loop/await-part
                  (fn [expected work-ms]
                    (async/offer! parts
                                  (assoc turn-bound
                                         :seon.agent/expected expected
                                         :seon.agent/timeout-ms (+ timeout-ms work-ms))))}]
    (when backstop-state
      (reset! backstop-state backstop))
    (.execute
     ^Executor executor
     ^Runnable
     (fn []
       (loop [bound turn-bound]
         (let [timeout (async/timeout (:seon.agent/timeout-ms bound))
               [part selected] (async/alts!! [cancel parts timeout] :priority true)]
           (cond
             (= selected parts) (recur part)
             (= selected timeout)
             ;; Disarm joins this same failure, including its admitted part.
             (async/put! failure-channel
                         (try (record-turn-backstop-fault! bound)
                              ;; a failure before any receipt settles the
                              ;; join too, and still requests the stop
                              (catch Throwable failure
                                (when-let [stop! (:seon.fault/stop-request! bound)]
                                  (try (stop!) (catch Throwable stop-failure
                                                 (.addSuppressed failure stop-failure))))
                                failure)))
             :else
             (do
               (when backstop-state
                 (compare-and-set! backstop-state backstop nil))
               (async/close! failure-channel)))))))
    backstop))

(defn- await-settled!
  "The agent's previous settlement was answered when applied: when the
  connection has not caught up with it, the next pass waits for its commit
  (`await-durable!`), so every read of the pass sees it."
  {:malli/schema [:=> [:cat :seon.turn.loop/cluster :map] :nil]}
  [cluster {pending :seon.db/durable settled :seon.turn.loop/basis}]
  (when (and pending settled
             (> (db/basis-t settled) (db/basis-t (db/db (:seon.db/connection cluster)))))
    (await-durable! cluster pending))
  nil)

(defn step
  "The turn transform, in Flow's four arities: ONE episode pass.
  Settle this agent's orphan (the wedge fence, per-agent), pin one
  database value, derive `next-agent-work`, run the situation through
  `seon.turn/turn` — the surviving owner of open/call/resume/
  close and the pre-provider capture — then
  `offer!` one wake into this agent's OWN mailbox, so the next pass's one
  derivation finds what remains. A source submission arrives as a value on
  the request port and is its own work: its run's open is applied here and
  commits with its settlement, and its reply port is answered with the
  report. Coalescing on sliding-1 keeps the rewake non-recursive. Failures inside the pass stay VALUES (the existing
  `refused!`/`error-tx` owners); a Throwable that escapes anyway is a
  core fault and rides this graph's error channel into the cluster's
  fault committer, tagged with the agent. The completion channel is an
  armed-ready permit: arm publishes it before Flow scheduling, an active
  transform holds it under the completion allowance plus the admitted provider
  schedule or each form's evaluation limit, and
  `finally` republishes it without an interruptible park. The bound retires
  there, at the transform's actual exit, and only after its terminal: `turn`
  closes the run before any throw leaves it (`close-escaped-run!`), so an
  escaped pass is one closed run plus its one fault. Disarm awaits the stop
  transition or joins that same active bound; a ready permit never
  substitutes for proc exit."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :seon.schema/value]
                   [:tuple :map [:maybe [:map [::flow/report [:vector :seon.turn.loop/pass-report]]]]]]]}
  ([]
   ;; The agent's request port (`:seon.agent/request`, one source submission
   ;; as a value) joins as an external in-port (`seon.cluster.agent/turn-step`).
   {:ins {:seon.agent/episode "One payload-free episode signal from the mailbox."}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   (select-keys state [:seon.turn/id]))})
  ([args]
   args)
  ([state _transition]
   state)
  ([state input message]
   (let [cluster (:seon.turn.loop/cluster state)
         completion (:seon.turn.loop/completion cluster)
         submission (when (= :seon.agent/request input) message)
         reply (:seon.agent/reply submission)]
     (if (and (:seon.turn.loop/parked state) (not submission))
       ;; PARKED. The declared write-refusal bound already fired and committed
       ;; its one fault; a later WAKE must not resurrect the storm. An
       ;; explicit submission is a caller asking: it gets one attempt, whose
       ;; refusal is answered on its reply with the park, and a committed
       ;; write clears the park (a transient cause such as memory pressure
       ;; must not park the agent until restart, sweep 2026-09-27 item 2).
       [state nil]
       (if-some [turn-bound (await-turn-permit! state)]
       (if (:seon.error/at turn-bound)
         ;; A turn dial refused or absent: the pass cannot run. The proc parks
         ;; with its one recorded fault, answering a caller's reply with it;
         ;; a later submission tries again (a config apply can repair it).
         (let [agent-id (:seon.agent/id state)]
           (when-not (:seon.turn.loop/parked state)
             (fault/fault! (env/of cluster) (ex-info (:seon.error/message turn-bound) turn-bound)
                           {:seon.error/layer :seon.turn/configuration
                            :seon.error/operation `step
                            :seon.db.process/id (:seon.db.process/id cluster)
                            :seon.agent/id agent-id}))
           (when reply (async/offer! reply {:seon.turn.loop/refusal turn-bound}))
           [(assoc state :seon.turn.loop/parked turn-bound) nil])
       (let [backstop (arm-turn-completion-backstop! turn-bound)
             cluster (assoc cluster :seon.turn.loop/await-part
                            (:seon.turn.loop/await-part backstop))]
        (try
                (let [agent-id (:seon.agent/id state)
                      connection (:seon.db/connection cluster)
                      process (:seon.db.process/id cluster)
                      now (Date.)
                      request {:seon.agent/id agent-id
                               :seon.db.process/id process}
                      ;; THE PREVIOUS SETTLEMENT WAS ANSWERED WHEN APPLIED; this
                      ;; pass starts on its committed value, so every read below
                      ;; (derivation, fork, forms, gate) sees it. Waiting happens
                      ;; only when the next pass follows within the commit.
                      _ (await-settled! cluster state)
                      database (db/db connection)
                      ;; A SUBMISSION IS ITS OWN WORK. The writer's own decision
                      ;; (`system-run-plan`) on this one value refuses a busy or
                      ;; closed agent to its caller and names the starting
                      ;; namespace; the open itself rides the form's first durable
                      ;; write or the settlement (`submission-open-tx`).
                      run-request (:seon.agent/run-request submission)
                      plan (when submission
                             ;; only the writer's declared refusals answer the
                             ;; caller; anything else escapes whole to the fault route
                             (try (system-run-plan database run-request)
                                  (catch clojure.lang.ExceptionInfo refused
                                    (if (::rule (ex-data refused)) (ex-data refused) (throw refused)))))
                      ;; A reply large enough to be a staged blob opens its run
                      ;; in its own commit, publishing the blob with it.
                      opened (when (and plan (not (:seon.error/at plan))
                                        (seq (:seon.blob/staged-writes submission)))
                               (blob/with-publication!
                                connection (:seon.blob/staged-writes submission)
                                #(db/transact! connection (submission-open-tx run-request))))
                      refusal (some #(when (:seon.error/at %) %) [plan opened])
                      next (cond
                             (nil? submission) (next-agent-work database request)
                             refusal nil
                             :else {:seon.turn.work/situation :resume
                                    :seon.turn/id (:seon.turn/id submission)
                                    :seon.agent/id agent-id
                                    :seon.cluster.eval/ordinal 0})
                      ;; A REFUSED DERIVATION IS A CORE FAULT, NOT A SITUATION:
                      ;; its declared error arm escapes whole to this graph's
                      ;; fault route instead of reaching `turn` as work.
                      _ (when (:seon.error/at next)
                          (throw (ex-info (:seon.error/message next "The next-work read refused.")
                                          next)))
                      ;; THE BOUND LEARNS ITS SUBJECT HERE, once, from
                      ;; the derivation that decided it. `:open` mints
                      ;; its run inside the turn, so the report supplies
                      ;; it below; every other situation names it now.
                      _ (when-let [derived (:seon.turn/id next)]
                          (reset! (:seon.agent/run-id turn-bound) derived))]
                  (if (nil? next)
                    (do (some->> refusal (async/offer! reply))
                        [(dissoc state :seon.turn/id)
                         {::flow/report [{:seon.agent/id agent-id
                                         :seon.turn.loop/idle? true}]}])
                    (let [report (turn
                                  {:seon.turn.loop/cluster
                                   (cond-> cluster
                                     (and submission (not opened))
                                     (assoc :seon.turn.loop/submission
                                            {:seon.db/tx-data (submission-open-tx run-request)
                                             :seon.agent/run-request run-request
                                             :seon.turn/sources (::sources run-request)
                                             :seon.ns/name (:seon.ns/name plan)}))
                                   :seon.turn.work/next next}
                                  now)
                          pending-commit (select-keys report [:seon.turn.loop/basis :seon.db/durable])
                          report (dissoc report :seon.turn.loop/basis :seon.db/durable)
                          _ (when-let [opened (:seon.turn/id report)]
                              (reset! (:seon.agent/run-id turn-bound) opened))
                          ;; A refusal whose settlement still committed (a
                          ;; refused batch settled through its refusal arm: the
                          ;; agent's own mistake, answered with a basis) is no
                          ;; refused WRITE: three refused deletions parked an
                          ;; agent until restart (stabfix-a, 2026-09-27).
                          refusal (when-not (:seon.turn.loop/basis pending-commit)
                                    (:seon.turn.loop/refusal report))
                          ;; THE PROC'S OWN SERIALIZING STATE, and nothing
                          ;; more: how many of this agent's turn writes were
                          ;; refused in a row. A committed write resets it.
                          refusals (if refusal
                                     (inc (long (:seon.turn.loop/write-refusals state 0)))
                                     0)
                          bound (when refusal
                                  (write-refusal-bound
                                   (db/db connection) cluster agent-id))
                          ;; a bound the config cannot supply parks at once:
                          ;; absence is never "no bound"
                          parked? (boolean (and refusal bound
                                                (or (:seon.error/at bound) (>= refusals bound))))
                          bound (if (:seon.error/at bound) refusals bound)
                          ;; A PARKED AGENT NEVER RE-FIRES. The wake is the only
                          ;; thing that made the refused write repeat, so the
                          ;; bound removes exactly it — no retry, no sleep, no
                          ;; tuned backoff. The fault is recorded once, when the
                          ;; park begins; a submission refused while parked is
                          ;; answered the park.
                          park (when parked?
                                 (if (:seon.turn.loop/parked state)
                                   (write-refusal-error agent-id (:seon.turn/id report)
                                                        refusal refusals bound)
                                   (record-write-refusal-fault!
                                    cluster agent-id (:seon.turn/id report)
                                    refusal refusals bound)))
                          _ (when reply
                              (async/offer! reply (cond-> (merge report pending-commit)
                                                    park (assoc :seon.turn.loop/refusal park))))]
               ;; Run closure is an armer wake because first-agent
               ;; supervision is derived from closed-run and root-idle facts.
               ;; The signal is disposable: the armer re-derives the complete
               ;; supervision transition from the current database value.
                      (when (and
                             (= :closed (:seon.turn.loop/outcome report))
                             (:seon.cluster.wake/armer-channel cluster))
                        (async/offer!
                         (:seon.cluster.wake/armer-channel cluster)
                         :seon.agent/wake))
               ;; self-rewake into this agent's OWN mailbox, coalescing on
               ;; its (sliding-buffer 1): it cannot recurse, because the pass
               ;; is only re-entered after this transform returns
                      ;; THE NEXT PASS DERIVES ITS OWN WORK. A pass that
                      ;; turned something rewakes once; the next pass's one
                      ;; derivation finds the rest or reports idle, after this
                      ;; pass's caller was answered (task 62c45543598c).
                      (when-not parked?
                        (async/offer!
                         (:seon.cluster.wake/channel cluster) :seon.agent/wake))
                      ;; THE PASS REPORTS THE RUN IT TURNED, not whatever
                      ;; the database says is held now: the turn may have
                      ;; closed and released custody, and re-deriving here
                      ;; made the ping state disagree with the report in
                      ;; exactly that ordinary case.
                      [(let [run-id (:seon.turn/id report)]
                         (cond-> (dissoc state :seon.turn/id
                                         :seon.turn.loop/write-refusals
                                         :seon.turn.loop/parked
                                         :seon.turn.loop/basis :seon.db/durable)
                           ;; an applied settlement's pending commit, awaited
                           ;; by the next pass if it starts before it lands
                           (:seon.db/durable pending-commit) (merge pending-commit)
                           run-id (assoc :seon.turn/id run-id)
                           (pos? refusals)
                           (assoc :seon.turn.loop/write-refusals refusals)
                           park (assoc :seon.turn.loop/parked park)))
                ;; flow's own report channel: observation, never a dependency
                       {::flow/report [report]}])))
         ;; WHAT ESCAPED IS ANSWERED ON THE REPLY before it closes: its error
         ;; value (whole chain) and, when the throw already carries one, its
         ;; fault receipt. The throw still continues to the proc's fault route.
         (catch Throwable escaped
           (when reply
             (try
               (async/offer!
                reply
                {:seon.agent/id (:seon.agent/id state)
                 :seon.turn.loop/refusal
                 (cond-> (seon.error.refusal/diagnostic
                          {:seon.error/at (Date.)
                           :seon.error/layer :seon.agent/agent-graph
                           :seon.error/operation 'seon.turn/step
                           :seon.error/throwable escaped})
                   (fault/carried-receipt escaped)
                   (assoc :seon.fault/recorded (fault/carried-receipt escaped)))})
               ;; answering never replaces what escaped
               (catch Throwable unanswered (.addSuppressed escaped unanswered))))
           (throw escaped))
         (finally
           ;; An answered reply keeps its value; one never answered (the pass
           ;; escaped or was interrupted) closes, so its caller's await ends now.
           (some-> reply async/close!)
           (if (async/offer! completion :seon.agent/ready)
             (do
               (async/offer! (:seon.agent/cancel backstop) :seon.agent/completed)
               (when-let [backstop-state
                          (:seon.agent/turn-backstop-state cluster)]
                 (compare-and-set! backstop-state backstop nil)))
             (throw
              (ex-info
               "The agent turn could not publish its terminal completion."
               {:seon.agent/id
                (:seon.agent/id state)
                :seon.agent/turn-completion-undeliverable
                (:seon.agent/id state)})))))))
       [state nil])))))
