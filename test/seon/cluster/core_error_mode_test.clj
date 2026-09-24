(ns seon.cluster.core-error-mode-test
  "An unreadable core-error dial panics; it never selects record mode."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fault :as fault]
            [seon.test-support :as support]))

(deftest an-unreadable-core-error-dial-panics-and-prints-its-refusal
  (let [database (db/db (:seon.db/connection (support/execution-handle nil)))
        cluster-name (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)
        mode (#'cluster/core-error-mode database cluster-name)
        printed (java.io.StringWriter.)
        unreadable (binding [*err* printed]
                     (#'cluster/core-error-mode database "no-such-cluster"))]
    (is (= (:seon.config/on-core-error (config/effective database cluster-name)) mode))
    (is (#{:record :panic} mode))
    (is (nil? unreadable))
    (is (= :panic (fault/policy unreadable true)))
    (is (str/includes? (str printed) "SEON CORE-ERROR MODE UNREADABLE (panic)"))
    (is (str/includes? (str printed) ":seon.config/missing-effective \"no-such-cluster\""))))
