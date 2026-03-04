(ns seon.health.workout-test
  "Tests for seon.health.workout and seon.health.workout.render.
   Verifies render functions, scanner detection of *ctx* specs,
   and page renderer identification."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.graph.extract :as extract]
            [seon.graph.scanner :as scanner]
            [seon.health.workout :as workout]
            [seon.test-utils]
            [seon.health.workout.render :as workout-render]
            [seon.render :as render]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-workout-" (System/currentTimeMillis))
        conn (d/create-conn dir ingest/datalevin-schema)]
    (try
      (binding [*conn* conn]
        (f))
      (finally
        (render/set-conn! nil)
        (d/close conn)
        (let [d (io/file dir)]
          (doseq [file (reverse (file-seq d))]
            (.delete file)))))))

(use-fixtures :each with-temp-datalevin)

;;; ---------------------------------------------------------------------------
;;; workout-set-render Tests
;;; ---------------------------------------------------------------------------

(deftest workout-set-render-test
  (testing "workout-set-render produces both HTML and AI output"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}
          result (workout/workout-set-render data)]
      (is (= "Squat — 5x5 @ 100kg" (:seon.render/ai result)))
      (is (vector? (:seon.render/html result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest workout-set-render-formats-test
  (testing "AI format is concise and readable"
    (let [bench {::workout/exercise "Bench Press"
                 ::workout/sets 3
                 ::workout/reps 8
                 ::workout/weight 80}]
      (is (= "Bench Press — 3x8 @ 80kg"
             (:seon.render/ai (workout/workout-set-render bench))))))

  (testing "HTML format contains all fields"
    (let [deadlift {::workout/exercise "Deadlift"
                    ::workout/sets 1
                    ::workout/reps 5
                    ::workout/weight 150}
          html (:seon.render/html (workout/workout-set-render deadlift))]
      (is (= :tr (first html)))
      (is (= 4 (count (filter #(and (vector? %) (= :td (first %))) html)))))))

;;; ---------------------------------------------------------------------------
;;; Companion Render Tests
;;; ---------------------------------------------------------------------------

(deftest companion-workout-set-test
  (testing "workout.render/workout-set matches parent output"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}
          result (workout-render/workout-set data)]
      (is (= "Squat — 5x5 @ 100kg" (:seon.render/ai result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest page-render-test
  (testing "page-render produces HTML and AI from ctx data"
    (let [ctx-val {::workout/workouts workout/workouts}
          result (workout-render/page-render {::workout/*ctx* ctx-val})]
      (is (string? (:seon.render/ai result)))
      (is (str/includes? (:seon.render/ai result) "5 exercises"))
      (is (str/includes? (:seon.render/ai result) "Squat"))
      (is (vector? (:seon.render/html result)))
      (is (= :main#morph (first (:seon.render/html result)))))))

(deftest page-render-empty-test
  (testing "page-render handles empty workouts"
    (let [ctx-val {::workout/workouts []}
          result (workout-render/page-render {::workout/*ctx* ctx-val})]
      (is (str/includes? (:seon.render/ai result) "0 exercises")))))

;;; ---------------------------------------------------------------------------
;;; Scanner Tests
;;; ---------------------------------------------------------------------------

(deftest scanner-picks-up-specs-test
  (testing "scan-file finds request and response specs with correct contains-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          by-key (into {} (map (juxt :seon.spec/key identity)) specs)]
      (is (>= (count specs) 2) "Should find render specs")

      (let [req (get by-key ::workout/workout-set-render-request)]
        (is req "Request spec should exist")
        (is (= :map (:seon.spec/base-type req)))
        (is (= (set [::workout/exercise ::workout/sets
                     ::workout/reps ::workout/weight])
               (set (:seon.spec/contains-keys req)))))

      (let [resp (get by-key ::workout/workout-set-render-response)]
        (is resp "Response spec should exist")
        (is (= (set [:seon.render/html :seon.render/ai])
               (set (:seon.spec/contains-keys resp))))))))

(deftest scanner-detects-ctx-spec-test
  (testing "scan-file marks namespace as dynamic when ::*ctx* spec exists"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          ns-entity (some #(when (:seon.ns/dynamic? %) %) specs)]
      (is ns-entity "Should produce a namespace entity with :seon.ns/dynamic?")
      (is (= "seon.health.workout" (:seon.ns/name ns-entity)))
      (is (true? (:seon.ns/dynamic? ns-entity))))))

(deftest extract-detects-page-renderer-test
  (testing "extract-graph links page-render to specs - render detection at query time"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout/render.clj"})
          fn-entity (first (filter #(= "seon.health.workout.render/page-render"
                                       (:seon.fn/qualified-name %))
                                   (::extract/functions graph)))
          specs (::extract/specs graph)
          input-spec (first (filter #(= :seon.health.workout.render/page-render-request
                                        (:seon.spec/key %)) specs))
          output-spec (first (filter #(= :seon.health.workout.render/page-render-response
                                         (:seon.spec/key %)) specs))]
      (is (some? fn-entity) "Should find page-render function")
      ;; Function is linked to specs
      (is (some? (:seon.fn/input-spec fn-entity)))
      (is (some? (:seon.fn/output-spec fn-entity)))
      ;; Input spec contains *ctx* key (used for page renderer detection at query time)
      (is (some? input-spec))
      (is (some #(str/ends-with? (name %) "*ctx*") (:seon.spec/contains-keys input-spec)))
      ;; Output spec contains :seon.render/html
      (is (some? output-spec))
      (is (contains? (set (:seon.spec/contains-keys output-spec)) :seon.render/html)))))

(deftest extract-links-workout-render-fn-test
  (testing "extract-graph links workout-set-render to its specs"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout.clj"})
          fn-entity (first (filter #(= "seon.health.workout/workout-set-render"
                                       (:seon.fn/qualified-name %))
                                   (::extract/functions graph)))
          specs (::extract/specs graph)
          input-spec (first (filter #(= ::workout/workout-set-render-request
                                        (:seon.spec/key %)) specs))]
      (is (some? fn-entity) "Should find workout-set-render")
      (is (= [:seon.spec/key ::workout/workout-set-render-request]
             (:seon.fn/input-spec fn-entity)))
      (is (= [:seon.spec/key ::workout/workout-set-render-response]
             (:seon.fn/output-spec fn-entity)))
      ;; Input spec contains the required keys (used for resolution at query time)
      (is (some? input-spec))
      (is (= (set [::workout/exercise ::workout/sets
                   ::workout/reps ::workout/weight])
             (set (:seon.spec/contains-keys input-spec)))))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests
;;; ---------------------------------------------------------------------------

(deftest find-renderer-integration-test
  (testing "find-renderer discovers workout-set-render after ingestion"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout.clj"})]
      ;; Transact specs first (functions reference them via lookup refs)
      (d/transact! *conn* (vec (::extract/specs graph)))
      ;; Transact linked functions
      (d/transact! *conn* (vec (::extract/functions graph))))

    (let [workout-data {::workout/exercise "Squat"
                        ::workout/sets 3
                        ::workout/reps 8
                        ::workout/weight 100}]
      (is (= "seon.health.workout/workout-set-render"
             (render/find-renderer *conn* workout-data :html)))
      (is (= "seon.health.workout/workout-set-render"
             (render/find-renderer *conn* workout-data :ai))))))

(deftest try-render-test
  (testing "try-render returns nil when no renderer is registered"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}]
      (render/set-conn! *conn*)
      (is (nil? (render/try-render data :ai)))
      (render/set-conn! nil))))

(deftest has-renderer-test
  (testing "has-renderer? returns false when no renderer registered"
    (render/set-conn! *conn*)
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}]
      (is (not (render/has-renderer? data :ai))))
    (render/set-conn! nil)))

(deftest initial-state-test
  (testing "initial-state returns map with ::workouts"
    (let [state (workout/initial-state)]
      (is (map? state))
      (is (= workout/workouts (::workout/workouts state)))
      (is (= 5 (count (::workout/workouts state)))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.health.workout-test)
  nil)
