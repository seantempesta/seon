(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[datahike.lru :as lru] '[clojure.pprint :as pp])
(def schema {:u/id {:db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
             :u/value {:db/unique :db.unique/value :db/cardinality :db.cardinality/one}
             :u/ref {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
             :u/a {:db/index true} :u/b {} :u/c {}})
(def before (d/db-with (db/empty-db schema {:keep-history? true})
              (vec (for [i (range 1 81)]
                     (cond-> {:db/id i :u/id i :u/value (+ 100 i) :u/a (mod i 7) :u/b (mod i 3)
                              :u/ref (vec (distinct [(inc (mod i 80)) (inc (mod (+ i 3) 80))]))}
                       (even? i) (assoc :u/c "yes"))))))
(def after (d/db-with before [[:db/retract 1 :u/id 1] [:db/add 81 :u/id 1] [:db/add 81 :u/value 181]
                              [:db/add 81 :u/a 3] [:db/add 81 :u/b 1] [:db/add 81 :u/ref 3]
                              [:db/retract 2 :u/ref 3] [:db/add 2 :u/a 8]]))
(def cases
  [[:collection '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[1 2 80 999]]]
   [:empty-input '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[]]]
   [:relation '[:find ?e ?x :in $ [[?id ?a]] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[[1 2] [2 3] [999 4]]]]
   [:lookup '[:find ?x ?a :in $ [?e ...] :where [?x :u/ref ?e] [?x :u/a ?a]] [[[:u/id 2] [:u/id 3]]]]
   [:unique-value '[:find ?e ?x :in $ [?id ...] :where [?e :u/value ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[101 102]]]
   [:literal '[:find ?x ?a :where [?e :u/id 2] [?x :u/ref ?e] [?x :u/a ?a]] []]
   [:absent '[:find ?x ?a :where [?e :u/id 999] [?x :u/ref ?e] [?x :u/a ?a]] []]
   [:predicate '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a] [(< ?a 4)]] [[1 2 3]]]
   [:aggregate '[:find ?id (count ?x) :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[1 2 3]]]
   [:pull '[:find (pull ?x [:u/id :u/ref]) :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a]] [[1 2 3]]]
   [:not '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a] (not [?x :u/c "yes"]) ] [[1 2 3]]]
   [:not-join '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a] (not-join [?x] [?x :u/b 1])] [[1 2 3]]]
   [:or '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/ref ?e] (or [?x :u/a 1] [?x :u/b 2])] [[1 2 3]]]
   [:or-join '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] (or-join [?e ?x] [?x :u/ref ?e] [?e :u/ref ?x]) [?x :u/a ?a]] [[1 2 3]]]
   [:rule '[:find ?e ?x :in $ % [?id ...] :where [?e :u/id ?id] (edge ?e ?x) [?x :u/a ?a]]
    ['[[(edge ?e ?x) [?x :u/ref ?e]]] [1 2 3]]]
   [:recursive '[:find ?e ?x :in $ % [?id ...] :where [?e :u/id ?id] (path ?e ?x) [?x :u/a ?a]]
    ['[[(path ?e ?x) [?e :u/ref ?x]] [(path ?e ?x) [?e :u/ref ?y] [(< ?e ?y)] [(< ?y 8)] (path ?y ?x)]] [1 2]]]
   [:disconnected '[:find ?e ?x :in $ [?id ...] :where [?e :u/id ?id] [?x :u/a 1] [?x :u/b 1]] [[1 2 3]]]
   [:history-versions '[:find ?e ?tx ?added ?a :in $ [?id ...] :where [?e :u/id ?id ?tx ?added] [?e :u/a ?a]] [[1 2 3]]]])
(defn outcome [database query args disabled?]
  (try (binding [q/*disable-planner* disabled? q/*query-result-cache?* false]
         {:value (apply d/q query database args)})
       (catch Throwable e {:error (.getMessage e) :data (ex-data e)})))
(defn permutations [xs] (if (empty? xs) [[]] (mapcat (fn [x] (map #(cons x %) (permutations (remove #{x} xs)))) xs)))
(def all-cases
  (into cases
        (for [[i clauses] (map-indexed vector (permutations '[[?e :u/id ?id] [?x :u/ref ?e] [?x :u/a ?a] [?y :u/a ?a]]))]
          [(keyword (str "permutation-" i)) (into '[:find ?x ?y :in $ [?id ...] :where] clauses) [[1 2 3]]])))
(def results
  (mapv (fn [[kind database]]
          (println :start kind)
          (flush)
          {:kind kind
           :cases (mapv (fn [[label query args]]
                          (println :case kind label)
                          (flush)
                          (vreset! @#'q/plan-cache (lru/lru 100))
                          (let [planned (outcome database query args false)
                                base (outcome database query args true)]
                            (when (not= planned base) (prn :difference kind label planned base))
                            {:label label :planned planned :base base}))
                        all-cases)})
        [[:current after] [:history (d/history after)] [:as-of (d/as-of after (:max-tx before))]]))
(spit (first *command-line-args*) (with-out-str (pp/pprint results)))
(println :done (count all-cases))
(shutdown-agents)
