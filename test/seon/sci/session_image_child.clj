(ns seon.sci.session-image-child
  "Foreign-JVM half of the session-image restart regression."
  (:require [datahike.api :as d]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as loop]
            [seon.config :as config]
            [seon.sci.eval :as eval])
  (:import [java.nio.file Files Path]))

(defn- configuration [path store-id]
  {:store {:backend :file :path path :id (parse-uuid store-id)}
   :schema-flexibility :write
   :keep-history? true})

(defn- evaluation [ctx source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name 'my.agents.fresh-jvm]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps (config/result-caps (config/defaults))
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- write-image! [configuration]
  (d/create-database configuration)
  (let [connection (d/connect configuration)]
    (try
      (cluster/populate-source! {:seon.store/branch-connection connection})
      (d/transact connection
                  {:tx-data
                   [{:seon.source/digest (apply str (repeat 64 "0"))}
                    {:seon.config.eval.result/blob-threshold 32768}
                    {:seon.ns/name 'my.agents.fresh-jvm
                     :seon.ns/source "(ns my.agents.fresh-jvm)"}]})
      (let [ctx (eval/cluster-ctx @connection connection)
            sources ["(def big (vec (range 200000)))"
                     "(def names [\"Ada\" \"Grace\"] )"
                     "(def limit 10)"
                     "(def scale (fn [v] (* v limit)))"]]
        (doseq [[ordinal source] (map-indexed vector sources)]
          (let [evaluated (evaluation ctx source)
                stored (#'loop/store-session-values! connection evaluated)]
            (d/transact
             connection
             {:tx-data (#'loop/session-image-tx
                        @connection stored ordinal)}))))
      (finally (d/release connection)))))

(defn- read-image [configuration]
  (let [connection (d/connect configuration)]
    (try
      (let [ctx (eval/cluster-ctx @connection connection)
            resolve-value #(some-> (sci/resolve ctx %) deref)]
        {:count (count (resolve-value 'my.agents.fresh-jvm/big))
         :scaled ((resolve-value 'my.agents.fresh-jvm/scale) 4)
         :names (resolve-value 'my.agents.fresh-jvm/names)})
      (finally (d/release connection)))))

(defn -main [mode path store-id output-path]
  (let [configuration (configuration path store-id)
        result (case mode
                 "write" (do (write-image! configuration) :written)
                 "read" (read-image configuration))]
    (Files/writeString (Path/of output-path (make-array String 0))
                       (pr-str result)
                       (make-array java.nio.file.OpenOption 0))))
