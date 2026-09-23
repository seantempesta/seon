(ns seon.cluster.branch-release-retention-test
  "A released branch connection's node cache is collectable: no Seon cache keeps it."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support])
  (:import (java.lang.ref WeakReference)
           (org.replikativ.persistent_sorted_set PersistentSortedSet)))

(defn- released-cycle!
  "Acquire an isolated branch, read and evaluate through it, release it, and
  return a weak reference to the branch connection's node cache."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle] [:fn clojure.core/some?]]}
  [parent]
  (let [handle (agent/acquire-context!
                parent nil {:seon.agent/isolate? true
                            :seon.cluster.registry/from (:seon.source/commit-id parent)})
        connection (:seon.db/connection handle)]
    (try
      (is (pos? (db/q '[:find (count ?f) . :where [?f :seon.fn/sym]] @connection)))
      (let [evaluation (eval/evaluate
                        {:seon.cluster.eval/source "(+ 1 2)"
                         :seon.cluster.eval/ns [:seon.ns/name 'user]
                         :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :seon.sci.admit/caps (config/result-caps config/defaults)
                         :seon.sci.eval/time-limit-ms 5000
                         :seon.config/on-core-error :panic})]
        (is (= 3 (:seon.sci.admit/value evaluation)) (pr-str evaluation)))
      (WeakReference. (.-_storage ^PersistentSortedSet (:eavt @connection)))
      (finally (agent/release-context! handle)))))

(deftest ^{:seon.test/long "Three isolated acquisitions and releases on the member's store, then three full collections of the shared JVM's heap; measured on default in the landing note."
           :seon.test/long-ms 15000}
  released-branch-node-caches-are-collectable
  (let [parent (support/execution-handle nil)
        references (mapv (fn [_] (released-cycle! parent)) (range 3))]
    (dotimes [_ 3] (System/gc))
    (is (every? #(nil? (.get ^WeakReference %)) references)
        "a released branch's node cache is reachable from a retained value")
    (is (not-any? #(instance? datahike.connector.Connection (second %))
                  (keys @@#'eval/refusal-recording-cache))
        "the refusal-recording cache keys on connection identity data")
    (is (not-any? (fn [cell]
                    (and (realized? cell)
                         (some #(contains? @(::kernel/program-snapshot @cell) %)
                               [:seon.db/db :seon.sci.eval/loaded-database])))
                  (vals @@#'eval/base-context-cache))
        "a memoized base context holds no database value")
    (is (not-any? (fn [cell]
                    (and (realized? cell)
                         (some (fn [[_ bindings]]
                                 (some #(and (instance? sci.lang.Var %)
                                             (instance? datahike.db.DB (.getRawRoot ^sci.lang.Var %)))
                                       (vals bindings)))
                               (:namespaces @(:env @cell)))))
                  (vals @@#'eval/base-context-cache))
        "no interpreter Var root in a memoized base is a database value")))
