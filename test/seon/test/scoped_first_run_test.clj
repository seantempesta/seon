(ns seon.test.scoped-first-run-test
  "An incremental request naming changed identities selects their reach even
  in a world with no run history; only an unscoped first run widens to every
  test (a clean member world never has a previous run)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.program :as program]
            [seon.test :as sut]
            [seon.test-support :as support]))

(defn- install!
  "Admit `source` as entities of the `scoped.fixture` namespace."
  {:malli/schema [:=> [:cat :seon.db/connection :string] :nil]}
  [connection source]
  (let [database (db/db connection)
        projection (db/carried-projection database)]
    (support/transacted!
     connection
     [(program/declaration-row projection {:seon.ns/name 'scoped.fixture
                                           :seon.ns/source "(ns scoped.fixture)"}
                               :all :agent)])
    (support/transacted!
     connection
     (functions/source-rows (db/db connection) (program/shapes-in projection)
                            (db/pull (db/db connection) '[*] [:seon.ns/name 'scoped.fixture])
                            source (set (keys (:seon.schema.projection/forms projection)))))
    nil))

(deftest a-first-incremental-request-naming-a-change-selects-only-its-reach
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "scoped-first-run")
     (install! connection
               "(defn leaf [] 1)
                (defn stranger [] 2)
                (clojure.test/deftest direct (clojure.test/is (= 1 (leaf))))
                (clojure.test/deftest unrelated (clojure.test/is (= 2 (stranger))))")
     (let [started (System/nanoTime)
           selected (sut/select {:seon.db/db (db/db connection)
                                 :seon.test.run/cluster [:seon.cluster/name "scoped-first-run"]
                                 :seon.test.run/policy :incremental
                                 :seon.test/changed ['scoped.fixture/leaf]})
           elapsed-ms (/ (- (System/nanoTime) started) 1e6)
           members (set (map :seon.test/sym (:seon.test.run/members selected)))]
       (is (nil? (:seon.error/at selected)))
       ;; This test's own entity names `leaf` too, so it reaches it as well.
       (is (= #{'scoped.fixture/direct} (set (filter #(= "scoped.fixture" (namespace %)) members))))
       (is (> 5 (count members)) "the request never widens to every test")
       (is (< elapsed-ms 5000))))))
