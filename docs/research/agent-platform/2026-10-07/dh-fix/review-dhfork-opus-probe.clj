;; review-dhfork-opus differential probe: planner vs base engine (*disable-planner*)
;; on current, history and as-of dbs at two sizes, plan cache fresh per query
;; and shared across worlds. Usage: java -cp ... clojure.main diff.clj OUT.edn
(ns diff
  (:require [datahike.api :as d] [datahike.db :as db] [datahike.query :as q]
            [datahike.lru :as lru] [clojure.pprint :as pp]))

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

(defn cases [n]
  (let [mid (quot n 2) ids (fn [& is] (mapv #(str "p" %) is))]
    [;; identity joins
     [:id-scalar '[:find ?e ?f :in $ ?id :where [?e :p/id ?id] [?e :p/friend ?f]] ["p5"]]
     [:id-coll '[:find ?e ?f ?a :in $ [?id ...] :where [?e :p/id ?id] [?f :p/friend ?e] [?f :p/age ?a]] [(ids 1 2 5 9 mid 99999)]]
     [:id-rel '[:find ?e ?t :in $ [[?id ?t]] :where [?e :p/id ?id] [?e :p/team ?t]] [[["p1" :t1] ["p2" :t0] ["p3" :t3] ["nope" :t1]]]]
     [:lookup-scalar '[:find ?x ?a :in $ ?e :where [?e :p/likes ?x] [?x :p/age ?a]] [[:p/id "p5"]]]
     [:lookup-coll '[:find ?x ?a :in $ [?e ...] :where [?e :p/likes ?x] [?x :p/age ?a]] [[[:p/id "p5"] [:p/id "p6"]]]]
     [:lookup-missing '[:find ?x :in $ ?e :where [?e :p/likes ?x]] [[:p/id "nope"]]]
     [:lookup-retracted '[:find ?x :in $ ?e :where [?e :p/likes ?x]] [[:p/id "p4"]]]
     [:unique-value '[:find ?e ?t :in $ [?u ...] :where [?e :p/uv ?u] [?e :p/team ?t]] [[1001 1002 99999]]]
     [:literal '[:find ?x ?a :where [?e :p/id "p7"] [?e :p/likes ?x] [?x :p/age ?a]] []]
     [:literal-absent '[:find ?x :where [?e :p/id "zz"] [?e :p/likes ?x]] []]
     [:literal-lookup '[:find ?x :where [[:p/id "p7"] :p/likes ?x]] []]
     ;; ref joins
     [:ref-join '[:find ?e ?f :where [?e :p/friend ?f] [?f :p/team :t1] [?e :p/score 2]] []]
     [:reverse '[:find ?x ?id :in $ [?id ...] :where [?e :p/id ?id] [?x :p/likes ?e]] [(ids 1 3 mid)]]
     [:card-many '[:find ?e ?t :where [?e :p/tags ?t] [?e :p/team :t2]] []]
     [:card-many-shared-v '[:find ?e ?v :where [?e :p/nums ?v] [?e :p/score ?v]] []]
     [:two-card-many-shared-v '[:find ?e ?v :where [?e :p/nums ?v] [?e :p/tags ?t] [?e :p/age ?v]] []]
     ;; predicates
     [:range '[:find ?e :where [?e :p/age ?a] [(> ?a 10)] [(< ?a 20)]] []]
     [:var-compare '[:find ?e ?f :where [?e :p/friend ?f] [?e :p/age ?a] [?f :p/age ?b] [(< ?a ?b)]] []]
     [:input-compare '[:find ?e :in $ ?min :where [?e :p/age ?a] [(> ?a ?min)] [?e :p/team :t0]] [15]]
     [:var-eq-compare '[:find ?e ?f :where [?e :p/age ?a] [?f :p/score ?s] [(= ?a ?s)] [?e :p/team :t1] [?f :p/team :t2]] []]
     [:fn-bind '[:find ?e ?g :where [?e :p/score 1] [?e :p/age ?a] [(+ ?a 1) ?a1] [?g :p/age ?a1]] []]
     [:get-else '[:find ?e ?f :where [?e :p/team :t3] [(get-else $ ?e :p/friend -1) ?f]] []]
     [:missing '[:find ?e :where [?e :p/id] [(missing? $ ?e :p/friend)]] []]
     [:pred-on-join '[:find ?form ?o :in $ ?id :where [?r :p/id ?id] [?form :p/likes ?r] [?form :p/age ?o] [(> ?o 0)]] ["p3"]]
     ;; aggregates
     [:count '[:find ?t (count ?e) :where [?e :p/team ?t]] []]
     [:count-distinct '[:find (count-distinct ?a) . :where [?e :p/age ?a]] []]
     [:max-min '[:find ?t (max ?a) (min ?a) (sum ?s) :where [?e :p/team ?t] [?e :p/age ?a] [?e :p/score ?s]] []]
     [:with '[:find (sum ?s) :with ?e :where [?e :p/score ?s]] []]
     ;; pull and find specs
     [:pull '[:find (pull ?e [:p/id {:p/friend [:p/id]}]) :in $ [?id ...] :where [?e :p/id ?id]] [(ids 1 2 3 4 5)]]
     [:scalar-find '[:find ?a . :in $ ?id :where [?e :p/id ?id] [?e :p/age ?a]] ["p8"]]
     [:coll-find '[:find [?t ...] :where [?e :p/team ?t] [?e :p/score 3]] []]
     [:tuple-find '[:find [?a ?t] :in $ ?id :where [?e :p/id ?id] [?e :p/age ?a] [?e :p/team ?t]] ["p9"]]
     ;; or / not
     [:or '[:find ?e :where [?e :p/team :t1] (or [?e :p/score 1] [?e :p/score 2])] []]
     [:or-join '[:find ?e ?x :in $ ?id :where [?e :p/id ?id] (or-join [?e ?x] [?x :p/likes ?e] [?e :p/likes ?x])] ["p3"]]
     [:not '[:find ?e :where [?e :p/team :t0] (not [?e :p/score 0])] []]
     [:not-join '[:find ?e :where [?e :p/team :t2] (not-join [?e] [?e :p/friend ?f] [?f :p/team :t1])] []]
     [:not-or '[:find ?e :where [?e :p/score 4] (not (or [?e :p/team :t0] [?e :p/team :t1]))] []]
     ;; non-recursive rules
     [:rule '[:find ?e ?x :in $ % [?id ...] :where [?e :p/id ?id] (edge ?e ?x)] [nonrec (ids 1 2 3)]]
     [:rule-lookup-literal '[:find ?x :in $ % :where (edge [:p/id "p3"] ?x)] [nonrec]]
     [:rule-lookup-input '[:find ?x :in $ % ?e :where (edge ?e ?x)] [nonrec [:p/id "p3"]]]
     [:rule-lookup-pred-literal '[:find ?e :in $ % :where [?e :p/id "p1"] (same ?e [:p/id "p1"])] [nonrec]]
     [:rule-lookup-pred-input '[:find ?e :in $ % ?r :where [?e :p/id "p1"] (same ?e ?r)] [nonrec [:p/id "p1"]]]
     [:rule-tuple-literal '[:find ?e :in $ % :where (has-pair ?e [:k1 1])] [nonrec]]
     [:rule-tuple-input '[:find ?e :in $ % ?p :where (has-pair ?e ?p)] [nonrec [:k1 1]]]
     [:rule-lookup-missing '[:find ?y :in $ % :where (friend-of [:p/id "nope"] ?y)] [nonrec]]
     [:rule-lookup-nonunique '[:find ?y :in $ % :where (friend-of [:p/team :t1] ?y)] [nonrec]]
     ;; recursive rules
     [:reach-outer-a '[:find ?b :in $ % ?id :where [?a :p/id ?id] (reach ?a ?b)] [reach "p6"]]
     [:reach-outer-coll '[:find ?id ?b :in $ % [?id ...] :where [?a :p/id ?id] (reach ?a ?b)] [reach (ids 6 9 13 mid)]]
     [:reach-lookup-input '[:find ?b :in $ % ?a :where (reach ?a ?b)] [reach [:p/id "p6"]]]
     [:reach-lookup-literal '[:find ?b :in $ % :where (reach [:p/id "p6"] ?b)] [reach]]
     [:reach-const-eid '[:find ?b :in $ % :where (reach 7 ?b)] [reach]]
     [:reach-const-target '[:find ?a :in $ % :where (reach ?a 3)] [reach]]
     [:reach-bound-b '[:find ?a :in $ % :where [?b :p/id "p2"] (reach ?a ?b)] [reach]]
     [:reach-both-bound '[:find ?a ?b :in $ % :where [?a :p/team :t1] [?b :p/team :t1] (reach ?a ?b)] [reach]]
     [:reach-rule-first '[:find ?a ?b :in $ % :where (reach ?a ?b) [?a :p/id "p8"]] [reach]]
     [:reach-self '[:find ?a :in $ % :where (reach ?a ?a)] [reach]]
     [:anc-outer-a '[:find ?b :in $ % :where [?a :p/id "p8"] (anc ?a ?b)] [anc]]
     [:anc-outer-ab '[:find ?a ?b :in $ % :where [?a :p/friend ?b0] [?a :p/score 3] (anc ?a ?b)] [anc]]
     [:anc-outer-b-shared '[:find ?a ?b :in $ % :where [?a :p/score 2] [?a :p/friend ?b] (anc ?a ?b)] [anc]]
     [:anc-coll '[:find ?a ?b :in $ % [?a ...] :where (anc ?a ?b)] [anc [3 9 19 28]]]
     [:anc-free '[:find (count ?a) . :in $ % :where (anc ?a ?b)] [anc]]
     [:anc-const '[:find ?b :in $ % :where (anc 9 ?b)] [anc]]
     [:desc-outer-b '[:find ?a :in $ % :where [?b :p/id "p1"] (desc ?a ?b)] [desc]]
     [:desc-outer-a '[:find ?b :in $ % :where [?a :p/id "p8"] (desc ?a ?b)] [desc]]
     [:swapped '[:find ?a ?b :in $ % :where [?a :p/id "p7"] (sw ?a ?b)] [swapped]]
     [:swapped-rev '[:find ?a ?b :in $ % :where [?b :p/id "p7"] (sw ?b ?a)] [swapped]]
     [:labelled '[:find ?x ?y :in $ % :where [?x :p/id "p2"] (lp :t1 ?x ?y)] [labelled]]
     [:labelled-var '[:find ?t ?y :in $ % :where [?x :p/id "p2"] [?x :p/team ?t] (lp ?t ?x ?y)] [labelled]]
     [:mixed '[:find ?y :in $ % :where [?x :p/id "p9"] (mx ?x ?y)] [mixed]]
     [:guarded '[:find ?y :in $ % :where [?x :p/id "p2"] (gd ?x ?y)] [guarded]]
     [:mutual '[:find ?y :in $ % :where [?x :p/id "p9"] (even ?x ?y)] [mutual]]
     [:mutual-odd '[:find ?x ?y :in $ % :where [?x :p/team :t1] (odd ?x ?y)] [mutual]]
     [:wrapped '[:find ?a ?b :in $ % :where [?a :p/id "p9"] (wrap ?a ?b)] [wrapped]]
     [:wrapped-x '[:find ?x ?b :in $ % :where [?x :p/id "p9"] (wrap ?x ?b)] [wrapped]]
     [:reach-twice '[:find ?b ?c :in $ % :where [?a :p/id "p9"] (reach ?a ?b) (reach ?b ?c)] [reach]]
     [:reach-not '[:find ?a :in $ % :where [?a :p/team :t2] (not (reach ?a 1))] [reach]]
     [:reach-or '[:find ?b :in $ % :where [?a :p/id "p9"] (or (reach ?a ?b) [?a :p/likes ?b])] [reach]]
     ;; temporal shapes
     [:tx-var '[:find ?e ?t ?tx :where [?e :p/tags ?t ?tx] [?e :p/team :t1]] []]
     [:tx-shared '[:find ?e ?a ?t :where [?e :p/age ?a ?tx] [?e :p/team ?t ?tx]] []]
     [:tx-shared-many '[:find ?e ?n ?t :where [?e :p/nums ?n ?tx] [?e :p/tags ?t ?tx]] []]
     [:added-var '[:find ?e ?a ?op :where [?e :p/age ?a ?tx ?op] [?e :p/score 1]] []]
     [:added-shared '[:find ?e ?n ?t ?op :where [?e :p/nums ?n ?tx ?op] [?e :p/tags ?t ?tx2 ?op]] []]
     [:added-false '[:find ?e ?t :where [?e :p/tags ?t ?tx false] [?e :p/score 0]] []]
     [:tags-shared-v '[:find ?e ?v :where [?e :p/tags ?v] [?e :p/tags ?w] [?e :p/nums ?n] [(str "tag" ?n) ?v]] []]
     ;; empties
     [:empty-coll '[:find ?e ?x :in $ [?id ...] :where [?e :p/id ?id] [?x :p/likes ?e]] [[]]]
     [:empty-pred '[:find ?e :where [?e :p/age ?a] [(> ?a 1000)]] []]]))

(defn outcome* [database query args disabled?]
  (try (binding [q/*disable-planner* disabled? q/*query-result-cache?* false]
         (let [r (apply d/q query database args)]
           {:value (if (and (coll? r) (not (map? r)) (not (vector? r))) (set r) r)}))
       (catch Throwable e {:error (.getMessage e)})))

(defn outcome [database query args disabled?]
  (let [f (future (let [t0 (System/nanoTime) r (outcome* database query args disabled?)]
                    (assoc r :ms (quot (- (System/nanoTime) t0) 1000000))))
        r (deref f 20000 ::timeout)]
    (if (= r ::timeout) (do (future-cancel f) {:error "TIMEOUT"}) r)))

(defn norm [o] (if (:error o) {:error true} (-> o (dissoc :ms) (update :value #(if (vector? %) (set %) %)))))

(defn run-world [size kind database shared?]
  (vec (for [[label query args] (cases size)
             :let [rules (when (vector? (first args)) (first args))
                   ref-args (if-let [r (references rules)] (assoc (vec args) 0 r) args)]]
         (do (when-not shared? (vreset! @#'q/plan-cache (lru/lru 100)))
             (let [_ (binding [*out* *err*] (println :run size kind label shared?))
                   p (outcome database query args false) b (outcome database query ref-args true)]
               (when (or (> (:ms p 0) 2000) (> (:ms b 0) 2000) (= "TIMEOUT" (:error p)) (= "TIMEOUT" (:error b)))
                 (println :SLOW size kind label shared? :planned-ms (:ms p (:error p)) :base-ms (:ms b (:error b))))
               {:size size :world kind :label label :shared? shared? :same? (= (norm p) (norm b))
                :planned p :base b})))))

(defn -main [out]
  (let [sizes [40 1500]
        ws (into {} (map (fn [n] [n (worlds n)]) sizes))
        fresh (vec (mapcat (fn [n] (mapcat (fn [[k db]] (run-world n k db false)) (ws n))) sizes))
        _ (vreset! @#'q/plan-cache (lru/lru 100))
        shared-up (vec (mapcat (fn [n] (mapcat (fn [[k db]] (run-world n k db true)) (ws n))) sizes))
        _ (vreset! @#'q/plan-cache (lru/lru 100))
        shared-down (vec (mapcat (fn [n] (mapcat (fn [[k db]] (run-world n k db true)) (ws n))) (reverse sizes)))
        all (concat fresh shared-up shared-down)
        bad (remove :same? all)]
    (spit out (with-out-str (pp/pprint {:total (count all) :mismatches (vec bad)})))
    (println :total (count all) :mismatches (count bad))
    (doseq [m bad] (println :MISMATCH (:size m) (:world m) (:label m) (:shared? m)
                            :planned (let [v (:planned m)] (if (:error v) v (update v :value #(if (coll? %) (count %) %))))
                            :base (let [v (:base m)] (if (:error v) v (update v :value #(if (coll? %) (count %) %))))))))

(-main (first *command-line-args*))
(shutdown-agents)
(System/exit 0)
