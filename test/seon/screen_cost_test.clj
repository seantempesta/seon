(ns seon.screen-cost-test
  "Cost proof of the screen's diff (seon.screen/diff), which grows with the screen: each section
  compared once."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost]))

(defn diff-of
  "seon.screen/diff's arguments: a screen of n sections (a map of n proofs and a page of n tiles),
  then the same screen with one proof and one tile changed, made apart so equality walks each."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [screen (fn [changed] {:proofs (into {} (for [i (range n)] [(symbol "demo-test" (str "t" i)) (if (= i changed) :pass :fail)]))
                              :page (vec (for [i (range n)] [:div {:id (str "tile" i)} (if (= i changed) "changed" (str "tile " i))]))})]
    [(screen -1) (screen 0)]))

(deftest a-diff-compares-each-section-once
  ;; task seon.screen/the-screen-is-sent-as-diffs
  (cost/proof
    {:of 'seon.screen/diff :input 'seon.screen-cost-test/diff-of
     :n "sections of the screen (proofs and tiles)"
     :work "each section compared once by equality, and a path conj'd for each: linear in the sections"
     :growth :linear
     ;; measured 2026-10-25 (n 100 -> 10,000): 248 ticks and 1.28 KB a section, one contracted
     ;; call; with each map's keys sorted by name first it was 10 KB a section and steeper
     :per-n {:ticks 260.0 :bytes 1300.0 :calls 0.0}
     :fixed {:ticks 2000.0 :bytes 5000.0 :calls 1.0}
     :sizes [100 1000 10000]}))
