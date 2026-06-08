(ns seon.dev.compliance-test
  "Tests for convention compliance checking.

   The compliance bar (rule relaxed 2026-06-08): a public fn is arg-compliant
   iff its :malli/schema FULLY SPECS all args and the return — via EITHER
   map-in/map-out OR named positional :cat/:catn. The violation is an unspecced
   or bare-keyword argument (or :any, or a missing input/output), NOT a
   positional one. These tests pin that behavior with live examples."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ai.gemini :as gemini]
            [seon.dev.compliance :as compliance]
            [seon.dev.context :as context]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Sample functions — exercise the two sanctioned shapes + the violations.
;;; ---------------------------------------------------------------------------

;; Slot schemas for the positional :catn example.
(schema/register! ::sample-a :int)
(schema/register! ::sample-b :string)

(defn sample-positional-specced
  "A FULLY-specced positional :catn fn. Under the new rule this is COMPLIANT —
   every slot is named + specced, the return is specced, no :any."
  {:malli/schema [:=> [:catn [::sample-a ::sample-a] [::sample-b ::sample-b]] :boolean]}
  [a b]
  (boolean (and a b)))

(defn sample-unspecced-args
  "A positional fn with NO :malli/schema at all — unspecced args. FLAGGED."
  [x y]
  (+ x y))

(defn sample-incomplete-any
  "A fn whose :malli/schema uses :any — incomplete. FLAGGED with :incomplete-spec."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (boolean x))

;;; ---------------------------------------------------------------------------
;;; The load-bearing falsification: positional-specced PASSES, unspecced FLAGGED
;;; ---------------------------------------------------------------------------

(deftest positional-fully-specced-passes-test
  (testing "a fully-specced positional :catn fn is compliant (NOT :incomplete-spec)"
    (let [result (compliance/check-function
                  {::compliance/var #'sample-positional-specced})]
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/complete-spec? result))
          "positional :catn with specced slots + return must be complete")
      (is (empty? (::compliance/violations result))
          "a valid positional fn must not be flagged at all")
      (is (not-any? #(= :incomplete-spec (::compliance/violation-type %))
                    (::compliance/violations result)))))

  (testing "a real map-in/map-out fn (gemini/ask) is also compliant"
    (let [result (compliance/check-function {::compliance/var #'gemini/ask})]
      (is (true? (::compliance/complete-spec? result)))
      (is (empty? (::compliance/violations result))))))

(deftest unspecced-args-flagged-test
  (testing "an unspecced-arg fn (no :malli/schema) is flagged"
    (let [result (compliance/check-function
                  {::compliance/var #'sample-unspecced-args})]
      (is (false? (::compliance/complete-spec? result)))
      (is (seq (::compliance/violations result)))
      (is (some #(= :no-malli-schema (::compliance/violation-type %))
                (::compliance/violations result))
          "no schema at all => :no-malli-schema")))

  (testing "a schema present but incomplete (:any) is flagged :incomplete-spec"
    (let [result (compliance/check-function
                  {::compliance/var #'sample-incomplete-any})]
      (is (true? (::compliance/has-schema? result)))
      (is (false? (::compliance/complete-spec? result)))
      (is (some #(= :incomplete-spec (::compliance/violation-type %))
                (::compliance/violations result))
          ":any in the input must trigger :incomplete-spec")
      (is (not-any? #(= :no-malli-schema (::compliance/violation-type %))
                    (::compliance/violations result))
          "schema IS present, so :no-malli-schema must NOT fire"))))

;;; ---------------------------------------------------------------------------
;;; analyze-namespace tests
;;; ---------------------------------------------------------------------------

(deftest analyze-namespace-test
  (testing "analyzes a compliant namespace, returns the new shape"
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.ai.gemini})]
      (is (map? result))
      (is (contains? result ::compliance/compliant?))
      (is (contains? result ::compliance/violations))
      (is (contains? result ::compliance/public-fns))
      (is (contains? result ::compliance/with-schema))
      (is (contains? result ::compliance/with-complete-specs))
      (is (pos? (::compliance/public-fns result)))
      (is (pos? (::compliance/with-schema result)))
      ;; map-in/map-out fns count as fully specced under the new rule.
      (is (pos? (::compliance/with-complete-specs result)))
      (is (<= (::compliance/with-complete-specs result)
              (::compliance/public-fns result)))))

  (testing "positional db ops are NOT flagged for being positional"
    ;; seon.db ops are positional; they may still be flagged :incomplete-spec
    ;; (some use :any pending T15), but NEVER for the mere fact of being
    ;; positional. There is no positional-shape violation type anymore.
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.db})
          vtypes (set (map ::compliance/violation-type
                           (::compliance/violations result)))]
      (is (map? result))
      (is (pos? (::compliance/public-fns result)))
      ;; The old :no-map-in violation type is gone entirely.
      (is (not (contains? vtypes :no-map-in)))))

  (testing "handles namespace as symbol"
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.schema})]
      (is (map? result))
      (is (integer? (::compliance/public-fns result))))))

;;; ---------------------------------------------------------------------------
;;; check-function tests
;;; ---------------------------------------------------------------------------

(deftest check-function-test
  (testing "checks a compliant map-in/map-out function"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (= "ask" (::compliance/fn-name result)))
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/has-docstring? result)))
      (is (true? (::compliance/complete-spec? result)))
      (is (empty? (::compliance/violations result)))))

  (testing "checks a function lacking :malli/schema (schema/register!)"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.schema/register!})]
      (is (= "register!" (::compliance/fn-name result)))
      (is (true? (::compliance/has-docstring? result)))
      (is (false? (::compliance/complete-spec? result)))
      (is (seq (::compliance/violations result)))
      ;; Lacking a schema => :no-malli-schema (NOT the retired :no-map-in).
      (is (some #(= :no-malli-schema (::compliance/violation-type %))
                (::compliance/violations result)))))

  (testing "detects docstring presence on a compliant fn"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.dev.context/record-edit!})]
      (is (true? (::compliance/has-docstring? result)))
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/complete-spec? result)))
      (is (empty? (::compliance/violations result))))))

;;; ---------------------------------------------------------------------------
;;; format-violations tests
;;; ---------------------------------------------------------------------------

(deftest format-violations-test
  (testing "formats empty violations"
    (let [result (compliance/format-violations
                  {::compliance/violations []})]
      (is (= "All functions comply with conventions."
             (::compliance/formatted result)))))

  (testing "formats single violation"
    (let [violations [{::compliance/fn-name "foo"
                       ::compliance/violation-type :no-malli-schema
                       ::compliance/message "foo is missing :malli/schema"}]
          result (compliance/format-violations
                  {::compliance/violations violations})]
      (is (string? (::compliance/formatted result)))
      (is (.contains (::compliance/formatted result) "Convention violations:"))
      (is (.contains (::compliance/formatted result) "foo"))
      (is (.contains (::compliance/formatted result) "missing :malli/schema"))))

  (testing "formats an :incomplete-spec violation"
    (let [violations [{::compliance/fn-name "bar"
                       ::compliance/violation-type :no-malli-schema
                       ::compliance/message "bar is missing schema"}
                      {::compliance/fn-name "baz"
                       ::compliance/violation-type :incomplete-spec
                       ::compliance/message "baz incomplete spec"}]
          result (compliance/format-violations
                  {::compliance/violations violations})]
      (is (.contains (::compliance/formatted result) "missing :malli/schema"))
      (is (.contains (::compliance/formatted result) "incomplete arg/return spec"))))

  (testing "respects max-length"
    (let [violations (vec (for [i (range 50)]
                            {::compliance/fn-name (str "function" i)
                             ::compliance/violation-type :no-malli-schema
                             ::compliance/message (str "function" i " missing schema")}))
          result (compliance/format-violations
                  {::compliance/violations violations
                   ::compliance/max-length 100})]
      (is (<= (count (::compliance/formatted result)) 112))  ; 100 + "[truncated]"
      (is (.contains (::compliance/formatted result) "[truncated]")))))

;;; ---------------------------------------------------------------------------
;;; compliance-summary tests
;;; ---------------------------------------------------------------------------

(deftest compliance-summary-test
  (testing "returns summary map with the new wording"
    (let [result (compliance/compliance-summary
                  {::compliance/namespace 'seon.ai.gemini})]
      (is (map? result))
      (is (string? (::compliance/summary result)))
      (is (.contains (::compliance/summary result) "compliant"))
      (is (.contains (::compliance/summary result) "with schema"))
      (is (.contains (::compliance/summary result) "fully specced"))
      (is (integer? (::compliance/compliant-count result)))
      (is (integer? (::compliance/total-count result))))))

;;; ---------------------------------------------------------------------------
;;; Deep schema verification tests
;;; ---------------------------------------------------------------------------

(deftest deep-schema-verification-test
  (testing "extracts schema refs from :malli/schema metadata"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (empty? (filter #(= :unregistered-schema (::compliance/violation-type %))
                          (::compliance/violations result))))))

  (testing "map-in fn (gemini/ask) follows the request/response naming convention"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (empty? (filter #(= :wrong-naming (::compliance/violation-type %))
                          (::compliance/violations result))))))

  (testing "positional :catn fn is NOT subject to the request/response naming check"
    ;; The naming convention is a map-in concept; positional fns name slots,
    ;; so they must never be flagged :wrong-naming.
    (let [result (compliance/check-function
                  {::compliance/var #'sample-positional-specced})]
      (is (empty? (filter #(= :wrong-naming (::compliance/violation-type %))
                          (::compliance/violations result)))))))

;;; ---------------------------------------------------------------------------
;;; Fix generation tests
;;; ---------------------------------------------------------------------------

(deftest generate-fix-test
  (testing "generate-fix returns compliant? true for a compliant function"
    (let [result (compliance/generate-fix
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (true? (::compliance/compliant? result)))
      (is (nil? (::compliance/fix-code result)))))

  (testing "generate-fix returns fix code for a non-compliant function"
    (let [result (compliance/generate-fix
                  {::compliance/var #'sample-unspecced-args})]
      (is (false? (::compliance/compliant? result)))
      (is (string? (::compliance/fix-code result)))
      (is (.contains (::compliance/fix-code result) "needs"))
      ;; The fix offers BOTH sanctioned shapes and must never emit :any.
      (is (.contains (::compliance/fix-code result) "map-in"))
      (is (.contains (::compliance/fix-code result) ":catn"))
      (is (not (.contains (::compliance/fix-code result) ":any")))))

  (testing "format-violations with-fixes generates actionable, :any-free code"
    (let [formatted (compliance/format-violations
                     {::compliance/violations
                      (::compliance/violations
                       (compliance/check-function
                        {::compliance/var #'sample-incomplete-any}))
                      ::compliance/with-fixes true
                      ::compliance/max-length 2000})]
      (is (string? (::compliance/formatted formatted)))
      (is (.contains (::compliance/formatted formatted) "needs"))
      (is (not (.contains (::compliance/formatted formatted) ":any"))))))
