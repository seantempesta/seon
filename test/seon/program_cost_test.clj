(ns seon.program-cost-test
  "Recording a form costs the form, never the program (next-review #6; owner 2026-10-21:
  per form, through the run-ahead writer, no waiting): the facts of a redefinition on a
  branch that already holds n forms are a few index seeks, whatever n. Loading orders the
  forms in one pass over them and their references."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program]))

(defn- acct
  {:malli/schema [:=> [:cat :int :string] :map]}
  [i body]
  {:id (str "app.pc/f" i "-" body) :src (str "(defn f" i " [x] " body ")") :ns 'app.pc :ns-form? false
   :defs [{:var (symbol "app.pc" (str "f" i)) :kind :fn}] :declares #{} :methods [] :impls []
   :refs (if (pos? i) #{(symbol "app.pc" (str "f" (dec i)))} #{}) :requires #{} :banned #{} :inline-defs #{}})

(defn branch-of
  "record-tx's arguments: a branch db holding n forms (app.pc/f0..fn-1, each calling the one
  before), and the account of redefining the middle one."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map]]}
  [n]
  (let [db (ts/test-db)
        db (d/db-with db (vec (mapcat #(program/account-tx db (acct % "x") %) (range n))))]
    [db (acct (quot n 2) "(inc x)")]))

(deftest recording-a-form-is-flat-in-the-program
  (cost/proof
    {:of 'seon.program/record-tx :input 'seon.program-cost-test/branch-of
     :n "forms the branch's db already holds"
     :work (str "the form only: one AVET seek for the form's id, one for each definition it makes, the "
                "entity read of each one's current form and of that form's :form/defines (to see it is "
                "superseded), and one map per fact; nothing scans the program. Six seeks or entity reads, "
                "each one log n index seek (seon.task-cost-test: ~3 us and 1.3 KB)")
     :growth :log
     :per-n {:ns 900 :bytes 300}
     ;; contracted calls: record-tx, account-tx, eid x2, form-of, the tempid makers x6, method/impl
     ;; keys none here: 12, whatever n
     :fixed {:ns 18000 :bytes 8000 :calls 12}
     :sizes [100 1000 10000]
     ;; measured 2026-10-21 (source mode, load 5): 105 -> 114 us and 121 -> 138 KB from 100 to
     ;; 10k forms, on the whole process's count. Re-measured 2026-10-23 on the proof's own thread
     ;; (18b0774: per-thread bytes, thread CPU time; release runtime, Chez 80e4b9f, load 17, as
     ;; bin/prove runs): 161 -> 183 KB, the same in every run (six AVET seeks, ~21 KB each as
     ;; seon.task-cost-test's one seek, and the entity reads), and 192 -> 213 us, which machine
     ;; load moves ~1.3x (158 us at load 10). The record a ratchet compares with is this measure's
     :overrun {:task "seon.db/avet-seek-allocates" :measured {:ns 213400.0 :bytes 183100.0}}}))

(defn program-of
  "load-order's argument: the n live forms of app.pc (the test store's example program is
  beside them)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :map]]]}
  [n]
  (let [[db] (branch-of n)] [(filterv #(= 'app.pc (:ns %)) (program/live-forms db))]))

(deftest load-order-is-one-pass-over-the-forms
  (cost/proof
    {:of 'seon.program/load-order :input 'seon.program-cost-test/program-of
     :n "live forms"
     :work (str "one pass to index each form by id and each var by its earliest interning form, one per "
                "namespace for its dependencies, and a depth-first visit of each form and reference edge, "
                "the forms sorted by order once (n log n compares)")
     :growth :n-log-n
     :ops {:map/assoc 3 :map/get 4 :sort/compare 1 :vec/conj 2 :set/conj! 2}
     ;; itself, and the depth-first sort once for the namespaces and once per namespace (one here)
     :fixed {:calls 3}
     :sizes [100 1000 10000]}))
