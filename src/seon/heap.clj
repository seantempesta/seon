(ns seon.heap
  "Early warning before the heap runs out, from what the JVM already reports.

  One listener per running cluster on every `GarbageCollectorMXBean`'s
  `GarbageCollectionNotificationInfo`: nothing polls, and the JVM's
  notification thread calls it after each collection. [[step]] folds each
  collection's heap after it into the watch's state. A bound crossed
  (`:seon.config.heap/warn-fraction`, `:seon.config.heap/critical-fraction`)
  is first confirmed as live bytes by one forced full collection; a heap
  after major collections rising across `:seon.config.heap/growth-collections`
  of them in a row is a growth run. Either answers one [[observation]], handed
  to the watch's `on-crossing` (boot records it as its cluster's error,
  `seon.cluster.boot/heap-crossed!`). A critical crossing also marks every
  running evaluation (`seon.sci.kernel/mark-running!`), whose reply then
  carries it, and closes new arms (`seon.sci.kernel/enter-memory-pressure!`).
  A reading below the warning bound re-arms the bounds."
  (:require [seon.cache :as cache]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.profile :as profile]
            [seon.sci.kernel :as kernel])
  (:import [com.sun.management GarbageCollectionNotificationInfo]
           [java.lang.management GarbageCollectorMXBean ManagementFactory MemoryUsage]
           [javax.management Notification NotificationEmitter NotificationListener]
           [javax.management.openmbean CompositeData]))

(def ^:private levels
  "The bounds in rising order, each with its dial."
  [[:warn :seon.config.heap/warn-fraction] [:critical :seon.config.heap/critical-fraction]])

(defn level
  "The highest level whose bound `used` bytes reach, of `max-bytes`, or nil."
  {:malli/schema [:=> [:cat :seon.heap/dials :seon.heap/used-bytes :seon.heap/max-bytes] [:maybe :seon.heap/level]]}
  [dials used max-bytes]
  (reduce (fn [found [k dial]] (if (>= used (* (double (get dials dial)) max-bytes)) k found)) nil levels))

(defn step
  "The watch state after one `reading`, and what it asks for, if anything.

  A level above the armed one asks for a forced full collection (`:measure`);
  that collection's live reading crossing a level above the armed one is a
  crossing, and arms that level. A reading below the warning bound disarms.
  Major readings strictly rising `growth-collections` times in a row are one
  `:growth` crossing, and the run starts again."
  {:malli/schema [:=> [:cat :seon.heap/dials :seon.heap/state :seon.heap/reading] :seon.heap/step]}
  [dials {armed :seon.heap/armed rising :seon.heap/rising :as state}
   {used :seon.heap/used-bytes max-bytes :seon.heap/max-bytes major? :seon.heap/major? live? :seon.heap/live?}]
  (let [seen (level dials used max-bytes)
        rank {nil 0 :warn 1 :critical 2}
        rising (cond (not major?) rising
                     (and (seq rising) (> used (peek rising))) (conj rising used)
                     :else [used])
        grew? (= (count rising) (:seon.config.heap/growth-collections dials))
        state (assoc state :seon.heap/rising (if grew? [] rising))]
    (cond
      (nil? seen) [(assoc state :seon.heap/armed nil) (when grew? :growth)]
      (<= (rank seen) (rank armed)) [state (when grew? :growth)]
      live? [(assoc state :seon.heap/armed seen) seen]
      :else [state :measure])))

(defn- top-threads
  "The `limit` live threads that allocated most over their lifetime, by name
  and state: a crossing's suspects, read once from the thread bean."
  {:malli/schema [:=> [:cat [:int {:min 1}]] :seon.heap/threads]}
  [limit]
  (let [thread-bean ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        ids (.getAllThreadIds thread-bean)
        allocated (.getThreadAllocatedBytes thread-bean ids)
        infos (.getThreadInfo thread-bean ids 0)]
    (into [] (comp (keep (fn [i] (when-let [info (aget infos i)]
                                   {:seon.heap/thread (.getThreadName info)
                                    :seon.heap/thread-state (keyword (.toLowerCase (str (.getThreadState info))))
                                    :seon.heap/allocated-bytes (aget allocated i)})))
                   (filter #(pos? (:seon.heap/allocated-bytes %)))
                   (take limit))
          (sort-by #(- (aget allocated %)) (range (alength ids))))))

(defn observation
  "The crossing `kind` at `used` of `max-bytes` heap bytes, as one error value.

  References and numbers only: the bound crossed, the evaluations running
  (`seon.sci.kernel/running-evaluations`), the threads that allocated most,
  the process cache's weighed bytes and the functions that took most self
  time."
  {:malli/schema [:=> [:cat :seon.heap/dials :seon.heap/kind :seon.heap/used-bytes :seon.heap/max-bytes]
                  :seon.heap/crossing-error]}
  [dials kind used max-bytes]
  (let [bound (if (= :critical kind) :seon.config.heap/critical-fraction :seon.config.heap/warn-fraction)]
    (refusal/diagnostic
     (java.util.Date.) :seon.heap/watch 'seon.heap/observation
     {:seon.error/message (case kind
                            :critical "The live heap crossed the critical bound: this JVM is close to running out of memory, and new evaluations refuse until it falls below the warning bound."
                            :warn "The live heap crossed the warning bound."
                            :growth "The heap after major collections rose across consecutive collections.")
      :seon.heap/level kind
      :seon.heap/used-bytes used
      :seon.heap/max-bytes max-bytes
      :seon.heap/threshold-bytes (long (* (double (get dials bound)) max-bytes))
      :seon.sci.kernel/in-flight (kernel/running-evaluations)
      :seon.heap/threads (top-threads 5)
      :seon.cache/weighted-bytes (:seon.cache/weighted-bytes (cache/status))
      :seon.profile/by-self (:seon.profile/by-self (profile/summary (profile/cells) 5))})))

(defn observe!
  "What one `reading` asked of a watch's `fold`, done; nil when it asked nothing.

  It folds the reading into the watch's state, then forces the full
  collection that measures live bytes, or hands the crossing to the watch's
  `on-crossing`. A critical crossing first marks the running evaluations and
  closes new arms."
  {:malli/schema [:=> [:cat :seon.heap/fold :seon.heap/reading] [:maybe :seon.heap/asked]]}
  [{dials :seon.heap/dials state :seon.heap/state on-crossing :seon.heap/on-crossing} reading]
  (let [[next-state asked] (step dials @state reading)]
    (vreset! state next-state)
    (case asked
      nil nil
      ;; the forced collection's own notification judges live bytes
      :measure (System/gc)
      (let [crossing (observation dials asked (:seon.heap/used-bytes reading) (:seon.heap/max-bytes reading))]
        (when (= :critical asked)
          (kernel/mark-running! crossing)
          (kernel/enter-memory-pressure! dials (:seon.sci.kernel/in-flight crossing)))
        (on-crossing crossing)))
    asked))

(defn- reading
  "The heap after the collection `info` reports: the after-usage of the pools
  its collector manages (`GarbageCollectorMXBean/getMemoryPoolNames`), so
  non-heap pools and the eden a concurrent cycle never collects are not read."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The JVM hands each collection as a GarbageCollectionNotificationInfo, a host object with no data form.", :gen/elements [nil]}]]
                  :seon.heap/reading]}
  [^GarbageCollectionNotificationInfo info]
  {:seon.heap/used-bytes (let [after (.getMemoryUsageAfterGc (.getGcInfo info))
                               collector (some #(when (= (.getGcName info) (.getName ^GarbageCollectorMXBean %)) %)
                                               (ManagementFactory/getGarbageCollectorMXBeans))]
                           (reduce + 0 (map #(.getUsed ^MemoryUsage (get after %))
                                            (.getMemoryPoolNames ^GarbageCollectorMXBean collector))))
   :seon.heap/max-bytes (.getMax (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean)))
   :seon.heap/major? (or (= "end of major GC" (.getGcAction info))
                         (= "G1 Concurrent GC" (.getGcName info)))
   :seon.heap/live? (= "System.gc()" (.getGcCause info))})

(defn notified!
  "Observe the collection one JMX `notification` reports with `fold`.

  The JVM's notification thread is an entrant boundary: a failure is printed
  whole with the watch's operation, and the next collection is observed."
  {:seon/entrant true
   :malli/schema [:=> [:cat :seon.heap/fold [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The JVM's notification thread hands a javax.management.Notification, a host object with no data form.", :gen/elements [nil]}]] :nil]}
  [fold ^Notification notification]
  (try
    (when (= GarbageCollectionNotificationInfo/GARBAGE_COLLECTION_NOTIFICATION (.getType notification))
      (observe! fold (reading (GarbageCollectionNotificationInfo/from ^CompositeData (.getUserData notification)))))
    (catch Throwable failure
      (fault/print-unrecorded! {:seon.error/message "SEON HEAP WATCH (observation failed, not stored)"
                                :seon.error/operation `notified!}
                               [failure])))
  nil)

(defn watch!
  "A watch listening on every collector; [[unwatch!]] removes it.

  Each crossing under `dials` is handed to `on-crossing` on the JVM's
  notification thread."
  {:malli/schema [:=> [:cat :seon.heap/dials :seon.heap/on-crossing] :seon.heap/watch]}
  [dials on-crossing]
  (let [emitters (vec (ManagementFactory/getGarbageCollectorMXBeans))
        fold {:seon.heap/dials dials
              :seon.heap/state (volatile! {:seon.heap/armed nil :seon.heap/rising []})
              :seon.heap/on-crossing on-crossing}
        ;; the JVM calls through the Var, so a reload's `notified!` is the one called
        listener (reify NotificationListener
                   (handleNotification [_ notification _] (notified! fold notification)))]
    (doseq [emitter emitters]
      (.addNotificationListener ^NotificationEmitter emitter listener nil nil))
    (assoc fold :seon.heap/listener listener :seon.heap/emitters emitters)))

(defn unwatch!
  "Remove `watch`'s listener from every collector it listens to."
  {:malli/schema [:=> [:cat :seon.heap/watch] :nil]}
  [{listener :seon.heap/listener emitters :seon.heap/emitters}]
  (doseq [emitter emitters]
    (.removeNotificationListener ^NotificationEmitter emitter ^NotificationListener listener))
  nil)
