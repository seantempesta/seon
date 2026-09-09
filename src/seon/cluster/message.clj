(ns seon.cluster.message
  "Delivery: what an agent's message value becomes as durable facts.

  THE DRIVER HALF of `my.message`. The agent returns a value; this
  namespace resolves it against a database value into ordinary tx-data
  the run loop rides in its own terminal transaction. Pure over `db` —
  it commits nothing, so the whole delivery rule (recipients, the
  conversation bound, the refusals) is testable against an in-memory
  database with no cluster, no loop and no model.

  DELIVERY IS THE EXISTING WAKE, and that is the entire transport.
  `:seon.cluster.message/to` is the wake attribute, so committing a
  message wakes the recipient's loop by construction: there is no
  queue, no acknowledgement, no inbox flag and no second channel.
  `seon.error/commit-tx` has been relying on this since the error rung;
  agents are simply the second producer of the same fact.

  THE BOUND, AND WHY IT IS A DIFFERENT BOUND FROM THE ERROR STORM.
  Alice messages bob, bob's reply wakes alice, alice replies — a polite
  infinite conversation, every message different, every hop a paid
  model call. The error path's fence counts occurrences of one
  SIGNATURE, so it cannot see this: nothing repeats. What repeats is
  the CHAIN, and the chain is already recorded — every run records its
  trigger message when it opens, and every outbound message records the
  message that caused it. So the depth of a conversation is a WALK over
  ordinary committed connections, derived on demand:

      message → its cause → …

  and the walk ends at a message with no recorded cause —
  which is exactly a message from outside the agent population: a
  human's nudge, or the error recorder's. THE HUMAN BARRIER IS
  THEREFORE FREE. The quarry paid for it twice: it stored a `hops`
  integer on every message (`src-old/seon/agent/message.cljc:64`) and
  then needed a second rule — per-peer counting against the newest
  human message — because the first, global rule summed every hop of a
  delegation TREE and silently deadlocked a routine two-round
  delegation (`src-old/seon/agent/message/internal.cljc:35-56`). Both
  rules dissolve here: the causal chain is per-conversation because
  causation is, and it resets at a human message because a human
  message has no cause we recorded.

  A REFUSED DELIVERY IS A FACT, NEVER A DROP. An unknown recipient or
  an over-long chain comes back as a flat error VALUE, which the loop
  hands to the error recorder in the same transaction as the receipt.
  It is deliberately not mailed to the sender: telling an agent by
  message that its message was refused is the storm shape, and the
  recorder's own rule (a returned VALUE tells nobody; only a Throwable
  that interrupted a run does) already says so.

  Crash walk: nothing here holds state. Ordinary message ids are DERIVED
  from (run, ordinal, index) exactly as receipt ids are. A message or
  declination carrying `about` instead derives its identity from the
  resolved (about entity, recipient), so concurrent terminal
  transactions upsert one answer at Datahike's serial commit point."
  (:refer-clojure :exclude [read send])
  (:require [seon.db :as db]
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
                  [:maybe :seon.cluster.message/id]]}
  [db run-id]
  (db/q '[:find ?message-id .
         :in $ ?run-id
         :where
         [?run :seon.turn/id ?run-id]
         [?run :seon.turn/trigger ?message]
         [?message :seon.cluster.message/id ?message-id]]
       db run-id))

(defn- caused-by
  "The message whose answering produced `message-id`, or nil."
  [db message-id]
  (db/q '[:find ?parent-id .
         :in $ ?message-id
         :where
         [?message :seon.cluster.message/id ?message-id]
         [?message :seon.cluster.message/caused-by ?parent]
         [?parent :seon.cluster.message/id ?parent-id]]
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
                       :seon.cluster.message/id]
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
                       :seon.cluster.message/id]
                  [:maybe :seon.cluster.agent/id]]}
  [db message-id]
  (db/q '[:find ?agent-id .
         :in $ ?message-id
         :where
         [?message :seon.cluster.message/id ?message-id]
         [?message :seon.cluster.message/from ?agent]
         [?agent :seon.cluster.agent/id ?agent-id]]
       db message-id))

(defn reply
  "The message a completed run owes the agent that asked for it, or nil.
  A `my.message/send` VALUE, deliberately — so the reply goes through
  `delivery` like any other message and inherits the recipient check,
  the conversation bound and the derived id, rather than becoming a
  second way to make a message.

  THIS IS DERIVED, NOT REMEMBERED, and the live drive is the argument.
  Alice delegated correctly; bob read \"agent alice sent you: how many
  primes under 100?\", worked it out, and called
  `(my.run/complete \"25\")` — which addressed nobody, because
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
                       :seon.cluster.message/reply-request]
                  [:maybe :my.message/message]]}
  [db {:keys [:seon.cluster.message/trigger :my.run/result
              :seon.cluster.agent/id]}]
  (let [asker (and trigger (sender db trigger))
        answering-us? (and trigger
                           (= id (some->> (caused-by db trigger)
                                          (sender db))))]
    (when (and asker (not answering-us?))
      {:my.message/to asker
       :my.message/content result})))

;;; ---------------------------------------------------------------------------
;;; The delivery
;;; ---------------------------------------------------------------------------

(defn- agent-exists?
  [db agent-id]
  (some? (db/q '[:find ?agent .
                :in $ ?id
                :where [?agent :seon.cluster.agent/id ?id]]
              db agent-id)))

(defn- message-id
  "One outbound message's identity, derived from turn, ordinal, and index.
  The same derived-identity idiom as receipts and attempt rows, and for
  the same reason: an identity that is a function of where the message
  came from is one nothing has to allocate, remember, or reconcile."
  [run-id ordinal index]
  (id/digest 12 [::id run-id ordinal index]))

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
      {:seon.error/kind ::unknown-about
       :seon.cluster.message/unknown-about identity
       :seon.error/message
       (str "There is no identified fact named \"" identity
            "\", so nothing was assigned about it.")
       :seon.error/data {:my.message/about identity}}

      (< 1 (count entities))
      {:seon.error/kind ::ambiguous-about
       :seon.cluster.message/ambiguous-about identity
       :seon.error/message
       (str "More than one identified fact is named \"" identity
            "\", so the assignment target is ambiguous.")
       :seon.error/data {:my.message/about identity}}

      :else
      {:seon.cluster.message/about (first entities)})))

(defn- assignment-message-id
  "One assignment's commit-time unique identity.

  `pr-str` preserves the pair boundary for arbitrary recipient strings;
  the resolved entity id is stable within the database where the unique
  message id performs the upsert."
  [about recipient]
  (id/digest 12 [::assignment about recipient]))

(defn- inbound-message-id
  "One outside message's identity, derived at the serial writer basis."
  [db index]
  (id/digest 12 [::inbound (db/basis-t db) index]))

(defn inbound-tx
  "Derive one outside message row or a flat refusal value.

  Pure over the database value. The web boundary calls this once to
  turn invalid input into a response, then commits the accepted request
  through `:db.fn/call` so the identity is derived from the immediate
  predecessor basis inside Datahike's serial writer. Exactly one row,
  index zero, because one inbound POST is one message.

  Absence of `:seon.cluster.message/from` is the origin contract: this
  message came from outside the agent population. Provenance belongs on
  the transaction and is therefore absent here too."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.message/inbound-request]
                  :seon.cluster.message/inbound]}
  [db {:keys [:seon.cluster.agent/id
              :seon.cluster.message/inbound-content
              :seon.cluster.message/at
              :seon.config.eval.result/max-string]}]
  (cond
    (not (agent-exists? db id))
    {:seon.error/kind ::unknown-recipient
     :seon.error/message
     (str "There is no agent named \"" id
          "\" in this cluster, so nothing was sent to it.")
     :seon.error/data {:seon.cluster.agent/id id}
     :seon.cluster.message/unknown-recipient id}

    (str/blank? inbound-content)
    {:seon.error/kind ::blank-content
     :seon.error/message "A message must contain some text."
     :seon.error/data {:seon.cluster.agent/id id}
     :seon.cluster.message/blank-content true}

    (> (count inbound-content) max-string)
    {:seon.error/kind ::content-too-large
     :seon.cluster.message/content-too-large (count inbound-content)
     :seon.error/message
     (str "This message is " (count inbound-content)
          " characters; the configured limit is " max-string ".")
     :seon.error/data
     {:seon.cluster.agent/id id
      :seon.config.eval.result/max-string max-string}}

    :else
    [{:seon.cluster.message/id (inbound-message-id db 0)
      :seon.cluster.message/ordinal 0
      :seon.cluster.message/to [:seon.cluster.agent/id id]
      :seon.cluster.message/content inbound-content
      :seon.cluster.message/at at}]))

(defn delivery
  "What one admitted message-family value sends, resolved against `db`.
  Returns the tx-data rows to commit and the flat error values for
  every candidate that could not be delivered — both, because a value
  asking to send three messages where one names a stranger delivers the
  two and records the one.

  The chain depth of every message this call produces is the same
  number: they are all caused by the same trigger, so the bound is
  computed ONCE and the guard is all-or-nothing for the form. That is
  the honest reading of the rule — the bound is on the conversation,
  not on the individual sentence."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.message/delivery-request]
                  :seon.cluster.message/delivery]}
  [db {:keys [:my.message/value :seon.cluster.agent/id
              :seon.turn/id :seon.cluster.eval/ordinal
              :seon.cluster.message/at :seon.cluster.message/trigger
              :seon.config.message/max-chain]
       :as request}]
  (let [sender (:seon.cluster.agent/id request)
        run-id (:seon.turn/id request)
        candidates (if (vector? value) value [value])
        ;; a run whose trigger cannot be found starts a fresh chain at
        ;; one hop — the same depth as answering a human, because that
        ;; is the only way a run with no recorded cause could have come
        ;; about
        depth (if trigger (inc (chain-depth db trigger)) 1)]
    (cond
      ;; FAIL CLOSED, LOUDLY. The bound is a required dial, but
      ;; requiredness is a contract and contracts are not enforced until
      ;; instrumentation is on — and `(> 1 nil)` throws. A messaging
      ;; path with no bound is exactly the runaway the bound exists for,
      ;; so an absent dial delivers NOTHING and says so as a fact with
      ;; its own kind, rather than delivering everything or throwing
      ;; into the loop. (The error recorder makes the opposite choice
      ;; from the same shape — it records and mails nothing — because a
      ;; recorder that refuses to record loses the evidence, while a
      ;; deliverer that refuses to deliver loses only a message.)
      (not (pos-int? max-chain))
      {:seon.cluster.message/rows []
       :seon.error/values
       [{:seon.error/kind ::no-limit
         :seon.error/message
         (str "Messaging is unbounded in this cluster — "
              ":seon.config.message/max-chain is absent, so nothing was "
              "delivered.")
         :seon.error/data {:seon.cluster.agent/id sender
                           :seon.turn/id run-id}
         :seon.cluster.message/no-limit true}]}

      (> depth max-chain)
      {:seon.cluster.message/rows []
       :seon.error/values
       [{:seon.error/kind ::chain-limit
         :seon.cluster.message/chain-limit max-chain
         :seon.error/message
         (str "This conversation has run " depth
              " agent-to-agent hops without a human, over the limit of "
              max-chain ". Nothing was delivered.")
         :seon.error/data {:seon.config.message/max-chain max-chain
                           :seon.cluster.agent/id sender
                           :seon.turn/id run-id}}]}

      :else
      (reduce
       (fn [delivered [index candidate]]
         (let [to (:my.message/to candidate)
               reason (:my.message/reason candidate)]
           (if-not (agent-exists? db to)
             ;; a lookup ref to an agent this cluster does not have
             ;; fails the WHOLE transaction — receipt, disposition and
             ;; all — so the stranger costs the message and never the
             ;; record of the turn
             (update delivered :seon.error/values conj
                     {:seon.error/kind ::unknown-recipient
                      :seon.error/message
                      (str "There is no agent named \"" to
                           "\" in this cluster, so nothing was sent to it.")
                      :seon.error/data {:my.message/to to
                                        :seon.cluster.agent/id sender
                                        :seon.turn/id run-id}
                      :seon.cluster.message/unknown-recipient to})
             (let [about-identity (:my.message/about candidate)
                   about (when about-identity
                           (resolve-about db about-identity))]
               (if (:seon.error/kind about)
                 (update delivered :seon.error/values conj
                         (update about :seon.error/data
                                 merge
                                 {:seon.cluster.agent/id sender
                                  :seon.turn/id run-id}))
                 (update
                  delivered
                  :seon.cluster.message/rows
                  conj
                  (cond->
                   {:seon.cluster.message/id
                    (if about
                      (assignment-message-id
                       (:seon.cluster.message/about about) to)
                      (message-id run-id ordinal index))
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id to]
                    :seon.cluster.message/from
                    [:seon.cluster.agent/id sender]
                    :seon.cluster.message/content
                    (or (:my.message/content candidate) reason)
                    :seon.cluster.message/ordinal index
                    :seon.cluster.message/at at}
                    trigger
                    (assoc :seon.cluster.message/caused-by
                           [:seon.cluster.message/id trigger])
                    about
                    (assoc :seon.cluster.message/about
                           (:seon.cluster.message/about about))
                    reason
                    (assoc :my.message/reason reason))))))))
       {:seon.cluster.message/rows []
        :seon.error/values []}
       (map-indexed vector candidates)))))

;;; ---------------------------------------------------------------------------
;;; The family default render
;;; ---------------------------------------------------------------------------

(defn- agent-reference-id
  [database reference]
  (or (:seon.cluster.agent/id reference)
      (let [entity-id
        (cond
          (map? reference)
          (or (when-let [entry (find reference :seon.cluster.agent/id)]
                [:seon.cluster.agent/id (val entry)])
              (:db/id reference))

          :else reference)]
        (when (and database entity-id)
          (let [result
                (db/q '[:find ?id .
                        :in $ ?agent
                        :where [?agent :seon.cluster.agent/id ?id]]
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
        content (get unit ::content)
        from-ref (get unit ::from)
        to-ref (get unit ::to)]
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
  (when-let [id (get unit ::id)]
    (str "; Read this message; reply with (doc my.message/send).\n"
         (pr-str (list 'my.message/read {:my.message/id id})))))

(defn render-html
  "`:seon.render/html` — one message, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (let [database (get unit :seon.db/db)
        content (get unit ::content)
        from-ref (get unit ::from)
        to-ref (get unit ::to)
        at (get unit ::at)
        about (identity-reference database (get unit ::about))
        caused-by-id (some-> (get unit ::caused-by)
                             (get ::id))
        caused-by-ref (when caused-by-id [::id caused-by-id])]
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
                from (str "Agent " from)
                from-ref (str "Unresolved sender " (pr-str from-ref))
                :else "Outside this cluster")]
             [:span {:class "seon-message-arrow" :aria-hidden "true"} "→"]
             [:span {:class "seon-message-to"}
              (cond
                to (str "Agent " to)
                to-ref (str "Unresolved recipient " (pr-str to-ref))
                :else "No recipient")]]]
            at
            (conj (let [instant (.toString (.toInstant ^java.util.Date at))]
                    [:time {:class "seon-message-at" :datetime instant}
                     instant])))
          [:p {:class "seon-message-content" :style {:white-space "pre-wrap"}} content]
          [:p {:class "seon-message-reply"}
           (if (and from (get unit ::id))
             [:code (pr-str (list 'my.message/send {:my.message/to from :my.message/content "Your reply" :my.message/about (get unit ::id)}))]
             "This message has no agent sender to address a reply to.")]]
          (or about caused-by-ref)
          (conj
           (cond->
            [:p {:class "seon-message-links"}]
             about
             (conj [:span {:class "seon-message-about"}
                    (data-link (str "about " (pr-str about)) about)])
             (and about caused-by-ref) (conj " · ")
             caused-by-ref
             (conj [:span {:class "seon-message-caused-by"}
                    (data-link (str "caused-by " caused-by-id)
                               caused-by-ref)]))))))))

;;; ---------------------------------------------------------------------------
;;; The inbox unit
;;;
;;; `:seon.cluster.message/to` is reached two ways. A walk from one message
;;; hands the single recipient reference; the agent's declared
;;; `:seon.cluster.message/_to` unit hands every message addressed to that
;;; agent. One producer per projection answers both, because both are the same
;;; question asked from the two ends of one ref.
;;;
;;; A DECLARED REVERSE UNIT HAS NO SCHEMA KEY OF ITS OWN. Both the render
;;; contract coherence check and instrumentation read the FORWARD attribute's
;;; schema, which describes one ref and not the collection the reverse pulls,
;;; so the declared producer input is `:seon.schema/value` — the render seam's
;;; own name for "the value this seam hands me". The honest collection shape
;;; is `:seon.cluster.message/inbox-unit` and it is enforced one call inward,
;;; at `inbox-html`, where instrumentation states it. The missing fact is
;;; recorded in the research note.
;;; ---------------------------------------------------------------------------

(defn render-inbox-ai
  "Read each acquired message once through its entity's AI pair."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.render/source]}
  [recipient-or-inbox]
  (if (and (sequential? recipient-or-inbox)
           (every? map? recipient-or-inbox))
    (str/join "\n\n"
              (keep render-ai
                    (sort-by (juxt ::at ::id) recipient-or-inbox)))
    "; Messages are read from the recipient's inbox.\n(my.message/inbox)"))

(defn- message-order
  [message]
  [(if-let [at (get message ::at)] (.getTime ^java.util.Date at) 0)
   (long (or (get message ::ordinal) 0))
   (str (get message ::id))])

(defn inbox-html
  "Render every message addressed to one agent, oldest first.

  Each message reaches the one message renderer, so a message in this list
  and the same message rendered alone state exactly the same facts."
  {:malli/schema [:=> [:cat :seon.cluster.message/inbox-unit
                       :seon.db/database-value]
                  :seon.render/hiccup]}
  [messages database]
  (let [entries (into []
                      (keep #(render-html (assoc % :seon.db/db database)))
                      (sort-by message-order messages))]
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
  {:malli/schema [:=> [:cat :seon.schema/value :seon.db/database-value]
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
         [:p (or (data-link (pr-str recipient) recipient)
                 [:code (pr-str recipient)])]
         [:p {:class "seon-message-inbox-empty"}
          (str "The recipient reference "
               (pr-str recipient-or-inbox)
               " does not resolve to an entity with an identity attribute.")])])))

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
    [:=> [:cat :my.message/inbox-request]
     [:or :my.message/inbox :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
     [:or :my.message/inbox :seon.error/value]]
    [:=> [:cat :my.message/inbox-options
          :seon.db/database-value :seon.cluster.agent/id]
     [:or :my.message/inbox :seon.error/value]]]}
  ([request]
   (inbox* (:seon.db/db request)
           (:seon.cluster.agent/id request)
           (:seon.db/since request)))
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
      {:seon.error/kind :my.message/not-found
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
    {:my.message/to to
     :my.message/about about
     :my.message/reason reason}))
