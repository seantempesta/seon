(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as seon.fn]))

(deftest index-rows-admit-only-the-canonical-program
  (let [root (str "tmp/fn-test/" (random-uuid))
        file (io/file root "sample.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str
           "(ns sample (:require [clojure.test :refer [deftest]] "
           "[seon.schema :as schema]))\n"
           "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
           "contracted [x] (inc x))\n"
           "(def scratch 42)\n"
           "(schema/register! ::amount [:int {:min 0}])\n"
           "(deftest contracted-test)"))
    (let [rows (seon.fn/rows {:seon.fn/roots [root]})]
      (is (= #{"sample/contracted"}
             (into #{} (keep :seon.fn/sym) rows)))
      (is (= #{"sample/contracted-test"}
             (into #{} (keep :seon.test/sym) rows)))
      (is (= #{:sample/amount}
             (into #{} (keep :seon.schema/key) rows)))
      (is (= #{'sample}
             (into #{} (keep :seon.ns/name) rows))))))
