(ns repro
  (:require [datahike.api :as d] [datahike.db :as db] [datahike.query :as q] [datahike.lru :as lru]))

(defn outcome [database query args disabled?]
  (vreset! @#'q/plan-cache (lru/lru 100))
  (try (binding [q/*disable-planner* disabled? q/*query-result-cache?* false]
         {:value (set (apply d/q query database args))})
       (catch Throwable e {:error (.getMessage e)})))

(defn both [database query & args]
  (let [p (outcome database query args false) b (outcome database query args true)]
    {:same? (= p b) :planned p :base b}))

(def chain
  (let [base (d/db-with (db/empty-db {:p/id {:db/unique :db.unique/identity}
                                      :p/friend {:db/valueType :db.type/ref}}
                                     {:keep-history? true})
                        (vec (for [i (range 20)]
                               (cond-> {:db/id (- (inc i)) :p/id (str "p" i)}
                                 (pos? i) (assoc :p/friend (- i))))))]
    (d/db-with base [[:db/retractEntity [:p/id "p4"]]])))

(def reach '[[(reach ?a ?b) [?a :p/friend ?b]] [(reach ?a ?b) [?a :p/friend ?x] (reach ?x ?b)]])

(def buf-db
  (d/db-with (db/empty-db {:item/a {:db/cardinality :db.cardinality/many}
                           :item/b {:db/cardinality :db.cardinality/many}}
                          {:keep-history? true})
             [{:db/id 1 :item/a [1 2] :item/b [1 4 5]}]))
