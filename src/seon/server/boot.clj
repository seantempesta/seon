(ns seon.server.boot
  "Wire-server boot entry and transaction-feed gap-recovery ops.

   `replay-tx` is the request-socket sibling of the live transaction feed. The
   pod calls it on every reconnect with its last-applied basis-t and receives a
   bounded page of missed transactions. Live delivery remains push-only over
   the pub socket; there is no polled per-subscriber queue.

   The wire-server is launched via `:writer` → `-m seon.server.boot` (deps.edn);
   `-main` delegates straight to `wire/-main`."
  (:require ;; Register the :proximum secondary-index type with datahike's
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
            ;; bounded backfill). Loading it before the server opens a conn makes
            ;; the hook part of that conn's one-time initialization.
            [seon.embed])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Tx-feed gap recovery (replay-tx) — the req-socket side of the push feed.
;;
;; Live tx events ride the pub socket (`broadcast/start-pub-server!`, fed by
;; the conn's `::raw-broadcast` listener). Each event is the SAME map
;; `seon.server.wire`'s broadcaster builds (`ok-event-from-report`):
;; `:seon.store.wire/*` keyword keys, `tx-data` as native 5-vectors
;; [e a v t op], `tx-meta`/wire id carried through. `replay-tx` returns the
;; missed range of exactly-that-shaped events for a reconnecting subscriber.
;; ---------------------------------------------------------------------------

(defn- db-name-for-req
  "The broadcast db-name a feed op should resolve to, derived the SAME way
   request routing resolves a conn: explicit db-name, else the ambient conn's
   db-name. Returns the db-name STRING the
   `::raw-broadcast` listener tags events with (keyword db-names are stringified
   without the leading colon, matching `raw-broadcast-listener-fn`)."
  [req]
  (let [db-name  (some-> (:seon.store.wire/db-name req) keyword)
        kw->str  (fn [kw] (if (keyword? kw) (subs (str kw) 1) (str kw)))
        resolved (registry/resolve-conn
                  (cond-> {}
                    db-name (assoc :seon.server.registry/db-name db-name)))]
    (cond
      (:seon.server.registry/db-name resolved) (kw->str (:seon.server.registry/db-name resolved))
      ;; ::unresolved? (neither key) → ambient conn's db-name
      :else (wire/ambient-db-name))))

(defmethod wire/handle-op "replay-tx" [conn req]
  ;; DE-2 lossless wake: return ONE bounded page of missed txs directly in the
  ;; reply. The first page captures `through-t`; every continuation retains it,
  ;; so commits racing the replay remain on the already-open buffered pub
  ;; socket. Explicit continuation/done facts prevent both truncation and an
  ;; accidental empty-page loop. The resolved db-name remains the pub demux key.
  (let [since-t   (:seon.store.wire/since-t req)
        through-t (:seon.store.wire/through-t req)]
    (if-not (some? since-t)
      {:seon.store.wire/ok false
       :seon.store.wire/error "replay-tx requires :seon.store.wire/since-t"
       :seon.store.wire/error-kind "protocol"}
      (try
        (let [db-name (db-name-for-req req)
              page    (wire/replay-tx-page conn db-name since-t through-t)]
          (assoc page
                 :seon.store.wire/ok true
                 :seon.store.wire/db-name db-name))
        (catch clojure.lang.ExceptionInfo error
          {:seon.store.wire/ok false
           :seon.store.wire/error (.getMessage error)
           :seon.store.wire/error-kind
           (or (:seon.store.wire/error-kind (ex-data error)) "protocol")})))))

;; ---------------------------------------------------------------------------

(defn -main
  "Boot the wire-server with transaction replay and live socket fanout.

   `--preflight`: instead of starting the server, run the embedding-feature
   self-check (`seon.embed.preflight/run-preflight!`) and System/exit with its code (0 =
   all green; distinct non-zero per failure mode). This is the loud gate a
   third party scripts against — see the preflight ns. Otherwise delegates to
   `wire/-main` to start serving."
  [& args]
  (if (some #{"--preflight"} args)
    (let [code ((requiring-resolve 'seon.embed.preflight/run-preflight!))]
      (flush)
      (System/exit code))
    (apply wire/-main args)))
