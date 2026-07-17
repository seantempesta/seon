(ns seon.agent.internal
  "Framework data-manipulation internals for the agent lifecycle functions.

   This namespace is NOT whitelisted for full-source rendering — it holds
   the small shared plumbing the teaching namespaces ([[seon.agent.lifecycle]])
   lean on so their bodies stay clean and self-explaining in agent context.

   Right now that is the one shared shape the scoped functions need: the loud
   'no agent in scope' error value and the one parent-graph authorization
   rule for target-scoped lifecycle management (errors are values, never a
   throw).")

(def managed-agent-selector
  "Agent facts needed by the one parent-tree authorization rule."
  '[:db/id :seon.agent/id :seon.agent/terminated-at
    {:seon.agent/parent ...}
    {:seon.agent/run
     [:seon.agent.run/id :seon.agent.run/status
      :seon.agent.run/started-at :seon.agent.run/paused-at]}])

(defn no-agent-error
  "The direct error returned when a scope-defaulting function runs with no
   agent in the ALS scope. `fn-name` is the function name (string) used to
   build a guiding message that points the caller at `(seon.db/with-agent …)`.
   Errors are values — this is a value, not a throw."
  [fn-name]
  {:seon.error/message
   (str fn-name ": no agent in scope — call inside "
        "(seon.db/with-agent …).")})

(defn manages?
  "Whether `caller-id` may manage an already-pulled target tree.

   Root manages existing agents in the cluster. An ordinary agent manages
   itself and descendants reached through `:seon.agent/parent`. A missing
   caller has no management authority; core code establishes an explicit root
   scope when it intentionally uses this agent-facing boundary. Missing
   targets and parent cycles fail closed."
  [caller-id target]
  (cond
    (nil? caller-id) false
    (nil? (:seon.agent/id target)) false
    (= "root" caller-id) true
    :else
    (loop [agent target seen #{}]
      (let [id (:seon.agent/id agent)]
        (cond
          (nil? id) false
          (= caller-id id) true
          (contains? seen id) false
          :else
          (recur (:seon.agent/parent agent) (conj seen id)))))))

(defn unauthorized-target-error
  "The direct error for a caller outside a target's management subtree."
  [fn-name caller-id target-id]
  {:seon.error/message
   (str fn-name ": agent " (pr-str caller-id) " cannot manage "
        (pr-str target-id) "; root manages the cluster and ordinary agents "
        "manage only themselves and their descendants.")})
