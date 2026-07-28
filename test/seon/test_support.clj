(ns seon.test-support
  "Shared test constructions which invoke production owners."
  (:require [datahike.api :as d]
            [seon.cluster :as cluster]))

(set! *warn-on-reflection* true)

(defn with-database
  "Run `body` with a fresh canonical in-memory database.

   The production ancestor population owns schema installation. Optional
   `:seon.test-support/extra-schema` rows are synthetic declarations whose
   installation is itself part of a test."
  ([body]
   (with-database {} body))
  ([{:seon.test-support/keys [database-id extra-schema]} body]
   (let [configuration
         {:store {:backend :memory :id (or database-id (random-uuid))}
          :schema-flexibility :write}
         _ (d/create-database configuration)
         connection (d/connect configuration)]
     (try
       (cluster/populate-ancestor!
        {:seon.store/branch-connection connection})
       (when (seq extra-schema)
         (d/transact connection {:tx-data extra-schema}))
       (body connection)
       (finally
         (d/release connection)
         (d/delete-database configuration))))))
