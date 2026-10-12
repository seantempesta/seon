(ns seon.datahike-fork-test
  "Seon-owned acceptance checks for behavior maintained in its Datahike fork."
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.walk :as walk]
   [datahike.datom :as datom]
   [datahike.db :as db]
   [datahike.index.persistent-set]
   [datahike.query :as query]
   [datahike.writer]
   [konserve.core :as k]
   [konserve.protocols :as store]
   [konserve.serializers :as serializers]
   [org.replikativ.persistent-sorted-set.fressian :as pss-fress]
   [seon.cluster.registry :as registry]
   [seon.cluster.store]
   [seon.db]
   [seon.profile :as profile]
   [seon.schema]
   [seon.test-support]
   [seon.turn]))

(deftest ^{:seon/tasks #{'seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply}}
  a-branch-serializes-only-its-commit-pointer
  (seon.test-support/with-database
   (fn [connection]
     (let [database (seon.db/db connection)
           physical (:seon.store/store (seon.test-support/execution-handle connection))
           branch (keyword (str "cost-acquire-" (random-uuid)))
           commit (seon.db/commit-id database)
           serializer (serializers/fressian-serializer
                       {} (merge pss-fress/write-handlers pss-fress/root-write-handlers
                                 @#'datahike.index.persistent-set/datom-write-handler))
           handlers (atom {})]
       (try
         (registry/branch! {:seon.store/store physical :seon.store/branch branch
                            :seon.cluster.registry/from commit})
         (let [head (k/get (:store database) branch nil {:sync? true})
               measured (profile/measure
                         #(let [out (java.io.ByteArrayOutputStream.)]
                            (store/-serialize serializer out handlers head)
                            (.size out)))
               reopened (seon.cluster.store/open-branch! physical branch)]
           (try
             (is (= commit (seon.db/commit-id @reopened)))
             (is (= (:max-tx database) (:max-tx @reopened)))
             (is (= branch (get-in @reopened [:config :branch])))
             (is (= #{:meta} (set (keys head))))
             (is (< (:seon.profile/alloc-bytes measured) 64000))
             (finally (seon.cluster.store/release-branch! reopened))))
         (finally
           (registry/retire-branch! {:seon.store/store physical :seon.store/branch branch})))))))

(deftest ^{:seon/tasks #{'seon.cluster.store/each-commit-serializes-hundreds-of-times-the-bytes-it-writes}}
  datom-serialization-writes-fields-without-sequence-copies
  (let [values (mapv #(datom/datom % :cost/value % (+ 536870912 %) (even? %)) (range 1000))
        serializer (serializers/fressian-serializer
                    @#'datahike.index.persistent-set/datom-read-handler
                    @#'datahike.index.persistent-set/datom-write-handler)
        handlers (atom {})
        encode #(let [out (java.io.ByteArrayOutputStream.)]
                  (store/-serialize serializer out handlers values)
                  (.toByteArray out))
        _ (encode)
        measured (profile/measure encode)
        decoded (store/-deserialize serializer handlers
                                    (java.io.ByteArrayInputStream. (:seon.profile/value measured)))]
    (is (= (mapv #(vec (seq %)) values) (mapv #(vec (seq %)) decoded)))
    (is (< (:seon.profile/alloc-bytes measured) 1000000))))

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
    (is (:result check))))

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
    (is (some? (:db-after result)))
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
