(ns probe-registry-thread-fallback
  "Surface: seon.schema/seon-registry, installed as Malli's PROCESS-GLOBAL default
   registry, whose declaration population is selected by THREAD-LOCAL dynamic
   vars (*packaged-forms*, *projection-state*, *projection*).

   Hypothesis: an environment's declarations are visible only on the thread that
   established the binding. Work handed to any other thread — a flow proc, an
   executor task, a virtual thread, a core.async go block — silently falls back
   to the process-global packaged population and validates against the WRONG
   declarations without any error."
  (:require [clojure.core.async :as async]
            [malli.core :as m]
            [seon.schema :as schema])
  (:import [java.util.concurrent Executors]))

(set! *warn-on-reflection* true)

(def environment-forms
  {:probe/marker [:= "environment"]})

(defn- resolves-in-environment?
  "True when Malli's default registry can see this environment's declaration."
  []
  (try
    (m/validate (m/schema :probe/marker) "environment")
    (catch Throwable _ false)))

(defn run
  "Compare declaration visibility on the binding thread and on foreign threads."
  [_options]
  (let [forms (merge ((requiring-resolve 'seon.schema.edn/packaged-forms))
                     environment-forms)]
    (schema/call-with-forms
     forms
     (fn []
       (let [on-binding-thread (resolves-in-environment?)
             on-plain-thread
             (let [result (promise)]
               (doto (Thread. ^Runnable #(deliver result
                                                  (resolves-in-environment?))
                              "probe-plain-thread")
                 (.start)
                 (.join))
               @result)
             on-virtual-thread
             (let [executor (Executors/newVirtualThreadPerTaskExecutor)]
               (try
                 (.get (.submit executor
                                ^Callable (fn [] (resolves-in-environment?))))
                 (finally (.close executor))))
             on-future (deref (future (resolves-in-environment?)))
             on-go-block (async/<!! (async/go (resolves-in-environment?)))]
         {:probe/name 'probe-registry-thread-fallback
          :probe/surface
          "seon.schema/seon-registry default + thread-local declaration binding"
          :probe/verdict
          (if (= true on-binding-thread on-plain-thread on-virtual-thread
                 on-future on-go-block)
            :pass
            :fail)
          :probe/evidence
          {:probe/binding-thread on-binding-thread
           :probe/plain-thread on-plain-thread
           :probe/virtual-thread on-virtual-thread
           :probe/future on-future
           :probe/go-block on-go-block}})))))
