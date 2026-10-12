(ns seon.schema.registry-retention-test
  "A projection's lifetime never extends the world of a callable armed under it."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest a-partial-retained-registry-keeps-the-dependency-defaults
  (let [forms {::value :int}
        supplied (schema/declaration-projection forms)
        projection (schema/build-projection forms {} {:seon.schema/projection supplied})
        options (:seon.schema.projection/compile-options projection)]
    (is (m/validate :int 1 options))
    (is (not (m/validate :int "one" options)))
    (is (m/validate ::value 1 options))))

(defn- wrap-and-forget!
  "Arm one interpreted callable closing over a fresh world, call it, and
  return only a weak reference to that world."
  [projection caps]
  (let [world (Object.)
        wrapped (instrument/wrap-interpreted
                 'my.agents.contract/value
                 '[:=> [:cat [:fn clojure.core/int?]] :int]
                 projection caps
                 (fn [value] (when world value)))]
    (is (= 1 (wrapped 1)))
    (java.lang.ref.WeakReference. world)))

(deftest a-projection-does-not-retain-callables-armed-under-it
  ;; Each context acquisition arms fresh callables that close over that
  ;; context and its database value. The long-lived projection they were
  ;; compiled under must not keep them reachable once their owner drops them.
  (let [projection (or (schema/current-projection)
                       (schema/build-projection (schema/snapshot)))
        caps (config/result-caps (test-support/effective-config))
        reference (wrap-and-forget! projection caps)]
    (loop [collections 0]
      (when (and (some? (.get reference)) (< collections 3))
        (System/gc)
        (recur (inc collections))))
    (is (nil? (.get reference))
        "the dropped callable's world is unreachable while its projection lives")
    (is (map? projection))))
