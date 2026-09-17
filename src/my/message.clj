(ns ^{:seon.ns/context-relevant? true} my.message
  "The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity."
  (:refer-clojure :exclude [read send])
  (:require [seon.cluster.message :as message]))

(defn inbox
  "Read messages in my inbox, oldest first.

  Returns a vector of {:my.message/id string :my.message/content string
  :my.message/at instant}, with :my.message/from when the sender is known.
  Pass :seon.db/since to select messages newer than a transaction basis.

  Example:
  (my.message/inbox)"
  {:malli/schema [:=> [:cat :my.message/inbox-request] [:or :my.message/inbox :seon.error/value]]}
  [request]
  (message/inbox request))

(defn read
  "Read one message by its identity.

  Returns the stored :seon.message/id, :seon.message/content and refs;
  a missing identity returns a flat :seon.error value.

  Example:
  (my.message/read {:my.message/id \"example-message\"})"
  {:malli/schema [:=> [:cat :my.message/read-request] [:or :seon.message/message :seon.error/value]]}
  [request]
  (message/read (:my.message/id request) (:seon.db/db request)))

(defn send
  "Send a message now and return the written message with its identity.

  Returns the stored :seon.message/id, :seon.message/content and endpoint refs.
  Sending inside let or do delivers even when its return value is discarded.
  Optional :my.message/about is a nonempty string subject identity token, stored
  verbatim without looking up a target; omit it when there is no subject.
  Optional :my.message/assignment names the evaluation assigned for repair,
  independently of the subject. The sender is supplied from my agent identity:
  :seon.message/from alone marks an inside wake, never the subject or assignment.
  Handling is recorded when the turn settles.

  Example:
  (my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})"
  {:malli/schema [:=> [:cat :my.message/send-request]
                  [:or :seon.message/message :seon.error/value]]}
  [request]
  (message/send! (dissoc request :seon.db/connection :seon.agent/id)
                 (:seon.db/connection request) (:seon.agent/id request)))

(defn decline
  "Send a reason for declining an assignment to its sender.

  Returns the written message, including :seon.message/id and endpoint refs.
  :my.message/assignment identifies the evaluation assigned for repair.

  Example:
  (my.message/decline {:my.message/to \"root\"
                       :my.message/assignment \"evaluation-id\"
                       :my.message/reason \"The required input is unavailable.\"})"
  {:malli/schema [:=> [:cat :my.message/decline-request]
                  [:or :seon.message/message :seon.error/value]]}
  [request]
  (message/send! (-> request
                     (dissoc :seon.db/connection :seon.agent/id)
                     (assoc :my.message/content (:my.message/reason request)))
                 (:seon.db/connection request) (:seon.agent/id request)))
