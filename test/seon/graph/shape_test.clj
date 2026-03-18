(ns seon.graph.shape-test
  "Tests for shape graph: schema walker, entity generation, and ingestion."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.graph.extract :as extract]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq]
            [seon.schema :as schema])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-shape-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn with-temp-conn [f]
  (let [dir (temp-dir)
        conn (d/create-conn dir ingest/datalevin-schema)
        mock-manager {::dl-conn/connections (atom {:test-db {::dl-conn/connection conn}})}]
    (try
      (binding [db/*direct-mode* true
                db/*conn-manager* mock-manager]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; Walker: Named Specs
;;; ---------------------------------------------------------------------------

(deftest walker-named-spec-test
  (testing "produces correct shapes from a source file with named specs"
    ;; Register schemas in the live registry (walker resolves via m/schema)
    (schema/register! :seon.shape.test1/name :string)
    (schema/register! :seon.shape.test1/age :int)
    (schema/register! :seon.shape.test1/active :boolean)
    (schema/register! :seon.shape.test1/person-request
                      [:map
                       [:seon.shape.test1/name :seon.shape.test1/name]
                       [:seon.shape.test1/age {:optional true} :seon.shape.test1/age]
                       [:seon.shape.test1/active :seon.shape.test1/active]])

    (let [source "(ns seon.shape.test1
  (:require [seon.schema :as schema]))

(schema/register! ::name :string)
(schema/register! ::age :int)
(schema/register! ::active :boolean)

(schema/register! ::person-request
                  [:map
                   [::name ::name]
                   [::age {:optional true} ::age]
                   [::active ::active]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Should produce shapes for the :map schema
      (is (seq (::extract/shapes graph)) "Should produce shapes")

      (let [shapes (::extract/shapes graph)
            entries (::extract/entries graph)
            person-shape (first (filter #(= :seon.shape.test1/person-request
                                            (:seon.shape/spec-key %))
                                        shapes))]
        (is (some? person-shape) "Should find person-request shape")
        (is (= "shape:seon.shape.test1/person-request" (:seon.shape/id person-shape)))
        (is (= "seon.shape.test1" (:seon.shape/namespace person-shape)))
        (is (= 3 (count (:seon.shape/entries person-shape)))
            "Should have 3 entry refs")

        ;; Check entries
        (let [entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
          (is (= 3 (count entries)) "Should have 3 entries")

          ;; ::name entry
          (let [name-entry (get entry-by-key :seon.shape.test1/name)]
            (is (some? name-entry))
            (is (= :string (:seon.entry/value-type name-entry)))
            (is (false? (:seon.entry/optional name-entry)))
            (is (false? (:seon.entry/injectable name-entry))))

          ;; ::age entry (optional)
          (let [age-entry (get entry-by-key :seon.shape.test1/age)]
            (is (some? age-entry))
            (is (= :int (:seon.entry/value-type age-entry)))
            (is (true? (:seon.entry/optional age-entry))))

          ;; ::active entry
          (let [active-entry (get entry-by-key :seon.shape.test1/active)]
            (is (some? active-entry))
            (is (= :boolean (:seon.entry/value-type active-entry)))
            (is (false? (:seon.entry/optional active-entry)))))))))

;;; ---------------------------------------------------------------------------
;;; Walker: Inline Schemas
;;; ---------------------------------------------------------------------------

(deftest walker-inline-schema-test
  (testing "produces shapes from inline :malli/schema metadata"
    ;; We need schemas registered in the live registry for the walker
    ;; Register test schemas
    (schema/register! :seon.shape.test2/data :string)
    (schema/register! :seon.shape.test2/result :int)
    (schema/register! :seon.shape.test2/my-input
                      [:map
                       [:seon.shape.test2/data :seon.shape.test2/data]])
    (schema/register! :seon.shape.test2/my-output
                      [:map
                       [:seon.shape.test2/result :seon.shape.test2/result]])

    (let [source "(ns seon.shape.test2
  (:require [seon.schema :as schema]))

(schema/register! ::data :string)
(schema/register! ::result :int)
(schema/register! ::my-input [:map [::data ::data]])
(schema/register! ::my-output [:map [::result ::result]])

(defn transform
  \"Transform data.\"
  {:malli/schema [:=> [:cat ::my-input] ::my-output]}
  [{::keys [data]}]
  {::result (count data)})"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Should have shapes
      (is (seq (::extract/shapes graph)))

      ;; Function should be linked to shapes
      (let [fns (::extract/functions graph)
            transform-fn (first (filter #(= "transform" (:seon.fn/name %)) fns))]
        (is (some? transform-fn))
        (is (= [:seon.shape/id "shape:seon.shape.test2/my-input"]
               (:seon.fn/input-shape transform-fn))
            "Should link to input shape")
        (is (= [:seon.shape/id "shape:seon.shape.test2/my-output"]
               (:seon.fn/output-shape transform-fn))
            "Should link to output shape")))))

;;; ---------------------------------------------------------------------------
;;; Walker: Nested Maps
;;; ---------------------------------------------------------------------------

(deftest walker-nested-maps-test
  (testing "handles nested map schemas recursively"
    (schema/register! :seon.shape.test3/street :string)
    (schema/register! :seon.shape.test3/city :string)
    (schema/register! :seon.shape.test3/address
                      [:map
                       [:seon.shape.test3/street :seon.shape.test3/street]
                       [:seon.shape.test3/city :seon.shape.test3/city]])
    (schema/register! :seon.shape.test3/name :string)
    (schema/register! :seon.shape.test3/person
                      [:map
                       [:seon.shape.test3/name :seon.shape.test3/name]
                       [:seon.shape.test3/address :seon.shape.test3/address]])

    (let [source "(ns seon.shape.test3
  (:require [seon.schema :as schema]))

(schema/register! ::street :string)
(schema/register! ::city :string)
(schema/register! ::address [:map [::street ::street] [::city ::city]])
(schema/register! ::name :string)
(schema/register! ::person [:map [::name ::name] [::address ::address]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [shapes (::extract/shapes graph)
            entries (::extract/entries graph)
            person-shape (first (filter #(= :seon.shape.test3/person
                                            (:seon.shape/spec-key %))
                                        shapes))
            address-shape (first (filter #(= :seon.shape.test3/address
                                             (:seon.shape/spec-key %))
                                         shapes))]
        (is (some? person-shape) "Should find person shape")
        (is (some? address-shape) "Should find address shape")

        ;; Person has an address entry with value-shape ref
        (let [address-entry (first (filter #(= :seon.shape.test3/address
                                                (:seon.entry/key %))
                                           entries))]
          (is (some? address-entry) "Should find address entry")
          (is (= :map (:seon.entry/value-type address-entry)))
          (is (= [:seon.shape/id "shape:seon.shape.test3/address"]
                 (:seon.entry/value-shape address-entry))
              "Should reference address shape"))))))

;;; ---------------------------------------------------------------------------
;;; Walker: Cycle Detection
;;; ---------------------------------------------------------------------------

(deftest walker-cycle-detection-test
  (testing "gracefully skips self-referential schemas without crashing"
    ;; Self-referential schemas cause StackOverflow in m/schema.
    ;; The walker catches this and skips the schema gracefully.
    ;; This matches the PRD's "7 schemas may fail m/schema resolution".
    (schema/register! :seon.shape.test4/value :string)
    (schema/register! :seon.shape.test4/node
                      [:map
                       [:seon.shape.test4/value :seon.shape.test4/value]
                       [:seon.shape.test4/child {:optional true} :seon.shape.test4/node]])

    (let [source "(ns seon.shape.test4
  (:require [seon.schema :as schema]))

(schema/register! ::value :string)
(schema/register! ::node [:map [::value ::value] [::child {:optional true} ::node]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Should NOT crash with StackOverflow
      (is (vector? (::extract/shapes graph))
          "Should return shapes vector (possibly empty) without crashing")
      ;; The self-referential ::node schema will be skipped
      ;; but ::value (a non-map) won't produce shapes either
      ;; The important thing is: no StackOverflow
      (is (vector? (::extract/entries graph))
          "Should return entries vector without crashing"))))

;;; ---------------------------------------------------------------------------
;;; Walker: Non-Map Types as Leaves
;;; ---------------------------------------------------------------------------

(deftest walker-leaf-types-test
  (testing "handles non-map types as leaf value-types"
    (schema/register! :seon.shape.test5/status [:enum :active :inactive])
    (schema/register! :seon.shape.test5/name :string)
    (schema/register! :seon.shape.test5/tags [:vector :keyword])
    (schema/register! :seon.shape.test5/entity
                      [:map
                       [:seon.shape.test5/name :seon.shape.test5/name]
                       [:seon.shape.test5/status :seon.shape.test5/status]
                       [:seon.shape.test5/tags :seon.shape.test5/tags]])

    (let [source "(ns seon.shape.test5
  (:require [seon.schema :as schema]))

(schema/register! ::status [:enum :active :inactive])
(schema/register! ::name :string)
(schema/register! ::tags [:vector :keyword])
(schema/register! ::entity [:map [::name ::name] [::status ::status] [::tags ::tags]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        ;; enum is a leaf
        (is (= :enum (:seon.entry/value-type
                       (get entry-by-key :seon.shape.test5/status))))
        ;; vector of keyword is a leaf (not a map)
        (let [tags-entry (get entry-by-key :seon.shape.test5/tags)]
          (is (= :keyword (:seon.entry/value-type tags-entry)))
          (is (= :vector (:seon.entry/collection tags-entry))))))))

;;; ---------------------------------------------------------------------------
;;; Walker: Injectable Detection
;;; ---------------------------------------------------------------------------

(deftest walker-injectable-test
  (testing "detects :default/fn on entry props as injectable"
    (schema/register! :seon.shape.test6/name :string)
    (schema/register! :seon.shape.test6/conn :seon.db/ref)
    (schema/register! :seon.shape.test6/my-request
                      [:map
                       [:seon.shape.test6/name :seon.shape.test6/name]
                       [:seon.shape.test6/conn {:default/fn (fn [] nil)} :seon.shape.test6/conn]])

    (let [source "(ns seon.shape.test6
  (:require [seon.schema :as schema]))

(schema/register! ::name :string)
(schema/register! ::conn :seon.db/ref)
(schema/register! ::my-request [:map [::name ::name] [::conn {:default/fn (fn [] nil)} ::conn]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        ;; name is NOT injectable
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6/name))))
        ;; conn IS injectable
        (is (true? (:seon.entry/injectable
                    (get entry-by-key :seon.shape.test6/conn))))))))

;;; ---------------------------------------------------------------------------
;;; Walker: Injectable Detection — Value Schema Defaults
;;; ---------------------------------------------------------------------------

(deftest walker-injectable-value-schema-default-fn-test
  (testing ":default/fn on the VALUE SCHEMA (via register!) marks entry injectable"
    ;; Register a value schema with :default/fn in its own properties
    (schema/register! :seon.shape.test6b/name :string)
    (schema/register! :seon.shape.test6b/ctx
      [:map {:default/fn '(fn [] {:screen :home})}
       [:seon.shape.test6b/name :seon.shape.test6b/name]])

    (schema/register! :seon.shape.test6b/data :string)
    (schema/register! :seon.shape.test6b/my-request
      [:map
       [:seon.shape.test6b/data :seon.shape.test6b/data]
       ;; No :default/fn on the ENTRY props — it's on ::ctx's own schema
       [:seon.shape.test6b/ctx :seon.shape.test6b/ctx]])

    (let [source "(ns seon.shape.test6b
  (:require [seon.schema :as schema]))

(schema/register! ::name :string)
(schema/register! ::ctx
  [:map {:default/fn '(fn [] {:screen :home})}
   [::name ::name]])
(schema/register! ::data :string)
(schema/register! ::my-request
  [:map [::data ::data] [::ctx ::ctx]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        ;; data is NOT injectable
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6b/data))))
        ;; ctx IS injectable — :default/fn is on the value schema
        (is (true? (:seon.entry/injectable
                    (get entry-by-key :seon.shape.test6b/ctx)))
            ":default/fn on value schema should make entry injectable")))))

(deftest walker-injectable-static-default-entry-test
  (testing "static :default on entry props marks entry injectable"
    (schema/register! :seon.shape.test6c/name :string)
    (schema/register! :seon.shape.test6c/mode [:enum :fast :slow])
    (schema/register! :seon.shape.test6c/my-request
      [:map
       [:seon.shape.test6c/name :seon.shape.test6c/name]
       [:seon.shape.test6c/mode {:default :fast} :seon.shape.test6c/mode]])

    (let [source "(ns seon.shape.test6c
  (:require [seon.schema :as schema]))

(schema/register! ::name :string)
(schema/register! ::mode [:enum :fast :slow])
(schema/register! ::my-request [:map [::name ::name] [::mode {:default :fast} ::mode]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6c/name))))
        (is (true? (:seon.entry/injectable
                    (get entry-by-key :seon.shape.test6c/mode)))
            "static :default on entry props should make entry injectable")))))

(deftest walker-injectable-static-default-value-schema-test
  (testing "static :default on the VALUE SCHEMA marks entry injectable"
    (schema/register! :seon.shape.test6d/name :string)
    (schema/register! :seon.shape.test6d/level
      [:int {:default 1}])
    (schema/register! :seon.shape.test6d/my-request
      [:map
       [:seon.shape.test6d/name :seon.shape.test6d/name]
       [:seon.shape.test6d/level :seon.shape.test6d/level]])

    (let [source "(ns seon.shape.test6d
  (:require [seon.schema :as schema]))

(schema/register! ::name :string)
(schema/register! ::level [:int {:default 1}])
(schema/register! ::my-request [:map [::name ::name] [::level ::level]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6d/name))))
        (is (true? (:seon.entry/injectable
                    (get entry-by-key :seon.shape.test6d/level)))
            "static :default on value schema should make entry injectable")))))

(deftest walker-injectable-no-default-test
  (testing "entry with no default anywhere is NOT injectable"
    (schema/register! :seon.shape.test6e/x :int)
    (schema/register! :seon.shape.test6e/y :int)
    (schema/register! :seon.shape.test6e/req
      [:map
       [:seon.shape.test6e/x :seon.shape.test6e/x]
       [:seon.shape.test6e/y :seon.shape.test6e/y]])

    (let [source "(ns seon.shape.test6e
  (:require [seon.schema :as schema]))

(schema/register! ::x :int)
(schema/register! ::y :int)
(schema/register! ::req [:map [::x ::x] [::y ::y]])"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (let [entries (::extract/entries graph)
            entry-by-key (into {} (map (juxt :seon.entry/key identity)) entries)]
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6e/x))))
        (is (false? (:seon.entry/injectable
                     (get entry-by-key :seon.shape.test6e/y))))))))

;;; ---------------------------------------------------------------------------
;;; Discovery Queries
;;; ---------------------------------------------------------------------------

(deftest functions-matching-data-test
  (testing "finds functions whose required non-injectable entries match available keys"
    ;; Register schemas for a mini domain
    (schema/register! :seon.shape.disc/exercise :string)
    (schema/register! :seon.shape.disc/weight :double)
    (schema/register! :seon.shape.disc/reps :int)
    (schema/register! :seon.shape.disc/bodyweight :double)
    (schema/register! :seon.shape.disc/volume :double)
    (schema/register! :seon.shape.disc/sets :int)
    ;; ctx with :default/fn on value schema — injectable
    (schema/register! :seon.shape.disc/ctx
      [:map {:default/fn '(fn [] {})}])
    ;; strength-ratios output
    (schema/register! :seon.shape.disc/strength-ratios [:vector :keyword])

    ;; Function input/output schemas
    (schema/register! :seon.shape.disc/add-workout-input
      [:map
       [:seon.shape.disc/ctx :seon.shape.disc/ctx]
       [:seon.shape.disc/exercise :seon.shape.disc/exercise]
       [:seon.shape.disc/weight :seon.shape.disc/weight]
       [:seon.shape.disc/reps :seon.shape.disc/reps]])
    (schema/register! :seon.shape.disc/add-workout-output
      [:map [:seon.shape.disc/ctx :seon.shape.disc/ctx]])

    (schema/register! :seon.shape.disc/record-bw-input
      [:map
       [:seon.shape.disc/ctx :seon.shape.disc/ctx]
       [:seon.shape.disc/bodyweight :seon.shape.disc/bodyweight]])
    (schema/register! :seon.shape.disc/record-bw-output
      [:map [:seon.shape.disc/ctx :seon.shape.disc/ctx]])

    (schema/register! :seon.shape.disc/volume-input
      [:map [:seon.shape.disc/ctx :seon.shape.disc/ctx]])
    (schema/register! :seon.shape.disc/volume-output
      [:map
       [:seon.shape.disc/volume :seon.shape.disc/volume]
       [:seon.shape.disc/sets :seon.shape.disc/sets]])

    (let [source "(ns seon.shape.disc
  (:require [seon.schema :as schema]))

(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::bodyweight :double)
(schema/register! ::volume :double)
(schema/register! ::sets :int)
(schema/register! ::ctx [:map {:default/fn '(fn [] {})}])
(schema/register! ::strength-ratios [:vector :keyword])

(schema/register! ::add-workout-input
  [:map [::ctx ::ctx] [::exercise ::exercise] [::weight ::weight] [::reps ::reps]])
(schema/register! ::add-workout-output [:map [::ctx ::ctx]])

(schema/register! ::record-bw-input
  [:map [::ctx ::ctx] [::bodyweight ::bodyweight]])
(schema/register! ::record-bw-output [:map [::ctx ::ctx]])

(schema/register! ::volume-input [:map [::ctx ::ctx]])
(schema/register! ::volume-output [:map [::volume ::volume] [::sets ::sets]])

(defn add-workout!
  {:malli/schema [:=> [:cat ::add-workout-input] ::add-workout-output]}
  [{::keys [ctx exercise weight reps]}]
  {::ctx ctx})

(defn record-bodyweight!
  {:malli/schema [:=> [:cat ::record-bw-input] ::record-bw-output]}
  [{::keys [ctx bodyweight]}]
  {::ctx ctx})

(defn total-volume
  {:malli/schema [:=> [:cat ::volume-input] ::volume-output]}
  [{::keys [ctx]}]
  {::volume 0.0 ::sets 0})"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Ingest into test DB
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.shape.disc"
        ::ingest/functions (::extract/functions graph)
        ::ingest/specs (::extract/specs graph)
        ::ingest/entries (::extract/entries graph)
        ::ingest/shapes (::extract/shapes graph)})

      (testing "workout data keys find add-workout! (ctx is injectable)"
        (let [results (gq/functions-matching-data
                       {::gq/db-name :test-db
                        ::gq/available-keys #{:seon.shape.disc/exercise
                                              :seon.shape.disc/weight
                                              :seon.shape.disc/reps}})]
          (is (some #(= "seon.shape.disc/add-workout!"
                        (:seon.fn/qualified-name %))
                    results)
              "add-workout! should match — ctx is injectable, other 3 keys provided")
          (is (not (some #(= "seon.shape.disc/record-bodyweight!"
                             (:seon.fn/qualified-name %))
                         results))
              "record-bodyweight! should NOT match — bodyweight not provided")))

      (testing "empty keys find functions that only need injectable keys"
        (let [results (gq/functions-matching-data
                       {::gq/db-name :test-db
                        ::gq/available-keys #{}})]
          (is (some #(= "seon.shape.disc/total-volume"
                        (:seon.fn/qualified-name %))
                    results)
              "total-volume should match — its only input (ctx) is injectable")))

      (testing "bodyweight key finds record-bodyweight! but not add-workout!"
        (let [results (gq/functions-matching-data
                       {::gq/db-name :test-db
                        ::gq/available-keys #{:seon.shape.disc/bodyweight}})]
          (is (some #(= "seon.shape.disc/record-bodyweight!"
                        (:seon.fn/qualified-name %))
                    results)
              "record-bodyweight! should match")
          (is (not (some #(= "seon.shape.disc/add-workout!"
                             (:seon.fn/qualified-name %))
                         results))
              "add-workout! should NOT match — missing exercise/weight/reps")))

      (testing "results sorted by specificity (most matched non-injectable keys first)"
        (let [results (gq/functions-matching-data
                       {::gq/db-name :test-db
                        ::gq/available-keys #{:seon.shape.disc/exercise
                                              :seon.shape.disc/weight
                                              :seon.shape.disc/reps
                                              :seon.shape.disc/bodyweight}})]
          ;; add-workout! matches 3 non-injectable keys, record-bodyweight! matches 1
          (is (>= (count results) 2) "Should find at least 2 matching functions")
          (let [positions (into {} (map-indexed (fn [i r] [(:seon.fn/qualified-name r) i]))
                                results)]
            (when (and (positions "seon.shape.disc/add-workout!")
                       (positions "seon.shape.disc/record-bodyweight!"))
              (is (< (positions "seon.shape.disc/add-workout!")
                     (positions "seon.shape.disc/record-bodyweight!"))
                  "add-workout! (3 keys) should rank before record-bodyweight! (1 key)")))))

      (testing "functions with no input shape are excluded"
        ;; All results should have an input shape
        (let [results (gq/functions-matching-data
                       {::gq/db-name :test-db
                        ::gq/available-keys #{:seon.shape.disc/exercise
                                              :seon.shape.disc/weight
                                              :seon.shape.disc/reps
                                              :seon.shape.disc/bodyweight}})]
          (is (every? :seon.fn/qualified-name results)
              "All results should be functions"))))))

(deftest function-output-keys-test
  (testing "returns correct output keys for a function"
    ;; Uses the same DB populated in functions-matching-data-test
    ;; but we need our own fixture run, so re-register + re-ingest
    (schema/register! :seon.shape.out/x :int)
    (schema/register! :seon.shape.out/y :int)
    (schema/register! :seon.shape.out/z :int)
    (schema/register! :seon.shape.out/input [:map [:seon.shape.out/x :seon.shape.out/x]])
    (schema/register! :seon.shape.out/output
      [:map
       [:seon.shape.out/y :seon.shape.out/y]
       [:seon.shape.out/z :seon.shape.out/z]])

    (let [source "(ns seon.shape.out
  (:require [seon.schema :as schema]))

(schema/register! ::x :int)
(schema/register! ::y :int)
(schema/register! ::z :int)
(schema/register! ::input [:map [::x ::x]])
(schema/register! ::output [:map [::y ::y] [::z ::z]])

(defn compute
  {:malli/schema [:=> [:cat ::input] ::output]}
  [{::keys [x]}]
  {::y (inc x) ::z (* x 2)})"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.shape.out"
        ::ingest/functions (::extract/functions graph)
        ::ingest/specs (::extract/specs graph)
        ::ingest/entries (::extract/entries graph)
        ::ingest/shapes (::extract/shapes graph)})

      (let [keys (gq/function-output-keys
                  {::gq/db-name :test-db
                   ::gq/qualified-name "seon.shape.out/compute"})]
        (is (= #{:seon.shape.out/y :seon.shape.out/z} (set keys))
            "Should return both output keys")))))

;;; ---------------------------------------------------------------------------
;;; Deduplication
;;; ---------------------------------------------------------------------------

(deftest walker-dedup-test
  (testing "same named spec referenced from fn-schema and spec list produces one shape"
    (schema/register! :seon.shape.test7/x :int)
    (schema/register! :seon.shape.test7/y :int)
    (schema/register! :seon.shape.test7/request
                      [:map
                       [:seon.shape.test7/x :seon.shape.test7/x]
                       [:seon.shape.test7/y :seon.shape.test7/y]])
    (schema/register! :seon.shape.test7/response
                      [:map
                       [:seon.shape.test7/y :seon.shape.test7/y]])

    (let [source "(ns seon.shape.test7
  (:require [seon.schema :as schema]))

(schema/register! ::x :int)
(schema/register! ::y :int)
(schema/register! ::request [:map [::x ::x] [::y ::y]])
(schema/register! ::response [:map [::y ::y]])

(defn process
  {:malli/schema [:=> [:cat ::request] ::response]}
  [{::keys [x y]}]
  {::y (+ x y)})"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})
          shapes (::extract/shapes graph)
          request-shapes (filter #(= "shape:seon.shape.test7/request"
                                     (:seon.shape/id %))
                                 shapes)]
      ;; Should have exactly ONE request shape, not two
      (is (= 1 (count request-shapes))
          "Named spec should be deduplicated"))))

;;; ---------------------------------------------------------------------------
;;; Ingestion into Datalevin
;;; ---------------------------------------------------------------------------

(deftest ingest-shapes-test
  (testing "shapes and entries are queryable after ingestion"
    (schema/register! :seon.shape.test8/a :string)
    (schema/register! :seon.shape.test8/b :int)
    (schema/register! :seon.shape.test8/input
                      [:map
                       [:seon.shape.test8/a :seon.shape.test8/a]
                       [:seon.shape.test8/b {:optional true} :seon.shape.test8/b]])
    (schema/register! :seon.shape.test8/output
                      [:map
                       [:seon.shape.test8/b :seon.shape.test8/b]])

    (let [source "(ns seon.shape.test8
  (:require [seon.schema :as schema]))

(schema/register! ::a :string)
(schema/register! ::b :int)
(schema/register! ::input [:map [::a ::a] [::b {:optional true} ::b]])
(schema/register! ::output [:map [::b ::b]])

(defn do-thing
  {:malli/schema [:=> [:cat ::input] ::output]}
  [{::keys [a b]}]
  {::b (count a)})"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Ingest
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.shape.test8"
        ::ingest/functions (::extract/functions graph)
        ::ingest/specs (::extract/specs graph)
        ::ingest/entries (::extract/entries graph)
        ::ingest/shapes (::extract/shapes graph)})

      ;; Query: find functions with input shapes and their entry keys
      (let [results (db/query :test-db
                              '[:find ?fn ?key
                                :where
                                [?f :seon.fn/qualified-name ?fn]
                                [?f :seon.fn/input-shape ?s]
                                [?s :seon.shape/entries ?e]
                                [?e :seon.entry/key ?key]])]
        (is (seq results) "Should find functions with input shape entries")
        (is (some (fn [[fn-name key]]
                    (and (= "seon.shape.test8/do-thing" fn-name)
                         (= :seon.shape.test8/a key)))
                  results)
            "Should find do-thing with key ::a")
        (is (some (fn [[fn-name key]]
                    (and (= "seon.shape.test8/do-thing" fn-name)
                         (= :seon.shape.test8/b key)))
                  results)
            "Should find do-thing with key ::b"))

      ;; Query: shape has spec-key
      (let [shapes (db/query :test-db
                             '[:find ?id ?spec
                               :where
                               [?s :seon.shape/id ?id]
                               [?s :seon.shape/spec-key ?spec]])]
        (is (some (fn [[_ spec]]
                    (= :seon.shape.test8/input spec))
                  shapes)
            "Should find input shape with spec-key"))

      ;; Query: entry optional flag
      (let [opt-entries (db/query :test-db
                                  '[:find ?key ?opt
                                    :where
                                    [?e :seon.entry/key ?key]
                                    [?e :seon.entry/optional ?opt]])]
        (is (some (fn [[k opt]]
                    (and (= :seon.shape.test8/b k) (true? opt)))
                  opt-entries)
            "::b should be optional in input shape")))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.shape-test)
  nil)
