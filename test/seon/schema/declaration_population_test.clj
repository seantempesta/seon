(ns ^{:seon.test/platform
       "Moving part: one declaration population per operation, write side."}
    seon.schema.declaration-population-test
  "The class regression for per-item declaration resolution.

  An unhanded declaration operation now refuses loudly without reading a
  schema resource. An explicit packaged acquisition reads and merges every
  schema resource once. Before the repair, an operation asking a per-item
  question —
  `schema/schema-definition` per config key, per print option, or
  `schema/identity-attr?` per registry key — therefore performs one complete
  resource population PER ITEM. Measured 2026-08-07 before the repair:
  `seon.reconcile`'s identity scan cost 21-26 seconds and 286,672 resource
  reads; `seon.config`'s registration defaults cost 1,003 ms and 12,464.

  The class is dead when an operation performs ONE resource population,
  whatever the item count, and an unhanded operation refuses. These tests
  explicitly clear the runner's carrier for the refusal, count reads at the
  resource seam, and assert the refusal's caller so neither rail is vacuous.

  Issue: docs/seon/issues/packaged-forms-rereads-every-schema-resource-per-call.md"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.print :as print]
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

(defn- one-population-reads []
  (resource-reads schema.edn/packaged-forms))

(def ^:private carrier-symbols
  '[*candidate-forms-overlay* *projection* *projection-state* *packaged-forms*])

(defn- without-handed-projection
  "Call `thunk` after explicitly clearing every schema projection carrier."
  [thunk]
  (with-bindings
    (into {} (map (fn [sym] [(ns-resolve 'seon.schema sym) nil]))
          carrier-symbols)
    (thunk)))

(deftest an-operation-resolves-the-declaration-population-once
  (let [one (one-population-reads)]
    (testing "one explicit packaged resolution reads every schema resource"
      (is (pos? one)
          "the fallback must actually read resources, or this test is vacuous"))
    (doseq [[operation thunk]
            [["seon.config/default-decisions" config/default-decisions]
             ["seon.config/default-population" config/default-population]
             ["seon.print/default-options" print/default-options]]]
      (testing operation
        (is (= one (resource-reads thunk))
            (str operation
                 " must perform ONE declaration resolution, not one per item"))))))

(deftest a-supplied-population-is-not-resolved-again
  (testing "every question answered from a population in hand reads nothing"
    (let [forms (schema.edn/packaged-forms)]
      (is (zero?
           (resource-reads
            (fn []
              (run! (fn [attribute] (schema/identity-attr? forms attribute))
                    (take 200 (keys forms))))))
          "the population-taking arities must not re-resolve"))))

(deftest an-unhanded-declaration-projection-refuses-without-reading-resources
  (let [reads (atom 0)
        read-one @#'schema.edn/read-schema-resource
        failure
        (with-redefs [schema.edn/read-schema-resource
                      (fn [resource] (swap! reads inc) (read-one resource))]
          (without-handed-projection
           #(try
              (schema/declaration-projection)
              nil
              (catch clojure.lang.ExceptionInfo exception exception))))]
    (is (some? failure))
    (is (= :seon.schema/missing-projection
           (:seon.error/kind (ex-data failure))))
    (is (str/includes?
         (get-in (ex-data failure) [:seon.error/data :seon.schema/caller])
         "seon.schema.declaration-population-test"))
    (is (zero? @reads))))
