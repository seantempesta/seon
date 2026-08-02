(ns seon.schema.edn-test
  "Sealed acceptance for the schema-EDN loader and the one admission
  gate (B2 wave).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing seon.schema.edn (and wiring `register!`
  through the one gate) ONLY — schemas and tests are byte-sealed.
  Fixture EDN lives under test/seon/schema_edn_fixtures/ (on the :test
  classpath), one resource per scenario, so the production
  `seon/schema.edn` resource is never touched by a test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; The loader
;;; ---------------------------------------------------------------------------

(deftest production-schema-edn-is-a-resource-not-source
  (let [loaded (schema.edn/load! {})
        file (:seon.schema.edn/file loaded)]
    (is (str/includes? file "/resources/seon/schema.edn")
        "production schema EDN is the one named resource")))

(deftest one-resource-is-one-population
  (let [resource "seon/schema_edn_fixtures/valid.edn"
        resolve-resource io/resource
        calls (atom [])
        loaded
        (with-redefs [io/resource
                      (fn [name]
                        (swap! calls conj name)
                        (resolve-resource name))]
          (schema.edn/load! {:seon.schema.edn/resource resource}))]
    (testing "one lookup contributes all three keys"
      (is (= [resource] @calls))
      (is (= 3 (:seon.schema.edn/keys loaded))))
    (testing "a cross-section alias is a candidate like any other"
      (is (schema/registered? :seon.schema.edn.fixture/label)))
    (testing "a loaded attribute validates values end to end"
      (is (schema/valid-candidate-value?
           :seon.schema.edn.fixture/name "alpha"))
      (is (not (schema/valid-candidate-value?
                :seon.schema.edn.fixture/name ""))))))

(deftest duplicates-across-sections-refuse
  (let [data (test-support/refusal-data
              #(schema.edn/load!
                {:seon.schema.edn/resource
                 "seon/schema_edn_fixtures/duplicate.edn"}))]
    (is (map? data) "the duplicate refused")
    (is (= :seon.schema.edn.fixture/twice
           (:seon.schema.edn/attribute data))
        "the refusal names the colliding key")
    (is (str/ends-with? (:seon.schema.edn/file data) "/duplicate.edn")
        "the refusal names the one resource")))

(deftest unreadable-files-refuse-by-name
  (let [data (test-support/refusal-data
              #(schema.edn/load!
                {:seon.schema.edn/resource
                 "seon/schema_edn_fixtures/unreadable.edn"}))]
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

(deftest an-unregistered-predicate-refusal-names-its-reload-owner
  (let [predicate 'seon.schema.edn-test/no-such-predicate?
        failure
        (try
          (schema.edn/admit
           {:seon.schema/forms
            {:seon.schema.edn.gate/missing
             [:fn {:gen/schema :inst} predicate]}})
          nil
          (catch clojure.lang.ExceptionInfo cause
            cause))]
    (is (some? failure))
    (is (= predicate (::schema.edn/predicate (ex-data failure))))
    (is (= 'seon.schema.edn-test
           (::schema.edn/predicate-owner (ex-data failure))))
    (is (str/includes? (ex-message failure)
                       "load or reload that namespace before schema admission"))))

(deftest register!-flows-through-the-same-gate
  (let [state (schema/snapshot-state)]
    (try
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
        (is (schema/registered? :seon.schema.edn.gate/agent-honest)))
      (finally
        (schema/restore-state! state)))))

(deftest one-config-registration-derives-every-structural-contract
  (let [scratch :seon.config.scratch/enabled
        state (schema/snapshot-state)
        entries
        (fn [schema-key]
          (into {} (map (juxt first identity))
                (schema.form/map-entries
                 (schema/schema-definition schema-key))))]
    (try
      (schema/register!
       scratch
       [:boolean {:seon.config/default false
                  :seon.config/per-agent true}])
      (testing "the public registration producer derives every contract"
        (is (contains? (entries :seon.config/manifest) scratch))
        (is (contains? (entries :seon.config/effective) scratch))
        (is (contains? (entries :seon.config/agent-overlay) scratch))
        (is (contains? (entries :seon.config/entity) scratch))
        (is (contains? (set (schema/canonical-database-attributes)) scratch))
        (is (= [scratch
                {:optional true}
                [:or scratch [:= :seon.config/absent]]]
               (get (entries :seon.config/manifest) scratch))
            "manifest derivation retains both optionality and explicit absence")
        (is (= [scratch {:optional true} scratch]
               (get (entries :seon.config/agent-overlay) scratch))
            "agent absence inherits through one derived optional entry")
        (is (= false (get (config/default-decisions) scratch))
            "the same registration is defaults-checkable"))
      (testing "structural config attributes never become manifest entries"
        (is (not (contains? (entries :seon.config/manifest)
                            :seon.config/cluster)))
        (is (not (contains? (entries :seon.config/manifest)
                            :seon.config/applied-manifest-digest))))
      (finally
        (schema/restore-state! state)))
    (is (not (schema/registered? scratch))
        "the scratch registration is removed")))

(deftest per-agent-config-overlay-is-one-derived-optional-surface
  (let [forms (schema/registered-schemas)
        per-agent-identities
        (into #{}
              (keep (fn [[identity definition]]
                      (when (true? (:seon.config/per-agent
                                    (schema.form/attr-form-properties
                                     definition)))
                        identity)))
              forms)
        overlay-entries
        (schema.form/map-entries
         (schema/schema-definition :seon.config/agent-overlay))
        overlay-identities (into #{} (map first) overlay-entries)
        ai-dial-identities
        (into #{}
              (filter #(str/starts-with? (namespace %)
                                         "seon.config.ai"))
              (map first
                   (schema.form/map-entries
                    (schema/schema-definition :seon.config/manifest))))]
    (is (= per-agent-identities overlay-identities)
        "the overlay is derived from per-agent registrations without a list")
    (is (= ai-dial-identities per-agent-identities)
        "every registered AI dial is uniformly per-agent overridable")
    (is (every? (fn [[_ properties _]]
                  (= {:optional true} properties))
                overlay-entries)
        "agent absence means inheritance for every override")))
