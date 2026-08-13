(require '[clojure.data :as data] '[clojure.set :as set] '[clojure.pprint :as pp])

(def before
  [{:seon.message/id "m1" :seon.message/text "hello"  :seon.message/read? false}
   {:seon.message/id "m2" :seon.message/text "second" :seon.message/read? false}
   {:seon.message/id "m3" :seon.message/text "third"  :seon.message/read? false}])

(def after
  [{:seon.message/id "m1" :seon.message/text "hello"  :seon.message/read? true}   ; changed
   {:seon.message/id "m3" :seon.message/text "third"  :seon.message/read? false}  ; unchanged, MOVED
   {:seon.message/id "m4" :seon.message/text "fourth" :seon.message/read? false}]) ; added
;; m2 removed

(defn show [label v] (println (str "\n;; === " label " ===")) (pp/pprint v))

(show "(clojure.data/diff before after)" (data/diff before after))

(show "sets: only-before" (set/difference (set before) (set after)))
(show "sets: only-after"  (set/difference (set after) (set before)))
(show "sets: both"        (set/intersection (set before) (set after)))

;;; identity-aware, core functions only
(def k :seon.message/id)
(let [b (into {} (map (juxt k identity)) before)
      a (into {} (map (juxt k identity)) after)
      bk (set (keys b)) ak (set (keys a))]
  (show "identity diff via clojure.set on key sets"
        {:added   (mapv a (sort (set/difference ak bk)))
         :removed (mapv b (sort (set/difference bk ak)))
         :changed (into [] (comp (filter #(not= (b %) (a %))) (map #(vector (b %) (a %))))
                        (sort (set/intersection ak bk)))}))

;;; same thing as merge-with over the two maps
(let [pairs (merge-with (fn [[x _] [_ y]] [x y])
                        (into {} (map (juxt k (fn [m] [m nil]))) before)
                        (into {} (map (juxt k (fn [m] [nil m]))) after))]
  (show "merge-with pairing (one expression, before/after pairs)" (into (sorted-map) pairs))
  (show "clojure.data/diff of the two identity MAPS (not vectors)"
        (data/diff (into {} (map (juxt k identity)) before)
                   (into {} (map (juxt k identity)) after))))

;;; what does data/diff give for the SAME collections as sets?
(show "(data/diff (set before) (set after))" (data/diff (set before) (set after)))
