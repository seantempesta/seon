(ns seon.sci.defs-child
  "Foreign-JVM halves of the W-A defs crash regression."
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as eval])
  (:import [java.nio.file Files Path]))

(def ^:private agent-id "defs-crash-agent")
(def ^:private namespace-name 'my.agents.defs-crash)
(def ^:private run-id "defs-crash-run")

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
   {:seon.cluster.eval/source source
    :seon.cluster.eval/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps (config/result-caps (config/defaults))
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- cluster-handle
  "The declared cluster handle, from the shipped decisions.

  `seon.cluster.run/settlement-projection` takes `:seon.cluster.loop/cluster`
  — the handle an armed agent carries — so this child JVM hands the same
  shape production hands rather than the one entry it happens to read. It
  cannot use `seon.test-support`: that namespace stands up its own database
  base, and this half runs in a foreign JVM against a real file store."
  [connection ctx]
  (let [decisions (config/defaults)]
    {:seon.db/connection connection
     :seon.cluster/name "defs-crash"
     :seon.cluster.run/process "defs-crash-child"
     :seon.sci.eval/ctx ctx
     :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
     :seon.render/context-channel (async/chan (async/sliding-buffer 1))
     :seon.cluster.loop/completion (async/promise-chan)
     :seon.sci.admit/caps (config/result-caps decisions)
     :seon.config.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)
     :seon.config/on-core-error (:seon.config/on-core-error decisions)
     :seon.config.error/recurrence-limit
     (:seon.config.error/recurrence-limit decisions)
     :seon.config.error/max-evidence-bytes
     (:seon.config.error/max-evidence-bytes decisions)
     :seon.config.message/max-chain
     (:seon.config.message/max-chain decisions)}))

(defn- settle!
  [connection ctx ordinal evaluated]
  (let [stored (second
                (run/settlement-projection
                 (cluster-handle connection ctx)
                 evaluated))
        rows (#'loop/def-rows @connection agent-id stored ordinal)]
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

(defn- write-defs!
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
         :seon.ns/source "(ns my.agents.defs-crash)"}}]})
    (db/transact!
     connection
     {:tx-data
      (run/open-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at (java.util.Date.)})})
    (let [ctx (sci/fork (eval/cluster-ctx @connection connection))
          wrapper-calls (atom 0)
          probe-ns (sci/create-ns 'defs.probe)
          _ (sci/add-namespace!
             ctx 'defs.probe
             {'touch! (sci/new-var 'touch!
                                   (fn [] (swap! wrapper-calls inc))
                                   {:ns probe-ns})})
          sources ["(def helper (let [captured (defs.probe/touch!)] (fn [x] (+ captured x))))"
                   "(defn ^{:malli/schema [:=> [:cat :int] :int]} contracted [x] (+ x 2))"
                   "(def data {:answer 42})"
                   "(def scratch (atom 1))"
                   "(swap! scratch + 6)"
                   "(def lost (let [state (atom 1)] (fn [] @state)))"]]
      (doseq [[ordinal source] (map-indexed vector sources)]
        (settle! connection ctx ordinal (evaluation ctx source)))
      (write-result! ready-path {:wrapper-calls @wrapper-calls}))
    @(promise)))

(defn- read-and-clear-defs!
  [configuration result-path]
  (let [connection (d/connect configuration)]
    (try
      (let [eval-form-calls (atom 0)
            original-eval-form sci/eval-form
            {:keys [base restored]}
            (with-redefs [sci/eval-form
                          (fn [& args]
                            (swap! eval-form-calls inc)
                            (apply original-eval-form args))]
              (let [base (eval/cluster-ctx @connection connection)]
                {:base base
                 :restored
                 (eval/fork-for-turn
                  {:seon.sci.eval/ctx base
                   :seon.db/db @connection
                   :seon.db/connection connection
                   :seon.cluster.agent/id agent-id})}))
            ctx (:seon.sci.eval/ctx restored)
            root #(some-> (sci/resolve ctx %) deref)
            before-clear
            {:helper ((root 'my.agents.defs-crash/helper) 4)
             :contracted ((root 'my.agents.defs-crash/contracted) 40)
             :data (root 'my.agents.defs-crash/data)
             :atom @(root 'my.agents.defs-crash/scratch)
             :eval-form-calls @eval-form-calls
             :notices (:seon.sci.eval/defs-notices restored)}]
        (db/transact!
         connection
         {:tx-data
          (run/clear-defs-tx
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
                  :def-count
                  (or
                   (db/q '[:find (count ?definition) .
                           :in $ ?agent-id
                           :where
                           [?agent :seon.cluster.agent/id ?agent-id]
                           [?definition :seon.def/agent ?agent]]
                         @connection agent-id)
                   0)
                  :data-after-clear
                  (some-> (sci/resolve (:seon.sci.eval/ctx cleared)
                                      'my.agents.defs-crash/data)
                          deref)
                  :notices-after-clear
                  (:seon.sci.eval/defs-notices cleared)))))
      (finally
        (d/release connection)))))

(defn -main
  [mode database-path store-id output-path]
  (let [configuration (configuration database-path store-id)]
    (case mode
      "write" (write-defs! configuration output-path)
      "read-clear" (read-and-clear-defs! configuration output-path))))
