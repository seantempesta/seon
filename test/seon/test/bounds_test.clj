(ns seon.test.bounds-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.test.bounds :as bounds]
            [seon.test-support :as support])
  (:import [java.util.concurrent TimeUnit]))

(deftest bounds-derive-from-declarations-and-admit-only-the-orchestrator
  (is (= 290 (bounds/exchange-seconds 0 bounds/fixture-priming-ms)))
  (is (= 921 (bounds/exchange-seconds 900001 bounds/fixture-priming-ms)))
  (is (= 320 (bounds/silence-seconds {})))
  (is (= 1800 (bounds/silence-seconds {"SEON_TEST_ORCHESTRATOR" "1"
                                      "SEON_TEST_SILENCE_SECONDS" "1800"})))
  (doseq [environment [{"SEON_TEST_SILENCE_SECONDS" "1800"}
                       {"SEON_TEST_SILENCE_SECONDS" "1500" "SEON_CODEX_LANE" "lane"}
                       {"SEON_TEST_SILENCE_SECONDS" "1800" "SEON_CODEX_LANE" "lane"
                        "SEON_TEST_ORCHESTRATOR" "1"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"declared silence bound 320s"
                         (bounds/silence-seconds environment)))))

(deftest shell-admission-refuses-overrides-before-acquiring-a-slot
  (let [root (doto (io/file "tmp" (str "bounds-" (random-uuid))) .mkdirs)
        log (io/file root "output")]
    (try
      (doseq [variable ["SEON_TEST_SILENCE_SECONDS" "SEON_TEST_SLOTS"]
              role [:undeclared :lane :both :orchestrator]]
        (let [builder (doto (ProcessBuilder.
                             ^java.util.List
                             ["bash" "-c" "source bin/_test-slot; echo ADMITTED"])
                        (.redirectErrorStream true) (.redirectOutput log))
              environment (.environment builder)]
          (doseq [variable-name ["SEON_CODEX_LANE" "SEON_TEST_ORCHESTRATOR"
                       "SEON_TEST_SILENCE_SECONDS" "SEON_TEST_SLOTS"]]
            (.remove environment variable-name))
          (.put environment variable "1800")
          (when (#{:lane :both} role) (.put environment "SEON_CODEX_LANE" "fixture"))
          (when (#{:both :orchestrator} role) (.put environment "SEON_TEST_ORCHESTRATOR" "1"))
          (let [child (.start builder)]
            (try
              (is (.waitFor child support/event-backstop-seconds TimeUnit/SECONDS))
              (when-not (.isAlive child)
                (let [output (slurp log)]
                  (is (= (if (= :orchestrator role) 0 64) (.exitValue child)) output)
                  (is (str/includes? output (if (= :orchestrator role) "ADMITTED"
                                               (str variable " refused: declared"))) output)))
              (finally
                (when (.isAlive child) (.destroyForcibly child))
                (.waitFor child support/event-backstop-seconds TimeUnit/SECONDS))))))
      (finally (support/delete-recursively! root)))))
