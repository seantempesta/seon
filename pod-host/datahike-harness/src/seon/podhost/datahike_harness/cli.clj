(ns seon.podhost.datahike-harness.cli
  "CLI entry: run the harness across (backends × sizes), print a summary
   table, and write the raw results as EDN.

   Usage:
     clojure -M:run                            # default: all backends × default sizes
     clojure -M:run --backends memory,file     # subset of backends
     clojure -M:run --sizes 1000,10000         # subset of sizes
     clojure -M:run --workload cold-resume     # different workload
     clojure -M:run --out results/2026-05-16-1.edn

   The :gcs backend incurs real network + GCS API cost — small ($0.0x range
   per full run at 10k entities) but not free. Skipped past 100k by default."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.podhost.datahike-harness.backends :as backends]
            [seon.podhost.datahike-harness.metrics :as m]
            [seon.podhost.datahike-harness.schema :as schema]
            [seon.podhost.datahike-harness.workloads :as wl]))

;; ---------------------------------------------------------------------------
;; arg parsing — keep it simple, no cli-tools dep
;; ---------------------------------------------------------------------------

(defn- parse-args [argv]
  (loop [args (vec argv) acc {}]
    (if (empty? args)
      acc
      (let [[k v & rest] args]
        (recur (vec rest) (assoc acc (keyword (subs k 2)) v))))))

(def ^:private default-sizes [1000 10000])
;; :lmdb dropped from the default set — konserve-lmdb 0.1.11 (the version on
;; `io.replikativ`) predates konserve's PAssocSerializers protocol. The newer
;; `org.replikativ/konserve-lmdb 0.1.14` requires `org.replikativ/konserve`
;; which conflicts with datahike's `io.replikativ/konserve`. Pass `--backends
;; memory,file,lmdb,gcs` explicitly if you've coordinated the version bump.
(def ^:private default-backends [:memory :file :gcs])
(def ^:private default-batch-size 1000)

(defn- read-csv [s f] (mapv f (str/split s #",\s*")))

(defn- now-tag []
  (-> (java.time.LocalDateTime/now)
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss"))))

;; ---------------------------------------------------------------------------
;; single backend × size run
;; ---------------------------------------------------------------------------

(defn run-one [backend size batch-size run-id]
  (println (str "\n[harness] backend=" backend " size=" size " batch=" batch-size " run-id=" run-id))
  (let [cfg (backends/build backend run-id)]
    (try
      ;; Clear any pre-existing store at this id
      (when (d/database-exists? cfg)
        (d/delete-database cfg))

      (let [[_ setup-ms]   (m/time-block (d/create-database cfg))
            _              (println "[harness]   setup:" (m/fmt-ms setup-ms))
            conn           (d/connect cfg)
            _              (d/transact conn schema/schema)

            {:keys [ms id-vec]} (wl/bulk-load! conn size batch-size)
            eps  (m/eps size ms)
            _ (println "[harness]   bulk-load:" (m/fmt-ms ms) "(" eps "eps )")

            queries (wl/run-queries conn id-vec size)
            _ (doseq [[qk qres] queries]
                (println (str "[harness]   " (name qk) ": "
                              (m/fmt-ms (:ms qres))
                              (when (:count qres) (str " (" (:count qres) " rows)"))
                              (when (:err qres) (str " ERR: " (:err qres))))))

            ;; cold-resume measurement: close, then re-open + first query
            _ (d/release conn)
            cold (try (wl/cold-resume! cfg)
                      (catch Throwable t
                        (println "[harness]   cold-resume ERR:" (ex-message t))
                        {:err (ex-message t)}))
            _ (when-not (:err cold)
                (println "[harness]   cold-resume connect:" (m/fmt-ms (:connect-ms cold))
                         " first-q:" (m/fmt-ms (:first-query-ms cold))))]
        {:backend backend
         :size size
         :batch-size batch-size
         :run-id run-id
         :setup-ms setup-ms
         :load-ms ms
         :load-eps eps
         :queries queries
         :cold-resume cold})
      (finally
        (backends/cleanup! backend cfg)))))

;; ---------------------------------------------------------------------------
;; multi-run driver
;; ---------------------------------------------------------------------------

(defn- write-progress! [out result]
  (when out
    (with-open [w (io/writer out)]
      (binding [*out* w] (pp/pprint result)))))

(defn run-matrix
  [{:keys [backends sizes batch-size run-tag out]
    :or {backends default-backends
         sizes default-sizes
         batch-size default-batch-size
         run-tag (now-tag)}}]
  (println "[harness] starting matrix run" run-tag)
  (println "[harness] backends:" backends "sizes:" sizes "batch:" batch-size)
  (let [results (atom [])]
    (doseq [backend backends
            size    sizes
            :when (not (and (= backend :gcs) (> size 100000)))]
      (try
        (let [r (run-one backend size batch-size
                         (str (name backend) "-" size "-" run-tag))]
          (swap! results conj r)
          (write-progress! out {:run-tag run-tag :results @results}))
        (catch Throwable t
          (println "[harness] ERR for" backend size ":" (ex-message t))
          (swap! results conj {:backend backend :size size :err (ex-message t)})
          (write-progress! out {:run-tag run-tag :results @results}))))
    (println "\n========== summary ==========")
    (println m/header)
    (doseq [r @results]
      (println (if (:err r)
                 (format "%-8s %-8s  ERR — %s" (name (:backend r)) (str (:size r)) (:err r))
                 (m/pp-row r))))
    (println)
    {:run-tag run-tag :results @results}))

(defn -main [& argv]
  (let [args (parse-args argv)
        opts (cond-> {}
               (:backends args)   (assoc :backends (read-csv (:backends args) keyword))
               (:sizes args)      (assoc :sizes (read-csv (:sizes args) #(Long/parseLong %)))
               (:batch-size args) (assoc :batch-size (Long/parseLong (:batch-size args)))
               (:out args)        (assoc :out (:out args))
               true               (assoc :run-tag (or (:tag args) (now-tag))))
        result (run-matrix opts)]
    (when (:out opts)
      (println "[harness] wrote" (:out opts)))
    (shutdown-agents)
    (System/exit 0)))
