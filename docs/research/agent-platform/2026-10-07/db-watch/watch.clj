;; DB watch: one expression, no defs. Run it as the CODE of an MCP eval_clj call
;; (mode jvm, cluster "default"); never load-file it. Edit the two inputs on the
;; first lines. It reads only: the store directory, the cluster-default database,
;; the heap pools and seon.cache/status. Cost ~1-2 s; the call itself is one
;; recorded JVM run (~20 datoms, ~22 files, ~130 KB on disk).
(let [since-minutes 60          ; growth window for files and transactions
      basis-t       nil         ; or a previous run's :basis-t to diff datom counts against
      store-dir "/Users/sean/src/seon/data/store"
      conn (seon.cluster.boot/connection "default")
      db @conn
      now (System/currentTimeMillis)
      since-ms (- now (* since-minutes 60 1000))
      ;; store: apparent bytes, 4 KB-block bytes, files, and what is newer than the window
      files (with-open [s (java.nio.file.Files/list (java.nio.file.Paths/get store-dir (make-array String 0)))]
              (into [] (map (fn [p] (let [f (.toFile ^java.nio.file.Path p)] [(.length f) (.lastModified f)]))) (iterator-seq (.iterator s))))
      fsum (fn [xs] {:files (count xs) :mb (quot (reduce + (map first xs)) 1000000)
                     :block-mb (quot (reduce + (map #(* 4096 (quot (+ 4095 (first %)) 4096)) xs)) 1000000)})
      ;; datoms by attribute, current and history
      fold (fn [ds] (persistent! (reduce (fn [m d] (assoc! m (:a d) (inc (get m (:a d) 0)))) (transient {}) ds)))
      cur (fold (datahike.api/datoms db :aevt))
      hist (fold (datahike.api/datoms (datahike.api/history db) :aevt))
      by-ns (->> cur (group-by (comp #(or % "-") namespace key)) (map (fn [[n v]] [n (reduce + (map val v))])) (sort-by second >) (take 9) vec)
      ;; transactions in the window on cluster-default, by provenance operation
      txs (into [] (comp (filter #(> (inst-ms (:v %)) since-ms)) (map :e)) (datahike.api/datoms db :aevt :db/txInstant))
      ops (frequencies (map #(:v (first (datahike.api/datoms db :eavt % :seon.db.transaction/operation))) txs))
      since-basis (when basis-t
                    (frequencies (keep #(when (> (:tx %) basis-t) (:a %)) (datahike.api/datoms (datahike.api/history db) :aevt))))
      ;; heap: live after the last old-generation collection, no forced GC
      pools (java.lang.management.ManagementFactory/getMemoryPoolMXBeans)
      after-gc (reduce + (keep #(some-> ^java.lang.management.MemoryPoolMXBean % .getCollectionUsage .getUsed) pools))
      heap (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))
      cache (seon.cache/status)
      ;; live SCI result bindings per agent context (runtime-only values; they never leave)
      inst (get @seon.operator.runtime/running-instances "default")
      roster (fn roster [r] (let [v @r] (concat (for [[[_ agent] h] (:seon.agent/contexts v)
                                                      :let [ctx (or (:seon.sci.eval/agent-ctx h) (:seon.sci.eval/ctx h))]]
                                                  [(or agent "-") (count (some-> ctx :seon.sci.eval/result-objects deref))])
                                                (mapcat (fn [[_ c]] (when (instance? clojure.lang.IDeref c) (roster c))) (:seon.agent/children v)))))
      results (when inst (vec (sort-by second > (remove (comp zero? second) (roster (:seon.agent/routing inst))))))]
  {:at (java.util.Date. now)
   :basis-t (:max-tx db)
   :store (assoc (fsum files) :newer-than-window (fsum (filter #(> (second %) since-ms) files)))
   :roster (count (datahike.api/branches conn))
   :datoms {:current (reduce + (vals cur)) :history (reduce + (vals hist))
            :top-current (vec (take 9 (sort-by val > cur)))
            :top-retracted (vec (take 8 (sort-by val > (into {} (map (fn [[a n]] [a (- n (get cur a 0))])) hist))))
            :by-namespace by-ns}
   :window {:minutes since-minutes :transactions (count txs) :by-operation (vec (sort-by val > ops))}
   :since-basis (when since-basis {:datoms (reduce + (vals since-basis)) :top (vec (take 9 (sort-by val > since-basis)))})
   :heap-mb {:used (quot (.getUsed heap) 1000000) :live-after-last-gc (quot after-gc 1000000) :max (quot (.getMax heap) 1000000)}
   :cache-mb {:weighted (quot (:seon.cache/weighted-bytes cache) 1000000) :max (quot (:seon.cache/max-bytes cache) 1000000)
              :kinds (vec (take 9 (sort-by second > (map (fn [[k v]] [k (quot (or (:seon.cache/bytes v) 0) 1000000)
                                                                  (:seon.cache/evictions v)]) (:seon.cache/kinds cache)))))}
   :live-results-by-agent (vec (take 8 results))})
