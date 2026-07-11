(ns seon.repl-parity-test
  "REPL-parity intercepts + REPL-form routing (real-REPL semantics, owner
   rulings 2026-07-10).

   `parity-intercept` now owns exactly ONE translation: bare `*ns*` →
   the current ns as an honest VALUE (probed live 2026-06-09: it
   silently evaluated to nil). `in-ns` is NO LONGER a parity error —
   it is THE movement form, executed by `seon.eval/dispatch-repl-form!`
   together with `alias` / `ns-unmap` / `ns-unalias` ([[repl-form-of]]
   routes them). Behavioral coverage for the forms lives in
   seon.eval.repl-forms-test; this file pins the routing seams.

   There is no `*1 *2 *3` intercept: every successful eval's value is a
   live, addressable `result/<id>` var (subsuming REPL history), so a
   bare `*1` is NOT intercepted — it falls through to a normal eval."
  (:require
    [cljs.test :refer [deftest is testing]]
    ;; Required so their JS ns objects exist on globalThis — `home-ns-alias-hint`
    ;; reads back their publics via `ns-fn-members`; without these loaded the
    ;; `:as`-alias cases would resolve nil.
    [seon.agent.message]
    [my.plan]
    [seon.db]
    [seon.eval :as seval]))

(deftest in-ns-is-a-repl-form-not-a-parity-error
  (testing "parity-intercept no longer owns in-ns"
    (is (nil? (seval/parity-intercept "(in-ns 'kb.docs)" 'my.agent.x))))
  (testing "repl-form-of routes it (with or without the quote)"
    (is (= '(in-ns 'kb.docs) (seval/repl-form-of "(in-ns 'kb.docs)")))
    (is (some? (seval/repl-form-of "(in-ns my.ns)")))))

(deftest form-routing-covers-the-whole-form-surface
  (testing "each REPL form parses to its form"
    (is (some? (seval/repl-form-of "(alias 'a 'clojure.string)")))
    (is (some? (seval/repl-form-of "(ns-unmap 'foo 'bar)")))
    (is (some? (seval/repl-form-of "(ns-unmap 'bar)")))
    (is (some? (seval/repl-form-of "(ns-unalias 'a)"))))
  (testing "ordinary forms are NOT form-routed"
    (is (nil? (seval/repl-form-of "(+ 1 2)")))
    (is (nil? (seval/repl-form-of "(ns foo.bar)")))
    (is (nil? (seval/repl-form-of "(require '[x :as y])"))
        "bare require flows through the NORMAL eval path (ns* op)")
    (is (nil? (seval/repl-form-of "(in-ns-helper 1)"))
        "lookalike heads don't false-positive")
    (is (nil? (seval/repl-form-of "(do (in-ns 'x))"))
        "only a TOP-LEVEL repl form routes")))

(deftest home-ns-alias-hint-resolves-the-correct-aliased-form
  ;; The missing hint (#70 finding 2): a bare function name that failed to resolve maps
  ;; back to the home-ns alias/refer form — derived from home-ns-require-specs,
  ;; never a hardcoded list.
  (testing "an :as-aliased library fn resolves to alias/<name>"
    (is (= "plan/plan!" (seval/home-ns-alias-hint "plan!")))
    (is (= "message/user" (seval/home-ns-alias-hint "user")))
    (is (= "db/transact!" (seval/home-ns-alias-hint "transact!"))))
  (testing "a :refer'd lifecycle fn resolves to its fully-qualified form"
    (is (= "seon.agent.lifecycle/complete" (seval/home-ns-alias-hint "complete")))
    (is (= "seon.agent.lifecycle/wait" (seval/home-ns-alias-hint "wait"))))
  (testing "an unknown name yields no hint"
    (is (nil? (seval/home-ns-alias-hint "totally-made-up-xyz")))))

(deftest bare-star-ns-returns-the-current-ns-as-a-value
  ;; The honest intercept: *ns* IS the ns the form runs in — returning it
  ;; beats both the silent nil and a teaching-only error.
  (let [r (seval/parity-intercept "*ns*" 'my.agent.abc)]
    (is (= :value (:seon.eval/parity r)))
    (is (= 'my.agent.abc (:seon.eval/value r))))
  (let [r (seval/parity-intercept " *ns* " 'other.ns)]
    (is (= 'other.ns (:seon.eval/value r)) "whitespace-trimmed")))

(deftest star-1-2-3-are-not-intercepted
  ;; *1 *2 *3 are gone — value reuse is the `result/<id>` var. A bare
  ;; `*1` is NOT intercepted; it falls through to a normal eval.
  (doseq [s ["*1" "*2" "*3"]]
    (is (nil? (seval/parity-intercept s 'my.agent.x))
        (str s " is not intercepted — falls through to normal eval"))))

(deftest normal-and-embedded-forms-are-not-intercepted
  (testing "ordinary forms pass through untouched"
    (is (nil? (seval/parity-intercept "(+ 1 2)" 'x)))
    (is (nil? (seval/parity-intercept "(ns foo.bar)" 'x)))
    (is (nil? (seval/parity-intercept "(seon.db/query {})" 'x))))
  (testing "only the WHOLE bare *ns* intercepts — embedded uses don't"
    (is (nil? (seval/parity-intercept "(prn *ns*)" 'x)))))
