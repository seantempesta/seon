;; Settlement timing of ordinary runs: 20 x (inc 1) and 10 x defn on a lane
;; branch's hosted agent through seon.cluster.agent/evaluate-source! (the MCP
;; SCI entrance minus the bridge). :ms is the answer; :durable-ms adds the wait
;; for a pending commit when the answer carries one (:seon.db/durable, the
;; applied-then-durable path); with a synchronous settlement both are equal.
;; :visible counts runs whose turn is on (seon.db/db conn) right after answer.
;; Store bytes: sum of file sizes under the store path before and after.
;; Run at default's JVM REPL after (my.agent/create-branch! {:seon.store/branch B}):
;;   (def user/rwa-branch B) (load-file "<checkout>/script/seon/dev/settlement_probe.clj")
(let [cluster "default"
      branch (deref (resolve 'user/rwa-branch))
      instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) cluster)
      handle (:seon.turn.loop/cluster instance)
      host ((requiring-resolve 'seon.cluster.host/host!)
            {:seon.turn.loop/cluster (assoc handle :seon.store/store (:seon.store/store instance))
             :seon.agent/branch branch :seon.agent/id (str "mcp/" (name branch)) :seon.ns/name 'user})
      custody (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
      evaluate! (requiring-resolve 'seon.cluster.agent/evaluate-source!)
      db (requiring-resolve 'seon.db/db)
      q (requiring-resolve 'seon.db/q)
      branch-conn (fn [] (:seon.db/connection (:seon.turn.loop/cluster custody)))
      store-path (get-in @(:seon.db/connection handle) [:config :store :path])
      store-bytes #(reduce + (map (fn [^java.io.File f] (.length f))
                                  (filter (fn [^java.io.File f] (.isFile f)) (file-seq (java.io.File. ^String store-path)))))
      sample (fn [source]
               (let [t0 (System/nanoTime)
                     r (evaluate! (assoc custody :seon.agent/segmenter 'seon.turn/code-sources
                                    :seon.cluster.reply/text source))
                     ms (/ (- (System/nanoTime) t0) 1e6)
                     run-id (:seon.turn/id r)
                     conn (branch-conn)
                     visible (when (and run-id conn)
                               (some? (q '[:find ?run . :in $ ?id :where [?run :seon.turn/id ?id]] (db conn) run-id)))
                     pending (:seon.db/durable r)
                     _ (when pending (deref pending 5000 ::timeout))
                     durable-ms (/ (- (System/nanoTime) t0) 1e6)]
                 {:ms ms :durable-ms durable-ms :applied? (some? pending) :visible? visible
                  :ok (boolean run-id) :err (:seon.error/message r)}))
      summary (fn [rows]
                (let [n (count rows)
                      pct (fn [k p] (nth (sort (map k rows)) (min (dec n) (int (Math/floor (* p n))))))]
                  {:n n :answer-p50 (pct :ms 0.5) :answer-p95 (pct :ms 0.95)
                   :durable-p50 (pct :durable-ms 0.5) :durable-p95 (pct :durable-ms 0.95)
                   :ok (count (filter :ok rows)) :applied (count (filter :applied? rows))
                   :visible (count (filter :visible? rows)) :errs (distinct (keep :err rows))}))
      _ (sample "(inc 1)")
      bytes-0 (store-bytes)
      inc-rows (mapv (fn [_] (sample "(inc 1)")) (range 20))
      bytes-1 (store-bytes)
      defn-rows (mapv (fn [i] (sample (str "(defn rwa-f" i " \"Add " i ".\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " i "))")))
                      (range 10))
      bytes-2 (store-bytes)]
  {:inc (assoc (summary inc-rows) :store-bytes-per-run (quot (- bytes-1 bytes-0) 20))
   :defn (assoc (summary defn-rows) :store-bytes-per-run (quot (- bytes-2 bytes-1) 10))})
