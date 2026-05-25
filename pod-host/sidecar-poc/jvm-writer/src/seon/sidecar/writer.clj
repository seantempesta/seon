(ns seon.sidecar.writer
  "Sidecar JVM writer: owns the single Datahike connection and answers requests
   over a UDS socket. Broadcasts tx events on a separate UDS socket.

   Run with:
     clj -M:writer
   or
     clj -M:writer --backend memory
     clj -M:writer --backend sqlite --path data/seon-poc.sqlite
     clj -M:writer --req-sock /tmp/seon-poc-req.sock --pub-sock /tmp/seon-poc-pub.sock"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [konserve-jdbc.core]
            [seon.sidecar.codec :as codec]
            [seon.sidecar.broadcast :as bcast])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io File])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))

;; ---------- Configuration ----------

(defn- parse-args [args]
  (loop [acc {:backend "memory"
              :path "data/seon-poc.sqlite"
              :req-sock "/tmp/seon-poc-req.sock"
              :pub-sock "/tmp/seon-poc-pub.sock"}
         xs args]
    (case (first xs)
      "--backend"   (recur (assoc acc :backend (second xs)) (drop 2 xs))
      "--path"      (recur (assoc acc :path (second xs)) (drop 2 xs))
      "--req-sock"  (recur (assoc acc :req-sock (second xs)) (drop 2 xs))
      "--pub-sock"  (recur (assoc acc :pub-sock (second xs)) (drop 2 xs))
      nil acc
      (do (println "Unknown arg:" (first xs)) (System/exit 2)))))

(defn- store-config [opts]
  (case (:backend opts)
    "memory"
    {:store {:backend :memory :id #uuid "00000000-0000-0000-0000-000000000001"}
     :keep-history? true
     :schema-flexibility :write}

    "file"
    (let [^File f (java.io.File. ^String (:path opts))]
      ;; Datahike's create-database makes the dir; only mkdirs the PARENT.
      (when-let [parent (.getParentFile f)] (.mkdirs parent))
      {:store {:backend :file
               :path (:path opts)
               :id #uuid "11111111-1111-1111-1111-111111111111"}
       :keep-history? true
       :schema-flexibility :write})

    "sqlite"
    (let [^File f (java.io.File. ^String (:path opts))]
      (when-let [parent (.getParentFile f)] (.mkdirs parent))
      ;; NOTE: konserve-jdbc 0.2.91 was built against konserve 0.8.x and does
      ;; not register the :jdbc backend with konserve 0.9.346. Phase 1 deferred
      ;; konserve-jdbc per the PRD contingency. Use --backend file for
      ;; persistence in the meantime.
      {:store {:backend :jdbc
               :dbtype "sqlite"
               :dbname (:path opts)
               :table "store"
               :id #uuid "11111111-1111-1111-1111-111111111111"}
       :keep-history? true
       :schema-flexibility :write})))

;; ---------- DB lifecycle ----------

(defn- ensure-db! [cfg]
  (when-not (d/database-exists? cfg)
    (d/create-database cfg))
  (d/connect cfg))

;; ---------- Request handling ----------

(defn- ok [m] (assoc m "ok" true))
(defn- err [kind msg]
  {"ok" false "error" msg "error-kind" kind})

(defn- basis-t-of [db]
  ;; datahike: (:max-tx db) is the post-commit basis-t
  (some-> db :max-tx))

(defn- cbor-safe
  "Some datahike results contain Datom records, sets, keywords, java.util.Date,
   etc. Walk it to a plain CBOR-compatible shape."
  [x]
  (cond
    (nil? x)                                              nil
    (keyword? x)                                          (str (when-let [n (namespace x)] (str n "/")) (name x))
    (symbol? x)                                           (str x)
    (instance? datahike.datom.Datom x)                    (let [^datahike.datom.Datom d x]
                                                           [(cbor-safe (.-e d))
                                                            (cbor-safe (.-a d))
                                                            (cbor-safe (.-v d))
                                                            (cbor-safe (.-tx d))
                                                            (boolean (:added d))])
    (map? x)                                              (into {} (for [[k v] x] [(cbor-safe k) (cbor-safe v)]))
    (set? x)                                              (mapv cbor-safe x)
    (sequential? x)                                       (mapv cbor-safe x)
    (or (string? x) (boolean? x) (integer? x) (double? x)
        (float? x))                                       x
    (instance? java.util.Date x)                          x
    :else                                                 (str x)))

(defn- tx-data->wire
  "Convert a tx-report :tx-data seq (Datom records) to the wire vector shape
   [[e a v t op] ...]. Same shape datahike.core/listen! callbacks receive on
   the JVM, modulo CBOR-native keyword encoding."
  [tx-data]
  (mapv (fn [^datahike.datom.Datom d]
          [(cbor-safe (.-e d))
           (cbor-safe (.-a d))
           (cbor-safe (.-v d))
           (cbor-safe (.-tx d))
           (boolean (:added d))])
        tx-data))

;; ---------- Filtered-db handle registry ----------
;;
;; `d/filter` in Datahike takes a `(fn [db datom] -> bool)` predicate. We can't
;; ship guest closures across the wire, so the sidecar protocol takes a
;; **predicate query** (an EDN datalog query) that returns the set of eids
;; to retain; the writer compiles it into a datom-level predicate at
;; handle-creation time, caches the filtered db, and hands the guest an
;; integer handle for use in subsequent `q-filtered` / `pull-filtered` calls.
;;
;; This is a deliberate departure from native `d/filter` — see PROTOCOL.md.

(defonce ^:private filtered-dbs (atom {}))   ; {handle -> {:db filtered-db :basis-t Long}}
(defonce ^:private filter-counter (atom 0))

(defn- register-filtered-db!
  "Cache a FilteredDB under a fresh handle. We also capture the source db's
   basis-t at handle creation; FilteredDB itself disables `valAt`, so we read
   basis-t from the unfiltered db."
  [filtered-db source-bt]
  (let [h (swap! filter-counter inc)]
    (swap! filtered-dbs assoc h {:db filtered-db :basis-t source-bt})
    h))

(defn- resolve-db-with-basis-t
  "Returns a db value pinned to `basis-t` if provided, else current db.
   Uses `d/as-of` for snapshot reads."
  [conn basis-t-or-nil]
  (let [db (d/db conn)]
    (if basis-t-or-nil
      (d/as-of db basis-t-or-nil)
      db)))

(defn- read-edn-eid
  "An eid on the wire may be an int (CBOR integer) OR a string carrying an
   EDN lookup-ref like `[:person/name \"alice\"]`. Normalize."
  [eid]
  (cond
    (integer? eid) eid
    (string? eid)  (edn/read-string eid)
    :else          eid))

(defmulti handle-op (fn [_conn req] (get req "op")))

(defmethod handle-op :default [_ req]
  (err "protocol" (str "unknown op: " (pr-str (get req "op")))))

(defmethod handle-op "ping" [_ _]
  (ok {"pong" true}))

(defmethod handle-op "q" [conn req]
  (let [query   (edn/read-string (get req "query"))
        args    (mapv identity (get req "args" []))
        basis-t (get req "basis-t")
        db      (resolve-db-with-basis-t conn basis-t)
        result  (apply d/q query db args)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe result)})))

(defmethod handle-op "transact" [conn req]
  (let [tx         (edn/read-string (get req "tx-data"))
        tx-meta-in (when-let [s (get req "tx-meta")] (edn/read-string s))
        request-id (get req "request-id")
        report     (if tx-meta-in
                     (d/transact conn {:tx-data tx :tx-meta tx-meta-in})
                     (d/transact conn tx))
        db         (:db-after report)
        db-before  (:db-before report)
        tx-data    (:tx-data report)
        wire-data  (tx-data->wire tx-data)
        added      (count (filter :added tx-data))
        retracted  (count (remove :added tx-data))
        bt         (basis-t-of db)
        bt-before  (basis-t-of db-before)
        ;; gap #1: full tx-report shape on the pub event so listeners can
        ;; reason about specific changes, not just counts.
        event      (cond-> {"event" "tx"
                            "basis-t" bt
                            "basis-t-before" bt-before
                            "db-name" "default"
                            "tx-data" wire-data
                            "datoms-added" added
                            "datoms-retracted" retracted
                            "tx-meta" (cbor-safe (:tx-meta report))}
                     request-id (assoc "request-id" request-id))]
    (try (bcast/broadcast! event) (catch Throwable _))
    (ok (cond-> {"basis-t" bt
                 "basis-t-before" bt-before
                 "tempids" (cbor-safe (dissoc (:tempids report) :db/current-tx))
                 "tx-data" wire-data
                 "tx-meta" (cbor-safe (:tx-meta report))
                 "datoms-added" added
                 "datoms-retracted" retracted}
          request-id (assoc "request-id" request-id)))))

(defmethod handle-op "transact-batch" [conn req]
  ;; Apply a list of tx-data vectors in order, each as its own datahike
  ;; commit. Emits one pub event per individual tx (matching d/listen
  ;; semantics). Returns a vector of per-tx reports in matching order.
  ;;
  ;; On any single failure: stops processing, returns partial results
  ;; with the failing index. Subsequent entries are NOT applied. The
  ;; caller decides what to do with them.
  ;;
  ;; Ordering guarantee: each entry is committed sequentially in the
  ;; same thread before this fn returns. Listeners across all
  ;; subscribers observe events in this order.
  (let [tx-data-list (mapv edn/read-string (get req "tx-data-list"))
        tx-meta-list (when-let [ms (get req "tx-meta-list")]
                       (mapv #(when % (edn/read-string %)) ms))
        request-ids  (get req "request-ids")
        n            (count tx-data-list)
        per-tx-report
        (fn [idx tx tx-meta-in request-id]
          (let [report (if tx-meta-in
                         (d/transact conn {:tx-data tx :tx-meta tx-meta-in})
                         (d/transact conn tx))
                db        (:db-after report)
                db-before (:db-before report)
                tx-data   (:tx-data report)
                wire-data (tx-data->wire tx-data)
                added     (count (filter :added tx-data))
                retracted (count (remove :added tx-data))
                bt        (basis-t-of db)
                bt-before (basis-t-of db-before)
                event     (cond-> {"event" "tx"
                                   "basis-t" bt
                                   "basis-t-before" bt-before
                                   "db-name" "default"
                                   "tx-data" wire-data
                                   "datoms-added" added
                                   "datoms-retracted" retracted
                                   "tx-meta" (cbor-safe (:tx-meta report))}
                            request-id (assoc "request-id" request-id))]
            (try (bcast/broadcast! event) (catch Throwable _))
            (cond-> {"basis-t" bt
                     "basis-t-before" bt-before
                     "tempids" (cbor-safe (dissoc (:tempids report) :db/current-tx))
                     "tx-data" wire-data
                     "tx-meta" (cbor-safe (:tx-meta report))
                     "datoms-added" added
                     "datoms-retracted" retracted
                     "index" idx}
              request-id (assoc "request-id" request-id))))]
    (loop [idx     0
           reports (transient [])]
      (if (>= idx n)
        (ok {"reports" (persistent! reports)
             "applied" idx
             "total"   n})
        (let [tx         (nth tx-data-list idx)
              tx-meta-in (some-> tx-meta-list (nth idx nil))
              request-id (some-> request-ids (nth idx nil))]
          (let [result (try
                         {:ok (per-tx-report idx tx tx-meta-in request-id)}
                         (catch clojure.lang.ExceptionInfo e
                           {:err {:kind "datahike"
                                  :msg  (str (.getMessage e) " " (pr-str (ex-data e)))}})
                         (catch Throwable t
                           {:err {:kind "internal"
                                  :msg  (.toString t)}}))]
            (if-let [ok-rep (:ok result)]
              (recur (inc idx) (conj! reports ok-rep))
              (let [{:keys [kind msg]} (:err result)]
                ;; Partial success: return accumulated reports + the failure
                (ok {"reports"     (persistent! reports)
                     "applied"     idx
                     "total"       n
                     "failed-at"   idx
                     "error"       msg
                     "error-kind"  kind})))))))))

(defmethod handle-op "pull" [conn req]
  (let [selector (edn/read-string (get req "selector"))
        eid      (read-edn-eid (get req "eid"))
        basis-t  (get req "basis-t")
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe (d/pull db selector eid))})))

;; ---------- New ops (Phase B.1) ----------

(defn- expand-component-refs
  "Walk a pulled entity map; for any value that is itself a map (because we
   used `'[*]` on a component-ref attr, Datahike eagerly realizes it), recurse
   one level. For attr values that are pulled as eids (non-component refs with
   `'[*]` selector), we leave them as-is — guests can pull again if needed.
   Depth defaults to 1 to mirror `d/entity` shallow access."
  [m depth]
  (if (or (nil? m) (zero? depth) (not (map? m)))
    m
    (into {}
          (for [[k v] m]
            [k (cond
                 (map? v)         (expand-component-refs v (dec depth))
                 (and (sequential? v) (every? map? v))
                 (mapv #(expand-component-refs % (dec depth)) v)
                 :else            v)]))))

(defmethod handle-op "entity-pull" [conn req]
  ;; Eager `d/entity` replacement. Selector defaults to `'[*]`; an optional
  ;; `depth` controls how deep we recursively realize component-ref maps
  ;; (default 1 — matches the V0 usage audit).
  ;;
  ;; A missing lookup-ref is NOT an error — V0's `d/entity` returns nil for a
  ;; missing entity, and the audit's call sites rely on that. We catch
  ;; Datahike's "missing lookup ref" exception and surface it as `result=nil`.
  (let [eid      (read-edn-eid (get req "ref"))
        selector (if-let [s (get req "selector")]
                   (edn/read-string s)
                   '[*])
        depth    (long (or (get req "depth") 1))
        basis-t  (get req "basis-t")
        db       (resolve-db-with-basis-t conn basis-t)
        raw      (try
                   (d/pull db selector eid)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :entity-id/missing (:error (ex-data e)))
                       nil
                       (throw e))))
        result   (when raw (expand-component-refs raw depth))]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe result)})))

(defmethod handle-op "pull-many" [conn req]
  (let [selector (edn/read-string (get req "selector"))
        eids     (->> (get req "eids")
                      (mapv read-edn-eid))
        basis-t  (get req "basis-t")
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe (d/pull-many db selector eids))})))

(defmethod handle-op "schema" [conn _req]
  (let [db (d/db conn)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe (:schema db))})))

(defmethod handle-op "reverse-schema" [conn _req]
  (let [db (d/db conn)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (cbor-safe (:rschema db))})))

(defmethod handle-op "db-filter" [conn req]
  ;; Build a filtered-db handle. The wire shape is a predicate **query** —
  ;; a Datalog query that returns a relation of `[?e]` rows. We turn that
  ;; into a set of eids, then materialize a `d/filter` that retains only
  ;; datoms whose entity is in the set.
  (let [pred-query (edn/read-string (get req "pred-query"))
        args       (mapv identity (get req "args" []))
        db         (d/db conn)
        rows       (apply d/q pred-query db args)
        keep-eids  (into #{} (map first) rows)
        filtered-db
        (d/filter db (fn [_db ^datahike.datom.Datom d]
                       (contains? keep-eids (.-e d))))
        bt         (basis-t-of db)
        handle     (register-filtered-db! filtered-db bt)]
    (ok {"basis-t" bt
         "handle"  handle
         "kept"    (count keep-eids)})))

(defmethod handle-op "q-filtered" [_conn req]
  (let [handle  (long (get req "handle"))
        query   (edn/read-string (get req "query"))
        args    (mapv identity (get req "args" []))]
    (if-let [entry (get @filtered-dbs handle)]
      (let [db (:db entry)]
        (ok {"basis-t" (:basis-t entry)
             "result"  (cbor-safe (apply d/q query db args))}))
      (err "not-found" (str "no filtered-db handle: " handle)))))

(defmethod handle-op "filter-release" [_conn req]
  ;; Drop a filtered-db handle. Always succeeds (idempotent).
  (let [handle (long (get req "handle"))]
    (swap! filtered-dbs dissoc handle)
    (ok {"released" true "handle" handle})))

(defn- handle-req [conn req]
  (try
    (handle-op conn req)
    (catch clojure.lang.ExceptionInfo e
      (err "datahike" (str (.getMessage e) " " (pr-str (ex-data e)))))
    (catch Throwable t
      (err "internal" (.toString t)))))

;; ---------- Req server ----------

(defn- start-req-server! [conn ^String path]
  (try (.. (java.io.File. path) delete) (catch Throwable _))
  (let [addr (UnixDomainSocketAddress/of path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind server addr)
    (doto (Thread. ^Runnable
                   (fn []
                     (try
                       (loop []
                         (let [^SocketChannel ch (.accept server)
                               in  (Channels/newInputStream ch)
                               out (Channels/newOutputStream ch)]
                           (doto (Thread. ^Runnable
                                          (fn []
                                            (try
                                              (loop []
                                                (when-let [req (codec/read-frame in)]
                                                  (let [resp (handle-req conn req)]
                                                    (codec/write-frame! out resp))
                                                  (recur)))
                                              (catch Throwable t
                                                (binding [*out* *err*]
                                                  (println "[req-conn] died:" (.getMessage t))))
                                              (finally
                                                (try (.close ch) (catch Throwable _)))))
                                          "sidecar-req-conn")
                             (.setDaemon true)
                             (.start)))
                         (recur))
                       (catch java.nio.channels.AsynchronousCloseException _ nil)
                       (catch Throwable t
                         (binding [*out* *err*]
                           (println "[req-accept] died:" (.getMessage t))))))
                   "sidecar-req-accept")
      (.setDaemon true)
      (.start))
    server))

;; ---------- Main ----------

(defn -main [& args]
  (let [opts (parse-args args)
        cfg  (store-config opts)
        _    (println "[writer] starting with" opts)
        conn (ensure-db! cfg)
        _    (println "[writer] datahike ready; basis-t=" (basis-t-of (d/db conn)))
        pub-server (bcast/start-pub-server! (:pub-sock opts))
        _    (println "[writer] pub socket:" (:pub-sock opts))
        req-server (start-req-server! conn (:req-sock opts))
        _    (println "[writer] req socket:" (:req-sock opts))]
    (reset! state {:conn conn :req-server req-server :pub-server pub-server})
    (println "[writer] ready. PID=" (.pid (java.lang.ProcessHandle/current)))
    ;; Block forever. Shutdown by SIGTERM.
    (.. (Thread/currentThread) join)))
