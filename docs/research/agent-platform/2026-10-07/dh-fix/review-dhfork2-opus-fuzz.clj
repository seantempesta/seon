;; review-dhfork2-opus random differential (planner vs base), 1,500 queries per seed. Load after review-dhfork2-opus-cases.clj in the same JVM.
(in-ns 'cx)
(def attrs {:p/friend :ref :p/likes :ref :p/score :num :p/age :num :p/team :kw :p/nums :num :p/tags :str})
(def consts {:ref [1 2 3 5 9] :num [0 1 2 3 4 7] :kw [:t0 :t1 :t2] :str ["tag0" "tag1" "tag2" "late"]})
(def vs '[?a ?b ?c ?d ?e])
(defn pick [r xs] (nth xs (.nextInt ^java.util.Random r (count xs))))
(defn pattern [r]
  (let [a (pick r (keys attrs)) e (pick r vs)
        v (case (.nextInt ^java.util.Random r 4) 0 (pick r (consts (attrs a))) 1 e (pick r vs))]
    (if (= 0 (.nextInt ^java.util.Random r 6)) [e a v (pick r '[?tx ?t2]) ] [e a v])))
(defn query [r]
  (let [ps (vec (repeatedly (+ 1 (.nextInt ^java.util.Random r 3)) #(pattern r)))
        bound (vec (distinct (filter symbol? (mapcat identity ps))))
        bv #(pick r bound)
        extras (vec (repeatedly (.nextInt ^java.util.Random r 3)
                     (fn [] (case (.nextInt ^java.util.Random r 8)
                              0 [(list (pick r '[< = > not=]) (bv) (bv))]
                              1 [(list '> (bv) (pick r [1 2 3]))]
                              2 [(list 'get-else '$ (bv) (pick r [:p/age :p/score]) -1) (pick r (into bound '[?g]))]
                              3 (list 'not (pattern r))
                              4 (list 'not-join [(bv)] (pattern r))
                              5 (let [x (bv)] (list 'or [x (pick r [:p/score :p/age]) (pick r [1 2 3])] [x :p/team (pick r [:t0 :t1])]))
                              6 [(list 'identity (bv)) (pick r (into bound '[?h]))]
                              7 (let [x (bv)] (list 'or-join [x] [x :p/friend (pick r vs)] [(pick r vs) :p/likes x]))))))
        all (into ps extras)
        avail (vec (distinct (filter #(and (symbol? %) (= \? (first (name %))) (not (#{'?tx '?t2} %))) (flatten (map #(if (seq? %) (if (#{'not 'not-join} (first %)) [] (seq %)) %) all)))))
        fv (vec (take (+ 1 (.nextInt ^java.util.Random r 2)) (shuffle avail)))]
    (into [:find] (concat (if (seq fv) fv ['?a]) [:where] all))))
(defn run-fuzz [seed n dbs]
  (let [r (java.util.Random. seed)]
    (reduce (fn [acc i]
              (let [qq (query r) [dn database] (pick r dbs)
                    p (run1 database qq [] false) b (run1 database qq [] true)
                    perr (and (string? p) (.startsWith ^String p "ERR")) berr (and (string? b) (.startsWith ^String b "ERR"))]
                (cond (or (= p b) (and perr berr) (= b ::timeout) (= p ::timeout)) acc
                      :else (conj acc {:i i :db dn :q qq :planner (if (coll? p) (count p) p) :base (if (coll? b) (count b) b)}))))
            [] (range n))))
(def fuzz-dbs [[:small small] [:cur40 (W :current)] [:hist40 (W :history)] [:asof40 (W :as-of)]])
(def found (vec (mapcat #(run-fuzz % 1500 fuzz-dbs) [7 11 23 31 47])))
(println :mismatches (count found))
(doseq [f (take 25 found)] (prn f))
