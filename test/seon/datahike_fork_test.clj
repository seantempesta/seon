(ns seon.datahike-fork-test
  "Seon-owned acceptance checks for behavior maintained in its Datahike fork."
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.walk :as walk]
   [datahike.db :as db]
   [datahike.query :as query]
   [datahike.writer]
   [seon.db]
   [seon.schema]
   [seon.test-support]
   [seon.turn]))

(def ^:private planner-database
  (db/empty-db {}))

(def ^:private canonical-vars
  '[?first ?second ?third])

(def ^:private variable-name-generator
  (gen/vector-distinct
   (gen/elements
    '[?a ?b ?c ?agent ?block ?item ?root ?value ?x ?y ?z])
   {:num-elements 3}))

(defn- independent-function-clauses
  [variables]
  (mapv (fn [ordinal variable]
          [(list 'identity ordinal) variable])
        (range 1 4)
        variables))

(defn- create-plan
  [clauses]
  (#'query/create-plan-via-ir planner-database clauses #{} nil nil))

(deftest alpha-renaming-does-not-change-plan-selection
  (let [canonical-plan
        (create-plan (independent-function-clauses canonical-vars))
        check
        (tc/quick-check
         100
         (prop/for-all
          [renamed-vars variable-name-generator]
          (let [renamed-plan
                (create-plan (independent-function-clauses renamed-vars))
                renamed->canonical
                (zipmap renamed-vars canonical-vars)]
            (= canonical-plan
               (walk/postwalk-replace renamed->canonical renamed-plan))))
         :seed 1785366001)]
    (is (:result check) (pr-str check))))

(deftest schema-deletion-reads-earlier-declarations-in-one-transaction
 (seon.test-support/with-database
  (fn [connection]
   (let [key :turn-schema-cache/value
         schema-form [:int {:seon.db/index true}]
         ;; The writer stores a declaration's canonical printing, without
         ;; namespace maps, whatever the runner binds.
         form (binding [*print-namespace-maps* false] (pr-str schema-form))
         observed (atom nil)
         before (seon.db/db connection)
         _ (seon.schema/projection-from-database before (seon.db/carried-projection before))
         result (seon.db/transact! connection
                  {:tx-data
                   [[:db.fn/call #'seon.turn/row-tx {}
                     {:seon.schema/key key :seon.schema/form form}]
                    [:db.fn/call
                     (fn [during]
                      (reset! observed
                       {:seon.test/form
                        (seon.db/q '[:find ?form . :in $ ?key
                                     :where [?e :seon.schema/key ?key]
                                            [?e :seon.schema/form ?form]] during key)
                        :seon.test/projected-form
                        (get (:seon.schema.projection/forms
                              (seon.schema/projection-from-database
                               during (seon.db/carried-projection during))) key)
                        :seon.test/cache-identity (db/committed-cache-identity during)})
                      (#'seon.turn/row-tx during {}
                       {:seon.program/delete-identities [[:seon.schema/key key]]}))]]})
         after (seon.db/db connection)
         forms (:seon.schema.projection/forms
                (seon.schema/projection-from-database after (seon.db/carried-projection after)))]
    (is (some? (:db-after result)) (pr-str result))
    (is (= form (:seon.test/form @observed)))
    (is (= schema-form (:seon.test/projected-form @observed)))
    (is (nil? (:seon.test/cache-identity @observed)))
    ;; Deletion retracts the whole declaration entity (row-tx, a95f8dee0).
    (is (nil? (seon.db/pull after [:seon.schema/key :seon.schema/form] [:seon.schema/key key])))
    (is (not (contains? (:schema after) key)))
    (is (map? forms))
    (is (not (contains? forms key)))))))

(deftest a-write-error-log-carries-no-stack-trace
  ;; A failed transact! printed its whole stack (~30 frames) through trove to
  ;; the writer thread's inherited *out*, which eval_clj replies carried
  ;; unbounded; the caller already receives the error as the transaction's
  ;; value (fork 591c825d).
  (let [failure (ex-info "probe boom" {:seon.probe/secret 1}
                         (IllegalStateException. "root"))
        logged (#'datahike.writer/write-error-log
                {:config {:branch :db} :meta {}} {:op 'transact!} failure)]
    (is (= 'transact! (:op logged)))
    (is (= "root" (get-in logged [:error :cause])))
    (is (= 2 (count (get-in logged [:error :via]))) "each cause link is named")
    (is (not (contains? (:error logged) :trace)) "no stack trace")
    (is (not-any? :data (get-in logged [:error :via])) "no exception data")))
