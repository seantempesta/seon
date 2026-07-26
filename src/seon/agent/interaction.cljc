(ns seon.agent.interaction
  "Database-owned authored interaction facts and receipt transactions.

   One interaction entity is also one open run entity. The cluster JVM
   attaches that run to its subject agent, records a durable running receipt,
   invokes the pinned authored handler, and atomically records the terminal
   outcome with the held run fence."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require
   [#?(:clj clojure.edn :cljs cljs.reader) :as reader]
   [malli.core :as m]
   [seon.agent.run.core :as run.core]
   [seon.content-hash :as content-hash]
   [seon.db.id :as db.id]
   [seon.db.protocol]
   [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

;;; ---------------------------------------------------------------------------
;;; Interaction facts
;;; ---------------------------------------------------------------------------

(schema/register!
 :seon.agent.interaction/id
 [:and {:seon.db/identity true
        :seon.db.id/generator :seon.db.id.generator/compact}
  ::db.id/compact-value])
(schema/register! :seon.agent.interaction/handler :qualified-symbol)
(schema/register!
 :seon.agent.interaction/handler-source-fingerprint
 [:and
  {:gen/elements
   ["0000000000000000000000000000000000000000000000000000000000000000"]}
  ::content-hash/digest])

;; This is the deliberately polymorphic persistence boundary. Every member is
;; EDN-readable ordinary data; nested maps own their own schema-projected
;; contents, while sequential values stay bounded to EDN-safe members.
(schema/register!
 ::persisted-member
 [:or :nil :boolean :int :double :string :keyword :symbol :uuid :inst :map])
(schema/register!
 ::persisted-value
 [:or
  ::persisted-member
  [:vector ::persisted-member]
  [:set ::persisted-member]])

(defn persisted-value?
  "Whether one ordinary value has the interaction slot's lossless EDN shape."
  {:malli/schema
   [:=> [:cat :seon.db.protocol/ordinary-wire-value] :boolean]}
  [value]
  (and
   (schema/valid-candidate-value? ::persisted-value value)
   (try
     (= value (reader/read-string (pr-str value)))
     (catch #?(:clj Throwable :cljs :default) _ false))))

;; These are cardinality-one EDN slots. The explicit storage property keeps
;; the complete value in one string datom while both union arms preserve the
;; intended Malli value set. Arguments therefore remain an ordered vector
;; instead of becoming cardinality-many datoms.
(schema/register!
 :seon.agent.interaction/arguments
 [:or {:seon.db/value-type :db.type/string}
  [:vector ::persisted-value]
  [:tuple]])
(schema/register!
 ::projected-arguments
 [:and
  :seon.agent.interaction/arguments
  [:vector :seon.db.protocol/ordinary-wire-value]])
(schema/register!
 :seon.agent.interaction/subjects
 [:set {:min 1} :seon.db/ref])
(schema/register!
 :seon.agent.interaction/status
 [:enum :pending :running :done :error :interrupted])
(schema/register!
 :seon.agent.interaction/result
 [:or {:seon.db/value-type :db.type/string}
  ::persisted-member
  [:vector ::persisted-member]
  [:set ::persisted-member]])
(schema/register!
 ::flat-error
 [:map
  [:seon.error/message :string]
  [:seon.error/kind {:optional true} :keyword]
  [:seon.error/data
   {:optional true} ::persisted-value]])
(schema/register!
 :seon.agent.interaction/error
 [:or {:seon.db/value-type :db.type/string}
  ::flat-error
  [:and ::flat-error ::flat-error]])

(schema/register!
 ::entity
 [:map {:seon.db/entity true}
  [:seon.agent.interaction/id :seon.agent.interaction/id]
  [:seon.agent.interaction/handler :seon.agent.interaction/handler]
  [:seon.agent.interaction/handler-source-fingerprint
   :seon.agent.interaction/handler-source-fingerprint]
  [:seon.agent.interaction/arguments :seon.agent.interaction/arguments]
  [:seon.agent.interaction/subjects :seon.agent.interaction/subjects]
  [:seon.agent.interaction/status :seon.agent.interaction/status]
  [:seon.agent.interaction/result
   {:optional true} :seon.agent.interaction/result]
  [:seon.agent.interaction/error
   {:optional true} :seon.agent.interaction/error]])

(schema/register!
 ::validated-request
 [:map
  [:seon.agent/id :string]
  [:seon.agent.interaction/handler :seon.agent.interaction/handler]
  [:seon.agent.interaction/handler-source-fingerprint
   :seon.agent.interaction/handler-source-fingerprint]
  [:seon.agent.interaction/arguments ::projected-arguments]])
(schema/register!
 ::validation-input
 [:map
  [:seon.agent/id :string]
  [:seon.agent.interaction/handler :seon.agent.interaction/handler]
  [:seon.agent.interaction/handler-source-fingerprint
   :seon.agent.interaction/handler-source-fingerprint]
  [:seon.agent.interaction/handler-spec :string]
  [:seon.agent.interaction/arguments ::projected-arguments]
  [:seon.schema/projection :seon.schema/projection]])
(schema/register! ::validation-result [:or ::validated-request ::flat-error])

(defn validate-request
  "Validate one argument vector against the exact committed handler contract.

   The caller supplies the function row's persisted spec and source
   fingerprint from the same immutable database value used by its capability
   check. A failure is flat error data and cannot create an interaction fact."
  {:malli/schema [:=> [:catn [::input ::validation-input]]
                  ::validation-result]}
  [{agent-id :seon.agent/id
    handler :seon.agent.interaction/handler
    fingerprint :seon.agent.interaction/handler-source-fingerprint
    spec :seon.agent.interaction/handler-spec
    arguments :seon.agent.interaction/arguments
    projection :seon.schema/projection}]
  (if-not (and (schema/valid-candidate-value?
                ::projected-arguments arguments)
               (every? persisted-value? arguments))
    {:seon.error/message
     "Interaction arguments must be lossless ordinary persisted data."
     :seon.error/kind :user-input
     :seon.error/data
     {:seon.agent.interaction/handler handler}}
    (try
      (let [function-schema
            (m/function-schema
             (reader/read-string spec)
             (:seon.schema.projection/compile-options projection))
            valid?
            (some
             (fn [arity-schema]
               (let [input (:input (m/-function-info arity-schema))]
                 ((m/-validator input) arguments)))
             (m/-function-schema-arities function-schema))]
        (if valid?
          {:seon.agent/id agent-id
           :seon.agent.interaction/handler handler
           :seon.agent.interaction/handler-source-fingerprint fingerprint
           :seon.agent.interaction/arguments arguments}
          {:seon.error/message
           (str "Arguments do not satisfy the committed schema for "
                handler ".")
           :seon.error/kind :user-input
           :seon.error/data
           {:seon.agent.interaction/handler handler
            :seon.agent.interaction/argument-count (count arguments)}}))
      (catch #?(:clj Throwable :cljs :default) _
        {:seon.error/message
         (str "The committed schema for " handler " is not executable.")
         :seon.error/kind :core-bug
         :seon.error/data
         {:seon.agent.interaction/handler handler}}))))

(schema/register!
 ::open-request
 [:map
  [:seon.agent.interaction/id ::db.id/compact-value]
  [:seon.agent.run/id ::db.id/compact-value]
  [:seon.agent/id :string]
  [:seon.agent.interaction/handler :seon.agent.interaction/handler]
  [:seon.agent.interaction/handler-source-fingerprint
   :seon.agent.interaction/handler-source-fingerprint]
  [:seon.agent.interaction/arguments ::projected-arguments]
  [:seon.agent.interaction/subjects :seon.agent.interaction/subjects]
  [:seon.agent.interaction/requested-at :inst]])

(defn open-tx-data
  "Build one pending interaction/run fact.

   The run is deliberately not attached to `:seon.agent/run` here. Submission
   always commits and acknowledges; the run acquisition transaction
   later CASes an idle agent pointer from nil to this run. Multiple
   interactions may therefore queue without a second scheduler."
  {:malli/schema [:=> [:catn [::request ::open-request]]
                  :seon.db/tx-data]}
  [{interaction-id :seon.agent.interaction/id
    run-id :seon.agent.run/id
    agent-id :seon.agent/id
    handler :seon.agent.interaction/handler
    fingerprint :seon.agent.interaction/handler-source-fingerprint
    arguments :seon.agent.interaction/arguments
    subjects :seon.agent.interaction/subjects
    requested-at :seon.agent.interaction/requested-at}]
  [{:seon.agent.interaction/id interaction-id
    :seon.agent.interaction/handler handler
    :seon.agent.interaction/handler-source-fingerprint fingerprint
    :seon.agent.interaction/arguments arguments
    :seon.agent.interaction/subjects
    (conj subjects [:seon.agent/id agent-id])
    :seon.agent.interaction/status :pending
    :seon.agent.run/id run-id
    :seon.agent.run/agent [:seon.agent/id agent-id]
    :seon.agent.run/started-at requested-at
    :seon.agent.run/status :open}])

(schema/register!
 ::receipt-request
 [:map
  [:seon.agent/id :string]
  [:seon.agent.run/id ::db.id/compact-value]
  [:seon.agent.run/claim-epoch [:int {:min 1}]]
  [:seon.agent.interaction/id ::db.id/compact-value]])

(defn start-tx-data
  "CAS one pending interaction to its durable running receipt.

   The transition is under the run
   pointer and epoch fence. If this transaction does not commit, SCI does not
   run."
  {:malli/schema [:=> [:catn [::request ::receipt-request]]
                  :seon.db/tx-data]}
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    interaction-id :seon.agent.interaction/id}]
  (conj
   (run.core/run-fence agent-id run-id claim-epoch)
   [:db.fn/cas [:seon.agent.interaction/id interaction-id]
    :seon.agent.interaction/status :pending :running]))

(schema/register!
 ::success-request
 [:map
  [:seon.agent/id :string]
  [:seon.agent.run/id ::db.id/compact-value]
  [:seon.agent.run/claim-epoch [:int {:min 1}]]
  [:seon.agent.interaction/id ::db.id/compact-value]
  [:seon.agent.interaction/result
   :seon.agent.interaction/result]
  [:seon.agent.interaction/settled-at :inst]])

(defn success-tx-data
  "Atomically terminalize a running receipt.

   The transaction commits its ordinary result and closes/releases the held
   run."
  {:malli/schema [:=> [:catn [::request ::success-request]]
                  :seon.db/tx-data]}
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    interaction-id :seon.agent.interaction/id
    result :seon.agent.interaction/result
    settled-at :seon.agent.interaction/settled-at}]
  (into
   (run.core/finish-tx-data
    agent-id run-id claim-epoch :completed settled-at)
   [[:db.fn/cas [:seon.agent.interaction/id interaction-id]
     :seon.agent.interaction/status :running :done]
    [:db/add [:seon.agent.interaction/id interaction-id]
     :seon.agent.interaction/result result]]))

(schema/register!
 ::error-request
 [:map
  [:seon.agent/id :string]
  [:seon.agent.run/id ::db.id/compact-value]
  [:seon.agent.run/claim-epoch [:int {:min 1}]]
  [:seon.agent.interaction/id ::db.id/compact-value]
  [:seon.agent.interaction/observed-status
   [:enum :pending :running]]
  [:seon.agent.interaction/terminal-status
   [:enum :error :interrupted]]
  [:seon.agent.interaction/error :seon.agent.interaction/error]
  [:seon.agent.interaction/settled-at :inst]])

(defn error-tx-data
  "Atomically record one flat interaction error and close/release its run.

   `:running → :interrupted` is the takeover rule: a durable admitted handler
   is never replayed after process loss. `:pending → :error` is used when a
   run bound closes queued work before SCI admission."
  {:malli/schema [:=> [:catn [::request ::error-request]]
                  :seon.db/tx-data]}
  [{agent-id :seon.agent/id
    run-id :seon.agent.run/id
    claim-epoch :seon.agent.run/claim-epoch
    interaction-id :seon.agent.interaction/id
    observed-status :seon.agent.interaction/observed-status
    terminal-status :seon.agent.interaction/terminal-status
    error :seon.agent.interaction/error
    settled-at :seon.agent.interaction/settled-at}]
  (into
   (run.core/finish-tx-data agent-id run-id claim-epoch :error settled-at)
   [[:db.fn/cas [:seon.agent.interaction/id interaction-id]
     :seon.agent.interaction/status observed-status terminal-status]
    [:db/add [:seon.agent.interaction/id interaction-id]
     :seon.agent.interaction/error error]]))
