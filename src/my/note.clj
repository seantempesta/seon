(ns my.note
  "My notes are durable facts I can read and transact.

  Upsert a note by :my.note/id and link it to my agent with :my.note/agent.
  Use :my.note/about when the note concerns another entity.

  Example:
  (seon.db/transact! [{:my.note/id \"customer-total\"
                      :my.note/agent [:seon.agent/id \"juniper\"]
                      :my.note/content \"Ada totals 155 after the new order.\"}])"
  (:require [seon.note :as note]))

(defn notes
  "Read my current notes."
  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.note/notes :seon.error/value]]}
  [request]
  (note/notes (:seon.db/db request) (:seon.agent/id request)))

(defn add!
  "Add or update my note and return it."
  {:malli/schema [:=> [:cat :my.note/add-request] [:or :my.note/note :seon.error/value]]}
  [request]
  (if-let [about (:my.note/about request)]
    (note/add! (:my.note/id request) (:my.note/content request) about (:seon.db/connection request) (:seon.agent/id request))
    (note/add! (:my.note/id request) (:my.note/content request) (:seon.db/connection request) (:seon.agent/id request))))

(defn forget!
  "Forget my current note while retaining its database history."
  {:malli/schema [:=> [:cat :my.note/forget-request] [:or :my.note/id :seon.error/value]]}
  [request]
  (note/forget! (:my.note/id request) (:seon.db/connection request) (:seon.agent/id request)))
