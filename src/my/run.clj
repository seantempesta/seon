(ns my.run
  "The lifecycle protocol for every run.

  Every run ends by calling `complete` or `wait`. An undisposed run is
  unfinished work: it has neither answered its requester nor recorded what
  must happen before work can continue."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn render-namespace-ai
  "Present my.run as the lifecycle protocol, in use order."
  {:malli/schema [:=> [:cat :my.run/namespace-unit] [:maybe :string]]}
  [unit]
  (let [database (:seon.db/db unit)
        docs
        (when database
          (into
           {}
           (db/q '[:find ?sym ?doc
                   :in $ [?sym ...]
                   :where
                   [?function :seon.fn/sym ?sym]
                   [?function :seon.fn/doc ?doc]]
                 database
                 ["my.run/complete" "my.run/wait"])))]
    (str (:seon.ns/doc unit)
         "\n\n1. complete — "
         (or (get docs "my.run/complete")
             "Finish completed work with a reply for its requester.")
         "\n\n2. wait — "
         (or (get docs "my.run/wait")
             "Finish paused work with the condition needed to continue."))))

;;; ---------------------------------------------------------------------------
;;; The two dispositions
;;; ---------------------------------------------------------------------------

(defn wait
  "Finish this run without a reply and record what you await.

  Takes a non-blank continuation note and returns a wait disposition or a flat
  error. Use `wait` when this run cannot finish until a named event or reply;
  include everything the later run will need in the note."
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
  error. Use `complete` when the requested work is finished and this text is
  the real reply its requester should receive."
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
