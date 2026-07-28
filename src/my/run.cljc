(ns my.run
  "What an agent says about its own run: two values, nothing else.

  This contract layer is fully implemented and live-proven.

  THE AGENT-FACING SURFACE IS VALUES, NOT EFFECTS. These two functions
  are the first of the three agent-facing shapes — pure code returning
  a VALUE the driver interprets. They commit nothing, read nothing, and
  need no capability: an agent's last form evaluates to one of them and
  the loop reads it out of the admitted value. `my.message/send` is the
  second member of the same family and works the same way.

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
  "End this run with no reply, leaving a note saying what you await.
  MEASURED, not aspirational (2026-07-28): the loop releases custody
  and its very next pass closes the run, because a run whose plan is
  fully executed has nothing left to resume. What resumes is the AGENT,
  on its next trigger — a peer's reply, a human's nudge — with a fresh
  run and a freshly derived prompt. The earlier wording here promised
  that \"the run resumes on a later wake\"; it does not, and an agent
  reasoning from that would expect a continuity it does not have.

  THE NOTE IS THAT CONTINUITY, and it is the only one there is. The
  next prompt reads it back out of this form's receipt
  (`seon.cluster.prompt`), so a delegating agent must put everything
  its next run will need into the note: the fresh run has a fresh sci
  ctx, so no def survives, and a peer's reply arriving as \"25\" is
  unanswerable without it. It is not a status flag.
  A blank or non-string note returns the ONE registered flat error
  value, same as `complete` — an agent mistake answers, never throws
  and never yields a silently-invalid disposition."
  {:malli/schema [:=> [:cat :my.run/note]
                  [:or :my.run/wait :seon.error/value]]}
  [note]
  (if (or (not (string? note)) (str/blank? note))
    {:seon.error/kind ::blank-note
     :seon.error/message
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
    {:seon.error/kind ::blank-result
     :seon.error/message
     "complete needs the reply text you want delivered, as a string."}
    {:my.run/disposition :completed
     :my.run/result result}))
