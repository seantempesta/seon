(ns seon.eval.race-timeout-test
  "The ONE async wall-clock racer — `seon.eval/race-timeout` + `timed-out?`.

   Pins the contract every bounded await in the pod (eval, the agent loop's
   per-turn bound, `call-llm!`'s per-attempt bound) sits on:
     - a fast inner settle returns the inner VALUE and CLEARS the pending
       timer (no dangling setTimeout when the inner Promise wins);
     - a never-settling inner returns the timeout sentinel, recognized ONLY
       by [[seon.eval/timed-out?]] (identity, never shape);
     - an ordinary value is never mistaken for a timeout."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.eval :as seval]))

(deftest inner-win-returns-value-and-clears-timer
  (async done
    (let [orig-clear (.-clearTimeout js/globalThis)
          !cleared   (atom 0)]
      ;; Intercept the global clearTimeout (race-timeout resolves it at call
      ;; time) so the timer-clear-on-settle is OBSERVED, not inferred.
      (set! (.-clearTimeout js/globalThis)
            (fn [id] (swap! !cleared inc) (orig-clear id)))
      (-> (seval/race-timeout (js/Promise.resolve :fast) 60000)
          (.then (fn [v]
                   (set! (.-clearTimeout js/globalThis) orig-clear)
                   (testing "the inner value flows through untouched"
                     (is (= :fast v))
                     (is (false? (seval/timed-out? v))))
                   (testing "the pending 60s timer was cleared when inner won"
                     (is (pos? @!cleared)))
                   (done)))
          (.catch (fn [e]
                    (set! (.-clearTimeout js/globalThis) orig-clear)
                    (is false (str "threw — " e))
                    (done)))))))

(deftest timer-win-returns-the-sentinel
  (async done
    (let [never (js/Promise. (fn [_ _]))]
      (-> (seval/race-timeout never 30)
          (.then (fn [v]
                   (testing "a never-settling inner yields the timeout sentinel"
                     (is (true? (seval/timed-out? v))))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest sentinel-is-identity-not-shape
  (async done
    (-> (seval/race-timeout (js/Promise.resolve #js {:_seon_eval_timeout true}) 60000)
        (.then (fn [v]
                 (testing "a look-alike value is NOT a timeout (identity check)"
                   (is (false? (seval/timed-out? v))))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
