(ns seon.schema.edn-test
  "Sealed acceptance for the schema-EDN loader and the one admission
  gate (B2 wave).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing seon.schema.edn (and wiring `register!`
  through the one gate) ONLY — schemas and tests are byte-sealed.
  The valid fixture is a classpath resource. Negative EDN is written beneath
  `tmp/` during each test so malformed inputs never enter publication."
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

(defn- with-temporary-resources
  [files f]
  (let [fixture-id (str (random-uuid))
        resource-root (str "schema-edn-test/" fixture-id)
        directory (io/file "tmp" "schema-edn-test" fixture-id)
        resolve-resource io/resource]
    (doseq [[relative-path content] files]
      (let [file (io/file directory relative-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)))
    (with-redefs [io/resource
                  (fn [resource]
                    (if (or (= resource resource-root)
                            (str/starts-with? resource
                                              (str resource-root "/")))
                      (let [relative-path
                            (subs resource (count resource-root))
                            relative-path
                            (if (str/starts-with? relative-path "/")
                              (subs relative-path 1)
                              relative-path)
                            file (if (seq relative-path)
                                   (io/file directory relative-path)
                                   directory)]
                        (when (.exists file)
                          (.toURL (.toURI file))))
                      (resolve-resource resource)))]
      (f resource-root))))

(deftest production-schema-edn-is-a-resource-not-source
  (let [paths ((deref #'schema.edn/schema-resource-paths)
               schema.edn/default-resource)
        loaded (schema.edn/load! {})
        file (:seon.schema.edn/file loaded)]
    (is (= "seon/schemas" file)
        "production schema EDN is the one named resource directory")
    (is (= (count paths)
           (count (into #{}
                        (map (comp namespace key))
                        (::schema.edn/declared-forms
                         ((deref #'schema.edn/resource-population)
                          schema.edn/default-resource)))))
        "the flat directory has exactly one file per key namespace")))

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
    (testing "one resource contributes all three keys"
      (is (= #{resource} (set @calls)))
      (is (= 3 (:seon.schema.edn/keys loaded))))
    (testing "a cross-section alias is a candidate like any other"
      (is (schema/registered? :seon.schema.edn.fixture/label)))
    (testing "a loaded attribute validates values end to end"
      (is (schema/valid-candidate-value?
           :seon.schema.edn.fixture/name "alpha"))
      (is (not (schema/valid-candidate-value?
                :seon.schema.edn.fixture/name ""))))))

(deftest duplicates-across-sections-refuse
  (with-temporary-resources
    {"duplicate.edn"
     (str "{:seon.schema.edn.fixture/twice [:string {:min 1}]\n"
          " :seon.schema.edn.fixture/twice [:int {:min 0}]}\n")}
    (fn [root]
      (let [data (test-support/refusal-data
                  #(schema.edn/load!
                    {::schema.edn/resource (str root "/duplicate.edn")}))]
        (is (map? data) "the duplicate refused")
        (is (= :seon.schema.edn.fixture/twice
               (::schema.edn/attribute data))
            "the refusal names the colliding key")
        (is (str/ends-with? (::schema.edn/file data) "/duplicate.edn")
            "the refusal names the one resource")))))

(deftest duplicates-across-files-refuse-with-both-file-names
  (with-temporary-resources
    {"duplicate_files/first.edn"
     "{:seon.schema.edn.fixture/across-files :string}\n"
     "duplicate_files/second.edn"
     "{:seon.schema.edn.fixture/across-files [:string {:min 1}]}\n"}
    (fn [root]
      (let [failure
            (try
              (schema.edn/load!
               {::schema.edn/resource (str root "/duplicate_files")})
              nil
              (catch clojure.lang.ExceptionInfo error error))
            data (ex-data failure)
            files (::schema.edn/files data)]
        (is (= :seon.schema.edn.fixture/across-files
               (::schema.edn/attribute data)))
        (is (= 2 (count files)))
        (is (some #(str/ends-with? % "/first.edn") files))
        (is (some #(str/ends-with? % "/second.edn") files))
        (is (every? #(str/includes? (ex-message failure) %) files)
            "the loud collision message names both files")))))

(deftest declaration-digest-is-independent-of-resource-order
  (let [resource-paths-var #'schema.edn/schema-resource-paths
        resource-paths @resource-paths-var
        expected (schema.edn/declaration-digest)]
    (with-redefs-fn
      {resource-paths-var
       (fn [resource]
         (reverse (resource-paths resource)))}
      #(is (= expected (schema.edn/declaration-digest))
           "the ancestor digest hashes merged declarations, not file order"))))

(deftest an-empty-resource-directory-refuses-loudly
  (let [directory (io/file "tmp/schema-edn-test" (str (random-uuid)))
        enumerate @#'schema.edn/directory-resource-paths]
    (.mkdirs directory)
    (try
      (let [failure
            (try
              (enumerate "empty-schema-directory" (.toURL (.toURI directory)))
              nil
              (catch clojure.lang.ExceptionInfo error
                error))]
        (is (= ::schema.edn/unreadable-file
               (::schema.edn/error (ex-data failure))))
        (is (str/includes? (ex-message failure) "contains no EDN files")))
      (finally
        (.delete directory)))))

(deftest a-misplaced-declaration-refuses-with-its-required-filename
  (with-temporary-resources
    {"misplaced/wrong.namespace.edn" "{:actual.namespace/key :string}\n"}
    (fn [root]
      (let [failure
            (try
              (schema.edn/load!
               {::schema.edn/resource (str root "/misplaced")})
              nil
              (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::schema.edn/misplaced-attribute
               (::schema.edn/error (ex-data failure))))
        (is (= "actual.namespace.edn"
               (::schema.edn/expected-file (ex-data failure))))
        (is (str/includes? (ex-message failure) "wrong.namespace.edn"))))))

(deftest an-unsafe-key-namespace-refuses-before-placement
  (with-temporary-resources
    {"unsafe_namespace/bad.edn" "{:bad?/key :string}\n"}
    (fn [root]
      (let [failure
            (try
              (schema.edn/load!
               {::schema.edn/resource (str root "/unsafe_namespace")})
              nil
              (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::schema.edn/unsafe-namespace
               (::schema.edn/error (ex-data failure))))
        (is (= :bad?/key (::schema.edn/attribute (ex-data failure))))
        (is (str/includes? (ex-message failure) "verbatim filename"))))))

(deftest unreadable-files-refuse-by-name
  (with-temporary-resources
    {"unreadable.edn" "{:broken [unbalanced\n"}
    (fn [root]
      (let [data (test-support/refusal-data
                  #(schema.edn/load!
                    {::schema.edn/resource (str root "/unreadable.edn")}))]
        (is (map? data))
        (is (string? (::schema.edn/file data))
            "the refusal names the unreadable file")))))

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
