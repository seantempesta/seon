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
   verbs stay home-only; the `my.*` toolkit stays full-qualified.

   Pins:
   - the pure source rewrite (adds canonical specs, dedupes, no-ops on
     non-agent / complete forms);
   - the LIVE self-host proof: a fn defined in a brand-new agent ns that
     references `db/query` FAILS on a bare `(ns …)` (the bug) and RESOLVES
     through `eval-batch!` (the fix).

   Run via `bin/test-cljs`, or interactively:
     (require 'seon.eval.auto-refer-test :reload)
     (cljs.test/run-tests 'seon.eval.auto-refer-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-int]))

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
        "lifecycle :refer verbs stay home-only — NOT carried over")
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
    (let [home (seval/home-ns-form 'my.agent.x)]
      (is (identical? home (seval/augment-ns-source home))))))

;; ---------------------------------------------------------------------------
;; LIVE self-host: a fn in a NEW agent ns referencing db/query.
;; BEFORE (bare ns, no augmentation) → fails. AFTER (eval-batch!) → resolves.
;; ---------------------------------------------------------------------------

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

(deftest after-new-agent-ns-via-batch-resolves-db-alias
  ;; The fix: the agent's eval-batch path augments the `(ns …)` form, so a
  ;; fn it then defines in that ns resolves `db/query` with no error.
  (async done
    (let [aid    "ar73-2606291200"
          new-ns "my.recall.ar73"
          src    (str "(ns " new-ns ")\n"
                      "(defn db-ok? [] (some? db/query))\n"
                      "(db-ok?)")]
      (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
          (.then (fn [pair]
                   (let [cs   (aget pair 0)
                         conn (aget pair 1)
                         hns  (symbol (str "my.agent." aid))]
                     (-> (seval/setup-agent-ns! cs hns aid)
                         (.then (fn [_]
                                  ;; CLJS dynamic bindings don't cross async
                                  ;; hops — set the root so db/*conn* holds
                                  ;; through eval-batch!'s awaits.
                                  (set! db/*conn* conn)
                                  (seval/eval-batch!
                                    cs (repl-int/parse-forms src) hns aid
                                    (db/new-id!) nil)))
                         (.then (fn [r]
                                  (is (= 0 (:seon.eval/n-fail r))
                                      "no form failed — db/query resolved in the new ns")
                                  (is (= 3 (:seon.eval/n-ok r))
                                      "the ns switch, the defn, and the call all ran")
                                  ;; read the (db-ok?) value back via result/<id>
                                  (let [last-id (last (:seon.eval/ids r))]
                                    (-> (seval/eval cs (str "result/" last-id)
                                                    {:seon.eval/starting-ns (symbol new-ns) :seon.eval/analyze-deps? false})
                                        (.then (fn [r2]
                                                 (is (true? (:seon.eval/value r2))
                                                     "db/query is the live fn — (some? db/query) is true")))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
