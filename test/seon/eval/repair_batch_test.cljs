(ns seon.eval.repair-batch-test
  "Integration tests for the multi-form eval heart (Part A) at the
   `eval-batch!` seam: per-form repair auto-eval (A.2), sharpened
   unrepairable errors (A.3), the false-confidence failed-def guard
   (A.4 — this file's `failed-def-then-reference` test is the
   FALSIFICATION GATE), and the load-bearing invariant that form N+1
   runs even when N fails.

   Every test opens a FRESH `:memory` datahike conn (the pod's boot
   schema transacted) and drives the REAL bootstrap compile-state via
   `repl/ensure-bootstrap!`, then `parse-forms` → `eval-batch!`, and
   reads the recorded `:seon.eval` rows back out. Nothing here touches
   the live agent conn.

   Run interactively (single ns, NEVER overlapping in the live pod):
     (require 'seon.eval.repair-batch-test :reload)
     (cljs.test/run-tests 'seon.eval.repair-batch-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent]                          ; :seon.eval / :seon.agent.turn registrations
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as internal]))

;; ---------------------------------------------------------------------------
;; Fixtures
;;
;; `eval-batch!` is `^:async` and its `record-eval!` transacts run AFTER
;; awaits, so `db/*conn*` must be `set!` as the ROOT (a plain `binding`
;; does NOT survive a Promise/await boundary in CLJS — same reason the pod
;; boot + agent_loop_test use set!). Turn ids must satisfy the registered
;; compact identity schema, or the turn entity fails Malli validation and the
;; eval row is lost.
;; ---------------------------------------------------------------------------

(defn- with-conn
  "Open a fresh full-schema :memory conn, `set!` it as the ROOT
   `db/*conn*`, run `body` (0-arg, may return a Promise), restore after.
   Returns a Promise. Mirrors agent_loop_test/with-conn."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(def ^:private fixture-turn-id "turnrepair01")

(defn- run-batch!
  "Parse `source` and run it through `eval-batch!` against the root-bound
   conn. Returns Promise<batch-result>. The turn-id is a real id so the
   recorded evals all hang off ONE valid turn entity we can read back."
  [source turn-id]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [_]
               (seval/eval-batch! @repl/!compile-state
                                  (internal/parse-forms source)
                                  'my.agent.test
                                  "rb-agent-2606"
                                  turn-id
                                  nil)))))   ; runless eval path — no work fence

(defn- eval-rows
  "All recorded eval rows in `db*`, as a vector of
   {:id :ok? :source :error} maps, in stored order."
  [db*]
  (->> (d/q '[:find ?id ?ok
              :where [?e :seon.eval/id ?id] [?e :seon.eval/ok? ?ok]]
            db*)
       (map (fn [[id ok]]
              (let [src (ffirst (d/q '[:find ?s :in $ ?id
                                       :where [?e :seon.eval/id ?id]
                                              [?e :seon.eval/source ?s]]
                                     db* id))
                    err (ffirst (d/q '[:find ?er :in $ ?id
                                       :where [?e :seon.eval/id ?id]
                                              [?e :seon.eval/error ?er]]
                                     db* id))
                    ens (ffirst (d/q '[:find ?ns :in $ ?id
                                       :where [?e :seon.eval/id ?id]
                                              [?e :seon.eval/ns ?ns]]
                                     db* id))]
                {:id id :ok? ok :source src :error err :ns ens})))
       (sort-by :id)
       vec))

;; ===========================================================================
;; A.2 — repair succeeds, auto-evals, records ok? with the diff note.
;; The REAL high-scores form (failed 12×) is the fixture.
;; ===========================================================================

(def real-high-scores-form
  (str "(defn my-kb-high-scores-tile [_]\n"
       "  (let [total-count 3]\n"
       "    {:seon.render/hiccup\n"
       "     [:div {:style {:gap \"12px\"}}\n"
       "      [:div {:style {:gap \"4px\"}}\n"
       "       (str \"generated.md · \" total-count \" rows · :verified · \" (js/Date.)]]}\n"
       "     :seon.render/ai \"High scores tile updated with 50 papers.\"})"))

(deftest repair-succeeds-auto-evals-with-diff-note
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res  (await (run-batch! real-high-scores-form fixture-turn-id))
                  db*  @db/*conn*
                  rows (eval-rows db*)
                  row  (first rows)]
              (testing "the read failure was repaired + auto-eval'd ok"
                (is (= 1 (:seon.eval/n-ok res)))
                (is (= 0 (:seon.eval/n-fail res)))
                (is (= 1 (count rows)))
                (is (true? (:ok? row))))
              (testing "recorded source is the REPAIRED, now-readable source"
                (is (string? (:source row)))
                (is (every? #(not= :read (:seon.repl/kind %))
                            (internal/parse-forms (:source row)))
                    "repaired source re-reads with no :read failures"))
              (testing "the fn was defined with BOTH render keys"
                (is (some? (seval/lookup-value
                             'my.agent.test/my-kb-high-scores-tile))))
              (testing "the transparency note rode on the narration"
                (let [narr (ffirst
                             (d/q '[:find ?n
                                    :where [?e :seon.eval/id]
                                           [?e :seon.eval/narration ?n]]
                                  db*))]
                  (is (re-find #"auto-balanced" (str narr))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; A.3 — unrepairable read failure → sharp error: closer + line:col +
;; offending line + caret + "defined nothing — do not call/wire".
;; ===========================================================================

(deftest unrepairable-read-failure-gives-sharp-error
  (async done
    (-> (with-conn
          (fn ^:async run []
            ;; A bare unterminated string: parinfer can't infer intent,
            ;; so repair is rejected and we fall through to A.3.
            (let [res  (await (run-batch! "\"half-typed" fixture-turn-id))
                  rows (eval-rows @db/*conn*)
                  row  (first rows)]
              (testing "recorded as a failed eval (defined nothing)"
                (is (= 0 (:seon.eval/n-ok res)))
                (is (= 1 (:seon.eval/n-fail res)))
                (is (false? (:ok? row))))
              (testing "the error is the sharpened READ ERROR"
                (is (string? (:error row)))
                (is (re-find #"(?i)defined nothing|READ ERROR|EOF|delimiter|string"
                             (str (:error row))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest unrepairable-multiline-error-has-caret-and-offending-line
  (async done
    (-> (with-conn
          (fn ^:async run []
            ;; A multi-line form ending in an unterminated string: parinfer
            ;; indent-mode CANNOT salvage an unterminated string (it stays
            ;; unreadable), so we fall through to the sharpened A.3 error —
            ;; which must name the coordinate, slice the offending line, and
            ;; underline it with a caret (the "clear diff of what's wrong").
            (let [src (str "(defn f [x]\n"
                           "  (let [y 1]\n"
                           "    (str \"unterminated")
                  _   (await (run-batch! src fixture-turn-id))
                  row (first (eval-rows @db/*conn*))]
              (testing "unrepairable → failed eval"
                (is (some? row))
                (is (false? (:ok? row))))
              (testing "sharp error: coordinate + offending source line + caret"
                (let [msg (str (:error row))]
                  (is (re-find #"(?i)DEFINED NOTHING" msg)
                      "names that the form defined nothing")
                  (is (or (re-find #"line \d+, col \d+" msg)
                          (re-find #"\^" msg))
                      (str "has a coordinate or a caret — got: " msg))
                  (is (str/includes? msg "unterminated")
                      "slices out the offending source line"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest mismatched-closer-is-repaired-not-errored
  ;; The flip side of the above: a mismatched closer DOES get repaired by
  ;; indent-mode, so the agent sees the FIXED source (no negative example),
  ;; per the on-by-default repair policy.
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch! "(defn g [x] (+ x 1]" fixture-turn-id))
                  row (first (eval-rows @db/*conn*))]
              (testing "mismatched closer repaired + auto-eval'd ok"
                (is (= 1 (:seon.eval/n-ok res)))
                (is (= 0 (:seon.eval/n-fail res)))
                (is (true? (:ok? row)))
                (is (every? #(not= :read (:seon.repl/kind %))
                            (internal/parse-forms (:source row))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; #27 FALSIFICATION GATE — a REPAIRED form re-enters the SAME per-entry
;; dispatch the main loop uses. `(in-ns 'probe.rvp.a` (missing closer) is a
;; delimiter failure the repair fixes to `(in-ns 'probe.rvp.a)`; the
;; repaired form must hit the REPL-form dispatch (real in-ns semantics —
;; owner rulings 2026-07-10) IDENTICALLY to an already-valid `(in-ns …)`.
;; Pre-unification the repair sub-loop called eval-form-entry! directly,
;; BYPASSING the per-entry dispatch — so the repaired form eval'd `in-ns`
;; raw (not bootstrapped) and gave an opaque undeclared error. The gate:
;; in-ns SUCCEEDS and moves the accumulator; an un-dispatched `in-ns`
;; could only fail.
;; ===========================================================================

(deftest repaired-repl-form-re-enters-same-dispatch
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [;; missing close paren → triggers repair → (in-ns 'probe.rvp.a)
                  rep-res  (await (run-batch! "(in-ns 'probe.rvp.a" fixture-turn-id))
                  rep-row  (first (eval-rows @db/*conn*))
                  rep-narr (ffirst
                             (d/q '[:find ?n
                                    :where [?e :seon.eval/id]
                                           [?e :seon.eval/narration ?n]]
                                  @db/*conn*))]
              (testing "repaired (in-ns 'probe.rvp.a runs as the REAL movement form"
                (is (= 1 (:seon.eval/n-ok rep-res)))
                (is (= 0 (:seon.eval/n-fail rep-res)))
                (is (true? (:ok? rep-row)))
                (is (= :probe.rvp.a (:ns rep-row))
                    "the accumulator moved — the REPL-form dispatch ran, not a raw eval")
                (is (re-find #"auto-balanced" (str rep-narr))
                    "the repair diff note still rides on the narration")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest normal-repl-form-matches-repaired
  ;; The other half of the gate: an already-valid (in-ns …) behaves the
  ;; SAME, proving the repaired path is byte-for-byte the same per-entry
  ;; mechanism — one dispatch, no parallel path.
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch! "(in-ns 'probe.rvp.b)" fixture-turn-id))
                  row (first (eval-rows @db/*conn*))]
              (testing "normal (in-ns 'probe.rvp.b) also runs as the movement form"
                (is (= 1 (:seon.eval/n-ok res)))
                (is (= 0 (:seon.eval/n-fail res)))
                (is (true? (:ok? row)))
                (is (= :probe.rvp.b (:ns row)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; (c) A.4 FALSIFICATION GATE — a failed (def x …) then (get x :k).
;; OBSERVED (live, 2026-06-18): without the fix, (get x :k) → nil/ok? true
;; (the false-confidence trap). WITH the def-site provenance fix, the
;; reference escalates to an honest error (ok? false). [critique-flagged]
;; ===========================================================================

(deftest failed-def-then-reference-escalates-honestly
  (async done
    (-> (with-conn
          (fn ^:async run []
            ;; Form 1: a def whose RHS references an undefined fn → fails.
            ;; Form 2: a reference to that failed-def var.
            ;; Form 3: an independent good form (proves N+2 still runs).
            (let [res  (await (run-batch!
                                (str "(def tile-content (zzz-undefined-fn-2606 nil))\n"
                                     "(get tile-content :seon.render/hiccup)\n"
                                     "(+ 1 2)")
                                fixture-turn-id))
                  rows (eval-rows @db/*conn*)]
              (testing "def fails, reference escalates, good form runs"
                ;; 1 ok (the (+ 1 2)), 2 fail (def + reference)
                (is (= 1 (:seon.eval/n-ok res)))
                (is (= 2 (:seon.eval/n-fail res)))
                (is (= 3 (count rows))))
              (testing "the reference is an HONEST error, not nil/ok? true"
                (let [ref-row (->> rows
                                   (filter #(str/includes?
                                              (str (:source %))
                                              "(get tile-content"))
                                   first)]
                  (is (some? ref-row))
                  (is (false? (:ok? ref-row))
                      "the failed-def reference is ok? FALSE (was true pre-fix)")
                  (is (re-find #"(?i)does not exist|defined NOTHING|failed"
                               (str (:error ref-row)))
                      "names the failed-def provenance"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; Multi-form: one broken (unrepairable) form among good ones — the forms
;; before AND after still run; the broken one errors. ns accounting holds.
;; ===========================================================================

(deftest broken-form-among-good-ones-isolated
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res  (await (run-batch!
                                (str "(def aaa 1)\n"
                                     "\"unterminated\n"
                                     "(def bbb 2)")
                                fixture-turn-id))
                  rows (eval-rows @db/*conn*)
                  srcs (map :source rows)
                  oks  (zipmap srcs (map :ok? rows))]
              (testing "good forms before AND after the bad one ran"
                ;; aaa + bbb succeed, the unterminated string fails
                (is (= 2 (:seon.eval/n-ok res)))
                (is (= 1 (:seon.eval/n-fail res)))
                (is (= 3 (count rows))))
              (testing "the two defs both evaluated ok"
                (is (some (fn [[s ok]]
                            (and (str/includes? (str s) "aaa") ok))
                          oks))
                (is (some (fn [[s ok]]
                            (and (str/includes? (str s) "bbb") ok))
                          oks))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; undeclared-var (no def): a bare call to a missing fn → :compile error
;; naming the symbol (the analyzer path, distinct from A.4 provenance).
;; ===========================================================================

(deftest undeclared-var-call-is-compile-error
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch! "(my-undefined-fn-2606 nil)" fixture-turn-id))
                  row (first (eval-rows @db/*conn*))]
              (testing "a bare undeclared call fails (ok? false)"
                (is (= 0 (:seon.eval/n-ok res)))
                (is (= 1 (:seon.eval/n-fail res)))
                (is (false? (:ok? row)))
                (is (re-find #"(?i)undeclared|my-undefined-fn"
                             (str (:error row))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; read-error-message — the truncation (output-cap) symptom (#73). A pure
;; sync fn: an "Unexpected EOF" read error is the signature of a form that
;; ran past the output budget (a giant inline report message), so its
;; guidance must steer to REPORT=DATA, MESSAGE=POINTER — not "fix the
;; delimiter" (which is right only for an actual unmatched closer).
;; ===========================================================================

(deftest read-error-eof-teaches-store-then-point
  (let [eof (seval/read-error-message
              "Unexpected EOF while reading string. [at line 2, column 26]"
              "(message/agent \"abc\" (str \"# Report\nlots cut off mid")
        del (seval/read-error-message
              "Unmatched delimiter: ] [at line 1, column 8]"
              "(foo bar])")]
    (testing "the EOF/truncation case steers to store-data + send-pointer"
      (is (re-find #"(?i)truncat"  eof))
      (is (re-find #"(?i)pointer"  eof))
      (is (re-find #"my\.kb|:seon\.items" eof))
      (is (not (re-find #"(?i)fix the delimiter" eof))))
    (testing "a genuine unmatched-delimiter error keeps the delimiter guidance"
      (is (re-find #"(?i)fix the delimiter" del))
      (is (not (re-find #"(?i)pointer" del))))))
