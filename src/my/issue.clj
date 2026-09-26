(ns ^{:seon.ns/context-relevant? true} my.issue
  "Read issues, author problems, and add the tests that define completion."
  (:require [seon.db :as db]
            [seon.issue :as issue]))

(defn- home
  "The connection tasks live on, seen from `database`: root's home branch
  (root's row names it, as a message to root is delivered there) through this
  process's connection to it; nil when `database` is that branch, or when no
  root row or connection names another."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :seon.db/connection]]}
  [database]
  (let [branch (db/q '[:find ?branch . :where [?root :seon.agent/id "root"] [?root :seon.agent/branch ?branch]]
                     database)]
    (when (and (keyword? branch) (not= branch (get-in database [:config :branch])))
      (db/branch-connection database branch))))

(defn- task-connection
  "The connection an issue write goes through: the task home's, else `connection`."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.db/connection]}
  [connection]
  (or (home (db/db connection)) connection))

(defn status
  "Read an issue and its current test outcomes."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  :map]}
  [request]
  (issue/status (cond-> request
                  (home (:seon.db/db request)) (assoc :seon.db/db (db/db (home (:seon.db/db request)))))))

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
                  :map]}
  [request]
  (let [connection (task-connection (:seon.db/connection request))]
    (db/call-with-custody {:seon.db/connection connection}
                          #(issue/add! (assoc request :seon.db/connection connection)))))

(defn tests!
  "Add success tests to an existing issue without removing any."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/tests :seon.issue/tests]]]
                  :map]}
  [request]
  (let [connection (task-connection (:seon.db/connection request))]
    (db/call-with-custody {:seon.db/connection connection}
                          #(issue/tests! (assoc request :seon.db/connection connection)))))

