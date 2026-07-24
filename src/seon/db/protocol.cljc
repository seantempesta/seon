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
  (:require [clojure.set :as set]
            #?@(:bb [] :default [[hasch.core :as hasch]])
            #?@(:cljs [[cognitect.transit :as transit]
                       [goog.object :as gobj]
                       [cljs.reader :as reader]]
                :default [[clojure.edn :as edn]])
            [seon.content-hash]
            [seon.db.branch :as branch]
            [seon.db.restore.schema]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]))

;;; Protocol vocabulary

(def ping-operation :seon.db.protocol.operation/ping)
(def session-open-operation :seon.db.protocol.operation/session-open)
(def capabilities-operation :seon.db.protocol.operation/capabilities)
(def resolve-head-operation :seon.db.protocol.operation/resolve-head)
(def query-operation :seon.db.protocol.operation/query)
(def pull-operation :seon.db.protocol.operation/pull)
(def pull-many-operation :seon.db.protocol.operation/pull-many)
(def schema-operation :seon.db.protocol.operation/schema)
(def index-page-operation :seon.db.protocol.operation/index-page)
(def execute-many-operation :seon.db.protocol.operation/execute-many)
(def listen-operation :seon.db.protocol.operation/listen)
(def unlisten-operation :seon.db.protocol.operation/unlisten)
(def cancel-operation :seon.db.protocol.operation/cancel)
(def ensure-database-operation
  :seon.db.protocol.operation/ensure-database)
(def acquire-database-operation
  :seon.db.protocol.operation/acquire-database)
(def observe-database-lifecycle-operation
  :seon.db.protocol.operation/observe-database-lifecycle)
(def create-branch-operation :seon.db.protocol.operation/create-branch)
(def release-database-operation
  :seon.db.protocol.operation/release-database)
(def delete-branch-operation :seon.db.protocol.operation/delete-branch)
(def transact-operation :seon.db.protocol.operation/transact)
(def resolve-transaction-branch-head-operation
  :seon.db.protocol.operation/resolve-transaction-branch-head)
(def knn-search-operation :seon.db.protocol.operation/knn-search)

(def datoms-event :seon.db.protocol.event/datoms)
(def resynchronization-event :seon.db.protocol.event/resynchronization)
(def database-advanced-event :seon.db.protocol.event/database-advanced)

(def protocol-error :seon.db.protocol.error/protocol)
(def database-error :seon.db.protocol.error/database)
(def internal-error :seon.db.protocol.error/internal)
(def not-found-error :seon.db.protocol.error/not-found)
(def request-conflict-error :seon.db.protocol.error/request-conflict)
(def stale-database-value-error :seon.db.protocol.error/stale-database-value)
(def generated-candidate-conflict-error
  :seon.db.protocol.error/generated-candidate-conflict)
(def duplicate-route-error :seon.db.protocol.error/duplicate-route)
(def duplicate-connection-id-error :seon.db.protocol.error/duplicate-connection-id)
(def connection-id-mismatch-error :seon.db.protocol.error/connection-id-mismatch)
(def stale-source-head-error :seon.db.protocol.error/stale-source-head)
(def stale-target-head-error :seon.db.protocol.error/stale-target-head)
(def missing-commit-error :seon.db.protocol.error/missing-commit)
(def unsupported-history-error :seon.db.protocol.error/unsupported-history)
(def cut-not-branchable-error :seon.db.protocol.error/cut-not-branchable)
(def branch-exists-error :seon.db.protocol.error/branch-exists)
(def branch-missing-error :seon.db.protocol.error/branch-missing)
(def protected-main-branch-error
  :seon.db.protocol.error/protected-main-branch)
(def active-branch-error :seon.db.protocol.error/active-branch)
(def initializer-error :seon.db.protocol.error/initializer)
(def release-error :seon.db.protocol.error/release)
(def cleanup-required-error :seon.db.protocol.error/cleanup-required)
(def stale-branch-roster-error
  :seon.db.protocol.error/stale-branch-roster)
(def restore-divergence-error
  :seon.db.protocol.error/restore-divergence)
(def non-ancestor-error :seon.db.protocol.error/non-ancestor)
(def ambiguous-history-error
  :seon.db.protocol.error/ambiguous-history)
(def incompatible-version-error
  :seon.db.protocol.error/incompatible-version)
(def connection-capacity-error
  :seon.db.protocol.error/connection-capacity)
(def session-open-required-error
  :seon.db.protocol.error/session-open-required)
(def frame-too-large-error
  :seon.db.protocol.error/frame-too-large)

(def lifecycle-error-kinds
  #{duplicate-route-error duplicate-connection-id-error connection-id-mismatch-error
    stale-source-head-error stale-target-head-error missing-commit-error
    unsupported-history-error cut-not-branchable-error branch-exists-error
    branch-missing-error protected-main-branch-error active-branch-error
    initializer-error release-error cleanup-required-error
    stale-branch-roster-error restore-divergence-error non-ancestor-error
    ambiguous-history-error})

(def committed-status :seon.db.protocol.status/committed)
(def unknown-status :seon.db.protocol.status/unknown)
(def feed-behind-status :seon.db.protocol.status/feed-behind)

(def current-version 14)

(def session-open-request-id "session/open")
(def session-control-request-id "session/control")
(def session-open-maximum-frame-bytes 4096)

;; One wire contract must reject the same legal frame on every host. Paging and
;; operation-level result bounds remain the preferred way to stay well below it.
(def maximum-frame-bytes (* 4 1024 1024))

(def writer-process :seon.dev.process/writer)

;;; Ordinary wire values

#?(:clj
   (def ^:private byte-array-class (Class/forName "[B")))

(defn ordinary-wire-value?
  "True when `value` is eager data supported by every protocol host."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (cond
    (or (fn? value)
        (record? value)
        #?(:clj (or (instance? clojure.lang.IDeref value)
                    (instance? java.lang.Thread value)
                    (instance? java.util.concurrent.Future value)
                    (instance? Throwable value))
           :cljs (or (satisfies? IDeref value)
                     (instance? js/Promise value)
                     (instance? js/Error value))))
    false

    (map? value)
    (and (every? ordinary-wire-value? (keys value))
         (every? ordinary-wire-value? (vals value)))

    (vector? value)
    (every? ordinary-wire-value? value)

    (set? value)
    (every? ordinary-wire-value? value)

    (list? value)
    (every? ordinary-wire-value? value)

    (sequential? value)
    false

    :else
    (or (nil? value)
        (boolean? value)
        (number? value)
        (string? value)
        (keyword? value)
        (symbol? value)
        (uuid? value)
        (inst? value)
        #?(:clj
           (or (instance? java.net.URI value)
               (instance? byte-array-class value))
           :cljs
           (or (instance? js/Uint8Array value)
               (transit/integer? value)
               (transit/bigint? value)
               (transit/bigdec? value)
               (transit/uri? value)
               (and (transit/tagged-value? value)
                    (= "ratio" (gobj/get value "tag"))
                    (let [representation (gobj/get value "rep")]
                      (and (vector? representation)
                           (= 2 (count representation))
                           (every? ordinary-wire-value?
                                   representation)))))))))

;;; Shared schemas

(schema/register-core-predicate!
 'seon.db.protocol/ordinary-wire-value?
 ordinary-wire-value?)

(schema/register!
 ::ordinary-wire-value
 [:fn {:error/message "must be eager ordinary protocol data"
       :gen/schema
       [:or :nil :boolean :int :double :string :keyword :symbol
        [:vector {:max 8} [:or :nil :boolean :int :string :keyword]]]}
  'seon.db.protocol/ordinary-wire-value?])

(defn- one-temporal-bound?
  [value]
  (not (and (some? (:as-of value))
            (some? (:since value)))))

(schema/register!
 ::operation
 [:enum ping-operation
  session-open-operation
  capabilities-operation
  resolve-head-operation
  query-operation
  pull-operation
  pull-many-operation
  schema-operation
  index-page-operation
  execute-many-operation
  listen-operation
  unlisten-operation
  cancel-operation
  ensure-database-operation
  acquire-database-operation
  observe-database-lifecycle-operation
  create-branch-operation
  release-database-operation
  delete-branch-operation
  transact-operation
  resolve-transaction-branch-head-operation
  knn-search-operation])
(schema/register! ::success? :boolean)
(schema/register! ::pong? :boolean)
(schema/register! ::capabilities :map)
(schema/register! ::maximum-frame-bytes [:int {:min 1}])
(schema/register! ::configured-maximum-frame-bytes [:int {:min 1}])
(schema/register! ::maximum-connections [:int {:min 1}])
(schema/register! ::peer-version [:int {:min 1}])
(schema/register! ::configuration-key :qualified-keyword)
;; Datahike query and pull values are intentionally polymorphic data. The
;; canonical request/response validators apply `ordinary-wire-value?`
;; recursively while preserving native result shapes and legitimate bare keys.
(schema/register! ::result ::ordinary-wire-value)
(schema/register! ::arguments [:vector ::ordinary-wire-value])
(schema/register! ::selector ::ordinary-wire-value)
(schema/register! ::entity-id :any)
(schema/register! ::query-form
                  [:or [:vector :any] :map [:string {:min 1}]])
(schema/register! :seon.db/db
                  [:map {:closed true}
                   [:db-name [:string {:min 1}]]
                   [:store-id [:vector {:min 2 :max 3} :any]]
                   [:t [:int {:min 0}]]
                   [:as-of [:or :nil [:int {:min 0}] :inst]]
                   [:since [:or :nil [:int {:min 0}] :inst]]
                   [:history :boolean]
                   [:datahike/commit-id :uuid]])
(schema/register! :seon.db/expected-db :seon.db/db)
(schema/register! :seon.db/current-db :seon.db/db)

(defn database-value?
  "True when `value` is one complete ordinary database value."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (schema/valid-candidate-value? :seon.db/db value)
       (one-temporal-bound? value)))
(schema/register! :datahike.resource/max-work [:int {:min 1}])
(schema/register! :datahike.resource/max-results [:int {:min 1}])
(schema/register! :datahike.resource/max-result-weight [:int {:min 1}])
(schema/register! :datahike.query/result :any)
(schema/register! :datahike.query/cache-evidence :map)
(schema/register! :datahike.query/resource-evidence :map)
(schema/register! ::database-name [:string {:min 1}])
(schema/register! ::database-path [:string {:min 1}])
(schema/register! ::backend [:enum :memory :file])
(schema/register! :seon.db/program
                  [:vector [:map-of :qualified-keyword :any]])
(schema/register! :seon.db/attributes [:vector :qualified-keyword])
(schema/register! :seon.db/initial-data
                  [:vector [:map-of :qualified-keyword :any]])
(schema/register! :seon.execution/artifact-digest
                  :seon.content-hash/digest)
(schema/register! :seon.db.initialization/page-rows [:int {:min 1}])
(schema/register! :seon.db.initialization/fingerprint
                  [:string {:min 1}])
(schema/register! :seon.db.initialization/page-fingerprint
                  [:string {:min 1}])
(schema/register! :seon.db.initialization/identities :string)
(schema/register! :seon.db.initialization/page-index [:int {:min 0}])
(schema/register! :seon.db.initialization/page-count [:int {:min 1}])
(schema/register!
 :seon.db.initialization/phase
 [:enum :seon.db.initialization.phase/schema
  :seon.db.initialization.phase/attributes
  :seon.db.initialization.phase/program
  :seon.db.initialization.phase/initial-data
  :seon.db.initialization.phase/completion])
(schema/register!
 :seon.db/initialization-page
 [:map {:closed true}
  [:seon.db.initialization/fingerprint
   :seon.db.initialization/fingerprint]
  [:seon.db.initialization/page-index
   :seon.db.initialization/page-index]
  [:seon.db.initialization/page-count
   :seon.db.initialization/page-count]
  [:seon.db.initialization/page-rows
   :seon.db.initialization/page-rows]
  [:seon.db.initialization/phase
   :seon.db.initialization/phase]
  [:seon.db/attributes {:optional true} :seon.db/attributes]
  [:seon.db/program {:optional true} :seon.db/program]
  [:seon.db/initial-data {:optional true} :seon.db/initial-data]])
(schema/register! :seon.db/initialization-pages
                  [:vector {:min 2} :seon.db/initialization-page])
(schema/register! :seon.db.initialization/release-digest
                  :seon.content-hash/digest)
(schema/register! :seon.db.initialization/config-manifest-digest
                  :seon.content-hash/digest)
(schema/register!
 :seon.db/raw-initialization
 [:map {:closed true}
  [:seon.execution/artifact-digest :seon.content-hash/digest]
  [:seon.db.initialization/config-manifest-digest
   :seon.content-hash/digest]
  [:seon.db.initialization/page-rows
   :seon.db.initialization/page-rows]
  [:seon.db/attributes :seon.db/attributes]
  [:seon.db/program :seon.db/program]
  [:seon.db/initial-data :seon.db/initial-data]])
(schema/register!
 :seon.db/precomputed-initialization
 [:map {:closed true}
  [:seon.execution/artifact-digest :seon.content-hash/digest]
  [:seon.db.initialization/config-manifest-digest
   :seon.content-hash/digest]
  [:seon.db/initialization-pages :seon.db/initialization-pages]])
(schema/register!
 :seon.db/initialization
 [:or :seon.db/raw-initialization
  :seon.db/precomputed-initialization])
(schema/register! :seon.db.initialization/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.db.initialization/status
                  [:enum :seon.db.initialization.status/in-progress
                   :seon.db.initialization.status/complete])
(schema/register!
 :seon.db.initialization/entity
 [:map {:seon.db/entity true}
  [:seon.db.initialization/id :seon.db.initialization/id]
  [:seon.db.initialization/fingerprint
   :seon.db.initialization/fingerprint]
  [:seon.db.initialization/page-count
   :seon.db.initialization/page-count]
  [:seon.db.initialization/status :seon.db.initialization/status]
  [:seon.db.initialization/release-digest
   {:optional true} :seon.content-hash/digest]
  [:seon.db.initialization/config-manifest-digest
   {:optional true} :seon.content-hash/digest]])
(schema/register! ::branch-head ::branch/head)
(schema/register!
 ::containing-branch-head
 [:map {:closed true}
  [::branch/store-id ::branch/store-id]
  [::branch/name [:= :db]]
  [::branch/commit-id ::branch/commit-id]
  [::branch/basis-t ::branch/basis-t]])
(schema/register! ::transaction-id ::branch/basis-t)
(schema/register! ::source-branch-head ::branch/head)
(schema/register! ::expected-source-head ::branch/head)
(schema/register! ::expected-target-head ::branch/head)
(schema/register! ::source-head ::branch/head)
(schema/register! ::target-connection-id ::branch/connection-id)
(schema/register! ::target-branch :keyword)
(schema/register! ::main-branch-head ::branch/head)
(schema/register! ::branch-heads
                  [:map-of :keyword ::branch/head])
(schema/register! ::branch-roster [:set :keyword])
(schema/register! ::restore-completion-branch-heads
                  [:map-of :seon.db.restore/id ::branch/head])
(schema/register! ::source-database-name ::database-name)
(schema/register! ::target-database-name ::database-name)
(schema/register! ::created? :boolean)
(schema/register! ::adopted? :boolean)
(schema/register! ::acquired? :boolean)
(schema/register! ::released? :boolean)
(schema/register! ::deleted? :boolean)
(schema/register! ::request-id
                  [:string {:min 1 :seon.db/identity true}])
(schema/register! ::target-request-id ::request-id)
(schema/register! ::canceled? :boolean)
(schema/register! ::running? :boolean)
(schema/register! ::request-hash :uuid)
(schema/register! ::version [:int {:min 1}])
(schema/register! ::transaction-data [:vector :any])
(schema/register! ::transaction-meta :map)
(schema/register!
 ::generated-candidate
 [:map {:closed true}
  [:seon.db.id/key :qualified-keyword]
  [:seon.db.id/identity-attr :qualified-keyword]
  [:seon.db.id/value :seon.db/id]
  [:seon.db.id/dependent-lookup-refs {:optional true}
   [:vector {:min 1}
    [:tuple :qualified-keyword :seon.db/lookup-ref-value]]]])
(schema/register! ::generated-candidates
                  [:vector {:min 1} ::generated-candidate])
(schema/register! ::generated-entity-ids
                  [:map-of :qualified-keyword :int])
(schema/register! ::recovered? :boolean)
(schema/register!
 ::event
 [:enum datoms-event resynchronization-event database-advanced-event])
(schema/register! ::query [:string {:min 1}])
(schema/register! ::limit [:int {:min 1}])
(schema/register! ::index [:enum :eavt :aevt :avet])
(schema/register! ::prefix [:vector {:max 4} :any])
(schema/register! ::direction [:enum :forward :reverse])
(schema/register! ::index-page-limit [:int {:min 1 :max 200}])
(schema/register! ::datom [:tuple :int :keyword :any :int :boolean])
(schema/register! ::datoms [:vector ::datom])
(schema/register! ::cursor ::datom)
(schema/register! :datahike.index-page/datoms ::datoms)
(schema/register! :datahike.index-page/complete? :boolean)
(schema/register! :datahike.index-page/cursor ::cursor)
(schema/register! ::schema :map)
(schema/register!
 :datahike.query/attribute-dependencies
 [:or [:= :all] [:set :keyword]])
(schema/register!
 :datahike.query.source/attributes
 :datahike.query/attribute-dependencies)
(schema/register!
 :datahike.query.source/dependency
 [:map {:closed true}
  [:datahike.query.source/symbol :symbol]
  [:datahike.query.source/argument-position [:int {:min 0}]]
  [:datahike.query.source/attributes
   :datahike.query.source/attributes]])
(schema/register!
 :datahike.query.dependency/sources
 [:vector :datahike.query.source/dependency])
(schema/register!
 :datahike.read/dependency-plan
 [:or
  [:= :all]
  [:map {:closed true}
   [:datahike.query.dependency/sources
    :datahike.query.dependency/sources]]])
(schema/register! :seon.db/source-argument-position [:int {:min 0}])
(schema/register!
 :seon.db/read-dependency
 [:map {:closed true}
  [:seon.db/db :seon.db/db]
  [:seon.db/source-argument-position :seon.db/source-argument-position]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]])
(schema/register! :seon.db/read-evidence
                  [:vector :seon.db/read-dependency])
(schema/register!
 ::datom-pattern
 [:map {:closed true}
  [:seon.db/a :keyword]
  [:seon.db/e {:optional true} :int]
  [:seon.db/v {:optional true} :any]
  [:seon.db/added? {:optional true} :boolean]])
(schema/register! ::datom-patterns
                  [:vector {:min 1 :max 64} ::datom-pattern])
(schema/register! ::listening? :boolean)
(schema/register! ::entity-ids [:vector :any])
(schema/register! ::knn-entity-ids [:vector :int])
(schema/register! ::hits [:vector :map])
(schema/register! :db-before :seon.db/db)
(schema/register! :db-after :seon.db/db)
(schema/register! :tx-data ::datoms)
(schema/register! :tempids :map)
(schema/register! :tx-meta :map)
(schema/register!
 ::error-kind
 [:enum protocol-error database-error internal-error not-found-error
  request-conflict-error stale-database-value-error
  generated-candidate-conflict-error
  duplicate-route-error duplicate-connection-id-error connection-id-mismatch-error
  stale-source-head-error stale-target-head-error missing-commit-error
  unsupported-history-error cut-not-branchable-error branch-exists-error
  branch-missing-error protected-main-branch-error active-branch-error
  initializer-error release-error cleanup-required-error
  stale-branch-roster-error restore-divergence-error non-ancestor-error
  ambiguous-history-error incompatible-version-error connection-capacity-error
  session-open-required-error frame-too-large-error])
(schema/register!
 ::ordinary-error-kind
 [:enum protocol-error database-error internal-error not-found-error
  request-conflict-error stale-database-value-error
  generated-candidate-conflict-error
  duplicate-route-error duplicate-connection-id-error connection-id-mismatch-error
  stale-source-head-error stale-target-head-error missing-commit-error
  unsupported-history-error cut-not-branchable-error branch-exists-error
  branch-missing-error protected-main-branch-error active-branch-error
  initializer-error release-error cleanup-required-error
  stale-branch-roster-error restore-divergence-error non-ancestor-error
  ambiguous-history-error])
(schema/register! ::error [:string {:min 1}])
(schema/register!
 ::status
 [:enum committed-status unknown-status feed-behind-status])
(schema/register! ::attempts [:int {:min 1}])
(schema/register! ::transport-failure :keyword)

;; The containment owner transports this application value as opaque EDN.
;; Keep the complete database-release shape portable so the JVM writer and
;; Babashka operator validate the same value without loading writer resources.
(schema/register!
 ::writer-release-result
 [:map {:closed true}
  [:seon.db.registry/database-name :keyword]
  [:seon.db.registry/connection-id ::branch/connection-id]
  [:seon.db.registry/branch-head ::branch/head]
  [:seon.db.registry/released? :boolean]
  [:seon.db.registry/release-error {:optional true}
   [:string {:min 1}]]])
(schema/register! ::writer-release-results
                  [:vector ::writer-release-result])
(schema/register!
 ::writer-stop-response
 [:map {:closed true}
  [:seon.db.writer/stopped? :boolean]
  [:seon.db.writer/release-results ::writer-release-results]])
(schema/register!
 ::server-stop-response
 [:map {:closed true}
  [:seon.db.server/stopped? :boolean]
  [:seon.db.server/release-results ::writer-release-results]])
(schema/register!
 :seon.db.terminal/generation
 [:and :string
  [:re "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"]])
(schema/register! :seon.db.terminal/process [:= writer-process])
(schema/register! :seon.db.terminal/completed? :boolean)
(schema/register! :seon.db.terminal/stop-response ::server-stop-response)
(schema/register! :seon.db.terminal/stop-error
                  [:string {:min 1 :max 4096}])
(schema/register!
 ::completed-writer-terminal-result
 [:map {:closed true}
  [:seon.db.terminal/generation :seon.db.terminal/generation]
  [:seon.db.terminal/process :seon.db.terminal/process]
  [:seon.db.terminal/completed? [:= true]]
  [:seon.db.terminal/stop-response :seon.db.terminal/stop-response]])
(schema/register!
 ::failed-writer-terminal-result
 [:map {:closed true}
  [:seon.db.terminal/generation :seon.db.terminal/generation]
  [:seon.db.terminal/process :seon.db.terminal/process]
  [:seon.db.terminal/completed? [:= false]]
  [:seon.db.terminal/stop-error :seon.db.terminal/stop-error]])
(schema/register!
 ::writer-terminal-result
 [:or ::completed-writer-terminal-result ::failed-writer-terminal-result])

(schema/register! :seon.db.protocol.tempid/string-key :string)
(schema/register! :seon.db.protocol.tempid/int-key :int)
(schema/register! :seon.db.protocol.tempid/entity :seon.db/ref)

(schema/register!
 ::ping-request
 [:map {:closed true}
  [::operation [:= ping-operation]]
  [::request-id ::request-id]])
(schema/register!
 ::session-open-request
 [:map {:closed true}
  [::operation [:= session-open-operation]]
  [::request-id [:= session-open-request-id]]
  [::version [:= current-version]]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::capabilities-request
 [:map {:closed true}
  [::operation [:= capabilities-operation]]
  [::request-id ::request-id]])
(schema/register!
 ::resolve-head-request
 [:map {:closed true}
  [::operation [:= resolve-head-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]])
(schema/register!
 ::query-request
 [:map {:closed true}
  [::operation [:= query-operation]]
  [::request-id ::request-id]
  [:seon.db/db {:optional true} :seon.db/db]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-request
 [:map {:closed true}
  [::operation [:= pull-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-id ::entity-id]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-many-request
 [:map {:closed true}
  [::operation [:= pull-many-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::schema-request
 [:map {:closed true}
  [::operation [:= schema-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]])
(schema/register!
 ::index-page-request
 [:map {:closed true}
  [::operation [:= index-page-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::cursor {:optional true} ::cursor]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::query-member
 [:map {:closed true}
  [::operation [:= query-operation]]
  [:seon.db/db {:optional true} :seon.db/db]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-member
 [:map {:closed true}
  [::operation [:= pull-operation]]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-id ::entity-id]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-many-member
 [:map {:closed true}
  [::operation [:= pull-many-operation]]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::schema-member
 [:map {:closed true}
  [::operation [:= schema-operation]]
  [:seon.db/db :seon.db/db]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]])
(schema/register!
 ::index-page-member
 [:map {:closed true}
  [::operation [:= index-page-operation]]
  [:seon.db/db :seon.db/db]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::cursor {:optional true} ::cursor]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::member
 [:multi {:dispatch ::operation}
  [query-operation ::query-member]
  [pull-operation ::pull-member]
  [pull-many-operation ::pull-many-member]
  [schema-operation ::schema-member]
  [index-page-operation ::index-page-member]])
(schema/register! ::members [:vector {:min 1 :max 64} ::member])
(schema/register!
 ::execute-many-request
 [:map {:closed true}
  [::operation [:= execute-many-operation]]
  [::request-id ::request-id]
  [::members ::members]
  [:datahike.resource/max-result-weight
   :datahike.resource/max-result-weight]])
(schema/register!
 ::cancel-request
 [:map {:closed true}
  [::operation [:= cancel-operation]]
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]])
(schema/register!
 ::query-listen-request
 [:map {:closed true}
  [::operation [:= listen-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::query-form ::query-form]])
(schema/register!
 ::dependency-plan-listen-request
 [:map {:closed true}
  [::operation [:= listen-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]])
(schema/register!
 ::read-evidence-listen-request
 [:map {:closed true}
  [::operation [:= listen-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:seon.db/read-evidence :seon.db/read-evidence]])
(schema/register!
 ::datom-listen-request
 [:map {:closed true}
  [::operation [:= listen-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::datom-patterns ::datom-patterns]])
(schema/register! ::listen-request
                  [:or ::read-evidence-listen-request
                   ::dependency-plan-listen-request
                   ::query-listen-request ::datom-listen-request])
(schema/register!
 ::unlisten-request
 [:map {:closed true}
  [::operation [:= unlisten-operation]]
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]])
(schema/register!
 ::ensure-database-request
 [:map {:closed true}
  [::operation [:= ensure-database-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::branch/connection-id {:optional true} ::branch/connection-id]
  [:seon.db/initialization-page
   {:optional true} :seon.db/initialization-page]])
(schema/register!
 ::acquire-database-request
 [:map {:closed true}
  [::operation [:= acquire-database-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::database-advanced? {:optional true} :boolean]])
(schema/register!
 ::observe-database-lifecycle-request
 [:map {:closed true}
  [::operation [:= observe-database-lifecycle-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]])
(schema/register!
 ::create-branch-request
 [:map {:closed true}
  [::operation [:= create-branch-operation]]
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::source-branch-head ::source-branch-head]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]])
(schema/register!
 ::release-database-request
 [:map {:closed true}
  [::operation [:= release-database-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]])
(schema/register!
 ::delete-branch-request
 [:map {:closed true}
  [::operation [:= delete-branch-operation]]
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::target-connection-id ::target-connection-id]
  [::expected-target-head ::expected-target-head]])
(schema/register!
  ::transaction-request
  [:map {:closed true}
  [::operation [:= transact-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::transaction-data ::transaction-data]
  [:seon.db/expected-db {:optional true} :seon.db/expected-db]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
 ::resolve-transaction-branch-head-request
 [:map {:closed true}
  [::operation [:= resolve-transaction-branch-head-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::containing-branch-head ::containing-branch-head]
  [::transaction-id ::transaction-id]])
(schema/register!
  ::knn-search-request
  [:map {:closed true}
  [::operation [:= knn-search-operation]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::knn-entity-ids]])

(schema/register!
 ::request
 [:multi {:dispatch ::operation}
  [session-open-operation ::session-open-request]
  [ping-operation ::ping-request]
  [capabilities-operation ::capabilities-request]
  [resolve-head-operation ::resolve-head-request]
  [query-operation ::query-request]
  [pull-operation ::pull-request]
  [pull-many-operation ::pull-many-request]
  [schema-operation ::schema-request]
  [index-page-operation ::index-page-request]
  [execute-many-operation ::execute-many-request]
  [listen-operation ::listen-request]
  [unlisten-operation ::unlisten-request]
  [cancel-operation ::cancel-request]
  [ensure-database-operation ::ensure-database-request]
  [acquire-database-operation ::acquire-database-request]
  [observe-database-lifecycle-operation
   ::observe-database-lifecycle-request]
  [create-branch-operation ::create-branch-request]
  [release-database-operation ::release-database-request]
  [delete-branch-operation ::delete-branch-request]
  [transact-operation ::transaction-request]
  [resolve-transaction-branch-head-operation
   ::resolve-transaction-branch-head-request]
  [knn-search-operation ::knn-search-request]])
(schema/register!
 ::failed-response
 [:map
  [::success? [:= false]]
 [::request-id ::request-id]
 [::error-kind ::ordinary-error-kind]
 [::error ::error]
  [:seon.error/kind {:optional true} :keyword]
  [::generated-candidate {:optional true} ::generated-candidate]
  [:seon.db/expected-db {:optional true} :seon.db/expected-db]
  [:seon.db/current-db {:optional true} :seon.db/current-db]])
(schema/register!
 ::session-open-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id [:= session-open-request-id]]
  [::version [:= current-version]]
  [::configured-maximum-frame-bytes ::configured-maximum-frame-bytes]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::incompatible-version-response
 [:map {:closed true}
  [::success? [:= false]]
  [::request-id [:= session-open-request-id]]
  [::error-kind [:= incompatible-version-error]]
  [::error ::error]
  [:seon.error/kind [:= :configuration]]
  [::version [:= current-version]]
  [::peer-version ::peer-version]])
(schema/register!
 ::connection-capacity-response
 [:map {:closed true}
  [::success? [:= false]]
  [::request-id [:= session-open-request-id]]
  [::error-kind [:= connection-capacity-error]]
  [::error ::error]
  [:seon.error/kind [:= :configuration]]
  [::configuration-key
   [:= :seon.config.database.transport/maximum-connections]]
  [::maximum-connections ::maximum-connections]])
(schema/register!
 ::session-open-required-response
 [:map {:closed true}
  [::success? [:= false]]
  [::request-id ::request-id]
  [::error-kind [:= session-open-required-error]]
  [::error ::error]])
(schema/register!
 ::frame-too-large-response
 [:map {:closed true}
  [::success? [:= false]]
  [::request-id ::request-id]
  [::error-kind [:= frame-too-large-error]]
  [::error ::error]
  [::configuration-key
   [:= :seon.config.database.transport/maximum-frame-bytes]]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::ping-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::pong? ::pong?]])
(schema/register!
 ::capabilities-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::capabilities ::capabilities]])
(schema/register!
 ::resolve-head-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]])
(schema/register!
 ::query-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:datahike.query/result :datahike.query/result]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]
  [:datahike.query/attribute-dependencies
   :datahike.query/attribute-dependencies]
  [:datahike.query/cache-evidence :datahike.query/cache-evidence]
  [:datahike.query/resource-evidence :datahike.query/resource-evidence]])
(schema/register!
 ::read-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]
  [::result ::result]])
(schema/register!
 ::schema-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]
  [::schema ::schema]])
(schema/register!
 ::index-page-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]
  [:datahike.index-page/datoms :datahike.index-page/datoms]
  [:datahike.index-page/complete? :datahike.index-page/complete?]
  [:datahike.index-page/cursor {:optional true}
   :datahike.index-page/cursor]])
(schema/register! ::member-response :any)
(schema/register! ::results [:vector ::member-response])
(schema/register!
 ::execute-many-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::results ::results]])
(schema/register!
 ::cancel-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]
  [::canceled? ::canceled?]
  [::running? ::running?]])
(schema/register!
 ::listen-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:db-after :db-after]
  [::listening? [:= true]]])
(schema/register!
 ::unlisten-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]
  [::listening? [:= false]]])
(schema/register!
 ::datoms-event-map
 [:map {:closed true}
  [::event [:= datoms-event]]
  [::request-id ::request-id]
  [:db-before :db-before]
  [:db-after :db-after]
  [:tx-data :tx-data]
  [:tempids :tempids]
  [:tx-meta :tx-meta]])
(schema/register!
 ::resynchronization-event-map
 [:map {:closed true}
  [::event [:= resynchronization-event]]
  [::request-id ::request-id]
  [:db-after :db-after]])
(schema/register!
 ::database-advanced-event-map
 [:map {:closed true}
  [::event [:= database-advanced-event]]
  [:db-after :db-after]])
(schema/register!
 ::ensure-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [:seon.db/db :seon.db/db]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]])
(schema/register!
 ::acquire-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [:seon.db/db :seon.db/db]
  [::acquired? ::acquired?]])
(schema/register!
 ::observe-database-lifecycle-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::main-branch-head ::main-branch-head]
  [::main-parent-commit-ids [:set :uuid]]
  [::branch-heads ::branch-heads]
  [::branch-roster ::branch-roster]
  [::restore-completions [:vector :seon.db.restore/completion]]
  [::completed-restore-ids [:set :seon.db.restore/id]]
  [::restore-completion-branch-heads
   ::restore-completion-branch-heads]])
(schema/register!
 ::create-branch-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-connection-id ::target-connection-id]
  [::branch-head ::branch-head]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::created? ::created?]
  [::adopted? ::adopted?]])
(schema/register!
 ::release-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::released? ::released?]])
(schema/register!
 ::delete-branch-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-connection-id ::target-connection-id]
  [::source-head ::source-head]
  [::released? ::released?]
  [::deleted? ::deleted?]])
(schema/register!
 ::transaction-response
  [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [:db-before :db-before]
  [:db-after :db-after]
  [:tx-data :tx-data]
  [:tempids :tempids]
  [:tx-meta :tx-meta]
  [::generated-entity-ids {:optional true} ::generated-entity-ids]
  [::recovered? {:optional true} ::recovered?]])
(schema/register!
 ::resolve-transaction-branch-head-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::branch-head ::branch-head]])
(schema/register!
 ::knn-search-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::hits ::hits]])
(schema/register!
 ::response
 [:or
  ::session-open-response
  ::incompatible-version-response
  ::connection-capacity-response
  ::session-open-required-response
  ::frame-too-large-response
  ::failed-response
  ::ping-response
  ::capabilities-response
  ::resolve-head-response
  ::query-response
  ::read-response
  ::schema-response
  ::index-page-response
  ::execute-many-response
  ::cancel-response
  ::listen-response
  ::unlisten-response
  ::datoms-event-map
  ::resynchronization-event-map
  ::database-advanced-event-map
  ::ensure-database-response
  ::acquire-database-response
  ::observe-database-lifecycle-response
  ::create-branch-response
  ::release-database-response
  ::delete-branch-response
  ::transaction-response
  ::resolve-transaction-branch-head-response
  ::knn-search-response])

(schema/register! ::body :map)
(schema/register!
 ::failure-request
 [:map
  [::error-kind ::error-kind]
  [::error ::error]
  [:seon.error/kind {:optional true} :keyword]
  [::body {:optional true} ::body]])
(schema/register!
 ::session-open-request-input
 [:map {:closed true}
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::session-open-success-input
 [:map {:closed true}
  [::configured-maximum-frame-bytes ::configured-maximum-frame-bytes]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::peer-version-input
 [:map {:closed true}
  [::peer-version ::peer-version]])
(schema/register!
 ::maximum-connections-input
 [:map {:closed true}
  [::maximum-connections ::maximum-connections]])
(schema/register!
 ::session-open-required-input
 [:map {:closed true}
  [::request-id ::request-id]])
(schema/register!
 ::frame-too-large-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::maximum-frame-bytes ::maximum-frame-bytes]])
(schema/register!
 ::ensure-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::branch/connection-id {:optional true} ::branch/connection-id]
  [:seon.db/initialization-page
   {:optional true} :seon.db/initialization-page]])
(schema/register!
 ::acquire-database-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::database-advanced? {:optional true} :boolean]])
(schema/register!
 ::request-id-input
 [:map {:closed true}
  [::request-id ::request-id]])
(schema/register!
 ::resolve-head-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]])
(schema/register!
 ::query-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db {:optional true} :seon.db/db]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-id ::entity-id]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-many-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::schema-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]])
(schema/register!
 ::index-page-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::cursor {:optional true} ::cursor]
  [:seon.db/user {:optional true} :seon.db/ref]
  [:seon.db/process {:optional true} :seon.db/ref]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::execute-many-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::members ::members]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::cancel-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]])
(schema/register!
 ::query-listen-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::query-form ::query-form]])
(schema/register!
 ::dependency-plan-listen-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:datahike.read/dependency-plan :datahike.read/dependency-plan]])
(schema/register!
 ::read-evidence-listen-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [:seon.db/read-evidence :seon.db/read-evidence]])
(schema/register!
 ::datom-listen-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::datom-patterns ::datom-patterns]])
(schema/register! ::listen-request-input
                  [:or ::read-evidence-listen-request-input
                   ::dependency-plan-listen-request-input
                   ::query-listen-request-input
                   ::datom-listen-request-input])
(schema/register!
 ::unlisten-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::target-request-id ::target-request-id]])
(schema/register!
 ::observe-database-lifecycle-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]])
(schema/register!
 ::create-branch-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::source-branch-head ::source-branch-head]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]])
(schema/register!
 ::release-database-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]])
(schema/register!
 ::delete-branch-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::target-connection-id ::target-connection-id]
  [::expected-target-head ::expected-target-head]])
(schema/register!
  ::transaction-request-input
  [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::transaction-data ::transaction-data]
  [:seon.db/expected-db {:optional true} :seon.db/expected-db]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
 ::resolve-transaction-branch-head-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::containing-branch-head ::containing-branch-head]
  [::transaction-id ::transaction-id]])
(schema/register!
  ::knn-request-input
  [:map {:closed true}
  [::request-id ::request-id]
  [:seon.db/db :seon.db/db]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::knn-entity-ids]])

;;; Pure constructors and validation

(declare success failure)

(defn session-open-request
  "Construct the fixed-identity database session opening request."
  {:malli/schema [:=> [:cat ::session-open-request-input]
                  ::session-open-request]}
  [{::keys [maximum-frame-bytes]}]
  {::operation session-open-operation
   ::request-id session-open-request-id
   ::version current-version
   ::maximum-frame-bytes maximum-frame-bytes})

(defn session-open-success
  "Construct the successful database session opening response."
  {:malli/schema [:=> [:cat ::session-open-success-input]
                  ::session-open-response]}
  [{::keys [configured-maximum-frame-bytes maximum-frame-bytes]}]
  (success
   {::request-id session-open-request-id
    ::version current-version
    ::configured-maximum-frame-bytes configured-maximum-frame-bytes
    ::maximum-frame-bytes maximum-frame-bytes}))

(defn incompatible-version-failure
  "Construct the exact-version database session rejection."
  {:malli/schema [:=> [:cat ::peer-version-input]
                  ::incompatible-version-response]}
  [{::keys [peer-version]}]
  (failure
   {::error-kind incompatible-version-error
    ::error "The database protocol version is incompatible."
    :seon.error/kind :configuration
    ::body {::request-id session-open-request-id
            ::version current-version
            ::peer-version peer-version}}))

(defn connection-capacity-failure
  "Construct the database writer connection-capacity rejection."
  {:malli/schema [:=> [:cat ::maximum-connections-input]
                  ::connection-capacity-response]}
  [{::keys [maximum-connections]}]
  (failure
   {::error-kind connection-capacity-error
    ::error "The database writer is at its connection capacity."
    :seon.error/kind :configuration
    ::body {::request-id session-open-request-id
            ::configuration-key
            :seon.config.database.transport/maximum-connections
            ::maximum-connections maximum-connections}}))

(defn session-open-required-failure
  "Construct the correlated mandatory session-opening rejection."
  {:malli/schema [:=> [:cat ::session-open-required-input]
                  ::session-open-required-response]}
  [{::keys [request-id]}]
  (failure
   {::error-kind session-open-required-error
    ::error
    "The session must open with session-open before ordinary requests."
    ::body {::request-id request-id}}))

(defn frame-too-large-failure
  "Construct the bounded database session frame-ceiling failure."
  {:malli/schema [:=> [:cat ::frame-too-large-input]
                  ::frame-too-large-response]}
  [{::keys [request-id maximum-frame-bytes]}]
  (failure
   {::error-kind frame-too-large-error
    ::error "The database protocol frame is too large."
    ::body {::request-id request-id
            ::configuration-key
            :seon.config.database.transport/maximum-frame-bytes
            ::maximum-frame-bytes maximum-frame-bytes}}))

(defn ping-request
  "Construct the writer readiness request."
  {:malli/schema [:=> [:cat ::request-id-input] ::ping-request]}
  [input]
  (assoc input ::operation ping-operation))

(defn capabilities-request
  "Construct one correlated Datahike capability-discovery request."
  {:malli/schema [:=> [:cat ::request-id-input] ::capabilities-request]}
  [input]
  (assoc input ::operation capabilities-operation))

(defn resolve-head-request
  "Construct one correlated request for a database's current portable value."
  {:malli/schema [:=> [:cat ::resolve-head-request-input]
                  ::resolve-head-request]}
  [input]
  (assoc input ::operation resolve-head-operation))

(defn query-request
  "Construct one immutable-database Datahike query request."
  {:malli/schema [:=> [:cat ::query-request-input] ::query-request]}
  [input]
  (assoc input ::operation query-operation))

(defn pull-request
  "Construct one immutable-database Datahike pull request."
  {:malli/schema [:=> [:cat ::pull-request-input] ::pull-request]}
  [input]
  (assoc input ::operation pull-operation))

(defn pull-many-request
  "Construct one immutable-database Datahike pull-many request."
  {:malli/schema [:=> [:cat ::pull-many-request-input] ::pull-many-request]}
  [input]
  (assoc input ::operation pull-many-operation))

(defn schema-request
  "Construct one immutable-database Datahike schema request."
  {:malli/schema [:=> [:cat ::schema-request-input] ::schema-request]}
  [input]
  (assoc input ::operation schema-operation))

(defn index-page-request
  "Construct one immutable-database Datahike index page request."
  {:malli/schema [:=> [:cat ::index-page-request-input]
                  ::index-page-request]}
  [input]
  (assoc input ::operation index-page-operation))

(defn execute-many-request
  "Construct one bounded group of independent database reads."
  {:malli/schema [:=> [:cat ::execute-many-request-input]
                  ::execute-many-request]}
  [input]
  (assoc input
         ::operation execute-many-operation
         :datahike.resource/max-result-weight
         (or (:datahike.resource/max-result-weight input)
             maximum-frame-bytes)))

(defn cancel-request
  "Construct one request to cancel another request by its existing identity."
  {:malli/schema [:=> [:cat ::cancel-request-input] ::cancel-request]}
  [input]
  (assoc input ::operation cancel-operation))

(defn listen-request
  "Construct one physical-connection-owned database interest request."
  {:malli/schema [:=> [:cat ::listen-request-input] ::listen-request]}
  [input]
  (assoc input ::operation listen-operation))

(defn unlisten-request
  "Construct one request to remove a database interest by request identity."
  {:malli/schema [:=> [:cat ::unlisten-request-input] ::unlisten-request]}
  [input]
  (assoc input ::operation unlisten-operation))

(def ^:private initialization-bootstrap-attributes
  "Fixed schema closure needed to persist genesis, canonical schema rows, and
   the initialization receipt before the rest of the schema corpus exists."
  #{:seon.agent/id
    :seon.db.process/id
    :seon.db/user
    :seon.db/process
    :seon.ns/name
    :seon.schema/key
    :seon.schema/form
    :seon.schema/ns
    :seon.db.id/generator
    :seon.db.initialization/id
    :seon.db.initialization/fingerprint
    :seon.db.initialization/page-fingerprint
    :seon.db.initialization/identities
    :seon.db.initialization/page-count
    :seon.db.initialization/status})

(defn- initialization-schema-form
  [row]
  (#?(:cljs reader/read-string :default edn/read-string)
   (:seon.schema/form row)))

(defn- schema-reference-closure
  [projection forms roots]
  (loop [pending (set roots)
         closure #{}]
    (if (empty? pending)
      closure
      (let [key (first pending)
            dependencies
            (schema/direct-references projection (get forms key))]
        (recur (into (disj pending key)
                     (remove closure)
                     dependencies)
               (conj closure key))))))

(defn- schema-dependency-order
  [projection forms resolved pending]
  (let [known (set (keys forms))]
    (loop [resolved (set resolved)
           pending (set pending)
           ordered []]
      (if (empty? pending)
        ordered
        (let [ready
              (->> pending
                   (filter
                    (fn [key]
                      (set/subset?
                       (set/intersection
                        known
                        (schema/direct-references projection
                                                  (get forms key)))
                       resolved)))
                   (sort-by str)
                   vec)]
          (when (empty? ready)
            (throw
             (ex-info "Database initialization schema dependencies are cyclic."
                      {:seon.db.initialization/pending-schema-keys
                       (vec (sort-by str pending))
                       :seon.error/kind :core-bug})))
          (recur (into resolved ready)
                 (reduce disj pending ready)
                 (into ordered ready)))))))

(defn- initialization-schema-pages
  [schema-rows page-rows]
  (let [rows-by-key (into {} (map (juxt :seon.schema/key identity))
                          schema-rows)
        forms (update-vals rows-by-key initialization-schema-form)
        projection (schema/build-projection forms)
        missing (set (remove #(contains? forms %)
                             initialization-bootstrap-attributes))]
    (when (seq missing)
      (throw
       (ex-info "Database initialization lacks bootstrap schema forms."
                {:seon.db.initialization/missing-schema-keys
                 (vec (sort missing))
                 :seon.error/kind :core-bug})))
    (let [bootstrap-keys
          (schema-reference-closure
           projection forms initialization-bootstrap-attributes)
          bootstrap-rows
          (into [] (keep rows-by-key) (sort bootstrap-keys))
          remaining-keys
          (set/difference (set (keys rows-by-key)) bootstrap-keys)
          remaining-rows
          (into []
                (keep rows-by-key)
                (schema-dependency-order
                 projection forms bootstrap-keys remaining-keys))]
      (into [bootstrap-rows]
            (comp (map vec) (remove empty?))
            (partition-all page-rows remaining-rows)))))

(defn- initialization-entity-attributes
  [schema-rows]
  (into #{}
        (comp
         (map initialization-schema-form)
         (filter schema.form/map-shape?)
         (filter #(true? (:seon.db/entity
                          (schema.form/schema-properties %))))
         (mapcat schema.form/map-entries)
         (keep (fn [entry]
                 (let [attribute (when (vector? entry) (first entry))]
                   (when (qualified-keyword? attribute) attribute)))))
        schema-rows))

(defn- derive-initialization-pages
  [initialization]
  #?(:bb
     (throw
      (ex-info "Database initialization paging is unavailable in Babashka."
               {:seon.error/kind :core-bug}))
     :default
     (let [page-rows (:seon.db.initialization/page-rows initialization)
           program (:seon.db/program initialization)
           schema-rows
           (into [] (filter #(contains? % :seon.schema/key)) program)
           ordinary-program
           (into [] (remove #(contains? % :seon.schema/key)) program)
           schema-pages (initialization-schema-pages schema-rows page-rows)
           attributes
           (into []
                 (distinct)
                 (concat (:seon.db/attributes initialization)
                         (sort (initialization-entity-attributes schema-rows))))
           fingerprint (str (hasch/uuid initialization))
           payloads
           (into
            (mapv
             (fn [rows]
               {:seon.db.initialization/phase
                :seon.db.initialization.phase/schema
                :seon.db/program rows})
             schema-pages)
            (concat
             (map
              (fn [attributes]
                {:seon.db.initialization/phase
                 :seon.db.initialization.phase/attributes
                 :seon.db/attributes (vec attributes)})
              (partition-all page-rows
                             attributes))
             (map
              (fn [rows]
                {:seon.db.initialization/phase
                 :seon.db.initialization.phase/program
                 :seon.db/program (vec rows)})
              (partition-all page-rows ordinary-program))
             (map
              (fn [rows]
                {:seon.db.initialization/phase
                 :seon.db.initialization.phase/initial-data
                 :seon.db/initial-data (vec rows)})
              (partition-all page-rows
                             (:seon.db/initial-data initialization)))
             [{:seon.db.initialization/phase
               :seon.db.initialization.phase/completion}]))
           page-count (count payloads)]
       (mapv
        (fn [page-index payload]
          (assoc payload
                 :seon.db.initialization/fingerprint fingerprint
                 :seon.db.initialization/page-index page-index
                 :seon.db.initialization/page-count page-count
                 :seon.db.initialization/page-rows page-rows))
        (range)
        payloads))))

(defn initialization-pages
  "Return the precomputed pages or derive them from raw initialization."
  {:malli/schema [:=> [:cat :seon.db/initialization]
                  :seon.db/initialization-pages]}
  [initialization]
  (if (contains? initialization :seon.db/initialization-pages)
    (:seon.db/initialization-pages initialization)
    (derive-initialization-pages initialization)))

(defn ensure-database-request
  "Construct one idempotent database-open request."
  {:malli/schema [:=> [:cat ::ensure-request-input]
                  ::ensure-database-request]}
  [{::keys [request-id database-name backend database-path]
    :seon.db/keys [initialization-page]
    :as input}]
  (cond-> {::operation ensure-database-operation
           ::request-id request-id
           ::database-name database-name
           ::backend backend}
    database-path (assoc ::database-path database-path)
    (::branch/connection-id input)
    (assoc ::branch/connection-id (::branch/connection-id input))
    initialization-page
    (assoc :seon.db/initialization-page initialization-page)))

(defn acquire-database-request
  "Construct one database acquisition for the current transport connection."
  {:malli/schema [:=> [:cat ::acquire-database-request-input]
                  ::acquire-database-request]}
  [input]
  (assoc input ::operation acquire-database-operation))

(defn observe-database-lifecycle-request
  "Construct one exact native database-lifecycle observation request."
  {:malli/schema
   [:=> [:cat ::observe-database-lifecycle-request-input]
    ::observe-database-lifecycle-request]}
  [input]
  (assoc input ::operation observe-database-lifecycle-operation))

(defn create-branch-request
  "Construct one exact native branch-creation request."
  {:malli/schema [:=> [:cat ::create-branch-request-input]
                  ::create-branch-request]}
  [input]
  (assoc input ::operation create-branch-operation))

(defn release-database-request
  "Construct one release request for an acquired database value."
  {:malli/schema [:=> [:cat ::release-database-request-input]
                  ::release-database-request]}
  [input]
  (assoc input ::operation release-database-operation))

(defn delete-branch-request
  "Construct one exact native branch-deletion request."
  {:malli/schema [:=> [:cat ::delete-branch-request-input]
                  ::delete-branch-request]}
  [input]
  (assoc input ::operation delete-branch-operation))

(defn transaction-request
  "Construct one idempotent logical transaction request."
  {:malli/schema [:=> [:cat ::transaction-request-input]
                  ::transaction-request]}
  [{::keys [request-id transaction-data transaction-meta generated-candidates]
    database :seon.db/db
    expected-db :seon.db/expected-db
    :as input}]
  (cond-> {::operation transact-operation
           ::request-id request-id
           :seon.db/db database
           ::transaction-data transaction-data}
    expected-db (assoc :seon.db/expected-db expected-db)
    (seq transaction-meta) (assoc ::transaction-meta transaction-meta)
    (contains? input ::generated-candidates)
    (assoc ::generated-candidates generated-candidates)))

(defn resolve-transaction-branch-head-request
  "Construct one transaction-to-branch-head resolution request."
  {:malli/schema
   [:=> [:cat ::resolve-transaction-branch-head-request-input]
    ::resolve-transaction-branch-head-request]}
  [input]
  (assoc input ::operation resolve-transaction-branch-head-operation))

(defn knn-search-request
  "Construct one bounded embedding-neighbor request."
  {:malli/schema [:=> [:cat ::knn-request-input] ::knn-search-request]}
  [{::keys [request-id query limit entity-ids]
    database :seon.db/db}]
  (cond-> {::operation knn-search-operation
           ::request-id request-id
           :seon.db/db database
           ::query query
           ::limit limit}
    (seq entity-ids) (assoc ::entity-ids entity-ids)))

(defn success
  "Add the canonical successful response fact to `body`."
  {:malli/schema [:=> [:catn [::body ::body]] :map]}
  [body]
  (assoc body ::success? true))

(defn failure
  "Construct the canonical failed response."
  {:malli/schema [:=> [:cat ::failure-request] :map]}
  [{::keys [error-kind error body] :as input}]
  (cond-> (assoc (or body {})
                 ::success? false
                 ::error-kind error-kind
                 ::error error)
    (:seon.error/kind input)
    (assoc :seon.error/kind (:seon.error/kind input))))

(def ^:private request-validator
  (delay (schema/candidate-validator ::request)))
(def ^:private response-validator
  (delay (schema/candidate-validator ::response)))
(def ^:private writer-terminal-result-validator
  (delay (schema/candidate-validator ::writer-terminal-result)))
(def ^:private request-explainer
  (delay (schema/candidate-explainer ::request)))
(def ^:private response-explainer
  (delay (schema/candidate-explainer ::response)))

(defn- generated-candidate-keys-unique?
  [request]
  (if-let [candidates (::generated-candidates request)]
    (let [candidate-keys (map :seon.db.id/key candidates)]
      (= (count candidate-keys) (count (distinct candidate-keys))))
    true))

(defn- current-database-value?
  [value]
  (and (database-value? value)
       (nil? (:as-of value))
       (nil? (:since value))
       (false? (:history value))))

(defn- request-semantics-valid?
  [request]
  (let [database-values
        (cond-> [(get request :seon.db/db)
                 (get request :seon.db/expected-db)]
          (= execute-many-operation (::operation request))
          (into (map :seon.db/db (::members request))))]
    (and (every? database-value? (remove nil? database-values))
         (case (::operation request)
           :seon.db.protocol.operation/transact
           (and (current-database-value? (:seon.db/db request))
                (or (nil? (:seon.db/expected-db request))
                    (current-database-value?
                     (:seon.db/expected-db request))))

           :seon.db.protocol.operation/listen
           (current-database-value? (:seon.db/db request))

           true)
         (generated-candidate-keys-unique? request))))

(defn- response-semantics-valid?
  [response]
  (every? database-value?
          (remove nil?
                  [(get response :seon.db/db)
                   (get response :seon.db/expected-db)
                   (get response :seon.db/current-db)
                   (:db-before response)
                   (:db-after response)])))

(defn valid-request?
  "True when `request` is one complete canonical protocol request."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [request]
  (and (@request-validator request)
       (ordinary-wire-value? request)
       (request-semantics-valid? request)))

(defn explain-request
  "Malli explanation for an invalid request, or nil when valid."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [request]
  (or (@request-explainer request)
      (when-not (ordinary-wire-value? request)
        {::error "Protocol requests contain only eager ordinary wire values."})
      (when-not (request-semantics-valid? request)
        {::error "Protocol database values must have one temporal bound."})
      (when-not (generated-candidate-keys-unique? request)
        {::generated-candidates (::generated-candidates request)
         ::error "Generated candidate keys must be unique."})))

(defn valid-response?
  "True when `response` is one complete canonical protocol response."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [response]
  (and (@response-validator response)
       (ordinary-wire-value? response)
       (response-semantics-valid? response)))

(defn valid-writer-terminal-result?
  "True when `result` is one complete writer terminal value."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [result]
  (@writer-terminal-result-validator result))

(defn explain-response
  "Malli explanation for an invalid response, or nil when valid."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [response]
  (or (@response-explainer response)
      (when-not (ordinary-wire-value? response)
        {::error "Protocol responses contain only eager ordinary wire values."})
      (when-not (response-semantics-valid? response)
        {::error "Protocol database values must have one temporal bound."})))

;;; Durable idempotency receipt

(def receipt-attributes
  #{:seon.db.protocol.tempid/string-key
    :seon.db.protocol.tempid/int-key
    :seon.db.protocol.tempid/entity})

(def reserved-attributes
  (into receipt-attributes #{::request-id ::request-hash ::version}))

#?(:bb nil
   :default
   (defn logical-transaction-hash
     "Map-order-independent fingerprint of one logical transaction request."
     {:malli/schema [:=> [:cat ::transaction-request] :uuid]}
     [request]
     (hasch/uuid
      {::version current-version
       ::transaction-data (::transaction-data request)
       ;; The source database is delivery context, not logical operation
       ;; identity. Public op-id replay resolves from a later head; an explicit
       ;; expected-db remains part of the hash when snapshot identity matters.
       :seon.db/expected-db (:seon.db/expected-db request)
       ::transaction-meta (or (::transaction-meta request) {})
       ::generated-candidates (or (::generated-candidates request) [])})))

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
         (cond->
           {:db/id marker-id
            :seon.db.protocol.tempid/entity tempid}
           (string? tempid)
           (assoc :seon.db.protocol.tempid/string-key tempid)
           (int? tempid)
           (assoc :seon.db.protocol.tempid/int-key tempid))))
     (range)
     tempids)))
