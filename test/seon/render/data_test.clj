(ns seon.render.data-test
  "The shared routed-floor cursor: total parsing and `get-in` selection."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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
