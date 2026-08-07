(ns probe-work-launcher-binding
  "Surface: `seon.flow/submit!!` (compute) versus `seon.flow/submit!` (IO) and
   whether either conveys the submitting environment.

   Hypothesis: compute work conveys bindings (`bound-fn*` at
   src/seon/flow.clj:673) while IO work does not (`submit!` wraps only
   `::complete!` at src/seon/flow.clj:618). Because EVERY ambient environment
   value in Seon rides the same dynamic-binding carrier, the loss is not limited
   to schema declarations: the ambient database connection (`seon.db/*conn*`)
   and the effect request context (`seon.effect/*request-context*`) travel and
   vanish identically.

   The same probe also checks that two launchers sharing the process-root
   executors are independent."
  (:require [malli.core :as m]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.flow :as flow]
            [seon.schema :as schema])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(set! *warn-on-reflection* true)

(def launcher-configuration
  {:seon.config.flow.compute/concurrency 2
   :seon.config.flow.compute/queue-depth 8
   :seon.config.flow.io/concurrency 2
   :seon.config.flow.io/queue-depth 8})

(def environment-forms {:probe/marker [:= "environment"]})

(defn- environment-view
  "Everything an environment rides on the one dynamic-binding carrier."
  []
  {:probe/schema-declarations
   (try (m/validate (m/schema :probe/marker) "environment")
        (catch Throwable _ false))
   :probe/ambient-connection (some? (var-get #'db/*conn*))
   :probe/effect-request-context (some? (var-get #'effect/*request-context*))})

(defn- start! []
  (flow/start-work-launcher! {:seon.flow/configuration launcher-configuration}))

(defn- probe-body [launcher-a launcher-b]
  (let [submitter (environment-view)
        compute-result
        (flow/submit!!
         launcher-a
         {:seon.flow/submission-id :probe/compute
          :seon.flow/workload :compute
          :seon.flow/time-limit-ms 20000
          :seon.flow/work-fn (fn [_] (environment-view))})
        io-seen (promise)
        settled (CountDownLatch. 1)
        _ (flow/submit!
           launcher-a
           {:seon.flow/submission-id :probe/io
            :seon.flow/workload :io
            :seon.flow/time-limit-ms 20000
            :seon.flow/work-fn (fn [_] (deliver io-seen (environment-view)) :done)
            :seon.flow/complete! (fn [_] (.countDown settled))})
        _ (.await settled 20 TimeUnit/SECONDS)
        ;; Independence of two launchers over the shared root executors.
        _ (flow/stop-work-launcher! launcher-b)
        after-peer-stop
        (flow/submit!!
         launcher-a
         {:seon.flow/submission-id :probe/after
          :seon.flow/workload :compute
          :seon.flow/time-limit-ms 20000
          :seon.flow/work-fn (constantly :still-accepting)})
        compute-view (:seon.flow/value compute-result compute-result)
        io-view (deref io-seen 1000 :probe/never-ran)]
    {:probe/name 'probe-work-launcher-binding
     :probe/surface "seon.flow submit!! / submit! environment conveyance"
     :probe/verdict (if (= submitter compute-view io-view) :pass :fail)
     :probe/evidence
     {:probe/submitter-thread submitter
      :probe/compute-work compute-view
      :probe/io-work io-view
      :probe/peer-launcher-stop-independent after-peer-stop}}))

(defn run
  "Submit compute and IO work from inside one fully bound environment."
  [_options]
  (let [launcher-a (start!)
        launcher-b (start!)]
    (try
      (schema/call-with-forms
       (merge ((requiring-resolve 'seon.schema.edn/packaged-forms))
              environment-forms)
       (fn []
         (with-bindings {#'db/*conn* ::probe-connection
                         #'effect/*request-context* ::probe-request}
           (probe-body launcher-a launcher-b))))
      (finally
        (try (flow/stop-work-launcher! launcher-a) (catch Throwable _ nil))))))
