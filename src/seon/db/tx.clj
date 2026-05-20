(ns seon.db.tx
  "Transaction metadata for Datahike writes.

   Every transaction gets `:seon.db.tx/at`, `:seon.db.tx/caller`, and
   `:seon.db.tx/source` automatically. Callers can pass additional
   metadata (session-id, agent-ns, op, reason) which gets merged onto
   the transaction entity via `:db/current-tx`.

   Datahike does NOT auto-add timestamps (unlike Datomic), so we must."
  (:require [seon.db.schema :as dbs]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas — registered globally so agents can discover them
;;; ---------------------------------------------------------------------------

(schema/register! ::at :inst)
(schema/register! ::caller :string)
(schema/register! ::source [:enum :agent :system :user :repl :migration])
(schema/register! ::session-id [:string {:min 4 :max 4}])
(schema/register! ::agent-ns :string)
(schema/register! ::op [:enum :create :update :delete :sync :scan :import])
(schema/register! ::reason :string)

(schema/register! ::extra
                  [:map
                   [::source {:optional true} [:enum :agent :system :user :repl :migration]]
                   [::session-id {:optional true} [:string {:min 4 :max 4}]]
                   [::agent-ns {:optional true} :string]
                   [::op {:optional true} [:enum :create :update :delete :sync :scan :import]]
                   [::reason {:optional true} :string]])

(def entity-schema
  "Malli schema for transaction metadata. Single source of truth.
   ::at, ::caller, ::source are always present (set by build-tx-entity).
   ::session-id, ::agent-ns, ::op, ::reason come from extra and may be absent."
  [:map
   [::at :inst]
   [::caller :string]
   [::source [:enum :agent :system :user :repl :migration]]
   [::session-id {:optional true} [:string {:min 4 :max 4}]]
   [::agent-ns {:optional true} :string]
   [::op {:optional true} [:enum :create :update :delete :sync :scan :import]]
   [::reason {:optional true} :string]])

(dbs/register-entity-schema! "seon.db.tx" entity-schema)

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
  {:malli/schema [:=> [:cat :string [:maybe ::extra]]
                  [:map
                   [:db/id :keyword]
                   [::at :inst]
                   [::caller :string]
                   [::source [:enum :agent :system :user :repl :migration]]
                   [::session-id {:optional true} [:string {:min 4 :max 4}]]
                   [::agent-ns {:optional true} :string]
                   [::op {:optional true} [:enum :create :update :delete :sync :scan :import]]
                   [::reason {:optional true} :string]]]}
  [caller-ns extra]
  (let [base {:db/id          :db/current-tx
              ::at             (java.util.Date.)
              ::caller         (str caller-ns)
              ::source         (or (::source extra)
                                   (infer-source caller-ns))}]
    (merge base (dissoc extra ::source))))
