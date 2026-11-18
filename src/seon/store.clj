(ns seon.store
  "Durable multi-process Datahike store on LMDB with shared atomic entity/tx IDs (native/ids.c)
   and Malli schema rails enforced at every transaction.
   Every point in the system across branches and time is addressed by an immutable Datahike commit-id
   or [branch tx], so the entire system or any agent runtime can rebuild from any commit."
  (:require [datahike.api :as d]
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
            [malli.core :as m]))

;; --- Native shared ID page (target/lib/libprobe.dylib -> native/ids.c) ---

(def checkout-root
  (or (System/getenv "SEON_CHECKOUT")
      (System/getProperty "user.dir")))

(ffi/load-library)
(ffi/load-library (str checkout-root "/target/lib/libprobe.dylib"))

(ffi/defcfn c-ids-open "seon_ids_open" [:string] :pointer {:capture-native-error true})
(ffi/defcfn c-ids-take "seon_ids_take" [:pointer :int] :uint64)
(ffi/defcfn c-ids-reach "seon_ids_reach" [:pointer :int :uint64] :void)
(ffi/defcfn c-ids-trusted "seon_ids_trusted" [:pointer :string :string] :int)
(ffi/defcfn c-ids-stamp "seon_ids_stamp" [:pointer :string :string] :void)

(def ^:private MDB_NOSYNC 0x10000)
(def ^:private MDB_SAFENOSYNC 0x8000)

;; --- Single Source of Attribute Definitions (Datahike + Malli Rails) ---

(def attributes
  [;; Git commit tracking (1-to-1 Datahike :main === Git main)
   {:ident :git/repo            :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :keyword}
   {:ident :git/commit          :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 7}]}
   {:ident :git/branch          :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}

   ;; Clojure namespaces & top-level forms (code as data)
   {:ident :ns/name             :type :db.type/symbol  :card :db.cardinality/one  :unique :db.unique/identity :malli :symbol}
   {:ident :ns/path             :type :db.type/string  :card :db.cardinality/one  :index true :malli [:string {:min 1}]}
   {:ident :ns/tail             :type :db.type/string  :card :db.cardinality/one  :malli :string}

   {:ident :form/key            :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 1}]}
   {:ident :form/ns             :type :db.type/symbol  :card :db.cardinality/one  :index true :malli :symbol}
   {:ident :form/order          :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :form/lead           :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :form/src            :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}
   {:ident :form/hash           :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 8}]}
   {:ident :form/deps           :type :db.type/symbol  :card :db.cardinality/many :malli [:sequential :symbol]}
   {:ident :form/test?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; Non-Clojure tracked files
   {:ident :file/path           :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 1}]}
   {:ident :file/text           :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :file/exec?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; Tasks (desired state + prerequisites + clojure.test vars)
   {:ident :task/id             :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :qualified-keyword}
   {:ident :task/title          :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}
   {:ident :task/plan           :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :task/needs          :type :db.type/keyword :card :db.cardinality/many :malli [:sequential :qualified-keyword]}
   {:ident :task/tests          :type :db.type/symbol  :card :db.cardinality/many :malli [:sequential :qualified-symbol]}
   {:ident :task/done?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; Agents (logical actors on branches; live OS PID / running status is NEVER stored here!)
   {:ident :agent/id            :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :keyword}
   {:ident :agent/parent        :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :agent/branch        :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :agent/task          :type :db.type/keyword :card :db.cardinality/one  :malli :qualified-keyword}
   {:ident :agent/role          :type :db.type/keyword :card :db.cardinality/one  :malli [:enum :coordinator :worker :repl]}
   {:ident :agent/goal          :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :agent/paused?       :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :agent/waiting?      :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :agent/ready?        :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :agent/tested-tx     :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :agent/merged-tx     :type :db.type/long    :card :db.cardinality/one  :malli :int}

   ;; Messages (unread = main inbox minus branch :eval/seen-messages)
   {:ident :message/id          :type :db.type/uuid    :card :db.cardinality/one  :unique :db.unique/identity :malli :uuid}
   {:ident :message/seq         :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :message/from        :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :message/to          :type :db.type/keyword :card :db.cardinality/one  :index true :malli :keyword}
   {:ident :message/type        :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :message/body        :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; REPL Evaluations & Agent Turns (stored on the agent's branch)
   {:ident :eval/id             :type :db.type/uuid    :card :db.cardinality/one  :unique :db.unique/identity :malli :uuid}
   {:ident :eval/agent          :type :db.type/keyword :card :db.cardinality/one  :index true :malli :keyword}
   {:ident :eval/turn           :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :eval/seen-messages  :type :db.type/uuid    :card :db.cardinality/many :malli [:sequential :uuid]}
   {:ident :eval/thought        :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/code           :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/value          :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/out            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/error          :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; clojure.test reach-digest cache (stored in DB, shared across all branches & sessions!)
   {:ident :test/digest         :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 8}]}
   {:ident :test/var            :type :db.type/symbol  :card :db.cardinality/one  :index true :malli :qualified-symbol}
   {:ident :test/pass?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :test/ms             :type :db.type/double  :card :db.cardinality/one  :malli :double}
   {:ident :test/report         :type :db.type/string  :card :db.cardinality/one  :malli :string}])

(def schema
  (mapv (fn [{:keys [ident type card unique index]}]
          (cond-> {:db/ident ident :db/valueType type :db/cardinality card}
            unique (assoc :db/unique unique)
            index  (assoc :db/index true)))
        attributes))

(def ^:private attr-validators
  (into {}
        (map (fn [{:keys [ident card malli]}]
               (let [val-schema (if (= :db.cardinality/many card)
                                  [:or (second malli) [:sequential (second malli)] [:set (second malli)]]
                                  malli)]
                 [ident {:schema val-schema :valid? (m/validator val-schema)}])))
        attributes))

(defn validate-tx-data!
  "Rail at the write seam: rejects any malformed attribute value before it can enter any Datahike branch."
  [tx-data]
  (letfn [(check-kv! [a v]
            (when-let [{:keys [schema valid?]} (get attr-validators a)]
              (when (and (some? v) (not (valid? v)))
                (throw (ex-info (str "Schema rail rejected " a ": " (pr-str v))
                                {:error :seon.store/invalid-datom
                                 :attribute a
                                 :value v
                                 :explain (m/explain schema v)})))))]
    (doseq [op tx-data]
      (cond
        (map? op)
        (doseq [[a v] op :when (not= :db/id a)]
          (check-kv! a v))

        (and (vector? op) (= :db/add (first op)) (>= (count op) 4))
        (check-kv! (nth op 2) (nth op 3))))))

(defn- transact-checked [db arg-map]
  (let [tx-data (if (map? arg-map) (:tx-data arg-map) arg-map)]
    (validate-tx-data! tx-data)
    (datahike.writing/transact! db arg-map)))

;; --- Store Lifecycle, Time-Travel & Multi-Process Primitives ---

(defn data-file [path]
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
  "Pure EDN Datahike config for an LMDB store at `path` (safe to pass across `fork` / `remote` channels)."
  ([path] (store-cfg path {}))
  ([path {:keys [map-bytes cache-bytes]
          :or {map-bytes (* 4 1024 1024 1024)
               cache-bytes (* 64 1024 1024)}}]
   (let [cfg {:store {:backend :lmdb
                      :path path
                      :id (hasch/uuid path)
                      :config {:encoding {:serializer :FaslSerializer
                                          :compressor {:type :lz4}}}
                      :lmdb {:flags (bit-or MDB_NOSYNC MDB_SAFENOSYNC)
                             :map-size map-bytes
                             :sync-pages 4096}}
              :schema-flexibility :write
              :keep-history? true
              :crypto-hash? true
              :store-cache-bytes cache-bytes
              :writer {:backend :sync}
              :index-config {:branching-factor 64
                             :value-out-of-line-bytes 512}}]
     (if-let [id (stored-id cfg)]
       (assoc-in cfg [:store :id] id)
       cfg))))

(defn attach-ids!
  "Attaches the shared mmap'd `ids` page to `conn`'s store so every OS process issues unique eids/txids."
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

(defn connect
  "Opens a Datahike connection to `branch` using `cfg`, enforcing Malli write rails and shared IDs."
  ([cfg] (connect cfg (:branch cfg :main)))
  ([cfg branch]
   (-> (d/connect (-> cfg
                      (assoc :branch branch)
                      (assoc-in [:writer :write-fn-map] {'transact! transact-checked})))
       attach-ids!)))

(defn sync!
  "Flushes dirty LMDB pages to disk so the latest commit becomes the crash-safe snapshot."
  [cfg]
  (let [path (get-in cfg [:store :path])
        lmdb-opts (get-in cfg [:store :lmdb])]
    (lmdb-ffi/sync! (klmdb/open-env! path lmdb-opts))
    nil))

(defn branch? [conn-or-db branch]
  (let [s (:store (if (instance? clojure.lang.IDeref conn-or-db) @conn-or-db conn-or-db))]
    (boolean (k/exists? s branch {:sync? true}))))

(defn branch!
  "Creates `new-branch` pointing at `from-branch`'s current head (~1 ms pointer write)."
  [conn from-branch new-branch]
  (when-not (branch? conn new-branch)
    (d/branch! conn from-branch new-branch))
  new-branch)

(defn head
  "Reads `branch`'s latest committed immutable DB value directly from `store` or `conn`."
  [store-or-conn branch]
  (let [s (if (instance? clojure.lang.IDeref store-or-conn) (:store @store-or-conn) store-or-conn)]
    (d/branch-as-db s branch)))

(defn db-at
  "Time-travel & branch resolver: returns an immutable Datahike DB at any `{:commit uuid}` or `{:branch kw :tx long}`."
  [store-or-conn {:keys [commit branch tx]}]
  (let [s (if (instance? clojure.lang.IDeref store-or-conn) (:store @store-or-conn) store-or-conn)]
    (cond
      commit (d/commit-as-db s commit)
      :else  (let [base (d/branch-as-db s (or branch :main))]
               (if tx (d/as-of base tx) base)))))

(defn point
  "Pure serializable coordinate `{:commit uuid :tx long :branch kw}` for any immutable `db`."
  [db]
  {:commit (d/commit-id db)
   :tx (:max-tx db)
   :branch (get-in db [:config :branch] :main)})

(defn merge-base-db
  "Returns the immutable DB at the git-style common ancestor of `branch-a` and `branch-b`."
  [conn branch-a branch-b]
  (when-let [cid (d/merge-base conn branch-a branch-b)]
    (d/commit-as-db conn cid)))

(defn after-fork!
  "Called first inside any forked child process before opening LMDB: drops inherited LMDB env
   descriptors while retaining warm decoded Datahike node caches."
  [cfg]
  (swap! @#'konserve.jolt.lmdb/envs dissoc (get-in cfg [:store :path]))
  (datahike.connections/after-fork!))

(defn- install-missing-schema! [conn]
  (let [have (:schema @conn)
        missing (filterv #(not (contains? have (:db/ident %))) schema)]
    (when (seq missing)
      (d/transact conn {:tx-data missing}))
    missing))

(defn open!
  "Opens (or atomically creates) the LMDB store at `path` and returns `{:cfg :conn :created?}` on `:main`."
  ([path] (open! path {}))
  ([path opts]
   (let [created?
         (if (posix/exists? (data-file path))
           false
           (let [tmp (str path ".making-" (random-uuid))
                 tcfg (store-cfg tmp opts)]
             (try
               (d/create-database tcfg)
               (let [c (d/connect tcfg)]
                 (try
                   (d/transact c {:tx-data schema})
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
                   (run! #(.delete ^java.io.File %) (reverse (file-seq (java.io.File. tmp)))))))))]
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
           added (install-missing-schema! main-conn)]
       (when (or created? (seq added))
         (sync! cfg))
       {:cfg cfg :conn main-conn :created? created?}))))

(defn close! [{:keys [conn cfg]}]
  (when conn
    (try (sync! cfg) (catch Throwable _))
    (d/release conn)))
