;; Raw datahike.api/q cost of or / or-join followed by a join, per Datahike revision.
;; git -C reference-code/datahike archive REV | tar -x -C DIR; (cd DIR && clojure -T:build compile-java)
;; REV=REV clojure -Sdeps "{:deps {org.replikativ/datahike {:local/root \"DIR\"}}}" -M:dev raw-orjoin-bench.clj
(require '[datahike.api :as d])
(def cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :seon.fn/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :seon.test/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :seon.program/definition-digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn meas [f]
  (let [tid (.getId (Thread/currentThread)) b0 (.getThreadAllocatedBytes mx tid) t0 (System/nanoTime) r (f)]
    {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0) :mb (/ (Math/round (/ (- (.getThreadAllocatedBytes mx tid) b0) 1e4)) 100.0) :rows (count r)}))
(def or-join '[:find ?sym ?digest :in $ [?sym ...] :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym]) [?e :seon.program/definition-digest ?digest]])
(def plain '[:find ?sym ?digest :in $ [?sym ...] :where [?e :seon.fn/sym ?sym] [?e :seon.program/definition-digest ?digest]])
(doseq [n-entities [60000]]
  (d/transact conn (vec (for [i (range n-entities)]
                          (if (even? i)
                            {:seon.fn/sym (symbol (str "p" n-entities ".fn") (str "f" i)) :seon.program/definition-digest (str "d" i)}
                            {:seon.test/sym (symbol (str "p" n-entities ".test") (str "t" i)) :seon.program/definition-digest (str "d" i)}))))
  (let [db @conn
        syms (vec (map first (sort (d/q '[:find ?s :where [?e :seon.fn/sym ?s]] db))))]
    (dotimes [w 3] (d/q or-join db [(syms (+ 5 w))]))
    (doseq [n [1 10 142]]
      (println (System/getenv "REV") :digests (count (d/datoms db :aevt :seon.program/definition-digest)) n
               :or (meas #(d/q or-join db (subvec syms (* 11 n) (+ (* 11 n) n))))
               :or-alone (meas #(d/q '[:find ?sym ?e :in $ [?sym ...] :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])] db (subvec syms (+ 2000 (* 9 n)) (+ 2000 (* 10 n)))))
               :or-join-e (meas #(d/q '[:find ?sym ?digest :in $ [?sym ...] :where (or-join [?e ?sym] [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym]) [?e :seon.program/definition-digest ?digest]] db (subvec syms (+ 4000 (* 9 n)) (+ 4000 (* 10 n)))))
               :digest-first (meas #(d/q '[:find ?sym ?digest :in $ [?sym ...] :where [?e :seon.program/definition-digest ?digest] (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])] db (subvec syms (+ 6000 (* 9 n)) (+ 6000 (* 10 n)))))))))
(shutdown-agents)
(System/exit 0)
