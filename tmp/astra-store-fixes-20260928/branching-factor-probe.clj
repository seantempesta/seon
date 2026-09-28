(do
 (require '[datahike.api :as d] '[datahike.index.interface :as idx]
          '[seon.db :as db] '[seon.cluster.boot :as boot]
          '[seon.cluster.store :as store] '[seon.id :as id]
          '[clojure.java.io :as io] '[konserve.impl.defaults :as defaults])
 (def factor-source
   (let [database (db/db (boot/connection "default"))]
     (->> (d/datoms database :eavt) (take 208000)
          (mapv (comp vec seq)) (sort-by #(nth % 3)) vec)))
 {:sample-datoms (count factor-source)})

(defn measure-factor [factor]
 (let [path (str "/Users/sean/src/seon/tmp/astra-store-fixes-20260928/factor-" factor "-" (id/id))
       config (assoc-in (store/datahike-configuration path) [:index-config :branching-factor] factor)
       _ (d/create-database config)
       connection (d/connect config)]
   (try
     (let [started (System/nanoTime)
           imported @(d/load-entities connection factor-source)
           import-ms (/ (- (System/nanoTime) started) 1e6)
           _ (assert (:db-after imported))
           subjects (vec (take 100 (sort-by second (d/q '[:find ?e ?sym ?doc
                                                        :where [?e :seon.fn/sym ?sym]
                                                               [?e :seon.fn/doc ?doc]] @connection))))
           _ (assert (= 100 (count subjects)))
           mark (fn [database seen]
                  (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                      [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                        files (filter #(.isFile %) (map #(io/file path (defaults/key->store-key %)) nodes))]
                    {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
           files (fn [] (into {} (for [f (file-seq (io/file path)) :when (.isFile f)]
                                  [(.getPath f) [(.length f) (.lastModified f)]])))
           baseline (assoc (mark @connection (atom #{})) :all-file-bytes (reduce + (map (comp first val) (files))))
           measurements
           (mapv
            (fn [n]
              (let [selected (subvec subjects 0 n)
                    seen (atom #{}) _ (mark @connection seen) before-files (files)
                    tx (mapv (fn [[entity _ doc]] [:db/add entity :seon.fn/doc (str doc "\nbranching factor probe " n)]) selected)
                    start (System/nanoTime)
                    report (d/transact connection tx)
                    ms (/ (- (System/nanoTime) start) 1e6)
                    query-start (System/nanoTime)
                    answer (set (d/q '[:find ?sym ?doc :in $ [?sym ...]
                                       :where [?e :seon.fn/sym ?sym] [?e :seon.fn/doc ?doc]]
                                     @connection (mapv second selected)))
                    query-ms (/ (- (System/nanoTime) query-start) 1e6)
                    expected (set (map (fn [[_ sym doc]] [sym (str doc "\nbranching factor probe " n)]) selected))]
                (assert (= expected answer))
                {:n n :commits 1 :datoms (count (:tx-data report)) :ms ms :query-ms query-ms
                 :answer-digest (id/digest 64 (sort-by first answer)) :storage (mark @connection seen)
                 :written-files (count (remove (fn [[path state]] (= state (get before-files path))) (files)))
                 :written-bytes (reduce + (for [[path state] (files) :when (not= state (get before-files path))] (first state)))}))
            [1 10 100])]
       {:factor factor :sample-datoms (count factor-source) :import-ms import-ms
        :baseline baseline :measurements measurements})
     (finally (d/release connection) (d/delete-database config)))))

;; Run each call separately through MCP; keep the result envelopes.
;; (measure-factor 4096)
;; (measure-factor 512)
;; (measure-factor 128)
