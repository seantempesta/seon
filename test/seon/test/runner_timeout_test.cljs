(ns seon.test.runner-timeout-test
  "Proves the per-test wall-clock bound in `seon.test.runner`
   (`with-test-timeout`): a test body that NEVER settles times out with an
   `:error` event instead of parking `run-vars` — and, through it, the agent
   turn / `run-loop!` that awaits the auto-test-run. This is the
   async-continuation WEDGE root-caused in
   docs/prds/agent-fsm/research/pod-wedge-root-cause-2026-06-28.md.

   The probes (`seon.test.runner-timeout-probes`) live off-namespace and are
   armed only for the bounded run here (see that ns's `armed?` docstring). Each
   test drops `SEON_TEST_TIMEOUT_MS` to 150ms so a hang resolves fast, and
   restores it (and disarms the probes) in a `.finally`."
  (:require
    [cljs.test :as t :refer [deftest is async]]
    [seon.test.runner :as r]
    [seon.test.runner-timeout-probes :as probes]))

(def ^:private probe-ns "seon.test.runner-timeout-probes")

(defn- timeout-event?
  "True for the timeout `:error` event `report-timeout!` emits."
  [ev]
  (and (= :error (:type ev))
       (some? (:message ev))
       (boolean (re-find #"timed out after" (:message ev)))))

(defn- restore-bound!
  "Restore `SEON_TEST_TIMEOUT_MS` to `prior` (or delete it if it was unset)."
  [prior]
  (if (some? prior)
    (set! (.. js/process -env -SEON_TEST_TIMEOUT_MS) prior)
    (js-delete (.. js/process -env) "SEON_TEST_TIMEOUT_MS")))

(defn- drive-probe
  "Arm the probes, set a 150ms bound, run the named probe var through
   `run-vars`, hand the result map to `check`, then disarm + restore + done.
   `check` runs inside the resolved-result path; a thrown run-vars (it must
   NOT throw / hang) fails loudly."
  [probe-name check done]
  (reset! probes/armed? true)
  (let [prior (.. js/process -env -SEON_TEST_TIMEOUT_MS)]
    (set! (.. js/process -env -SEON_TEST_TIMEOUT_MS) "150")
    (-> (r/run-vars {:seon.test.runner/vars [(symbol probe-ns probe-name)]})
        (.then check)
        (.catch (fn [e] (is false (str "run-vars must not hang/throw; got: " e))))
        (.finally (fn []
                    (reset! probes/armed? false)
                    (restore-bound! prior)
                    (done))))))

(deftest never-resolving-async-body-times-out
  ;; A `^:async`-shaped test body returning a never-resolving Promise must be
  ;; abandoned by the bound, not park the runner.
  (async done
    (drive-probe
      "probe-never-resolving-async"
      (fn [result]
        (let [events  (:seon.test.runner/events result)
              summary (:seon.test.runner/summary result)]
          (is (some timeout-event? events)
              (str "expected a timeout :error event; got " (pr-str events)))
          (is (pos? (:error summary))
              (str "summary must count the timeout as an error; got "
                   (pr-str summary)))))
      done)))

(deftest async-done-never-called-times-out
  ;; An `(async done …)` test that never calls `done` must be abandoned by the
  ;; bound, not park the runner.
  (async done
    (drive-probe
      "probe-async-done-never-called"
      (fn [result]
        (let [events  (:seon.test.runner/events result)
              summary (:seon.test.runner/summary result)]
          (is (some timeout-event? events)
              (str "expected a timeout :error event; got " (pr-str events)))
          (is (pos? (:error summary))
              (str "summary must count the timeout as an error; got "
                   (pr-str summary)))))
      done)))

(deftest overlapping-runs-both-settle
  ;; Two `run-vars` overlapping (one hanging, one fast) must BOTH settle — the
  ;; hang times out, the concurrent fast run is unaffected. Proves a parked
  ;; run can no longer wedge a concurrent one.
  (async done
    (reset! probes/armed? true)
    (let [prior (.. js/process -env -SEON_TEST_TIMEOUT_MS)]
      (set! (.. js/process -env -SEON_TEST_TIMEOUT_MS) "150")
      (let [p-hang (r/run-vars {:seon.test.runner/vars
                                [(symbol probe-ns "probe-never-resolving-async")]})
            p-fast (r/run-vars {:seon.test.runner/vars
                                [(symbol probe-ns "probe-fast-passing")]})]
        (-> (js/Promise.all #js [p-hang p-fast])
            (.then (fn [results]
                     (let [hang (aget results 0)
                           fast (aget results 1)]
                       (is (some timeout-event? (:seon.test.runner/events hang))
                           "the hanging run must time out")
                       (is (zero? (:error (:seon.test.runner/summary fast)))
                           (str "the concurrent fast run must be unaffected; got "
                                (pr-str (:seon.test.runner/summary fast))))
                       (is (pos? (:pass (:seon.test.runner/summary fast)))
                           "the concurrent fast run must still pass"))))
            (.catch (fn [e] (is false (str "overlapping runs must not hang/throw; got: " e))))
            (.finally (fn []
                        (reset! probes/armed? false)
                        (restore-bound! prior)
                        (done))))))))
