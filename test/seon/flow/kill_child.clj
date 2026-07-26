(ns seon.flow.kill-child
  "Child JVM used only by the Flow process-death standing proof."
  (:require [datahike.api :as d])
  (:import [java.nio.file Files Path StandardOpenOption]))

(def ^:private durable-schema
  [{:db/ident :seon.flow.kill/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.flow.kill/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn -main
  "Commit an admitted fact, publish readiness, and wait to be killed."
  {:malli/schema
   [:=> [:cat :string :string :string] :nil]}
  [database-path store-id ready-path]
  (let [configuration
        {:store {:backend :file
                 :path database-path
                 :id (parse-uuid store-id)}
         :schema-flexibility :write
         :keep-history? true}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (d/transact connection durable-schema)
    ;; This is the admitted, committed half of a step. The parent kills the
    ;; process after this report and before any terminal transaction.
    (d/transact
     connection
     [{:seon.flow.kill/id "durable-step"
       :seon.flow.kill/count 1}])
    (Files/writeString
     (Path/of ready-path (make-array String 0))
     "committed"
     (into-array StandardOpenOption
                 [StandardOpenOption/CREATE
                  StandardOpenOption/TRUNCATE_EXISTING
                  StandardOpenOption/WRITE]))
    ;; An actual SIGKILL from the parent ends this process mid-step.
    (Thread/sleep Long/MAX_VALUE)))
