(in-ns 'falsify.harness)
;;; ATTACK 1 — ordering stability (spec P4) over 30 turns of realistic churn.

(defn narrow-basis
  "max tx over the entity's OWN datoms (a pull-only read set)."
  [db eid]
  (reduce max 0 (map :tx (d/datoms db :eavt eid))))

(defn walk-basis
  "max tx over what the WALK actually reads: own datoms + reverse datoms."
  [db eid]
  (max (narrow-basis db eid)
       (reduce max 0 (map (fn [[t]] t)
                          (d/q '[:find ?tx :in $ ?target
                                 :where [?s ?a ?target ?tx]] db eid)))))

(defn flat-blocks
  "The walk's distinct entities as flat blocks: {eid text basis}."
  [db root basis-fn]
  (let [n (walk-node db root 2)]
    (->> (nodes n)
         (keep (fn [nd]
                 (when-let [eid (:seon.render.walk/lookup nd)]
                   (when (integer? eid)
                     {:eid eid :text (or (node-text nd) "")
                      :basis (basis-fn db eid)}))))
         (reduce (fn [m b] (if (contains? m (:eid b)) m (assoc m (:eid b) b))) {})
         vals
         (sort-by (juxt :basis :eid))
         vec)))

(defn assemble [blocks] (str/join "\n\n" (map :text blocks)))

(defn common-prefix [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0] (if (and (< i n) (= (.charAt ^String a i) (.charAt ^String b i)))
                  (recur (inc i)) i))))

(defn inversions
  "Pairs of blocks present in BOTH orders whose relative order flipped."
  [before after]
  (let [ai (into {} (map-indexed (fn [i b] [(:eid b) i])) after)
        shared (filterv #(contains? ai (:eid %)) before)
        idx (mapv #(ai (:eid %)) shared)]
    [(count shared)
     (count (for [i (range (count idx)) j (range (inc i) (count idx))
                  :when (> (nth idx i) (nth idx j))] 1))]))

(defn unchanged-set
  "eids whose own datoms did not change between the two bases."
  [db-before db-after eids]
  (into #{} (filter (fn [e] (= (narrow-basis db-before e) (narrow-basis db-after e)))) eids))

(defn run-scenario [basis-fn label]
  (with-db
    (fn [conn]
      (seed! conn)
      (let [root [:seon.cluster.agent/id "alpha"]
            results (atom [])]
        (loop [turn 0, prev-db nil, prev-blocks nil, prev-text nil]
          (when (< turn 30)
            ;; churn for this turn
            (message! conn {:id (str "m" turn) :to "alpha"
                            :from (if (even? turn) "beta" nil)
                            :content (str "turn " turn " request")
                            :at (java.util.Date. (+ 1785000000000 (* turn 60000)))})
            ;; every 3rd turn: mutate an instruction row in place (spec §4(2))
            (when (zero? (mod turn 7))
              (tx! conn [{:seon.cluster.instruction/id :global
                          :seon.cluster.instruction/text
                          (str "Project instructions rev " turn)}]))
            (let [db @conn
                  blocks (flat-blocks db root basis-fn)
                  text (assemble blocks)]
              (when prev-blocks
                (let [[shared inv] (inversions prev-blocks blocks)
                      unchanged (unchanged-set prev-db db
                                               (set (map :eid prev-blocks)))
                      ;; inversions restricted to UNCHANGED blocks
                      pb (filterv #(unchanged (:eid %)) prev-blocks)
                      [ushared uinv] (inversions pb blocks)
                      cp (common-prefix prev-text text)]
                  (swap! results conj
                         {:turn turn :blocks (count blocks) :shared shared
                          :inversions inv :unchanged (count unchanged)
                          :unchanged-inversions uinv :unchanged-shared ushared
                          :prefix cp :bytes (count text)
                          :prefix-frac (if (pos? (count text))
                                         (double (/ cp (count text))) 0.0)})))
              (recur (inc turn) db blocks text))))
        (let [rs @results]
          (println "\n===" label "===")
          (println (format "turns=%d blocks(last)=%d" (count rs) (:blocks (last rs))))
          (println (format "turn-to-turn inversions among UNCHANGED blocks: total=%d  turns-with-any=%d/%d"
                           (reduce + (map :unchanged-inversions rs))
                           (count (filter #(pos? (:unchanged-inversions %)) rs))
                           (count rs)))
          (println (format "prefix survival: mean=%.1f%% min=%.1f%% max=%.1f%%  (mean bytes kept=%.0f of %.0f)"
                           (* 100 (/ (reduce + (map :prefix-frac rs)) (count rs)))
                           (* 100 (apply min (map :prefix-frac rs)))
                           (* 100 (apply max (map :prefix-frac rs)))
                           (double (/ (reduce + (map :prefix rs)) (count rs)))
                           (double (/ (reduce + (map :bytes rs)) (count rs)))))
          (doseq [r (take 8 rs)] (prn r)))))))

(run-scenario narrow-basis "A. read set = own datoms only (pull-narrow)")
(run-scenario walk-basis  "B. read set = what the walk really reads (own + reverse)")
