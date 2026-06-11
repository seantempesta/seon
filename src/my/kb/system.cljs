(ns my.kb.system
  "The SYSTEM-WIDE instruction surface — standing guidance shown to ALL
   agents in the cluster (context-v4 PRD §2.2, home 3). ONE `::system`
   entity (identity `::id` \"system\") carries a many-refs vector of
   instruction rows under `::instructions`; agents and the user keep
   APPENDING — one transact adds a row and the ref in the same nested
   map:

     (seon.db/transact!
       {:seon.db/tx-data
        [{:my.kb.system/id \"system\"
          :my.kb.system/instructions
          [{:my.kb.system/text \"Always store provenance (:my.kb/source-path) with findings.\"
            :my.kb.system/at   (js/Date.)}]}]})

   Reading is one fn call — `(my.kb.system/instructions)` — run as a
   real eval in every agent's creation turn (the startup tutorial), and
   re-run whenever the current set is wanted. Append-only for now: the
   whole surface is one transact and one read.

   The OTHER instruction homes (don't mix them up): per-agent standing
   orders go on the agent's OWN entity; identity/personality lives in
   `my.soul` (the API-level system message); static behavioral defaults
   are in the system prompt itself. This ns is only the cluster-wide,
   all-agents home."
  (:require
    [my.kb]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr. The singleton
;; --- upserts on ::id; rows are anonymous component children (they
;; --- live and die with the singleton, addressed only via the ref).

(schema/register! ::id [:string {:seon.db/identity true}])  ; the one entity: "system"
(schema/register! ::instructions [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! ::text [:string {:min 1}])
(schema/register! ::at :inst)                               ; append time — the read order

(schema/register! ::system
  [:map {:seon.db/entity true}
   [::id ::id]
   [::instructions {:optional true} ::instructions]])

;; --- Boot seed — the EMPTY zero state. The four shipped behavioral
;; --- teachings live in the system prompt (context-v4 §2.2 home 1),
;; --- NOT here: this entity starts with no rows and grows only by
;; --- runtime appends, which the seed never clobbers.

(defn seed-tx-data
  "Tx-data for the EMPTY system-instructions singleton. Identity
   upsert on `::id`, no `::instructions` value — idempotent (re-seeding
   asserts zero new datoms) and append-safe (rows transacted at runtime
   are never touched by a re-seed)."
  {:malli/schema [:=> [:cat] [:vector ::system]]}
  []
  [{::id "system"}])

(defn instructions
  "The current system-wide instructions — standing guidance for ALL
   agents in this cluster — as a vector of text strings, oldest append
   first. Anyone (your human, another agent, you) can append a row (see
   the ns doc for the one-transact append shape); re-run this whenever
   you want the current set. Returns [] when none exist yet.

   ;; read the current set:
   (my.kb.system/instructions)
   ;; => [\"Always store provenance (:my.kb/source-path) with findings.\"]"
  {:malli/schema [:=> [:cat] [:vector ::text]]}
  []
  (->> (db/query {:seon.db/query
                  '[:find ?at ?text
                    :where
                    [?s ::id "system"]
                    [?s ::instructions ?r]
                    [?r ::at ?at]
                    [?r ::text ?text]]})
       (sort-by (fn [[at text]] [(.getTime at) text]))
       (mapv second)))
