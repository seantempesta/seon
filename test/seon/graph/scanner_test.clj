(ns seon.graph.scanner-test
  "Tests for seon.graph.scanner - static spec/schema extraction."
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
      (let [namespaces (set (map :seon.spec/namespace specs))]
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

(deftest link-fns-to-specs-test
  (testing "links functions to matching request/response specs"
    (let [fns [{:seon.fn/qualified-name "seon.health.workout/log-workout"
                :seon.fn/namespace "seon.health.workout"
                :seon.fn/name "log-workout"}]
          specs [{:seon.spec/key :seon.health.workout/log-workout-request
                  :seon.spec/namespace "seon.health.workout"
                  :seon.spec/base-type :map
                  :seon.spec/contains-keys [:seon.health.workout/exercise
                                            :seon.health.workout/sets]
                  :seon.spec/definition "[:map ...]"
                  :seon.spec/updated-at (java.util.Date.)}
                 {:seon.spec/key :seon.health.workout/log-workout-response
                  :seon.spec/namespace "seon.health.workout"
                  :seon.spec/base-type :map
                  :seon.spec/contains-keys [:seon.health.workout/id]
                  :seon.spec/definition "[:map ...]"
                  :seon.spec/updated-at (java.util.Date.)}]
          result (scanner/link-fns-to-specs fns specs)
          fn-entity (first result)]
      (is (= [:seon.spec/key :seon.health.workout/log-workout-request]
             (:seon.fn/input-spec fn-entity)))
      (is (= [:seon.spec/key :seon.health.workout/log-workout-response]
             (:seon.fn/output-spec fn-entity)))
      (is (inst? (:seon.fn/updated-at fn-entity)))
      (is (nil? (:seon.fn/render-input-keys fn-entity))
          "Non-render function should not have render-input-keys")))

  (testing "detects render functions and populates render-input-keys"
    (let [fns [{:seon.fn/qualified-name "seon.health.workout.render/workout-set"
                :seon.fn/namespace "seon.health.workout.render"
                :seon.fn/name "workout-set"}]
          specs [{:seon.spec/key :seon.health.workout.render/workout-set-request
                  :seon.spec/namespace "seon.health.workout.render"
                  :seon.spec/base-type :map
                  :seon.spec/contains-keys [:seon.health.workout/exercise
                                            :seon.health.workout/sets
                                            :seon.health.workout/reps]
                  :seon.spec/definition "[:map ...]"
                  :seon.spec/updated-at (java.util.Date.)}
                 {:seon.spec/key :seon.health.workout.render/workout-set-response
                  :seon.spec/namespace "seon.health.workout.render"
                  :seon.spec/base-type :map
                  :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                  :seon.spec/definition "[:map ...]"
                  :seon.spec/updated-at (java.util.Date.)}]
          result (scanner/link-fns-to-specs fns specs)
          fn-entity (first result)]
      (is (= [:seon.health.workout/exercise
              :seon.health.workout/sets
              :seon.health.workout/reps]
             (:seon.fn/render-input-keys fn-entity))
          "Render function should have input keys populated")))

  (testing "handles functions with no matching specs"
    (let [fns [{:seon.fn/qualified-name "seon.foo/bar"
                :seon.fn/namespace "seon.foo"
                :seon.fn/name "bar"}]
          result (scanner/link-fns-to-specs fns [])]
      (is (= 1 (count result)))
      (is (nil? (:seon.fn/input-spec (first result))))
      (is (nil? (:seon.fn/output-spec (first result))))
      (is (inst? (:seon.fn/updated-at (first result)))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.scanner-test)
  nil)
