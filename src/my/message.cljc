(ns my.message
  "Send and read messages between agents — the flat my.* messaging tool.

  CONTRACT LAYER ONLY (Fable-authored, 2026-07-26): complete schemas and
  function contracts with honest not-implemented stubs; the step-1
  implementation lane activates them. Every function is map-in / map-out
  and enters `seon.effect/request!`; results follow the message owner's
  idiom — a concise domain map or a flat error value
  (`seon.agent.message/message!` is the one write entry underneath).

  Message identity: the implementation derives delivery identity from the
  sending receipt — (run, ordinal, epoch) — so re-execution after a crash
  replays the SAME message instead of double-sending. That property is
  contract, not implementation detail; its pending generative test guards
  it."
  (:require [seon.schema :as schema]
            ; loads the :seon.agent.message/* schema registrations
            [seon.agent.message]
            [seon.effect :as effect]))

(schema/register!
 ::send-request
 [:map {:closed true}
  [:seon.agent.message/content [:string {:min 1}]]
  [:seon.agent.message/to
   [:vector {:min 1} :seon.agent/id]]])

(schema/register!
 ::sent
 [:map {:closed true}
  [:seon.agent.message/id :seon.agent.message/id]
  [:seon.agent.message/hops :int]])

(schema/register!
 ::error
 [:map
  [:seon.error/message :string]
  [:seon.error/kind {:optional true} :qualified-keyword]])

(defn send!
  "Send one message to the named agents; returns the concise send result.
  Delivery is at-least-once with replay identity from the sending
  receipt, so a crash between commit and completion never double-sends."
  {:malli/schema [:=> [:cat ::send-request] [:or ::sent ::error]]}
  [request]
  (effect/request!
   {:seon.effect/family :message
    :seon.effect/args request}))
