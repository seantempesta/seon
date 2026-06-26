(ns seon.agent.fsm
  "The agent FSM — PURE, dual-track-shared logic (compiles on BOTH CLJ and
   CLJS; no db, no platform deps). The transition table + `derive-state` are
   the contract both tracks read: the CLJS executor drives the loop with it,
   the CLJ server renders/inspects with it.

   State is DERIVED from primitives, never stored:
     :terminated  if the agent carries `:seon.agent/terminated-at`
     :idle        else if there is NO open run
     :paused      else if the open run carries `:seon.agent.run/paused-at`
     :running     else.

   [[transitions]] is the whole machine as one value — `{state {event →
   next-state}}`. [[transition]] folds an event over it (unknown event ⇒ the
   state is unchanged). [[derive-state]] is the projection of the three
   primitives onto the state label. Both are pure fns of their args."
  (:require [seon.schema :as schema]))

;; ── Shared enums (register ONCE here; referenced by [[transition]],
;;    [[derive-state]], and seon.agent/derive-status). The DERIVED state enum
;;    is `:idle/:running/:paused/:terminated` — there is no stored agent
;;    state; it is a pure projection of the run/terminated-at primitives. ──
(schema/register! :seon.agent.fsm/state
  [:enum :idle :running :paused :terminated])

(schema/register! :seon.agent.fsm/event
  [:enum :trigger :turn-ok :wait :complete :turn-limit :deadline
         :superseded :error :no-forms :pause :terminate :resume])

(def transitions
  "The whole FSM as data — `{state {event → next-state}}`. A wake (`:trigger`)
   opens a run (`:idle`→`:running`); verbs/bounds/fences close it back to
   `:idle`; `:pause`/`:resume` hold without killing; `:terminate` is terminal.
   An event absent from a state's row leaves the state unchanged (see
   [[transition]])."
  {:idle       {:trigger :running}
   :running    {:turn-ok    :running :wait     :idle :complete   :idle
                :turn-limit :idle    :deadline :idle :superseded :idle
                :error      :idle    :no-forms :idle :pause      :paused
                :terminate  :terminated}
   :paused     {:resume :running :terminate :terminated}
   :terminated {}})

(defn transition
  "The single transition function: `(state, event) → next-state`. An event
   that is not in `state`'s row leaves the state unchanged (e.g. `:resume`
   while `:running`, or anything while `:terminated`)."
  {:malli/schema [:=> [:catn [:seon.agent.fsm/state :seon.agent.fsm/state]
                             [:seon.agent.fsm/event :seon.agent.fsm/event]]
                  :seon.agent.fsm/state]}
  [state event]
  (get-in transitions [state event] state))

;; The primitives derive-state projects. Keys carry the real attr names; their
;; VALUE schemas are base types (`:inst`/`:boolean`) so this ns has NO
;; load-time dependency on the attr registrations in seon.agent / seon.agent.run
;; (it stays pure + cljc). `open?` is "is there an open run" (the `(nil?
;; open-run)` test from the spec).
(schema/register! :seon.agent.fsm/primitives
  [:map
   [:seon.agent/terminated-at {:optional true} :inst]
   [:seon.agent.run/open?     {:optional true} :boolean]
   [:seon.agent.run/paused-at {:optional true} :inst]])

(defn derive-state
  "Project the three primitives onto the derived state keyword. Pure — the
   caller reads the primitives from the db and hands them in. Presence of
   `:seon.agent/terminated-at` ⇒ `:terminated`; no open run ⇒ `:idle`; an
   open run with `:seon.agent.run/paused-at` ⇒ `:paused`; else `:running`."
  {:malli/schema [:=> [:catn [:seon.agent.fsm/primitives :seon.agent.fsm/primitives]]
                  :seon.agent.fsm/state]}
  [{:seon.agent/keys [terminated-at]
    open?     :seon.agent.run/open?
    paused-at :seon.agent.run/paused-at}]
  (cond
    terminated-at :terminated
    (not open?)   :idle
    paused-at     :paused
    :else         :running))
