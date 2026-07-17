(ns seon.eval.auto-refer-test
  "Real requires (#73/#56): the canonical short aliases (`db`→seon.db,
   `plan`→my.plan, `message`→seon.agent.message, `schema`→seon.schema)
   are established ONLY in the agent's HOME ns by `setup-agent-ns!`. When an
   agent authors a NEW `my.*` ns and a fn there reaches for the
   `db/`/`message/`/`plan/` aliases it SEES in its home-ns workspace, those
   aliases were NOT in scope → `db/transact! is not defined` (~60 such errors
   per fn-authoring drive).

   `seon.eval/augment-ns-source` closes the footgun: when the agent's
   eval-batch path sees a NEW agent-authored `(ns …)` form, it writes the
   canonical aliases into its REAL `:require` clause (the ones the agent
   didn't already claim) so the new ns resolves `db/query` exactly as the
   home ns does — no magic injection. `seon.agent` and the lifecycle `:refer`
   functions stay home-only; the `my.*` toolkit stays full-qualified.

   Pins:
   - the pure source rewrite (adds canonical specs, dedupes, no-ops on
     non-agent / complete forms);
   - the LIVE self-host proof: a fn defined in a brand-new agent ns that
     references `db/query` FAILS on a bare `(ns …)` (the bug) and RESOLVES
     after the one source augmentation (the fix).

   Run via `bin/test-cljs`, or interactively:
     (require 'seon.eval.auto-refer-test :reload)
     (cljs.test/run-tests 'seon.eval.auto-refer-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [my.blob]
    [my.canvas]
    [my.data]
    [my.kb]
    [my.ns]
    [my.plan]
    [my.skills]
    [my.ui]
    [seon.agent]
    [seon.agent.fs]
    [seon.agent.home :as home]
    [seon.agent.lifecycle]
    [seon.agent.message]
    [seon.agent.search]
    [seon.agent.shell]
    [seon.agent.web]
    [seon.config :as config]
    [seon.db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.schema]))

;; ---------------------------------------------------------------------------
;; Pure: augment-ns-source — string in, string out. No compile-state needed.
;; ---------------------------------------------------------------------------

(deftest augment-adds-canonical-aliases-to-bare-agent-ns
  (let [out (seval/augment-ns-source "(ns my.recall)")]
    (is (not= out "(ns my.recall)") "a bare agent ns is rewritten")
    (is (str/includes? out "[seon.db :as db]")
        "db alias is auto-required")
    (is (str/includes? out "[seon.agent.message :as message]")
        "message alias is auto-required")
    (is (str/includes? out "[my.plan :as plan]")
        "plan alias is auto-required")
    (is (str/includes? out "[seon.schema :as schema]"))
    (is (not (str/includes? out "[seon.agent :as agent]"))
        "seon.agent is HOME-only — NOT added to an authored ns")
    (is (not (str/includes? out "seon.agent.lifecycle"))
        "lifecycle :refer functions stay home-only — NOT carried over")
    (is (str/starts-with? out "(ns my.recall")
        "the ns NAME is preserved")))

(deftest augment-does-not-duplicate-the-agents-own-requires
  (let [out (seval/augment-ns-source
              "(ns my.recall (:require [seon.db :as db] [my.kb :as kb]))")]
    (is (= 1 (count (re-seq #"\[seon\.db :as db\]" out)))
        "the agent's own [seon.db :as db] is NOT duplicated")
    (is (str/includes? out "[my.kb :as kb]")
        "the agent's own non-canonical require is preserved")
    (is (str/includes? out "[seon.agent.message :as message]")
        "the still-missing canonical aliases are added alongside")))

(deftest augment-respects-a-conflicting-alias
  ;; The agent aliased `db` to something else — we must NOT add a second
  ;; `:as db` (duplicate-alias analyzer error); the agent's binding wins.
  (let [out (seval/augment-ns-source
              "(ns my.recall (:require [my.kb :as db]))")]
    (is (str/includes? out "[my.kb :as db]") "agent's db alias preserved")
    (is (not (str/includes? out "[seon.db :as db]"))
        "no duplicate db alias is injected")
    (is (str/includes? out "[my.plan :as plan]")
        "non-conflicting canonical aliases still added")))

(deftest augment-is-a-no-op-on-non-agent-and-non-ns-forms
  (testing "a non-ns form is returned identical"
    (let [src "(+ 1 2)"]
      (is (identical? src (seval/augment-ns-source src)))))
  (testing "transient scaffolding nses are left alone"
    (let [src "(ns cljs.user)"]
      (is (identical? src (seval/augment-ns-source src)))))
  (testing "a multi-form source is left alone (only a lone ns form is rewritten)"
    (let [src "(ns my.recall) (defn f [] 1)"]
      (is (identical? src (seval/augment-ns-source src)))))
  (testing "an already-complete ns form is a no-op (nothing to add)"
    (let [home-source (home/home-ns-form 'my.agent.x)]
      (is (identical? home-source (seval/augment-ns-source home-source))))))

;; ---------------------------------------------------------------------------
;; LIVE self-host: a fn in a NEW agent ns referencing db/query.
;; BEFORE (bare ns, no augmentation) → fails. AFTER (eval-batch!) → resolves.
;; ---------------------------------------------------------------------------

(deftest fresh-bootstrap-seeds-the-resolved-root-refers
  (async done
    (let [root-requires (:seon.eval/home-requires
                          (config/resolve-agent-context "root" nil))
          root-spec '[seon.agent :refer [start! delegate! set-purpose!]]
          !cs (atom nil)]
      (is (some #(= root-spec %) root-requires)
          "the test exercises the exact configured root orchestration edge")
      (-> (seval/init-bootstrap!)
          (.then
            (fn [cs]
              (reset! !cs cs)
              (with-redefs [home/home-requires-for
                            (fn
                              ([_] (js/Promise.resolve root-requires))
                              ([_database _id]
                               (js/Promise.resolve root-requires)))]
                (seval/setup-agent-ns! cs 'my.agent.root "root"))))
          (.then
            (fn [_]
              (let [cs @!cs
                    uses (get-in @cs
                                 [:cljs.analyzer/namespaces
                                  'my.agent.root :uses])]
                (is (= {'start! 'seon.agent
                        'delegate! 'seon.agent
                        'set-purpose! 'seon.agent}
                       (select-keys uses
                                    '[start! delegate! set-purpose!])))
                (seval/eval
                  cs
                  "(every? fn? [start! delegate! set-purpose!])"
                  {:seon.eval/starting-ns 'my.agent.root
                   :seon.eval/analyze-deps? false}))))
          (.then (fn [result]
                   (is (true? (:seon.eval/ok? result)))
                   (is (true? (:seon.eval/value result)))))
          (.then
            (fn [_]
              (with-redefs
                [home/home-requires-for
                 (fn
                   ([_]
                    (js/Promise.resolve
                      '[[seon.agent :refer [not-a-real-seon-agent-var]]]))
                   ([_database _id]
                    (js/Promise.resolve
                      '[[seon.agent :refer [not-a-real-seon-agent-var]]])))]
                (-> (seval/setup-agent-ns!
                      @!cs 'my.agent.invalid-root "invalid-root")
                    (.then (fn [_]
                             (is false "a nonexistent referred var must fail")))
                    (.catch
                      (fn [error]
                        (is (str/includes? (str error)
                                           "setup-agent-ns! failed"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest before-bare-agent-ns-cannot-resolve-db-alias
  ;; Reproduces the #73 bug on the real self-host: a bare `(ns …)` form (NO
  ;; augmentation — `seval/eval` does not augment) leaves no `db` alias, so a
  ;; defn body referencing `db/query` is an undeclared-var compile error.
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then (fn [cs]
                 (-> (seval/eval cs "(ns scratch.before-73)"
                                 {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})
                     (.then (fn [_]
                              (seval/eval cs "(defn db-ok? [] (some? db/query))"
                                          {:seon.eval/starting-ns 'scratch.before-73 :seon.eval/analyze-deps? false})))
                     (.then (fn [r]
                              (is (not (:seon.eval/ok? r))
                                  "without the canonical alias, db/query does not resolve")
                              (is (str/includes? (str (:seon/error r)) "db/query")
                                  "the error names the unresolved db/query"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest after-augmented-agent-ns-resolves-db-alias
  ;; The fix: the one source augmentation gives the self-host compiler the
  ;; exact `(ns …)` form eval-batch uses, so `db/query` resolves with no DB
  ;; fixture or second namespace setup path.
  (async done
    (let [new-ns 'my.recall.ar73]
      (-> (repl/ensure-bootstrap!)
          (.then
            (fn [cs]
              (-> (seval/eval
                    cs
                    (seval/augment-ns-source (str "(ns " new-ns ")"))
                    {:seon.eval/starting-ns 'cljs.user
                     :seon.eval/analyze-deps? true})
                  (.then
                    (fn [result]
                      (is (true? (:seon.eval/ok? result)))
                      (seval/eval
                        cs
                        "(defn db-ok? [] (some? db/query))"
                        {:seon.eval/starting-ns new-ns
                         :seon.eval/analyze-deps? false})))
                  (.then
                    (fn [result]
                      (is (true? (:seon.eval/ok? result)))
                      (seval/eval
                        cs "(db-ok?)"
                        {:seon.eval/starting-ns new-ns
                         :seon.eval/analyze-deps? false})))
                  (.then
                    (fn [result]
                      (is (true? (:seon.eval/ok? result)))
                      (is (true? (:seon.eval/value result))
                          "db/query is the live function in the authored ns"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
