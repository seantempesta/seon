(ns seon.operator
  "In-JVM operator verbs for a terminal or editor REPL.

  Attach to any advertised cluster io-prepl with
  `rlwrap nc <advertised-host> <advertised-prepl-port>`, require this
  namespace, and call these ordinary functions. The attached prepl is the
  complete terminal/editor control story for this slice; no nREPL server or
  namespace-refresh mechanism is involved.

  This namespace owns no lifecycle state. Every call delegates to the cluster,
  registry, source-publication, or Flow-observation owner, and every status
  value is derived again for that call. In-JVM `status` describes this JVM's
  current instances; the foreign-process `bin/seon status` additionally
  reconciles process records and possibly stale advertisements."
  (:require [clojure.string :as str]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.operator.runtime :as runtime]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- flat-error
  [error]
  (let [data (ex-data error)]
    {:seon.error/kind (or (:seon.error/kind data) ::failed)
     :seon.error/message (or (ex-message error)
                             "The operator call failed.")
     :seon.error/data (or data {})}))

(defn- attempt
  [f]
  (try
    (f)
    (catch Throwable error
      (flat-error error))))

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn start!
  "Start one named cluster in this JVM."
  {:malli/schema
   [:=> [:cat :seon.boot/start-request]
    [:or :seon.boot/instance :seon.error/value]]}
  [request]
  (attempt #(cluster/start! request)))

(defn stop!
  "Stop one addressed cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :nil :seon.error/value]]}
  [instance]
  (attempt #(cluster/stop! instance)))

(defn restart!
  "Stop and start one addressed cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :seon.boot/instance :seon.error/value]]}
  [instance]
  (let [stopped (stop! instance)]
    (if (error-value? stopped)
      stopped
      (start! (:seon.boot/config instance)))))

(defn status
  "Derived readiness and Flow observations for this JVM's clusters."
  {:malli/schema [:=> [:cat] [:or :seon.operator/status :seon.error/value]]}
  []
  (attempt
   #(let [cluster-names (sort (keys @runtime/running-instances))]
      {:seon.operator/clusters
       (mapv
        (fn [cluster-name]
          (let [observation (cluster/mcp-runtime-observation cluster-name)]
            (cond->
             {:seon.boot/cluster-name cluster-name
              :seon.operator/health (:seon.dev.mcp/health observation)
              :seon.operator/flow (:seon.dev.mcp/flow observation)}
              (:seon.dev.mcp/readiness observation)
              (assoc :seon.operator/readiness
                     (:seon.dev.mcp/readiness observation)))))
        cluster-names)})))

(defn banner
  "Human-readable readiness for this JVM's clusters."
  {:malli/schema [:=> [:cat] [:or :string :seon.error/value]]}
  []
  (let [current (status)]
    (if (error-value? current)
      current
      (attempt
       #(str/join
         "\n\n"
         (keep (fn [{ready :seon.operator/readiness}]
                 (when ready (cluster/banner ready)))
               (:seon.operator/clusters current)))))))

(defn clusters
  "The held branch roster and live advertisements for this JVM."
  {:malli/schema [:=> [:cat] [:or :seon.operator/census :seon.error/value]]}
  []
  (attempt
   #(let [instances (vals @runtime/running-instances)
          stores (keep :seon.store/store
                       (vals @runtime/root-store-holder))]
      {:seon.operator/advertisements
       (->> instances
            (filter map?)
            (keep :seon.boot/advertisement)
            (sort-by :seon.boot/cluster-name)
            vec)
       :seon.operator/branches
       (into #{} (mapcat registry/roster) stores)})))

(defn publish!
  "Publish the current source tree onto `current-src`."
  {:malli/schema
   [:=> [:cat :seon.operator/publish-request]
    [:or :seon.source/published :seon.error/value]]}
  [{root :seon.boot/root changed-paths :seon.operator/changed-paths}]
  (attempt
   #(if changed-paths
      (cluster/refresh-source! root changed-paths)
      (cluster/refresh-source! root))))

(defn refork!
  "Destroy and refork one addressed cluster branch."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :seon.cluster.registry/branch-result :seon.error/value]]}
  [instance]
  (attempt #(cluster/refork! instance)))
