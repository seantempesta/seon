(ns seon.agent.lifecycle
  "Control durable agent lifecycle and process-local hosting.

   This agent-facing namespace exposes scoped operations for waiting,
   completing, pausing, resuming, and terminating work. It validates authority
   and returns errors as data. It also connects database-authored agent
   identities to ephemeral execution resources and cleanup. Durable lifecycle
   truth stays in database facts; process state is retained only where the host
   must manage a live runtime."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require
   [clojure.string :as str]
   #?@(:cljs [[seon.agent.home :as home]
              [seon.agent.loop :as loop]
              [seon.agent.run :as run]
              [seon.ai.dispatch :as ai.dispatch]
              [seon.runtime.admission :as admission]])
   [seon.agent.lifecycle.core :as core]
   [seon.agent.lifecycle.leaf :as leaf]
   #?(:cljs [seon.agent.lifecycle.pod :as pod])
   [seon.agent.message :as message]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(defn- register-schema! [key definition]
  #?(:cljs (schema/register! key definition)
     :clj nil))

(def ^:dynamic *leaf* nil)
(declare wait complete pause resume terminate)

(defn bind-leaf
  "Return agent-facing lifecycle functions bound to one platform leaf."
  ([platform-leaf] (bind-leaf platform-leaf nil))
  ([platform-leaf database-leaf]
  (into {}
        (map (fn [v]
               [(:name (meta v))
                (fn [& args]
                  (binding [*leaf* platform-leaf
                            db/*leaf* (or database-leaf db/*leaf*)]
                    (apply @v args)))])
             [#'wait #'complete #'pause #'resume #'terminate]))))

(defn- platform-leaf [] (or *leaf* #?(:cljs (pod/services) :clj nil)))
(defn- leaf-fn [key]
  (or (get (platform-leaf) key)
      (throw (ex-info "The lifecycle platform leaf is not installed."
                      {:seon.error/kind :core-bug}))))

(register-schema! ::note :string)
(register-schema! ::result :string)
(register-schema! ::target-request
  [:map [:seon.agent/id {:optional true} :seon.agent/id]])
(register-schema! ::direct-error
  [:map [:seon.error/message :string]])
(register-schema! ::lifecycle-result
  [:or :seon.derive/state ::direct-error])

(register-schema! ::wake? [:boolean {:default true}])
(register-schema! ::llm-fn 'fn?)
(register-schema! ::resumed? :boolean)
(register-schema! ::unhosted? :boolean)
(register-schema! ::unhosted-ids [:vector :seon.agent/id])
(register-schema! ::error :string)

(register-schema! ::resume-request
  [:map
   [:seon.agent/id :seon.agent/id]
   [::llm-fn {:optional true} ::llm-fn]])

(register-schema! ::resume-response
  [:or
   [:map
    [:seon.agent/id :seon.agent/id]
    [:seon.agent/ns :symbol]
    [::resumed? [:= true]]]
   [:map
    [:seon.agent/id :seon.agent/id]
    [::resumed? [:= false]]
    [::error ::error]
    [:seon/error {:optional true} :map]]])

(register-schema! ::unhost-request
  [:map [:seon.agent/id :seon.agent/id]])
(register-schema! ::unhost-response
  [:map
   [:seon.agent/id :seon.agent/id]
   [::unhosted? [:= true]]])
(register-schema! ::unhost-all-response
  [:map {:closed true}
   [::unhosted-ids ::unhosted-ids]])

(defn- error-value?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- error-value
  ([message]
   {:seon.error/message message})
  ([message data]
   {:seon.error/message message
    :seon.error/data data}))

(defn- wake-armed?
  "Whether an acquired agent row enables its automatic message interest."
  [entity]
  (not= false (::wake? entity)))

(defn- database-error? [value]
  (and (map? value) (string? (:seon.error/message value))))

#?(:cljs
   (do
(defn unhost!
  "Remove every process-local handle for one agent; idempotent."
  {:malli/schema [:=> [:cat ::unhost-request] ::unhost-response]}
  [{:seon.agent/keys [id]}]
  (loop/uninstall-wake-trigger! {:seon.agent/id id})
  {:seon.agent/id id ::unhosted? true})

(defn unhost-all!
  "Remove every agent runtime hosted by this process."
  {:malli/schema [:=> [:cat] ::unhost-all-response]}
  []
  {::unhosted-ids
   (:seon.agent.loop/uninstalled-ids (loop/uninstall-all-wake-triggers!))})

(defn ^:async resume!
  "Reconstruct one existing, nonterminated agent in this process.

   The database entity must already exist. The function replaces any stale
   loop listener/input. The supervised execution child reconstructs the
   agent's compiler and authored program lazily. No cluster seed, program
   replay, global instrumentation, identity allocation, or duplicate
   membership bookkeeping occurs here."
  {:malli/schema [:=> [:cat ::resume-request] ::resume-response]}
  [{:seon.agent/keys [id] ::keys [llm-fn]}]
  (if-not (admission/available?)
    {:seon.agent/id id
     ::resumed? false
     ::error "resume!: runtime program generation is unavailable"
     :seon/error (:seon/error (admission/unavailable))}
    (let [entity (await
                  (db/pull
                   {::db/pull-pattern
                    [:seon.agent/id :seon.agent/terminated-at ::wake?
                     {:seon.agent/namespace [:seon.ns/name]}]
                    ::db/ref [:seon.agent/id id]}))]
      (cond
        (database-error? entity)
        {:seon.agent/id id
         ::resumed? false
         ::error "resume!: database authority read failed"
         :seon/error entity}

        (nil? entity)
        {:seon.agent/id id
         ::resumed? false
         ::error (str "resume!: no durable agent entity for " id)}

        (some? (:seon.agent/terminated-at entity))
        (do
          (unhost! {:seon.agent/id id})
          {:seon.agent/id id
           ::resumed? false
           ::error (str "resume!: agent " id " is terminated")})

        :else
        (let [llm (or llm-fn (ai.dispatch/llm-fn))
              ns  (home/starting-ns id entity)]
          (await
            (db/with-agent id
              (fn ^:async resume-agent! []
                (when (admission/available?)
                  (if (wake-armed? entity)
                    (do
                      (await
                       (loop/install-wake-trigger!
                        {:seon.agent/id id
                         :seon.agent/llm-fn llm}))
                      (loop/drive-run! {:seon.agent/id id}))
                    (loop/uninstall-wake-trigger! {:seon.agent/id id}))))))
          (if (admission/available?)
            {:seon.agent/id id
             :seon.agent/ns ns
             ::resumed? true}
            {:seon.agent/id id
             ::resumed? false
             ::error "resume!: runtime program generation became unavailable"
             :seon/error (:seon/error (admission/unavailable))}))))))
     )
   :clj
   (do
     (defn unhost! [{:seon.agent/keys [id]}]
       {:seon.agent/id id ::unhosted? true})
     (defn unhost-all! [] {::unhosted-ids []})
     (defn resume! [{:seon.agent/keys [id]}]
       {:seon.agent/id id ::resumed? false
        ::error "resume!: process-local hosting is available only in the pod"})))

(defn- no-open-run-error
  [function-name agent-id]
  (error-value
   (str function-name ": agent " (pr-str agent-id)
        " has no open run to act on (it is not currently running).")))

(defn ^:async ^:private current-run [database agent-id]
  #?(:cljs
     (await (run/current-run {:seon.agent/id agent-id :seon.db/db database}))
     :clj
     (let [agent (await (db/pull
                      {::db/db database
                       ::db/pull-pattern
                       [{:seon.agent/run
                         [:seon.agent.run/id :seon.agent.run/status
                          :seon.agent.run/started-at :seon.agent.run/deadline
                          :seon.agent.run/paused-at :seon.agent.run/remaining-ms]}]
                       ::db/ref [:seon.agent/id agent-id]}))
        run (:seon.agent/run agent)]
    (cond (error-value? agent) agent
          (= :open (:seon.agent.run/status run)) run
          :else nil))))

(defn ^:async ^:private pause-run! [database agent-id run-id]
  #?(:cljs
     (await (run/pause! {:seon.agent/id agent-id
                         :seon.agent.run/id run-id
                         :seon.db/db database}))
     :clj
     (let [run (await (db/pull {::db/db database
                             ::db/pull-pattern [:seon.agent.run/deadline]
                             ::db/ref [:seon.agent.run/id run-id]}))]
    (if-let [deadline (:seon.agent.run/deadline run)]
      (await (db/transact! {::db/db database
                            ::db/tx-data (core/pause-tx-data
                                          agent-id run-id deadline
                                          ((leaf-fn ::leaf/now)))}))
      (if (error-value? run) run
          (error-value (str "pause!: no run " (pr-str run-id) ".")))))))

(defn ^:async ^:private resume-run! [database agent-id run-id]
  #?(:cljs
     (await (run/resume! {:seon.agent/id agent-id
                          :seon.agent.run/id run-id
                          :seon.db/db database}))
     :clj
     (let [run (await (db/pull {::db/db database
                             ::db/pull-pattern [:seon.agent.run/paused-at
                                                :seon.agent.run/remaining-ms]
                             ::db/ref [:seon.agent.run/id run-id]}))
        paused-at (:seon.agent.run/paused-at run)
        remaining-ms (:seon.agent.run/remaining-ms run)]
    (cond (error-value? run) run
          (or (nil? paused-at) (nil? remaining-ms))
          (error-value (str "resume!: run " (pr-str run-id)
                            " is not paused with a banked remaining duration."))
          :else
          (await (db/transact! {::db/db database
                                ::db/tx-data (core/resume-tx-data
                                              agent-id run-id paused-at remaining-ms
                                              ((leaf-fn ::leaf/now)))}))))))

(defn ^:async ^:private acquire-target
  [database function-name caller-id target-id]
  (let [target
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern core/managed-agent-selector
           ::db/ref [:seon.agent/id target-id]}))]
    (cond
      (error-value? target) target
      (not (core/manages? caller-id target))
      (core/unauthorized function-name caller-id target-id)
      :else target)))

(defn- stale-database-error?
  [value]
  (= protocol/stale-database-value-error
     (get-in value [:seon.error/data ::protocol/error-kind])))

(def ^:private maximum-lifecycle-attempts 32)

(defn ^:async ^:private retry-stale!
  [operation]
  (loop [attempt 1]
    (let [result (await (operation))]
      (if (and (stale-database-error? result)
               (< attempt maximum-lifecycle-attempts))
        (recur (inc attempt))
        result))))

(defn- completed-test-error
  [test-runs]
  (when-let [{:seon.agent.testrun/keys [passed failed errors]}
             (when (seq test-runs)
               (apply max-key :db/id test-runs))]
    (when (or (pos? (or failed 0)) (pos? (or errors 0)))
      (error-value
       (str "complete refused — your latest test run is RED ("
            (or failed 0) " failed, " (or passed 0) " passed"
            (when (pos? (or errors 0))
              (str ", " errors " error" (when (not= errors 1) "s")))
            "). Run the tests and SEE a green result render before completing; "
            "to stop without claiming success, pause or report the honest status "
            "with message/user.")))))

(defn- close-transaction-data
  [agent-id run-id reason closed-at]
  (core/close-tx-data agent-id run-id reason closed-at))

(defn ^{:async #?(:cljs true :clj false) :seon.fn/agent-facing? true
        :seon.capability/effect :external} wait
  "Park the calling agent by closing its current run as `:waited`."
  {:malli/schema [:=> [:catn [::note :string]] ::lifecycle-result]}
  [_note]
  (if-let [agent-id (db/current-agent-id)]
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [current (await (current-run database agent-id))]
          (cond
            (error-value? current) current
            (nil? current) (no-open-run-error "wait" agent-id)
            :else
            (let [report
                  (await
                   (db/transact!
                    {::db/db database
                     ::db/tx-data
                     (close-transaction-data
                      agent-id (:seon.agent.run/id current) :waited
                      ((leaf-fn ::leaf/now)))}))]
              (if (error-value? report) report :idle))))))
    (core/no-agent-error "wait")))

(def ^:private completion-agent-selector
  '[:db/id :seon.agent/id
    {:seon.agent/parent [:db/id :seon.agent/id]}
    {:seon.agent.testrun/_agent
     [:db/id :seon.agent.testrun/passed :seon.agent.testrun/failed
      :seon.agent.testrun/errors]}])

(defn ^:async ^:private completion-data
  [database agent-id current]
  (let [agent
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern completion-agent-selector
           ::db/ref [:seon.agent/id agent-id]}))]
    (if (error-value? agent)
      agent
      (let [parent (:seon.agent/parent agent)
            recipient-ref
            (if parent
              [:seon.agent/id (:seon.agent/id parent)]
              message/user-ref)
            recipient
            (if parent
              parent
              (await
               (db/pull
                {::db/db database
                 ::db/pull-pattern [:db/id :seon.user/id]
                 ::db/ref message/user-ref})))]
        (if (error-value? recipient)
          recipient
          (let [messages
                (await
                 (db/query
                  {::db/db database
                   ::db/query
                   '[:find ?message ?at
                     :in $ ?from ?to
                     :where
                     [?message :seon.agent.message/from ?from]
                     [?message :seon.agent.message/to ?to]
                     [?message :seon.agent.message/at ?at]]
                   ::db/args [(:db/id agent) (:db/id recipient)]
                   ::db/max-results 10000
                   ::db/max-result-weight 524288}))]
            (if (error-value? messages)
              messages
              {:seon.agent.lifecycle/agent agent
               :seon.agent.lifecycle/recipient recipient-ref
               :seon.agent.lifecycle/already-messaged?
               (boolean
                (some
                 (fn [[_ at]]
                   (>= (inst-ms at)
                       (inst-ms (:seon.agent.run/started-at current))))
                 messages))})))))))

(defn- completion-transaction-data
  [agent-id run-id result result-ref message-data ids now]
  (let [[fence close-row retract-pointer]
        (close-transaction-data agent-id run-id :completed now)
        result-row
        (cond-> {:seon.agent.run/id run-id}
          (not (str/blank? result)) (assoc :seon.agent.run/result result)
          (some? result-ref) (assoc :seon.agent.run/result-ref result-ref))
        message-rows
        (if message-data
          (:seon.db/tx-data
           ((:seon.agent.message/transaction-builder message-data) ids))
          [])]
    (into [fence]
          (concat
           (when (> (count result-row) 1) [result-row])
           message-rows
           [close-row retract-pointer]))))

(defn ^:async ^:private complete-once
  [result result-ref op-id]
  (if-let [agent-id (db/current-agent-id)]
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [current (await (current-run database agent-id))]
          (cond
            (error-value? current) current
            (nil? current) (no-open-run-error "complete" agent-id)
            :else
            (let [acquired (await (completion-data database agent-id current))]
              (if (error-value? acquired)
                acquired
                (or
                 (completed-test-error
                  (:seon.agent.testrun/_agent
                   (:seon.agent.lifecycle/agent acquired)))
                 (let [send?
                       (and (not (str/blank? result))
                            (not (:seon.agent.lifecycle/already-messaged?
                                  acquired)))
                       message-data
                       (when send?
                         (await
                          (message/message-transaction-for
                           database
                           {:seon.agent.message/content result
                            :seon.agent.message/from
                            [:seon.agent/id agent-id]
                            :seon.agent.message/to
                            [(:seon.agent.lifecycle/recipient acquired)]})))
                       run-id (:seon.agent.run/id current)]
                   (if (error-value? message-data)
                     message-data
                     (if message-data
                       (await
                        (db.id/allocate!
                         {::db/db database
                          ::db.id/allocations
                          (:seon.agent.message/allocations message-data)
                          ::db.id/transaction-builder
                          (fn [ids]
                            {::db/expected-db database
                             :seon.capability/op-id op-id
                             ::db/tx-data
                             (completion-transaction-data
                              agent-id run-id result result-ref message-data ids
                              ((leaf-fn ::leaf/now)))})}))
                       (await
                        (db/transact!
                         {::db/db database
                          :seon.capability/op-id op-id
                          ::db/expected-db database
                          ::db/tx-data
                          (completion-transaction-data
                           agent-id run-id result result-ref nil nil
                           ((leaf-fn ::leaf/now)))}))))))))))))
    (core/no-agent-error "complete")))

(defn ^:async ^:private complete*
  [result result-ref]
  (let [op-id ((leaf-fn ::leaf/uuid))
        final-result
        (await (retry-stale! #(complete-once result result-ref op-id)))]
    (if (error-value? final-result) final-result :idle)))

(defn ^{:async #?(:cljs true :clj false) :seon.fn/agent-facing? true
        :seon.capability/effect :idempotent} complete
  "Complete the current run atomically with its result and optional message."
  {:malli/schema
   [:function
    [:=> [:catn [::result :string]] ::lifecycle-result]
    [:=> [:catn [::result :string] [::result-ref :seon.db/ref]]
     ::lifecycle-result]]}
  ([result] (await (complete* result nil)))
  ([result result-ref] (await (complete* result result-ref))))

(defn ^{:async #?(:cljs true :clj false) :seon.fn/agent-facing? true
        :seon.capability/effect :external} pause
  "Pause the current run of a managed agent."
  {:malli/schema
   [:function
    [:=> [:catn] ::lifecycle-result]
    [:=> [:cat ::target-request] ::lifecycle-result]]}
  ([] (await (pause {})))
  ([{target-id :seon.agent/id}]
   (let [caller-id (db/current-agent-id)
         target-id (or target-id caller-id)]
     (if-not caller-id
       (core/no-agent-error "pause")
       (let [database (await (db/db))]
         (if (error-value? database)
           database
           (let [target
                 (await
                  (acquire-target database "pause" caller-id target-id))]
             (if (error-value? target)
               target
               (let [current (await (current-run database target-id))]
                 (cond
                   (error-value? current) current
                   (nil? current) (no-open-run-error "pause" target-id)
                   :else
                   (let [report
                         (await (pause-run! database target-id
                                            (:seon.agent.run/id current)))]
                     (if (error-value? report) report :paused))))))))))))

(defn ^{:async #?(:cljs true :clj false) :seon.fn/agent-facing? true
        :seon.capability/effect :external} resume
  "Resume the paused current run of a managed agent."
  {:malli/schema
   [:function
    [:=> [:catn] ::lifecycle-result]
    [:=> [:cat ::target-request] ::lifecycle-result]]}
  ([] (await (resume {})))
  ([{target-id :seon.agent/id}]
   (if-not ((leaf-fn ::leaf/available?))
     ((leaf-fn ::leaf/unavailable))
     (let [caller-id (db/current-agent-id)
           target-id (or target-id caller-id)]
       (if-not caller-id
         (core/no-agent-error "resume")
         (let [database (await (db/db))]
           (if (error-value? database)
             database
             (let [target
                   (await
                    (acquire-target database "resume" caller-id target-id))]
               (if (error-value? target)
                 target
                 (let [current (await (current-run database target-id))]
                   (cond
                     (error-value? current) current
                     (nil? current) (no-open-run-error "resume" target-id)
                     :else
                     (let [report
                           (await (resume-run! database target-id
                                               (:seon.agent.run/id current)))]
                       (cond
                         (error-value? report) report
                         (not ((leaf-fn ::leaf/available?)))
                         ((leaf-fn ::leaf/unavailable))
                         :else :running)))))))))))))

(defn ^:async ^:private terminate-once
  [caller-id target-id]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [target
            (await
             (acquire-target database "terminate" caller-id target-id))]
        (cond
          (error-value? target) target
          (:seon.agent/terminated-at target) :terminated
          :else
          (let [current (await (current-run database target-id))]
            (if (error-value? current)
              current
                (let [termination
                    [:db.fn/cas [:seon.agent/id target-id]
                     :seon.agent/terminated-at nil ((leaf-fn ::leaf/now))]
                    release-namespace
                    [:db.fn/retractAttribute [:seon.agent/id target-id]
                     :seon.agent/namespace]
                    close-data
                    (when current
                      (close-transaction-data
                       target-id (:seon.agent.run/id current) :terminated
                       ((leaf-fn ::leaf/now))))
                    report
                    (await
                     (db/transact!
                      {::db/db database
                       ::db/expected-db database
                       ::db/tx-data
                       (into [termination release-namespace] close-data)}))]
                (if (error-value? report) report :terminated)))))))))

(defn ^{:async #?(:cljs true :clj false) :seon.fn/agent-facing? true
        :seon.capability/effect :idempotent} terminate
  "Terminate a managed non-root agent and atomically close its current run."
  {:malli/schema [:=> [:catn [::id :seon.agent/id]] ::lifecycle-result]}
  [target-id]
  (if-let [caller-id (db/current-agent-id)]
    (if (= "root" target-id)
      (error-value "terminate: the cluster root cannot be terminated.")
      (await (retry-stale! #(terminate-once caller-id target-id))))
    (core/no-agent-error "terminate")))
