(ns seon.sci.desk-child
  "Foreign-JVM halves of the W-A desk crash regression."
  (:require [datahike.api :as d]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as eval])
  (:import [java.nio.file Files Path]))

(def ^:private agent-id "desk-crash-agent")
(def ^:private namespace-name 'my.agents.desk-crash)
(def ^:private run-id "desk-crash-run")

(defn- configuration
  [path store-id]
  {:store {:backend :file :path path :id (parse-uuid store-id)}
   :schema-flexibility :write
   :keep-history? true})

(defn- write-result!
  [path value]
  (Files/writeString (Path/of path (make-array String 0))
                     (pr-str value)
                     (make-array java.nio.file.OpenOption 0)))

(defn- evaluation
  [ctx source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps (config/result-caps (config/defaults))
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- settle!
  [connection ordinal evaluated]
  (let [stored (second
                (run/settlement-projection
                 {:seon.db/connection connection}
                 evaluated))
        rows (#'loop/desk-rows @connection agent-id stored ordinal)]
    (db/transact!
     connection
     {:tx-data
      (run/receipt-start-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.eval/ordinal ordinal
        :seon.cluster.eval/at (java.util.Date.)})})
    (db/transact!
     connection
     {:tx-data
      (run/receipt-settle-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.eval/ordinal ordinal
        :seon.cluster.eval/result-edn
        (:seon.cluster.eval/result-edn evaluated)
        :seon.def/rows rows})})))

(defn- write-desk!
  [configuration ready-path]
  (d/create-database configuration)
  (let [connection (d/connect configuration)]
    (cluster/populate-source! {:seon.db/connection connection})
    (db/transact!
     connection
     {:tx-data
      [{:seon.config.eval.result/blob-threshold 32768}
       {:seon.cluster.agent/id agent-id
        :seon.cluster.agent/namespace
        {:seon.ns/name namespace-name
         :seon.ns/source "(ns my.agents.desk-crash)"}}]})
    (db/transact!
     connection
     {:tx-data
      (run/open-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at (java.util.Date.)})})
    (let [ctx (sci/fork (eval/cluster-ctx @connection connection))
          wrapper-calls (atom 0)
          probe-ns (sci/create-ns 'desk.probe)
          _ (sci/add-namespace!
             ctx 'desk.probe
             {'touch! (sci/new-var 'touch!
                                   (fn [] (swap! wrapper-calls inc))
                                   {:ns probe-ns})})
          sources ["(def helper (let [captured (desk.probe/touch!)] (fn [x] (+ captured x))))"
                   "(def data {:answer 42})"
                   "(def scratch (atom 1))"
                   "(swap! scratch + 6)"
                   "(def lost (let [state (atom 1)] (fn [] @state)))"]]
      (doseq [[ordinal source] (map-indexed vector sources)]
        (settle! connection ordinal (evaluation ctx source)))
      (write-result! ready-path {:wrapper-calls @wrapper-calls}))
    @(promise)))

(defn- read-and-clear-desk!
  [configuration result-path]
  (let [connection (d/connect configuration)]
    (try
      (let [base (eval/cluster-ctx @connection connection)
            eval-form-calls (atom 0)
            original-eval-form sci/eval-form
            restored
            (with-redefs [sci/eval-form
                          (fn [& args]
                            (swap! eval-form-calls inc)
                            (apply original-eval-form args))]
              (eval/fork-for-turn
               {:seon.sci.eval/ctx base
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.cluster.agent/id agent-id}))
            ctx (:seon.sci.eval/ctx restored)
            root #(some-> (sci/resolve ctx %) deref)
            before-clear
            {:helper ((root 'my.agents.desk-crash/helper) 4)
             :data (root 'my.agents.desk-crash/data)
             :atom @(root 'my.agents.desk-crash/scratch)
             :eval-form-calls @eval-form-calls
             :notices (:seon.sci.eval/desk-notices restored)}]
        (db/transact!
         connection
         {:tx-data
          (run/clear-desk-tx
           {:seon.def/agent [:seon.cluster.agent/id agent-id]})})
        (let [cleared
              (eval/fork-for-turn
               {:seon.sci.eval/ctx base
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.cluster.agent/id agent-id})]
          (write-result!
           result-path
           (assoc before-clear
                  :desk-count
                  (or
                   (db/q '[:find (count ?desk) .
                           :in $ ?agent-id
                           :where
                           [?agent :seon.cluster.agent/id ?agent-id]
                           [?desk :seon.def/agent ?agent]]
                         @connection agent-id)
                   0)
                  :data-after-clear
                  (some-> (sci/resolve (:seon.sci.eval/ctx cleared)
                                      'my.agents.desk-crash/data)
                          deref)
                  :notices-after-clear
                  (:seon.sci.eval/desk-notices cleared)))))
      (finally
        (d/release connection)))))

(defn -main
  [mode database-path store-id output-path]
  (let [configuration (configuration database-path store-id)]
    (case mode
      "write" (write-desk! configuration output-path)
      "read-clear" (read-and-clear-desk! configuration output-path))))
