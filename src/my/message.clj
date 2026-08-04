(ns my.message
  "Return message values for the run loop to deliver to other agents."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The value constructors
;;; ---------------------------------------------------------------------------

(defn- send-value
  [to content about? about]
  (cond
    ;; agent-facing: a wrong TYPE is an agent mistake too, and
    ;; `str/blank?` on a non-string would throw out of the one place
    ;; that must not throw
    (or (not (string? to)) (str/blank? to))
    {:seon.error/kind ::no-recipient
     :seon.error/message
     "send needs the id of the agent to message, as a string."}

    (or (not (string? content)) (str/blank? content))
    {:seon.error/kind ::no-content
     :seon.error/message
     "send needs the message to deliver, as a string."}

    (and about?
         (or (not (string? about)) (str/blank? about)))
    {:seon.error/kind ::no-about
     :seon.error/message
     "send's about argument must be a non-blank identity string."}

    :else
    (cond-> {:my.message/to to
             :my.message/content content}
      about? (assoc :my.message/about about))))

(defn send
  "Address a message to another agent.

  Takes the recipient id, message text, and optional identity of the related
  fact. Returns a message value for the run loop to deliver, or a flat error.
  Use it as a form result; return a vector to send several messages."
  {:malli/schema
   [:function
    [:=> [:cat :my.message/to :my.message/content]
     [:or :my.message/message :seon.error/value]]
    [:=> [:cat :my.message/to :my.message/content :my.message/about]
     [:or :my.message/message :seon.error/value]]]}
  ([to content]
   (send-value to content false nil))
  ([to content about]
   (send-value to content true about)))

(defn decline
  "Decline an assignment and explain why to its sender.

  Takes the sender id, assignment identity, and reader-facing reason. Returns
  a declination value for the run loop to deliver, or a flat error. Use it when
  you cannot complete delegated work."
  {:malli/schema
   [:=> [:cat :my.message/to :my.message/about :my.message/reason]
    [:or :my.message/declination :seon.error/value]]}
  [to about reason]
  (cond
    (or (not (string? to)) (str/blank? to))
    {:seon.error/kind ::no-recipient
     :seon.error/message
     "decline needs the id of the assigning agent, as a string."}

    (or (not (string? about)) (str/blank? about))
    {:seon.error/kind ::no-about
     :seon.error/message
     "decline's about argument must be a non-blank identity string."}

    (or (not (string? reason)) (str/blank? reason))
    {:seon.error/kind ::no-reason
     :seon.error/message
     "decline needs a reader-facing reason, as a string."}

    :else
    {:my.message/to to
     :my.message/about about
     :my.message/reason reason}))
