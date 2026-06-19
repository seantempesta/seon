(ns seon.server.boot
  "Wire-server boot entry — the platform-lane glue that composes the listener
   contributors WITHOUT coupling them to each other.

   Why this ns exists: `seon.server.wire` deliberately does NOT `require`
   `seon.server.reactive` (the P1 decoupling — platform must boot/test the
   wire-server with no reactive ns present). But at runtime both need to load so
   each registers its OWN `register-on-ensure-db-hook!` (wire → `::raw-broadcast`,
   reactive → `::reactive`) and its OWN schema. Requiring reactive from a thin
   boot ns — not from `wire.clj` — keeps `wire.clj` reactive-free while ensuring
   reactive is actually on the load path when the server starts.

   This ns is ALSO the home of the reactive op-wrappers (`handle-op` defmethods),
   as the original boot docstring planned — they need both `wire` (the
   multimethod) and `reactive`/`broadcast` (the pure fns + the pub fanout), so
   defining them here keeps `wire.clj` decoupled. Two families of ops live here:

   1. RAW TX FEED (the guest `listen!` model — `seon.client-runtime.db/listen!`
      → `wit/subscribe-tx-call` / `next-tx-event-call`): `subscribe-tx`,
      `next-tx-event`, `unsubscribe-tx`. A subscription is a per-DB
      `broadcast/subscribe!` callback feeding a bounded queue; `next-tx-event`
      drains it with a short bounded wait. This is what the live two-pane
      webview's `listen!` loop polls.

   2. QUERY SUBSCRIPTIONS (the reactive engine — changed query rows):
      `register-subscription` / `unregister-subscription`, delegating to
      `seon.server.reactive`. The `::reactive` on-ensure-db hook (installed here)
      gives every conn the registry opens a reactive engine wired alongside the
      raw broadcaster.

   The wire-server is launched via `:writer` → `-m seon.server.boot` (deps.edn);
   `-main` delegates straight to `wire/-main`."
  (:require [datahike.api :as d]
            ;; Register the :proximum secondary-index type with datahike's
            ;; `datahike.index.secondary` multimethods BEFORE any cluster conn
            ;; opens. seon.embed/install! bakes a :proximum index into a
            ;; cluster store's schema; without this require, restoring that
            ;; store on a fresh wire-server boot throws "Unknown secondary
            ;; index type: :proximum" before any REPL/session can require it.
            ;; Boot is the glue ns that owns the writer's full load path, so
            ;; the require lives here (wire.clj stays secondary-index-free).
            [datahike.index.secondary.proximum]
            [seon.server.wire :as wire]
            [seon.server.registry :as registry]
            ;; seon.embed installs the embed-on-write tx-augmenter into
            ;; wire.clj AND registers the ::embed on-ensure-db hook (install! +
            ;; bounded backfill). It MUST be required BEFORE seon.server.reactive
            ;; so the ::embed hook registers (and therefore FIRES) before
            ;; ::reactive — embeddings are part of the write, reactive summaries
            ;; derive from the post-write db. Registration order = fire order.
            [seon.embed]
            [seon.server.broadcast :as bcast]
            [seon.server.transit :as transit]
            [seon.server.reactive :as reactive]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent ConcurrentLinkedQueue]
           [java.util.concurrent.atomic AtomicInteger])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Raw tx-feed subscriptions (the guest `listen!` model)
;;
;; A `subscribe-tx` opens a per-handle bounded queue and a `broadcast/subscribe!`
;; callback for the target db-name; every committed tx for that db-name (emitted
;; by the conn's `::raw-broadcast` listener) is enqueued. `next-tx-event` drains
;; one event with a short bounded wait — exactly the bounded-poll contract the
;; guest's loop expects (it swallows the `no-event` timeout and re-loops).
;;
;; The event delivered is the SAME map `seon.server.wire`'s broadcaster builds
;; (`ok-event-from-report`): string keys, `tx-data` as [e a-transit v-transit
;; t op], `tx-meta`/`request-id` carried through. The guest decodes the Transit
;; a/v fields itself.
;; ---------------------------------------------------------------------------

;; handle -> {:db-name str :sub-id <bcast-sub-id> :queue ConcurrentLinkedQueue}
(defonce ^:private !tx-subs (atom {}))
(defonce ^:private !tx-sub-counter (atom 0))

;; A bounded queue so a guest that never drains can't grow memory without
;; limit. When full, the oldest event is dropped (the guest catches up via
;; basis-t on its next read; a dropped raw event is not a correctness hazard —
;; it only means the listener missed an intermediate frame).
(def ^:private max-queued-events 1024)

(defn- db-name-for-req
  "The broadcast db-name a `subscribe-tx` should listen on, derived the SAME way
   request routing resolves a conn: agent-id → registry db-name, else explicit
   db-name, else the ambient conn's db-name. Returns the db-name STRING the
   `::raw-broadcast` listener tags events with (keyword db-names are stringified
   without the leading colon, matching `raw-broadcast-listener-fn`)."
  [req]
  (let [agent-id (let [a (get req "agent-id")] (when (and a (not= "" a)) a))
        db-name  (some-> (get req "db-name") keyword)
        kw->str  (fn [kw] (if (keyword? kw) (subs (str kw) 1) (str kw)))
        resolved (registry/resolve-conn
                  (cond-> {}
                    agent-id (assoc :seon.agent/id agent-id)
                    db-name  (assoc :seon.server.registry/db-name db-name)))]
    (cond
      (:seon.server.registry/db-name resolved) (kw->str (:seon.server.registry/db-name resolved))
      ;; ::unresolved? (neither key) → ambient conn's db-name
      :else (wire/ambient-db-name))))

(defmethod wire/handle-op "subscribe-tx" [_conn req]
  (let [db-name (db-name-for-req req)
        queue   (ConcurrentLinkedQueue.)
        ;; Track size in an AtomicInteger — ConcurrentLinkedQueue.size() is
        ;; O(n) (it traverses the queue); this callback runs on EVERY commit
        ;; for the db-name, so an O(n) size check per event is real overhead.
        qsize   (AtomicInteger. 0)
        handle  (swap! !tx-sub-counter inc)
        sub-id  (bcast/subscribe!
                 db-name
                 (fn [event]
                   ;; Bounded: drop oldest when full (basis-t lets the guest
                   ;; recover a missed intermediate frame). O(1) size via the
                   ;; AtomicInteger counter.
                   (when (>= (.get qsize) max-queued-events)
                     (when (.poll queue) (.decrementAndGet qsize)))
                   (.offer queue event)
                   (.incrementAndGet qsize)))]
    (swap! !tx-subs assoc handle {:db-name db-name :sub-id sub-id
                                  :queue queue :qsize qsize})
    ;; The wire control envelope is plain CBOR; handle is an int.
    {"ok" true "handle" handle "db-name" db-name}))

(defmethod wire/handle-op "next-tx-event" [_conn req]
  (let [handle (long (get req "handle"))]
    (if-let [{:keys [^ConcurrentLinkedQueue queue]} (get @!tx-subs handle)]
      ;; Bounded wait: poll up to ~50ms for an event so the guest's loop
      ;; doesn't hot-spin. On timeout, a typed "no-event" protocol error —
      ;; the guest swallows it (see seon.client-runtime.db/ensure-listener-loop!).
      (let [deadline (+ (System/currentTimeMillis) 50)]
        (loop []
          (if-let [ev (.poll queue)]
            (assoc ev "ok" true)
            (if (< (System/currentTimeMillis) deadline)
              (do (Thread/sleep 5) (recur))
              {"ok" false "error" "no-event" "error-kind" "not-found"}))))
      {"ok" false "error" (str "unknown tx-sub handle: " handle) "error-kind" "not-found"})))

(defmethod wire/handle-op "unsubscribe-tx" [_conn req]
  (let [handle (long (get req "handle"))]
    (when-let [{:keys [db-name sub-id]} (get @!tx-subs handle)]
      (bcast/unsubscribe! db-name sub-id)
      (swap! !tx-subs dissoc handle))
    {"ok" true "handle" handle "unsubscribed" true}))

;; ---------------------------------------------------------------------------
;; Query subscriptions (the reactive engine) — register/unregister + the
;; ::reactive on-ensure-db hook. Per-conn engine state, keyed by db-name.
;; ---------------------------------------------------------------------------

;; db-name (str) -> reactive engine state atom. One engine per conn/cluster.
(defonce ^:private !engines (atom {}))

(defn- engine-for [db-name]
  (or (get @!engines db-name)
      (let [st (reactive/new-engine-state db-name)]
        (swap! !engines assoc db-name st)
        (get @!engines db-name))))

(defn- seed-subscription-schema!
  "Install the durable subscription attrs into a conn so the reactive engine's
   `register-subscription!` can persist a subscription datom under
   `:schema-flexibility :write` (the wire-server conn flavor). Idempotent — a
   re-seed transacts the same :db/idents (a no-op datahike upsert). The reactive
   ns registers these in the Seon Malli registry; this installs the datahike
   schema on the actual conn."
  [conn]
  (d/transact conn [{:db/ident       :seon.subscription/id
                     :db/valueType   :db.type/string
                     :db/unique      :db.unique/identity
                     :db/cardinality :db.cardinality/one}
                    {:db/ident       :seon.subscription/query
                     :db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/one}
                    {:db/ident       :seon.subscription/active?
                     :db/valueType   :db.type/boolean
                     :db/cardinality :db.cardinality/one}]))

;; Install the reactive engine as a per-conn ::reactive d/listen! via the
;; registry's on-ensure-db hook. Runs at every ns load — registration is
;; key-based idempotent (re-registering ::reactive replaces in place), so
;; reloads can't accumulate copies AND can't strand an emptied hook vector
;; (the 2026-06-10 hook-loss bug: a defonce guard here blocked
;; re-registration until JVM restart) — mirrors wire.clj's ::raw-broadcast
;; hook. Each commit routes through the engine's two-gate dispatch and emits
;; a changed-summaries event on the SAME pub fanout (db-name-tagged), so
;; changed query rows ride the existing broadcast. Hook failures are caught +
;; logged by `run-on-ensure-db-hooks!`.
(registry/register-on-ensure-db-hook!
 {:seon.server.registry/hook-key ::reactive
  :seon.server.registry/hook-fn
  (fn [conn db-name]
    (let [db-name-str (if (keyword? db-name) (subs (str db-name) 1) (str db-name))
          state       (engine-for db-name-str)]
      ;; seed the durable subscription attrs so register-subscription!
      ;; can persist its datom under :schema-flexibility :write.
      (seed-subscription-schema! conn)
      ;; reconstitute the engine cache from any persisted active subs
      ;; (a file-backed conn may already hold subscription datoms).
      (try (reactive/rebuild! state conn)
           (catch Throwable t
             (log/warn t "reactive engine rebuild! failed on ensure-db — engine starts empty"
                       {:seon.server.registry/db-name db-name})))
      (d/listen conn ::reactive
                (fn [report]
                  (reactive/on-tx!
                   {:db-name db-name-str
                    :conn conn
                    :state state
                    :emit! (fn [ev]
                             ;; tag the changed-summaries event with the
                             ;; db-name for pub routing, ship the body as
                             ;; a Transit-JSON payload (it carries
                             ;; keywords/rows that CBOR can't represent).
                             (bcast/broadcast!
                              {"event"   "changed-summaries"
                               "db-name" db-name-str
                               "basis-t" (:seon.server.reactive/basis-t ev)
                               "payload" (transit/write-str ev)}))}
                   report)))))})

(defmethod wire/handle-op "register-subscription" [conn req]
  (let [sub-id (get req "sub-id")
        query  (get req "query")            ; SOURCE STRING (code-as-data)
        db-name (db-name-for-req req)
        state   (engine-for db-name)
        resp    (reactive/register-subscription
                 state conn
                 {:seon.server.reactive/sub-id sub-id
                  :seon.server.reactive/query  query})]
    {"ok" true
     "sub-id"  (:seon.subscription/id resp)
     "basis-t" (:seon.server.reactive/basis-t resp)
     "payload" (transit/write-str resp)}))

(defmethod wire/handle-op "unregister-subscription" [conn req]
  (let [sub-id (get req "sub-id")
        db-name (db-name-for-req req)
        state   (engine-for db-name)
        resp    (reactive/unregister-subscription
                 state conn {:seon.server.reactive/sub-id sub-id})]
    {"ok" true
     "sub-id" (:seon.subscription/id resp)
     "payload" (transit/write-str resp)}))

;; ---------------------------------------------------------------------------

(defn -main
  "Boot the wire-server. Loading this ns registered the raw tx-feed +
   query-subscription `handle-op` defmethods, the reactive `::reactive`
   on-ensure-db hook, and both reactive + raw-broadcast schemas. Delegates to
   `wire/-main`."
  [& args]
  (apply wire/-main args))
