(require '[clojure.core.async :as async]
         '[clojure.core.async.flow :as flow]
         '[clojure.string :as str]
         '[seon.flow :as seon.flow])

(defn- thread-counts
  []
  (let [threads (keys (Thread/getAllStackTraces))
        platform (remove #(.isVirtual ^Thread %) threads)]
    {:platform (count platform)
     :async-mixed
     (count (filter #(str/starts-with? (.getName ^Thread %)
                                      "async-mixed-")
                    platform))}))

(defn- join!
  [mode source fault-channel ordinal]
  (case mode
    "legacy-pipeline"
    (async/pipeline
     1 fault-channel
     (map #(merge % {:seon.cluster.agent/id (str "agent-" ordinal)}))
     source false)

    "virtual-io"
    (seon.flow/join-error-fanout!
     {::seon.flow/started {:error-chan source}
      ::seon.flow/fault-channel fault-channel
      ::seon.flow/tag {:seon.cluster.agent/id (str "agent-" ordinal)}})))

(defn- exercise-fleet!
  [mode source-count]
  (let [fault-channel (async/chan source-count)
        sources (vec (repeatedly source-count #(async/chan 1)))
        joins (mapv #(join! mode %2 fault-channel %1)
                    (range source-count)
                    sources)]
    (doseq [[ordinal source] (map-indexed vector sources)]
      (async/>!! source {::flow/pid (keyword (str "proc-" ordinal))}))
    (dotimes [_ source-count]
      (when-not (async/<!! fault-channel)
        (throw (ex-info "Fault fan-out closed before delivering the fleet."
                        {:mode mode :source-count source-count}))))
    {:fault-channel fault-channel
     :sources sources
     :joins joins}))

(defn- stop-fleet!
  [{:keys [fault-channel sources joins]}]
  (doseq [source sources]
    (async/close! source))
  (doseq [join joins]
    (async/<!! join))
  (async/close! fault-channel))

(defn- warm-dispatch!
  [source-count]
  (stop-fleet! (exercise-fleet! "virtual-io" source-count)))

(let [[mode count-text] *command-line-args*
      source-count (Long/parseLong count-text)]
  (when-not (#{"legacy-pipeline" "virtual-io"} mode)
    (throw (ex-info "Mode must be legacy-pipeline or virtual-io."
                    {:mode mode})))
  (warm-dispatch! source-count)
  (let [before (thread-counts)
        fleet (exercise-fleet! mode source-count)
        after (thread-counts)]
    (prn {:mode mode
          :sources source-count
          :before before
          :after after
          :delta (merge-with - after before)})
    (flush)
    (stop-fleet! fleet)))
