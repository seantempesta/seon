(ns my.turn
  "The agent's turn function: wait ends the turn loop after this turn's forms."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.turn :as turn]))

(defn evals
  "The last turn's evaluations in full: each item's :form, :printed value or error, and :out."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (turn/last-evals))

(defn wait
  "Stop working and wait for root: the attempt ends :waiting and root holds the task."
  {:malli/schema [:=> [:cat] :map]}
  [] (turn/request-stop! :waiting nil))
