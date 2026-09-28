(ns seon.db-overhead-test
  (:require [clojure.test :refer [deftest is]]
            [seon.batch :as batch]
            [seon.db :as db]
            [seon.test-support :as support])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLongArray]))

(defn observe
  "Measure one read in its own call sink, retaining its actual value.
  Root can use this same probe for q, pull and my.task/open after adoption."
  {:malli/schema
   [:=> [:cat [:=> [:cat] :seon.schema/value]]
    [:map [:seon.db-overhead/value :seon.schema/value]
     [:seon.db-overhead/us :double]
     [:seon.db-overhead/calls :int]
     [:seon.db-overhead/by-function [:map-of :qualified-symbol :int]]]]}
  [read-value]
  (let [sink (ConcurrentHashMap.)
        started (System/nanoTime)
        value (binding [batch/*world* (assoc batch/*world* :seon.batch/sink sink)]
                (read-value))
        elapsed (/ (- (System/nanoTime) started) 1000.0)
        counts (reduce (fn [counts [[sym _digest] counter]]
                         (update counts sym (fnil + 0) (.get ^AtomicLongArray counter 0)))
                       (sorted-map) sink)]
    {:seon.db-overhead/value value
     :seon.db-overhead/us elapsed
     :seon.db-overhead/calls (reduce + 0 (vals counts))
     :seon.db-overhead/by-function counts}))

(deftest ^{:seon/tasks #{"d6e3b775f474"}} query-preparation-has-bounded-armed-work
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           query '[:find ?key . :where [_ :seon.schema/key ?key]
                   [(= ?key :seon.cluster/name)]]
           {:seon.db-overhead/keys [value calls by-function]}
           (observe #(db/q database query))]
       (is (= :seon.cluster/name value))
       (is (= 1 (get by-function 'seon.db/q)) "the query entrant is armed")
       ;; Armed scratch prototype: 43 before, 35 after on the same value.
       ;; Five calls of margin; the old repeated preparation exceeds it.
       (is (<= calls 40) "a scalar query stays within forty armed calls")))))
