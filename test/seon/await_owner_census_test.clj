(ns seon.await-owner-census-test
  "Program-graph census for the single bounded-await owner."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(def ^:private await-owner
  "seon.await/await!")

(def ^:private declared-production-callers
  #{"seon.effect/dispatch"
    "seon.flow/stop-work-launcher!"
    "seon.render/acquire-context!"
    "seon.render.web/settle-package!"
    "seon.render.web/write-package!"
    "seon.shell.jvm/task-result"})

(defn- production-callers
  [database owner-symbol]
  (set
   (db/q '[:find [?caller-symbol ...]
           :in $ ?owner-symbol
           :where
           [?owner :seon.fn/sym ?owner-symbol]
           [?caller :seon.fn/calls ?owner]
           [?caller :seon.fn/sym ?caller-symbol]]
         database
         owner-symbol)))

(deftest bounded-await-owner-has-exactly-the-declared-production-callers
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            owners
            (db/q '[:find [?owner ...]
                    :in $ ?owner-symbol
                    :where
                    [?owner :seon.fn/sym ?owner-symbol]]
                  database
                  await-owner)
            callers (production-callers database await-owner)]
        (testing "the census proves that it inspected the owner subject"
          (is (= 1 (count owners)) (pr-str owners)))
        (testing "every production member is declared and new members fail"
          (is (= declared-production-callers callers)
              (pr-str {:expected declared-production-callers
                       :actual callers})))))))
