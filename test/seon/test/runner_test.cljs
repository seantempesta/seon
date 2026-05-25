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
     - `run-ns!` is sugar that records to the DB and returns a run-id
     - `last-result` round-trips the run via :seon.test/last-run-id + stash
     - the async driver awaits the probe body before resolving (verified
       via a side-effect atom that the body mutates from inside setTimeout)

   Each test exercises ONE specific runner behaviour. Failure messages
   include the data that drove the decision so a regression's `:fail`
   event tells you immediately which capability broke."
  (:require
    [cljs.test :as t :refer [deftest is async]]
    [seon.test.runner :as r]
    [seon.test.runner-probes :as probes]))

(def ^:private probes-ns 'seon.test.runner-probes)

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
;; run! — ::ns selector
;; ============================================================

(deftest run!-with-ns-selector-discovers-and-runs-all-probes
  (async done
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
              (is (= 3 (:test summary))
                  (str "expected 3 tests (one per probe), summary="
                       (pr-str summary)))
              (is (pos? (:fail summary))
                  (str "expected at least 1 fail from probe-failing-test; "
                       "summary=" (pr-str summary))))
            (done))))))

;; ============================================================
;; run-ns! — sugar wrapper, default ::record? true
;; ============================================================

(deftest run-ns!-records-and-returns-run-id
  (async done
    (-> (r/run-ns! {:seon.test.runner/ns probes-ns
                    :seon.test.runner/record? true})
        (.then
          (fn [result]
            (is (string? (:seon.test.runner/run-id result))
                (str "run-ns! with record? should populate ::run-id; "
                     "result keys=" (pr-str (keys result))))
            (is (true? (:seon.test.runner/recorded? result))
                "run-ns! with record? should set ::recorded? true")
            (is (vector? (:seon.test.runner/recorded-syms result))
                "::recorded-syms should be a vector")
            (is (pos? (count (:seon.test.runner/recorded-syms result)))
                "::recorded-syms should be non-empty after recording")
            (done))))))

;; ============================================================
;; last-result — DB lookup + globalThis stash round-trip
;; ============================================================

(deftest last-result-roundtrips-most-recent-run
  (async done
    (-> (r/run-ns! {:seon.test.runner/ns probes-ns
                    :seon.test.runner/record? true})
        (.then
          (fn [first-result]
            (let [run-id  (:seon.test.runner/run-id first-result)
                  fetched (r/last-result {})]
              (is (some? fetched)
                  "last-result must return a value after a recorded run")
              (is (= run-id (:seon.test.runner/run-id fetched))
                  (str "last-result run-id should match the just-recorded "
                       "run-id; expected=" run-id
                       " got=" (:seon.test.runner/run-id fetched)))
              (is (some? (:seon.test.runner/run-result fetched))
                  "last-result should hydrate the run-result blob from stash")
              (let [hydrated (:seon.test.runner/run-result fetched)]
                (is (vector? (:seon.test.runner/events hydrated))
                    "hydrated run-result must carry ::events vector")
                (is (map? (:seon.test.runner/summary hydrated))
                    "hydrated run-result must carry ::summary map")))
            (done))))))

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
