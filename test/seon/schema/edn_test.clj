(ns seon.schema.edn-test
  "Sealed acceptance for the schema-EDN loader and the one admission
  gate (B2 wave).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing seon.schema.edn (and wiring `register!`
  through the one gate) ONLY — schemas and tests are byte-sealed.
  Fixture EDN lives under test/seon/schema_edn_fixtures/ (on the :test
  classpath), one directory per scenario, so the production
  `seon/schema` resource directory is never touched by a test."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; The loader
;;; ---------------------------------------------------------------------------

(deftest a-directory-of-files-is-one-population
  (let [loaded (schema.edn/load!
                {:seon.schema.edn/resource-dir
                 "seon/schema_edn_fixtures/valid"})]
    (testing "both files contribute, three keys total"
      (is (= 2 (count (:seon.schema.edn/files loaded))))
      (is (= 3 (:seon.schema.edn/keys loaded))))
    (testing "a cross-file alias reference is a candidate like any other
              — file boundaries carry zero semantic meaning"
      (is (schema/registered? :seon.schema.edn.fixture/label)))
    (testing "a loaded attribute validates values end to end"
      (is (schema/valid-candidate-value?
           :seon.schema.edn.fixture/name "alpha"))
      (is (not (schema/valid-candidate-value?
                :seon.schema.edn.fixture/name ""))))))

(deftest duplicates-across-files-refuse-naming-both
  (let [data (test-support/refusal-data
              #(schema.edn/load!
                {:seon.schema.edn/resource-dir
                 "seon/schema_edn_fixtures/duplicate"}))]
    (is (map? data) "the duplicate refused")
    (is (= :seon.schema.edn.fixture/twice
           (:seon.schema.edn/attribute data))
        "the refusal names the colliding key")
    (is (= 2 (count (:seon.schema.edn/files data)))
        "the refusal names BOTH contributing files")))

(deftest unreadable-files-refuse-by-name
  (let [data (test-support/refusal-data
              #(schema.edn/load!
                {:seon.schema.edn/resource-dir
                 "seon/schema_edn_fixtures/unreadable"}))]
    (is (map? data))
    (is (string? (:seon.schema.edn/file data))
        "the refusal names the unreadable file")))

;;; ---------------------------------------------------------------------------
;;; The one gate
;;; ---------------------------------------------------------------------------

(defn- fixture-instant?
  "A core predicate registered by this suite for the honesty cases."
  [value]
  (inst? value))

(schema/register-core-predicate! 'seon.schema.edn-test/fixture-instant?
                                 fixture-instant?)

(deftest the-gate-admits-and-refuses-populations
  (testing "a resolvable, honest population admits"
    (is (vector?
         (schema.edn/admit
          {:seon.schema/forms
           {:seon.schema.edn.gate/base [:string {:min 1}]
            :seon.schema.edn.gate/alias :seon.schema.edn.gate/base
            :seon.schema.edn.gate/stamp
            [:fn {:gen/schema :inst}
             'seon.schema.edn-test/fixture-instant?]}}))))
  (testing "an unresolved reference refuses, naming the key"
    (let [data (test-support/refusal-data
                #(schema.edn/admit
                  {:seon.schema/forms
                   {:seon.schema.edn.gate/dangling
                    :seon.schema.edn.gate/nowhere}}))]
      (is (map? data))))
  (testing "a [:fn] whose UNLOADED owner namespace registers the
            predicate at load admits — the gate requiring-resolves the
            owner instead of demanding load-order (nothing requires
            seon.schema.edn-test-fixture; admission loads it)"
    (is (vector?
         (schema.edn/admit
          {:seon.schema/forms
           {:seon.schema.edn.gate/late
            [:fn {:gen/schema :inst}
             'seon.schema.edn-test-fixture/late-instant?]}}))))
  (testing "a [:fn] in a namespace that does not exist still refuses"
    (is (map? (test-support/refusal-data
               #(schema.edn/admit
                 {:seon.schema/forms
                  {:seon.schema.edn.gate/phantom
                   [:fn {:gen/schema :inst}
                    'seon.schema.no-such-namespace/predicate?]}})))))
  (testing "a [:fn] naming no registered core predicate refuses"
    (is (map? (test-support/refusal-data
               #(schema.edn/admit
                 {:seon.schema/forms
                  {:seon.schema.edn.gate/ghost
                   [:fn {:gen/schema :inst}
                    'seon.schema.edn-test/no-such-predicate?]}})))))
  (testing "a [:fn] with no honest generator refuses"
    (is (map? (test-support/refusal-data
               #(schema.edn/admit
                 {:seon.schema/forms
                  {:seon.schema.edn.gate/dishonest
                   [:fn 'seon.schema.edn-test/fixture-instant?]}}))))))

(deftest register!-flows-through-the-same-gate
  (testing "the agent producer meets the same honesty bar — one gate,
            two producers"
    (is (map? (test-support/refusal-data
               #(schema/register!
                 :seon.schema.edn.gate/agent-dishonest
                 [:fn 'seon.schema.edn-test/fixture-instant?])))))
  (testing "an honest agent registration still lands"
    (schema/register!
     :seon.schema.edn.gate/agent-honest
     [:fn {:gen/schema :inst}
      'seon.schema.edn-test/fixture-instant?])
    (is (schema/registered? :seon.schema.edn.gate/agent-honest))))
