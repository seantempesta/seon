(ns seon.server.wire
  "Sidecar JVM writer: owns the single Datahike connection and answers requests
   over a UDS socket. Broadcasts tx events on a separate UDS socket.

   Wire protocol:
   - Control envelope: CBOR map with string keys (op, ok, basis-t, ...)
   - Value payloads (query/pull results, tx-data values, tx-meta, tempids,
     query args, selectors, eids): Transit-JSON strings inside the envelope.
   - Datom shape: [e a-transit v-transit t op] — a and v are Transit-JSON
     strings; e, t are ints; op is bool.

   See PROTOCOL.md for the full surface."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [konserve-jdbc.core]
            [seon.server.codec :as codec]
            [seon.server.transit :as transit]
            [seon.server.store :as store]
            [seon.server.broadcast :as bcast])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels])
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
      "--db-name"   (recur (assoc acc :db-name (second xs)) (drop 2 xs))
      "--path"      (recur (assoc acc :path (second xs)) (drop 2 xs))
      "--req-sock"  (recur (assoc acc :req-sock (second xs)) (drop 2 xs))
      "--pub-sock"  (recur (assoc acc :pub-sock (second xs)) (drop 2 xs))
      nil acc
      (do (println "Unknown arg:" (first xs)) (System/exit 2)))))

(defn- opts->config-for-request
  "Translate CLI opts (string backend, optional :db-name/:path) into the
   namespaced map `seon.server.store/config-for` expects. Derives a
   default db-name from the req-sock basename when --db-name is absent,
   so two standalone wire-servers on different sockets get distinct
   per-name stores. Omits ::path when nil/for :memory (the schema is
   [:string {:min 1}], so passing nil would fail instrumentation)."
  [{:keys [backend db-name path req-sock]}]
  (let [db-name-kw (keyword (or db-name
                                (str "seon.server/"
                                     (.getName (java.io.File. ^String req-sock)))))]
    (cond-> {:seon.server.store/db-name db-name-kw
             :seon.server.store/backend (keyword backend)}
      (and path (not= "memory" backend))
      (assoc :seon.server.store/path path))))

;; ---------- DB lifecycle ----------

(defn- ensure-db! [cfg]
  (when-not (d/database-exists? cfg)
    (d/create-database cfg))
  (d/connect cfg))

;; ---------- Response helpers ----------

(defn- ok [m] (assoc m "ok" true))
(defn- err [kind msg]
  {"ok" false "error" msg "error-kind" kind})

(defn- basis-t-of [db]
  (some-> db :max-tx))

;; ---------- Transit value payloads ----------
;;
;; Anything carrying a Clojure value across the wire is Transit-JSON. The
;; control envelope (op, ok, basis-t, error-kind, handle, applied, total,
;; failed-at, datoms-added, datoms-retracted, request-id, db-name, event)
;; is plain CBOR.

(defn- T
  "Transit-JSON encode."
  [v]
  (transit/write-str v))

(defn- read-T
  "Decode a value-payload string. Production callers (CLJS guest via WIT)
   send Transit-JSON. The Rust diagnostic harness (smoke driver, REPL,
   multi-agent seeds) sends EDN strings as a transitional convenience.

   Disambiguation: try Transit first; if that fails, try EDN. nil/empty
   string → nil. The EDN fallback will be removed once the host
   diagnostic harness is migrated to Transit."
  [s]
  (when (and s (not= "" s))
    (try
      (transit/read-str s)
      (catch Throwable _transit-failed
        (edn/read-string s)))))

;; ---------- Datom wire shape ----------

(defn- datom->wire
  "Convert a datahike Datom record to [e a-transit v-transit t op]. a and v
   are Transit-JSON strings; the keyword attribute is encoded as a Transit
   keyword (~:ns/name); the value can be any Clojure value (Transit handles
   instants, keywords, BigInts, etc.)."
  [^datahike.datom.Datom d]
  [(.-e d)
   (T (.-a d))
   (T (.-v d))
   (.-tx d)
   (boolean (:added d))])

(defn- tx-data->wire [tx-data]
  (mapv datom->wire tx-data))

;; ---------- Schema-driven type coercion ----------
;;
;; JS Numbers don't distinguish 1 from 1.0. Transit-cljs writes both as ~i1
;; on read-side and a plain JSON number on write-side. To preserve the
;; double-typed nature of attrs whose schema declares :db.type/float or
;; :db.type/double, we coerce ints → doubles for those attrs before
;; transacting. Read-side: (double v) is already a Double, Transit-clj
;; serializes it with the appropriate type tag, and the guest gets a JS
;; Number back (the truth lives in the DB).

(defn- schema-of [conn]
  (-> (d/db conn) :schema))

(defn- valueType-of [schema attr]
  (when (keyword? attr)
    (get-in schema [attr :db/valueType])))

(defn- coerce-value-for-attr
  "If schema says attr is float/double and v is an integer, coerce to double.
   Otherwise return v unchanged."
  [schema attr v]
  (let [vt (valueType-of schema attr)]
    (cond
      (and (or (= vt :db.type/double) (= vt :db.type/float))
           (integer? v))                              (double v)
      :else                                            v)))

(defn- coerce-tx-data-for-schema
  "Walk a tx-data vector and coerce values that should be doubles. Handles
   the two common shapes: maps ({:attr v ...}) and 5-vectors ([:db/add e a v]
   or [:db/retract e a v])."
  [schema tx-data]
  (mapv
    (fn [item]
      (cond
        (map? item)
        (reduce-kv
          (fn [m k v]
            (assoc m k (coerce-value-for-attr schema k v)))
          {}
          item)

        (and (vector? item) (#{:db/add :db/retract} (first item)) (= 4 (count item)))
        (let [[op e a v] item]
          [op e a (coerce-value-for-attr schema a v)])

        :else item))
    tx-data))

;; ---------- Filtered-db handle registry ----------

(defonce ^:private filtered-dbs (atom {}))
(defonce ^:private filter-counter (atom 0))

(defn- register-filtered-db! [filtered-db source-bt]
  (let [h (swap! filter-counter inc)]
    (swap! filtered-dbs assoc h {:db filtered-db :basis-t source-bt})
    h))

(defn- resolve-db-with-basis-t [conn basis-t-or-nil]
  (let [db (d/db conn)]
    (if (and basis-t-or-nil (pos? (long basis-t-or-nil)))
      (d/as-of db basis-t-or-nil)
      db)))

;; ---------- Request handlers ----------

(defmulti handle-op (fn [_conn req] (get req "op")))

(defmethod handle-op :default [_ req]
  (err "protocol" (str "unknown op: " (pr-str (get req "op")))))

(defmethod handle-op "ping" [_ _]
  (ok {"pong" true}))

(defmethod handle-op "q" [conn req]
  (let [query   (read-T (get req "query"))
        args    (mapv read-T (get req "args" []))
        basis-t (get req "basis-t")
        db      (resolve-db-with-basis-t conn basis-t)
        result  (apply d/q query db args)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (T result)})))

(defn- tx-report->ok-map
  [report request-id]
  (let [db        (:db-after report)
        db-before (:db-before report)
        tx-data   (:tx-data report)
        wire-data (tx-data->wire tx-data)
        added     (count (filter :added tx-data))
        retracted (count (remove :added tx-data))
        bt        (basis-t-of db)
        bt-before (basis-t-of db-before)
        tempids   (dissoc (:tempids report) :db/current-tx)
        tx-meta   (:tx-meta report)]
    {:wire-data  wire-data
     :added      added
     :retracted  retracted
     :bt         bt
     :bt-before  bt-before
     :tempids    tempids
     :tx-meta    tx-meta
     :request-id request-id}))

(defn- ok-event-from-report [{:keys [wire-data added retracted bt bt-before
                                     tx-meta request-id]}]
  (cond-> {"event" "tx"
           "basis-t" bt
           "basis-t-before" bt-before
           "db-name" "default"
           "tx-data" wire-data
           "datoms-added" added
           "datoms-retracted" retracted
           "tx-meta" (T tx-meta)}
    request-id (assoc "request-id" request-id)))

(defn- ok-response-from-report [{:keys [wire-data added retracted bt bt-before
                                        tempids tx-meta request-id]}]
  ;; Two forms in one envelope:
  ;; - Structured fields (basis-t, tx-data, etc.) for tests and the Rust
  ;;   host's pub/cache machinery that needs to read basis-t, datoms-added,
  ;;   etc. without parsing Transit.
  ;; - "payload": a single Transit-JSON string of the full Clojure-side
  ;;   response map. The CLJS guest reads this one field with one Transit
  ;;   decode — no per-field assembly across the boundary.
  (cond-> {"basis-t"           bt
           "basis-t-before"    bt-before
           "tempids"           (T tempids)
           "tx-data"           wire-data
           "tx-meta"           (T tx-meta)
           "datoms-added"      added
           "datoms-retracted"  retracted
           "payload"
           (T (cond-> {:basis-t           bt
                       :basis-t-before    bt-before
                       :tempids           tempids
                       :datoms-added      added
                       :datoms-retracted  retracted
                       :tx-meta           tx-meta}
                request-id (assoc :request-id request-id)))}
    request-id (assoc "request-id" request-id)))

(defmethod handle-op "transact" [conn req]
  (let [tx         (read-T (get req "tx-data"))
        tx-meta-in (read-T (get req "tx-meta"))
        request-id (let [r (get req "request-id")] (when (and r (not= "" r)) r))
        schema     (schema-of conn)
        tx*        (coerce-tx-data-for-schema schema tx)
        report     (if tx-meta-in
                     (d/transact conn {:tx-data tx* :tx-meta tx-meta-in})
                     (d/transact conn tx*))
        r          (tx-report->ok-map report request-id)
        event      (ok-event-from-report r)]
    (try (bcast/broadcast! event) (catch Throwable _))
    (ok (ok-response-from-report r))))

(defn- report->clj
  "Plain Clojure value of a tx report, for embedding in :reports inside the
   Transit-encoded payload. Keeps native keywords/instants/etc. — Transit
   handles them on the wire."
  [{:keys [bt bt-before tempids added retracted tx-meta request-id]
    :as r}]
  (let [tx-data (->> (:tx-data-raw r)
                     (mapv (fn [^datahike.datom.Datom d]
                             [(.-e d) (.-a d) (.-v d) (.-tx d)
                              (boolean (:added d))])))]
    (cond-> {:basis-t          bt
             :basis-t-before   bt-before
             :tempids          tempids
             :tx-data          tx-data
             :tx-meta          tx-meta
             :datoms-added     added
             :datoms-retracted retracted}
      request-id (assoc :request-id request-id))))

(defmethod handle-op "transact-batch" [conn req]
  (let [tx-data-list (mapv read-T (get req "tx-data-list"))
        tx-meta-list (when-let [ms (get req "tx-meta-list")] (mapv read-T ms))
        request-ids  (get req "request-ids")
        n            (count tx-data-list)
        schema       (schema-of conn)
        per-tx-report
        (fn [idx tx tx-meta-in request-id]
          (let [tx*    (coerce-tx-data-for-schema schema tx)
                report (if tx-meta-in
                         (d/transact conn {:tx-data tx* :tx-meta tx-meta-in})
                         (d/transact conn tx*))
                r      (assoc (tx-report->ok-map report request-id)
                              :tx-data-raw (:tx-data report))
                event  (ok-event-from-report r)]
            (try (bcast/broadcast! event) (catch Throwable _))
            {:wire (assoc (ok-response-from-report r) "index" idx)
             :clj  (assoc (report->clj r) :index idx)}))]
    (loop [idx 0 wire-reports (transient []) clj-reports (transient [])]
      (if (>= idx n)
        (let [wreps (persistent! wire-reports)
              creps (persistent! clj-reports)]
          (ok {"reports" wreps
               "applied" idx
               "total"   n
               "payload" (T {:applied idx :total n :reports creps})}))
        (let [tx         (nth tx-data-list idx)
              tx-meta-in (some-> tx-meta-list (nth idx nil))
              request-id (let [r (some-> request-ids (nth idx nil))]
                           (when (and r (not= "" r)) r))]
          (let [result (try
                         {:ok (per-tx-report idx tx tx-meta-in request-id)}
                         (catch clojure.lang.ExceptionInfo e
                           {:err {:kind "datahike"
                                  :msg  (str (.getMessage e) " " (pr-str (ex-data e)))}})
                         (catch Throwable t
                           {:err {:kind "internal"
                                  :msg  (.toString t)}}))]
            (if-let [ok-rep (:ok result)]
              (recur (inc idx)
                     (conj! wire-reports (:wire ok-rep))
                     (conj! clj-reports (:clj ok-rep)))
              (let [{:keys [kind msg]} (:err result)
                    wreps (persistent! wire-reports)
                    creps (persistent! clj-reports)]
                (ok {"reports"    wreps
                     "applied"    idx
                     "total"      n
                     "failed-at"  idx
                     "error"      msg
                     "error-kind" kind
                     "payload"    (T {:applied    idx
                                      :total      n
                                      :reports    creps
                                      :failed-at  idx
                                      :error      msg
                                      :error-kind kind})})))))))))

(defmethod handle-op "pull" [conn req]
  (let [selector (read-T (get req "selector"))
        eid      (read-T (get req "eid"))
        basis-t  (get req "basis-t")
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (T (d/pull db selector eid))})))

(defn- expand-component-refs [m depth]
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
  (let [eid      (read-T (get req "ref"))
        sel-raw  (read-T (get req "selector"))
        selector (or sel-raw '[*])
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
         "result"  (T result)})))

(defmethod handle-op "pull-many" [conn req]
  (let [selector (read-T (get req "selector"))
        eids     (mapv read-T (get req "eids"))
        basis-t  (get req "basis-t")
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (T (d/pull-many db selector eids))})))

(defmethod handle-op "schema" [conn _req]
  (let [db (d/db conn)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (T (:schema db))})))

(defmethod handle-op "reverse-schema" [conn _req]
  (let [db (d/db conn)]
    (ok {"basis-t" (basis-t-of db)
         "result"  (T (:rschema db))})))

(defmethod handle-op "db-filter" [conn req]
  (let [pred-query (read-T (get req "pred-query"))
        args       (mapv read-T (get req "args" []))
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
        query   (read-T (get req "query"))
        args    (mapv read-T (get req "args" []))]
    (if-let [entry (get @filtered-dbs handle)]
      (let [db (:db entry)]
        (ok {"basis-t" (:basis-t entry)
             "result"  (T (apply d/q query db args))}))
      (err "not-found" (str "no filtered-db handle: " handle)))))

(defmethod handle-op "filter-release" [_conn req]
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
        cfg  (store/config-for (opts->config-for-request opts))
        _    (println "[writer] starting with" opts)
        conn (ensure-db! cfg)
        _    (println "[writer] datahike ready; basis-t=" (basis-t-of (d/db conn)))
        pub-server (bcast/start-pub-server! (:pub-sock opts))
        _    (println "[writer] pub socket:" (:pub-sock opts))
        req-server (start-req-server! conn (:req-sock opts))
        _    (println "[writer] req socket:" (:req-sock opts))]
    (reset! state {:conn conn :req-server req-server :pub-server pub-server})
    (println "[writer] ready. PID=" (.pid (java.lang.ProcessHandle/current)))
    (.. (Thread/currentThread) join)))
