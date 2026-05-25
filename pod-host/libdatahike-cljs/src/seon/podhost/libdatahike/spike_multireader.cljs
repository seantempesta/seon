(ns seon.podhost.libdatahike.spike-multireader
  "Multi-reader / multi-process spike for datahike-cljs + konserve-sqlite-cljs.

   One CLJS namespace, dispatched on argv[2]:
     writer   — fresh DB, transact ~100 entities, exit.
     reader   — open same DB, run q, print [pid basis-t count rows], exit.
     rwwriter — open existing DB, commit 100 datoms in batches of 10 over ~5s.
     ropoll   — open existing DB, poll basis-t every 200ms for ~6s.

   Store path comes from argv[3] (or env STORE_PATH, default tmp/multi-reader/store.sqlite).

   Goal: empirically verify two CLJS Node processes can concurrently read the
   same SQLite store while a third writes. See research note for the verdict."
  (:require [datahike.api :as d]
            [datahike.datom]
            [datahike.index.persistent-set]
            [konserve-sqlite-cljs.core]
            [me.tonsky.persistent-sorted-set :as psset]
            [me.tonsky.persistent-sorted-set.btset :as btset]
            [cljs.core.async :as a :refer [<!]])
  (:require-macros [cljs.core.async :refer [go]]))

;; -- datahike/persistent-sorted-set compat patches (same as bench) -----------
(defonce ^:private patches-applied?
  (do
    (let [orig btset/from-opts]
      (set! btset/from-opts
            (fn [opts]
              (let [opts' (if (and (:cmp opts) (not (:comparator opts)))
                            (assoc opts :comparator (:cmp opts))
                            opts)]
                (orig opts')))))
    (let [_ datahike.index.persistent-set/insert]
      (set! datahike.index.persistent-set/insert
            (fn [pset datom index-type]
              (psset/conj pset datom
                          (datahike.datom/index-type->cmp-quick index-type)))))
    true))

(def fs (js/require "fs"))
(def path-mod (js/require "path"))

(defn- pid [] (.-pid js/process))
(defn- now [] (.toISOString (js/Date.)))

(defn- log [tag & args]
  (apply println (str "[" tag " pid=" (pid) " " (now) "]") args))

(defn- cfg-for [store-path]
  ;; Note: konserve-sqlite-cljs caches conns by :path (string), so :id is
  ;; a per-process random uuid — datahike key prefix; doesn't affect file.
  {:store              {:backend :sqlite
                        :path    store-path
                        :id      #uuid "deadbeef-0000-0000-0000-000000000001"}
   :schema-flexibility :write
   :keep-history?      false})

(def schema
  [{:db/ident :ent/id :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :ent/name :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :ent/n :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :ent/tag :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(defn- rm-sqlite! [p]
  (doseq [sfx ["" "-wal" "-shm"]]
    (let [fp (str p sfx)]
      (try (.rmSync fs fp #js {:force true}) (catch :default _ nil)))))

(defn- gen-entity [i]
  {:ent/id   (str "ent-" i)
   :ent/name (str "Entity " i)
   :ent/n    i
   :ent/tag  ["alpha" (if (even? i) "even" "odd")]})

;; -- writer (Step 1) ---------------------------------------------------------
(defn- run-writer! [store-path]
  (log "writer" "creating fresh store at" store-path)
  (rm-sqlite! store-path)
  (go
    (let [cfg (cfg-for store-path)]
      (<! (d/create-database cfg))
      (let [conn (<! (d/connect cfg {:sync? false}))]
        (<! (d/transact! conn schema))
        (<! (d/transact! conn (mapv gen-entity (range 100))))
        (let [db   @conn
              t    (:max-tx db)
              cnt  (count (d/q '[:find ?e :where [?e :ent/id _]] db))]
          (log "writer" "DONE basis-t=" t " entity-count=" cnt))
        (js/process.exit 0)))))

;; -- reader (Step 2) ---------------------------------------------------------
(defn- run-reader! [store-path]
  (log "reader" "opening store at" store-path)
  (go
    (let [cfg (cfg-for store-path)]
      (try
        (let [conn (<! (d/connect cfg {:sync? false}))
              db   @conn
              t    (:max-tx db)
              rows (vec (sort-by second (d/q '[:find ?id ?n :where
                                                [?e :ent/id ?id]
                                                [?e :ent/n ?n]]
                                              db)))]
          (log "reader" "basis-t=" t " result-count=" (count rows)
               " first3=" (pr-str (take 3 rows)))
          (js/process.exit 0))
        (catch :default e
          (log "reader" "ERROR" (.-message e))
          (js/console.error e)
          (js/process.exit 2))))))

;; -- concurrent writer (Step 3) ---------------------------------------------
;; Writes 100 more entities (ids ent-100..ent-199) in 10 batches of 10,
;; with 500ms between batches. Total ~5s.
(defn- run-rwwriter! [store-path]
  (log "rwwriter" "opening store at" store-path)
  (go
    (let [cfg  (cfg-for store-path)
          conn (<! (d/connect cfg {:sync? false}))]
      (loop [batch 0]
        (if (>= batch 10)
          (let [t (:max-tx @conn)]
            (log "rwwriter" "DONE final basis-t=" t)
            (js/process.exit 0))
          (let [start (+ 100 (* batch 10))
                ents  (mapv gen-entity (range start (+ start 10)))]
            (<! (d/transact! conn ents))
            (log "rwwriter" "committed batch=" batch " basis-t=" (:max-tx @conn))
            (<! (a/timeout 500))
            (recur (inc batch))))))))

;; -- concurrent reader-poller (Step 3) --------------------------------------
;; Polls basis-t and count every 200ms for 6 seconds.
(defn- run-ropoll! [store-path]
  (log "ropoll" "opening store at" store-path)
  (go
    (try
      (let [cfg  (cfg-for store-path)
            conn (<! (d/connect cfg {:sync? false}))
            t-start (js/Date.now)]
        (loop [i 0 last-t -1]
          (if (>= (- (js/Date.now) t-start) 6000)
            (do (log "ropoll" "DONE iterations=" i)
                (js/process.exit 0))
            ;; Re-connect each poll? No — datahike-cljs is single-conn-per-store
            ;; in this process. @conn returns the latest db value held in-memory
            ;; by THIS process's connector. To see writer's commits, we must
            ;; re-read the store. konserve-sqlite-cljs's read path goes through
            ;; the cached SqliteConn — but its cache is in-memory in the konserve
            ;; layer (header/meta/val per row). New SELECTs DO hit SQLite.
            ;; However, datahike's connector caches the db state and only updates
            ;; on local transact!. To observe remote writes we must call
            ;; `d/connect` again to re-load the latest db. That's expensive but
            ;; correct for this experiment.
            (let [conn' (<! (d/connect cfg {:sync? false}))
                  db    @conn'
                  t     (:max-tx db)
                  cnt   (count (d/q '[:find ?e :where [?e :ent/id _]] db))]
              (when (not= t last-t)
                (log "ropoll" "tick=" i " basis-t=" t " count=" cnt))
              (<! (a/timeout 200))
              (recur (inc i) t)))))
      (catch :default e
        (log "ropoll" "ERROR" (.-message e))
        (js/console.error e)
        (js/process.exit 2)))))

;; -- entrypoint --------------------------------------------------------------
(defn main [& _]
  (let [argv  (vec (.-argv js/process))
        ;; node out/spike-multireader.js <role> <store-path>
        role  (nth argv 2 nil)
        path' (or (nth argv 3 nil)
                  (some-> js/process .-env (aget "STORE_PATH"))
                  "tmp/multi-reader/store.sqlite")]
    (case role
      "writer"   (run-writer!   path')
      "reader"   (run-reader!   path')
      "rwwriter" (run-rwwriter! path')
      "ropoll"   (run-ropoll!   path')
      (do (println "Usage: node spike-multireader.js <writer|reader|rwwriter|ropoll> [store-path]")
          (js/process.exit 1)))))
