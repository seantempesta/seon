(ns seon.cluster.message
  "Durable messages with permanent routing and settlement claims."
  (:refer-clojure :exclude [read send])
  (:require [seon.db :as db]
            [seon.repl :as repl]
            [seon.id :as id]
            [clojure.string :as str]
            [seon.render.route :as route]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The chain, walked from recorded connections
;;; ---------------------------------------------------------------------------

(defn trigger
  "The message the run `run-id` is answering, or nil.
  The `:open` transition commits this connection with the run itself,
  so the cause is equally available in temporal and non-temporal
  databases."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/id]
                  [:maybe :seon.message/id]]}
  [db run-id]
  (db/q '[:find ?message-id .
         :in $ ?run-id
         :where
         [?run :seon.turn/id ?run-id]
         [?run :seon.turn/trigger ?message]
         [?message :seon.message/id ?message-id]]
       db run-id))

(defn- caused-by
  "The message whose answering produced `message-id`, or nil."
  [db message-id]
  (db/q '[:find ?parent-id .
         :in $ ?message-id
         :where
         [?message :seon.message/id ?message-id]
         [?message :seon.message/caused-by ?parent]
         [?parent :seon.message/id ?parent-id]]
       db message-id))

(defn chain-depth
  "How many agent hops separate `message-id` from outside the population.
  Zero for a message nobody's turn produced — a human's, or the error
  recorder's — and one more for each answering hop after that. DERIVED
  by walking recorded refs; there is no counter to keep, which
  is why nothing can reset it wrongly and nothing can forget to
  increment it.

  The `seen` set is not defensive decoration about cycles that cannot
  happen (a transaction's trigger is always older than the transaction
  itself): it is what makes this function TOTAL against a database that
  a fixture, an import or a bug could hand it, in the one place whose
  job is to stop something running forever."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.message/id]
                  [:int {:min 0}]]}
  [db message-id]
  (loop [id message-id
         depth 0
         seen #{}]
    (if-let [parent (when-not (contains? seen id) (caused-by db id))]
      (recur parent (inc depth) (conj seen id))
      depth)))

(defn sender
  "The agent that sent `message-id`, or nil when it came from outside."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.message/id]
                  [:maybe :seon.agent/id]]}
  [db message-id]
  (db/q '[:find ?agent-id .
         :in $ ?message-id
         :where
         [?message :seon.message/id ?message-id]
         [?message :seon.message/from ?agent]
         [?agent :seon.agent/id ?agent-id]]
       db message-id))

(defn reply
  "The message a completed run owes the agent that asked for it, or nil.
  Delivery transaction input, so the reply goes through
  `delivery` like any other message and inherits the recipient check,
  the conversation bound and the derived id, rather than becoming a
  second way to make a message.

  THIS IS DERIVED, NOT REMEMBERED, and the live drive is the argument.
  Alice delegated correctly; bob read \"agent alice sent you: how many
  primes under 100?\", worked it out, and called
  `(my.turn/complete \"25\")` — which addressed nobody, because
  completion had no recipient. Alice waited forever for an answer that
  had already been computed. Asking the model to remember \"reply by
  message, THEN complete\" would be a protocol an agent can forget on
  any turn; the trigger already knows who asked, so the driver answers
  them.

  Nil when the trigger came from outside the agent population — a
  human's request completes to the human, and delivery to a human is a
  surface, not a message to an agent that does not exist.

  AND NIL WHEN THE TRIGGER IS ALREADY AN ANSWER TO US, which is the
  correction the second live drive forced. A REPLY IS NOT A QUESTION:
  alice delegated to bob, bob answered, and alice's own completion —
  the sentence meant for the human — was delivered straight back to
  bob, who opened a run to consider it. The conversation would have
  bounced to the chain limit and only the limit would have stopped it.

  The distinction is derivable from the chain that is already recorded:
  the trigger is an answer to us exactly when the message that CAUSED
  it was one of ours. Nothing new is stored, no reply flag, no
  in-reply-to attribute — the same `caused-by` walk the depth bound
  uses answers a second question. And it is the right terminator:
  a delegation ends when the delegator completes, so the chain bound
  goes back to being the backstop it should be rather than the thing
  that stops ordinary conversations."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.message/reply-request]
                  [:maybe :my.message/message]]}
  [db {:keys [:seon.message/trigger :my.turn/result
              :seon.agent/id]}]
  (let [asker (and trigger (sender db trigger))
        answering-us? (and trigger
                           (= id (some->> (caused-by db trigger)
                                          (sender db))))]
    (when (and asker (not answering-us?))
      {:my.message/to asker
       :my.message/content result
       :my.message/about trigger})))

;;; ---------------------------------------------------------------------------
;;; The delivery
;;; ---------------------------------------------------------------------------

(defn- agent-exists?
  [db agent-id]
  (some? (db/q '[:find ?agent .
                :in $ ?id
                :where [?agent :seon.agent/id ?id]]
              db agent-id)))



(defn- identified-entities
  "Entities whose installed unique identity attribute equals `identity`.

  An agent holds the ordinary string identity, never an entity id or an
  attribute-specific lookup ref. Resolve against every installed
  `:db.unique/identity` attribute, then make ambiguity a value instead of
  guessing which fact the agent meant."
  [db identity]
  (into
   #{}
   (keep
    (fn [[entity attribute]]
      (when (= :db.unique/identity
               (get-in db [:schema attribute :db/unique]))
        entity)))
   (db/q '[:find ?entity ?attribute
          :in $ ?identity
          :where [?entity ?attribute ?identity]]
        db identity)))

(defn- resolve-about
  [db identity]
  (let [entities (identified-entities db identity)]
    (cond
      (empty? entities)
      {:seon.error/kind :seon.message/unknown-about
       :seon.message/unknown-about identity
       :seon.error/message
       (str "There is no identified fact named \"" identity
            "\", so nothing was assigned about it.")
       :seon.error/data {:my.message/about identity}}

      (< 1 (count entities))
      {:seon.error/kind :seon.message/ambiguous-about
       :seon.message/ambiguous-about identity
       :seon.error/message
       (str "More than one identified fact is named \"" identity
            "\", so the assignment target is ambiguous.")
       :seon.error/data {:my.message/about identity}}

      :else
      {:seon.message/about (first entities)})))





(defn inbound-tx
  "Admit one outside message and mint its event identity at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.message/inbound-request]
                  :seon.message/inbound]}
  [database {:keys [:seon.agent/id :seon.message/inbound-content
                    :seon.config.eval.result/max-string]}]
  (cond
    (not (agent-exists? database id))
    {:seon.error/kind :seon.message/unknown-recipient :seon.message/unknown-recipient id
     :seon.error/message (str "There is no agent named " (pr-str id) ".")}
    (str/blank? inbound-content)
    {:seon.error/kind :seon.message/blank-content :seon.message/blank-content true
     :seon.error/message "A message must contain some text."}
    (> (count inbound-content) max-string)
    {:seon.error/kind :seon.message/content-too-large
     :seon.message/content-too-large (count inbound-content)
     :seon.error/message (str "The message exceeds the configured " max-string " character bound.")}
    :else
    [{:seon.message/id (id/id (random-uuid) 8)
      :seon.message/to [:seon.agent/id id]
      :seon.message/content inbound-content}]))

(defn delivery
  "Prepare durable messages; handling belongs to turn settlement."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [:my.message/value :my.message/value]
                        [:seon.agent/id :seon.agent/id]
                        [:seon.config.message/max-chain :seon.config.message/max-chain]
                        [:seon.message/trigger {:optional true} :seon.message/id]]]
                  :seon.message/delivery]}
  [database {:keys [:my.message/value :seon.message/trigger
                    :seon.config.message/max-chain] :as request}]
  (let [sender (:seon.agent/id request)
        candidates (if (vector? value) value [value])
        depth (if trigger (inc (chain-depth database trigger)) 1)]
    (cond
      (not (pos-int? max-chain))
      {:seon.message/rows []
       :seon.error/values [{:seon.error/kind :seon.message/no-limit :seon.message/no-limit true
                            :seon.error/message "Messaging requires a configured chain bound."}]}
      (> depth max-chain)
      {:seon.message/rows []
       :seon.error/values [{:seon.error/kind :seon.message/chain-limit :seon.message/chain-limit max-chain
                            :seon.error/message "The conversation reached its configured chain bound."}]}
      :else
      (reduce
       (fn [delivered candidate]
         (let [recipient (:my.message/to candidate)
               about-id (:my.message/about candidate)
               about (when about-id (resolve-about database about-id))
               failure (cond
                         (not (agent-exists? database recipient))
                         {:seon.error/kind :seon.message/unknown-recipient
                          :seon.message/unknown-recipient recipient
                          :seon.error/message (str "There is no agent named " (pr-str recipient) ".")}
                         (:seon.error/kind about) about)]
           (if failure
             (update delivered :seon.error/values conj failure)
             (let [subject (:seon.message/about about)
                   row (cond-> {:seon.message/id (or (:seon.message/id candidate)
                                                      (id/id))
                                :seon.message/to [:seon.agent/id recipient]
                                :seon.message/from [:seon.agent/id sender]
                                :seon.message/content (or (:my.message/content candidate)
                                                          (:my.message/reason candidate))}
                         trigger (assoc :seon.message/caused-by [:seon.message/id trigger])
                         subject (assoc :seon.message/about subject))]
               (update delivered :seon.message/rows conj row)))))
       {:seon.message/rows [] :seon.error/values []}
       candidates))))

(defn- agent-reference-id
  [database reference]
  (or (:seon.agent/id reference)
      (let [entity-id
        (cond
          (map? reference)
          (or (when-let [entry (find reference :seon.agent/id)]
                [:seon.agent/id (val entry)])
              (:db/id reference))

          :else reference)]
        (when (and database entity-id)
          (let [result
                (db/q '[:find ?id .
                        :in $ ?agent
                        :where [?agent :seon.agent/id ?id]]
                      database entity-id)]
            (when-not (:seon.error/kind result)
              result))))))

(defn- identity-reference
  [database reference]
  (when (and database reference)
    (if (and (vector? reference)
             (= 2 (count reference))
             (qualified-keyword? (first reference)))
      reference
      (let [entity-id (if (map? reference)
                        (or (:db/id reference)
                            (some (fn [attribute]
                                    (when-let [entry (find reference attribute)]
                                      [attribute (val entry)]))
                                  (db/identity-attributes database)))
                        reference)
            entity (when entity-id
                     (db/pull database
                              (into [:db/id]
                                    (db/identity-attributes database))
                              entity-id))]
        (some (fn [attribute]
                (when-let [entry (find entity attribute)]
                  [attribute (val entry)]))
              (db/identity-attributes database))))))

(defn- data-link
  [label identity-ref]
  (when identity-ref
    [:a {:href (route/path ::route/data
                           {}
                           {:entity (pr-str identity-ref)})}
     label]))

(defn- message-instant [database message]
  (when database
    (db/q '[:find ?instant . :in $ ?id
            :where [?message :seon.message/id ?id]
                   [?message :seon.message/to _ ?tx]
                   [?tx :db/txInstant ?instant]]
          database (:seon.message/id message))))

(defn format-ai
  "Format one message as the terminal sentence it was.

  The authored source projection calls this function after reading the
  durable message, so the stored evaluation value is the exact sentence.

  ABSENCE OF `from` IS THE OTHER HALF OF THE CONTRACT and it is read
  here exactly as the schema states it: a message with no sender came
  from outside the agent population — the human, or the system's own
  error recorder — so this says so rather than inventing a sender. That
  is the same rule the retired prompt prose applied, moved to the
  family that owns the fact.

  Render preparation may supply a ref as an entity-id map, identity map, or
  lookup ref. Naming an agent resolves each shape against the database value
  riding on the unit. A present ref that does not resolve stays visibly
  unresolved; only an absent `from` means outside the cluster."
  {:malli/schema [:=> [:cat [:or :seon.render/unit :seon.error/value]]
                  [:or :nil :string :seon.error/value]]}
  [unit]
  (if (:seon.error/kind unit)
    unit
    (let [database (get unit :seon.db/db)
        content (get unit :seon.message/content)
        from-ref (get unit :seon.message/from)
        to-ref (get unit :seon.message/to)]
    (when content
      (let [from (agent-reference-id database from-ref)
            to (agent-reference-id database to-ref)]
        (str (cond
               from (str "Agent " from " said")
               from-ref (str "An unresolved sender " (pr-str from-ref) " said")
               :else "From outside this cluster")
             (cond
               to (str " to " to)
               to-ref (str " to unresolved recipient " (pr-str to-ref)))
             ": " content))))))

(defn render-ai
  "Read this message as data, including its sender, time, and content."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/source]]}
  [unit]
  (when-let [id (get unit :seon.message/id)]
    (str ";; I should read this message and decide how to respond.\n"
         (pr-str (list 'my.message/read {:my.message/id id})))))

(defn render-html
  "`:seon.render/html` — one message, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (let [database (get unit :seon.db/db)
        content (get unit :seon.message/content)
        from-ref (get unit :seon.message/from)
        to-ref (get unit :seon.message/to)
        at (message-instant database unit)
        about (identity-reference database (get unit :seon.message/about))
        caused-by-id (some-> (get unit :seon.message/caused-by)
                             (get :seon.message/id))
        caused-by-ref (when caused-by-id [:seon.message/id caused-by-id])]
    (when content
      (let [from (agent-reference-id database from-ref)
            to (agent-reference-id database to-ref)]
        (cond->
         [:article {:class "seon-family-entry seon-message-entry"}
          (cond->
           [:header {:class "seon-message-meta"}
            [:span {:class "seon-message-direction"}
             [:span {:class "seon-message-from"}
              (cond
                from from
                from-ref "Unresolved sender"
                :else "Outside this cluster")]
             [:span {:class "seon-message-arrow" :aria-hidden "true"} "→"]
             [:span {:class "seon-message-to"}
              (cond
                to to
                to-ref "Unresolved recipient"
                :else "No recipient")]]]
            at
            (conj (let [instant (.toInstant ^java.util.Date at)
                        local (.atZone instant (java.time.ZoneId/systemDefault))
                        minutes (max 0 (quot (- (System/currentTimeMillis) (.getTime ^java.util.Date at)) 60000))]
                    [:time {:class "seon-message-at" :datetime (str instant)
                            :title (str local)
                            :data-attr:title (str "new Date('" instant "').toLocaleString()")}
                     (cond (< minutes 1) "just now"
                           (< minutes 60) (str minutes " min ago")
                           (< minutes 1440) (str (quot minutes 60) " hr ago")
                           :else (str (quot minutes 1440) " days ago"))])))
          [:p {:class "seon-message-content" :style {:white-space "pre-wrap"}} content]
          (let [stored (when (and database (:seon.message/id unit))
                         (db/pull database [:db/id] [:seon.message/id (:seon.message/id unit)]))
                handled? (when (:db/id stored)
                           (db/q '[:find ?turn . :in $ ?message
                                   :where [?turn :seon.turn/handled ?message]]
                                 database (:db/id stored)))]
            [:p {:class (str "seon-message-state " (if handled? "is-handled" "is-unread"))}
             "● " (cond handled? "handled"
                        stored "unread"
                        :else "status unavailable")])]
          (or about caused-by-ref)
          (conj
           (cond->
            [:p {:class "seon-message-links"}]
             about
             (conj [:span {:class "seon-message-about"}
                    (data-link (str "About " (second about)) about)])
             (and about caused-by-ref) (conj " · ")
             caused-by-ref
             (conj [:span {:class "seon-message-caused-by"}
                    (data-link (str "caused-by " caused-by-id)
                               caused-by-ref)]))))))))

;;; ---------------------------------------------------------------------------
;;; The inbox unit
;;;
;;; `:seon.message/to` is reached two ways. A walk from one message
;;; hands the single recipient reference; the agent's declared
;;; `:seon.message/_to` unit hands every message addressed to that
;;; agent. One producer per projection answers both, because both are the same
;;; question asked from the two ends of one ref.
;;;
;;; A forward inbox attribute supplies a recipient reference; its reverse
;;; supplies acquired message maps. Both input alternatives are declared.
;;; ---------------------------------------------------------------------------

(defn render-inbox-ai
  "Read each acquired message once through its entity's AI pair."
  {:malli/schema [:=> [:cat [:or :seon.message/to :seon.message/inbox-unit]] :seon.render/source]}
  [recipient-or-inbox]
  (if (and (sequential? recipient-or-inbox)
           (every? map? recipient-or-inbox))
    (str/join "\n\n"
              (keep render-ai
                    (sort-by :seon.message/id recipient-or-inbox)))
    (str ";; I should follow incoming messages with a reverse-ref pull on myself.\n"
         (repl/source-text
          (list 'seon.db/pull
                      (list 'quote
                            '[{:seon.message/_to
                               [:seon.message/id :seon.message/content
                                {:seon.message/from [:seon.agent/id]}]}])
                      recipient-or-inbox)))))

(defn- message-order [database message]
  [(if-let [instant (message-instant database message)] (.getTime ^java.util.Date instant) 0)
   (:seon.message/id message)])

(defn inbox-html
  "Render every message addressed to one agent, oldest first.

  Each message reaches the one message renderer, so a message in this list
  and the same message rendered alone state exactly the same facts."
  {:malli/schema [:=> [:cat :seon.message/inbox-unit
                       :seon.db/database-value]
                  :seon.render/hiccup]}
  [messages database]
  (let [entries (into []
                      (keep #(render-html (assoc % :seon.db/db database)))
                      (sort-by #(message-order database %) messages))]
    (if (seq entries)
      (into [:section {:class "seon-family-entry seon-message-inbox"}
             [:h2 (str "Messages (" (count entries) ")")]]
            entries)
      [:section {:class "seon-family-entry seon-message-inbox"}
       [:h2 "Messages (0)"]
       [:p {:class "seon-message-inbox-empty"}
        "No message is addressed to this agent yet."]])))

(defn render-inbox-html
  "`:seon.render/html` — the same messages as reader-facing entries.

  The reverse unit hands every message it acquired and renders them through
  [[inbox-html]]; a walk from one message hands that message's single
  recipient reference and renders it as the reference it is."
  {:malli/schema [:=> [:cat [:or :seon.message/to :seon.message/inbox-unit] :seon.db/database-value]
                  :seon.render/hiccup]}
  [recipient-or-inbox database]
  ;; A LOOKUP REF IS ALSO SEQUENTIAL, so the collection branch is the one
  ;; whose every element is an entity map, never merely a vector.
  (if (and (sequential? recipient-or-inbox)
           (every? map? recipient-or-inbox))
    (inbox-html (vec recipient-or-inbox) database)
    (let [recipient (identity-reference database recipient-or-inbox)]
      [:section {:class "seon-family-entry seon-message-inbox"}
       [:p {:class "seon-kicker"} "Addressed to"]
       (if recipient
         [:p (data-link (str (second recipient)) recipient)]
         [:p {:class "seon-message-inbox-empty"}
          "The recipient could not be resolved."])])))

(def ^:private message-selector
  '[:seon.message/id
    :seon.message/content
    {:seon.message/to [:seon.agent/id]}
    {:seon.message/from [:seon.agent/id]}
    {:seon.message/caused-by [:seon.message/id]}
    :seon.message/about
    :my.message/reason])

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

(defn- endpoint-id
  [message endpoint]
  (get-in message [endpoint :seon.agent/id]))

(defn- admitted-message
  [message]
  (when message
    (cond-> (-> message
                (update :seon.message/to
                        (fn [endpoint]
                          [:seon.agent/id
                           (:seon.agent/id endpoint)])))
      (:seon.message/from message)
      (update :seon.message/from
              (fn [endpoint]
                [:seon.agent/id
                 (:seon.agent/id endpoint)]))

      (:seon.message/caused-by message)
      (update :seon.message/caused-by
              (fn [cause]
                [:seon.message/id
                 (:seon.message/id cause)]))

      (:seon.message/about message)
      (update :seon.message/about :db/id))))

(defn- listing-entry
  [database message]
  (cond-> {:my.message/id (:seon.message/id message)
           :my.message/at (message-instant database message)
           :my.message/content (:seon.message/content message)}
    (endpoint-id message :seon.message/from)
    (assoc :my.message/from
           (endpoint-id message :seon.message/from))))

(defn- recipient-eid
  [database agent-id]
  (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.agent/id ?agent-id]]
        database agent-id))

(defn- inbox-message-eids
  [database current recipient]
  (db/q '[:find [?message ...]
          :in $ $current ?recipient
          :where [?message :seon.message/to ?recipient]
                 (not [$current _ :seon.turn/handled ?message])]
        database current recipient))

(defn- inbox*
  [database agent-id since]
  (let [recipient (recipient-eid database agent-id)]
    (if (error-value? recipient)
      recipient
      (let [source (if (some? since) (db/since database since) database)]
        (if (error-value? source)
          source
          (let [ids (inbox-message-eids source database recipient)]
            (if (error-value? ids)
              ids
              (->> ids
                   (map #(db/pull database message-selector %))
                   (map #(listing-entry database %))
                   (sort-by (juxt :my.message/at :my.message/id))
                   vec))))))))

(defn inbox
  "List messages addressed to this agent; use `since` after a shown basis."
  {:malli/schema
   [:function
    [:=> [:cat :my.message/inbox-request]
     [:or :my.message/inbox :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.agent/id]
     [:or :my.message/inbox :seon.error/value]]
    [:=> [:cat :my.message/inbox-options
          :seon.db/database-value :seon.agent/id]
     [:or :my.message/inbox :seon.error/value]]]}
  ([request]
   (inbox* (:seon.db/db request)
           (:seon.agent/id request)
           (:seon.db/since request)))
  ([database agent-id]
   (inbox* database agent-id nil))
  ([options database agent-id]
   (inbox* database agent-id (:seon.db/since options))))

(defn read
  "Read a message by id when its full stored content is needed."
  {:malli/schema
   [:=> [:cat :seon.message/id :seon.db/database-value]
    [:or :seon.message/message :seon.error/value]]}
  [message-id database]
  (let [message (db/pull database message-selector
                         [:seon.message/id message-id])]
    (cond
      (error-value? message) message
      message (admitted-message message)
      :else
      {:seon.error/kind :my.message/not-found
       :my.message/not-found message-id
       :seon.error/message (str "There is no message named " (pr-str message-id) ".")
       :seon.error/data {:seon.message/id message-id}})))

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
    {:seon.error/kind :my.message/no-recipient
     :my.message/no-recipient true
     :seon.error/message
     "send needs the id of the agent to message, as a string."}

    (or (not (string? content)) (str/blank? content))
    {:seon.error/kind :my.message/no-content
     :my.message/no-content true
     :seon.error/message
     "send needs the message to deliver, as a string."}

    (and about?
         (or (not (string? about)) (str/blank? about)))
    {:seon.error/kind :my.message/no-about
     :my.message/no-about true
     :seon.error/message
     "send's about argument must be a non-blank identity string."}

    :else
    (cond-> {:seon.message/id (id/id)
             :my.message/to to
             :my.message/content content}
      about? (assoc :my.message/about about))))

(defn send
  "Construct input for the system's delivery transaction.

  Takes the recipient id, message text, and optional identity of the related
  fact. This is transaction input, not a write. Use `my.message/send` to
  write from an agent, or `send!` with an explicit connection and sender."
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

(defn- send-call
  [database candidate agent-id]
  (let [limits (db/q '[:find [?limit ...]
                       :where [?cluster :seon.cluster/config ?config]
                              [?config :seon.config.message/max-chain ?limit]] database)
        limit (when (= 1 (count limits)) (first limits))
        _ (when-not (pos-int? limit)
            (throw (ex-info "Messaging requires one configured chain bound."
                            {:seon.error/kind :seon.message/no-limit
                             :seon.message/no-limit true
                             :seon.error/message "Messaging requires one configured chain bound."
                             :seon.error/data {:seon.config.message/max-chain limits}})))
        run-id (db/q '[:find ?id . :in $ ?agent-id
                       :where [?agent :seon.agent/id ?agent-id]
                              [?turn :seon.turn/agent ?agent]
                              [?turn :seon.turn/id ?id]
                              (not [?turn :seon.turn/closed-tx _])]
                     database agent-id)
        cause (when run-id (trigger database run-id))
        result (delivery database
                         (cond-> {:my.message/value candidate
                                  :seon.agent/id agent-id
                                  :seon.config.message/max-chain limit}
                           cause (assoc :seon.message/trigger cause)))]
    (if-let [failure (first (:seon.error/values result))]
      (throw (ex-info (:seon.error/message failure) failure))
      (:seon.message/rows result))))

(defn send!
  "Write a message and its permanent recipient atomically; return the stored message.

  The writer resolves recipients, the active turn's cause, and the configured
  conversation bound against its current database, just as it resolves about."
  {:malli/schema [:=> [:cat :my.message/message :seon.db/connection :seon.agent/id]
                  [:or :seon.message/message :seon.error/value]]}
  [request connection agent-id]
  (let [candidate (send-value (:my.message/to request) (:my.message/content request)
                              (some? (:my.message/about request)) (:my.message/about request))]
    (if (error-value? candidate)
      candidate
      (let [result (db/transact!
                    connection
                    {:tx-data [[:db.fn/call #'send-call candidate agent-id]]
                     :tx-meta {:seon.db/user [:seon.agent/id agent-id]}})]
        (if (error-value? result) result
            (read (:seon.message/id candidate) (:db-after result)))))))

(defn decline
  "Decline an assignment and explain why to its sender.

  Takes the sender id, assignment identity, and reader-facing reason. Returns
  transaction input or a flat error; `my.message/decline` writes it directly."
  {:malli/schema
   [:=> [:cat :my.message/to :my.message/about :my.message/reason]
    [:or :my.message/declination :seon.error/value]]}
  [to about reason]
  (cond
    (or (not (string? to)) (str/blank? to))
    {:seon.error/kind :my.message/no-recipient
     :my.message/no-recipient true
     :seon.error/message
     "decline needs the id of the assigning agent, as a string."}

    (or (not (string? about)) (str/blank? about))
    {:seon.error/kind :my.message/no-about
     :my.message/no-about true
     :seon.error/message
     "decline's about argument must be a non-blank identity string."}

    (or (not (string? reason)) (str/blank? reason))
    {:seon.error/kind :my.message/no-reason
     :my.message/no-reason true
     :seon.error/message
     "decline needs a reader-facing reason, as a string."}

    :else
    {:seon.message/id (id/id)
     :my.message/to to
     :my.message/about about
     :my.message/reason reason}))
