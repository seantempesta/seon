(require '[datahike.api :as d] '[seon.cluster.store :as store])
(def conn user/file-conn)
(def root-eid user/root-eid)
(defn batch [i n] (into [] (mapcat (fn [j] (user/message-row root-eid (str "b" i "-" j)))) (range n)))
(doseq [size [1 10 100 1000]]
  (let [q (user/quantiles (user/timed 3 20 (fn [i] (store/transact! conn (batch (str size "-" i) size)))))]
    (println :BATCH size (pr-str (assoc q :rows-per-s (Math/round (* size (double (:sustained-per-s q)))))))))
(def db-value @conn)
(def queries
  {:agent-by-id (fn [db] (d/q '[:find ?e . :in $ ?id :where [?e :seon.cluster.agent/id ?id]] db "root"))
   :messages-to-agent (fn [db] (count (d/q '[:find ?m ?c :in $ ?to :where [?m :seon.cluster.message/to ?to] [?m :seon.cluster.message/content ?c]] db root-eid)))
   :pull-agent (fn [db] (d/pull db '[* {:seon.cluster.agent/namespace [*]}] root-eid))
   :fns-in-namespace (fn [db] (count (d/q '[:find ?f ?sym :in $ ?nsname :where [?n :seon.ns/name ?nsname] [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?sym]] db 'seon.cluster.store)))
   :corpus-census (fn [db] (count (d/q '[:find ?f :where [?f :seon.fn/sym _]] db)))})
(doseq [[k q] (sort-by key queries)]
  (println :READ-CACHED k (pr-str (user/quantiles (user/timed 20 200 (fn [_] (q db-value)))))))
(doseq [[k q] (sort-by key queries)]
  (println :READ-UNCACHED k
    (pr-str (user/quantiles
      (mapv (fn [i] (store/transact! conn (user/message-row root-eid (str "u" (name k) i)))
                    (let [db @conn s (System/nanoTime)] (q db) (- (System/nanoTime) s)))
            (range 25))))))
(println :DATOMS (count (d/datoms @conn :eavt)))
(println :DB4-DONE)
