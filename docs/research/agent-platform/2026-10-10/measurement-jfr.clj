;; JVM eval_clj as astra-measure, after measurement-probe.clj creates the case agent.
;; The recording is owned scratch; the returned aggregates are the evidence.
(require 'seon.mcp 'clojure.edn)
(let [path (java.nio.file.Path/of "/Users/sean/src/seon/tmp/astra-measure/request.jfr" (make-array String 0))
      mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)]
  (with-open [recording (jdk.jfr.Recording.)]
    (doto (.enable recording "jdk.ObjectAllocationSample") (.with "throttle" "off") (.withStackTrace))
    (.enable recording "jdk.CPUTimeSample")
    (.enable recording "jdk.ExecutionSample")
    (.start recording)
    (let [allocated (.getTotalThreadAllocatedBytes mx)
          [texts failed?] (#'seon.mcp/eval-clj
                          {:seon.mcp/cluster "default" :seon.mcp/agent "astra-measure-cases"
                           :seon.mcp/code "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"})
          total (- (.getTotalThreadAllocatedBytes mx) allocated)]
      (.stop recording)
      (.dump recording path)
      {:window-alloc-mb (/ total 1e6) :failed? failed?
       :reply (clojure.edn/read-string (last texts))
       :events
       (with-open [events (jdk.jfr.consumer.RecordingFile. path)]
         (loop [counts {} groups {} owners {}]
           (if (.hasMoreEvents events)
             (let [event (.readEvent events) type (.getName (.getEventType event))]
               (if (= type "jdk.ObjectAllocationSample")
                 (let [thread (.getThread event) weight (.getLong event "weight")
                       group (cond (.isVirtual thread) :virtual
                                   (= "seon-test-body" (.getJavaName thread)) :test-body
                                   (.startsWith (.getJavaName thread) "async-mixed-") :pool
                                   :else :other)
                       owner (some (fn [frame]
                                     (let [method (.getMethod ^jdk.jfr.consumer.RecordedFrame frame)
                                           name (.getName (.getType method))]
                                       (when (.startsWith name "seon.") name)))
                                   (some-> event .getStackTrace .getFrames))]
                   (recur (update counts type (fnil inc 0))
                          (update groups group (fnil + 0) weight)
                          (update owners (or owner "outside-seon-stack") (fnil + 0) weight)))
                 (recur (update counts type (fnil inc 0)) groups owners)))
             {:counts counts :groups-mb (update-vals groups #(/ % 1e6))
              :leaf-seon-allocation-mb (mapv (fn [[k v]] [k (/ v 1e6)]) (take 10 (sort-by val > owners)))})))})))
