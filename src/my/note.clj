(ns my.note
  "Save durable observations with add! and read them with notes.

  Reusing :my.note/id updates that note; my agent identity is supplied.
  Use :my.note/about when the note concerns another entity.

  Example:
  (my.note/add! {:my.note/id \"observation\"
                 :my.note/content \"Verified the customer total.\"})"
  (:require [seon.note :as note]))

(defn notes
  "Read my current notes in identity order.

  Returns a vector of note maps with :my.note/id, :my.note/content and
  :my.note/agent, plus :my.note/about when supplied; [] means no notes.

  Example:
  (my.note/notes)"
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.note/notes :seon.error/value]]}
  [request]
  (note/notes (:seon.db/db request) (:seon.agent/id request)))

(defn add!
  "Add or update my note by identity and return the saved note.

  Supply :my.note/id and :my.note/content; optionally supply :my.note/about
  as an existing entity ref. My agent identity and connection are supplied.
  Returns {:my.note/id string :my.note/content string :my.note/agent ref},
  with :my.note/about when present. An id owned by another agent is refused.

  Example:
  (my.note/add! {:my.note/id \"observation\"
                 :my.note/content \"Verified the customer total.\"})"
  {:malli/schema [:=> [:cat :my.note/add-request] [:or :my.note/note :seon.error/value]]}
  [request]
  (if-let [about (:my.note/about request)]
    (note/add! (:my.note/id request) (:my.note/content request) about (:seon.db/connection request) (:seon.agent/id request))
    (note/add! (:my.note/id request) (:my.note/content request) (:seon.db/connection request) (:seon.agent/id request))))

(defn forget!
  "Forget my current note while retaining its database history.

  Returns the removed note map, including :my.note/id and :my.note/content;
  a missing or foreign note returns a flat error. The note is no longer current.

  Example:
  (do (my.note/add! {:my.note/id \"temporary-observation\"
                     :my.note/content \"Superseded observation.\"})
      (my.note/forget! {:my.note/id \"temporary-observation\"}))"
  {:malli/schema [:=> [:cat :my.note/forget-request] [:or :my.note/note :seon.error/value]]}
  [request]
  (note/forget! (:my.note/id request) (:seon.db/connection request) (:seon.agent/id request)))
