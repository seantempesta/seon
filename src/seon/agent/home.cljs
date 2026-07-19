(ns seon.agent.home
  "Derive each agent's home namespace and require policy.

   This database-leaf namespace owns the canonical home-name projection,
   reactive require data, current-namespace selection, and namespace source
   rendering shared by creation, evaluation, and context assembly."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::id [:maybe ::agent-id])
(schema/register! ::home-ns [:or :symbol :string :keyword])
(schema/register! ::require-spec
  [:cat :symbol [:enum :as :refer] [:or :symbol [:vector :symbol]]])
(schema/register! ::require-specs [:vector ::require-spec])
(schema/register! ::require-edge
  [:map
   [:seon.ns.require/target :keyword]
   [:seon.ns.require/alias {:optional true} :symbol]
   [:seon.ns.require/refers {:optional true} [:set :symbol]]])
(schema/register! ::require-edges [:set ::require-edge])
(schema/register! ::error [:map [:seon.error/message :string]])
(schema/register! ::home-requires-result [:or ::require-specs ::error])

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]] :symbol]}
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn starting-ns
  "Return an agent's database-selected starting namespace.

   Older database values without the namespace ref retain the deterministic
   home namespace."
  {:malli/schema [:=> [:cat ::agent-id [:maybe :map]] :symbol]}
  [agent-id agent]
  (or (some-> agent :seon.agent/namespace :seon.ns/name name symbol)
      (home-ns agent-id)))

(def latest-successful-ns-query
  "Query for the latest successful eval namespace and its transaction."
  '{:find [?ns ?at ?eval-tx]
    :in [$ ?aid]
    :where [[?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/evals ?eval]
            [?eval :seon.eval/ok? true]
            [?eval :seon.eval/at ?at ?eval-tx]
            [?eval :seon.eval/ns ?ns]]
    :order-by [?at :desc ?eval-tx :desc]
    :limit 1})

(def namespace-assignment-query
  "Query for an agent's assigned namespace and assignment transaction."
  '[:find ?ns ?assignment-tx
    :in $ ?aid
    :where
    [?agent :seon.agent/id ?aid]
    [?agent :seon.agent/namespace ?namespace ?assignment-tx]
    [?namespace :seon.ns/name ?ns]])

(schema/register! ::latest-successful-ns
  [:tuple :seon.ns/name :inst :int])
(schema/register! ::namespace-assignment
  [:tuple :seon.ns/name :int])

(defn current-ns
  "Return the namespace selected by eval history and assignment."
  {:malli/schema
   [:=> [:catn
         [::agent-id ::agent-id]
         [::agent [:maybe :map]]
         [::latest-successful-ns [:maybe ::latest-successful-ns]]
         [::namespace-assignment [:maybe ::namespace-assignment]]]
    :seon.ns/name]}
  [agent-id agent latest-successful-ns namespace-assignment]
  (let [[eval-ns _ eval-tx] latest-successful-ns
        [assigned-ns assignment-tx] namespace-assignment]
    (keyword
     (name
      (cond
        (and assigned-ns
             (or (nil? eval-tx) (> assignment-tx eval-tx)))
        assigned-ns
        eval-ns eval-ns
        :else (starting-ns agent-id agent))))))

(def home-ns-require-specs
  "THE canonical require list every agent's home namespace is wired with —
   the single source of truth, shared by [[seon.eval/setup-agent-ns!]] (which INSTALLS
   it) and `seon.agent.ctx.namespaces/cur-ns-workspace-stub` (which RENDERS it
   VERBATIM into the agent's workspace block). No parallel reconstruction, no
   hidden aliasing: the agent SEES the exact aliases and requires. Lifecycle
   examples remain fully qualified so they keep resolving after namespace
   movement; `(message/user …)`, `(schema/register! …)`, and `(db/transact! …)`
   use the aliases carried by authored namespaces.

   Each entry is a `(require …)`-style spec — `[ns :as alias]` or
   `[ns :refer [functions…]]` — `pr-str`'d straight into the `(ns … (:require …))`
   head by [[home-ns-form]]."
  '[[seon.agent.message :as message]
    [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
    [seon.schema :as schema]
    [seon.db :as db]
    [my.plan :as plan]])

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn ^:async home-requires-for
  "The require specs for agent `id`'s home ns.

   Resolved from durable data in precedence:

   The one-argument form resolves the current database value. The two-argument
   form reuses the supplied immutable database value so a larger acquisition
   cannot mix snapshots.

     1. the agent's `:seon.eval/home-requires` DATOM, when present — the
        re-arm case (the entity exists). A live
        `(db/transact! {:seon.agent/id id :seon.eval/home-requires […]})`
        drives the next `setup-agent-ns!`, so the dial is reactive, not
        write-only. (Mixed-`:or` schema → stored `pr-str`'d → decode on read.)
        The attr is NOT in the boot schema; it self-installs on that first
        override transact (`ensure-datahike-attrs!` runs inside `transact!`),
        so by read time the `installed-schema` gate below is TRUE whenever a
        datom exists. The gate is not a no-op: on a fresh pod with no override
        yet, the attr is uninstalled and querying it would THROW — the gate
        makes the read fall to (2) instead. VERIFIED live (scratch conn):
        transact → attr installs, value round-trips through decode.
     2. else the [[home-ns-require-specs]] canonical data. Fresh creation
        resolves configuration in `seon.agent/initial-agent-tx` and persists
        the selected requires before this reader can observe the agent."
  {:malli/schema
   [:function
    [:=> [:catn [::id ::id]] ::home-requires-result]
    [:=> [:catn [::database :seon.db/db] [::id ::id]]
     ::home-requires-result]]}
  ([id]
   (if-not id
     home-ns-require-specs
     (let [database (await (db/db))]
       (if (error-value? database)
         database
         (await (home-requires-for database id))))))
  ([database id]
   (if-not id
     home-ns-require-specs
     (let [installed (await (db/installed-schema database))]
       (if (error-value? installed)
         installed
         (let [agent
               (when (contains? installed :seon.eval/home-requires)
                 (await (db/entity database [:seon.agent/id id])))]
           (if (error-value? agent)
             agent
             (or
              ;; (1) the persisted datom, if the entity carries it (re-arm).
              (some->> (:seon.eval/home-requires agent)
                       (db/decode-edn-value :seon.eval/home-requires)
                       seq
                       vec)
              ;; (2) canonical data for agents without a persisted override.
              home-ns-require-specs))))))))

(defn home-ns-form
  "The exact `(ns …)` SOURCE wired into an agent's home ns.

   The one form [[seon.eval/setup-agent-ns!]] evaluates AND the one the
   workspace block renders verbatim, with every alias/refer visible (no
   bare-name reconstruction). `home-ns` is the home-ns symbol/string/keyword
   (e.g. `my.agent.<id>`).

   Two arities: the 1-arg renders the DEFAULT [[home-ns-require-specs]] (the
   stub/preview shape); the 2-arg takes the resolved `specs` for a specific
   agent ([[home-requires-for]]) — `setup-agent-ns!` passes the per-agent list
   so a `:seon.eval/home-requires` override actually wires the agent's ns."
  {:malli/schema [:function
                  [:=> [:catn [::home-ns ::home-ns]] :string]
                  [:=> [:catn [::home-ns ::home-ns]
                              [::specs ::require-specs]] :string]]}
  ([home-ns-value] (home-ns-form home-ns-value home-ns-require-specs))
  ([home-ns-value specs]
   (str "(ns " (name home-ns-value) "\n  (:require "
        (str/join "\n            " (map pr-str specs))
        "))")))

(defn require-edges
  "Project canonical home require specs into durable require-edge facts.

   The specs are already structured data; this function never parses the
   rendered `(ns …)` text. Each `[target :as alias]` or
   `[target :refer [symbols…]]` becomes the same component-map shape used by
   the program graph."
  {:malli/schema [:=> [:catn [::specs ::require-specs]] ::require-edges]}
  [specs]
  (into #{}
        (map (fn [[target mode value]]
               (cond-> {:seon.ns.require/target (keyword (str target))}
                 (= :as mode)
                 (assoc :seon.ns.require/alias value)

                 (= :refer mode)
                 (assoc :seon.ns.require/refers (set value)))))
        specs))

(defn initial-ns-entity
  "Return the complete durable starting-namespace entity for a new agent.

   `namespace` is the ClojureScript namespace symbol the agent starts in;
   `home-requires` is the exact creation-time require list selected for the
   agent. Namespace source and structural dependency edges therefore commit in
   the same transaction as the agent identity instead of being discovered by a
   later analyzer side effect."
  {:malli/schema
   [:=>
    [:catn [:request
            [:map
             [:seon.agent/namespace :symbol]
             [:seon.eval/home-requires ::require-specs]]]]
    [:map
     [:seon.ns/name :keyword]
     [:seon.ns/source :string]
     [:seon.ns/require-edges [:vector ::require-edge]]]]}
  [{namespace :seon.agent/namespace
    :seon.eval/keys [home-requires]}]
  {:seon.ns/name (keyword (str namespace))
   :seon.ns/source (home-ns-form namespace home-requires)
   :seon.ns/require-edges (vec (require-edges home-requires))})
