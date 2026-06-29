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
    [seon.db :as db]))

(defn clip-title
  "A SHORT single-line preview of message `content` for an address-todo
   title: internal newlines collapsed to spaces, trimmed, clipped to ~80
   chars with a trailing `…` when cut. Never blank (the todo title schema
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
  "Does `ref` resolve to THE user entity?"
  [ref]
  (boolean (:seon.user/id (db/entity {:seon.db/ref ref}))))

(defn- latest-user-at
  "Wall-clock of the most recent message FROM the user (any conversation),
   or nil when the user has never spoken. The hop-chain BARRIER: a human
   message resets every agent↔agent pair's ping-pong count."
  []
  (ffirst (db/query
            {:seon.db/query
             '[:find (max ?at)
               :where
               [?m :seon.agent.message/from ?u]
               [?u :seon.user/id _]
               [?m :seon.agent.message/at ?at]]})))

(defn outbound-hops
  "BASE hops for a new outbound from `agent-id` to recipients `to-refs`
   (the normalized `to` vector — lookup-refs or eids); `message!` adds 1.

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
  [agent-id to-refs]
  (let [my-eid    (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
        peer-eids (into #{} (keep #(:db/id (db/entity {:seon.db/ref %}))) to-refs)]
    (if (or (nil? my-eid) (empty? peer-eids))
      0
      (let [barrier (or (latest-user-at) (js/Date. 0))]
        (->> (db/query
               {:seon.db/query
                '[:find ?h
                  :in $ ?me [?peer ...] ?barrier
                  :where
                  [?m :seon.agent.message/to ?me]
                  [?m :seon.agent.message/from ?peer]
                  [?m :seon.agent.message/at ?at]
                  [(> ?at ?barrier)]
                  [(get-else $ ?m :seon.agent.message/hops 0) ?h]]
                :seon.db/args [my-eid (vec peer-eids) barrier]})
             (map first)
             (reduce max 0))))))
