(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[clojure.edn :as edn])
(defn answer [query database args]
  (into {} (for [disabled? [false true]]
             [(if disabled? :base :planned)
              (try (binding [q/*disable-planner* disabled? q/*query-result-cache?* false]
                     (apply d/q query database args))
                   (catch Throwable e {:error (.getMessage e)}))])))
(let [database (d/db-with (db/empty-db {:node/next {:db/valueType :db.type/ref}})
                         [[:db/add 1 :node/next 2] [:db/add 2 :node/next 3]
                          [:db/add 3 :node/next 4] [:db/add 4 :node/next 1]])]
  (doseq [comparison ['(< ?lo ?z) '(> ?z ?lo)]]
    (prn :bound comparison
         (answer {:find '[?x] :in '[$ [?lo ...]] :where ['[?x :node/next ?z] [comparison] '[(< ?z 4)]]}
                 database [[1]]))))
(let [before (d/db-with (db/empty-db {:item/id {:db/unique :db.unique/identity} :item/value {}} {:keep-history? true})
                       [{:db/id 1 :item/id 1 :item/value 10} {:db/id 2 :item/id 2 :item/value 20} {:db/id 3 :item/id 3 :item/value 30}])
      after (d/db-with before [[:db/retract 1 :item/id 1] [:db/add 4 :item/id 1] [:db/add 4 :item/value 40]])]
  (prn :history-order (answer '[:find ?e ?value :in $ [?id ...] :where [?e :item/id ?id] [?e :item/value ?value]] (d/history after) [[1 2 3]])))
(let [database (d/db-with (db/empty-db {:item/a {:db/cardinality :db.cardinality/many} :item/b {:db/cardinality :db.cardinality/many}} {:keep-history? true})
                         [{:db/id 1 :item/a [1 2] :item/b [1 4 5]}])]
  (prn :history-correlation (answer '[:find ?v :where [?e :item/a ?v] [?e :item/b ?v]] (d/history database) [])))
(when-let [cfg-path (first *command-line-args*)]
  (let [conn (d/connect (edn/read-string (slurp cfg-path)))]
    (try (prn :identity-attached-predicate
              (answer '[:find ?x ?o :in $ ?id :where [?run :turn/id ?id]
                        [?x :eval/run ?run] [?x :eval/ordinal ?o] [(> ?o 0)]]
                      @conn ["run-13"]))
         (prn :identity-attached-predicate-collection
              (answer '[:find ?x ?o :in $ [?id ...] :where [?run :turn/id ?id]
                        [?x :eval/run ?run] [?x :eval/ordinal ?o] [?x :eval/source ?source] [(> ?o 0)]]
                      @conn [["run-13"]]))
         (prn :identity-attached-predicate-wide-group
              (answer '[:find ?x ?o :in $ ?id :where [?run :turn/id ?id]
                        [?x :eval/run ?run] [?x :eval/ordinal ?o] [?x :eval/source ?source]
                        [?x :eval/shown ?shown] [(> ?o 0)]] @conn ["run-12"]))
         (finally (d/release conn)))))
(shutdown-agents)
