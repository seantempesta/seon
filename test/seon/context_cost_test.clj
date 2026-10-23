(ns seon.context-cost-test
  "Cost proofs of the context's parts that grow with the program."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]
            [datahike.api :as d] [seon.db :as pdb] [seon.context :as context] [seon.program-cost-test :as pct]))

(defn- program-of
  "seon.program-cost-test/branch-of's db of n functions (app.pc/f0.., each calling the one
  before), with n more forms each naming one of five hubs (f0..f4), so use counts are skewed
  as a real program's are: a few functions named by many forms, most by one."
  {:malli/schema [:=> [:cat pos-int?] :seon.host/db]}
  [n]
  (let [db (first (pct/branch-of n)) ne (pdb/eid db :ns/name 'app.pc)]
    (d/db-with db (vec (for [i (range n) :let [hub (symbol "app.pc" (str "f" (mod i 5)))]]
                         {:form/id (str "caller " i) :form/src (str "(" hub ")") :form/ns ne :form/order (+ n i) :form/refs [hub]})))))

(defn ns-map-of
  "seon.context/ns-map's arguments: program-of n, and one var of its namespace."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:vector :qualified-symbol]]]}
  [n] [(program-of n) ['app.pc/f0]])

(deftest a-namespace-map-counts-each-vars-uses-once
  (cost/proof
    {:of 'seon.context/ns-map :input 'seon.context-cost-test/ns-map-of
     :n "functions in the cited namespace"
     :work (str "the namespace's vars (one query), each var's uses counted once (an AVET count of the forms "
                "naming it), one sort by them, and a line (doc, arglists, its printed tokens) for each var "
                "until the map's share is spent: the shown lines are bounded by the share, not by n. Sorting "
                "with sort-by counted a var's uses on every comparison, 2 n log n counts")
     :growth :n-log-n
     ;; measured 2026-10-23 on program-of's skewed uses: 3.06M ticks / 10.8 MB at 100, 6.57M / 23.5 MB
     ;; at 300, 14.4M / 53.2 MB at 1,000 (3.01M, 6.47M, 14.0M on an unskewed chain); with sort-by's per-comparison counts 8.6M / 30 MB, 28.5M /
     ;; 102 MB and 108M / 401 MB
     :per-n {:ticks 1200.0 :bytes 4600.0}
     :fixed {:ticks 2500000.0 :bytes 8300000.0}
     :sizes [100 300 1000]}))

(defn found-of
  "seon.context/found's arguments: program-of n, nothing named, and every one of its functions
  as a source, so each has a hit to score; no task vector (the call graph's cost alone)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:set :qualified-symbol] [:vector :qualified-symbol] :nil]]}
  [n] [(program-of n) #{} (vec (for [i (range n)] (symbol "app.pc" (str "f" i)))) nil])

(deftest found-scores-each-hit-once
  (cost/proof
    {:of 'seon.context/found :input 'seon.context-cost-test/found-of
     :n "sources, each naming one function (n hits)"
     :work (str "the sources' one-hop callees (one query), each hit's score taken once (an AVET count of the "
                "forms naming it), one sort by them, and doc depth for the hits shown (at most "
                ":seon.config.render/found). Sorting with sort-by scored a hit on every comparison")
     :growth :n-log-n
     ;; measured 2026-10-23 on program-of's skewed uses: 3.12M ticks / 12.2 MB at 100, 9.08M / 35.2 MB
     ;; at 300, 30.7M / 121 MB at 1,000; with sort-by's per-comparison scores 8.2M / 30 MB, 30.8M / 114 MB and 124M / 468 MB
     :per-n {:ticks 2950.0 :bytes 11500.0}
     :fixed {:ticks 1200000.0 :bytes 4500000.0}
     :sizes [100 300 1000]}))
