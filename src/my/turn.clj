(ns my.turn
  "The agent's turn verb: wait ends the turn loop after this turn's forms."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.turn :as turn]))

(defn wait
  "Stop working and wait for root: the attempt ends :waiting and root holds the task."
  {:malli/schema [:=> [:cat] :map]}
  [] (turn/request-stop! :waiting nil))
