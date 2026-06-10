(ns my.kb.instruction
  "Cluster-wide behavioral guidance as DATA — the FIRST worked `my.kb`
   domain (see the `my.kb` ns-doc for the design rule it models): a
   real entity schema, the shared `:my.kb/*` provenance attrs
   referenced (never redefined), rows edited at runtime BY TRANSACT —
   identity upsert on `:my.kb.instruction/id`:

     (seon.db/transact!
       {:seon.db/tx-data
        [{:my.kb.instruction/id   \"reply-every-asked-turn\"
          :my.kb.instruction/text \"…the amended guidance…\"}]})

   These are SHARED instructions — every agent in the cluster renders
   them (priority-ordered) in its `:instructions` context section.
   Per-agent doctrine lives on the agent's own `:seon.agent/ctx`."
  (:require
    [clojure.string :as str]
    [my.kb]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr. Provenance comes
;; --- from the shared :my.kb/* shapes (referenced at transact time).

(schema/register! ::id [:string {:seon.db/identity true}])  ; slug, e.g. "store-proactively"
(schema/register! ::text [:string {:min 1}])
(schema/register! ::priority :int)                          ; render order, smallest first
(schema/register! ::applies-when :string)                   ; when the instruction bites

;; --- The stored entity kind. `{:seon.db/entity true}` DECLARES that
;; --- rows of this shape live in the DB (puts the kind in the catalog).

(schema/register! ::instruction
  [:map {:seon.db/entity true}
   [::id           ::id]
   [::text         ::text]
   [::priority     ::priority]
   [::applies-when {:optional true} ::applies-when]
   [:my.kb/source-path {:optional true} :my.kb/source-path]
   [:my.kb/source-line {:optional true} :my.kb/source-line]
   [:my.kb/verified-at {:optional true} :my.kb/verified-at]
   [:my.kb/confidence  {:optional true} :my.kb/confidence]])

;; --- Boot seed — the substrate-shipped guidance every cluster starts
;; --- with. Identity upsert: re-seeding a store where a row was edited
;; --- by transact RE-ASSERTS the shipped text (the seed is the source
;; --- of truth for these four; durable edits belong in NEW rows).

(def ^:private seed-source-path "src/my/kb/instruction.cljs")

(defn seed-tx-data
  "Tx-data for the shipped cluster-wide instructions. Pure; the caller
   (`seon.client/seed-substrate!`) transacts under
   `:seon.db/origin :substrate-seed`. Values are static (no per-boot
   timestamps) so re-seeding asserts zero new datoms."
  {:malli/schema [:=> [:cat] [:vector ::instruction]]}
  []
  (mapv #(assoc %
                :my.kb/source-path seed-source-path
                :my.kb/confidence  :verified)
        [{::id       "consult-before-research"
          ::priority 10
          ::text     (str "Consult stored knowledge FIRST: check the schema-catalog "
                          "for my.kb.* attrs and datalog those exact keywords before "
                          "any research. Prior agents already answered many questions "
                          "— re-deriving a stored answer is wasted turns. Search the "
                          "repo only when no stored knowledge covers the question.")
          ::applies-when "any question about the repo, the system, or your human's data"}
         {::id       "store-proactively"
          ::priority 20
          ::text     (str "Store what you verify, without being asked: design (or "
                          "reuse) a my.kb.<domain> schema for the kind of knowledge "
                          "at hand, reference the shared :my.kb/* provenance attrs, "
                          "and transact the fact. Knowledge nobody stored is research "
                          "the next agent pays for again.")
          ::applies-when "whenever you verify a non-trivial result"}
         {::id       "reply-every-asked-turn"
          ::priority 30
          ::text     (str "A turn serving a question MUST end with "
                          "(seon.agent/reply! …) in the SAME response. Consulting, "
                          "searching and computing are never the end of the work — "
                          "your human sees NOTHING until reply! lands.")
          ::applies-when "every turn woken by a question"}
         {::id       "namespace-map"
          ::priority 40
          ::text     (str "Your code is my.*, your knowledge is my.kb.* (real "
                          "schemas per domain), and the substrate is seon.agent.* "
                          "plus the other seon.* namespaces — call substrate fns, "
                          "never redefine them.")}]))

;; --- Derived context view (same pattern as
;; --- seon.agent.todo/open-todos-section): pure render of the db,
;; --- vanishes when no rows exist — nothing stored, nothing to clear.

(defn instructions-block
  "Priority-ordered `[<id>] <text>` lines for every instruction row in
   db value `db`; \"\" when none."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]] :string]}
  [db]
  (let [rows (->> (db/query {:seon.db/query '[:find ?id ?p ?text
                                              :where
                                              [?i ::id ?id]
                                              [?i ::priority ?p]
                                              [?i ::text ?text]]
                             :seon.db/db db})
                  (sort-by (fn [[id p _]] [p id])))]
    (if (empty? rows)
      ""
      (str "<instructions>\n"
           ";; Cluster-wide guidance — :my.kb.instruction rows, priority-ordered.\n"
           ";; Runtime-editable: transact a row with the same :my.kb.instruction/id\n"
           ";; (identity upsert) to amend one.\n"
           (str/join "\n"
                     (map (fn [[id _ text]] (str "- [" id "] " text)) rows))
           "\n</instructions>"))))

(defn instructions-section
  "Context-section fn (`:instructions`, substrate-default-ctx priority
   15): [[instructions-block]] over the render's db value."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (instructions-block db))
