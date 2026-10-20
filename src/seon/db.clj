(ns seon.db
  "The database side: schema of the example program, the seed, the store, and the merge
  delta (a branch's net change since its merge base, as tx-data that replays onto main).
  From proto/flow f2-final proto.db; the store's node codec is X1's jfasl
  (konserve.jolt.fasl-store, FaslSerializer), and the JVM-written-store reader stays behind
  (BAGGAGE.md)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-] :as contract] [seon.schema.datahike :as sd] [seon.config :as cfg]
            [datahike.connections] [datahike.writing] [hasch.core :as hasch] [datahike.api :as d] [konserve.core :as k] [konserve.jolt.store] [konserve.jolt.lmdb :as klmdb]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi] [konserve.jolt.posix :as posix]))

(def schema
  "The Datahike schema, derived from the registry's entity schemas (resources/seon/schemas,
  seon.schema.datahike; lane D1): one source of truth with the contracts."
  (sd/declarations contract/registry))

(def observation-attrs
  "Attributes of observations (evaluations, the agent's answered t): data on the branch
  that recorded them, never carried by a merge (G1's proto.db/observation-attrs, derived by
  attribute namespace from the schema)."
  (into #{} (comp (map :db/ident) (filter #(#{"seon.cluster.eval" "seon.agent"} (namespace %)))) schema))

(defn seed-tx
  "Transactions for about n datoms: per namespace, one tx of the namespace and its 20
  vars (5 datoms each), then one tx of a task referring to two of its vars."
  {:malli/schema [:=> [:cat :int] [:sequential :seon.db/tx-data]]}
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

(defn mem-cfg {:malli/schema [:=> [:cat] :seon.db/cfg]}
  [] {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false
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
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string] :seon.db/cfg]}
  [backend path]
  {:store (cond-> {:backend backend :path path :id (hasch/uuid path)
                   :default-serializer :FaslSerializer}
            (= backend :lmdb) (assoc :lmdb {:flags MDB_NOSYNC :map-size (* 1048576 (Long/parseLong (or (System/getenv "SEON_LMDB_MB") "1024")))})
            (= backend :file) (assoc :config {:defer-immutable-sync? true}))
   :schema-flexibility :write :keep-history? false
   :writer writer-cfg
   :index-config {:branching-factor BF}})

(defn sync!
  "Make every commit so far durable: one env sync for LMDB (MDB_NOSYNC); the filestore
  syncs per write itself. nil."
  {:malli/schema [:=> [:cat :seon.db/cfg] :nil]}
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    (lmdb-ffi/sync! (klmdb/open-env! (get-in cfg [:store :path]) nil)))
  nil)

(defn- seed! {:malli/schema [:=> [:cat :seon.host/conn :int] :any]}
  [conn n]
  (d/transact conn {:tx-data schema})
  (doseq [tx (seed-tx n)] (d/transact conn {:tx-data tx})))

(defn create!
  "A fresh in-memory database seeded with about n datoms. Answers [cfg conn]."
  {:malli/schema [:=> [:cat :int] [:tuple :seon.db/cfg :seon.host/conn]]}
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
  {:malli/schema [:=> [:cat :seon.host/db] :int]}
  [db]
  (count (d/datoms db :aevt)) (count (d/datoms db :avet))
  (count (d/datoms db :eavt)))

(defn open!
  "The durable database at path: recovered when the store exists, else created and
  seeded with about n datoms. Answers {:cfg :conn :recovered? bool}."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string :int] :seon.db/open]}
  [backend path n]
  (let [cfg (store-cfg backend path)
        exists? (posix/exists? (if (= backend :lmdb) (str path "/data.mdb") path))]
    (when-not exists? (d/create-database cfg))
    (let [conn (d/connect cfg)]
      (when-not exists? (seed! conn n))
      (sync! cfg)
      {:cfg cfg :conn conn :recovered? exists?})))

(defn after-fork!
  "Make this forked process (an agent, a clone) open the store itself: an LMDB env and the
  connections inherited from its parent must not be used across fork(), so they are
  forgotten (not closed: they are the parent's), and the store's entity-id allocator starts
  at this process's lease. Physics, not a guard: fork shares the parent's handles."
  {:malli/schema [:=> [:cat :seon.db/cfg :int] :any]}
  [cfg lease]
  (swap! @#'konserve.jolt.lmdb/envs dissoc (get-in cfg [:store :path]))
  (reset! datahike.connections/*connections* {})
  (.put ^java.util.concurrent.ConcurrentHashMap @#'datahike.writing/eid-allocators
        (get-in cfg [:store :id]) (java.util.concurrent.atomic.AtomicLong. lease)))

(defn lease-base
  "The first entity id past every branch head of the store: agents' leases start here."
  {:malli/schema [:=> [:cat :seon.host/conn] :int]}
  [conn] (+ cfg/EID-LEASE (max (:max-eid @conn) (#'datahike.writing/roster-max-eid (:store @conn)))))

(defn branch?
  "Does the store hold branch b? Its head key exists exactly when the branch does
  (datahike.roster), an O(1) read; d/branches folds the whole roster, O(branches)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :boolean]}
  [conn b] (boolean (k/exists? (:store @conn) b {:sync? true})))

(defn head
  "Branch b's committed value, read from the store (another process may have committed it)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :seon.host/db]}
  [conn b] (d/branch-as-db (:store @conn) b))

(defn diff-ops
  "Explicit-id tx-data turning db `from` into db `to` (retractions first, so an
  attribute's new value replaces its old one in net-change). O(datoms of both)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] :seon.db/resolved-tx]}
  [from to]
  (let [ds (fn [db] (into #{} (map (fn [dt] [(:e dt) (:a dt) (:v dt)])) (d/datoms db :eavt)))
        a (ds from) b (ds to)]
    (into (vec (for [[e at v] a :when (not (b [e at v]))] [:db/retract e at v]))
          (for [[e at v] b :when (not (a [e at v]))] [:db/add e at v]))))

(defn branch-base
  "The db at the merge base of branch and main (git's merge-base over Datahike's commit
  graph): the branch point, the branch head root last merged, or the main head last
  merged into the branch."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :seon.host/db]}
  [conn branch]
  (d/commit-as-db conn (d/merge-base conn branch :db)))

;; ---- tx-data that replays exactly -----------------------------------------------
;; Seon's Datahike fork issues entity ids from one store-wide counter, so ids are unique
;; across branches: a branch's new entity keeps its id on main, and a replica (the fork
;; server's main, an agent's branch copy) applies the writer's RESOLVED datoms rather
;; than re-resolving tempids with its own counter.

(defn report->tx
  "The writer's tx-report as explicit-id tx-data (the transaction entity's own datoms left out)."
  {:malli/schema [:=> [:cat :seon.db/tx-report] :seon.db/resolved-tx]}
  [rep]
  (vec (for [dt (:tx-data rep) :when (not= (:e dt) (:tx dt))]
         [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])))

