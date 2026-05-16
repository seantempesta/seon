(ns seon.podhost.datahike-harness.workloads
  "Named workloads: each takes a datahike conn (and a few params) and returns
   a result map suitable for EDN dumping. Workloads are pure data-producers;
   the CLI binds them to backends + sizes.

   Available:
     bulk-load  — transact N entities in batches; measure throughput
     queries    — run the standard query battery against a loaded DB
     cold-resume — close + reopen DB; measure connect + first-query time"
  (:require [datahike.api :as d]
            [seon.podhost.datahike-harness.schema :as schema]
            [seon.podhost.datahike-harness.metrics :as m]))

;; ---------------------------------------------------------------------------
;; bulk-load
;; ---------------------------------------------------------------------------

(defn bulk-load!
  "Transact `n` entities into `conn` in batches of `batch-size`. Schema must
   already be transacted. Returns {:ms total :batches N :id-vec ids}."
  [conn n batch-size]
  (let [id-vec (schema/gen-id-vec n)
        [_ ms] (m/time-block
                (loop [start 0 batches 0]
                  (if (>= start n)
                    batches
                    (let [m       (min batch-size (- n start))
                          batch   (schema/gen-batch start m id-vec)]
                      (d/transact conn batch)
                      (recur (+ start m) (inc batches))))))]
    {:ms ms :id-vec id-vec}))

;; ---------------------------------------------------------------------------
;; queries — same battery as the CLJS bench
;; ---------------------------------------------------------------------------

(defn- time-q [db q & inputs]
  (try
    (let [[res ms] (m/time-block (apply d/q q db inputs))]
      {:ms ms :count (count res) :sample (take 2 res)})
    (catch Throwable t
      {:ms -1 :err (ex-message t)})))

(defn- time-pull [db lookup-ref]
  (try
    (let [[res ms] (m/time-block (d/pull db '[*] lookup-ref))]
      {:ms ms :keys (count (keys (or res {})))})
    (catch Throwable t
      {:ms -1 :err (ex-message t)})))

(defn run-queries
  "The standard query battery: scan-all, scan-by-tag, indexed-by-id, pull,
   range-by-time. Indices `pick-idx` into the id-vec for indexed-by-id +
   path-based pull."
  [conn id-vec size]
  (let [db        @conn
        pick-idx  (quot size 2)
        pick-id   (nth id-vec pick-idx)
        pick-path (str "vault/notes/note-" pick-idx ".md")]
    {:scan-all       (time-q db '[:find (count ?e) :where [?e :note/path _]])
     :scan-by-tag    (time-q db '[:find ?p :in $ ?t
                                  :where [?e :note/tag ?t] [?e :note/path ?p]]
                             "seon")
     :indexed-by-id  (time-q db '[:find ?p :in $ ?id
                                  :where [?e :note/id ?id] [?e :note/path ?p]]
                             pick-id)
     :pull-by-path   (time-pull db [:note/path pick-path])
     :range-by-time  (time-q db '[:find (count ?e)
                                  :where
                                  [?e :note/created-at ?t]
                                  [(>= ?t 1700050000000)]
                                  [(<= ?t 1700100000000)]])}))

;; ---------------------------------------------------------------------------
;; cold-resume — close + reopen; measure connect + first query
;; ---------------------------------------------------------------------------

(defn cold-resume!
  "Given a config that points at an already-populated store, close any open
   connection, re-create a fresh JVM-side process state (release-store +
   garbage-collect references), then time connect + first query.

   This isn't a perfect cold-process measurement — we still share a JVM —
   but it captures the konserve sync + b-tree root-resolve cost, which is
   the dominant on-disk piece of resume latency. For real cold-process
   measurement, the CLI runs this in a separate `clojure -M:run` invocation."
  [cfg]
  (let [[conn connect-ms]
        (m/time-block (d/connect cfg))
        [first-q first-ms]
        (m/time-block (d/q '[:find (count ?e) :where [?e :note/path _]] @conn))]
    (d/release conn)
    {:connect-ms connect-ms
     :first-query-ms first-ms
     :first-query-count (ffirst first-q)}))
