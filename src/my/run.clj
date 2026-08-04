(ns my.run
  "Return values that tell the run loop to wait or complete."
  (:require [clojure.string :as str]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The two dispositions
;;; ---------------------------------------------------------------------------

(defn wait
  "Finish this run without a reply and record what you await.

  Takes a non-blank continuation note and returns a wait disposition or a flat
  error. Use it after starting or delegating work that a later agent run must
  continue; include everything that later run will need in the note."
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
  "Finish this run with a reply for its requester.

  Takes non-blank reply text and returns a completed disposition or a flat
  error. Use it only after the run's requested work is done."
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
