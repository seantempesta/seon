(ns my.kb.shared
  "The SYSTEM-WIDE instruction surface — standing guidance shown to ALL
   agents in the cluster (context-v4 PRD §2.2, home 3). ONE `::shared`
   entity (identity `::id` \"shared\") carries a many-refs vector of
   instruction rows under `::instructions`; agents and the user keep
   APPENDING — one transact adds a row and the ref in the same nested
   map:

     (db/transact!
       {:seon.db/tx-data
        [{:my.kb.shared/id \"shared\"
          :my.kb.shared/instructions
          [{:my.kb.shared/text \"Always store provenance (:my.kb/source-path) with findings.\"
            :my.kb.shared/at   (js/Date.)}]}]})

   These rows are shown to EVERY agent automatically, every turn — the
   `:shared-instructions` context section ([[instructions-block]])
   renders them live from the db, so an append here is durable standing
   guidance, not a one-off. Reading is one fn call —
   `(my.kb.shared/instructions)`. Append-only for now.

   The OTHER instruction homes (don't mix them up): per-agent standing
   orders go on the agent's OWN entity; identity/personality lives in the
   SOUL.md / AGENTS.md files (read LIVE as context file-sections —
   `seon.agent.ctx/file-block`); static behavioral defaults are the hardcoded
   system prompt (`seon.agent.ctx/system-text`). This ns is only the
   cluster-wide, all-agents home."
  (:require
    [clojure.string :as str]
    [my.kb]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr. The singleton
;; --- upserts on ::id; rows are anonymous component children (they
;; --- live and die with the singleton, addressed only via the ref).

(schema/register! ::id [:string {:seon.db/identity true}])  ; the one entity: "shared"
(schema/register! ::instructions [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! ::text [:string {:min 1}])
(schema/register! ::at :inst)                               ; append time — the read order
(schema/register! ::instruction-texts [:vector ::text])
(schema/register! ::instructions-result [:or ::instruction-texts ::db/error])

(schema/register! ::shared
  [:map {:seon.db/entity true}
   [::id ::id]
   [::instructions {:optional true} ::instructions]])

;; --- Boot seed — the EMPTY zero state. The four shipped behavioral
;; --- teachings live in the system prompt (context-v4 §2.2 home 1),
;; --- NOT here: this entity starts with no rows and grows only by
;; --- runtime appends, which the seed never clobbers.

(defn seed-tx-data
  "Tx-data for the EMPTY shared-instructions singleton.

   Identity upsert on `::id`, no `::instructions` value — idempotent
   (re-seeding asserts zero new datoms) and append-safe (rows transacted at
   runtime are never touched by a re-seed)."
  {:malli/schema [:=> [:cat] [:vector ::shared]]}
  []
  [{::id "shared"}])

(defn ^{:async true :seon.fn/agent-facing? true} instructions
  "The current cluster-wide instructions, oldest append first.

   Standing guidance for ALL agents in this cluster, as a vector of text
   strings. Anyone (your human, another agent, you) can append a row (see
   the ns doc for the one-transact append shape); re-run this whenever
   you want the current set. Returns [] when none exist yet.

   ; read the current authority value:
   (my.kb.shared/instructions)
   ; reuse an immutable database value already acquired by a larger operation:
   (my.kb.shared/instructions {:seon.db/db database})
   ; returns «vector: [\"Always store provenance (:my.kb/source-path) with findings.\"]»"
  {:malli/schema
   [:function
    [:=> [:cat] ::instructions-result]
    [:=> [:cat [:map {:closed true} [::db/db ::db/db]]]
     ::instructions-result]]}
  ([]
   (let [database (or (::db/db (db/current-tx-context))
                      (await (db/db)))]
     (if (and (map? database)
              (string? (:seon.error/message database)))
       database
       (await (instructions {::db/db database})))))
  ([{database ::db/db}]
   (let [result
         (await
          (db/query
           {::db/db database
            ::db/query '[:find ?at ?text
                         :where
                         [?s ::id "shared"]
                         [?s ::instructions ?r]
                         [?r ::at ?at]
                         [?r ::text ?text]]}))]
     (if (and (map? result) (string? (:seon.error/message result)))
       result
       (->> result
            (sort-by (fn [[at text]] [(.getTime at) text]))
            (mapv second))))))

(defn- format-instructions-block
  [items]
  (if (empty? items)
    ""
    (str "; SHARED INSTRUCTIONS\n"
         "; Standing guidance for ALL agents in this cluster (the shared\n"
         "; KB, not the system prompt). When your human gives a durable\n"
         "; instruction, append a row so every agent — present and\n"
         "; future — sees it here.\n"
         (str/join "\n"
                   (map-indexed (fn [i text]
                                  (str ";   " (inc i) ". " text))
                                items)))))

(defn ^:async instructions-block
  "The single-`;` `SHARED INSTRUCTIONS` context block.

   Cluster-wide standing guidance shown to EVERY agent, every turn. This is
   the SHARED
   knowledge-base surface, NOT the hardcoded system prompt
   (`seon.agent.ctx/system-text`) — it never overrides those mechanics, it adds
   durable human/agent guidance alongside them. Rendered FRESH from the db
   ([[instructions]] over the turn's immutable `:seon.db/db`), so it is reactive:
   append a row and it appears next turn; retract it and the line is gone
   — nothing stored that needs clearing. Empty string when none exist, so
   the whole section vanishes until the first instruction lands.
   Symbol-wired into the composer (`config manifest`) as
   `'my.kb.shared/instructions-block`."
  {:malli/schema [:=> [:cat :map] :string]}
  [{database ::db/db}]
  (let [result (await (if database
                        (instructions {::db/db database})
                        (instructions)))]
    (if (and (map? result) (string? (:seon.error/message result)))
      (throw
       (ex-info (:seon.error/message result)
                (cond-> result
                  (nil? (:seon.error/kind result))
                  (assoc :seon.error/kind :core-bug))))
      (format-instructions-block result))))
