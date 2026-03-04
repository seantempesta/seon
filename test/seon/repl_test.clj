(ns seon.repl-test
  "Tests for seon.repl namespace.

   Uses a temporary local Datalevin database (no server required)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.graph.ingest :as ingest]
            [seon.repl :as super])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir
  "Create a temporary directory for Datalevin."
  []
  (let [dir (File/createTempFile "seon-super-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir
  "Recursively delete a directory."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn with-temp-conn [f]
  (let [dir (temp-dir)
        schema (merge ingest/datalevin-schema super/datalevin-schema)
        conn (d/create-conn dir schema)]
    (try
      (binding [*test-conn* conn
                db/*direct-write* true]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; classify-form tests
;;; ---------------------------------------------------------------------------

(deftest classify-form-test
  (testing "defn classification"
    (let [result (super/classify-form {::super/source "(defn ema [period data] (reduce + data))"})]
      (is (= ::super/form-type (ffirst (select-keys result [::super/form-type]))))
      (is (= :defn (::super/form-type result)))
      (is (= "ema" (::super/form-name result)))))

  (testing "defn- classification"
    (let [result (super/classify-form {::super/source "(defn- helper [x] x)"})]
      (is (= :defn (::super/form-type result)))
      (is (= "helper" (::super/form-name result)))))

  (testing "def classification"
    (let [result (super/classify-form {::super/source "(def my-val 42)"})]
      (is (= :def (::super/form-type result)))
      (is (= "my-val" (::super/form-name result)))))

  (testing "ns classification"
    (let [result (super/classify-form {::super/source "(ns seon.trading.signals)"})]
      (is (= :ns (::super/form-type result)))
      (is (= "seon.trading.signals" (::super/form-name result)))))

  (testing "require classification"
    (let [result (super/classify-form {::super/source "(require '[clojure.string :as str])"})]
      (is (= :require (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "expression classification"
    (let [result (super/classify-form {::super/source "(+ 1 2)"})]
      (is (= :expression (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "non-list form is expression"
    (let [result (super/classify-form {::super/source "42"})]
      (is (= :expression (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "malformed form falls back to expression"
    (let [result (super/classify-form {::super/source "(defn"})]
      (is (= :expression (::super/form-type result))))))

;;; ---------------------------------------------------------------------------
;;; eval-form! tests (no nREPL port — storage + classification only)
;;; ---------------------------------------------------------------------------

(deftest eval-form-stores-and-versions-test
  (testing "eval-form! stores form in Datalevin"
    (let [result (super/eval-form! {::super/source "(defn ema [period data] (reduce + data))"
                                    ::super/namespace "seon.trading.signals"
                                    ::super/agent-id "a13b"
                                    ::super/conn *test-conn*})]
      (is (= :defn (::super/form-type result)))
      (is (= "ema" (::super/form-name result)))
      (is (= 1 (::super/version result)))
      (is (nil? (::super/result result)))))

  (testing "version increments on re-eval of same form"
    ;; Note: first testing block already stored ema v1, so this continues from v2
    (let [r1 (super/eval-form! {::super/source "(defn ema [period data] (reduce + data))"
                                 ::super/namespace "seon.trading.signals"
                                 ::super/agent-id "a13b"
                                 ::super/conn *test-conn*})
          r2 (super/eval-form! {::super/source "(defn ema [period data] (* 2 (reduce + data)))"
                                 ::super/namespace "seon.trading.signals"
                                 ::super/agent-id "a13b"
                                 ::super/conn *test-conn*})]
      (is (= 2 (::super/version r1)))
      (is (= 3 (::super/version r2)))))

  (testing "expressions always get version 1"
    (let [r1 (super/eval-form! {::super/source "(+ 1 2)"
                                 ::super/namespace "seon.trading.signals"
                                 ::super/agent-id "a13b"
                                 ::super/conn *test-conn*})
          r2 (super/eval-form! {::super/source "(+ 3 4)"
                                 ::super/namespace "seon.trading.signals"
                                 ::super/agent-id "a13b"
                                 ::super/conn *test-conn*})]
      (is (= 1 (::super/version r1)))
      (is (= 1 (::super/version r2))))))

;;; ---------------------------------------------------------------------------
;;; current-forms tests
;;; ---------------------------------------------------------------------------

(deftest current-forms-test
  (testing "returns latest version of each named form"
    ;; Store two versions of ema and one version of sma
    (super/eval-form! {::super/source "(defn ema [p d] (reduce + d))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})
    (super/eval-form! {::super/source "(defn ema [p d] (* 2 (reduce + d)))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})
    (super/eval-form! {::super/source "(defn sma [period data] (/ (reduce + data) period))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})

    (let [forms (super/current-forms {::super/conn *test-conn*
                                      ::super/namespace "seon.trading.signals"})
          by-name (into {} (map (fn [f] [(:form/name f) f]) forms))]
      (is (= 2 (count forms)))
      (is (= 2 (:form/version (get by-name "ema"))))
      (is (= 1 (:form/version (get by-name "sma")))))))

;;; ---------------------------------------------------------------------------
;;; form-history tests
;;; ---------------------------------------------------------------------------

(deftest form-history-test
  (testing "returns all versions sorted ascending"
    (super/eval-form! {::super/source "(defn ema [p d] v1)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})
    (super/eval-form! {::super/source "(defn ema [p d] v2)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})
    (super/eval-form! {::super/source "(defn ema [p d] v3)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})

    (let [history (super/form-history {::super/conn *test-conn*
                                       ::super/namespace "seon.trading.signals"
                                       ::super/form-name "ema"})]
      (is (= 3 (count history)))
      (is (= [1 2 3] (mapv :form/version history))))))

;;; ---------------------------------------------------------------------------
;;; Code index update tests
;;; ---------------------------------------------------------------------------

(deftest code-index-updated-test
  (testing "eval-form! updates the knowledge graph for defn forms"
    (super/eval-form! {::super/source "(defn ema [period data] (reduce + data))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/conn *test-conn*})

    ;; Check that the function was indexed in the knowledge graph
    (let [results (d/q '[:find ?qn
                         :where
                         [?e :seon.fn/qualified-name ?qn]]
                       @*test-conn*)]
      ;; analyzer should have picked up the defn
      (is (seq results) "Knowledge graph should have function entities after eval"))))
