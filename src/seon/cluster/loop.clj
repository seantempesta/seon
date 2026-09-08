(ns seon.cluster.loop
  "THE TURN: open → call → resume → close, and the custody law.

  THERE IS NO LOOP HERE ANY MORE (F2 §3.1). The central serial pass —
  settle-all → global `next-work` → turn → global `more-work?` rewake —
  is DELETED. Every agent is its own flow graph, and the pass that
  survives is `seon.cluster.agent/turn-step`: the same shape narrowed
  to ONE agent, with per-agent orphan settling, `next-agent-work`, this
  namespace's `turn`, and a self-rewake into that agent's own mailbox.
  A global `next-work` was wrong the moment two agents could run.

  The namespace keeps its name while its loop dies: the rename to
  `seon.cluster.turn` is a separate atomic wave (F2 R5), because
  mixing a rename into a cut blurs every diff the review depends on.

  WHAT SURVIVES, and why it is the durable half:

  THE TERMINAL TRANSACTION IS ONE COMMIT carrying the receipt AND the
  interpreted disposition. Splitting them reintroduces a torn window
  the quarry already closed (`driver.clj:289-297`). A rejected terminal
  transaction is followed by a separate minimal terminal commit carrying
  the admitted flat ERROR value, its durable error fact, and the run
  close, but no program row or agent disposition. Under
  `db/transact!` that is a branch on a returned value, not a catch,
  which is strictly better.

  NOTHING RETRIES A PAID CALL, and recovery is not a code path: a turn
  only ever acts on what `next-agent-work` derived from facts, and a
  crashed run reaches it as ordinary facts. `interruption` is settled
  with no reply, and the agent's next prompt carries the one warning.

  Crash walk: every row is a state `next-agent-work`/`interruption`
  already answer, so this namespace holds no durable state of its own
  and a killed turn leaves exactly the facts its last committed
  transaction wrote. The sealed suite drives the rows as kill positions
  in a state-machine property, per agent."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [parinferish.core :as parinferish]
            [seon.ai :as ai]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.context :as context]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.reply :as reply]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.flow :as seon.flow]
            [seon.fn :as seon.fn]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.sci.reader :as reader]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.test.accretion :as accretion])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

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
  ;; `:seon.cluster.message/to` DELIBERATELY, and that commit wakes the
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
                        (schema.form/map-entries
                         (schema/schema-definition entity))))
              (filter vector?)
              (map first))
        [:seon.cluster.run/run
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
  The loop reads `my.run`'s two values out of the LAST form's admitted
  result. Anything else — a number, a map that merely looks similar, an
  error value — is not a disposition, and a run whose plan ends without
  one simply stays open for the next wake."
  {:malli/schema [:=> [:cat :any] [:maybe :my.run/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.run/value value)
    value))

(defn messages
  "The messages an admitted eval value asks to send, or nil.
  The exact counterpart of `disposition`, over the second agent-facing
  value: one `my.message/send` result, or a vector of them. Anything
  else is not a delivery, and a form that returns an ordinary value
  simply sends nothing.

  Disposition and delivery schemas are open for accretion and are interpreted
  independently. A turn that intentionally sends and finishes uses two forms,
  which makes their order visible to a reader."
  {:malli/schema [:=> [:cat :any] [:maybe :my.message/value]]}
  [value]
  (when (schema/valid-candidate-value? :my.message/value value)
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
    (let [database @(:seon.db/connection cluster)
          analyzed-row (:seon.program/row evaluation)
          test-symbols (seon.fn/gate-set database function-symbol)
          seed (accretion/seed-for receipt-id)
          candidate
          (sci.eval/evaluate-candidate
           {:seon.sci.eval/ctx base-ctx
            :seon.db/db database
            :seon.db/connection (:seon.db/connection cluster)
            :seon.cluster.agent/id agent-id
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
          evaluation (merge evaluation evidence
                            {:seon.program/row analyzed-row})]
      (if (:seon.test.accretion/install? report)
        (append-output evaluation
                       (:seon.test.accretion/advisories report))
        (let [refusal (accretion/install-refusal report)
              admitted
              (admit/admit
               {:seon.sci.admit/value refusal
                :seon.sci.admit/interrupt-fn (constantly nil)
                :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                :seon.schema/projection
                (schema/projection-from-database database)
                :seon.config/on-core-error
                (:seon.config/on-core-error cluster)})]
          (-> evaluation
              (dissoc :seon.program/row)
              (merge admitted)
              (assoc :seon.cluster.eval/error (accretion/render-ai refusal)
                     :seon.error/kind
                     :seon.test.accretion/install-refused)))))
    evaluation))

(defn- def-rows
  "Restore-ladder rows admitted by the terminal receipt transaction."
  [_db agent-id evaluation ordinal]
  (let [successful-evaluation?
        (= :ok (get-in evaluation
                       [:seon.sci.admit/record :seon.eval/outcome]))
        row-base
        (fn [candidate]
          (-> candidate
              (dissoc :seon.sci.eval/value)
              (assoc :seon.def/key
                     (pr-str [agent-id (:seon.def/id candidate)])
                     :seon.def/agent
                     [:seon.cluster.agent/id agent-id]
                     :seon.def/ordinal ordinal
                     :seon.schema.admission/source :agent)))
        rows
        (mapv
         (fn [candidate]
           (let [stored? (or (:seon.def/value-edn candidate)
                             (:seon.def/blob candidate))
                 atom? (:seon.def/atom? candidate)
                 root-data (:seon.sci.eval/value candidate)
                 root-reason (:sci.root/unrestorable-reason root-data)]
             (cond
               root-reason
               (assoc (row-base candidate)
                      :seon.def/unrestorable-reason root-reason)

               stored?
               (dissoc (row-base candidate) :seon.def/unrestorable-reason)

               :else
               (-> (row-base candidate)
                   (dissoc :seon.def/value-edn :seon.def/blob :seon.def/size)
                   (assoc :seon.def/unrestorable-reason
                          (cond
                            (and atom? (not stored?))
                            "The atom's settled value is not store-faithful."

                            (not successful-evaluation?)
                            "Defining evaluation did not complete successfully."
                            :else
                            "The settled root is not store-faithful."))))))
         (:seon.sci.eval/defs evaluation))]
    rows))

;;; ---------------------------------------------------------------------------
;;; The proc
;;; ---------------------------------------------------------------------------

(declare turn settle-interruption! resume-turn)

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
    (sci.eval/unrun-evaluation
     {:seon.sci.admit/value
      {:seon.error/kind :seon.flow/time-limit
       :seon.flow/time-limit ::turn
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
           :seon.error/process (:seon.cluster.run/process cluster)
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
    evaluation :seon.sci.eval/evaluation
    settled ::settled
    problem :seon.problems/form-problem
    agent-id :seon.cluster.agent/id
    trigger :seon.cluster.message/trigger}]
  (or (messages (:seon.sci.admit/value evaluation))
      (when (= :completed (:my.run/disposition settled))
        (message/reply
         db
         (cond-> {:my.run/result (:my.run/result settled)
                  :seon.cluster.agent/id agent-id}
           trigger (assoc :seon.cluster.message/trigger trigger))))
      (when problem (problems/assignment-value problem))))

(defn- delivery-rows
  "Delivery rows and refusal transaction data for one asked value."
  [{db :seon.db/db
    cluster ::cluster
    asked ::asked
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    ordinal :seon.cluster.eval/ordinal
    now ::now
    problem :seon.problems/form-problem
    trigger :seon.cluster.message/trigger}]
  (let [receipt-eid
        (when problem
          (db/q '[:find ?receipt .
                  :in $ ?run-id ?ordinal
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?receipt :seon.cluster.eval/run ?run]
                  [?receipt :seon.cluster.eval/ordinal ?ordinal]]
                db run-id ordinal))
        delivery
        (when asked
          (message/delivery
           db
           (cond-> {:my.message/value
                    (if problem
                      (dissoc asked :my.message/about)
                      asked)
                    :seon.cluster.agent/id agent-id
                    :seon.cluster.run/id run-id
                    :seon.cluster.eval/ordinal ordinal
                    :seon.cluster.message/at now
                    :seon.config.message/max-chain
                    (:seon.config.message/max-chain cluster)}
             trigger (assoc :seon.cluster.message/trigger trigger))))]
    {:seon.cluster.message/rows
     (cond->> (:seon.cluster.message/rows delivery)
       problem (mapv #(assoc % :seon.cluster.message/about receipt-eid)))
     :seon.error/values-tx
     (into []
           (mapcat
            (fn [failure]
              (error-tx cluster db failure now
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/id run-id})))
           (:seon.error/values delivery))}))

(defn- phase
  "Return one phase's value, translating a host failure to flat data."
  [operation]
  (try
    (operation)
    (catch Throwable failure
      (merge {:seon.error/kind ::phase-failed
              :seon.error/message
              (or (ex-message failure) (.getName (class failure))) :seon.cluster.loop/phase-failed true}
             (error/refusal failure)))))

(defn- evaluation-terminal-data
  [{cluster ::cluster
    now ::now
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    process :seon.cluster.run/process
    ordinal :seon.cluster.eval/ordinal
    evaluation :seon.sci.eval/evaluation
    problem :seon.problems/form-problem
    trigger :seon.cluster.message/trigger
    batch? ::batch?}]
  (let [database @(get cluster :seon.db/connection)
        raw-settled (disposition (:seon.sci.admit/value evaluation))
        settled
        (cond-> raw-settled
          (= :completed (:my.run/disposition raw-settled))
          (assoc :my.run/delivered-to
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
                   :seon.schema/projection (schema/current-projection)}))
          evaluation)
        last-ordinal
        (db/q '[:find (max ?ordinal) .
                :in $ ?run-id
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?form :seon.cluster.eval/run ?run]
                [?form :seon.cluster.eval/ordinal ?ordinal]]
              database run-id)
        triggered-agent-form?
        (boolean
         (db/q '[:find ?form .
                 :in $ ?run-id ?ordinal
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?run :seon.cluster.run/trigger _]
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
                        ::settled settled
                        :seon.cluster.agent/id agent-id}
                 problem (assoc :seon.problems/form-problem problem)
                 trigger (assoc :seon.cluster.message/trigger trigger)))
        delivery
        (delivery-rows
         (cond-> {:seon.db/db database
                  ::cluster cluster
                  ::asked asked
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  ::now now}
           problem (assoc :seon.problems/form-problem problem)
           trigger (assoc :seon.cluster.message/trigger trigger)))
        [settlement-evaluation defs-evaluation settlement-stages]
        (run/settlement-projection cluster evaluation)
        rows (def-rows database agent-id defs-evaluation ordinal)
        receipt
        (run/evaluation-facts
         (cond-> {:seon.cluster.run/id run-id
                  :seon.cluster.run/process process
                  :seon.cluster.eval/ordinal ordinal
                  :seon.sci.eval/evaluation evaluation
                  :seon.def/rows rows
                  ::settlement-evaluation settlement-evaluation}
           problem (assoc :seon.problems/form-problem problem)
           settled (assoc :my.run/value settled)))
        side-tx
        (concat
         (when (or undisposed?
                   (contains? #{:completed :wait}
                              (:my.run/disposition settled)))
           (run/close-tx
            (cond-> {:seon.cluster.run/id run-id
                     :seon.cluster.run/process process
                     :seon.cluster.run/closed-at now}
              undisposed?
              (assoc :seon.cluster.run/undisposed-at now))))
         (:seon.cluster.message/rows delivery)
         (:seon.error/values-tx delivery))
        tx-data (if batch?
                  (vec side-tx)
                  (into (run/receipt-settle-tx database receipt) side-tx))]
    {::settled settled
     ::undisposed? undisposed?
     ::evaluation evaluation
     ::receipt receipt
     :seon.blob/staged-writes settlement-stages
     :seon.db/tx-data tx-data}))

(declare settle-batch-refusal!)

(defn- settle-batch!
  "Settle every evaluated form and all turn side effects in one transaction."
  [cluster requests]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        prepared (mapv #(evaluation-terminal-data
                         (assoc % ::batch? true
                                  :seon.cluster.run/process process))
                       requests)
        namespace-rows
        (into []
              (comp
               (map ::receipt)
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
         (into (into namespace-rows
                     (run/receipt-settle-batch-tx (mapv ::receipt prepared)))
               (mapcat :seon.db/tx-data)
               prepared)}
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
;;; `#{:seon.cluster.message/to}`), `:seon.config.error/escalate-to` named root,
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
  [cluster database now agent-id run-id process ordinal receipt source]
  (let [recording
        (error-tx cluster database source now
                  (cond-> {:seon.cluster.agent/id agent-id}
                    run-id (assoc :seon.cluster.run/id run-id)))
        value (error/value (first recording))
        receipt-tx
        (when ordinal
          (run/receipt-settle-tx
           database
           (cond->
            {:seon.cluster.run/id run-id
             :seon.cluster.eval/ordinal ordinal
             :seon.cluster.eval/result-edn (pr-str value)
             :seon.cluster.eval/error (:seon.error/message value)
             :seon.error/kind (:seon.error/kind value)}
             (seq (:seon.def/rows receipt))
             (assoc :seon.def/rows (:seon.def/rows receipt)))))]
    {:seon.error/value value
     :seon.db/tx-data
     (into [] cat
           [receipt-tx
            (when (and run-id (not ordinal))
              [[:db/add [:seon.cluster.run/id run-id]
                :seon.cluster.run/error (:seon.error/message value)]])
            (when run-id
              (run/close-tx
               {:seon.cluster.run/id run-id
                :seon.cluster.run/process process
                :seon.cluster.run/closed-at now}))
            recording])}))

(defn- settle-batch-refusal!
  "Settle every begun ordinal after the atomic turn settlement is refused."
  [cluster requests prepared refusal]
  (let [connection (:seon.db/connection cluster)
        {now ::now
         agent-id :seon.cluster.agent/id
         run-id :seon.cluster.run/id}
        (first requests)
        process (:seon.cluster.run/process cluster)
        recording (error-tx cluster @connection refusal now
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/id run-id})
        value (error/value (first recording))
        serialized (pr-str value)
        receipts
        (mapv
         (fn [entry]
           (-> (::receipt entry)
               ;; `evaluation-terminal-data` already projected and staged the
               ;; durable def values. Re-projecting raw in-memory defs here
               ;; discarded those values and made a refused definition
               ;; unrestorable on the next turn.
               (dissoc :seon.program/row ::run/form-facts)
               (assoc :seon.cluster.eval/result-edn serialized
                      :seon.cluster.eval/error (:seon.error/message value)
                      :seon.error/kind (:seon.error/kind value))))
         prepared)
        transaction
        {:tx-data
         (into (run/receipt-settle-batch-tx receipts)
               cat
               [(run/close-tx {:seon.cluster.run/id run-id
                               :seon.cluster.run/process process
                               :seon.cluster.run/closed-at now})
                recording])}
        outcome (db/transact! connection transaction)]
    (when (:seon.error/kind outcome)
      (throw
       (ex-info "Batch refusal settlement was refused."
                {:seon.error/kind ::terminal-refusal-settlement-refused
                 ::settlement outcome
                 ::refused-outcome refusal})))
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
    [:cat :seon.cluster.loop/settle-request]
    :seon.cluster.loop/settlement]}
  [{cluster ::cluster
    now ::now
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    ordinal :seon.cluster.eval/ordinal
    evaluation :seon.sci.eval/evaluation
    failure :seon.error/value
    :as request}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        prepared
        (if evaluation
          (phase #(evaluation-terminal-data
                   (assoc request :seon.cluster.run/process process)))
          failure)
        prepared
        (if (:seon.error/kind prepared)
          (refusal-terminal-data cluster @connection now agent-id run-id
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
      (assoc prepared ::outcome outcome)
      (let [refusal
            (refusal-terminal-data
             cluster @connection now agent-id run-id process ordinal
             (::receipt prepared) outcome)
            refused (commit refusal)]
        (when (:seon.error/kind refused)
          (throw
           (ex-info "Terminal refusal settlement was refused."
                    {:seon.error/kind ::terminal-refusal-settlement-refused
                     :seon.cluster.loop/terminal-refusal-settlement-refused
                     (:seon.cluster.run/rule outcome)
                     ::settlement refused
                     ::refused-outcome outcome})))
        (assoc refusal
               ::outcome refused
               ::refused-outcome outcome)))))

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
   (count (db/q '[:find ?attempt
                 :in $ ?run-id
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?attempt :seon.ai.attempt/run ?run]]
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
      (:seon.ai.attempt/sent-body completion)
      (assoc :seon.ai.attempt/sent-body
             (:seon.ai.attempt/sent-body completion))
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
    run-id :seon.cluster.run/id
    agent-id :seon.cluster.agent/id
    evidence ::attempt-evidence}]
  (cond-> (merge {:seon.ai/target target
                  :seon.ai/settings settings
                  :seon.cluster.run/id run-id
                  :seon.cluster.agent/id agent-id
                  :seon.ai.attempt/ordinal ordinal}
                 evidence)
    value (assoc :seon.error/value value)
    failover-from (assoc :seon.ai.attempt/failover-from failover-from)
    delay-ms (assoc :seon.ai.attempt/delay-ms delay-ms)))

(defn- provider-targets
  "Resolved provider targets, settings, and finite schedule for one turn."
  [{db :seon.db/db
    cluster-name :seon.cluster/name
    agent-id :seon.cluster.agent/id}]
  (let [settings (ai/settings (config/effective db cluster-name)
                              (ai/agent-overlay db agent-id))
        targets (ai/targets db settings)
        primary (:seon.ai/primary targets)
        backup (:seon.ai/backup targets)
        strategy (ai/retry-strategy settings)]
    {:seon.ai/primary primary
     :seon.ai/backup backup
     :seon.ai/settings settings
     ::schedule (if backup [] (ai/delays strategy rand))}))

(defn- record-attempt!
  "Commit ONE model attempt and its error or truncation facts.
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
         run-id :seon.cluster.run/id
         agent-id :seon.cluster.agent/id
         ordinal :seon.ai.attempt/ordinal
         usage :seon.ai/usage
         latency-ms :seon.ai.model/last-latency-ms
         settings :seon.ai/settings
         sent-body :seon.ai.attempt/sent-body
         reasoning-content :seon.ai/reasoning-content
         finish-reason :seon.ai/finish-reason
         truncation :seon.ai/truncation
         delay-ms :seon.ai.attempt/delay-ms
         failover-from :seon.ai.attempt/failover-from} request
        connection (:seon.db/connection cluster)
        db @connection
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
        attribution {:seon.cluster.agent/id agent-id
                     :seon.cluster.run/id run-id}
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
                     {:seon.ai.attempt/id (attempt-id run-id ordinal)
                      :seon.ai.attempt/run [:seon.cluster.run/id run-id]
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
              sent-body
              (assoc :seon.ai.attempt/sent-body sent-body)
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
                         (into (conj recording row) observation-tx))))]
    (when-not (:seon.error/kind outcome)
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
               [?run :seon.cluster.run/id ?run-id]
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
              [?run :seon.cluster.run/id ?run-id]
              [?run :seon.cluster.run/starting-ns ?namespace]
              [?namespace :seon.ns/name ?starting-ns]]
            db run-id)))

(defn- evaluation-request
  "One admitted form projected into the guarded evaluation request."
  [{form ::admitted-form
    evaluation-namespace ::evaluation-namespace
    cluster ::cluster
    ctx :seon.sci.eval/ctx
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    form-ordinal :seon.cluster.eval/ordinal}]
  (merge form
         (cond->
          {:seon.cluster.eval/ns [:seon.ns/name evaluation-namespace]
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.sci.eval/ctx ctx
           :seon.cluster.agent/id agent-id
           :seon.cluster.eval/ordinal form-ordinal
           :seon.boot/cluster-name (:seon.cluster/name cluster)
           :seon.sci.eval/time-limit-ms
           (:seon.config.eval/time-limit-ms cluster)
           :seon.config/on-core-error
           (:seon.config/on-core-error cluster)}
           run-id (assoc :seon.cluster.run/id run-id)
           (:seon.flow/work-launcher cluster)
           (assoc :seon.flow/work-launcher
                  (:seon.flow/work-launcher cluster)))))

(defn settle-interruption!
  "Reclaim a generated prefix or bury one ordinary orphaned run.
  Planned or unplanned, an unheld run is not work: there is no cold
  resume of an authored plan. A system-generated prefix is append-only data,
  however, so its open run is reclaimed and continues at the next ordinal.
  Every other interruption is claim-then-close through the ordinary
  transitions: a survivor cannot close a run it does not hold
  (`close-call` refuses `::not-the-holder`), so it takes custody by the
  takeover path first and closes as the holder.

  Boot recovery released the dead custody; this releases the AGENT.
  The two are deliberately separate: recovery states who no longer
  holds what, and settlement decides what to do about it — and only
  the loop is entitled to decide that.

  Settle-only for N3 outside generated derivation. The explanation an agent reads is derived from
  the settled run's own shape (no plan, closed) by
  `seon.cluster.prompt`; when a richer reason is wanted, this
  transaction is where it would ride."
  {:malli/schema [:=> [:cat :seon.cluster.loop/cluster
                       :seon.cluster.run/id :inst]
                  :boolean]}
  [cluster run-id now]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        claimed (db/transact!
                 connection
                 (run/claim-tx {:seon.cluster.run/id run-id
                                :seon.cluster.run/process process
                                ;; the only live process on this branch
                                ;; is this one — flock + single writer
                                :seon.cluster.run/live-processes #{process}
                                :seon.cluster.run/now now}))]
    (if (:seon.error/kind claimed)
      false
      (let [generated?
            (= :generate
               (:seon.cluster.work/situation
                (db/pull @connection [:seon.cluster.work/situation]
                         [:seon.cluster.run/id run-id])))]
        (if generated?
          true
          (let [closed
                (db/transact!
                 connection
                 (run/close-tx
                  {:seon.cluster.run/id run-id
                   :seon.cluster.run/process process
                   ;; the pass's ONE clock, not a second reading of it
                   :seon.cluster.run/closed-at now}))]
            (not (:seon.error/kind closed))))))))

(defn- open-turn
  "Open and claim one run before any paid provider call."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)]
    ;; OPEN + CLAIM FIRST, model second. The busy fence has to exist
    ;; before the expensive part.
    ;;
    ;; ANSWEREDNESS IS THIS TRANSACTION'S OWN `:t`. Nothing claims a
    ;; wake: every wake with `:t` at or before the opening transaction
    ;; is answered by the turn whose context contained it, so two wakes
    ;; in one commit are one paid call and a wake arriving mid-turn
    ;; opens the next one. The `::trigger-already-answered` fence is
    ;; gone with the reference it guarded — `open-call`'s
    ;; `::agent-already-running` is what stops two openers, and the
    ;; derivation is what stops a second turn for an answered wake.
    ;;
    ;; `:seon.cluster.run/trigger` is retained as PROVENANCE ONLY — the
    ;; oldest message wake this turn opened for, which the page and the
    ;; context still name. It decides nothing.
    (let [id (str (random-uuid))
          open-request
          (cond->
           {:seon.cluster.run/id id
            :seon.cluster.run/agent
            [:seon.cluster.agent/id agent-id]
            :seon.cluster.run/opening-commit-id
            (db/commit-id @connection)
            :seon.cluster.run/opened-at now}
            (:seon.cluster.message/id work)
            (assoc
             :seon.cluster.run/trigger
             [:seon.cluster.message/id
              (:seon.cluster.message/id work)]))
          outcome (db/transact!
                   connection
                   {:tx-data
                    (into [[:db.fn/call #'run/open-call open-request]]
                          (run/claim-tx {:seon.cluster.run/id id
                                         :seon.cluster.run/process process
                                         :seon.cluster.run/live-processes
                                         #{process}
                                         :seon.cluster.run/now now}))})]
      (cond
        (:seon.error/kind outcome)
        (do
          ;; The open transaction formed no run, so settlement records the
          ;; error and escalation with no run attribution or close.
          (settle! {::cluster cluster
                    ::now now
                    :seon.cluster.agent/id agent-id
                    :seon.error/value outcome})
          (report :error 0))

        :else
        (report :released 0)))))

(defn- call-turn
  "Call the provider and freeze the returned plan."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)]
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
          db @connection
          providers (provider-targets
                     {:seon.db/db db
                      :seon.cluster/name (:seon.cluster/name cluster)
                      :seon.cluster.agent/id agent-id})
          settings (:seon.ai/settings providers)
          primary (:seon.ai/primary providers)
          backup (:seon.ai/backup providers)
          schedule (::schedule providers)
          ;; STREAMING IS ON BY CONSTRUCTION (F2 §2.1): the sink is
          ;; one `offer!` of the run id plus the complete
          ;; `:seon.ai/partial` snapshot
          ;; onto the cluster's ONE sliding-1 stream conn — newest
          ;; wins, a slow render pass can never backpressure the
          ;; provider fold, and a streamed call and a one-shot call
          ;; return the same completion value. There is no dial; a
          ;; handle with no stream channel simply calls one-shot.
          stream-channel (:seon.cluster.loop/stream-channel cluster)
          sink (when stream-channel
                 (fn [snapshot]
                   (async/offer! stream-channel
                                 {:seon.cluster.agent/id agent-id
                                  :seon.cluster.run/id run-id
                                  :seon.ai/partial snapshot})))
          fail!
          (fn [failure]
            (settle! {::cluster cluster
                      ::now now
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id run-id
                      :seon.error/value failure})
            (report :error 0))
          freeze!
          (fn [completion]
            ;; ONE INTENT COMMIT. The raw reply and every result-less eval row
            ;; become durable together before any form runs. A later crash is
            ;; therefore exactly the set difference between intent rows and
            ;; terminal results; recovery never has to reconstruct or rerun it.
            (let [reply-text (:seon.ai/text completion)
                  database @connection
                  namespace-name (sci.eval/agent-namespace database agent-id)
                  max-source
                  (get-in cluster
                          [:seon.sci.admit/caps
                           :seon.config.eval.result/max-source])
                  prepared
                  (planned-sources reply-text namespace-name max-source)
                  sources (if (vector? prepared) prepared [])
                  staged-reply (run/stage-reply! connection reply-text)
                  plan-request
                  (merge (dissoc staged-reply :seon.blob/staged-writes)
                         {:seon.cluster.run/id run-id
                          :seon.cluster.run/process process
                          :seon.cluster.run/plan-digest
                          (run/plan-digest sources)
                          ;; ONE ENTITY PER (run, ordinal): freezing the plan
                          ;; IS minting the evaluations, source and author and
                          ;; comment and all, with no terminal fact. There is
                          ;; no twin form row for the ordinals to disagree
                          ;; about, and the first ordinal is derived inside
                          ;; the transaction rather than assumed out here.
                          :seon.cluster.eval/at now
                          :seon.cluster.run/sources sources})
                  intent-tx (run/plan-tx plan-request)
                  outcome
                  (blob/with-publication!
                   connection (:seon.blob/staged-writes staged-reply)
                   #(db/transact! connection {:tx-data intent-tx}))]
              (cond
                (:seon.error/kind outcome) (fail! outcome)
                (empty? sources) (fail! prepared)
                :else
                (resume-turn
                 {::cluster cluster
                  ::work (assoc work
                                :seon.cluster.work/situation :resume
                                :seon.cluster.eval/ordinal 0)
                  ::now now
                  ::report report}))))
          ;; THE PROMPT REQUEST NAMES THE HELD RUN — `prompt` derives
          ;; the trigger from the run's own creating transaction
          ;; (`message/trigger`), never a re-asked queue: the recorded
          ;; cause is the prompt's cause. One derivation, one owner.
          ;; NOTHING THROWS INTO THE AGENT LOOP: the prompt owner
          ;; refuses by throwing (`::no-trigger`, `::missing-input`),
          ;; and this one call site turns that refusal into the flat
          ;; error value the loop already records — the same shape a
          ;; refused transaction takes through `db/transact!`.
          observed-db @connection
          prompt-db (run/opening-db observed-db run-id)
          rendered
          (phase
           #(prompt/prompt prompt-db
                           {:seon.cluster.run/id run-id
                            :seon.cluster.agent/id agent-id
                            :seon.db/connection connection
                            :seon.sci.admit/caps
                            (:seon.sci.admit/caps cluster)
                            :seon.sci.eval/ctx
                            (:seon.sci.eval/ctx cluster)
                            :seon.sci.eval/time-limit-ms
                            (:seon.config.eval/time-limit-ms cluster)
                            :seon.config/on-core-error
                            (:seon.config/on-core-error cluster)
                            :seon.render/context-channel
                            (:seon.render/context-channel cluster)}))
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
          (db/transact!
           connection
           (context/capture-tx
            (if (:seon.error/kind rendered)
              {:seon.cluster.run/id run-id
               ;; The immutable opening value the refused derivation used,
               ;; never a fresh connection deref after the fact.
               :seon.db/db (if (:seon.error/kind prompt-db)
                             observed-db
                             prompt-db)
               :seon.error/value rendered}
              {:seon.cluster.run/id run-id
               :seon.cluster.prompt/rendered-context rendered})))
          ;; THE EXACT-TEXT HANDOFF: the loop extracts the rendered
          ;; text and alone places that string in `:seon.ai/prompt` —
          ;; the bytes the capture recorded are the bytes sent.
          text (:seon.cluster.prompt/text rendered)]
      (cond
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
                                         :seon.cluster.run/id run-id
                                         :seon.cluster.agent/id agent-id
                                         :seon.ai.attempt/ordinal ordinal
                                         ::attempt-evidence evidence}
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
                      {:seon.db/db @connection
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
              :else (fail! failure))))))))

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

(defn evaluate-sources
  "Evaluate ordered sources in one fork without settling or staging them.

  An explicit database is the basis of every form. Otherwise each form sees
  the connection's current value, as in an ordinary turn. Results and namespace
  changes advance the same fork; callers decide whether to persist outcomes."
  {:malli/schema [:=> [:cat :seon.cluster.loop/evaluate-sources-request]
                  :seon.cluster.loop/evaluated-sources]}
  [{cluster ::cluster
    snapshot :seon.db/db
    ctx :seon.sci.eval/ctx
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    first-ordinal :seon.cluster.eval/ordinal
    sources :seon.cluster.reply/sources
    starting-namespace :seon.ns/name
    defs-notices :seon.sci.eval/defs-notices}]
  (let [connection (:seon.db/connection cluster)]
    (loop [remaining (seq sources)
           ordinal first-ordinal
           namespace-name starting-namespace
           results []]
      (if-let [source (first remaining)]
        (let [form (assoc source :seon.cluster.eval/ns
                          [:seon.ns/name namespace-name])
              database (or snapshot @connection)
              captured (atom [])
              at (java.util.Date.)
              request
              (cond-> (evaluation-request
                       {::admitted-form form
                        ::evaluation-namespace namespace-name
                        ::cluster cluster
                        :seon.sci.eval/ctx ctx
                        :seon.cluster.agent/id agent-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.run/id run-id})
                snapshot (assoc :seon.db/db snapshot)
                (and (empty? results) (seq defs-notices))
                (assoc :seon.sci.eval/output-prefix (str/join "\n" defs-notices)))
              evaluation
              (binding [db/*read-evidence-sink* captured]
                (render/call-with-walk-context
                 {:seon.db/db database
                  :seon.db/connection connection
                  :seon.cluster.agent/id agent-id
                  :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                  :seon.sci.eval/ctx ctx
                  :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
                  :seon.config/on-core-error (:seon.config/on-core-error cluster)}
                 #(sci.eval/evaluate request)))
              evaluation
              (if (:seon.error/kind evaluation)
                {:seon.sci.admit/value evaluation
                 :seon.cluster.eval/result-edn (pr-str evaluation)
                 :seon.cluster.eval/error (:seon.error/message evaluation)
                 :seon.error/kind (:seon.error/kind evaluation)}
                evaluation)
              evaluation
              (assoc evaluation
                     :seon.cluster.eval/at at
                     :seon.cluster.eval/read-evidence (db/read-evidence @captured)
                     :seon.cluster.eval/read-basis-transaction (db/basis-t database))
              ;; THE HANDLE IS THE EVALUATION'S OWN IDENTITY. The freeze
              ;; already transacted this ordinal's evaluation entity, so its
              ;; entity id is resolvable against the connection's CURRENT
              ;; value — and an evaluation that never persisted (the page's
              ;; in-memory preview) has no identity, so it gets no handle and
              ;; binds nothing rather than a name a later turn cannot reach
              ;; (ruling 59c).
              entity-id (when run-id
                          (evaluation-entity-id
                           @connection
                           (run/receipt-identity run-id ordinal)))
              ;; AND ONLY WHEN THE NODE ACTUALLY HELD THE VALUE. `(def x 1)`
              ;; and `(in-ns …)` admit to a Var and an object face — names,
              ;; not values — so `bind-stored-results!` refuses them on the
              ;; NEXT turn. Naming one here anyway put `:result result/eN`
              ;; in the agent's context for a symbol that would resolve to
              ;; nothing when the agent read it, and made the page's own
              ;; render differ from the stored one it is supposed to equal.
              ;; One predicate decides for the binder and both emitters.
              handle (when (and entity-id
                                (admit/restorable-node
                                 (:seon.cluster.eval/result-edn evaluation)))
                       (admit/result-handle entity-id))
              evaluation (cond-> evaluation
                           handle (assoc :seon.repl/handle handle))]
          (when handle
            (sci.eval/bind-result! ctx handle (:seon.sci.admit/value evaluation)))
          (recur (next remaining) (inc ordinal)
                 (or (:seon.sci.eval/ending-ns evaluation) namespace-name)
                 (conj results
                       {:seon.cluster.eval/ordinal ordinal
                        ::admitted-form form
                        :seon.sci.eval/evaluation evaluation})))
        results))))

(defn preview-sources
  "Evaluate authored source once in the assigned agent's fork, persisting nothing.

  THE PAGE IS NOT A SECOND EVALUATOR. A preview is the same parse, the same
  fork, and the same `evaluate-sources` an ordinary turn runs; what it lacks
  is a run, so nothing settles, no evaluation entity exists, and no
  `result/eN` handle is bound (ruling 59c). The renderer that shows a preview
  calls this and renders the returned evaluations; it never forks or parses
  on its own."
  {:malli/schema [:=> [:cat :seon.cluster.loop/preview-sources-request]
                  [:or :seon.cluster.loop/preview :seon.error/value]]}
  [{cluster ::cluster
    database :seon.db/db
    base-ctx :seon.sci.eval/ctx
    agent-id :seon.cluster.agent/id
    namespace-name :seon.ns/name
    text :seon.cluster.reply/text
    caps :seon.sci.admit/caps}]
  (let [opened-at (Date.)
        forked (sci.eval/fork-for-turn
                {:seon.sci.eval/ctx base-ctx
                 :seon.db/db database
                 :seon.db/connection (:seon.db/connection cluster)
                 :seon.cluster.agent/id agent-id})]
    (if (:seon.error/kind forked)
      forked
      (let [sources (planned-sources
                     text namespace-name
                     (:seon.config.eval.result/max-source caps))]
        (if (map? sources)
          sources
          {:seon.cluster.loop/evaluated-sources
           (evaluate-sources
            {::cluster cluster
             :seon.db/db database
             :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
             :seon.cluster.agent/id agent-id
             :seon.cluster.eval/ordinal 0
             :seon.ns/name namespace-name
             :seon.cluster.reply/sources sources
             :seon.sci.eval/defs-notices (vec (:seon.sci.eval/defs-notices forked))})
           :seon.cluster.agent/id agent-id
           :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
           :seon.cluster.run/opened-at opened-at
           :seon.cluster.run/closed-at (Date.)
           :seon.db/db database})))))

(defn- resume-turn
  "Evaluate an intent-frozen turn in memory, then settle the whole batch once."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)
        base-ctx (:seon.sci.eval/ctx cluster)
        forked
        (phase #(sci.eval/fork-for-turn
                 {:seon.sci.eval/ctx base-ctx
                  :seon.db/db @connection
                  :seon.db/connection connection
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id}))
        trigger (phase #(message/trigger @connection run-id))]
    (if-let [failure (some #(when (:seon.error/kind %) %)
                           [forked trigger])]
      (do
        (settle! {::cluster cluster
                  ::now now
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.error/value failure})
        (report :error 0))
      (let [{ctx :seon.sci.eval/ctx
             defs-notices :seon.sci.eval/defs-notices} forked
            database @connection
            first-ordinal (:seon.cluster.eval/ordinal work)
            evaluations (fold-evaluations database run-id)
            evaluated
            (phase
             #(evaluate-sources
               {::cluster cluster
                :seon.sci.eval/ctx ctx
                :seon.cluster.agent/id agent-id
                :seon.cluster.run/id run-id
                :seon.cluster.eval/ordinal first-ordinal
                :seon.ns/name (or (fold-namespace database run-id evaluations
                                                 first-ordinal)
                                  (sci.eval/agent-namespace database agent-id))
                :seon.sci.eval/defs-notices defs-notices
                :seon.cluster.reply/sources
                (into []
                      (comp (filter (fn [evaluation]
                                      (<= first-ordinal
                                          (:seon.cluster.eval/ordinal
                                           evaluation))))
                            (map fold-source))
                      evaluations)}))
            defining
            (into []
                  (keep-indexed
                   (fn [index {form ::admitted-form evaluation :seon.sci.eval/evaluation}]
                     (when (:seon.program/row evaluation)
                       [index
                        {:seon.cluster.eval/source
                         (:seon.cluster.eval/source form)
                         :seon.cluster.eval/ns
                         (:seon.cluster.eval/ns form)
                         :seon.program/row
                         (:seon.program/row evaluation)}])))
                  evaluated)
            analyzed
            (if (seq defining)
              (phase #(seon.fn/analyze-forms database (mapv second defining)))
              [])]
        (if (:seon.error/kind analyzed)
          (do
            (settle! {::cluster cluster
                      ::now now
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id run-id
                      :seon.error/value analyzed})
            (report :error (count evaluated)))
          (let [evaluated
                (reduce
                 (fn [all [[index _] [form-facts row]]]
                   (-> all
                       (assoc-in [index :seon.sci.eval/evaluation :seon.program/row] row)
                       (assoc-in
                        [index :seon.sci.eval/evaluation ::run/form-facts]
                        (assoc form-facts
                               :db/id
                               [:seon.cluster.eval/id
                                (run/receipt-identity
                                 run-id (:seon.cluster.eval/ordinal (nth all index)))]))))
                 evaluated
                 (map vector defining analyzed))
                gated
                (mapv
                 (fn [{ordinal :seon.cluster.eval/ordinal form ::admitted-form
                       evaluation :seon.sci.eval/evaluation :as item}]
                   (assoc item :seon.sci.eval/evaluation
                          (gate-function-install
                           cluster ctx agent-id
                           (run/receipt-identity run-id ordinal)
                           form evaluation)))
                 evaluated)
                requests
                (mapv
                 (fn [{ordinal :seon.cluster.eval/ordinal evaluation :seon.sci.eval/evaluation}]
                   (let [problem
                         (phase
                          #(problems/form-problem
                            database
                            {:seon.cluster.run/id run-id
                             :seon.cluster.eval/ordinal ordinal
                             :seon.sci.eval/evaluation evaluation}))]
                     (cond->
                      {::cluster cluster
                       ::now now
                       :seon.cluster.agent/id agent-id
                       :seon.cluster.run/id run-id
                       :seon.cluster.eval/ordinal ordinal
                       :seon.sci.eval/evaluation evaluation
                       :seon.cluster.message/trigger trigger}
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
                (sci.eval/install-evaluated-rows!
                 {:seon.sci.eval/ctx base-ctx
                  :seon.db/db (:db-after outcome)
                  :seon.sci.eval/installations
                  (into []
                        (keep
                         (fn [{evaluation :seon.sci.eval/evaluation}]
                           (let [row (:seon.program/row evaluation)]
                             (when (and row
                                        (sci.eval/committed-row?
                                         (:db-after outcome) row))
                               {:seon.program/row row
                                :seon.sci.eval/evaluation evaluation}))))
                        gated)})
                (if (or (::settled last-prepared)
                        (::undisposed? last-prepared))
                  (report :closed (count gated))
                  (report :released (count gated)))))))))))
(defn- close-turn
  "Claim when needed and close one fully settled run."
  [{cluster ::cluster work ::work now ::now report ::report}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)]
    ;; the fold is done and nothing said otherwise: close it, so the
    ;; agent stops being busy.
    ;;
    ;; CLAIM FIRST WHEN WE DO NOT HOLD IT, and this is a fix, not a
    ;; flourish: `next-agent-work` derives `:close` for any open planned run
    ;; whose forms are all settled, INCLUDING one nobody holds — a run
    ;; released by `my.run/wait`, or one whose holder died after the
    ;; last receipt. `close-call` refuses a run it is not the holder
    ;; of (`::not-the-holder`), so those closes failed, the derivation
    ;; kept returning `:close`, and the self-rewake kept firing:
    ;; a HOT LIVELOCK committing one error fact per pass. Measured on
    ;; the wait path — twelve passes, nine error facts, `next-agent-work`
    ;; still saying `:close`. Taking custody first is the same
    ;; takeover `settle-interruption!` already uses, and it is what
    ;; makes "only the holder may close a run" a rule the loop can
    ;; keep rather than one it repeatedly breaks.
    (let [held (db/pull @connection [:seon.cluster.run/process]
                       [:seon.cluster.run/id run-id])
          claimed (when-not (= process (:seon.cluster.run/process held))
                    (db/transact!
                     connection
                     (run/claim-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/live-processes
                                    #{process}
                                    :seon.cluster.run/now now})))
          outcome (if (:seon.error/kind claimed)
                    ;; somebody else holds it: not ours to close, and
                    ;; not an error of ours either
                    claimed
                    (db/transact!
                     connection
                     (run/close-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/closed-at now})))]
      (if (:seon.error/kind outcome)
        (do
          ;; A refused claim means another process owns this run. Record the
          ;; refusal without mutating that process's terminal state.
          (settle! {::cluster cluster
                    ::now now
                    :seon.cluster.agent/id agent-id
                    :seon.error/value outcome})
          (report :error 0))
        (report :closed 0)))))

(defn- generate-turn
  "Append and execute one dependency-ready generated bootstrap form."
  [{cluster ::cluster work ::work now ::now report ::report :as request}]
  (let [connection (:seon.db/connection cluster)
        process (:seon.cluster.run/process cluster)
        agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)
        ordinal
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?form :seon.cluster.eval/run ?run]]
                  @connection run-id)
             0))
        entry
        (phase
         #(bootstrap/next-entry
           {:seon.db/db @connection
            :seon.db/connection connection
            :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
            :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
            :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
            :seon.sci.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms cluster)
            :seon.config/on-core-error (:seon.config/on-core-error cluster)
            :seon.render/output :seon.render/form
            ;; measured live 2026-08-29 on the ruling-47 population:
            ;; distance 3 = 499 units / 28 s PER ADVANCE (the third hop
            ;; explodes into every namespace's function detail), starving
            ;; every armed backstop; distance 2 = 109 units / 1.2 s and
            ;; keeps the toolkit hop. Ruling 46 already retired eager
            ;; deep openings — the world arrives as affordances, not as
            ;; a three-hop content walk.
            :seon.render/distance 2}
           run-id))]
    (cond
      (:seon.error/kind entry)
      (do
        (settle! {::cluster cluster
                  ::now now
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.error/value entry})
        (report :error 0))

      ;; A GENERATED RUN THAT HAS NOTHING LEFT TO GENERATE IS FINISHED.
      ;; The other arm — advancing a generated run to a model call — wrote
      ;; the one `:generate` → `:call` edge that existed, and no production
      ;; path ever reached it: `generated-run-tx`'s only caller is
      ;; `seon.bootstrap/seed-tx`, whose run id is always
      ;; `(bootstrap/run-id agent-id)`. The dead branch and its transition
      ;; are deleted rather than kept as a shape nothing can produce.
      (nil? entry)
      (let [terminal
            (db/transact!
             connection
             (run/close-tx
              {:seon.cluster.run/id run-id
               :seon.cluster.run/process process
               :seon.cluster.run/closed-at now}))]
        (if (:seon.error/kind terminal)
          (do
            (settle! {::cluster cluster
                      ::now now
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id run-id
                      :seon.error/value terminal})
            (report :error 0))
          (report :closed 0)))

      :else
      (let [appended
            (db/transact!
             connection
             (run/append-generated-tx
              (cond-> {:seon.cluster.run/id run-id
                       :seon.cluster.run/process process
                       :seon.cluster.eval/at now
                       :seon.cluster.eval/ordinal ordinal
                       ;; THE COMMENT AND THE FORM ARE TWO FIELDS. A generated
                       ;; opening reads back through the one REPL grammar, so
                       ;; its prose sits above the prompt exactly like an
                       ;; agent's own.
                       :seon.cluster.eval/source
                       (pr-str (:seon.repl/form entry))
                       :seon.ns/name
                       (sci.eval/agent-namespace @connection agent-id)}
                (:seon.repl/comment entry)
                (assoc :seon.cluster.eval/comment
                       (:seon.repl/comment entry)))))]
        (if (:seon.error/kind appended)
          (do
            (settle! {::cluster cluster
                      ::now now
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id run-id
                      :seon.error/value appended})
            (report :error 0))
          (resume-turn
           (assoc request ::work
                  (assoc work
                         :seon.cluster.work/situation :resume
                         :seon.cluster.eval/ordinal ordinal))))))))

(defn turn
  "Run one turn to its next durable boundary; returns the turn report.
  The sequence is the contract: claim → derive prompt → model (`:io`)
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
  {:malli/schema [:=> [:cat :seon.cluster.loop/turn-request :inst]
                  :seon.cluster.loop/turn-report]}
  [{:keys [:seon.cluster.loop/cluster] work :seon.cluster.work/next}
   now]
  (let [agent-id (:seon.cluster.agent/id work)
        run-id (:seon.cluster.run/id work)
        report (fn [outcome forms-run]
                 (cond-> {:seon.cluster.agent/id agent-id
                          :seon.cluster.work/situation
                          (:seon.cluster.work/situation work)
                          :seon.cluster.loop/forms-run forms-run
                          :seon.cluster.loop/outcome outcome}
                   run-id (assoc :seon.cluster.run/id run-id)))
        request {::cluster cluster
                 ::work work
                 ::now now
                 ::report report}
        pass (fn []
               (case (:seon.cluster.work/situation work)
                 :open (open-turn request)
                 :call (call-turn request)
                 :generate (generate-turn request)
                 :resume (resume-turn request)
                 :close (close-turn request)))]
    (if-let [projection-state (:seon.sci.eval/projection-state cluster)]
      (schema/call-with-projection-state projection-state pass)
      (pass))))
