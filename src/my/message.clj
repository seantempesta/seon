(ns ^{:seon.ns/context-relevant? true} my.message
  "The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity."
  (:refer-clojure :exclude [read send])
  (:require [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]))

(defn inbox
  "Read my unread messages, oldest first.

  Unread means addressed to me without :seon.message/received-tx;
  my.message/received! acknowledges one. Returns a vector of
  {:my.message/id string :my.message/content string :my.message/at instant},
  with :my.message/from when the sender is known.
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

(defn received!
  "Acknowledge a message addressed to me; it is then no longer unread.

  Writes :seon.message/received-tx, a ref to this acknowledging transaction,
  and returns the message with it. Unread means addressed to me without that
  fact. Acknowledging an acknowledged message is inert. A missing identity
  returns a flat :seon.error value. My connection and identity are supplied.

  Example:
  (my.message/received! \"example-message\")"
  {:malli/schema [:=> [:cat :seon.message/id :seon.db/connection :seon.agent/id]
                  [:or :seon.message/message :seon.error/value]]}
  [message-id connection agent-id]
  (message/received! message-id connection agent-id))

(defn send
  "Send a message now and return the written message with its identity.

  Returns the stored :seon.message/id, :seon.message/content and endpoint refs.
  Sending inside let or do delivers even when its return value is discarded.
  Optional :my.message/about is a nonempty string subject identity token, stored
  verbatim without looking up a target; omit it when there is no subject.
  Optional :my.message/assignment names the evaluation assigned for repair,
  independently of the subject. The sender is supplied from my agent identity:
  :seon.message/from alone marks an inside wake, never the subject or assignment.
  Handling is recorded when the turn settles. A recipient on a branch no host
  runs (every agent after a restart) is hosted first, through the cluster handle
  supplied as :seon.agent/context-source, so it wakes.

  Example:
  (my.message/send {:my.message/to \"root\" :my.message/content \"The verification passed.\"})"
  {:malli/schema [:=> [:cat :my.message/send-request]
                  [:or :seon.message/message :seon.error/value]]}
  [{cluster :seon.agent/context-source connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (let [message (dissoc request :seon.db/connection :seon.agent/id :seon.agent/context-source)
        sent (message/send! message connection agent-id)
        home (:seon.message/home sent)]
    ;; a recipient admitted on a listed branch that no host runs is resumed:
    ;; hosted on the cluster roster (its armer derives the message), then written to
    (if-not (and cluster home)
      sent
      (if-not (contains? (registry/roster (:seon.store/store cluster)) home)
        ;; a home the store roster no longer lists can never be hosted again
        (assoc sent :seon.cluster.store/branch-absent home
               :seon.error/message "The recipient's home branch is no longer in the store; the message was not sent.")
        (let [host ((requiring-resolve 'seon.cluster.host/host!)
                    {:seon.turn.loop/cluster cluster :seon.agent/branch home
                     :seon.agent/id (:my.message/to request) :seon.ns/name 'user})]
          (if (:seon.error/at host) host (message/send! message connection agent-id)))))))

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
