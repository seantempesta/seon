(ns seon.db.pipeline-test
  "Generative pipeline tests: Malli schema -> derive Datahike schema -> generate
   entities -> transact -> pull -> validate roundtrip.

   The core utility `assert-pipeline-roundtrip!` takes a Malli :map schema and
   verifies that N generated entities survive the full pipeline through the
   datahike bridge (`seon.db.datahike.schema/malli-map->datahike-schema`),
   exercised transitively via `seon.db/transact!` / `seon.db/pull-by-name` against
   the per-test isolated datahike `:memory` flow set up by
   `seon.test-utils/with-test-db`.

   Design constraints (from schema-unification design.md):
   - No :any, no :some -- every field has a concrete type
   - No [:maybe X] on persisted schemas -- use {:optional true} X
   - All keys are namespaced keywords
   - Datahike schema derived from Malli, never hardcoded

   Datahike-specific notes:
   - Refs (`:seon.db/ref`) are stored as UUIDs, not lookup-refs (Decision 6).
     Generative ref tests use UUIDs; ref keys are excluded from the entity
     schema roundtrip and exercised by parallel manual tests.
   - Nested `:map` (component refs) are unsupported in phase 1 -- the bridge
     throws. There is no test for component-ref roundtrip here.
   - Pull results carry `:db/id` and the auto-stamped `:seon.db/namespace`
     (Decision 7) which the comparison helpers strip before equality."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.db :as db]
            [seon.db.datahike.schema :as dh-schema]
            [seon.schema :as schema]
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
  "Get the Malli type of a map entry's value schema, unwrapping :malli.core/val
   AND chasing through `:malli.core/schema` registered-schema references so we
   see the underlying leaf/ref/coll type. Registered schema refs are how the
   production entity schemas express `:seon.fn/input-spec` and similar — the
   raw entry type is `:malli.core/schema`, not `:seon.db/ref`.

   `:seon.db/ref` is identified by its registry keyword form, not by the
   underlying `:or` shape — chasing through the deref would return `:or`,
   which is not how callers (e.g. `find-ref-keys`, `strip-ref-keys`) want
   to recognize ref attrs."
  [entry-schema]
  (let [unwrapped (resolve-entry-child entry-schema)]
    (loop [s unwrapped]
      (if (= :malli.core/schema (m/type s))
        (if (= :seon.db/ref (m/form s))
          :seon.db/ref
          (recur (m/deref s)))
        (m/type s)))))

(defn- find-many-keys
  "Find all keys in a :map schema whose value type is :set or :vector.
   Both map to cardinality-many in Datahike, which returns vectors,
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

(defn- find-ref-keys
  "Find all keys whose value type is `:seon.db/ref`. These are cross-namespace
   refs stored as UUIDs in datahike; generative tests skip them because the
   generator would emit random non-UUID payloads."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))]
    (into #{}
          (comp (filter (fn [[_k entry-schema]]
                          (= :seon.db/ref (entry-schema-type entry-schema))))
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

(defn- strip-pull-fluff
  "Remove `:db/id` and the auto-stamped `:seon.db/namespace` from a pulled
   entity. The datahike conn-process installs `:seon.db/namespace` as an ident
   and `seon.db/transact!` stamps it on every entity map (Decision 7), so it
   shows up in pulls but is not part of the original Malli entity."
  [entity]
  (dissoc entity :db/id :seon.db/namespace))

(defn- strip-empty-colls
  "Remove keys with empty collections from entity. Empty sets/vectors produce
   no datoms in Datahike and thus are absent on pull."
  [entity]
  (into {}
        (remove (fn [[_k v]]
                  (and (coll? v) (not (map? v)) (empty? v))))
        entity))

(defn- coerce-pulled-entity
  "Apply known Datahike pull transformations to make pulled entity comparable.
   - Convert vectors to sets for :set-typed keys (Datahike returns vectors)
   - Strip :db/id and :seon.db/namespace"
  [pulled set-keys]
  (let [stripped (strip-pull-fluff pulled)]
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
                           "Every field must have a concrete Datahike-compatible type. Keys: "
                           (pr-str any-keys))
                      {:violation :any-type
                       :keys any-keys})))
    (when-not (all-keys-namespaced? malli-schema)
      (throw (ex-info "All keys in persisted schemas must be namespaced keywords."
                      {:violation :unnamespaced-keys})))))

;;; ---------------------------------------------------------------------------
;;; Bridge Derivation Helpers
;;; ---------------------------------------------------------------------------

(defn- dh-schema-index
  "Index the datahike schema vector by :db/ident for per-attr lookup."
  [dh-vec]
  (into {} (map (juxt :db/ident identity)) dh-vec))

;;; ---------------------------------------------------------------------------
;;; Core Pipeline Roundtrip
;;; ---------------------------------------------------------------------------

(defn- format-failure
  "Format a single attribute failure for error reporting."
  [attr expected actual dh-by-ident]
  {:attr attr
   :expected expected
   :actual actual
   :datahike-schema (pr-str (get dh-by-ident attr))})

(defn- compare-entities
  "Compare original and pulled entities, returning a list of failures.
   Accounts for known Datahike transformations:
   - Empty colls in original become absent in pulled (no datoms)
   - Cardinality-many values are deduplicated and unordered (compare as sets)
   - Optional keys absent in original are absent in pulled"
  [original pulled many-keys dh-by-ident]
  (let [original-clean (strip-empty-colls original)]
    (reduce-kv
     (fn [failures k expected]
       (let [actual (get pulled k ::missing)]
         (cond
           ;; Key missing from pulled
           (= actual ::missing)
           (conj failures (format-failure k expected ::missing dh-by-ident))

           ;; Cardinality-many: compare as sets (order not preserved, dedup)
           (contains? many-keys k)
           (if (= (set expected) (set actual))
             failures
             (conj failures (format-failure k expected actual dh-by-ident)))

           ;; Direct equality
           :else
           (if (= expected actual)
             failures
             (conj failures (format-failure k expected actual dh-by-ident))))))
     []
     original-clean)))

(defn- validate-pulled-with-malli
  "Validate a pulled entity against the Malli schema, accounting for Datahike
   pull behavior: cardinality-many keys with empty collections are absent in
   pull results (no datoms = no key), so we treat them as optional for validation."
  [malli-schema pulled many-keys set-keys]
  (if (empty? many-keys)
    (m/validate malli-schema pulled)
    (let [with-defaults (reduce (fn [acc k]
                                  (if (contains? acc k)
                                    acc
                                    (assoc acc k (if (contains? set-keys k) #{} []))))
                                pulled
                                many-keys)]
      (m/validate malli-schema with-defaults))))

(defn- roundtrip-one-entity!
  "Roundtrip a single generated entity through datahike via `seon.db`.
   Returns {:pass true} or {:pass false :failure {...}}."
  [db-name entity identity-key set-keys many-keys dh-by-ident malli-schema i]
  (let [id-type (get-in dh-by-ident [identity-key :db/valueType])
        id-val (if (= :db.type/string id-type)
                 (str "gen-" i)
                 (get entity identity-key))
        entity (-> entity
                   (assoc identity-key id-val)
                   strip-empty-colls)
        lookup-ref [identity-key id-val]]
    (db/transact! db-name [entity])
    (let [pulled-raw (db/pull-by-name db-name '[*] lookup-ref)]
      (if (or (nil? pulled-raw) (empty? (dissoc pulled-raw :db/id :seon.db/namespace)))
        {:pass false
         :failure {:entity-index i
                   :original entity
                   :pulled pulled-raw
                   :malli-valid? false
                   :attr-failures [{:attr :db/pull
                                    :expected "non-nil entity"
                                    :actual pulled-raw
                                    :datahike-schema "N/A"}]}}
        (let [pulled (coerce-pulled-entity pulled-raw set-keys)
              valid? (validate-pulled-with-malli malli-schema pulled many-keys set-keys)
              attr-failures (compare-entities entity pulled many-keys dh-by-ident)]
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
   2. Derive Datahike schema via the bridge (`malli-map->datahike-schema`)
   3. Stand up an isolated `:memory` datahike flow under `db-name`
   4. Generate entity from Malli schema
   5. Strip empty collections (Datahike ignores them)
   6. Transact via `seon.db/transact!`
   7. Pull entity back via `seon.db/pull-by-name`
   8. Coerce pulled entity (vector->set for :set keys, strip :db/id + :seon.db/namespace)
   9. Validate pulled entity against Malli schema
   10. Assert value equality (sets for cardinality-many, direct for scalars)

   Options:
     :num-samples  - number of entities to generate (default 20)
     :identity-key - which key is the identity attr (required)
     :db-name      - logical db-name to install the schema under (required)

   Returns {:pass-count N :fail-count 0 :failures []} on success.
   Each failure includes :entity-index, :original, :pulled,
   :malli-valid?, and :attr-failures for debugging."
  [malli-schema {:keys [num-samples identity-key db-name]
                 :or {num-samples 20}}]
  (assert identity-key ":identity-key option is required")
  (assert db-name ":db-name option is required")

  (validate-schema-constraints! malli-schema)

  (let [dh-vec (dh-schema/malli-map->datahike-schema malli-schema)
        dh-by-ident (dh-schema-index dh-vec)
        set-keys (find-set-keys malli-schema)
        many-keys (find-many-keys malli-schema)
        parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        results (atom {:pass-count 0 :fail-count 0 :failures []})]

    ;; Verify bridge derived an entry for each map key
    (doseq [[k _] (m/entries parsed)]
      (is (contains? dh-by-ident k)
          (str "Bridge failed to derive Datahike schema for " k)))

    (tu/with-test-db
      {::tu/namespaces [db-name]
       ::tu/schemas {db-name malli-schema}}
      (fn [_]
        (doseq [i (range num-samples)]
          (let [entity (mg/generate malli-schema)
                result (roundtrip-one-entity! db-name entity identity-key
                                              set-keys many-keys
                                              dh-by-ident parsed i)]
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

(defn- assert-tempid-roundtrip!
  "Generative roundtrip test for entity schemas that lack a :db/unique identity
   key. Datahike supports negative tempids, so the pattern is the same as
   datalevin: transact with a tempid, resolve via :tempids, pull, compare.

   Routes through `seon.db/transact!`, which auto-stamps `:seon.db/namespace`
   onto every entity map (Decision 7). The stamp is stripped before equality.

   The pre-flight checks and bridge-derivation assertions match
   `assert-pipeline-roundtrip!`."
  [malli-schema {:keys [num-samples db-name] :or {num-samples 20}}]
  (assert db-name ":db-name option is required")
  (validate-schema-constraints! malli-schema)
  (let [dh-vec (dh-schema/malli-map->datahike-schema malli-schema)
        dh-by-ident (dh-schema-index dh-vec)
        parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        results (atom {:pass-count 0 :fail-count 0 :failures []})]

    (doseq [[k _] (m/entries parsed)]
      (is (contains? dh-by-ident k)
          (str "Bridge failed to derive Datahike schema for " k)))

    (tu/with-test-db
      {::tu/namespaces [db-name]
       ::tu/schemas {db-name malli-schema}}
      (fn [_]
        (doseq [i (range num-samples)]
          (let [entity (mg/generate malli-schema)
                tempid (- -1 i)
                tx-result (db/transact! db-name [(assoc entity :db/id tempid)])
                eid (get (:tempids tx-result) tempid)
                pulled-raw (db/pull-by-name db-name '[*] eid)
                pulled (strip-pull-fluff pulled-raw)
                valid? (m/validate malli-schema pulled)
                match? (= entity pulled)]
            (if (and valid? match?)
              (swap! results update :pass-count inc)
              (do
                (swap! results update :fail-count inc)
                (swap! results update :failures conj
                       {:entity-index i
                        :original entity
                        :pulled pulled
                        :malli-valid? valid?
                        :match? match?})
                (is false
                    (str "Tempid roundtrip failed for entity " i ":\n"
                         (when-not valid?
                           (str "  Malli validation: "
                                (pr-str (m/explain malli-schema pulled)) "\n"))
                         (when-not match?
                           (str "  Value mismatch:\n"
                                "    original: " (pr-str entity) "\n"
                                "    pulled:   " (pr-str pulled) "\n"))))))))))
    @results))

;;; ---------------------------------------------------------------------------
;;; Local Schema Registration (for the synthetic per-test schemas below)
;;; ---------------------------------------------------------------------------
;;;
;;; The production-entity tests reuse schemas registered in their owning
;;; namespaces. The synthetic tests below register their attrs here so
;;; `seon.db/transact!`'s Malli attr validation accepts them. Registration is
;;; idempotent — repeat loads under `(user/reload)` are safe.

(schema/register! :leaf/id [:string {:seon.db/identity true}])
(schema/register! :leaf/str :string)
(schema/register! :leaf/int :int)
(schema/register! :leaf/double :double)
(schema/register! :leaf/bool :boolean)
(schema/register! :leaf/kw :keyword)
(schema/register! :leaf/sym :symbol)
(schema/register! :leaf/uuid :uuid)
(schema/register! :leaf/inst :inst)

(schema/register! :opt/id [:string {:seon.db/identity true}])
(schema/register! :opt/required :string)
(schema/register! :opt/maybe-str :string)
(schema/register! :opt/maybe-int :int)
(schema/register! :opt/maybe-kw :keyword)

(schema/register! :enumk/id [:string {:seon.db/identity true}])
(schema/register! :enumk/status [:enum :active :inactive :pending :archived])

(schema/register! :enums/id [:string {:seon.db/identity true}])
(schema/register! :enums/role [:enum "admin" "user" "guest" "moderator"])

(schema/register! :setk/id [:string {:seon.db/identity true}])
(schema/register! :setk/tags [:set :keyword])

(schema/register! :vecs/id [:string {:seon.db/identity true}])
(schema/register! :vecs/names [:vector :string])

(schema/register! :ref/id [:string {:seon.db/identity true}])
(schema/register! :ref/target :seon.db/ref)

(schema/register! :complex/id [:string {:seon.db/identity true}])
(schema/register! :complex/name :string)
(schema/register! :complex/count :int)
(schema/register! :complex/score :double)
(schema/register! :complex/active :boolean)
(schema/register! :complex/kind [:enum :alpha :beta :gamma])
(schema/register! :complex/uuid :uuid)
(schema/register! :complex/tags [:set :keyword])
(schema/register! :complex/note :string)
(schema/register! :complex/priority :int)

;; Attrs used by intra-DB ref roundtrip tests.
(schema/register! :owner/id [:string {:seon.db/identity true}])
(schema/register! :owner/name :string)
(schema/register! :item/id [:string {:seon.db/identity true}])
(schema/register! :item/owner :seon.db/ref)

;;; ---------------------------------------------------------------------------
;;; Tests: Simple Leaf Types
;;; ---------------------------------------------------------------------------

(deftest simple-leaf-types-pipeline-test
  (testing "all leaf types survive the pipeline generatively"
    (let [schema [:map
                  [:leaf/id [:string {:seon.db/identity true}]]
                  [:leaf/str :string]
                  [:leaf/int :int]
                  [:leaf/double :double]
                  [:leaf/bool :boolean]
                  [:leaf/kw :keyword]
                  [:leaf/sym :symbol]
                  [:leaf/uuid :uuid]
                  [:leaf/inst :inst]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :leaf/id :num-samples 20 :db-name :leaf})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Optional Keys
;;; ---------------------------------------------------------------------------

(deftest optional-keys-pipeline-test
  (testing "entities with optional keys roundtrip correctly"
    (let [schema [:map
                  [:opt/id [:string {:seon.db/identity true}]]
                  [:opt/required :string]
                  [:opt/maybe-str {:optional true} :string]
                  [:opt/maybe-int {:optional true} :int]
                  [:opt/maybe-kw {:optional true} :keyword]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :opt/id :num-samples 20 :db-name :opt})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Enums
;;; ---------------------------------------------------------------------------

(deftest keyword-enum-pipeline-test
  (testing "keyword enum roundtrips generatively"
    (let [schema [:map
                  [:enumk/id [:string {:seon.db/identity true}]]
                  [:enumk/status [:enum :active :inactive :pending :archived]]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :enumk/id :num-samples 20 :db-name :enumk})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest string-enum-pipeline-test
  (testing "string enum roundtrips generatively"
    (let [schema [:map
                  [:enums/id [:string {:seon.db/identity true}]]
                  [:enums/role [:enum "admin" "user" "guest" "moderator"]]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :enums/id :num-samples 20 :db-name :enums})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Cardinality-Many
;;; ---------------------------------------------------------------------------

(deftest set-of-keywords-pipeline-test
  (testing "[:set :keyword] roundtrips via cardinality-many"
    (let [schema [:map
                  [:setk/id [:string {:seon.db/identity true}]]
                  [:setk/tags [:set :keyword]]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :setk/id :num-samples 20 :db-name :setk})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest vector-of-strings-pipeline-test
  (testing "[:vector :string] roundtrips via cardinality-many (dedup, no order)"
    (let [schema [:map
                  [:vecs/id [:string {:seon.db/identity true}]]
                  [:vecs/names [:vector :string]]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :vecs/id :num-samples 20 :db-name :vecs})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Intra-DB Refs (`:db.type/ref`)
;;; ---------------------------------------------------------------------------
;;;
;;; `:seon.db/ref` maps to `:db.type/ref` (intra-DB ref). Values are pos-int
;;; eids, neg-int / string tempids, or `[unique-attr value]` lookup-refs.
;;; See `docs/prds/datahike-migration/ref-model-research.md`.
;;;
;;; The roundtrip pattern for ref attrs: create the target entity in the same
;;; db, then transact a referencing entity using either a lookup-ref tuple
;;; (against the target's `:db.unique/identity` attr) or a same-tx tempid.
;;; Pulling the ref back returns `{:db/id N}` — the allocated eid.

(deftest cross-namespace-ref-roundtrip-test
  (testing "intra-DB :seon.db/ref roundtrips via lookup-ref"
    (let [schema [:map
                  [:owner/id [:string {:seon.db/identity true}]]
                  [:owner/name :string]
                  [:item/id [:string {:seon.db/identity true}]]
                  [:item/owner :seon.db/ref]]]
      (tu/with-test-db
        {::tu/namespaces [:ref-roundtrip]
         ::tu/schemas {:ref-roundtrip schema}}
        (fn [_]
          (db/transact! :ref-roundtrip [{:owner/id "o1" :owner/name "Alice"}])
          (db/transact! :ref-roundtrip
                        [{:item/id "i1" :item/owner [:owner/id "o1"]}])
          (let [owner-eid (:db/id (db/pull-by-name :ref-roundtrip '[*]
                                                   [:owner/id "o1"]))
                pulled (db/pull-by-name :ref-roundtrip '[*] [:item/id "i1"])]
            (is (some? owner-eid))
            (is (= "i1" (:item/id pulled)))
            (is (= {:db/id owner-eid} (:item/owner pulled)))))))))

(deftest agent-run-ref-pipeline-test
  (testing "seon.agent.run/runtime ref roundtrips against a :seon.runtime entity"
    (let [schema [:map
                  [:seon.runtime/namespace :seon.runtime/namespace]
                  [:seon.runtime/status :keyword]
                  [:seon.agent.run/id [:string {:seon.db/identity true}]]
                  [:seon.agent.run/status :keyword]
                  [:seon.agent.run/runtime :seon.db/ref]]]
      (tu/with-test-db
        {::tu/namespaces [:agent-run-ref]
         ::tu/schemas {:agent-run-ref schema}}
        (fn [_]
          (db/transact! :agent-run-ref
                        [{:seon.runtime/namespace "my.ns"
                          :seon.runtime/status :running}])
          (db/transact! :agent-run-ref
                        [{:seon.agent.run/id "run-1"
                          :seon.agent.run/status :running
                          :seon.agent.run/runtime [:seon.runtime/namespace "my.ns"]}])
          (let [rt-eid (:db/id (db/pull-by-name :agent-run-ref '[*]
                                                [:seon.runtime/namespace "my.ns"]))
                pulled (db/pull-by-name :agent-run-ref '[*]
                                        [:seon.agent.run/id "run-1"])]
            (is (some? rt-eid))
            (is (= {:db/id rt-eid} (:seon.agent.run/runtime pulled)))))))))

(deftest ingest-fn-spec-ref-pipeline-test
  (testing ":seon.fn/input-spec and :seon.fn/output-spec roundtrip via [:seon.spec/key ...]"
    (tu/with-test-db
        {::tu/namespaces [:fn-spec-ref]
         ::tu/schemas {:fn-spec-ref [:map
                                     [:seon.spec/key :seon.spec/key]
                                     [:seon.spec/namespace :string]
                                     [:seon.spec/definition :string]
                                     [:seon.spec/base-type :keyword]
                                     [:seon.spec/updated-at :inst]
                                     [:seon.fn/qualified-name :seon.fn/qualified-name]
                                     [:seon.fn/namespace :string]
                                     [:seon.fn/name :string]
                                     [:seon.fn/private :boolean]
                                     [:seon.fn/input-spec :seon.db/ref]
                                     [:seon.fn/output-spec :seon.db/ref]]}}
        (fn [_]
          (let [in-key :my.ns/in
                out-key :my.ns/out]
            (db/transact! :fn-spec-ref
                          [{:seon.spec/key in-key
                            :seon.spec/namespace "my.ns"
                            :seon.spec/definition ":int"
                            :seon.spec/base-type :int
                            :seon.spec/updated-at (java.util.Date.)}
                           {:seon.spec/key out-key
                            :seon.spec/namespace "my.ns"
                            :seon.spec/definition ":string"
                            :seon.spec/base-type :string
                            :seon.spec/updated-at (java.util.Date.)}])
            (db/transact! :fn-spec-ref
                          [{:seon.fn/qualified-name "my.ns/do-thing"
                            :seon.fn/namespace "my.ns"
                            :seon.fn/name "do-thing"
                            :seon.fn/private false
                            :seon.fn/input-spec [:seon.spec/key in-key]
                            :seon.fn/output-spec [:seon.spec/key out-key]}])
            (let [in-eid (:db/id (db/pull-by-name :fn-spec-ref '[*]
                                                  [:seon.spec/key in-key]))
                  out-eid (:db/id (db/pull-by-name :fn-spec-ref '[*]
                                                   [:seon.spec/key out-key]))
                  pulled (db/pull-by-name :fn-spec-ref '[*]
                                          [:seon.fn/qualified-name "my.ns/do-thing"])]
              (is (= {:db/id in-eid} (:seon.fn/input-spec pulled)))
              (is (= {:db/id out-eid} (:seon.fn/output-spec pulled)))))))))

(deftest ingest-fn-shape-ref-pipeline-test
  (testing ":seon.fn/input-shape and :seon.fn/output-shape roundtrip via [:seon.shape/id ...]"
    (tu/with-test-db
      {::tu/namespaces [:fn-shape-ref]
       ::tu/schemas {:fn-shape-ref [:map
                                    [:seon.shape/id :seon.shape/id]
                                    [:seon.shape/namespace :string]
                                    [:seon.fn/qualified-name :seon.fn/qualified-name]
                                    [:seon.fn/namespace :string]
                                    [:seon.fn/name :string]
                                    [:seon.fn/private :boolean]
                                    [:seon.fn/input-shape :seon.db/ref]
                                    [:seon.fn/output-shape :seon.db/ref]]}}
      (fn [_]
        (db/transact! :fn-shape-ref
                      [{:seon.shape/id "shape-in" :seon.shape/namespace "my.ns"}
                       {:seon.shape/id "shape-out" :seon.shape/namespace "my.ns"}])
        (db/transact! :fn-shape-ref
                      [{:seon.fn/qualified-name "my.ns/do-thing"
                        :seon.fn/namespace "my.ns"
                        :seon.fn/name "do-thing"
                        :seon.fn/private false
                        :seon.fn/input-shape [:seon.shape/id "shape-in"]
                        :seon.fn/output-shape [:seon.shape/id "shape-out"]}])
        (let [in-eid (:db/id (db/pull-by-name :fn-shape-ref '[*]
                                              [:seon.shape/id "shape-in"]))
              out-eid (:db/id (db/pull-by-name :fn-shape-ref '[*]
                                               [:seon.shape/id "shape-out"]))
              pulled (db/pull-by-name :fn-shape-ref '[*]
                                      [:seon.fn/qualified-name "my.ns/do-thing"])]
          (is (= {:db/id in-eid} (:seon.fn/input-shape pulled)))
          (is (= {:db/id out-eid} (:seon.fn/output-shape pulled))))))))

(deftest ingest-call-entity-pipeline-test
  (testing ":seon.call/from-fn and :seon.call/to-fn roundtrip via [:seon.fn/qualified-name ...]"
    (tu/with-test-db
      {::tu/namespaces [:call-ref]
       ::tu/schemas {:call-ref [:map
                                [:seon.fn/qualified-name :seon.fn/qualified-name]
                                [:seon.fn/namespace :string]
                                [:seon.fn/name :string]
                                [:seon.fn/private :boolean]
                                [:seon.call/from-fn :seon.db/ref]
                                [:seon.call/to-fn :seon.db/ref]
                                [:seon.call/row {:optional true} :int]]}}
      (fn [_]
        (db/transact! :call-ref
                      [{:seon.fn/qualified-name "my.ns/caller"
                        :seon.fn/namespace "my.ns"
                        :seon.fn/name "caller"
                        :seon.fn/private false}
                       {:seon.fn/qualified-name "my.ns/callee"
                        :seon.fn/namespace "my.ns"
                        :seon.fn/name "callee"
                        :seon.fn/private false}])
        (let [from-eid (:db/id (db/pull-by-name :call-ref '[*]
                                                [:seon.fn/qualified-name "my.ns/caller"]))
              to-eid (:db/id (db/pull-by-name :call-ref '[*]
                                              [:seon.fn/qualified-name "my.ns/callee"]))
              {:keys [tempids]} (db/transact!
                                 :call-ref
                                 [{:db/id "call-1"
                                   :seon.call/from-fn [:seon.fn/qualified-name "my.ns/caller"]
                                   :seon.call/to-fn [:seon.fn/qualified-name "my.ns/callee"]}])
              call-eid (get tempids "call-1")
              pulled (db/pull-by-name :call-ref '[*] call-eid)]
          (is (some? call-eid))
          (is (= {:db/id from-eid} (:seon.call/from-fn pulled)))
          (is (= {:db/id to-eid} (:seon.call/to-fn pulled))))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Complex Entity (Mix of All Supported Types)
;;; ---------------------------------------------------------------------------
;;;
;;; The datalevin version included a nested `:map` component-ref; the datahike
;;; bridge rejects nested maps in phase 1, so this test exercises the same
;;; surface minus that one shape.

(deftest complex-entity-pipeline-test
  (testing "complex entity with mix of leaf types, enums, sets, optional"
    (let [schema [:map
                  [:complex/id [:string {:seon.db/identity true}]]
                  [:complex/name :string]
                  [:complex/count :int]
                  [:complex/score :double]
                  [:complex/active :boolean]
                  [:complex/kind [:enum :alpha :beta :gamma]]
                  [:complex/uuid :uuid]
                  [:complex/tags [:set :keyword]]
                  [:complex/note {:optional true} :string]
                  [:complex/priority {:optional true} :int]]
          result (assert-pipeline-roundtrip!
                   schema
                   {:identity-key :complex/id :num-samples 20 :db-name :complex})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Component Refs (NOT SUPPORTED — datahike phase 1)
;;; ---------------------------------------------------------------------------

(deftest component-ref-rejected-test
  (testing "nested :map (component ref) is rejected by the datahike bridge"
    (let [schema [:map
                  [:parent/id [:string {:seon.db/identity true}]]
                  [:parent/child [:map
                                  [:child/name :string]
                                  [:child/score :int]]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Nested map as component ref not supported"
                            (dh-schema/malli-map->datahike-schema schema))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Constraint Violations (Utility Catches Bad Schemas)
;;; ---------------------------------------------------------------------------

(deftest maybe-schema-rejected-test
  (testing "schema with [:maybe X] is rejected by the utility"
    (let [schema [:map
                  [:bad/id [:string {:seon.db/identity true}]]
                  [:bad/name [:maybe :string]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"maybe.*banned"
                            (assert-pipeline-roundtrip!
                              schema
                              {:identity-key :bad/id :db-name :bad}))))))

(deftest any-schema-rejected-test
  (testing "schema with :any is rejected by the utility"
    (let [schema [:map
                  [:bad/id [:string {:seon.db/identity true}]]
                  [:bad/val :any]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"any.*banned"
                            (assert-pipeline-roundtrip!
                              schema
                              {:identity-key :bad/id :db-name :bad}))))))

(deftest unnamespaced-keys-rejected-test
  (testing "schema with unnamespaced keys is rejected"
    (let [schema [:map
                  [:id [:string {:seon.db/identity true}]]
                  [:name :string]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"namespaced"
                            (assert-pipeline-roundtrip!
                              schema
                              {:identity-key :id :db-name :bad}))))))

(deftest identity-key-required-test
  (testing "assert-pipeline-roundtrip! requires :identity-key option"
    (let [schema [:map [:test/id :string]]]
      (is (thrown? AssertionError
                   (assert-pipeline-roundtrip! schema {:db-name :test}))))))

(deftest db-name-required-test
  (testing "assert-pipeline-roundtrip! requires :db-name option"
    (let [schema [:map [:test/id :string]]]
      (is (thrown? AssertionError
                   (assert-pipeline-roundtrip! schema {:identity-key :test/id}))))))

;;; ---------------------------------------------------------------------------
;;; Helper: drop ref keys from a registered entity schema for generative use.
;;; ---------------------------------------------------------------------------
;;;
;;; The production entity schemas below declare `:seon.db/ref` attrs whose
;;; generator would emit random non-UUID payloads. Stripping ref keys is the
;;; minimum fix (option A in the design doc). The decision to strip by **type**
;;; rather than by a hand-maintained name list addresses the smell flagged in
;;; cluster 2 of `docs/prds/datahike-migration/test-error-triage.md`: any
;;; future ref-typed attr added to a registered entity schema is automatically
;;; excluded from the generative roundtrip, without anyone touching this file.

(defn- strip-ref-keys
  "Return a `:map` Malli schema with every `:seon.db/ref` entry removed.

   Reconstructs from the raw form (not `m/entries`, which wraps each value in
   `:malli.core/val` — the bridge unwraps that at the top level but not in
   reconstructed sub-schemas, so a wrapped reconstruction would fail at
   bridge time)."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        ref-keys (find-ref-keys parsed)]
    (into [:map]
          (remove (fn [entry] (contains? ref-keys (first entry))))
          (rest (m/form parsed)))))

(defn- align-with-registered-schemas
  "Rewrite each map entry of a `:map` Malli schema so the child schema is the
   registered attr keyword (e.g. `:seon.runtime/session-id`) when one exists.

   Background: many production entity schemas declare attrs as plain leaf
   types (`:int`, `:string`) while the corresponding registered schema is
   stricter (`[:int {:min 0}]`, `[:string {:min 4 :max 6 :pattern ...}]`).
   `seon.db/transact!` validates each transacted value against the **registered**
   schema, so the generator must produce values valid for the registered one.
   Substituting the registered keyword as the child causes `mg/generate` to
   honor the stricter constraint. The bridge sees a `:malli.core/schema` child
   and recursively derives the correct datahike type from the leaf.

   The entry's optionality/property metadata is preserved verbatim."
  [malli-schema]
  (let [parsed (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        rewrite-entry
        (fn [entry]
          (let [k (first entry)
                [props child]
                (cond
                  (and (= 3 (count entry)) (map? (second entry)))
                  [(second entry) (nth entry 2)]

                  (= 2 (count entry))
                  [nil (second entry)]

                  :else
                  [nil (last entry)])]
            (if (schema/registered? k)
              (if props
                [k props k]
                [k k])
              (if props
                [k props child]
                [k child]))))]
    (into [:map] (map rewrite-entry) (rest (m/form parsed)))))

;;; ---------------------------------------------------------------------------
;;; Tests: Module Entity Schemas
;;; ---------------------------------------------------------------------------

(deftest ctx-entity-pipeline-test
  (testing "seon.ctx/ctx-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                  (align-with-registered-schemas
                    @(requiring-resolve 'seon.ctx/ctx-entity-schema))
                  {:identity-key :seon.ctx/instance-id
                   :num-samples 20
                   :db-name :seon.ctx})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest repl-form-entity-pipeline-test
  (testing "seon.repl/form-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                  (align-with-registered-schemas
                    @(requiring-resolve 'seon.repl/form-entity-schema))
                  {:identity-key :form/id
                   :num-samples 20
                   :db-name :seon.repl})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Trace Entity Schema (no unique identity key)
;;; ---------------------------------------------------------------------------

(deftest trace-entity-pipeline-test
  (testing "seon.flow.trace/entity-schema survives the full pipeline (tempid)"
    (let [result (assert-tempid-roundtrip!
                  (align-with-registered-schemas
                   @(requiring-resolve 'seon.flow.trace/entity-schema))
                  {:num-samples 20 :db-name :seon.flow.trace})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Runtime Entity Schemas
;;; ---------------------------------------------------------------------------

(deftest runtime-entity-pipeline-test
  (testing "seon.runtime/runtime-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.runtime/runtime-entity-schema))
                   {:identity-key :seon.runtime/namespace
                    :num-samples 20
                    :db-name :seon.runtime})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest agent-run-entity-pipeline-test
  (testing "seon.runtime/agent-run-entity-schema survives the full pipeline"
    ;; Ref keys (:seon.agent.run/runtime) are stripped — see strip-ref-keys docstring.
    (let [full @(requiring-resolve 'seon.runtime/agent-run-entity-schema)
          aligned (-> full strip-ref-keys align-with-registered-schemas)
          result (assert-pipeline-roundtrip!
                   aligned
                   {:identity-key :seon.agent.run/id
                    :num-samples 20
                    :db-name :seon.agent.run})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;; agent-run-ref-pipeline-test moved to the intra-DB refs section above.

(deftest flow-snap-entity-pipeline-test
  (testing "seon.runtime/flow-snap-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.runtime/flow-snap-entity-schema))
                   {:identity-key :seon.flow.snap/id
                    :num-samples 20
                    :db-name :seon.flow.snap})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Tx Entity Schema (no identity key)
;;; ---------------------------------------------------------------------------

(deftest tx-entity-pipeline-test
  (testing "seon.db.tx/entity-schema survives the full pipeline"
    (let [result (assert-tempid-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.db.tx/entity-schema))
                   {:num-samples 20 :db-name :seon.db.tx})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;;; ---------------------------------------------------------------------------
;;; Tests: Ingest Entity Schemas
;;; ---------------------------------------------------------------------------

(deftest ingest-ns-entity-pipeline-test
  (testing "seon.graph.ingest/ns-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.graph.ingest/ns-entity-schema))
                   {:identity-key :seon.ns/name
                    :num-samples 20
                    :db-name :seon.ns})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest ingest-fn-entity-pipeline-test
  (testing "seon.graph.ingest/fn-entity-schema survives the full pipeline"
    ;; The fn schema has FOUR :seon.db/ref keys (:input-spec, :output-spec,
    ;; :input-shape, :output-shape). `strip-ref-keys` removes all of them by
    ;; type, so this stays correct as new ref keys are added (the original
    ;; failure was a hand-maintained deny-list missing :input-shape /
    ;; :output-shape after the shape graph landed).
    (let [full @(requiring-resolve 'seon.graph.ingest/fn-entity-schema)
          aligned (-> full strip-ref-keys align-with-registered-schemas)
          result (assert-pipeline-roundtrip!
                   aligned
                   {:identity-key :seon.fn/qualified-name
                    :num-samples 20
                    :db-name :seon.fn})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

;; ingest-fn-spec-ref / ingest-fn-shape-ref / ingest-call-entity
;; pipeline tests moved to the intra-DB refs section above.

(deftest ingest-ns-dep-entity-pipeline-test
  (testing "seon.graph.ingest/ns-dep-entity-schema survives the full pipeline (tempid)"
    (let [result (assert-tempid-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.graph.ingest/ns-dep-entity-schema))
                   {:num-samples 20 :db-name :seon.ns.dep})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest ingest-spec-entity-pipeline-test
  (testing "seon.graph.ingest/spec-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.graph.ingest/spec-entity-schema))
                   {:identity-key :seon.spec/key
                    :num-samples 20
                    :db-name :seon.spec})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))

(deftest ingest-var-entity-pipeline-test
  (testing "seon.graph.ingest/var-entity-schema survives the full pipeline"
    (let [result (assert-pipeline-roundtrip!
                   (align-with-registered-schemas
                     @(requiring-resolve 'seon.graph.ingest/var-entity-schema))
                   {:identity-key :seon.var/qualified-name
                    :num-samples 20
                    :db-name :seon.var})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))
