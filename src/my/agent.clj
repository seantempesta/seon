(ns my.agent
  "An agent's own verbs (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                the live value of an earlier result (also result/h3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root"
  (:require [seon.agent :as agent] [seon.lib :as lib]))

(defn $ "The live value of an earlier result, by handle (:h3)." [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." [tos body] (agent/send-message tos body))
