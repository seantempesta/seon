(ns my.plan.generation
  "Own the generated-plan scheduler vocabulary shared across namespaces."
  (:require
    [seon.schema :as schema]))

(schema/register! ::ready-namespace-state
  [:map
   [:my.plan/id :my.plan/id]
   [:seon.ns/name :symbol]])
(schema/register! ::namespace-step-state
  [:map
   [:my.plan/id :my.plan/id]
   [:seon.ns/name :symbol]
   [:my.plan/status :my.plan/status]
   [:my.plan/claim {:optional true} :my.plan/claim]])
(schema/register! ::namespace-steps [:vector ::namespace-step-state])
(schema/register! ::ready-steps [:vector ::ready-namespace-state])
(schema/register! ::generated-root-state
  [:map
   [:my.plan/id :my.plan/id]
   [:my.plan/status :my.plan/status]
   [:my.plan/progress :my.plan/progress]
   [:my.plan/blocked? :my.plan/blocked?]
   [::namespace-steps ::namespace-steps]
   [::ready-steps ::ready-steps]])
