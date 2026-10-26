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

(defn session-of
  "The input at n: a main db holding a session of n evals by agent b/a1 across two runs (as a
  woken or loaded session's first request reads it cold), each record production-shaped (item),
  and the agent's entity id."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db (d/db-with (ts/test-db)
                      (vec (for [i (range n) :let [[_ v] (item i)]]
                             (assoc v :seon.eval/agent {:seon.agent/key "b/a1"}
                                    :seon.eval/record (assoc (:seon.eval/record v) :run (if (< i (quot n 2)) "demo/f#1" "demo/f#2"))))))]
    [db (:db/id (d/entity db [:seon.agent/key "b/a1"]))]))

(deftest a-sessions-records-cost-one-read-per-record
  ;; design agent-loop section 1: the conversation is the session's, so the first request after
  ;; a wake or a load reads every record of it, cold; a later request reads only what is new
  ;; (:printed, a-turn-prints-its-new-records-flat-in-the-history)
  (cost/proof {:of 'seon.context/records :input 'seon.context-cost-test/session-of
               :n "eval records of the session"
               :work (str "per record: its datom found by the agent's AVET range (one seek), one pull of three attributes "
                          "(its record a data value), and one place in the sort by tx; the messages' query adds nothing "
                          "when none came")
               :growth :linear
               ;; measured 2026-10-25: 798 KB at 10, 5.46 MB at 100, 52.8 MB at 1,000 (about 52 KB a
               ;; record: its pull and its record's data value), one contracted call (records itself)
               :per-n {:bytes 52500}
               :fixed {:bytes 300000 :calls 1}
               :sizes [10 100 1000]}))

(defn related-of-changed
  "seon.context/related's arguments at n wiki sections, each with a vector, and the plan's: its
  answer made anew, as when its vector changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [unit (fn [seed] (let [v (mapv (fn [i] (Math/sin (* (inc seed) (inc i) 0.37))) (range 768)) m (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % m) v)))
        db (ts/test-db)
        db (d/db-with db (vec (for [i (range n)] {:seon.section/address (str "docs/p.md#s" i) :seon.section/heading (str "S" i)
                                                  :seon.section/lead (str "Section " i " says it.")})))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan "Say it."}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range n)] (pdb/eid db :seon.section/address (str "docs/p.md#s" i))))]
    [(d/db-with db (vector/tx-data es (repeat (count es) 1) (mapv unit (range (count es))) (repeat (count es) 10) "m")) te]))

(deftest related-ranks-each-kind-by-one-search
  (cost/proof {:of 'seon.context/related :input 'seon.context-cost-test/related-of-changed
               :n "wiki sections with vectors"
               :work (str "the plan's citations parsed once, and one seon.vector/near a kind (each reads every coarse "
                          "row, its shortlist of 400 reranked: one seek a row), the graph's seeks for what the plan "
                          "cites (none here; each neighbour also one bounded count of the forms naming it, at most "
                          ":seon.config.render/related-rarity datoms), and one rrf of each kind's two rankings: linear in the vectors")
               :growth :linear
               ;; measured 2026-10-25: 145 calls, 3.39 MB at n=100 (below the shortlist); 445 calls
               ;; at 500 and at 1,000, 8.18 MB and 8.92 MB (~1.48 KB a row). The calls are one
               ;; seon.vector/stored seek per row the fine level reranks (the 400 of
               ;; :seon.config.vector/shortlist, each row once: the shortlist's rows are scattered, a
               ;; batch read would scan every f16 row) and ~45 fixed: three near (each its vector-of,
               ;; unpack and pack-dims), the citation parse and the resolver's read; constant past
               ;; the shortlist, as seon.vector-cost-test/a-search-reads-each-row-once measures past
               ;; it. A row is its coarse datom read (~590 B) and its kind's membership set, the :of
               ;; attribute's datoms (~790 B for sections); the second and third coarse scans add
               ;; ~40 B (measured, seon.vector/one-scan-ranks-every-kind).
               :per-n {:bytes 1480.0 :calls 0.0}
               :fixed {:bytes 7440000.0 :calls 445.0}
               :sizes [500 1000 2000]}))

(defn kept-of
  "seon.context/kept's arguments: n parts of a conversation, each a reply's call (every third
  continuing the one before), whole 1,000 tokens and collapsed 20, at the 32k history budget, so the
  cuts jump as the history passes it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :map] :int]]}
  [n] [(vec (for [i (range n)] {:cost [1000 20] :call (if (zero? (mod i 3)) 0 1)})) 32000])

(deftest the-history-cut-is-one-walk-of-the-parts
  ;; task seon.context/history-steps-hold-below-one-step: the cuts only advance, each part passed once
  (cost/proof
    {:of 'seon.context/kept :input 'seon.context-cost-test/kept-of
     :n "parts of the conversation"
     :work "each part's two costs summed once (prefix sums), then one walk holding two cuts that only advance: linear in the parts"
     :growth :linear
     ;; measured 2026-10-25 (n 100 -> 10,000): 231 ticks and 1.57 KB a part, one contracted call
     :per-n {:ticks 240.0 :bytes 1600.0 :calls 0.0}
     :fixed {:ticks 2000.0 :bytes 5000.0 :calls 1.0}
     :sizes [100 1000 10000]}))
