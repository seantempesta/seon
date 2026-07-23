(ns seon.derive.state
  "Portable derived agent-state projection."
  (:require
    [seon.schema :as schema]))

(schema/register! :seon.derive/state
  [:enum :idle :running :paused :terminated])
(schema/register! :seon.derive/agent-state-read-attrs
  [:set :qualified-keyword])
(schema/register! :seon.derive/primitives
  [:map
   [:seon.agent/terminated-at {:optional true} :inst]
   [:seon.agent.run/open? {:optional true} :boolean]
   [:seon.agent.run/paused-at {:optional true} :inst]])

(def agent-state-read-attrs
  "Stored attributes used to derive an agent's state."
  #{:seon.agent/id
    :seon.agent/run
    :seon.agent/terminated-at
    :seon.agent.run/status
    :seon.agent.run/paused-at})

(defn state-from-primitives
  "Project stored state primitives onto one derived agent state."
  {:malli/schema [:=> [:catn [:seon.derive/primitives :seon.derive/primitives]]
                  :seon.derive/state]}
  [{:seon.agent/keys [terminated-at]
    open? :seon.agent.run/open?
    paused-at :seon.agent.run/paused-at}]
  (cond
    terminated-at :terminated
    (not open?) :idle
    paused-at :paused
    :else :running))
