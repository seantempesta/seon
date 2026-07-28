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
  the CHAIN, and the chain is already recorded — every run-opening
  transaction names its trigger in `:seon.db/trigger` transaction
  metadata, and a terminal transaction that delivers messages names the
  same trigger. So the depth of a conversation is a WALK over committed
  transaction metadata, derived on demand:

      message → the transaction that created it → its trigger → …

  and the walk ends at a message whose transaction named no trigger —
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

  Crash walk: nothing here holds state. Message ids are DERIVED from
  (run, ordinal, index) exactly as receipt ids are, so the same form
  delivering twice would upsert the same rows rather than double-send —
  a property the crash model does not need today (nothing re-executes)
  and would need the moment anything did."
  (:require [datahike.api :as d]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/message.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The chain, walked from transaction metadata
;;; ---------------------------------------------------------------------------

(defn trigger
  "The message the run `run-id` is answering, or nil.
  Read from the run's OWN creating transaction: the `:open` transition
  commits the run and names its trigger as `:seon.db/trigger` tx-meta
  in one transaction, so the run's identity datom and the trigger ref
  share a transaction entity. Nothing is stored on the run for this —
  that is the night ruling, and this is the read it implies."
  {:malli/schema [:=> [:cat :any :seon.cluster.run/id]
                  [:maybe :seon.cluster.message/id]]}
  [db run-id]
  (d/q '[:find ?message-id .
         :in $ ?run-id
         :where
         [?run :seon.cluster.run/id ?run-id ?tx]
         [?tx :seon.db/trigger ?message]
         [?message :seon.cluster.message/id ?message-id]]
       db run-id))

(defn- caused-by
  "The message whose answering produced `message-id`, or nil."
  [db message-id]
  (d/q '[:find ?parent-id .
         :in $ ?message-id
         :where
         [?message :seon.cluster.message/id ?message-id ?tx]
         [?tx :seon.db/trigger ?parent]
         [?parent :seon.cluster.message/id ?parent-id]]
       db message-id))

(defn chain-depth
  "How many agent hops separate `message-id` from outside the population.
  Zero for a message nobody's turn produced — a human's, or the error
  recorder's — and one more for each answering hop after that. DERIVED
  by walking transaction metadata; there is no counter to keep, which
  is why nothing can reset it wrongly and nothing can forget to
  increment it.

  The `seen` set is not defensive decoration about cycles that cannot
  happen (a transaction's trigger is always older than the transaction
  itself): it is what makes this function TOTAL against a database that
  a fixture, an import or a bug could hand it, in the one place whose
  job is to stop something running forever."
  {:malli/schema [:=> [:cat :any :seon.cluster.message/id] [:int {:min 0}]]}
  [db message-id]
  (loop [id message-id
         depth 0
         seen #{}]
    (if-let [parent (when-not (contains? seen id) (caused-by db id))]
      (recur parent (inc depth) (conj seen id))
      depth)))

(defn sender
  "The agent that sent `message-id`, or nil when it came from outside."
  {:malli/schema [:=> [:cat :any :seon.cluster.message/id]
                  [:maybe :seon.cluster.agent/id]]}
  [db message-id]
  (d/q '[:find ?agent-id .
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
  {:malli/schema [:=> [:cat :any :seon.cluster.message/reply-request]
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
  (some? (d/q '[:find ?agent .
                :in $ ?id
                :where [?agent :seon.cluster.agent/id ?id]]
              db agent-id)))

(defn- message-id
  "One outbound message's identity: `<run>-<ordinal>-<index>`.
  The same derived-identity idiom as receipts and attempt rows, and for
  the same reason: an identity that is a function of where the message
  came from is one nothing has to allocate, remember, or reconcile."
  [run-id ordinal index]
  (str run-id "-" ordinal "-message-" index))

(defn delivery
  "What one admitted message value asks to send, resolved against `db`.
  Returns the tx-data rows to commit and the flat error values for
  every candidate that could not be delivered — both, because a value
  asking to send three messages where one names a stranger delivers the
  two and records the one.

  The chain depth of every message this call produces is the same
  number: they are all caused by the same trigger, so the bound is
  computed ONCE and the guard is all-or-nothing for the form. That is
  the honest reading of the rule — the bound is on the conversation,
  not on the individual sentence."
  {:malli/schema [:=> [:cat :any :seon.cluster.message/delivery-request]
                  :seon.cluster.message/delivery]}
  [db {:keys [:my.message/value :seon.cluster.agent/id
              :seon.cluster.run/id :seon.cluster.run.form/ordinal
              :seon.cluster.message/at :seon.cluster.message/trigger
              :seon.config.message/max-chain]
       :as request}]
  (let [sender (:seon.cluster.agent/id request)
        run-id (:seon.cluster.run/id request)
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
                           :seon.cluster.run/id run-id}}]}

      (> depth max-chain)
      {:seon.cluster.message/rows []
       :seon.error/values
       [{:seon.error/kind ::chain-limit
         :seon.error/message
         (str "This conversation has run " depth
              " agent-to-agent hops without a human, over the limit of "
              max-chain ". Nothing was delivered.")
         :seon.error/data {:seon.config.message/max-chain max-chain
                           :seon.cluster.agent/id sender
                           :seon.cluster.run/id run-id}}]}

      :else
      (reduce
       (fn [delivered [index candidate]]
         (let [to (:my.message/to candidate)]
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
                                        :seon.cluster.run/id run-id}})
             (update delivered :seon.cluster.message/rows conj
                     {:seon.cluster.message/id
                      (message-id run-id ordinal index)
                      :seon.cluster.message/to [:seon.cluster.agent/id to]
                      :seon.cluster.message/from
                      [:seon.cluster.agent/id sender]
                      :seon.cluster.message/content
                      (:my.message/content candidate)
                      :seon.cluster.message/at at}))))
       {:seon.cluster.message/rows []
        :seon.error/values []}
       (map-indexed vector candidates)))))
