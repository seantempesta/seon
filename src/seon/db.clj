(ns seon.db
  "The database side: schema of the example program, the seed, the store, and the merge
  delta (a branch's net change since its merge base, as tx-data that replays onto main).
  From proto/flow f2-final proto.db; the store's node codec is X1's jfasl
  (konserve.jolt.fasl-store, FaslSerializer), and the JVM-written-store reader stays behind
  (BAGGAGE.md)."
  (:require [datahike.api :as d] [konserve.jolt.store] [konserve.jolt.lmdb :as klmdb]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi] [konserve.jolt.posix :as posix]))

(def schema
  [{:db/ident :ns/name :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :ns/doc :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :var/name :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :var/ns :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :var/doc :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :var/src :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :var/arity :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :task/name :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :task/plan :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :task/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :task/refs :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :task/agent :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :note/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :note/agent :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :note/about :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}])

(defn seed-tx
  "Transactions for about n datoms: per namespace, one tx of the namespace and its 20
  vars (5 datoms each), then one tx of a task referring to two of its vars."
  [n]
  (let [nns (max 1 (quot n 110))]
    (mapcat (fn [k]
              (let [nsym (symbol (str "app.m" k))]
                [(into [{:db/id "ns" :ns/name nsym :ns/doc (str "Module " k " of the example program.")}]
                       (for [i (range 20)]
                         {:var/name (symbol (str nsym) (str "f" i)) :var/ns "ns" :var/doc (str "f" i " of module " k)
                          :var/src (str "(defn f" i " [x] (+ x " i "))") :var/arity 1}))
                 [{:task/name (keyword (str "app.m" k) "tidy") :task/plan (str "Tidy module " k ": name every value by its schema.")
                   :task/status :held :task/refs [[:var/name (symbol (str nsym) "f0")] [:var/name (symbol (str nsym) "f1")]]}]]))
            (range nns))))

;; :writer :sync (vendor datahike.writer, lane F1): root's Flow :writer proc is the only
;; caller of transact, so it is THE serial writer; Datahike runs no queue, commit loop or
;; go block of its own (memory: commit p50 0.49 -> 0.39 ms, 639 -> 446 KB/tx, 2 threads
;; fewer per connection; src/proto/f1_bench.clj).
(def writer-cfg {:backend :sync})

(defn mem-cfg [] {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false
                  :writer writer-cfg})

;; ---- the durable store ----------------------------------------------------------
;; konserve on Jolt (libs/konserve): LMDB (konserve.jolt.lmdb) or the POSIX filestore,
;; holding E4a's real index nodes, one content-addressed node per key. Branching factor
;; 64: a commit rewrites the nodes it touched, so smaller nodes mean fewer bytes printed
;; and written per commit (dev/dur.clj: 512 -> 64 cut a small commit 27 -> 16 ms with
;; fsync, before the node emitter). LMDB runs MDB_NOSYNC and the writer syncs the env
;; once per commit (sync!), so a commit is one drive flush, not one per node.

(def ^:private BF 64)
(def ^:private MDB_NOSYNC 0x10000)

(defn store-cfg
  "Datahike config for the durable store at path; backend :lmdb (default) or :file."
  [backend path]
  {:store (cond-> {:backend backend :path path :id #uuid "50a0c0de-0000-4000-8000-000000000001"
                   :default-serializer :FaslSerializer}
            (= backend :lmdb) (assoc :lmdb {:flags MDB_NOSYNC :map-size (* 1048576 (Long/parseLong (or (System/getenv "SEON_LMDB_MB") "1024")))})
            (= backend :file) (assoc :config {:defer-immutable-sync? true}))
   :schema-flexibility :write :keep-history? false
   :writer writer-cfg
   :index-config {:branching-factor BF}})

(defn sync!
  "Make every commit so far durable: one env sync for LMDB (MDB_NOSYNC); the filestore
  syncs per write itself. nil."
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    (lmdb-ffi/sync! (klmdb/open-env! (get-in cfg [:store :path]) nil)))
  nil)

(defn- seed! [conn n]
  (d/transact conn {:tx-data schema})
  (doseq [tx (seed-tx n)] (d/transact conn {:tx-data tx})))

(defn create!
  "A fresh in-memory database seeded with about n datoms. Answers [cfg conn]."
  [n]
  (let [cfg (mem-cfg)]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (seed! conn n)
      [cfg conn])))

(defn preload!
  "Read every node of db's indexes, so this process holds the whole db in its heap and
  forked children (the fork server, agents) never read the store: an LMDB env must not
  be used across fork. Answers the datom count."
  [db]
  (count (d/datoms db :aevt)) (count (d/datoms db :avet))
  (count (d/datoms db :eavt)))

(defn open!
  "The durable database at path: recovered when the store exists, else created and
  seeded with about n datoms. Answers {:cfg :conn :recovered? bool}."
  [backend path n]
  (let [cfg (store-cfg backend path)
        exists? (posix/exists? (if (= backend :lmdb) (str path "/data.mdb") path))]
    (when-not exists? (d/create-database cfg))
    (let [conn (d/connect cfg)]
      (when-not exists? (seed! conn n))
      (sync! cfg)
      {:cfg cfg :conn conn :recovered? exists?})))

(defn diff-ops
  "Explicit-id tx-data turning db `from` into db `to` (retractions first, so an
  attribute's new value replaces its old one in net-change). O(datoms of both)."
  [from to]
  (let [ds (fn [db] (into #{} (map (fn [dt] [(:e dt) (:a dt) (:v dt)])) (d/datoms db :eavt)))
        a (ds from) b (ds to)]
    (into (vec (for [[e at v] a :when (not (b [e at v]))] [:db/retract e at v]))
          (for [[e at v] b :when (not (a [e at v]))] [:db/add e at v]))))

(defn branch-base
  "The db at the merge base of branch and main (git's merge-base over Datahike's commit
  graph): the branch point, the branch head root last merged, or the main head last
  merged into the branch."
  [conn branch]
  (d/commit-as-db conn (d/merge-base conn branch :db)))

;; ---- tx-data that replays exactly -----------------------------------------------
;; Seon's Datahike fork issues entity ids from one store-wide counter, so ids are unique
;; across branches: a branch's new entity keeps its id on main, and a replica (the fork
;; server's main, an agent's branch copy) applies the writer's RESOLVED datoms rather
;; than re-resolving tempids with its own counter.

(defn report->tx
  "The writer's tx-report as explicit-id tx-data (the transaction entity's own datoms left out)."
  [rep]
  (vec (for [dt (:tx-data rep) :when (not= (:e dt) (:tx dt))]
         [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])))

(defn- card-one? [db a]
  (not= :db.cardinality/many (get-in (d/schema db) [a :db/cardinality])))

(defn net-change
  "Fold a branch's resolved tx-data (in order) into {key [op e a v]}: a
  cardinality-one attribute is keyed by [e a], so a later value replaces an earlier one
  (Jolt's tx-report leaves out the retraction of a replaced value), a cardinality-many
  one by [e a v]. db supplies the schema."
  [db ops]
  (reduce (fn [m [op e a v :as o]] (assoc m (if (card-one? db a) [e a] [e a v]) o)) {} ops))

(defn delta-tx
  "The branch's net change since `base` (its merge base) as tx-data for main, ids as they
  are. Answers {:tx-data [...] :touched #{[e a]}}: touched names the attributes of
  entities that existed at the base, for the conflict check."
  [base ops]
  (let [nc (net-change base ops)
        in-base? (fn [e a v] (seq (d/datoms base :eavt e a v)))
        existed? (fn [e] (seq (d/datoms base :eavt e)))]
    {:tx-data (vec (for [[_ [op e a v]] nc
                         :when (if (= op :db/add) (not (in-base? e a v)) (in-base? e a v))]
                     [op e a v]))
     :touched (set (for [[_ [_ e a _]] nc :when (existed? e)] [e a]))}))

(defn conflicts
  "Attributes of existing entities the branch changed that main also changed since the
  merge base: git's 'both modified'."
  [base main touched]
  (vec (for [[e a] touched
             :let [b (set (map :v (d/datoms base :eavt e a)))
                   m (set (map :v (d/datoms main :eavt e a)))]
             :when (not= b m)]
         {:e e :a a :base b :main m})))
