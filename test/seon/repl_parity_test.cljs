(ns seon.repl-parity-test
  "Unit #23 fix d — REPL-parity translations (the plan's REPL-PARITY
   CONTRACT): the agent's reflexive REPL moves must work or fail with a
   translation that teaches the substrate equivalent.

   Probed live 2026-06-09 before this unit: `(in-ns 'foo)` failed with an
   opaque `undeclared-var cljs.user/in-ns`; bare `*ns*` and `*1` both
   SILENTLY evaluated to nil. `seon.eval/parity-intercept` is the
   form-level pre-check in `eval-batch!` that replaces those outcomes:

     (in-ns 'foo) → legible error teaching (ns foo)
     *ns*         → intercepted VALUE: the current ns symbol
     *1 *2 *3     → legible error teaching (result :<eval-id>)

   These are pure unit tests on the intercept; the end-to-end path
   (record + transcript) was REPL-verified against a scratch conn and is
   covered by the eval-batch! suites."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [seon.eval :as seval]))

(deftest in-ns-translates-to-a-teaching-error
  (let [r (seval/parity-intercept "(in-ns 'kb.docs)" 'seon.agent.x)]
    (is (= :error (:seon.eval/parity r)))
    (testing "names the exact replacement, with the agent's own target ns"
      (is (str/includes? (:seon.error/message r) "in-ns is not available"))
      (is (str/includes? (:seon.error/message r) "(ns kb.docs)"))))
  (testing "unquoted / whitespace variants still intercept"
    (let [r (seval/parity-intercept "  (in-ns  my.ns)" 'seon.agent.x)]
      (is (= :error (:seon.eval/parity r)))
      (is (str/includes? (:seon.error/message r) "(ns my.ns)")))))

(deftest bare-star-ns-returns-the-current-ns-as-a-value
  ;; The honest intercept: *ns* IS the ns the form runs in — returning it
  ;; beats both the silent nil and a teaching-only error.
  (let [r (seval/parity-intercept "*ns*" 'seon.agent.abc)]
    (is (= :value (:seon.eval/parity r)))
    (is (= 'seon.agent.abc (:seon.eval/value r))))
  (let [r (seval/parity-intercept " *ns* " 'other.ns)]
    (is (= 'other.ns (:seon.eval/value r)) "whitespace-trimmed")))

(deftest star-1-2-3-teach-the-result-accessor
  (doseq [s ["*1" "*2" "*3"]]
    (let [r (seval/parity-intercept s 'seon.agent.x)]
      (is (= :error (:seon.eval/parity r)) (str s " intercepted"))
      (is (str/includes? (:seon.error/message r) "(result :<eval-id>)")
          (str s " teaches the durable replacement")))))

(deftest normal-and-embedded-forms-are-not-intercepted
  (testing "ordinary forms pass through untouched"
    (is (nil? (seval/parity-intercept "(+ 1 2)" 'x)))
    (is (nil? (seval/parity-intercept "(ns foo.bar)" 'x)))
    (is (nil? (seval/parity-intercept "(seon.db/query {})" 'x))))
  (testing "only the WHOLE bare form intercepts — embedded uses don't"
    (is (nil? (seval/parity-intercept "(str *1)" 'x)))
    (is (nil? (seval/parity-intercept "(prn *ns*)" 'x))))
  (testing "lookalike symbols don't false-positive"
    (is (nil? (seval/parity-intercept "(in-ns-helper 1)" 'x)))
    (is (nil? (seval/parity-intercept "*1-counter" 'x)))))
