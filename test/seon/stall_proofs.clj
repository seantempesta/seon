(ns seon.stall-proofs
  "A stall task's proofs, its boundary conditions (seon.template/stall-proofs): each reads the task
  the agent works (my.task/current) and its :seon.task/cause. Not a *_test namespace, so bin/test never selects them, and no agent's plan does unless they
  are its task's (seon.agent/test-plan): they run as a stall task's proofs, in the agent that works it."
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str] [datahike.api :as d]
            [seon.agent :as agent] [seon.db :as pdb] [my.task]))

(defn- cause [] (:seon.task/cause (my.task/current)))

(defn- main [] (pdb/head (:store-conn @agent/state) (pdb/main-branch (:cfg @agent/state))))

(deftest the-stall-ended
  ;; the stalled agent spoke or ended: main holds no :seon.process/stall on a process of the agent of its task
  (let [{:keys [task agent]} (:stall (cause))
        a (:seon.task/agent (d/entity (main) [:seon.task/id task]))]
    (is (some? a) (str "main holds task " task " and its agent " agent))
    (is (nil? (some :seon.process/stall (:seon.process/_agent a))) (str "agent " agent " is still stalled: " (pr-str (mapv :seon.process/stall (:seon.process/_agent a)))))))

(deftest its-cause-is-recorded
  ;; what the stall was, with the evidence captured while it was stuck (sample, lsof, ps)
  (let [{:keys [what evidence]} (cause)]
    (is (not (str/blank? what)) "the cause: (my.task/update! :seon.task/cause merge {:what ..})")
    (is (seq evidence) "its evidence: {:sample .. :lsof .. :ps ..}")))

(deftest a-bug-is-filed
  ;; whether the cause is a bug is decided, and a bug's defect task is filed (on main or this branch)
  (let [{:keys [bug? defect]} (cause)]
    (is (boolean? bug?) "decided: :bug? true or false")
    (when bug?
      (is (and defect (or (d/entity (main) [:seon.task/id defect]) (d/entity (agent/db) [:seon.task/id defect])))
          (str "a bug's defect task is filed and named (:defect): " (pr-str defect))))))
