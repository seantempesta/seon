(ns seon.fn-core-calls-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(def ^:private printer-symbols
  ["clojure.core/print"
   "clojure.core/println"
   "clojure.core/prn"
   "clojure.core/pr-str"])

(deftest core-call-census-is-non-vacuous
  (test-support/with-database
    (fn [connection]
      (let [targets
            (set
             (db/q '[:find [?target-symbol ...]
                     :in $ ?caller-symbol [?target-symbol ...]
                     :where
                     [?caller :seon.fn/sym ?caller-symbol]
                     [?caller :seon.fn/calls ?target]
                     [?target :seon.fn/sym ?target-symbol]]
                   @connection
                   "seon.fn/var-row"
                   printer-symbols))]
        (is (seq targets)
            "a planted core-printer caller must make the census non-vacuous")
        (is (= #{"clojure.core/pr-str"} targets)
            "the census resolves the call edge through the core function row")))))
