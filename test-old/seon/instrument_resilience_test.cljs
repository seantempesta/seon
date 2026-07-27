(ns seon.instrument-resilience-test
  "A stale function contract fails candidate construction before publication.

   The database-derived runtime projection validates every function form
   against the same immutable registry as schema forms. A dangling reference
   cannot become a partially instrumented boot generation or mutate Malli's
   process-global function-schema registry."
  (:require
    [cljs.test :refer [deftest is]]
    [malli.core :as m]
    [seon.schema :as schema]))

(def ^:private bad-sym 'seon.render.value/sample)

(def ^:private bad-spec
  [:=> [:cat :int] :totally/nonexistent-schema-xyz])

(deftest unresolvable-contract-rejects-the-complete-candidate
  (let [global-functions-before (m/function-schemas :cljs)]
    (is (thrown? :default
                 (schema/build-projection {} {bad-sym bad-spec})))
    (is (= global-functions-before (m/function-schemas :cljs))
        "pure candidate validation leaves Malli global function data unchanged")))
