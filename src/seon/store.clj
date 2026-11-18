(ns seon.store
  "Durable multi-process Datahike store on LMDB with shared atomic entity/tx IDs (`native/ids.c`),
   structured program/schema/doc/config entities, and Malli schema rails enforced at every write.
   Every point in the system across branches and time is addressed by an immutable Datahike commit-id
   or `[branch tx]`, so the entire system or any agent runtime can rebuild from any commit."
  (:require [aero.core :as aero]
            [clojure.edn :as edn]
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

;; --- Single Source of Attribute Definitions (Datahike + Malli Rails + Generic 3-Way Merge Metadata) ---

(def attributes
  [;; Git commit tracking (1-to-1 Datahike :main === Git main)
   {:ident :git/repo              :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :keyword :merge :skip}
   {:ident :git/commit            :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 7}]   :merge :skip}
   {:ident :git/branch            :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]   :merge :skip}

   ;; Clojure namespaces (`src/` & `test/`)
   {:ident :ns/name               :type :db.type/symbol  :card :db.cardinality/one  :unique :db.unique/identity :malli :symbol}
   {:ident :ns/path               :type :db.type/string  :card :db.cardinality/one  :index true :malli [:string {:min 1}]}
   {:ident :ns/doc                :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :ns/requires           :type :db.type/symbol  :card :db.cardinality/many :index true :malli [:sequential :symbol]}
   {:ident :ns/tail               :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; Top-level forms (lossless source reproduction + semantic hash)
   {:ident :form/key              :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 1}]}
   {:ident :form/ns               :type :db.type/symbol  :card :db.cardinality/one  :index true :malli :symbol}
   {:ident :form/order            :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :form/lead             :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :form/src              :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}
   {:ident :form/hash             :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 8}]}
   {:ident :form/deps             :type :db.type/symbol  :card :db.cardinality/many :index true :malli [:sequential :symbol]}
   {:ident :form/test?            :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; Structured Vars (`defn`, `def`, `defmacro`, `deftest` — extracted via `jolt.analyzer`, 0 live parsing on query!)
   {:ident :var/name              :type :db.type/symbol  :card :db.cardinality/one  :unique :db.unique/identity :malli :qualified-symbol}
   {:ident :var/ns                :type :db.type/symbol  :card :db.cardinality/one  :index true :malli :symbol}
   {:ident :var/form              :type :db.type/string  :card :db.cardinality/one  :index true :malli [:string {:min 1}]}
   {:ident :var/doc               :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :var/arglists          :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :var/private?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :var/macro?            :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :var/test?             :type :db.type/boolean :card :db.cardinality/one  :index true :malli :boolean}
   {:ident :var/platform-test?    :type :db.type/boolean :card :db.cardinality/one  :index true :malli :boolean}
   {:ident :var/schema            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :var/schema-refs       :type :db.type/keyword :card :db.cardinality/many :index true :malli [:sequential :qualified-keyword]}
   {:ident :var/refs              :type :db.type/symbol  :card :db.cardinality/many :index true :malli [:sequential :qualified-symbol]}
   {:ident :var/ticks-budget      :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :var/bytes-budget      :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :var/big-o             :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; First-class Malli Schemas (`:schema/key` entities)
   {:ident :schema/key            :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :qualified-keyword}
   {:ident :schema/form           :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}
   {:ident :schema/doc            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :schema/examples       :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :schema/render         :type :db.type/symbol  :card :db.cardinality/one  :malli :qualified-symbol}

   ;; Structured Markdown Documentation Sections (`docs/*.md` & `AGENTS.md`)
   {:ident :doc/id                :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 1}]}
   {:ident :doc/path              :type :db.type/string  :card :db.cardinality/one  :index true :malli [:string {:min 1}]}
   {:ident :doc/order             :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :doc/heading           :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :doc/body              :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; Non-Clojure / Non-Doc tracked files
   {:ident :file/path             :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 1}]}
   {:ident :file/text             :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :file/exec?            :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; System & Per-Agent Inheritable Configuration (`aero` seeded onto `:config/key :system/root`; agents override on `:agent/id`!)
   {:ident :config/key            :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :qualified-keyword}
   {:ident :config/eval-ticks     :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/eval-bytes     :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/gen-samples    :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/gen-seed       :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/max-os-procs   :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/max-test-workers :type :db.type/long  :card :db.cardinality/one  :malli :int}
   {:ident :config/max-model-slots :type :db.type/long   :card :db.cardinality/one  :malli :int}
   {:ident :config/child-cache-mb :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/gc-generations :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :config/auto-sync-git? :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :config/ai-model       :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :config/extra-edn      :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; Tasks (desired state + prerequisites + clojure.test vars)
   {:ident :task/id               :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :qualified-keyword}
   {:ident :task/title            :type :db.type/string  :card :db.cardinality/one  :malli [:string {:min 1}]}
   {:ident :task/plan             :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :task/needs            :type :db.type/keyword :card :db.cardinality/many :malli [:sequential :qualified-keyword]}
   {:ident :task/tests            :type :db.type/symbol  :card :db.cardinality/many :malli [:sequential :qualified-symbol]}
   {:ident :task/done?            :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}

   ;; Agents (logical actors on branches; live OS PID / running status is NEVER stored here!)
   {:ident :agent/id              :type :db.type/keyword :card :db.cardinality/one  :unique :db.unique/identity :malli :keyword}
   {:ident :agent/parent          :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :agent/branch          :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :agent/task            :type :db.type/keyword :card :db.cardinality/one  :malli :qualified-keyword}
   {:ident :agent/role            :type :db.type/keyword :card :db.cardinality/one  :malli [:enum :coordinator :worker :repl]}
   {:ident :agent/goal            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :agent/paused?         :type :db.type/boolean :card :db.cardinality/one  :malli :boolean :merge :skip}
   {:ident :agent/waiting?        :type :db.type/boolean :card :db.cardinality/one  :malli :boolean :merge :skip}
   {:ident :agent/ready?          :type :db.type/boolean :card :db.cardinality/one  :malli :boolean :merge :skip}
   {:ident :agent/tested-tx       :type :db.type/long    :card :db.cardinality/one  :malli :int     :merge :skip}
   {:ident :agent/merged-tx       :type :db.type/long    :card :db.cardinality/one  :malli :int     :merge :skip}

   ;; Messages (unread = main inbox minus branch :eval/seen-messages)
   {:ident :message/id            :type :db.type/uuid    :card :db.cardinality/one  :unique :db.unique/identity :malli :uuid}
   {:ident :message/seq           :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :message/from          :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :message/to            :type :db.type/keyword :card :db.cardinality/one  :index true :malli :keyword}
   {:ident :message/type          :type :db.type/keyword :card :db.cardinality/one  :malli :keyword}
   {:ident :message/body          :type :db.type/string  :card :db.cardinality/one  :malli :string}

   ;; REPL Evaluations & Multi-Tool Agent Turns (stored on the agent's branch)
   {:ident :eval/id               :type :db.type/uuid    :card :db.cardinality/one  :unique :db.unique/identity :malli :uuid}
   {:ident :eval/agent            :type :db.type/keyword :card :db.cardinality/one  :index true :malli :keyword}
   {:ident :eval/turn             :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :eval/call-index       :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :eval/call-id          :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/seen-messages    :type :db.type/uuid    :card :db.cardinality/many :malli [:sequential :uuid]}
   {:ident :eval/thought          :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/code             :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/value            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/out              :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/error            :type :db.type/string  :card :db.cardinality/one  :malli :string}
   {:ident :eval/ticks            :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :eval/bytes            :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :eval/ms               :type :db.type/double  :card :db.cardinality/one  :malli :double}

   ;; clojure.test & Malli generative reach-digest cache (stored in DB, shared across all branches!)
   {:ident :test/digest           :type :db.type/string  :card :db.cardinality/one  :unique :db.unique/identity :malli [:string {:min 8}]}
   {:ident :test/var              :type :db.type/symbol  :card :db.cardinality/one  :index true :malli :qualified-symbol}
   {:ident :test/kind             :type :db.type/keyword :card :db.cardinality/one  :malli [:enum :deftest :generative]}
   {:ident :test/pass?            :type :db.type/boolean :card :db.cardinality/one  :malli :boolean}
   {:ident :test/ms               :type :db.type/double  :card :db.cardinality/one  :malli :double}
   {:ident :test/ticks            :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :test/bytes            :type :db.type/long    :card :db.cardinality/one  :malli :int}
   {:ident :test/report           :type :db.type/string  :card :db.cardinality/one  :malli :string}])

(def attr-by-ident
  (into {} (map (juxt :ident identity)) attributes))

(def identity-attrs
  "Vector of all `:db.unique/identity` attributes whose entities participate in generic 3-way merge."
  (vec (keep #(when (and (= :db.unique/identity (:unique %))
                         (not= :skip (:merge %)))
                (:ident %))
             attributes)))

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

;; --- Simple Entity-Based Config Inheritance (`agent-entity -> :system/root`, ~20 µs) ---

(def default-config-values
  {:config/eval-ticks       100000000
   :config/eval-bytes       268435456
   :config/gen-samples      100
   :config/gen-seed         42
   :config/max-os-procs     6
   :config/max-test-workers 4
   :config/max-model-slots  4
   :config/child-cache-mb   4
   :config/gc-generations   6
   :config/auto-sync-git?   true
   :config/ai-model         "deepseek-chat"})

(defn read-aero-config-tx
  "Reads an Aero EDN config file once at bootstrap and returns the `:system/root` entity map."
  [file-or-text]
  (let [raw (if (instance? java.io.File file-or-text)
              (when (.isFile ^java.io.File file-or-text)
                (aero/read-config file-or-text))
              (when (string? file-or-text)
                (aero/read-config (java.io.StringReader. file-or-text))))
        merged (merge default-config-values
                      (select-keys raw (keys default-config-values)))
        extras (apply dissoc raw (keys default-config-values))]
    (cond-> (assoc merged :config/key :system/root)
      (seq extras) (assoc :config/extra-edn (pr-str extras)))))

(defn config-val
  "Reads config key `k` from `db`, checking `agent-id`'s entity first (if given) and falling back
   to `:config/key :system/root`, then `default-config-values`. 1 indexed pull (~20 µs), 0 file reads!"
  ([db k] (config-val db nil k))
  ([db agent-id k]
   (let [agent-v (when (and db agent-id (first (d/datoms db :avet :agent/id agent-id)))
                   (get (d/pull db [k] [:agent/id agent-id]) k))]
     (if (some? agent-v)
       agent-v
       (let [root-v (when (and db (first (d/datoms db :avet :config/key :system/root)))
                      (get (d/pull db [k] [:config/key :system/root]) k))]
         (if (some? root-v)
           root-v
           (get default-config-values k)))))))

(defn config-map
  "Returns the effective config map for `agent-id` (or `:system/root` when nil), plus `:overrides` set."
  ([db] (config-map db nil))
  ([db agent-id]
   (let [ks (vec (keys default-config-values))
         root-m (merge default-config-values
                       (when (and db (first (d/datoms db :avet :config/key :system/root)))
                         (dissoc (d/pull db ks [:config/key :system/root]) :db/id)))
         agent-m (when (and db agent-id (first (d/datoms db :avet :agent/id agent-id)))
                   (dissoc (d/pull db ks [:agent/id agent-id]) :db/id))]
     {:effective (merge root-m agent-m)
      :overrides (set (keys agent-m))})))

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

(defn child-cfg
  "Lean store config for forked COW child processes (`child-cache-mb`, default 4 MB instead of 64 MB)."
  ([cfg] (child-cfg cfg 4))
  ([cfg mb]
   (assoc cfg :store-cache-bytes (* (max 1 (long (or mb 4))) 1024 1024))))

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

(defn- ensure-default-config! [conn]
  (when-not (first (d/datoms @conn :avet :config/key :system/root))
    (let [cfg-file (io/file checkout-root "resources/seon/config/default.edn")
          root-ent (if (.isFile cfg-file)
                     (read-aero-config-tx cfg-file)
                     (assoc default-config-values :config/key :system/root))]
      (d/transact conn {:tx-data [root-ent]}))))

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
           added (install-missing-schema! main-conn)
           seeded-cfg? (ensure-default-config! main-conn)]
       (when (or created? (seq added) seeded-cfg?)
         (sync! cfg))
       {:cfg cfg :conn main-conn :created? created?}))))

(defn close! [{:keys [conn cfg]}]
  (when conn
    (try (sync! cfg) (catch Throwable _))
    (d/release conn)
    (when cfg
      (try (klmdb/close-env! (get-in cfg [:store :path])) (catch Throwable _)))))
