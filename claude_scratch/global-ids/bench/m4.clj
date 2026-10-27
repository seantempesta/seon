;; a planned d/q over (d/history db) against the same query over db, warm, N = 115k current / 169k history
(require '[datahike.api :as d] '[seon.db :as pdb] '[datahike.query :as dq])
(def path "/Users/sean/src/seon-jolt/next-global-ids/target/m/big")
(def cfg (assoc (pdb/store-cfg :lmdb path) :store-cache-size 200000))
(def conn (d/connect cfg))
(def db (pdb/head conn :hist))
(def hdb (d/history db))
(defn ms [t0] (/ (- (System/nanoTime) t0) 1e6))
(def qs {:point '[:find ?src ?o :in $ ?n :where [?v :var/name ?n] [?v :var/form ?f] [?f :form/src ?src] [?f :form/order ?o]]
         :ns20  '[:find ?f ?o :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?f :form/order ?o]]
         :join  '[:find ?doc ?o :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?v :var/form ?f] [?v :var/doc ?doc] [?f :form/order ?o]]})
(def args {:point 'app.m33/f7 :ns20 'app.m33 :join 'app.m33})
(println "planner-eligible db?" (@#'dq/planner-eligible-db? db) "history?" (@#'dq/planner-eligible-db? hdb))
(doseq [[k q] qs, [label x] [["db     " db] ["history" hdb]]]
  (dotimes [_ 20] (d/q q x (args k)))
  (let [t0 (System/nanoTime) r (d/q q x (args k))] 
    (dotimes [_ 199] (d/q q x (args k)))
    (println k label "rows" (count r) (format "us/query %.0f" (/ (* 1000 (ms t0)) 200)))))
(def es (vec (take 1000 (shuffle (map :e (d/datoms db :aevt :form/id))))))
(doseq [[label x] [["db     " db] ["history" hdb]]]
  (doseq [e es] (count (d/datoms x :eavt e)))
  (let [t0 (System/nanoTime) n (reduce + (map #(count (d/datoms x :eavt %)) es))]
    (println "eavt seek" label "datoms/seek" (/ n 1000.0) (format "us/seek %.1f" (/ (* 1000 (ms t0)) 1000))))
  (let [t0 (System/nanoTime) n (reduce + (map #(count (d/datoms x :avet :form/id (str "app.m" (mod % 400) "/f" (mod % 20)))) es))]
    (println "avet seek" label "hits" n (format "us/seek %.1f" (/ (* 1000 (ms t0)) 1000)))))
(doseq [[label x] [["db     " db] ["history" hdb]]]
  (let [q '[:find ?src :where [?v :var/name app.m33/f7] [?v :var/form ?f] [?f :form/src ?src]]]
    (dotimes [_ 20] (d/q q x))
    (let [t0 (System/nanoTime)] (dotimes [_ 200] (d/q q x)) (println "literal point" label (format "us/query %.0f" (/ (* 1000 (ms t0)) 200))))))
