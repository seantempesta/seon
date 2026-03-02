(ns seon.db.tx
  "Transaction metadata for Datalevin writes.

   Every transaction gets `:seon.db.tx/at`, `:seon.db.tx/caller`, and
   `:seon.db.tx/source` automatically. Callers can pass additional
   metadata (session-id, agent-ns, op, reason) which gets merged onto
   the transaction entity via `:db/current-tx`.

   Datalevin does NOT auto-add timestamps (unlike Datomic), so we must."
  (:require [seon.db.schema :as dbs]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas — registered globally so agents can discover them
;;; ---------------------------------------------------------------------------

(schema/register! ::at inst?)
(schema/register! ::caller :string)
(schema/register! ::source [:enum :agent :system :user :repl :migration])
(schema/register! ::session-id [:string {:min 4 :max 4}])
(schema/register! ::agent-ns :string)
(schema/register! ::op [:enum :create :update :delete :sync :scan :import])
(schema/register! ::reason :string)

(def entity-schema
  "Malli schema for transaction metadata. Single source of truth."
  [:map
   [::at inst?]
   [::caller :string]
   [::source [:enum :agent :system :user :repl :migration]]
   [::session-id :string]
   [::agent-ns :string]
   [::op [:enum :create :update :delete :sync :scan :import]]
   [::reason :string]])

(def datalevin-schema
  "Datalevin schema for transaction metadata attributes.
   Derived from entity-schema via the bridge. Merge into every database's schema."
  (dbs/malli-map->datalevin-schema entity-schema))

;;; ---------------------------------------------------------------------------
;;; Builder
;;; ---------------------------------------------------------------------------

(defn- infer-source
  "Infer transaction source from caller namespace string."
  [caller]
  (cond
    (nil? caller)                        :system
    (re-find #"^user$|^user\." caller)   :repl
    (re-find #"\.agent" caller)          :agent
    :else                                :system))

(defn build-tx-entity
  "Build the transaction entity map for `:db/current-tx`.

   Always includes `::at`, `::caller`, `::source`.
   Merges any extra `:seon.db.tx/*` keys from `extra`."
  {:malli/schema [:=> [:cat :string [:maybe [:map-of :keyword :any]]]
                  [:map-of :keyword :any]]}
  [caller-ns extra]
  (let [base {:db/id          :db/current-tx
              ::at             (java.util.Date.)
              ::caller         (str caller-ns)
              ::source         (or (::source extra)
                                   (infer-source caller-ns))}]
    (merge base (dissoc extra ::source))))
