(ns my.db
  "Query and transact the database with Datahike's own names and args.

  CONTRACT LAYER ONLY (Fable-authored, 2026-07-26): owner ruling — no new
  names for established functions. `q` and `transact` mirror
  `datahike.api` exactly, with one shortcut: omit the database input and
  the most recent database value is injected. Writes carry the
  `:seon.capability/op-id` replay identity the run loop derives from the
  executing receipt, so at-least-once re-execution is a replay, never a
  second write. Results are concise domain maps or flat error values.
  Stubs return honest not-implemented errors until the step-1
  implementation activates them over `seon.effect/request!`."
  (:require [seon.schema :as schema]
            [seon.effect :as effect]
            [datalog.parser :as datalog.parser]))

(defn datalog-query?
  "True when `query` parses under the database's own query grammar.
  Delegates to datalog-parser — the dependency's parser is the one
  validator; nothing here re-implements its grammar."
  [query]
  (try (some? (datalog.parser/parse query))
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
 ::query
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
 ::arg-map
 [:map {:closed true}
  [:tx-data [:vector {:min 1} ::tx-datum]]])

(schema/register!
 ::transacted
 [:map {:closed true}
  [:seon.db/t :int]
  [:seon.capability/op-id :seon.capability/op-id]
  [:seon.capability/replayed? {:optional true} :boolean]])

(schema/register!
 ::error
 [:map
  [:seon.error/message :string]
  [:seon.error/kind {:optional true} :qualified-keyword]])

(defn q
  "Run a Datalog query, exactly as datahike.api/q takes it.
  `(q query & inputs)` — when no database value appears among the
  inputs, the most recent database value is injected as the first
  input. Returns the result as ordinary data bounded by the admission
  caps, or a flat error value."
  {:malli/schema [:=> [:cat ::query [:* :seon.effect/args]]
                  [:or :seon.effect/value ::error]]}
  [query & inputs]
  (effect/request!
   {:seon.effect/family :db
    :seon.effect/args {:my.db/q query
                       :my.db/inputs (vec inputs)}}))

(defn transact
  "Commit transaction data, exactly as datahike.api/transact takes it.
  `(transact {:tx-data [...]})` — the connection is implied (the
  co-located writer). Returns the concise basis result; the run loop's
  replay identity makes re-execution after a crash a replay, never a
  second write."
  {:malli/schema [:=> [:cat ::arg-map] [:or ::transacted ::error]]}
  [arg-map]
  (effect/request!
   {:seon.effect/family :db
    :seon.effect/args {:my.db/tx-data (:tx-data arg-map)}}))
