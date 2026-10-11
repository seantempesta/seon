(ns seon.context
  "What a model pass sends and the current-state block it ends with.

  `model-context` is the one assembler: `(help)` as the system message, the
  agent's own model exchanges, then the block. Nothing of it is stored; an
  attempt records the basis it was assembled at."
  (:require [seon.batch :as batch]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.error.refusal]
            [seon.eval]
            [seon.render]
            [seon.repl :as repl]
            [seon.sci.eval :as sci.eval]
            [seon.problems :as problems]
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

(defn- all-green?
  "True when the proofs section `proven` holds proofs and every one is green."
  {:malli/schema [:=> [:cat [:or :nil :seon.context/proofs :seon.error/value]] :boolean]}
  [proven]
  (= #{:green} (set (keys (:seon.context/counts proven)))))

(defn- held-task
  "The declaration of the task `agent-id` holds, read at the task home seen from `database`; nil when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :nil :seon.context/task :seon.error/value]]}
  [database agent-id]
  (let [home (if-let [connection (task/home database)] (db/db connection) database)
        handle (task/agent-task home agent-id)]
    (cond (:seon.error/at handle) handle
          (string? handle)
          (let [pulled (db/pull home task-selector [:seon.task/id handle])]
            ;; a cardinality-many pull is a vector; the declaration holds a set
            (cond-> pulled (:seon.task/must-read pulled) (update :seon.task/must-read set))))))

(defn task-done?
  "True when the task `agent-id` holds has proofs, all green on `database`, the agent's branch, and its turn's
  test step found no reached test its change made red (`:seon.agent/reached-red` absent: red on the branch and not
  red on main under the same evaluator, `seon.test/change-reds`): the task is done for the agent, pending root's
  merge, and the turn loop opens no continuation for it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :boolean]}
  [database agent-id]
  (let [held (held-task database agent-id)]
    (boolean (and held (not (:seon.error/at held))
                  (all-green? (proofs database (:seon.task/sym held)))
                  (empty? (:seon.agent/reached-red (db/pull database [:seon.agent/reached-red] [:seon.agent/id agent-id])))))))

(defn- unshown
  "The inbox `items` no turn of `agent-id` has handled: a closed model turn's
  settlement records each message its pass was shown (`seon.turn/close-call`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :my.message/inbox]
                  [:or :my.message/inbox :seon.error/value]]}
  [database agent-id items]
  (let [handled (db/q '[:find [?id ...] :in $ ?agent-id
                        :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/handled ?message] [?message :seon.message/id ?id]]
                      database agent-id)]
    (if (:seon.error/at handled)
      handled
      (into [] (remove (comp (set handled) :my.message/id)) items))))

(defn- branch-cost
  "The branch's stored cost entities (`:seon.fn.cost/entity`) that differ from
  main's (a sample digest main does not hold, or main stores none for that
  symbol and scope), as `seon.batch/cost-diff` comparisons, like with like
  only: the most self time spent first, at most `:seon.config.test.cost/top`,
  the dials of the branch's effective configuration. Main is the task home
  (`seon.task/home`). Nil on main itself, the branch stores none, its program
  declares no cost entities or its configuration is refused."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :seon.cost/diff]]}
  [database]
  (let [main (some-> (task/home database) db/db)
        cluster (when main (db/q '[:find ?n . :where [_ :seon.config/cluster ?n]] database))
        effective (when cluster (config/effective database cluster))
        cost-keys (when (and effective (not (:seon.error/at effective)))
                    (db/q database '[:find [?k ...] :where [_ :seon.fn.cost/key ?k]]))]
    (when (and (seq cost-keys) (not (db/read-refusal? cost-keys)))
      (let [stored (db/stored-costs main cost-keys)
            unchanged? (fn [entity]
                         (let [main-entity (get stored (:seon.fn.cost/key entity))]
                           (every? #(= (first (batch/sample entity %)) (first (batch/sample main-entity %)))
                                   (filter #(batch/sample entity %) [:warm :cold]))))
            live (->> (vals (db/stored-costs database cost-keys))
                      (remove unchanged?)
                      (sort-by #(- (batch/spent %))))
            diff (batch/cost-diff (vec live) stored (:seon.config.fn.cost/min-calls effective))]
        (not-empty (into [] (take (:seon.config.test.cost/top effective)) diff))))))

(defn- agent-namespace
  "The namespace `agent-id` is in: where its latest evaluation ended (its SCI context keeps `*ns*`),
  else the namespace of the task `held`; nil with neither."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:maybe :seon.context/task]]
                  [:maybe :seon.ns/name]]}
  [database agent-id held]
  (or (when-let [eid (:db/id (db/pull database [:db/id] [:seon.agent/id agent-id]))]
        (turn/previous-ending-namespace database eid))
      (some-> (:seon.task/sym held) namespace symbol)))

(defn- branch-outcome
  "What the agent `agent-id` did on `branch-db` toward task `sym`: its model calls and tokens and its proofs'
  counts and red proofs; an error when a read refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:maybe :seon.task/sym]]
                  [:or [:map [:seon.task/calls :seon.task/calls] [:seon.task/prompt-tokens :seon.task/prompt-tokens]
                        [:seon.task/completion-tokens :seon.task/completion-tokens]
                        [:seon.task/proof-counts {:optional true} :seon.task/proof-counts]
                        [:seon.task/red-proofs {:optional true} :seon.task/red-proofs]]
                   :seon.error/value]]}
  [branch-db agent-id sym]
  (let [proven (when sym (proofs branch-db sym))
        attempts (db/q '[:find ?turn (pull ?attempt [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens])
                         :in $ ?agent-id
                         :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/attempts ?attempt]]
                       branch-db agent-id)
        total #(transduce (keep (comp % second)) + 0 attempts)]
    (or (some #(when (:seon.error/at %) %) [proven attempts])
        (cond-> {:seon.task/calls (count (into #{} (map first) attempts))
                 :seon.task/prompt-tokens (total :seon.ai.usage/prompt-tokens)
                 :seon.task/completion-tokens (total :seon.ai.usage/completion-tokens)}
          proven (assoc :seon.task/proof-counts (:seon.context/counts proven)
                        :seon.task/red-proofs (into [] (comp (filter #(= :red (:seon.context/proof-status %))) (map :seon.test/sym))
                                                    (:seon.context/proof-list proven)))))))

(def ^:private proof-count-attributes
  "The agent attribute recording each proof status's count."
  {:green :seon.agent/outcome-green :red :seon.agent/outcome-red :pending :seon.agent/outcome-pending})

(defn- outcome-facts
  "The agent attributes recording `outcome` (a `branch-outcome`)."
  {:malli/schema [:=> [:cat :map] :map]}
  [outcome]
  (cond-> {:seon.agent/outcome-calls (:seon.task/calls outcome)
           :seon.agent/outcome-prompt-tokens (:seon.task/prompt-tokens outcome)
           :seon.agent/outcome-completion-tokens (:seon.task/completion-tokens outcome)}
    (seq (:seon.task/red-proofs outcome)) (assoc :seon.agent/outcome-red-proofs (set (:seon.task/red-proofs outcome)))
    true (into (keep (fn [[status attribute]] (when-let [n (get-in outcome [:seon.task/proof-counts status])] [attribute n])))
               proof-count-attributes)))

(defn- recorded-outcome
  "The outcome recorded on agent `agent-id` when its task ended, as a `branch-outcome`; nil when none was."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:maybe :map]]}
  [database agent-id]
  (let [row (db/pull database (into [:seon.agent/outcome-calls :seon.agent/outcome-prompt-tokens :seon.agent/outcome-completion-tokens
                                     :seon.agent/outcome-red-proofs]
                                    (vals proof-count-attributes))
                     [:seon.agent/id agent-id])
        counts (into {} (keep (fn [[status attribute]] (when-let [n (get row attribute)] [status n]))) proof-count-attributes)]
    (when (and (map? row) (contains? row :seon.agent/outcome-calls))
      (cond-> {:seon.task/calls (:seon.agent/outcome-calls row)
               :seon.task/prompt-tokens (:seon.agent/outcome-prompt-tokens row)
               :seon.task/completion-tokens (:seon.agent/outcome-completion-tokens row)}
        (seq counts) (assoc :seon.task/proof-counts counts)
        (seq counts) (assoc :seon.task/red-proofs (vec (sort (:seon.agent/outcome-red-proofs row))))))))

(defn outcome-tx
  "Transaction data recording the outcome of the task `agent-id` holds, derived from its branch in `database`
  (calls, tokens, proof counts and red proofs): what the end summary needs after the branch is retired. Empty
  when the agent has no branch, no task or its evidence is unreadable, so a summary never reads zeros for evidence
  that was not there."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :seon.store/transaction-data]}
  [database agent-id]
  (let [branch (db/q '[:find ?b . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/branch ?b]] database agent-id)
        home (if-let [connection (task/home database)] (db/db connection) database)
        handle (task/agent-task home agent-id)
        sym (when (string? handle) (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/sym ?s]] home handle))
        branch-db (when (keyword? branch) (db/branch-database database branch))
        outcome (when branch-db (branch-outcome branch-db agent-id sym))]
    (if (or (nil? outcome) (:seon.error/at outcome))
      []
      [(assoc (outcome-facts outcome) :seon.agent/id agent-id)])))

(defn ended
  "The value a task's end message `entry` (an inbox entry carrying `:seon.task/end`) stands for: the task, how it
  ended, its proofs' counts and red proofs, the agent's errors grouped by signature, its model calls and tokens,
  the merge task when it ended green with its landing (the commits it made, or the refusal it met), and the
  operations root takes next. The outcome is the one recorded when
  the task ended, else derived from the agent's branch, else unknown (`:seon.task/outcome-unknown`), never zeros."
  {:malli/schema [:=> [:cat :seon.db/database-value :my.message/inbox-entry] [:or :seon.task/ended :seon.error/value]]}
  [database {agent-id :my.message/from task-id :seon.message/about end :seon.task/end merge :seon.task/merge}]
  (let [branch (db/q '[:find ?b . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/branch ?b]] database agent-id)
        home (if-let [connection (task/home database)] (db/db connection) database)
        sym (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/sym ?s]] home task-id)
        recorded (recorded-outcome database agent-id)
        branch-db (when (keyword? branch) (db/branch-database database branch))
        outcome (or recorded (when branch-db (branch-outcome branch-db agent-id sym)))
        landing (when merge
                  (let [row (db/pull home [:seon.task/landed :seon.task/landed-git :seon.task/refusal :seon.task/refused-tests]
                                     [:seon.task/id merge])]
                    (when (map? row) (cond-> (dissoc row :db/id)
                                       (:seon.task/refused-tests row) (update :seon.task/refused-tests set)))))]
    (or (when (:seon.error/at outcome) outcome)
        (cond-> (into {:seon.task/id task-id :seon.task/end end :seon.agent/id agent-id
                        :seon.render/operations
                        (cond-> [(list 'seon.context/model-context (list 'seon.db/branch-database (list 'seon.db/db) branch) {} agent-id)]
                          merge (conj (list 'my.program/prepare-merge! {:seon.task/merge merge}))
                          sym (conj (list 'seon.task/create! {:seon.task/ns (symbol (namespace sym)) :seon.task/name (name sym)
                                                              :seon.task/basis task-id})))}
                       outcome)
          (nil? outcome) (assoc :seon.task/outcome-unknown true)
          sym (assoc :seon.task/sym sym)
          (keyword? branch) (assoc :seon.agent/branch branch)
          merge (assoc :seon.task/merge merge)
          (seq landing) (into landing)
          branch-db (assoc :seon.task/errors (problems/agent-errors-by-signature database agent-id branch))))))

(defn current-state
  "The current-state block's data for `agent-id`: its held task's declaration as the
  task home reads it now, and its turns left with the task's status (`:done` once every
  proof is green, then `:capped` with no turns left, else `:working`) and the namespace
  it is in (`agent-namespace`). Sections are
  absent when empty. `:seon.context/unread` holds the agent's undelivered messages
  no model turn of it was shown yet, oldest first, each once with its id, sender,
  instant and content. `:seon.context/cost` holds the branch's stored function costs
  that differ from main's (`branch-cost`).

  ## Costs

  One assignment query, one task pull, the turn count, one pull per unread message,
  one query of the agent's handled messages, one scan of the branch's cost entities
  and one query of main's for them."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/current-state :seon.error/value]]}
  [database agent-id]
  (let [found (held-task database agent-id)
        held (when-not (:seon.error/at found) found)
        left (turn/turns-left database agent-id)
        proven (when held (proofs database (:seon.task/sym held)))
        unread (let [items (message/inbox database agent-id)]
                 (if (:seon.error/at items)
                   items
                   (let [items (unshown database agent-id items)]
                     (if (:seon.error/at items)
                       items
                       ;; a task's end message is shown with the value its references stand for, derived now
                       (mapv #(if (:seon.task/end %) (assoc % :seon.task/ended (ended database %)) %) items)))))
        cost (branch-cost database)
        namespace (agent-namespace database agent-id held)
        reads (when held (ref/references database {:seon.ref/text (:seon.task/intent held)
                                                   :seon.ref/names (:seon.task/must-read held #{})}))]
    (or (some #(when (:seon.error/at %) %) (concat [found left proven unread] reads))
        (cond-> {:seon.context/turns (cond-> {:my.agent/turns-left left}
                                       held (assoc :seon.task/status (cond (all-green? proven) :done
                                                                           (zero? left) :capped
                                                                           :else :working))
                                       namespace (assoc :seon.context/namespace namespace))}
          held (assoc :seon.context/task held)
          proven (assoc :seon.context/proofs proven)
          (seq reads) (assoc :seon.context/must-reads reads)
          cost (assoc :seon.context/cost cost)
          (seq unread) (assoc :seon.context/unread {:seon.context/items unread
                                                    :seon.render/operations ['(my.message/inbox)]})))))

(defn current-state-ai
  "The block's sections as the agent reads them: the task by symbol and declaration, without its handle.
  The render argument carries the evaluation's world beside the block; only the sections are returned."
  {:malli/schema [:=> [:cat :seon.context/current-state] :seon.context/current-state-view]}
  [block]
  (cond-> (select-keys block [:seon.context/task :seon.context/proofs :seon.context/must-reads :seon.context/cost :seon.context/unread :seon.context/turns])
    (:seon.context/task block) (update :seon.context/task dissoc :seon.task/id)))

;;; ---------------------------------------------------------------------------
;;; What a model pass sends
;;; ---------------------------------------------------------------------------

(def ^:private exchange-selector
  "An evaluation as `seon.repl/entity-emission` reads it, with its call row's attempts."
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
    {:seon.cluster.eval/run [:seon.turn/id {:seon.turn/attempts [:db/id]}]}])

(defn- exchange-form
  "One evaluation as the REPL showed it: the form as sent and what the REPL answered."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:map :seon.ai/form [:seon.ai/reply {:optional true}]]]}
  [emission]
  (let [reply (repl/reply-text emission)]
    (cond-> {:seon.ai/form (repl/input-text emission)}
      reply (assoc :seon.ai/reply reply))))

(defn- exchanges
  "The agent's own model exchanges in agent order: one per call row carrying a model attempt."
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
                   (mapv #(exchange-form (repl/entity-emission (assoc % :seon.db/db database))) run)))))))

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
        shown #(sci.eval/shown-result %1 %2 nil)
        system (shown (bootstrap/help-value database agent-id) request)
        ;; the root call names the producer whose declared AI render shows the block
        prompt (shown block (assoc request :seon.render.call/function 'seon.context/current-state))
        ;; help carries its own AI render: a request that cannot run it (no context or no
        ;; caps) prints the help map whole, with no renderer; that is a refusal, never the
        ;; system message
        unrendered (not (:seon.eval/renderer system))]
    ;; a request with no context or profile cannot render: that refusal is the answer, never
    ;; the printed help map as the system message
    (or (some #(when (:seon.error/at %) %) [history block profile])
        (when unrendered
          (seon.error.refusal/diagnostic
           (java.util.Date.) :seon.context/model-context `model-context
           (cond-> {:seon.error/type :seon.render/request-error
                    :seon.error/message "The help render did not run, so the system message would be the printed help map."
                    :seon.error/fix "Carry the turn's context, caps and time limit, as the model pass does."}
             (:seon.cluster.eval/diagnostics system)
             (assoc :seon.cluster.eval/diagnostics (:seon.cluster.eval/diagnostics system)))))
        {:seon.ai/system (:seon.eval/shown system)
         :seon.context/exchanges history
         :seon.ai/prompt (:seon.eval/shown prompt)})))
