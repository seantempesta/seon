(ns seon.sci.ctx
  "Build one shared SCI base and fork it for each evaluation."
  (:require [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.agent.lifecycle :as lifecycle]
            [seon.schema :as schema]))

(schema/register! ::ctx 'some?)
(schema/register! ::base-ctx 'some?)
(schema/register! ::fork-request
                  [:map {:closed true}
                   [::base-ctx {:optional true} ::base-ctx]
                   [:interrupt-fn 'fn?]])

(def base
  "The process-shared SCI context."
  (delay
    (let [lifecycle-ns (sci/create-ns 'seon.agent.lifecycle)]
      (sci/init
       {:namespaces
        {'clojure.core sci.interrupt/clojure-core
         'clojure.string sci.interrupt/clojure-string
         'seon.agent.lifecycle
         {'wait (sci/copy-var lifecycle/wait lifecycle-ns)
          'complete (sci/copy-var lifecycle/complete lifecycle-ns)
          'pause (sci/copy-var lifecycle/pause lifecycle-ns)
          'resume (sci/copy-var lifecycle/resume lifecycle-ns)
          'terminate (sci/copy-var lifecycle/terminate lifecycle-ns)}}
        :classes
        {'Throwable Throwable
         'java.lang.Throwable Throwable
         'Error Error
         'java.lang.Error Error}}))))

(defn fork
  "Fork the shared `ctx` and install one evaluation's `:interrupt-fn`."
  {:malli/schema [:=> [:cat ::fork-request] ::ctx]}
  [{::keys [base-ctx] :keys [interrupt-fn]}]
  (assoc (sci/fork (or base-ctx @base))
         :interrupt-fn interrupt-fn))
