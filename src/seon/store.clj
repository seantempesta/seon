(ns seon.store
  "Durable multi-process Datahike store on LMDB with shared atomic entity/tx IDs (`native/ids.c`),
   100% Malli-derived Datahike schema (`seon.schema.datahike` — zero duplicate attribute tables!),
   Malli write-seam validation, and Aero-seeded `:seon.config/root` inheritance."
  (:require [aero.core :as aero]
            [clojure.core.cache :as cache]
            [clojure.core.cache.wrapped :as cw]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike.config]
            [datahike.connections]
            [datahike.ids]
            [datahike.store]
            [datahike.writing]
            [hasch.core :as hasch]
            [jolt.ffi :as ffi]
            [konserve.core :as k]
            [konserve.jolt.fasl-store]
            [konserve.jolt.lmdb :as klmdb]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi]
            [konserve.jolt.posix :as posix]
            [konserve.jolt.store]
            [konserve.store]
            [malli.core :as m]
            [seon.schema :as schema]
            [datahike.index.interface :as di]
            [datahike.reads :as reads]
            [jolt.scheme :as scm]
            [jolt.fs :as fs]))

(def checkout-root schema/checkout-root)

(ffi/load-library)

(ffi/load-library (str checkout-root "/target/lib/libprobe.dylib"))

(ffi/defcfn c-ids-open "seon_ids_open" [:string] :pointer {:capture-native-error true})

(ffi/defcfn c-ids-take "seon_ids_take" [:pointer :int] :uint64)

(ffi/defcfn c-ids-reach "seon_ids_reach" [:pointer :int :uint64] :void)

(ffi/defcfn c-ids-trusted "seon_ids_trusted" [:pointer :string :string] :int)

(ffi/defcfn c-ids-stamp "seon_ids_stamp" [:pointer :string :string] :void)

(def ^:private MDB_NOSYNC 0x10000)

(def ^:private MDB_SAFENOSYNC 0x8000)

(def default-config-values
  "Default operational values for `:seon.config/root` (overridden by `resources/seon/config/default.edn`)."
  {:seon.config/eval-ticks               100000000
   :seon.config/eval-bytes               268435456
   :seon.config/gen-samples              100
   :seon.config/gen-seed                 42
   :seon.config/max-os-procs             6
   :seon.config/max-test-workers         4
   :seon.config/max-model-slots          4
   :seon.config/child-cache-mb           32
   :seon.config/gc-generations           6
   :seon.config/store-map-bytes          1099511627776
   :seon.config/gc-keep-merged-branches  16
   :seon.config/gc-history-hours         168
   :seon.config/review-every-turns       0
   :seon.config/unwritten-review-turns   8
   :seon.config/semantic-min-score       0.45
   :seon.config/auto-sync-git?           true
   :seon.config/auto-merge?              true
   :seon.config/auto-switch-over?        true
   :seon.config/fast-test-ms             150
   :seon.config/ai-model                 "deepseek-flash"
   :seon.config/ai-base-url              "https://api.deepseek.com"
   :seon.config/ai-api-key-env           "DEEPSEEK_API_KEY"
   :seon.config/ai-thinking              :enabled
   :seon.config/ai-reasoning-effort      :low
   :seon.config/max-tool-turns           32
   :seon.config/viewport-screenshot?     false
   :seon.config/ns-outline-tokens        6000
   :seon.config/eval-out-chars           10000})

(defn read-aero-config-tx
  "Reads an Aero EDN config file once at bootstrap and returns the `:seon.config/root` entity map.
   When `db` is supplied, filters attributes to those installed in `(:schema db)`."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat [:maybe :seon.host/db] :any] :map]]}
  ([file-or-text] (read-aero-config-tx nil file-or-text))
  ([db file-or-text]
   (let [raw (if (instance? java.io.File file-or-text)
               (when (.isFile ^java.io.File file-or-text)
                 (aero/read-config file-or-text))
               (when (string? file-or-text)
                 (aero/read-config (java.io.StringReader. file-or-text))))
         merged (merge default-config-values
                       (select-keys raw (keys default-config-values)))
         extras (into {}
                      (map (fn [[k v]] [k (str v)]))
                      (apply dissoc raw (keys default-config-values)))
         ent (cond-> (assoc merged :seon.config/key :seon.config/root)
               (seq extras) (assoc :seon.config/extra extras))]
     (if (and db (map? (:schema db)))
       (select-keys ent (keys (:schema db)))
       ent))))

(defn config-map
  "Returns `{:effective map :overrides #{...}}` for `agent-id` (or `:seon.config/root` when nil)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db [:maybe :keyword]] :map]]}
  ([db] (config-map db nil))
  ([db agent-id]
   (let [ks (vec (keys default-config-values))
         root-m (merge default-config-values
                       (when (and db (first (d/datoms db :avet :seon.config/key :seon.config/root)))
                         (dissoc (d/pull db ks [:seon.config/key :seon.config/root]) :db/id)))
         agent-m (when (and db agent-id (first (d/datoms db :avet :seon.agent/id agent-id)))
                   (dissoc (d/pull db ks [:seon.agent/id agent-id]) :db/id))]
     {:effective (merge root-m agent-m)
      :overrides (set (keys agent-m))})))

(defn data-file
  {:malli/schema [:=> [:cat :string] :string]}
  [path]
  (str path "/data.mdb"))

(defn- stored-id [cfg]
  (let [path (get-in cfg [:store :path])]
    (when (posix/exists? (data-file path))
      (let [s (konserve.store/connect-store (:store cfg) {:sync? true})
            id (-> (datahike.store/add-cache-and-handlers s (datahike.config/load-config cfg))
                   (datahike.writing/read-head :db)
                   (get-in [:config :store :id]))]
        (konserve.store/release-store (:store cfg) s {:sync? true})
        id))))

(defn store-cfg
  "Pure EDN Datahike config for an LMDB store at `path`."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string :map] :map]]}
  ([path] (store-cfg path {}))
  ([path {:keys [map-bytes cache-bytes]
          :or {map-bytes (long (:seon.config/store-map-bytes default-config-values))
               cache-bytes (* 64 1024 1024)}}]
   (let [cfg {:store {:backend :lmdb
                      :path path
                      :id (hasch/uuid path)
                      :config {:encoding {:serializer :FaslSerializer
                                          :compressor {:type :zstd}}}
                      :lmdb {:flags (bit-or MDB_NOSYNC MDB_SAFENOSYNC)
                             :map-size map-bytes
                             :sync-pages 4096}}
              :schema-flexibility :write
              :keep-history? true
              :crypto-hash? true
              :store-cache-bytes cache-bytes
              :writer {:backend :sync}
              :index-config {:branching-factor 32
              :diff-buf-size 64
              :edge-append? true
              :value-out-of-line-bytes 512}}]
     (if-let [id (stored-id cfg)]
       (assoc-in cfg [:store :id] id)
       cfg))))

(defn child-cfg
  {:malli/schema [:=> [:cat :map [:maybe :int]] :map], :cost/ticks 200000, :cost/bytes 131072}
  [cfg mb]
  (let [min-mb (long (get default-config-values :seon.config/child-cache-mb 32))]
    (assoc cfg :store-cache-bytes (* (max min-mb (long (or mb min-mb))) 1024 1024))))

(defn attach-ids!
  {:malli/schema [:=> [:cat :seon.host/conn] :seon.host/conn]}
  [conn]
  (let [db @conn
        {:keys [path id]} (get-in db [:config :store])
        ids-path (str path "/ids")
        mdb-path (data-file path)
        [page errno] (c-ids-open ids-path)]
    (when (zero? (ffi/address page))
      (throw (ex-info (str "Failed to mmap store ids page " ids-path ": " (ffi/errno-message errno))
                      {:path ids-path :errno errno})))
    (let [store (:store db)
          ids (reify datahike.ids/IIds
                (-take! [_ slot-kind] (c-ids-take page (if (= :eid slot-kind) 0 1)))
                (-reach! [_ slot-kind n] (c-ids-reach page (if (= :eid slot-kind) 0 1) n) nil))]
      (if (= 1 (c-ids-trusted page ids-path mdb-path))
        (datahike.writing/set-ids! store id ids {:past-heads? true})
        (do (datahike.writing/set-ids! store id ids)
            (c-ids-stamp page ids-path mdb-path))))
    conn))

(defn ^:seon.flow/edge sync!
  {:malli/schema [:=> [:cat :map] :nil]}
  [cfg]
  (let [path (get-in cfg [:store :path])
        lmdb-opts (get-in cfg [:store :lmdb])]
    (lmdb-ffi/sync! (klmdb/open-env! path lmdb-opts))
    nil))

(defn branch?
  {:malli/schema [:=> [:cat [:or :seon.host/conn :seon.host/db :map] :keyword] :boolean]}
  [conn-or-db branch]
  (let [s (if (instance? clojure.lang.IDeref conn-or-db) (:store @conn-or-db) (or (:store conn-or-db) conn-or-db))]
    (boolean (k/exists? s branch {:sync? true}))))

(defn branch!
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :keyword] :keyword]}
  [conn from-branch new-branch]
  (when-not (branch? conn new-branch)
    (d/branch! conn from-branch new-branch))
  new-branch)

(defn db-at
  {:malli/schema [:=> [:cat [:or :seon.host/conn :seon.host/db :map] :map] :any]}
  [store-or-conn {:keys [commit branch tx]}]
  (let [s (if (instance? clojure.lang.IDeref store-or-conn) (:store @store-or-conn) store-or-conn)]
    (cond
      commit (d/commit-as-db s commit)
      :else  (let [base (d/branch-as-db s (or branch :main))]
               (if tx (d/as-of base tx) base)))))

(defn point
  {:malli/schema [:=> [:cat :seon.host/db] :seon.store/point]}
  [db]
  {:commit (d/commit-id db)
   :tx (:max-tx db)
   :branch (get-in db [:config :branch] :main)})

(defn merge-base-db
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :keyword] :any]}
  [conn branch-a branch-b]
  (when-let [cid (d/merge-base conn branch-a branch-b)]
    (d/commit-as-db conn cid)))

(defn agent-seen-message-ids
  "Returns the set of `:seon.agent.message/id` UUIDs ever seen by `agent-id` on `branch-db`,
   reading Datahike's native `(d/history branch-db)` so retracted/compacted evals never replay
   already-processed inbox messages. Respects `d/as-of` time points automatically."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:set :any]]}
  [branch-db agent-id]
  (if (and branch-db agent-id
           (contains? (:schema branch-db) :seon.agent.eval/agent)
           (contains? (:schema branch-db) :seon.agent.eval/seen-messages))
    (let [hdb (d/history branch-db)
          eids (into #{} (comp (filter :added) (map :e))
                     (d/datoms hdb :avet :seon.agent.eval/agent agent-id))]
      (if (empty? eids)
        #{}
        (into #{}
              (comp (filter :added)
                    (filter #(contains? eids (:e %)))
                    (map :v))
              (d/datoms hdb :aevt :seon.agent.eval/seen-messages))))
    #{}))

(defn agent-eval-history-stats
  "Returns `{:eval-count n :max-turn t}` across Datahike's full `(d/history branch-db)` for
   `agent-id`, keeping turn numbers and `result/e<n>` handles strictly monotonic across compactions."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :map]}
  [branch-db agent-id]
  (if (and branch-db agent-id (contains? (:schema branch-db) :seon.agent.eval/agent))
    (let [hdb (d/history branch-db)
          eids (into #{} (comp (filter :added) (map :e))
                     (d/datoms hdb :avet :seon.agent.eval/agent agent-id))
          max-t (reduce (fn [m eid]
                          (reduce (fn [m2 dt]
                                    (if (:added dt) (max m2 (long (:v dt))) m2))
                                  m
                                  (d/datoms hdb :eavt eid :seon.agent.eval/turn)))
                        0
                        eids)]
      {:eval-count (count eids) :max-turn max-t})
    {:eval-count 0 :max-turn 0}))

(defn agent-historical-evals
  "Reconstructs every eval ever recorded for `agent-id` on `branch-db` via `(d/history branch-db)`,
   tagging `:seon.agent.eval/tx` (creation tx, usable with `d/as-of`) and `:seon.agent.eval/retracted-tx`
   (non-nil when retracted by session compaction)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:vector :map]]}
  [branch-db agent-id]
  (if (and branch-db agent-id (contains? (:schema branch-db) :seon.agent.eval/agent))
    (let [hdb (d/history branch-db)
          by-eid (reduce (fn [acc dt]
                           (let [eid (:e dt)]
                             (if (:added dt)
                               (assoc-in acc [eid :tx] (:tx dt))
                               (assoc-in acc [eid :retracted-tx] (:tx dt)))))
                         {}
                         (d/datoms hdb :avet :seon.agent.eval/agent agent-id))]
      (->> by-eid
           (map (fn [[eid {:keys [tx retracted-tx]}]]
                  (let [base (reduce (fn [m dt]
                                       (if (:added dt)
                                         (let [a (:a dt) v (:v dt)]
                                           (if (= a :seon.agent.eval/seen-messages)
                                             (update m a (fnil conj #{}) v)
                                             (assoc m a v)))
                                         m))
                                     {:db/id eid
                                      :seon.agent.eval/tx tx}
                                     (d/datoms hdb :eavt eid))]
                    (cond-> base
                      retracted-tx (assoc :seon.agent.eval/retracted-tx retracted-tx)))))
           (sort-by (juxt #(or (:seon.agent.eval/turn %) 0)
                          #(or (:seon.agent.eval/call-index %) 0)
                          #(or (:seon.agent.eval/tx %) 0)))
           vec))
    []))

(defn after-fork!
  {:malli/schema [:=> [:cat :map] :any]}
  [cfg]
  (swap! @#'konserve.jolt.lmdb/envs dissoc (get-in cfg [:store :path]))
  (datahike.connections/after-fork!))

(defn- install-missing-schema-and-seeds!
  ([conn] (install-missing-schema-and-seeds! conn nil))
  ([conn repo-dir]
   (let [{:keys [declarations forms registry schema-ast-edges]} @schema/base-registry
         have-dh (:schema @conn)
         missing-dh (schema/sync-dh-decls have-dh declarations)
         _ (when (seq missing-dh)
             (d/transact conn {:tx-data missing-dh}))
         db @conn
         have-keys (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
         missing-forms (into {} (remove #(contains? have-keys (key %))) forms)
         missing-ents (when (seq missing-forms)
                        (schema/enrich-schema-entities-tx db missing-forms registry schema-ast-edges))
         root-ent (when-not (first (d/datoms db :avet :seon.config/key :seon.config/root))
                    (let [cfg-file (io/file (or repo-dir checkout-root) "resources/seon/config/default.edn")]
                      (if (.isFile cfg-file)
                        (read-aero-config-tx db cfg-file)
                        (select-keys (assoc default-config-values :seon.config/key :seon.config/root)
                                     (keys (:schema db))))))
         seed-tx (cond-> (vec missing-ents) root-ent (conj root-ent))]
     (when (seq seed-tx)
       (d/transact conn {:tx-data seed-tx}))
     (boolean (or (seq missing-dh) (seq seed-tx))))))

(defn ^:seon.flow/edge close!
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [conn cfg]}]
  (when conn
    (try (sync! cfg) (catch Throwable _))
    (d/release conn)
    (when cfg
      (try (klmdb/close-env! (get-in cfg [:store :path])) (catch Throwable _)))))

(defn lru-cache
  "Creates a bounded `clojure.core.cache.wrapped` LRU cache atom."
  {:malli/schema [:=> [:cat pos-int?] :any]}
  [threshold]
  (cw/lru-cache-factory {} :threshold (long threshold)))

(defn lru-get!
"Hot-reload-safe bounded LRU lookup-or-miss that composes transparently with `datahike.reads/with-reads`:\n   when `datahike.index.interface/*reads*` is bound, cached entries retain and replay the exact index\n   read ranges of `compute-fn` so incremental UI walks never lose reactive dependencies on cache hits."
  {:malli/schema [:=> [:cat :any :int :any :seon.host/fn] :any]}
[cache-atom threshold k compute-fn]
(when-not (satisfies? cache/CacheProtocol @cache-atom)
  (reset!
    cache-atom
    (cache/lru-cache-factory {} :threshold (long threshold))))
(if-let [outer di/*reads*]
(let [entry (cw/lookup-or-miss
              cache-atom
              k
              (fn [_]
                (let [[val rs] (reads/with-reads compute-fn)]
                  {:seon.store/lru-val val,
                   :seon.store/lru-reads rs})))]
  (if (and (map? entry) (contains? entry :seon.store/lru-val))
    (do
      (when (seq (:seon.store/lru-reads entry))
        (swap! outer into (:seon.store/lru-reads entry)))
      (:seon.store/lru-val entry))
    (let [[val rs] (reads/with-reads compute-fn)]
      (swap!
        cache-atom
        cache/miss
        k
        {:seon.store/lru-val val, :seon.store/lru-reads rs})
      val)))
(let [entry (cw/lookup-or-miss cache-atom k (fn [_] (compute-fn)))]
  (if (and (map? entry) (contains? entry :seon.store/lru-val))
    (:seon.store/lru-val entry)
    entry))))

(defn ^:seon.flow/edge rm-rf!
  "Recursively deletes a file or directory tree via symlink-safe `jolt.fs/delete-tree` (nil-safe, accepts File, Path, or path String)."
  {:malli/schema [:=> [:cat :any] :any]}
  [f]
  (when (or (instance? java.io.File f)
            (instance? java.nio.file.Path f)
            (and (string? f) (boolean (re-find #"\S" f))))
    (when (fs/exists? f)
      (fs/delete-tree f))))

(def ^:dynamic *tx-meta*
  "Optional map of `:seon.tx/*` metadata automatically stamped onto `datomic.tx` at `transact-checked`."
  nil)

(defn- stamp-tx-meta [db tx-data]
  (let [m *tx-meta*
        schema (:schema db)]
    (if (and (map? m) (seq m) (map? schema) (sequential? tx-data) (seq tx-data)
             (not (every? #(and (map? %) (contains? % :db/ident)) tx-data)))
      (let [allowed (reduce-kv (fn [acc k v]
                                 (if (and (some? v) (contains? schema k)) (assoc acc k v) acc))
                               {}
                               m)]
        (if (seq allowed)
          (conj (vec tx-data) (assoc allowed :db/id :db/current-tx))
          tx-data))
      tx-data)))

(defonce ^:private ^:seon.flow/edge lmdb-tx-state (atom {:active-writes 0 :aborted-writes 0}))

(defn ^:seon.flow/edge active-lmdb-write-txns
  "Returns the count of currently open LMDB write transactions on this process."
  {:malli/schema [:=> [:cat] :int]
   :cost/ticks 50000}
  []
  (long (:active-writes @lmdb-tx-state 0)))

(defn ^:seon.flow/edge aborted-lmdb-write-txns
  "Returns the cumulative count of LMDB write transactions aborted via unwind/error in this process."
  {:malli/schema [:=> [:cat] :int]
   :cost/ticks 50000}
  []
  (long (:aborted-writes @lmdb-tx-state 0)))

(def ^:private ^:seon.flow/edge with-masked-lmdb-txn-fn
(scm/eval-string
 "(lambda (try-lk unlock-lk bfn body cfn rcfn afn)
    (let ((lv (jolt-locks-held)) (lk #f) (t #f) (fin #f))
      (dynamic-wind jolt-finally-in
        (lambda ()
          (jolt-locks-enter!)
          (let ((got (if try-lk (try-lk) #t)))
            (if (not got)
                (begin (jolt-locks-exit!) #f)
                (begin
                  (when try-lk (set! lk #t))
                  (let ((x (bfn))) (set! t x) (jolt-locks-exit!))
                  (if (not t) #f
                      (let ((r (body t)))
                        (if cfn
                            (let ((rc #f))
                              (jolt-locks-enter!) (set! fin #t) (set! rc (cfn t)) (jolt-locks-exit!)
                              (when rcfn (rcfn rc)) r)
                            r)))))))
        (lambda ()
          (when (fx>? (jolt-locks-held) lv) (jolt-locks-exit!))
          (when (and t (not fin))
            (jolt-locks-enter!) (set! fin #t)
            (dynamic-wind jolt-finally-in
              (lambda () (afn t))
              (lambda () (when (fx>? (jolt-locks-held) lv) (jolt-locks-exit!)))))
          (when lk
            (jolt-locks-enter!) (set! lk #f)
            (dynamic-wind jolt-finally-in
              (lambda () (unlock-lk))
              (lambda () (when (fx>? (jolt-locks-held) lv) (jolt-locks-exit!))))))))) "))

(defn ^:seon.flow/edge install-interrupt-safe-lmdb!
  "Idempotently wraps `konserve.jolt.lmdb-ffi/{transact,write-txn,read-txn}` with masked `tryLock`/`dynamic-wind` unwind abort, `MDB_MAP_RESIZED` adoption, and `MDB_MAP_FULL` automatic 2x mapsize growth so `run-interruptible` never leaks an LMDB txn or lock and stores scale to available disk."
  {:malli/schema [:=> [:cat] :boolean]
   :cost/ticks 500000}
  []
  (let [v-tr (find-var 'konserve.jolt.lmdb-ffi/transact)
        v-wt (find-var 'konserve.jolt.lmdb-ffi/write-txn)
        v-rt (find-var 'konserve.jolt.lmdb-ffi/read-txn)
        v-br (find-var 'konserve.jolt.lmdb-ffi/begin-raw)
        v-bg (find-var 'konserve.jolt.lmdb-ffi/begin)
        v-ad (find-var 'konserve.jolt.lmdb-ffi/adopt-mapsize-unlocked!)
        v-em (find-var 'konserve.jolt.lmdb-ffi/env-map-bytes)
        v-sm (find-var 'konserve.jolt.lmdb-ffi/mdb-env-set-mapsize)
        v-le (find-var 'konserve.jolt.lmdb-ffi/live-env)
        v-ck (find-var 'konserve.jolt.lmdb-ffi/check)
        v-cm (find-var 'konserve.jolt.lmdb-ffi/mdb-txn-commit)
        v-ab (find-var 'konserve.jolt.lmdb-ffi/mdb-txn-abort)]
    (if (and v-tr v-wt v-rt (or v-br v-bg) v-le v-ck v-cm v-ab)
      (do
        (when (not= 3 (:seon.store/interrupt-safe-v (meta v-tr)))
          (let [br-f (when v-br @v-br) bg-f (when v-bg @v-bg) ad-f (when v-ad @v-ad)
                em-f (when v-em @v-em) sm-f (when v-sm @v-sm)
                live-f @v-le check-f @v-ck commit-f @v-cm abort-f @v-ab
                rd-flags lmdb-ffi/MDB_RDONLY
                resized-rc (or (some-> (find-var 'konserve.jolt.lmdb-ffi/MDB_MAP_RESIZED) deref) -30785)
                full-rc (or (some-> (find-var 'konserve.jolt.lmdb-ffi/MDB_MAP_FULL) deref) -30792)
                map-full? (fn [e] (let [d (ex-data e)] (and (= :lmdb-error (:type d)) (= full-rc (:rc d)))))
                adopt-masked!
                (fn [db ^java.util.concurrent.locks.ReentrantReadWriteLock rw]
                  (let [wl (.writeLock rw) done? (volatile! false)]
                    (loop []
                      (with-masked-lmdb-txn-fn
                        #(.tryLock wl) #(.unlock wl)
                        (fn [] (ad-f db) (vreset! done? true) false)
                        false false false false)
                      (when-not @done? (Thread/yield) (recur)))))
                grow-masked!
                (fn [db ^java.util.concurrent.locks.ReentrantReadWriteLock rw seen]
                  (let [wl (when rw (.writeLock rw)) done? (volatile! false)
                        do-grow! (fn []
                                   (let [env (live-f db)
                                         before (long (em-f env))]
                                     (when (<= before (long seen))
                                       (when ad-f (ad-f db))
                                       (let [adopted (long (em-f env))]
                                         (when (<= adopted (long seen))
                                           (let [base (max before adopted (long seen))
                                                 pg 16384
                                                 n (max (* 2 base) (+ base (* 1024 1024)))
                                                 target (* (quot (+ n (dec pg)) pg) pg)]
                                             (check-f "env_set_mapsize" (sm-f env target))))))))]
                    (if wl
                      (loop []
                        (with-masked-lmdb-txn-fn
                          #(.tryLock wl) #(.unlock wl)
                          (fn [] (do-grow!) (vreset! done? true) false)
                          false false false false)
                        (when-not @done? (Thread/yield) (recur)))
                      (do-grow!))))
                run-txn!
                (fn [db flags write? body-f]
                  (let [^java.util.concurrent.locks.ReentrantReadWriteLock rw (:rw-lock db)
                        rl (when rw (.readLock rw))
                        state (volatile! nil)
                        seen-v (volatile! 0)]
                    (loop [tries 0 grows 0]
                      (vreset! state nil)
                      (let [res (try
                                  (with-masked-lmdb-txn-fn
                                    (if rl (fn [] (if (.tryLock rl) true (do (vreset! state :contended) false))) false)
                                    (if rl #(.unlock rl) false)
                                    (fn []
                                      (let [env (live-f db)]
                                        (when (and write? em-f) (vreset! seen-v (long (em-f env))))
                                        (if br-f
                                          (let [[rc t] (br-f env flags)]
                                            (cond
                                              (zero? rc) (do (when write? (swap! lmdb-tx-state update :active-writes inc)) t)
                                              (and (= rc resized-rc) (< tries 2) rw ad-f (= 1 (.getReadHoldCount rw)))
                                              (do (vreset! state :resized) false)
                                              :else (do (check-f "txn_begin" rc) t)))
                                          (let [t (bg-f env flags)]
                                            (when write? (swap! lmdb-tx-state update :active-writes inc)) t))))
                                    body-f
                                    (if write? (fn [t] (swap! lmdb-tx-state update :active-writes #(max 0 (dec %))) (commit-f t)) false)
                                    (if write? (fn [rc] (check-f "txn_commit" rc)) false)
                                    (if write?
                                      (fn [t] (try (abort-f t) (finally (swap! lmdb-tx-state #(-> % (update :active-writes (fn [n] (max 0 (dec n)))) (update :aborted-writes inc))))))
                                      (fn [t] (abort-f t))))
                                  (catch Throwable e
                                    (if (and write? em-f sm-f (map-full? e) (< grows 16)
                                             (or (nil? rw) (zero? (.getReadHoldCount rw))))
                                      (do (vreset! state :full) nil)
                                      (throw e))))]
                        (case @state
                          :contended (do (Thread/yield) (recur tries grows))
                          :resized (do (adopt-masked! db rw) (recur (inc tries) grows))
                          :full (do (grow-masked! db rw @seen-v) (recur 0 (inc grows)))
                          res)))))]
            (alter-var-root v-tr (constantly (fn [db f] (if lmdb-ffi/*txn* (f) (run-txn! db 0 true (fn [txn] (binding [lmdb-ffi/*txn* txn] (f))))))))
            (alter-meta! v-tr assoc :seon.store/interrupt-safe? true :seon.store/interrupt-safe-v 3)
            (alter-var-root v-wt (constantly (fn [db f] (if-some [t lmdb-ffi/*txn*] (f t) (run-txn! db 0 true f)))))
            (alter-meta! v-wt assoc :seon.store/interrupt-safe? true :seon.store/interrupt-safe-v 3)
            (alter-var-root v-rt (constantly (fn [db f] (if-some [t lmdb-ffi/*txn*] (f t) (run-txn! db rd-flags false f)))))
            (alter-meta! v-rt assoc :seon.store/interrupt-safe? true :seon.store/interrupt-safe-v 3)))
        true)
      false)))

(defn fast-registry-for-db
"Delegates to `seon.schema/registry-for-db` (single canonical registry lookup with `:seon.schema/doc` normalization)."
{:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
 :cost/ticks 15000000}
[db]
(if (nil? db)
  @schema/base-registry
  (schema/registry-for-db db)))

(defn validate-tx-data!
  "Rail at the write seam: validates every datom against the branch's live Malli registry."
  {:malli/schema [:=> [:cat :seon.host/db :any] :any]}
  [db tx-data]
  (when (and (seq tx-data)
           (some (fn [op]
                   (or (and (map? op) (not (and (contains? op :db/ident) (contains? op :db/valueType))))
                       (and (vector? op) (= :db/add (first op)) (>= (count op) 4))))
                 tx-data))
  (let [reg-info (fast-registry-for-db db)
        local-v (volatile! {})
        get-validator (fn [a]
                        (let [hit (get @local-v a ::miss)]
                          (if (identical? hit ::miss)
                            (let [res (or (schema/attr-validator-for reg-info a) ::none)]
                              (vswap! local-v assoc a res)
                              (when-not (identical? res ::none) res))
                            (when-not (identical? hit ::none) hit))))
        check-kv! (fn [a v]
                    (when (and (qualified-keyword? a) (not= "db" (namespace a)) (some? v))
                      (when-let [{:keys [schema valid?]} (get-validator a)]
                        (when-not (valid? v)
                          (throw (ex-info (str "Schema rail rejected " a ": " (pr-str v))
                                          {:error :seon.store/invalid-datom
                                           :attribute a
                                           :value v
                                           :explain (m/explain schema v)}))))))]
    (doseq [op tx-data]
      (cond
        (and (map? op) (contains? op :db/ident) (contains? op :db/valueType))
        nil

        (map? op)
        (doseq [[a v] op :when (not= :db/id a)]
          (check-kv! a v))

        (and (vector? op) (= :db/add (first op)) (>= (count op) 4))
        (check-kv! (nth op 2) (nth op 3)))))))

(defn- transact-checked [db arg-map]
  (let [raw-tx (if (map? arg-map) (:tx-data arg-map) arg-map)
      tx-data (stamp-tx-meta db raw-tx)
      eff-arg (if (map? arg-map) (assoc arg-map :tx-data tx-data) tx-data)]
  (validate-tx-data! db tx-data)
  (datahike.writing/transact! db eff-arg)))

(defn connect
  "Opens a Datahike connection to `branch` using `cfg`, enforcing Malli write rails and shared IDs."
  {:malli/schema [:function [:=> [:cat :map] :seon.host/conn] [:=> [:cat :map :keyword] :seon.host/conn]]}
  ([cfg] (connect cfg (:branch cfg :main)))
  ([cfg branch]
   (-> (d/connect (-> cfg
                      (assoc :branch (do (install-interrupt-safe-lmdb!) branch))
                      (assoc-in [:writer :write-fn-map] {'transact! #'transact-checked})))
       attach-ids!)))

(defn open!
  "Opens (or atomically creates) the LMDB store at `path`, installing the Malli-derived Datahike
   schema and seeding all co-located `:seon.schema/key` entities and `:seon.config/root`."
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string :map] :map]]}
  ([path] (open! path {}))
  ([path opts]
   (let [_ (install-interrupt-safe-lmdb!) derived-dh (:declarations @schema/base-registry)
         created?
         (let [seed-src (when-let [ss (:seed-store opts)]
                        (let [f (io/file (data-file ss))]
                          (when (.isFile f) f)))]
         (cond
           (posix/exists? (data-file path))
           false

           seed-src
           (let [tmp (str path ".seeding-" (random-uuid))
                 tdir (io/file tmp)]
             (fs/create-dirs tdir)
             (io/copy seed-src (io/file tdir "data.mdb"))
             (let [[rc errno] (posix/c-rename tmp path)]
               (when-not (or (zero? rc) (posix/exists? (data-file path)))
                 (throw (ex-info (str "Could not place seeded store at " path ": " (ffi/errno-message errno))
                                 {:path path :seed-store (:seed-store opts) :errno errno}))))
             true)

           :else
           (let [tmp (str path ".making-" (random-uuid))
                 tcfg (store-cfg tmp opts)]
             (try
               (d/create-database tcfg)
               (let [c (d/connect tcfg)]
                 (try
                   (d/transact c {:tx-data derived-dh})
                   (d/branch! c :db :main)
                   (sync! tcfg)
                   (finally (d/release c))))
               (let [[rc errno] (posix/c-rename tmp path)]
                 (when-not (or (zero? rc) (posix/exists? (data-file path)))
                   (throw (ex-info (str "Could not place store at " path ": " (ffi/errno-message errno))
                                   {:path path :errno errno})))
                 (zero? rc))
               (finally
                 (when (posix/exists? tmp)
                   (try (klmdb/close-env! tmp) (catch Throwable _))
                   (fs/delete-tree tmp)))))))]
     (let [cfg (assoc (store-cfg path opts) :branch :main)
           _ (when-not created?
               (let [sc (d/connect (dissoc cfg :branch))]
                 (when-not (branch? sc :main)
                   (d/branch! sc :db :main))
                 (d/release sc)))
           main-conn (-> (d/connect (-> cfg
                                        (assoc-in [:writer :streaming?] true)
                                        (assoc-in [:writer :write-fn-map] {'transact! transact-checked})))
                         attach-ids!)
           changed? (install-missing-schema-and-seeds! main-conn (:repo-dir opts))]
       (when (or created? changed?)
         (sync! cfg))
       {:cfg cfg :conn main-conn :created? created?}))))

(def ^:private ^:seon.flow/edge _lmdb-interrupt-guard (install-interrupt-safe-lmdb!))

(defn store-id
 "Returns the Datahike store `:id` (UUID) for `db`, or nil when absent."
 {:malli/schema [:=> [:cat :any] :any]
  :cost/ticks 50000}
 [db]
 (get-in db [:config :store :id]))

(defn live-cid
 "Returns `(d/commit-id db)` for a live `datahike.db.DB`, else nil."
 {:malli/schema [:=> [:cat :any] :any]
  :cost/ticks 50000}
 [db]
 (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

(defn db-cache-sig
 "Stable, cross-store-safe cache key for live `DB` / `AsOfDB` handles:
  `[store-id branch commit-id max-tx time-point]` (nil for un-cacheable values).
  Centralizing here guarantees keys never collide across distinct LMDB stores or branches."
 {:malli/schema [:=> [:cat :any] :any]
  :cost/ticks 50000}
 [db]
 (cond
  (instance? datahike.db.DB db)
  [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)
   (d/commit-id db) (:max-tx db) nil]

  (instance? datahike.db.AsOfDB db)
  (when-let [orig (:origin-db db)]
   [(get-in orig [:config :store :id]) (get-in orig [:config :branch] :main)
    (when (instance? datahike.db.DB orig) (d/commit-id orig))
    (:max-tx orig) (:time-point db)])

  :else nil))

(defn cache-key
 "Builds a cross-store-safe LRU cache key from `db`'s stable signature plus `extra`
  (e.g. `[nsym max-n]`). Returns nil when `db` is not cacheable so callers fall back to
  computing. Prefer this over hand-rolled `[store-id ...]` vectors to avoid collisions
  across distinct stores/branches."
 {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat :any [:sequential :any]] :any]]
  :cost/ticks 50000}
 ([db] (db-cache-sig db))
 ([db extra] (when-let [s (db-cache-sig db)] (into s extra))))

(defonce ^:private ^:seon.flow/edge head-db-cache (lru-cache 16))

(defn head
  {:malli/schema [:=> [:cat [:or :seon.host/conn :seon.host/db :map] :keyword] :any]}
  [store-or-conn branch]
  (let [s (if (instance? clojure.lang.IDeref store-or-conn)
            (:store @store-or-conn)
            (or (:store store-or-conn) store-or-conn))
        raw-head (try (k/get s branch nil {:sync? true}) (catch Throwable _ nil))
        cid (get-in raw-head [:meta :datahike/commit-id])]
    (if (and raw-head cid)
      (lru-get! head-db-cache 16 [s branch cid]
                #(datahike.writing/stored->db
                  (datahike.writing/resolve-head s branch raw-head {:sync? true}) s))
      (d/branch-as-db s branch))))

(defn fork-parent-target
  "Resolves branch-to-branch merge target for `branch` / `agent-id` honoring `:seon.agent/merge-target` (`:main`/`:none` -> nil, explicit branch/agent ref -> target, omitted -> parent branch of `:seon.agent/forked-from`)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword [:maybe :keyword]] [:maybe :map]] :cost/ticks 1000000}
  [main-db branch agent-id]
  (let [dh (or (:schema main-db) {})]
    (when (and (contains? dh :seon.agent/forked-from) (contains? dh :seon.agent/id))
      (let [pat (cond-> [:seon.agent/id :seon.agent/branch :seon.agent/forked-from]
                  (contains? dh :seon.agent/merge-target) (conj :seon.agent/merge-target))
            ag-by-id (when agent-id
                       (let [a (d/pull main-db pat [:seon.agent/id agent-id])]
                         (when (= branch (:seon.agent/branch a)) a)))
            ag (or ag-by-id
                   (when (and branch (not= branch :main) (contains? dh :seon.agent/branch))
                     (some #(let [a (d/pull main-db pat (:e %))]
                              (when (= branch (:seon.agent/branch a)) a))
                           (d/datoms main-db :aevt :seon.agent/forked-from))))
            mt (:seon.agent/merge-target ag)]
        (cond
          (or (= mt :main) (= mt :none)) nil

          (some? mt)
          (let [ta (cond
                     (integer? mt) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) mt)
                     (and (map? mt) (integer? (:db/id mt))) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) (:db/id mt))
                     (and (vector? mt) (= 2 (count mt)) (= :seon.agent/id (first mt)))
                     (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) mt)
                     (qualified-keyword? mt)
                     (let [a (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) [:seon.agent/id mt])]
                       (if (:seon.agent/id a) a {:seon.agent/branch mt}))
                     :else nil)
                tb (:seon.agent/branch ta)]
            (when (and (:seon.agent/id ag) tb (not= tb :main) (not= tb branch))
              {:agent-id (:seon.agent/id ag) :target-id (:seon.agent/id ta) :target-branch tb}))

          :else
          (let [feid (let [f (:seon.agent/forked-from ag)] (if (map? f) (:db/id f) f))
                pa (when (integer? feid) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) feid))
                tb (:seon.agent/branch pa)]
            (when (and (:seon.agent/id ag) (:seon.agent/id pa) tb (not= tb :main) (not= tb branch))
              {:agent-id (:seon.agent/id ag) :target-id (:seon.agent/id pa) :target-branch tb})))))))

(defn sha256-hex
  "Deterministic lowercase SHA-256 hex digest of UTF-8 string `s` (full 64 chars, or first `n` chars)."
  {:malli/schema [:=> {:examples [[["hello"] "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"]
                                  [["hello" 16] "2cf24dba5fb0a30e"]]}
                  [:cat :string [:? [:int {:min 1 :max 64}]]] :string]
   :cost/ticks 200000
   :cost/bytes 131072}
  ([^String s] (sha256-hex s 64))
  ([^String s n]
   (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))
         nb (min (alength bs) (quot (inc (long n)) 2))
         sb (StringBuilder. (* 2 nb))]
     (dotimes [i nb]
       (let [b (bit-and 0xff (aget bs i))]
         (when (< b 16) (.append sb \0))
         (.append sb (Integer/toHexString b))))
     (let [hx (.toString sb)]
       (if (< (long n) (count hx)) (subs hx 0 (long n)) hx)))))

(defn db-schema
  "Returns the Datahike schema map for `db` (handling `DB`, `AsOfDB`, `HistoricalDB`, or plain map), else `{}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 100000}
  [db]
  (cond
    (instance? datahike.db.interface.ISearch db) (or (:schema db) (d/schema db) {})
    (and (map? db) (map? (:schema db))) (:schema db)
    :else {}))

(def ^:dynamic *capturing-agent-eval-reads?*
  "Bound true ONLY inside `seon.code/eval-single-form` while evaluating an agent expression;
   allows internal runtime plumbing to opt out of agent touch capture without affecting UI/rule `reads/with-reads` reactivity."
  false)

(defn without-agent-eval-reads
  "Invokes 0-arg `f`, temporarily binding `di/*reads*` nil ONLY when `*capturing-agent-eval-reads?*` is true.
   Preserves 100% of UI walk and Datalog rule `datahike.reads` invalidation while eliminating self-inflicted plumbing noise."
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] :any]
   :cost/ticks 100000}
  [f]
  (if *capturing-agent-eval-reads?*
    (binding [di/*reads* nil] (f))
    (f)))

(defn config-val
  "Reads config key `k` from `db`, checking `agent-id`'s entity first (if given) and falling back
   to `:seon.config/key :seon.config/root`, then `default-config-values`. 1 indexed pull (~20 µs)!"
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword] :any]
                  [:=> [:cat :seon.host/db [:or :keyword :nil] :qualified-keyword] :any]]}
  ([db k] (config-val db nil k))
  ([db agent-id k]
   (without-agent-eval-reads
   (fn []
     (let [agent-v (when (and db agent-id (first (d/datoms db :avet :seon.agent/id agent-id)))
                     (get (d/pull db [k] [:seon.agent/id agent-id]) k))]
       (if (some? agent-v)
         agent-v
         (let [root-v (when (and db (first (d/datoms db :avet :seon.config/key :seon.config/root)))
                        (get (d/pull db [k] [:seon.config/key :seon.config/root]) k))]
           (if (some? root-v)
             root-v
             (get default-config-values k)))))))))

(defn ^:seon.flow/edge prune-merged-branches!
  "Deletes already-merged, non-protected, non-active branches beyond `:seon.config/gc-keep-merged-branches` so old branches never pin LMDB storage indefinitely."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn] :map]
                  [:=> [:cat :seon.host/conn nat-int?] :map]]
   :cost/ticks 15000000}
  ([conn]
   (prune-merged-branches! conn
     (long (or (config-val @conn nil :seon.config/gc-keep-merged-branches)
               (:seon.config/gc-keep-merged-branches default-config-values)))))
  ([conn keep-n]
   (let [mdb @conn
       protected #{:db :main :branch/repl}
       all-brs (set (d/branches mdb))
       ags (mapv #(d/pull mdb '[:db/id :seon.agent/branch :seon.agent/merged-tx :seon.agent/archived?] (:e %))
                 (d/datoms mdb :aevt :seon.agent/id))
       active-brs (into #{} (keep (fn [ag]
                                    (when (and (:seon.agent/branch ag)
                                               (nil? (:seon.agent/merged-tx ag))
                                               (not (:seon.agent/archived? ag)))
                                      (:seon.agent/branch ag))))
                        ags)
       merged (->> ags
                   (keep (fn [ag]
                           (let [b (:seon.agent/branch ag)
                                 mtx (:seon.agent/merged-tx ag)]
                             (when (and b (all-brs b) (not (protected b)) (not (active-brs b)) (or mtx (:seon.agent/archived? ag)))
                             {:branch b :merged-tx (long (or mtx 0))}))))
                   (group-by :branch)
                   (map (fn [[b xs]] {:branch b :merged-tx (apply max (map :merged-tx xs))}))
                   (sort-by :merged-tx >)
                   vec)
       to-del (mapv :branch (drop (long keep-n) merged))
       to-del-set (set to-del)
       archive-tx (vec (keep (fn [ag]
                               (when (and (contains? to-del-set (:seon.agent/branch ag))
                                          (not (:seon.agent/archived? ag)))
                                 {:db/id (:db/id ag) :seon.agent/archived? true}))
                             ags))
       _ (when (seq archive-tx)
           (d/transact conn archive-tx))
       deleted (volatile! 0)]
   (doseq [b to-del]
     (try (d/delete-branch! conn b) (vswap! deleted inc) (catch Throwable _ nil)))
   {:ok? true :deleted @deleted :archived (count archive-tx) :kept (min (count merged) (long keep-n))})))

(defn ^:seon.flow/edge gc-store!
  "Prunes merged branches beyond `:seon.config/gc-keep-merged-branches` and runs `d/gc-storage` with cutoff `:seon.config/gc-history-hours` ago (preserving active branch merge-bases)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn] :map]
                  [:=> [:cat :seon.host/conn [:maybe :map]] :map]]
   :cost/ticks 25000000}
  ([conn] (gc-store! conn nil))
  ([conn opts]
   (let [mdb @conn
         keep-n (long (or (:keep opts)
                          (config-val mdb nil :seon.config/gc-keep-merged-branches)
                          (:seon.config/gc-keep-merged-branches default-config-values)))
         hrs (long (or (:history-hours opts)
                       (config-val mdb nil :seon.config/gc-history-hours)
                       (:seon.config/gc-history-hours default-config-values)))
         pruned (prune-merged-branches! conn keep-n)
         cutoff (java.util.Date. (- (System/currentTimeMillis) (* hrs 3600 1000)))
         swept @(d/gc-storage conn cutoff)
         n-swept (if (coll? swept) (count swept) swept)]
     {:ok? true :pruned pruned :swept n-swept :history-hours hrs})))