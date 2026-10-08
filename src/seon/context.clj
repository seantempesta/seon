(ns seon.context
  "What a model pass sends and the current-state block it ends with.

  `model-context` is the one assembler: `(help)` as the system message, the
  agent's own model exchanges, then the block. Nothing of it is stored; an
  attempt records the basis it was assembled at."
  (:require [clojure.string :as str]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.message :as message]
            [seon.error.refusal]
            [seon.eval]
            [seon.render]
            [seon.repl :as repl]
            [seon.sci.eval :as sci.eval]
            [seon.ref :as ref]
            [seon.task :as task]
            [seon.test.runner :as runner]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Message custody in one run's rendered context
;;; ---------------------------------------------------------------------------

(defn message-custody
  "Classify one message relative to the run whose prompt is being rendered.

  DERIVED FROM `:t`, LIKE EVERY OTHER ANSWEREDNESS QUESTION. A turn's
  own transaction is the basis its context projected from, so a message
  is in this turn's context exactly when its wake datom's `:t` is at or
  before the turn's; it is what this turn is ABOUT when no earlier turn
  of the same agent had already seen it; and it arrived too late for
  this turn when its `:t` is greater. The recorded trigger reference
  this replaces could not express two messages in one transaction, and
  it swallowed a message transacted with the turn that answered it."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:maybe :seon.turn/id]
         :seon.agent/id
         :int]
    :keyword]}
  [database run-id agent-id message-eid]
  (if-not run-id
    ::history
    (let [message-t
          (db/q '[:find ?tx .
                  :in $ ?agent-id ?message
                  :where
                  [?agent :seon.agent/id ?agent-id]
                  [?message :seon.message/to ?agent ?tx]]
                database agent-id message-eid)
          run-t (db/q '[:find ?tx .
                        :in $ ?run-id
                        :where
                        [?run :seon.turn/id ?run-id ?tx]]
                      database run-id)]
      (cond
        (or (nil? message-t) (nil? run-t)) ::history
        (> message-t run-t) ::pending
        :else
        (let [previous-t
              (or (db/q '[:find (max ?tx) .
                          :in $ ?agent-id ?run-t
                          :where
                          [?agent :seon.agent/id ?agent-id]
                          [?run :seon.turn/agent ?agent]
                          [?run :seon.turn/id _ ?tx]
                          [(< ?tx ?run-t)]]
                        database agent-id run-t)
                  0)]
          (if (> message-t previous-t) ::current-trigger ::history))))))

;;; ---------------------------------------------------------------------------
;;; The current-state block
;;; ---------------------------------------------------------------------------

(def ^:private task-selector
  [:seon.task/id :seon.task/sym :seon.task/intent :seon.task/must-read])

;; Every failing assertion of a red proof, in the order `seon.render.test` shows (file, line, report id)
;; so two passes show the same list. The
;; runner stores a claim's expected and actual as the printer's shown text; they stay text, marked so.
(def ^:private failure-keys
  {:seon.test.failure/type :type :seon.test.failure/message :message
   :seon.test.failure/testing-contexts :testing :seon.test.failure/reported-file :file
   :seon.test.failure/line :line :seon.test.failure/expected :expected-text
   :seon.test.failure/actual :actual-text})

(defn- proof-failures
  {:malli/schema [:=> [:cat [:or :seon.test/result :seon.test/result-unknown]]
                  [:or :nil :seon.context/proof-failures]]}
  [result]
  (when-let [reports (seq (:seon.test.failure/reports result))]
    (mapv #(update-keys (select-keys % (keys failure-keys)) failure-keys)
          (sort-by (juxt #(get % :seon.test.failure/reported-file "") #(get % :seon.test.failure/line 0) :seon.test.report/id) reports))))

(defn- proof-status
  "A proof's status from its latest result and its current reach digest."
  {:malli/schema [:=> [:cat [:or :nil :seon.test/result :seon.test/result-unknown] [:or :nil :seon.test/reach-digest]]
                  :seon.context/proof-status]}
  [result current]
  (cond
    (or (nil? result) (:seon.error/at result) (nil? current)
        (not= current (:seon.test.member/reach-digest result))) :pending
    (and (pos? (:seon.test/pass-count result))
         (zero? (+ (:seon.test/fail-count result) (:seon.test/error-count result)))) :green
    :else :red))

(defn proofs
  "The proofs section for the task `sym` on `database` (the agent's branch): counts first, then each proof."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :nil :seon.context/proofs :seon.error/value]]}
  [database sym]
  (let [edges (task/task-edges database sym)
        names (when-not (:seon.error/at edges) (vec (sort (:seon.task/proofs edges))))]
    (if (empty? names)
      (when (:seon.error/at edges) edges)
      (let [digests (runner/reach-digests database names)
            results (runner/latest-results database names)
            sources (when-not (or (:seon.error/at digests) (:seon.error/at results))
                      (into {} (db/q database '[:find ?s ?src :in $ [?s ...] :where [?t :seon.test/sym ?s] [?t :seon.test/source ?src]]
                                     names)))]
        (or (some #(when (:seon.error/at %) %) [sources digests results])
            (let [by-sym (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity)) results)
                  entries (mapv (fn [s]
                                  (let [result (by-sym s)
                                        status (proof-status result (get digests s))]
                                    (cond-> {:seon.test/sym s :seon.test/source (sources s) :seon.context/proof-status status}
                                      (= :red status) (as-> entry (if-let [f (proof-failures result)]
                                                                    (assoc entry :seon.context/proof-failures f) entry)))))
                                names)]
              {:seon.context/counts (frequencies (map :seon.context/proof-status entries))
               :seon.context/proof-list entries}))))))

(defn- unshown
  "The inbox `items` no turn of `agent-id` has handled: a closed model turn's
  settlement records each message its pass was shown (`seon.turn/close-call`)."
  {:seon/tasks #{'seon.context/a-handled-message-stays-unread-in-every-later-pass}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :my.message/inbox]
                  [:or :my.message/inbox :seon.error/value]]}
  [database agent-id items]
  (let [handled (db/q '[:find [?id ...] :in $ ?agent-id
                        :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/handled ?message] [?message :seon.message/id ?id]]
                      database agent-id)]
    (if (:seon.error/at handled)
      handled
      (into [] (remove (comp (set handled) :my.message/id)) items))))

(defn current-state
  "The current-state block's data for `agent-id`: its held task's declaration as the
  task home reads it now, and its turns left with the task's status. Sections are
  absent when empty. `:seon.context/unread` holds the agent's undelivered messages
  no model turn of it was shown yet, oldest first, each once with its id, sender,
  instant and content.

  ## Costs

  One assignment query, one task pull, the turn count, one pull per unread message and
  one query of the agent's handled messages."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/current-state :seon.error/value]]}
  [database agent-id]
  (let [home (if-let [connection (task/home database)] (db/db connection) database)
        handle (task/agent-task home agent-id)
        held (when (string? handle)
               (let [pulled (db/pull home task-selector [:seon.task/id handle])]
                 ;; a cardinality-many pull is a vector; the declaration holds a set
                 (cond-> pulled (:seon.task/must-read pulled) (update :seon.task/must-read set))))
        left (turn/turns-left database agent-id)
        proven (when held (proofs database (:seon.task/sym held)))
        unread (let [items (message/inbox database agent-id)]
                 (if (:seon.error/at items) items (unshown database agent-id items)))
        reads (when held (ref/references database {:seon.ref/text (:seon.task/intent held)
                                                   :seon.ref/names (:seon.task/must-read held #{})}))]
    (or (some #(when (:seon.error/at %) %) (concat [handle held left proven unread] reads))
        (cond-> {:seon.context/turns (cond-> {:my.agent/turns-left left}
                                       held (assoc :seon.task/status (if (zero? left) :capped :working)))}
          held (assoc :seon.context/task held)
          proven (assoc :seon.context/proofs proven)
          (seq reads) (assoc :seon.context/must-reads reads)
          (seq unread) (assoc :seon.context/unread {:seon.context/items unread
                                                    :seon.render/operations ['(my.message/inbox)]})))))

(defn current-state-ai
  "The block's sections as the agent reads them: the task by symbol and declaration, without its handle.
  The render argument carries the evaluation's world beside the block; only the sections are returned."
  {:malli/schema [:=> [:cat :seon.context/current-state] :seon.context/current-state-view]}
  [block]
  (cond-> (select-keys block [:seon.context/task :seon.context/proofs :seon.context/must-reads :seon.context/unread :seon.context/turns])
    (:seon.context/task block) (update :seon.context/task dissoc :seon.task/id)))

;;; ---------------------------------------------------------------------------
;;; What a model pass sends
;;; ---------------------------------------------------------------------------

(def ^:private exchange-selector
  "An evaluation as `seon.repl/entity-emission` reads it, with its call row's attempts."
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
    {:seon.cluster.eval/run [:seon.turn/id {:seon.turn/attempts [:db/id]}]}])

(defn- exchanges
  "The agent's own model exchanges in agent order: one text per call row carrying a model attempt."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/exchanges :seon.error/value]]}
  [database agent-id]
  (let [evaluations (seon.eval/of-agent database agent-id exchange-selector)]
    (if (:seon.error/at evaluations)
      evaluations
      (->> evaluations
           (filter #(seq (get-in % [:seon.cluster.eval/run :seon.turn/attempts])))
           (partition-by #(get-in % [:seon.cluster.eval/run :seon.turn/id]))
           (mapv (fn [run]
                   (str/join "\n" (map #(repl/text (repl/entity-emission (assoc % :seon.db/db database))) run))))))))

(defn model-context
  "What one model pass sends, as shown text.

  The system message is `(help)`; then the agent's own model exchanges; then
  the current-state block, last. The one assembler the model call reads."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id]
                  [:or :seon.context/model-context :seon.error/value]]}
  [database request agent-id]
  (let [request (assoc request :seon.db/db database :seon.agent/id agent-id)
        history (exchanges database agent-id)
        block (current-state database agent-id)
        profile (seon.render/request-profile request)
        shown #(:seon.eval/shown (sci.eval/shown-result %1 %2 nil))
        system (shown (bootstrap/help-value database agent-id) request)
        ;; the root call names the producer whose declared AI render shows the block
        prompt (shown block (assoc request :seon.render.call/function 'seon.context/current-state))]
    ;; a request with no context or profile cannot render: that refusal is the answer, never
    ;; the printed help map as the system message
    (or (some #(when (:seon.error/at %) %) [history block profile])
        {:seon.ai/system system
         :seon.context/exchanges history
         :seon.ai/prompt prompt})))
