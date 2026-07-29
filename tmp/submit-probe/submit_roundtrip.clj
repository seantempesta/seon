(ns submit-probe.submit-roundtrip
  (:require [sci.core :as sci]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.sci.eval :as eval]
            [seon.sci.reader :as reader]))

(def configuration
  {:seon.config.flow.compute/queue-depth 2
   :seon.config.flow.compute/concurrency 2})

(def caps
  (config/result-caps (config/defaults)))

(defn submission
  [submission-id work-fn time-limit-ms]
  (flow/submit!!
   {::flow/submission-id submission-id
    ::flow/workload :compute
    ::flow/work-fn work-fn
    ::flow/time-limit-ms time-limit-ms}))

(defn evaluate-events
  [text]
  (let [events
        (reader/read
         {::reader/text text
          ::reader/ns 'user
          ::reader/features #{:clj}})
        ctx (sci/fork (eval/base))]
    {:submit-probe/read-events
     (mapv #(select-keys % [::reader/form ::reader/source]) events)
     :submit-probe/evaluations
     (mapv
      (fn [event]
        (eval/evaluate
         {:seon.cluster.run.form/source (::reader/source event)
          :seon.sci.eval/ctx ctx
          :seon.sci.admit/caps caps
          :seon.sci.eval/time-limit-ms 1000
          :seon.config/on-core-error :panic}))
      events)}))

(defn summary
  []
  (let [closure-result
        (submission
         :closure
         (fn [{::flow/keys [started!]}]
           (started!)
           {:submit-probe/answer (+ 20 22)
            :submit-probe/thread (.getName (Thread/currentThread))
            :submit-probe/virtual? (.isVirtual (Thread/currentThread))})
         1000)
        forms-result
        (submission
         :parsed-forms
         (fn [{::flow/keys [started!]}]
           (started!)
           (evaluate-events
            "(do (def offset 40) :defined)\n(+ offset 2)"))
         2000)
        sci-time-limit
        (submission
         :sci-time-limit
         (fn [{::flow/keys [started!]}]
           (started!)
           (eval/evaluate
            {:seon.cluster.run.form/source "(loop [] (recur))"
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 40
             :seon.config/on-core-error :panic}))
         2000)]
    {:submit-probe/closure closure-result
     :submit-probe/forms-round-trip forms-result
     :submit-probe/sci-time-limit sci-time-limit}))

(flow/install-work-launcher! {::flow/configuration configuration})
(try
  (prn (summary))
  (finally
    (flow/stop-installed-work-launcher!)))
