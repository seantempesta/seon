;; B (reply when applied) measurement: answer vs durable, p50/p95, for (inc 1)
;; and a defn, as ordinary runs of a fresh branch's hosted agent through
;; seon.cluster.agent/evaluate-source! (the MCP SCI entrance minus the bridge).
;; :ms is the answer; :durable-ms adds the wait for the reply's own pending
;; commit (:seon.db/durable), i.e. what A (answer after commit) would answer at.
;; Each sample waits for durability before the next, so samples do not overlap.
;; Run at default's JVM REPL after the branch exists (MCP `branch` create), e.g.
;;   (def user/rwa-branch :rwa-probe)
(let [cluster "default"
      branch (deref (resolve 'user/rwa-branch))
      instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) cluster)
      handle (:seon.turn.loop/cluster instance)
      host ((requiring-resolve 'seon.cluster.host/host!)
            {:seon.turn.loop/cluster (assoc handle :seon.store/store (:seon.store/store instance))
             :seon.agent/branch branch :seon.agent/id (str "mcp/" (name branch)) :seon.ns/name 'user})
      custody (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
      run! (requiring-resolve 'seon.cluster.agent/evaluate-source!)
      sample (fn [source]
               (let [t0 (System/nanoTime)
                     r (run! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                    :seon.cluster.reply/text source))
                     ms (/ (- (System/nanoTime) t0) 1e6)
                     pending (:seon.db/durable r)
                     committed (when pending (deref pending 5000 ::timeout))
                     durable-ms (/ (- (System/nanoTime) t0) 1e6)]
                 {:ms ms :durable-ms durable-ms :applied? (some? pending)
                  :ok (boolean (:seon.turn/id r)) :err (:seon.error/message r)
                  :committed? (map? committed)}))
      summary (fn [rows]
                (let [n (count rows)
                      pct (fn [k p] (nth (sort (map k rows)) (min (dec n) (int (Math/floor (* p n))))))]
                  {:n n :answer-p50 (pct :ms 0.5) :answer-p95 (pct :ms 0.95)
                   :durable-p50 (pct :durable-ms 0.5) :durable-p95 (pct :durable-ms 0.95)
                   :ok (count (filter :ok rows)) :applied (count (filter :applied? rows))
                   :committed (count (filter :committed? rows)) :errs (distinct (keep :err rows))}))
      _ (sample "(inc 1)")
      inc-rows (mapv (fn [_] (sample "(inc 1)")) (range 20))
      defn-rows (mapv (fn [i] (sample (str "(defn rwa-f" i " \"Add " i ".\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " i "))")))
                      (range 10))]
  {:inc (summary inc-rows) :defn (summary defn-rows)})
