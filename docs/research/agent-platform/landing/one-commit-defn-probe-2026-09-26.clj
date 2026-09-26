;; Time contracted defns as ordinary runs of a fresh branch's hosted agent:
;; the first (host acquisition + first program change) and three later ones.
;; Bind `branch` before loading: (def oc-branch :name) in user.
(let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
      handle (:seon.turn.loop/cluster instance)
      host ((requiring-resolve 'seon.cluster.host/host!)
            {:seon.turn.loop/cluster (assoc handle :seon.store/store (:seon.store/store instance))
             :seon.agent/branch (deref (resolve 'user/oc-branch)) :seon.agent/id (str "mcp/" (name (deref (resolve 'user/oc-branch)))) :seon.ns/name 'user})
      custody (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id])
      run! (requiring-resolve 'seon.cluster.agent/evaluate-source!)
      timed (fn [text] (let [t0 (System/nanoTime)
                             r (run! (assoc custody :seon.agent/segmenter 'seon.cluster.reply/code-sources :seon.cluster.reply/text text))]
                         {:ms (/ (- (System/nanoTime) t0) 1e6) :ok (boolean (:seon.turn/id r)) :err (:seon.error/message r)}))]
  (mapv #(timed (str "(defn oc-probe-" % " \"Adds " % ".\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " % "))")) (range 4)))
