(ns n3-crash-child
  "Phase 2 child: boot, arm the loop, trigger alice, then hold.

  The parent kill -9s this JVM ~1.2s after the trigger — inside the
  model-call window (the live phase-1 numbers: claim +0.65s, plan
  +2.7s), so death lands on a CLAIMED run with NO plan. The hold is a
  plain sleep: the loop works on its own threads; this thread only
  keeps the JVM alive to be murdered."
  (:require [seon.cluster :as cluster]
            [seon.cluster.wake :as wake]
            [seon.cluster.loop :as cluster.loop]
            [seon.config :as config]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]))

(defn- stamp [& parts]
  (println (str "[child " (.toString (java.time.LocalTime/now)) "] "
                (apply str parts)))
  (flush))

(def root "tmp/n3-crash/clusters")
(def instance (cluster/start! {:seon.boot/cluster-name "live"
                               :seon.boot/root root}))
(def connection (:seon.boot/cluster-connection instance))
(def dials (config/effective @connection "live"))
(def wake-channel (async/chan (async/sliding-buffer 1)))
(def faults (async/chan (async/sliding-buffer 8)))
(def handle
  {:seon.store/branch-connection connection
   :seon.cluster.run/process
   (cluster/process-identity (:seon.boot/advertisement instance))
   :seon.cluster.wake/channel wake-channel
   :seon.cluster.loop/provider
   {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
    :seon.ai/model "deepseek-chat"
    :seon.ai/api-key-variable "DEEPSEEK_API_KEY"
    :seon.ai/timeout-ms 60000}
   :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
   :seon.sci.admit/caps (select-keys dials
                                     [:seon.config.eval.result/max-depth
                                      :seon.config.eval.result/max-collection
                                      :seon.config.eval.result/max-string
                                      :seon.config.eval.result/max-nodes])
   :seon.config.eval/time-limit-ms (:seon.config.eval/time-limit-ms dials)
   :seon.config/on-core-error (:seon.config/on-core-error dials)})
(wake/listen! {:seon.cluster.wake/connection connection
               :seon.cluster.wake/attributes (wake/wake-attributes)
               :seon.cluster.wake/channel wake-channel
               :seon.cluster.wake/fault-channel faults
               :seon.cluster.wake/key ::crash})
(def graph (flow/create-flow
            {:procs {::loop {:proc (flow/process #'cluster.loop/step
                                                 {:workload :io})
                             :args handle}}
             :conns []}))
(flow/start graph)
(flow/resume graph)
(async/offer! wake-channel ::boot)
(stamp "armed as process "
       (:seon.boot/pid (:seon.boot/advertisement instance))
       " — this is the pid that will die holding a claimed run")
(d/transact connection [{:seon.cluster.agent/id "alice"}])
(d/transact connection
            [{:seon.cluster.message/id (str (random-uuid))
              :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
              :seon.cluster.message/content
              "Compute the product of 1..7 and complete with the answer."
              :seon.cluster.message/at (java.util.Date.)}])
(stamp "TRIGGERED — kill -9 me ~1.2s from this line "
       "(claim lands ~+0.65s, plan ~+2.7s, so 1.2s is inside the "
       "model-call window)")
(Thread/sleep 300000)
