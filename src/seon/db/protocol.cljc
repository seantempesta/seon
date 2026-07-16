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
  (:require #?@(:bb [] :default [[hasch.core :as hasch]])
            #?@(:cljs [[cognitect.transit :as transit]
                       [goog.object :as gobj]])
            [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.restore.schema]
            [seon.schema :as schema]))

;;; Protocol vocabulary

(def ping-operation :seon.db.protocol.operation/ping)
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
(def replay-transactions-operation
  :seon.db.protocol.operation/replay-transactions)
(def resolve-transaction-coordinate-operation
  :seon.db.protocol.operation/resolve-transaction-coordinate)
(def knn-search-operation :seon.db.protocol.operation/knn-search)

(def transaction-event :seon.db.protocol.event/transaction)
(def datoms-event :seon.db.protocol.event/datoms)
(def resynchronization-event :seon.db.protocol.event/resynchronization)

(def protocol-error :seon.db.protocol.error/protocol)
(def database-error :seon.db.protocol.error/database)
(def internal-error :seon.db.protocol.error/internal)
(def not-found-error :seon.db.protocol.error/not-found)
(def request-conflict-error :seon.db.protocol.error/request-conflict)
(def stale-coordinate-error :seon.db.protocol.error/stale-coordinate)
(def generated-candidate-conflict-error
  :seon.db.protocol.error/generated-candidate-conflict)
(def duplicate-route-error :seon.db.protocol.error/duplicate-route)
(def duplicate-attachment-error :seon.db.protocol.error/duplicate-attachment)
(def attachment-mismatch-error :seon.db.protocol.error/attachment-mismatch)
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

(def lifecycle-error-kinds
  #{duplicate-route-error duplicate-attachment-error attachment-mismatch-error
    stale-source-head-error stale-target-head-error missing-commit-error
    unsupported-history-error cut-not-branchable-error branch-exists-error
    branch-missing-error protected-main-branch-error active-branch-error
    initializer-error release-error cleanup-required-error
    stale-branch-roster-error restore-divergence-error non-ancestor-error
    ambiguous-history-error})

(def committed-status :seon.db.protocol.status/committed)
(def unknown-status :seon.db.protocol.status/unknown)
(def feed-behind-status :seon.db.protocol.status/feed-behind)

(def current-version 6)

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

(schema/register!
 ::operation
 [:enum ping-operation
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
  replay-transactions-operation
  resolve-transaction-coordinate-operation
  knn-search-operation])
(schema/register! ::success? :boolean)
(schema/register! ::pong? :boolean)
(schema/register! ::capabilities :map)
(schema/register! ::maximum-frame-bytes [:int {:min 1}])
;; Datahike query and pull values are intentionally polymorphic data. The
;; canonical request/response validators apply `ordinary-wire-value?`
;; recursively while preserving native result shapes and legitimate bare keys.
(schema/register! ::result :any)
(schema/register! ::arguments [:vector :any])
(schema/register! ::selector :any)
(schema/register! ::entity-id :any)
(schema/register! ::query-form
                  [:or [:vector :any] :map [:string {:min 1}]])
(schema/register! ::history? :boolean)
(schema/register! :datahike.resource/max-work [:int {:min 1}])
(schema/register! :datahike.resource/max-results [:int {:min 1}])
(schema/register! :datahike.resource/max-result-weight [:int {:min 1}])
(schema/register! :datahike.query/result :any)
(schema/register! :datahike.query/cache-evidence :map)
(schema/register! :datahike.query/resource-evidence :map)
(schema/register! ::database-name [:string {:min 1}])
(schema/register! ::database-path [:string {:min 1}])
(schema/register! ::backend [:enum :memory :file])
(schema/register! ::coordinate ::coordinate/coordinate)
(schema/register! ::previous-coordinate ::coordinate/coordinate)
(schema/register! ::since-coordinate ::coordinate/coordinate)
(schema/register! ::through-coordinate ::coordinate/coordinate)
(schema/register!
 ::head-coordinate
 [:map {:closed true}
  [::coordinate/database-id ::coordinate/database-id]
  [::coordinate/branch [:= :db]]
  [::coordinate/commit-id ::coordinate/commit-id]
  [::coordinate/t ::coordinate/t]])
(schema/register! ::transaction-id ::coordinate/t)
(schema/register! ::continuation-coordinate ::coordinate/coordinate)
(schema/register! ::expected-coordinate ::coordinate/coordinate)
(schema/register! ::current-coordinate ::coordinate/coordinate)
(schema/register! ::source-coordinate ::coordinate/coordinate)
(schema/register! ::expected-source-head ::coordinate/coordinate)
(schema/register! ::expected-target-head ::coordinate/coordinate)
(schema/register! ::source-head ::coordinate/coordinate)
(schema/register! ::attachment ::coordinate/attachment)
(schema/register! ::target-attachment ::coordinate/attachment)
(schema/register! ::target-branch :keyword)
(schema/register! ::main-coordinate ::coordinate/coordinate)
(schema/register! ::branch-coordinates
                  [:map-of :keyword ::coordinate/coordinate])
(schema/register! ::branch-roster [:set :keyword])
(schema/register! ::restore-completion-coordinates
                  [:map-of :seon.db.restore/id ::coordinate/coordinate])
(schema/register! ::source-database-name ::database-name)
(schema/register! ::target-database-name ::database-name)
(schema/register! ::created? :boolean)
(schema/register! ::adopted? :boolean)
(schema/register! ::acquired? :boolean)
(schema/register! ::released? :boolean)
(schema/register! ::deleted? :boolean)
(schema/register! ::complete? :boolean)
(schema/register! ::replayed-count [:int {:min 0}])
(schema/register! ::datoms-added [:int {:min 0}])
(schema/register! ::datoms-retracted [:int {:min 0}])
(schema/register! ::request-id
                  [:string {:min 1 :seon.db/identity true}])
(schema/register! ::target-request-id ::request-id)
(schema/register! ::canceled? :boolean)
(schema/register! ::running? :boolean)
(schema/register! ::request-hash :uuid)
(schema/register! ::version [:int {:min 1}])
(schema/register! ::transaction-data [:vector :any])
(schema/register! ::transaction-meta :map)
(schema/register! ::temporary-ids :map)
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
(schema/register! ::event
                  [:enum transaction-event datoms-event
                   resynchronization-event])
(schema/register! ::query [:string {:min 1}])
(schema/register! ::limit [:int {:min 1}])
(schema/register! ::index [:enum :eavt :aevt :avet])
(schema/register! ::prefix [:vector {:max 4} :any])
(schema/register! ::direction [:enum :forward :reverse])
(schema/register! ::index-page-limit [:int {:min 1 :max 200}])
(schema/register!
 ::datom
 [:map {:closed true}
  [:seon.db/e :int]
  [:seon.db/a :keyword]
  [:seon.db/v :any]
  [:seon.db/tx :int]
  [:seon.db/added? :boolean]])
(schema/register! ::datoms [:vector ::datom])
(schema/register!
 ::cursor
 [:map {:closed true}
  [::coordinate ::coordinate]
  [::index ::index]
  [::direction ::direction]
  [::history? ::history?]
  [:seon.db/e :int]
  [:seon.db/a :keyword]
  [:seon.db/v :any]
  [:seon.db/tx :int]
  [:seon.db/added? :boolean]])
(schema/register! ::schema :map)
(schema/register!
 :datahike.query/attribute-dependencies
 [:or [:= :all] [:set :keyword]])
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
(schema/register!
 ::error-kind
 [:enum protocol-error database-error internal-error not-found-error
  request-conflict-error stale-coordinate-error
  generated-candidate-conflict-error
  duplicate-route-error duplicate-attachment-error attachment-mismatch-error
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
;; Keep the complete database-release shape portable so the JVM publisher and
;; Babashka operator validate the same value without loading writer resources.
(schema/register!
 ::writer-release-result
 [:map {:closed true}
  [:seon.db.registry/database-name :keyword]
  [:seon.db.registry/attachment ::coordinate/attachment]
  [:seon.db.registry/coordinate ::coordinate/coordinate]
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

(schema/register!
 ::transaction-event-map
 [:map
  [::event [:= transaction-event]]
  [::database-name ::database-name]
  [::coordinate ::coordinate]
  [::previous-coordinate ::previous-coordinate]
  [::transaction-data ::transaction-data]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::request-id {:optional true} ::request-id]
  [::datoms-added ::datoms-added]
  [::datoms-retracted ::datoms-retracted]])
(schema/register! ::events [:vector ::transaction-event-map])

(schema/register! :seon.db.protocol.tempid/key-edn :string)
(schema/register! :seon.db.protocol.tempid/entity :seon.db/ref)

(defn- cursor-matches-index-page?
  [coordinate* request]
  (if-let [cursor (::cursor request)]
    (= {::coordinate coordinate*
        ::index (::index request)
        ::direction (::direction request)
        ::history? (true? (::history? request))}
       (select-keys cursor
                    [::coordinate ::index ::direction ::history?]))
    true))

(defn- execute-many-cursors-match?
  [{::keys [coordinate members]}]
  (every? (fn [member]
            (or (not= index-page-operation (::operation member))
                (cursor-matches-index-page? coordinate member)))
          members))

(defn- request-cursors-match?
  [request]
  (case (::operation request)
    :seon.db.protocol.operation/index-page
    (cursor-matches-index-page? (::coordinate request) request)

    :seon.db.protocol.operation/execute-many
    (execute-many-cursors-match? request)

    true))

(schema/register!
 ::ping-request
 [:map {:closed true}
  [::operation [:= ping-operation]]
  [::request-id ::request-id]])
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [::history? {:optional true} ::history?]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::selector ::selector]
  [::entity-id ::entity-id]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]])
(schema/register!
 ::index-page-request
 [:map {:closed true}
  [::operation [:= index-page-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::history? {:optional true} ::history?]
  [::cursor {:optional true} ::cursor]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::query-member
 [:map {:closed true}
  [::operation [:= query-operation]]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [::history? {:optional true} ::history?]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-member
 [:map {:closed true}
  [::operation [:= pull-operation]]
  [::selector ::selector]
  [::entity-id ::entity-id]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::pull-many-member
 [:map {:closed true}
  [::operation [:= pull-many-operation]]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
  [:datahike.resource/max-work {:optional true} :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true} :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::schema-member
 [:map {:closed true}
  [::operation [:= schema-operation]]])
(schema/register!
 ::index-page-member
 [:map {:closed true}
  [::operation [:= index-page-operation]]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::history? {:optional true} ::history?]
  [::cursor {:optional true} ::cursor]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::query-form ::query-form]])
(schema/register!
 ::datom-listen-request
 [:map {:closed true}
  [::operation [:= listen-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::datom-patterns ::datom-patterns]])
(schema/register! ::listen-request
                  [:or ::query-listen-request ::datom-listen-request])
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
  [::coordinate/attachment {:optional true} ::coordinate/attachment]
  [::database-path {:optional true} ::database-path]])
(schema/register!
 ::acquire-database-request
 [:map {:closed true}
  [::operation [:= acquire-database-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]])
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
  [::source-coordinate ::source-coordinate]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]])
(schema/register!
 ::release-database-request
 [:map {:closed true}
  [::operation [:= release-database-operation]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
 ::delete-branch-request
 [:map {:closed true}
  [::operation [:= delete-branch-operation]]
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
  ::transaction-request
  [:map
  [::operation [:= transact-operation]]
  [::database-name ::database-name]
  [::request-id ::request-id]
  [::transaction-data ::transaction-data]
  [::expected-coordinate {:optional true} ::expected-coordinate]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
  ::replay-transactions-request
  [:map {:closed true}
  [::operation [:= replay-transactions-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::since-coordinate ::since-coordinate]
  [::through-coordinate {:optional true} ::through-coordinate]])
(schema/register!
 ::resolve-transaction-coordinate-request
 [:map {:closed true}
  [::operation [:= resolve-transaction-coordinate-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::head-coordinate ::head-coordinate]
  [::transaction-id ::transaction-id]])
(schema/register!
  ::knn-search-request
  [:map {:closed true}
  [::operation [:= knn-search-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::knn-entity-ids]])

(schema/register!
 ::request
 [:multi {:dispatch ::operation}
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
  [replay-transactions-operation ::replay-transactions-request]
  [resolve-transaction-coordinate-operation
   ::resolve-transaction-coordinate-request]
  [knn-search-operation ::knn-search-request]])
(schema/register!
 ::failed-response
 [:map
  [::success? [:= false]]
  [::request-id ::request-id]
  [::error-kind ::error-kind]
  [::error ::error]
  [::generated-candidate {:optional true} ::generated-candidate]
  [::expected-coordinate {:optional true} ::expected-coordinate]
  [::current-coordinate {:optional true} ::current-coordinate]])
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]])
(schema/register!
 ::query-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [:datahike.query/result :datahike.query/result]
  [:datahike.query/attribute-dependencies
   :datahike.query/attribute-dependencies]
  [:datahike.query/cache-evidence :datahike.query/cache-evidence]
  [:datahike.query/resource-evidence :datahike.query/resource-evidence]])
(schema/register!
 ::read-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::result ::result]])
(schema/register!
 ::schema-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::schema ::schema]])
(schema/register!
 ::index-page-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::datoms ::datoms]
  [::complete? ::complete?]
  [::cursor {:optional true} ::cursor]])
(schema/register!
 ::query-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [:datahike.query/result :datahike.query/result]
  [:datahike.query/attribute-dependencies
   :datahike.query/attribute-dependencies]
  [:datahike.query/cache-evidence :datahike.query/cache-evidence]
  [:datahike.query/resource-evidence :datahike.query/resource-evidence]])
(schema/register!
 ::read-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [::result ::result]])
(schema/register!
 ::schema-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [::schema ::schema]])
(schema/register!
 ::index-page-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [::datoms ::datoms]
  [::complete? ::complete?]
  [::cursor {:optional true} ::cursor]])
(schema/register!
 ::failed-member-response
 [:map {:closed true}
  [::success? [:= false]]
  [::error-kind ::error-kind]
  [::error ::error]])
(schema/register! ::member-response
                  [:or ::failed-member-response ::query-member-response
                   ::read-member-response ::schema-member-response
                   ::index-page-member-response])
(schema/register! ::results [:vector ::member-response])
(schema/register!
 ::execute-many-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
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
  [::coordinate ::coordinate]
  [::datoms ::datoms]])
(schema/register!
 ::resynchronization-event-map
 [:map {:closed true}
  [::event [:= resynchronization-event]]
  [::request-id ::request-id]
  [::coordinate ::coordinate]])
(schema/register!
 ::ensure-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::coordinate/coordinate ::coordinate/coordinate]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]])
(schema/register!
 ::acquire-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::acquired? ::acquired?]])
(schema/register!
 ::observe-database-lifecycle-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::main-coordinate ::main-coordinate]
  [::main-parent-commit-ids [:set :uuid]]
  [::branch-coordinates ::branch-coordinates]
  [::branch-roster ::branch-roster]
  [::restore-completions [:vector :seon.db.restore/completion]]
  [::completed-restore-ids [:set :seon.db.restore/id]]
  [::restore-completion-coordinates
   ::restore-completion-coordinates]])
(schema/register!
 ::create-branch-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::coordinate ::coordinate]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::created? ::created?]
  [::adopted? ::adopted?]])
(schema/register!
 ::release-database-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::released? ::released?]])
(schema/register!
 ::delete-branch-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::source-head ::source-head]
  [::released? ::released?]
  [::deleted? ::deleted?]])
(schema/register!
 ::transaction-response
 [:map
  [::success? [:= true]]
  [::request-id ::request-id]
  [::coordinate ::coordinate]
  [::previous-coordinate ::previous-coordinate]
  [::temporary-ids ::temporary-ids]
  [::transaction-data ::transaction-data]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::datoms-added ::datoms-added]
  [::datoms-retracted ::datoms-retracted]
  [::generated-entity-ids {:optional true} ::generated-entity-ids]
  [::recovered? {:optional true} ::recovered?]])
(schema/register!
 ::replay-transactions-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::since-coordinate ::since-coordinate]
  [::through-coordinate ::through-coordinate]
  [::continuation-coordinate ::continuation-coordinate]
  [::complete? ::complete?]
  [::events ::events]
  [::replayed-count ::replayed-count]])
(schema/register!
 ::resolve-transaction-coordinate-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::coordinate ::coordinate]])
(schema/register!
 ::knn-search-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::hits ::hits]])
(schema/register!
 ::response
 [:or
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
  ::ensure-database-response
  ::acquire-database-response
  ::observe-database-lifecycle-response
  ::create-branch-response
  ::release-database-response
  ::delete-branch-response
  ::transaction-response
  ::replay-transactions-response
  ::resolve-transaction-coordinate-response
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
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::backend ::backend]
  [::coordinate/attachment {:optional true} ::coordinate/attachment]
  [::database-path {:optional true} ::database-path]])
(schema/register!
 ::acquire-database-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]])
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [::history? {:optional true} ::history?]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::selector ::selector]
  [::entity-id ::entity-id]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]])
(schema/register!
 ::index-page-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::index ::index]
  [::prefix ::prefix]
  [::direction ::direction]
  [::limit ::index-page-limit]
  [::history? {:optional true} ::history?]
  [::cursor {:optional true} ::cursor]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])
(schema/register!
 ::execute-many-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
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
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::query-form ::query-form]])
(schema/register!
 ::datom-listen-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::datom-patterns ::datom-patterns]])
(schema/register! ::listen-request-input
                  [:or ::query-listen-request-input
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
  [::source-coordinate ::source-coordinate]
  [::expected-source-head ::expected-source-head]
  [::target-branch ::target-branch]])
(schema/register!
 ::release-database-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
 ::delete-branch-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::source-database-name ::source-database-name]
  [::target-database-name ::target-database-name]
  [::target-attachment ::target-attachment]
  [::expected-target-head ::expected-target-head]])
(schema/register!
  ::transaction-request-input
  [:map
  [::database-name ::database-name]
  [::request-id ::request-id]
  [::transaction-data ::transaction-data]
  [::expected-coordinate {:optional true} ::expected-coordinate]
  [::transaction-meta {:optional true} ::transaction-meta]
  [::generated-candidates {:optional true} ::generated-candidates]])
(schema/register!
  ::replay-request-input
  [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::since-coordinate ::since-coordinate]
  [::through-coordinate {:optional true} ::through-coordinate]])
(schema/register!
 ::resolve-transaction-coordinate-request-input
 [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::head-coordinate ::head-coordinate]
  [::transaction-id ::transaction-id]])
(schema/register!
  ::knn-request-input
  [:map {:closed true}
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::query ::query]
  [::limit ::limit]
  [::entity-ids {:optional true} ::knn-entity-ids]])

;;; Pure constructors and validation

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
  "Construct one coordinate-pinned Datahike query request."
  {:malli/schema [:=> [:cat ::query-request-input] ::query-request]}
  [input]
  (assoc input ::operation query-operation))

(defn pull-request
  "Construct one coordinate-pinned Datahike pull request."
  {:malli/schema [:=> [:cat ::pull-request-input] ::pull-request]}
  [input]
  (assoc input ::operation pull-operation))

(defn pull-many-request
  "Construct one coordinate-pinned Datahike pull-many request."
  {:malli/schema [:=> [:cat ::pull-many-request-input] ::pull-many-request]}
  [input]
  (assoc input ::operation pull-many-operation))

(defn schema-request
  "Construct one coordinate-pinned Datahike schema request."
  {:malli/schema [:=> [:cat ::schema-request-input] ::schema-request]}
  [input]
  (assoc input ::operation schema-operation))

(defn index-page-request
  "Construct one coordinate-pinned bounded Datahike index page request."
  {:malli/schema [:=> [:cat ::index-page-request-input]
                  ::index-page-request]}
  [input]
  (assoc input ::operation index-page-operation))

(defn execute-many-request
  "Construct one coordinate-pinned group of independent database reads."
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

(defn ensure-database-request
  "Construct one idempotent database-open request."
  {:malli/schema [:=> [:cat ::ensure-request-input]
                  ::ensure-database-request]}
  [{::keys [request-id database-name backend database-path]
    attachment ::coordinate/attachment}]
  (cond-> {::operation ensure-database-operation
           ::request-id request-id
           ::database-name database-name
           ::backend backend}
    attachment (assoc ::coordinate/attachment attachment)
    database-path (assoc ::database-path database-path)))

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
  "Construct one attachment-fenced database-release request."
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
  [{::keys [database-name request-id transaction-data transaction-meta
            expected-coordinate generated-candidates]
    :as input}]
  (cond-> {::operation transact-operation
           ::database-name database-name
           ::request-id request-id
           ::transaction-data transaction-data}
    expected-coordinate (assoc ::expected-coordinate expected-coordinate)
    (seq transaction-meta) (assoc ::transaction-meta transaction-meta)
    (contains? input ::generated-candidates)
    (assoc ::generated-candidates generated-candidates)))

(defn replay-transactions-request
  "Construct one bounded transaction-history page request."
  {:malli/schema [:=> [:cat ::replay-request-input]
                  ::replay-transactions-request]}
  [{::keys [request-id database-name since-coordinate through-coordinate]}]
  (cond-> {::operation replay-transactions-operation
           ::request-id request-id
           ::database-name database-name
           ::since-coordinate since-coordinate}
    (some? through-coordinate)
    (assoc ::through-coordinate through-coordinate)))

(defn resolve-transaction-coordinate-request
  "Construct one frozen-head transaction-coordinate request."
  {:malli/schema
   [:=> [:cat ::resolve-transaction-coordinate-request-input]
    ::resolve-transaction-coordinate-request]}
  [input]
  (assoc input ::operation resolve-transaction-coordinate-operation))

(defn knn-search-request
  "Construct one bounded embedding-neighbor request."
  {:malli/schema [:=> [:cat ::knn-request-input] ::knn-search-request]}
  [{::keys [request-id database-name attachment coordinate query limit entity-ids]}]
  (cond-> {::operation knn-search-operation
           ::request-id request-id
           ::database-name database-name
           ::attachment attachment
           ::coordinate coordinate
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
  [{::keys [error-kind error body]}]
  (assoc (or body {})
         ::success? false
         ::error-kind error-kind
         ::error error))

(defonce ^:private request-schema
  (delay (m/deref-recursive ::request)))
(defonce ^:private response-schema
  (delay (m/deref-recursive ::response)))
(defonce ^:private writer-terminal-result-schema
  (delay (m/deref-recursive ::writer-terminal-result)))
(defonce ^:private request-validator
  (delay (m/validator @request-schema)))
(defonce ^:private request-explainer
  (delay (m/explainer @request-schema)))
(defonce ^:private response-validator
  (delay (m/validator @response-schema)))
(defonce ^:private response-explainer
  (delay (m/explainer @response-schema)))
(defonce ^:private writer-terminal-result-validator
  (delay (m/validator @writer-terminal-result-schema)))

(defn- generated-candidate-keys-unique?
  [request]
  (if-let [candidates (::generated-candidates request)]
    (let [candidate-keys (map :seon.db.id/key candidates)]
      (= (count candidate-keys) (count (distinct candidate-keys))))
    true))

(defn- request-semantics-valid?
  [request]
  (and (request-cursors-match? request)
       (generated-candidate-keys-unique? request)))

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
      (when-not (request-cursors-match? request)
        {::cursor (::cursor request)
         ::error "Index-page cursor does not match request."})
      (when-not (generated-candidate-keys-unique? request)
        {::generated-candidates (::generated-candidates request)
         ::error "Generated candidate keys must be unique."})))

(defn valid-response?
  "True when `response` is one complete canonical protocol response."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [response]
  (and (@response-validator response)
       (ordinary-wire-value? response)))

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
        {::error "Protocol responses contain only eager ordinary wire values."})))

;;; Durable idempotency receipt

(def receipt-attributes
  #{:seon.db.protocol.tempid/key-edn
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
       ::expected-coordinate (::expected-coordinate request)
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
         {:db/id marker-id
          :seon.db.protocol.tempid/key-edn (pr-str tempid)
          :seon.db.protocol.tempid/entity tempid}))
     (range)
     tempids)))
