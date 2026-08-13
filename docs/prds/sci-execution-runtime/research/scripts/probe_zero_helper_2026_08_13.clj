(require '[clojure.data :as data] '[clojure.set :as set] '[clojure.pprint :as pp])
(defn show [l v] (println (str "\n;; === " l " ===")) (pp/pprint v))
(println ";; Clojure" (clojure-version))

(def before [{:my.message/id "m1" :my.message/preview "hello"}
             {:my.message/id "m2" :my.message/preview "second"}])
(def after  [{:my.message/id "m1" :my.message/preview "hello, edited"}
             {:my.message/id "m4" :my.message/preview "fourth"}])

;; the ZERO-HELPER idiom, core functions only, no library, no seon namespace
(show "(update-vals (group-by :my.message/id before) first)"
      (update-vals (group-by :my.message/id before) first))
(show "ZERO-HELPER diff, one form the agent can type"
      (data/diff (update-vals (group-by :my.message/id before) first)
                 (update-vals (group-by :my.message/id after) first)))

;; clojure.set/index — the "real relational" convention
(show "(clojure.set/index (set before) [:my.message/id])"
      (set/index (set before) [:my.message/id]))
(show "data/diff over set/index results"
      (data/diff (set/index (set before) [:my.message/id])
                 (set/index (set after)  [:my.message/id])))

;; does data/diff distinguish added vs changed WITHOUT extra work?
(let [[b a both] (data/diff (update-vals (group-by :my.message/id before) first)
                            (update-vals (group-by :my.message/id after) first))]
  (show "classification from the triple, by key-set algebra"
        {:added   (sort (set/difference (set (keys a)) (set (keys b))))
         :removed (sort (set/difference (set (keys b)) (set (keys a))))
         :changed (sort (set/intersection (set (keys a)) (set (keys b))))}))
