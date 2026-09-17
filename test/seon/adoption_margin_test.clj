(ns seon.adoption-margin-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.operator.state :as state]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest lifecycle-hold-measures-progress-silence
  (let [root (str (io/file "tmp/adoption-margin-root" (str (random-uuid))))
        progress (atom "schema declarations")
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        silence-ms 150
        request {:seon.operator.lock/path (state/root-lifecycle-lock-path root)
                 :seon.operator.lock/command "init --dev scratch"
                 :seon.operator.lock/acquisition-timeout-ms 1000
                 :seon.config.operator/event-silence-backstop-ms silence-ms
                 :seon.operator.lock/progress progress}]
    (try
      (let [started (System/nanoTime)
            outcome (future
                      (try
                        (state/with-lifecycle-lock!
                         request
                         (fn []
                           (reset! progress "SCI acquisition")
                           (.countDown entered)
                           (support/await-event! release :release-stalled-phase)))
                        (catch clojure.lang.ExceptionInfo failure (ex-data failure))))]
        (support/await-event! entered :phase-entered)
        (let [failure (support/await-event! outcome :phase-silence-refusal)]
          (is (= :seon.operator/lock-hold-timeout (:seon.error/kind failure)))
          (is (= "SCI acquisition"
                 (get-in failure [:seon.operator.lock/holder :seon.operator.lock/phase])))
          (is (<= silence-ms (quot (- (System/nanoTime) started) 1000000) 1500))
          (is (:seon.operator.lock/holder-alive? (state/lock-holder (:seon.operator.lock/path request))))
          (.countDown release)
          (is (= :released
                 (state/with-lifecycle-lock!
                  (dissoc request :seon.operator.lock/progress)
                  (constantly :released))))))
      (let [started (System/nanoTime)]
        (is (= :converged
               (state/with-lifecycle-lock!
                request
                (fn []
                  (dotimes [phase 12]
                    (reset! progress (str "reconciled entity batch " phase))
                    ;; Deliberately advance phase work across three original
                    ;; hold windows; these are actual events, not a timer heartbeat.
                    (.await (CountDownLatch. 1) 40 TimeUnit/MILLISECONDS))
                  :converged))))
        (is (> (quot (- (System/nanoTime) started) 1000000) (* 3 silence-ms))))
      (finally
        (.countDown release)
        (support/delete-recursively! root)))))
