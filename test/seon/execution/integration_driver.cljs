(ns seon.execution.integration-driver
  "Real two-child program publication proof driver."
  (:require
   [cljs.reader :as reader]
   [seon.agent]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.execution :as execution]
   [seon.execution.host :as host]
   [seon.execution.runtime]
   [seon.launch :as launch]
   [seon.schema :as schema]))

(defn- descriptor
  [socket-path database-name execution-output execution-digest]
  (let [base
        (launch/default-descriptor
         {::launch/cluster-dir (str "tmp/" database-name)
          ::launch/artifact-flavor :seon.dev.artifact.flavor/default
          ::launch/client-build-id "client"
          ::launch/execution-build-id "execution"
          ::launch/execution-output execution-output
          ::launch/request-socket-path socket-path
          ::launch/writer-repl-port-file "tmp/execution-proof.writer.port"
          ::launch/process-dir "tmp/execution-proof-processes"
          ::launch/log-dir "logs/execution-proof"
          ::launch/http-port 0
          ::launch/http-port-file "tmp/execution-proof.http.port"})
        memory-descriptor
        (assoc base ::launch/database
               (assoc (::launch/database base)
                      ::protocol/database-name database-name
                      ::protocol/backend :memory))]
    (launch/with-execution-artifact
     {::launch/descriptor memory-descriptor
      ::launch/execution-build-id "execution"
      ::launch/execution-output execution-output
      ::launch/execution-digest execution-digest})))

(defn- native-spawn!
  [spawns options]
  (let [startup (execution/decode-message
                 (nth (::host/cmd options) 2))
        process
        (js/Bun.spawn
         #js {:cmd (clj->js (::host/cmd options))
              :ipc (::host/ipc options)
              :stdout (::host/stdout options)
              :stderr (::host/stderr options)})]
    (swap! spawns update (::execution/agent-id startup) (fnil conj [])
           (.-pid process))
    process))

(defn- ^:async invoke!
  [database agent-id function-symbol arguments]
  (let [messages
        (await
         (host/invoke-plans!
          database
          [(execution/invocation-plan agent-id function-symbol arguments)]))
        message (first messages)]
    (if (= execution/result-message (::execution/message message))
      {::value (::execution/result message)
       ::database (:seon.db/db message)}
      (throw
       (ex-info "Execution child invocation failed."
                {:seon.execution.integration-driver/message message})))))

(defn- parallel-invoke!
  [database agent-ids function-symbol arguments]
  (-> (js/Promise.all
       (clj->js
        (mapv #(invoke! database % function-symbol arguments) agent-ids)))
      (.then #(vec (array-seq %)))))

(defn- emit! [value]
  (println (pr-str value)))

(defn ^:async run-proof!
  "Run two children across one accepted database-wide program change."
  [socket-path database-name execution-output execution-digest initial-database]
  (let [opened
        (await
         (db/open-session!
          {:seon.db/socket-path socket-path
           :seon.db/database-name database-name
           :seon.db/backend :memory}))
        spawns (atom {})
        agent-ids ["agent-a" "agent-b"]
        current-symbol 'my.execution-proof/current
        publish-symbol 'my.execution-proof/publish!
        new-current-source
        (str "(defn current []\n"
             "  {:seon.execution-proof/agent (db/current-agent-id)\n"
             "   :seon.execution-proof/pid (.-pid js/process)\n"
             "   :seon.execution-proof/value :after\n"
             "   :seon.execution-proof/removed-absent?\n"
             "   (nil? (eval/lookup-value 'my.execution-proof/removed))\n"
             "   :seon.execution-proof/publish-absent?\n"
             "   (nil? (eval/lookup-value 'my.execution-proof/publish!))})")
        update-data
        [{:seon.fn/sym "my.execution-proof/current"
          :seon.fn/source new-current-source}
         [:db.fn/retractEntity
          [:seon.fn/sym "my.execution-proof/removed"]]
         [:db.fn/retractEntity
          [:seon.fn/sym "my.execution-proof/publish!"]]]]
    (host/configure!
     {::host/launch-descriptor
      (descriptor socket-path database-name execution-output execution-digest)
      ::host/javascript-runtime "bun"
      ::host/ready-timeout-ms 30000
      ::host/idle-timeout-ms 120000
      ::host/cancel-grace-ms 1000
      ::host/spawn! (partial native-spawn! spawns)})
    (try
      (let [schema-report
            (await
             (db/transact!
              {:seon.db/db initial-database
               :seon.db/tx-data
               (into []
                     (keep (fn [[key form]]
                             (when (and (keyword? key)
                                        (not (and (vector? form)
                                                  (= :maybe (first form)))))
                               {:seon.schema/key key
                                :seon.schema/form (schema/form-string key)})))
                     (schema/registered-schemas))
               :seon.db/tx-meta
               {:seon.db/user [:seon.agent/id "agent-a"]
                :seon.db/process
                [:seon.db.process/id :seon.db.process/repl]}}))
            _ (when-not (:db-after schema-report)
                (throw
                 (ex-info "The compiled schema transaction failed."
                          {:seon.execution-proof/schema-report schema-report})))
            initial-database (:db-after schema-report)
            program-probe
            (await
             (db/query
              {:seon.db/db initial-database
               :seon.db/query
               '[:find ?sym ?source
                 :where
                 [?function :seon.fn/sym ?sym]
                 [?function :seon.fn/source ?source ?tx]
                 [?tx :seon.db/process ?process]
                 [?process :seon.db.process/id :seon.db.process/repl]]}))
            _ (when-not (= 3 (count program-probe))
                (throw
                 (ex-info "The driver cannot see the seeded current program."
                          {:seon.execution-proof/program-probe program-probe
                           :seon.execution-proof/database initial-database})))
            before
            (await (parallel-invoke! initial-database agent-ids current-symbol []))
            publication
            (await
             (invoke! initial-database "agent-a" publish-symbol
                      [initial-database update-data]))
            current-database (get-in publication [::value :db-after])
            agent-b-after
            (await (invoke! current-database "agent-b" current-symbol []))
            agent-a-after
            (await (invoke! current-database "agent-a" current-symbol []))
            evidence
            {:seon.execution-proof/initial-database initial-database
             :seon.execution-proof/current-database current-database
             :seon.execution-proof/before before
             :seon.execution-proof/after [agent-b-after agent-a-after]
             :seon.execution-proof/spawns @spawns}]
        (emit! evidence)
        evidence)
      (finally
        (host/stop!)
        (db/close-session!)))))

(defn -main
  "Run the real two-child proof from Shadow's command-line entrypoint."
  [& [socket-path database-name execution-output execution-digest database-edn]]
  (-> (run-proof! socket-path database-name execution-output execution-digest
                  (reader/read-string database-edn))
      (.then (fn [_] (js/setTimeout #(.exit js/process 0) 1200)))
      (.catch
       (fn [exception]
         (emit! {:seon.execution-proof/failed? true
                 :seon.error/message (or (.-message exception)
                                         (str exception))
                 :seon.error/data (ex-data exception)})
         (js/setTimeout #(.exit js/process 1) 1200)))))
