(ns seon.db.protocol
  "Semantic messages exchanged with the authoritative database writer.

   This namespace owns protocol DATA only: fully namespaced request/response
   keys, keyword operations and errors, durable request-receipt attributes,
   validation, and pure constructors. A transport encodes these maps but never
   interprets them; the JVM writer interprets them but never invents a second
   envelope.

   The durable request receipt deliberately shares `::request-id` with the
   transaction request. One logical write therefore has one identity from
   delivery through recovery."
  (:require [hasch.core :as hasch]
            [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.schema :as schema]))

;;; Protocol vocabulary

(def ping-operation :seon.db.protocol.operation/ping)
(def ensure-database-operation
  :seon.db.protocol.operation/ensure-database)
(def transact-operation :seon.db.protocol.operation/transact)
(def replay-transactions-operation
  :seon.db.protocol.operation/replay-transactions)
(def knn-search-operation :seon.db.protocol.operation/knn-search)

(def transaction-event :seon.db.protocol.event/transaction)

(def protocol-error :seon.db.protocol.error/protocol)
(def database-error :seon.db.protocol.error/database)
(def internal-error :seon.db.protocol.error/internal)
(def not-found-error :seon.db.protocol.error/not-found)
(def request-conflict-error :seon.db.protocol.error/request-conflict)
(def stale-basis-error :seon.db.protocol.error/stale-basis)
(def generated-candidate-conflict-error
  :seon.db.protocol.error/generated-candidate-conflict)

(def committed-status :seon.db.protocol.status/committed)
(def unknown-status :seon.db.protocol.status/unknown)
(def feed-behind-status :seon.db.protocol.status/feed-behind)

(def current-version 1)

;;; Shared schemas

(schema/register!
 ::operation
 [:enum ping-operation
  ensure-database-operation
  transact-operation
  replay-transactions-operation
  knn-search-operation])
(schema/register! ::success? :boolean)
(schema/register! ::pong? :boolean)
(schema/register! ::database-name [:string {:min 1}])
(schema/register! ::database-path [:string {:min 1}])
(schema/register! ::backend [:enum :memory :file])
(schema/register! ::basis-t [:int {:min 0}])
(schema/register! ::basis-t-before [:int {:min 0}])
(schema/register! ::expected-basis-t [:int {:min 0}])
(schema/register! ::current-basis-t [:int {:min 0}])
(schema/register! ::since-t [:int {:min 0}])
(schema/register! ::through-t [:int {:min 0}])
(schema/register! ::continuation-t [:int {:min 0}])
(schema/register! ::complete? :boolean)
(schema/register! ::replayed-count [:int {:min 0}])
(schema/register! ::datoms-added [:int {:min 0}])
(schema/register! ::datoms-retracted [:int {:min 0}])
(schema/register! ::request-id [:string {:min 1}])
(schema/register! ::request-hash :uuid)
(schema/register! ::version [:int {:min 1}])
(schema/register! ::transaction-data [:vector :any])
(schema/register! ::transaction-meta :map)
(schema/register! ::temporary-ids :map)
(schema/register! ::generated-candidates [:vector :any])
(schema/register! ::generated-candidate :any)
(schema/register! ::generated-entity-ids :map)
(schema/register! ::recovered? :boolean)
(schema/register! ::event [:enum transaction-event])
(schema/register! ::query [:string {:min 1}])
(schema/register! ::limit [:int {:min 1}])
(schema/register! ::entity-ids [:vector :int])
(schema/register! ::hits [:vector :map])
(schema/register!
 ::error-kind
 [:enum protocol-error database-error internal-error not-found-error
  request-conflict-error stale-basis-error
  generated-candidate-conflict-error])
(schema/register! ::error [:string {:min 1}])
(schema/register!
 ::status
 [:enum committed-status unknown-status feed-behind-status])
(schema/register! ::attempts [:int {:min 1}])
(schema/register! ::transport-failure :keyword)

(schema/register!
 ::transaction-event-map
 [:map
  [::event [:= transaction-event]]
  [::database-name ::database-name]
  [::basis-t ::basis-t]
  [::basis-t-before ::basis-t-before]
  [::transaction-data ::transaction-data]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::request-id {:optional true} ::request-id]
  [::datoms-added ::datoms-added]
  [::datoms-retracted ::datoms-retracted]])
(schema/register! ::events [:vector ::transaction-event-map])

(schema/register! :seon.db.protocol.tempid/key-edn :string)
(schema/register! :seon.db.protocol.tempid/entity :seon.db/ref)

(schema/register!
 ::ping-request
 [:map [::operation [:= ping-operation]]])
(schema/register!
 ::ensure-database-request
 [:map
  [::operation [:= ensure-database-operation]]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]])
(schema/register!
  ::transaction-request
  [:map
  [::operation [:= transact-operation]]
  [::database-name ::database-name]
  [::request-id ::request-id]
  [::transaction-data ::transaction-data]
  [::expected-basis-t {:optional true} ::expected-basis-t]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
  ::replay-transactions-request
  [:map
  [::operation [:= replay-transactions-operation]]
  [::database-name ::database-name]
  [::since-t ::since-t]
  [::through-t {:optional true} ::through-t]])
(schema/register!
  ::knn-search-request
  [:map
  [::operation [:= knn-search-operation]]
  [::database-name ::database-name]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::entity-ids]])

(schema/register!
 ::request
 [:multi {:dispatch ::operation}
  [ping-operation ::ping-request]
  [ensure-database-operation ::ensure-database-request]
  [transact-operation ::transaction-request]
  [replay-transactions-operation ::replay-transactions-request]
  [knn-search-operation ::knn-search-request]])
(schema/register!
 ::failed-response
 [:map
  [::success? [:= false]]
  [::error-kind ::error-kind]
  [::error ::error]
  [::expected-basis-t {:optional true} ::expected-basis-t]
  [::current-basis-t {:optional true} ::current-basis-t]])
(schema/register!
 ::ping-response
 [:map
  [::success? [:= true]]
  [::pong? ::pong?]])
(schema/register!
 ::ensure-database-response
 [:map
  [::success? [:= true]]
  [::database-name ::database-name]
  [::coordinate/coordinate ::coordinate/coordinate]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]])
(schema/register!
 ::transaction-response
 [:map
  [::success? [:= true]]
  [::request-id ::request-id]
  [::basis-t ::basis-t]
  [::basis-t-before ::basis-t-before]
  [::temporary-ids ::temporary-ids]
  [::transaction-data ::transaction-data]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::datoms-added ::datoms-added]
  [::datoms-retracted ::datoms-retracted]
  [::generated-entity-ids {:optional true} ::generated-entity-ids]
  [::recovered? {:optional true} ::recovered?]])
(schema/register!
 ::replay-transactions-response
 [:map
  [::success? [:= true]]
  [::database-name ::database-name]
  [::since-t ::since-t]
  [::through-t ::through-t]
  [::continuation-t ::continuation-t]
  [::complete? ::complete?]
  [::events ::events]
  [::replayed-count ::replayed-count]])
(schema/register!
 ::knn-search-response
 [:map
  [::success? [:= true]]
  [::hits ::hits]])
(schema/register!
 ::response
 [:or
  ::failed-response
  ::ping-response
  ::ensure-database-response
  ::transaction-response
  ::replay-transactions-response
  ::knn-search-response])

(schema/register! ::body :map)
(schema/register!
 ::failure-request
 [:map
  [::error-kind ::error-kind]
  [::error ::error]
  [::body {:optional true} ::body]])
(schema/register!
 ::ensure-request-input
 [:map
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]])
(schema/register!
  ::transaction-request-input
  [:map
  [::database-name ::database-name]
  [::request-id ::request-id]
  [::transaction-data ::transaction-data]
  [::expected-basis-t {:optional true} ::expected-basis-t]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
  ::replay-request-input
  [:map
  [::database-name ::database-name]
  [::since-t ::since-t]
  [::through-t {:optional true} ::through-t]])
(schema/register!
  ::knn-request-input
  [:map
  [::database-name ::database-name]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::entity-ids]])

;;; Pure constructors and validation

(defn ping-request
  "Construct the writer readiness request."
  {:malli/schema [:=> [:cat] ::ping-request]}
  []
  {::operation ping-operation})

(defn ensure-database-request
  "Construct one idempotent database-open request."
  {:malli/schema [:=> [:cat ::ensure-request-input]
                  ::ensure-database-request]}
  [{::keys [database-name backend database-path]}]
  (cond-> {::operation ensure-database-operation
           ::database-name database-name
           ::backend backend}
    database-path (assoc ::database-path database-path)))

(defn transaction-request
  "Construct one idempotent logical transaction request."
  {:malli/schema [:=> [:cat ::transaction-request-input]
                  ::transaction-request]}
  [{::keys [database-name request-id transaction-data transaction-meta
            expected-basis-t generated-candidates]
    :as input}]
  (cond-> {::operation transact-operation
           ::database-name database-name
           ::request-id request-id
           ::transaction-data transaction-data}
    (some? expected-basis-t) (assoc ::expected-basis-t expected-basis-t)
    (seq transaction-meta) (assoc ::transaction-meta transaction-meta)
    (contains? input ::generated-candidates)
    (assoc ::generated-candidates generated-candidates)))

(defn replay-transactions-request
  "Construct one bounded transaction-history page request."
  {:malli/schema [:=> [:cat ::replay-request-input]
                  ::replay-transactions-request]}
  [{::keys [database-name since-t through-t]}]
  (cond-> {::operation replay-transactions-operation
           ::database-name database-name
           ::since-t since-t}
    (some? through-t) (assoc ::through-t through-t)))

(defn knn-search-request
  "Construct one bounded embedding-neighbor request."
  {:malli/schema [:=> [:cat ::knn-request-input] ::knn-search-request]}
  [{::keys [database-name query limit entity-ids]}]
  (cond-> {::operation knn-search-operation
           ::database-name database-name
           ::query query
           ::limit limit}
    (seq entity-ids) (assoc ::entity-ids entity-ids)))

(defn success
  "Add the canonical successful response fact to `body`."
  {:malli/schema [:=> [:catn [::body ::body]] ::response]}
  [body]
  (assoc body ::success? true))

(defn failure
  "Construct the canonical failed response."
  {:malli/schema [:=> [:cat ::failure-request] ::response]}
  [{::keys [error-kind error body]}]
  (assoc (or body {})
         ::success? false
         ::error-kind error-kind
         ::error error))

(defn valid-request?
  "True when `request` is one complete canonical protocol request."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [request]
  (m/validate ::request request))

(defn explain-request
  "Malli explanation for an invalid request, or nil when valid."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [request]
  (m/explain ::request request))

(defn valid-response?
  "True when `response` is one complete canonical protocol response."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [response]
  (m/validate ::response response))

(defn explain-response
  "Malli explanation for an invalid response, or nil when valid."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [response]
  (m/explain ::response response))

;;; Durable idempotency receipt

(def receipt-schema
  "Raw Datahike declarations for the protocol receipt attributes."
  [{:db/ident ::request-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::request-hash
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident ::version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.db.protocol.tempid/key-edn
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.db.protocol.tempid/entity
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def receipt-attributes
  #{:seon.db.protocol.tempid/key-edn
    :seon.db.protocol.tempid/entity})

(def reserved-attributes
  (into receipt-attributes #{::request-id ::request-hash ::version}))

(defn logical-transaction-hash
  "Map-order-independent fingerprint of one logical transaction request."
  {:malli/schema [:=> [:cat ::transaction-request] :uuid]}
  [request]
  (hasch/uuid
   {::version current-version
    ::transaction-data (::transaction-data request)
    ::expected-basis-t (::expected-basis-t request)
    ::transaction-meta (or (::transaction-meta request) {})
    ::generated-candidates (or (::generated-candidates request) [])}))

(defn tempid-receipts
  "Build collision-free same-transaction markers for caller tempids."
  {:malli/schema [:=> [:catn [::request-id ::request-id]
                            [:seon.db.protocol/tempids [:sequential :any]]]
                  [:vector :map]]}
  [request-id tempids]
  (let [used (set tempids)]
    (mapv
     (fn [index tempid]
       (let [marker-id
             (loop [salt 0]
               (let [candidate (str "seon.db.protocol.tempid/" request-id
                                    "/" index "/" salt)]
                 (if (contains? used candidate)
                   (recur (inc salt))
                   candidate)))]
         {:db/id marker-id
          :seon.db.protocol.tempid/key-edn (pr-str tempid)
          :seon.db.protocol.tempid/entity tempid}))
     (range)
     tempids)))
