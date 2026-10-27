;; Experiment 3: the hook. A watch on the agent's state (seon.agent/state, the one atom whose :db every
;; write of this process sets: agent/transact!, commit!, merge-main!) patches the ctx atom with what the
;; db's move changed. Here the datoms come from datoms-diff (the state holds no tx-data); a commit, which
;; moves :db to the same datoms flushed, changes no max-tx and is skipped.
(def ctx (atom nil))
(def hook-log (atom []))
(defn on-state [_ _ old new]
  (let [a (:db old) b (:db new)]
    (when (and a b @ctx (not (identical? a b)) (not= (:max-tx a) (:max-tx b)))
      (let [m (volatile! nil)
            _ (seon.profile/measure!
               #(let [[gone added] (d/datoms-diff a b :eavt [])
                      ds (vec (concat (map (fn [x] (dm x false)) gone) (map (fn [x] (dm x true)) added)))]
                  (swap! ctx patch b ds)
                  (count ds))
               (fn [r] (vreset! m r)))]
        (swap! hook-log conj (select-keys @m [:ticks :bytes]))))))
(reset! ctx (ctx0))
(add-watch agent/state ::ctx on-state)
(reset! n 90000)
:installed
