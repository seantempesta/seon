(ns seon.repl.graduate-test
  "Tests for seon.repl.graduate namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.repl :as super]
            [seon.repl.graduate :as grad])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures (same pattern as super_test)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)
(def ^:dynamic *test-dir* nil)

(defn- temp-dir
  "Create a temporary directory."
  []
  (let [dir (File/createTempFile "seon-grad-test" "")]
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
  (let [db-dir (temp-dir)
        out-dir (temp-dir)
        schema (merge ingest/datalevin-schema super/datalevin-schema)
        conn (d/create-conn db-dir schema)]
    (try
      (binding [*test-conn* conn
                *test-dir* out-dir]
        (f))
      (finally
        (d/close conn)
        (delete-dir db-dir)
        (delete-dir out-dir)))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; Helper: store forms quickly
;;; ---------------------------------------------------------------------------

(defn- store! [source]
  (super/eval-form! {::super/source source
                     ::super/namespace "seon.trading.signals"
                     ::super/agent-id "test"
                     ::super/conn *test-conn*}))

;;; ---------------------------------------------------------------------------
;;; ns->file-path tests
;;; ---------------------------------------------------------------------------

(deftest ns->file-path-test
  (testing "basic namespace to file path"
    (is (= "src/seon/trading/signals.clj"
           (grad/ns->file-path {::grad/namespace "seon.trading.signals"}))))

  (testing "hyphenated namespace"
    (is (= "src/seon/my_app/core.clj"
           (grad/ns->file-path {::grad/namespace "seon.my-app.core"}))))

  (testing "cljs target"
    (is (= "src/seon/trading/signals.cljs"
           (grad/ns->file-path {::grad/namespace "seon.trading.signals"
                                ::grad/target :cljs}))))

  (testing "cljc target"
    (is (= "src/seon/trading/signals.cljc"
           (grad/ns->file-path {::grad/namespace "seon.trading.signals"
                                ::grad/target :cljc}))))

  (testing "custom base path"
    (is (= "/tmp/out/seon/trading/signals.clj"
           (grad/ns->file-path {::grad/namespace "seon.trading.signals"
                                ::grad/base-path "/tmp/out"})))))

;;; ---------------------------------------------------------------------------
;;; preview tests
;;; ---------------------------------------------------------------------------

(deftest preview-with-forms-test
  (testing "preview with ns, def, defn produces correct ordering"
    (store! "(defn ema [period data] (reduce + data))")
    (store! "(def window-size 20)")
    (store! "(ns seon.trading.signals (:require [clojure.string :as str]))")

    (let [{::grad/keys [file-content file-path form-count]}
          (grad/preview {::grad/conn *test-conn*
                         ::grad/namespace "seon.trading.signals"
                         ::grad/base-path *test-dir*})]
      (is (= 3 form-count))
      (is (str/ends-with? file-path "seon/trading/signals.clj"))
      ;; ns form comes first
      (is (str/starts-with? file-content "(ns seon.trading.signals"))
      ;; def before defn
      (let [def-idx (str/index-of file-content "(def window-size")
            defn-idx (str/index-of file-content "(defn ema")]
        (is (some? def-idx))
        (is (some? defn-idx))
        (is (< def-idx defn-idx) "def should come before defn")))))

(deftest preview-no-ns-form-test
  (testing "preview generates minimal ns when none stored"
    (store! "(defn ema [p d] (reduce + d))")
    (store! "(def x 1)")

    (let [{::grad/keys [file-content]}
          (grad/preview {::grad/conn *test-conn*
                         ::grad/namespace "seon.trading.signals"
                         ::grad/base-path *test-dir*})]
      (is (str/starts-with? file-content "(ns seon.trading.signals)")))))

;;; ---------------------------------------------------------------------------
;;; graduate! tests
;;; ---------------------------------------------------------------------------

(deftest graduate-writes-file-test
  (testing "graduate! writes file matching preview"
    (store! "(ns seon.trading.signals)")
    (store! "(defn ema [p d] (reduce + d))")

    (let [preview-result (grad/preview {::grad/conn *test-conn*
                                        ::grad/namespace "seon.trading.signals"
                                        ::grad/base-path *test-dir*})
          result (grad/graduate! {::grad/conn *test-conn*
                                  ::grad/namespace "seon.trading.signals"
                                  ::grad/base-path *test-dir*
                                  ::grad/git-commit? false})
          written (slurp (::grad/file-path result))]
      (is (= 2 (::grad/form-count result)))
      (is (false? (::grad/git-committed? result)))
      (is (= (::grad/file-content preview-result) written)))))
