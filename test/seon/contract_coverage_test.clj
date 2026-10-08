(ns seon.contract-coverage-test
  "One row per generated contract task (`seon.contracts/task`): the function
  carries a complete, compiled contract in the executing member's world. A row
  is tagged with its task and deleted by root when that task merges or retires."
  (:require [clojure.test :refer [deftest is]]
            [seon.contracts :as contracts]
            [seon.db :as db]))

(defn- contracted
  "Assert `function-symbol` has no contract gap in the executing member's world."
  {:malli/schema [:=> [:cat :seon.fn/sym] :boolean]}
  [function-symbol]
  (is (= [] (contracts/gaps (db/db) function-symbol))
      "the function carries a complete, compiled contract"))

(deftest a-contracted-function-has-no-gaps
  (contracted 'seon.contracts/gaps))

(deftest ^{:seon/tasks #{'seon.cluster.registry/contract-cluster-branch}} cluster-branch-is-contracted
  (contracted 'seon.cluster.registry/cluster-branch))

(deftest ^{:seon/tasks #{'seon.sci.eval/contract-context-projection}} context-projection-is-contracted
  (contracted 'seon.sci.eval/context-projection))

(deftest ^{:seon/tasks #{'seon.schema.edn/contract-resource-population}} resource-population-is-contracted
  (contracted 'seon.schema.edn/resource-population))

(deftest ^{:seon/tasks #{'seon.db/contract-edn-encoded?}} edn-encoded-is-contracted
  (contracted 'seon.db/edn-encoded?))

(deftest ^{:seon/tasks #{'seon.render.web/contract-query-params}} query-params-is-contracted
  (contracted 'seon.render.web/query-params))

(deftest ^{:seon/tasks #{'seon.fs/contract-attributes}} fs-attributes-is-contracted
  (contracted 'seon.fs/attributes))

(deftest ^{:seon/tasks #{'seon.render.transcript/contract-runtime-duration}} runtime-duration-is-contracted
  (contracted 'seon.render.transcript/runtime-duration))

(deftest ^{:seon/tasks #{'seon.schema/contract-function-arities-in}} function-arities-in-is-contracted
  (contracted 'seon.schema/function-arities-in))

(deftest ^{:seon/tasks #{'seon.ai/contract-output-observed?}} output-observed-is-contracted
  (contracted 'seon.ai/output-observed?))

(deftest ^{:seon/tasks #{'seon.cluster/contract-instruction-entity-changes}} instruction-entity-changes-is-contracted
  (contracted 'seon.cluster/instruction-entity-changes))
