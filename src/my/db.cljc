(ns my.db
  "Query and transact the database — the flat my.* database tool.

  CONTRACT LAYER ONLY (Fable-authored, 2026-07-26): schemas and function
  contracts with honest not-implemented stubs; the step-1 implementation
  activates them over `seon.effect/request!`. Reads are pointers into an
  immutable database value (O1 co-location); writes carry the
  `:seon.capability/op-id` replay identity `seon.db/transact!` already
  honors, so a re-executed form replays its write instead of repeating
  it. Results are concise domain maps or flat error values."
  (:require [seon.schema :as schema]
            [seon.effect :as effect]
            [datalog.parser :as datalog.parser]))

(defn datalog-query?
  "True when `q` parses under the database's own query grammar.
  Delegates to datalog-parser — the dependency's parser is the one
  validator; nothing here re-implements its grammar."
  [q]
  (try (some? (datalog.parser/parse q))
       (catch #?(:clj Exception :cljs :default) _ false)))

(defn tx-datum?
  "True for one admissible transaction input.
  An entity map, or an operation vector such as [:db/add e a v] or
  [:db.fn/cas ...]."
  [x]
  (or (map? x)
      (and (vector? x) (keyword? (first x)))))

(schema/register-core-predicate! 'my.db/datalog-query? datalog-query?)
(schema/register-core-predicate! 'my.db/tx-datum? tx-datum?)

(schema/register!
 ::q
 [:fn {:error/message "must parse under datalog-parser's query grammar"
       :gen/schema [:= '[:find ?e :where [?e :seon.agent/id]]]}
  'my.db/datalog-query?])
(schema/register!
 ::tx-datum
 [:fn {:error/message
       "must be an entity map or an operation vector like [:db/add e a v]"
       :gen/schema [:map-of :keyword :string]}
  'my.db/tx-datum?])

(schema/register!
 ::query-request
 [:map {:closed true}
  [:seon.db/q ::q]
  [:seon.db/args {:optional true}
   [:vector :seon.effect/args]]])

(schema/register!
 ::transact-request
 [:map {:closed true}
  [:seon.db/tx-data [:vector {:min 1} ::tx-datum]]
  [:seon.capability/op-id {:optional true} :seon.capability/op-id]])

(schema/register!
 ::transacted
 [:map {:closed true}
  [:seon.db/t :int]
  [:seon.capability/op-id :seon.capability/op-id]])

(schema/register!
 ::error
 [:map
  [:seon.error/message :string]
  [:seon.error/kind {:optional true} :qualified-keyword]])

(defn q
  "Run one query against the current database value.
  Returns the result set as ordinary data bounded by the admission caps."
  {:malli/schema [:=> [:cat ::query-request]
                  [:or [:map {:closed true}
                        [:seon.db/result :seon.effect/value]]
                   ::error]]}
  [request]
  (effect/request!
   {:seon.effect/family :db
    :seon.effect/args request}))

(defn transact!
  "Commit transaction data; returns the concise basis result.
  Carries the replay identity so at-least-once re-execution is a
  replay, never a second write."
  {:malli/schema [:=> [:cat ::transact-request] [:or ::transacted ::error]]}
  [request]
  (effect/request!
   {:seon.effect/family :db
    :seon.effect/args request}))
