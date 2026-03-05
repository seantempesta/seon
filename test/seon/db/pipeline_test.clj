(ns seon.db.pipeline-test
  "Generative pipeline tests: Malli schema -> derive Datalevin schema -> generate
   entities -> transact -> pull -> validate roundtrip.

   The core utility `assert-pipeline-roundtrip!` takes a Malli :map schema and
   verifies that N generated entities survive the full pipeline. This is the
   contract test and agent feedback loop for schema development.

   Design constraints (from schema-unification design.md):
   - No :any, no :some -- every field has a concrete type
   - No [:maybe X] on persisted schemas -- use {:optional true} X
   - All keys are namespaced keywords
   - Datalevin schema derived from Malli, never hardcoded"
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.db.schema :as db-schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Schema Analysis Helpers
;;; ---------------------------------------------------------------------------

(defn- resolve-entry-child
  "Unwrap :malli.core/val wrapper to get the actual child schema from an entry."
  [entry-schema]
  (if (= :malli.core/val (m/type entry-schema))
    (first (m/children entry-schema))
    entry-schema))

(defn- entry-schema-type
  "Get the Malli type of a map entry's value schema, unwrapping :malli.core/val."
  [entry-schema]
  (m/type (resolve-entry-child entry-schema)))

(defn- find-many-keys
  "Find all keys in a :map schema whose value type is :set or :vector.
   Both map to cardinality-many in Datalevin, which returns vectors,
   deduplicates values, and does not preserve order."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (#{:set :vector} (entry-schema-type entry-schema))))
                (map first))
          (m/entries parsed))))

(defn- find-set-keys
  "Find keys whose value type is :set (subset of many-keys).
   These need vector->set coercion for Malli validation after pull."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (= :set (entry-schema-type entry-schema))))
                (map first))
          (m/entries parsed))))

(defn- find-component-keys
  "Find all keys in a :map schema whose value type is :map (component refs).
   These will have :db/id added by Datalevin pull."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (= :map (entry-schema-type entry-schema))))
                (map first))
          (m/entries parsed))))

(defn- find-maybe-keys
  "Find all keys whose value type is :maybe. These are banned in persisted schemas."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (= :maybe (entry-schema-type entry-schema))))
                (map first))
          (m/entries parsed))))

(defn- find-any-keys
  "Find all keys whose value type is :any. These are banned entirely."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (= :any (entry-schema-type entry-schema))))
                (map first))
          (m/entries parsed))))

(defn- all-keys-namespaced?
  "Check that all keys in a :map schema are namespaced keywords."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (every? (fn [[k _]] (and (keyword? k) (namespace k)))
            (m/entries parsed))))

;;; ---------------------------------------------------------------------------
;;; Entity Transformation Helpers
;;; ---------------------------------------------------------------------------

(defn- strip-db-id
  "Recursively remove :db/id from a pulled entity and nested component entities."
  [entity]
  (let [without (dissoc entity :db/id)]
    (into {}
          (map (fn [[k v]]
                 (if (map? v)
                   [k (strip-db-id v)]
                   [k v])))
          without)))

(defn- strip-empty-colls
  "Remove keys with empty collections from entity. Empty sets/vectors produce
   no datoms in Datalevin and thus are absent on pull."
  [entity]
  (into {}
        (remove (fn [[_k v]]
                  (and (coll? v) (not (map? v)) (empty? v))))
        entity))

(defn- coerce-pulled-entity
  "Apply known Datalevin pull transformations to make pulled entity comparable.
   - Convert vectors to sets for :set-typed keys (Datalevin returns vectors)
   - Recursively strip :db/id from component refs"
  [pulled set-keys]
  (let [stripped (strip-db-id pulled)]
    (reduce (fn [acc k]
              (if-let [v (get acc k)]
                (assoc acc k (set v))
                acc))
            stripped
            set-keys)))

;;; ---------------------------------------------------------------------------
;;; Schema Validation (Pre-flight Checks)
;;; ---------------------------------------------------------------------------

(defn- validate-schema-constraints!
  "Validate that a Malli :map schema meets pipeline constraints.
   Throws ex-info with details on any violations found."
  [malli-schema]
  (let [maybe-keys (find-maybe-keys malli-schema)
        any-keys (find-any-keys malli-schema)]
    (when (seq maybe-keys)
      (throw (ex-info (str "Schema contains [:maybe X] keys which are banned in persisted schemas. "
                           "Use {:optional true} X instead. Keys: " (pr-str maybe-keys))
                      {:violation :maybe-in-persisted
                       :keys maybe-keys})))
    (when (seq any-keys)
      (throw (ex-info (str "Schema contains :any keys which are banned. "
                           "Every field must have a concrete Datalevin-compatible type. Keys: "
                           (pr-str any-keys))
                      {:violation :any-type
                       :keys any-keys})))
    (when-not (all-keys-namespaced? malli-schema)
      (throw (ex-info "All keys in persisted schemas must be namespaced keywords."
                      {:violation :unnamespaced-keys})))))

;;; ---------------------------------------------------------------------------
;;; Core Pipeline Roundtrip
;;; ---------------------------------------------------------------------------

(defn- format-failure
  "Format a single attribute failure for error reporting."
  [attr expected actual dl-schema]
  {:attr attr
   :expected expected
   :actual actual
   :datalevin-schema (pr-str (get dl-schema attr))})

(defn- compare-entities
  "Compare original and pulled entities, returning a list of failures.
   Accounts for known Datalevin transformations:
   - Empty colls in original become absent in pulled (no datoms)
   - Cardinality-many values are deduplicated and unordered (compare as sets)
   - Optional keys absent in original are absent in pulled"
  [original pulled many-keys dl-schema]
  (let [original-clean (strip-empty-colls original)]
    (reduce-kv
     (fn [failures k expected]
       (let [actual (get pulled k ::missing)]
         (cond
           ;; Key missing from pulled
           (= actual ::missing)
           (conj failures (format-failure k expected ::missing dl-schema))

           ;; Cardinality-many: compare as sets (order not preserved, dedup)
           (contains? many-keys k)
           (if (= (set expected) (set actual))
             failures
             (conj failures (format-failure k expected actual dl-schema)))

           ;; Direct equality (covers maps, scalars)
           :else
           (if (= expected actual)
             failures
             (conj failures (format-failure k expected actual dl-schema))))))
     []
     original-clean)))

(defn- validate-pulled-with-malli
  "Validate a pulled entity against the Malli schema, accounting for Datalevin
   pull behavior: cardinality-many keys with empty collections are absent in
   pull results (no datoms = no key), so we treat them as optional for validation."
  [malli-schema pulled many-keys set-keys]
  (if (empty? many-keys)
    ;; No cardinality-many keys -- validate directly
    (m/validate malli-schema pulled)
    ;; Add absent many-keys as empty collections so Malli doesn't complain
    ;; about missing required keys when the original had an empty set/vector.
    ;; Use the correct empty collection type per the schema.
    (let [with-defaults (reduce (fn [acc k]
                                  (if (contains? acc k)
                                    acc
                                    (assoc acc k (if (contains? set-keys k) #{} []))))
                                pulled
                                many-keys)]
      (m/validate malli-schema with-defaults))))

(defn- roundtrip-one-entity!
  "Roundtrip a single generated entity through Datalevin.
   Returns {:pass true} or {:pass false :failure {...}}."
  [conn entity identity-key set-keys many-keys dl-schema malli-schema i]
  (let [id-val (str "gen-" i)
        entity (-> entity
                   (assoc identity-key id-val)
                   strip-empty-colls)
        lookup-ref [identity-key id-val]]
    ;; Transact
    (d/transact! conn [entity])
    ;; Pull back
    (let [pulled-raw (d/pull @conn '[*] lookup-ref)]
      (if (nil? pulled-raw)
        {:pass false
         :failure {:entity-index i
                   :original entity
                   :pulled nil
                   :malli-valid? false
                   :attr-failures [{:attr :db/pull
                                    :expected "non-nil entity"
                                    :actual nil
                                    :datalevin-schema "N/A"}]}}
        (let [pulled (coerce-pulled-entity pulled-raw set-keys)
              valid? (validate-pulled-with-malli malli-schema pulled many-keys set-keys)
              attr-failures (compare-entities entity pulled many-keys dl-schema)]
          (if (and valid? (empty? attr-failures))
            {:pass true}
            {:pass false
             :failure {:entity-index i
                       :original entity
                       :pulled pulled
                       :malli-valid? valid?
                       :attr-failures attr-failures}}))))))

(defn assert-pipeline-roundtrip!
  "Generatively test that a Malli :map schema survives the full pipeline.

   For N generated entities:
   1. Validate schema meets pipeline constraints (no :any, no [:maybe X], namespaced keys)
   2. Derive Datalevin schema via bridge (malli-map->datalevin-schema)
   3. Generate entity from Malli schema
   4. Strip empty collections (Datalevin ignores them)
   5. Transact to temp Datalevin DB
   6. Pull entity back
   7. Coerce pulled entity (vector->set for :set keys, strip :db/id)
   8. Validate pulled entity against Malli schema
   9. Assert value equality (sets for cardinality-many, direct for scalars)

   Options:
     :num-samples  - number of entities to generate (default 20)
     :identity-key - which key is the identity attr (required)

   Returns {:pass-count N :fail-count 0 :failures []} on success.
   Each failure includes :entity-index, :original, :pulled,
   :malli-valid?, and :attr-failures for debugging."
  [malli-schema {:keys [num-samples identity-key]
                 :or {num-samples 20}}]
  (assert identity-key ":identity-key option is required")

  ;; Pre-flight: validate schema constraints
  (validate-schema-constraints! malli-schema)

  ;; Derive Datalevin schema from Malli (the core proposition)
  (let [dl-schema (db-schema/malli-map->datalevin-schema malli-schema)
        set-keys (find-set-keys malli-schema)
        many-keys (find-many-keys malli-schema)
        component-keys (find-component-keys malli-schema)
        parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        results (atom {:pass-count 0 :fail-count 0 :failures []})]

    ;; Verify bridge derived something for each entry
    (doseq [[k _] (m/entries parsed)]
      (when-not (contains? component-keys k)
        (is (contains? dl-schema k)
            (str "Bridge failed to derive Datalevin schema for " k))))

    ;; Run roundtrips in a temp Datalevin connection
    (tu/with-temp-conn dl-schema
      (fn [conn]
        (doseq [i (range num-samples)]
          (let [entity (mg/generate malli-schema)
                result (roundtrip-one-entity! conn entity identity-key
                                              set-keys many-keys
                                              dl-schema parsed i)]
            (if (:pass result)
              (swap! results update :pass-count inc)
              (do
                (swap! results update :fail-count inc)
                (swap! results update :failures conj (:failure result))
                (let [f (:failure result)]
                  (is false
                      (str "Pipeline roundtrip failed for entity " (:entity-index f) ":\n"
                           (when-not (:malli-valid? f)
                             (str "  Malli validation: "
                                  (pr-str (m/explain malli-schema (:pulled f))) "\n"))
                           (when (seq (:attr-failures f))
                             (str "  Attribute mismatches:\n"
                                  (apply str
                                         (for [af (:attr-failures f)]
                                           (str "    " (:attr af) ": expected "
                                                (pr-str (:expected af))
                                                ", got " (pr-str (:actual af))
                                                "\n"))))))))))))))
    @results))

;;; ---------------------------------------------------------------------------
;;; Tests: Simple Leaf Types
;;; ---------------------------------------------------------------------------

(deftest simple-leaf-types-pipeline-test
  (testing "all leaf types survive the pipeline generatively"
    (let [schema [:map
                  [:leaf/id {:db/unique :db.unique/identity} :string]
                  [:leaf/str :string]
                  [:leaf/int :int]
                  [:leaf/double :double]
                  [:leaf/bool :boolean]
                  [:leaf/kw :keyword]
                  [:leaf/sym :symbol]
                  [:leaf/uuid :uuid]
                  [:leaf/inst :inst]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :leaf/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Optional Keys
;;; ---------------------------------------------------------------------------

(deftest optional-keys-pipeline-test
  (testing "entities with optional keys roundtrip correctly"
    (let [schema [:map
                  [:opt/id {:db/unique :db.unique/identity} :string]
                  [:opt/required :string]
                  [:opt/maybe-str {:optional true} :string]
                  [:opt/maybe-int {:optional true} :int]
                  [:opt/maybe-kw {:optional true} :keyword]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :opt/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Enums
;;; ---------------------------------------------------------------------------

(deftest keyword-enum-pipeline-test
  (testing "keyword enum roundtrips generatively"
    (let [schema [:map
                  [:enumk/id {:db/unique :db.unique/identity} :string]
                  [:enumk/status [:enum :active :inactive :pending :archived]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :enumk/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest string-enum-pipeline-test
  (testing "string enum roundtrips generatively"
    (let [schema [:map
                  [:enums/id {:db/unique :db.unique/identity} :string]
                  [:enums/role [:enum "admin" "user" "guest" "moderator"]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :enums/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Cardinality-Many
;;; ---------------------------------------------------------------------------

(deftest set-of-keywords-pipeline-test
  (testing "[:set :keyword] roundtrips via cardinality-many"
    (let [schema [:map
                  [:setk/id {:db/unique :db.unique/identity} :string]
                  [:setk/tags [:set :keyword]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :setk/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest vector-of-strings-pipeline-test
  (testing "[:vector :string] roundtrips via cardinality-many (dedup, no order)"
    (let [schema [:map
                  [:vecs/id {:db/unique :db.unique/identity} :string]
                  [:vecs/names [:vector :string]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :vecs/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Component Refs (Nested Maps)
;;; ---------------------------------------------------------------------------

(deftest component-ref-pipeline-test
  (testing "nested :map roundtrips as component entity"
    (let [schema [:map
                  [:parent/id {:db/unique :db.unique/identity} :string]
                  [:parent/name :string]
                  [:parent/child [:map
                                  [:child/name :string]
                                  [:child/score :int]]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :parent/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Non-Component Refs
;;; ---------------------------------------------------------------------------

(deftest non-component-ref-pipeline-test
  (testing "non-component ref with :db/valueType :db.type/ref"
    ;; Non-component refs return {:db/id N} on pull, not the full entity.
    ;; This test manually verifies that behavior since lookup refs require
    ;; the target entity to exist first -- not generatively testable.
    (let [dl-schema {:ref/id {:db/valueType :db.type/string
                              :db/unique :db.unique/identity}
                     :ref/target {:db/valueType :db.type/ref}}]
      (tu/with-temp-conn dl-schema
        (fn [conn]
          ;; Create target entity
          (d/transact! conn [{:ref/id "target-1"}])
          ;; Create source with lookup ref
          (d/transact! conn [{:ref/id "source-1"
                              :ref/target [:ref/id "target-1"]}])
          (let [result (d/pull @conn '[*] [:ref/id "source-1"])]
            (is (map? (:ref/target result))
                "non-component ref returns a map")
            (is (contains? (:ref/target result) :db/id)
                "non-component ref map contains :db/id")))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Complex Entity (Mix of All Types)
;;; ---------------------------------------------------------------------------

(deftest complex-entity-pipeline-test
  (testing "complex entity with mix of leaf types, enums, sets, optional, nested"
    (let [schema [:map
                  [:complex/id {:db/unique :db.unique/identity} :string]
                  [:complex/name :string]
                  [:complex/count :int]
                  [:complex/score :double]
                  [:complex/active :boolean]
                  [:complex/kind [:enum :alpha :beta :gamma]]
                  [:complex/uuid :uuid]
                  [:complex/tags [:set :keyword]]
                  [:complex/note {:optional true} :string]
                  [:complex/priority {:optional true} :int]
                  [:complex/child [:map
                                   [:detail/label :string]
                                   [:detail/value :int]]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :complex/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Constraint Violations (Utility Catches Bad Schemas)
;;; ---------------------------------------------------------------------------

(deftest maybe-schema-rejected-test
  (testing "schema with [:maybe X] is rejected by the utility"
    (let [schema [:map
                  [:bad/id {:db/unique :db.unique/identity} :string]
                  [:bad/name [:maybe :string]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"maybe.*banned"
                            (assert-pipeline-roundtrip! schema
                              {:identity-key :bad/id}))))))

(deftest any-schema-rejected-test
  (testing "schema with :any is rejected by the utility"
    (let [schema [:map
                  [:bad/id {:db/unique :db.unique/identity} :string]
                  [:bad/val :any]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"any.*banned"
                            (assert-pipeline-roundtrip! schema
                              {:identity-key :bad/id}))))))

(deftest unnamespaced-keys-rejected-test
  (testing "schema with unnamespaced keys is rejected"
    (let [schema [:map
                  [:id {:db/unique :db.unique/identity} :string]
                  [:name :string]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"namespaced"
                            (assert-pipeline-roundtrip! schema
                              {:identity-key :id}))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Identity Key Required
;;; ---------------------------------------------------------------------------

(deftest identity-key-required-test
  (testing "assert-pipeline-roundtrip! requires :identity-key option"
    (let [schema [:map [:test/id :string]]]
      (is (thrown? AssertionError
                   (assert-pipeline-roundtrip! schema {}))))))
