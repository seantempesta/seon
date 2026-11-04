(ns seon.fork-server-cost-test
  "A fork's work is the spawn's, never the program's: the reach graph a fork hands its agent is
  brought up to date only when a commit since moved the program, so a spawn on an unmoved
  program costs the same whatever the program's size."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]] [datahike.api :as d]
            [seon.cost :as cost] [seon.program :as program] [seon.fork-server :as fs] [seon.program-cost-test :as pc]))

(defn spawned-of
  "graph-at-fork's argument at n: the fork server's state over a main of n forms (app.pc/f0..fn-1,
  seon.program-cost-test/branch-of's), its reach graph built, after a spawn's commit (an agent's
  facts: no program attribute)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [[db] (pc/branch-of n)
        ops [[:db/add 1 :seon.agent/key "boot-1/a1"] [:db/add 1 :seon.agent/boot "boot-1"]]]
    [(cond-> {:db db :graph (program/reach-graph db nil)} (program/moves-program? ops) (assoc :graph-moved true))]))

(deftest a-spawns-commit-leaves-the-graph-and-a-program-commit-moves-it
  (let [[st] (spawned-of 10)]
    (is (not (:graph-moved st)) "an agent's facts move no program")
    (is (identical? (:graph st) (:graph (fs/graph-at-fork st))) "so the fork hands on the graph it holds"))
  (is (program/moves-program? [[:db/add 7 :form/id "app.pc/f0-x"]]) "a form's facts move it")
  (is (program/moves-program? [[:db/retract 7 :var/form 9]]) "and so does a definition's pointer"))

(deftest a-program-commit-moves-the-graph-to-the-new-program
  ;; the graph a fork (or a standby: the self-review's finding, a standby forked before the next
  ;; fork kept the passed graph) hands on is the new program's, and the flag is cleared
  (let [[st] (spawned-of 10)
        db (:db st)
        acct {:id "app.pc/f5-(dec x)" :src "(defn f5 [x] (dec x))" :ns 'app.pc :ns-form? false
              :defs [{:var 'app.pc/f5 :kind :fn}] :declares #{} :methods [] :impls []
              :refs #{'app.pc/f4} :requires #{} :banned #{} :inline-defs #{}}
        {db2 :db-after txd :tx-data} (d/with db (program/record-tx db acct))
        ops (for [dt txd] [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])
        st2 (cond-> (assoc st :db db2) (program/moves-program? ops) (assoc :graph-moved true))
        moved (fs/graph-at-fork st2)
        fresh (program/reach-graph db2 nil)]
    (is (:graph-moved st2) "a redefinition moves the program")
    (is (= (select-keys fresh [:live :schemas]) (select-keys (:graph moved) [:live :schemas])) "the graph is the new program's")
    (is (not (:graph-moved moved)) "and the flag is cleared")))

(deftest a-fork-on-an-unmoved-program-costs-nothing-of-the-program
  (cost/proof
    {:of 'seon.fork-server/graph-at-fork :input 'seon.fork-server-cost-test/spawned-of
     :n "forms in main's program"
     :work "a key lookup: no commit since moved the program, so the graph is the one held (reach-update, which reads every live form, is not called)"
     :growth :constant
     :fixed {:calls 1 :bytes 2000}
     :sizes [10 100 1000]}))
