(ns seon.repl-idioms-test
  "The idioms docs/agent.md#small-answers teaches, on the world: each answers what its question
  asks, and the first few by index cost the few, not the index. Run: bin/test seon.repl-idioms-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [my.test :as t] [datahike.api :as d] [clojure.string :as str]))

(defn vars-db
  "The input of size n: the world with n more vars (app.big/f0 ...), as the one argument vector."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n] [(t/with (t/world) (t/code 'app.big (apply str "(ns app.big)\n" (for [i (range n)] (str "(defn f" i " [] " i ")\n")))))])

(defn first-names
  "The first five var names off the :var/name index: docs/agent.md#small-answers's head."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :symbol]]}
  [db] (->> (d/datoms db :aevt :var/name) (take 5) (mapv :v)))

(deftest the-idioms-answer-their-questions
  ;; docs/agent.md#small-answers: each idiom answers the question it is shown for
  (let [[db] (vars-db 30)
        q '[:find ?n :where [_ :var/name ?n]]]
    (is (= 37 (count (d/datoms db :aevt :var/name))) "how many: the world's 7 vars and 30 more")
    (is (= (count (d/q q db)) (count (d/datoms db :aevt :var/name))) "the index counts what the query collects")
    (is (= 5 (count (set (first-names db)))) "the first few: five names")
    (is (= 5 (count (d/q {:query q :args [db] :limit 5}))) "a query's own limit")
    (is (= [["app.big" 30] ["app.calc" 4]]
           (->> (d/datoms db :aevt :var/name) (map (comp namespace :v)) frequencies (sort-by val >) (take 2)))
        "counts by kind, largest first")
    (is (= [[2 "b proofs"] [4 "d proofs"]]
           (->> (str/split-lines "a\nb proofs\nc\nd proofs") (keep-indexed (fn [i l] (when (str/includes? l "proofs") [(inc i) l])))))
        "matching lines, numbered from 1")
    (is (= [:a :b] (->> {:a "xxxxxx" :b "x" :c 1} (update-vals (comp count pr-str)) (sort-by val >) (take 2) (map key)))
        "a map's keys by the size of their values")))

(deftest the-first-few-by-index-cost-the-few-not-the-index
  ;; docs/agent.md#small-answers: five datoms read lazily off the index cost the same whatever the db
  ;; holds; a datoms that read eagerly, or an idiom that walked the index, grows with it
  (cost/proof
    {:of 'seon.repl-idioms-test/first-names :input 'seon.repl-idioms-test/vars-db
     :n "vars in the db"
     :work "five datoms read off the :var/name index's first leaf, their values put in a vector"
     :growth :constant :fixed {:calls 1 :bytes 30000} :sizes [30 100 300]}))
