(ns seon.agent.testrun-test
  "Tests for `seon.agent.testrun` — the pytest parser, argv detection,
   persistence, and the `:test-failures` derived section.

   The parser tests run on CAPTURED real pytest output (pytest 9.x, default
   / `-q` / collection-error / green / no-tests variants) — hermetic, no
   subprocess, no network. The persist+section tests use a fresh :memory
   conn `set!` as the root db/*conn* (CLJS bindings don't survive awaits —
   the my.plan-test / turn-capture-test pattern).

   Run: bin/test-cljs, or interactively:
     (require 'seon.agent.testrun-test :reload)
     (cljs.test/run-tests 'seon.agent.testrun-test)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.ctx.testrun :as ctx-testrun]
    [seon.agent.testrun :as testrun]
    [seon.client :as client]
    [seon.db :as db]))

;; ---------------------------------------------------------------------------
;; Captured pytest output fixtures (verbatim, pytest 9.1.1).
;; ---------------------------------------------------------------------------

(def ^:private out-failures-default
  (str "============================= test session starts ==============================\n"
       "platform darwin -- Python 3.14.6, pytest-9.1.1, pluggy-1.6.0\n"
       "collected 4 items\n\n"
       "tmp/pyt/tests/test_sample.py ..FF                                        [100%]\n\n"
       "=================================== FAILURES ===================================\n"
       "________________________________ test_fail_math ________________________________\n"
       "    def test_fail_math():\n>       assert 2 * 2 == 5\nE       assert (2 * 2) == 5\n\n"
       "tmp/pyt/tests/test_sample.py:8: AssertionError\n"
       "=========================== short test summary info ============================\n"
       "FAILED tmp/pyt/tests/test_sample.py::test_fail_math - assert (2 * 2) == 5\n"
       "FAILED tmp/pyt/tests/test_sample.py::test_fail_str - AssertionError: assert '...\n"
       "========================= 2 failed, 2 passed in 0.09s ==========================\n"))

(def ^:private out-failures-q
  (str "..FF                                                                     [100%]\n"
       "=================================== FAILURES ===================================\n"
       "tmp/pyt/tests/test_sample.py:8: AssertionError\n"
       "=========================== short test summary info ============================\n"
       "FAILED tmp/pyt/tests/test_sample.py::test_fail_math - assert (2 * 2) == 5\n"
       "FAILED tmp/pyt/tests/test_sample.py::test_fail_str - AssertionError: assert '...\n"
       "2 failed, 2 passed in 0.06s\n"))

(def ^:private out-green-default
  (str "============================= test session starts ==============================\n"
       "collected 2 items\n\n"
       "tmp/pyt/tests/test_green.py ..                                           [100%]\n\n"
       "============================== 2 passed in 0.06s ===============================\n"))

(def ^:private out-green-q
  (str "..                                                                       [100%]\n"
       "2 passed in 0.06s\n"))

(def ^:private out-collection-error
  (str "=========================== short test summary info ============================\n"
       "ERROR tmp/pyt/tests/test_collerr.py\n"
       "!!!!!!!!!!!!!!!!!!!! Interrupted: 1 error during collection !!!!!!!!!!!!!!!!!!!!\n"
       "=============================== 1 error in 0.10s ===============================\n"))

(def ^:private out-no-tests
  (str "collected 0 items\n\n"
       "============================ no tests ran in 0.06s =============================\n"))

;; ---------------------------------------------------------------------------
;; argv detection — computed, not a hand-list.
;; ---------------------------------------------------------------------------

(deftest pytest-argv-detects-every-invocation-form
  (is (testrun/pytest-argv? "pytest" ["-q"]) "bare pytest")
  (is (testrun/pytest-argv? "/abs/path/pytest" []) "abs-path pytest")
  (is (testrun/pytest-argv? "python" ["-m" "pytest" "tests/"]) "python -m pytest")
  (is (testrun/pytest-argv? "python3" ["-m" "pytest"]) "python3 -m pytest")
  (is (testrun/pytest-argv? "python3.14" ["-m" "pytest"]) "versioned interpreter")
  (is (not (testrun/pytest-argv? "python3" ["-m" "unittest"])) "not a pytest module")
  (is (not (testrun/pytest-argv? "python3" ["script.py"])) "plain python script")
  (is (not (testrun/pytest-argv? "git" ["status"])) "unrelated command"))

;; ---------------------------------------------------------------------------
;; Parser — counts + failures + recognition.
;; ---------------------------------------------------------------------------

(deftest parse-failures-default-output
  (let [r (testrun/parse {:seon.agent.testrun/stdout out-failures-default})]
    (is (true? (:seon.agent.testrun/ok? r)))
    (is (= :pytest (:seon.agent.testrun/framework r)))
    (is (= 2 (:seon.agent.testrun/passed r)))
    (is (= 2 (:seon.agent.testrun/failed r)))
    (is (= 0 (:seon.agent.testrun/errors r)))
    (let [fs (:seon.agent.testrun/failures r)]
      (is (= 2 (count fs)))
      (is (= "tmp/pyt/tests/test_sample.py" (:seon.agent.testrun/path (first fs))))
      (is (= "test_fail_math" (:seon.agent.testrun/test-name (first fs))))
      (is (= "assert (2 * 2) == 5" (:seon.agent.testrun/message (first fs))))
      (is (= "test_fail_str" (:seon.agent.testrun/test-name (second fs)))))))

(deftest parse-tolerates-the-q-variant
  (let [r (testrun/parse {:seon.agent.testrun/stdout out-failures-q})]
    (is (true? (:seon.agent.testrun/ok? r)))
    (is (= 2 (:seon.agent.testrun/passed r)))
    (is (= 2 (:seon.agent.testrun/failed r)))
    (is (= 2 (count (:seon.agent.testrun/failures r)))
        "-q output has the same short-summary FAILED lines")))

(deftest parse-green-run-is-recognized-with-no-failures
  (doseq [[label out] {:default out-green-default :q out-green-q}]
    (let [r (testrun/parse {:seon.agent.testrun/stdout out})]
      (is (true? (:seon.agent.testrun/ok? r)) (str label " recognized"))
      (is (= 2 (:seon.agent.testrun/passed r)) (str label " passed count"))
      (is (= 0 (:seon.agent.testrun/failed r)) (str label " no failures"))
      (is (= [] (:seon.agent.testrun/failures r)) (str label " empty failure set")))))

(deftest parse-collection-error
  (let [r (testrun/parse {:seon.agent.testrun/stdout out-collection-error})]
    (is (true? (:seon.agent.testrun/ok? r)))
    (is (= 1 (:seon.agent.testrun/errors r)))
    (is (= 0 (:seon.agent.testrun/failed r)))
    (let [fs (:seon.agent.testrun/failures r)]
      (is (= 1 (count fs)))
      (is (= "tmp/pyt/tests/test_collerr.py" (:seon.agent.testrun/path (first fs)))
          "the file-only ERROR line parses (path, no ::test)")
      (is (= "" (:seon.agent.testrun/message (first fs))) "no message → empty string"))))

(deftest parse-no-tests-ran-is-recognized-green
  (let [r (testrun/parse {:seon.agent.testrun/stdout out-no-tests})]
    (is (true? (:seon.agent.testrun/ok? r)))
    (is (= 0 (:seon.agent.testrun/passed r)))
    (is (= [] (:seon.agent.testrun/failures r)))))

(deftest parse-unrecognized-is-a-value-not-a-throw
  (let [r (testrun/parse {:seon.agent.testrun/stdout "hello world\nnot a test run at all\n"})]
    (is (false? (:seon.agent.testrun/ok? r)))
    (is (= :unknown (:seon.agent.testrun/framework r)))
    (is (= "unrecognized test output format" (:seon.error/message r)))))

;; ---------------------------------------------------------------------------
;; Persistence + the derived :test-failures section.
;; ---------------------------------------------------------------------------

(def ^:private agent-id "trtestagent001")

(defn- with-conn
  "Fresh :memory agent conn `set!` as root db/*conn*, seeded with one agent,
   `body` (conn → Promise) run under it; prior root restored after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (db/transact! {:seon.db/tx-data [{:seon.agent/id agent-id}]})
                     (.then (fn [_] (body conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- section [conn]
  (ctx-testrun/testrun-block {:seon.db/db @conn :seon.agent/id agent-id}))

(deftest section-renders-latest-failures-then-vanishes-on-green
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (section conn)) "no run yet → empty section")
            (-> (testrun/record!
                  {:seon.agent.testrun/agent-id agent-id
                   :seon.agent.testrun/result
                   (testrun/parse {:seon.agent.testrun/stdout out-failures-default})})
                (.then
                  (fn [rec]
                    (is (true? (:seon.agent.testrun/ok? rec)) "persist ok")
                    (let [txt (section conn)]
                      (is (str/includes? txt "TEST FAILURES") "header renders")
                      (is (str/includes? txt "2 failed") "failed count renders")
                      (is (str/includes? txt "test_fail_math") "names a failing test")
                      (is (str/includes? txt "test_fail_str") "names the other one"))))
                ;; A LATER green run supersedes → the section self-heals to empty.
                (.then
                  (fn [_]
                    (testrun/record!
                      {:seon.agent.testrun/agent-id agent-id
                       :seon.agent.testrun/result
                       (testrun/parse {:seon.agent.testrun/stdout out-green-default})})))
                (.then
                  (fn [_]
                    (is (= "" (section conn))
                        "latest run is green → section vanishes (no stored flag)"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest record-without-agent-scope-persists-nothing
  (async done
    (-> (with-conn
          (fn [_conn]
            (db/without-agent
              (fn []
                (-> (testrun/record!
                      {:seon.agent.testrun/result
                       (testrun/parse {:seon.agent.testrun/stdout out-failures-default})})
                    (.then (fn [rec]
                             (is (false? (:seon.agent.testrun/ok? rec))
                                 "no agent scope → not persisted, but a value not a throw"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
