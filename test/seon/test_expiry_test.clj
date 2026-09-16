(ns seon.test-expiry-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.gc-guard :as guard]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest expiry-does-not-interrupt-a-fixture-awaiting-its-roster-permit
  (support/with-database
    (fn [connection]
      (let [namespace-name (symbol (str "expiry.probe" (id/id)))
            test-var (intern (create-ns namespace-name) 'probe)
            finished (CountDownLatch. 1)
            outcome (promise)
            permit (guard/acquire-reachability-permit!
                     (get-in @connection [:config :store :id]) :roster)]
        (alter-meta! test-var assoc :test
          (fn []
            (try
              (support/with-database
                (fn [child]
                  (let [observed (number? (db/basis-t @child))]
                    (is observed)
                    (deliver outcome observed))))
              (catch Throwable failure (deliver outcome failure))
              (finally (.countDown finished)))))
        (try
          (let [database (db/db connection)
                result (try
                         (sut/run test-var connection
                           {:seon.db/db database
                            :seon.test.run/provenance (runner/provenance database)
                            :seon.test/remaining-ms 1000})
                         (finally (guard/release-reachability-permit! permit)))]
            (is (= 1 (:seon.test/error-count result)) (pr-str result))
            (is (.await finished support/event-backstop-seconds TimeUnit/SECONDS)
                "the expired daemon completes its fixture after admission resumes")
            (is (= true (deref outcome 0 :unavailable))
                "expiry never interrupts the branch acquisition")
            (support/with-database
              (fn [later] (is (number? (db/basis-t @later))
                             "later fixtures still acquire and release the permit"))))
          (finally (remove-ns namespace-name)))))))
