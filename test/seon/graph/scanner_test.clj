(ns seon.graph.scanner-test
  "Tests for seon.graph.scanner - static spec/schema and var extraction.
   Function extraction and fn-to-spec linking are tested in extract_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.graph.scanner :as scanner]))

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest scan-file-pool-test
  (testing "scans schema/register! calls from pool.clj"
    (let [specs (scanner/scan-file {::scanner/file-path "src/seon/flow/pool.clj"})]
      (is (vector? specs))
      (is (pos? (count specs))
          "pool.clj has multiple schema registrations")

      ;; Check for the ::port spec specifically
      (let [port-spec (some #(when (= :seon.flow.pool/port (:seon.spec/key %)) %) specs)]
        (is (some? port-spec) "Should find ::port spec")
        (is (= "seon.flow.pool" (:seon.spec/namespace port-spec)))
        (is (= :int (:seon.spec/base-type port-spec)))
        (is (string? (:seon.spec/definition port-spec)))
        (is (inst? (:seon.spec/updated-at port-spec)))))))

(deftest scan-file-analyzer-test
  (testing "scans schema/register! calls from analyzer.clj"
    (let [specs (scanner/scan-file {::scanner/file-path "src/seon/graph/analyzer.clj"})]
      (is (pos? (count specs))
          "analyzer.clj has many schema registrations")

      ;; Check for a :map type spec with contains-keys
      (let [ns-entity (some #(when (= :seon.graph.analyzer/namespace-entity
                                      (:seon.spec/key %)) %)
                            specs)]
        (is (some? ns-entity) "Should find ::namespace-entity spec")
        (is (= :map (:seon.spec/base-type ns-entity)))
        ;; :map specs with qualified keys should have :seon.spec/contains-keys
        (is (vector? (:seon.spec/contains-keys ns-entity))
            "Map specs should extract contains-keys")
        (is (some #{:seon.ns/name} (:seon.spec/contains-keys ns-entity))
            "Should contain :seon.ns/name key")))))

(deftest scan-file-nonexistent-test
  (testing "returns empty vector for non-existent file"
    (is (= [] (scanner/scan-file {::scanner/file-path "src/nonexistent.clj"})))))

(deftest scan-directory-test
  (testing "scans all files in a directory"
    (let [specs (scanner/scan-directory {::scanner/dir-path "src/seon/graph/"})]
      (is (vector? specs))
      (is (pos? (count specs))
          "graph directory has files with schema registrations")

      ;; Should have specs from multiple namespaces
      (let [namespaces (set (map :seon.spec/namespace
                                 (filter :seon.spec/namespace specs)))]
        (is (contains? namespaces "seon.graph.analyzer")
            "Should include analyzer specs")
        (is (contains? namespaces "seon.graph.ingest")
            "Should include ingest specs")))))

(deftest scan-directory-nonexistent-test
  (testing "returns empty vector for non-existent directory"
    (is (= [] (scanner/scan-directory {::scanner/dir-path "nonexistent/"})))))

(deftest extract-base-type-test
  (testing "extracts base type from schema forms"
    (is (= :int (scanner/extract-base-type [:int {:min 0}])))
    (is (= :map (scanner/extract-base-type [:map [:foo :string]])))
    (is (= :vector (scanner/extract-base-type [:vector :string])))
    (is (= :string (scanner/extract-base-type :string)))
    (is (= :boolean (scanner/extract-base-type :boolean)))))

(deftest extract-contains-keys-test
  (testing "extracts qualified keys from :map schemas"
    (is (= [:seon.ns/name :seon.ns/file]
           (scanner/extract-contains-keys
            [:map [:seon.ns/name :string] [:seon.ns/file :string]])))

    (is (= [:seon.ns/name]
           (scanner/extract-contains-keys
            [:map {:description "test"} [:seon.ns/name :string]]))
        "Should skip props map"))

  (testing "returns nil for non-map schemas"
    (is (nil? (scanner/extract-contains-keys [:int {:min 0}])))
    (is (nil? (scanner/extract-contains-keys :string)))))

(deftest scan-source-no-defn-entities-test
  (testing "scan-source does NOT produce fn entities (handled by extract.clj)"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(schema/register! ::foo [:string])

(defn my-public-fn [x] x)

(defn- my-private-fn [x] x)"
          results (scanner/scan-source {::scanner/source source})
          fns (filter :seon.fn/qualified-name results)
          specs (filter :seon.spec/key results)]
      (is (= 1 (count specs)) "Should find one spec")
      (is (= 0 (count fns)) "Should NOT find function entities (handled by clj-kondo)"))))

(deftest scan-source-detects-def-test
  (testing "scan-source finds def forms and infers value types"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(def my-vec [1 2 3])

(def my-map {:a 1})

(def ^:private my-private \"a docstring\" {:secret true})

(def my-string \"hello\")

(def my-num 42)

(def my-kw :foo)

(def my-bool true)

(def my-expr (+ 1 2))

(defn my-fn [x] x)"
          results (scanner/scan-source {::scanner/source source})
          vars (filter :seon.var/qualified-name results)]
      (is (= 8 (count vars)) "Should find 8 def vars (not the defn)")

      (let [by-name (into {} (map (juxt :seon.var/name identity)) vars)]
        (is (= :vector (:seon.var/value-type (get by-name "my-vec"))))
        (is (= :map (:seon.var/value-type (get by-name "my-map"))))
        (is (= :string (:seon.var/value-type (get by-name "my-string"))))
        (is (= :number (:seon.var/value-type (get by-name "my-num"))))
        (is (= :keyword (:seon.var/value-type (get by-name "my-kw"))))
        (is (= :boolean (:seon.var/value-type (get by-name "my-bool"))))
        (is (= :expr (:seon.var/value-type (get by-name "my-expr"))))

        ;; Docstring extraction
        (is (= "a docstring" (:seon.var/doc (get by-name "my-private"))))

        ;; Qualified name
        (is (= "seon.example/my-vec"
               (:seon.var/qualified-name (get by-name "my-vec"))))
        (is (= "seon.example" (:seon.var/namespace (get by-name "my-vec"))))

        ;; All vars should have updated-at
        (is (every? #(inst? (:seon.var/updated-at %)) vars))))))

(deftest scan-workout-finds-def-vars-test
  (testing "scanning workout.clj finds def vars like workouts"
    (let [results (scanner/scan-file {::scanner/file-path "src/seon/health/workout.clj"})
          vars (filter :seon.var/qualified-name results)
          workouts-var (first (filter #(= "workouts" (:seon.var/name %)) vars))]
      (is (some? workouts-var) "Should find workouts def var")
      (is (= "seon.health.workout/workouts"
             (:seon.var/qualified-name workouts-var)))
      (is (= :vector (:seon.var/value-type workouts-var))))))

(deftest extract-optional-keys-test
  (testing "extracts optional qualified keys from :map schemas"
    (is (= [:seon.foo/bar]
           (scanner/extract-optional-keys
            [:map
             [:seon.foo/required :string]
             [:seon.foo/bar {:optional true} :string]]))
        "Should find keys with {:optional true}")

    (is (= [:seon.foo/a :seon.foo/b]
           (scanner/extract-optional-keys
            [:map
             [:seon.foo/req :int]
             [:seon.foo/a {:optional true} :string]
             [:seon.foo/b {:optional true} :int]])))

    (is (= []
           (scanner/extract-optional-keys
            [:map [:seon.foo/a :string] [:seon.foo/b :int]]))
        "No optional keys -> empty vector"))

  (testing "returns nil for non-map schemas"
    (is (nil? (scanner/extract-optional-keys [:int {:min 0}])))
    (is (nil? (scanner/extract-optional-keys :string)))))

(deftest scan-source-optional-keys-test
  (testing "scan-source extracts optional-keys from specs"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(schema/register! ::page-request
  [:map
   [::ctx :any]
   [::sort-by {:optional true} :string]
   [::page {:optional true} :int]])"
          results (scanner/scan-source {::scanner/source source})
          spec (first (filter #(= :seon.example/page-request (:seon.spec/key %)) results))]
      (is (some? spec))
      (is (= [:seon.example/ctx :seon.example/sort-by :seon.example/page]
             (:seon.spec/contains-keys spec))
          "contains-keys has all keys")
      (is (= [:seon.example/sort-by :seon.example/page]
             (:seon.spec/optional-keys spec))
          "optional-keys has only optional ones"))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.scanner-test)
  nil)
