(ns seon.datahike-fork-test
  "Seon-owned acceptance checks for behavior maintained in its Datahike fork."
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.walk :as walk]
   [datahike.db :as db]
   [datahike.query :as query]))

(def ^:private planner-database
  (db/empty-db {}))

(def ^:private canonical-vars
  '[?first ?second ?third])

(def ^:private variable-name-generator
  (gen/vector-distinct
   (gen/elements
    '[?a ?b ?c ?agent ?block ?item ?root ?value ?x ?y ?z])
   {:num-elements 3}))

(defn- independent-function-clauses
  [variables]
  (mapv (fn [ordinal variable]
          [(list 'identity ordinal) variable])
        (range 1 4)
        variables))

(defn- create-plan
  [clauses]
  (#'query/create-plan-via-ir planner-database clauses #{} nil nil))

(deftest alpha-renaming-does-not-change-plan-selection
  (let [canonical-plan
        (create-plan (independent-function-clauses canonical-vars))
        check
        (tc/quick-check
         100
         (prop/for-all
          [renamed-vars variable-name-generator]
          (let [renamed-plan
                (create-plan (independent-function-clauses renamed-vars))
                renamed->canonical
                (zipmap renamed-vars canonical-vars)]
            (= canonical-plan
               (walk/postwalk-replace renamed->canonical renamed-plan))))
         :seed 1785366001)]
    (is (:result check) (pr-str check))))
