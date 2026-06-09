(ns seon.dev.replica-probe
  "2.2c DIS-replica probe — Node side (READER).

   Opens a datahike `:file` store WRITTEN BY A JVM PROCESS with a stub
   non-streaming writer (mirrors `datahike.http.writer/DatahikeServerWriter`'s
   shape: `-streaming?` false, so `deref-conn` re-reads the branch root from
   konserve on every `@conn` — connector.cljc:69-78). Falsifies/confirms the
   load-bearing claims of
   docs/prds/agent-runtime/research/datahike-native-replica-2026-06-09.md:

     - fressian byte compat JVM->Node (any blob read error = REFUTED)
     - sync lazy node fetch (fs.openSync counted; no awaits on the read path)
     - root-follow: fresh `@conn` sees datoms the JVM transacted after the
       previous run of this script
     - lazy-vs-full: a tiny query must NOT open every blob in the store

   Driven by `seon.probe.replica-jvm` (clj -M:replica-probe-jvm). Env:
     REPLICA_STORE_PATH  store dir (default tmp/replica-probe/store)
     REPLICA_STORE_ID    RFC UUID string — must match the JVM's create id
     REPLICA_MODE        full | tiny
     REPLICA_TINY_ID     entity :seon.probe/id for tiny mode

   Emits ONE machine-readable line to stdout: `PROBE-EDN {...}`.
   Build:  clj -M:cljs compile replica-probe   (fresh JVM, not cljs-watch)
   Run:    node out/replica-probe/main.js"
  (:require
   [clojure.string :as str]
   [datahike.api :as d]
   [datahike.connector :as connector]
   [datahike.writer :as w]
   ;; registers datahike's :file backend on Node (same require the pod does,
   ;; client.cljs:35)
   [konserve.core :as k]
   [konserve.impl.storage-layout :as storage-layout]
   [konserve.node-filestore :as nfs]))

(def ^:private fs (js/require "fs"))

;; ---------------------------------------------------------------------------
;; fs read accounting — konserve's sync FileChannel opens each blob with
;; fs.openSync (node_filestore.cljs:118-122) then readSync's from the fd, so
;; "number of .ksv opens" = number of konserve blob reads, and the opened
;; file's size bounds the bytes read (read-value reads the whole value).
;; ---------------------------------------------------------------------------

(def !opens
  "Vector of {:seon.probe/path :seon.probe/bytes} — one entry per .ksv open."
  (atom []))

(defn- install-read-counter! []
  (let [orig (.-openSync fs)]
    (set! (.-openSync fs)
          (fn [& args]
            (let [path (first args)]
              (when (and (string? path) (str/ends-with? path ".ksv"))
                (swap! !opens conj
                       {:seon.probe/path  path
                        :seon.probe/bytes (try (.-size (.statSync fs path))
                                               (catch :default _ 0))}))
              (.apply orig fs (to-array args)))))))

(defn- drain-opens! []
  (let [v @!opens]
    (reset! !opens [])
    {:seon.probe/blob-reads (count v)
     :seon.probe/blob-bytes (reduce + 0 (map :seon.probe/bytes v))}))

;; ---------------------------------------------------------------------------
;; DIAGNOSTIC SHIM (probe-only, env REPLICA_HEADER_SHIM=1) — konserve 0.9.346
;; header bug found by this probe 2026-06-09: CLJ `create-header` encodes
;; meta-size as a 4-byte BIG-ENDIAN INT at bytes 4-7 (`.putInt bb 4 meta`,
;; storage_layout.cljc:29) while CLJS writes/parses ONE byte at offset 4
;; (`aset`/`aget`, :40/:118). A JVM-written blob with meta-size 32 has bytes
;; [0 0 0 32] there; the stock CLJS parser reads 0 and deserializes the META
;; section as the VALUE. This shim re-parses meta-size as the JVM's 4-byte BE
;; int so the probe can falsify the claims DOWNSTREAM of the header (fressian
;; datom/PSS handlers, lazy fetch, root-follow). It is NOT the production fix
;; — that belongs in the konserve fork (/Users/sean/src/konserve), with a
;; migration story for existing 1-byte-encoded CLJS-written stores.
;; ---------------------------------------------------------------------------

(defn- meta-size-be32 [header-bytes]
  (+ (* (aget header-bytes 4) 0x1000000)
     (* (aget header-bytes 5) 0x10000)
     (* (aget header-bytes 6) 0x100)
     (aget header-bytes 7)))

(defn- install-jvm-header-shim! []
  (let [orig storage-layout/parse-header]
    (set! storage-layout/parse-header
          (fn [header-bytes serializers]
            (let [[version serializer compressor encryptor _ hsize]
                  (orig header-bytes serializers)]
              [version serializer compressor encryptor
               (meta-size-be32 header-bytes) hsize])))))

;; ---------------------------------------------------------------------------
;; Stub non-streaming writer — the ONE piece that flips deref-conn into
;; follow-the-store mode. Read-only: any dispatch throws.
;; ---------------------------------------------------------------------------

(defrecord StubWriter []
  w/PWriter
  (-dispatch! [_ _]
    (throw (ex-info "replica-probe stub writer is read-only"
                    {:seon.probe/writer :probe-stub})))
  (-shutdown [_] nil)
  (-streaming? [_] false))

(defmethod w/create-writer :probe-stub [_ _] (->StubWriter))

(defmethod connector/-connect* :probe-stub [config opts]
  (connector/-connect-impl* config opts))

;; ---------------------------------------------------------------------------
;; Probe body
;; ---------------------------------------------------------------------------

(defn- now [] (js/performance.now))

(def ^:private full-query
  '[:find ?id ?name
    :where [?e :seon.probe/id ?id] [?e :seon.probe/name ?name]])

(defn- debug-dump-root!
  "REPLICA_DEBUG=1 — read the raw branch root straight off konserve (no
   datahike) and dump what CLJS fressian actually deserialized. For
   diagnosing JVM->Node byte-compat failures."
  [store-path]
  (let [store  (nfs/connect-fs-store store-path :opts {:sync? true})
        stored (k/get store :db nil {:sync? true})]
    (println "DEBUG root nil?" (nil? stored) "type:" (pr-str (type stored)))
    (when stored
      (println "DEBUG root keys:" (pr-str (vec (keys stored))))
      (println "DEBUG key types:" (pr-str (mapv type (keys stored))))
      (println "DEBUG :config =" (pr-str (:config stored)))
      (println "DEBUG :max-tx =" (pr-str (:max-tx stored)))
      (println "DEBUG :meta   =" (pr-str (:meta stored))))))

(defn ^:async -main [& _]
  (install-read-counter!)
  (try
    (let [env        (.-env js/process)
          store-path (or (.-REPLICA_STORE_PATH env) "tmp/replica-probe/store")
          _          (when (.-REPLICA_HEADER_SHIM env) (install-jvm-header-shim!))
          _          (when (.-REPLICA_DEBUG env) (debug-dump-root! store-path))
          store-id   (uuid (.-REPLICA_STORE_ID env))
          mode       (keyword (or (.-REPLICA_MODE env) "full"))
          cfg        {:store              {:backend :file
                                           :path    store-path
                                           :id      store-id}
                      :keep-history?      true
                      :schema-flexibility :write
                      :writer             {:backend :probe-stub}
                      :allow-unsafe-config true}
          t0         (now)
          conn       (await (d/connect cfg))
          connect-ms (- (now) t0)
          connect-io (drain-opens!)
          ;; deref #1 — cold root-follow (fresh stored->db from the root key)
          td1        (now)
          db         @conn
          deref1-ms  (- (now) td1)
          deref1-io  (drain-opens!)
          ;; query — sync, lazy node fetch under it
          tq         (now)
          result     (case mode
                       :tiny (:seon.probe/name
                              (d/entity db [:seon.probe/id
                                            (js/parseInt (.-REPLICA_TINY_ID env) 10)]))
                       :full (d/q full-query db))
          query-ms   (- (now) tq)
          query-io   (drain-opens!)
          ;; d/datoms index walk — sync, no await
          datoms5    (mapv (fn [dat] [(:e dat) (:a dat)])
                           (take 5 (d/datoms db {:index :eavt :components []})))
          ;; deref #2 — warm (LRU shared across derefs of the conn)
          td2        (now)
          db2        @conn
          deref2-ms  (- (now) td2)
          deref2-io  (drain-opens!)]
      (println
       (str "PROBE-EDN "
            (pr-str
             {:seon.probe/mode        mode
              :seon.probe/max-tx      (:max-tx db)
              :seon.probe/max-tx-2    (:max-tx db2)
              :seon.probe/result      (if (= mode :full)
                                        (vec (sort result))
                                        result)
              :seon.probe/datoms-head datoms5
              :seon.probe/connect-ms  connect-ms
              :seon.probe/deref1-ms   deref1-ms
              :seon.probe/deref2-ms   deref2-ms
              :seon.probe/query-ms    query-ms
              :seon.probe/connect-io  connect-io
              :seon.probe/deref1-io   deref1-io
              :seon.probe/query-io    query-io
              :seon.probe/deref2-io   deref2-io})))
      (.exit js/process 0))
    (catch :default e
      (println (str "PROBE-EDN "
                    (pr-str {:seon.probe/error (str e)
                             :seon.probe/data  (pr-str (ex-data e))})))
      (.exit js/process 1))))
