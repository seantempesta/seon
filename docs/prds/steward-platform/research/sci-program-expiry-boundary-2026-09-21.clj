;; Run with clojure -M docs/prds/steward-platform/research/sci-program-expiry-boundary-2026-09-21.clj
(require 'seon.sci.eval 'seon.sci.kernel 'seon.sci.admit 'seon.program
         '[seon.await :as await]
         '[seon.test :as test])
(println :loads)
(let [failure (#'test/unknown :seon.test/selection "Selection could not complete.")
      completed (java.util.concurrent.FutureTask.
                 ^java.util.concurrent.Callable (fn [] failure))
      pending (java.util.concurrent.FutureTask.
               ^java.util.concurrent.Callable (fn [] :never-started))
      request {:seon.await/bound
               {:seon.await/config-attribute :seon.test/check-time-limit-ms
                :seon.await/config-value 1}
               :seon.await/diagnostic
               {:seon.error/diagnostic-layer :test
                :seon.error/diagnostic-operation :seon.test/check
                :seon.error/diagnostic-member :check-completion
                :seon.error/diagnostic-expected :check-result
                :seon.error/diagnostic-offending :pending
                :seon.error/diagnostic-evidence {}}}]
  (.run completed)
  (let [result (await/await! (assoc request :seon.await/future completed))
        ;; The exact selection condition currently used by seon.test/check.
        selected-expiry? (and (map? result)
                              (contains? result :seon.error/at)
                              (contains? result :seon.error/layer)
                              (contains? result :seon.error/operation))
        expiry (#'test/expired-result
                {:seon.test/progress "reaching selection"}
                (System/nanoTime) (:seon.error/message result))
        timeout (await/await! (assoc request :seon.await/future pending))]
    (assert (.isDone completed))
    (assert (identical? failure result))
    (assert selected-expiry?)
    (assert (not= (:seon.test/unknown failure) (:seon.test/unknown expiry)))
    (prn {:sci-program/await-output (last (:malli/schema (meta #'await/await!)))
          :sci-program/completed-error-preserved (identical? failure result)
          :sci-program/completed-error-selects-expiry selected-expiry?
          :sci-program/original-unknown (:seon.test/unknown failure)
          :sci-program/replacement-unknown (:seon.test/unknown expiry)
          :sci-program/timeout-base-members
          (select-keys timeout [:seon.error/at :seon.error/layer :seon.error/operation])
          :sci-program/timeout-cause
          (get-in timeout [:seon.error/data :seon.error/diagnostic-cause])})))
