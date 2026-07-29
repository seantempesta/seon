(ns seon.fn-test
  (:require [clojure.test :refer [deftest is]]
            [seon.fn :as seon.fn]))

(deftest index-rows-admit-only-the-canonical-program
  (let [rows (seon.fn/rows {:seon.fn/roots ["test/seon/fixtures/program"]})]
    (is (= #{"sample/contracted"}
           (into #{} (keep :seon.fn/sym) rows)))
    (is (= #{"sample/contracted-test"}
           (into #{} (keep :seon.test/sym) rows)))
    (is (= #{:sample/amount}
           (into #{} (keep :seon.schema/key) rows)))
    (is (= #{'sample}
           (into #{} (keep :seon.ns/name) rows)))))
