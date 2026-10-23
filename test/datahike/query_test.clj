(ns datahike.query-test
  "Datahike's query, as Seon's agents call it: what it answers and what it refuses."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts])
  (:import [clojure.lang ExceptionInfo]))

(deftest a-nested-call-in-a-predicate-is-refused
  ;; task datahike.query/a-nested-call-in-a-predicate-is-refused: Datalog evaluates no call
  ;; inside a predicate or function clause's arguments (Datomic refuses the query); the
  ;; parser reads (str ?n) as a constant list, so the clause compared against it and the
  ;; query answered no rows, which a model read as "no test namespaces"
  (let [db (d/db-with (ts/test-db) [{:ns/name 'proof.one-test} {:ns/name 'proof.two-test} {:ns/name 'proof.three}])
        nested '[:find ?n :where [?e :ns/name ?n] [(clojure.string/ends-with? (str ?n) "-test")]]
        bound '[:find ?n :where [?e :ns/name ?n] [(str ?n) ?s] [(clojure.string/ends-with? ?s "-test")]
                [(clojure.string/starts-with? ?s "proof.")]]
        refused (fn [q & args] (ex-data (is (thrown? ExceptionInfo (apply d/q q db args)))))]
    (is (= #{['proof.one-test] ['proof.two-test]} (d/q bound db)) "bound first, the call answers")
    (let [r (refused nested)]
      (is (= :query/nested-call (:error r)))
      (is (= '(str ?n) (:argument r))))
    (is (= :query/nested-call
           (:error (refused '[:find ?s :where [?e :ns/name ?n] [(str (name ?n)) ?s]])))
        "a function clause too")
    (is (= :query/nested-call
           (:error (refused '[:find ?n :in $ % :where [?e :ns/name ?n] (tested ?n)]
                            '[[(tested ?n) [(clojure.string/ends-with? (str ?n) "-test")]]])))
        "and a rule's body")
    (is (= :query/nested-call
           (:error (refused '[:find ?n :where [?e :ns/name ?n] (not [?e :ns/name ?n] [(clojure.string/ends-with? (str ?n) "three")])])))
        "and inside not")
    (is (= :query/nested-call
           (:error (refused '[:find ?n :where [?e :ns/name ?n]
                              (or (and [?e :ns/name ?n] [(clojure.string/ends-with? (str ?n) "-test")]) [?e :ns/name ?n])])))
        "and inside or")
    (is (= :query/nested-call
           (:error (refused '[:find ?n :where [?e :ns/name ?n]
                              (not-join [?n] [?x :ns/name ?n] [(clojure.string/ends-with? (str ?n) "three")])])))
        "and inside not-join")
    (is (= :query/nested-call
           (:error (refused '[:find ?n :where [?e :ns/name ?n] [(contains? '#{proof.three} ?n)]])))
        "a quoted literal inside a quoted query is the list (quote ...), refused")
    (is (= #{['proof.three]}
           (d/q '[:find ?n :where [?e :ns/name ?n] [(contains? #{proof.three} ?n)]] db))
        "the literal itself is a constant")
    (is (= #{['proof.three]}
           (d/q '[:find ?n :where (not [(clojure.string/ends-with? ?s "-test")]) [?e :ns/name ?n] [(str ?n) ?s]
                  [(clojure.string/starts-with? ?s "proof.")]] db))
        "a where opening with not, its calls flat, answers")
    (is (contains? (d/q '[:find ?n :where [(q '[:find ?m :where [_ :ns/name ?m]] $) [[?n]]]] db) ['proof.three])
        "a quoted subquery is a constant, not a nested call")))

(deftest a-predicate-that-throws-is-an-error
  ;; task datahike.query/a-predicate-that-throws-is-an-error: a predicate's ClassCastException or
  ;; IllegalArgumentException was read as false (filter-by-pred's safe-pred, the planner's
  ;; post-filter-preds), so a call given a value of the wrong type dropped every row without a
  ;; word and the query read as a true empty answer
  (let [db (d/db-with (ts/test-db) [{:ns/name 'proof.one-test} {:ns/name 'proof.two}])
        e (is (thrown? ExceptionInfo
                       (d/q '[:find ?n :where [_ :ns/name ?n] [(pos? ?n)]] db)))]
    (is (= :query/call-threw (:error (ex-data e))) "a symbol given to a number's predicate is an error")
    (is (re-find #"pos\?" (pr-str (:clause (ex-data e)))) "naming the clause")
    (is (some? (ex-cause e)) "with the call's own throw as its cause")
    (is (= #{['proof.one-test]}
           (d/q '[:find ?n :where [_ :ns/name ?n] [(str ?n) ?s] [(clojure.string/ends-with? ?s "-test")]
                  [(clojure.string/starts-with? ?s "proof.")]] db))
        "given the string, it answers")
    (testing "the built-in comparisons order values of different kinds, as the indexes do, and never throw"
      (do
        (is (= (d/q '[:find ?n :where [_ :ns/name ?n] [(str ?n) ?s] [(clojure.string/starts-with? ?s "proof.")] [(> ?n 5)]] db)
               (d/q '[:find ?n :where [_ :ns/name ?n] [(str ?n) ?s] [(clojure.string/starts-with? ?s "proof.")] [(> ?n 5)]] db))
            "a symbol against a number: an answer, the same each time")
        (is (= #{[3]} (d/q '[:find ?x :in [?x ...] :where [(< ?x 4)] [(> ?x 2)]] [1 2 3 4 5])) "numbers as before")))))
