(ns my.message
  "The inter-agent message protocol.

  A message is a durable addressed fact. Return `send` values from a run form
  when another agent needs information or work; the run loop records and
  delivers them through the ordinary message path."
  (:refer-clojure :exclude [read send])
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.message.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Reads
;;; ---------------------------------------------------------------------------

(def ^:private message-selector
  '[:seon.cluster.message/id
    :seon.cluster.message/content
    :seon.cluster.message/at
    :seon.cluster.message/ordinal
    {:seon.cluster.message/to [:seon.cluster.agent/id]}
    {:seon.cluster.message/from [:seon.cluster.agent/id]}
    {:seon.cluster.message/caused-by [:seon.cluster.message/id]}
    :seon.cluster.message/about
    :my.message/reason])

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

(defn- endpoint-id
  [message endpoint]
  (get-in message [endpoint :seon.cluster.agent/id]))

(defn- admitted-message
  [message]
  (when message
    (cond-> (-> message
                (update :seon.cluster.message/to
                        (fn [endpoint]
                          [:seon.cluster.agent/id
                           (:seon.cluster.agent/id endpoint)])))
      (:seon.cluster.message/from message)
      (update :seon.cluster.message/from
              (fn [endpoint]
                [:seon.cluster.agent/id
                 (:seon.cluster.agent/id endpoint)]))

      (:seon.cluster.message/caused-by message)
      (update :seon.cluster.message/caused-by
              (fn [cause]
                [:seon.cluster.message/id
                 (:seon.cluster.message/id cause)]))

      (:seon.cluster.message/about message)
      (update :seon.cluster.message/about :db/id))))

(defn- listing-entry
  [message]
  (cond-> {:my.message/id (:seon.cluster.message/id message)
           :my.message/at (:seon.cluster.message/at message)
           :my.message/content (:seon.cluster.message/content message)}
    (endpoint-id message :seon.cluster.message/from)
    (assoc :my.message/from
           (endpoint-id message :seon.cluster.message/from))))

(defn- recipient-eid
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.cluster.agent/id ?agent-id]]
        database agent-id))

(defn- inbox-message-eids
  [database recipient]
  (db/q '[:find [?message ...]
          :in $ ?recipient
          :where [?message :seon.cluster.message/to ?recipient]]
        database recipient))

(defn- inbox*
  [database agent-id since]
  (let [recipient (recipient-eid database agent-id)]
    (if (error-value? recipient)
      recipient
      (let [source (if (some? since) (db/since database since) database)]
        (if (error-value? source)
          source
          (let [ids (inbox-message-eids source recipient)]
            (if (error-value? ids)
              ids
              (->> ids
                   (map #(db/pull database message-selector %))
                   (map listing-entry)
                   (sort-by (juxt :my.message/at :my.message/id))
                   vec))))))))

(defn inbox
  "List messages addressed to this agent; use `since` after a shown basis."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
     [:or :my.message/inbox :seon.error/value]]
    [:=> [:cat :my.message/inbox-options
          :seon.db/database-value :seon.cluster.agent/id]
     [:or :my.message/inbox :seon.error/value]]]}
  ([database agent-id]
   (inbox* database agent-id nil))
  ([options database agent-id]
   (inbox* database agent-id (:seon.db/since options))))

(defn read
  "Read a message by id when its full stored content is needed."
  {:malli/schema
   [:=> [:cat :seon.cluster.message/id :seon.db/database-value]
    [:or :seon.cluster.message/message :seon.error/value]]}
  [message-id database]
  (let [message (db/pull database message-selector
                         [:seon.cluster.message/id message-id])]
    (cond
      (error-value? message) message
      message (admitted-message message)
      :else
      {:seon.error/kind ::not-found
       :my.message/not-found message-id
       :seon.error/message (str "There is no message named " (pr-str message-id) ".")
       :seon.error/data {:seon.cluster.message/id message-id}})))

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
     :my.message/no-recipient true
     :seon.error/message
     "send needs the id of the agent to message, as a string."}

    (or (not (string? content)) (str/blank? content))
    {:seon.error/kind ::no-content
     :my.message/no-content true
     :seon.error/message
     "send needs the message to deliver, as a string."}

    (and about?
         (or (not (string? about)) (str/blank? about)))
    {:seon.error/kind ::no-about
     :my.message/no-about true
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
  Use `send` when another agent needs a question, an answer, or a bounded
  assignment; use it as a form result and return a vector to send several
  messages."
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
     :my.message/no-recipient true
     :seon.error/message
     "decline needs the id of the assigning agent, as a string."}

    (or (not (string? about)) (str/blank? about))
    {:seon.error/kind ::no-about
     :my.message/no-about true
     :seon.error/message
     "decline's about argument must be a non-blank identity string."}

    (or (not (string? reason)) (str/blank? reason))
    {:seon.error/kind ::no-reason
     :my.message/no-reason true
     :seon.error/message
     "decline needs a reader-facing reason, as a string."}

    :else
    {:my.message/to to
     :my.message/about about
     :my.message/reason reason}))
