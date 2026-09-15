(ns my.message
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
  "Return an addressed message for the turn to deliver.

  Returns {:seon.message/id string :my.message/to string
  :my.message/content string}, plus :my.message/about when supplied.
  The turn delivers this value and preserves its id. Return it as the form's
  result; constructing it inside a discarded expression does not send it.
  Supply :my.message/about to answer a message and remove its inbox edge.

  Example:
  (my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})"
  {:malli/schema [:=> [:cat :my.message/message] [:or :my.message/message :seon.error/value]]}
  [request]
  (if-let [about (:my.message/about request)]
    (message/send (:my.message/to request) (:my.message/content request) about)
    (message/send (:my.message/to request) (:my.message/content request))))

(defn decline
  "Return a reason for declining an assignment to its sender.

  Returns {:seon.message/id string :my.message/to string :my.message/about string
  :my.message/reason string} for the turn to deliver as the form's result.
  :my.message/about identifies the assignment message being answered.

  Example:
  (my.message/decline {:my.message/to \"root\"
                       :my.message/about \"example-message\"
                       :my.message/reason \"The required input is unavailable.\"})"
  {:malli/schema [:=> [:cat :my.message/declination] [:or :my.message/declination :seon.error/value]]}
  [request]
  (message/decline (:my.message/to request) (:my.message/about request) (:my.message/reason request)))
