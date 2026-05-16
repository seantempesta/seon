(ns seon.podhost.libdatahike.bench
  "CLJS-2.5: head-to-head perf bench across `:memory`, `:file`
   (konserve.node-filestore on real disk), and `:indexeddb`-via-tiered
   (konserve.indexeddb under the fake-indexeddb polyfill).

   Workload approximates an Seon personal-vault: notes with a unique id,
   a path, text body, created-at timestamp, ~3 tags (cardinality/many) and
   ~2 refs-to other notes (cardinality/many ref). ~9 datoms per entity on
   average; 1k notes ≈ 9k datoms.

   Sizes run by default: 1k, 10k, 100k entities (= ~9k / 90k / 900k datoms).
   The 100k tier approximates the upper edge of what Sean's
   ~/src/workspace.ai vault would generate at the 'medium schema density'
   level (per the ingestion estimate: ~300k datoms across ~1500 markdown
   files at 200 datoms/file).

   Run individual tiers via env vars:
     BENCH_SIZE=10000 BENCH_BACKEND=fs node out/bench.js

   Or all combinations (default):
     node out/bench.js

   Output: a results table for the orchestrator to read."
  (:require [datahike.api :as d]
            [datahike.datom]
            [datahike.index.persistent-set]
            [konserve.node-filestore]   ;; :file backend
            [konserve.indexeddb]        ;; :indexeddb backend
            [me.tonsky.persistent-sorted-set :as psset]
            [me.tonsky.persistent-sorted-set.btset :as btset]
            [cljs.core.async :as a :refer [<!]])
  (:require-macros [cljs.core.async :refer [go go-loop]]))

;; ---------------------------------------------------------------------------
;; CLJS-DATAHIKE FIXES — see REPL-WORKFLOW.md "Diagnosis sidebar" for the
;; full root-cause analysis. Two upstream incompatibilities between datahike
;; 0.7.1624 and persistent-sorted-set 0.3.116:
;;   (1) `empty-index` passes `:cmp` but psset reads `:comparator`
;;   (2) `insert`'s 3-arg `psset/lookup` call treats the comparator as
;;       a `not-found` value, causing cardinality/many duplicates to be
;;       silently dropped.
;; Patches mirror the ones in seon.podhost.libdatahike.repl.
;; ---------------------------------------------------------------------------

(defonce ^:private patches-applied?
  (do
    (let [orig btset/from-opts]
      (set! btset/from-opts
            (fn [opts]
              (let [opts' (if (and (:cmp opts) (not (:comparator opts)))
                            (assoc opts :comparator (:cmp opts))
                            opts)]
                (orig opts')))))
    (let [_orig-insert datahike.index.persistent-set/insert]
      (set! datahike.index.persistent-set/insert
            (fn [pset datom index-type]
              (psset/conj pset datom
                          (datahike.datom/index-type->cmp-quick index-type)))))
    true))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def os (js/require "os"))

(defn- now-ms [] (js/performance.now))

(defn- env [k default]
  (or (some-> js/process .-env (aget k)) default))

(defn- rm-rf [p]
  (when (.existsSync fs p)
    (.rmSync fs p #js {:recursive true :force true})))

;; -- schema -------------------------------------------------------------------

(def schema
  ;; Seon-vault-realistic schema: string id, vault-relative path, text body,
  ;; created-at timestamp, cardinality/many tags. We drop the optional
  ;; :note/refs-to ref attr for now — the bench's pseudo-entities don't form
  ;; a coherent graph anyway, so refs would just be synthetic edges with no
  ;; query meaning. Add back if/when we want graph-traversal benchmarks.
  ;; Note: :db.type/uuid is also broken in CLJS datahike's persistent-sorted-set
  ;; comparator path — string ids are what Seon would use anyway (opaque /
  ;; hash-derived).
  [{:db/ident       :note/id
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :note/path
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :note/text
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}
   {:db/ident       :note/created-at
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/valueType   :db.type/long}
   {:db/ident       :note/tag
    :db/cardinality :db.cardinality/many
    :db/index       true
    :db/valueType   :db.type/string}])

;; -- data generator -----------------------------------------------------------

(def ^:private tag-pool
  ["seon" "seon" "datahike" "wasmer" "edgejs" "clojure" "cljs"
   "research" "spec" "todo" "meeting" "idea" "decision"])

(defn- pick-tags
  "Every note carries 'seon' plus two deterministic seeded tags from the pool.
   That keeps :scan-by-tag a meaningful query while preserving variety."
  [seed]
  (let [b (mod (* seed 13) (count tag-pool))
        c (mod (* seed 31) (count tag-pool))]
    (vec (distinct ["seon" (nth tag-pool b) (nth tag-pool c)]))))

(def ^:private base-ts 1700000000000) ;; 2026-11-15 UTC, anchor for timestamps

(defn- gen-text
  "Deterministic ~80-char body per entity — enough to stress string storage
   without being huge."
  [i]
  (str "note-" i " body text. Lorem ipsum sit amet at index " i
       " consectetur adipiscing elit, integer scelerisque."))

(defn- gen-note [i id-vec]
  {:note/id         (nth id-vec i)
   :note/path       (str "vault/notes/note-" i ".md")
   :note/text       (gen-text i)
   :note/created-at (+ base-ts (* i 1000))
   :note/tag        (pick-tags i)})

(defn- gen-batch
  "Generate a flat batch of notes — no cross-entity refs in this version of
   the bench to keep the index shape simple. Add refs back when stress-testing
   the ref-graph specifically."
  [start n id-vec]
  (mapv (fn [i] (gen-note i id-vec)) (range start (+ start n))))

;; -- config builders ----------------------------------------------------------

(defn- mem-cfg []
  {:store              {:backend :memory :id #uuid "1d50b780-0000-0000-0000-00000000b001"}
   :schema-flexibility :write :keep-history? false})

(defn- fs-cfg [store-path]
  {:store              {:backend :file
                        :path    store-path
                        :id      #uuid "1d50b780-0000-0000-0000-00000000b002"}
   :schema-flexibility :write :keep-history? false})

(defn- idb-cfg []
  (let [tid #uuid "1d50b780-0000-0000-0000-00000000b003"]
    {:store              {:backend         :tiered
                          :frontend-config {:backend :memory :id tid}
                          :backend-config  {:backend :indexeddb :name "bench-store" :id tid}
                          :id              tid}
     :schema-flexibility :write :keep-history? false}))

;; -- bench primitives ---------------------------------------------------------

(defn- bulk-load!
  "Returns a channel yielding {:ms ms-elapsed :batches batch-count :id-vec ids}."
  [conn n batch-size]
  (let [id-vec (mapv #(str "note-id-" %) (range n))]
    (go
      (let [t0 (now-ms)]
        (loop [start 0 batches 0]
          (if (>= start n)
            {:ms (- (now-ms) t0) :batches batches :id-vec id-vec}
            (let [m      (min batch-size (- n start))
                  batch  (gen-batch start m id-vec)
                  tx-rep (<! (d/transact! conn batch))]
              (when (instance? js/Error tx-rep)
                (println "[bench] tx ERR @start=" start ":" (.-message tx-rep)))
              (recur (+ start m) (inc batches)))))))))

(defn- time-q [conn q & inputs]
  (try
    (let [t0 (now-ms)
          result (apply d/q q @conn inputs)
          ms (- (now-ms) t0)]
      {:ms ms :count (count result)})
    (catch :default e
      (println "[bench]   query err:" (ex-message e))
      {:ms -1 :count 0})))

(defn- time-pull [conn lookup-ref]
  (try
    (let [t0 (now-ms)
          result (d/pull @conn '[*] lookup-ref)
          ms (- (now-ms) t0)]
      {:ms ms :keys (count (keys (or result {})))})
    (catch :default e
      (println "[bench]   pull err:" (ex-message e))
      {:ms -1 :keys 0})))

(defn- bench-queries [conn id-vec size]
  ;; Pick the middle entity by path (not by id-vec position — pulling by
  ;; lookup-ref [:note/path ...] is more robust against id-vec quirks).
  (let [pick-idx  (quot size 2)
        pick-id   (nth id-vec pick-idx)
        pick-path (str "vault/notes/note-" pick-idx ".md")]
    {:scan-all
     (time-q conn '[:find (count ?e) :where [?e :note/path _]])

     :scan-by-tag
     (time-q conn '[:find ?p
                    :in $ ?t
                    :where
                    [?e :note/tag ?t]
                    [?e :note/path ?p]]
             "seon")

     :indexed-by-id
     (time-q conn '[:find ?p
                    :in $ ?id
                    :where
                    [?e :note/id ?id]
                    [?e :note/path ?p]]
             pick-id)

     :pull-by-path
     (time-pull conn [:note/path pick-path])

     ;; Range-by-time: catches the first ~10% of entities (each created-at
     ;; is base-ts + i*1000, window = base..base + 0.1*n*1000).
     :range-by-time
     (let [lo 1700000000000
           hi (+ lo (* (quot size 10) 1000))]
       (time-q conn '[:find (count ?e)
                      :in $ ?lo ?hi
                      :where
                      [?e :note/created-at ?t]
                      [(>= ?t ?lo)]
                      [(<= ?t ?hi)]]
               lo hi))}))

;; -- per-backend driver -------------------------------------------------------

(defn- run-bench-once [backend size batch-size]
  (println (str "\n[bench] backend=" backend " size=" size " batch=" batch-size))
  (go
    (let [t-setup0 (now-ms)
          cfg (case backend
                :memory (mem-cfg)
                :fs     (let [p (str (.tmpdir os) "/seon-bench-" (.getTime (js/Date.)))]
                          (rm-rf p)
                          (fs-cfg p))
                :idb    (idb-cfg))
          _ (<! (d/create-database cfg))
          conn (<! (d/connect cfg {:sync? false}))
          _ (<! (d/transact! conn schema))
          t-setup (- (now-ms) t-setup0)
          _ (println (str "[bench] setup: " (.toFixed t-setup 1) "ms"))

          {:keys [ms batches id-vec]} (<! (bulk-load! conn size batch-size))
          load-ms ms
          throughput (.toFixed (/ size (/ load-ms 1000)) 0)
          _ (println (str "[bench] load: " (.toFixed load-ms 1) "ms ("
                          throughput " entities/s across " batches " batches)"))

          ;; Sanity: scan + spot-check first entity's path exists
          first-scan (time-q conn '[:find (count ?e) :where [?e :note/path _]])
          _ (println (str "[bench] sanity scan-all: " (:count first-scan) " count"))
          probe-path "vault/notes/note-0.md"
          probe (time-q conn '[:find ?id :in $ ?p :where [?e :note/path ?p] [?e :note/id ?id]] probe-path)
          _ (println (str "[bench] probe path " probe-path " ⇒ " (:count probe) " matches"))

          q (bench-queries conn id-vec size)
          {scan-all :scan-all
           scan-by-tag :scan-by-tag
           indexed-by-id :indexed-by-id
           pull-by-path :pull-by-path
           range-by-time :range-by-time} q]
      (println (str "[bench] scan-all     : " (.toFixed (:ms scan-all) 2) "ms ("(:count scan-all)" rows)"))
      (println (str "[bench] scan-by-tag  : " (.toFixed (:ms scan-by-tag) 2) "ms ("(:count scan-by-tag)" rows)"))
      (println (str "[bench] indexed-by-id: " (.toFixed (:ms indexed-by-id) 2) "ms ("(:count indexed-by-id)" rows)"))
      (println (str "[bench] pull-by-path : " (.toFixed (:ms pull-by-path) 2) "ms ("(:keys pull-by-path)" attrs)"))
      (println (str "[bench] range-by-time: " (.toFixed (:ms range-by-time) 2) "ms ("(:count range-by-time)" rows)"))
      {:backend backend :size size :batches batches
       :setup-ms t-setup :load-ms load-ms :load-eps (parse-long throughput)
       :queries q})))

;; -- runner -------------------------------------------------------------------

(defn- format-summary [results]
  (println "\n========== summary ==========")
  (println (str "backend  size       load(ms)   eps      scan    by-tag  by-id   pull    range"))
  (doseq [{:keys [backend size load-ms load-eps queries]} results]
    (println (str (subs (str (name backend) "       ") 0 8)
                  " " (subs (str size "       ") 0 10)
                  " " (subs (str (.toFixed load-ms 0) "         ") 0 10)
                  " " (subs (str load-eps "       ") 0 8)
                  " " (subs (str (.toFixed (:ms (:scan-all queries)) 1) "      ") 0 7)
                  " " (subs (str (.toFixed (:ms (:scan-by-tag queries)) 1) "      ") 0 7)
                  " " (subs (str (.toFixed (:ms (:indexed-by-id queries)) 2) "      ") 0 7)
                  " " (subs (str (.toFixed (:ms (:pull-by-path queries)) 2) "      ") 0 7)
                  " " (subs (str (.toFixed (:ms (:range-by-time queries)) 1) "      ") 0 7)))))

(defn- parse-sizes []
  (let [raw (env "BENCH_SIZES" "1000,10000")
        parts (.split raw ",")]
    (mapv #(parse-long (.trim %)) parts)))

(defn- parse-backends []
  (let [raw (env "BENCH_BACKENDS" "memory,fs,idb")
        parts (.split raw ",")]
    (mapv #(keyword (.trim %)) parts)))

(defn main [& _args]
  (println "[bench] indexedDB present?" (boolean (.-indexedDB js/globalThis)))
  (let [sizes (parse-sizes)
        backends (parse-backends)
        batch-size (parse-long (env "BENCH_BATCH" "1000"))]
    (println (str "[bench] sizes=" sizes " backends=" backends " batch=" batch-size))
    (go
      (let [results (atom [])]
        (doseq [backend backends
                size    sizes]
          ;; IDB is slow under fake-indexeddb at large sizes; skip past 100k.
          (when-not (and (= backend :idb) (> size 100000))
            (let [r (<! (run-bench-once backend size batch-size))]
              (swap! results conj r))))
        (format-summary @results)
        (println "\n[bench] done")))))
