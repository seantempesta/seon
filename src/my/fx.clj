(ns my.fx
  "An agent's effects (from worktree session FX1): every outside effect has one owner proc in root; these
  wrappers reach it and record the outcome with this eval request. An agent may still do
  anything in its own process; these are the default path, with admission, budget and
  recording for free.
    (call kind req)        synchronous: the outcome {:value ...} or the declared error
    (call kind req opts)   opts {:prio n :budget n :budget-key k}
    (send kind req)        async: answers k now; the outcome arrives as a message
                           {:from :fx :k k :kind kind :body outcome} ((messages))
  Inside a test (a fork of this agent) nothing leaves the process: the outcome is
  seon.agent/*fx-stub*'s, or :seon.fx/in-test."
  (:refer-clojure :exclude [defn defn- send])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent]))

(defn call "The effect kind with req, answered: {:value ...} or its declared error."
  {:malli/schema [:function [:=> [:cat :keyword :map] :map] [:=> [:cat :keyword :map :map] :map]]}
  ([kind req] (agent/fx! kind req {}))
  ([kind req opts] (agent/fx! kind req opts)))

(defn send "The effect kind with req, started: its key; the outcome comes as a message."
  {:malli/schema [:function [:=> [:cat :keyword :map] [:or :int :map]] [:=> [:cat :keyword :map :map] [:or :int :map]]]}
  ([kind req] (agent/fx-send! kind req {}))
  ([kind req opts] (agent/fx-send! kind req opts)))
