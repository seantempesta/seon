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
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
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

(def ^:private mutation-generator
  (gen/elements
   [{:mutation :unresolved
     :attribute :seon.schema.edn.gate/alias
     :error ::schema.edn/unresolved-reference}
    {:mutation :dishonest-generator
     :attribute :seon.schema.edn.gate/stamp
     :error ::schema.edn/dishonest-generator}
    {:mutation :unregistered-predicate
     :attribute :seon.schema.edn.gate/stamp
     :error ::schema.edn/unregistered-predicate}
    {:mutation :malformed
     :attribute :seon.schema.edn.gate/malformed
     :error :seon.schema/invalid-schema}]))

(defn- valid-population
  []
  {:seon.schema.edn.gate/base [:string {:min 1}]
   :seon.schema.edn.gate/alias :seon.schema.edn.gate/base
   :seon.schema.edn.gate/stamp
   [:fn {:gen/schema :inst}
    'seon.schema.edn-test/fixture-instant?]})

(defn- mutate-population
  [forms mutation]
  (case mutation
    :unresolved
    (assoc forms :seon.schema.edn.gate/alias
           :seon.schema.edn.gate/nowhere)

    :dishonest-generator
    (assoc forms :seon.schema.edn.gate/stamp
           [:fn 'seon.schema.edn-test/fixture-instant?])

    :unregistered-predicate
    (assoc forms :seon.schema.edn.gate/stamp
           [:fn {:gen/schema :inst}
            'seon.schema.edn-test/no-such-predicate?])

    :malformed
    (assoc forms :seon.schema.edn.gate/malformed [:not-a-schema])))

(deftest one-gate-admits-populations-or-names-the-generated-mutation
  (test-support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [{:keys [mutation attribute error]} mutation-generator]
      (let [forms (valid-population)
            admitted (schema.edn/admit {:seon.schema/forms forms})
            refusal
            (try
              (schema.edn/admit
               {:seon.schema/forms (mutate-population forms mutation)})
              test-support/committed
              (catch clojure.lang.ExceptionInfo failure
                (ex-data failure)))]
        (and (= (set (keys forms))
                (into #{} (map :seon.schema/key) admitted))
             (= error (or (::schema.edn/error refusal)
                          (:seon.schema/error refusal)))
             (= attribute (or (::schema.edn/attribute refusal)
                              (:seon.schema/key refusal)))
             (= :user-input (:seon.error/kind refusal)))))
    :seed 202607280703)
   "schema population admission"))

(deftest an-unloaded-predicate-owner-registers-at-admission
  (is (vector?
       (schema.edn/admit
        {:seon.schema/forms
         {:seon.schema.edn.gate/late
          [:fn {:gen/schema :inst}
           'seon.schema.edn-test-fixture/late-instant?]}}))))

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

(deftest one-config-registration-derives-every-structural-contract
  (let [scratch :seon.config.scratch/enabled
        forms
        {:seon.config/cluster
         [:string {:min 1 :seon.db/identity true}]
         :seon.config/applied-manifest-digest :string
         :seon.config/on-core-error
         [:enum {:seon.config/dial true} :record :panic]
         scratch
         [:boolean {:seon.config/default false}]
         :seon.config/apply-request
         [:map
          [:seon.config/manifest :seon.config/manifest]]}
        derived (schema.edn/derive-config-forms forms)
        entries
        (fn [schema-key]
          (into {} (map (juxt first identity))
                (schema.form/map-entries (get derived schema-key))))]
    (testing "the scratch registration is admitted everywhere with no roster"
      (is (contains? (entries :seon.config/manifest) scratch))
      (is (contains? (entries :seon.config/effective) scratch))
      (is (contains? (entries :seon.config/entity) scratch))
      (is (contains? (set (schema.form/database-attributes derived)) scratch)))
    (testing "structural config attributes never become manifest entries"
      (is (not (contains? (entries :seon.config/manifest)
                          :seon.config/cluster)))
      (is (not (contains? (entries :seon.config/manifest)
                          :seon.config/applied-manifest-digest))))
    (testing "one registration also carries its smart default"
      (is (= false
             (get (schema.edn/config-registration-defaults derived)
                  scratch))))))
