(ns seon.task-cost-test
  "Cost proofs of the task reader's hot lookups."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.task :as task] [seon.cost :as cost]))

(defn tasks-db
  "The input of size n: a main db holding n tasks, and the id of one of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :keyword :string]]}
  [n]
  (let [db (ts/test-db)
        decls (vec (for [i (range n)] {:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan "p"}))]
    [(d/db-with db (task/create-tx db decls)) :seon.task/id (str "demo/t" (quot n 2))]))

(deftest finding-a-task-by-its-id-is-one-index-seek
  (cost/proof
    {:of 'seon.db/eid :input 'seon.task-cost-test/tasks-db
     :n "tasks on main"
     :work (str "one seek in the unique attribute's AVET index: 2-3 B-tree nodes, a binary search of ~6 datom "
                "comparisons (~100 ns each on Jolt) per node, then one datom answered; no query planning, nothing "
                "per task. About 3 us and 1.3 KB at 10k tasks")
     :growth :log
     :per-n {:ns 150 :bytes 50}
     :fixed {:ns 1000 :bytes 600 :calls 1}
     :sizes [100 1000 10000]
     ;; measured 2026-10-20 (was a Datalog query: 99 us, 108 KB): the seek itself allocates
     ;; 23-32 KB in our Datahike / persistent-sorted-set on Jolt
     :overrun {:task "seon.db/avet-seek-allocates" :measured {:ns 17000.0 :bytes 33200.0}}}))
