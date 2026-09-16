(ns seon.turn
  "The per-agent turn proc, work derivation, and writer-owned transitions.

  Open means no closed-tx. Boot closes unfinished work; execution never
  resumes across a JVM restart. The agent graph advances open, call,
  evaluations, and close from database facts and rewakes only for more work."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [parinferish.core :as parinferish]
            [seon.ai :as ai]
            [seon.blob :as blob]
            [seon.cluster.message :as message]
            [seon.cluster.reply :as reply]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.error :as error]
            [seon.flow :as seon.flow]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.plan :as plan]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.reader :as reader]
            [seon.test.accretion :as accretion])
  (:import [java.util Date]
           [java.util.concurrent Executor]))

(schema.edn/load! {})


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
  (let [error (or (:seon.cluster.eval/error evaluation)
                  (:seon.cluster.eval/error form-problem))
        kind (or (:seon.error/kind (:seon.sci.admit/value evaluation))
                 (:seon.error/kind form-problem))]
    (cond-> {:seon.turn/id id
             :seon.cluster.eval/ordinal ordinal}
      (:seon.eval/renderer settlement-evaluation)
      (assoc :seon.eval/renderer (:seon.eval/renderer settlement-evaluation))
      (:seon.eval/shown settlement-evaluation)
      (assoc :seon.eval/shown
             (:seon.eval/shown settlement-evaluation))
      error (assoc :seon.cluster.eval/error error)
      (:seon.cluster.eval/triage-edn evaluation)
      (assoc :seon.cluster.eval/triage-edn
             (:seon.cluster.eval/triage-edn evaluation))
      (:seon.cluster.eval/interrupted-at evaluation)
      (assoc :seon.cluster.eval/interrupted-at
             (:seon.cluster.eval/interrupted-at evaluation))
      kind (assoc :seon.error/kind kind)
      (:seon.cluster.eval/output evaluation)
      (assoc :seon.cluster.eval/output
             (:seon.cluster.eval/output evaluation))
      (seq (:seon.cluster.eval/read-evidence evaluation))
      (assoc :seon.cluster.eval/read-evidence
             (:seon.cluster.eval/read-evidence evaluation))
      (:seon.cluster.eval/read-basis-transaction evaluation)
      (assoc :seon.cluster.eval/read-basis-transaction
             (:seon.cluster.eval/read-basis-transaction evaluation))
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
      ;; A FORM'S OWN `set!` OF *print-length* / *print-level*. Captured
      ;; while sci's binding is still installed (`sci/eval.clj` print-options
      ;; volatile), it is what the response must print the value under.
      (int? (get-in evaluation [:seon.print/options :seon.print/length]))
      (assoc :seon.print/length
             (get-in evaluation [:seon.print/options :seon.print/length]))
      (int? (get-in evaluation [:seon.print/options :seon.print/level]))
      (assoc :seon.print/level
             (get-in evaluation [:seon.print/options :seon.print/level]))
      (:seon.test.accretion/gate-tests settlement-evaluation)
      (assoc :seon.test.accretion/gate-tests
             (mapv (fn [test-symbol] [:seon.test/sym test-symbol])
                   (:seon.test.accretion/gate-tests settlement-evaluation)))
      (some? (:seon.test.accretion/gate-test-count settlement-evaluation))
      (assoc :seon.test.accretion/gate-test-count
             (:seon.test.accretion/gate-test-count settlement-evaluation))
      (some? (:seon.test.accretion/gate-pass-count settlement-evaluation))
      (assoc :seon.test.accretion/gate-pass-count
             (:seon.test.accretion/gate-pass-count settlement-evaluation))
      (some? (:seon.test.accretion/gate-fail-count settlement-evaluation))
      (assoc :seon.test.accretion/gate-fail-count
             (:seon.test.accretion/gate-fail-count settlement-evaluation))
      (:seon.test.accretion/seed settlement-evaluation)
      (assoc :seon.test.accretion/seed
             (:seon.test.accretion/seed settlement-evaluation))
      (some? (:seon.test.accretion/case-count settlement-evaluation))
      (assoc :seon.test.accretion/case-count
             (:seon.test.accretion/case-count settlement-evaluation))
      (some? (:seon.test.accretion/executed-count settlement-evaluation))
      (assoc :seon.test.accretion/executed-count
             (:seon.test.accretion/executed-count settlement-evaluation))
      (:seon.test.accretion/status settlement-evaluation)
      (assoc :seon.test.accretion/status
             (:seon.test.accretion/status settlement-evaluation))
      (:seon.test.accretion/report-blob settlement-evaluation)
      (assoc :seon.test.accretion/report-blob
             (:seon.test.accretion/report-blob settlement-evaluation))
      (:seon.test.accretion/report-size settlement-evaluation)
      (assoc :seon.test.accretion/report-size
             (:seon.test.accretion/report-size settlement-evaluation))
      (:seon.program/row evaluation)
      (assoc :seon.program/row
             (:seon.program/row evaluation))
      (::form-facts evaluation)
      (assoc ::form-facts (::form-facts evaluation))
      value (assoc :my.turn/value value))))

(defn settlement-projection
  "Preserve evaluation evidence and stage an optional test report blob."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]] :seon.turn.loop/evaluation]
    [:tuple :map :map [:vector :seon.blob/staged-write]]]}
  [cluster evaluation]
  ;; Shown text is already projected; settlement preserves its exact bytes.
  (let [receipt evaluation
        receipt
        (if-let [report-edn (:seon.test.accretion/report-edn receipt)]
          (let [staged (blob/stage! (:seon.db/connection cluster) report-edn)]
            (-> receipt
                (dissoc :seon.test.accretion/report-edn)
                (assoc :seon.test.accretion/report-blob
                       (:seon.blob/digest staged)
                       :seon.test.accretion/report-size
                       (long (count report-edn)))
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
  {:malli/schema [:=> [:cat [:map [::closed-tx {:optional true}
                                   [:or ::closed-tx [:map [:db/id :int]]]]]]
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
                  [:or :seon.db/database-value :seon.error/value]]}
  [database id]
  (let [opening-tx
        (db/q '[:find ?tx .
                :in $ ?id
                :where
                [?run :seon.turn/id ?id ?tx]]
              database id)]
    (if opening-tx
      (db/as-of database opening-tx)
      {:seon.error/kind ::missing-opening-datom
       :seon.error/message "The run has no opening datom."
       :seon.error/data {::id id} :seon.turn/missing-opening-datom true})))

;;; ---------------------------------------------------------------------------
;;; Transitions — pure functions OF THE MID-TRANSACTION DATABASE VALUE,
;;; each invoked as [:db.fn/call f request] on the one serial writer.
;;;
;;; Shared contract, every `*-call`:
;;; - reads the current run/agent state from `db` (datahike.api/pull or
;;;   entity over the db value it is handed);
;;; - THROWS ex-info {:seon.error/kind :seon.turn/refused, ...}
;;;   naming the violated rule when the transition is ineligible — the
;;;   writer aborts the whole transaction atomically;
;;; - otherwise returns plain tx-data (the serial writer makes the read
;;;   atomic with the write; no nested CAS is needed or wanted).
;;;
;;; `require-open-run` checks existence and closure inside the serial writer.
;;; ---------------------------------------------------------------------------

(defn- refuse!
  "Abort the whole transaction, naming the rule the request violated."
  [transition rule request]
  (throw (ex-info (str "run transition refused: " (name rule))
                  {:seon.error/kind ::refused
                   ::transition transition
                   ::rule rule
                   ::request request :seon.turn/refused rule})))

(defn- current-run
  "The run's current facts on `db`, or nil when no such run exists.
  The mid-transaction pull IS the eligibility read: a missing lookup ref
  pulls to nil rather than throwing, so absence is an ordinary value."
  [db id]
  (db/pull db '[*] [::id id]))

(defn- require-open-run
  "Read and require an existing open turn inside the serial writer."
  [database operation request]
  (let [turn (current-run database (::id request))]
    (cond
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
(declare close-call plan-call refresh-call
         open-call receipt-start-call receipt-settle-call
         recover-call)



(defn open-for-agent
  "Read the agent's open turn id from its owning ref and absence of closed-tx."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/ref]
                  [:or [:maybe :seon.turn/id] :seon.error/value]]}
  [database agent-ref]
  (let [row (db/pull database [:db/id] agent-ref)]
    (if (:seon.error/kind row)
      row
      (when-let [agent-eid (:db/id row)]
        (db/q '[:find ?id .
                :in $ ?agent
                :where [?runtime :seon.runtime/agent ?agent]
                [?runtime :seon.runtime/turns ?turn]
                [?turn :seon.turn/id ?id]
                (not [?turn :seon.turn/closed-tx])]
              database agent-eid)))))

(defn open-call
  "Open one turn inside the writer; refuse an existing id or open turn.
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
      (some? (current-run db id)) (refuse! `open-call ::run-exists request)

      (some? (open-for-agent db agent-eid))
      (refuse! `open-call ::agent-already-running request)

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
  "Close the open turn in the writer with this transaction's identity."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map [::id ::id]]]
                  :seon.store/transaction-data]}
  [database request]
  (let [turn (require-open-run database `close-call request)
        trigger (get-in turn [::trigger :db/id])
        recipient (when trigger (db/pull database
                                        '[:db/id {:seon.message/inbox [:db/id]}]
                                        trigger))
        answered? (and (db/q '[:find ?turn . :in $ ?turn
                               :where [?turn :seon.turn/id _ ?opened]
                                      [?turn :seon.turn/reply-size _ ?replied]
                                      [(> ?replied ?opened)]] database (:db/id turn))
                       (= (get-in turn [::agent :db/id])
                          (get-in recipient [:seon.message/inbox :db/id])))]
    (cond-> [[:db/add (:db/id turn) ::closed-tx "datomic.tx"]]
      answered? (into [[:db/retract trigger :seon.message/inbox
                        (get-in recipient [:seon.message/inbox :db/id])]
                       [:db/add trigger :seon.message/read-tx "datomic.tx"]]))))

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

;;; ONE (run, ordinal) PAIR NAMES ONE ENTITY. It used to name two — the
;;; frozen form and its receipt — with two `:db.unique/identity`
;;; attributes, and an agent holds only the ordinary string, so
;;; `seon.cluster.message/resolve-about` resolves it against EVERY
;;; installed identity attribute and makes a tie a refusal rather than a
;;; guess. Minting a string for both families therefore made
;;; `my.message/decline` (and any `my.message/send` naming a problem)
;;; refuse `:seon.message/ambiguous-about` for every problem that
;;; ever existed. The families are merged; the ambiguity class is
;;; unwritable, not qualified away.

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

(declare receipt-row current-receipt)

(defn- current-transaction-instant
  "The transaction instant already allocated before a transaction call runs."
  [db]
  (:db/txInstant (db/pull db [:db/txInstant] (inc (db/basis-t db)))))

(defn- source-rows
  "The shared namespace rows and the ordered evaluation entities of one plan.

  ONE ENTITY PER (run, ordinal): the frozen source, its author, its
  namespace and its comment are asserted here with NO terminal fact, and
  settlement accretes the terminal facts onto the same entity. The
  identity fence is the one `receipt-start-call` keeps — an ordinal that
  ever had an evaluation refuses forever, so nothing re-executes."
  [db id run-eid first-ordinal starting-namespace author at sources]
  (let [namespaces (into []
                           (comp (map #(or (:seon.ns/name %)
                                          starting-namespace))
                                 (keep identity)
                                 (distinct)
                                 (map (fn [namespace-name]
                                        {:db/id (str "namespace:"
                                                     namespace-name)
                                         :seon.ns/name namespace-name})))
                           (cons {:seon.ns/name starting-namespace} sources))
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
        requested-starting-namespace (resolve-namespace-name db starting-ns)
        starting-namespace
        (or requested-starting-namespace agent-namespace)
        existing-form-count
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run
                    :where
                    [?form :seon.cluster.eval/run ?run]]
                  db run-eid)
             0))]
    (when (some? (::reply-size run))
      (refuse! `plan-call ::plan-frozen request))
    (when-not (= :call (:seon.turn.work/situation run))
      (refuse! `plan-call ::not-call-situation request))
    (when-not starting-namespace
      (refuse! `plan-call ::starting-namespace-missing request))
    (when (and (= :system author)
               requested-starting-namespace
               (not= requested-starting-namespace agent-namespace))
      (refuse! `plan-call ::starting-namespace-changed request))
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

(defn system-run-call
  "Open a system-authored turn and freeze its sources at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/system-run-request]
                  :seon.store/transaction-data]}
  [database request]
  (let [{agent-id :seon.agent/id
         ::keys [id starting-ns sources trigger]} request
        open-rows (open-call database
                                 (cond-> {::id id ::agent [:seon.agent/id agent-id] ::opened-tx "datomic.tx"}
                                   trigger (assoc ::trigger trigger)))
        opened (first open-rows)
        agent-namespace (db/q '[:find ?name . :in $ ?agent
                                :where [?agent :seon.agent/namespace ?ns]
                                [?ns :seon.ns/name ?name]]
                              database (::agent opened))
        requested-namespace (resolve-namespace-name database starting-ns)
        namespace-name (or requested-namespace agent-namespace)]
    (when-not namespace-name
      (refuse! `system-run-call ::starting-namespace-missing request))
    (when (and requested-namespace (not= requested-namespace agent-namespace))
      (refuse! `system-run-call ::starting-namespace-changed request))
    (into [(merge opened
                  (select-keys request [::reply ::reply-blob ::reply-size])
                  {::starting-ns (str "namespace:" namespace-name)
                   ::reply-size (long (or (::reply-size request) (count (or (::reply request) (str/join "\n" (map :seon.cluster.eval/source sources))))))})]
          (concat (rest open-rows) (source-rows database id (:db/id opened) 0 namespace-name
                       :system (current-transaction-instant database) sources)))))

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
    (into
     [{:db/id (str "namespace:" namespace-name) :seon.ns/name namespace-name}]
     (receipt-start-call
      db
      (cond-> {::id id
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at receipt-at
               :seon.cluster.eval/author :system
               :seon.cluster.eval/source source
               :seon.cluster.eval/ns [:seon.ns/name namespace-name]}
        comment (assoc :seon.cluster.eval/comment comment))))))

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
  [database request]
  (let [{agent-id :seon.agent/id
         run-id ::id
         opened-at ::opened-tx
         starting-ns ::starting-ns
         trigger ::trigger} request
        namespace-name (if (vector? starting-ns)
                         (second starting-ns)
                         starting-ns)
        namespace-tempid (str "namespace:" namespace-name)]
    (into [] cat
          [[{:db/id namespace-tempid :seon.ns/name namespace-name}]
           (open-tx
            (cond-> {::id run-id ::agent [:seon.agent/id agent-id] ::starting-ns [:seon.ns/name namespace-name] :seon.turn.work/situation :generate ::opened-tx "datomic.tx"}
              trigger (assoc ::trigger trigger)))])))

(defn refresh-tx
  "Transaction data refreshing one prior system-authored form."
  {:malli/schema
   [:=> [:cat :seon.cluster.eval/id] :seon.store/transaction-data]}
  [prior-form-id]
  [[:db.fn/call #'refresh-call prior-form-id]])

(defn- refresh-run-id
  [db prior-form-id]
  (id/digest 12 [::refresh prior-form-id (db/basis-t db)]))

(defn refresh-call
  "Append one ordinary system run from a prior refreshable evaluation."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.eval/id]
    :seon.store/transaction-data]}
  [db prior-form-id]
  (let [request {:seon.cluster.eval/id prior-form-id}
        ;; ONE ENTITY: the frozen source and its terminal facts are the same
        ;; evaluation, so this reads it once instead of joining a twin.
        prior
        (db/pull db
                 '[* {:seon.cluster.eval/run
                      [:db/id :seon.turn/id
                       {:seon.turn/agent
                        [:db/id :seon.agent/id]}]}
                   {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
                   {:seon.cluster.eval/read-evidence [*]}]
                 [:seon.cluster.eval/id prior-form-id])]
    (when-not (:db/id prior)
      (refuse! `refresh-call ::no-such-form request))
    (when-not (= :system (:seon.cluster.eval/author prior))
      (refuse! `refresh-call ::refresh-agent-authored request))
    (let [prior-run (:seon.cluster.eval/run prior)
          successor
          (db/q '[:find ?successor .
                  :in $ ?prior
                  :where
                  [?successor :seon.cluster.eval/refreshes ?prior]]
                db (:db/id prior))]
      (when-not (terminal? prior)
        (refuse! `refresh-call ::refresh-receipt-not-terminal request))
      (when-not (seq (:seon.cluster.eval/read-evidence prior))
        (refuse! `refresh-call ::refresh-read-evidence-missing request))
      (when successor
        (refuse! `refresh-call ::refresh-successor-exists request))
      (let [run-id (refresh-run-id db prior-form-id)
            run-tempid (str "seon.turn/" run-id)
            evaluation-id (receipt-identity run-id 0)
            namespace (:seon.cluster.eval/ns prior)
            source (:seon.cluster.eval/source prior)
            opened-at (current-transaction-instant db)
            open-rows
            (open-call db
                       {::id run-id ::agent (:db/id (::agent prior-run)) ::opened-tx "datomic.tx"})
            evaluation {:db/id evaluation-id
                        :seon.cluster.eval/id evaluation-id
                        :seon.cluster.eval/run run-tempid
                        :seon.cluster.eval/ordinal 0
                        :seon.cluster.eval/at opened-at
                        :seon.cluster.eval/author :system
                        :seon.cluster.eval/source source
                        :seon.cluster.eval/ns (:db/id namespace)
                        :seon.cluster.eval/refreshes (:db/id prior)}]
        (into open-rows
              [[:db/add run-tempid ::reply-size (long (count source))]
               [:db/add run-tempid ::starting-ns (:db/id namespace)]
               evaluation])))))

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

(defn- settlement-form
  [database request]
  (let [form (db/pull database
                      [:db/id :seon.cluster.eval/source :seon.cluster.eval/ns]
                      [:seon.cluster.eval/id
                       (receipt-identity (::id request)
                                         (:seon.cluster.eval/ordinal request))])]
    (when (:db/id form)
      (update form :seon.cluster.eval/ns :db/id))))

(defn- analyze-settlement
  [database request]
  (if-let [form (settlement-form database request)]
    (let [[form-facts program-row]
          (seon.fn/analyze-form
           database
           (:seon.cluster.eval/source form)
           (:seon.cluster.eval/ns form)
           (:seon.program/row request))
          subject (:seon.test/subject program-row)
          subject-present?
          (or (nil? subject)
              (:db/id (db/pull database [:db/id] subject)))
          form-facts (cond-> form-facts
                       (not subject-present?) (dissoc :seon.test/subject))
          required-namespace-rows
          (into []
                (comp
                 (map second)
                 (remove (fn [namespace-name]
                           (:db/id (db/pull database [:db/id]
                                            [:seon.ns/name namespace-name]))))
                 (map (fn [namespace-name]
                        {:seon.ns/name namespace-name})))
                (:seon.ns/requires program-row))]
      (cond-> (assoc request ::form-facts
                     (assoc form-facts :db/id (:db/id form)))
        (seq required-namespace-rows)
        (assoc ::required-namespace-rows required-namespace-rows)
        program-row (assoc :seon.program/row program-row)))
    request))

(defn- receipt-settle-tx*
  "Build receipt settlement transaction data without crossing a contract seam."
  [request]
  (let [required-namespace-rows (::required-namespace-rows request)
        request (dissoc request ::required-namespace-rows)]
    (into (vec required-namespace-rows)
          [[:db.fn/call #'receipt-settle-call request]])))

(defn receipt-settle-tx
  "Transaction data settling one running receipt exactly once.
  Settling IS asserting terminal facts: `result-edn`, `error`, and/or
  `interrupted-at` — at least one, and there is no status label."
  {:malli/schema
   [:function
    [:=> [:cat :seon.cluster.eval/settle-request]
     :seon.store/transaction-data]
    [:=> [:cat :seon.db/database-value
          :seon.cluster.eval/settle-request]
     :seon.store/transaction-data]]}
  ([request]
   (receipt-settle-tx* request))
  ([database request]
   (receipt-settle-tx*
    (if database
      (analyze-settlement database request)
      request))))

(defn receipt-settle-batch-call
  "Settle independent evaluations together, retaining each terminal fence."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:vector :seon.cluster.eval/settle-request]] :seon.store/transaction-data]}
  [database requests]
  (when (some :seon.program/row requests)
    (refuse! `receipt-settle-batch-call ::ordered-declarations-required
             {::evaluations requests}))
  (let [identities (mapv (juxt ::id :seon.cluster.eval/ordinal) requests)]
    (when-not (= (count identities) (count (set identities)))
      (refuse! `receipt-settle-batch-call ::receipt-terminal
               {::evaluations requests}))
    (into [] (mapcat #(receipt-settle-call database %)) requests)))

(defn receipt-settle-batch-tx
  "Transaction data settling an ordered turn batch in one commit.

  Ordinary evaluations share one writer call. Program declarations retain
  ordered calls: a later declaration must see earlier declarations in the
  same transaction. Requests carry the turn's already analyzed rows."
  {:malli/schema
   [:=> [:cat [:vector :seon.cluster.eval/settle-request]] :seon.store/transaction-data]}
  [requests]
  (if (some :seon.program/row requests)
    (into [] (mapcat receipt-settle-tx) requests)
    [[:db.fn/call #'receipt-settle-batch-call requests]]))

(defn- affected-schema-attributes
  "Database attributes derived by the affected schema forms."
  [projection affected]
  (set
   (schema.form/database-attributes
    (select-keys (:seon.schema.projection/forms projection) affected))))

(defn- current-schema-data-attributes
  "Installed affected database attributes carrying current datoms in `db`."
  [db projection schema-keys]
  (let [affected
        (schema/dependent-schema-keys projection schema-keys)]
    (into []
          (comp
           (filter #(contains? (:schema db) %))
           (filter #(seq (db/datoms db :aevt %))))
          (sort (affected-schema-attributes projection affected)))))

(defn- assert-schema-data-unused!
  "Refuse schema change while affected attributes carry current data."
  [db projection schema-keys]
  (let [attributes
        (current-schema-data-attributes db projection schema-keys)]
    (when (seq attributes)
      (throw
       (ex-info
        (str "Schema change refused: current data uses " attributes ".")
    {:seon.schema/error :seon.schema/current-data-blocks-change
     :seon.schema/current-data-blocks-change true
     :seon.schema/keys schema-keys
         :seon.schema/data-attributes attributes
         :seon.error/kind :user-input})))))

(defn- schema-attribute-change-tx
  "Deterministic Datahike diff between complete schema projections."
  [db current-projection candidate-projection]
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
        changed-attributes
        (into #{}
              (filter #(not= (get current-declarations %)
                             (get candidate-declarations %)))
              (into (set (keys current-declarations))
                    (keys candidate-declarations)))
        retracted
        (into []
              (comp
               (filter #(contains? current-declarations %))
               (filter #(contains? (:schema db) %))
               (map (fn [attribute]
                      [:db.fn/retractEntity attribute])))
              (sort changed-attributes))]
    (into retracted
          (keep candidate-declarations)
          (sort changed-attributes))))

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
  [db attribute value]
  (if (cardinality-many? db attribute)
    (into #{} (map #(declared-one db attribute %)) (or value []))
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
  ;; Contract AST and arity rows are deterministic projections of the
  ;; declaration's `:seon.fn/spec`. Their component entity ids are database
  ;; mechanics, not declared content.
  (when-some [canonical (program/canonical-row value)]
    (declared-map db (dissoc canonical :seon.fn/arities :seon.fn/ast))))

(defn- declaration-written-by-run?
  "True when the declaration identified by `identity-attribute`/`identity-value`
  and one receipt of `run-id` were asserted by the same terminal transaction.

  A run's own write is never a concurrent change, whichever declaration family
  it belongs to. The question is asked of the transaction rather than of one
  family's content attribute, so there is no per-family attribute to forget."
  [db identity-attribute identity-value run-id]
  (boolean
   (db/q '[:find ?receipt .
           :in $ ?identity-attribute ?identity-value ?run-id
           :where
           [?declaration ?identity-attribute ?identity-value]
           [?declaration _ _ ?tx true]
           [?receipt :seon.eval/shown _ ?tx]
           [?receipt :seon.cluster.eval/run ?run]
           [?run :seon.turn/id ?run-id]]
         (db/history db) identity-attribute identity-value run-id)))

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
      (when (:seon.error/kind opening-database)
        (refuse! `receipt-settle-call ::run-opening-basis-unreadable request))
      (let [opening-existing
            (db/pull opening-database '[*] [identity-attribute identity-value])]
        (and (not= (declared-content db opening-existing)
                   (declared-content db existing))
             (not (declaration-written-by-run?
                   db identity-attribute identity-value run-id)))))
    false))

(def ^:private program-relation-attributes
  [:seon.fn/calls :seon.fn/keywords
   :seon.test/subject :seon.test/pending-subject])

(defn- relation-assertions
  [entity row]
  (into []
        (concat
         (map (fn [target] [:db/add entity :seon.fn/calls target])
              (:seon.fn/calls row))
         (map (fn [used] [:db/add entity :seon.fn/keywords used])
              (:seon.fn/keywords row))
         (when-let [subject (:seon.test/subject row)]
           [[:db/add entity :seon.test/subject subject]])
         (when-let [pending-subject (:seon.test/pending-subject row)]
           [[:db/add entity :seon.test/pending-subject pending-subject]]))))

(defn- pending-subject-resolution-tx
  [db identity identity-value existing]
  (when (= :seon.fn/sym identity)
    (let [target (or (:db/id existing) (str "sym:" identity-value))]
      (into []
            (mapcat (fn [test-eid]
                      [[:db/retract test-eid :seon.test/pending-subject
                        identity-value]
                       [:db/add test-eid :seon.test/subject target]]))
            (db/q '[:find [?test ...]
                    :in $ ?subject
                    :where
                    [?test :seon.test/pending-subject ?subject]]
                  db identity-value)))))

(defn- row-tx
  "Validate and exact-upsert one reader-produced durable declaration."
  [db request row]
  (if-let [deleted-identities (:seon.program/delete-identities row)]
    (let [schema-keys
          (into #{}
                (keep (fn [[identity-attribute identity-value]]
                        (when (= :seon.schema/key identity-attribute)
                          identity-value)))
                deleted-identities)
          current-projection
          (when (seq schema-keys) (or (db/carried-projection db)
                  (throw (ex-info "Declaration database has no carried projection"
                                  (db/projection-fallback 'seon.turn/row-tx)))))
          candidate-projection
          (reduce schema/projection-without-schema
                  current-projection
                  (sort schema-keys))
          _ (when (seq schema-keys)
              (assert-schema-data-unused!
               db current-projection schema-keys))
          schema-tx
          (if (seq schema-keys)
            (schema-attribute-change-tx
             db current-projection candidate-projection)
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
            (mapcat
             (fn [[identity-attribute identity-value declaration]]
               (map (fn [attribute]
                      [:db.fn/retractAttribute (:db/id declaration) attribute])
                    (program/changed-attributes
                     declaration {identity-attribute identity-value}))))
            declarations))
    (let [row (or (program/declaration-row row :all :agent)
                  (refuse! `receipt-settle-call
                           ::row-not-admitted request))
          [identity identity-value] (program/row-identity row)
          namespace-ref (or (:seon.fn/ns row)
                            (:seon.test/ns row))
          existing (when identity (db/pull db '[*] [identity identity-value]))
          subject-symbol
          (or (second (:seon.test/subject row))
              (:seon.test/pending-subject row))
          row
          (if (and (= :seon.test/sym identity) subject-symbol)
            (if (:db/id (db/pull db [:db/id]
                                 [:seon.fn/sym subject-symbol]))
              (-> row
                  (dissoc :seon.test/pending-subject)
                  (assoc :seon.test/subject
                         [:seon.fn/sym subject-symbol]))
              (-> row
                  (dissoc :seon.test/subject)
                  (assoc :seon.test/pending-subject subject-symbol)))
            row)]
      (when (and namespace-ref
                 (not (:db/id (db/pull db [:db/id] namespace-ref))))
        (refuse! `receipt-settle-call ::program-namespace-missing request))
      (let [current-projection
            (when (or (= :seon.schema/key identity)
                      (and (= :seon.fn/sym identity)
                           (:seon.fn/spec row)))
              ;; Earlier declarations in this same transaction are already
              ;; facts in db, but are absent from its entering projection.
              ;; Validate against the writer's current value, reusing compiled
              ;; declarations through the schema owner's existing derivation.
              (schema/projection-from-database
               db
               (or (db/carried-projection db)
                   (throw (ex-info "Declaration database has no carried projection"
                                   (db/projection-fallback 'seon.turn/row-tx))))))
            schema-redefinition?
            (and (= identity :seon.schema/key)
                 existing
                 (not= (:seon.schema/form existing)
                       (:seon.schema/form row)))
            ;; ONE decision path owns "may this declaration change". The
            ;; concurrency question — did the installed row diverge from the
            ;; basis this run opened on — is measured identically for every
            ;; declaration family. What differs is only what a legal change
            ;; then costs, and for a schema key that cost is answered by the
            ;; usage guard, which names the attributes current data blocks on.
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
            _ (when schema-redefinition?
                (assert-schema-data-unused!
                 db current-projection #{identity-value}))
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
               current-projection (symbol identity-value)
               (edn/read-string (:seon.fn/spec row))
               {:seon.schema.admission/source :agent}))

              nil)
            relation-row (select-keys row program-relation-attributes)
            base-row (apply dissoc row program-relation-attributes)
            schema-declarations
            (if (= identity :seon.schema/key)
              (if schema-redefinition?
                (schema-attribute-change-tx
                 db current-projection candidate-projection)
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
        (into schema-declarations
              (concat
               (cond
                 (nil? existing)
                 [(assoc base-row :db/id
                         (str (name identity) ":" identity-value))]

                 (= (declared-content db existing) (declared-content db row))
                 []

                 :else
                 (program/exact-replacement-tx existing base-row))
               (relation-assertions [identity identity-value]
                                    relation-row)
               (pending-subject-resolution-tx
                db identity identity-value existing)))))))

(def ^:private receipt-terminal-attributes
  [:seon.eval/shown
   :seon.eval/renderer


   :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn
   :seon.cluster.eval/interrupted-at
   :seon.error/kind
   :seon.cluster.eval/output
   :seon.cluster.eval/read-basis-transaction
   :seon.cluster.eval/ns
   :seon.sci.eval/ending-ns
   :seon.eval/duration-ms
   :seon.print/length
   :seon.print/level
   :seon.test.accretion/gate-test-count
   :seon.test.accretion/gate-pass-count
   :seon.test.accretion/gate-fail-count
   :seon.test.accretion/seed
   :seon.test.accretion/case-count
   :seon.test.accretion/executed-count
   :seon.test.accretion/status
   :seon.test.accretion/report-blob
   :seon.test.accretion/report-size])

(defn- receipt-gate-test-assertions
  [receipt request]
  (mapv (fn [test-ref]
          [:db/add (:db/id receipt) :seon.test.accretion/gate-tests test-ref])
        (:seon.test.accretion/gate-tests request)))

(defn- receipt-terminal-assertions
  "Terminal assertions present in `request`, targeting `receipt`."
  [receipt request]
  (into
   []
   (keep (fn [attribute]
           (when-some [value (get request attribute)]
             [:db/add (:db/id receipt) attribute value])))
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

(defn- stored-record-content
  [database id]
  (let [run (current-run database id)
        evaluations
        (db/q '[:find [(pull ?evaluation
                            [* {:seon.cluster.eval/ns [:seon.ns/name]}
                             {:seon.cluster.eval/read-evidence [*]}]) ...]
                :in $ ?run
                :where [?evaluation :seon.cluster.eval/run ?run]]
              database (:db/id run))]
    {::recorded-run
     (-> (select-keys run [::id ::agent ::starting-ns ::opened-tx ::closed-tx
                           ::reply ::reply-blob ::reply-size])
         (update ::agent :db/id)
         (update ::starting-ns #(resolve-namespace-name database (:db/id %))))
     ::evaluations
     (mapv (fn [evaluation]
             (cond-> (recorded-evaluation evaluation)
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
        expected {::recorded-run run
                  ::evaluations (mapv recorded-evaluation prepared-evaluations)}
        namespace-rows
        (into []
              (comp (map #(or (:seon.ns/name %) starting-namespace))
                    (keep identity)
                    (distinct)
                    (map (fn [namespace-name]
                           {:db/id (str "namespace:" namespace-name)
                            :seon.ns/name namespace-name})))
              (cons {:seon.ns/name starting-namespace} sources))]
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
            [namespace-rows
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
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]
          [:seon.cluster.eval/read-evidence {:optional true}
           :seon.cluster.eval/read-evidence]
          [:seon.cluster.eval/read-basis-transaction {:optional true}
           :seon.cluster.eval/read-basis-transaction]
          [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns]
          [:seon.sci.eval/ending-ns {:optional true}
           :seon.sci.eval/ending-ns]
          [:seon.test.accretion/gate-tests {:optional true}
           :seon.test.accretion/gate-tests]
          [:seon.test.accretion/gate-test-count {:optional true}
           :seon.test.accretion/gate-test-count]
          [:seon.test.accretion/gate-pass-count {:optional true}
           :seon.test.accretion/gate-pass-count]
          [:seon.test.accretion/gate-fail-count {:optional true}
           :seon.test.accretion/gate-fail-count]
          [:seon.test.accretion/seed {:optional true}
           :seon.test.accretion/seed]
          [:seon.test.accretion/case-count {:optional true}
           :seon.test.accretion/case-count]
          [:seon.test.accretion/executed-count {:optional true}
           :seon.test.accretion/executed-count]
          [:seon.test.accretion/status {:optional true}
           :seon.test.accretion/status]
          [:seon.test.accretion/report-blob {:optional true}
           :seon.test.accretion/report-blob]
          [:seon.test.accretion/report-size {:optional true}
           :seon.test.accretion/report-size]
          [:seon.program/row {:optional true}
           :seon.program/row]]]
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
    (let [program-row (:seon.program/row request)]
      (into [] cat
            [(if program-row (row-tx db request program-row) [])
             (relation-assertions (:db/id (::form-facts request))
                                  (::form-facts request))
             (receipt-read-evidence-tx receipt request)
             (receipt-gate-test-assertions receipt request)
             (receipt-terminal-assertions receipt request)]))))

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
  [receipt]
  (some-> (:seon.eval/shown receipt)
          (#(try (edn/read-string %)
                 (catch Throwable _
                   nil)))))

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
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        id (get unit ::id)]
    (when id
      (let [opened (:db/txInstant (db/pull db [:db/txInstant]
                                                     (let [ref (get unit ::opened-tx)]
                                                       (if (map? ref) (:db/id ref) ref))))
            receipts (when db (run-receipts db (:db/id unit)))
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

(defn- declared-sources [handle database agent-id namespace-name]
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
                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)
                :seon.config/on-core-error (:seon.config/on-core-error handle)})]
    (or (some (fn [unit]
                (let [failure (:seon.error/value unit)]
                  (when-not (:seon.render.walk/elided failure) failure))) units)
        (let [sources (reduce
         (fn [sources unit]
           (let [lookup (:seon.render.walk/lookup unit)
                 call-id [:seon.render/ai lookup (:seon.render/distance unit)]
                 text (:seon.render.call/source (get @calls call-id))]
             (if text
               (let [parsed ((requiring-resolve 'seon.turn/planned-sources)
                             text namespace-name
                             (get-in handle [:seon.sci.admit/caps
                                             :seon.config.eval.result/max-source]))]
                 (if (:seon.error/kind parsed)
                   (reduced parsed)
                   (into sources
                         (map #(assoc % :seon.render.walk/lookup lookup)) parsed)))
               sources)))
         [] units)]
          (if (:seon.error/kind sources) sources
              {:seon.turn/forms sources :seon.render.walk/units units})))))

(defn- latest-evaluations [database agent-id]
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
                           {:seon.cluster.eval/read-evidence [*]}])
                    :in $ ?id
                    :where [?agent :seon.agent/id ?id]
                    [?agent :seon.agent/runtime ?runtime]
                    [?runtime :seon.runtime/turns ?turn]
                    [?turn :seon.turn/id _ ?t]
                    [?evaluation :seon.cluster.eval/run ?turn]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                    [?evaluation :seon.cluster.eval/source]]
                  database agent-id))))

(defn- read-only-evaluation? [database evaluation]
  (let [events (source-events evaluation)
        evaluation-id (:db/id evaluation)
        run-id (get-in evaluation [:seon.cluster.eval/run :db/id])]
    (and (nil? (:seon.cluster.eval/error evaluation))
         (seq (:seon.cluster.eval/read-evidence evaluation))
         (vector? events)
         (not-any? #(program/declaration-row % :all :agent) events)
         (nil? (db/q '[:find ?transaction . :in $ ?evaluation
                        :where [?transaction :seon.db/receipt ?evaluation]]
                      database evaluation-id))
         (nil? (db/q '[:find ?effect . :in $ ?turn ?ordinal
                        :where [?effect :seon.effect/run ?turn]
                        [?effect :seon.effect/form-ordinal ?ordinal]]
                      database run-id (:seon.cluster.eval/ordinal evaluation))))))

(declare generated-read-fault evaluate-sources preview-sources)

(defn- system-plan [database declared latest]
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
                           (db/read-evidence-current? database evidence) :unchanged
                           :else :changed)]
              (cond-> (assoc (select-keys source
                                         [:seon.cluster.eval/source
                                          :seon.cluster.eval/comment
                                          :seon.ns/name :seon.render.walk/lookup])
                             :seon.turn/status status)
                basis (assoc :seon.cluster.eval/read-basis-transaction basis)
                (= status :changed)
                (assoc :seon.turn/changes
                       (db/read-evidence-changes database evidence basis)))))))
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
                                       (nil? (generated-read-fault database % %)))
                                 (sort-by (juxt :seon.turn/basis-t
                                                :seon.cluster.eval/ordinal)
                                          (vals latest))))))))

(defn- generated-read-fault [database source evaluation]
  (let [inert (set (db/q '[:find [?attribute ...]
                          :where [?schema :seon.schema/key ?attribute]
                          [?schema :seon.wake/context-inert true]]
                        database))
        evidence (:seon.cluster.eval/read-evidence evaluation)
        offending (into (sorted-set)
                        (mapcat (fn [read]
                                  (let [position (:seon.db/source-argument-position read)
                                        source (some #(when (= position (:datahike.query.source/argument-position %)) %)
                                                     (get-in read [:datahike.read/dependency-plan
                                                                   :datahike.query.dependency/sources]))
                                        patterns (:seon.db/read-index-patterns source)
                                        ;; Freshness uses these complete patterns before
                                        ;; the conservative attribute revision as well.
                                        attributes (if (and patterns (every? :seon.db/pattern-attribute patterns))
                                                     (map :seon.db/pattern-attribute patterns)
                                                     (get-in read [:datahike.read/revision
                                                                   :datahike.read/attributes]))]
                                    (if (= :all attributes)
                                      inert
                                      (filter inert attributes)))))
                        evidence)]
    (when (seq offending)
      (error/diagnostic
       {:seon.error/kind ::generated-read-depends-on-turns
        :seon.error/message "A generated context read depends on the agent's own turn-taking."
        :seon.error/diagnostic-layer :seon.turn
        :seon.error/diagnostic-operation `system-turn
        :seon.error/diagnostic-member :seon.cluster.eval/source
        :seon.error/diagnostic-expected :seon.wake/context-inert
        :seon.error/diagnostic-offending (:seon.cluster.eval/source source)
        :seon.error/diagnostic-cause ::generated-read-depends-on-turns
        :seon.error/diagnostic-evidence
        {:datahike.read/attributes offending}}))))

(defn system-turn
  "Project the declared opening and every distinct retained read form.
  Unchanged reads contribute no evaluation. With write? true, save the exact
  evaluated sources as one closed system turn with no provider attempt."
  {:malli/schema [:=> [:cat :seon.turn/system-request]
                  [:or :seon.turn/system-result :seon.error/value]]}
  [{handle :seon.turn.loop/cluster
    agent-id :seon.agent/id
    write? :seon.turn/write?}]
  (let [connection (:seon.db/connection handle)
        database (db/db connection)
        namespace-name ((requiring-resolve 'seon.sci.eval/agent-namespace) database agent-id)
        declared (when namespace-name
                   (declared-sources handle database agent-id namespace-name))]
    (cond
      (nil? namespace-name)
      (error/diagnostic
       {:seon.error/kind ::agent-namespace-missing
        :seon.error/message "The system turn requires the agent's assigned namespace."
        :seon.error/diagnostic-layer :seon.turn
        :seon.error/diagnostic-operation `system-turn
        :seon.error/diagnostic-member :seon.agent/id
        :seon.error/diagnostic-expected :seon.agent/id
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause ::agent-namespace-missing
        :seon.error/diagnostic-evidence {}})

      (:seon.error/kind declared) declared

      :else
      (let [latest (latest-evaluations database agent-id)
            plan (system-plan database (:seon.turn/forms declared) latest)
            selected (filterv #(not= :unchanged (:seon.turn/status %)) plan)
            turn-id (when (and write? (seq selected))
                      (next-id database (:seon.cluster/name handle) agent-id))
            agent-ctx (when turn-id
                        ((requiring-resolve 'seon.cluster.agent/acquire-context!)
                         handle agent-id))
            previews (second
                      (reduce
                       (fn [[ordinal previews] source]
                         (let [preview (if turn-id
                          {:seon.turn.loop/evaluated-sources
                           (evaluate-sources
                            {:seon.turn.loop/cluster handle
                             :seon.db/db database
                             :seon.sci.eval/ctx agent-ctx
                             :seon.agent/id agent-id
                             :seon.turn/id turn-id
                             :seon.turn/write? true
                             :seon.cluster.eval/ordinal ordinal
                             :seon.ns/name (:seon.ns/name source)
                             :seon.cluster.reply/sources [source]})}
                          (preview-sources
                           {:seon.turn.loop/cluster handle
                            :seon.db/db database
                            :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                            :seon.agent/id agent-id
                            :seon.ns/name (:seon.ns/name source)
                            :seon.cluster.reply/text (:seon.cluster.eval/source source)
                            :seon.sci.admit/caps (:seon.sci.admit/caps handle)}))
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
        (or (some #(when (:seon.error/kind %) %) previews)
            (some identity
                  (map (fn [source preview]
                         (some #(generated-read-fault database source
                                                      (:seon.sci.eval/evaluation %))
                               (:seon.turn.loop/evaluated-sources preview)))
                       selected previews))
            (let [silent (filterv #(= :unchanged (:seon.turn/status (second %)))
                                  (mapv vector selected previews))
                  refresh-tx
                  (into [] cat
                        (map (fn [[source preview]]
                               (let [previous (get latest (source-key source))
                                     evaluation (get-in preview [:seon.turn.loop/evaluated-sources 0
                                                                 :seon.sci.eval/evaluation])]
                                 (concat
                                  (map (fn [evidence] [:db.fn/retractEntity (:db/id evidence)])
                                       (:seon.cluster.eval/read-evidence previous))
                                  [[:db/add (:db/id previous) :seon.cluster.eval/read-basis-transaction
                                    (:seon.cluster.eval/read-basis-transaction evaluation)]]
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
                          (if-let [previous (get latest (source-key source))]
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
                                 (record-evaluated-tx
                                  {:seon.turn.loop/cluster handle :seon.db/db database :seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/reply (str/join "\n" (map :seon.cluster.eval/source selected)) :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.turn.loop/evaluated-sources evaluated}))
                      report (blob/with-publication!
                              connection (vec (:seon.blob/staged-writes prepared))
                              #(db/transact!
                                connection
                                [[:db.fn/call
                                  (fn [current]
                                    ;; Compaction or another system pass may
                                    ;; have changed history during evaluation.
                                    ;; The writer admits this append only against
                                    ;; the history from which it was derived.
                                    (if (= latest (latest-evaluations current agent-id))
                                      (conj (into refresh-tx (:seon.db/tx-data prepared))
                                            [:db.fn/call #'plan/settle-call agent-id])
                                      []))]]))]
                  (if (:seon.error/kind report) report
                      (if (some #(and (= :seon.turn/id (:a %))
                                      (= turn-id (:v %))) (:tx-data report))
                        (do
                          (doseq [item evaluated
                                  :let [evaluation (:seon.sci.eval/evaluation item)]]
                            ((requiring-resolve 'seon.sci.eval/bind-result!)
                             agent-ctx (:seon.repl/handle evaluation)
                             (:seon.sci.admit/value evaluation)))
                          (assoc result :seon.turn/id turn-id))
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
                  (error/diagnostic
                   {:seon.error/kind ::compaction-refused
                    :seon.error/message message
                    :seon.error/diagnostic-layer :seon.turn
                    :seon.error/diagnostic-operation `compact!
                    :seon.error/diagnostic-member :seon.agent/id
                    :seon.error/diagnostic-expected :seon.agent/id
                    :seon.error/diagnostic-offending agent-id
                    :seon.error/diagnostic-cause ::compaction-refused
                    :seon.error/diagnostic-evidence
                    (cond-> {} open-id (assoc :seon.turn/id open-id))})))))
    (mapv (fn [evaluation] [:db.fn/retractEntity evaluation])
          (db/q '[:find [?evaluation ...] :in $ ?agent
                  :where [?turn :seon.turn/agent ?agent]
                  [?evaluation :seon.cluster.eval/run ?turn]]
                database agent-eid))))

(defn compact!
  "Retract an idle agent's evaluations; the next system walk is fresh."
  {:malli/schema [:=> [:cat :seon.turn/compaction-request]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id}]
  (db/transact! connection [[:db.fn/call #'compact-call agent-id]]))

(defn virtual-turn!
  "Submit a fixture reply to the ordinary agent proc, without a provider.
  Returns its durable turn identity; completion is observed in its facts."
  {:malli/schema [:=> [:cat :seon.turn/virtual-request]
                  [:or :seon.agent/source-submission-result
                   :seon.error/value]]}
  [request]
  ((requiring-resolve 'seon.cluster.agent/submit-source!)
   (update request :seon.cluster.reply/text #(or % ""))))



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

(defn unbound-value?
  "True when a live result contains SCI's unbound value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "SCI admission inspects arbitrary result values for nested unbound markers, including scalars and nil; no candidate shape can be required before this inspection."
                         :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (boolean
   (some (fn [node]
           (instance? sci.impl.vars.SciUnbound node))
         (tree-seq coll? seq value))))

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
  "True when a terminal receipt is red: error, interruption, or unbound."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean
   (or (:seon.cluster.eval/error receipt)
       (:seon.cluster.eval/interrupted-at receipt)
       (unbound-value? (receipt-value receipt)))))

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
                     [?assignment :seon.message/about ?problem]
                     [?assignment :seon.message/from ?author]
                     [?assignment :seon.message/to ?owner]]
                   db evaluation-eid author-eid owner-eid)))
        declination?
        (boolean
         (and assignment?
              (db/q '[:find ?declination .
                     :in $ ?problem ?author ?owner
                     :where
                     [?declination :seon.message/about ?problem]
                     [?declination :seon.message/from ?owner]
                     [?declination :seon.message/to ?author]
                     [?declination :my.message/reason _]]
                   db evaluation-eid author-eid owner-eid)))]
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
  "The entity id of `agent-id` on `db`, or nil.
  The one lookup the wake seeks need: every listened attribute is a ref
  whose value IS this entity, so the index seek is (attribute, value)."
  [db agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.agent/id ?agent-id]]
        db agent-id))

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

(defn episode-runs
  "The agent's ordinary turns taken since its latest outside wake.

  System turns have no provider attempt and freeze their plan in the identity
  transaction; they do not consume this bound. Ordinary virtual turns open
  first and freeze a reply later. Provider attempts always consume the bound.

  DERIVED FROM `:t` AND NOTHING ELSE. Datahike stamps every datom with
  its transaction, so a turn's own identity datom carries the basis it
  projected from and a wake carries the moment it arrived. The count is
  the turns whose own `:t` is at or after the anchor `outside-wake-t`
  returns — no stored counter, no episode entity, and no reset code,
  because an outside wake arriving IS the reset.

  Measured at 0.115 ms against 0.134 ms for the run-trigger derivation
  it replaces, same answer (prototype claim 4). The anchor moved: this
  refills the bound the moment an outside wake ARRIVES, where the trigger
  derivation refilled when one was ANSWERED."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  :seon.turn.work/episode-runs]}
  [db agent-id]
  (or (db/q '[:find (count ?run) .
              :in $ ?agent-id ?since
              :where
              [?agent :seon.agent/id ?agent-id]
              [?run :seon.turn/agent ?agent]
              [?run :seon.turn/id _ ?tx]
              [(>= ?tx ?since)]
              (or-join [?run ?tx]
                [?run :seon.turn/attempts _]
                (not [?run :seon.turn/reply-size _ ?tx]))]
            db agent-id (outside-wake-t db agent-id))
      0))

(defn- max-episode-runs
  "Read the agent override, otherwise the branch's config singleton."
  [database agent-id]
  (or (:seon.config.run/max-episode-runs (ai/agent-overlay database agent-id))
      (db/q '[:find ?value .
              :where [?config :seon.config/cluster _]
                     [?config :seon.config.run/max-episode-runs ?value]]
            database)))

(defn turns-left
  "The remaining turns under the same session bound that admits a turn."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id] :my.agent/turns-left]}
  [database agent-id]
  (long (max 0 (- (or (max-episode-runs database agent-id) 0)
                  (episode-runs database agent-id)))))

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
  [db agent-id]
  (let [limit (max-episode-runs db agent-id)]
    (or (nil? limit)
        (some? (wake/declarations-refusal db))
        (>= (episode-runs db agent-id) limit)
        ;; A terminal provider refusal leaves the wake unanswered, but
        ;; cannot itself authorize another attempt. A new outside wake
        ;; moves this basis and permits another turn.
        (some? (db/q '[:find ?turn .
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
                     db agent-id (outside-wake-t db agent-id))))))

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
                  [:maybe :seon.turn.work/next]]}
  [db {:keys [:seon.agent/id]}]
  (let [agent-id id
        run (agent-run db agent-id)]
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

      :else
      ;; ONE TURN FOR EVERY UNANSWERED WAKE. The turn's own transaction
      ;; answers all of them, so nothing is selected and nothing is
      ;; claimed; the wakes are named only so a consumer can say what it
      ;; is about to answer.
      (when-not (opening-deferred? db agent-id)
        (let [wakes (unanswered-wakes db agent-id {})]
          (when (or (seq wakes) (continuing-reply? db agent-id))
            (cond->
             {:seon.turn.work/situation :open
              :seon.agent/id agent-id}
              (some :seon.message/id wakes)
              (assoc :seon.message/id
                     (some :seon.message/id wakes)))))))))

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
  (`latest-answering-turn-t`) has `:t` at or after it — no reference
  from turn to wake, no claim, no per-wake write, and no way for a turn
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
       (filter #(= :seon.message/inbox (:seon.wake/attribute %)))
       (sort-by (juxt :seon.wake/t :db/id))
       (mapv #(select-keys % [:seon.message/id]))))



;;; ---------------------------------------------------------------------------
;;; Reply delimiter repair — one bounded pass per isolated failure span
;;; ---------------------------------------------------------------------------

(defn- read-source
  [source namespace-name max-source]
  (reader/read
   {:seon.sci.reader/text source
    :seon.sci.reader/ns namespace-name
    :seon.sci.reader/defer-auto-resolve? true
    :seon.config.eval.result/max-source max-source}))

(defn- clean-source?
  [source namespace-name max-source]
  (let [events (read-source source namespace-name max-source)]
    (and (vector? events)
         (seq events)
         (not-any? :seon.sci.reader/error events))))

(defn- repairable-delimiter-error?
  [event]
  (contains?
   #{:unclosed :stray-closer}
   (get-in event [:seon.sci.reader/error
                  :seon.error/data
                  :seon.sci.reader/error-kind])))

(defn- repaired-span
  "One honest Parinfer indent-mode trial, or nil.

  parinferish 0.8.0's source exposes exactly `(parse source {:mode :indent})`
  followed by `(flatten parsed)`. Indent mode needs neither cursor nor other
  options. A candidate is accepted only when it changed and the same SCI
  reader finds no remaining error event."
  [source namespace-name max-source]
  (try
    (let [parsed (parinferish/parse source {:mode :indent})
          candidate (parinferish/flatten parsed)]
      (when (and (not= source candidate)
                 (clean-source? candidate namespace-name max-source))
        candidate))
    (catch Exception _ nil)))

(defn- repair-source
  "Repair each reader-isolated delimiter failure at most once.

  Spans are processed from the end so earlier original offsets remain exact.
  Odd maps, invalid tokens, bad metadata, and other failures are untouched."
  [source namespace-name max-source]
  (let [events (read-source source namespace-name max-source)]
    (if-not (vector? events)
      source
      (reduce
       (fn [result event]
         (let [start (:seon.sci.reader/source-start event)
               end (:seon.sci.reader/source-end event)
               original (subs source start end)]
           (if-let [fixed (repaired-span original namespace-name max-source)]
             (str (subs result 0 start) fixed (subs result end))
             result)))
       source
       (->> events
            (filter repairable-delimiter-error?)
            (sort-by :seon.sci.reader/source-start >))))))

(defn- repair-sources
  [sources namespace-name max-source]
  (mapv
   (fn [source]
     (update source :seon.cluster.eval/source
             repair-source
             (or (:seon.ns/name source) namespace-name)
             max-source))
   sources))

(defn planned-sources
  "Parse and repair the ordered forms that one ordinary source run executes.

  This is the same source preparation used for a provider reply. Reader
  failures remain executable source so the ordinary evaluator records their
  terminal diagnostic; a reply containing no forms remains a flat refusal."
  {:malli/schema
   [:=> [:catn
         [:text :seon.cluster.reply/text]
         [:namespace-name :seon.ns/name]
         [:max-source :seon.config.eval.result/max-source]]
    [:or :seon.cluster.reply/sources :seon.error/value]]}
  [text namespace-name max-source]
  (let [parsed (reply/sources text namespace-name max-source)]
    (cond
      (vector? parsed) (repair-sources parsed namespace-name max-source)
      (= ::reply/no-forms (:seon.error/kind parsed)) parsed
      :else [{:seon.cluster.eval/source text
              :seon.ns/name namespace-name}])))

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
                        (schema.form/map-entries
                         (schema/schema-definition entity))))
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
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "The final SCI evaluation may return any value; this classifier returns a disposition only when the candidate satisfies my.turn/value."
                         :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe :my.turn/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.turn/value value)
    value))

(defn- append-output
  [evaluation lines]
  (if (seq lines)
    (update evaluation :seon.cluster.eval/output
            (fn [output]
              (str (when (seq output) (str output "\n"))
                   (str/join "\n" lines))))
    evaluation))

(defn- gate-function-install
  "Evaluate one function's complete green-to-install decision from one db."
  [cluster base-ctx agent-id receipt-id form evaluation]
  (if-let [function-symbol
           (when (get-in evaluation [:seon.program/row :seon.fn/spec])
             (get-in evaluation [:seon.program/row :seon.fn/sym]))]
    (let [database (:seon.db/db form)
          analysis (seon.fn/analyze-forms
                    database [(assoc (select-keys form [:seon.cluster.eval/source
                                                        :seon.cluster.eval/ns])
                                     :seon.program/row (:seon.program/row evaluation))])
          _ (when (:seon.error/kind analysis)
              (throw (ex-info (:seon.error/message analysis) analysis)))
          [form-facts analyzed-row] (first analysis)
          test-symbols (seon.fn/gate-set database function-symbol)
          seed (accretion/seed-for receipt-id)
          candidate
          ((requiring-resolve 'seon.sci.eval/evaluate-candidate)
           {:seon.sci.eval/ctx base-ctx
            :seon.db/db database
            :seon.db/connection (:seon.db/connection cluster)
            :seon.agent/id agent-id
            :seon.test.accretion/candidate-ctx
            (:seon.test.accretion/candidate-ctx evaluation)
            :seon.test.accretion/evaluation evaluation
            :seon.cluster.eval/source
            (:seon.cluster.eval/source form)
            :seon.cluster.eval/ns (:seon.cluster.eval/ns form)
            :seon.program/row analyzed-row
            :seon.test.accretion/gate-set test-symbols
            :seon.config.test/auto-check-cases
            (:seon.config.test/auto-check-cases
             (config/effective database (:seon.cluster/name cluster)))
            :seon.test.accretion/seed seed
            :seon.sci.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms cluster)
            :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
            :seon.config/on-core-error (:seon.config/on-core-error cluster)})
          check (:seon.test.accretion/auto-check candidate)
          report
          (accretion/gate-report
           {:seon.fn/sym function-symbol
            :seon.test.accretion/results
            (:seon.test.accretion/results candidate)
            :seon.test.accretion/auto-check check})
          evidence
          {:seon.test.accretion/gate-tests test-symbols
           :seon.test.accretion/gate-test-count
           (:seon.test.accretion/test-count report)
           :seon.test.accretion/gate-pass-count
           (:seon.test.accretion/test-pass-count report)
           :seon.test.accretion/gate-fail-count
           (:seon.test.accretion/test-fail-count report)
           :seon.test.accretion/seed seed
           :seon.test.accretion/case-count
           (:seon.test.accretion/case-count check)
           :seon.test.accretion/executed-count
           (:seon.test.accretion/executed-count check)
           :seon.test.accretion/status
           (:seon.test.accretion/status check)
           :seon.test.accretion/report-edn (pr-str report)}
          evaluation (merge (dissoc evaluation :seon.test.accretion/candidate-ctx)
                            evidence
                            {:seon.program/row analyzed-row
                             :seon.turn/form-facts
                             (assoc form-facts :db/id [:seon.cluster.eval/id receipt-id])})]
      (if (:seon.test.accretion/install? report)
        (append-output ((requiring-resolve 'seon.sci.eval/accept-candidate!)
                        {:seon.sci.eval/ctx base-ctx :seon.db/db database
                         :seon.sci.eval/evaluation evaluation})
                       (:seon.test.accretion/advisories report))
        ((requiring-resolve 'seon.sci.eval/refuse-install)
         form evaluation (accretion/install-refusal report))))
    (if (get-in evaluation [:seon.program/row :seon.fn/sym])
      (dissoc evaluation :seon.program/row :seon.test.accretion/candidate-ctx)
      (dissoc evaluation :seon.test.accretion/candidate-ctx))))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(declare turn resume-turn)

(defn- submission-time-limit-evaluation
  "The evaluation value for a submission the backstop cut.

  Built by `seon.sci.eval/unrun-evaluation`, the ONE constructor of that
  value, so this arm cannot omit a required key the way a hand-built map did.
  Its own report used to reach `seon.problems/form-problem` missing four
  required keys, and the durable evidence of the interruption became a
  contract violation from the recorder instead of the interruption."
  [request submission-wait-ms]
  (let [time-limit-ms (:seon.sci.eval/time-limit-ms request)
        message (str "Evaluation submission did not settle within "
                     time-limit-ms "ms.")]
    ((requiring-resolve 'seon.sci.eval/unrun-evaluation)
     {:seon.sci.admit/value
      {:seon.error/kind :seon.flow/time-limit
       :seon.flow/time-limit :seon.turn.loop/turn
       :seon.error/message message
       :seon.error/data {:seon.flow/submission-wait-ms submission-wait-ms}}
      :seon.cluster.eval/ns (:seon.cluster.eval/ns request)
      :seon.eval/duration-ms (long submission-wait-ms)
      :seon.cluster.eval/interrupted-at (Date.)})))

(defn- submit-evaluation!!
  [cluster evaluate submission-id request]
  (let [submission
        (seon.flow/submit!!
         (:seon.flow/work-launcher cluster)
         {:seon.env/environment (:seon.env/environment cluster)
          ::seon.flow/submission-id submission-id
          ::seon.flow/workload :compute
          ::seon.flow/time-limit-ms
          (* 2 (:seon.sci.eval/time-limit-ms request))
          ::seon.flow/work-fn
          (fn [{::seon.flow/keys [started!]}]
            (started!)
            (evaluate request))})]
    (if (= ::seon.flow/completed (::seon.flow/outcome submission))
      (::seon.flow/value submission)
      (submission-time-limit-evaluation
       request
       (::seon.flow/submission-wait-ms submission)))))

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
           :seon.error/process (:seon.db.process/id cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.error/basis-t (db/basis-t db)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)
           :seon.config.error/max-evidence-bytes
           (:seon.config.error/max-evidence-bytes cluster)}
          (when-let [escalate-to (:seon.config.error/escalate-to cluster)]
            {:seon.config.error/escalate-to escalate-to})
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
      (when problem ((requiring-resolve 'seon.problems/assignment-value) problem))))

(defn- delivery-rows
  "Delivery rows and refusal transaction data for one asked value."
  [{db :seon.db/db
    cluster :seon.turn.loop/cluster
    asked :seon.turn.loop/asked
    agent-id :seon.agent/id
    run-id :seon.turn/id
    ordinal :seon.cluster.eval/ordinal
    now :seon.turn.loop/now
    problem :seon.problems/form-problem
    trigger :seon.message/trigger}]
  (let [receipt-eid
        (when problem
          (db/q '[:find ?receipt .
                  :in $ ?run-id ?ordinal
                  :where
                  [?run :seon.turn/id ?run-id]
                  [?receipt :seon.cluster.eval/run ?run]
                  [?receipt :seon.cluster.eval/ordinal ?ordinal]]
                db run-id ordinal))
        delivery
        (when asked
          (message/delivery
           db
           (cond-> {:my.message/value (if problem
                      (dissoc asked :my.message/about)
                      asked) :seon.agent/id agent-id :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal :seon.config.message/max-chain (:seon.config.message/max-chain cluster)}
             trigger (assoc :seon.message/trigger trigger))))]
    {:seon.message/rows
     (cond->> (:seon.message/rows delivery)
       problem (mapv #(assoc % :seon.message/about receipt-eid)))
     :seon.error/values-tx
     (into []
           (mapcat
            (fn [failure]
              (error-tx cluster db failure now
                        {:seon.agent/id agent-id
                         :seon.turn/id run-id})))
           (:seon.error/values delivery))}))

(defn- phase
  "Return one phase's value, translating a host failure to flat data."
  [operation]
  (try
    (operation)
    (catch Throwable failure
      (merge {:seon.error/kind :seon.turn.loop/phase-failed
              :seon.error/message
              (or (ex-message failure) (.getName (class failure))) :seon.turn.loop/phase-failed true}
             (error/refusal failure)))))

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
        raw-settled (disposition (:seon.sci.admit/value evaluation))
        settled
        (cond-> raw-settled
          (= :completed (:my.turn/disposition raw-settled))
          (assoc :my.turn/delivered-to
                 (or (some->> trigger (message/sender database))
                     :outside)))
        evaluation
        (if (and settled (not= settled raw-settled))
          (merge evaluation
                 (admit/admit
                  {:seon.sci.admit/value settled
                   :seon.sci.admit/interrupt-fn (constantly nil)
                   :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                   :seon.config/on-core-error
                   (:seon.config/on-core-error cluster)
                   :seon.schema/projection
                   (db/carried-projection database)}))
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
        side-tx
        (concat
         (when settled
           [[:db/add [:seon.turn/id run-id]
             :seon.turn/disposition (:my.turn/disposition settled)]])
         (when (or undisposed?
                   (contains? #{:completed :wait}
                              (:my.turn/disposition settled)))
           (close-tx
            {:seon.turn/id run-id :seon.db.process/id process}))
         (:seon.message/rows delivery)
         (:seon.error/values-tx delivery))
        tx-data (if batch?
                  (vec side-tx)
                  (conj (into (receipt-settle-tx database receipt) side-tx)
                        [:db.fn/call #'plan/settle-call agent-id]))]
    {:seon.turn.loop/settled settled
     :seon.turn.loop/undisposed? undisposed?
     :seon.turn.loop/evaluation evaluation
     :seon.turn.loop/receipt receipt
     :seon.blob/staged-writes settlement-stages
     :seon.db/tx-data tx-data}))

(declare settle-batch-refusal!)

(defn- settle-batch!
  "Settle every evaluated form and all turn side effects in one transaction."
  [cluster requests]
  (let [connection (:seon.db/connection cluster)
        process (:seon.db.process/id cluster)
        prepared (mapv #(evaluation-terminal-data
                         (assoc % :seon.turn.loop/batch? true
                                  :seon.db.process/id process))
                       requests)
        namespace-rows
        (into []
              (comp
               (map :seon.turn.loop/receipt)
               (map :seon.program/row)
               (mapcat :seon.ns/requires)
               (map second)
               (remove nil?)
               (distinct)
               (map (fn [namespace-name] {:seon.ns/name namespace-name})))
              prepared)
        transaction
        {:seon.blob/staged-writes
         (into [] (mapcat :seon.blob/staged-writes) prepared)
         :seon.db/tx-data
         (into
          (into (into namespace-rows
                      (receipt-settle-batch-tx (mapv :seon.turn.loop/receipt prepared)))
                (mapcat :seon.db/tx-data)
                prepared)
          (map (fn [agent-id] [:db.fn/call #'plan/settle-call agent-id]))
          (distinct (map :seon.agent/id requests)))}
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
        outcome
        (phase
         #(blob/with-publication!
           connection (:seon.blob/staged-writes transaction)
           (fn [] (db/transact!
                   connection {:tx-data (:seon.db/tx-data transaction)}))))]
    (if (:seon.error/kind outcome)
      (settle-batch-refusal! cluster requests prepared outcome)
      {:prepared prepared :outcome outcome})))

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
  [cluster database now agent-id run-id process ordinal _receipt source]
  (let [recording
        (error-tx cluster database source now
                  (cond-> {:seon.agent/id agent-id}
                    run-id (assoc :seon.turn/id run-id)))
        value (error/value (first recording))
        receipt-tx
        (when ordinal
          (receipt-settle-tx
           database
            {:seon.turn/id run-id
             :seon.cluster.eval/ordinal ordinal
             :seon.eval/shown (pr-str value)
             :seon.cluster.eval/error (:seon.error/message value)
             :seon.error/kind (:seon.error/kind value)}))]
    {:seon.error/value value
     :seon.db/tx-data
     (into [] cat
           [receipt-tx
            (when run-id
              (close-tx
               {:seon.turn/id run-id :seon.db.process/id process :seon.turn/closed-tx "datomic.tx"}))
            recording])}))

(defn- settle-batch-refusal!
  "Settle every begun ordinal after the atomic turn settlement is refused."
  [cluster requests prepared refusal]
  (let [connection (:seon.db/connection cluster)
        {now :seon.turn.loop/now
         agent-id :seon.agent/id
         run-id :seon.turn/id}
        (first requests)
        process (:seon.db.process/id cluster)
        recording (error-tx cluster (db/db connection) refusal now
                            {:seon.agent/id agent-id
                             :seon.turn/id run-id})
        value (error/value (first recording))
        serialized (pr-str value)
        receipts
        (mapv
         (fn [entry]
           (-> (:seon.turn.loop/receipt entry)
               ;; `evaluation-terminal-data` already projected and staged the
               ;; durable def values. Re-projecting raw in-memory defs here
               ;; discarded those values and made a refused definition
               ;; unrestorable on the next turn.
               (dissoc :seon.program/row :seon.turn/form-facts)
               (assoc :seon.eval/shown serialized
                      :seon.cluster.eval/error (:seon.error/message value)
                      :seon.error/kind (:seon.error/kind value))))
         prepared)
        transaction
        {:tx-data
         (into (receipt-settle-batch-tx receipts)
               cat
               [(close-tx {:seon.turn/id run-id :seon.db.process/id process :seon.turn/closed-tx "datomic.tx"})
                recording])}
        outcome (db/transact! connection transaction)]
    (when (:seon.error/kind outcome)
      (throw
       (ex-info "Batch refusal settlement was refused."
                {:seon.error/kind :seon.turn.loop/terminal-refusal-settlement-refused
                 :seon.turn.loop/settlement outcome
                 :seon.turn.loop/refused-outcome refusal})))
    {:prepared prepared
     :outcome outcome
     :refused-outcome refusal}))

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
        (if (:seon.error/kind prepared)
          (refusal-terminal-data cluster (db/db connection) now agent-id run-id
                                 process ordinal nil prepared)
          prepared)
        ;; The same total commit as `settle-batch!`: one vector of staged
        ;; writes (absent means none, never nil into the contract) and one
        ;; `phase`, so a host failure lands in the refusal arm below rather
        ;; than escaping with the run still open.
        commit
        (fn [transaction]
          (phase
           #(blob/with-publication!
             connection (vec (:seon.blob/staged-writes transaction))
             (fn [] (db/transact!
                     connection
                     {:tx-data (:seon.db/tx-data transaction)})))))
        outcome (commit prepared)]
    (if-not (:seon.error/kind outcome)
      (assoc prepared :seon.turn.loop/outcome outcome)
      (let [refusal
            (refusal-terminal-data
             cluster (db/db connection) now agent-id run-id process ordinal
             (:seon.turn.loop/receipt prepared) outcome)
            refused (commit refusal)]
        (when (:seon.error/kind refused)
          (throw
           (ex-info "Terminal refusal settlement was refused."
                    {:seon.error/kind :seon.turn.loop/terminal-refusal-settlement-refused
                     :seon.turn.loop/terminal-refusal-settlement-refused
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
  [{completion :seon.ai/completion}]
  (let [truncation (or (:seon.ai/truncation completion)
                       (when (= :seon.ai/stream-truncated
                                (:seon.error/kind completion))
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
        targets (ai/targets db settings)
        primary (:seon.ai/primary targets)
        backup (:seon.ai/backup targets)
        strategy (ai/retry-strategy settings)]
    {:seon.ai/primary primary
     :seon.ai/backup backup
     :seon.ai/settings settings
     :seon.turn.loop/schedule (if backup [] (ai/delays strategy rand))}))

(defn- record-attempt!
  "Commit ONE model attempt and its error or truncation facts.
  Returns the committed error fact, nil on success, or a typed transaction refusal.

  The error fact and the attempt row ride ONE transaction, with the
  attempt's `:seon.ai.attempt/error` pointing at the fact through the
  shared tempid. That is not tidiness: the caller may only build the
  backup's context from a fact that is already durable, and one
  transaction is what makes \"already durable\" true with no window.

  A refused transaction returns its diagnostic so the caller cannot freeze
  a successful reply or make another paid call without durable evidence."
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
                            (error-tx cluster db failure now attribution))
        truncation-recording
        (cond
          (nil? truncation) nil
          (= truncation failure) failure-recording
          :else (error-tx cluster db truncation now attribution))
        recording (into (vec failure-recording)
                        (when (not= truncation failure)
                          truncation-recording))
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
                     (:db/id (first failure-recording)))
              truncation-recording
              (assoc :seon.ai.attempt/truncation
                     (:db/id (first truncation-recording)))
              settings
              (assoc :seon.ai.attempt/settings-edn (pr-str settings))
              usage (assoc :seon.ai.attempt/usage-edn (pr-str usage))
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
         connection (cond-> [] reasoning-stage (conj reasoning-stage))
         (fn []
           (db/transact! connection
                         (into (conj recording row
                                     [:db/add [:seon.turn/id run-id] :seon.turn/attempts "attempt"]) observation-tx))))]
    (if (:seon.error/kind outcome)
      outcome
      (some-> failure-recording first (dissoc :db/id)))))

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
           (:seon.config.eval/time-limit-ms cluster)
           :seon.config/on-core-error
           (:seon.config/on-core-error cluster)}
           run-id (assoc :seon.turn/id run-id)
           (:seon.flow/work-launcher cluster)
           (assoc :seon.flow/work-launcher
                  (:seon.flow/work-launcher cluster)))))

(defn- open-turn
  "Open one turn before any paid provider call."
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
    ;; gone with the reference it guarded — `open-call`'s
    ;; `:seon.turn.loop/agent-already-running` is what stops two openers, and the
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
          outcome (if (:seon.error/kind refreshed)
                    refreshed
                    (db/transact!
                   connection
                   {:tx-data
                    [[:db.fn/call #'open-call open-request]]}))]
      (cond
        (:seon.error/kind outcome)
        (do
          ;; The open transaction formed no run, so settlement records the
          ;; error and escalation with no run attribution or close.
          (settle! {:seon.turn.loop/cluster cluster
                    :seon.turn.loop/now now
                    :seon.agent/id agent-id
                    :seon.error/value outcome})
          (report :error 0))

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
          (fn [failure]
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value failure})
            (report :error 0))
          freeze!
          (fn [completion]
            ;; ONE INTENT COMMIT. The raw reply and every result-less eval row
            ;; become durable together before any form runs. A later crash is
            ;; therefore exactly the set difference between intent rows and
            ;; terminal results; recovery never has to reconstruct or rerun it.
            (let [reply-text (:seon.ai/text completion)
                  database (db/db connection)
                  namespace-name ((requiring-resolve 'seon.sci.eval/agent-namespace) database agent-id)
                  max-source
                  (get-in cluster
                          [:seon.sci.admit/caps
                           :seon.config.eval.result/max-source])
                  prepared
                  (if (and (:seon.config.ai/no-provider settings)
                           (empty? reply-text))
                    []
                    (planned-sources reply-text namespace-name max-source))
                  no-forms? (= ::reply/no-forms (:seon.error/kind prepared))
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
                (:seon.error/kind outcome) (fail! outcome)
                (and (:seon.error/kind prepared) (not no-forms?)) (fail! prepared)
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
           #((requiring-resolve 'seon.cluster.prompt/prompt) prompt-db
                           {:seon.turn/id run-id
                            :seon.agent/id agent-id
                            :seon.db/connection connection
                            :seon.sci.admit/caps
                            (:seon.sci.admit/caps cluster)
                            :seon.sci.eval/ctx
                            (:seon.sci.eval/ctx cluster)
                            :seon.sci.eval/time-limit-ms
                            (:seon.config.eval/time-limit-ms cluster)
                            :seon.config/on-core-error
                            (:seon.config/on-core-error cluster)})))
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
           ((requiring-resolve 'seon.context/capture-tx)
            (if (:seon.error/kind rendered)
              {:seon.turn/id run-id
               ;; The immutable opening value the refused derivation used,
               ;; never a fresh connection deref after the fact.
               :seon.db/db (if (:seon.error/kind prompt-db)
                             observed-db
                             prompt-db)
               :seon.error/value rendered}
              {:seon.turn/id run-id
               :seon.cluster.prompt/rendered-context rendered}))))
          ;; THE EXACT-TEXT HANDOFF: the loop extracts the rendered
          ;; text and alone places that string in `:seon.ai/prompt` —
          ;; the bytes the capture recorded are the bytes sent.
          text (:seon.cluster.prompt/text rendered)]
      (cond
        (:seon.config.ai/no-provider settings)
        (freeze! {:seon.ai/text ""})

        (:seon.error/kind captured)
        ;; A refused prompt/capture closes this run and records the refusal.
        ;; The next pass derives correction from those facts below the ONE
        ;; episode cap; at the cap it derives no work. No provider call occurs
        ;; without durable prompt evidence.
        (fail! captured)

        (:seon.error/kind rendered)
        ;; The refusal capture is now durable. Close without crossing the
        ;; provider boundary; a diagnosis never depends on absent signal.
        (fail! rendered)

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
                            (cond-> (assoc target :seon.ai/prompt text)
                              system (assoc :seon.ai/system system)
                              sink (assoc :seon.ai/stream? true
                                          :seon.ai/sink sink)))
                failure (when (:seon.error/kind completion) completion)
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
              (and (:seon.error/kind fact) (nil? (:seon.error/id fact))) (fail! fact)

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
                     (render/render-ai
                      {:seon.db/db (db/db connection)
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                       :seon.render/value
                       (error/notice {:seon.error/fact fact
                                      :seon.error/reason :failover})
                       :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                       :seon.sci.eval/time-limit-ms
                       (:seon.config.eval/time-limit-ms cluster)
                       :seon.config/on-core-error
                       (:seon.config/on-core-error cluster)}))

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
                (if (:seon.error/kind closed)
                  (fail! closed)
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

(defn- disposition-rule-error
  [source namespace-name last? evaluation]
  (let [form (:seon.sci.reader/form
              (first (read-source source namespace-name (count source))))
        calls (tree-seq
               #(and (coll? %)
                     (not (and (seq? %)
                               (contains? #{'quote 'fn 'fn* 'defn 'defn-} (first %)))))
               seq form)
        done-calls (filter #(and (seq? %) (= 'my.agent/done (first %))) calls)]
    (when (or (and (seq done-calls)
                   (not (and last? (= form (first done-calls)) (= 1 (count done-calls)))))
              (and (disposition (:seon.sci.admit/value evaluation)) (not last?)))
      {:seon.error/kind :seon.turn/invalid-disposition
       :seon.error/message "(my.agent/done) must be the last form of your reply; a disposition cannot precede another reply form."
       :seon.error/data {:seon.cluster.eval/source source}})))

(defn evaluate-sources
  "Evaluate ordered sources in one fork without settling or staging them.

  An explicit database is the basis of every form. Otherwise each form sees
  the connection's current value, as in an ordinary turn. Results and namespace
  changes advance the same fork; callers decide whether to persist outcomes."
  {:malli/schema [:=> [:cat :seon.turn.loop/evaluate-sources-request]
                  :seon.turn.loop/evaluated-sources]}
  [{cluster :seon.turn.loop/cluster
    snapshot :seon.db/db
    ctx :seon.sci.eval/ctx
    agent-id :seon.agent/id
    run-id :seon.turn/id
    write? :seon.turn/write?
    first-ordinal :seon.cluster.eval/ordinal
    sources :seon.cluster.reply/sources
    starting-namespace :seon.ns/name}]
  (let [connection (:seon.db/connection cluster)
        cluster (merge cluster (ai/agent-overlay (or snapshot (db/db connection)) agent-id))]
    (loop [remaining (seq sources)
           ordinal first-ordinal
           namespace-name starting-namespace
           results []]
      (if-let [source (first remaining)]
        (let [form (assoc source :seon.cluster.eval/ns
                          [:seon.ns/name namespace-name])
              database (or snapshot (db/db connection))
              captured (atom [])
              _ (await-turn-part! cluster :seon.sci.eval/evaluation
                                  (:seon.config.eval/time-limit-ms cluster))
              at (java.util.Date.)
              entity-id (when run-id
                          (evaluation-entity-id
                           database (receipt-identity run-id ordinal)))
              handle (when (or entity-id (and run-id write?))
                       (admit/result-handle (receipt-identity run-id ordinal)))
              request
              (cond-> (assoc (evaluation-request
                       {:seon.turn.loop/admitted-form form
                        :seon.turn.loop/evaluation-namespace namespace-name
                        :seon.turn.loop/cluster cluster
                        :seon.sci.eval/ctx ctx
                        :seon.agent/id agent-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.turn/id run-id})
                             :seon.db/db database
                             :seon.db/connection connection
                             :seon.render/profile
                             (render/request-profile
                              {:seon.db/db database
                               :seon.agent/id agent-id}))
                handle (assoc :seon.repl/handle handle))
              evaluation
              (binding [db/*read-evidence-sink* captured]
                (render/call-with-walk-context
                 {:seon.db/db database
                  :seon.db/connection connection
                  :seon.agent/id agent-id
                  :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                  :seon.sci.eval/ctx ctx
                  :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
                  :seon.config/on-core-error (:seon.config/on-core-error cluster)}
                 #(gate-function-install
                   cluster ctx agent-id
                   (if run-id (receipt-identity run-id ordinal)
                       (id/id [agent-id form]))
                   request
                   (sci.eval/evaluate-for-install request))))
              evaluation (or (disposition-rule-error
                               (:seon.cluster.eval/source form) namespace-name
                               (nil? (next remaining)) evaluation)
                              evaluation)
              evaluation
              (if (:seon.error/kind evaluation)
                {:seon.sci.admit/value evaluation
                 :seon.eval/shown (pr-str evaluation)
                 :seon.cluster.eval/error (:seon.error/message evaluation)
                 :seon.error/kind (:seon.error/kind evaluation)}
                evaluation)
              evaluation
              (assoc evaluation
                     :seon.cluster.eval/at at
                     :seon.cluster.eval/read-evidence (db/read-evidence @captured)
                     :seon.cluster.eval/read-basis-transaction (db/basis-t database))
              evaluation (cond-> evaluation
                           handle (assoc :seon.repl/handle handle))]
          (when (and entity-id
                     (= :system (:seon.cluster.eval/author
                                 (db/pull database [:seon.cluster.eval/author] entity-id)))
                     (= :generate (:seon.turn.work/situation
                                   (db/pull database [:seon.turn.work/situation]
                                            [:seon.turn/id run-id]))))
            (when-let [fault (generated-read-fault database form evaluation)]
              (throw (ex-info (:seon.error/message fault) fault))))
          (when entity-id
            ((requiring-resolve 'seon.sci.eval/bind-result!) ctx handle (:seon.sci.admit/value evaluation)))
          (let [results (conj results
                              {:seon.cluster.eval/ordinal ordinal
                               :seon.turn.loop/admitted-form form
                               :seon.sci.eval/evaluation evaluation})]
            (if (contains? #{:completed :wait}
                           (:my.turn/disposition (:seon.sci.admit/value evaluation)))
              results
              (recur (next remaining) (inc ordinal)
                     (or (:seon.sci.eval/ending-ns evaluation) namespace-name)
                     results))))
        results))))

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
        forked ((requiring-resolve 'seon.sci.eval/fork-for-turn)
                {:seon.sci.eval/ctx base-ctx
                 :seon.db/db database
                 :seon.db/connection (:seon.db/connection cluster)
                 :seon.agent/id agent-id})]
    (if (:seon.error/kind forked)
      forked
      (let [sources (planned-sources
                     text namespace-name
                     (:seon.config.eval.result/max-source caps))]
        (if (map? sources)
          sources
          {:seon.turn.loop/evaluated-sources (evaluate-sources
            {:seon.turn.loop/cluster cluster
             :seon.db/db database
             :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
             :seon.agent/id agent-id
             :seon.cluster.eval/ordinal 0
             :seon.ns/name namespace-name
             :seon.cluster.reply/sources sources}) :seon.agent/id agent-id :seon.turn/starting-ns [:seon.ns/name namespace-name] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.db/db database})))))

(defn- resume-turn
  "Evaluate an intent-frozen turn in memory, then settle the whole batch once."
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now
    report :seon.turn.loop/report reader-event :seon.sci.eval/event}]
  (let [connection (:seon.db/connection cluster)
        agent-id (:seon.agent/id work)
        run-id (:seon.turn/id work)
        base-ctx (:seon.sci.eval/ctx cluster)
        forked
        (phase #((requiring-resolve 'seon.sci.eval/fork-for-turn)
                 (cond-> {:seon.sci.eval/ctx base-ctx
                  :seon.db/db (db/db connection)
                  :seon.db/connection connection
                  :seon.agent/id agent-id
                  :seon.turn/id run-id}
                   (:seon.sci.eval/agent-ctx cluster)
                   (assoc :seon.sci.eval/agent-ctx
                          (:seon.sci.eval/agent-ctx cluster)))))
        trigger (phase #(message/trigger (db/db connection) run-id))]
    (if-let [failure (some #(when (:seon.error/kind %) %)
                           [forked trigger])]
      (do
        (settle! {:seon.turn.loop/cluster cluster
                  :seon.turn.loop/now now
                  :seon.agent/id agent-id
                  :seon.turn/id run-id
                  :seon.error/value failure})
        (report :error 0))
      (let [{ctx :seon.sci.eval/ctx} forked
            database (db/db connection)
            first-ordinal (:seon.cluster.eval/ordinal work)
            evaluations (fold-evaluations database run-id)
            evaluated
            (phase
             #(evaluate-sources
               {:seon.turn.loop/cluster cluster
                :seon.sci.eval/ctx ctx
                :seon.agent/id agent-id
                :seon.turn/id run-id
                :seon.cluster.eval/ordinal first-ordinal
                :seon.ns/name (or (fold-namespace database run-id evaluations
                                                 first-ordinal)
                                  ((requiring-resolve 'seon.sci.eval/agent-namespace) database agent-id))
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
                      evaluations)}))
            submitted
            (into []
                  (map-indexed
                   (fn [index {form :seon.turn.loop/admitted-form evaluation :seon.sci.eval/evaluation}]
                     [index
                      (cond->
                       {:seon.cluster.eval/source (:seon.cluster.eval/source form)
                        :seon.cluster.eval/ns (:seon.cluster.eval/ns form)}
                        (:seon.program/row evaluation)
                        (assoc :seon.program/row (:seon.program/row evaluation)))]))
                  evaluated)
            analyzed
            (cond
              (:seon.error/kind evaluated) evaluated
              (seq submitted) (phase #(seon.fn/analyze-forms database (mapv second submitted)))
              :else [])]
        (if (:seon.error/kind analyzed)
          (do
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value analyzed})
            (report :error (count evaluated)))
          (let [evaluated
                (reduce
                 (fn [all [[index _] [form-facts row]]]
                   (cond-> all
                       row (assoc-in [index :seon.sci.eval/evaluation :seon.program/row] row)
                       true (assoc-in
                        [index :seon.sci.eval/evaluation :seon.turn/form-facts]
                        (assoc form-facts
                               :db/id
                               [:seon.cluster.eval/id
                                (receipt-identity
                                 run-id (:seon.cluster.eval/ordinal (nth all index)))]))))
                 evaluated
                 (map vector submitted analyzed))
                gated evaluated
                requests
                (mapv
                 (fn [{ordinal :seon.cluster.eval/ordinal evaluation :seon.sci.eval/evaluation}]
                   (let [problem
                         ; A reply with no form has no program owner to notify.
                         ; Its reader error belongs only in the author's history.
                         (when-not reader-event
                           (phase
                            #((requiring-resolve 'seon.problems/form-problem)
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
                       :seon.sci.eval/evaluation evaluation
                       :seon.message/trigger trigger}
                       (and problem (not (:seon.error/kind problem)))
                       (assoc :seon.problems/form-problem problem))))
                 gated)
                settlement (settle-batch! cluster requests)
                outcome (:outcome settlement)
                prepared (:prepared settlement)
                last-prepared (peek prepared)]
            (if (or (:seon.error/kind outcome)
                    (:refused-outcome settlement))
              (report :error (count gated))
              (do
                ((requiring-resolve 'seon.sci.eval/install-evaluated-rows!)
                 {:seon.sci.eval/ctx base-ctx
                  :seon.db/db (:db-after outcome)
                  :seon.sci.eval/installations
                  (into []
                        (keep
                         (fn [{evaluation :seon.sci.eval/evaluation}]
                           (let [row (:seon.program/row evaluation)]
                             (when (and row
                                        ((requiring-resolve 'seon.sci.eval/committed-row?)
                                         (:db-after outcome) row))
                               {:seon.program/row row
                                :seon.sci.eval/evaluation evaluation}))))
                        gated)})
                (if (or (:seon.turn.loop/settled last-prepared)
                        (:seon.turn.loop/undisposed? last-prepared))
                  (report :closed (count gated))
                  (report :released (count gated)))))))))))
(defn- close-turn
  [{cluster :seon.turn.loop/cluster work :seon.turn.loop/work now :seon.turn.loop/now report :seon.turn.loop/report}]
  (let [outcome (db/transact!
                 (:seon.db/connection cluster)
                 (conj (close-tx {:seon.turn/id (:seon.turn/id work) :seon.turn/closed-tx "datomic.tx"})
                       [:db.fn/call #'plan/settle-call (:seon.agent/id work)]))]
    (if (:seon.error/kind outcome)
      (do (settle! {:seon.turn.loop/cluster cluster :seon.turn.loop/now now
                     :seon.agent/id (:seon.agent/id work)
                     :seon.error/value outcome})
          (report :error 0))
      (report :closed 0))))

(defn- generate-turn
  "Append one opening form from the shared system-turn source generator."
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
                namespace-name ((requiring-resolve 'seon.sci.eval/agent-namespace)
                                database agent-id)
                declared (declared-sources cluster database agent-id namespace-name)]
            (if (:seon.error/kind declared)
              declared
              (nth (system-plan database (:seon.turn/forms declared) {}) ordinal nil))))]
    (cond
      (:seon.error/kind entry)
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
        (if (:seon.error/kind terminal)
          (do
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value terminal})
            (report :error 0))
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
                       ((requiring-resolve 'seon.sci.eval/agent-namespace) (db/db connection) agent-id)}
                (:seon.cluster.eval/comment entry)
                (assoc :seon.cluster.eval/comment
                       (:seon.cluster.eval/comment entry)))))]
        (if (:seon.error/kind appended)
          (do
            (settle! {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/now now
                      :seon.agent/id agent-id
                      :seon.turn/id run-id
                      :seon.error/value appended})
            (report :error 0))
          (resume-turn
           (assoc request :seon.turn.loop/work
                  (assoc work
                         :seon.turn.work/situation :resume
                         :seon.cluster.eval/ordinal ordinal))))))))

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
        report (fn [outcome forms-run]
                 (cond-> {:seon.agent/id agent-id
                          :seon.turn.work/situation
                          (:seon.turn.work/situation work)
                          :seon.turn.loop/forms-run forms-run
                          :seon.turn.loop/outcome outcome}
                   run-id (assoc :seon.turn/id run-id)))
        request {:seon.turn.loop/cluster cluster
                 :seon.turn.loop/work work
                 :seon.turn.loop/now now
                 :seon.turn.loop/report report}
        pass (fn []
               (let [request (update request :seon.turn.loop/cluster merge
                                     (ai/agent-overlay
                                      (db/db (:seon.db/connection cluster)) agent-id))]
                 (case (:seon.turn.work/situation work)
                   :open (open-turn request)
                   :call (call-turn request)
                   :generate (generate-turn request)
                   :resume (resume-turn request)
                   :close (close-turn request))))]
    (if-let [projection-state (:seon.sci.eval/projection-state cluster)]
      (schema/call-with-projection-state projection-state pass)
      (pass))))

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
        (error/diagnostic
         (cond->
          {:seon.error/kind :seon.agent/turn-completion-backstop
           :seon.error/message
           (str "Agent " (pr-str agent-id)
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
           :seon.error/diagnostic-layer :seon.agent/agent-graph
           :seon.error/diagnostic-operation operation
           :seon.error/diagnostic-member :seon.turn.loop/completion
           :seon.error/diagnostic-expected expected
           :seon.error/diagnostic-offending evidence
           :seon.error/diagnostic-cause :seon.agent/turn-completion-backstop
           :seon.error/diagnostic-evidence evidence}
           run-id (assoc :seon.turn/id run-id)))]
    diagnostic))

(defn- turn-completion-backstop-failure
  [agent-id run-id timeout-ms operation expected events]
  (let [diagnostic (turn-completion-error agent-id run-id timeout-ms operation expected events)]
    (ex-info (:seon.error/message diagnostic) diagnostic)))

(defn- await-turn-permit!
  [state]
  (let [{connection :seon.db/connection
         cluster-name :seon.cluster/name
         process :seon.db.process/id
         completion :seon.turn.loop/completion
         executor :seon.flow/executor
         fault-channel :seon.agent/fault-channel
         carried-timeout-ms
         :seon.config.agent/turn-completion-backstop-ms
         backstop-state :seon.agent/turn-backstop-state}
        (:seon.turn.loop/cluster state)
        agent-id (:seon.agent/id state)
        database (db/db connection)
        run-id (open-for-agent database [:seon.agent/id agent-id])
        settings (ai/agent-overlay database agent-id)
        timeout-ms
        (or (:seon.config.agent/turn-completion-backstop-ms settings)
            carried-timeout-ms
            (:seon.config.agent/turn-completion-backstop-ms
             (config/effective database cluster-name)))
        [value selected]
        (async/alts!! [completion (async/timeout timeout-ms)] :priority true)]
    (if (= selected completion)
      {:seon.agent/turn-permit value
       :seon.agent/connection connection
       :seon.agent/process process
       :seon.agent/executor executor
       :seon.agent/fault-channel fault-channel
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
      (throw
       (turn-completion-backstop-failure
        agent-id run-id timeout-ms :seon.agent/turn-start :seon.agent/turn-permit
        [:seon.turn.loop/completion])))))

(defn- offer-turn-backstop-fault!
  [{:seon.agent/keys [fault-channel agent-id timeout-ms expected] armed-run :seon.agent/run-id}]
  (let [run-id @armed-run
        failure
        (turn-completion-backstop-failure
         agent-id run-id timeout-ms :seon.agent/turn-transform
         (or expected :seon.agent/turn-terminal)
         [:seon.turn.loop/completion])
        fault
        (cond->
         {::flow/pid :seon.agent/turn
          ::flow/status :running
          ::flow/op :seon.agent/turn-completion-backstop
          ::flow/ex failure
          :seon.agent/id agent-id}
          run-id (assoc :seon.turn/id run-id))]
    (when-not (and fault-channel (async/offer! fault-channel fault))
      (binding [*out* *err*]
        (println "SEON CORE FAULT (agent turn backstop):"
                 (ex-message failure))
        (flush)))
    failure))

(defn- arm-turn-completion-backstop!
  "Arm the live bound after the ready permit is consumed.

  Success cancels only after the permit is republished. An escaped transform
  republishes the permit for lifecycle progress but deliberately leaves this
  observer armed, so quiescence cannot hide the failed turn."
  [{:seon.agent/keys [executor timeout-ms backstop-state] :as turn-bound}]
  (when-not (instance? Executor executor)
    (throw
     (ex-info "An active agent turn requires its carried IO executor."
              {:seon.error/kind :seon.agent/turn-completion-backstop
               :seon.agent/id (:seon.agent/agent-id turn-bound)
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
             (async/put! failure-channel (offer-turn-backstop-fault! bound))
             :else
             (do
               (when backstop-state
                 (compare-and-set! backstop-state backstop nil))
               (async/close! failure-channel)))))))
    backstop))

(defn step
  "The turn transform, in Flow's four arities: ONE episode pass.
  Settle this agent's orphan (the wedge fence, per-agent), pin one
  database value, derive `next-agent-work`, run the situation through
  `seon.turn/turn` — the surviving owner of open/call/resume/
  close and the pre-provider capture — then
  `offer!` one wake into this agent's OWN mailbox when
  `more-agent-work?`. Coalescing on sliding-1 keeps the rewake
  non-recursive. Failures inside the pass stay VALUES (the existing
  `refused!`/`error-tx` owners); a Throwable that escapes anyway is a
  core fault and rides this graph's error channel into the cluster's
  fault committer, tagged with the agent. The completion channel is an
  armed-ready permit: arm publishes it before Flow scheduling, an active
  transform holds it under the completion allowance plus the admitted provider
  schedule or each form's evaluation limit, and
  `finally` republishes it without an interruptible park. A successful pass
  then cancels the bound; an escaped pass leaves it armed. Disarm awaits the
  stop transition or joins that same active bound; a ready permit never
  substitutes for proc exit."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "The core.async.flow callback treats every incoming payload as a wake signal and never interprets it; payload values are intentionally unrestricted."
                         :gen/elements [nil false 0 "" :k [] {}]}]]
                   [:tuple :map [:maybe [:map [::flow/report [:vector :seon.turn.loop/pass-report]]]]]]]}
  ([]
   {:ins {:seon.agent/episode "One payload-free episode signal from the mailbox."}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   (select-keys state [:seon.turn/id]))})
  ([args]
   args)
  ([state transition]
   (when (= ::flow/stop transition)
     (async/offer!
      (:seon.agent/turn-stopped
       (:seon.turn.loop/cluster state))
      :seon.agent/stopped))
   state)
  ([state _input _message]
   (let [cluster (:seon.turn.loop/cluster state)
         completion (:seon.turn.loop/completion cluster)]
     (if-some [turn-bound (await-turn-permit! state)]
       (let [backstop (arm-turn-completion-backstop! turn-bound)
             cluster (assoc cluster :seon.turn.loop/await-part
                            (:seon.turn.loop/await-part backstop))
             succeeded? (volatile! false)]
        (try
          (let [result
                (let [agent-id (:seon.agent/id state)
                      connection (:seon.db/connection cluster)
                      process (:seon.db.process/id cluster)
                      now (Date.)
                      request {:seon.agent/id agent-id
                               :seon.db.process/id process}
               ;; ONE database value for the derivation
                      next (next-agent-work (db/db connection) request)
                      ;; THE BOUND LEARNS ITS SUBJECT HERE, once, from
                      ;; the derivation that decided it. `:open` mints
                      ;; its run inside the turn, so the report supplies
                      ;; it below; every other situation names it now.
                      _ (when-let [derived (:seon.turn/id next)]
                          (reset! (:seon.agent/run-id turn-bound) derived))]
                  (if (nil? next)
                    [(dissoc state :seon.turn/id)
                     {::flow/report [{:seon.agent/id agent-id
                                     :seon.turn.loop/idle? true}]}]
                    (let [report (turn
                                  {:seon.turn.loop/cluster cluster
                                   :seon.turn.work/next next}
                                  now)
                          _ (when-let [opened (:seon.turn/id report)]
                              (reset! (:seon.agent/run-id turn-bound) opened))]
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
                      (when (more-agent-work? (db/db connection) request)
                        (async/offer!
                         (:seon.cluster.wake/channel cluster) :seon.agent/wake))
                      ;; THE PASS REPORTS THE RUN IT TURNED, not whatever
                      ;; the database says is held now: the turn may have
                      ;; closed and released custody, and re-deriving here
                      ;; made the ping state disagree with the report in
                      ;; exactly that ordinary case.
                      [(let [run-id (:seon.turn/id report)]
                         (cond-> (dissoc state :seon.turn/id)
                           run-id (assoc :seon.turn/id run-id)))
                ;; flow's own report channel: observation, never a dependency
                       {::flow/report [report]}])))]
           (vreset! succeeded? true)
           result)
         (finally
           (if (async/offer! completion :seon.agent/ready)
             (when @succeeded?
               (async/offer! (:seon.agent/cancel backstop) :seon.agent/completed)
               (when-let [backstop-state
                          (:seon.agent/turn-backstop-state cluster)]
                 (compare-and-set! backstop-state backstop nil)))
             (throw
              (ex-info
               "The agent turn could not publish its terminal completion."
               {:seon.error/kind :seon.agent/turn-completion-undeliverable
                :seon.agent/id
                (:seon.agent/id state)
                :seon.agent/turn-completion-undeliverable
                (:seon.agent/id state)}))))))
       [state nil]))))
