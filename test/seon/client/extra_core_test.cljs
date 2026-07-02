(ns seon.client.extra-core-test
  "Extra-core registration (task #36 — SEON_EXTRA_SRC): downstream
   vars registered into `seon.client/!extra-core-vars` index, render
   full-source, replay-skip like the core's own; reserved-prefix
   (seon.* / my.*) extras are refused LOUDLY at boot-index time; vars the
   downstream's macro expansion shares with the core dedup silently.

   Uses the committed `acme.extra-fixture` ns (under seon's test/ root)
   as the stand-in downstream namespace — no env var or external
   checkout needed; the registration call is exactly what a downstream
   entry ns does with `(seon.indexing/public-fn-vars)`.

   Spec: docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md §d."
  (:require [acme.extra-fixture]
            [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.client :as client]
            [seon.db :as db]
            [seon.eval :as seval]
            [seon.repl :as repl]))

;; ---------------------------------------------------------------------------
;; The capability, end-to-end. "An agent owns and evolves its own functions"
;; has two pillars; this one deftest exercises BOTH:
;;
;;   (1) DOWNSTREAM/CONSUMER FNS GET INDEXED — a fn registered as an extra-core
;;       var surfaces in the boot index with its REAL source + spec, and its
;;       owning ns joins the replay-skip set (treated as indexed core).
;;       (HALF 1 — formerly the standalone `extra-core-vars-join-the-boot-index`
;;       deftest, folded in here verbatim.)
;;
;;   (2) A FN IS OVERRIDABLE AND THE OVERRIDE FLOWS THROUGH A LATE-BOUND CALLER
;;       — redefine (`(defn …)` upsert) and `(set! …)` of a callee both reach
;;       an EXISTING compiled caller that reads the callee through the global
;;       var slot. (HALF 2.)
;;
;; The override's REAL surface is the RUNTIME compile-state: the agent overrides
;; by evaluating `(defn …)` / `(set! …)` through `seon.eval/eval` →
;; `cljs.js/eval-str` → `goog.globalEval` into GLOBAL scope. That path reads/
;; writes the global var slot (`probe.demo.greeting`) and is `:static-fns`-immune
;; at every optimization level (CLJS emits every cross-ns var reference as a live
;; global property read; `set!`/redefine writes that exact property). So HALF 2
;; MUST exercise override through that runtime path, NOT through the test
;; bundle's own compiled namespaces — `probe.demo` is a test-local reconstituted
;; ns (mirroring `src/seon/demo.cljs`'s shape under a fresh name so start-agent
;; core-skip never excludes it), loaded via `replay-program-graph!` exactly like
;; agent-authored corpus, then overridden through `seon.eval/eval`.
;;
;; DEV `:none` is the level the suite runs (`bin/test-cljs` → `compile test` →
;; `:dev` → `:none`) and the level the live pod runs. Late binding is
;; source-proven robust at `:none`/`:simple`; `:advanced` is the ONLY level that
;; breaks it (Closure DCE/inlining can sever a mutated global slot) and is
;; explicitly out of scope. `:simple` build work is DEFERRED until dev is proven.
;; Mechanism + per-level verdict:
;; docs/prds/agent-runtime/research/shadow-late-binding-and-extra-src-2026-06-21.md
;; ---------------------------------------------------------------------------

(deftest downstream-fn-is-indexed-and-its-override-flows-through-a-late-bound-caller
  (async done
    (let [before @client/!extra-core-vars]
      (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
          (.then
            (fn [res]
              (let [cs   (aget res 0)
                    conn (aget res 1)]
                (binding [db/*conn* conn]
                  ;; ===== HALF 1 — DOWNSTREAM FN IS INDEXED =====
                  (reset! client/!extra-core-vars [#'acme.extra-fixture/echo-greeting])
                  (let [rows   (client/index-core!)
                        fn-row (some #(when (= "acme.extra-fixture/echo-greeting"
                                               (:seon.fn/sym %)) %)
                                     rows)]
                    (testing "the downstream fn surfaces in the boot index (agent can SEE it)"
                      (is (some? fn-row)))
                    (testing "with its REAL source"
                      (is (str/includes? (:seon.fn/source fn-row "") "defn echo-greeting")))
                    (testing "and its REAL spec"
                      (is (some? (:seon.fn/spec fn-row))))
                    (testing "the extra ns joins the replay-skip set (treated as indexed core)"
                      (is (contains? (client/core-ns-set) :acme.extra-fixture))))
                  ;; ===== HALF 2 — OVERRIDE FLOWS THROUGH A LATE-BOUND CALLER =====
                  ;; A test-local reconstituted ns `probe.demo` mirrors seon.demo:
                  ;; a callee `greeting` and a late-bound caller `greet-loudly` that
                  ;; reads `greeting` through the global var slot. Loaded via the
                  ;; agent-corpus replay path, then overridden through the RUNTIME
                  ;; compile-state (the agent's real, optimization-immune path).
                  (-> (db/transact!
                        {:seon.db/tx-data
                         [{:seon.ns/name   :probe.demo
                           :seon.ns/source "(ns probe.demo)"}
                          {:seon.fn/sym        "probe.demo/greeting"
                           :seon.fn/ns         {:seon.ns/name :probe.demo}
                           :seon.fn/source     "(defn greeting [] \"hello from core\")"
                           :seon.fn/created-at (js/Date.)}
                          {:seon.fn/sym        "probe.demo/greet-loudly"
                           :seon.fn/ns         {:seon.ns/name :probe.demo}
                           :seon.fn/source     "(defn greet-loudly [] (str (greeting) \"!\"))"
                           :seon.fn/created-at (js/Date.)}]})
                      (.then
                        (fn [_]
                          (client/replay-program-graph!
                            {:conn conn :compile-state cs :agent-id "extra-core-test"})))
                      (.then
                        (fn [stats]
                          (testing "the reconstituted ns replays cleanly through the agent-corpus lane"
                            (is (= 0 (:seon.client/replay-n-fail stats))
                                (str "replay had failures — " (pr-str stats))))
                          ;; BASELINE — caller reflects the original callee.
                          (seval/eval cs "(probe.demo/greet-loudly)"
                                      {:ns 'cljs.user :analyze-deps? false})))
                      (.then
                        (fn [r]
                          (testing "BASELINE: late-bound caller reflects the original callee"
                            (is (:ok r) (str "baseline eval not ok — " (pr-str (:error r))))
                            (is (= "hello from core!" (:value r))))
                          ;; REDEFINE — the converged redefine=upsert path: re-eval
                          ;; `(defn greeting …)` IN ns probe.demo (live-proven on the
                          ;; pod against these exact rows, 2026-06-21).
                          (seval/eval cs "(defn greeting [] \"redefined\")"
                                      {:ns 'probe.demo :analyze-deps? false})))
                      (.then
                        (fn [r]
                          (testing "the redefine evals cleanly in ns probe.demo"
                            (is (:ok r) (str "redefine eval not ok — " (pr-str (:error r)))))
                          (seval/eval cs "(probe.demo/greet-loudly)"
                                      {:ns 'cljs.user :analyze-deps? false})))
                      (.then
                        (fn [r]
                          (testing "REDEFINE flows through the UNCHANGED late-bound caller"
                            (is (:ok r) (str "post-redefine eval not ok — " (pr-str (:error r))))
                            (is (= "redefined!" (:value r))))
                          ;; SET! — reassign the callee's global slot directly.
                          (seval/eval cs "(set! probe.demo/greeting (fn [] \"set-bang\"))"
                                      {:ns 'cljs.user :analyze-deps? false})))
                      (.then
                        (fn [r]
                          (testing "the set! evals cleanly"
                            (is (:ok r) (str "set! eval not ok — " (pr-str (:error r)))))
                          (seval/eval cs "(probe.demo/greet-loudly)"
                                      {:ns 'cljs.user :analyze-deps? false})))
                      (.then
                        (fn [r]
                          (testing "SET! flows through the UNCHANGED late-bound caller"
                            (is (:ok r) (str "post-set! eval not ok — " (pr-str (:error r))))
                            (is (= "set-bang!" (:value r)))))))))))
          (.then (fn [_]
                   (reset! client/!extra-core-vars before)
                   (done)))
          (.catch (fn [e]
                    (reset! client/!extra-core-vars before)
                    (is false (str "threw — " e))
                    (done)))))))

(defn guard-bait
  "A specced fn in a `seon.*` ns that is NOT in `core-vars` (test
   nses are outside seon.client's require closure) — registering it as
   an extra var must trip the reserved-prefix refusal."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  s)

(deftest reserved-prefix-extra-registration-refused-at-boot-index
  (let [before @client/!extra-core-vars]
    (try
      (reset! client/!extra-core-vars [#'guard-bait])
      (let [err (try (client/index-core!) nil
                     (catch :default e e))]
        (is (some? err)
            "index-core! must THROW on a reserved-prefix extra ns")
        (is (str/includes? (str (ex-message err))
                           "seon.client.extra-core-test")
            "the refusal names the offending ns")
        (is (= ["seon.client.extra-core-test"]
               (:seon.client/reserved-extra-nses (ex-data err)))))
      (finally
        (reset! client/!extra-core-vars before)))))

(deftest core-overlap-dedups-silently
  ;; A downstream entry's (public-fn-vars) expansion sees the seon
  ;; surface its require closure pulls in. Those vars dedup away by
  ;; fully-qualified sym BEFORE the reserved-prefix guard runs — no
  ;; throw, no duplicate rows.
  (let [before @client/!extra-core-vars]
    (try
      (reset! client/!extra-core-vars [#'seon.db/transact!])
      (let [rows (client/index-core!)]
        (is (= 1 (count (filter #(= "seon.db/transact!" (:seon.fn/sym %))
                                rows)))
            "exactly one row for the overlapping var — and no guard throw"))
      (finally
        (reset! client/!extra-core-vars before)))))
