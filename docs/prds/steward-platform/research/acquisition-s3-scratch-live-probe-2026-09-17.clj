; Run this single JVM form only on root tmp/s3-live-root, cluster s3-live.
; A is the sole seeded agent. B and C are disposable SCI forks, not agent rows.
; The accepted declaration traverses the ordinary proc and installation gate.
(do
  (require '[clojure.core.async :as async] '[datahike.api :as d]
           '[seon.db :as db] '[seon.operator] '[seon.operator.runtime]
           '[seon.cluster.agent :as agent] '[seon.turn :as turn]
           '[seon.sci.eval :as evaluation] '[seon.config :as config]
           '[seon.program :as program] '[seon.schema :as schema] '[sci.core :as sci])
  (let [connection (seon.operator/connection "s3-live")
        instance (get @seon.operator.runtime/running-instances "s3-live")
        cluster (:seon.turn.loop/cluster instance)
        base (:seon.sci.eval/ctx cluster)
        routing (:seon.agent/routing instance)
        agent-id "s3-live-a"
        source "(defn- uuid-text {:malli/schema [:=> [:cat] :string]} [] \"s3-accepted-database-definition\")"
        target 'seon.eval.drive/uuid-text
        checked (fn [value]
                  (when (:seon.error/kind value)
                    (throw (ex-info (:seon.error/message value) value)))
                  value)
        initial-agent-count (db/q '[:find (count ?a) . :where [?a :seon.agent/id]] (db/db connection))
        _ (checked
           (db/transact!
            connection
            (mapv (fn [row]
                    (if (:seon.agent/id row)
                      (assoc-in row [:seon.agent/settings :seon.config.ai/no-provider] true)
                      row))
                  (agent/creation-tx {:seon.cluster/name "s3-live"
                                      :seon.agent/id agent-id
                                      :seon.ns/name 'seon.eval.drive}))))
        before (db/db connection)
        fork (fn [ctx old database]
               (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 (cond-> {:seon.sci.eval/ctx ctx :seon.db/db database
                          :seon.agent/id agent-id}
                   old (assoc :seon.sci.eval/agent-ctx old)))))
        run-in (fn [ctx database]
                 (evaluation/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db database
                   :seon.schema/projection (:seon.schema/projection ctx)
                   :seon.cluster.eval/source "(seon.eval.drive/uuid-text)"
                   :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                   :seon.sci.eval/time-limit-ms 2000
                   :seon.config/on-core-error :panic}))
        b (fork base nil before)
        private-object (atom :s3-private)
        _ (sci/intern b 'user 's3-private private-object)
        before-value (run-in b before)
        bound (:seon.config.agent/turn-completion-backstop-ms
               (checked (config/effective before "s3-live")))
        deadline (+ (System/nanoTime) (* 1000000 bound))
        event (async/chan (async/sliding-buffer 1))
        listener (Object.)
        await! (fn []
                 (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
                   (when (or (not (pos? remaining))
                             (not= event (second (async/alts!! [event (async/timeout remaining)]))))
                     (throw (ex-info "Scratch accepted-definition turn did not settle."
                                     {:s3/agent agent-id :s3/source source :s3/bound-ms bound})))))
        _ (d/listen connection listener
                    (fn [report]
                      (when (some #(and (= :seon.turn/closed-tx (:a %)) (:added %))
                                  (:tx-data report))
                        (async/offer! event true))))
        turn-id
        (try
          (loop []
            (let [submitted (turn/virtual-turn!
                             {:seon.turn.loop/cluster cluster :seon.agent/routing routing
                              :seon.agent/id agent-id :seon.cluster.reply/text source})]
              (if (#{:seon.turn/agent-already-running :seon.turn/run-exists}
                    (:seon.turn/rule submitted))
                (do (await!) (recur))
                (let [id (:seon.turn/id (checked submitted))]
                  (loop []
                    (when-not (:seon.turn/closed-tx
                               (db/pull (db/db connection) [:seon.turn/closed-tx]
                                        [:seon.turn/id id]))
                      (await!)
                      (recur)))
                  id))))
          (finally (d/unlisten connection listener) (async/close! event)))
        after (db/db connection)
        admitted (db/pull after [:seon.fn/sym :seon.fn/source :seon.schema.admission/source]
                          [:seon.fn/sym (str target)])
        _ (when-not (and (= :agent (:seon.schema.admission/source admitted))
                         (= source (:seon.fn/source admitted)))
            (throw (ex-info "The real installation gate did not accept the override."
                            {:s3/turn turn-id :s3/admitted admitted})))
        base-result (run-in base after)
        started (System/nanoTime)
        regenerated (fork base b after)
        private-ms (/ (- (System/nanoTime) started) 1e6)
        b-result (run-in regenerated after)
        c (fork base nil after)
        c-result (run-in c after)
        started (System/nanoTime)
        rebuilt (evaluation/base-ctx after)
        rebuilt-ms (/ (- (System/nanoTime) started) 1e6)
        rebuilt-result (run-in rebuilt after)
        started (System/nanoTime)
        installed (schema/call-with-projection
                   (:seon.schema/projection rebuilt)
                   #(evaluation/install-row!
                     {:seon.sci.eval/ctx rebuilt :seon.db/db after
                      :seon.program/row
                      (assoc (dissoc admitted :db/id)
                             :seon.fn/ns [:seon.ns/name 'seon.eval.drive])}))
        install-ms (/ (- (System/nanoTime) started) 1e6)
        evaluations (db/q '[:find [(pull ?e [*]) ...] :in $ ?id
                            :where [?t :seon.turn/id ?id] [?e :seon.cluster.eval/run ?t]]
                          after turn-id)]
    {:s3/pid (.pid (java.lang.ProcessHandle/current))
     :s3/before-t (db/basis-t before) :s3/after-t (db/basis-t after)
     :s3/initial-agent-count initial-agent-count
     :s3/agent-count (db/q '[:find (count ?a) . :where [?a :seon.agent/id]] after)
     :s3/turn-id turn-id :s3/admitted admitted
     :s3/a-evaluations (mapv #(select-keys % [:seon.cluster.eval/id :seon.cluster.eval/source
                                             :seon.cluster.eval/error :seon.eval/shown
                                             :seon.test.accretion/gate-test-count
                                             :seon.test.accretion/gate-pass-count]) evaluations)
     :s3/b-before (:seon.sci.admit/value before-value)
     :s3/base (:seon.sci.admit/value base-result)
     :s3/b-next (:seon.sci.admit/value b-result)
     :s3/c-fresh (:seon.sci.admit/value c-result)
     :s3/rebuilt (:seon.sci.admit/value rebuilt-result)
     :s3/private-ms private-ms :s3/rebuilt-ms rebuilt-ms
     :s3/accepted-row-install-ms install-ms
     :s3/install-state (:seon.sci.eval/load-state installed)
     :s3/b-handle-identical (identical? b regenerated)
     :s3/private-object-identical (identical? private-object @(sci/resolve regenerated 'user/s3-private))
     :s3/b-base-root-identical (identical? @(sci/resolve base target) @(sci/resolve regenerated target))
     :s3/c-base-root-identical (identical? @(sci/resolve base target) @(sci/resolve c target))
     :s3/overrides (program/overrides after)
     :s3/doc-note (:seon.schema.admission/note (evaluation/documentation-value after target target))}))
