(ns seon.agent.core
  "Define the portable schema of one agent entity."
  (:require
    [seon.schema :as schema]))

(schema/register! :seon.agent/purpose :string)
(schema/register! :seon.agent/namespace
                  [:and {:seon.db/unique true} :seon.db/ref])
(schema/register! :seon.agent/parent :seon.db/ref)
(schema/register! :seon.agent/run :seon.db/ref)
(schema/register! :seon.agent/terminated-at :inst)
(schema/register! :seon.agent/default-turn-limit :int)
(schema/register! :seon.agent/default-deadline-ms :int)
(schema/register! :seon.agent/schedules
                  [:set {:seon.db/component true} :seon.db/ref])

(schema/register!
  :seon.agent
  [:map {:seon.db/entity true}
   [:seon.agent/id :seon.agent/id]
   [:seon.agent/namespace :seon.agent/namespace]
   [:seon.agent/purpose {:optional true} :seon.agent/purpose]
   [:seon.agent/parent {:optional true} :seon.agent/parent]
   [:seon.agent/run {:optional true} :seon.agent/run]
   [:seon.agent/terminated-at
    {:optional true} :seon.agent/terminated-at]
   [:seon.agent/default-turn-limit
    {:optional true} :seon.agent/default-turn-limit]
   [:seon.agent/default-deadline-ms
    {:optional true} :seon.agent/default-deadline-ms]
   [:seon.agent/schedules {:optional true} :seon.agent/schedules]
   [:seon.agent/ctx {:optional true} :seon.agent/ctx]
   [:seon.agent.ctx/capabilities
    {:optional true} :seon.agent.ctx/capabilities]
   [:seon.agent.ctx/escape-clipping?
    {:optional true} :seon.agent.ctx/escape-clipping?]
   [:seon.agent.ctx/cache-breakpoint
    {:optional true} :seon.agent.ctx/cache-breakpoint]
   [:seon.config/repl-mode {:optional true} :seon.config/repl-mode]
   [:seon.eval/home-requires {:optional true} :seon.eval/home-requires]
   [:seon.render/ai {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])
