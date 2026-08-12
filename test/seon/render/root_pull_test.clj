(ns seon.render.root-pull-test
  "Class regressions for schema-derived root acquisition and membership."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.sci.kernel :as sci.kernel]
            [seon.test-support :as support]))

(def ^:private root-pull-schema
  [{:db/ident ::root-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::node-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::forward
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::edge
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::component
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true}
   {:db/ident ::value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private caps (config/result-caps (config/defaults)))

(defn- request
  [connection]
  {:seon.db/db @connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.render.walk/lookup [::root-id "root"]
   :seon.render/distance 1
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record})

(defn- acquire
  [connection]
  (walk/root-acquisition (request connection)))

(defn- member-lookups
  [acquisition]
  (set (keys (:seon.render.walk/members acquisition))))

(defn- changed-lookups
  [diff kind]
  (into #{} (map :seon.render.walk/lookup) (get diff kind)))

(deftest root-acquisition-contract-is-projection-neutral
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection [{::root-id "root" ::value "one"}])
     (let [acquisition-request (request connection)
           acquisition (walk/root-acquisition acquisition-request)
           units (walk/neighborhood
                  (assoc acquisition-request
                         :seon.render/output :seon.render/ai
                         :seon.sci.eval/time-limit-ms 5000
                         :seon.config/on-core-error :panic
                         :seon.render.walk/root-acquisition acquisition))]
       (is (not (contains? acquisition-request :seon.render/output)))
       (is (schema/valid-candidate-value?
            :seon.render.walk/acquisition-request acquisition-request))
       (is (= [::root-id "root"]
              (first (:seon.render.walk/order acquisition))))
       (is (schema/valid-candidate-value? :seon.render.walk/units units))
       (is (every? #(not (contains? % :seon.render.walk/changed-at))
                   units))))))

(defn- reverse-attribute
  [attribute]
  (keyword (namespace attribute) (str "_" (name attribute))))

(deftest root-selector-is-concrete-bidirectional-and-evidence-bearing
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection
                   [{::root-id "root"
                     ::component "component"}
                    {:db/id "component"
                     ::node-id "component"
                     ::value "before"}])
     (let [reads (atom [])
           pull db/pull
           count-read (fn [operation f]
                        (fn [& arguments]
                          (swap! reads conj operation)
                          (apply f arguments)))
           acquisition
           (with-redefs [db/q (count-read :q db/q)
                         db/pull (count-read :pull pull)
                         db/pull-many (count-read :pull-many db/pull-many)
                         db/entity (count-read :entity db/entity)
                         db/datoms (count-read :datoms db/datoms)]
             (acquire connection))
           selector (:seon.render.walk/selector acquisition)
           selector-values (tree-seq coll? seq selector)
           selector-map-keys (into #{}
                                   (comp (filter map?) (mapcat keys))
                                   selector-values)
           plan (:datahike.read/dependency-plan
                 (d/pull-with-evidence @connection selector [::root-id "root"]))
           attributes (d/dependency-plan-attributes plan 0)]
       (is (= [:pull] @reads)
           "cold root acquisition is exactly one pull and no other read")
       (is (not-any? #{'* :* "*"} selector-values)
           "the selector never widens its dependency fingerprint")
       (is (contains? selector-map-keys
                      [::forward :limit
                       (inc (:seon.config.eval.result/max-collection caps))])
           "the forward stored ref is nested and capped")
       (is (contains? selector-map-keys
                      [(reverse-attribute ::edge) :limit
                       (inc (:seon.config.eval.result/max-collection caps))])
           "the same stored ref has its reverse spelling")
       (is (set? attributes))
       (is (every? attributes
                   [::root-id ::node-id ::forward ::edge ::component ::value])
           "explicit component nesting keeps every concrete dependency")))))

(deftest root-membership-diffs-forward-reverse-and-component-changes
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection
                   [{::root-id "root"
                     ::component "component"}
                    {:db/id "component"
                     ::node-id "component"
                     ::value "before"}])
     (let [initial (acquire connection)]
       (testing "a forward boundary edge adds and removes one stable member"
         (db/transact! connection
                       [{::node-id "forward"}
                        {::root-id "root"
                         ::forward [::node-id "forward"]}])
         (let [with-forward (acquire connection)
               added (walk/membership-diff initial with-forward)]
           (is (contains? (member-lookups with-forward)
                          [::node-id "forward"]))
           (is (= #{[::node-id "forward"]}
                  (changed-lookups added :seon.render.walk/added)))
           (db/transact! connection
                         [[:db/retract [::root-id "root"] ::forward
                           [::node-id "forward"]]])
           (let [without-forward (acquire connection)
                 removed (walk/membership-diff with-forward without-forward)]
             (is (= #{[::node-id "forward"]}
                    (changed-lookups removed :seon.render.walk/removed))))))

       (testing "a reverse boundary edge uses the canonical stored ref"
         (let [before-reverse (acquire connection)]
           (db/transact! connection
                         [{::node-id "reverse"
                           ::edge [::root-id "root"]}])
           (let [with-reverse (acquire connection)
                 added (walk/membership-diff before-reverse with-reverse)]
             (is (= #{[::node-id "reverse"]}
                    (changed-lookups added :seon.render.walk/added)))
             (db/transact! connection
                           [[:db/retract [::node-id "reverse"] ::edge
                             [::root-id "root"]]])
             (let [without-reverse (acquire connection)
                   removed
                   (walk/membership-diff with-reverse without-reverse)]
               (is (= #{[::node-id "reverse"]}
                      (changed-lookups removed
                                       :seon.render.walk/removed)))))))

       (testing "a component-only touch changes the component, not its root"
         (let [before-component (acquire connection)]
           (db/transact! connection
                         [[:db/add [::node-id "component"] ::value "after"]])
           (let [after-component (acquire connection)
                 changed
                 (walk/membership-diff before-component after-component)]
             (is (= #{[::node-id "component"]}
                    (changed-lookups changed :seon.render.walk/changed)))
             (is (empty? (:seon.render.walk/added changed)))
             (is (empty? (:seon.render.walk/removed changed))))))))))

(deftest supplied-root-acquisition-is-the-only-membership-read
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection [{::root-id "root" ::value "one"}])
     (let [render-request (request connection)
           acquisition (walk/root-acquisition render-request)
           reads (atom 0)
           count-read (fn [f]
                        (fn [& arguments]
                          (swap! reads inc)
                          (apply f arguments)))]
       (with-redefs [db/q (count-read db/q)
                     db/pull (count-read db/pull)
                     db/pull-many (count-read db/pull-many)
                     db/datoms (count-read db/datoms)]
         (walk/neighborhood
          (assoc render-request
                 :seon.render/output :seon.render/ai
                 :seon.render.walk/root-acquisition acquisition))
         (is (zero? @reads)
             "neighborhood consumes the acquisition without discovery")
         (walk/history
          (assoc render-request
                 :seon.cluster.agent/id "root"
                 :seon.render.walk/root-acquisition acquisition
                 :seon.render/captured-calls (atom {})))
         (is (zero? @reads)
             "history shares the acquisition without a second discovery"))))))

(deftest declared-identity-precedes-a-lexically-earlier-database-unique
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection
                   [{::root-id "root" ::forward "both"}
                    {:db/id "both" ::root-id "lexical" ::node-id "declared"}])
     (with-redefs [sci.kernel/context-projection
                   (constantly
                    {:seon.schema.projection/shape-rows
                     {::declared {:seon.entity/id-attr ::node-id}}})]
       (let [acquisition (acquire connection)]
         (is (contains? (:seon.render.walk/members acquisition)
                        [::node-id "declared"])
             "production acquisition chooses the declared identity"))))))

(deftest as-of-revision-comparison-uses-the-database-read-owner
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection [{::root-id "root" ::value "one"}])
     (let [captured (atom [])
           database @connection
           acquisition (binding [db/*read-evidence-sink* captured]
                         (walk/root-acquisition (request connection)))
           call {:seon.render.call/read-evidence (db/read-evidence @captured)
                 :seon.render.call/output acquisition}
           fixed (db/as-of database (db/basis-t database))]
       (is (empty? (#'web/candidate-call-ids
                    {::root call} fixed))
           "an opening as-of database compares through seon.db revisions")))))

(deftest relevant-semantically-equal-root-read-replays-once-and-advances
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection [{::root-id "root" ::value "retained"}])
     (let [render-request (request connection)
           call-id ::root
           [_ initial-entry] (#'web/acquire-root render-request call-id)]
       (db/transact! connection [{::node-id "outside" ::value "changed"}])
       (let [database @connection
             retained {call-id initial-entry}
             candidates (#'web/candidate-call-ids retained database)
             pulls (atom 0)
             pull db/pull
             refreshed (with-redefs [db/pull
                                     (fn [& arguments]
                                       (swap! pulls inc)
                                       (apply pull arguments))]
                         (#'web/refresh-root
                          (assoc render-request :seon.db/db database)
                          retained call-id candidates))
             appended (web/append-history [] [])]
         (is (= #{call-id} candidates)
             "the relevant attribute revision selects the root read")
         (is (= 1 @pulls) "the semantically equal root read replays once")
         (is (false? (:changed? refreshed)))
         (is (empty? appended) "semantic equality appends no entry")
         (is (empty? (#'web/candidate-call-ids
                      {call-id (:entry refreshed)} database))
             "the consumed revision advances even when the result is equal"))))))

(deftest cold-root-pull-records-an-informational-latency-sample
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (db/transact! connection [{::root-id "root" ::value "sample"}])
     (let [started (System/nanoTime)
           acquisition (acquire connection)
           elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)]
       (println (pr-str {:seon.render.walk/cold-pull-ms elapsed-ms
                         :seon.render.walk/four-query-floor-ms 46.0}))
       (is (seq (:seon.render.walk/order acquisition))
           "latency is recorded while correctness remains the verdict")))))
