(ns seon.render.data-test
  "The shared routed-floor cursor: total parsing and `get-in` selection."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.render.data :as data]
            [seon.schema]))

(defn- cursor
  ([] (cursor [] 0))
  ([path offset] {:seon.render.data/path path
                  :seon.render.data/offset offset}))

(deftest cursor-parsing-is-total-and-round-trips-a-get-in-path
  (let [path [:seon.error/data 2 "key"]]
    (is (= (cursor path 12)
           (data/parse-cursor (pr-str path) "12"))))
  (testing "stale query data returns the root cursor"
    (doseq [[path offset] [[nil nil] ["" ""] ["{not a vector}" "-5"]
                           ["((((" "abc"] ["\"a string\"" "9999999999999999999999"]]]
      (let [parsed (data/parse-cursor path offset)]
        (is (vector? (:seon.render.data/path parsed)))
        (is (nat-int? (:seon.render.data/offset parsed))))))
  (let [check (tc/quick-check
               300
               (prop/for-all
                [path (gen/one-of [gen/string-ascii (gen/return nil)])
                 offset (gen/one-of [gen/string-ascii (gen/return nil)])]
                (seon.schema/valid-candidate-value?
                 :seon.render.data/cursor (data/parse-cursor path offset)))
               :seed 202607280401)]
    (is (true? (:result check)) (pr-str check))))

(def ^:private nested-value
  {:agents [{:id "root" :runs [1 2 3]} {:id "b"}]
   :counts {:a 1 :b 2}
   :tags #{:x :y}})

(deftest get-in-selection-supports-maps-sequences-and-sets
  (is (= "root" (:seon.render.data/value
                  (data/at nested-value (cursor [:agents 0 :id] 0)))))
  (is (= 2 (:seon.render.data/value
            (data/at nested-value (cursor [:counts :b] 0)))))
  (is (= :x (:seon.render.data/value
             (data/at nested-value (cursor [:tags :x] 0)))))
  (is (= 3 (:seon.render.data/value
            (data/at (iterate inc 0) (cursor [3] 0))))
      "an explicit index does not count or realize an unbounded sequence")
  (is (= nested-value (:seon.render.data/value
                       (data/at nested-value (cursor))))))

(deftest a-missing-path-is-distinct-from-a-present-nil
  (let [refused (data/at nested-value (cursor [:agents 99] 0))]
    (is (seon.schema/valid-candidate-value? :seon.error/value refused))
    (is (= :seon.render.data/no-such-path (:seon.error/kind refused))))
  (let [found (data/at {:present nil} (cursor [:present] 0))]
    (is (contains? found :seon.render.data/value))
    (is (nil? (:seon.render.data/value found)))))

(deftest observation-pages-preserve-facts-and-refuse-cross-snapshot-cursors
  (support/with-database
   {:seon.test-support/extra-schema
    [{:db/ident ::id :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
     {:db/ident ::link :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many}]}
   (fn [connection]
     (d/transact connection [{:db/id "a" ::id "a" ::link ["a" "b"]}
                             {:db/id "b" ::id "b" ::link ["a"]}])
     (let [database @connection
           request {:seon.db/db database ::data/subject [::id "a"]
                    ::data/limit 1 ::data/max-result-weight 65536
                    ::data/max-ref-attributes 200}
           first-page (data/entity-observation request)
           cursor (get-in first-page [::data/outgoing ::data/continuation])
           second-page (data/entity-observation (assoc request ::data/outgoing-cursor cursor))
           eid (::data/eid first-page)
           expected (mapv #(select-keys % [:e :a :v :tx :added])
                          (d/datoms database :eavt eid))
           pages (take (count expected)
                       (iterate (fn [page]
                                  (data/entity-observation
                                   (assoc request ::data/outgoing-cursor
                                          (get-in page [::data/outgoing ::data/continuation]))))
                                first-page))]
       (is (pos-int? eid))
       (is (= expected (into [] (mapcat #(get-in % [::data/outgoing ::data/datoms])) pages)))
       (is (false? (::data/identities-complete? first-page)))
       (is (false? (::data/identities-complete? (last pages))))
       (is (not= (get-in first-page [::data/outgoing ::data/datoms])
                 (get-in second-page [::data/outgoing ::data/datoms])))
       (is (= ::data/missing-subject
              (:seon.error/kind (data/entity-observation
                                (assoc request ::data/subject [::id "absent"])))))
       (is (= ::data/stale-continuation
              (:seon.error/kind (data/entity-observation
                                (assoc request ::data/subject [::id "b"]
                                       ::data/outgoing-cursor cursor)))))
       (is (= (:t (::data/snapshot first-page)) (db/basis-t @connection))
           "observation itself transacts nothing")
       (d/transact connection [{::id "c"}])
       (is (= ::data/stale-continuation
              (:seon.error/kind (data/entity-observation
                                (assoc request :seon.db/db @connection
                                       ::data/outgoing-cursor cursor)))))
       (let [incoming (data/entity-observation (assoc request ::data/limit 200))
             incoming-rows (get-in incoming [::data/incoming ::data/datoms])]
         (is (= 2 (count (filter #(= ::link (:a %)) incoming-rows)))))))))

(deftest index-pages-enforce-native-bounds
  (support/with-database
   {:seon.test-support/extra-schema
    [{:db/ident ::raw-value :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one}]}
   (fn [connection]
     (d/transact connection [{::raw-value "fixture"}])
     (let [database @connection
           eid (db/q '[:find ?e . :where [?e ::raw-value "fixture"]] database)
           options {:index :eavt :components [eid ::raw-value]
                    :direction :forward :limit 1 :max-result-weight 65536}
           result (db/index-page database options)]
       (is (pos-int? eid))
       (is (= "fixture" (:v (first (:datahike.index-page/datoms result)))))
       (is (:seon.error/kind
            (db/index-page database (assoc options :max-result-weight 1))))
       (is (:seon.error/kind
            (db/index-page database
                           (assoc options :cursor
                                  [eid ::raw-value "missing" 1 true]))))))))
