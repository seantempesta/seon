(ns seon.web.value
  "Acquire value-render inputs from one explicit immutable database value."
  (:require
   [seon.config :as config]
   [seon.db :as db]
   [seon.schema :as schema]))

(def ^:private schema-query
  '[:find ?key ?form (pull ?tx ?provenance-pattern)
    :in $ ?provenance-pattern
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form ?tx]])

(def ^:private function-contract-query
  '[:find ?sym ?form (pull ?tx ?provenance-pattern)
    :in $ ?provenance-pattern
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form ?tx]])

(defn- db-error? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn ^:async policy!
  "Acquire decoded value-render policy at one immutable database value."
  {:malli/schema
   [:=> [:catn [:seon.db/database :seon.db/db]] :seon.config/singleton]}
  [database]
  (let [stored
        (await
         (db/entity database [:seon.config/id config/cluster-config-id]))]
    (when (or (db-error? stored) (nil? stored))
      (throw (js/Error. "configuration unavailable")))
    (db/decode-edn-values stored)))

(defn ^:async program-projection!
  "Acquire the complete value-render program at one immutable database value."
  {:malli/schema
   [:=> [:catn [:seon.db/database :seon.db/db]] :seon.schema/projection]}
  [database]
  (let [results
        (await
         (js/Promise.all
          #js [(db/query {::db/db database
                          ::db/query schema-query
                          ::db/args
                          [schema/asserting-transaction-provenance-pattern]})
               (db/query {::db/db database
                          ::db/query function-contract-query
                          ::db/args
                          [schema/asserting-transaction-provenance-pattern]})]))
        [schema-rows function-contract-rows] (array-seq results)]
    (when (some db-error? [schema-rows function-contract-rows])
      (throw (js/Error. "program projection unavailable")))
    (schema/projection-from-rows
     {:seon.schema/schema-rows schema-rows
      :seon.schema/function-contract-rows function-contract-rows})))
