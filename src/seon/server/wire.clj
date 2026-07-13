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
   - Echo id: `:seon.store.wire/id` end-to-end — the transport key ==
     the persisted Datahike attr (one id, transport + storage)."
  (:require [clojure.core.server :as srv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.constants :as datahike.constants]
            [datahike.db.interface :as dbi]
            [hasch.core :as hasch]
            [seon.db.id :as id]
            [seon.server.codec :as codec]
            [seon.server.registry :as registry]
            [seon.server.broadcast :as bcast])
  (:import [datahike.db AsOfDB]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))

(declare ambient-db-name)

;; ---------- Configuration ----------

(defn- parse-args [args]
  (loop [acc {:backend "memory"
              :path "data/seon-client-runtime/store"
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
      ;; --repl-port-file: where the dev REPL's bound port is written for
      ;; discovery. Per-supervisor (registry C48) — a shared path let a second
      ;; supervisor's wire-server (bin/acme) clobber the first's file.
      "--repl-port-file" (recur (assoc acc :repl-port-file (second xs)) (drop 2 xs))
      ;; --preflight: a flag (no value). boot/-main intercepts it and runs the
      ;; embedding self-check BEFORE starting the server. parse-args records it
      ;; so the default "Unknown arg" branch no longer exit-2s on it.
      "--preflight" (recur (assoc acc :preflight? true) (drop 1 xs))
      nil acc
      (do (println "Unknown arg:" (first xs)) (System/exit 2)))))

;; ---------- DB lifecycle ----------

(declare raw-broadcast-listener-fn)

(def ^:private base-schema
  [{:db/ident       :seon.store.wire/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :seon.store.wire/request-hash
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :seon.store.wire/protocol-version
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :seon.store.wire.tempid/key-edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :seon.store.wire.tempid/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def ^:private schema-properties
  [:db/valueType :db/cardinality :db/unique :db/isComponent])

(defn- seed-base-schema!
  "Install the exact raw Datahike schema for idempotent wire transactions.

   `:seon.store.wire/id` is the durable, unique logical-write identity on the
   transaction entity. The versioned request hash rejects accidental id reuse,
   while same-transaction tempid markers retain the only response data that
   cannot be derived from transaction history. Existing compatible attrs are not
   re-transacted; an incompatible declaration fails loudly."
  [conn]
  (let [installed (:schema (d/db conn))
        incompatible
        (keep (fn [{:db/keys [ident] :as declaration}]
                (when-let [actual (get installed ident)]
                  (let [expected (select-keys declaration schema-properties)
                        actual   (select-keys actual schema-properties)]
                    (when (not= expected actual)
                      {:seon.store.wire/attribute ident
                       :seon.store.wire/expected expected
                       :seon.store.wire/actual actual}))))
              base-schema)
        missing (filterv #(not (contains? installed (:db/ident %))) base-schema)]
    (when (seq incompatible)
      (throw (ex-info "Wire schema is incompatible with the idempotent protocol."
                      {:seon.store.wire/incompatible (vec incompatible)})))
    (when (seq missing)
      (d/transact conn missing))))

;; ---------- Response helpers ----------

(defn- ok [m] (assoc m :seon.store.wire/ok true))
(defn- err [kind msg]
  {:seon.store.wire/ok false
   :seon.store.wire/error msg
   :seon.store.wire/error-kind kind})

(defn- basis-t-of [db]
  (when db
    (if (instance? AsOfDB db)
      (dbi/-time-point db)
      (dbi/-max-tx db))))

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

;; ---------- Idempotent transaction receipts ----------

(defn- request-hash
  "Map-order-independent content fingerprint of the logical request."
  [request]
  (hasch/uuid request))

(def ^:private internal-tempid-prefix
  "seon.store.wire.tempid/")

(defn- internal-tempid?
  [value]
  (and (string? value) (str/starts-with? value internal-tempid-prefix)))

(defn- tempid-receipts
  [wire-id tempids]
  (let [used (set tempids)]
    (mapv (fn [index tempid]
            (let [marker-id
                  (loop [salt 0]
                    (let [candidate (str internal-tempid-prefix wire-id "/"
                                         index "/" salt)]
                      (if (contains? used candidate)
                        (recur (inc salt))
                        candidate)))]
              {:db/id marker-id
               :seon.store.wire.tempid/key-edn (pr-str tempid)
               :seon.store.wire.tempid/entity tempid}))
          (range)
          tempids)))

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
  ;; reseeding. db-name's VALUE is a string on the wire (the CLUSTER name);
  ;; default backend :file (the settled pod-attachable default — pass
  ;; `backend "memory"` explicitly for a JVM-side ephemeral). On open,
  ;; registry's on-ensure-db hooks install this conn's base schema,
  ;; ::raw-broadcast listener, and optional embedding index.
  (let [db-name (some-> (:seon.store.wire/db-name req) keyword)
        backend (some-> (:seon.store.wire/backend req) keyword)
        path    (:seon.store.wire/path req)]
    (if-not db-name
      (err "protocol" "ensure-db requires :seon.store.wire/db-name")
      (let [entry (registry/ensure-db!
                   (cond-> {:seon.server.registry/db-name db-name
                            :seon.server.registry/backend (or backend :file)}
                     path (assoc :seon.server.registry/path path)))
            conn  (:seon.server.registry/conn entry)]
        (ok {:seon.store.wire/db-name (subs (str db-name) 1)
             :seon.store.wire/basis-t (basis-t-of (d/db conn))})))))

;; ---------- Supervisor-facing cluster-lifecycle ops ----------
;;
;; `list-dbs` / `remove-db` are SUPERVISOR/REPL-surface ops: they ride the
;; host-local UDS req socket (and the 7891 wire REPL), and NOTHING pod-side
;; wraps them — `seon.db` has no function for them and the agent toolkit never
;; sees them, so an agent's capability surface stays one-cluster by
;; construction. `bin/seon cluster destroy` is the caller.

(defmethod handle-op "list-dbs" [_conn _req]
  (let [{:seon.server.registry/keys [sessions]} (registry/list-sessions {})]
    (ok {:seon.store.wire/dbs
         (mapv (fn [{:seon.server.registry/keys [db-name backend path]}]
                 (cond-> {:seon.store.wire/db-name (subs (str db-name) 1)
                          :seon.store.wire/backend (name backend)}
                   path (assoc :seon.store.wire/path path)))
               sessions)})))

(defmethod handle-op "remove-db" [_conn req]
  ;; Release the conn, drop the registry entry, and DELETE the database in
  ;; its store (`registry/delete-db!`). The process's own ambient cluster is
  ;; refused — removing the db under the ambient conn would wedge every
  ;; unrouted request.
  (let [db-name (some-> (:seon.store.wire/db-name req) keyword)]
    (cond
      (nil? db-name)
      (err "protocol" "remove-db requires :seon.store.wire/db-name")

      (= (name db-name) (ambient-db-name))
      (err "protocol" (str "refusing to remove the ambient cluster db: "
                           (name db-name)))

      :else
      (let [{:seon.server.registry/keys [removed? deleted?]}
            (registry/delete-db! {:seon.server.registry/db-name db-name})]
        (ok {:seon.store.wire/db-name (subs (str db-name) 1)
             :seon.store.wire/removed removed?
             :seon.store.wire/deleted deleted?})))))

(defmethod handle-op "q" [conn req]
  (let [query   (:seon.store.wire/query req)
        args    (vec (:seon.store.wire/args req))
        basis-t (:seon.store.wire/basis-t req)
        db      (resolve-db-with-basis-t conn basis-t)
        result  (apply d/q query db args)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  result})))

(def ^:private receipt-attributes
  #{:seon.store.wire.tempid/key-edn
    :seon.store.wire.tempid/entity})

(def ^:private protocol-attributes
  (into receipt-attributes
        #{:seon.store.wire/id
          :seon.store.wire/request-hash
          :seon.store.wire/protocol-version}))

(defn- transaction-attributes
  [tx-data]
  (into #{}
        (mapcat (fn [node]
                  (cond
                    (map? node)
                    (cond-> (filterv qualified-keyword? (keys node))
                      (qualified-keyword? (:db/ident node))
                      (conj (:db/ident node)))

                    (and (vector? node)
                         (#{:db/add :db/retract} (first node))
                         (<= 3 (count node)))
                    [(nth node 2)]

                    :else [])))
        (tree-seq coll? seq tx-data)))

(defn- assert-protocol-fields-free!
  [tx-data tx-meta]
  (let [used (into (set (keys (or tx-meta {})))
                   (transaction-attributes tx-data))
        reserved (vec (sort-by str (filter used protocol-attributes)))]
    (when (seq reserved)
      (throw
       (ex-info "Transaction data may not assert wire-protocol attributes."
                {:seon.server.wire/error
                 :seon.server.wire.error/reserved-attribute
                 :seon.store.wire/attributes reserved})))))

(defn- public-transaction-datoms
  [datoms]
  (filterv (fn [^datahike.datom.Datom datom]
             (not (contains? receipt-attributes (.-a datom))))
           datoms))

(defn- tx-report->ok-map
  [report wire-id]
  (let [db        (:db-after report)
        db-before (:db-before report)
        tx-data   (public-transaction-datoms (:tx-data report))
        wire-data (tx-data->wire tx-data)
        added     (count (filter :added tx-data))
        retracted (count (remove :added tx-data))
        bt        (basis-t-of db)
        bt-before (basis-t-of db-before)
        tempids   (into {}
                        (remove (fn [[tempid _]]
                                  (or (= :db/current-tx tempid)
                                      (internal-tempid? tempid))))
                        (:tempids report))
        tx-meta   (:tx-meta report)]
    {::wire-data wire-data
     ::added added
     ::retracted retracted
     ::basis-t bt
     ::basis-t-before bt-before
     ::tempids tempids
     ::tx-meta tx-meta
     ::wire-id wire-id}))

(defn- ok-event-from-report
  "Build the raw `tx` broadcast event. `db-name` is the committing conn's real
   db-name string (no more hardcoded \"default\"). The wire id comes from the
   commit's tx-meta (`:seon.store.wire/id`) so it survives the async hop to
   the `::raw-broadcast` listener thread — the listener, not the request handler,
   emits the event now (see `raw-broadcast-listener-fn`). It rides the broadcast
   event under `:seon.store.wire/id` (transport key == persisted attr),
   which the pod matches for echo suppression."
  [db-name {wire-data ::wire-data
            added ::added
            retracted ::retracted
            bt ::basis-t
            bt-before ::basis-t-before
            tx-meta ::tx-meta
            wire-id ::wire-id}]
  (cond-> {:seon.store.wire/event "tx"
           :seon.store.wire/basis-t bt
           :seon.store.wire/basis-t-before bt-before
           :seon.store.wire/db-name db-name
           :seon.store.wire/tx-data wire-data
           :seon.store.wire/datoms-added added
           :seon.store.wire/datoms-retracted retracted
           :seon.store.wire/tx-meta tx-meta}
    wire-id (assoc :seon.store.wire/id wire-id)))

;; ---------- paginated since-t replay (the DE-2 lossless-wake fix) ----------
;;
;; The tx FEED is a push over the pub socket: a dropped/missed event is
;; harmless for RENDERING (the subscriber re-reads latest) but FATAL for the
;; WAKE edge (the event IS the trigger to act — drop it and an idle agent sits
;; with unread mail). Live frames in a disconnect gap are simply never
;; delivered. `replay-tx-page` makes the wake edge lossless without building
;; one unbounded reply: the first request captures a fixed upper watermark,
;; subsequent requests retain it, and each page advances an explicit cursor.
;; A reconnecting subscriber keeps its pub socket open and buffers live frames
;; until the final page; replay/live overlap is removed by its monotonic
;; basis-t watermark. Per-subscriber by construction — no pod singleton.

(def ^:private replay-page-size
  "Maximum committed transactions materialized in one replay reply. Pagination
   makes the total gap unbounded while each history reconstruction and Transit
   frame stays bounded."
  256)

(defn- replay-protocol-error
  [message data]
  (throw (ex-info message (assoc data :seon.store.wire/error-kind "protocol"))))

(defn- tx-id
  "Return a history datom's committing transaction id.

   Datahike encodes a retraction's tx as negative; the basis-t identity is its
   absolute value while the signed value remains unchanged in the wire datom."
  [^datahike.datom.Datom datom]
  (Math/abs (long (.-tx datom))))

(defn- page-tx-ids
  "Read at most `page-size + 1` transaction ids in `(since-t, through-t]`.

   Datahike allocates every successful transaction as `(inc :max-tx)`; a failed
   transaction does not advance the head. The reachable branch is therefore a
   contiguous basis-t range after the `tx0` bootstrap sentinel. Deriving the
   page from that fact is both O(page-size) and independent of mutable prior
   transaction metadata. If a later edit makes a transaction unreconstructable
   (for example, retracting its no-history `:db/txInstant`), replay fails at that
   basis-t instead of silently omitting it and advancing. The extra id
   establishes whether another page exists."
  [since-t through-t page-size]
  (->> (range (max (inc since-t) (inc datahike.constants/tx0))
              (inc through-t))
       (take (inc page-size))
       vec))

(defn- history-by-tx
  "Reconstruct only `selected-tx-ids` from immutable transaction history."
  [db since-t selected-tx-ids]
  (let [selected (set selected-tx-ids)]
    (reduce
     (fn [by-tx ^datahike.datom.Datom datom]
       (let [basis-t (tx-id datom)]
         (if (contains? selected basis-t)
           (update by-tx basis-t (fnil conj []) datom)
           by-tx)))
     {}
     (-> db d/history (d/since since-t) (d/datoms :eavt)))))

(defn- replay-events
  "Build ascending live-shaped events for one selected transaction page."
  [db db-name since-t selected-tx-ids]
  (let [by-tx (history-by-tx db since-t selected-tx-ids)]
    (::events
     (reduce
      (fn [{events ::events previous-basis-t ::previous-basis-t} basis-t]
        (let [datoms    (get by-tx basis-t)
              _         (when (empty? datoms)
                          (replay-protocol-error
                           "replay-tx could not reconstruct a selected transaction"
                           {:seon.store.wire/basis-t basis-t}))
              tx-meta   (into {}
                              (map (fn [^datahike.datom.Datom datom]
                                     [(.-a datom) (.-v datom)]))
                              (filter (fn [^datahike.datom.Datom datom]
                                        (= (long (.-e datom)) basis-t))
                                      datoms))
              event     (ok-event-from-report
                         db-name
                         {::wire-data (tx-data->wire datoms)
                          ::added (count (filter :added datoms))
                          ::retracted (count (remove :added datoms))
                          ::basis-t basis-t
                          ::basis-t-before previous-basis-t
                          ::tx-meta (not-empty tx-meta)
                          ::wire-id (:seon.store.wire/id tx-meta)})]
          {::events (conj events event)
           ::previous-basis-t basis-t}))
      {::events [] ::previous-basis-t since-t}
      selected-tx-ids))))

(defn- replay-tx-page*
  "Return one bounded, lossless page of committed tx events.

   `since-t` is the exclusive cursor. On the first request `through-t` is nil,
   so this function captures the connection's current basis-t. Every following
   request MUST send that returned `:seon.store.wire/through-t`; concurrent
   commits are then outside this replay and remain buffered on the live socket.

   The response contains ascending live-shaped `:seon.store.wire/events`, the
   fixed upper watermark, an explicit `:seon.store.wire/continuation-t`, and
   `:seon.store.wire/done?`. A non-final page continues from its last event; a
   final page continues at the fixed upper watermark. Repeating the same
   `(since-t, through-t)` request is deterministic. No range is truncated.

   History reconstruction preserves assertions, retractions (including their
   negative tx value), transaction metadata, and the durable wire id."
  [conn db-name since-t through-t page-size]
  (when-not (and (integer? since-t) (<= 0 since-t))
    (replay-protocol-error
     "replay-tx since-t must be a non-negative integer"
     {:seon.store.wire/since-t since-t}))
  (when-not (and (integer? page-size) (pos? page-size))
    (replay-protocol-error
     "replay-tx page-size must be a positive integer"
     {:seon.store.wire/page-size page-size}))
  (let [db        (d/db conn)
        current-t (long (or (basis-t-of db) 0))
        since-t   (long since-t)
        through-t (if (some? through-t)
                    (do
                      (when-not (and (integer? through-t) (<= 0 through-t))
                        (replay-protocol-error
                         "replay-tx through-t must be a non-negative integer"
                         {:seon.store.wire/through-t through-t}))
                      (long through-t))
                    current-t)]
    (when (> through-t current-t)
      (replay-protocol-error
       "replay-tx through-t is ahead of the writer"
       {:seon.store.wire/through-t through-t
        :seon.store.wire/current-t current-t}))
    (when (> since-t through-t)
      (replay-protocol-error
       "replay-tx since-t is ahead of through-t"
       {:seon.store.wire/since-t since-t
        :seon.store.wire/through-t through-t}))
    (if (= since-t through-t)
      {:seon.store.wire/since-t since-t
       :seon.store.wire/through-t through-t
       :seon.store.wire/continuation-t through-t
       :seon.store.wire/done? true
       :seon.store.wire/events []
       :seon.store.wire/replayed 0}
      (let [candidate-tx-ids (page-tx-ids since-t through-t page-size)
            selected-tx-ids  (vec (take page-size candidate-tx-ids))
            more?            (> (count candidate-tx-ids) page-size)]
        (when (empty? selected-tx-ids)
          (replay-protocol-error
           "replay-tx found no transaction before its upper watermark"
           {:seon.store.wire/since-t since-t
            :seon.store.wire/through-t through-t}))
        (let [events       (replay-events db db-name since-t selected-tx-ids)
              continuation (if more? (peek selected-tx-ids) through-t)]
          {:seon.store.wire/since-t since-t
           :seon.store.wire/through-t through-t
           :seon.store.wire/continuation-t continuation
           :seon.store.wire/done? (not more?)
           :seon.store.wire/events events
           :seon.store.wire/replayed (count events)})))))

(defn replay-tx-page
  "Return the next production-sized replay page.

   `since-t` is exclusive. A nil `through-t` captures the current writer head;
   subsequent calls retain the returned upper watermark. See the response's
   fully namespaced continuation, done, and event facts."
  {:malli/schema
   [:=>
    [:catn
     [:conn :any]
     [:db-name :string]
     [:since-t :int]
     [:through-t [:or :nil :int]]]
    [:map
     [:seon.store.wire/since-t :int]
     [:seon.store.wire/through-t :int]
     [:seon.store.wire/continuation-t :int]
     [:seon.store.wire/done? :boolean]
     [:seon.store.wire/events [:vector :map]]
     [:seon.store.wire/replayed :int]]]}
  [conn db-name since-t through-t]
  (replay-tx-page* conn db-name since-t through-t replay-page-size))

;; ---------- ::raw-broadcast listener (the P1 hook) ----------
;;
;; Broadcast is no longer imperative at the transact call sites. Each conn
;; carries a `d/listen!`-registered `::raw-broadcast` callback that fires
;; synchronously on every commit and emits the db-name-tagged `tx` event. The
;; durable wire id rides tx-meta (`:seon.store.wire/id`) because the listener
;; runs on the writer thread.

(defn raw-broadcast-listener-fn
  "Return a `d/listen!` callback `(fn [tx-report])` that emits the raw
   db-name-tagged `tx` event for `db-name` via `bcast/broadcast!`. READ-ONLY;
   never transacts. Exceptions are swallowed so a broadcast failure can't wedge
   the writer."
  [db-name]
  (let [db-name-str (if (keyword? db-name) (subs (str db-name) 1) (str db-name))]
    (fn [report]
      (try
        (let [wire-id (:seon.store.wire/id (:tx-meta report))
              r       (assoc (tx-report->ok-map report nil) ::wire-id wire-id)]
          (bcast/broadcast! (ok-event-from-report db-name-str r)))
        (catch Throwable _)))))

;; Register the wire-server's ::raw-broadcast listener as an on-ensure-db hook,
;; so EVERY conn the registry opens gets broadcast wired — without the registry
;; requiring this ns. Runs at every ns load — registration is key-based idempotent
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

(defn- ok-response-from-report
  [{wire-data ::wire-data
    added ::added
    retracted ::retracted
    bt ::basis-t
    bt-before ::basis-t-before
    tempids ::tempids
    tx-meta ::tx-meta
    wire-id ::wire-id
    recovered? ::recovered?}]
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
    wire-id    (assoc :seon.store.wire/id wire-id)
    recovered? (assoc :seon.store.wire/recovered? true)))

(defn- committed-wire-transaction
  [db wire-id]
  (d/q '[:find ?tx .
         :in $ ?wire-id
         :where [?tx :seon.store.wire/id ?wire-id]]
       db wire-id))

(defn- transaction-datoms
  [db tx]
  (->> (d/datoms (d/since (d/history db) (dec tx)) :eavt)
       (filterv (fn [^datahike.datom.Datom datom]
                  (= tx (Math/abs (long (.-tx datom))))))))

(defn- recovered-tempids
  [db tx]
  (into {}
        (map (fn [[key-edn entity]]
               [(edn/read-string key-edn) entity]))
        (d/q '[:find ?key-edn ?entity
               :in $ ?tx
               :where
               [?marker :seon.store.wire.tempid/key-edn ?key-edn ?tx]
               [?marker :seon.store.wire.tempid/entity ?entity ?tx]]
             db tx)))

(defn- recovered-generated-eids
  [datoms candidates]
  (when (seq candidates)
    (into {}
          (map (fn [candidate]
                 [(::id/key candidate)
                  (some (fn [^datahike.datom.Datom datom]
                          (when (and (:added datom)
                                     (= (::id/identity-attr candidate)
                                        (.-a datom))
                                     (= (::id/value candidate) (.-v datom)))
                            (.-e datom)))
                        datoms)]))
          candidates)))

(defn- recovered-response
  [db tx wire-id candidates]
  (let [all-datoms (transaction-datoms db tx)
        datoms (public-transaction-datoms all-datoms)
        tx-meta (-> (into {}
                          (map (fn [^datahike.datom.Datom datom]
                                 [(.-a datom) (.-v datom)]))
                          (filter (fn [^datahike.datom.Datom datom]
                                    (= tx (.-e datom)))
                                  all-datoms)))
        response (ok-response-from-report
                  {::wire-data (tx-data->wire datoms)
                   ::added (count (filter :added datoms))
                   ::retracted (count (remove :added datoms))
                   ::basis-t tx
                   ::basis-t-before (dec tx)
                   ::tempids (recovered-tempids db tx)
                   ::tx-meta (not-empty tx-meta)
                   ::wire-id wire-id
                   ::recovered? true})
        generated-eids (recovered-generated-eids datoms candidates)]
    (cond-> response
      (seq generated-eids)
      (assoc :seon.store.wire/generated-eids generated-eids))))

(defn- id-reuse-error
  [wire-id expected-hash actual-hash]
  (ex-info "A wire transaction id was reused for different transaction data."
           {:seon.server.wire/error :seon.server.wire.error/id-reuse
            :seon.store.wire/id wire-id
            :seon.store.wire/expected-request-hash expected-hash
            :seon.store.wire/actual-request-hash actual-hash}))

(defn- recover-committed
  [db tx wire-id expected-hash candidates]
  (let [actual-hash (:seon.store.wire/request-hash (d/entity db tx))]
    (if (= expected-hash actual-hash)
      (recovered-response db tx wire-id candidates)
      (throw (id-reuse-error wire-id expected-hash actual-hash)))))

(defn- transact-once!
  "Commit or recover one logical wire transaction under its durable id."
  [conn request]
  (locking conn
    (let [tx-data     (:seon.store.wire/tx-data request)
          tx-meta     (:seon.store.wire/tx-meta request)
          wire-id     (:seon.store.wire/id request)
          candidates  (:seon.store.wire/generated-candidates request)
          generated?  (contains? request
                                 :seon.store.wire/generated-candidates)
          _           (assert-protocol-fields-free! tx-data tx-meta)
          db-value    (d/db conn)
          schema      (:schema db-value)
          tx0         (coerce-tx-data-for-schema schema tx-data)
          fingerprint (when wire-id
                        (request-hash
                         {:seon.store.wire.request/version 2
                          :seon.store.wire.request/tx-data tx-data
                          :seon.store.wire.request/tx-meta (or tx-meta {})
                          :seon.store.wire.request/generated-candidates
                          (or candidates [])}))
          recover-current
          (fn []
            (let [db (d/db conn)]
              (when-let [tx (and wire-id
                                 (committed-wire-transaction db wire-id))]
                (recover-committed db tx wire-id fingerprint candidates))))]
      (or (recover-current)
          (let [caller-tempids (id/transaction-tempids
                                {::id/db-value db-value
                                 ::id/transaction-data tx0})
                tx*            (augment-tx conn tx0)
                tx-with-receipt (into (vec tx*)
                                      (tempid-receipts wire-id caller-tempids))
                tx-meta*       (cond-> (or tx-meta {})
                                 wire-id
                                 (assoc :seon.store.wire/id wire-id
                                        :seon.store.wire/request-hash
                                        fingerprint
                                        :seon.store.wire/protocol-version 2))
                transaction    (cond-> {:tx-data tx-with-receipt}
                                 (seq tx-meta*) (assoc :tx-meta tx-meta*)
                                 generated?
                                 (assoc ::id/generated-candidates candidates))]
            (try
              (let [report (d/transact conn transaction)
                    response (ok-response-from-report
                              (tx-report->ok-map report wire-id))
                    generated-eids (::id/generated-eids report)]
                (cond-> response
                  (some? generated-eids)
                  (assoc :seon.store.wire/generated-eids generated-eids)))
              (catch Throwable throwable
                ;; Another writer process may have won the unique-id race, or
                ;; this process may have committed before losing its callback.
                ;; The durable receipt is authoritative in either case.
                (or (recover-current)
                    (throw throwable)))))))))

;; Generated ids use this same transaction operation. The registry configures
;; Datahike's serialized writer with `seon.db.id`; this handler only forwards
;; its manifest and translates an exact candidate conflict for the wire.

(defn- generated-candidate-conflict
  [candidate]
  (assoc (err "generated-candidate-conflict"
              "a generated identity candidate is already in use")
         :seon.store.wire/generated-candidate candidate))

(defmethod handle-op "transact" [conn req]
  (let [wire-id     (let [candidate (:seon.store.wire/id req)]
                      (when (and (string? candidate)
                                 (not (str/blank? candidate)))
                        candidate))
        candidates  (:seon.store.wire/generated-candidates req)
        generated?  (contains? req
                               :seon.store.wire/generated-candidates)
        transaction-request (assoc req :seon.store.wire/id wire-id)]
    (if-not wire-id
      (err "protocol" "transact requires a nonblank :seon.store.wire/id")
      (try
        (when generated?
          (id/assert-allocation-writer! conn))
        (ok (transact-once! conn transaction-request))
        (catch Throwable throwable
          (let [server-error (:seon.server.wire/error (ex-data throwable))]
            (cond
              (= :seon.server.wire.error/id-reuse server-error)
              (err "wire-id-conflict" (.getMessage throwable))

              (= :seon.server.wire.error/reserved-attribute server-error)
              (err "protocol" (.getMessage throwable))

              generated?
              (let [classified (id/classify-allocation-error
                                {::id/generated-candidates candidates
                                 ::id/throwable throwable})]
                (case (::id/error-status classified)
                  :seon.db.id/candidate-conflict
                  (generated-candidate-conflict
                   (::id/generated-candidate classified))

                  :seon.db.id/protocol-error
                  (err "protocol" (::id/message classified))

                  :seon.db.id/unrelated
                  (throw throwable)))

              :else
              (throw throwable))))))))

(defmethod handle-op "pull" [conn req]
  (let [selector (:seon.store.wire/selector req)
        eid      (:seon.store.wire/eid req)
        basis-t  (:seon.store.wire/basis-t req)
        db       (resolve-db-with-basis-t conn basis-t)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (d/pull db selector eid)})))

(defmethod handle-op "schema" [conn _req]
  (let [db (d/db conn)]
    (ok {:seon.store.wire/basis-t (basis-t-of db)
         :seon.store.wire/result  (:schema db)})))

(defn- resolve-conn-for-req
  "Resolve a request's target connection by `db-name`.

   Returns `{:conn <c>}` on success, `{:conn ambient}` when no db-name is
   present, or `{:error <env>}` for an unknown db-name."
  [ambient-conn req]
  (let [db-name  (some-> (:seon.store.wire/db-name req) keyword)
        res      (registry/resolve-conn
                  (cond-> {}
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
    ;; routes to a conn resolved by db-name (or the ambient conn).
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
;; Writes the chosen port to a PER-SUPERVISOR file (like the sockets) so a
;; connecting tool can discover it. One REPL reaches the live `state` atom /
;; conn(s). The path is per-supervisor (registry C48): a single shared
;; `tmp/seon-writer-repl-port` was clobbered by whichever cluster's JVM
;; (default vs acme) started LAST, routing file-based consumers to the wrong
;; writer. Resolution: `--repl-port-file` arg > `$SEON_WRITER_REPL_PORT_FILE`
;; env > `tmp/seon-writer-repl-port-<db-name>` (db-name = the cluster name,
;; so even a bare launch is collision-free).

(defn- repl-port-file
  "Per-supervisor REPL port-file path: arg > env > derived from db-name."
  [{:keys [opts db-name]}]
  (or (:repl-port-file opts)
      (System/getenv "SEON_WRITER_REPL_PORT_FILE")
      (str "tmp/seon-writer-repl-port-" db-name)))

(defn- start-repl-server!
  "Start a loopback-only Clojure socket REPL on `port`, writing the port
   to `port-file` for discovery. Returns the server-socket so it can be
   closed on shutdown."
  [port port-file]
  (let [server (srv/start-server
                {:name "seon-writer-repl"
                 :address "127.0.0.1"
                 :port port
                 :accept 'clojure.core.server/repl})]
    (spit (io/file port-file) (str port))
    (.deleteOnExit (io/file port-file))
    server))

;; ---------- Main ----------

(defn ambient-db-name
  "The db-name string the ambient conn broadcasts under (the same value
   `ensure-db!` passed to its `::raw-broadcast` listener). The tx-feed ops
   (`seon.server.boot`) use this to route a `replay-tx` with
   no db-name to the ambient conn's pub events. Defaults to \"default\" when not
   yet booted (matches `ensure-db!`'s fallback)."
  []
  (or (:ambient-db-name @state) "default"))

(defn -main [& args]
  ;; VERY FIRST statement (consumer ask 37): a breadcrumb before any other
  ;; work, so even a pre-`-main` death (e.g. a make-classpath2 hiccup in the
  ;; downstream launcher's pre-exec window) is distinguishable from a writer
  ;; that never started — an empty wire.log means we died BEFORE this line.
  (println "[writer] booting pid=" (.pid (java.lang.ProcessHandle/current)))
  (let [opts (parse-args args)
        ;; db-name = the CLUSTER NAME (registry C15) — `--db-name` from the
        ;; supervisor (bin/seon passes the basename of $SEON_CLUSTER_DIR),
        ;; default "default". Never a socket-path artifact.
        db-name-kw (keyword (or (:db-name opts) "default"))
        db-name    (name db-name-kw)
        _    (println "[writer] starting with" opts)
        ;; The ambient conn is a REGISTRY entry like every other cluster db —
        ;; one open mechanism. ensure-db! creates/connects and fires the
        ;; on-ensure-db hooks (::raw-broadcast + schema/index seeds),
        ;; and db-name-routed requests to this cluster resolve to the SAME
        ;; conn the unrouted (ambient) path uses.
        entry (registry/ensure-db!
               (cond-> {:seon.server.registry/db-name db-name-kw
                        :seon.server.registry/backend (keyword (:backend opts))}
                 (and (:path opts) (not= "memory" (:backend opts)))
                 (assoc :seon.server.registry/path (:path opts))))
        conn  (:seon.server.registry/conn entry)
        _    (println "[writer] datahike ready; basis-t=" (basis-t-of (d/db conn)))
        pub-server (bcast/start-pub-server! (:pub-sock opts))
        _    (println "[writer] pub socket:" (:pub-sock opts))
        req-server (start-req-server! conn (:req-sock opts))
        _    (println "[writer] req socket:" (:req-sock opts))
        repl-server (when-let [p (:repl-port opts)]
                      (let [pf (repl-port-file {:opts opts :db-name db-name})
                            s  (start-repl-server! p pf)]
                        (println "[writer] dev REPL (127.0.0.1):" p "port-file:" pf)
                        s))]
    (reset! state {:conn conn :req-server req-server :pub-server pub-server
                   :repl-server repl-server
                   ;; the cluster name — the tx-feed replay op and the
                   ;; remove-db ambient guard route/compare against it.
                   :ambient-db-name db-name})
    (println "[writer] ready. PID=" (.pid (java.lang.ProcessHandle/current)))
    (.. (Thread/currentThread) join)))
