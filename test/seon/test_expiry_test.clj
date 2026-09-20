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
      (support/seed-cluster! connection "expiry")
      (support/transacted!
       connection
       [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                   (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [::expiry-inputs])}])
      (let [namespace-name (symbol (str "expiry.probe" (id/id)))
            test-var (intern (create-ns namespace-name) 'probe)
            finished (CountDownLatch. 1)
            outcome (promise)
            _ (support/transacted! connection [{:seon.ns/name namespace-name}])
            _ (support/transacted!
               connection
               [(support/program-row
                 (db/db connection) [:seon.test/sym (symbol (str namespace-name) "probe")]
                 "(clojure.test/deftest probe (seon.test-support/with-database (fn [child] (clojure.test/is (number? (seon.db/basis-t @child))))))")])
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
                            :seon.test.run/cluster [:seon.cluster/name "expiry"]
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
