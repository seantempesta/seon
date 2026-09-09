(ns my.note
  "My durable notes through one request map per call."
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
