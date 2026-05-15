(ns seon.repl.graduate-test
  "Tests for seon.repl.graduate namespace. Ported in M-2b from the legacy
   datalevin `d/create-conn` + `*direct-mode*` + `*conn-manager*` shape to
   the canonical datahike `:memory` fixture.

   The fixture installs only the form-entity schema; `eval-form!`'s
   internal `update-code-index!` side-effect catches its own exceptions
   (the analyzer + graph-ingest path is currently incompatible with
   datahike's lookup-ref strictness — fix bundled with M-3), so the
   tests that only care about form storage still pass."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [seon.repl :as super]
            [seon.repl.graduate :as grad]
            [seon.test-utils :as tu])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-dir* nil)

(defn- temp-dir
  "Create a temporary directory for graduate file output."
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

(def ^:private graduate-malli-schema
  "Form entity schema — only what `seon.repl/eval-form!` stores."
  [:map
   [:form/id :form/id]
   [:form/namespace :string]
   [:form/type :keyword]
   [:form/name {:optional true} :string]
   [:form/source :string]
   [:form/agent-id :string]
   [:form/version :int]
   [:form/created-at :inst]])

(use-fixtures :each
  (fn [f]
    (let [out-dir (temp-dir)]
      (try
        ((tu/with-test-db-fixture
           {::tu/namespaces [:test-grad]
            ::tu/schemas    {:test-grad graduate-malli-schema}})
         (fn []
           (binding [*test-dir* out-dir]
             (f))))
        (finally
          (delete-dir out-dir))))))

;;; ---------------------------------------------------------------------------
;;; Helper: store forms quickly
;;; ---------------------------------------------------------------------------

(defn- store! [source]
  (super/eval-form! {::super/source source
                     ::super/namespace "seon.trading.signals"
                     ::super/agent-id "test"
                     ::super/db-name :test-grad}))

;;; ---------------------------------------------------------------------------
;;; ns->file-path tests (pure, no fixture)
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
          (grad/preview {::grad/db-name :test-grad
                         ::grad/namespace "seon.trading.signals"
                         ::grad/base-path *test-dir*})]
      (is (= 3 form-count))
      (is (str/ends-with? file-path "seon/trading/signals.clj"))
      (is (str/starts-with? file-content "(ns seon.trading.signals"))
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
          (grad/preview {::grad/db-name :test-grad
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

    (let [preview-result (grad/preview {::grad/db-name :test-grad
                                        ::grad/namespace "seon.trading.signals"
                                        ::grad/base-path *test-dir*})
          result (grad/graduate! {::grad/db-name :test-grad
                                  ::grad/namespace "seon.trading.signals"
                                  ::grad/base-path *test-dir*
                                  ::grad/git-commit? false})
          written (slurp (::grad/file-path result))]
      (is (= 2 (::grad/form-count result)))
      (is (false? (::grad/git-committed? result)))
      (is (= (::grad/file-content preview-result) written)))))
