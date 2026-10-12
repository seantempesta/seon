(ns seon.cluster.message
  "Message values offered through Flow and stored by their recipient."
  (:refer-clojure :exclude [read send])
  (:require [clojure.core.async :as async]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.id :as id]
            [clojure.string :as str]
            [seon.render.route :as route]
            [seon.schema.edn :as schema.edn]
            [seon.task :as task]))

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



(defn inbound-tx
  "Admit one outside message and mint its event identity at the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.message/inbound-request]
                  :seon.message/inbound]}
  [database {:keys [:seon.agent/id :seon.message/inbound-content
                    :seon.config.eval.result/max-string]}]
  (cond
    (not (agent-exists? database id))
    {:seon.message/unknown-recipient id
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/inbound-tx
     :seon.error/message (str "There is no agent named " (pr-str id) ".")}
    (str/blank? inbound-content)
    {:seon.message/blank-content true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/inbound-tx
     :seon.error/message "A message must contain some text."}
    (> (count inbound-content) max-string)
    {:seon.message/content-too-large (count inbound-content)
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/inbound-tx
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
       :seon.error/values [{:seon.message/no-limit true
                            :seon.message/refused-chain-limit (pr-str max-chain)
                            :seon.error/at (java.util.Date.)
                            :seon.error/layer :seon.message/delivery
                            :seon.error/operation 'seon.cluster.message/delivery
                            :seon.error/message "Messaging requires a configured chain bound."}]}
      (> depth max-chain)
      {:seon.message/rows []
       :seon.error/values [{:seon.message/chain-limit max-chain
                            :seon.error/at (java.util.Date.)
                            :seon.error/layer :seon.message/delivery
                            :seon.error/operation 'seon.cluster.message/delivery
                            :seon.error/message "The conversation reached its configured chain bound."}]}
      :else
      (reduce
       (fn [delivered candidate]
         (let [recipient (:my.message/to candidate)
               about-id (:my.message/about candidate)
               failure (cond
                         (not (agent-exists? database recipient))
                         {:seon.message/unknown-recipient recipient
                          :seon.error/at (java.util.Date.)
                          :seon.error/layer :seon.message/delivery
                          :seon.error/operation 'seon.cluster.message/delivery
                          :seon.error/message (str "There is no agent named " (pr-str recipient) ".")}
                         :else nil)]
           (if failure
             (update delivered :seon.error/values conj failure)
             (let [subject about-id
                   entity (cond-> {:seon.message/id (or (:seon.message/id candidate)
                                                      (id/id))
                                :seon.message/to [:seon.agent/id recipient]
                                :seon.message/from [:seon.agent/id sender]
                                :seon.message/content (or (:my.message/content candidate)
                                                          (:my.message/reason candidate))}
                         trigger (assoc :seon.message/caused-by [:seon.message/id trigger])
                         subject (assoc :seon.message/about subject)
                         (:my.message/assignment candidate)
                         (assoc :seon.message/assignment (:my.message/assignment candidate))
                         (:my.message/reason candidate)
                         (assoc :my.message/reason (:my.message/reason candidate))
                         ;; a task's end message: how it ended and, ended green, the merge task it proposed
                         true (merge (select-keys candidate [:seon.task/end :seon.task/merge])))]
               (update delivered :seon.message/rows conj entity)))))
       {:seon.message/rows [] :seon.error/values []}
       candidates))))

(defn- agent-reference-id
  {:malli/schema [:=> [:cat [:or :nil :seon.db/database-value] :seon.schema/value]
                  [:or :nil :seon.agent/id]]}
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
            (when-not (db/read-refusal? result)
              result))))))

(defn- data-link
  [label identity-ref]
  (when identity-ref
    [:a {:href (route/path ::route/data
                           {}
                           {:entity (pr-str identity-ref)})}
     label]))

(defn- message-instant [database message]
  ;; An unstored message (no id) has no commit instant. Querying with a nil id
  ;; is a mistyped read the owner refuses as a value (9b5eefbb7), which the
  ;; callers would otherwise cast to a Date.
  (when (and database (:seon.message/id message))
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
  from outside the agent population — a human — so this says so rather than
  inventing a sender. That
  is the same rule the retired prompt prose applied, moved to the
  family that owns the fact.

  Render preparation may supply a ref as an entity-id map, identity map, or
  lookup ref. Naming an agent resolves each shape against the database value
  riding on the unit. A present ref that does not resolve stays visibly
  unresolved; only an absent `from` means outside the cluster."
  {:malli/schema [:=> [:cat [:or :seon.render/unit :seon.error/value]]
                  [:or :nil :string :seon.error/value]]}
  [unit]
  (if (db/read-refusal? unit)
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

(defn- received-tx
  "The acknowledging transaction of one message: the value's own fact, else the
  database's, else nil (unread)."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :map] [:maybe :int]]}
  [database message]
  (let [held (:seon.message/received-tx message)
        stored (when (and (nil? held) database (:seon.message/id message))
                 (db/q '[:find ?tx . :in $ ?id
                         :where [?message :seon.message/id ?id]
                                [?message :seon.message/received-tx ?tx]]
                       database (:seon.message/id message)))]
    (cond
      (map? held) (:db/id held)
      (int? held) held
      (int? stored) stored)))

(defn message-view
  "One message as tight data seen by `viewer`, from the value it was handed.

  The recipient sees who sent it, and either its acknowledging transaction or
  the operation that acknowledges it; the sender sees whom it went to and
  whether it was received; anyone else sees both endpoints and receipt."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] [:maybe :seon.agent/id] :map] :map]}
  [database viewer message]
  (let [id (:seon.message/id message)
        endpoint (fn [attribute]
                   (when-let [reference (get message attribute)]
                     (or (agent-reference-id database reference) reference)))
        from (endpoint :seon.message/from)
        to (endpoint :seon.message/to)
        tx (received-tx database message)
        recipient? (and viewer (= viewer to))
        at (message-instant database message)
        cause (some-> (:seon.message/caused-by message)
                      (as-> c (if (map? c) (:seon.message/id c) (second c))))
        entries (cond-> [[:seon.message/id id]]
                  (and from (not= viewer from)) (conj [:seon.message/from from])
                  (and to (not recipient?)) (conj [:seon.message/to to])
                  at (conj [:seon.message/at at])
                  ;; content is text for the reader: its lines print unescaped
                  true (conj [:seon.message/lines (str/split-lines (str (:seon.message/content message)))])
                  (:seon.message/about message) (conj [:seon.message/about (:seon.message/about message)])
                  (:seon.message/assignment message) (conj [:seon.message/assignment (:seon.message/assignment message)])
                  (:my.message/reason message) (conj [:my.message/reason (:my.message/reason message)])
                  cause (conj [:seon.message/caused-by cause])
                  (and recipient? tx) (conj [:seon.message/received-tx tx])
                  (and recipient? (not tx)) (conj [:seon.render/operations
                                                   [(list 'my.message/received! id)]])
                  (not recipient?) (conj [:seon.message/received? (some? tx)]))]
    (apply array-map (apply concat entries))))

(defn render-ai
  "Show this message as data from the viewing agent's side.

  Its recipient is offered `(my.message/received! id)` until it acknowledges;
  its sender sees whether it was received. Renders the value it was handed,
  so a reply shows the message its write returned."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :map]]}
  [unit]
  (when (get unit :seon.message/id)
    (message-view (get unit :seon.db/db) (get unit :seon.agent/id) unit)))

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
        about (get unit :seon.message/about)
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
                received? (some? (received-tx database unit))]
            [:p {:class (str "seon-message-state " (if received? "is-received" "is-unread"))}
             "● " (cond received? "received"
                        stored "unread"
                        :else "status unavailable")])]
          (or about caused-by-ref)
          (conj
           (cond->
            [:p {:class "seon-message-links"}]
             about
             (conj [:span {:class "seon-message-about"}
                    (str "About " about)])
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
;;; The forward route supplies a recipient reference; its reverse
;;; supplies acquired message maps. Both input alternatives are declared.
;;; ---------------------------------------------------------------------------

(defn- message-order [database message]
  [(if-let [instant (message-instant database message)] (.getTime ^java.util.Date instant) 0)
   (:seon.message/id message)])

(defn inbox-form
  "Read the recipient's unread messages, oldest first, through `my.message/inbox`.

  Unread derives from the acknowledgement facts, so the read's evidence names
  `:seon.message/to` and `:seon.message/received-tx`: a new message or a new
  acknowledgement changes it. Each entry offers `(my.message/received! id)`;
  acknowledged messages are one `(my.message/read ...)` away. A recipient
  handed as an entity id is resolved in the form itself."
  {:malli/schema [:=> [:cat :seon.message/to] :seon.render/read-form]}
  [recipient]
  (list 'my.message/inbox
        {:seon.agent/id
         (cond
           (and (vector? recipient) (= :seon.agent/id (first recipient))) (second recipient)
           (and (map? recipient) (:seon.agent/id recipient)) (:seon.agent/id recipient)
           :else (list :seon.agent/id
                       (list 'seon.db/pull [:seon.agent/id]
                             (if (map? recipient) (:db/id recipient) recipient))))}))

(declare inbox*)

(defn render-inbox-ai
  "Show an inbox from the agent's side, as data.

  An agent's own `:seon.message/_to` concern hands its recipient reference:
  the render reads its unread messages, oldest first, as [[inbox-form]]
  finds them, each its sender, its content as lines and
  `(my.message/received! id)`; order carries the time, so the instant is
  left to the read. The
  read names `:seon.message/to` and `:seon.message/received-tx`, so a new,
  changed or acknowledged message changes the shown read.
  Handed the acquired messages, it shows the unread oldest first and the
  received count."
  {:malli/schema [:=> [:cat [:or :seon.message/to :seon.message/inbox-unit] :seon.db/database-value]
                  [:or [:vector :seon.message/unread-entry] :seon.message/inbox-view :seon.error/value]]}
  [recipient-or-inbox database]
  ;; A LOOKUP REF IS ALSO SEQUENTIAL (as in `render-inbox-html`).
  (if (and (sequential? recipient-or-inbox) (every? map? recipient-or-inbox))
    (let [ordered (sort-by #(message-order database %) recipient-or-inbox)
          views (map #(message-view database (agent-reference-id database (:seon.message/to %)) %)
                     ordered)
          received? #(or (contains? % :seon.message/received-tx) (:seon.message/received? %))]
      #:seon.message{:unread (vec (remove received? views))
                     :received (count (filter received? views))})
    (if-let [agent-id (agent-reference-id database recipient-or-inbox)]
      (let [unread (inbox* database agent-id nil)]
        (if (vector? unread)
          (mapv #(-> % (dissoc :my.message/at :my.message/content)
                     (assoc :seon.message/lines (str/split-lines (:my.message/content %))))
                unread)
          unread))
      (seon.error.refusal/diagnostic
       (java.util.Date.) :seon.message/delivery 'seon.cluster.message/render-inbox-ai
       {:seon.error/message "The inbox's recipient names no agent."
        :seon.error/member :seon.message/to :seon.error/expected :seon.agent/id
        :seon.error/offending recipient-or-inbox}))))

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

(declare recipient-eid inbox-message-eids)

(defn- received-button
  "The inbox's offered `(my.message/received! id)` as a button: it posts the
  acknowledgement to the agent's context route, which commits it."
  {:malli/schema [:=> [:cat :seon.agent/id :seon.message/id] :seon.render/hiccup]}
  [agent-id message-id]
  [:form {:class "seon-message-received"
          (keyword "data-on:submit")
          (str "@post('" (route/path :seon.render.route/agent-context {:id agent-id})
               "', {contentType:'form'})")}
   [:input {:type "hidden" :name "action" :value "received"}]
   [:input {:type "hidden" :name "message-id" :value message-id}]
   [:button {:type "submit"} "received"]])

(defn render-inbox-html
  "`:seon.render/html` — the same messages the AI face shows, as reader-facing
  entries.

  The reverse unit hands every message it acquired and renders them through
  [[inbox-html]]. A recipient reference (the agent's own
  `:seon.message/_to` concern) shows that agent's unread messages, oldest
  first, exactly as [[render-inbox-ai]] reads them, each with its
  `(my.message/received! id)` as a button."
  {:malli/schema [:=> [:cat [:or :seon.message/to :seon.message/inbox-unit] :seon.db/database-value]
                  :seon.render/hiccup]}
  [recipient-or-inbox database]
  ;; A LOOKUP REF IS ALSO SEQUENTIAL, so the collection branch is the one
  ;; whose every element is an entity map, never merely a vector.
  (if (and (sequential? recipient-or-inbox)
           (every? map? recipient-or-inbox))
    (inbox-html (vec recipient-or-inbox) database)
    (let [agent-id (agent-reference-id database recipient-or-inbox)
          recipient (when agent-id (recipient-eid database agent-id))
          eids (when (int? recipient) (inbox-message-eids database database recipient))
          unread (when (sequential? eids)
                   (sort-by #(message-order database %) (db/pull-many database '[*] (vec eids))))]
      (if (and agent-id (sequential? eids))
        (into [:section {:class "seon-family-entry seon-message-inbox"}
               [:h2 (str "Unread messages (" (count unread) ")")]]
              (map (fn [message]
                     [:div {:class "seon-message-unread"}
                      (render-html (assoc message :seon.db/db database))
                      (received-button agent-id (:seon.message/id message))]))
              unread)
        [:section {:class "seon-family-entry seon-message-inbox"}
         [:p {:class "seon-message-inbox-empty"}
          "The recipient's inbox could not be read."]]))))

(def ^:private message-selector
  '[:seon.message/id
    :seon.message/content
    {:seon.message/to [:seon.agent/id]}
    {:seon.message/from [:seon.agent/id]}
    {:seon.message/caused-by [:seon.message/id]}
    :seon.message/about
    :seon.message/assignment
    :seon.message/received-tx
    :my.message/reason
    :seon.task/end
    :seon.task/merge])

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
                 (:seon.message/id cause)])))))

(defn- listing-entry
  [database message]
  ;; an unread entry offers its acknowledgement, as message-view does
  (cond-> {:my.message/id (:seon.message/id message)
           :my.message/at (message-instant database message)
           :my.message/content (:seon.message/content message)
           :seon.render/operations [(list 'my.message/received! (:seon.message/id message))]}
    (endpoint-id message :seon.message/from)
    (assoc :my.message/from
           (endpoint-id message :seon.message/from))
    ;; a task's end message: its references, from which the reader derives the ended value
    (:seon.task/end message)
    (merge (select-keys message [:seon.message/about :seon.task/end :seon.task/merge]))))

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
                 (not [$current ?message :seon.message/received-tx _])]
        database current recipient))

(defn- inbox*
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id
                       [:or :nil :seon.db/basis-t]]
                  [:or :my.message/inbox :seon.db/error-result]]}
  [database agent-id since]
  (let [recipient (recipient-eid database agent-id)]
    (if (db/read-refusal? recipient)
      recipient
      (let [source (if (some? since) (db/since database since) database)]
        (if (db/read-refusal? source)
          source
          (let [ids (inbox-message-eids source database recipient)]
            (if (db/read-refusal? ids)
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
      (db/read-refusal? message) message
      message (admitted-message message)
      :else
      {:my.message/not-found message-id
       :seon.error/at (java.util.Date.)
       :seon.error/layer :seon.message/delivery
       :seon.error/operation 'seon.cluster.message/read
       :seon.error/message (str "There is no message named " (pr-str message-id) ".")
       :seon.error/data {:seon.message/id message-id}})))

(defn- received-call
  "Acknowledge `message-id` in this transaction unless it already is."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.message/id] :seon.db/tx-data]}
  [database message-id]
  (let [acknowledged (db/q '[:find ?tx . :in $ ?id
                             :where [?message :seon.message/id ?id]
                                    [?message :seon.message/received-tx ?tx]]
                           database message-id)]
    (cond
      ;; A refused read is not "unacknowledged": the transaction refuses.
      (db/read-refusal? acknowledged) (throw (ex-info (:seon.error/message acknowledged) acknowledged))
      acknowledged []
      :else [[:db/add [:seon.message/id message-id] :seon.message/received-tx "datomic.tx"]])))

(defn received!
  "Acknowledge a message and return it with :seon.message/received-tx.

  The writer decides: acknowledging an acknowledged message is inert. A
  missing identity returns `read`'s not-found error."
  {:malli/schema [:=> [:cat :seon.message/id :seon.db/connection :seon.agent/id]
                  [:or :seon.message/message :seon.error/value]]}
  [message-id connection agent-id]
  (let [message (read message-id (db/db connection))]
    (if (or (not (:seon.message/id message)) (:seon.message/received-tx message))
      message
      (let [result (db/transact! connection
                                 {:tx-data [[:db.fn/call #'received-call message-id]]
                                  :tx-meta {:seon.db/user [:seon.agent/id agent-id]}})]
        (if (:db-after result) (read message-id (:db-after result)) result)))))

;;; ---------------------------------------------------------------------------
;;; The value constructors
;;; ---------------------------------------------------------------------------

(defn- send-value
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :boolean
                       :seon.schema/value]
                  [:or :my.message/message :my.message/no-recipient-error
                   :my.message/no-content-error :my.message/no-about-error]]}
  [to content about? about]
  (cond
    ;; agent-facing: a wrong TYPE is an agent mistake too, and
    ;; `str/blank?` on a non-string would throw out of the one place
    ;; that must not throw
    (or (not (string? to)) (str/blank? to))
    {:my.message/no-recipient true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/send-value
     :seon.error/message
     "send needs the id of the agent to message, as a string."}

    (or (not (string? content)) (str/blank? content))
    {:my.message/no-content true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/send-value
     :seon.error/message
     "send needs the message to deliver, as a string."}

    (and about?
         (or (not (string? about)) (str/blank? about)))
    {:my.message/no-about true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/send-value
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

(defn- prepared-message
  "The message admitted against the sender's database, or its declared refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :my.message/message
                       :seon.agent/id] [:or :seon.message/message :seon.error/value]]}
  [database candidate agent-id]
  (let [limits (db/q '[:find [?limit ...]
                       :where [?cluster :seon.cluster/config ?config]
                              [?config :seon.config.message/max-chain ?limit]] database)
        limit (when (= 1 (count limits)) (first limits))
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
    (or (first (:seon.error/values result))
        (first (:seon.message/rows result)))))

(defn send-call
  "Write an admitted message once by identity, or prepare a legacy candidate at the writer."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.message/message] :seon.db/tx-data]
                  [:=> [:cat :seon.db/database-value :my.message/message :seon.agent/id] :seon.db/tx-data]]}
  ([database message]
   (if (db/q '[:find ?m . :in $ ?id :where [?m :seon.message/id ?id]]
             database (:seon.message/id message))
     []
     [message]))
  ([database candidate agent-id]
   (let [message (prepared-message database candidate agent-id)]
     (if (:seon.error/at message)
       (throw (ex-info (:seon.error/message message) message))
       (send-call database message)))))

(defn candidate
  "The message `request` writes, with its minted id and the members it carries (assignment, reason, a task's end and
  its merge task), or the refusal naming its missing recipient, content or subject."
  {:malli/schema [:=> [:cat :my.message/message]
                  [:or :my.message/message :my.message/no-recipient-error
                   :my.message/no-content-error :my.message/no-about-error]]}
  [request]
  (let [value (send-value (:my.message/to request) (:my.message/content request)
                          (some? (:my.message/about request)) (:my.message/about request))]
    (if (:seon.error/at value)
      value
      (merge value (select-keys request [:seon.message/id :my.message/assignment :my.message/reason :seon.task/end :seon.task/merge])))))

(defn- inbox-port
  "The recipient's inbox in the execution registry's tree: its root and every branch child below it, at any depth."
  {:malli/schema [:=> [:cat [:maybe :seon.agent/routing] :seon.agent/id]
                  [:maybe :seon.flow/channel]]}
  [routing recipient]
  (when routing
    (let [root (loop [registry routing]
                 (if-let [parent (:seon.agent/parent @registry)] (recur parent) registry))]
      (some #(get-in @% [:seon.agent/started recipient :seon.agent/inbox])
            (tree-seq (constantly true) #(vals (:seon.agent/children @%)) root)))))

(defn- undeliverable
  "The sender's undeliverable value, naming recipient intent and channel outcome."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.message/cause]
                  :seon.message/undeliverable]}
  [database recipient cause]
  (let [intent (db/q '[:find ?intent . :in $ % ?id
                       :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g]
                              [?a :seon.task/attempt-of ?t] (not [?a :seon.task.attempt/end _])
                              (execution ?t ?intent)] database task/ready-rules recipient)]
    (seon.error.refusal/error
     :seon.message/undeliverable
     {:seon.error/operation `send!
      :seon.error/message "The recipient's inbox could not accept this message."
      :seon.message/recipient recipient
      :seon.message/intent (or intent :unknown)
      :seon.message/cause cause})))

(defn send!
  "Offer a message without parking; keep a sender copy only under `:my.message/keep?`."
  {:malli/schema [:=> [:cat :my.message/message :seon.db/connection :seon.agent/id]
                  [:or :seon.message/accepted-result :seon.error/value]]}
  [request connection agent-id]
  (let [candidate (candidate request)
        database (db/db connection)]
    (if (:seon.error/at candidate)
      candidate
      (let [message (prepared-message database candidate agent-id)
            recipient (:my.message/to candidate)
            port (inbox-port (:seon.agent/routing request) recipient)]
        (if (:seon.error/at message)
          (if (:seon.message/unknown-recipient message)
            (undeliverable database recipient :no-graph)
            message)
          (let [offered (when port (async/offer! port message))]
            (if (true? offered)
              (let [kept (when (:my.message/keep? request)
                           (db/transact! connection
                                         {:tx-data [[:db.fn/call #'send-call message]]
                                          :tx-meta {:seon.db/user [:seon.agent/id agent-id]}}))]
                (if (and kept (not (:db-after kept)))
                  kept
                  {:seon.message/id (:seon.message/id message) :seon.message/accepted true}))
              (undeliverable database recipient
                             (cond (nil? port) :no-graph (false? offered) :closed :else :full)))))))))

(defn decline
  "Decline an assignment and explain why to its sender.

  Takes the sender id, assignment identity, and reader-facing reason. Returns
  transaction input or a flat error; an agent returns it as a form's value."
  {:malli/schema
   [:=> [:cat :my.message/to :my.message/assignment :my.message/reason]
    [:or :my.message/declination :seon.error/value]]}
  [to assignment reason]
  (cond
    (or (not (string? to)) (str/blank? to))
    {:my.message/no-recipient true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/decline
     :seon.error/message
     "decline needs the id of the assigning agent, as a string."}

    (or (not (string? assignment)) (str/blank? assignment))
    {:my.message/no-assignment true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/decline
     :seon.error/message
     "decline's assignment argument must be a non-blank identity string."}

    (or (not (string? reason)) (str/blank? reason))
    {:my.message/no-reason true
     :seon.error/at (java.util.Date.)
     :seon.error/layer :seon.message/delivery
     :seon.error/operation 'seon.cluster.message/decline
     :seon.error/message
     "decline needs a reader-facing reason, as a string."}

    :else
    {:seon.message/id (id/id)
     :my.message/to to
     :my.message/assignment assignment
     :my.message/reason reason}))
