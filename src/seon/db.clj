(ns seon.db
  "The database side: schema of the example program, the seed, the store, and the merge
  delta (a branch's net change since its merge base, as tx-data that replays onto main).
  From proto/flow f2-final proto.db; the store's node codec is X1's jfasl
  (konserve.jolt.fasl-store, FaslSerializer), and the JVM-written-store reader stays behind
  (BAGGAGE.md)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.profile :as profile] [seon.contract :refer [defn defn-] :as contract] [seon.schema.datahike :as sd] [seon.config :as cfg]
            [datahike.connections] [datahike.writing] [datahike.config] [datahike.store] [konserve.store] [datahike.db.transaction :as dbt] [hasch.core :as hasch] [datahike.api :as d] [konserve.core :as k] [konserve.jolt.store] [konserve.jolt.lmdb :as klmdb]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi] [konserve.jolt.posix :as posix]))

(def schema
  "The Datahike schema, derived from the registry's entity schemas (resources/seon/schemas,
  seon.schema.datahike; session D1): one source of truth with the contracts."
  (sd/declarations contract/registry))

(defn eid
  "The entity id of the entity whose unique attribute a has value v on db, or nil: one seek
  in a's AVET index, never a query (a Datalog query here cost ~95 us and 100 KB at 100
  tasks, planned on every call). The one entity lookup (docs/storage.md#entity-ids)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :any] [:maybe :int]]}
  [db a v] (:e (first (d/datoms db :avet a v))))

(defn seed-tx
  "Transactions of an example program sized by n (the test store's and the check store's
  seed, never a root's own): n/110 namespaces (none below 110), each one tx of the
  namespace and its 20 contracted functions (each a form and its var: 12 datoms, so about
  2n datoms in all), then one tx of a task referring to two of its vars."
  {:malli/schema [:=> [:cat :int] [:sequential :seon.db/tx-data]]}
  [n]
  (let [nns (quot n 110)]
    (mapcat (fn [k]
              (let [nsym (symbol (str "app.m" k))]
                [(into [{:db/id "ns" :ns/name nsym :ns/doc (str "Module " k " of the example program.")}]
                       (for [i (range 20)
                             :let [src (str "(defn f" i " {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " i "))")]
                             e [{:db/id (str "form " i) :form/id (str nsym "/f" i) :form/src src :form/ns "ns" :form/order i
                                 :form/defines [(str "var " i)]}
                                {:db/id (str "var " i) :var/name (symbol (str nsym) (str "f" i)) :var/ns "ns" :var/doc (str "f" i " of module " k)
                                 :var/form (str "form " i) :var/kind :fn :var/contract "[:=> [:cat :int] :int]" :var/arity 1}]]
                         e))
                 [{:task/name (keyword (str "app.m" k) "tidy") :task/plan (str "Tidy module " k ": name every value by its schema.")
                   :task/status :held :task/refs [[:var/name (symbol (str nsym) "f0")] [:var/name (symbol (str nsym) "f1")]]}]]))
            (range nns))))

;; :writer :sync (vendor datahike.writer, session F1): root's Flow :writer proc is the only
;; caller of transact, so it is THE serial writer; Datahike runs no queue, commit loop or
;; go block of its own (memory: commit p50 0.49 -> 0.39 ms, 639 -> 446 KB/tx, 2 threads
;; fewer per connection; src/proto/f1_bench.clj).
(def writer-cfg {:backend :sync})

;; ---- the durable store ----------------------------------------------------------
;; konserve on Jolt (libs/konserve): LMDB (konserve.jolt.lmdb) or the POSIX filestore,
;; holding E4a's real index nodes, one content-addressed node per key. Branching factor
;; 64: a commit rewrites the nodes it touched, so smaller nodes mean fewer bytes printed
;; and written per commit (dev/dur.clj: 512 -> 64 cut a small commit 27 -> 16 ms with
;; fsync, before the node emitter). LMDB runs MDB_NOSYNC and the flusher syncs the env
;; once for every commit so far (sync!), so a commit is at most one drive flush, not one per node.

(def ^:private MDB_NOSYNC 0x10000)

(defn- stored-id
  "The id the store cfg names was made with, read from its :db head's stored config (0.8 ms),
  or nil when no store is there yet."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe :uuid]]}
  [cfg]
  (let [{:keys [backend path]} (:store cfg)]
    (when (posix/exists? (if (= backend :lmdb) (str path "/data.mdb") path))
      (-> (konserve.store/connect-store (:store cfg) {:sync? true})
          (datahike.store/add-cache-and-handlers (datahike.config/load-config cfg))
          (datahike.writing/read-head :db)
          (get-in [:config :store :id])))))

(defn store-cfg
  "Datahike config for the durable store at path; backend :lmdb (default) or :file. A store is
  named by the id it records, never by where it lies: a kept run opens wherever it is copied,
  moved or archived (stored-id); the path names only a store this config is about to make.
  A copy keeps its original's id, as a replica does, so one process opens one of them. It names
  no :keep-history?: that is fixed when a store is made (Datahike's store-fixed-record-keys;
  made-cfg), and a connect adopts what the store records, so a kept store made either way opens."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string] :seon.db/cfg]}
  [backend path]
  (let [cfg {:store (cond-> {:backend backend :path path :id (hasch/uuid path)
                             :default-serializer :FaslSerializer}
                      (= backend :lmdb) (assoc :lmdb {:flags MDB_NOSYNC :map-size (cfg/value :seon.config.store/lmdb-map-bytes)})
                      ;; konserve's per-value compressor (its header names it, so stored values of either kind read)
                      (#{:lz4 :zstd} (cfg/value :seon.config.store/compressor)) (assoc-in [:config :compressor] {:type (cfg/value :seon.config.store/compressor)})
                      (= backend :file) (assoc :config {:defer-immutable-sync? true}))
             :schema-flexibility :write
             :writer writer-cfg
             :index-config (let [n (cfg/value :seon.config.store/value-out-of-line-bytes)]
                             (cond-> {:branching-factor (cfg/value :seon.config.store/branching-factor)}
                               (int? n) (assoc :value-out-of-line-bytes n)))}]
    (if-let [id (stored-id cfg)] (assoc-in cfg [:store :id] id) cfg)))

(defn made-cfg
  "cfg as a store is made with it: keeping history (its temporal indexes, and each
  transaction's tx-meta) as :seon.config.store/keep-history? says (v1's ruling 23)."
  {:malli/schema [:=> [:cat :seon.db/cfg] :seon.db/cfg]}
  [cfg] (assoc cfg :keep-history? (cfg/value :seon.config.store/keep-history?)))

(defn sync!
  "Make every commit so far durable: one env sync for LMDB (MDB_NOSYNC); the filestore
  syncs per write itself. nil."
  {:malli/schema [:=> [:cat :seon.db/cfg] :nil]}
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    (lmdb-ffi/sync! (klmdb/open-env! (get-in cfg [:store :path]) nil)))
  nil)

(defn tx-meta
  "The tx-meta of every transaction Seon makes: the runtime that made it
  (:seon.tx/runtime, jolt.host/runtime-fingerprint). A runtime Jolt cannot identify (nil)
  is refused by this contract, never recorded as no runtime."
  {:malli/schema [:=> [:cat] [:map [:seon.tx/runtime :string]]]}
  [] {:seon.tx/runtime (jolt.host/runtime-fingerprint)})

(defn- install-missing!
  "Transact the attributes of `schema` the db under conn lacks and answer how many: first
  :seon.tx/runtime alone when it is missing (the one transaction that cannot name its
  runtime, since it installs the attribute), then the rest in one transaction carrying
  tx-meta (an attribute it holds as another value type is refused, :seon.db/types-differ:
  a value type is fixed once installed): a kept store seeded before an attribute existed gains it when a root
  opens it (Datahike accepts new attribute definitions at any time). Nothing missing
  writes nothing."
  {:malli/schema [:=> [:cat :seon.host/conn] :int]}
  [conn]
  (let [have (d/schema @conn)
        differ (into {} (for [{k :db/ident t :db/valueType} schema
                              :let [stored (get-in have [k :db/valueType])]
                              :when (and stored (not= stored t))]
                          [k {:stored stored :declared t}]))
        _ (when (seq differ)
            (let [e (contract/error :seon.db/types-differ 'seon.db/open!
                                    (str "the store holds " (count differ) " attributes as another value type than the schema declares: " (pr-str (keys differ)))
                                    {:seon.db/attributes differ
                                     :seon.error/fix "An attribute's value type is fixed once installed: open a store made with this schema (a fresh one), or keep running the build that made this store."})]
              (throw (ex-info (:seon.error/message e) e))))
        missing (vec (remove #(contains? have (:db/ident %)) schema))]
    (let [[runtime others] ((juxt filter remove) #(= :seon.tx/runtime (:db/ident %)) missing)]
      (when (seq runtime) (d/transact conn {:tx-data (vec runtime)}))
      (when (seq others) (d/transact conn {:tx-data (vec others) :tx-meta (tx-meta)})))
    (count missing)))

(def renamed
  "Stored attributes renamed (AGENTS.md section 7a, owner 2026-10-22), old name -> new name.
  Datahike renames nothing itself: an attribute's :db/ident never changes and no second
  name aliases it (datahike.schema/find-invalid-schema-updates), so open! moves each current
  fact to its new name (rename-tx) and the old attribute stays installed: history keeps what
  was written, under the name it was written with."
  {:seon.task/stuck :seon.task/budget-spent
   :seon.config.contract/guarantees :seon.config.contract/instrument
   :seon.ai.model/thinking-dials :seon.ai.model/thinking-modes})

(defn rename-tx
  "tx-data moving every current fact db holds under a renamed attribute to its new name: an
  add under the new name, a retract under the old. Empty once nothing is left under an old
  name, so a second open writes nothing. O(current facts of the renamed attributes)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.db/tx-data]}
  [db]
  (let [have (d/schema db)]
    (vec (for [[old new] renamed :when (contains? have old)
               dt (d/datoms db :aevt old)
               op [[:db/add (:e dt) new (:v dt)] [:db/retract (:e dt) old (:v dt)]]]
           op))))

(defn seed!
  "Seed the empty store under conn: the schema (install-missing!), then (seed-tx n)."
  {:malli/schema [:=> [:cat :seon.host/conn :int] :any]}
  [conn n]
  (install-missing! conn)
  (doseq [tx (seed-tx n)] (d/transact conn {:tx-data tx :tx-meta (tx-meta)})))

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

(defn open-main!
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

(defn delete-main!
  "Delete main and every branch held under it (of-main) from the store of conn, a connection
  to :db (datahike.api/delete-branch!; a branch's connections are released first): what a
  check step or a test made for itself, deleted by the run that made it. Real agent runs are
  never deleted this way (owner, 2026-10-22: kept as evidence). Answers the deleted branches;
  a main the store lacks deletes nothing."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] [:vector :seon.db/branch]]}
  [conn main]
  (let [under (mapv #(on-main main %) (of-main main (d/branches conn)))
        bs (cond-> under (branch? conn main) (conj main))]
    (doseq [b bs] (d/delete-branch! conn b))
    bs))

(defn preload!
  "Read every node of db's indexes, so this process holds the whole db in its heap and
  forked children (the fork server, agents) never read the store: an LMDB env must not
  be used across fork. Answers the datom count."
  {:malli/schema [:=> [:cat :seon.host/db] :int]}
  [db]
  (count (d/datoms db :aevt)) (count (d/datoms db :avet))
  (count (d/datoms db :eavt)))

(defn store?
  "Does a durable LMDB store exist at path (its data file)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path] (boolean (posix/exists? (str path "/data.mdb"))))

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
      (d/create-database (made-cfg cfg))
      (let [conn (d/connect cfg)] (seed! conn n) (d/release conn)))
    (let [[conn existed?] (open-main! cfg main)
          added (install-missing! conn)
          moved (let [tx (rename-tx @conn)]
                  (when (seq tx) (d/transact conn {:tx-data tx :tx-meta (tx-meta)}))
                  (count tx))
          cfg (cond-> cfg (not= :db main) (assoc :branch main))]
      ;; durable before root runs: the seed, the new branch, the added attributes or the
      ;; renamed facts (an fsync, ~5-10 ms); a recovered main with its schema whole wrote nothing
      (when-not (and exists? existed? (zero? added) (zero? moved)) (sync! cfg))
      {:cfg cfg :conn conn :recovered? (and exists? existed?) :seeded? (not exists?)})))

(defn collect!
  "Collect the store under conn (datahike.api/gc-storage: every key no branch reaches, nodes
  and values out of line, is swept) when a branch the last record saw is gone (main's
  :seon.root/collected): the record to write, {:swept :ms :branches :gone}. With no record yet
  (a new main) or none gone but branches made since, the record of what is here, nothing swept,
  so a branch seen at one quiescence and gone at a later one is reclaimed; {:skipped true} when
  the branches are the ones recorded. Only while no
  other process writes the store (owner, 2026-10-22, simplest first): root's writer runs it
  (:collect) and root refuses it while an agent is live (docs/storage.md#collection)."
  {:malli/schema [:=> [:cat :seon.host/conn] [:or [:map [:skipped :boolean]] :seon.root/collected]]}
  [conn]
  (let [last (:seon.root/collected (d/pull @conn [:seon.root/collected] [:seon.root/id :root]))
        now (set (d/branches conn))
        gone (count (remove now (:branches last)))]
    (cond
      (and last (zero? gone) (= now (:branches last))) {:skipped true}
      ;; no record yet (a new main: every check step's) or nothing gone: record what is here,
      ;; sweep nothing; a full sweep on a store of hundreds of branches held a check step's
      ;; spawns behind root's writer for seconds (scale: 8.2 s to ready, 2026-10-22)
      (zero? gone) {:swept 0 :ms 0.0 :branches now :gone 0}
      :else
      (let [t0 (profile/now-ns)
            swept @(d/gc-storage conn (java.util.Date. 0))]
        {:swept (count swept) :ms (profile/ms-since t0) :branches now :gone gone}))))

(defn lease!
  "Make this process mint the store's entity ids from its lease: from lease, up to (not
  including) lease + EID-LEASE; past it a transaction is refused (lease-spent), never
  minting the next agent's ids. nil."
  {:malli/schema [:=> [:cat :seon.db/cfg :int] :nil]}
  [cfg lease]
  (.put ^java.util.concurrent.ConcurrentHashMap @#'datahike.writing/eid-allocators
        (get-in cfg [:store :id]) (java.util.concurrent.atomic.AtomicLong. lease))
  (dbt/set-eid-ceiling! (get-in cfg [:store :id]) (+ lease (cfg/value :seon.config.store/eid-lease))))

(defn after-fork!
  "Make this forked process (an agent, a clone) open the store itself: an LMDB env and the
  connections inherited from its parent must not be used across fork(), so they are
  forgotten (not closed: they are the parent's), and the store's entity-id allocator starts
  at this process's lease and stops at its end (lease!). Physics, not a guard: fork shares
  the parent's handles."
  {:malli/schema [:=> [:cat :seon.db/cfg :int] :any]}
  [cfg lease]
  (swap! @#'konserve.jolt.lmdb/envs dissoc (get-in cfg [:store :path]))
  (reset! datahike.connections/*connections* {})
  (lease! cfg lease))

(defn lease-spent
  "The typed error :seon.db/lease-spent when x (what a transaction answered) is Datahike's
  error to mint past this process's ceiling (after-fork!), for the agent whose lease
  starts at lease; else nil."
  {:malli/schema [:=> [:cat :any [:maybe :int]] [:maybe :seon.db/lease-spent]]}
  [x lease]
  (let [d (ex-data x)]
    (when (and lease (= :entity-id/lease-spent (:error d)))
      (contract/error :seon.db/lease-spent 'seon.agent/transact!
                      (str "this agent's entity ids are spent: its lease is " (cfg/value :seon.config.store/eid-lease) " ids from " lease)
                      {:lease lease :ceiling (:ceiling d) :entity-id (:entity-id d)
                       :seon.error/fix "Write fewer new entities in this agent (retract or reuse), or raise :seon.config.store/eid-lease for the run (--set); the transaction was not applied."}))))

(defn lease-use
  "How much of the lease starting at lease db's branch has minted: {:from :used :of}."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int]] [:maybe [:map [:from :int] [:used :int] [:of :int]]]]}
  [db lease]
  (when lease {:from lease :used (max 0 (- (:max-eid db) lease)) :of (cfg/value :seon.config.store/eid-lease)}))

(defn map-use
  "The LMDB store's use of its map: {:used-bytes (data.mdb's length: LMDB grows the file as it
  uses pages) :map-bytes}, or nil for another backend."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe [:map [:used-bytes :int] [:map-bytes :int]]]]}
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    {:used-bytes (.length (java.io.File. (str (get-in cfg [:store :path]) "/data.mdb")))
     :map-bytes (get-in cfg [:store :lmdb :map-size])}))

(defn lease-base
  "The first entity id past every branch head of the store: agents' leases start here."
  {:malli/schema [:=> [:cat :seon.host/conn] :int]}
  [conn] (+ (cfg/value :seon.config.store/eid-lease) (max (:max-eid @conn) (#'datahike.writing/roster-max-eid (:store @conn)))))

(defn head
  "Branch b's committed value, read from the store (another process may have committed it)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :seon.host/db]}
  [conn b] (d/branch-as-db (:store @conn) b))

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

(defn report->tx-meta
  "The transaction entity's own facts in the writer's tx-report ({attribute value}: its
  :db/txInstant and any tx-meta; empty without history): what a replica of the branch
  applies as its own transaction's :tx-meta, so its transaction entity holds main's facts
  (a transaction's id is its branch's own, so they cannot travel as datoms on it)."
  {:malli/schema [:=> [:cat :seon.db/tx-report] :map]}
  [rep]
  (into {} (for [dt (:tx-data rep) :when (and (= (:e dt) (:tx dt)) (:added dt))] [(:a dt) (:v dt)])))

(defn report->tx
  "The writer's tx-report as explicit-id tx-data (the transaction entity's own datoms left
  out: report->tx-meta carries them)."
  {:malli/schema [:=> [:cat :seon.db/tx-report] :seon.db/resolved-tx]}
  [rep]
  (vec (for [dt (:tx-data rep) :when (not= (:e dt) (:tx dt))]
         [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])))

