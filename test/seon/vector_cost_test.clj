(ns seon.vector-cost-test
  "Cost proofs of semantic search's parts that grow with the store: a search reads every coarse
  row once and only its shortlist's whole vectors; what is due reads each text's and vector's
  facts once."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [datahike.api :as d] [seon.test-store :as ts] [seon.vector :as vector] [seon.context :as context]))

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
     ;; measured 2026-10-23 in the full run: 1.24M ticks / 5.92 MB at 500, 1.32M / 6.18 MB at 1,000,
     ;; 1.50M / 6.85 MB at 2,000: ~173 ticks and 620 B a coarse row; below the shortlist (all rows
     ;; reranked) 0.33M / 1.6 MB at 100, ~2,900 ticks and 14 KB a reranked row, so the 400 reranked
     ;; rows are most of the fixed part (set below it: a fixed part near the smallest size's whole
     ;; cost reads as steeper growth)
     :per-n {:ticks 200.0 :bytes 700.0}
     :fixed {:ticks 1050000.0 :bytes 5200000.0}
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

(defn similar-of
  "seon.context/similar's arguments: n forms with vectors and none a function, so it ranks every
  one and walks every rank (its worst case); the query one of them, nothing named."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int [:set :qualified-symbol]]]}
  [n] (let [db (store-of n)
            ;; each form defines a test, so every rank's vars are read and none is a function
            db (d/db-with db (vec (for [i (range n)] {:var/name (symbol "vc-test" (str "t" i)) :var/kind :test :var/form [:form/id (str "vf" i)]})))
            q (:e (first (d/datoms db :avet :form/id "vf0")))]
        ;; the query asks for five (its own setting, as a task's :seon.task/config is: off by default)
        [(d/db-with db [{:db/id q :seon.config.render/similar 5}]) q #{}]))

(deftest the-story-ranks-every-form-once
  ;; the owner's ruling (2026-10-24, DECISIONS "Embeddings join the context"): :similar is an
  ;; exact search, measured
  (cost/proof
    {:of 'seon.context/similar :input 'seon.vector-cost-test/similar-of
     :n "forms with vectors"
     :work (str "near with every vector its shortlist at every dim (each coarse row and each whole vector "
                "read once, one C scan and one C rerank of all of them, one sort), then each rank's form's "
                "vars read until :seon.config.render/similar functions are found (here none: every rank)")
     :growth :n-log-n
     :per-n {:ticks 4000.0 :bytes 20000.0}
     :fixed {:ticks 300000.0 :bytes 1500000.0}
     :sizes [1000 2000 4000]}))
