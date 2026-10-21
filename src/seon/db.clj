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

(defn seed-tx
  "Transactions for about n datoms of an example program (the test store's and the check
  store's seed, never a root's own): per namespace, one tx of the namespace and its 20
  functions (each a form and its var, 11 datoms), then one tx of a task referring to two
  of its vars. None below 230."
  {:malli/schema [:=> [:cat :int] [:sequential :seon.db/tx-data]]}
  [n]
  (let [nns (quot n 230)]
    (mapcat (fn [k]
              (let [nsym (symbol (str "app.m" k))]
                [(into [{:db/id "ns" :ns/name nsym :ns/doc (str "Module " k " of the example program.")}]
                       (for [i (range 20)
                             :let [src (str "(defn f" i " [x] (+ x " i "))")]
                             e [{:db/id (str "form " i) :form/id (str nsym "/f" i) :form/src src :form/ns "ns" :form/order i
                                 :form/defines [(str "var " i)]}
                                {:db/id (str "var " i) :var/name (symbol (str nsym) (str "f" i)) :var/ns "ns" :var/doc (str "f" i " of module " k)
                                 :var/form (str "form " i) :var/kind :fn :var/arity 1}]]
                         e))
                 [{:task/name (keyword (str "app.m" k) "tidy") :task/plan (str "Tidy module " k ": name every value by its schema.")
                   :task/status :held :task/refs [[:var/name (symbol (str nsym) "f0")] [:var/name (symbol (str nsym) "f1")]]}]]))
            (range nns))))

;; :writer :sync (vendor datahike.writer, lane F1): root's Flow :writer proc is the only
;; caller of transact, so it is THE serial writer; Datahike runs no queue, commit loop or
;; go block of its own (memory: commit p50 0.49 -> 0.39 ms, 639 -> 446 KB/tx, 2 threads
;; fewer per connection; src/proto/f1_bench.clj).
(def writer-cfg {:backend :sync})

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

;; ---- one store, many branches, as in production (owner, 2026-10-21) ------------
;; A store holds one seed on :db. Every root works on a main of its own, a branch of :db
;; (production's root works on :db itself; each check step, each run, each test on a
;; branch), and its agents' and tasks' branches are named under that main, so roots
;; sharing a store never share a branch. A branch is one pointer write (~1 ms measured,
;; 2026-10-21) where a fresh seeded store was ~200 ms and ~2 MB.

(defn main-branch
  "The branch the root holding cfg works on as its main: cfg's :branch, :db when none."
  {:malli/schema [:=> [:cat :seon.db/cfg] :seon.db/branch]}
  [cfg] (:branch cfg :db))

(defn conn-main
  "The main of the root holding conn (its connection to main): the branch conn is on."
  {:malli/schema [:=> [:cat :seon.host/conn] :seon.db/branch]}
  [conn] (main-branch (:config @conn)))

(defn on-main
  "Branch b of the root whose main is `main`, as the store names it: :db names main itself;
  on :db (production) b is b; on any other main b is qualified by main's name (:smoke-x/agent-1).
  Idempotent."
  {:malli/schema [:=> [:cat :seon.db/branch :seon.db/branch] :seon.db/branch]}
  [main b]
  (cond (= :db b) main
        (or (= :db main) (= main b) (= (name main) (namespace b))) b
        :else (keyword (name main) (if (namespace b) (str (namespace b) "." (name b)) (name b)))))

(defn of-main
  "The branches of the store held under main, by the names its root gives them (on-main undone),
  main itself left out."
  {:malli/schema [:=> [:cat :seon.db/branch [:set :seon.db/branch]] [:vector :seon.db/branch]]}
  [main branches]
  (vec (sort (for [b branches
                   :when (if (= :db main) (and (nil? (namespace b)) (not= :db b)) (= (name main) (namespace b)))]
               (if (= :db main) b (keyword (name b)))))))

(defn branch?
  "Does the store hold branch b? Its head key exists exactly when the branch does
  (datahike.roster), an O(1) read; d/branches folds the whole roster, O(branches)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :boolean]}
  [conn b] (boolean (k/exists? (:store @conn) b {:sync? true})))

(defn- open-main!
  "main on the store of cfg, whose :db exists: made from :db's head when the store has no
  such branch (one pointer write). Answers [conn existed?]."
  {:malli/schema [:=> [:cat :seon.db/cfg :seon.db/branch] [:tuple :seon.host/conn :boolean]]}
  [cfg main]
  (let [seed-conn (d/connect cfg)
        existed? (or (= :db main) (branch? seed-conn main))]
    (when-not existed? (d/branch! seed-conn :db main))
    (if (= :db main)
      [seed-conn existed?]
      (let [conn (d/connect (assoc cfg :branch main))] (d/release seed-conn) [conn existed?]))))

(defn agents-dir
  "Where the agents of the root holding cfg keep their stderr (seon.agent/stderr-path):
  <store path>-agents/<main>, or nil for a store with no path (in memory)."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe :string]]}
  [cfg] (when-let [p (get-in cfg [:store :path])] (str p "-agents/" (name (main-branch cfg)))))

(def ^:private test-store
  "This process's one in-memory store for tests and cost setups, seeded once on :db
  (bin/test makes it on first use): each test takes a branch of it (test-branch!)."
  (delay (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false
                        :writer writer-cfg}]
           (d/create-database cfg)
           (let [conn (d/connect cfg)] (seed! conn 2000) (d/release conn))
           cfg)))

(defn test-branch!
  "A fresh branch of the test store (its seed: the schema and about 2,000 datoms of the
  example program), for one test: [cfg conn], cfg naming the branch. What the test writes
  stays on its branch."
  {:malli/schema [:=> [:cat] [:tuple :seon.db/cfg :seon.host/conn]]}
  []
  (let [cfg (assoc @test-store :branch (keyword (str "test-" (random-uuid))))
        [conn _] (open-main! (dissoc cfg :branch) (:branch cfg))]
    [cfg conn]))

(defn preload!
  "Read every node of db's indexes, so this process holds the whole db in its heap and
  forked children (the fork server, agents) never read the store: an LMDB env must not
  be used across fork. Answers the datom count."
  {:malli/schema [:=> [:cat :seon.host/db] :int]}
  [db]
  (count (d/datoms db :aevt)) (count (d/datoms db :avet))
  (count (d/datoms db :eavt)))

(defn open!
  "Root's main on the durable store at path. A store that does not exist is created and
  seeded with about n datoms on :db; main (a branch name) is made from :db's head when the
  store has no such branch, or recovered when it has. Answers {:cfg (naming main) :conn
  :recovered? (main existed) :seeded? (this call made the store)}."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string :int :seon.db/branch] :seon.db/open]}
  [backend path n main]
  (let [cfg (store-cfg backend path)
        exists? (posix/exists? (if (= backend :lmdb) (str path "/data.mdb") path))]
    (when-not exists?
      (d/create-database cfg)
      (let [conn (d/connect cfg)] (seed! conn n) (when-not (= :db main) (d/release conn))))
    (let [[conn existed?] (open-main! cfg main)
          cfg (cond-> cfg (not= :db main) (assoc :branch main))]
      ;; durable before root runs: the seed or the new branch (an fsync, ~5-10 ms); a
      ;; recovered main wrote nothing
      (when-not (and exists? existed?) (sync! cfg))
      {:cfg cfg :conn conn :recovered? (and exists? existed?) :seeded? (not exists?)})))

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
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch :seon.db/branch] :seon.host/db]}
  [conn main branch]
  (d/commit-as-db conn (d/merge-base conn branch main)))

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

