(require '[datahike.api :as d]
         '[org.replikativ.persistent-sorted-set :as psset])
(def dir (str "tmp/diff-probes/store-" (rand-int 100000)))
(def cfg {:store {:backend :file :path dir :id (java.util.UUID/nameUUIDFromBytes (.getBytes dir "UTF-8"))}
          :writer {:backend :self}
          :keep-history? true :fuse-index-roots? true
          :index-config {:diff-buf-size 256}
          :schema-flexibility :write})
(d/delete-database cfg) (d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :m/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :m/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                  {:db/ident :m/read? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])
(d/transact conn (vec (for [i (range 20000)] {:m/id (str "m" i) :m/text (str "t" i) :m/read? false})))
(def db0 @conn)
(defn addrs [pss] (let [a (atom #{})] (psset/walk-addresses pss #(swap! a conj %)) @a))
(def a0 (addrs (:eavt db0)))
(d/transact conn [{:m/id "m5" :m/read? true}])
(def db1 @conn)
(def a1 (addrs (:eavt db1)))
(println :addr-count-db0 (count a0) :db1 (count a1))
(println :shared-addresses (count (clojure.set/intersection a0 a1)))
(println :only-in-db1 (count (clojure.set/difference a1 a0)))
(println :sample-address (first a1))
;; branch fork sharing
(require '[datahike.experimental.versioning :as v] :reload)
(println :branches (try (v/branch-history conn) (catch Throwable e (.getMessage e))))
