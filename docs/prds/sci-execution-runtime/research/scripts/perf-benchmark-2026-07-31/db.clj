;; Section 1 — database write/read throughput on the file store.
;;
;; Isolated from wakes and renders on purpose: a NEW branch forked from the
;; live bench cluster's branch (so the full schema, config facts and cluster
;; entity are present) opened with `seon.cluster.store/open-branch!`. Nothing
;; listens on it, so what is measured is Datahike's serial writer over the
;; file store, through Seon's one write door `seon.cluster.store/transact!`.
;; The live-cluster (wake + render attached) numbers are measured separately
;; in web.clj.
(require '[datahike.api :as d]
         '[seon.cluster.store :as store]
         '[seon.cluster.registry :as registry]
         '[seon.cluster.agent :as agent])

(def inst (get @@(ns-resolve 'seon.cluster 'running-instances) "bench"))
(def cluster-conn (:seon.boot/cluster-connection inst))
(def bench-store (:seon.store/store inst))

(defn quantiles
  "Median / p95 / mean over nanosecond samples, plus derived per-second rate."
  [samples]
  (let [sorted (vec (sort samples))
        n (count sorted)
        at (fn [q] (nth sorted (min (dec n) (long (* q n)))))
        median (at 0.5)
        p95 (at 0.95)
        total (reduce + 0 sorted)]
    {:n n
     :median-ms (/ (Math/round (/ median 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ p95 1000.0)) 1000.0)
     :mean-ms (/ (Math/round (/ (double (/ total n)) 1000.0)) 1000.0)
     :sustained-per-s (Math/round (/ (* 1e9 n) (double total)))}))

(defn timed
  "Run `f` `warm` times untimed, then `n` times returning nanosecond samples."
  [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i]
          (let [start (System/nanoTime)]
            (f (+ warm i))
            (- (System/nanoTime) start)))
        (range n)))

;;; the isolated bench branch -------------------------------------------------
(registry/branch! {:seon.store/store bench-store
                   :seon.cluster.registry/from :cluster-bench
                   :seon.store/branch :bench-db})
(def conn (store/open-branch! bench-store :bench-db))
(println :branch-datoms (count (d/datoms @conn :eavt)))

(def root-eid
  (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "root"]] @conn))
(def process-eid
  (d/q '[:find ?p . :where [?p :seon.db.process/id _]] @conn))
(def process-id
  (d/q '[:find ?id . :where [?e :seon.db.process/id ?id]] @conn))
(println :root-eid root-eid :process process-id)

;;; shape (a) — one small fact commit: a message row --------------------------
(defn message-row [i]
  [{:seon.cluster.message/id (str "bench-msg-" i)
    :seon.cluster.message/to root-eid
    :seon.cluster.message/content (str "benchmark message " i)
    :seon.cluster.message/at (java.util.Date.)}])

(def before-a (:max-tx @conn))
(def samples-a (timed 50 500 (fn [i] (store/transact! conn (message-row i)))))
(def datoms-a
  (count (:tx-data (store/transact! conn (message-row 999999)))))
(println :A (pr-str (assoc (quantiles samples-a) :datoms-per-tx datoms-a)))

;;; shape (b) — a medium commit: agent creation -------------------------------
(defn agent-tx [i]
  (agent/creation-tx {:seon.cluster.agent/id (str "bench-agent-" i)
                      :seon.ns/name (symbol (str "my.agents.bench" i))
                      :seon.cluster/name "bench"}))

(def samples-b (timed 20 300 (fn [i] (store/transact! conn (agent-tx i)))))
(def datoms-b
  (count (:tx-data (store/transact! conn (agent-tx 999999)))))
(println :B (pr-str (assoc (quantiles samples-b) :datoms-per-tx datoms-b)))

;;; shape (c) — a small commit carrying tx-meta provenance --------------------
(defn message-with-provenance [i]
  {:tx-data (message-row (+ 2000000 i))
   :tx-meta {:seon.db/process [:seon.db.process/id process-id]}})

(def samples-c
  (timed 50 500 (fn [i] (store/transact! conn (message-with-provenance i)))))
(def datoms-c
  (count (:tx-data (store/transact! conn (message-with-provenance 999999)))))
(println :C (pr-str (assoc (quantiles samples-c) :datoms-per-tx datoms-c)))

;;; batching — the same message rows committed N per transaction --------------
(defn message-batch [i n]
  (into [] (mapcat (fn [j] (message-row (+ 3000000 (* i 1000) j)))) (range n)))

(doseq [batch [10 100]]
  (let [samples (timed 5 50 (fn [i] (store/transact! conn (message-batch i batch))))
        q (quantiles samples)]
    (println :BATCH batch
             (pr-str (assoc q :rows-per-s (Math/round (* batch (double (:sustained-per-s q)))))))))

;;; reads — a representative walk query mix -----------------------------------
(def db-value @conn)

(def queries
  {:agent-by-id
   (fn [db] (d/q '[:find ?e . :in $ ?id :where [?e :seon.cluster.agent/id ?id]]
                 db "root"))
   :messages-to-agent
   (fn [db] (count (d/q '[:find ?m ?content
                          :in $ ?to
                          :where [?m :seon.cluster.message/to ?to]
                          [?m :seon.cluster.message/content ?content]]
                        db root-eid)))
   :pull-agent
   (fn [db] (d/pull db '[* {:seon.cluster.agent/namespace [*]}] root-eid))
   :fns-in-namespace
   (fn [db] (count (d/q '[:find ?f ?sym
                          :in $ ?ns
                          :where [?f :seon.fn/ns ?ns]
                          [?f :seon.fn/sym ?sym]]
                        db 'seon.cluster.store)))
   :corpus-census
   (fn [db] (count (d/q '[:find ?f :where [?f :seon.fn/sym _]] db)))})

(doseq [[query-name query] (sort-by key queries)]
  (let [samples (timed 20 200 (fn [_] (query db-value)))]
    (println :READ-CACHED query-name (pr-str (quantiles samples)))))

;; uncached: a fresh database value each time — a new commit changes the db
;; value, so Datahike's query cache (keyed on the whole query + inputs) misses.
;; The commit itself is OUTSIDE the timed window.
(doseq [[query-name query] (sort-by key queries)]
  (let [samples (mapv (fn [i]
                        (store/transact! conn (message-row (+ 5000000 (* 100 (hash query-name)) i)))
                        (let [db @conn
                              start (System/nanoTime)]
                          (query db)
                          (- (System/nanoTime) start)))
                      (range 30))]
    (println :READ-UNCACHED query-name (pr-str (quantiles samples)))))

(println :FINAL-DATOMS (count (d/datoms @conn :eavt)))
(println :DB-DONE)
