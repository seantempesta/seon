(ns seon.context-cost-test
  "The conversation's cost proofs: a turn prints only its new records (seon.context/printed-run),
  flat in the run's history (root's ruling 2026-10-25: a record's printed message is a function
  of it and the records before it, which never change, so it is printed once and reused by its
  id). Run: bin/test seon.context-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.context]
            [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb] [seon.task :as task] [seon.vector :as vector]))

(defn- item
  "Eval record i of a run as the conversation reads it ([ref v k tx]): a model's call whose value
  is a production-shaped row set (a few maps of strings and numbers), each unlike the others."
  {:malli/schema [:=> [:cat nat-int?] :any]}
  [i]
  (let [id (str "e" i)]
    [[:eval id]
     {:seon.eval/id id :seon.eval/by "a1"
      :seon.eval/record {:code (str "(rows " i ")") :call-id (str "c-" i) :call 0 :prose (str "Read the rows of part " i ".")
                         :response {:handle (symbol "result" id) :value (vec (for [j (range 6)] {:part i :row j :name (str "row-" i "-" j)}))
                                    :ms 0.5 :ticks 1000 :bytes 4096}
                         :line (str "[{:part " i " …}]")}}
     :seon.db/eval-fact (inc i)]))

(defn held-of
  "The input at n: the run's next record, and the cache of its n records before it, printed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n] [[(item n)] (#'seon.context/printed-run (mapv item (range n)) nil)])

(deftest a-turn-prints-its-new-records-flat-in-the-history
  (cost/proof {:of 'seon.context/printed-run :input 'seon.context-cost-test/held-of
               :n "records of the run printed before (the cache)"
               :work (str "the new record printed whole and collapsed, once, by the one printer, its fold looked up "
                          "among the values the earlier records showed (a hash map by value and type); nothing of the "
                          "earlier records is printed again")
               :growth :constant
               ;; measured 2026-10-25: 16 calls and 329 KB at 10 held records (the new record's two prints)
               :fixed {:calls 16 :bytes 340000}
               :sizes [10 100 1000]}))

(defn related-of
  "seon.context/related-at's arguments at n wiki sections, each with a vector, and the plan's
  answer made once: the next turn's call, its plan unchanged."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int :int :map]]}
  [n]
  (let [unit (fn [seed] (let [v (mapv (fn [i] (Math/sin (* (inc seed) (inc i) 0.37))) (range 768)) m (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % m) v)))
        db (ts/test-db)
        db (d/db-with db (vec (for [i (range n)] {:seon.section/address (str "docs/p.md#s" i) :seon.section/heading (str "S" i)
                                                  :seon.section/lead (str "Section " i " says it.")})))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan "Say it."}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range n)] (pdb/eid db :seon.section/address (str "docs/p.md#s" i))))
        db (d/db-with db (vector/tx-data es (repeat (count es) 1) (mapv unit (range (count es))) (repeat (count es) 10) "m"))]
    [db te 1 (seon.context/related-at db te 1 nil)]))

(deftest related-sections-cost-nothing-when-the-plan-is-unchanged
  (cost/proof {:of 'seon.context/related-at :input 'seon.context-cost-test/related-of
               :n "wiki sections with vectors"
               :work "the count read through the resolver (one pull) and the key compared with the held one's: nothing ranked, nothing read of the sections"
               :growth :constant
               :fixed {:calls 4 :bytes 20000}
               :sizes [10 100 1000]}))

(defn related-of-changed
  "seon.context/related's arguments at n wiki sections with vectors: the plan's answer made anew,
  as when its vector changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n] (let [[db te] (related-of n)] [db te]))

(deftest related-ranks-each-kind-by-one-search
  (cost/proof {:of 'seon.context/related :input 'seon.context-cost-test/related-of-changed
               :n "wiki sections with vectors"
               :work (str "the plan's citations parsed once, and one seon.vector/near a kind (every coarse row read "
                          "once, its shortlist reranked), the graph's seeks for what the plan cites (none here), "
                          "and one rrf of each kind's two rankings: linear in the vectors")
               :growth :linear
               ;; measured at n=100 (prove, 2026-10-25): 145 calls, 3.39 MB. The calls are ~100
               ;; seon.vector/stored, one EAVT seek per row the fine level reranks (every row below
               ;; the 400-row :seon.config.vector/shortlist), and ~45 fixed: three near (each its
               ;; vector-of, unpack and pack-dims), the citation parse and the resolver's read. One
               ;; seek a reranked row is the work (the shortlist's rows are scattered: a batch read
               ;; would scan every f16 row), so past the shortlist the calls are constant, ~445, as
               ;; seon.vector-cost-test/a-search-reads-each-row-once measures past it. Each near reads
               ;; every coarse row before :of filters them (two of the three here keep none): per-n
               ;; bytes three times near's own 700. Estimates to be set from the run at these sizes.
               :per-n {:bytes 2100.0 :calls 0.0}
               :fixed {:bytes 6500000.0 :calls 460.0}
               :sizes [500 1000 2000]}))
