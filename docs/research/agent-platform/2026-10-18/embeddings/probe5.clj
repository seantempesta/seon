;; Baseline: the same commits on a store with no Proximum index.
(require '[datahike.api :as d])
(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(def root (str (System/getProperty "user.dir") "/tmp/embeddings/store5-" (System/currentTimeMillis)))
(def cfg {:store {:backend :file :path (str root "/dh") :id (random-uuid)} :schema-flexibility :write :keep-history? true})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :decl/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
(doseq [part (partition-all 1000 (range 7500))] (d/transact conn (mapv (fn [i] {:decl/name (str "n" i)}) part)))
(let [ts (vec (for [i (range 20)] (first (ms (d/transact conn [{:decl/name (str "plain" i)}])))))]
  (println "20 plain commits, no index, ms" (mapv #(Math/round %) ts)))
(shutdown-agents) (System/exit 0)
