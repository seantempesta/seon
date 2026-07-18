(ns seon.agent.message.internal
  "Private plumbing for `seon.agent.message` — the user-entity predicate
   and the newest-inbound hop base used by `message!`.

   Factored out of the public message surface so the teaching ns shows ONLY
   message!/user/agent + the public wake-rule predicates + their register!
   schemas (the `*.internal` convention drops these from rendered agent
   context — see `seon.agent.ctx.namespaces/hidden-ns-name?`).

   Required ONLY by its parent ns `seon.agent.message`.

   Keyword-namespace note: this lives under `seon.agent.message.internal`,
   so `::foo` would expand WRONG. Every helper references the owning ns's
   attrs fully-qualified (`:seon.agent.message/from`, never `::from`)."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(defn clip-title
  "A SHORT single-line preview of message `content` for an address-step
   title: internal newlines collapsed to spaces, trimmed, clipped to ~80
   chars with a trailing `…` when cut. Never blank (the step title schema
   demands min 1) — falls back to a placeholder for whitespace-only input
   (message! already refuses blank content, so this is belt-and-braces)."
  [content]
  (let [one-line (-> (str content)
                     (str/replace #"\s+" " ")
                     str/trim)]
    (cond
      (str/blank? one-line) "(message)"
      (<= (count one-line) 80) one-line
      :else (str (subs one-line 0 80) "…"))))

(defn user-entity?
  "True when an ordinary pulled entity is the one human user."
  [entity]
  (boolean (:seon.user/id entity)))

(defn outbound-hops
  "Base hops from one bounded authority query result; `message!` adds one.

   The ping-pong guard measures the depth of a back-and-forth within the
   SAME {me, peer} PAIR — reset at each human message — NOT the length of
   a delegation tree's wake chain. So this is the MAX hops among inbound
   messages to me whose `from` is one of THESE recipients AND that arrived
   AFTER the latest human message (the barrier); 0 when none — a fresh
   pair, first contact with this peer, or just-nudged by the human.

   Why per-peer + barrier rather than the GLOBAL newest inbound (the old
   rule): a parent delegating to childA then re-spawning childB walks
   DISTINCT pairs — each is its own conversation and must not inherit the
   other's depth. The old global-newest rule summed every wake-chain hop,
   so a routine two-round delegation (parent→A→parent→B→parent) hit the
   cap and silently deadlocked. Per-peer fixes that WITHOUT weakening the
   guard: a genuine A↔B↔A↔B runaway stays inside one pair, so its count
   still climbs every bounce and trips at `seon.warn/hop-cap`. A human
   message moves the barrier forward and resets every pair."
  [query-result]
  (or query-result 0))

(def ^:private sender-pull-pattern
  '[:db/id :seon.user/id :seon.agent/id])

(def ^:private participant-max-results 64)
(def ^:private human-message-max-results 65536)

(defn- query-member
  [database query arguments max-results]
  {::protocol/operation protocol/query-operation
   ::db/db database
   ::protocol/query-form query
   ::protocol/arguments arguments
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight 65536})

(defn- pull-many-member
  [database refs]
  {::protocol/operation protocol/pull-many-operation
   ::db/db database
   ::protocol/selector sender-pull-pattern
   ::protocol/entity-ids refs
   :datahike.resource/max-results participant-max-results
   :datahike.resource/max-result-weight 65536})

(defn- member-result
  [member]
  (or (::protocol/result member)
      (:datahike.query/result member)))

(defn- failure
  [message data]
  {:seon.error/message message
   :seon.error/data data})

(defn- resolved-participant?
  [entity]
  (and (map? entity)
       (or (:seon.user/id entity)
           (:seon.agent/id entity))))

(defn ^:async acquire-send-data
  "Acquire sender identity and hop depth from one immutable database value."
  [database from to-refs]
  (let [refs (vec (distinct (into [from] to-refs)))
        initial
        (await
         (db/execute-many
          {::db/members
           [(pull-many-member database refs)
            (query-member
             database
             '[:find (max ?at) .
               :where
               [?message :seon.agent.message/from ?user]
               [?user :seon.user/id _]
               [?message :seon.agent.message/at ?at]]
             [] human-message-max-results)]
           ::db/max-result-weight 131072}))]
    (if-not (and (= 2 (count (::db/results initial)))
                 (every? #(true? (::protocol/success? %))
                         (::db/results initial)))
      (failure "Message database acquisition failed." initial)
      (let [[entities-member barrier-member] (::db/results initial)
            entities (member-result entities-member)
            by-ref (zipmap refs entities)
            sender (get by-ref from)
            recipients (mapv by-ref to-refs)
            unresolved
            (into []
                  (remove #(resolved-participant? (get by-ref %)))
                  refs)
            sender-eid (:db/id sender)
            recipient-eids (mapv :db/id recipients)
            agent-tos
            (into []
                  (keep-indexed
                   (fn [index entity]
                     (when (:seon.agent/id entity)
                       (nth to-refs index)))
                   recipients))
            peer-eids (set recipient-eids)
            from-user? (user-entity? sender)
            barrier (or (member-result barrier-member) (js/Date. 0))]
        (cond
          (seq unresolved)
          (failure "Message sender or recipient does not resolve to a user or agent."
                   {:seon.agent.message/refs unresolved})

          (some #(= sender-eid %) recipient-eids)
          (failure "message!: refused self-recipient — sender and recipient must differ."
                   {:seon.agent.message/from from
                    :seon.agent.message/to to-refs})

          (or from-user? (empty? peer-eids))
          {::db/db database
           :seon.agent.message/from-user? from-user?
           :seon.agent.message/agent-tos agent-tos
           :seon.agent.message/hops 0}

          :else
          (let [hops
                (await
                 (db/query
                  {::db/db database
                   ::db/query
                   '[:find (max ?h) .
                     :in $ ?sender [?peer ...] ?barrier
                     :where
                     [?message :seon.agent.message/to ?sender]
                     [?message :seon.agent.message/from ?peer]
                     [?message :seon.agent.message/at ?at]
                     [(> ?at ?barrier)]
                     [(get-else $ ?message :seon.agent.message/hops 0) ?h]]
                   ::db/args [sender-eid (vec peer-eids) barrier]
                   ::db/max-results 64
                   ::db/max-result-weight 65536}))]
            (if (and (map? hops) (:seon.error/message hops))
              (failure "Message hop query failed." hops)
              {::db/db database
               :seon.agent.message/from-user? false
               :seon.agent.message/agent-tos agent-tos
               :seon.agent.message/hops (outbound-hops hops)})))))))
