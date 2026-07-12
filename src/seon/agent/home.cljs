(ns seon.agent.home
  "Agent home-namespace data and reads.

   The home namespace is a pure projection of an agent id. This namespace is
   the one lower owner of that projection, its canonical require data, the
   reactive per-agent require read, and the exact `(ns …)` source renderer.
   It depends only on config and database leaves, so agent creation, eval, and
   context rendering can all reuse the same data without a require cycle."
  (:require
    [clojure.string :as str]
    [seon.config :as config]
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

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]] :symbol]}
  [agent-id]
  (symbol (str "my.agent." agent-id)))

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
    [seon.agent :as agent]
    [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
    [seon.schema :as schema]
    [seon.db :as db]
    [my.plan :as plan]])

(defn home-requires-for
  "The require specs for agent `id`'s home ns.

   REACTIVE config-on-record
   (decision 2), resolved in precedence:

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
     2. else the `:seon.eval/home-requires` from `resolve-agent-context` — the
        fresh-MINT case, before the datom is written (the config/manifest value).
     3. else the [[home-ns-require-specs]] const (= byte-parity for a no-config
        agent). The const is the DEFAULT VALUE only."
  {:malli/schema [:=> [:catn [::id ::id]] ::require-specs]}
  [id]
  (or (when id
        ;; (1) the persisted datom, if the entity carries it (re-arm).
        (let [db (some-> db/*conn* deref)]
          (when (and db (contains? (db/installed-schema db) :seon.eval/home-requires))
            (some->> (:seon.eval/home-requires
                       (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))
                     (db/decode-edn-value :seon.eval/home-requires)
                     seq
                     vec))))
      ;; (2) the config/manifest value (fresh mint — datom not yet written).
      (when id
        (let [reqs (:seon.eval/home-requires (config/resolve-agent-context id nil))]
          (when (seq reqs) (vec reqs))))
      ;; (3) the const default.
      home-ns-require-specs))

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
  "Return the complete durable home-namespace entity for a new agent.

   `home-requires` must be the exact creation-time require list selected for
   the agent. Namespace source and structural dependency edges therefore
   commit in the same transaction as the agent identity instead of being
   discovered by a later analyzer side effect."
  {:malli/schema
   [:=>
    [:catn [:request
            [:map
             [:seon.agent/id ::agent-id]
             [:seon.eval/home-requires ::require-specs]]]]
    [:map
     [:seon.ns/name :keyword]
     [:seon.ns/source :string]
     [:seon.ns/require-edges [:vector ::require-edge]]]]}
  [{:seon.agent/keys [id] :seon.eval/keys [home-requires]}]
  (let [ns-sym (home-ns id)]
    {:seon.ns/name (keyword (str ns-sym))
     :seon.ns/source (home-ns-form ns-sym home-requires)
     :seon.ns/require-edges (vec (require-edges home-requires))}))
