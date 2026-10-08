(ns seon.competency-test
  "Proofs of the seon.competency tasks; each runs as its member on a branch off the run's agent."
  {:seon.test/long "Red until an agent does the task: excluded from the bulk tier, run by identity."}
  (:require [clojure.test :refer [deftest is]]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- v
  "The value `code` answers in this member's world."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [code]
  (support/agent-value (kernel/world-ctx) code))

(deftest ^{:seon/tasks #{'seon.competency/area-is-contracted}} t1-area-is-contracted
  (is (= 6 (v "(comp.geo/area 2 3)")))
  (is (some? (:seon.error/type (v "(comp.geo/area -1 3)"))) "the armed contract refuses")
  (is (some? (v "(:malli/schema (meta #'comp.geo/area))"))))
