(ns my.user
  "The agent's functions on the person it works for (Seon's my.user): the sessions working for them, and move their view. See docs/users.md#whats-on-screen.
    (my.user/sessions)       every session on main as this agent last heard it: [{:key :status :task :outcome}], by key
    (my.user/focus! \"k\")     move the person's view to session k's page"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.ctx :as ctx] [seon.ratom :as ratom]))

(defn sessions
  "Every session on main as this agent's record holds them, at the point its turn heard main: each {:key :status :task :outcome}, by key.
  Read from the record's join through root to every agent (root's session's pattern, seon.root/root-session-pattern), so it reads no db and
  costs the sessions it lists; an agent whose pattern holds no such join has none. What root's session asks when it looks for a session
  already on a person's request (its page lists none: its model reads none in each request). See docs/users.md#roots-session."
  {:malli/schema [:=> [:cat] [:vector [:map [:key :string] [:status {:optional true} :any] [:task {:optional true} [:maybe :string]] [:outcome {:optional true} :any]]]]}
  []
  (->> (vals (ratom/realize (get-in @ctx/ctx [:seon.agent/root :seon.agent/_root])))
       (keep (fn [a] (when-let [k (:seon.agent/key a)]
                       {:key k :status (:seon.agent/status a) :task (get-in a [:seon.agent/task :seon.task/id]) :outcome (get-in a [:seon.agent/task :seon.task/outcome])})))
       (sort-by :key)
       vec))

(defn focus!
  "Move the signed-in person's view to the page of the agent with key k (their :seon.user/focus, which home opens): root's answer, or its refusal when no one is signed in or no agent has that key. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :string] :any]}
  [k]
  (agent/focus! k))
