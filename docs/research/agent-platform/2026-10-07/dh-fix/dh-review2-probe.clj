; Independent bounded query probes, not a test-runner invocation. Run from a fork archive.
(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[datahike.lru :as lru])
(def before (d/db-with (db/empty-db {:p/id {:db/unique :db.unique/identity} :p/ref {:db/valueType :db.type/ref} :p/a {:db/cardinality :db.cardinality/many :db/index true} :p/b {:db/cardinality :db.cardinality/many} :p/c {:db/cardinality :db.cardinality/many}} {:keep-history? true}) [{:db/id 1 :p/id "a" :p/a [1 2] :p/b [2 3] :p/c [1 3] :p/ref 1} {:db/id 2 :p/id "b" :p/a [2 3] :p/b [1 3] :p/c [2 3] :p/ref 1}]))
(def after (d/db-with before [[:db/retract 1 :p/b 2] [:db/add 1 :p/b 1] [:db/retract 2 :p/c 2]]))
(defn outcome [database query args base?] (try (binding [q/*query-result-cache?* false q/*disable-planner* base?] {:value (apply d/q query database args)}) (catch Throwable e {:error (ex-message e)})))
(defn permutations [xs] (if (empty? xs) [[]] (mapcat (fn [i] (map #(cons (nth xs i) %) (permutations (vec (concat (subvec xs 0 i) (subvec xs (inc i))))))) (range (count xs)))))
(def shapes ['[[?e :p/a ?v] [?e :p/b ?v] [?e :p/c ?v]]
             '[[?e :p/a ?v ?t ?a] [?e :p/b ?w ?t ?a] [?e :p/c ?x ?t ?a]]
             '[[?e :p/a ?v ?t ?a] [?e :p/b ?w ?u ?b] [?e :p/c ?w ?u ?b]]
             '[[?e :p/a ?v] [?e :p/b ?w] (not [?e :p/c ?w])]
             '[[?e :p/a ?v] [?e :p/b ?v] (not [?e :p/c ?v])]
             '[[?e :p/a ?v] [?e :p/b ?e] (not [?e :p/c ?e])]
             '[[?e :p/a ?v ?t ?a] [?e :p/b ?w ?u ?b] (not [?e :p/c ?w ?u ?b])]
             '[[?e :p/a ?v] [?e :p/b ?w ?t false] [?e :p/c ?x]]])
(doseq [[world database] [[:current after] [:history (d/history after)] [:as-of (d/as-of after (:max-tx before))]] clauses (mapcat permutations shapes)]
 (let [query {:find '[?e ?v] :where (vec clauses)} p (outcome database query [] false) b (outcome database query [] true)] (when (not= p b) (prn :difference world query p b))))
(def rules '[[(mix ?e ?v) [?e :p/id ?v] [(vector? ?e)]] [(val ?e ?v) [(identity ?e) ?v]] [(edge ?e ?v) [?e :p/ref ?v]]])
(doseq [[label query args]
 [[:mixed-literal '[:find ?v :in $ % :where (mix [:p/id "a"] ?v)] [rules]]
  [:mixed-input '[:find ?v :in $ % ?e :where (mix ?e ?v)] [rules [:p/id "a"]]]
  [:nested-data '[:find ?v :in $ % ?e :where (or-join [?v] (and [?e :p/id ?id] [(identity ?id) ?v]) (and [?x :p/id ?v])) (val ?e ?data)] [rules [:p/id "a"]]]
  [:rule-call-repeated-source '[:find ?e :in $ % :where ($ edge ?e ?e)] [rules]]
  [:fn-repeated '[:find ?x :where [(identity [1 2]) [?x ?x]]] []]
  [:fn-false-bound '[:find ?x :in ?x :where [(identity true) ?x]] [false]]]]
 (prn label (outcome after query args false) (outcome after query args true)))


(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q])
(def database (d/db-with (db/empty-db {:p/id {:db/unique :db.unique/identity} :p/ref {:db/valueType :db.type/ref} :p/a {} :p/b {} :p/c {}}) [{:db/id 1 :p/id "a" :p/ref 1 :p/a 1 :p/b 2 :p/c 3}]))
(doseq [query ['[:find ?e ?v :where [?e :p/a ?v] (not [?e :p/c ?w]) [?e :p/b ?w]]
               '[:find ?v :where [[:p/id "a"] :p/a ?v ?v]]
               '[:find ?v :where [1 :p/a ?v ?v]]
               '[:find ?v :where [$ [:p/id "a"] :p/a ?v ?v]]]]
 (prn query (mapv (fn [b] (try (binding [q/*disable-planner* b q/*query-result-cache?* false] (d/q query database)) (catch Throwable e {:error (ex-message e)}))) [false true])))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(def query '[:find ?v :where [[:p/id "a"] :p/a ?v ?v]])
(defn measure [base?]
 (let [tid (.threadId (Thread/currentThread)) a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) t (System/nanoTime)
       result (binding [q/*disable-planner* base? q/*query-result-cache?* false] (d/q query database))]
  {:ms (/ (- (System/nanoTime) t) 1e6) :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) a) :value result}))
(doseq [base? [false true]]
 (dotimes [_ 200] (measure base?))
 (let [samples (repeatedly 21 #(measure base?))]
  (prn :cost base? :ms (nth (sort (map :ms samples)) 10) :bytes (nth (sort (map :bytes samples)) 10))))
(shutdown-agents)
(System/exit 0)
