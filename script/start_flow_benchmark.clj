(ns start-flow-benchmark
  "Commit-to-paused-agent measurements over a prepared task's commit."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [datahike.query :as query]
            [seon.batch :as batch]
            [seon.cache :as cache]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as seon.flow]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]
            [seon.turn.world :as world]))

(defn empty-cache!
  "Empty derived entries in a disposable benchmark JVM; never call on default."
  {:malli/schema [:=> [:cat] nat-int?]}
  []
  (cache/evict-kinds! (set (keys (:seon.cache/kinds (cache/status))))))

(defn costs
  "Cost without the live value, with read counts and the hottest owners."
  {:malli/schema [:=> [:cat :seon.profile/measured-call] :map]}
  [measured]
  (assoc (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/calls
                               :seon.profile/reads :seon.cost/jvm-window
                               :seon.profile/called :seon.profile/ranked-by-self])
         ::datoms (get-in measured [:seon.profile/called 'seon.db/datoms] 0)
         ::owners (select-keys (:seon.profile/called measured)
                              '[seon.test.runner/reached-graph seon.test.runner/reach-components
                                seon.schema/contract-references seon.db/content-revisions
                                seon.db/declaration-content-key seon.sci.eval/derive-base-ctx
                                seon.sci.eval/derive-overlay-ctx])
         ::hot (vec (take 3 (:seon.profile/ranked-by-self measured)))))

(def base-queries
  "The six queries of the original cold base, retained as attribution inputs."
  {::load-core-namespaces
   '[:find [?name ...]
     :where [?file :seon.fn.file/relative-root ?root]
     [(not= ?root "test")]
     [?namespace :seon.fn/file ?file]
     [?namespace :seon.ns/name ?name]
     (or-join [?namespace]
       (and [?namespace :seon.schema.admission/source :core]
            [?namespace :seon.ns/source _])
       (and [?function :seon.fn/ns ?namespace]
            [?function :seon.fn/source _]
            [?function :seon.schema.admission/source :core]))]
   ::namespace-assertions
   '[:find ?namespace-name ?source ?admission
     :where [?namespace :seon.ns/name ?namespace-name]
     [?namespace :seon.ns/source ?source]
     [?namespace :seon.schema.admission/source ?admission]]
   ::namespace-names
   '[:find [?namespace-name ...] :where [_ :seon.ns/name ?namespace-name]]
   ::function-rows
   '[:find ?sym ?source ?admission ?private
     :where [?function :seon.fn/sym ?sym]
     [(get-else $ ?function :seon.fn/source "") ?source]
     [?function :seon.schema.admission/source ?admission]
     [(get-else $ ?function :seon.fn/private? false) ?private]]
   ::test-namespaces
   '[:find [?name ...]
     :where [?file :seon.fn.file/relative-root "test"]
     [?namespace :seon.fn/file ?file]
     [?namespace :seon.ns/name ?name]]
   ::test-rows
   '[:find ?sym ?source ?namespace-name ?admission
     :where [?test :seon.test/sym ?sym] [?test :seon.test/source ?source]
     [?test :seon.schema.admission/source ?admission]
     [?test :seon.test/ns ?namespace] [?namespace :seon.ns/name ?namespace-name]]})

(defn base-query-costs
  "Measure the original base queries, with or without Datahike result reuse."
  {:malli/schema [:=> [:cat :seon.db/database-value :boolean] :map]}
  [database reuse-results?]
  (let [loaded (#'sci.eval/loaded-program database)]
    (empty-cache!)
    (binding [query/*query-result-cache?* reuse-results?]
     (update-vals base-queries
                 (fn [query]
                   (let [measured (profile/measure #(db/q query loaded))]
                     (when (db/read-refusal? (:seon.profile/value measured))
                       (throw (ex-info "The attribution query failed." (:seon.profile/value measured))))
                     (assoc (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes])
                            ::count (count (:seon.profile/value measured)))))))))

(defn- paused-graph!
  "Create and start the production agent blueprint, leaving Flow paused."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/id] :map]}
  [source agent-id]
  (let [handle (support/cluster-handle
                (assoc source :seon.db.process/id cluster/boot-process-identity
                       :seon.cluster.wake/channel (agent/wake-channel)
                       :seon.schedule/channel (async/chan (async/sliding-buffer 1))
                       :seon.agent/requests (async/chan 1)
                       :seon.turn.loop/completion (async/chan 1)))
        executor (#'seon.flow/graph-executor
                  {:seon.flow/projection-state (:seon.sci.eval/projection-state handle)})
        graph (flow/create-flow (assoc (agent/graph-definition
                                        {:seon.turn.loop/cluster handle :seon.agent/id agent-id})
                                      :io-exec executor))
        started (flow/start graph)]
    {:seon.flow/graph graph :seon.flow/executor executor :seon.flow/started started
     ::handle handle ::states (update-vals (flow/ping graph) ::flow/status)}))

(defn- graph-cost!
  "Measure graph construction, observe paused procs, then stop and join them."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/id] :map]}
  [source agent-id]
  (let [measured (profile/measure #(paused-graph! source agent-id))
        {graph :seon.flow/graph ::keys [handle states] :as started} (:seon.profile/value measured)
        bound-ms (:seon.config.agent/turn-completion-backstop-ms config/defaults)]
    (try
      (assoc (costs measured) ::states states)
      (finally
        (flow/stop graph)
        (let [joined (seon.flow/join-graph!
                      (assoc started
                             :seon.await/bound
                             {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
                              :seon.await/config-value bound-ms
                              :seon.await/deadline-nanos (+ (System/nanoTime) (* 1000000 bound-ms))}
                             :seon.await/diagnostic
                             {:seon.error/layer ::graph :seon.error/operation `graph-cost!
                              :seon.error/message "Benchmark graph did not exit."}))]
          (when joined (throw (ex-info "Benchmark graph did not exit." joined))))
        (doseq [k [:seon.cluster.wake/channel :seon.schedule/channel :seon.agent/requests
                   :seon.turn.loop/completion]]
          (async/close! (get handle k)))))))

(defn sample!
  "Fork `commit`, construct world and SCI, read the task, start a paused graph."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.source/commit-id :seon.agent/id] :map]}
  [parent commit agent-id]
  (let [store (:seon.store/store parent)
        source (db/db (:seon.db/connection parent))
        branch (keyword (str "astra-flow-bench-" (id/id)))
        branch-request {:seon.store/store store :seon.store/branch branch
                        :seon.cluster.registry/from commit :seon.db/db source}
        forked (profile/measure #(registry/fork! branch-request))]
    (when-not (:seon.cluster/created? (:seon.profile/value forked))
      (throw (ex-info "Benchmark fork failed." (:seon.profile/value forked))))
    (try
      (let [connection (store/open-branch! store branch)]
        (try
          (db/with-branch
           {:seon.db/connection connection}
           (fn []
             (let [database (db/db connection)
                   cluster (:seon.cluster/name parent)
                   built-world (profile/measure
                                #(world/world {:seon.boot/cluster-name cluster :seon.db/connection connection
                                               :seon.agent/id agent-id :seon.agent/branch branch
                                               :seon.batch/sink (batch/sink)}))
                   acquired (profile/measure
                             #(sci.eval/fork-cluster-ctx (sci.eval/base-ctx database) database connection))
                   ctx (:seon.profile/value acquired)
                   first-context (profile/measure #(context/current-state database agent-id))
                   handle (env/carry
                           (assoc (merge parent (:seon.profile/value built-world))
                                  :seon.db/connection connection :seon.sci.eval/ctx ctx
                                  :seon.sci.eval/agent-ctx ctx
                                  :seon.sci.eval/projection-state (:seon.sci.eval/projection-state ctx))
                           (env/of ctx))]
               {::commit commit
                ::branch (costs forked)
                ::world (assoc (costs built-world) ::members (count (:seon.profile/value built-world)))
                ::sci (costs acquired)
                ::context (costs first-context)
                ::context-value (:seon.profile/value first-context)
                ::graph (graph-cost! handle agent-id)})))
          (finally (store/release-branch! connection))))
      (finally (registry/retire-branch! (select-keys branch-request [:seon.store/store :seon.store/branch]))))))
