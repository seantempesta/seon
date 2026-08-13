(require '[editscript.core :as e] '[editscript.edit :as ee] '[clojure.data :as data])
(def before [{:m/id "m1" :m/text "hello"  :m/read? false}
             {:m/id "m2" :m/text "second" :m/read? false}
             {:m/id "m3" :m/text "third"  :m/read? false}])
(def after  [{:m/id "m1" :m/text "hello"  :m/read? true}
             {:m/id "m3" :m/text "third"  :m/read? false}
             {:m/id "m4" :m/text "fourth" :m/read? false}])
(println :vectors-astar (ee/get-edits (e/diff before after)))
(println :vectors-quick (ee/get-edits (e/diff before after {:algo :quick})))
(defn by-id [c] (update-vals (group-by :m/id c) first))
(def d (e/diff (by-id before) (by-id after)))
(println :keyed-edits (ee/get-edits d))
(println :keyed-size (ee/get-size d) :adds (ee/get-adds-num d) :dels (ee/get-dels-num d) :reps (ee/get-reps-num d))
(println :patch-roundtrip (= (by-id after) (e/patch (by-id before) d)))
(println :no-change-edits (ee/get-edits (e/diff (by-id after) (by-id after))))
(println :core-diff-no-change (data/diff (by-id after) (by-id after)))
;; scale + timing on 5000 rows, one changed
(def big-b (into {} (for [i (range 5000)] [(str "m" i) {:m/id (str "m" i) :m/text (str "t" i) :m/read? false}])))
(def big-a (assoc big-b "m5" {:m/id "m5" :m/text "t5" :m/read? true}))
(defn ms [f] (dotimes [_ 3] (f)) (let [s (System/nanoTime) v (f)] [(/ (- (System/nanoTime) s) 1e6) v]))
(let [[t v] (ms #(ee/get-edits (e/diff big-b big-a)))] (println :editscript-5000-astar-ms t :edits v))
(let [[t v] (ms #(ee/get-edits (e/diff big-b big-a {:algo :quick})))] (println :editscript-5000-quick-ms t :edits v))
(let [[t v] (ms #(data/diff big-b big-a))] (println :core-diff-5000-ms t :only-a-keys (keys (first v))))
