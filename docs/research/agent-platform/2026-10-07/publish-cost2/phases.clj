(let [directory (seon.fs/source-directory) path "src/my/background.clj" file (clojure.java.io/file directory path)
      _ (spit file (str (slurp file) "\n; publication allocation probe\n"))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread (.threadId (Thread/currentThread)) rows (java.util.ArrayList.)
      record (fn [label] (.add rows {:phase label :nanos (System/nanoTime) :bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread)}))
      _ (record "begin")
      result (with-bindings {#'seon.cluster/*source-progress!* record}
               (seon.cluster/refresh-source! (System/getProperty "seon.operator.root") [path] nil directory))
      _ (record "end")]
 {:result (select-keys result [:seon.source/built? :seon.error/type :seon.error/message])
  :phases (mapv (fn [[a b]] {:phase (:phase b) :ms (/ (- (:nanos b) (:nanos a)) 1e6) :bytes (- (:bytes b) (:bytes a))}) (partition 2 1 (vec rows)))})
