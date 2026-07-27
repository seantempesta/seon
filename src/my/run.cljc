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
  for the agent's own next prompt — it is not a status flag."
  {:malli/schema [:=> [:cat :my.run/note] :my.run/wait]}
  [note]
  {:my.run/disposition :wait
   :my.run/note note})

(defn complete
  "Finish this run with the reply the agent wants delivered.
  The loop closes the run and commits the result as the completion
  message; there is no `:seon.cluster.run/result` attribute, because
  the message content already carries it (n3-plan §3.2).
  Blank text returns `{:seon.error/message …}` — a flat value the agent
  can see and correct on its next turn, never a throw."
  {:malli/schema [:=> [:cat :my.run/result]
                  [:or :my.run/completed [:map [:seon.error/message :string]]]]}
  [result]
  (if (str/blank? result)
    {:seon.error/message
     "complete needs the reply text you want delivered; it was blank."}
    {:my.run/disposition :completed
     :my.run/result result}))
