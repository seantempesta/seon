(ns seon.vector-cost-test
  "Cost proofs of semantic search's parts that grow with the store: a search reads every coarse
  row once and only its shortlist's whole vectors; what is due reads each text's and vector's
  facts once."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [datahike.api :as d] [seon.test-store :as ts] [seon.vector :as vector]))

(defn- unit-vector
  "A unit vector of 768 dims, seeded, its weight falling off along the dims as a Matryoshka
  model's does."
  {:malli/schema [:=> [:cat :int] [:vector number?]]}
  [seed]
  (let [v (mapv (fn [i] (* (Math/exp (/ (- i) 96.0)) (Math/sin (* (inc seed) (inc i) 0.37)))) (range 768))
        n (Math/sqrt (reduce + (map #(* % %) v)))]
    (mapv #(/ % n) v)))

(defn- store-of
  "The test store's seed with n forms, each with its vector."
  {:malli/schema [:=> [:cat pos-int?] :seon.host/db]}
  [n]
  (d/db-with (ts/test-db) (vec (for [i (range n)] (assoc (vector/pack (unit-vector i)) :db/id (str "f" i) :form/id (str "vf" i)
                                                       :form/src (str "(defn vf" i " [])") :seon.vector/model "m" :seon.vector/text-t 0 :seon.vector/tokens 8)))))

(defn near-of
  "seon.vector/near's arguments: n forms with vectors, a query vector, k 10."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:vector number?] :int]]}
  [n] [(store-of n) (unit-vector 100003) 10])

(deftest a-search-reads-each-row-once
  (cost/proof
    {:of 'seon.vector/near :input 'seon.vector-cost-test/near-of
     :n "vectors in the store, past the shortlist (below it every vector is the shortlist)"
     :work (str "every coarse row read from its datom and copied once into the kernel's buffer and one C scan "
                "of them (linear in n), then the shortlist's whole vectors read and ranked: at most "
                ":seon.config.vector/shortlist (400) of them, a constant")
     :growth :linear
     ;; measured 2026-10-23: 1.32M ticks / 6.2 MB at 1,000; below the shortlist (all rows reranked)
     ;; 0.33M / 1.6 MB at 100 and 0.94M / 4.5 MB at 300: ~133 ticks and 383 B a coarse row, ~2,900
     ;; ticks and 14 KB a reranked row, so 400 reranked rows are the fixed part
     :per-n {:ticks 160.0 :bytes 450.0}
     :fixed {:ticks 1250000.0 :bytes 6000000.0}
     :sizes [500 1000 2000]}))

(defn due-of
  "seon.vector/due's arguments: n forms, every one with its vector, of another model than asked,
  so each is read and each is due."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string]]}
  [n] [(store-of n) "m2"])

(deftest what-is-due-reads-each-fact-once
  (cost/proof
    {:of 'seon.vector/due :input 'seon.vector-cost-test/due-of
     :n "texts in the store"
     :work (str "the vectors' text transactions and models, one index scan each into a map, then each text's "
                "datom once with two lookups, and one sort of what is due (n log n, the scans dominate)")
     :growth :linear
     ;; measured 2026-10-23: 0.25M ticks / 1.5 MB at 100, 0.43M / 2.6 MB at 300, 0.98M / 6.1 MB at
     ;; 1,000 (the seed's own texts are the fixed part): ~790 ticks and 5 KB a text
     :per-n {:ticks 900.0 :bytes 5500.0}
     :fixed {:ticks 150000.0 :bytes 900000.0}
     :sizes [100 300 1000]}))
