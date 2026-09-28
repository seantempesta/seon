(ns ^{:seon.ns/context-relevant? true} my.issue
  "Read issues, author problems, and add the tests that define completion."
  (:require [seon.db :as db]
            [seon.issue :as issue]
            [seon.task :as task]))

(defn status
  "Read an issue and its current test outcomes."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  :map]}
  [request]
  (issue/status (cond-> request
                  (task/home (:seon.db/db request)) (assoc :seon.db/db (db/db (task/home (:seon.db/db request)))))))

(defn add!
  "Author a problem with function refs and optional success tests.

  The issue is written on the cluster, where tasks live, also from an agent
  on a branch; refs to declarations the cluster lacks refuse by name."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/title :seon.issue/title]
                             [:seon.issue/problem :seon.issue/problem]
                             [:seon.issue/severity :seon.issue/severity]
                             [:seon.issue/functions {:optional true} :seon.issue/functions]
                             [:seon.issue/tests {:optional true} :seon.issue/tests]]]
                  [:or :seon.issue/status-view :seon.error/value]]}
  [request]
  (let [connection (task/home-connection (:seon.db/connection request))]
    (db/call-with-custody {:seon.db/connection connection}
                          #(issue/add! (assoc request :seon.db/connection connection)))))

(defn tests!
  "Add success tests to an existing issue without removing any."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/tests :seon.issue/tests]]]
                  [:or :seon.issue/status-view :seon.error/value]]}
  [request]
  (let [connection (task/home-connection (:seon.db/connection request))]
    (db/call-with-custody {:seon.db/connection connection}
                          #(issue/tests! (assoc request :seon.db/connection connection)))))

