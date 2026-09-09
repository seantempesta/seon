(ns seon.turn
  "Turn facts and writer-owned transitions.

  Open means no closed-at. Opening queries the agent's existing turns in
  the writer's database; no agent run pointer is stored. Each transition
  returns transaction data for the same serial Datahike writer.

  Boot closes every prior open turn and interrupts unfinished evaluations.
  Process provenance belongs to execution requests and transactions."
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.program :as program]
            [seon.render.route :as render.route]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.error :as error]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.sci.reader :as reader]))

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
  (let [threshold (result-blob-threshold @connection)
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
           :seon.problems/form-problem :my.run/value]
    settlement-evaluation :seon.turn.loop/settlement-evaluation}]
  (let [error (or (:seon.cluster.eval/error evaluation)
                  (:seon.cluster.eval/error form-problem))
        kind (or (:seon.error/kind (:seon.sci.admit/value evaluation))
                 (:seon.error/kind form-problem))]
    (cond-> {:seon.turn/id id
             :seon.cluster.eval/ordinal ordinal}
      (:seon.eval/value settlement-evaluation)
      (assoc :seon.eval/value
             (:seon.eval/value settlement-evaluation))
      (:seon.eval/missing settlement-evaluation)
      (assoc :seon.eval/missing (:seon.eval/missing settlement-evaluation))
      (int? (:seon.eval/size settlement-evaluation))
      (assoc :seon.eval/size (:seon.eval/size settlement-evaluation))
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
      value (assoc :my.run/value value))))

(defn settlement-projection
  "Project an evaluation into receipt, defs, and staged-blob data."
  {:malli/schema
   [:=> [:cat :seon.turn.loop/cluster :seon.turn.loop/evaluation]
    [:tuple :map :map [:vector :seon.blob/staged-write]]]}
  [cluster evaluation]
  ;; A VALUE IS STORED FAITHFULLY OR IT IS MISSING. Admission already made
  ;; that decision under the one storage bound, so settlement stores the node
  ;; it was handed and nothing else — no window of a value, no size beside a
  ;; blob that holds the rest, no second representation to disagree with the
  ;; first (the storage-bound wave, 2026-09-07).
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
  {:malli/schema [:=> [:cat [:map [::closed-at {:optional true}
                                   ::closed-at]]]
                  :boolean]}
  [run]
  (not (contains? run ::closed-at)))

(defn terminal?
  "True when the receipt carries a terminal fact.
  THE ONE PRESENCE QUESTION over receipts (owner ruling 2026-07-28): a
  receipt settles by asserting `result-edn`, `:seon.eval/missing` or
  `error`, and is cut by `interrupted-at`. A receipt carrying none of the
  four is running — there is no status label to read.
  `seon.cluster.work/next-ordinal` asks the same question as a query; this
  is its map twin.

  MISSING IS A TERMINAL FACT. A form that ran and whose value went over the
  storage bound, or that the walk could only describe, is DONE: reading its
  absence of `result-edn` as `running` would re-attempt a form that already
  executed, which is the one thing the crash model forbids."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean (or (:seon.eval/value receipt)
               (:seon.eval/missing receipt)
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

(defn- unanswered-background-results
  [db agent-eid]
  (->> (db/q
        '[:find ?receipt ?effect-id ?tx
          :in $ ?agent
          :where
          [?receipt :seon.effect/to ?agent ?tx]
          [?receipt :seon.effect/id ?effect-id]
          (or-join [?receipt]
                   [?receipt :seon.effect/result-edn]
                   [?receipt :seon.effect/interrupted-at])
          (not [_ :seon.turn/background-results ?receipt])]
        db agent-eid)
       (sort-by (fn [[_ effect-id tx]] [tx effect-id]))
       (mapv first)))

(defn open-for-agent
  "Read the agent's open turn id from its owning ref and absence of closed-at."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/ref]
                  [:or [:maybe :seon.turn/id] :seon.error/value]]}
  [database agent-ref]
  (let [row (db/pull database [:db/id] agent-ref)]
    (if (:seon.error/kind row)
      row
      (when-let [agent-eid (:db/id row)]
        (db/q '[:find ?id .
                :in $ ?agent
                :where [?turn :seon.turn/agent ?agent]
                [?turn :seon.turn/id ?id]
                (not [?turn :seon.turn/closed-at])]
              database agent-eid)))))

(defn open-call
  "Open one turn inside the writer; refuse an existing id or open turn.
  The agent's owning ref and closed-at facts supply the decision in the
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
                        [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id agent trigger opened-at starting-ns]
         situation :seon.turn.work/situation} request
        agent-eid (:db/id (db/pull db [:db/id] agent))
        run-tempid (str "seon.turn/" id)
        background-results (unanswered-background-results db agent-eid)]
    (cond
      (nil? agent-eid) (refuse! `open-call ::no-such-agent request)
      (some? (current-run db id)) (refuse! `open-call ::run-exists request)

      (some? (open-for-agent db agent-eid))
      (refuse! `open-call ::agent-already-running request)

      :else [(cond-> {:db/id run-tempid
                      ::id id
                      ::agent agent-eid
                      :seon.turn.work/situation (or situation :call)
                      ::opened-at opened-at}
               trigger (assoc ::trigger trigger)
               starting-ns (assoc ::starting-ns starting-ns)
               (seq background-results)
               (assoc ::background-results background-results))])))

(defn close-tx
  "Transaction data closing an open turn."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]

                             [::closed-at ::closed-at]
                             [::undisposed-at {:optional true}
                              ::undisposed-at]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'close-call request]])

(defn close-call
  "Close the open turn inside the writer by asserting closed-at.
  The owning agent becomes available through the open-turn query."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]

                        [::closed-at ::closed-at]
                        [::undisposed-at {:optional true}
                         ::undisposed-at]]]
                  [:vector :some]]}
  [db request]
  (let [run (require-open-run db `close-call request)]
    (cond-> [[:db/add (:db/id run) ::closed-at (::closed-at request)]]
      (::undisposed-at request)
      (conj [:db/add (:db/id run) ::undisposed-at
             (::undisposed-at request)]))))

(defn- plan-tx-for-author
  [author request]
  [[:db.fn/call #'plan-call
    (assoc request :seon.cluster.eval/author author)]])

(defn plan-tx
  "Transaction data freezing one agent-authored form plan on the open turn."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]

                             [::plan-digest ::plan-digest]
                             [::reply {:optional true} ::reply]
                             [::reply-blob {:optional true} ::reply-blob]
                             [::reply-size {:optional true} ::reply-size]
                             [::starting-ns {:optional true} ::starting-ns]
                             [:seon.cluster.eval/at {:optional true}
                              :seon.cluster.eval/at]
                             [::sources :seon.cluster.reply/sources]]]
                  [:vector :some]]}
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
;;; refuse `:seon.cluster.message/ambiguous-about` for every problem that
;;; ever existed. The families are merged; the ambiguity class is
;;; unwritable, not qualified away.

(defn next-id
  "Identify the next turn from branch, agent, and its ordinal.

  The caller hands this identity to the writer. Compaction retains turns;
  competing openers derive the same identity and the writer admits one."
  {:malli/schema [:=> [:cat :seon.db/database-value :string
                       :seon.cluster.agent/id] ::id]}
  [database branch-id agent-id]
  (id/digest 12
             [::id branch-id agent-id
              (or (db/q '[:find (count ?turn) . :in $ ?agent-id
                          :where [?agent :seon.cluster.agent/id ?agent-id]
                          [?turn :seon.turn/agent ?agent]]
                        database agent-id) 0)]))

(defn receipt-identity
  "The `:seon.cluster.eval/id` of one run's ordinal.
  Agent-facing: this is the problem identity an owner is asked to repair,
  and the one `seon.cluster.work/problem-id` returns."
  {:malli/schema [:=> [:cat ::id :seon.cluster.eval/ordinal]
                  :seon.cluster.eval/id]}
  [run-id ordinal]
  (id/evaluation run-id ordinal))

(defn plan-digest
  "The SHA-256 identity of one ordered source plan."
  {:malli/schema [:=> [:catn [:sources :seon.cluster.reply/sources]]
                  ::plan-digest]}
  [sources]
  (id/digest 64 sources))

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
  "Freeze the plan, inside the transaction.
  Assert the digest and the
  owned ordered evaluations. Refuses unless the turn is open
  and has no existing `::plan-digest` —
  concurrent replies are mutually exclusive because the second one
  reads the first one's digest and refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]

                        [::plan-digest ::plan-digest]
                        [::reply {:optional true} ::reply]
                        [::reply-blob {:optional true} ::reply-blob]
                        [::reply-size {:optional true} ::reply-size]
                        [::starting-ns {:optional true} ::starting-ns]
                        [:seon.cluster.eval/author
                         :seon.cluster.eval/author]
                        [:seon.cluster.eval/at {:optional true}
                         :seon.cluster.eval/at]
                        [::sources :seon.cluster.reply/sources]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id plan-digest reply reply-blob reply-size sources starting-ns]
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
               [?agent :seon.cluster.agent/namespace ?namespace]
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
    (when (some? (::plan-digest run))
      (refuse! `plan-call ::plan-frozen request))
    (when-not (= :call (:seon.turn.work/situation run))
      (refuse! `plan-call ::not-call-situation request))
    (when-not starting-namespace
      (refuse! `plan-call ::starting-namespace-missing request))
    (when (and (= :system author)
               requested-starting-namespace
               (not= requested-starting-namespace agent-namespace))
      (refuse! `plan-call ::starting-namespace-changed request))
    (into (cond-> [[:db/add run-eid ::plan-digest plan-digest]
                    [:db/add run-eid ::starting-ns
                     (str "namespace:" starting-namespace)]]
            reply (conj [:db/add run-eid ::reply reply])
            reply-blob (conj [:db/add run-eid ::reply-blob reply-blob])
            reply-size (conj [:db/add run-eid ::reply-size reply-size]))
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
                             [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'open-call request]])

(declare receipt-start-tx)

(defn system-run-call
  "Open a system-authored turn and freeze its sources at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.turn/system-run-request]
                  :seon.store/transaction-data]}
  [database request]
  (let [{agent-id :seon.cluster.agent/id
         ::keys [id opened-at starting-ns sources trigger]} request
        opened (first (open-call database
                                 (cond-> {::id id
                                          ::agent [:seon.cluster.agent/id agent-id]
                                          ::opened-at opened-at}
                                   trigger (assoc ::trigger trigger))))
        agent-namespace (db/q '[:find ?name . :in $ ?agent
                                :where [?agent :seon.cluster.agent/namespace ?ns]
                                [?ns :seon.ns/name ?name]]
                              database (::agent opened))
        requested-namespace (resolve-namespace-name database starting-ns)
        namespace-name (or requested-namespace agent-namespace)]
    (when-not namespace-name
      (refuse! `system-run-call ::starting-namespace-missing request))
    (when (and requested-namespace (not= requested-namespace agent-namespace))
      (refuse! `system-run-call ::starting-namespace-changed request))
    (into [(merge opened
                  (select-keys request [::plan-digest ::reply ::reply-blob ::reply-size])
                  {::starting-ns (str "namespace:" namespace-name)})]
          (source-rows database id (:db/id opened) 0 namespace-name
                       :system opened-at sources))))

(defn system-run-tx
  "Open, plan, and start every evaluation of one system-authored run.

  The caller owns the ordered sources and their digest. The ordinary run
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
                  [:vector :some]]}
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
                    (or [?receipt :seon.eval/value _]
                        [?receipt :seon.eval/missing _]
                        [?receipt :seon.cluster.eval/error _]
                        [?receipt :seon.cluster.eval/interrupted-at _])]
                  db run-eid (dec ordinal))))]
    (when (some? (::plan-digest held))
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
  (let [{agent-id :seon.cluster.agent/id
         run-id ::id
         opened-at ::opened-at
         starting-ns ::starting-ns
         trigger ::trigger} request
        namespace-name (if (vector? starting-ns)
                         (second starting-ns)
                         starting-ns)
        namespace-tempid (str "namespace:" namespace-name)]
    (into [] cat
          [[{:db/id namespace-tempid :seon.ns/name namespace-name}]
           (open-tx
            (cond-> {::id run-id
                     ::agent [:seon.cluster.agent/id agent-id]
                     ::starting-ns [:seon.ns/name namespace-name]
                     :seon.turn.work/situation :generate
                     ::opened-at opened-at}
              trigger (assoc ::trigger trigger)))])))

(defn refresh-tx
  "Transaction data refreshing one prior system-authored form."
  {:malli/schema
   [:=> [:cat :seon.cluster.eval/id] [:vector :some]]}
  [prior-form-id]
  [[:db.fn/call #'refresh-call prior-form-id]])

(defn- refresh-run-id
  [db prior-form-id]
  (id/digest 12 [::refresh prior-form-id (db/basis-t db)]))

(defn refresh-call
  "Append one ordinary system run from a prior refreshable evaluation."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.eval/id]
    [:vector :some]]}
  [db prior-form-id]
  (let [request {:seon.cluster.eval/id prior-form-id}
        ;; ONE ENTITY: the frozen source and its terminal facts are the same
        ;; evaluation, so this reads it once instead of joining a twin.
        prior
        (db/pull db
                 '[* {:seon.cluster.eval/run
                      [:db/id :seon.turn/id
                       {:seon.turn/agent
                        [:db/id :seon.cluster.agent/id]}]}
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
            plan-digest
            (id/digest 64 [source (:seon.ns/name namespace)])
            open-rows
            (open-call db
                       {::id run-id
                        ::agent (:db/id (::agent prior-run))
                        ::opened-at opened-at})
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
              [[:db/add run-tempid ::plan-digest plan-digest]
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
    [:vector :some]]}
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
    [:vector :some]]}
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
  (if-let [form (when (:seon.program/row request)
                  (settlement-form database request))]
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
     [:vector :some]]
    [:=> [:cat :seon.db/database-value
          :seon.cluster.eval/settle-request]
     [:vector :some]]]}
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
         [:vector :seon.cluster.eval/settle-request]] [:vector :some]]}
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
   [:=> [:cat [:vector :seon.cluster.eval/settle-request]] [:vector :some]]}
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
           [?receipt :seon.eval/value _ ?tx]
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
          (when (seq schema-keys) (schema/projection-from-database db))
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
              (schema/projection-from-database db))
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
  [:seon.eval/value
   :seon.eval/missing
   :seon.eval/size
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
     (-> (select-keys run [::id ::agent ::starting-ns ::opened-at ::closed-at
                           ::reply ::reply-blob ::reply-size
                           ::plan-digest])
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
        agent-data (db/pull database [:db/id :seon.cluster.agent/id] agent)
        agent-eid (when (:seon.cluster.agent/id agent-data) (:db/id agent-data))
        starting-namespace (resolve-namespace-name database starting-ns)
        run-eid (str "seon.turn/" id)
        run (assoc (select-keys request
                               [::id ::opened-at ::closed-at
                                ::reply ::reply-blob ::reply-size])
                   ::agent agent-eid
                   ::starting-ns starting-namespace
                   ::plan-digest (plan-digest sources))
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
                  (not [?turn :seon.turn/closed-at _])]
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
                    ::starting-ns (str "namespace:" starting-namespace))]
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
        (merge (select-keys request [::id ::agent ::starting-ns ::opened-at ::closed-at])
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
          [:seon.eval/value {:optional true}
           :seon.eval/value]
          [:seon.eval/missing {:optional true} :seon.eval/missing]
          [:seon.eval/size {:optional true} :seon.eval/size]
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
    [:vector :some]]}
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
                  [:vector :some]]}
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
                  [:vector :some]]}
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
              [:db/add (:db/id run) ::closed-at now])))))

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
  (some-> (:seon.eval/value receipt)
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
                                            (:my.run/disposition last-value))
                         (first missing))]
      {:seon.cluster.eval/ordinal ordinal
       ::missing-results (count missing)})))

(defn render-ai
  "`:seon.render/ai` — one run, as the agent's own history of it.

  STATE IS PRESENCE, read exactly as the model stores it: a turn with a `closed-at` is over, one with an error
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
      (let [opened (get unit ::opened-at)
            receipts (when db (run-receipts db (:db/id unit)))
            cut (when db (interrupted-warning receipts))
            never-started
            (when (and db
                       (::closed-at unit)
                       (::plan-digest unit)
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
                                       (= :wait (:my.run/disposition %)))
                              (:my.run/note %))))
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

              (::error unit)
              (str "It did not run: " (::error unit)
                   " Nothing was retried, and nothing it asked for ran.")

              (and db (some? (::closed-at unit))
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

              (::undisposed-at unit)
              (str "It ended without my.run/complete or my.run/wait. "
                   "Its trigger remains unanswered; nothing was retried.")

              (some? (::closed-at unit)) "It completed."
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
                :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
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
               (let [parsed ((requiring-resolve 'seon.cluster.loop/planned-sources)
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
                         :seon.turn/basis-t basis)]
       (assoc latest (source-key source) source)))
   {}
   (sort-by (juxt first second)
            (db/q '[:find ?t ?ordinal
                    (pull ?evaluation
                          [* {:seon.cluster.eval/ns [:seon.ns/name]}
                           {:seon.cluster.eval/read-evidence [*]}])
                    :in $ ?id
                    :where [?agent :seon.cluster.agent/id ?id]
                    [?turn :seon.turn/agent ?agent]
                    [?turn :seon.turn/id _ ?t]
                    [?evaluation :seon.cluster.eval/run ?turn]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                    [?evaluation :seon.cluster.eval/source]]
                  database agent-id))))

(defn- read-only-evaluation? [database evaluation]
  (let [events (source-events evaluation)
        evaluation-id (:db/id evaluation)
        run-id (get-in evaluation [:seon.cluster.eval/run :db/id])]
    (and (seq (:seon.cluster.eval/read-evidence evaluation))
         (vector? events)
         (not-any? #(program/declaration-row % :all :agent) events)
         (nil? (db/q '[:find ?transaction . :in $ ?evaluation
                        :where [?transaction :seon.db/receipt ?evaluation]]
                      database evaluation-id))
         (nil? (db/q '[:find ?effect . :in $ ?turn ?ordinal
                        :where [?effect :seon.effect/run ?turn]
                        [?effect :seon.effect/form-ordinal ?ordinal]]
                      database run-id (:seon.cluster.eval/ordinal evaluation))))))

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
                         (filter #(read-only-evaluation? database %)
                                 (sort-by (juxt :seon.turn/basis-t
                                                :seon.cluster.eval/ordinal)
                                          (vals latest))))))))

(defn system-turn
  "Project the declared opening and every distinct retained read form.
  Unchanged reads contribute no evaluation. With write? true, save the exact
  evaluated sources as one closed system turn with no provider attempt."
  {:malli/schema [:=> [:cat :seon.turn/system-request]
                  [:or :seon.turn/system-result :seon.error/value]]}
  [{handle :seon.turn.loop/cluster
    agent-id :seon.cluster.agent/id
    write? :seon.turn/write?}]
  (let [connection (:seon.db/connection handle)
        database @connection
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
        :seon.error/diagnostic-member :seon.cluster.agent/id
        :seon.error/diagnostic-expected :seon.cluster.agent/id
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause ::agent-namespace-missing
        :seon.error/diagnostic-evidence {}})

      (:seon.error/kind declared) declared

      :else
      (let [plan (system-plan database (:seon.turn/forms declared)
                              (latest-evaluations database agent-id))
            selected (filterv #(not= :unchanged (:seon.turn/status %)) plan)
            previews (mapv
                      #((requiring-resolve 'seon.cluster.loop/preview-sources)
                        {:seon.turn.loop/cluster handle
                         :seon.db/db database
                         :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :seon.cluster.agent/id agent-id
                         :seon.ns/name (:seon.ns/name %)
                         :seon.cluster.reply/text (:seon.cluster.eval/source %)
                         :seon.sci.admit/caps (:seon.sci.admit/caps handle)})
                      selected)]
        (or (some #(when (:seon.error/kind %) %) previews)
            (let [evaluated (mapv (fn [ordinal source preview]
                                    (cond-> (assoc (first (:seon.turn.loop/evaluated-sources preview))
                                                   :seon.cluster.eval/ordinal ordinal)
                                      (:seon.cluster.eval/comment source)
                                      (assoc-in [:seon.turn.loop/admitted-form
                                                 :seon.cluster.eval/comment]
                                                (:seon.cluster.eval/comment source))))
                                  (range) selected previews)
                  text-by-source
                  (into {} (map (fn [source item]
                                  [(source-key source)
                                   (repl/text
                                    (merge (:seon.turn.loop/admitted-form item)
                                           (:seon.sci.eval/evaluation item)
                                           {:seon.ns/name (:seon.ns/name source)}))])
                                selected evaluated))
                  result {:seon.render.walk/units (:seon.render.walk/units declared)
                          :seon.turn/forms
                          (mapv #(if-let [text (get text-by-source (source-key %))]
                                   (assoc % :seon.turn/text text) %) plan)}]
              (if (and write? (seq evaluated))
                (let [turn-id (next-id database (:seon.cluster/name handle) agent-id)
                      prepared (record-evaluated-tx
                                {:seon.turn.loop/cluster handle
                                 :seon.db/db database
                                 :seon.turn/id turn-id
                                 :seon.turn/agent [:seon.cluster.agent/id agent-id]
                                 :seon.turn/starting-ns [:seon.ns/name namespace-name]
                                 :seon.turn/reply
                                 (str/join "\n" (map :seon.cluster.eval/source selected))
                                 :seon.turn/opened-at
                                 (:seon.turn/opened-at (first previews))
                                 :seon.turn/closed-at
                                 (:seon.turn/closed-at (peek previews))
                                 :seon.turn.loop/evaluated-sources evaluated})
                      report (blob/with-publication!
                              connection (:seon.blob/staged-writes prepared)
                              #(db/transact! connection (:seon.db/tx-data prepared)))]
                  (if (:seon.error/kind report) report
                      (assoc result :seon.turn/id turn-id)))
                result)))))))

(defn compact-call
  "Retract one idle agent's evaluations at the database writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
                  :seon.store/transaction-data]}
  [database agent-id]
  (let [agent-eid (db/q '[:find ?agent . :in $ ?id
                         :where [?agent :seon.cluster.agent/id ?id]]
                       database agent-id)
        open-id (when agent-eid
                  (db/q '[:find ?id . :in $ ?agent
                          :where [?turn :seon.turn/agent ?agent]
                          [?turn :seon.turn/id ?id]
                          (not [?turn :seon.turn/closed-at _])]
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
                    :seon.error/diagnostic-member :seon.cluster.agent/id
                    :seon.error/diagnostic-expected :seon.cluster.agent/id
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
  [{connection :seon.db/connection agent-id :seon.cluster.agent/id}]
  (db/transact! connection [[:db.fn/call #'compact-call agent-id]]))

(defn virtual-turn!
  "Submit a fixture reply to the ordinary agent proc, without a provider.
  Returns its durable turn identity; completion is observed in its facts."
  {:malli/schema [:=> [:cat :seon.turn/virtual-request]
                  [:or :seon.cluster.agent/source-submission-result
                   :seon.error/value]]}
  [request]
  ((requiring-resolve 'seon.cluster.agent/submit-source!)
   (update request :seon.cluster.reply/text #(or % "(+ 1 1)"))))
