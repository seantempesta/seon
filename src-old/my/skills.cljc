(ns my.skills
  "Discover skills in the database-backed catalog.

   This namespace covers catalog discovery plus file-backed and inline skill
   rows. Corpus discovery and source files remain startup configuration
   concerns."
  (:refer-clojure :exclude [load list])
  (:require
    [seon.agent.ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

;;; SCHEMA — register the attrs before any entity schema references them. A
;;; skill is identified by `:my.skills/name` (the catalog key AND the
;;; load/unload handle); the body source is the ATTRIBUTE PRESENCE of a
;;; file-path (file-backed) vs an inline body (agent-authored) — no `:kind`.

(schema/register! :my.skills/name        [:keyword {:seon.db/identity true}])
(schema/register! :my.skills/description [:string {:min 1}])   ; the catalog line; "Use when…" trigger
(schema/register! :my.skills/body        [:string {:min 1}])   ; inline body, ONLY for agent-authored skills

;; Config-driven agent-init CP-1 — WHICH skill bodies are always-on
;; (agent-level presence-set, decision 22a). The boot loader will transact
;; a :skill/<name> block per named skill; nothing reads this yet (purely
;; additive). Value type = the existing `:my.skills/name` handle.
(schema/register! ::load [:vector {:default [:repl]} :my.skills/name])

;; Function value shapes — map-out results + the derived catalog entry.
(schema/register! ::loaded? :boolean)

(schema/register! ::catalog-entry
  [:map
   [:my.skills/name        :my.skills/name]
   [:my.skills/description  :my.skills/description]
   [::loaded?              ::loaded?]])

(schema/register! ::list-response
  [:or [:vector ::catalog-entry]
   [:map [:seon.error/message :string]]])

;;; DERIVATION — loaded? is a pure projection of the agent's OWN ctx blocks
;;; (the `:skill/<name>` ones), never a stored flag.

(defn- loaded-skill-names
  "The set of skill names currently loaded in `agent-id`'s context — derived
   from its `:skill/<name>` block names, no stored flag. #{} when no agent or
   none loaded."
  [rows]
  (->> rows
       (filter #(= "skill" (namespace %)))
       (map #(keyword (name %)))
       set))

(defn- catalog-entries
  "The derived skill catalog over ordinary rows: one entry per `:my.skills/*` row
   (name + description), each marked `::loaded?` against `agent-id`'s own
   loaded `:skill/*` blocks. Sorted by name. Pure derivation."
  [catalog-rows loaded-rows]
  (let [loaded (loaded-skill-names loaded-rows)]
    (->> catalog-rows
         (sort-by first)
         (mapv (fn [[n d]]
                 {:my.skills/name        n
                  :my.skills/description  d
                  ::loaded?              (contains? loaded n)})))))

;;; FUNCTIONS — derived catalog query. The agent gets data.

(defn ^{:async true} list
  "The skill catalog: every available skill and whether YOU loaded it.

   Each entry carries its description and `::loaded?` — derived from your
   own `:skill/*` blocks. Read it to discover what you can `(load …)`.

     (my.skills/list)
     ; returns «vector: [{:my.skills/name :datahike, :my.skills/description \"…\", :my.skills/loaded? false} …]»"
  {:malli/schema [:=> [:cat] ::list-response]}
  []
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      database
      (let [agent-id (db/current-agent-id)
            catalog (await
                     (db/query
                      {:seon.db/db database
                       :seon.db/query '[:find ?n ?d
                                        :where
                                        [?e :my.skills/name ?n]
                                        [?e :my.skills/description ?d]]}))]
        (if (:seon.error/message catalog)
          catalog
          (let [loaded (if agent-id
                         (await
                          (db/query
                           {:seon.db/db database
                            :seon.db/query '[:find [?n ...]
                                             :in $ ?aid
                                             :where
                                             [?a :seon.agent/id ?aid]
                                             [?a :seon.agent/ctx ?b]
                                             [?b :seon.agent.ctx/name ?n]]
                            :seon.db/args [agent-id]}))
                         [])]
            (if (:seon.error/message loaded)
              loaded
              (catalog-entries catalog loaded))))))))
