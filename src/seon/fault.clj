(ns seon.fault
  "The one core-fault route (AGENTS.md \"The error policy\"): a producer calls
  `fault!` on its own thread and gets a receipt beside its result. There is no
  fault channel or committer.

  ## Recommendations

  - Call [[fault!]] from the failing boundary itself; call [[record!]] only when
    you need the raw recording tuple.
  - Whether a fault is still open is not stored here: ask
    [[seon.problems/open-faults]]; a fix elsewhere is recorded with
    [[seon.problems/resolve!]].
  - Declared agent mistakes are returned to the agent, never faulted; see
    [[seon.error.refusal/diagnostic]]. The wake is the writer's decision,
    [[seon.error/commit-call]]."
  (:require [clojure.core.async.flow :as-alias flow.core]
            [seon.batch]
            [seon.blob :as blob]
            [seon.cluster.branch :as cluster.branch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as refusal]
            [seon.render.value :as render.value]))

(defn- tagged-run
  "The tagged agent's open turn, or nil.
  Attribution is STRUCTURAL: an agent graph's fault arrives tagged with
  its agent (structural provenance from the error-channel join), so
  attribution is that agent's one open turn — exact under concurrency,
  where the serial-era global query stopped being. That global query
  (`attributed-run`) is deleted at F2 §3.3."
  [db agent-id]
  (db/q '[:find ?id .
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?run :seon.turn/agent ?agent]
         (not [?run :seon.turn/closed-tx])
         [?run :seon.turn/id ?id]]
       db agent-id))

(def ^:private reference-members
  "The references a producer may name on a fault; each is stored as a
  reference on the occurrence (`seon.error/commit-call`), never its world."
  [:seon.effect/id :seon.error/related-occurrence :seon.error/obligation])

(defn- delivered-to
  "The recipients of the notifications the writer selected for `signature`'s
  current opening in this transaction: the `:seon.error/notified` messages it
  asserted, plus the prior selection as of its own `:db-before` that it did
  not retract. Read from this transaction's own report, never the batch's
  `:db-after` as the selection (Datahike's writer hands every report of a
  batch the batch's database, `datahike/writer.cljc:229-264`)."
  {:malli/schema [:=> [:cat :seon.db/transaction-report :seon.error/signature] [:set :seon.agent/id]]}
  [{:keys [tx-data db-before db-after]} signature]
  (let [changes (filter #(= :seon.error/notified (:a %)) tx-data)
        retracted (into #{} (comp (remove :added) (map :v)) changes)
        prior (->> (db/pull db-before [{:seon.error/notified [:db/id]}] [:seon.error/signature signature])
                   :seon.error/notified (map :db/id))
        messages (into (into #{} (comp (filter :added) (map :v)) changes)
                       (remove retracted) (when (sequential? prior) prior))]
    (into #{} (keep #(get-in (db/pull db-after [{:seon.message/to [:seon.agent/id]}] %)
                             [:seon.message/to :seon.agent/id]))
          messages)))

(defn- landed-occurrence
  "The occurrence a committed recording landed on: the writer chooses among
  the recording's slots (`seon.error/commit-call`), and every landing writes
  its last instant."
  {:malli/schema [:=> [:cat :seon.db/transaction-report]
                  [:or :nil [:tuple [:= :seon.error.occurrence/id] :seon.error.occurrence/id]]]}
  [{:keys [tx-data db-after]}]
  (let [attribute (fn [datom] (let [a (:a datom)] (if (keyword? a) a (:db/ident (db/pull db-after [:db/ident] a)))))]
    (some (fn [datom]
            (when (and (:added datom) (= :seon.error.occurrence/last-at (attribute datom)))
              (when-let [id (:seon.error.occurrence/id (db/pull db-after [:seon.error.occurrence/id] (:e datom)))]
                [:seon.error.occurrence/id id])))
          tx-data)))

;;; FOLDED REPEATS (task e33c4c026538, owner's storage rule 2026-09-28). A
;;; repeat the writer would only count (`seon.error/repeat-of`) makes no commit
;;; of its own: its count waits here, per branch and occurrence, and rides the
;;; next commit on that branch (the next fault write here, or the settle of the
;;; running evaluation through `folded-tx`). No stored place holds a count not
;;; yet written, so this process-lifetime map does; a restart loses at most
;;; those counts, as every in-memory accumulator restarts from zero (health
;;; plan §2.6).
(defonce ^:private folded (java.util.concurrent.ConcurrentHashMap.))

(defn- fold!
  "Count one repeat of `occurrence` on `branch`, held until a commit carries it."
  {:malli/schema [:=> [:cat :seon.store/branch :seon.error.occurrence/id :inst] :nil]}
  [branch occurrence at]
  (.merge ^java.util.concurrent.ConcurrentHashMap folded [branch occurrence] [1 at]
          (reify java.util.function.BiFunction
            (apply [_ [n held-at] [m at]] [(+ n m) (if (.after ^java.util.Date held-at at) held-at at)])))
  nil)

(defn folded-counts
  "The repeats held for `branch`, by occurrence: `[count last-at]` not yet
  written. What a health read adds to the stored counts."
  {:malli/schema [:=> [:cat :seon.store/branch]
                  [:map-of :seon.error.occurrence/id [:tuple pos-int? :inst]]]}
  [branch]
  (into {} (keep (fn [[[held occurrence] counted]] (when (= held branch) [occurrence counted]))) folded))

(defn folded-tx
  "Take the repeats held for `connection`'s branch as transaction data for a
  commit that happens anyway: one `seon.error/bump-call` per occurrence. A
  caller whose commit then fails hands them back with `unfold!`.

  ## Examples

  (into settle-tx (seon.fault/folded-tx connection))"
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.store/transaction-data]}
  [connection]
  (let [branch (get-in (db/db connection) [:config :branch])]
    (into []
          (keep (fn [occurrence]
                  (when-let [[n at] (.remove ^java.util.concurrent.ConcurrentHashMap folded [branch occurrence])]
                    [:db.fn/call #'error/bump-call {:seon.error.occurrence/id occurrence
                                                    :seon.error.occurrence/count n
                                                    :seon.error.occurrence/last-at at}])))
          (keys (folded-counts branch)))))

(defn unfold!
  "Hand back repeats `folded-tx` took when the commit carrying them failed."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction-data] :nil]}
  [connection tx]
  (let [branch (get-in (db/db connection) [:config :branch])]
    (doseq [[_ _ {occurrence :seon.error.occurrence/id n :seon.error.occurrence/count at :seon.error.occurrence/last-at}] tx]
      (.merge ^java.util.concurrent.ConcurrentHashMap folded [branch occurrence] [n at]
              (reify java.util.function.BiFunction
                (apply [_ [a held-at] [b at]] [(+ a b) (if (.after ^java.util.Date held-at at) held-at at)]))))
    nil))

(defn record!
  "Commit one escaped Throwable as one durable fact per delivery.

  Total: it never throws. `fact` and its content signature are derived before the
  transaction is attempted, and recurrence is the query-derived count of
  committed facts.

  ## Edge cases

  - An untagged fault (the cluster graph's own, from the armer or the render
    proc) attributes to no run. That is correct, not missing: it is not a
    run's fault.
  - When the fault's agent branch is not the cluster branch and this process
    holds the cluster branch open, it writes twice: once on the origin, once on
    the cluster branch naming the origin, so the record outlives the branch's
    retirement. A cluster this process does not run (a fixture's name) gets no
    copy, and neither does a fault whose agent the cluster branch does not
    place on the origin branch (no agent entity, or one only the origin
    places there): it is recorded only on the connection's own branch (a test
    member's world records there, never on the cluster it was forked from).
  - An unreadable agent branch records on the connection's own branch; the
    fault is not lost.
  - `outcome` is the first write that did not commit, even if the second did.
  - A failure before the fact exists returns a message-only fact with no
    signature, so Flow cannot collapse repeats of it.

  ## Costs

  - At most two transactions (origin, then the cluster copy), each preceded by
    a blob publication when the evidence exceeds
    `:seon.config.error/max-evidence-bytes`; it reads the effective config and
    one run query per call. Not timed: default's profiler held no cells for it
    on 2026-09-26.

  ## Errors

  - A refused or failed write ([[:seon.db/transaction-refused]]) is returned as
    `outcome`, never thrown; the caller, [[fault!]], decides to stop."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.boot/cluster-name :seon.db.process/id
                       :seon.sci.admit/caps :seon.error/recording-observation]
                  :seon.fault/recording]}
  [connection cluster-name process caps observation]
  (try
    (let [db (db/db connection)
          dials (config/effective db cluster-name)
          source-fault (:seon.error/source observation)
          agent-id (:seon.agent/id source-fault)
          run-id (when agent-id (tagged-run db agent-id))
          request
          (cond-> {:seon.schema/projection (db/carried-projection db)
                   :seon.error/source source-fault
                   :seon.error/declared-schema (:seon.error/declared-schema observation)
                   :seon.error/id (str (random-uuid))
                   :seon.error/at (java.util.Date.)
                   :seon.error/process process
                   :seon.sci.admit/caps caps
                   :seon.error/basis-t (db/basis-t db)
                   :seon.config.error/recurrence-limit
                   (:seon.config.error/recurrence-limit dials)
                   ;; THE FAULT FAMILY'S OWN BOUND rides the request the
                   ;; committer builds, so `error/prepare` AND
                   ;; `error/commit-tx` — both of which declare it required —
                   ;; read the one dial this cluster's effective config
                   ;; carries. Handing it to only one of the two is how every
                   ;; core fault became unrecordable: `commit-tx` refused its
                   ;; contract and the operator printed the refusal about
                   ;; itself instead of the fault.
                   :seon.config.error/max-evidence-bytes
                   (:seon.config.error/max-evidence-bytes dials)}
            (:seon.config.error/escalate-to dials)
            (assoc :seon.config.error/escalate-to
                   (:seon.config.error/escalate-to dials))
            run-id (assoc :seon.turn/id run-id)
            agent-id (assoc :seon.agent/id agent-id)
            ;; the declared references a producer names, never its world
            true (merge (select-keys (:seon.error/references observation) reference-members)))
          ;; The fault family's own bound decides how much evidence the
          ;; durable fact keeps; a capped fact's whole evidence is staged by
          ;; `error/evidence-stage` and published below with its commit.
          {prepared-fact :seon.error/fact staged :seon.blob/staged-writes}
          ;; The boundary's declared layer and operation name the observed
          ;; site of a failure that declares none of its own.
          (error/evidence-stage connection
                                (error/prepare (merge request (select-keys observation [:seon.error/layer
                                                                                        :seon.error/operation]))))
          recording (error/recording db (assoc request :seon.error/fact prepared-fact))
          fact (:seon.error/fact recording)]
      (try
        ;; ONE WRITER, TWO TARGETS: the branch where the fault happened (its
        ;; tagged agent's branch, else this connection's) and the cluster
        ;; branch, whose copy names that origin and so outlives its retirement.
        (let [here (get-in db [:config :branch])
              placed-on (fn [database]
                          (when agent-id
                            (db/q '[:find ?branch . :in $ ?id
                                    :where [?agent :seon.agent/id ?id] [?agent :seon.agent/branch ?branch]]
                                  database agent-id)))
              agent-branch (placed-on db)
              ;; an unreadable agent branch records here: the fault is not lost
              origin (if (keyword? agent-branch) agent-branch here)
              ;; the producer's version that ran is the origin's: the cluster
              ;; need not hold the definition (an agent's own, before merge),
              ;; so its copy carries that digest (seon.error/resolution reads it)
              producer (:seon.error/producer prepared-fact)
              origin-digest (when (and producer (nil? (:seon.error/producer-digest source-fault)))
                              (some-> (if (= origin here) db (some-> (db/branch-connection db origin) db/db))
                                      (db/pull [:seon.program/definition-digest] [:seon.fn/sym producer])
                                      :seon.program/definition-digest))
              commit-on
              (fn [branch]
                (let [target (if (= branch here) connection (db/branch-connection db branch))
                      data (cond
                             (and (= branch here) (= branch origin)) recording
                             target (error/recording
                                     (db/db target)
                                     (cond-> (assoc request :seon.error/fact prepared-fact)
                                       (not= branch origin) (assoc :seon.error.occurrence/branch origin)
                                       (and (not= branch origin) (string? origin-digest))
                                       (assoc-in [:seon.error/source :seon.error/producer-digest] origin-digest))))
                      ;; a repeat the writer would only count is folded, no commit
                      only-count (when (and target (:seon.db/tx-data data))
                               (error/repeat-of (db/db target) data))
                      carried (when (and target (:seon.db/tx-data data) (not (string? only-count)))
                                (folded-tx target))
                      result (when (and (:seon.db/tx-data data) (not (string? only-count)))
                               ;; the target's own custody: a write to another branch is refused otherwise
                               (db/call-with-custody
                                {:seon.db/connection target}
                                #(blob/with-publication! target staged
                                   (fn [] (db/transact! target (into (vec (:seon.db/tx-data data)) carried))))))]
                  (when (string? only-count) (fold! branch only-count (:seon.error/at prepared-fact)))
                  (when (and (seq carried) (not (db/database-value? (:db-after result)))) (unfold! target carried))
                  [(cond
                     (string? only-count) :seon.fault/folded
                     (nil? target)
                     {:seon.error/at (java.util.Date.) :seon.error/layer :seon.fault/record
                      :seon.error/operation `record! :seon.store/branch branch
                      :seon.error/message (str "No open connection to branch " (pr-str branch)
                                               " in this process, so its record of this fault is not written.")}
                     (nil? result) data
                     (db/database-value? (:db-after result)) :seon.flow/committed
                     :else result)
                   (or (when (db/database-value? (:db-after result)) (landed-occurrence result))
                       (:seon.error.occurrence/ref data)
                       (when (string? only-count) [:seon.error.occurrence/id only-count]))
                   (when (db/database-value? (:db-after result))
                     (delivered-to result (:seon.error/signature prepared-fact)))]))
              home (cluster.branch/cluster-branch cluster-name)
              home-connection (if (= home here) connection (db/branch-connection db home))
              ;; The copy is the record of one of the CLUSTER's agents'
              ;; branches, kept past that branch's retirement, so the cluster
              ;; itself must place the agent there. A test member's world names
              ;; its cluster and may create agents placed on the world's own
              ;; branch; the cluster places none of them there, so their faults
              ;; stay where they happened (agent-test's backstop fault was
              ;; copied onto default, 2026-09-27). No copy when this process
              ;; runs no such cluster (a fixture's name).
              copy? (and (keyword? agent-branch) (not= home origin) (some? home-connection)
                         (= origin (placed-on (db/db home-connection))))
              [[outcome occurrence recipients] :as outcomes] (mapv commit-on (cond-> [origin] copy? (conj home)))]
          [fact (or (some #(when-not (#{:seon.flow/committed :seon.fault/folded} %) %) (map first outcomes)) outcome)
           occurrence recipients])
        (catch Throwable failure
          [fact failure (:seon.error.occurrence/ref recording) nil])))
    (catch Throwable failure
      ;; `error/commit-tx` is total. This last-resort shape is only for a
      ;; failure before its fact exists, so no content signature is available
      ;; for Flow to collapse honestly.
      (let [fault (:seon.error/source observation)
            cause (if (instance? Throwable fault) fault (::flow.core/ex fault))
            ;; never a printed copy of the fault: its value stays in the tuple's cause
            message (or (:seon.error/message fault)
                        (when (instance? Throwable cause)
                          (str (.getName (class cause)) ": " (ex-message cause)))
                        "A core fault failed before its fact existed.")]
        [{:seon.error/message message} failure nil nil]))))


(defn carried-receipt
  "The receipt `failure`'s cause chain already carries, if any.

  An unrecorded fault's throw carries its receipt; a later `fault!` finds it
  anywhere in the chain.

  ## Costs

  - 3.2 µs unarmed and 4.1 µs armed on a 13-link `ex-info` chain with no
    receipt (default, 2026-09-26); linear in the chain length.

  ## Known issues

  - A cyclic cause chain (`(.initCause a b)` where `b`'s cause is `a`, legal in
    the JDK) never returns and cannot be cancelled: the walk has no identity
    check or bound. A two-link cycle held one core for about seven minutes on
    2026-09-26. [[seon.render.value/core-text]] walks the same chain safely. Task
    [[task:465ff60ccda0]]."
  {:seon/tasks #{'seon.fault/carried-receipt-hangs-on-cyclic-cause-chain}
   :malli/schema [:=> [:cat [:or :seon.error/throwable :map]] [:or :nil :seon.fault/recorded]]}
  [failure]
  (when (instance? Throwable failure)
    (some (comp :seon.fault/recorded ex-data)
          (take-while some? (iterate ex-cause failure)))))

(defn print-unrecorded!
  "Write a fault no store holds onto stderr through the value printer: `evidence` as
  bounded text, then each Throwable's bounded cause chain. The one edge where
  a fault that recording could not take is still delivered.

  ## Costs

  - Armed on default 2026-09-28, the value printer's evidence text for a map holding a
    5,000,000-character string, a 200,000-element vector, an SCI context and an
    atom holding both: 401 characters in 334 µs, 44 KB allocated; `pr-str` of
    the same map built 12.6 million characters in 113 ms."
  {:malli/schema [:=> [:cat :map [:sequential :seon.error/throwable]] :nil]}
  [evidence throwables]
  (let [text (apply str (interpose "\n" (cons (render.value/core-text evidence)
                                              (map render.value/core-text throwables))))]
    (doto ^java.io.Writer *err* (.write text) (.write "\n") (.flush)))
  nil)

(defn fault!
  "Record one core fault on the caller's thread and wake its agent.

  The caller continues with the receipt beside its result. When storage
  fails (a refused, thrown or outcome-unknown write) the execution owner's
  `:seon.fault/stop-request!` runs and the fault is thrown: the one case in
  which a graph stops. No write is retried.

  ## Edge cases

  - A failure already carrying a receipt ([[carried-receipt]]) was recorded by
    this propagation: it is rethrown, never recounted.
  - An execution environment without admission caps gets them derived from the
    cluster's effective config, as boot does.
  - The declared schema falls back: the context's, then the throwable's
    `ex-data` metadata, then `:seon.flow/exception-error`.
  - If `:seon.fault/stop-request!` throws too, that failure is added to the
    thrown fault as suppressed.
  - `:seon.fault/delivered-to` names the recipients the writer selected for
    the fault's current opening, read from the transaction's own report.
  - A repeat the writer would only count (every capped occurrence of its
    signature and producer digest written, the signature open) makes no
    commit: `record!` folds it (`folded-tx`) and the receipt says
    `:seon.fault/folded? true` beside `:seon.fault/committed? true`, the count
    riding the next commit on its branch.
  - Without an agent in its context, the fault is attributed to the failing
    world's agent (`seon.batch/*world*`), so its cluster copy is written.

  ## Recommendations

  - Pass `:seon.fault/stop-request!` from the execution owner that must stop
    when the fault cannot be stored; without it nothing stops.
  - Do not retry around it.

  ## Errors

  - Storage failure throws an `ex-info` whose data holds the
    `:seon.fault/recorded` receipt; its cause is `failure` when that is a
    Throwable, else `failure` rides as `:seon.error/base`. No error schema
    declares this throw yet."
  {:malli/schema [:=> [:cat :seon.env/environment [:or :seon.error/throwable :seon.error/base]
                       :seon.fault/context]
                  :seon.fault/recorded]}
  [environment failure context]
  (when (carried-receipt failure) (throw failure))
  (let [{connection :seon.db/connection cluster-name :seon.boot/cluster-name
         carried-caps :seon.sci.admit/caps} environment
        ;; an agent's execution environment need not carry admission caps:
        ;; derive them from the cluster's declared config, as boot does
        caps (or carried-caps
                 (config/result-caps (config/effective (db/db connection) cluster-name)))
        ;; the failing world's agent when the boundary names none: its cluster
        ;; copy then carries the fault to the cluster branch root reads
        agent-id (or (:seon.agent/id context) (:seon.agent/id seon.batch/*world*))
        throwable? (instance? Throwable failure)
        [fact outcome occurrence recipients]
        (record! connection cluster-name (:seon.db.process/id context) caps
                 {:seon.error/source
                  (cond-> (if throwable? {::flow.core/ex failure} failure)
                    agent-id (assoc :seon.agent/id agent-id))
                  :seon.error/references (select-keys context reference-members)
                  :seon.error/layer (:seon.error/layer context)
                  :seon.error/operation (:seon.error/operation context)
                  :seon.error/declared-schema
                  (or (:seon.error/declared-schema context)
                      ;; the producer's declaration, wherever its link sits: an
                      ;; interpreted caller's refusal arrives inside SCI's
                      ;; location wrapper, whose own data declares nothing
                      (when throwable?
                        (some #(:seon.error/type (ex-data %))
                              (refusal/cause-links failure)))
                      :seon.flow/exception-error)})
        committed? (contains? #{:seon.flow/committed :seon.fault/folded} outcome)
        receipt (cond-> {:seon.boot/cluster-name cluster-name
                         :seon.error/layer (:seon.error/layer context)
                         :seon.error/operation (:seon.error/operation context)
                         :seon.fault/committed? committed?}
                  (:seon.error/signature fact) (assoc :seon.error/signature (:seon.error/signature fact))
                  occurrence (assoc :seon.error.occurrence/id (second occurrence))
                  (= :seon.fault/folded outcome) (assoc :seon.fault/folded? true)
                  recipients (assoc :seon.fault/delivered-to recipients))]
    (if committed?
      receipt
      (let [stop-failure (when-let [stop-request! (:seon.fault/stop-request! context)]
                           (try (stop-request!) nil (catch Throwable stop-failure stop-failure)))
            unrecorded (ex-info (str "SEON CORE FAULT (not recorded): "
                                     (or (:seon.error/message fact) (ex-message failure)))
                                (cond-> {:seon.fault/recorded receipt}
                                  (not throwable?) (assoc :seon.error/base failure)
                                  (map? outcome) (assoc :seon.fault/outcome outcome))
                                (when throwable? failure))]
        (doseq [suppressed [outcome stop-failure] :when (instance? Throwable suppressed)]
          (.addSuppressed unrecorded suppressed))
        (throw unrecorded)))))
