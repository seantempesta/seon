(ns seon.heap-test
  "The heap watch (`seon.heap`): a crossing is recorded on its cluster and
  names the evaluations in flight; a critical one rides the reply of each
  evaluation it found running; a reading that crosses nothing costs nothing."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.heap :as heap]
            [seon.problems :as problems]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support])
  (:import [java.lang.management ManagementFactory]))

(def ^:private dials
  {:seon.config.heap/warn-fraction 0.6
   :seon.config.heap/critical-fraction 0.8
   :seon.config.heap/growth-collections 4})

(defn- live-heap
  "The heap's used bytes after one full collection, and its cap."
  []
  (System/gc)
  (let [usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    [(.getUsed usage) (.getMax usage)]))

(defn- await!
  "Poll `ready?` until it answers truthy or `ms` pass; answers its last value."
  [ms ready?]
  (let [until (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (ready?)
          (when (< (System/currentTimeMillis) until)
            (Thread/sleep 10)
            (recur))))))

(defn- stored-running
  "The running evaluations the stored occurrences' evidence names."
  [connection]
  (into [] (comp (keep :seon.error/data-edn)
                 (map #(edn/read-string {:default tagged-literal} %))
                 (mapcat #(tree-seq coll? seq %))
                 (filter #(and (map? %) (contains? % ::kernel/thread))))
        (db/q '[:find [(pull ?occurrence [:seon.error/data-edn]) ...]
                :where [_ :seon.error/occurrences ?occurrence]]
              (db/db connection))))

(defn- held-evaluation!
  "A future holding one armed evaluation named by `form` until `release` is
  delivered: an evaluation in flight, as the running registry sees it."
  [ctx form release]
  (future (kernel/with-arm ctx 20000 (fn [_] (kernel/evaluating! form) @release))))

(deftest a-warning-crossing-is-recorded-on-its-cluster-naming-the-evaluation-in-flight
  ;; Real collections on a lowered warning bound: retaining past it makes the
  ;; next full collection's live reading a crossing, handed to the cluster's
  ;; work launcher and recorded as one open fault.
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "heap-watch"
           _ (test-support/apply-config! connection cluster-name {})
           effective (config/effective (db/db connection) cluster-name)
           members {:seon.boot/cluster-name cluster-name :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps effective)}
           launcher (flow/start-work-launcher!
                     {:seon.env/environment (env/environment members)
                      ::flow/configuration (select-keys effective flow/flow-workload-attributes)})
           environment (env/environment (assoc members :seon.flow/work-launcher launcher))
           [used max-bytes] (live-heap)
           lowered (assoc dials
                          :seon.config.heap/warn-fraction (double (/ (+ used (* 64 1024 1024)) max-bytes))
                          :seon.config.heap/critical-fraction 0.99)
           release (promise)
           ctx (eval/build-base-ctx (schema/handed-projection))
           held (held-evaluation! ctx '(my.probe/hold) release)
           seen (atom [])
           watch (heap/watch! lowered #(do (swap! seen conj %) (boot/heap-crossed! environment %)))]
       (try
         (await! 2000 #(seq (kernel/running-evaluations)))
         (let [retained (vec (repeatedly 128 #(byte-array (* 1024 1024))))]
           (System/gc)
           (is (await! 4000 #(seq @seen)) "the forced collection's live reading crossed the lowered bound")
           (is (= [:warn] (mapv :seon.heap/level @seen)))
           (let [fault (await! 4000 #(first (filter (fn [f] (= `heap/observation (get-in f [:seon.problems/clues :seon.error/operation]
                                                                                        (:seon.error/operation f))))
                                                     (problems/open-faults (db/db connection)))))]
                          (is (some? fault) "the crossing is one open fault on the cluster, shown in health")
             (is (= 128 (count retained)))
             (testing "the observation names the evaluation in flight"
               (is (some #(= 'my.probe/hold (::kernel/form-head %)) (stored-running connection))))))
         (finally
           (heap/unwatch! watch)
           (deliver release :done)
           @held
           (flow/stop-work-launcher! launcher)))))))

(deftest an-evaluation-in-flight-at-a-critical-crossing-carries-the-observation-in-its-reply
  ;; A critical live reading while one evaluation runs: the evaluation is
  ;; marked and its reply carries the crossing as a diagnostic; new arms close
  ;; until a collection reads the live heap under the warning bound again.
  (let [ctx (eval/build-base-ctx (schema/handed-projection))
        running (future (test-support/evaluate
                         {:seon.sci.eval/ctx ctx
                          :seon.schema/projection (schema/handed-projection)
                          :seon.cluster.eval/source "(deref (promise) 1500 :held)"
                          :seon.sci.admit/caps (config/result-caps config/defaults)
                          :seon.sci.eval/time-limit-ms 5000}))
        crossings (atom [])
        watch {:seon.heap/dials dials
               :seon.heap/state (volatile! {:seon.heap/armed nil :seon.heap/rising []})
               :seon.heap/on-crossing #(swap! crossings conj %)
}
        max-bytes (.getMax (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean)))]
    (is (await! 2000 #(some (fn [r] (= 'deref (::kernel/form-head r))) (kernel/running-evaluations)))
        "the evaluation is running, named by its form")
    (is (= :critical (heap/observe! watch {:seon.heap/used-bytes (long (* 0.9 max-bytes)) :seon.heap/max-bytes max-bytes
                                           :seon.heap/major? true :seon.heap/live? true})))
    (testing "new arms close at critical and reopen once the live heap is under the warning bound"
      (is (some? (kernel/memory-pressure)))
      (System/gc)
      (is (nil? (kernel/memory-pressure))))
    (let [evaluation (deref running 5000 ::hung)]
      (is (= :held (:seon.sci.admit/value evaluation)))
      (is (= :critical (get-in evaluation [:seon.sci.admit/record :seon.heap/crossing :seon.heap/level]))
          "the evaluation's record carries the crossing")
      (is (str/includes? (str (:seon.cluster.eval/diagnostics evaluation)) ":seon.heap/level :critical")
          "its reply shows the crossing beside the value")
      (is (= 1 (count @crossings))))))

(deftest a-reading-that-crosses-nothing-costs-nothing
  ;; The watch's whole cost while nothing crosses is one fold per collection
  ;; on the JVM's notification thread: no forced collection, no observation,
  ;; and micro-seconds of CPU against a collection's milliseconds.
  (let [crossings (atom 0)
        watch {:seon.heap/dials dials
               :seon.heap/state (volatile! {:seon.heap/armed nil :seon.heap/rising []})
               :seon.heap/on-crossing (fn [_] (swap! crossings inc))
}
        threads ^java.lang.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        n 2000
        cpu-before (.getCurrentThreadCpuTime threads)
        asked (into #{} (map (fn [i] (heap/observe! watch {:seon.heap/used-bytes (+ 1000 (mod i 3)) :seon.heap/max-bytes 100000
                                                         :seon.heap/major? (zero? (mod i 2)) :seon.heap/live? false})))
                    (range n))
        per-reading-us (/ (- (.getCurrentThreadCpuTime threads) cpu-before) 1000.0 n)]
    (is (= #{nil} asked) "no reading asks for a forced collection or records a crossing")
    (is (zero? @crossings))
    (is (< per-reading-us 200.0) "a reading costs micro-seconds of CPU, not a collection's milliseconds")))

(deftest a-reading-counts-only-the-heap-its-collector-manages
  ;; Live run 10 (default 577091e04): the 8 recorded growth crossings read 1,139 to 1,405 MB while the live old
  ;; generation was about 600 MB. `reading` summed every pool of the collection's after-usage: the non-heap pools
  ;; (Metaspace, CodeHeap, Compressed Class Space, ~290 MB) and, after a G1 concurrent cycle, which collects only
  ;; the old generation, the eden it never collected. The heap after a collection is the after-usage of the pools
  ;; its collector manages.
  (let [reading #'heap/reading
        seen (java.util.concurrent.LinkedBlockingQueue.)
        listener (reify javax.management.NotificationListener
                   (handleNotification [_ notification _]
                     (when (= com.sun.management.GarbageCollectionNotificationInfo/GARBAGE_COLLECTION_NOTIFICATION
                              (.getType notification))
                       (.offer seen (com.sun.management.GarbageCollectionNotificationInfo/from
                                      ^javax.management.openmbean.CompositeData (.getUserData notification))))))
        emitters (vec (ManagementFactory/getGarbageCollectorMXBeans))]
    (try
      (doseq [emitter emitters] (.addNotificationListener ^javax.management.NotificationEmitter emitter listener nil nil))
      (System/gc)
      (let [^com.sun.management.GarbageCollectionNotificationInfo info (.poll seen 5 java.util.concurrent.TimeUnit/SECONDS)
            managed (set (some #(when (= (.getGcName info) (.getName ^java.lang.management.GarbageCollectorMXBean %))
                                  (.getMemoryPoolNames ^java.lang.management.GarbageCollectorMXBean %))
                               emitters))
            after (.getMemoryUsageAfterGc (.getGcInfo info))
            used (fn [names] (reduce + 0 (map #(.getUsed ^java.lang.management.MemoryUsage (get after %)) names)))]
        (is (some? info) "a collection was observed")
        (is (= (used managed) (:seon.heap/used-bytes (reading info)))
            "the heap after a collection is what its collector's pools hold")
        (is (< (:seon.heap/used-bytes (reading info)) (used (keys after)))
            "the non-heap pools are not heap"))
      (finally
        (doseq [emitter emitters]
          (.removeNotificationListener ^javax.management.NotificationEmitter emitter listener))))))
