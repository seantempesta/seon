(ns seon.eval.prose-demote-test
  "#88 — demote all-bare-word parentheticals to prose. When an agent writes
   English prose with parens — `(June 3 before June 14)`, `(results look
   fine)` — the reader hands it to eval, the head throws \"not defined\", the
   live agent errors, and the eval-error rate inflates with non-errors.
   `seon.eval/dispatch-eval-entry!` now DEMOTES such a form to a prose row:
   recorded ok?, never evaluated, counted as neither n-ok nor n-fail.

   The two-sided gate is the heart of this file: an exhaustive DEMOTE matrix
   (prose → no error row, no eval) and an exhaustive KEEP matrix (real code →
   eval + count exactly as before), including the documented over-demotion
   boundary `(undefined-fn 1 2)` (KEPT — a plausible typo'd call).

   Every test opens a FRESH `:memory` conn and drives the REAL bootstrap
   compile-state via `repl/ensure-bootstrap!` → `parse-forms` →
   `eval-batch!`, then reads the recorded `:seon.eval` rows back out. Every
   async tail routes through `seon.test.async/settle!` (the #44/#41 hang-proof
   terminal) — never a hand-rolled `.then/.catch/done`.

   Run interactively (single ns, NEVER overlapping in the live pod):
     (require 'seon.eval.prose-demote-test :reload)
     (cljs.test/run-tests 'seon.eval.prose-demote-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent]                          ; :seon.eval / :seon.agent.turn registrations
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as internal]
    [seon.test.async :refer [settle!]]))

;; ---------------------------------------------------------------------------
;; Fixtures (mirror repair_batch_test/with-conn — set! root *conn* because
;; eval-batch!'s record-eval! transacts AFTER awaits, where a CLJS `binding`
;; of *conn* has already unwound).
;; ---------------------------------------------------------------------------

(defn- with-conn
  "Open a fresh full-schema :memory conn, `set!` it as the ROOT `db/*conn*`,
   run `body` (0-arg, may return a Promise), restore after. Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(def ^:private fixture-turn-id "turnprose001")

(defn- run-batch!
  "Parse `source` and run it through `eval-batch!` against the root-bound
   conn. Returns Promise<batch-result>."
  [source turn-id]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [_]
               (seval/eval-batch! @repl/!compile-state
                                  (internal/parse-forms source)
                                  'my.agent.test
                                  "prose-agent-2606"
                                  turn-id
                                  nil)))))

(defn- eval-rows
  "All recorded eval rows in `db*`, as {:id :ok? :source :error :narration}."
  [db*]
  (->> (d/q '[:find ?id ?ok
              :where [?e :seon.eval/id ?id] [?e :seon.eval/ok? ?ok]]
            db*)
       (map (fn [[id ok]]
              (let [g (fn [a]
                        (ffirst (d/q '[:find ?v :in $ ?id ?a
                                       :where [?e :seon.eval/id ?id]
                                              [?e ?a ?v]]
                                     db* id a)))]
                {:id id :ok? ok
                 :source    (g :seon.eval/source)
                 :error     (g :seon.eval/error)
                 :narration (g :seon.eval/narration)})))
       (sort-by :id)
       vec))

(defn- row-for
  "The recorded eval row whose source is `src` (rows accumulate on the one
   conn across batches, so we match by source, not (first rows))."
  [src]
  (->> (eval-rows @db/*conn*) (filter #(= src (:source %))) first))

(def ^:private prose-note-re #"(?i)PROSE, not code")

;; ===========================================================================
;; DEMOTE matrix — every word is an undefined bare symbol → prose row.
;; No eval, no error, counted as neither ok nor fail (n-ok = n-fail = 0).
;; ===========================================================================

(def demote-cases
  ["(Abk and fvV both look correct)"
   "(June 3 before June 14)"
   "(results look fine)"])

(deftest prose-parens-are-demoted
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (doseq [src demote-cases]
            (let [res (await (run-batch! src fixture-turn-id))
                  row (row-for src)]
              (testing (str "DEMOTE: " src)
                (is (= 0 (:seon.eval/n-ok res))
                    "prose is not counted as a success")
                (is (= 0 (:seon.eval/n-fail res))
                    "prose is NOT counted as an eval error (the whole point)")
                (is (true? (:ok? row))
                    "recorded ok? true — no :seon.eval/ok? false error row")
                (is (nil? (:error row))
                    "no :seon.eval/error stored")
                (is (= src (:source row))
                    "the prose text is preserved in the transcript")
                (is (re-find prose-note-re (str (:narration row)))
                    "the prose-demotion note rides on the narration"))))))
      done)))

;; ===========================================================================
;; KEEP matrix — a code signal is present (resolving/qualified head or arg,
;; a core/special head, a defined symbol). These eval + count EXACTLY as
;; before. The form may legitimately SUCCEED or FAIL; what matters is that
;; it was NOT demoted to a silent prose row.
;; ===========================================================================

(deftest core-headed-forms-are-kept-and-run
  ;; `(+ 1 2)` (core head) and `(vals totals)` (core head, undefined arg)
  ;; both resolve their head → real code, NOT demoted.
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (testing "(+ 1 2) — core head resolves → eval'd, succeeds"
            (let [res (await (run-batch! "(+ 1 2)" fixture-turn-id))
                  row (row-for "(+ 1 2)")]
              (is (= 1 (:seon.eval/n-ok res)))
              (is (= 0 (:seon.eval/n-fail res)))
              (is (true? (:ok? row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))
          (testing "(vals totals) — core head resolves → eval'd (errors on undefined arg, KEPT)"
            (let [res (await (run-batch! "(vals totals)" fixture-turn-id))
                  row (row-for "(vals totals)")]
              ;; `vals` resolves so it is real code; `totals` is undefined, so
              ;; the eval is a genuine error — counted as one (KEPT).
              (is (= 0 (:seon.eval/n-ok res)))
              (is (= 1 (:seon.eval/n-fail res))
                  "an undefined ARG is a real error, not demoted")
              (is (false? (:ok? row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))))
      done)))

(deftest macro-headed-introspection-is-kept-and-runs
  ;; Rung-1 smell (2026-07-10): `(ns-interns 'some.ns)` was DEMOTED — the
  ;; head is a core MACRO (absent from globalThis, so no var probe hit)
  ;; missing from code-head-syms' literal set, and the quoted ns-name reads
  ;; as 2 unresolvable arg symbols. The agent got a false-confidence
  ;; `ok? nil` for a form that never ran, while the qualified
  ;; `clojure.core/ns-interns` call worked. `core-macro-head?` (the
  ;; COMPUTED complement — the analyzer's own cljs.core$macros defs) keeps
  ;; every macro-headed call.
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (testing "(ns-interns 'cljs.user) — macro head → eval'd, returns a map"
            (let [res (await (run-batch! "(ns-interns 'cljs.user)" fixture-turn-id))
                  row (row-for "(ns-interns 'cljs.user)")]
              (is (= 1 (:seon.eval/n-ok res)) "evaluated, not demoted")
              (is (true? (:ok? row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))
          (testing "(ns-publics 'cljs.user) — same, the sibling macro"
            (let [res (await (run-batch! "(ns-publics 'cljs.user)" fixture-turn-id))
                  row (row-for "(ns-publics 'cljs.user)")]
              (is (= 1 (:seon.eval/n-ok res)) "evaluated, not demoted")
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))))
      done)))

(deftest namespaced-head-is-kept
  ;; A namespaced symbol ANYWHERE is a hard code signal — KEEP.
  ;; `clojure.string/upper-case` is bundled and resolves, so this real call
  ;; actually runs and succeeds (never demoted to a silent prose row).
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (let [res (await (run-batch! "(clojure.string/upper-case \"ok\")"
                                       fixture-turn-id))
                row (first (eval-rows @db/*conn*))]
            (testing "namespaced head → real code, eval'd ok (not demoted)"
              (is (= 1 (:seon.eval/n-ok res)))
              (is (= 0 (:seon.eval/n-fail res)))
              (is (true? (:ok? row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))))
      done)))

(deftest defined-fn-call-is-kept
  ;; Define a fn, THEN call it with an undefined arg. The head resolves (we
  ;; just defined it) → real code → KEEP. The undefined arg makes the call a
  ;; genuine error (counted), proving it was NOT demoted to a prose row.
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (let [res  (await (run-batch!
                              (str "(defn my-prose-fn-2606 [x] x)\n"
                                   "(my-prose-fn-2606 some-undefined-arg)")
                              fixture-turn-id))
                call (row-for "(my-prose-fn-2606 some-undefined-arg)")]
            (testing "defn succeeds; the defined-head call is KEPT (errors, counted)"
              (is (= 1 (:seon.eval/n-ok res)) "the defn ok")
              (is (= 1 (:seon.eval/n-fail res)) "the undefined-arg call errors — KEPT")
              (is (some? call))
              (is (false? (:ok? call)))
              (is (not (re-find prose-note-re (str (:narration call))))
                  "a call to a DEFINED fn is never demoted")))))
      done)))

(deftest macro-headed-form-is-kept
  ;; `(when foo bar)` — `when` is a core MACRO (not a globalThis var), so it
  ;; relies on `code-head-syms` to be recognized as a code head. With foo/bar
  ;; undefined the eval errors, but it is KEPT (counted), not demoted.
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (let [res (await (run-batch! "(when zzz-foo-2606 zzz-bar-2606)"
                                       fixture-turn-id))
                row (first (eval-rows @db/*conn*))]
            (testing "macro head → code, KEPT even with all-undefined operands"
              (is (= 0 (:seon.eval/n-ok res)))
              (is (= 1 (:seon.eval/n-fail res))
                  "a `when`-headed form is real code → real error, not demoted")
              (is (false? (:ok? row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))))
      done)))

(deftest undefined-head-literal-args-is-kept
  ;; DOCUMENTED BOUNDARY (err toward KEEP): `(undefined-fn 1 2)` — bare
  ;; undefined head, LITERAL args, ZERO arg symbols. A plausible typo'd call,
  ;; so it is KEPT and produces a normal :compile error (counted as n-fail),
  ;; NOT demoted. Preserves the pre-#88 behavior for typo'd calls.
  (async done
    (settle!
      (with-conn
        (fn ^:async run []
          (let [res (await (run-batch! "(zzz-undefined-fn-2606 1 2)"
                                       fixture-turn-id))
                row (first (eval-rows @db/*conn*))]
            (testing "undefined head + literal args → KEPT as a typo'd call"
              (is (= 0 (:seon.eval/n-ok res)))
              (is (= 1 (:seon.eval/n-fail res))
                  "literal-arg call is a real error (not demoted) — err toward KEEP")
              (is (false? (:ok? row)))
              (is (some? (:error row)))
              (is (not (re-find prose-note-re (str (:narration row)))) "NOT demoted")))))
      done)))

;; ===========================================================================
;; Pure-predicate unit matrix — drives `prose-paren?` directly against the
;; live bootstrap compile-state, no DB round-trip. The structural heart of
;; the gate, independent of the eval-batch! plumbing.
;; ===========================================================================

(deftest prose-paren-predicate-matrix
  (async done
    (settle!
      (-> (repl/ensure-bootstrap!)
          (.then
            (fn [_]
              (let [cs  @repl/!compile-state
                    ns* 'cljs.user
                    p?  (fn [s] (#'seval/prose-paren? cs ns* s))]
                (testing "DEMOTE — all-undefined-bare-word parentheticals"
                  (is (true? (p? "(Abk and fvV both look correct)")))
                  (is (true? (p? "(June 3 before June 14)")))
                  (is (true? (p? "(results look fine)")))
                  (is (true? (p? "(this all looks reasonable)")))
                  (is (true? (p? "(numbers and strings \"ok\" :kw allowed here too)"))))
                (testing "KEEP — a resolving head"
                  (is (false? (p? "(+ 1 2)")))
                  (is (false? (p? "(vals totals)")))
                  (is (false? (p? "(str hello world)"))))
                (testing "KEEP — a namespaced symbol anywhere"
                  (is (false? (p? "(seon.db/query foo bar)")))
                  (is (false? (p? "(see seon.db/query result here)"))))
                (testing "KEEP — a special form / core macro head"
                  (is (false? (p? "(when foo bar)")))
                  (is (false? (p? "(if a b c)")))
                  (is (false? (p? "(and Abk fvV both)")))
                  (is (false? (p? "(-> data foo bar)"))))
                (testing "KEEP — lone undefined head, literal-only or single-arg (typo'd call)"
                  (is (false? (p? "(undefined-fn 1 2)")))
                  (is (false? (p? "(undefined-fn \"x\")")))
                  (is (false? (p? "(parse-it x)"))))
                (testing "KEEP — not a single bare-headed list"
                  (is (false? (p? "(:keyword m)")) "keyword head = code")
                  (is (false? (p? "((f) x y)")) "non-symbol head")
                  (is (false? (p? "(foo) (bar baz qux)")) "two forms")
                  (is (false? (p? "42")) "not a list")
                  (is (false? (p? "[a b c]")) "vector"))
                (testing "KEEP — undefined head with a nested qualified ref in args"
                  (is (false? (p? "(note (seon.db/q x) looks off)"))))))))
      done)))
