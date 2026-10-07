(ns seon.task-queue-test
  "The open queue derives each task's closure from the task it already pulled:
  the same answer as `seon.task/resolution` by handle, without a second pull."
  (:require [clojure.test :refer [deftest is]]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.attribution-test :refer [triaged-task!]]
            [seon.fault :as fault]
            [seon.problems :as problems]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- misuse
  "Hands an armed contract a map where it declares a vector: an input violation this function produced."
  []
  (problems/open-faults {:seon.error/at (java.util.Date.)} {}))

(defn- refused
  "The armed refusal `f` throws."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo refusal refusal)))

(deftest the-queue-is-the-tasks-without-a-resolution
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "task-queue" {})
     (cluster/seed-root-agent! connection "task-queue" cluster/boot-process-identity)
     (let [environment (env/environment {:seon.boot/cluster-name "task-queue"
                                         :seon.db/connection connection
                                         :seon.sci.admit/caps (config/result-caps (config/effective (db/db connection) "task-queue"))})
           receipt (fault/fault! environment (refused misuse)
                                 {:seon.error/layer ::boundary :seon.error/operation `boundary
                                  :seon.db.process/id "task-queue-test"})
           [handle] (triaged-task! connection (:seon.error/signature receipt) `misuse)
           database (db/db connection)
           pulled (db/pull database task/resolution-selector [:seon.task/id handle])]
       (is (string? handle) "the task filed for the fault exists")
       (is (= (task/resolution database handle) (task/task-resolution database pulled))
           "resolving the pulled task is resolving it by handle")
       (is (= [handle] (mapv :seon.task/id (my.task/open database)))
           "an unresolved task is the whole queue")))))
