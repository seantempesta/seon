(ns seon.health.workout.render-test
  "Tests for the workout render companion namespace.
   Verifies scanner picks up specs, link-fns-to-specs works,
   find-renderer discovers the function, and direct calls produce output."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.graph.scanner :as scanner]
            [seon.health.workout.render :as workout-render]
            [seon.render :as render]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-workout-render-" (System/currentTimeMillis))
        conn (d/get-conn dir ingest/datalevin-schema)]
    (try
      (binding [*conn* conn]
        (f))
      (finally
        (d/close conn)
        (let [d (clojure.java.io/file dir)]
          (doseq [file (reverse (file-seq d))]
            (.delete file)))))))

(use-fixtures :each with-temp-datalevin)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest direct-render-test
  (testing "workout-set produces both HTML and AI output"
    (let [data {:seon.health.workout/exercise "Squat"
                :seon.health.workout/sets 3
                :seon.health.workout/reps 8
                :seon.health.workout/weight 100}
          result (workout-render/workout-set data)]
      (is (= "Squat — 3x8 @ 100kg" (:seon.render/ai result)))
      (is (vector? (:seon.render/html result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest scanner-picks-up-specs-test
  (testing "scan-file finds request and response specs with correct contains-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout/render.clj"})
          by-key (into {} (map (juxt :seon.spec/key identity)) specs)]
      (is (= 2 (count specs)) "Should find request and response specs")

      (let [req (get by-key :seon.health.workout.render/workout-set-request)]
        (is req "Request spec should exist")
        (is (= :map (:seon.spec/base-type req)))
        (is (= (set [:seon.health.workout/exercise
                      :seon.health.workout/sets
                      :seon.health.workout/reps
                      :seon.health.workout/weight])
               (set (:seon.spec/contains-keys req)))))

      (let [resp (get by-key :seon.health.workout.render/workout-set-response)]
        (is resp "Response spec should exist")
        (is (= (set [:seon.render/html :seon.render/ai])
               (set (:seon.spec/contains-keys resp))))))))

(deftest link-fns-to-specs-test
  (testing "link-fns-to-specs detects render function and populates render-input-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout/render.clj"})
          fns [{:seon.fn/qualified-name "seon.health.workout.render/workout-set"
                :seon.fn/namespace "seon.health.workout.render"
                :seon.fn/name "workout-set"
                :seon.fn/private false}]
          linked (scanner/link-fns-to-specs fns specs)
          fn-entity (first linked)]
      (is (= [:seon.spec/key :seon.health.workout.render/workout-set-request]
             (:seon.fn/input-spec fn-entity)))
      (is (= [:seon.spec/key :seon.health.workout.render/workout-set-response]
             (:seon.fn/output-spec fn-entity)))
      (is (= (set [:seon.health.workout/exercise
                    :seon.health.workout/sets
                    :seon.health.workout/reps
                    :seon.health.workout/weight])
             (set (:seon.fn/render-input-keys fn-entity)))))))

(deftest find-renderer-integration-test
  (testing "find-renderer discovers workout-set after ingestion"
    ;; Ingest specs
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout/render.clj"})]
      (d/transact! *conn* (vec specs)))

    ;; Ingest linked fn entity
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout/render.clj"})
          fns [{:seon.fn/qualified-name "seon.health.workout.render/workout-set"
                :seon.fn/namespace "seon.health.workout.render"
                :seon.fn/name "workout-set"
                :seon.fn/private false}]
          linked (scanner/link-fns-to-specs fns specs)]
      (d/transact! *conn* (vec linked)))

    ;; find-renderer should discover it
    (is (= "seon.health.workout.render/workout-set"
           (render/find-renderer *conn*
                                 {:seon.health.workout/exercise "Squat"
                                  :seon.health.workout/sets 3
                                  :seon.health.workout/reps 8
                                  :seon.health.workout/weight 100}
                                 :html)))
    (is (= "seon.health.workout.render/workout-set"
           (render/find-renderer *conn*
                                 {:seon.health.workout/exercise "Squat"
                                  :seon.health.workout/sets 3
                                  :seon.health.workout/reps 8
                                  :seon.health.workout/weight 100}
                                 :ai)))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.health.workout.render-test)
  nil)
