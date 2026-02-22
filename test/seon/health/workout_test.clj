(ns seon.health.workout-test
  "Tests for seon.health.workout namespace.
   Verifies workout-set-render produces correct output and
   scanner picks up specs correctly."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.graph.scanner :as scanner]
            [seon.health.workout :as workout]
            [seon.render :as render]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-workout-" (System/currentTimeMillis))
        conn (d/get-conn dir ingest/datalevin-schema)]
    (try
      (binding [*conn* conn]
        (f))
      (finally
        (d/close conn)
        (let [d (io/file dir)]
          (doseq [file (reverse (file-seq d))]
            (.delete file)))))))

(use-fixtures :each with-temp-datalevin)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest workout-set-render-test
  (testing "workout-set-render produces both HTML and AI output"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}
          result (workout/workout-set-render data)]
      (is (= "Squat 5x5 @ 100kg" (:seon.render/ai result)))
      (is (vector? (:seon.render/html result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest workout-set-render-formats-test
  (testing "AI format is concise and readable"
    (let [bench {::workout/exercise "Bench Press"
                 ::workout/sets 3
                 ::workout/reps 8
                 ::workout/weight 80}]
      (is (= "Bench Press 3x8 @ 80kg"
             (:seon.render/ai (workout/workout-set-render bench))))))

  (testing "HTML format contains all fields"
    (let [deadlift {::workout/exercise "Deadlift"
                    ::workout/sets 1
                    ::workout/reps 5
                    ::workout/weight 150}
          html (:seon.render/html (workout/workout-set-render deadlift))]
      ;; Check it's a table row with expected structure
      (is (= :tr (first html)))
      (is (= 4 (count (filter #(and (vector? %) (= :td (first %))) html)))))))

(deftest scanner-picks-up-specs-test
  (testing "scan-file finds request and response specs with correct contains-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          by-key (into {} (map (juxt :seon.spec/key identity)) specs)]
      ;; Should find multiple specs (exercise, sets, reps, weight, workout-set, etc.)
      (is (>= (count specs) 2) "Should find render specs")

      ;; Check request spec
      (let [req (get by-key ::workout/workout-set-render-request)]
        (is req "Request spec should exist")
        (is (= :map (:seon.spec/base-type req)))
        (is (= (set [::workout/exercise
                     ::workout/sets
                     ::workout/reps
                     ::workout/weight])
               (set (:seon.spec/contains-keys req)))))

      ;; Check response spec
      (let [resp (get by-key ::workout/workout-set-render-response)]
        (is resp "Response spec should exist")
        (is (= (set [:seon.render/html :seon.render/ai])
               (set (:seon.spec/contains-keys resp))))))))

(deftest link-fns-to-specs-test
  (testing "link-fns-to-specs detects render function and populates render-input-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          fns [{:seon.fn/qualified-name "seon.health.workout/workout-set-render"
                :seon.fn/namespace "seon.health.workout"
                :seon.fn/name "workout-set-render"
                :seon.fn/private false}]
          linked (scanner/link-fns-to-specs fns specs)
          fn-entity (first linked)]
      (is (= [:seon.spec/key ::workout/workout-set-render-request]
             (:seon.fn/input-spec fn-entity)))
      (is (= [:seon.spec/key ::workout/workout-set-render-response]
             (:seon.fn/output-spec fn-entity)))
      (is (= (set [::workout/exercise
                   ::workout/sets
                   ::workout/reps
                   ::workout/weight])
             (set (:seon.fn/render-input-keys fn-entity)))))))

(deftest find-renderer-integration-test
  (testing "find-renderer discovers workout-set-render after ingestion"
    ;; Ingest specs
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})]
      (d/transact! *conn* (vec specs)))

    ;; Ingest linked fn entity
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          fns [{:seon.fn/qualified-name "seon.health.workout/workout-set-render"
                :seon.fn/namespace "seon.health.workout"
                :seon.fn/name "workout-set-render"
                :seon.fn/private false}]
          linked (scanner/link-fns-to-specs fns specs)]
      (d/transact! *conn* (vec linked)))

    ;; find-renderer should discover it
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
    ;; With empty conn, no renderers exist
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}]
      ;; Set conn temporarily
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

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.health.workout-test)
  nil)
