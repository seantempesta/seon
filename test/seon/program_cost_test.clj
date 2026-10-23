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

(defn fan
  "closure's arguments of size n: one root whose neighbors are n leaves (the frontier n wide)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int] :seon.host/fn]]}
  [n]
  (let [leaves (vec (range 1 (inc n)))]
    [[0] (fn [x] (if (zero? x) leaves []))]))

(deftest a-closure-is-one-visit-per-node-and-edge
  (cost/proof
    {:of 'seon.program/closure :input 'seon.program-cost-test/fan
     :n "nodes reached (a root and its n leaves: the widest frontier)"
     :work (str "each node taken from the queue once and each edge followed once: per node reached, its "
                "conj onto the queue vector and into the seen set and one distinct's set conj (820 B at "
                "100 nodes, 1,010 at 1,000, measured 2026-10-23, the hash set a level deeper: the "
                "persistent set conj has no row in seon.cost/units), and a node "
                "with no neighbors costs its visit alone; nothing per node already queued (the frontier "
                "was copied on every visit before: 446 KB at 100 leaves, quadratic)")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 12.9k at 100, 153k at 1,000
     :per-n {:ticks 160 :bytes 1100 :calls 0}
     :fixed {:ticks 2000 :bytes 2000 :calls 1}
     :sizes [100 1000 10000]}))

(defn knotted
  "components' arguments of size n: n nodes, each pointing to the next and to the one five back
  when it is a multiple of seven (cycles of a few nodes along a chain)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int] :map]]}
  [n]
  [(vec (range n)) (into {} (for [i (range n)] [i (cond-> (if (< (inc i) n) [(inc i)] []) (and (pos? i) (zero? (mod i 7))) (conj (max 0 (- i 5))))]))])

(deftest the-strongly-connected-components-are-one-visit-per-node-and-edge
  (cost/proof
    {:of 'seon.program/components :input 'seon.program-cost-test/knotted
     :n "nodes (each with one or two edges)"
     :work (str "Tarjan's: each node entered once (an index, a low link, a push), each edge followed once, "
                "each node popped into its component once. Measured 2026-10-23: 145 us / 214 KB at 100 "
                "nodes, 1.6 ms / 2.2 MB at 1,000, 21 ms / 21.7 MB at 10,000 (~2 us, ~2.2 KB a node)")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 54k at 100, 559k at 1,000
     :per-n {:ticks 560 :bytes 2000 :calls 0}
     :fixed {:ticks 2000 :bytes 2000 :calls 1}
     :sizes [100 1000 10000]}))

(defn keyed-chain
  "reach-index's arguments: a branch holding n forms (app.pc/f0..fn-1, each calling the one
  before) and a key for every tenth, each reaching every form below it: n/10 keys whose
  walks would share most of the program."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map]]}
  [n]
  (let [[db] (branch-of n)]
    [db {:env "e" :tree "t"} (into {} (for [i (range 0 n 10)] [(str "k" i) [(symbol "app.pc" (str "f" i))]]))]))

(deftest the-reach-walk-is-one-pass-whatever-the-keys
  (cost/proof
    {:of 'seon.program/reach-index :input 'seon.program-cost-test/keyed-chain
     :n "forms (and n/10 keys, each reaching the forms below it)"
     :work (str "the live forms read and digested once, each form's neighbours once, the components once "
                "(Tarjan's) and each one's Merkle digest once, sinks first; then per key its starting "
                "components' digests: linear in the forms, never forms x keys (a walk per key was "
                "quadratic here). Measured 2026-10-23: 81 ms / 94 MB at 100, 244 ms / 270 MB at 1,000, "
                "557 ms / 665 MB at 3,000; the fixed part is the test store's example program")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 31.7M at 100, 95.1M at 1,000
     :per-n {:ticks 71000 :bytes 200000}
     :fixed {:ticks 25000000 :bytes 78000000}
     :sizes [100 1000 10000]}))

(defn changed-top
  "reach-update's arguments: the reach graph of a branch holding n forms (app.pc/f0..fn-1,
  each calling the one before) and that branch with the last redefined, which nothing reaches."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  (let [[db] (branch-of n)
        g (program/reach-graph db nil)]
    [g (d/db-with db (program/record-tx db (acct (dec n) "(dec x)")))]))

(deftest a-change-re-folds-only-what-reaches-it
  (cost/proof
    {:of 'seon.program/reach-update :input 'seon.program-cost-test/changed-top
     :n "forms the graph already holds"
     :work (str "four index scans of the forms and their definitions' pointers (live-ids, no entity read), "
                "then the changed form only: its row, its var's facts, its neighbours, and the components "
                "of it and of what reaches it (nothing, here) re-folded; every other form's neighbours and "
                "component digest kept. Measured 2026-10-23: 2.1 ms / 2.7 MB at 100, 3.9 ms / 6.5 MB at 1,000, "
                "11.5 ms / 16 MB at 3,000, one form re-folded each time; the per-form part is the scan "
                "(~3 us, ~5 KB a form), the re-fold is the change's")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 0.50M at 100, 1.24M at 1,000; calls 16
     :per-n {:ticks 850 :bytes 5000 :calls 0}
     :fixed {:ticks 420000 :bytes 4000000 :calls 16}
     :sizes [100 1000 10000]}))
