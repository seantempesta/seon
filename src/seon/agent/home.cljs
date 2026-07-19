(ns seon.agent.home
  "Agent home-namespace data and reads.

   The home namespace is a pure projection of an agent id. This namespace is
   the one lower owner of that projection, its canonical require data, the
   reactive per-agent require read, and the exact `(ns …)` source renderer.
   It depends only on the database leaf, so agent creation, eval, and context
   rendering can all reuse the same data without a require cycle."
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

   A later successful eval's `:seon.eval/ns` remains the current namespace.
   This value is only the fallback before that history exists. Older database
   values without the namespace ref retain the deterministic home namespace."
  {:malli/schema [:=> [:cat ::agent-id [:maybe :map]] :symbol]}
  [agent-id agent]
  (or (some-> agent :seon.agent/namespace :seon.ns/name name symbol)
      (home-ns agent-id)))

(def home-ns-require-specs
  "THE canonical require list every agent's home namespace is wired with —
   the single source of truth, shared by [[seon.eval/setup-agent-ns!]] (which INSTALLS
   it) and `seon.agent.ctx.namespaces/cur-ns-workspace-stub` (which RENDERS it
   VERBATIM into the agent's workspace block). No parallel reconstruction, no
   hidden aliasing: the agent SEES the exact aliases/refers its reflexive
   `(message/user …)` / `(wait …)` / `(schema/register! …)` / `(db/transact! …)`
   forms resolve against.

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
