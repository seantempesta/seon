(ns seon.agent.internal
  "Framework data-manipulation internals for the agent lifecycle functions.

   This namespace is NOT whitelisted for full-source rendering — it holds
   the small shared plumbing the teaching namespaces ([[seon.agent.lifecycle]])
   lean on so their bodies stay clean and self-explaining in agent context.

   Right now that is the one shared shape the scoped functions need: the loud
   'no agent in scope' error envelope and the one parent-graph authorization
   rule for target-scoped lifecycle management (errors are values, never a
   throw)."
  (:require [seon.db :as db]))

(defn no-agent-error
  "The error envelope returned when a scope-defaulting function runs with no
   agent in the ALS scope. `fn-name` is the function name (string) used to
   build a guiding message that points the caller at `(seon.db/with-agent …)`.
   Errors are values — this is a value, not a throw."
  [fn-name]
  {:seon.db/ok? false
   :seon.db/error {:seon.error/message
                   (str fn-name ": no agent in scope — call inside "
                        "(seon.db/with-agent …).")}})

(defn manages?
  "Whether `caller-id` may manage `target-id` in immutable database `db`.

   Root manages existing agents in the cluster. An ordinary agent manages
   itself and descendants reached through `:seon.agent/parent`. A missing
   caller has no management authority; core code establishes an explicit root
   scope when it intentionally uses this agent-facing boundary. Missing
   targets and parent cycles fail closed."
  [db caller-id target-id]
  (let [target (when target-id
                 (db/entity {:seon.db/db db
                             :seon.db/ref [:seon.agent/id target-id]}))]
    (cond
    (nil? caller-id) false
    (nil? (:seon.agent/id target)) false
    (= "root" caller-id) true
    (= caller-id target-id) true
    :else
    (loop [id target-id seen #{}]
      (cond
        (nil? id) false
        (contains? seen id) false
        (= caller-id id) true
        :else
        (recur
          (:seon.agent/id
            (:seon.agent/parent
              (db/entity {:seon.db/db db
                          :seon.db/ref [:seon.agent/id id]})))
          (conj seen id)))))))

(defn unauthorized-target-error
  "The error envelope for a caller outside a target's management subtree."
  [fn-name caller-id target-id]
  {:seon.db/ok? false
   :seon.db/error
   {:seon.error/message
    (str fn-name ": agent " (pr-str caller-id) " cannot manage "
         (pr-str target-id) "; root manages the cluster and ordinary agents "
         "manage only themselves and their descendants.")}})
