(ns seon.render.root-pull-test
  "Class regressions for schema-derived root acquisition and membership."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
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
   :seon.render/output :seon.render/ai
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
     (let [pull-count (atom 0)
           pull db/pull
           acquisition
           (with-redefs [db/pull
                         (fn [& arguments]
                           (swap! pull-count inc)
                           (apply pull arguments))]
             (acquire connection))
           selector (:seon.render.walk/selector acquisition)
           selector-values (tree-seq coll? seq selector)
           selector-map-keys (into #{}
                                   (comp (filter map?) (mapcat keys))
                                   selector-values)
           plan (:datahike.read/dependency-plan
                 (d/pull-with-evidence @connection selector [::root-id "root"]))
           attributes (d/dependency-plan-attributes plan 0)]
       (is (= 1 @pull-count)
           "root acquisition enters the database read door exactly once")
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
                 :seon.render.walk/root-acquisition acquisition))
         (is (zero? @reads)
             "a supplied acquisition replaces every membership query"))))))

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
