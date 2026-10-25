(ns seon.fork-server-cost-test
  "A fork's work is the spawn's, never the program's: the reach graph a fork hands its agent is
  brought up to date only when a commit since moved the program, so a spawn on an unmoved
  program costs the same whatever the program's size."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]] [datahike.api :as d]
            [seon.cost :as cost] [seon.program :as program] [seon.fork-server :as fs] [seon.program-cost-test :as pc]))

(defn spawned-of
  "graph-at-fork's argument at n: the fork server's state over a main of n forms (app.pc/f0..fn-1,
  seon.program-cost-test/branch-of's), its reach graph built, after a spawn's commit (a run's
  facts: no program attribute)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [[db] (pc/branch-of n)
        ops [[:db/add 1 :seon.run/id "demo/a#1"] [:db/add 1 :seon.run/status :running]]]
    [(cond-> {:db db :graph (program/reach-graph db nil)} (fs/moves-program? ops) (assoc :graph-moved true))]))

(deftest a-spawns-commit-leaves-the-graph-and-a-program-commit-moves-it
  (let [[st] (spawned-of 10)]
    (is (not (:graph-moved st)) "a run's facts move no program")
    (is (identical? (:graph st) (:graph (fs/graph-at-fork st))) "so the fork hands on the graph it holds"))
  (is (fs/moves-program? [[:db/add 7 :form/id "app.pc/f0-x"]]) "a form's facts move it")
  (is (fs/moves-program? [[:db/retract 7 :var/form 9]]) "and so does a definition's pointer"))

(deftest a-fork-on-an-unmoved-program-costs-nothing-of-the-program
  (cost/proof
    {:of 'seon.fork-server/graph-at-fork :input 'seon.fork-server-cost-test/spawned-of
     :n "forms in main's program"
     :work "a key lookup: no commit since moved the program, so the graph is the one held (reach-update, which reads every live form, is not called)"
     :growth :constant
     :fixed {:calls 1 :bytes 2000}
     :sizes [10 100 1000]}))
