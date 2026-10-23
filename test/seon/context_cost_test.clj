(ns seon.context-cost-test
  "Cost proofs of the context's parts that grow with the program."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [seon.context :as context] [seon.program-cost-test :as pct]))

(defn ns-map-of
  "seon.context/ns-map's arguments: a branch db whose namespace app.pc holds n functions, each
  calling the one before (seon.program-cost-test/branch-of), and one var of it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:vector :qualified-symbol]]]}
  [n] [(first (pct/branch-of n)) ['app.pc/f0]])

(deftest a-namespace-map-counts-each-vars-uses-once
  (cost/proof
    {:of 'seon.context/ns-map :input 'seon.context-cost-test/ns-map-of
     :n "functions in the cited namespace"
     :work (str "the namespace's vars (one query), each var's uses counted once (an AVET count of the forms "
                "naming it), one sort by them, and a line (doc, arglists, its printed tokens) for each var "
                "until the map's share is spent: the shown lines are bounded by the share, not by n. Sorting "
                "with sort-by counted a var's uses on every comparison, 2 n log n counts")
     :growth :n-log-n
     ;; measured 2026-10-23: 3.01M ticks / 10.6 MB at 100, 6.47M / 22.9 MB at 300, 14.0M / 51.4 MB at
     ;; 1,000 (167 lines shown from 300 on); with sort-by's per-comparison counts 8.6M / 30 MB, 28.5M /
     ;; 102 MB and 108M / 401 MB
     :per-n {:ticks 1200.0 :bytes 4600.0}
     :fixed {:ticks 2500000.0 :bytes 8300000.0}
     :sizes [100 300 1000]}))

(defn found-of
  "seon.context/found's arguments: branch-of's db of n functions, each calling the one before,
  nothing named, and every one of them as a source, so each has a hit to score."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:set :qualified-symbol] [:vector :qualified-symbol]]]}
  [n] [(first (pct/branch-of n)) #{} (vec (for [i (range n)] (symbol "app.pc" (str "f" i))))])

(deftest found-scores-each-hit-once
  (cost/proof
    {:of 'seon.context/found :input 'seon.context-cost-test/found-of
     :n "sources, each naming one function (n hits)"
     :work (str "the sources' one-hop callees (one query), each hit's score taken once (an AVET count of the "
                "forms naming it), one sort by them, and doc depth for the hits shown (at most "
                ":seon.config.render/found). Sorting with sort-by scored a hit on every comparison")
     :growth :n-log-n
     ;; measured 2026-10-23: 3.05M ticks / 11.8 MB at 100, 8.99M / 34.8 MB at 300, 30.1M / 117 MB at
     ;; 1,000; with sort-by's per-comparison scores 8.2M / 30 MB, 30.8M / 114 MB and 124M / 468 MB
     :per-n {:ticks 2950.0 :bytes 11500.0}
     :fixed {:ticks 1200000.0 :bytes 4500000.0}
     :sizes [100 300 1000]}))
