;; dh-fix2 repros: planner vs base engine for every defect fixed. java -cp FORK-CP clojure.main THIS
(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q])
(defn both [database query & args]
  (into {} (for [[k dis] [[:planner false] [:base true]]]
             [k (try (binding [q/*disable-planner* dis q/*query-result-cache?* false] (set (apply d/q query database args)))
                     (catch Throwable e (str "ERROR " (.getMessage e))))])))
(def pair (d/db-with (db/empty-db {:p/id {:db/unique :db.unique/identity}
                                   :p/friend {:db/valueType :db.type/ref}
                                   :p/pair {:db/valueType :db.type/tuple :db/tupleTypes [:db.type/keyword :db.type/long]}})
                     [{:db/id 1 :p/id "a" :p/pair [:k1 1]} {:db/id 2 :p/id "b" :p/pair [:k2 2] :p/friend 1} {:db/id 3 :p/id "c" :p/friend 2}]))
(def rules '[[(has-pair ?e ?p) [?e :p/pair ?p]] [(same ?x ?y) [(= ?x ?y)]] [(edge ?e ?x) [?x :p/friend ?e]] [(fr ?e ?x) [?e :p/friend ?x]]
             [(idv ?v ?x) [(identity ?v) ?x]]])
(doseq [[k q & args] [[:tuple-literal '[:find ?e :in $ % :where (has-pair ?e [:k1 1])] rules]
                      [:tuple-input '[:find ?e :in $ % ?p :where (has-pair ?e ?p)] rules [:k1 1]]
                      [:tuple-coll '[:find ?e :in $ % [?p ...] :where (has-pair ?e ?p)] rules [[:k1 1]]]
                      [:tuple-coll-top '[:find ?e :in $ [?p ...] :where [?e :p/pair ?p]] [[:k1 1]]]
                      [:lookup-as-value '[:find ?e :in $ % :where [?e :p/id "a"] (same ?e [:p/id "a"])] rules]
                      [:lookup-value-input '[:find ?x :in $ % ?v :where (idv ?v ?x)] rules [:p/id "b"]]
                      [:lookup-value-coll '[:find ?x :in $ % [?v ...] :where (idv ?v ?x)] rules [[:p/id "b"]]]
                      [:lookup-entity-lit '[:find ?x :in $ % :where (fr [:p/id "c"] ?x)] rules]
                      [:lookup-entity-input '[:find ?x :in $ % ?e :where (fr ?e ?x)] rules [:p/id "c"]]
                      [:lookup-entity-coll '[:find ?x :in $ % [?e ...] :where (fr ?e ?x)] rules [[:p/id "c"] [:p/id "b"]]]
                      [:lookup-refv-lit '[:find ?x :in $ % :where (edge [:p/id "a"] ?x)] rules]
                      [:lookup-refv-input '[:find ?x :in $ % ?e :where (edge ?e ?x)] rules [:p/id "a"]]
                      [:lookup-refv-coll '[:find ?e ?x :in $ % [?e ...] :where (edge ?e ?x)] rules [[:p/id "a"]]]
                      [:missing-data '[:find ?x :in $ % ?v :where (idv ?v ?x)] rules [:not/attr 2]]]]
  (prn k (apply both pair q args)))
(doseq [[k q & args] [[:fn-bound-e '[:find ?p :where [(identity [:p/id "a"]) ?x] [?x :p/pair ?p]]]
                      [:fn-bound-refv '[:find ?y :where [(identity [:p/id "a"]) ?x] [?y :p/friend ?x]]]
                      [:mixed-rule '[:find ?p :in $ % :where (mx [:p/id "a"] ?p)] '[[(mx ?x ?p) [?x :p/pair ?p] [(vector? ?x)]]]]
                      [:mixed-rule-input '[:find ?p :in $ % ?v :where (mx ?v ?p)] '[[(mx ?x ?p) [?x :p/pair ?p] [(vector? ?x)]]] [:p/id "a"]]
                      [:mixed-top-coll '[:find ?p :in $ [?x ...] :where [?x :p/pair ?p] [(vector? ?x)]] [[:p/id "a"]]]
                      [:entity-top-coll-multi '[:find ?p :in $ $2 [?x ...] :where [?x :p/pair ?p]] pair [[:p/id "a"]]]
                      [:value-top-coll-multi '[:find ?e :in $ $2 [?p ...] :where [?e :p/pair ?p]] pair [[:k1 1]]]]]
  (prn k (apply both pair q args)))
(def before (d/db-with (db/empty-db {:i/a {:db/cardinality :db.cardinality/many} :i/b {:db/cardinality :db.cardinality/many} :i/c {:db/cardinality :db.cardinality/many} :i/x {}} {:keep-history? true})
                       [{:db/id 1 :i/a [1 2] :i/b [1 4 5] :i/c [2 5 9] :i/x 0}]))
(def after (d/db-with before [[:db/retract 1 :i/b 1] [:db/add 1 :i/a 4] [:db/retract 1 :i/c 5] [:db/add 1 :i/x 3]]))
(def after (d/db-with before [[:db/retract 1 :i/b 1] [:db/add 1 :i/a 4] [:db/retract 1 :i/c 5] [:db/add 1 :i/x 3]]))
(doseq [q '[[:find ?v ?w ?added :where [?e :i/a ?v ?tx ?added] [?e :i/b ?w ?tx2 ?added]]
            [:find ?v ?w ?added :where [?e :i/a ?v ?tx] [?e :i/b ?w ?tx2 ?added] [?e :i/c ?w ?tx3 ?added]]
            [:find ?x ?v ?a1 :where [?e :i/x ?x ?t0 ?a0] [?e :i/b ?v ?t1 ?a1] [?e :i/c ?v ?t2 ?a2]]
            [:find ?x ?v :where [?e :i/x ?x] [?e :i/b ?v] [?e :i/c ?v]]
            [:find ?x ?t :where [?e :i/x ?x] [?e :i/b ?v ?t] [?e :i/c ?w ?t]]
            [:find ?v ?added :where [?e :i/a ?v ?tx ?added] [?e :i/b ?v ?tx2 ?added]]]]
  (prn q (let [r (both (d/history after) q)] [(count (:planner r)) (count (:base r)) (= (:planner r) (:base r))])))
(doseq [[w dbv] [[:current after] [:history (d/history after)] [:as-of (d/as-of after (:max-tx before))]]
        q '[[:find ?v ?added :where [?e :i/a ?v ?tx ?added] (not [?e :i/b 1 ?tx2 ?added])]
            [:find ?v :where [?e :i/a ?v] (not [?e :i/c ?v])]
            [:find ?v :where [?e :i/x ?x] [?e :i/a ?v] (not [?e :i/c ?v])]]]
  (prn w q (let [r (both dbv q)] [(:planner r) (:base r) (= (:planner r) (:base r))])))
(def tagdb2 (d/db-with (db/empty-db {:t/tags {:db/cardinality :db.cardinality/many} :t/n {}} {:keep-history? true})
                      [{:db/id 1 :t/tags ["tag1" "x"] :t/n 0} {:db/id 2 :t/tags ["x"] :t/n 7}]))
(def later (d/db-with tagdb2 [[:db/retract 1 :t/tags "x"]]))
(doseq [[k dbv q] [[:join-current later '[:find ?e ?t :where [?e :t/tags ?t ?tx false] [?e :t/n 0]]]
                   [:join-current-rev later '[:find ?e ?t :where [?e :t/n 0] [?e :t/tags ?t ?tx false]]]
                   [:join-current-true later '[:find ?e ?t :where [?e :t/tags ?t ?tx true] [?e :t/n 0]]]
                   [:join-current-not later '[:find ?e :where [?e :t/n ?n] (not [?e :t/tags "x" ?tx false])]]
                   [:join-history (d/history later) '[:find ?e ?t :where [?e :t/tags ?t ?tx false] [?e :t/n 0]]]
                   [:join-asof (d/as-of later (:max-tx tagdb2)) '[:find ?e ?t :where [?e :t/tags ?t ?tx false] [?e :t/n 0]]]
                   [:single-current later '[:find ?e ?t :where [?e :t/tags ?t ?tx false]]]]]
  (prn k (both dbv q)))
(def chain2 (d/db-with (db/empty-db {:p/friend {:db/valueType :db.type/ref} :p/n {}} {:keep-history? true})
                      [{:db/id 1 :p/n 1} {:db/id 2 :p/friend 1 :p/n 2} {:db/id 3 :p/friend 2} {:db/id 4 :p/friend 4 :p/n 4}]))
(def rr '[[(reach ?a ?b) [?a :p/friend ?b]] [(reach ?a ?b) [?a :p/friend ?x] (reach ?x ?b)]])
(doseq [[k q & args] [[:pattern-self '[:find ?a :where [?a :p/friend ?a]]]
                      [:pattern-self-group '[:find ?a ?n :where [?a :p/friend ?a] [?a :p/n ?n]]]
                      [:pattern-self-group2 '[:find ?a ?n :where [?a :p/n ?n] [?a :p/friend ?a]]]
                      [:pattern-self-v-n '[:find ?a :where [?a :p/n ?a]]]
                      [:nonrec-self '[:find ?a :in $ % :where (edge ?a ?a)] '[[(edge ?a ?b) [?a :p/friend ?b]]]]
                      [:nonrec-self-bound '[:find ?a :in $ % :where [?a :p/friend _] (edge ?a ?a)] '[[(edge ?a ?b) [?a :p/friend ?b]]]]
                      [:reach-self '[:find ?a :in $ % :where (reach ?a ?a)] rr]]]
  (prn k (apply both chain2 q args)))
(def tagdb (d/db-with (db/empty-db {:t/tags {:db/cardinality :db.cardinality/many} :t/n {}} {:keep-history? true})
                      [{:db/id 1 :t/tags ["tag1" "x"] :t/n 1} {:db/id 2 :t/tags ["x"] :t/n 7}]))
(prn :fn-binds-bound-var (both tagdb '[:find ?e ?v :where [?e :t/tags ?v] [?e :t/n ?n] [(str "tag" ?n) ?v]]))
(prn :fn-binds-bound-coll (both tagdb '[:find ?e ?v :where [?e :t/tags ?v] [?e :t/n ?n] [(vector (str "tag" ?n) "x") [?v ...]]]))
(prn :fn-binds-other-rel (both tagdb '[:find ?e ?v :where [?e :t/n ?n] [?x :t/tags ?v] [(str "tag" ?n) ?v]]))
(prn :fn-binds-fresh (both tagdb '[:find ?e ?v :where [?e :t/n ?n] [(str "tag" ?n) ?v]]))
(System/exit 0)
