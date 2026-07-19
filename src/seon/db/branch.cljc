(ns seon.db.branch
  "Define portable Datahike connection and Proximum branch values.

   These schemas name store, branch, basis-transaction, and commit facts shared
   across the database protocol; they do not manage live connections."
  (:require
   #?@(:bb []
       :clj [[datahike.api :as d]
             [datahike.constants :as constants]
             [datahike.db.interface :as dbi]]
       :default [])
   [seon.schema :as schema]))

(schema/register! ::store-id :uuid)
(schema/register! ::name :keyword)
(schema/register! ::commit-id :uuid)
(schema/register! ::basis-t [:int {:min 0}])

(schema/register!
 ::connection-id
 [:tuple ::store-id ::name])

(schema/register!
 ::head
 [:map {:closed true}
  [::store-id ::store-id]
  [::name ::name]
  [::commit-id ::commit-id]
  [::basis-t ::basis-t]])

;; A Datahike immutable database value is an opaque third-party record.
(schema/register! ::db-value :any)

(schema/register! ::target-basis-t ::basis-t)
(schema/register!
 ::at-request
 [:map {:closed true}
  [::db-value ::db-value]
  [::connection-id {:optional true} ::connection-id]
  [::target-basis-t ::target-basis-t]])

#?(:bb nil
   :clj
   (defn head
     "Return the branch head of a committed Datahike database value.

      Temporal wrapper values intentionally fail: Datahike `as-of` does not carry
      an independently selected commit id. Pin the containing committed database
      value first, then use `at` for a temporal cut within it."
     {:malli/schema [:=> [:catn [::db-value ::db-value]] ::head]}
     [db]
     (let [branch-head
           {::store-id (get-in db [:config :store :id])
            ::name (get-in db [:config :branch])
            ::commit-id (d/commit-id db)
            ::basis-t (dbi/-max-tx db)}]
       (when-not (schema/valid-candidate-value? ::head branch-head)
         (throw
          (ex-info "The database value has no complete branch head."
                   {::head branch-head
                    :seon.error/kind :core-bug})))
       branch-head)))

(defn connection-id
  "Return Datahike's self-writer connection ID for a branch head."
  {:malli/schema [:=> [:catn [::head ::head]] ::connection-id]}
  [branch-head]
  [(::store-id branch-head) (::name branch-head)])

(defn head-from-database-value
  "Translate Seon's ordinary database value into Proximum's branch-head fields."
  [database]
  (let [[store-id branch-name] (:store-id database)
        branch-head {::store-id store-id
                     ::name branch-name
                     ::commit-id (:datahike/commit-id database)
                     ::basis-t (:t database)}]
    (when-not (schema/valid-candidate-value? ::head branch-head)
      (throw
       (ex-info "The ordinary database value has no complete branch head."
                {:seon.db/db database
                 ::head branch-head
                 :seon.error/kind :invalid-database-value})))
    branch-head))

(defn same-connection?
  "True when two branch heads belong to the same Datahike connection."
  {:malli/schema
   [:=>
    [:catn
     [::left ::head]
     [::right ::head]]
    :boolean]}
  [left right]
  (= (connection-id left) (connection-id right)))

#?(:bb nil
   :clj
   (defn at
     "Return the exact basis transaction within one containing commit."
     {:malli/schema [:=> [:cat ::at-request] ::head]}
     [{::keys [db-value target-basis-t]
       expected-connection-id ::connection-id}]
     (let [container (head db-value)
           actual-connection-id (connection-id container)
           _ (when (and expected-connection-id
                        (not= expected-connection-id actual-connection-id))
               (throw
                (ex-info "The connection ID names a different database branch."
                         {::connection-id expected-connection-id
                          ::head container
                          :seon.error/kind :invalid-database-value})))
           max-t (::basis-t container)]
       (when-not (<= constants/tx0 target-basis-t max-t)
         (throw
          (ex-info "The temporal cut is outside its containing commit."
                   {::target-basis-t target-basis-t
                    ::head container
                    :seon.error/kind :invalid-database-value})))
       (when (and (> target-basis-t constants/tx0)
                  (empty? (d/datoms db-value :eavt target-basis-t
                                    :db/txInstant)))
         (throw
          (ex-info "The temporal cut is not an exact committed transaction."
                   {::target-basis-t target-basis-t
                    ::head container
                    :seon.error/kind :invalid-database-value})))
       (assoc container ::basis-t target-basis-t))))
