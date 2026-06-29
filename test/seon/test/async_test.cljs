(ns seon.test.async-test
  "Proves `seon.test.async/settle!` does what the #44/#41 hardening
   promises: a rejected (or never-settling) promise chain produces a
   LOUD `(is false …)` failure AND calls `done` exactly once — instead
   of silently hanging the single-process :node-test runner.

   Capture trick: settle!'s `(is …)` reports to the dynamically-current
   cljs.test env. We `set!` the root env to a throwaway capture env
   (cljs.test uses root `set!`, not `binding`, so the value survives the
   await/microtask), let settle! run against it, then read its
   `:report-counters` to confirm a `:fail` was recorded — and restore the
   real env before the proof test's own assertions + `done`. Same
   root-`set!`-survives-await mechanism seon.test.runner relies on."
  (:require [cljs.test :refer [deftest is async] :as t]
            [seon.test.async :refer [settle!]]))

(defn- fresh-capture-env []
  (assoc (t/empty-env)
         :report-counters {:test 0 :pass 0 :fail 0 :error 0}))

(deftest settle!-on-reject-fails-loudly-and-calls-done-once
  (async done
    (let [saved (t/get-current-env)
          calls (volatile! 0)]
      ;; Swap to a capture env so settle!'s loud `(is false …)` lands
      ;; HERE, not on this proof test (which must stay green).
      (set! t/*current-env* (fresh-capture-env))
      (-> (settle! (js/Promise.reject (js/Error. "boom — contract reject"))
                   (fn [] (vswap! calls inc)))
          (.then
            (fn [_]
              (let [fails (get-in (t/get-current-env) [:report-counters :fail])]
                ;; Restore BEFORE asserting so these `is` count toward the
                ;; real run, and later tests see the proper env.
                (set! t/*current-env* saved)
                (is (= 1 @calls)
                    "settle! calls done exactly once on reject (no hang)")
                (is (= 1 fails)
                    "settle! recorded a loud (is false …) failure on reject")
                (done))))))))

(deftest settle!-on-never-settling-promise-times-out-loudly
  ;; #41 — a chain that never settles must FAIL LOUDLY + INDIVIDUALLY,
  ;; not hang the run. The capture `done` here is reached ONLY via the
  ;; timeout path (the promise never resolves/rejects); reaching it at
  ;; all proves the runner was freed, and the recorded :fail proves the
  ;; failure was loud.
  (async done
    (let [saved (t/get-current-env)]
      (set! t/*current-env* (fresh-capture-env))
      (settle! (js/Promise. (fn [_ _]))            ; never settles
               (fn []                              ; only the timeout calls this
                 (let [fails (get-in (t/get-current-env) [:report-counters :fail])]
                   (set! t/*current-env* saved)
                   (is (= 1 fails)
                       "settle! recorded a loud TIMED OUT failure")
                   (done)))
               50))))                              ; 50ms — fast, deterministic
