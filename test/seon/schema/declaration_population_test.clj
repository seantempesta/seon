(ns seon.schema.declaration-population-test
  "The class regression for per-item declaration resolution.

  With no projection, projection state, or candidate overlay supplied,
  resolving the declaration population re-reads and re-merges every schema
  resource on disk. An operation that asks a per-item question —
  `schema/schema-definition` per config key, per print option, or
  `schema/identity-attr?` per registry key — therefore performs one complete
  resource population PER ITEM. Measured 2026-08-07 before the repair:
  `seon.reconcile`'s identity scan cost 21-26 seconds and 286,672 resource
  reads; `seon.config`'s registration defaults cost 1,003 ms and 12,464.

  The class is dead when an operation performs ONE resource population,
  whatever the item count. These tests count reads at the one read seam and
  assert exactly that, so a future caller that reintroduces the per-item shape
  fails here rather than in a wedged suite.

  Issue: docs/seon/issues/packaged-forms-rereads-every-schema-resource-per-call.md"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn- resource-reads
  "Schema resource reads performed while calling `thunk`."
  [thunk]
  (let [reads (atom 0)
        read-one @#'schema.edn/read-schema-resource]
    (with-redefs [schema.edn/read-schema-resource
                  (fn [resource] (swap! reads inc) (read-one resource))]
      (thunk))
    @reads))

(defn- one-population-reads
  "Reads performed by exactly one unbound declaration resolution."
  []
  (resource-reads schema/declaration-population))

(deftest an-operation-resolves-the-declaration-population-once
  (let [one (one-population-reads)]
    (testing "one unbound resolution reads every schema resource"
      (is (pos? one)
          "the fallback must actually read resources, or this test is vacuous"))
    (doseq [[operation thunk]
            [["seon.config/default-decisions" config/default-decisions]
             ["seon.config/default-population" config/default-population]
             ["seon.print/default-options" print/default-options]
             ["seon.reconcile identity-attributes"
              @#'reconcile/identity-attributes]]]
      (testing operation
        (is (= one (resource-reads thunk))
            (str operation
                 " must perform ONE declaration resolution, not one per item"))))))

(deftest a-supplied-population-is-not-resolved-again
  (testing "every question answered from a population in hand reads nothing"
    (let [forms (schema/declaration-population)]
      (is (zero?
           (resource-reads
            (fn []
              (run! (fn [attribute] (schema/identity-attr? forms attribute))
                    (take 200 (keys forms))))))
          "the population-taking arities must not re-resolve"))))

(defn- fallback-warning
  "Whatever the classpath fallback wrote to stderr while calling `thunk`."
  [thunk]
  (let [captured (java.io.StringWriter.)]
    (binding [*err* captured]
      (thunk))
    (str captured)))

(deftest the-classpath-fallback-is-never-silent
  (testing "resolution with no population in hand names its caller loudly"
    (let [warning (fallback-warning schema/declaration-population)]
      (is (str/includes? warning "DECLARATION POPULATION FALLBACK")
          "the fallback must announce itself — the 2026-08-07 incident logged
          nothing and was found only by thread dump")
      (is (str/includes? warning "seon.schema.declaration-population-test")
          "the warning must name the calling function, not the callee")))
  (testing "a population in hand says nothing"
    (let [forms (schema/declaration-population)]
      (is (= ""
             (fallback-warning
              (fn []
                (run! (fn [attribute] (schema/identity-attr? forms attribute))
                      (take 200 (keys forms))))))
          "the threaded path reaches no fallback, so it must not warn"))))
