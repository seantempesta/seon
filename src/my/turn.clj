(ns my.turn
  "The agent's turn function: wait ends the turn loop after this turn's forms."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.turn :as turn]))

(defn evals
  "The last turn's responses, one per eval call: each {:handle :value :out :err :ex}, its live value while this process holds it."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (turn/last-evals))

(defn wait
  "Stop working and wait: the run ends :waiting and parks; the next message to the session wakes
  it, with its conversation and a fresh turn budget."
  {:malli/schema [:=> [:cat] :map]}
  [] (turn/request-stop! :waiting nil))
