(ns seon.test.runner-test
  "Self-tests for `seon.test.runner` — the Phase 1 entrypoints.

   These tests drive the runner against synthetic probe `deftest` vars
   that live in a SEPARATE namespace (`seon.test.runner-probes`). The
   split exists for one specific reason: if the probes lived here, the
   `run!-with-ns-selector-*` test would invoke `r/run!` against this
   ns, which would re-select itself, recurse, and hang. Keeping probes
   off-namespace breaks the cycle.

   Coverage:
     - `vars-in-ns` discovers deftest vars + returns vector for unknown ns
     - `run!` with `::vars` selector runs a known-passing probe
     - `run!` with `::ns` selector picks up every probe in the probe ns
       AND surfaces the failing probe's fail event in the returned data
     - `run-ns!` records durable per-test summary facts without a session id
     - the async driver awaits the probe body before resolving (verified
       via a side-effect atom that the body mutates from inside setTimeout)

   Each test exercises ONE specific runner behaviour. Failure messages
   include the data that drove the decision so a regression's `:fail`
   event tells you immediately which capability broke."
  (:require
    [cljs.test :as t :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent]
    [seon.agent.message]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.test.runner :as r]
    [seon.test.runner-probes :as probes]))

(def ^:private probes-ns 'seon.test.runner-probes)

(defn- ensure-conn!
  "The runner's record path (`run-ns!` with `::record? true`) writes through
   the ambient `db/*conn*`. The pod binds that at boot; the node-test runner
   has no boot, so
   without this the record tests throw `*conn* is unbound` — an
   UNHANDLED rejection that kills the whole Node process (observed
   2026-06-09). Opens one fresh :memory conn for this suite and
   root-`set!`s `db/*conn*` (a `binding` would not survive the
   Promise boundaries). Returns a Promise of the conn."
  []
  (if db/*conn*
    (js/Promise.resolve db/*conn*)
    (let [cfg {:store              {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history?      true}]
      (-> (d/create-database cfg)
          (.then (fn [_] (d/connect cfg {:sync? false})))
          (.then (fn [conn]
                   (-> (db/ensure-provenance! {:seon.db/conn conn})
                       (.then
                         (fn [_]
                           (set! db/*conn* conn)
                           conn)))))))))

;; ============================================================
;; vars-in-ns
;; ============================================================

(deftest vars-in-ns-discovers-probe-tests
  (let [discovered (set (r/vars-in-ns {:seon.test.runner/ns probes-ns}))]
    (is (contains? discovered 'seon.test.runner-probes/probe-passing-test)
        (str "vars-in-ns should discover probe-passing-test; "
             "found: " (pr-str discovered)))
    (is (contains? discovered 'seon.test.runner-probes/probe-failing-test)
        "vars-in-ns should discover probe-failing-test")
    (is (contains? discovered 'seon.test.runner-probes/probe-async-test)
        "vars-in-ns should discover probe-async-test")))

(deftest vars-in-ns-returns-empty-for-unknown-ns
  (let [out (r/vars-in-ns {:seon.test.runner/ns 'seon.nonexistent.ns})]
    (is (vector? out)
        "vars-in-ns must always return a vector, even for missing ns")
    (is (zero? (count out))
        (str "expected empty vector for unknown ns, got: " (pr-str out)))))

;; ============================================================
;; run! — ::vars selector
;; ============================================================

(deftest run!-with-vars-selector-runs-a-passing-test
  (async done
    (-> (r/run! {:seon.test.runner/vars
                 '[seon.test.runner-probes/probe-passing-test]})
        (.then
          (fn [result]
            (let [{:keys [test pass fail error]}
                  (:seon.test.runner/summary result)]
              (is (= 1 test)  (str "expected 1 test, summary=" (pr-str (:seon.test.runner/summary result))))
              (is (= 1 pass)  "passing probe should produce 1 pass")
              (is (= 0 fail)  "passing probe should produce 0 fails")
              (is (= 0 error) "passing probe should produce 0 errors")
              (is (= '[seon.test.runner-probes/probe-passing-test]
                     (:seon.test.runner/selected-vars result))
                  "selected-vars should echo the input"))
            (done))))))

;; ============================================================
;; B9 — a `defn` usage example RUNS its `:test` thunk (not the impl)
;; ============================================================

(deftest run!-runs-a-defn-with-test-usage-example
  ;; The Part-4 proof: `probe-example-add` is a `defn` (NOT a deftest)
  ;; carrying `{:test (fn [] (is (= 5 (probe-example-add 2 3))))}`. Its
  ;; thunk lands on `cljs$lang$test`. If `resolve-test-fn` returned the
  ;; IMPLEMENTATION fn instead, driving it would call `(probe-example-add)`
  ;; at arity 0 → an `:error`, never the example's `:pass`. A clean
  ;; `:pass` here proves the EXAMPLE ran.
  (async done
    (-> (r/run! {:seon.test.runner/vars
                 '[seon.test.runner-probes/probe-example-add]})
        (.then
          (fn [result]
            (let [{:keys [test pass fail error]}
                  (:seon.test.runner/summary result)]
              (is (= 1 test)  (str "expected 1 test, summary="
                                   (pr-str (:seon.test.runner/summary result))))
              (is (= 1 pass)  (str "the example's (is (= 5 (probe-example-add 2 3))) "
                                   "must fire as 1 PASS — not the impl at arity 0; "
                                   "summary=" (pr-str (:seon.test.runner/summary result))))
              (is (= 0 fail)  "passing example → 0 fails")
              (is (= 0 error) (str "0 errors — an error here means the impl fn ran "
                                   "(wrong arity) instead of the :test thunk")))
            (done)))
        (.catch (fn [e]
                  (is false (str "threw — " e))
                  (done))))))

(deftest run!-surfaces-a-failing-defn-with-test-example
  ;; A failing usage example must report a FAIL (so a redefine that breaks
  ;; the contract is caught). Arm the example's assertion, drive it, expect
  ;; exactly one fail.
  (async done
    (reset! probes/armed? true)
    (-> (r/run! {:seon.test.runner/vars
                 '[seon.test.runner-probes/probe-example-armed]})
        (.then
          (fn [result]
            (let [{:keys [test pass fail error]}
                  (:seon.test.runner/summary result)]
              (is (= 1 test) (str "expected 1 test, summary=" (pr-str (:seon.test.runner/summary result))))
              (is (= 1 fail) (str "the armed example's assertion must FAIL (1 fail); "
                                  "summary=" (pr-str (:seon.test.runner/summary result))))
              (is (= 0 error) "a failing assertion is a :fail, not an :error"))
            (reset! probes/armed? false)
            (done)))
        (.catch (fn [e]
                  (reset! probes/armed? false)
                  (is false (str "threw — " e))
                  (done))))))

;; ============================================================
;; run! — ::ns selector
;; ============================================================

(deftest run!-with-ns-selector-discovers-and-runs-all-probes
  (async done
    ;; Arm the intentionally-failing probe — only the runner-driven
    ;; invocation should see it fail (see probes/armed? docstring).
    (reset! probes/armed? true)
    (-> (r/run! {:seon.test.runner/ns probes-ns})
        (.then
          (fn [result]
            (let [summary  (:seon.test.runner/summary result)
                  selected (set (:seon.test.runner/selected-vars result))]
              (is (contains? selected 'seon.test.runner-probes/probe-passing-test)
                  "probe-passing-test must be in selected-vars")
              (is (contains? selected 'seon.test.runner-probes/probe-failing-test)
                  "probe-failing-test must be in selected-vars")
              (is (contains? selected 'seon.test.runner-probes/probe-async-test)
                  "probe-async-test must be in selected-vars")
              ;; B9: a `defn` with a `:test` example is ALSO a discoverable
              ;; test target (its thunk lands on `cljs$lang$test`, the same
              ;; slot `vars-in-ns` scans). One unified notion of "a test".
              (is (contains? selected 'seon.test.runner-probes/probe-example-add)
                  "probe-example-add (defn-with-:test example) must be discovered")
              (is (contains? selected 'seon.test.runner-probes/probe-example-armed)
                  "probe-example-armed (defn-with-:test example) must be discovered")
              (is (= 5 (:test summary))
                  (str "expected 5 tests (3 deftest probes + 2 defn-with-:test "
                       "example probes), summary=" (pr-str summary)))
              (is (pos? (:fail summary))
                  (str "expected at least 1 fail from the armed probes; "
                       "summary=" (pr-str summary))))
            (reset! probes/armed? false)
            (done)))
        (.catch (fn [e]
                  (reset! probes/armed? false)
                  (is false (str "threw — " e))
                  (done))))))

;; ============================================================
;; run! — selector exclusivity (enforced in the body, not the schema:
;; `::selector` is the pure-data "at least one" :or-of-maps shape, so
;; both-keys passes instrumentation and must RESOLVE here to a legible
;; error ENVELOPE. Never a rejection: `run!` is a specced ^:async fn,
;; and the instrument wrapper records a rejection as a :core fault —
;; expected caller mistakes ride the value channel.)
;; ============================================================

(deftest run!-resolves-ambiguous-selector-to-error-envelope
  (async done
    (-> (r/run! {:seon.test.runner/vars
                 '[seon.test.runner-probes/probe-passing-test]
                 :seon.test.runner/ns probes-ns})
        (.then
          (fn [result]
            (let [err (:seon/error result)]
              (is (some? err)
                  (str "expected a :seon/error envelope, got "
                       (pr-str (:seon.test.runner/summary result))))
              (is (= :user-input (:seon.error/kind err))
                  "a selector violation is a caller mistake — :user-input")
              (is (string? (:seon.error/message err))
                  "the structural error envelope carries a human-readable message")
              (is (= {:test 0 :pass 0 :fail 0 :error 1}
                     (:seon.test.runner/summary result))
                  "no tests ran; the summary counts the violation")
              (is (= [] (:seon.test.runner/events result))
                  "envelope is a schema-valid ::run-result"))
            (done))
          (fn [e]
            (is false (str "run! must NEVER reject on an expected error "
                           "(selector violation) — got rejection: " e))
            (done))))))

;; ============================================================
;; run-ns! — sugar wrapper, default ::record? true
;; ============================================================

(deftest run-ns!-records-durable-summaries-without-a-session-id
  (async done
    (-> (ensure-conn!)
        (.then (fn [_]
                 (r/run-ns! {:seon.test.runner/ns probes-ns
                             :seon.test.runner/record? true
                             :seon.db/conn db/*conn*})))
        (.then
          (fn [result]
            (is (true? (:seon.test.runner/recorded? result))
                "run-ns! with record? should set ::recorded? true")
            (is (vector? (:seon.test.runner/recorded-syms result))
                "::recorded-syms should be a vector")
            (is (pos? (count (:seon.test.runner/recorded-syms result)))
                "::recorded-syms should be non-empty after recording")
            (let [test-sym (first (:seon.test.runner/recorded-syms result))
                  row      (db/pull '[*] [:seon.test/sym test-sym])]
              (is (= test-sym (:seon.test/sym row))
                  "the database retains one durable row per recorded test")
              (is (or (inst? (:seon.test/last-passed-at row))
                      (inst? (:seon.test/last-failed-at row)))
                  "the durable row carries the latest outcome timestamp"))
            (done)))
        (.catch (fn [e]
                  (is false (str "threw — " e))
                  (done))))))

(deftest no-outcome-recording-is-a-structural-no-op
  (async done
    (let [run-result {:seon.test.runner/events []
                      :seon.test.runner/summary
                      {:test 0 :pass 0 :fail 0 :error 0}}]
      (-> (r/record-run! {:seon.test.runner/run-result run-result})
          (.then
            (fn [recorded]
              (let [result    (:seon.test.runner/run-result recorded)
                    tx-result (:seon.test.runner/tx-report recorded)]
                (is (m/validate :seon.test.runner/record-response recorded)
                    "the no-op response satisfies the public structural schema")
                (is (true? (:seon.test.runner/recorded? result))
                    "a completed empty selection is still a recorded run")
                (is (= [] (:seon.test.runner/recorded-syms result))
                    "an empty selection records no per-test symbols")
                (is (true? (:seon.db/ok? tx-result))
                    "the no-op recording resolves through the success channel")
                (is (zero? (:seon.db/tx-count tx-result))
                    "no outcomes produce no durable transaction datoms"))))
          (.then (fn [_] (done)))
          (.catch (fn [e]
                    (is false (str "threw — " e))
                    (done)))))))

(deftest failed-db-recording-reports-no-recorded-symbols
  (async done
    (let [prior-conn db/*conn*
          run-result {:seon.test.runner/events
                      [{:type :pass
                        :var 'seon.test.runner-probes/probe-passing-test
                        :expected "truthy"
                        :actual "truthy"}]
                      :seon.test.runner/summary
                      {:test 1 :pass 1 :fail 0 :error 0}}]
      ;; No connection forces the ordinary db error envelope without changing
      ;; the valid test-result data that reaches the recording boundary.
      (set! db/*conn* nil)
      (-> (r/record-run! {:seon.test.runner/run-result run-result})
          (.then
            (fn [recorded]
              (let [result    (:seon.test.runner/run-result recorded)
                    tx-result (:seon.test.runner/tx-report recorded)]
                (is (m/validate :seon.test.runner/record-response recorded)
                    "the failed-write response satisfies the public schema")
                (is (false? (:seon.db/ok? tx-result))
                    "a rejected durable write remains an error value")
                (is (false? (:seon.test.runner/recorded? result))
                    "the result reports that durable recording failed")
                (is (= [] (:seon.test.runner/recorded-syms result))
                    "no symbols claim to be recorded after a rejected write"))))
          (.finally (fn []
                      (set! db/*conn* prior-conn)))
          (.then (fn [_] (done)))
          (.catch (fn [e]
                    (is false (str "threw — " e))
                    (done)))))))

(deftest failure-summary-is-clipped-at-the-shared-token-boundary
  (let [summarize (deref #'r/failure-summary)
        cap       (deref #'r/failure-summary-max-tokens)
        input     (apply str (repeat (* cap 8) "x"))
        output    (summarize {:message input})]
    (is (str/ends-with? output "…")
        "the shared clip boundary marks an oversized summary")
    (is (<= (tokens/estimate output) cap)
        "the surfaced failure summary respects its token budget")
    (is (< (tokens/estimate output) (tokens/estimate input))
        "an oversized failure is clipped before durable display")))

;; ============================================================
;; Async driver — the body's `(is true)` assertion fires inside a
;; `js/setTimeout` callback, where CLJS dynamic bindings are lost.
;; That means we cannot reliably assert the inner pass count from
;; this nested call. We CAN assert the runner's contract: it must
;; AWAIT the async body before resolving its returned Promise. The
;; probe mutates `probes/async-evidence` from inside the setTimeout —
;; if the runner resolves early, the atom stays `nil`.
;; ============================================================

(deftest run!-awaits-async-test-body
  (async done
    (reset! probes/async-evidence nil)
    (-> (r/run! {:seon.test.runner/vars
                 '[seon.test.runner-probes/probe-async-test]})
        (.then
          (fn [result]
            (is (= :body-ran @probes/async-evidence)
                (str "async probe body must have executed before run! "
                     "resolved; got @async-evidence="
                     (pr-str @probes/async-evidence)))
            (let [{:keys [error]} (:seon.test.runner/summary result)]
              (is (= 0 error)
                  "async driver should not surface a CPS error"))
            (done))))))
