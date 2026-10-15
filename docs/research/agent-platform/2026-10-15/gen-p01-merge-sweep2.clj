(require '[rewrite-clj.zip :as z] '[rewrite-clj.node :as n] '[clojure.java.io :as io])
(def skip (set (rest *command-line-args*)))
(def bases #{:seon.turn/error :my.root/open-assignment :seon.schema.finding/base :seon.config/error :seon.test/result :seon.render.web/server :seon.render/call-request :my.turn/completed :seon.cluster/servable})
(defn variant? [zloc]
  (and (z/vector? zloc)
       (let [kids (z/child-sexprs zloc)]
         (and (= :and (first kids))
              (some bases kids)))))
(defn mergeable? [zloc]
  (let [kids (rest (z/child-sexprs zloc))
        kids (if (map? (first kids)) (rest kids) kids)]
    (every? #(or (qualified-keyword? %) (and (vector? %) (= :map (first %)))) kids)))
(def stats (atom {:converted 0 :skipped []}))
(doseq [f (sort (filter #(.endsWith (.getName ^java.io.File %) ".edn") (file-seq (io/file "resources/seon/schemas"))))
        :when (not (skip (.getName f)))]
  (let [zloc (z/of-file f {:track-position? false})
        changed (atom 0)
        out (loop [loc zloc]
              (let [next-loc
                    (if (variant? loc)
                      (if (mergeable? loc)
                        (do (swap! changed inc) (swap! stats update :converted inc)
                            (z/replace (z/down loc) (n/keyword-node :merge)))
                        (do (swap! stats update :skipped conj [(.getName f) (z/sexpr loc)]) loc))
                      loc)
                    nxt (z/next next-loc)]
                (if (z/end? nxt) (z/root-string next-loc) (recur nxt))))]
    (when (pos? @changed) (spit f out))))
(prn (:converted @stats))
(doseq [s (:skipped @stats)] (prn s))
