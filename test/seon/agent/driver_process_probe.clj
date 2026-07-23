(ns seon.agent.driver-process-probe
  "External JVM process probe for claim arbitration and cursor evidence."
  (:require [seon.agent.driver :as driver]
            [seon.agent.driver.host :as driver.host]
            [seon.agent.run.core :as run.core]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]))

(defn- await-start! [start-ms]
  (let [remaining (- start-ms (System/currentTimeMillis))]
    (when (pos? remaining)
      (Thread/sleep remaining))))

(defn- render-step
  [_turn-label
   {:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        fence (run.core/run-fence agent-id run-id claim-epoch)
        allocations
        [{::db.id/key ::turn-id
          ::db.id/identity-attr :seon.agent.turn/id}]
        manifest
        (db.id/candidate-manifest
         {:seon.agent.turn/id :seon.db.id.generator/compact}
         allocations)
        turn-id (::db.id/value (first manifest))
        report
        (db/transact!
         {::db/db database
          ::db.id/generated-candidates manifest
          ::db/tx-data
          (conj
           (vec fence)
           {:seon.agent.turn/id turn-id
            :seon.agent.turn/at (java.util.Date.)
            :seon.agent.turn/status :running
            :seon.agent.turn/phase :rendered
            :seon.agent.turn/rendered-tx (:t database)
            :seon.agent.turn/prompt-chars 0
            :seon.agent.turn/run [:seon.agent.run/id run-id]})})]
    (if (:seon.error/message report)
      report
      {:seon.db/db (:db-after report)
       :seon.agent.turn/id turn-id})))

(defn -main
  "Race one external process through claim and render."
  [& [writer-socket database-name database-path agent-id run-id
      turn-label start-ms mode]]
  (let [writer
        (db.host/writer-session
         {::db.host/writer-socket-path writer-socket
          ::db.host/database-name database-name
          ::db.host/database-path database-path
          ::db.host/backend :file})
        database-leaf (driver.host/database-leaf writer)
        platform-leaf
        {:seon.agent.driver/capabilities
         (if (= "claim-only" mode)
           #{:seon.agent.driver.capability/render
             :seon.agent.driver.capability/llm
             :seon.agent.driver.capability/eval
             :seon.agent.driver.capability/publish}
           #{:seon.agent.driver.capability/render})
         :seon.agent.driver/now #(java.util.Date.)
         :seon.agent.driver/execute-step!
         #(render-step turn-label %)}]
    (try
      (let [original-transact db/transact!
            first-transaction? (atom true)]
        (with-redefs
          [db/transact!
           (fn [request]
             (when (compare-and-set! first-transaction? true false)
               (await-start! (parse-long start-ms)))
             (original-transact request))]
          (prn
           (driver/call-with-leaf
            platform-leaf
            database-leaf
            #((if (= "claim-only" mode) driver/claim! driver/drive-run!)
              {:seon.agent/id agent-id
               :seon.agent.run/id run-id})))))
      (finally
        (db.host/close-session! writer)))))
