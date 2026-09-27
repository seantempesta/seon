(ns seon.sci.observe
  "The write observer stage: the SCI fork's producer observer
  (`sci.lang/*write-observer*`) bound around one evaluation.

  Each completed root write, intern, unmap and namespace removal arrives
  once, outside the env swap's retries and before the Var's watches, with
  the source occurrence the definition ran as. A definition is armed at its
  write, on the exact Var written, with the observer unbound so arming's own
  root replacement is not a write of the program. After the evaluation, each
  namespace whose SCI map changed (aliases, refers, imports, interns) is one
  more write."
  (:require [sci.lang]
            [seon.sci.arm :as arm]))

(defn observe
  "Run `thunk` (one evaluation) under the producer observer, handing each
  write to `on-write`."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:=> [:cat :seon.batch/write] :any] [:=> [:cat] :any]] :any]}
  [{ctx :seon.sci.eval/ctx :as request} working on-write thunk]
  (let [env (:env ctx)
        before (:namespaces @env)]
    (try
      (binding [sci.lang/*write-observer*
                (fn [w]
                  (let [armed (binding [sci.lang/*write-observer* nil]
                                (if (and (= :bind (:op w)) (:ns (meta (:var w))))
                                  (arm/arm! request working w)
                                  w))]
                    (when (or (not= :bind (:op w)) (:ns (meta (:var w))))
                      (on-write (arm/write-event armed)))))]
        (thunk))
      (finally
        (doseq [n (arm/changed-namespaces before (:namespaces @env))]
          (on-write {:seon.batch/op :namespace :seon.batch/ns n}))))))
