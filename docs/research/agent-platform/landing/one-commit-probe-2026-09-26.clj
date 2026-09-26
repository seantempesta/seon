;; One bounded probe: 20 trivial forms as ordinary runs of a branch's hosted
;; agent through seon.cluster.agent/evaluate-source! (the MCP SCI entrance
;; minus the bridge), timing each form and counting commits on the branch.
;; :durable-ms is the time from submission until the branch's committed value
;; holds the run (under A it equals :ms; under B it is when durability lands,
;; i.e. what A would have answered at on the same JVM).
(let [cluster "default"
      branch :one-commit-a
      instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) cluster)
      handle (:seon.turn.loop/cluster instance)
      host ((requiring-resolve 'seon.cluster.host/host!)
            {:seon.turn.loop/cluster (assoc handle :seon.store/store (:seon.store/store instance))
             :seon.agent/branch branch :seon.agent/id "mcp/one-commit-a" :seon.ns/name 'user})
      custody (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
      conn (:seon.db/connection (:seon.turn.loop/cluster custody))
      run! (requiring-resolve 'seon.cluster.agent/evaluate-source!)
      sample (fn [i]
               (let [t0 (System/nanoTime) b0 (seon.db/basis-t @conn)
                     r (run! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                    :seon.cluster.reply/text (str "(+ 1 " i ")")))
                     ms (/ (- (System/nanoTime) t0) 1e6)
                     at-answer (- (seon.db/basis-t @conn) b0)
                     id (:seon.turn/id r)
                     durable? #(seon.db/q '[:find ?r . :in $ ?id :where [?r :seon.turn/id ?id]] @conn id)
                     ;; poll the connection's value cheaply; query only when it moved
                     _ (loop [seen nil]
                         (let [now @conn]
                           (when (and id (< (/ (- (System/nanoTime) t0) 1e6) 5000)
                                      (or (identical? now seen) (not (durable?))))
                             (Thread/sleep 1)
                             (recur now))))
                     durable-ms (/ (- (System/nanoTime) t0) 1e6)]
                 {:ms ms :durable-ms durable-ms :ok (boolean id) :err (:seon.error/message r)
                  :commits-at-answer at-answer}))
      _ (sample 0)
      rows (mapv sample (range 1 21))
      sorted (sort (map :ms rows))
      pct (fn [p] (nth sorted (min 19 (int (Math/floor (* p 20))))))]
  {:p50 (pct 0.5) :p95 (pct 0.95) :min (first sorted) :max (last sorted)
   :durable-p50 (nth (sort (map :durable-ms rows)) 10) :durable-p95 (nth (sort (map :durable-ms rows)) 19)
   :ok (count (filter :ok rows)) :errs (distinct (keep :err rows))
   :commits-at-answer (frequencies (map :commits-at-answer rows))})
