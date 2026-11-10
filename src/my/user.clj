(ns my.user
  "The agent's functions on the person it works for (Seon's my.user): move their view. See docs/users.md#whats-on-screen."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent]))

(defn focus!
  "Move the signed-in person's view to the page of the agent with key k (their :seon.user/focus, which home opens): root's answer, or its refusal when no one is signed in or no agent has that key. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :string] :any]}
  [k]
  (agent/focus! k))
