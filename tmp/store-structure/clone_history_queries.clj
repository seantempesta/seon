;; store-structure-opus: warm ms and answer hashes of history-query shapes over the
;; APFS clone's :cluster-default head, under whichever Datahike this JVM loads.
;; Run: clojure -M tmp/store-structure/clone_history_queries.clj <label>
(require '[datahike.api :as d]
         '[seon.cluster.store :as store])

(def queries
  {:entity-group-history
   '[:find ?e ?s ?p ?tx ?added :where [?e :seon.test.member/symbol ?s] [?e :seon.test.member/pass-count ?p ?tx ?added]]
   :card-one-scan-history
   '[:find ?e ?v ?tx ?added :where [?e :seon.fn/form-span ?v ?tx ?added]]
   :join-history
   '[:find ?sym ?digest :where [?e :seon.fn/sym ?sym] [?e :seon.program/definition-digest ?digest]]
   :lint-rows-history
   '[:find ?e ?row ?tx :where [?e :seon.lint/row ?row ?tx false]]})

(let [label (first *command-line-args*)
      cfg (-> (store/datahike-configuration "/Users/sean/src/seon/tmp/store-structure/store-clone")
              (assoc :branch :cluster-default)
              (assoc-in [:store :id] #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7")
              (dissoc :keep-history? :fuse-index-roots? :index-config))
      conn (d/connect cfg)
      db @conn
      h (d/history db)
      since (d/since h (- (:max-tx db) 300))
      run (fn [q src]
            (dotimes [_ 5] (d/q q src))
            (let [runs (vec (repeatedly 15 #(let [t0 (System/nanoTime) r (d/q q src)]
                                              [(/ (- (System/nanoTime) t0) 1e6) r])))
                  r (second (peek runs))]
              {:n (count r) :hash (hash (set r))
               :median-ms (/ (Math/round (* 10 (nth (sort (map first runs)) 7))) 10.0)}))]
  (prn {:label label :commit (d/commit-id db)
        :history (into {} (for [[k q] queries] [k (run q h)]))
        :since (into {} (for [[k q] queries] [k (run q since)]))})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
