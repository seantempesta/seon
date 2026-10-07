(def schema
  {:p/id {:db/unique :db.unique/identity}
   :p/uv {:db/unique :db.unique/value}
   :p/friend {:db/valueType :db.type/ref}
   :p/likes {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :p/age {:db/index true}
   :p/team {}
   :p/score {}
   :p/tags {:db/cardinality :db.cardinality/many}
   :p/nums {:db/cardinality :db.cardinality/many}
   :p/pair {:db/valueType :db.type/tuple :db/tupleTypes [:db.type/keyword :db.type/long]}})

(defn person [n i]
  (let [g (* 10 (quot i 10)) k (mod i 10)]
    (cond-> {:db/id (inc i) :p/id (str "p" i) :p/uv (+ 1000 i) :p/age (mod (* 7 i) 23)
             :p/team (keyword (str "t" (mod i 4))) :p/score (mod i 5)
             :p/tags [(str "tag" (mod i 3)) (str "tag" (mod i 5))]
             :p/nums [(mod i 3) (mod i 4)]
             :p/pair [(keyword (str "k" (mod i 3))) (mod i 2)]
             :p/likes [(inc (+ g (mod (inc k) 10))) (inc (+ g (mod (+ k 3) 10)))]}
      (pos? k) (assoc :p/friend i))))

(defn worlds [n]
  (let [d0 (db/empty-db schema {:keep-history? true})
        d1 (d/db-with d0 (mapv #(person n %) (range n)))
        d2 (d/db-with d1 (vec (concat
                               (for [i (range 0 n 7)] [:db/add (inc i) :p/age (+ 50 (mod i 9))])
                               (for [i (range 0 n 11)] [:db/add (inc i) :p/nums 7]))))
        mid (:max-tx d2)
        d3 (d/db-with d2 (vec (concat
                               (for [i (range 3 n 13)] [:db/retract (inc i) :p/friend i])
                               (for [i (range 0 n 6)] [:db/retract (inc i) :p/tags (str "tag" (mod i 3))])
                               (for [i (range 1 n 9)] [:db/add (inc i) :p/tags "late"])
                               (for [i (range 2 n 17)] [:db/add (inc i) :p/age (mod i 9)]))))
        d4 (d/db-with d3 [[:db/retractEntity [:p/id "p4"]]
                          [:db/add 5 :p/nums 1] [:db/retract 5 :p/nums 1]])]
    [[:current d4] [:history (d/history d4)] [:as-of (d/as-of d4 mid)]]))

(def reach '[[(reach ?a ?b) [?a :p/friend ?b]] [(reach ?a ?b) [?a :p/friend ?x] (reach ?x ?b)]])
;; position 0 invariant (left-linear)
(def anc '[[(anc ?x ?y) [?x :p/friend ?y]] [(anc ?x ?y) (anc ?x ?z) [?z :p/friend ?y]]])
;; position 1 invariant (right-linear)
(def desc '[[(desc ?x ?y) [?x :p/friend ?y]] [(desc ?x ?y) [?x :p/friend ?z] (desc ?z ?y)]])
;; head spelled like caller vars, swapped
(def swapped '[[(sw ?b ?a) [?b :p/friend ?a]] [(sw ?b ?a) (sw ?b ?z) [?z :p/friend ?a]]])
;; three heads, a label passed through
(def labelled '[[(lp ?t ?x ?y) [?x :p/team ?t] [?x :p/likes ?y]]
                [(lp ?t ?x ?y) (lp ?t ?x ?z) [?z :p/likes ?y] [?y :p/team ?t]]])
;; one branch passes through, another does not
(def mixed '[[(mx ?x ?y) [?x :p/friend ?y]]
             [(mx ?x ?y) (mx ?x ?z) [?z :p/friend ?y]]
             [(mx ?x ?y) [?x :p/likes ?z] [(< ?z ?x)] (mx ?z ?y)]])
;; head var also used in a body predicate
(def guarded '[[(gd ?x ?y) [?x :p/likes ?y]]
               [(gd ?x ?y) (gd ?x ?z) [?z :p/likes ?y] [(not= ?y ?x)]]])
;; mutual recursion
(def mutual '[[(even ?x ?y) [?x :p/friend ?y]]
              [(even ?x ?y) (odd ?x ?z) [?z :p/friend ?y]]
              [(odd ?x ?y) (even ?x ?z) [?z :p/friend ?y]]])
;; recursive rule called from a non-recursive one, internal names collide with outer
(def wrapped (into reach '[[(wrap ?a ?b) (reach ?a ?x) [?x :p/id ?b]]]))
(def nonrec '[[(edge ?e ?x) [?x :p/likes ?e]]
              [(same ?x ?y) [(= ?x ?y)]]
              [(has-pair ?e ?p) [?e :p/pair ?p]]
              [(friend-of ?x ?y) [?x :p/friend ?y]]])

(def ref-anc (into '[[(anc ?x ?y) (reach ?x ?y)]] reach))
(def ref-sw (into '[[(sw ?b ?a) (reach ?b ?a)]] reach))
(def ref-mixed '[[(fc ?x ?y) [?x :p/friend ?y]] [(fc ?x ?y) [?x :p/friend ?z] (fc ?z ?y)]
                 [(mx ?x ?y) (fc ?x ?y)] [(mx ?x ?y) [?x :p/likes ?z] [(< ?z ?x)] (mx ?z ?y)]])
(def ref-guarded '[[(lk ?x ?y) [?x :p/likes ?y]] [(lk ?x ?y) [?x :p/likes ?z] (lk ?z ?y)]
                   [(gd ?x ?y) (lk ?x ?y) [(not= ?x ?y)]]])
(def ref-labelled '[[(ch ?t ?z ?y) [?z :p/likes ?y] [?y :p/team ?t]]
                    [(ch ?t ?z ?y) [?z :p/likes ?w] [?w :p/team ?t] (ch ?t ?w ?y)]
                    [(lp ?t ?x ?y) [?x :p/team ?t] [?x :p/likes ?y]]
                    [(lp ?t ?x ?y) [?x :p/team ?t] [?x :p/likes ?y1] (ch ?t ?y1 ?y)]])
(def ref-mutual '[[(even ?x ?y) [?x :p/friend ?y]]
                  [(even ?x ?y) [?x :p/friend ?a] [?a :p/friend ?b] (even ?b ?y)]
                  [(odd ?x ?y) [?x :p/friend ?a] (even ?a ?y)]])
(def references {anc ref-anc swapped ref-sw mixed ref-mixed guarded ref-guarded labelled ref-labelled mutual ref-mutual})

