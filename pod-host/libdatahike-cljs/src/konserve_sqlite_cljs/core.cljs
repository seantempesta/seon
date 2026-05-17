(ns konserve-sqlite-cljs.core
  "konserve adapter for SQLite via node-sqlite3-wasm.

   Mirrors the JVM `konserve-jdbc` SQLite branch:

     - Schema: `konserve(id TEXT PRIMARY KEY, header BLOB, meta BLOB, val BLOB)`.
     - UPSERT: `INSERT ... ON CONFLICT (id) DO UPDATE SET header=excluded.header,
       meta=excluded.meta, val=excluded.val`.
     - WAL mode enabled for disk-backed databases (no-op for `:memory:`).

   node-sqlite3-wasm is synchronous. Konserve's `PBackingStore` /
   `PBackingBlob` protocols dispatch on a `:sync?` flag in `env`. We implement
   the sync path natively; for `{:sync? false}` callers we wrap each result in
   `(go ...)` so the protocol contract (channel return) is honoured.

   Config shape:
     `{:backend :sqlite :path \"/tmp/store.db\" :id #uuid \"...\" :table \"konserve\"}`

   `:path` can be `\":memory:\"` for an in-process SQLite DB (no disk persistence)."
  (:require [cljs.core.async :refer [go put! close!] :include-macros true]
            [konserve.compressor]
            [konserve.encryptor]
            [konserve.impl.defaults :as defaults]
            [konserve.impl.storage-layout :as storage-layout
             :refer [PBackingStore PBackingBlob PBackingLock
                     PMultiWriteBackingStore PMultiReadBackingStore]]
            [konserve.serializers]
            [konserve.store :as store]
            [konserve.utils :refer-macros [with-promise]]))

;; ---------------------------------------------------------------------------
;; SQLite handle + statement preparation
;; ---------------------------------------------------------------------------

(def ^:private Database
  (.-Database (js/require "node-sqlite3-wasm")))

(def ^:private default-table "konserve")

(defn- in-memory? [path] (= ":memory:" path))

(defn- ->uint8 [x]
  ;; node-sqlite3-wasm returns BLOBs as Uint8Array; we accept Uint8Array or
  ;; node Buffer. Normalise to Uint8Array so callers get a stable type.
  (cond
    (nil? x) nil
    (instance? js/Uint8Array x) x
    (instance? js/Buffer x) (js/Uint8Array.from x)
    :else (js/Uint8Array.from x)))

(defrecord SqliteConn [^js db table stmts]
  Object
  (close [_]
    (try
      (doseq [^js s (vals @stmts)]
        (when (and s (not (.-isFinalized s))) (.finalize s)))
      (catch :default _ nil))
    (try (.close db) (catch :default _ nil))))

(defn- prep!
  "Cache a prepared statement under `kw` on the conn."
  [^SqliteConn conn kw sql]
  (let [stmts (.-stmts conn)
        existing (get @stmts kw)]
    (if existing
      existing
      (let [s (.prepare ^js (.-db conn) sql)]
        (swap! stmts assoc kw s)
        s))))

(defn- ensure-schema!
  "Run `CREATE TABLE IF NOT EXISTS` + WAL pragma. Idempotent."
  [^js db table]
  (.exec db (str "CREATE TABLE IF NOT EXISTS " table
                 " (id TEXT PRIMARY KEY, header BLOB, meta BLOB, val BLOB);"))
  ;; PRAGMA is a no-op for :memory: but cheap to issue.
  (try (.exec db "PRAGMA journal_mode=WAL;") (catch :default _ nil))
  (try (.exec db "PRAGMA synchronous=NORMAL;") (catch :default _ nil)))

;; Process-level connection cache keyed by `path`. Two reasons:
;;   (1) `:memory:` databases are not shared across `new Database(\":memory:\")`
;;       calls — each call yields a fresh, empty DB. konserve's lifecycle
;;       opens the store at `create-database` time and re-opens at `connect`
;;       time; without caching the connect would see an empty DB.
;;   (2) Even for disk paths, holding one connection avoids opening / closing
;;       on every operation.
;;
;; Note: keyed by `path` (not by `[path id]`) because for SQLite the file
;; itself is the unique identifier — different :id values that share a path
;; still share the underlying database.
(defonce ^:private conn-cache (atom {}))

(defn- open-conn
  "Open or reuse a SQLite Database + prepare the schema."
  [path table]
  (or (get @conn-cache path)
      (let [db   (Database. path #js {})
            conn (->SqliteConn db table (atom {}))]
        (ensure-schema! db table)
        (swap! conn-cache assoc path conn)
        conn)))

(defn- drop-conn!
  "Drop the cached connection for `path` (and close it)."
  [path]
  (when-let [conn (get @conn-cache path)]
    (try (.close conn) (catch :default _ nil))
    (swap! conn-cache dissoc path)))

;; ---------------------------------------------------------------------------
;; Wrapped-sync helper — return value directly for :sync?=true; wrap in go for
;; :sync?=false so the contract (channel return) is satisfied.
;; ---------------------------------------------------------------------------

(defn- ret
  "Return `v` directly if env is :sync?=true, else as a go-channel."
  [env v]
  (if (:sync? env) v (go v)))

(defn- ret-err
  "Same as `ret` but for promise-chan style err returns: puts the err on the
   channel for async, throws for sync."
  [env e]
  (if (:sync? env)
    (throw e)
    (let [ch (cljs.core.async/promise-chan)]
      (put! ch e)
      ch)))

;; ---------------------------------------------------------------------------
;; PBackingLock — trivial single-process lock. The pod is single-writer and
;; node-sqlite3-wasm is synchronous, so there is no contention to manage.
;; ---------------------------------------------------------------------------

(extend-protocol PBackingLock
  boolean
  (-release [_ env] (ret env nil)))

;; ---------------------------------------------------------------------------
;; PBackingBlob — one row of `konserve` table addressed by `store-key`.
;;
;; Mirrors konserve-jdbc's JDBCRow: writes accumulate into `data` until -sync
;; emits a single UPSERT; reads populate a `cache` from the first read of any
;; of {header, meta, val}.
;; ---------------------------------------------------------------------------

(defn- read-row
  "SELECT id, header, meta, val FROM table WHERE id=? — returns a {:header
   :meta :val} map or nil if missing."
  [^SqliteConn conn store-key]
  (let [^js stmt (prep! conn :select
                        (str "SELECT header, meta, val FROM " (.-table conn) " WHERE id = ?;"))
        ^js row  (.get stmt #js [store-key])]
    (when row
      {:header (->uint8 (.-header row))
       :meta   (->uint8 (.-meta row))
       :val    (->uint8 (.-val row))})))

(defn- upsert-row!
  "Single-row UPSERT."
  [^SqliteConn conn store-key header meta value]
  (let [^js stmt (prep! conn :upsert
                        (str "INSERT INTO " (.-table conn)
                             " (id, header, meta, val) VALUES (?, ?, ?, ?) "
                             "ON CONFLICT (id) DO UPDATE "
                             "SET header = excluded.header, meta = excluded.meta, val = excluded.val;"))]
    (.run stmt #js [store-key header meta value])))

(defrecord SqliteRow [conn store-key data cache]
  PBackingBlob
  (-sync [_ env]
    (let [{:keys [header meta value]} @data]
      (if (and header meta value)
        (do (upsert-row! conn store-key header meta value)
            (reset! data {})
            (ret env nil))
        (ret-err env
                 (ex-info "Updating a row is only possible if header, meta and value are set."
                          {:data @data :store-key store-key})))))
  (-close [_ env] (ret env nil))
  (-get-lock [_ env] (ret env true))
  (-read-header [_ env]
    (when-not (:header @cache)
      (when-let [row (read-row conn store-key)] (reset! cache row)))
    (ret env (:header @cache)))
  (-read-meta [_ _meta-size env]
    (when-not (:meta @cache)
      (when-let [row (read-row conn store-key)] (reset! cache row)))
    (ret env (:meta @cache)))
  (-read-value [_ _meta-size env]
    (when-not (:val @cache)
      (when-let [row (read-row conn store-key)] (reset! cache row)))
    (ret env (:val @cache)))
  (-read-binary [_ _meta-size locked-cb env]
    (when-not (:val @cache)
      (when-let [row (read-row conn store-key)] (reset! cache row)))
    ;; Hand the raw Uint8Array to the locked-cb as a :blob (sync semantics)
    ;; or wrap an input-stream-equivalent. Konserve's sync filestore path
    ;; passes `{:blob js/Buffer}`; we mirror that shape.
    (let [v (:val @cache)
          buf (when v (js/Buffer.from (.-buffer v) (.-byteOffset v) (.-byteLength v)))
          res (locked-cb {:blob buf})]
      (ret env res)))
  (-write-header [_ header env]
    (swap! data assoc :header header)
    (ret env nil))
  (-write-meta [_ meta env]
    (swap! data assoc :meta meta)
    (ret env nil))
  (-write-value [_ value _meta-size env]
    (swap! data assoc :value value)
    (ret env nil))
  (-write-binary [_ _meta-size blob env]
    (swap! data assoc :value blob)
    (ret env nil)))

(defn- open-row [conn store-key]
  (->SqliteRow conn store-key (atom {}) (atom nil)))

;; ---------------------------------------------------------------------------
;; PBackingStore — table-level operations on `konserve`.
;; ---------------------------------------------------------------------------

(defrecord SqliteTable [conn path table]
  PBackingStore
  (-create-blob [_ store-key env]
    (ret env (open-row conn store-key)))
  (-delete-blob [_ store-key env]
    (let [^js stmt (prep! conn :delete
                          (str "DELETE FROM " table " WHERE id = ?;"))]
      (.run stmt #js [store-key])
      (ret env nil)))
  (-blob-exists? [_ store-key env]
    (let [^js stmt (prep! conn :exists
                          (str "SELECT 1 FROM " table " WHERE id = ? LIMIT 1;"))
          row      (.get stmt #js [store-key])]
      (ret env (some? row))))
  (-copy [_ from to env]
    (let [^js stmt (prep! conn :copy
                          (str "INSERT INTO " table " (id, header, meta, val) "
                               "SELECT ?, header, meta, val FROM " table " WHERE id = ? "
                               "ON CONFLICT (id) DO UPDATE "
                               "SET header = excluded.header, meta = excluded.meta, val = excluded.val;"))]
      (.run stmt #js [to from])
      (ret env nil)))
  (-atomic-move [_ from to env]
    (let [^js stmt (prep! conn :rename
                          (str "UPDATE " table " SET id = ? WHERE id = ?;"))]
      (.run stmt #js [to from])
      (ret env nil)))
  (-migratable [_ _key _store-key env] (ret env nil))
  (-migrate [_ _migration-key _key-vec _serializer _read-handlers _write-handlers env]
    (ret env nil))
  (-handle-foreign-key [_ _migration-key _serializer _read-handlers _write-handlers env]
    (ret env nil))
  (-create-store [_ env]
    ;; Schema was created on conn open; this is idempotent.
    (ensure-schema! (.-db conn) table)
    (ret env nil))
  (-sync-store [_ env]
    ;; node-sqlite3-wasm's WAL fsyncs on commit; nothing extra to do.
    (ret env nil))
  (-delete-store [_ env]
    (try (.exec (.-db conn) (str "DROP TABLE IF EXISTS " table ";"))
      (catch :default _ nil))
    (drop-conn! path)
    (when-not (in-memory? path)
      (try
        (let [fs (js/require "fs")]
          (.rmSync fs path #js {:force true})
          (.rmSync fs (str path "-wal") #js {:force true})
          (.rmSync fs (str path "-shm") #js {:force true}))
        (catch :default _ nil)))
    (ret env nil))
  (-store-exists? [_ env]
    ;; For :memory: the table exists iff this process created it (always true
    ;; after open). For disk paths, check whether the file exists OR the table
    ;; can be queried.
    (let [^js stmt (prep! conn :table-exists
                          (str "SELECT 1 FROM " table " LIMIT 1;"))]
      (try (.get stmt) (ret env true)
        (catch :default _ (ret env false)))))
  (-keys [_ env]
    (let [^js stmt (prep! conn :keys
                          (str "SELECT id FROM " table " ORDER BY id;"))
          rows     (.all stmt)]
      (ret env (vec (map #(.-id ^js %) rows)))))

  PMultiWriteBackingStore
  (-multi-write-blobs [_ store-key-values env]
    (if (empty? store-key-values)
      (ret env {})
      (let [^js db   (.-db conn)
            ^js stmt (prep! conn :upsert
                            (str "INSERT INTO " table
                                 " (id, header, meta, val) VALUES (?, ?, ?, ?) "
                                 "ON CONFLICT (id) DO UPDATE "
                                 "SET header = excluded.header, meta = excluded.meta, val = excluded.val;"))]
        (.exec db "BEGIN;")
        (try
          (let [result (reduce
                        (fn [acc [k {:keys [header meta value]}]]
                          (.run stmt #js [k header meta value])
                          (assoc acc k true))
                        {}
                        store-key-values)]
            (.exec db "COMMIT;")
            (ret env result))
          (catch :default e
            (try (.exec db "ROLLBACK;") (catch :default _ nil))
            (ret-err env e))))))
  (-multi-delete-blobs [_ store-keys env]
    (if (empty? store-keys)
      (ret env {})
      (let [^js db  (.-db conn)
            ^js sel (prep! conn :exists
                           (str "SELECT 1 FROM " table " WHERE id = ? LIMIT 1;"))
            ^js del (prep! conn :delete
                           (str "DELETE FROM " table " WHERE id = ?;"))]
        (.exec db "BEGIN;")
        (try
          (let [result (reduce
                        (fn [acc k]
                          (let [existed? (some? (.get sel #js [k]))]
                            (.run del #js [k])
                            (assoc acc k existed?)))
                        {}
                        store-keys)]
            (.exec db "COMMIT;")
            (ret env result))
          (catch :default e
            (try (.exec db "ROLLBACK;") (catch :default _ nil))
            (ret-err env e))))))

  PMultiReadBackingStore
  (-multi-read-blobs [_ store-keys env]
    (if (empty? store-keys)
      (ret env {})
      (let [^js stmt (prep! conn :select
                            (str "SELECT header, meta, val FROM " table " WHERE id = ?;"))
            result (reduce
                    (fn [acc k]
                      (if-let [^js row (.get stmt #js [k])]
                        (let [r (open-row conn k)
                              cached {:header (->uint8 (.-header row))
                                      :meta   (->uint8 (.-meta row))
                                      :val    (->uint8 (.-val row))}]
                          (reset! (:cache r) cached)
                          (assoc acc k r))
                        acc))
                    {}
                    store-keys)]
        (ret env result)))))

;; ---------------------------------------------------------------------------
;; Public connect + multimethod registration
;; ---------------------------------------------------------------------------

(defn connect-sqlite-store
  "Connect (or create) a SQLite-backed konserve store at `path`.

   `path` may be `\":memory:\"` for a non-persistent in-process database, or a
   filesystem path for a real on-disk WAL'd database.

   Returns a connected DefaultStore wrapping the SQLite backing. Honours
   `:sync?` in opts — sync mode returns the store directly, async returns a
   go-channel."
  [path & {:keys [table opts] :as params}]
  (let [table   (or table default-table)
        opts    (or opts {:sync? false})
        conn    (open-conn path table)
        backing (->SqliteTable conn path table)
        store-config (merge {:default-serializer :FressianSerializer
                             :compressor         konserve.compressor/null-compressor
                             :encryptor          konserve.encryptor/null-encryptor
                             :read-handlers      (atom {})
                             :write-handlers     (atom {})
                             :buffer-size        (* 1024 1024)
                             :opts               opts
                             :config             {:sync-blob? true
                                                  :in-place?  true
                                                  :no-backup? true
                                                  :lock-blob? true}}
                            (dissoc params :opts :table :config))]
    (defaults/connect-default-store backing store-config)))

(defn delete-sqlite-store
  "Drop the konserve table and unlink the disk files (no-op for `:memory:`)."
  [path & {:keys [table]}]
  (let [table (or table default-table)]
    (when-not (= path ":memory:")
      (try
        (let [fs (js/require "fs")]
          (when (.existsSync fs path)
            (.rmSync fs path #js {:force true}))
          (.rmSync fs (str path "-wal") #js {:force true})
          (.rmSync fs (str path "-shm") #js {:force true}))
        (catch :default _ nil)))
    nil))

;; -----------------------------------------------------------------------------
;; Multimethod registration — `:sqlite` backend dispatch through konserve.store
;; -----------------------------------------------------------------------------

(defmethod store/-connect-store :sqlite
  [{:keys [path table] :as config} opts]
  (let [opts (or opts {:sync? false})]
    (connect-sqlite-store path :table table :opts opts
                          :config (:config config))))

(defmethod store/-create-store :sqlite
  [{:keys [path table] :as config} opts]
  (let [opts (or opts {:sync? false})]
    (connect-sqlite-store path :table table :opts opts
                          :config (:config config))))

(defmethod store/-store-exists? :sqlite
  [{:keys [path]} opts]
  ;; For disk paths: the file exists on disk.
  ;; For `:memory:`: the store exists iff we already opened it in this process.
  ;; (A fresh process starts with no in-memory store; datahike's create-database
  ;; lifecycle is "exists? -> no -> create-store". subsequent connects see "yes".)
  (let [exists? (if (= path ":memory:")
                  (contains? @conn-cache path)
                  (let [fs (js/require "fs")] (.existsSync fs path)))]
    (if (:sync? opts) exists? (go exists?))))

(defmethod store/-delete-store :sqlite
  [{:keys [path table]} opts]
  (delete-sqlite-store path :table table)
  (if (:sync? opts) nil (go nil)))

(defmethod store/-release-store :sqlite
  [_config _store opts]
  ;; The conn-cache holds the connection; consumers may reconnect later, so we
  ;; do NOT close on release. The connection is closed when the store is
  ;; deleted (-delete-store) or the process exits.
  (if (:sync? opts) nil (go nil)))
