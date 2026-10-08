;; Throwaway: do index roots keep precomputed subtree counts at 128/0 vs 128/32?
;; Run: clojure -M tmp/store-writes/subtree_counts.clj
(require '[datahike.api :as d] '[clojure.java.io :as io]
         '[org.replikativ.persistent-sorted-set :as psset])
(def root "/Users/sean/src/seon/tmp/store-writes")
(defn cfg [dir dbs]
  (let [path (.getCanonicalPath (io/file dir))]
    {:store {:backend :file :path path :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}
     :writer {:backend :self} :keep-history? true :fuse-index-roots? true
     :index-config {:branching-factor 128 :diff-buf-size dbs} :schema-flexibility :write}))
(def ks [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
(defn counts [db] (into {} (for [k ks :let [i (get db k)] :when i] [k (psset/has-subtree-counts? i)])))
(defn run [dbs]
  (let [dir (str root "/probe-counts-" dbs) c0 (cfg dir dbs)
        _ (d/create-database c0)
        conn (d/connect c0)
        _ (d/transact conn [{:db/ident :x/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                            {:db/ident :x/v :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true}])
        _ (doseq [ch (partition-all 1000 (range 20000))] (d/transact conn (mapv (fn [i] {:x/id i :x/v i}) ch)))
        fresh (counts @conn)
        _ (d/release conn)
        conn (d/connect (dissoc c0 :index-config :keep-history? :fuse-index-roots?))
        reopened (counts @conn)
        _ (doseq [i (range 50)] (d/transact conn [{:x/id (* 397 i) :x/v (- i)}]))
        after-small (counts @conn)]
    (d/release conn)
    (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f))
    {:diff-buf dbs :fresh fresh :reopened reopened :after-50-small-commits after-small}))
(prn (run 0)) (prn (run 32))
(shutdown-agents) (System/exit 0)
