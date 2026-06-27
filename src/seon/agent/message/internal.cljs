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

(defn waking-hops
  "Hops of the NEWEST inbound message (to ∋ me, from ≠ me), or 0 when
   none. This — not the loop's original waking message — is the hops
   base for agent-originated sends: a long-running loop keeps replying
   while new inbound messages arrive, and deriving from the ORIGINAL
   waking message would pin hops constant forever (two agents would
   ping-pong at a fixed hop count and never reach the cap). The latest
   inbound climbs with the chain, so replies carry climbing hops and the
   guard actually bites."
  [agent-id]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))]
    (or (when my-eid
          (->> (db/query
                 {:seon.db/query
                  '[:find ?at ?h
                    :in $ ?me
                    :where
                    [?m :seon.agent.message/to ?me]
                    [?m :seon.agent.message/from ?f]
                    [(not= ?f ?me)]
                    [?m :seon.agent.message/at ?at]
                    [(get-else $ ?m :seon.agent.message/hops 0) ?h]]
                  :seon.db/args [my-eid]})
               (sort-by #(.getTime ^js (first %)))
               last
               second))
        0)))
