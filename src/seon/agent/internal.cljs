(ns seon.agent.internal
  "Support agent lifecycle functions with shared data transformations.

   This internal namespace centralizes scoped-agent error values and
   parent-graph authorization used by the public lifecycle surface. It is not
   rendered as agent-facing source and does not own lifecycle policy.")

(def managed-agent-selector
  "Agent facts needed by the parent-tree authorization rule."
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
