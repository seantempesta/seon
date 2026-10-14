;; Run from a datahike checkout: clojure -M:test -i <this file>. Lane datahike-asof, 2026-10-14.
(require '[datahike.api :as d] '[datahike.db :as db])
(def cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? true
          :initial-tx [{:db/ident :fn/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/index true}
                       {:db/ident :zz/tail :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true}
                       {:db/ident :aa/head :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true}]})
(d/create-database cfg)
(def conn (d/connect cfg))
(doseq [batch (partition-all 2000 (range 7000))]
  (d/transact conn {:tx-data (mapv (fn [n] {:fn/sym (symbol "seon.x" (str "f" n)) :aa/head n}) batch)}))
(doseq [batch (partition-all 10000 (range 300000))]
  (d/transact conn {:tx-data (mapv (fn [n] {:zz/tail n}) batch)}))
(def current @conn)
(def view (d/as-of current (:max-tx current)))
(defn page [v cursor] (d/index-page v (cond-> {:index :avet :components [:fn/sym] :direction :forward :limit 200} cursor (assoc :cursor cursor))))
(defn realized [f] (let [n (atom 0) o db/post-process-datoms]
  (with-redefs [db/post-process-datoms (fn [ds db c] (let [v (vec ds)] (swap! n + (count v)) (o v db c)))] (f)) @n))
(defn ms [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
(defn median [xs] (nth (sort xs) (quot (count xs) 2)))
(let [p1 (page view nil) mid (loop [c nil i 0] (let [p (page view c)] (if (= i 17) (:datahike.index-page/cursor p) (recur (:datahike.index-page/cursor p) (inc i)))))]
  (dotimes [_ 3] (page view nil) (page view mid))
  (println :index-datoms (count (d/datoms current {:index :avet})))
  (println :current-first-page-ms (median (repeatedly 7 #(ms (fn [] (page current nil))))))
  (println :as-of-first-page-ms (median (repeatedly 7 #(ms (fn [] (page view nil))))))
  (println :as-of-mid-page-ms (median (repeatedly 7 #(ms (fn [] (page view mid))))))
  (println :as-of-mid-page-realized (realized #(page view mid)))
  (println :as-of-whole-range-pages-ms (ms #(loop [c nil n 0] (let [p (page view c)] (if (:datahike.index-page/complete? p) (println :pages (inc n)) (recur (:datahike.index-page/cursor p) (inc n)))))))
  (println :as-of-d-datoms-ms (median (repeatedly 7 #(ms (fn [] (count (d/datoms view {:index :avet :components [:fn/sym]}))))))))
(shutdown-agents) (System/exit 0)
