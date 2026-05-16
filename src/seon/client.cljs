(ns seon.client
  "V0 CLJS pod entry point. Long-running Node process; the V0 client.

   This file is intentionally minimal — it proves three things end-to-end:

     1. The shadow-cljs watch / hot-reload / nREPL pipeline is working
        (heartbeat + reload hooks below).
     2. datahike-cljs is alive — the runtime patches at the top of this
        file plus the smoke test (`datahike-smoke-test!`) verify that a
        :memory DB boots, transacts schema + entities, and queries them
        correctly. Runs once on `-main`; callable any time from the REPL.
     3. The MCP eval surface routes into this runtime — connect to nREPL
        :7889, pivot via `(shadow.cljs.devtools.api/nrepl-select :client)`,
        eval `(seon.client/datahike-smoke-test!)` and watch it pass.

   Real lifecycle (read config.edn, open user's datahike DB with
   konserve :tiered storage, register platform schemas, install the
   seon.db/listen! tx-listener, spawn the user's default session, hand
   control to the session's flow) lands in subsequent commits per
   spec-01 §6.2.

   How to run it:

     ;; Terminal 1 — the watcher (compiles + writes nREPL port file)
     cd consumer/seon
     clj -M:cljs watch client

     ;; Terminal 2 — the Node host
     node out/client/main.js

     ;; Editor / MCP — connect to nREPL on localhost:7889, then
     ;; pivot into the running CLJS runtime:
     (shadow.cljs.devtools.api/nrepl-select :client)

   Edit this file → save → watcher recompiles → running process reloads
   automatically. `^:dev/before-load` fires before the namespace's new
   code lands; `^:dev/after-load` fires after."
  (:require
    [datahike.api :as d]
    [datahike.datom]
    [datahike.index.persistent-set]
    [me.tonsky.persistent-sorted-set :as psset]
    [me.tonsky.persistent-sorted-set.btset :as btset]
    [cljs.core.async :as a :refer [<!]])
  (:require-macros
    [cljs.core.async :refer [go]]))

;; ---------------------------------------------------------------------------
;; CLJS-DATAHIKE RUNTIME PATCHES
;;
;; Two upstream incompatibilities between datahike 0.7.1624 and
;; persistent-sorted-set 0.3.116 must be patched at runtime before any
;; datahike DB is created.
;;
;; (1) `empty-index` opt-key mismatch — datahike passes
;;       (psset/sorted-set* {:cmp <cmp-fn> ...})
;;     but psset's `btset/from-opts` only reads `:comparator`. Empty-built
;;     indexes therefore default their comparator to `cljs.core/compare`,
;;     which throws on Datom-vs-Datom comparison.
;;
;; (2) `insert` calls `(psset/lookup pset datom prefix-cmp)` expecting the
;;     3rd arg to be a custom comparator (works in CLJ where psset's
;;     `.lookup` Java method has a 3-arg overload), but in CLJS the 3-arg
;;     `psset/lookup` treats the 3rd argument as a `not-found` value, not
;;     a comparator. With `prefix-cmp` (a function) as `not-found`, lookup
;;     returns the function on "not found" — a truthy value — so `insert`
;;     thinks the datom already exists and skips the conj. Result:
;;     subsequent inserts into the same (e,a) get dropped silently. Most
;;     visible for cardinality/many where the second-and-later values all
;;     disappear.
;;
;; `defonce` so a hot-reload doesn't re-patch.
;; ---------------------------------------------------------------------------

(defonce ^:private patches-applied?
  (do
    ;; FIX (1) — from-opts honors :cmp as alias for :comparator.
    (let [orig btset/from-opts]
      (set! btset/from-opts
            (fn [opts]
              (let [opts' (if (and (:cmp opts) (not (:comparator opts)))
                            (assoc opts :comparator (:cmp opts))
                            opts)]
                (orig opts')))))
    ;; FIX (2) — replace `insert` to use conj's idempotency with a quick
    ;; comparator. conj with a 3-arg cmp returns the same set if the key
    ;; already exists per cmp, so we can detect "no change" by identity
    ;; comparison. Costs us the zero-allocation lookup path but is correct.
    (set! datahike.index.persistent-set/insert
          (fn [pset datom index-type]
            (let [quick-cmp (datahike.datom/index-type->cmp-quick index-type)]
              (psset/conj pset datom quick-cmp))))
    true))

;; ---------------------------------------------------------------------------
;; Process-lifetime state. `defonce` so reloads don't reset it.
;; ---------------------------------------------------------------------------

(defonce !state
  (atom {:boot-at      (.toISOString (js/Date.))
         :reload-count 0
         :heartbeat-id nil}))

(defn start-heartbeat!
  "Holds the Node event loop open with a minute-cadence heartbeat. The
   real V0 client will keep the loop alive via pending agent-loop work;
   for now this is the simplest 'process stays open' contract."
  []
  (let [id (js/setInterval
             (fn []
               (js/console.log "[client] heartbeat" (.toISOString (js/Date.))))
             60000)]
    (swap! !state assoc :heartbeat-id id)))

(defn stop-heartbeat! []
  (when-let [id (:heartbeat-id @!state)]
    (js/clearInterval id)
    (swap! !state assoc :heartbeat-id nil)))

(defn ^:dev/before-load before-reload []
  (js/console.log "[client] reloading…")
  (stop-heartbeat!))

(defn ^:dev/after-load after-reload []
  (swap! !state update :reload-count inc)
  (js/console.log
    (str "[client] reload #" (:reload-count @!state)
         " — booted " (:boot-at @!state)
         " — patches=" (boolean patches-applied?)))
  (start-heartbeat!))

;; ---------------------------------------------------------------------------
;; datahike-cljs smoke test — proves the substrate works end-to-end.
;;
;; This is the canonical 'is datahike-cljs alive?' check. Useful as:
;;   - boot-time verification (`-main` runs it),
;;   - REPL-callable health probe (`(datahike-smoke-test!)`),
;;   - reference for how to use datahike-cljs from agent code.
;; ---------------------------------------------------------------------------

(def ^:private smoke-schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def ^:private smoke-seed
  [{:name "Alpha"     :rank 1}
   {:name "Seon"     :rank 2}
   {:name "Datahike" :rank 3}])

(def ^:private smoke-expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn datahike-smoke-test!
  "Create a fresh :memory datahike-cljs DB, transact schema + seed entities,
   query, compare to expected. Returns a channel resolving to
   {:status :pass :datoms <n> :rows <set>} or
   {:status :fail :got <set> :expected <set>}.

   REPL usage:
     (cljs.core.async/go
       (println (cljs.core.async/<! (datahike-smoke-test!))))"
  []
  (let [cfg {:store              {:backend :memory
                                  :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (go
      (<! (d/create-database cfg))
      (let [conn (<! (d/connect cfg {:sync? false}))
            _    (<! (d/transact! conn smoke-schema))
            tx   (<! (d/transact! conn smoke-seed))
            rows (d/q '[:find ?name ?rank
                        :where
                        [?e :name ?name]
                        [?e :rank ?rank]]
                      @conn)]
        (if (= rows smoke-expected)
          {:status :pass :datoms (count (:tx-data tx)) :rows rows}
          {:status :fail :got rows :expected smoke-expected})))))

(defn mem-db
  "REPL convenience — open a fresh :memory datahike-cljs DB with optional
   schema. Returns a channel resolving to a conn atom. Useful for ad-hoc
   exploration."
  ([] (mem-db []))
  ([schema]
   (let [cfg {:store              {:backend :memory
                                   :id (random-uuid)}
              :schema-flexibility :write
              :keep-history?      false}]
     (go
       (<! (d/create-database cfg))
       (let [conn (<! (d/connect cfg {:sync? false}))]
         (when (seq schema)
           (<! (d/transact! conn schema)))
         conn)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (js/console.log "[client] -main boot at" (:boot-at @!state))
  (js/console.log "[client] patches applied? " (boolean patches-applied?))
  (go
    (let [result (<! (datahike-smoke-test!))]
      (case (:status result)
        :pass (js/console.log
                "[client] datahike-cljs smoke test PASS —"
                (:datoms result) "datoms")
        :fail (do (js/console.error "[client] datahike-cljs smoke test FAIL")
                  (js/console.error "  got:     " (pr-str (:got result)))
                  (js/console.error "  expected:" (pr-str (:expected result)))))))
  (js/console.log "[client] connect editor / mcp to nREPL :7889 then")
  (js/console.log "[client]   (shadow.cljs.devtools.api/nrepl-select :client)")
  (start-heartbeat!))
