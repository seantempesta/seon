(ns ^{:seon.ns/context-relevant? true} my.message
  "The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity."
  (:refer-clojure :exclude [read send])
  (:require [seon.cluster.message :as message]))

(defn inbox
  "Read my unread messages, oldest first.

  Unread means addressed to me without :seon.message/received-tx;
  my.message/received! acknowledges one. Returns a vector of
  {:my.message/id string :my.message/content string :my.message/at instant},
  with :my.message/from when the sender is known.
  Pass :seon.db/since to select messages newer than a transaction basis.

  ## Example

  ```clojure
  (my.message/inbox)
  ```"
  {:malli/schema [:=> [:cat :my.message/inbox-request] [:or :my.message/inbox :seon.error/value]]}
  [request]
  (message/inbox request))

(defn read
  "Read one message by its identity.

  Returns the stored :seon.message/id, :seon.message/content and refs;
  a missing identity returns a flat :seon.error value.

  ## Example

  ```clojure
  (my.message/read {:my.message/id \"example-message\"})
  ```"
  {:malli/schema [:=> [:cat :my.message/read-request] [:or :seon.message/message :seon.error/value]]}
  [request]
  (message/read (:my.message/id request) (:seon.db/db request)))

(defn received!
  "Acknowledge a message addressed to me; it is then no longer unread.

  Writes :seon.message/received-tx, a ref to this acknowledging transaction,
  and returns the message with it. Unread means addressed to me without that
  fact. Acknowledging an acknowledged message is inert. A missing identity
  returns a flat :seon.error value. My connection and identity are supplied.

  ## Example

  ```clojure
  (my.message/received! \"example-message\")
  ```"
  {:malli/schema [:=> [:cat :seon.message/id :seon.db/connection :seon.agent/id]
                  [:or :seon.message/message :seon.error/value]]}
  [message-id connection agent-id]
  (message/received! message-id connection agent-id))

(defn send
  "Offer a message to the recipient's Flow inbox and return its accepted identity.

  Accepted means queued. `:my.message/keep? true` also stores my own copy.
  A resend may carry `:seon.message/id`; the recipient writes that identity once.
  An undeliverable value names recipient, intent and cause. A running recipient
  without a graph is hosted by db<->flow's next pass; resend after it runs.
  Optional `:my.message/about` is a nonempty string subject identity token, stored
  verbatim; optional `:my.message/assignment` names the evaluation assigned for
  repair. The sender is supplied from my identity: `:seon.message/from` alone marks
  an inside wake, never the subject or assignment.

  ## Example

  ```clojure
  (my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})
  ```"
  {:malli/schema [:=> [:cat :my.message/send-request]
                  [:or :seon.message/accepted-result :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (message/send! request connection agent-id))
