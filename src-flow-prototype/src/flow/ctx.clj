(ns flow.ctx
  "One base ctx per process (sci/init), one sci/fork per eval.

   INVARIANT (enforced by a negative test in flow.demo): base vars hold only
   functions and immutable values. A fork isolates new defs; it does NOT
   isolate mutation of an existing shared var."
  (:require [datahike.api :as d]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]))

(def ^:dynamic *db*
  "The step's basis: the db-after of the previous step's transaction report.
   Bound on the :compute thread, never process-global."
  nil)

(def base
  (delay
    (sci/init
     {:namespaces
      {'clojure.core interrupt/clojure-core
       'db {'q (fn [query & args] (apply d/q query *db* args))
            'pull (fn [pattern eid] (d/pull *db* pattern eid))
            'basis (fn [] (:max-tx *db*))}
       ;; one host call that blocks -- the safepoint-free window
       'host {'block (fn [ms] (Thread/sleep (long ms)) :done)}}})))

(defn fork
  "2.1us / 539 bytes: a deref and one atom allocation (sci/core.cljc:318)."
  [interrupt-fn]
  (assoc (sci/fork @base) :interrupt-fn interrupt-fn))
