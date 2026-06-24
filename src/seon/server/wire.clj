(ns seon.server.wire
  "Sidecar JVM writer: owns the single Datahike connection and answers requests
   over a UDS socket. Broadcasts tx events on a separate UDS socket.

   Wire protocol:
   - Uniform Transit-JSON frame (`seon.server.codec`): one map with
     `:seon.store.wire/*` keyword keys and NATIVE Clojure values — op, ok,
     basis-t, query, args, result, tx-data, tx-meta, tempids, … all in one
     encode/decode. No inner Transit strings.
   - Datom shape: 5-vector [e a v t op] — a and v are NATIVE (keyword attr,
     any value); e, t are ints; op is bool.
   - Echo id: `:seon.store.wire/write-id` end-to-end — the transport key ==
     the persisted Datahike attr (one id, transport + storage)."
  (:require [clojure.core.server :as srv]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [konserve-jdbc.core]
            [seon.server.codec :as codec]
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
      ;; --preflight: a flag (no value). boot/-main intercepts it and runs the
      ;; embedding self-check BEFORE starting the server. parse-args records it
      ;; so the default "Unknown arg" branch no longer exit-2s on it.
      "--preflight" (recur (assoc acc :preflight? true) (drop 1 xs))
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
   the agent's domain schema. Currently just `:seon.store.wire/write-id` — the
   wire-protocol per-write ECHO-SUPPRESSION id: the pod mints a UUID per
   forwarded write, the wire-server threads it into the committed tx-meta under
   this attr, and the pod recognizes its own tx on the broadcast feed and skips
   re-firing local listeners. Declared here so `:schema-flexibility :write`
   accepts it in tx-meta (the channel that carries it to the ::raw-broadcast
   listener thread). This is a wire-protocol field, NOT a seon.db Malli-domain
   attr — the writer uses RAW datahike schema, so this seed IS its declaration.
   Idempotent: a re-seed transacts the same :db/ident, a no-op datahike upsert."
  [conn]
  (d/transact conn [{:db/ident       :seon.store.wire/write-id
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

(defn- ok [m] (assoc m :seon.store.wire/ok true))
(defn- err [kind msg]
  {:seon.store.wire/ok false
   :seon.store.wire/error msg
   :seon.store.wire/error-kind kind})

(defn- basis-t-of [db]
  (some-> db :max-tx))

;; ---------- Datom wire shape ----------

(defn- datom->wire
  "Convert a datahike Datom record to the 5-vector [e a v t op]. a and v are
   NATIVE under the uniform Transit frame — the keyword attribute and the
   value (any Clojure value: instant, keyword, BigInt, …) round-trip with
   their types intact through one decode."
  [^datahike.datom.Datom d]
  [(.-e d)
   (.-a d)
   (.-v d)
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

;; ---------- Embed-on-write seam ----------
;;
;; A tx-augmenter `(fn [db tx-data] -> tx-data')` that `seon.embed` installs at
;; load time (via `register-tx-augmenter!`) to embed-on-write any entity
;; carrying a registered trigger-attr. It scans the incoming tx-data, embeds the
;; changed docs through Gemini BEFORE this handler's `d/transact` (off the write
;; lock — the per-conn request thread, not the listener), and appends
;; `:seon/embedding` + `:seon.embed/source-hash` assertions.
;;
;; Kept as a seam (not a hard `seon.embed` require) so `wire.clj` still loads on
;; the plain :test/:dev JVM WITHOUT the Proximum `--add-modules
;; jdk.incubator.vector` classpath. On the :writer classpath, `seon.server.boot`
;; loads `seon.embed`, which installs the real augmenter here. When absent, the
;; default is identity — transact is unchanged. Exceptions in the augmenter are
;; swallowed (embedding must never wedge a write): a failed embed falls back to
;; the un-augmented tx so the primary write still commits.

(defonce ^:private !tx-augmenter (atom (fn [_db tx-data] tx-data)))

(defn register-tx-augmenter!
  "Install the embed-on-write tx-augmenter `(fn [db tx-data] -> tx-data')`.
   Idempotent — the latest registration wins (a reload of `seon.embed`
   re-installs in place). Returns nil."
  [augment-fn]
  (reset! !tx-augmenter augment-fn)
  nil)

(defn- augment-tx
  "Run the registered tx-augmenter over `tx*` with the conn's current db. Embed
   failures fall back to the un-augmented tx so the primary write still
   commits."
  [conn tx*]
  (try
    (@!tx-augmenter (d/db conn) (vec tx*))
    (catch Throwable t
      (binding [*out* *err*]
        (println "[embed] tx-augmenter failed; transacting un-augmented:"
                 (.getMessage t)))
      tx*)))

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

(defmulti handle-op (fn [_conn req] (:seon.store.wire/op req)))

(defmethod handle-op :default [_ req]
  (err "protocol" (str "unknown op: " (pr-str (:seon.store.wire/op req)))))

(defmethod handle-op "ping" [_ _]
  (ok {:seon.store.wire/pong true}))

(defmethod handle-op "ensure-db" [_conn req]
  ;; Materialize (or look up) a cluster's DB. Idempotent — a re-ensure of the
  ;; same db-name returns the existing conn's current basis-t without
  ;; reseeding. db-name's VALUE is a string on the wire; default backend
  ;; :memory for testability, override via :seon.store.wire/backend. On open,
  ;; registry's on-ensure-db hook installs this conn's ::raw-broadcast listener
  ;; (+ any ::reactive one).
  (let [db-name (some-> (:seon.store.wire/db-name req) keyword)
        backend (some-> (:seon.store.wire/backend req) keyword)
        path    (:seon.store.wire/path req)]
    (if-not db-name
      (err "protocol" "ensure-db requires :seon.store.wire/db-name")
      (let [entry (registry/ensure-db!
                   (cond-> {:seon.server.registry/db-name db-name
                            :seon.server.registry/backend (or backend :memory)}
                     path (assoc :seon.server.registry/path path)))
            conn  (:seon.server.registry/conn entry)]
        (ok {:seon.store.wire/db-name (subs (str db-name) 1)
             :seon.store.wire/basis-t (basis-t-of (d/db conn))})))))

(defmethod handle-op "q" [conn req]
  (let [query   (:seon.store.wire/query req)
        args    (vec (:seon.store.wire/args req))
        basis-t (:seon.store.wire/basis-t req)
        db      (resolve-db-with-basis-t conn basis-t)
        result  (apply d/q query db args)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  result})))

(defn- tx-report->ok-map
  [report write-id]
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
    {:wire-data wire-data
     :added     added
     :retracted retracted
     :bt        bt
     :bt-before bt-before
     :tempids   tempids
     :tx-meta   tx-meta
     :write-id  write-id}))

(defn- ok-event-from-report
  "Build the raw `tx` broadcast event. `db-name` is the committing conn's real
   db-name string (no more hardcoded \"default\"). `write-id` comes from the
   commit's tx-meta (`:seon.store.wire/write-id`) so it survives the async hop to
   the `::raw-broadcast` listener thread — the listener, not the request handler,
   emits the event now (see `raw-broadcast-listener-fn`). It rides the broadcast
   event under `:seon.store.wire/write-id` (transport key == persisted attr),
   which the pod matches for echo suppression."
  [db-name {:keys [wire-data added retracted bt bt-before tx-meta write-id]}]
  (cond-> {:seon.store.wire/event "tx"
           :seon.store.wire/basis-t bt
           :seon.store.wire/basis-t-before bt-before
           :seon.store.wire/db-name db-name
           :seon.store.wire/tx-data wire-data
           :seon.store.wire/datoms-added added
           :seon.store.wire/datoms-retracted retracted
           :seon.store.wire/tx-meta tx-meta}
    write-id (assoc :seon.store.wire/write-id write-id)))

;; ---------- ::raw-broadcast listener (the P1 hook) ----------
;;
;; Broadcast is no longer imperative at the transact call sites. Each conn
;; carries a `d/listen!`-registered `::raw-broadcast` callback that fires
;; synchronously on every commit and emits the db-name-tagged `tx` event. This
;; is the seam the reactive engine plugs a SECOND listener (`::reactive`) into
;; — distinct keys, both fire off the same TxReport. The wire write-id rides
;; tx-meta (`:seon.store.wire/write-id`) because the listener runs on the writer
;; thread.

(defn raw-broadcast-listener-fn
  "Return a `d/listen!` callback `(fn [tx-report])` that emits the raw
   db-name-tagged `tx` event for `db-name` via `bcast/broadcast!`. READ-ONLY;
   never transacts. Exceptions are swallowed so a broadcast failure can't wedge
   the writer."
  [db-name]
  (let [db-name-str (if (keyword? db-name) (subs (str db-name) 1) (str db-name))]
    (fn [report]
      (try
        (let [write-id (:seon.store.wire/write-id (:tx-meta report))
              r        (assoc (tx-report->ok-map report nil) :write-id write-id)]
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
                                        tempids tx-meta write-id]}]
  ;; One uniform Transit frame: the structured fields ARE the response — the
  ;; pod reads them directly (no separate Transit-string `payload` to double-
  ;; decode). basis-t / datoms-* are ints; tempids / tx-meta / tx-data are
  ;; native.
  (cond-> {:seon.store.wire/basis-t           bt
           :seon.store.wire/basis-t-before    bt-before
           :seon.store.wire/tempids           tempids
           :seon.store.wire/tx-data           wire-data
           :seon.store.wire/tx-meta           tx-meta
           :seon.store.wire/datoms-added      added
           :seon.store.wire/datoms-retracted  retracted}
    write-id (assoc :seon.store.wire/write-id write-id)))

(defmethod handle-op "transact" [conn req]
  (let [tx         (:seon.store.wire/tx-data req)
        tx-meta-in (:seon.store.wire/tx-meta req)
        write-id   (let [r (:seon.store.wire/write-id req)] (when (and r (not= "" r)) r))
        schema     (schema-of conn)
        tx0        (coerce-tx-data-for-schema schema tx)
        ;; Embed-on-write: append :seon/embedding + :seon.embed/source-hash
        ;; for entities carrying a registered trigger-attr whose composed-doc
        ;; hash changed. Gemini call happens HERE (request thread, before
        ;; d/transact), never inside the conn/listener. No-op when no trigger
        ;; attrs present or no augmenter installed.
        tx*        (augment-tx conn tx0)
        ;; Carry the wire write-id through tx-meta so the per-conn
        ;; ::raw-broadcast listener emits it on the pub event (the listener
        ;; fires on the writer thread, after this request thread). seed-base-schema!
        ;; declares the :seon.store.wire/write-id attr so :schema-flexibility
        ;; :write accepts it.
        tx-meta*   (cond-> (or tx-meta-in {})
                     write-id (assoc :seon.store.wire/write-id write-id))
        report     (if (seq tx-meta*)
                     (d/transact conn {:tx-data tx* :tx-meta tx-meta*})
                     (d/transact conn tx*))
        r          (tx-report->ok-map report write-id)]
    ;; Broadcast is NOT imperative here anymore: the per-conn
    ;; `::raw-broadcast` `d/listen!` (installed by the on-ensure-db hook /
    ;; start-req-server!) fires synchronously on commit and emits the
    ;; db-name-tagged tx event. See `raw-broadcast-listener-fn`.
    (ok (ok-response-from-report r))))

(defmethod handle-op "transact-batch" [conn req]
  (let [tx-data-list (vec (:seon.store.wire/tx-data-list req))
        tx-meta-list (some-> (:seon.store.wire/tx-meta-list req) vec)
        write-ids    (:seon.store.wire/write-ids req)
        n            (count tx-data-list)
        schema       (schema-of conn)
        per-tx-report
        (fn [idx tx tx-meta-in write-id]
          (let [tx0      (coerce-tx-data-for-schema schema tx)
                ;; Embed-on-write (per-tx in the batch): same seam as the
                ;; single "transact" handler. Re-reads (d/db conn) each tx so
                ;; an earlier batch tx's stored hash is visible to a later one.
                tx*      (augment-tx conn tx0)
                ;; Carry the wire write-id through tx-meta so the per-conn
                ;; ::raw-broadcast listener emits it on the pub event (it
                ;; fires on the writer thread, after the request thread).
                tx-meta* (cond-> (or tx-meta-in {})
                           write-id (assoc :seon.store.wire/write-id write-id))
                report   (if (seq tx-meta*)
                           (d/transact conn {:tx-data tx* :tx-meta tx-meta*})
                           (d/transact conn tx*))
                r        (tx-report->ok-map report write-id)]
            ;; Broadcast is NOT imperative here — the ::raw-broadcast listener
            ;; fires per commit. Response-side write-id flows via r.
            (assoc (ok-response-from-report r) :seon.store.wire/index idx)))]
    (loop [idx 0 wire-reports (transient [])]
      (if (>= idx n)
        (ok {:seon.store.wire/reports (persistent! wire-reports)
             :seon.store.wire/applied idx
             :seon.store.wire/total   n})
        (let [tx         (nth tx-data-list idx)
              tx-meta-in (some-> tx-meta-list (nth idx nil))
              write-id   (let [r (some-> write-ids (nth idx nil))]
                           (when (and r (not= "" r)) r))]
          (let [result (try
                         {:ok (per-tx-report idx tx tx-meta-in write-id)}
                         (catch clojure.lang.ExceptionInfo e
                           {:err {:kind "datahike"
                                  :msg  (str (.getMessage e) " " (pr-str (ex-data e)))}})
                         (catch Throwable t
                           {:err {:kind "internal"
                                  :msg  (.toString t)}}))]
            (if-let [ok-rep (:ok result)]
              (recur (inc idx) (conj! wire-reports ok-rep))
              (let [{:keys [kind msg]} (:err result)]
                (ok {:seon.store.wire/reports    (persistent! wire-reports)
                     :seon.store.wire/applied    idx
                     :seon.store.wire/total      n
                     :seon.store.wire/failed-at  idx
                     :seon.store.wire/error      msg
                     :seon.store.wire/error-kind kind})))))))))

(defmethod handle-op "pull" [conn req]
  (let [selector (:seon.store.wire/selector req)
        eid      (:seon.store.wire/eid req)
        basis-t  (:seon.store.wire/basis-t req)
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (d/pull db selector eid)})))

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
  (let [eid      (:seon.store.wire/ref req)
        sel-raw  (:seon.store.wire/selector req)
        selector (or sel-raw '[*])
        depth    (long (or (:seon.store.wire/depth req) 1))
        basis-t  (:seon.store.wire/basis-t req)
        db       (resolve-db-with-basis-t conn basis-t)
        raw      (try
                   (d/pull db selector eid)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :entity-id/missing (:error (ex-data e)))
                       nil
                       (throw e))))
        result   (when raw (expand-component-refs raw depth))]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  result})))

(defmethod handle-op "pull-many" [conn req]
  (let [selector (:seon.store.wire/selector req)
        eids     (vec (:seon.store.wire/eids req))
        basis-t  (:seon.store.wire/basis-t req)
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (d/pull-many db selector eids)})))

(defmethod handle-op "schema" [conn _req]
  (let [db (d/db conn)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (:schema db)})))

(defmethod handle-op "reverse-schema" [conn _req]
  (let [db (d/db conn)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (:rschema db)})))

(defmethod handle-op "db-filter" [conn req]
  (let [pred-query (:seon.store.wire/pred-query req)
        args       (vec (:seon.store.wire/args req))
        db         (d/db conn)
        rows       (apply d/q pred-query db args)
        keep-eids  (into #{} (map first) rows)
        filtered-db
        (d/filter db (fn [_db ^datahike.datom.Datom d]
                       (contains? keep-eids (.-e d))))
        bt         (basis-t-of db)
        handle     (register-filtered-db! filtered-db bt)]
    (ok {:seon.store.wire/basis-t bt
         :seon.store.wire/handle  handle
         :seon.store.wire/kept    (count keep-eids)})))

(defmethod handle-op "q-filtered" [_conn req]
  (let [handle  (long (:seon.store.wire/handle req))
        query   (:seon.store.wire/query req)
        args    (vec (:seon.store.wire/args req))]
    (if-let [entry (get @filtered-dbs handle)]
      (let [db (:db entry)]
        (ok {:seon.store.wire/basis-t (:basis-t entry)
             :seon.store.wire/result  (apply d/q query db args)}))
      (err "not-found" (str "no filtered-db handle: " handle)))))

(defmethod handle-op "filter-release" [_conn req]
  (let [handle (long (:seon.store.wire/handle req))]
    (swap! filtered-dbs dissoc handle)
    (ok {:seon.store.wire/released true :seon.store.wire/handle handle})))

(defn- resolve-conn-for-req
  "Resolve the target conn for a request from the registry by `agent-id` /
   `db-name`. Returns `{:conn <c>}` on success, `{:conn ambient}` when neither
   key is present (single-DB back-compat), or `{:error <env>}` for an unknown
   agent-id/db-name (typed `not-found`, matching the existing error envelope)."
  [ambient-conn req]
  (let [agent-id (let [a (:seon.store.wire/agent-id req)] (when (and a (not= "" a)) a))
        db-name  (some-> (:seon.store.wire/db-name req) keyword)
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
    (if (= "ensure-db" (:seon.store.wire/op req))
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
  ;; VERY FIRST statement (consumer ask 37): a breadcrumb before any other
  ;; work, so even a pre-`-main` death (e.g. a make-classpath2 hiccup in the
  ;; downstream launcher's pre-exec window) is distinguishable from a writer
  ;; that never started — an empty wire.log means we died BEFORE this line.
  (println "[writer] booting pid=" (.pid (java.lang.ProcessHandle/current)))
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
