(ns seon.repl-parity-test
  "Unit #23 fix d — REPL-parity translations (the plan's REPL-PARITY
   CONTRACT): the agent's reflexive REPL moves must work or fail with a
   translation that teaches the core equivalent.

   Probed live 2026-06-09 before this unit: `(in-ns 'foo)` failed with an
   opaque `undeclared-var cljs.user/in-ns`; bare `*ns*` SILENTLY evaluated
   to nil. `seon.eval/parity-intercept` is the form-level pre-check in
   `eval-batch!` that replaces those outcomes:

     (in-ns 'foo) → legible error teaching (ns foo)
     *ns*         → intercepted VALUE: the current ns symbol

   There is no `*1 *2 *3` intercept: every successful eval's value is a
   live, addressable `result/<id>` var (subsuming REPL history), so a
   bare `*1` is NOT intercepted — it falls through to a normal eval.

   These are pure unit tests on the intercept; the end-to-end path
   (record + transcript) was REPL-verified against a scratch conn and is
   covered by the eval-batch! suites."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    ;; Required so their JS ns objects exist on globalThis — `home-ns-alias-hint`
    ;; reads back their publics via `ns-fn-members`; without these loaded the
    ;; `:as`-alias cases would resolve nil.
    [seon.agent.message]
    [seon.agent.todo]
    [seon.db]
    [seon.eval :as seval]))

(deftest in-ns-translates-to-a-teaching-error
  (let [r (seval/parity-intercept "(in-ns 'kb.docs)" 'my.agent.x)]
    (is (= :error (:seon.eval/parity r)))
    (testing "names the exact replacement, with the agent's own target ns"
      (is (str/includes? (:seon.error/message r) "in-ns is not available"))
      (is (str/includes? (:seon.error/message r) "(ns kb.docs)"))))
  (testing "unquoted / whitespace variants still intercept"
    (let [r (seval/parity-intercept "  (in-ns  my.ns)" 'my.agent.x)]
      (is (= :error (:seon.eval/parity r)))
      (is (str/includes? (:seon.error/message r) "(ns my.ns)")))))

(deftest in-ns-steers-to-the-alias-not-a-destructive-switch
  ;; The load-bearing fix (#70): an agent reaching `(in-ns 'seon.agent.todo)`
  ;; to CALL a verb must be steered to `todo/<verb>`, NOT advised to switch
  ;; namespace (which strips its home-ns message/wait/complete aliases).
  (let [msg (:seon.error/message
              (seval/parity-intercept "(in-ns 'seon.agent.todo)" 'my.agent.x))]
    (testing "names the home-ns alias for the target ns"
      (is (str/includes? msg "todo/")))
    (testing "tells the agent NOT to switch namespace to call a verb"
      (is (str/includes? msg "do NOT need to switch namespace")))
    (testing "still names (ns …)-switch as the DEFINE-only path"
      (is (str/includes? msg "(ns seon.agent.todo)"))
      (is (str/includes? msg "DEFINE")))
    (testing "warns the switch replaces home aliases"
      (is (str/includes? msg "REPLACES your home aliases"))))
  (testing "an un-aliased target falls back to generic alias guidance"
    (let [msg (:seon.error/message
                (seval/parity-intercept "(in-ns 'foo.bar)" 'my.agent.x))]
      (is (str/includes? msg "home-ns alias"))
      (is (str/includes? msg "(ns foo.bar)")))))

(deftest home-ns-alias-hint-resolves-the-correct-aliased-form
  ;; The missing hint (#70 finding 2): a bare verb that failed to resolve maps
  ;; back to the home-ns alias/refer form — derived from home-ns-require-specs,
  ;; never a hardcoded list.
  (testing "an :as-aliased library verb resolves to alias/<verb>"
    (is (= "todo/plan!" (seval/home-ns-alias-hint "plan!")))
    (is (= "message/user" (seval/home-ns-alias-hint "user")))
    (is (= "db/transact!" (seval/home-ns-alias-hint "transact!"))))
  (testing "a :refer'd lifecycle verb resolves to its fully-qualified form"
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
    (is (nil? (seval/parity-intercept "(prn *ns*)" 'x))))
  (testing "lookalike symbols don't false-positive"
    (is (nil? (seval/parity-intercept "(in-ns-helper 1)" 'x)))))
