(ns my.run
  "What an agent says about its own run: two values, nothing else.

  CONTRACT LAYER (drafted + ORCHESTRATOR-SEALED 2026-07-27 — N3,
  package 1). Nothing here is implemented: every body throws
  `awaits implementation`.

  THE AGENT-FACING SURFACE IS VALUES, NOT EFFECTS. These two functions
  are the first of the three agent-facing shapes — pure code returning
  a VALUE the driver interprets. They commit nothing, read nothing, and
  need no capability: an agent's last form evaluates to one of them and
  the loop reads it out of the admitted value.

  EXACTLY TWO (owner ruling, 2026-07-27 night): `complete` and `wait`.
  No `start!`, no `pause`/`resume`/`terminate` — those are the quarry's
  (`src-old/seon/agent/lifecycle.cljc:58-86`) and they wait for an
  agent-lifecycle entity that does not exist. Adding a third
  disposition is a design change, not a convenience.

  ERRORS ARE VALUES HERE TOO. `complete` with blank text returns a flat
  `:seon.error` value rather than throwing — an agent mistake is never
  an exception, and the loop treats a non-disposition exactly as it
  treats any other final value: the run is not completed.

  Crash walk: neither function has durable state. A kill loses a value
  on a dead thread; the run's facts are untouched and N2's recovery
  owns what happens next."
  (:require [clojure.string :as str]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/dispositions.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The two dispositions
;;; ---------------------------------------------------------------------------

(defn wait
  "Leave this run open and unclaimed, with a note saying why.
  The agent has said what it is waiting for; the loop releases custody
  and the run resumes on a later wake. The note is for the human and
  for the agent's own next prompt — it is not a status flag.
  A blank or non-string note returns the ONE registered flat error
  value, same as `complete` — an agent mistake answers, never throws
  and never yields a silently-invalid disposition."
  {:malli/schema [:=> [:cat :my.run/note]
                  [:or :my.run/wait :seon.error/value]]}
  [note]
  (if (or (not (string? note)) (str/blank? note))
    {:seon.error/message
     "wait needs a note saying what you are waiting for, as a string."}
    {:my.run/disposition :wait
     :my.run/note note}))

(defn complete
  "Finish this run with the reply the agent wants delivered.
  The loop closes the run in the terminal transaction; the result is
  durable there as the last form's `result-edn` (the disposition IS the
  final value), so there is no `:seon.cluster.run/result` attribute and
  no completion message — N3 has no addressable recipient, and delivery
  to a human is the messaging rung's business (seal revision
  2026-07-27: the earlier sentence promised a message ahead of any
  mechanism that could carry one).
  Blank text returns the ONE registered flat error value
  (`:seon.error/value`) — something the agent can see and correct on its
  next turn, never a throw. One error shape, one owner (error.edn)."
  {:malli/schema [:=> [:cat :my.run/result]
                  [:or :my.run/completed :seon.error/value]]}
  [result]
  ; agent-facing: a wrong TYPE is an agent mistake too — the error
  ; value answers, str/blank? on a non-string would throw
  (if (or (not (string? result)) (str/blank? result))
    {:seon.error/message
     "complete needs the reply text you want delivered, as a string."}
    {:my.run/disposition :completed
     :my.run/result result}))
