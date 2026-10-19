(ns seon.schema.edn-test
  "Sealed acceptance for the schema-EDN loader and the one admission
  gate (B2 wave).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing seon.schema.edn (and wiring `register!`
  through the one gate) ONLY — schemas and tests are byte-sealed.
  The valid fixture is a classpath resource. Negative EDN is written beneath
  `tmp/` during each test so malformed inputs never enter publication."
  (:require [seon.schema.internal] [malli.core] [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; The loader
;;; ---------------------------------------------------------------------------

(deftest config-dials-are-declared-independent-of-their-names
  (let [forms (schema.edn/packaged-forms)
        declared? (fn [[_ definition]]
                    (true? (:seon.config/dial
                            (malli.core/properties (seon.schema/structural-schema definition)))))
        declared (into #{} (comp (filter declared?) (map key)) forms)
        entries #(into #{} (map first)
                       (seon.schema.internal/entity-entries (schema/structural-schema (:seon.config/manifest %))))
        synthetic (schema.edn/derive-config-forms
                   {:seon.config.synthetic/not-a-dial :boolean
                    :example/declared [:boolean {:seon.config/dial true}]})]
    (is (seq declared))
    ;; A seon.config.* name is no dial by itself (:seon.config.eval/bounds is a
    ;; declared finding schema, eab18c279): the manifest is exactly the declared.
    (is (= declared (entries forms)))
    (is (= #{:example/declared} (entries synthetic)))))

(defn- resource-loader
  "A class loader answering each resource through `answer` (a function of the
  resource name returning a URL or nil): what `schema.edn/*resource-loader*`
  binds, the way a boot rehearsal binds a candidate archive's loader."
  [answer]
  (proxy [ClassLoader] [nil]
    (getResource [^String resource-name] (answer resource-name))))

(defn- with-temporary-resources
  [files f]
  (let [fixture-id (str (random-uuid))
        resource-root (str "schema-edn-test/" fixture-id)
        directory (io/file "tmp" "schema-edn-test" fixture-id)]
    (doseq [[relative-path content] files]
      (let [file (io/file directory relative-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)))
    (binding [schema.edn/*resource-loader*
              (resource-loader
               (fn [resource]
                 (if (or (= resource resource-root)
                         (str/starts-with? resource (str resource-root "/")))
                   (let [relative-path (subs resource (count resource-root))
                         relative-path (if (str/starts-with? relative-path "/")
                                         (subs relative-path 1)
                                         relative-path)
                         file (if (seq relative-path)
                                (io/file directory relative-path)
                                directory)]
                     (when (.exists file)
                       (.toURL (.toURI file))))
                   (io/resource resource))))]
      (f resource-root))))

(deftest schema-resources-ignore-the-thread-context-class-loader
  ;; Fault 7b2cf983e1a4 (2026-09-29): a boot rehearsal set its thread's context
  ;; loader, the core.async threads it spawned inherited it, and once the
  ;; candidate archive was gone every settlement transaction on them refused.
  (let [expected (schema.edn/packaged-forms)
        task (java.util.concurrent.FutureTask.
              ^java.util.concurrent.Callable
              #(try (schema.edn/packaged-forms)
                    (catch Exception failure (ex-data failure))))
        thread (Thread. task)]
    (.setContextClassLoader thread (resource-loader (constantly nil)))
    (.start thread)
    (is (= expected (.get task 5 java.util.concurrent.TimeUnit/SECONDS))
        "a thread whose context loader sees no resources reads the packaged population")))

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
        calls (atom [])
        population
        (binding [schema.edn/*resource-loader*
                  (resource-loader (fn [name]
                                     (swap! calls conj name)
                                     (io/resource name)))]
          ((deref #'schema.edn/resource-population) resource))
        forms (::schema.edn/forms population)
        loaded {::schema.edn/file (::schema.edn/file population)
                ::schema.edn/keys (count forms)}]
    (testing "one resource contributes all three keys"
      (is (= #{resource} (set @calls)))
      (is (= 3 (:seon.schema.edn/keys loaded))))
    (testing "a cross-section alias is a candidate like any other"
      (is (contains? forms :seon.schema.edn.fixture/label)))
    (testing "a loaded attribute validates values end to end"
      (let [projection (schema/declaration-projection forms)
            valid? (schema/projection-validator projection :seon.schema.edn.fixture/name)]
        (is (valid? "alpha"))
        (is (not (valid? "")))))))

(deftest every-packaged-schema-resource-reads-as-edn
  ;; THE CLASS: a resource that clojure.edn refuses (a duplicate map key,
  ;; 260fed262's second `:seon.render.walk/node`) cannot be adopted, so
  ;; default cannot boot at HEAD. Every packaged file reads, by name.
  (let [files (->> (file-seq (io/file (io/resource "seon/schemas")))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".edn")))]
    (is (seq files))
    (doseq [^java.io.File file files]
      (is (map? (try (edn/read-string (slurp file))
                     (catch RuntimeException failure (ex-message failure))))
          "must read as one EDN map"))))

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
        (is (some #(str/ends-with? % "/second.edn") files))))))

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
              (enumerate "empty-schema-directory" (.toExternalForm (.toURL (.toURI directory))))
              nil
              (catch clojure.lang.ExceptionInfo error
                error))]
        (is (= ::schema.edn/unreadable-file
               (::schema.edn/error (ex-data failure)))))
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
        (is (str/ends-with? (::schema.edn/file (ex-data failure)) "/wrong.namespace.edn")
            "the refusal names the file that holds the misplaced key")))))

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
        (is (= :bad?/key (::schema.edn/attribute (ex-data failure))))))))

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
              (let [answered (schema.edn/admit
                              {:seon.schema/forms (mutate-population forms mutation)})]
                ;; a declared refusal is answered as a value; a compilation
                ;; failure admit does not declare still resurfaces whole
                (if (:seon.error/at answered) answered test-support/committed))
              (catch clojure.lang.ExceptionInfo failure
                (ex-data failure)))]
        (and (= (set (keys forms))
                (into #{} (map :seon.schema/key) admitted))
             (= error (or (::schema.edn/error refusal)
                          (:seon.schema/error refusal)))
             (= attribute (or (::schema.edn/attribute refusal)
                              (:seon.schema/key refusal))))))
    :seed 202607280703)
   "schema population admission"))

(deftest an-unloaded-predicate-owner-registers-at-admission
  (is (vector?
       (schema.edn/admit
        {:seon.schema/forms
         {:seon.schema.edn.gate/late
          [:fn {:gen/schema :inst}
           'seon.schema.edn-test-fixture/late-instant?]}}))))

(deftest config-preparation-inspects-syntax-without-loading-predicates
  (test-support/with-database
   (fn [_connection]
     (let [forms (:seon.schema.projection/forms (schema/handed-projection))
           owner 'seon.schema.edn-test.no-classpath-owner
           definition [:and {:seon.config/dial true :seon.config/default 7}
                       :seon.schema.edn-test/missing-target
                       [:fn 'seon.schema.edn-test.no-classpath-owner/predicate?]]
           prepared (schema.edn/derive-config-forms (assoc forms ::syntax-dial definition))]
       (is (nil? (find-ns owner)))
       (is (some #(= ::syntax-dial (first %))
                 (drop 1 (:seon.config/effective prepared))))
       (is (= 7 (get (schema.edn/config-registration-defaults prepared) ::syntax-dial)))
       (is (nil? (find-ns owner)))
       (is (thrown? clojure.lang.ExceptionInfo
                    (schema/build-projection prepared))
           "Opaque syntax is not proof of complete admission")))))

(deftest an-unregistered-predicate-refusal-names-its-reload-owner
  (let [predicate 'seon.schema.edn-test/no-such-predicate?
        refused
        (schema.edn/admit
         {:seon.schema/forms
          {:seon.schema.edn.gate/missing
           [:fn {:gen/schema :inst} predicate]}})]
    (is (= ::schema.edn/unregistered-predicate (:seon.error/member refused))
        "the refusal is answered as a value naming its kind")
    (is (= predicate (::schema.edn/predicate refused)))
    (is (= 'seon.schema.edn-test
           (::schema.edn/predicate-owner refused)))
    (is (str/includes? (:seon.error/message refused)
                       "load or reload that namespace before schema admission"))))

(deftest register!-flows-through-the-same-gate
  (let [predicate 'seon.schema.edn-test/fixture-instant?
        projection
        (schema/build-projection
         (schema/registered-schemas)
         {}
         {})]
      (testing "the agent producer meets the same honesty bar — one gate,
                two producers"
        (let [delta (schema/begin-registration-delta projection)]
          (is (map? (test-support/refusal-data
                     #(schema/call-with-registration-delta
                       delta
                       (fn []
                         (schema/register!
                          :seon.schema.edn.gate/agent-dishonest
                          [:fn 'seon.schema.edn-test/fixture-instant?]))))))))
      (testing "an honest agent registration still lands"
        (let [delta (schema/begin-registration-delta projection)
              definition [:fn {:gen/schema :inst
                               :error/message "must be an instant"}
                          'seon.schema.edn-test/fixture-instant?]]
          (schema/call-with-registration-delta
           delta
           (fn []
             (schema/register!
              :seon.schema.edn.gate/agent-honest definition)))
          (is (= definition
                 (schema/registration-delta-form
                  delta :seon.schema.edn.gate/agent-honest)))))))

(deftest one-config-registration-derives-every-structural-contract
  (test-support/with-database
    (fn [connection]
      (let [scratch :seon.config.scratch/enabled
            delta (schema/begin-registration-delta
                   (schema/projection-from-database @connection))
            entries
            (fn [forms schema-key]
              (into {} (map (fn [[k properties child]] [k [k properties (malli.core/form child)]]))
                    (seon.schema.internal/entity-entries (schema/structural-schema (get forms schema-key)))))]
        (schema/call-with-registration-delta
         delta
         (fn []
           (schema/register!
            scratch
            [:boolean {:seon.config/dial true
                       :seon.config/display-label "Scratch enabled"
                       :seon.config/default false
                       :seon.config/per-agent true}])))
        (let [forms @(:seon.schema.delta/candidate-forms delta)]
          (testing "the public registration producer derives every contract"
            (is (contains? (entries forms :seon.config/manifest) scratch))
            (is (contains? (entries forms :seon.config/effective) scratch))
            (is (contains? (entries forms :seon.config/agent-overlay) scratch))
            (is (contains? (entries forms :seon.config/entity) scratch))
            (is (contains? (set (schema/canonical-database-attributes (seon.schema/build-projection forms))) scratch))
            (is (= [scratch
                    {:optional true}
                    [:or scratch [:= :seon.config/absent]]]
                   (get (entries forms :seon.config/manifest) scratch))
                "manifest derivation retains both optionality and explicit absence")
            (is (= [scratch {:optional true} scratch]
                   (get (entries forms :seon.config/agent-overlay) scratch))
                "agent absence inherits through one derived optional entry")
            (is (= false
                   (:seon.config/default
                    (malli.core/properties (seon.schema/structural-schema (get forms scratch)))))
                "the same registration retains its declared default")
            (is (not (contains? (config/default-decisions) scratch))
                "uncommitted scratch data never changes shipped decisions"))
          (testing "structural config attributes never become manifest entries"
            (is (not (contains? (entries forms :seon.config/manifest)
                                :seon.config/cluster)))
            (is (not (contains? (entries forms :seon.config/manifest)
                                :seon.config/applied-manifest-digest)))))
        (is (not (contains? (schema/registered-schemas) scratch))
            "the scratch declaration never mutates packaged facts")))))

(deftest per-agent-config-overlay-is-one-derived-optional-surface
  (let [forms (schema/registered-schemas)
        per-agent-identities
        (into #{}
              (keep (fn [[identity definition]]
                      (when (true? (:seon.config/per-agent
                                    (malli.core/properties (seon.schema/structural-schema definition))))
                        identity)))
              forms)
        overlay-entries
        (seon.schema.internal/entity-entries (malli.core/schema (schema/schema-definition :seon.config/agent-overlay) (:seon.schema.projection/compile-options (seon.schema/handed-projection))))
        overlay-identities (into #{} (map first) overlay-entries)
        ai-dial-identities
        (into #{}
              (filter #(str/starts-with? (namespace %)
                                         "seon.config.ai"))
              (map first
                   (seon.schema.internal/entity-entries (malli.core/schema (schema/schema-definition :seon.config/manifest) (:seon.schema.projection/compile-options (seon.schema/handed-projection))))))]
    (is (= per-agent-identities overlay-identities)
        "the overlay is derived from per-agent registrations without a list")
    (is (every? per-agent-identities ai-dial-identities)
        "every registered AI dial is uniformly per-agent overridable")
    (is (every? (fn [[_ properties _]]
                  (= {:optional true} properties))
                overlay-entries)
        "agent absence means inheritance for every override")))

(deftest staged-alias-admission-resolves-its-candidate-reference-first
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database (seon.db/db connection))
           admit @#'schema.edn/admit-changed-identities
           forms (assoc (:seon.schema.projection/forms projection)
                        ::staged-alias ::staged-base
                        ::staged-base [:string {:min 1}])
           admitted (admit projection forms [::staged-alias]
                           {:seon.schema.admission/source :agent})
           options (:seon.schema.projection/compile-options admitted)]
       (is (= ::staged-base
              (get-in admitted [:seon.schema.projection/forms ::staged-alias])))
       (is (malli.core/validate ::staged-alias "x" options))
       (is (not (malli.core/validate ::staged-alias "" options)))))))
