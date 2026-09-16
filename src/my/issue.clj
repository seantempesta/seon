(ns ^{:seon.ns/context-relevant? true} my.issue
  "Read issues, author problems, and add the tests that define completion."
  (:require [seon.issue :as issue]))

(defn status
  "Read an issue and its current test outcomes."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  :map]}
  [request]
  (issue/status request))

(defn add!
  "Author a problem with function refs and optional success tests."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/title :seon.issue/title]
                             [:seon.issue/problem :seon.issue/problem]
                             [:seon.issue/severity :seon.issue/severity]
                             [:seon.issue/functions {:optional true} :seon.issue/functions]
                             [:seon.issue/tests {:optional true} :seon.issue/tests]]]
                  :map]}
  [request]
  (issue/add! request))

(defn tests!
  "Add success tests to an existing issue without removing any."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/tests :seon.issue/tests]]]
                  :map]}
  [request]
  (issue/tests! request))

