(ns my.message
  "The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity."
  (:refer-clojure :exclude [read send])
  (:require [seon.cluster.message :as message]))

(defn inbox
  "Read messages addressed to me, oldest first."
  {:malli/schema [:=> [:cat :my.message/inbox-request] [:or :my.message/inbox :seon.error/value]]}
  [request]
  (message/inbox request))

(defn read
  "Read one message by its identity."
  {:malli/schema [:=> [:cat :my.message/read-request] [:or :seon.cluster.message/message :seon.error/value]]}
  [request]
  (message/read (:my.message/id request) (:seon.db/db request)))

(defn send
  "Return an addressed message for the turn to deliver."
  {:malli/schema [:=> [:cat :my.message/message] [:or :my.message/message :seon.error/value]]}
  [request]
  (if-let [about (:my.message/about request)]
    (message/send (:my.message/to request) (:my.message/content request) about)
    (message/send (:my.message/to request) (:my.message/content request))))

(defn decline
  "Return a reason for declining an assignment to its sender."
  {:malli/schema [:=> [:cat :my.message/declination] [:or :my.message/declination :seon.error/value]]}
  [request]
  (message/decline (:my.message/to request) (:my.message/about request) (:my.message/reason request)))
