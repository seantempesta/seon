;; Archive checker, not a test request or a boot. Both writers receive the same
;; real published entities. Every memory store is released in finally.
(require '[clojure.edn :as edn]
         '[datahike.api :as d]
         '[seon.cluster :as cluster]
         '[seon.db :as db]
         '[seon.fn :as index]
         '[seon.id :as id]
         '[seon.program :as program]
         '[seon.schema :as schema]
         '[seon.sci.eval :as sci.eval])

(def input (edn/read-string (slurp "../live-population.edn")))
(def projection (schema/declaration-projection (:forms input)))

(defn population [reconcile?]
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write :keep-history? true}
        _ (d/create-database config)
        connection (d/connect config)]
    (try
      (db/carry-connection-projection-state!
       connection (sci.eval/projection-state @connection projection "astra-one-writer"))
      (schema/call-with-forms
       (:forms input)
       #(schema/call-with-projection
         projection
         (fn []
           (db/call-with-custody
            {:seon.db/connection connection}
            (fn []
              (cluster/accrete-schema-population! connection nil false)
              (assert (empty? (db/q '[:find ?k :where [_ :seon.schema/key ?k]] (db/db connection))))
              (let [started (System/nanoTime)
                    report
                    (db/transact!
                     connection
                     {:tx-data
                      (if reconcile?
                        [(#'index/reconcile-call projection (program/shapes-in projection) (:rows input) [])]
                        (let [{entities :seon.fn/index-entities
                               identities :seon.fn/index-identity-operations
                               keywords :seon.fn/index-keyword-operations}
                              (#'index/compile-index-transaction projection (:rows input)
                               (db/identity-attributes (db/db connection)))]
                          (into (into identities entities) keywords)))})
                    elapsed (/ (- (System/nanoTime) started) 1e6)]
                (when-not (:db-after report)
                  (throw (ex-info "Population refused" report)))
                (let [database (db/db connection)
                      rows (index/published-index-entities database)]
                  (assert (vector? rows))
                  {:writer (if reconcile? :reconcile :fresh)
                   :ms elapsed :datoms (count (:tx-data report))
                   :program (into {} (map (juxt program/row-identity identity)) rows)})))))))
      (finally (d/release connection) (d/delete-database config)))))

(try
  (let [before (population false)
        after (population true)
        equal? (= (:program before) (:program after))
        result {:same-program? equal?
                :rows (count (:program before))
                :before (dissoc before :program) :after (dissoc after :program)
                :different-identities (into [] (keep (fn [[k v]] (when (not= v (get (:program after) k)) k))) (:program before))}]
    (spit "../population-result.edn" (id/printed result))
    (prn (dissoc result :different-identities))
    (shutdown-agents)
    (System/exit (if equal? 0 1)))
  (catch Throwable failure
    (spit "../population-failure.edn" (id/printed (Throwable->map failure)))
    (prn {:failed (ex-message failure)})
    (shutdown-agents)
    (System/exit 1)))
