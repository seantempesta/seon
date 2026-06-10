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
  (:require [clojure.core.server :as srv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [konserve-jdbc.core]
            [seon.server.codec :as codec]
            [seon.server.transit :as transit]
            [seon.server.store :as store]
            [seon.server.registry :as registry]
            [seon.server.broadcast :as bcast])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))

;; ---------- Configuration ----------

(defn- parse-args [args]
  (loop [acc {:backend "memory"
              :path "data/seon-client-runtime.sqlite"
              :req-sock "tmp/seon-client-runtime-req.sock"
              :pub-sock "tmp/seon-client-runtime-pub.sock"}
         xs args]
    (case (first xs)
      "--backend"   (recur (assoc acc :backend (second xs)) (drop 2 xs))
      "--db-name"   (recur (assoc acc :db-name (second xs)) (drop 2 xs))
      "--path"      (recur (assoc acc :path (second xs)) (drop 2 xs))
      "--req-sock"  (recur (assoc acc :req-sock (second xs)) (drop 2 xs))
      "--pub-sock"  (recur (assoc acc :pub-sock (second xs)) (drop 2 xs))
      "--repl-port" (recur (assoc acc :repl-port (Long/parseLong (second xs))) (drop 2 xs))
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

(declare raw-broadcast-listener-fn)

(defn- seed-base-schema!
  "Install wire-server base-schema attrs that every conn needs regardless of
   the agent's domain schema. Currently just `:seon.db/request-id` — required
   so `:schema-flexibility :write` accepts request-id in tx-meta (the channel
   that carries it to the ::raw-broadcast listener thread). Idempotent: a
   re-seed transacts the same :db/ident, a no-op datahike upsert."
  [conn]
  (d/transact conn [{:db/ident       :seon.db/request-id
                     :db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/one}]))

(defn- ensure-db!
  "Open (creating if needed) the conn for `cfg`, seed the wire base-schema,
   and register the per-conn `::raw-broadcast` `d/listen!` that emits the raw
   db-name-tagged tx event on every commit. db-name is derived from cfg's
   `:name` (the single-DB / test path); the multi-DB registry path installs
   the same listener via the on-ensure-db hook with its real db-name."
  [cfg]
  (when-not (d/database-exists? cfg)
    (d/create-database cfg))
  (let [conn    (d/connect cfg)
        db-name (or (:name cfg) "default")]
    (seed-base-schema! conn)
    (d/listen conn ::raw-broadcast (raw-broadcast-listener-fn db-name))
    conn))

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

(defmethod handle-op "ensure-db" [_conn req]
  ;; Materialize (or look up) a cluster's DB. Idempotent — a re-ensure of the
  ;; same db-name returns the existing conn's current basis-t without
  ;; reseeding. db-name is a keyword on the wire; default backend :memory for
  ;; testability, override via \"backend\". On open, registry's on-ensure-db
  ;; hook installs this conn's ::raw-broadcast listener (+ any ::reactive one).
  (let [db-name (some-> (get req "db-name") keyword)
        backend (some-> (get req "backend") keyword)
        path    (get req "path")]
    (if-not db-name
      (err "protocol" "ensure-db requires \"db-name\"")
      (let [entry (registry/ensure-db!
                   (cond-> {:seon.server.registry/db-name db-name
                            :seon.server.registry/backend (or backend :memory)}
                     path (assoc :seon.server.registry/path path)))
            conn  (:seon.server.registry/conn entry)]
        (ok {"db-name" (subs (str db-name) 1)
             "basis-t" (basis-t-of (d/db conn))})))))

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

(defn- ok-event-from-report
  "Build the raw `tx` broadcast event. `db-name` is the committing conn's real
   db-name string (no more hardcoded \"default\"). `request-id` comes from the
   commit's tx-meta (`:seon.db/request-id`) so it survives the async hop to the
   `::raw-broadcast` listener thread — the listener, not the request handler,
   emits the event now (see `raw-broadcast-listener-fn`)."
  [db-name {:keys [wire-data added retracted bt bt-before tx-meta request-id]}]
  (cond-> {"event" "tx"
           "basis-t" bt
           "basis-t-before" bt-before
           "db-name" db-name
           "tx-data" wire-data
           "datoms-added" added
           "datoms-retracted" retracted
           "tx-meta" (T tx-meta)}
    request-id (assoc "request-id" request-id)))

;; ---------- ::raw-broadcast listener (the P1 hook) ----------
;;
;; Broadcast is no longer imperative at the transact call sites. Each conn
;; carries a `d/listen!`-registered `::raw-broadcast` callback that fires
;; synchronously on every commit and emits the db-name-tagged `tx` event. This
;; is the seam the reactive engine plugs a SECOND listener (`::reactive`) into
;; — distinct keys, both fire off the same TxReport. request-id rides tx-meta
;; (`:seon.db/request-id`) because the listener runs on the writer thread.

(defn raw-broadcast-listener-fn
  "Return a `d/listen!` callback `(fn [tx-report])` that emits the raw
   db-name-tagged `tx` event for `db-name` via `bcast/broadcast!`. READ-ONLY;
   never transacts. Exceptions are swallowed so a broadcast failure can't wedge
   the writer."
  [db-name]
  (let [db-name-str (if (keyword? db-name) (subs (str db-name) 1) (str db-name))]
    (fn [report]
      (try
        (let [request-id (:seon.db/request-id (:tx-meta report))
              r          (assoc (tx-report->ok-map report nil) :request-id request-id)]
          (bcast/broadcast! (ok-event-from-report db-name-str r)))
        (catch Throwable _)))))

;; Register the wire-server's ::raw-broadcast listener as an on-ensure-db hook,
;; so EVERY conn the registry opens gets broadcast wired — without the registry
;; requiring this ns. The reactive engine registers its own ::reactive hook the
;; same way. Runs at every ns load — registration is key-based idempotent
;; (re-registering ::raw-broadcast replaces in place), so reloads can't
;; accumulate copies AND can't strand an emptied hook vector (the 2026-06-10
;; hook-loss bug: a defonce guard here blocked re-registration until JVM
;; restart). Hook failures are caught + logged by `run-on-ensure-db-hooks!`.
(registry/register-on-ensure-db-hook!
 {:seon.server.registry/hook-key ::raw-broadcast
  :seon.server.registry/hook-fn
  (fn [conn db-name]
    (seed-base-schema! conn)
    (d/listen conn ::raw-broadcast (raw-broadcast-listener-fn db-name)))})

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
        ;; Carry request-id through tx-meta so the per-conn ::raw-broadcast
        ;; listener emits it on the pub event (the listener fires on the
        ;; writer thread, after this request thread). ensure-db! seeds the
        ;; :seon.db/request-id attr so :schema-flexibility :write accepts it.
        tx-meta*   (cond-> (or tx-meta-in {})
                     request-id (assoc :seon.db/request-id request-id))
        report     (if (seq tx-meta*)
                     (d/transact conn {:tx-data tx* :tx-meta tx-meta*})
                     (d/transact conn tx*))
        r          (tx-report->ok-map report request-id)]
    ;; Broadcast is NOT imperative here anymore: the per-conn
    ;; `::raw-broadcast` `d/listen!` (installed by the on-ensure-db hook /
    ;; start-req-server!) fires synchronously on commit and emits the
    ;; db-name-tagged tx event. See `raw-broadcast-listener-fn`.
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
          (let [tx*      (coerce-tx-data-for-schema schema tx)
                ;; Carry request-id through tx-meta so the per-conn
                ;; ::raw-broadcast listener emits it on the pub event (it
                ;; fires on the writer thread, after the request thread).
                tx-meta* (cond-> (or tx-meta-in {})
                           request-id (assoc :seon.db/request-id request-id))
                report   (if (seq tx-meta*)
                           (d/transact conn {:tx-data tx* :tx-meta tx-meta*})
                           (d/transact conn tx*))
                r        (assoc (tx-report->ok-map report request-id)
                                :tx-data-raw (:tx-data report))]
            ;; Broadcast is NOT imperative here — the ::raw-broadcast listener
            ;; fires per commit. Response-side request-id flows via r.
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

(defn- resolve-conn-for-req
  "Resolve the target conn for a request from the registry by `agent-id` /
   `db-name`. Returns `{:conn <c>}` on success, `{:conn ambient}` when neither
   key is present (single-DB back-compat), or `{:error <env>}` for an unknown
   agent-id/db-name (typed `not-found`, matching the existing error envelope)."
  [ambient-conn req]
  (let [agent-id (let [a (get req "agent-id")] (when (and a (not= "" a)) a))
        db-name  (some-> (get req "db-name") keyword)
        res      (registry/resolve-conn
                  (cond-> {}
                    agent-id (assoc :seon.agent/id agent-id)
                    db-name  (assoc :seon.server.registry/db-name db-name)))]
    (cond
      (:seon.server.registry/conn res) {:conn (:seon.server.registry/conn res)}
      (:seon.server.registry/error-kind res)
      {:error (err (:seon.server.registry/error-kind res)
                   (:seon.server.registry/error res))}
      ;; ::unresolved? — neither key present → ambient single-DB conn.
      :else {:conn ambient-conn})))

(defn- handle-req [conn req]
  (try
    ;; `ensure-db` is a cluster-lifecycle op with no pre-existing target conn —
    ;; it resolves/creates its own conn from the registry. Everything else
    ;; routes to a conn resolved by agent-id/db-name (or the ambient conn).
    (if (= "ensure-db" (get req "op"))
      (handle-op conn req)
      (let [{:keys [conn error]} (resolve-conn-for-req conn req)]
        (or error (handle-op conn req))))
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
                                  "wire-req-conn")
                     (.setDaemon true)
                     (.start)))
                 (recur))
               (catch java.nio.channels.AsynchronousCloseException _ nil)
               (catch Throwable t
                 (binding [*out* *err*]
                   (println "[req-accept] died:" (.getMessage t))))))
                   "wire-req-accept")
      (.setDaemon true)
      (.start))
    server))

;; ---------- Dev socket REPL ----------
;;
;; Opt-in diagnostic plane. OFF by default — only starts when `--repl-port N`
;; is passed (the Rust host does NOT pass it; it's a dev-only escape hatch).
;; Binds 127.0.0.1 ONLY (loopback) so the REPL is never reachable off-host.
;; Writes the chosen port to a file (like the sockets) so a connecting tool
;; can discover it. One REPL reaches the live `state` atom / conn(s).

(def ^:private repl-port-file "tmp/seon-writer-repl-port")

(defn- start-repl-server!
  "Start a loopback-only Clojure socket REPL on `port`. Returns the
   server-socket so it can be closed on shutdown."
  [port]
  (let [server (srv/start-server
                {:name "seon-writer-repl"
                 :address "127.0.0.1"
                 :port port
                 :accept 'clojure.core.server/repl})]
    (spit (io/file repl-port-file) (str port))
    (.deleteOnExit (io/file repl-port-file))
    server))

;; ---------- Main ----------

(defn ambient-db-name
  "The db-name string the ambient conn broadcasts under (the same value
   `ensure-db!` passed to its `::raw-broadcast` listener). The raw tx-feed
   subscribe ops (`seon.server.boot`) use this to route a `subscribe-tx` with
   no agent-id/db-name to the ambient conn's pub events. Defaults to
   \"default\" when not yet booted (matches `ensure-db!`'s fallback)."
  []
  (or (:ambient-db-name @state) "default"))

(defn -main [& args]
  (let [opts (parse-args args)
        cfg  (store/config-for (opts->config-for-request opts))
        _    (println "[writer] starting with" opts)
        conn (ensure-db! cfg)
        db-name (or (:name cfg) "default")
        ;; The ambient conn is created directly by ensure-db! (outside the
        ;; registry), so the registry's on-ensure-db hooks never fired for it.
        ;; Run them now so the ambient conn ALSO gets the reactive engine's
        ;; ::reactive listener + subscription schema (boot.clj registers that
        ;; hook). ::raw-broadcast is re-installed under the same key (datahike
        ;; replaces, not duplicates) — idempotent.
        _    (registry/run-on-ensure-db-hooks! conn db-name)
        _    (println "[writer] datahike ready; basis-t=" (basis-t-of (d/db conn)))
        pub-server (bcast/start-pub-server! (:pub-sock opts))
        _    (println "[writer] pub socket:" (:pub-sock opts))
        req-server (start-req-server! conn (:req-sock opts))
        _    (println "[writer] req socket:" (:req-sock opts))
        repl-server (when-let [p (:repl-port opts)]
                      (let [s (start-repl-server! p)]
                        (println "[writer] dev REPL (127.0.0.1):" p)
                        s))]
    (reset! state {:conn conn :req-server req-server :pub-server pub-server
                   :repl-server repl-server
                   ;; same db-name ensure-db! gave the ambient ::raw-broadcast
                   ;; listener — the raw tx-feed subscribe ops route to it.
                   :ambient-db-name (or (:name cfg) "default")})
    (println "[writer] ready. PID=" (.pid (java.lang.ProcessHandle/current)))
    (.. (Thread/currentThread) join)))
