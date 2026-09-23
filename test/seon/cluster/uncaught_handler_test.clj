(ns seon.cluster.uncaught-handler-test
  "The process uncaught handler stores a failure in the world it carries."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.test-support :as support])
  (:import [java.time Duration]))

(defn- exception-errors
  [connection]
  (set (db/q '[:find [?error ...]
               :where [?error :seon.error/throwable-class "clojure.lang.ExceptionInfo"]]
             (db/db connection))))

(deftest an-uncaught-failure-is-stored-in-the-world-it-carries
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "uncaught-world")
     (let [message (str "uncaught probe " (random-uuid))
           environment (env/environment
                        {:seon.boot/cluster-name "uncaught-world"
                         :seon.db/connection connection
                         :seon.sci.admit/caps (config/result-caps (support/effective-config))
                         :seon.config/on-core-error :record})
           before (exception-errors connection)
           thread (.unstarted (Thread/ofVirtual)
                              (fn [] (throw (ex-info message {:seon.env/environment environment}))))]
       (is (some? (Thread/getDefaultUncaughtExceptionHandler))
           "Boot installed the process handler.")
       (.start thread)
       (is (.join thread (Duration/ofMillis 5000)) "The failing thread exited.")
       (let [added (remove before (exception-errors connection))]
         (is (= 1 (count added)) (pr-str added))
         (is (str/includes? (pr-str (db/pull (db/db connection) '[*] (first added)))
                            message)
             "The stored fact is this failure, in the branch the failure carried."))))))
