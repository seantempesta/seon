(ns seon.cluster.wake
  "The wake: a commit says LOOK, and the woken pass derives from facts.

  This contract layer is fully implemented and live-proven.

  EVENT-DRIVEN, NOT POLLED. Datahike's own `listen!` fires on every
  commit, so nothing ever asks \"is there work?\" on a timer. The wake
  carries NO information — work is derived from facts, so a wake that
  lost its payload lost nothing, and one wake standing for three
  commits is correct rather than lossy.

  THE HANDLER CONTRACT IS FOUR LINES AND TWO ABSOLUTE PROHIBITIONS,
  because both were measured, not feared:

  1. IT MUST NEVER THROW. Datahike fires listeners INSIDE the
     transaction's go block and BEFORE `(deliver p tx-report)`
     (`reference-code/datahike/src/datahike/writer.cljc:384-386`).
     Probe A reproduced the consequence: the listener's exception
     escaped onto `async-mixed-6`, the deliver never happened, and the
     committing caller waited forever — the probe JVM had to be killed.
     A handler that throws does not lose a wake; it hangs the writer.
  2. IT MUST NEVER PARK. The handler runs on the committing caller's
     critical path: probe B's 800 ms listener made the triggering
     `transact` take 804 ms. So delivery is `offer!` — never `>!!`,
     never `put!` with a callback that could block — and a saturated
     channel DROPS rather than parks. Dropping is safe by construction:
     the channel is `(sliding-buffer 1)` and a wake means only \"look\".

  Nothing else belongs inside it. No query, no derivation, no commit —
  which also settles the API's own deadlock warning
  (`api/specification.cljc:1076-1078`): N3 never transacts from a
  callback at all.

  THE ROUTED SET IS DECLARED, NOT LISTED. An attribute wakes an agent
  exactly when its schema row carries `:seon.wake/listen true`
  (`resources/seon/schemas/seon.wake.edn`), so which attributes wake an
  agent is one Datalog query over facts the schema population already
  installs, and adding a source is one schema property with no code
  change. `wake-attributes` is that query; `route!` runs it ONCE at
  registration and closes over the answer, because the handler may not
  query (above).

  ROUTING HAS THREE TRAPS, all measured (probe A Q3, probe B Q10):

  - `:db/txInstant` is in EVERY tx-data, so routing on \"any datom\"
    would wake an agent on every commit including its own;
  - the routed set and the set of attributes a turn itself commits must
    be DISJOINT, and that is a computed property rather than a reviewed
    list (L8, L17). The routed set is `wake-attributes`; a turn commits
    `:seon.turn/*`, `:seon.cluster.eval/*`,
    `:seon.cluster.eval/*`, `:seon.agent/run`. The RENDER wake
    is deliberately outside that property: its interest is the union
    of retained reads' attributes, and its consumer derives pages
    rather than work, so it cannot make an idle cluster do anything;
  - an unchanged value emits no datom, so it emits no wake. Naming a
    routed attribute whose value is idempotently re-asserted produces
    ZERO wakes, silently. Every listened attribute is safe because its
    declaration states the rule its writers keep: the datom is asserted
    ONCE, on a new entity or as an edge that moves INTO the attribute,
    and never retracted-and-reasserted. A reassertion would also move
    the wake's transaction `:t` forward and re-open a paid turn.

  A REFUSED TRANSACTION DOES NOT WAKE: dispatch is gated on
  `(map? tx-report)` (`writer.cljc:372`), so a refusal cannot storm a
  mailbox.

  BOOT IS ONE INJECTED WAKE, not a special path. A listener only fires
  on future commits, so work committed before this process started is
  found by `offer!`ing one wake after the graph resumes — the same code
  path as every other pass.

  Crash walk: registration is process-local and durable state is
  untouched. A kill leaves no listener (the process is gone), the
  channel's contents are discarded (`flow/impl.clj:174-183`), and the
  next boot's injected wake re-derives everything from facts."
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn wake-attributes
  "The attributes a commit wakes an agent on, derived from schema rows.

  ONE query over `:seon.wake/listen`, never a list. A schema property
  declared on an attribute reaches its schema row through the general
  property lift (`seon.schema/canonical-schema-rows` ->
  `seon.schema.datahike/storable-properties-in`), so declaring a new
  wake source is one property in one schema resource, and this
  derivation, `route!`'s dispatch and `seon.turn`'s answeredness
  all learn it with no code change.

  The disjointness property (C2) still needs two COMPUTED sets to
  compare rather than one list to believe — this against
  `seon.turn/committed-attributes` — and both sides are now
  derivations rather than one derivation and one hand list.

  Reads a database value because the declaration IS a fact. Direct
  `datahike.api` survives here because this namespace is a system-side
  listener owner holding no agent custody."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.cluster.wake/attributes]}
  [database]
  (into #{}
        (d/q '[:find [?key ...]
               :where
               [?row :seon.wake/listen true]
               [?row :seon.schema/key ?key]]
             database)))

(defn turn-opening-attributes
  "The listened attributes whose unanswered wakes OPEN A TURN.

  The complement still routes and still reaches the agent's next
  context; it never causes a model call on its own — a schedule firing
  is the declared example, and the maintenance portfolio's ticks are why
  the distinction is not academic. One query over
  `:seon.wake/opens-turn?`, so the policy is a declaration rather than a
  branch in the loop."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.cluster.wake/attributes]}
  [database]
  (into #{}
        (d/q '[:find [?key ...]
               :where
               [?row :seon.wake/listen true]
               [?row :seon.wake/opens-turn? true]
               [?row :seon.schema/key ?key]]
             database)))

(defn inside-attributes
  "Attributes whose presence on a wake entity marks it the population's OWN.

  An inside wake never resets an agent's turn bound: an agent-sent
  message carries `:seon.message/from`, an error recorder's
  notification carries `:seon.message/about`, a fault routed for
  repair carries `:seon.error/steward`, and an effect the agent itself
  requested carries `:seon.effect/to`. One query over
  `:seon.wake/inside`, so the rule that used to be a hard-coded
  from-or-about pair inside `seon.turn` is now a declaration
  each family owns."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.cluster.wake/attributes]}
  [database]
  (into #{}
        (d/q '[:find [?key ...]
               :where
               [?row :seon.wake/inside true]
               [?row :seon.schema/key ?key]]
             database)))

(defn unindexed-listened-attributes
  "The listened attributes Datahike's `:avet` index does not hold.

  A LISTENED ATTRIBUTE MUST BE INDEXED, and that is a derived
  requirement rather than a convention: the turn bound and the
  unanswered-wake derivation read an agent's wakes through
  `d/datoms :avet <attribute> <agent>`, and Datahike's `:avet` index
  contains ONLY the attributes declared `:db/index true`
  (`reference-code/datahike/src/datahike/db.cljc:932`). A listened
  attribute without the index answers the seek with an empty sequence —
  no refusal, no exception — which is this project's absence-as-health
  class exactly: every wake would read as absent and the agent would
  never turn."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  :seon.cluster.wake/attributes]}
  [database]
  (let [schema (d/schema database)]
    (into #{}
          (remove (fn [attribute]
                    (true? (:db/index (get schema attribute)))))
          (wake-attributes database))))

(defn declarations-refusal
  "The refusal that says the wake declarations cannot carry a cluster, or nil.

  EMPTY FAILS CLOSED AND LOUD. With no listened attribute declared,
  every derivation over the set answers empty: nothing routes, nothing
  is unanswered, and — measured by the verifier — an empty INSIDE set
  makes every wake read as outside and REFILLS the turn bound on a paid
  loop. Absence of the declaration population is a schema defect, so it
  refuses at the seam that admits the work instead of reading as health
  at four separate derivations."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:maybe :seon.error/value]]}
  [database]
  (let [listened (wake-attributes database)
        inside (inside-attributes database)
        unindexed (unindexed-listened-attributes database)]
    (cond
      (empty? listened)
      {:seon.error/kind ::no-listened-attributes
       :seon.error/message
       (str "No attribute declares :seon.wake/listen, so nothing can ever "
            "wake an agent on this database.")
       :seon.cluster.wake/attributes #{}}

      (empty? inside)
      {:seon.error/kind ::no-inside-attributes
       :seon.error/message
       (str "No attribute declares :seon.wake/inside, so every wake would "
            "count as arriving from outside the agent and refill its turn "
            "bound.")
       :seon.cluster.wake/attributes listened}

      (seq unindexed)
      {:seon.error/kind ::unindexed-listened-attribute
       :seon.error/message
       (str "A listened attribute is not in the :avet index, so its wakes "
            "read as absent: " (pr-str (vec (sort unindexed))))
       :seon.cluster.wake/attributes unindexed})))

(defn agent-wake-datoms
  "Every wake datom addressed to one agent, newest index entry first.

  `[entity tx attribute]` tuples read straight out of Datahike's
  `:avet` index — one seek per attribute, no pull and no join. The turn
  bound and the unanswered-wake derivation both compose from this, which
  is what makes them O(the agent's own wakes) in cheap index iteration
  rather than a `db/pull` per wake the agent has ever received (measured
  47.5 ms per turn pass at 2,008 lifetime wakes before this).

  DESCENDING, because both callers want the newest first: the bound's
  anchor stops at the first outside wake, and the unanswered filter cuts
  at the latest answering turn. Datahike orders `:avet` by (a, v, e), so
  reversing one attribute's slice is descending entity order, and the
  transaction each datom carries is what either caller actually
  compares.

  Direct `datahike.api` here for the same reason the listener above uses
  it: this namespace is the system-side owner of the wake mechanism and
  holds no agent custody."
  {:malli/schema [:=> [:cat :seon.db/database-value :int
                       :seon.cluster.wake/attributes]
                  [:vector [:tuple :int :int :qualified-keyword]]]}
  [database agent-eid attributes]
  (into []
        (mapcat (fn [attribute]
                  (map (fn [datom] [(:e datom) (:tx datom) attribute])
                       (filter :added
                               (reverse (d/datoms database :avet attribute agent-eid))))))
        attributes))

(defn inside-wake?
  "True when this wake entity carries any attribute declared inside.

  One `:eavt` seek on the entity, so the anchor pays it for the newest
  candidate and stops."
  {:malli/schema [:=> [:cat :seon.db/database-value :int
                       :seon.cluster.wake/attributes]
                  :boolean]}
  [database wake-eid inside]
  (boolean
   (some (fn [datom] (contains? inside (:a datom)))
         (d/datoms database :eavt wake-eid))))

(defn delivery
  "`offer!`'s answers plus the route owner's derived fence, named.

  core.async reports whether a non-blocking offer delivered, met a
  closed channel, or would have parked:

  - `true` — `::delivered`. The wake is in the buffer.
  - `false` — the route is CLOSED
    (`reference-code/core.async/.../impl/channels.clj:80-84` returns
    `(box false)` on the closed branch and only there). This is
    `::fenced` ONLY when the agent routing owner derives that the exact
    mailbox is still its current closed route; otherwise it is
    `::refused`.
  - `nil` — `::refused`. The route is LIVE and would have to park
    (`:139-140`, `:159-160`, `:171` — the non-blockable handler
    returns no box). This is the genuinely non-accepting route, and it
    stays loud.

  A CLOSED AGENT MAILBOX MAY BE A LIFECYCLE STATE, but closedness alone
  cannot establish that: a closed render route is a broken live
  registration and remains loud. The agent construction order is what
  makes its narrower answer derivable rather than assumed:
  `agent/disarm!` drops the routing entry BEFORE it closes the mailbox,
  so an ordinary teardown is never visible here at all — the recipient
  simply has no entry and the armer belt takes the wake. A channel
  still REACHABLE through the routing map and already closed was
  therefore closed by the one thing that closes a mailbox in place: the
  terminal-settlement fence in `seon.turn`, which stops the
  agent taking another pass over its still-running receipt before boot
  recovery marks it interrupted. `agent/fenced-route?` derives that
  exact delivery-side state; `agent/fenced?` is its management view.

  Dropping a wake there loses nothing, by this namespace's own
  doctrine: the wake carries no information, the MESSAGE is a durable
  fact, and the fresh mailbox `arm!` creates after reboot is primed
  once with a pass that derives every message from facts. What the
  router must not do is call the fence a new failure — the fault the
  fence raised is the whole report, and its own explanation message
  arriving at the fenced mailbox is not a second incident.

  Sliding-1 buffers are never full
  (`impl/buffers.clj` — `SlidingBuffer.full?` is constantly false), so
  for a correctly built mailbox, a `nil` refusal is unrepresentable."
  {:malli/schema [:=> [:cat :seon.cluster.wake/offer-result :boolean]
                  :seon.cluster.wake/delivery]}
  [offered fenced?]
  (cond
    (true? offered) ::delivered
    (and (false? offered) fenced?) ::fenced
    :else ::refused))

(defn- deliver!
  "`offer!` one payload-free wake and classify the answer.
  `fenced?` is a zero-arg derived check invoked only after a closed
  offer. Faults on `::refused` — one classifier and one error kind.
  Returns the delivery."
  [fault-channel key route channel fenced?]
  (let [offered (async/offer! channel ::wake)
        outcome (delivery offered
                          (and (false? offered) (boolean (fenced?))))]
    (when (= ::refused outcome)
      (async/offer! fault-channel
                    (ex-info "a wake route refused delivery"
                             {:seon.error/kind ::undeliverable-wake
                              :seon.cluster.wake/undeliverable-wake key
                              :seon.error/message
                              "A wake route refused delivery."
                              ::key key
                              ::route route})))
    outcome))

(defn route!
  "Register the ROUTING wake handler on a connection (F1 4).
  The per-agent successor of `listen!`'s one-channel delivery, under
  the SAME two absolute prohibitions (it never throws and never parks
  — every delivery is `offer!` and the whole handler is one
  try/catch).

  THE SET IS DERIVED HERE AND RE-DERIVED WHEN THE DECLARATION CHANGES.
  `wake-attributes` runs against the connection's current database value
  at registration and the handler holds the answer; it re-runs on the
  report's own `:db-after` exactly when a transaction asserts a
  `:seon.wake/*` property on a schema row. The listener still never
  queries on an ordinary commit — that is the critical-path rule — and a
  development cluster, whose whole point is that schema rows change in
  place, no longer routes by a frozen answer while every other consumer
  re-derives.

  REGISTRATION REFUSES AN UNUSABLE DECLARATION POPULATION
  (`declarations-refusal`): no listened attribute, no inside attribute,
  or a listened attribute Datahike's `:avet` index does not hold. Each
  of those reads as health at four separate derivations, and one of them
  refills the turn bound on a paid loop.

  DISPATCH IS THE SAME DERIVATION. Every listened attribute is a ref
  whose VALUE is the recipient agent's entity id, so delivery is one
  lookup in the routing map the supplied `channels` fn returns: `offer!`
  a payload-free wake into that agent's mailbox. No query, no
  derivation, no commit. A recipient with NO routing entry offers to the
  ARMER instead — the belt that arms an agent created and addressed in
  one commit, and the reason no separate agent-creation wake is needed.

  THE SECOND DELIVERY (W2) is decided per REPORT, not delivered per
  datom. `:seon.render.web/interest` is one process-local projection
  of retained reads' dependency-plan attributes: `:all` before the
  first complete derivation, otherwise the concrete attribute union.
  The existing datom pass records whether any changed attribute
  intersects that value, then offers at most one payload-free render
  wake. There is no query, report payload, call-id routing, or second
  registration.

  A route refusing delivery is a FAULT fact, exactly as in
  `listen!` — a swallowed failure nobody hears about is an invisible
  one. Only an exact mailbox route the agent owner derives as fenced is
  benign; a closed render route is still a failure. Coalescing on every
  `(sliding-buffer 1)` target is safe by the standing argument: a wake
  says only look, and the woken pass derives everything from facts.
  L8 holds by construction: the armer's
  own work commits no wake-set attribute (arming writes nothing; the
  prime is an `offer!`)."
  {:malli/schema [:=> [:cat :seon.cluster.wake/route-request]
                  :seon.cluster.wake/key]}
  [{:keys [:seon.cluster.wake/connection :seon.cluster.wake/channels
           :seon.cluster.wake/fenced?
           :seon.cluster.wake/armer-channel :seon.cluster.wake/render-channel
           :seon.render.web/interest
           :seon.cluster.wake/search-channel
           :seon.cluster.wake/fault-channel :seon.cluster.wake/key]}]
  (when-let [refusal (declarations-refusal (d/db connection))]
    (throw (ex-info (:seon.error/message refusal) refusal)))
  (let [listened (volatile! (wake-attributes (d/db connection)))]
    (d/listen
     connection
     key
     (fn [report]
       (try
         (let [published-interest @interest
               render? (volatile! (= :all published-interest))]
           ;; Search needs the report's exact db-before/db-after bases. The
           ;; sliding-1 channel may coalesce reports; its proc detects that gap
           ;; and rebuilds from the newest database value instead of guessing.
           (when search-channel
             (when-not (true? (async/offer! search-channel report))
               (async/offer!
                fault-channel
                (ex-info
                 "The derived search index refused a transaction report."
                 {:seon.cluster.wake/key key
                  :seon.cluster.wake/route ::search}))))
           ;; A DECLARATION CHANGE RE-DERIVES THE SET, HERE, ON THE REPORT'S
           ;; OWN `:db-after`. The set used to be frozen at registration
           ;; while `seon.turn` re-derived per call, so one live
           ;; schema change left two answers to one question and an openable
           ;; wake nothing would ever deliver — with no refusal and no fault
           ;; (measured: verify-listened-attributes-2026-09-08 §1c). The
           ;; handler's two prohibitions still hold: this neither throws nor
           ;; parks, and the query runs only when a transaction actually
           ;; asserts a `:seon.wake/*` property on a schema row, which is a
           ;; schema publication and not an ordinary commit.
           (when (some (fn [datom]
                         (= "seon.wake" (namespace (nth datom 1))))
                       (:tx-data report))
             (vreset! listened (wake-attributes (:db-after report))))
           (doseq [datom (:tx-data report)]
             (let [attribute (nth datom 1)]
               (when (and (not @render?)
                          (contains? published-interest attribute))
                 (vreset! render? true))
               (when (contains? @listened attribute)
                 (let [agent-eid (nth datom 2)]
                   (if-let [channel (get (channels) agent-eid)]
                     (deliver! fault-channel key ::mailbox channel
                               #(fenced? agent-eid channel))
                     (async/offer! armer-channel ::wake))))))
           (when @render?
             (deliver! fault-channel key ::render render-channel
                       (constantly false))))
         (catch Throwable failure
           (async/offer! fault-channel failure))))))
  key)

(defn unlisten!
  "Remove the wake handler.
  Idempotent — removing an absent listener is
  a no-op, because `::flow/stop` may arrive after a store release."
  {:malli/schema [:=> [:cat :seon.cluster.wake/unlisten-request] :nil]}
  [{:keys [:seon.cluster.wake/connection :seon.cluster.wake/key]}]
  (try
    (d/unlisten connection key)
    ;; stop may arrive after a release, and an absent listener is the
    ;; state we wanted anyway
    (catch Throwable _ nil))
  nil)
