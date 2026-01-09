(ns seon.orchestrator.mcp-test
  "Tests for MCP eval functionality.

  Verifies that the nREPL-based eval mechanism used by the MCP server
  works correctly for basic code evaluation."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nrepl.core :as nrepl]
            [seon.orchestrator.nrepl :as nrepl-multi]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn cleanup-servers
  "Fixture that cleans up any running servers after each test."
  [f]
  (nrepl-multi/stop-all-namespace-nrepls!)
  (try
    (f)
    (finally
      (nrepl-multi/stop-all-namespace-nrepls!))))

(use-fixtures :each cleanup-servers)

;;; ---------------------------------------------------------------------------
;;; MCP Eval Tests
;;; ---------------------------------------------------------------------------

(deftest mcp-eval-basic-test
  (testing "MCP eval can evaluate (+ 1 2) and return 3"
    (let [{:keys [port]} (nrepl-multi/start-namespace-nrepl! {:namespace 'test.mcp.eval})]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              ;; Send eval request (same as MCP server does)
              responses (doall (nrepl/message client {:op "eval" :code "(+ 1 2)"}))
              ;; Extract the value from responses
              value (->> responses
                         (keep :value)
                         first)]
          (is (= "3" value)
              "Evaluating (+ 1 2) should return \"3\""))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.orchestrator.mcp-test)

  ;; Run specific test
  (clojure.test/test-var #'mcp-eval-basic-test))
