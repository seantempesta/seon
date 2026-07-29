(ns sample
  (:require [clojure.test :refer [deftest]]
            [seon.schema :as schema]))

(defn ^{:malli/schema [:=> [:cat :int] :int]}
  contracted
  [x]
  (inc x))

(def scratch 42)

(schema/register! ::amount [:int {:min 0}])

(deftest contracted-test)
