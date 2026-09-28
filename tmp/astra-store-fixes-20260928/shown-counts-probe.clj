(require '[seon.db :as db] '[seon.cluster.boot :as boot])

(defn shown-before [target-lists width]
  (frequencies (map first (take width (sort-by (juxt second first)
                                               (for [[g targets] (map-indexed vector target-lists)
                                                     position (range (count targets))]
                                                 [g position]))))))
(defn shown-after
  "Allocate `width` items breadth first from each group's target count."
  {:malli/schema [:=> [:cat [:sequential nat-int?] :int] [:map-of :int :int]]}
  [counts width]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                    (keep-indexed (fn [g n] (when (pos? n) [g n]))) counts)
         left width shown {}]
    (if (and (pos? left) (seq queue))
      (let [[g n] (peek queue)]
        (recur (cond-> (pop queue) (> n 1) (conj [g (dec n)]))
               (dec left) (update shown g (fnil inc 0))))
      shown)))

(let [database (db/db (boot/connection "default")) links (db/q '[:find ?callee ?caller :where [?caller :seon.fn/calls ?callee]] database) targets (->> links (group-by first) vals (sort-by (comp - count)) (take 100) (mapv #(mapv second %)))] (def related-targets targets) {:groups (count targets) :targets (reduce + (map count targets)) :largest (count (first targets))})

(mapv (fn [n] (let [targets (subvec related-targets 0 n) width 10 answers (mapv (fn [f] (let [visited (atom 0) groups (mapv (fn [xs] (map #(do (swap! visited inc) %) (apply list xs))) targets) start (System/nanoTime) answer (f groups width)] {:answer answer :ms (/ (- (System/nanoTime) start) 1e6) :visited @visited})) [shown-before #(shown-after (mapv count %) %2)])] (assert (= (:answer (first answers)) (:answer (second answers)))) {:groups n :targets (reduce + (map count targets)) :results answers})) [1 10 100])
