(ns my.task
  "The agent's task verbs (Seon's my.task): read its task, improve it, or send it back to
  root. Writes go to the agent's own branch through the writer; root sees them when the
  branch merges, and a send-back through the attempt's end."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.task :as task] [seon.turn :as turn]))

(defn current
  "The task this agent works, as data (:seon.task/task)."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/task]]}
  [] (some->> (turn/current-task) (task/task (agent/db))))

(defn update!
  "clojure.core/update on a content member of this agent's task (:seon.task/plan or
  :seon.task/instructions), written on its branch. Answers the task after the update."
  {:malli/schema [:=> [:cat [:enum :seon.task/plan :seon.task/instructions] :seon.host/fn [:* :any]] [:maybe :seon.task/task]]}
  [member f & args]
  (let [t (current)]
    (agent/transact! [{:seon.task/id (:seon.task/id t) member (apply f (get t member) args)}])
    (current)))

(defn send-back!
  "Give the task back to root as broken or impossible: reason, and a counterexample
  (a form or value showing it). The loop ends after this turn; root holds the task with
  the reason as its problem."
  {:malli/schema [:=> [:cat :string :any] :map]}
  [reason counterexample]
  (turn/request-stop! :sent-back (str reason " — counterexample: " (agent/printer counterexample))))
