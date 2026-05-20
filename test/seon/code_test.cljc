(ns seon.code-test
  "Examples + structural assertions for `seon.code/check`. The test file
   is `.cljc` and runs unchanged under both `clojure.test` (JVM seat) and
   `cljs.test` (pod / browser seat). When read by an agent, the bodies
   below are the canonical reference for what passes and what doesn't."
  (:require
   #?(:clj  [clojure.test :as t :refer [deftest is testing]]
      :cljs [cljs.test    :as t :refer [deftest is testing] :include-macros true])
   [malli.core :as m]
   [seon.code :as code]))

;;; ---------------------------------------------------------------------------
;;; Canonical examples
;;; ---------------------------------------------------------------------------

(def good-attr-map
  "Docstring → attr-map carrying :malli/schema → args. The shape CLAUDE.md
   prescribes for every public function."
  "(defn foo
     \"Does the thing.\"
     {:malli/schema [:=> [:cat ::req] ::resp]}
     [{::keys [x]}]
     {::y x})")

(def good-name-meta
  "Schema attached to the fn name via ^{...} reader metadata."
  "(defn ^{:malli/schema [:=> [:cat ::req] ::resp]} foo
     [{::keys [x]}]
     {::y x})")

(def good-private
  "defn- (private) is also accepted — privacy is orthogonal to the contract."
  "(defn- foo
     {:malli/schema [:=> [:cat ::req] ::resp]}
     [{::keys [x]}]
     {::y x})")

;;; ---------------------------------------------------------------------------
;;; Passing cases
;;; ---------------------------------------------------------------------------

(deftest good-attr-map-passes
  (let [r (code/check {::code/source good-attr-map})]
    (is (true? (::code/passed? r)))
    (is (nil? (::code/reasons r)))))

(deftest good-name-meta-passes
  (let [r (code/check {::code/source good-name-meta})]
    (is (true? (::code/passed? r)))))

(deftest good-private-passes
  (let [r (code/check {::code/source good-private})]
    (is (true? (::code/passed? r)))))

;;; ---------------------------------------------------------------------------
;;; Failing cases — one rule violated per example, so the reason set is exact.
;;; ---------------------------------------------------------------------------

(deftest missing-schema-fails
  (let [r (code/check {::code/source "(defn foo [{::keys [x]}] {::y x})"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/missing-malli-schema] (::code/reasons r)))))

(deftest non-map-binding-fails
  (let [r (code/check
            {::code/source
             "(defn foo
                {:malli/schema [:=> [:cat ::req] ::resp]}
                [x]
                {::y x})"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/not-map-binding] (::code/reasons r)))))

(deftest wrong-arity-fails
  (let [r (code/check
            {::code/source
             "(defn foo
                {:malli/schema [:=> [:cat ::req] ::resp]}
                [x y]
                {::z (+ x y)})"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/wrong-arity] (::code/reasons r)))))

(deftest multi-arity-fails
  (let [r (code/check
            {::code/source
             "(defn foo
                {:malli/schema [:=> [:cat ::req] ::resp]}
                ([{::keys [x]}] {::y x})
                ([{::keys [x]} _] {::y x}))"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/multi-arity] (::code/reasons r)))))

(deftest plain-keys-fails
  (let [r (code/check
            {::code/source
             "(defn foo
                {:malli/schema [:=> [:cat ::req] ::resp]}
                [{:keys [x y]}]
                {::z x})"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/not-namespaced] (::code/reasons r)))))

(deftest strs-fails
  (let [r (code/check
            {::code/source
             "(defn foo
                {:malli/schema [:=> [:cat ::req] ::resp]}
                [{:strs [x]}]
                {::y x})"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/not-namespaced] (::code/reasons r)))))

(deftest not-defn-fails
  (let [r (code/check {::code/source "(let [x 1] x)"})]
    (is (false? (::code/passed? r)))
    (is (= [::code/not-defn] (::code/reasons r)))))

(deftest multiple-violations-all-reported
  (testing "missing schema + wrong arity should both surface"
    (let [r (code/check {::code/source "(defn foo [x y] (+ x y))"})]
      (is (false? (::code/passed? r)))
      (let [reasons (set (::code/reasons r))]
        (is (contains? reasons ::code/wrong-arity))
        (is (contains? reasons ::code/missing-malli-schema))))))

;;; ---------------------------------------------------------------------------
;;; The ::source schema itself
;;; ---------------------------------------------------------------------------

(deftest source-schema-rejects-unparseable
  (testing "instrumentation boundary — the schema is the parse-check"
    (is (true?  (m/validate ::code/source good-attr-map)))
    (is (false? (m/validate ::code/source "(defn foo [")))
    (is (false? (m/validate ::code/source "")))))

(deftest check-response-shape
  (testing "responses always validate against ::check-response"
    (is (m/validate ::code/check-response
                    (code/check {::code/source good-attr-map})))
    (is (m/validate ::code/check-response
                    (code/check {::code/source "(let [x 1] x)"})))))
