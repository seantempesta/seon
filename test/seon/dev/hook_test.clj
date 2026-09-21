(ns seon.dev.hook-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.operator :as operator]
            [seon.cluster.process :as operator.process]
            [seon.test-support :as test-support]))

(deftest source-progress-wording-is-not-the-hook-contract
  (let [directory (doto (io/file "tmp" (str "hook-progress-" (random-uuid))) .mkdirs)
        result-file (io/file directory "operator.edn")
        phases ["incremental scalar publication" "completely renamed phase\nwith a newline"]]
    (try
      (let [result {:seon.source/commit-id "publication-probe"
                    :seon.operator/progress (mapv #(hash-map :seon.source/progress %) phases)}
            _ (spit result-file (pr-str result))
            program
            (str "(binding [*in* (java.io.StringReader. \"{}\") "
                 "*out* (java.io.StringWriter.)] (load-file \"bin/seon-hook\")) "
                 "(let [events (atom [])] "
                 "(with-redefs [process/process (fn [request] "
                 "(let [path (second (drop-while #(not= \"--result-file\" %) (:cmd request)))] "
                 "(spit path (slurp " (pr-str (str result-file)) ")) "
                 "(future {:exit 0 :out \"not EDN {[ console noise\" :err \"warning\"}))) "
                 "log! (fn [& event] (swap! events conj event))] "
                 "(prn {:seon.probe/feedback (publish-source-paths [\"probe.clj\"] "
                 "{:current-source {:cluster \"default\" :timeout-seconds 1 :check-tests false}} \"rename\") "
                 ":seon.probe/progress (mapv #(edn/read-string (last %)) @events)})))")
            observed (operator.process/run-process!
                      {:seon.operator.subprocess/argv ["bb" "-e" program]
                       :seon.operator.subprocess/directory (io/file ".")
                       :seon.operator.subprocess/extra-env
                       {"SEON_HOOK_STATE_DIR" (.getCanonicalPath directory)}
                       :seon.operator.subprocess/deadline-ms
                       (* 1000 test-support/event-backstop-seconds)})]
        (is (zero? (:seon.operator.subprocess/exit observed))
            (:seon.operator.subprocess/error-output observed))
        (is (= phases
               (:seon.probe/progress (edn/read-string (:seon.operator.subprocess/output observed)))))
        (is (str/starts-with?
             (:seon.probe/feedback (edn/read-string (:seon.operator.subprocess/output observed)))
             "converged:"))
        (is (= "publication-probe" (:seon.source/commit-id result))))
      (finally (test-support/delete-recursively! directory)))))

(def ^:private source-worker-probe
  '(do
     (require '[seon.operator :as operator]
              '[seon.dev.clj-kondo :as kondo])
     (binding [*in* (java.io.StringReader. "{}")
               *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [published (atom [])
           successor-ids (atom [])
           transports (atom [])
           operator-root (io/file (System/getenv "SEON_HOOK_STATE_DIR") "operator")
           advertised {:seon.boot/cluster-name "default"
                       :seon.boot/pid (.pid (java.lang.ProcessHandle/current))
                       :seon.boot/start-instant
                       (java.util.Date/from (.get (.startInstant (.info (java.lang.ProcessHandle/current)))))
                       :seon.boot/prepl-host "127.0.0.1"
                       :seon.boot/prepl-port 1}
           advertisement-file (io/file operator-root "data" "clusters" "default" "prepl.edn")
           config {:current-source {:quiet-seconds 86400 :timeout-seconds 1}}]
       (io/make-parents advertisement-file)
       (spit advertisement-file (pr-str advertised))
       (io/make-parents source-worker-path)
       ;; This process owns the worker. Exercise its real file lock, pending
       ;; batch, admission and completion without launching a second worker.
       (spit source-worker-path
             (pr-str {:seon.hook/pid (.pid (java.lang.ProcessHandle/current))}))
       (with-redefs [kondo/ensure-dependency-cache! (fn [& _] {:seon.dev.clj-kondo/status :ready})
                     operator/prepl-value!
                     (fn [advertisement & _]
                       (swap! transports conj advertisement)
                       {:seon.source/commit-id "probe"})
                     load-config (constantly config)
                     publish-source-paths
                     (fn [paths _ id]
                       (binding [*out* (java.io.StringWriter.)]
                         (operator/connected! {:seon.operator/managed-root (str operator-root)
                                              :seon.operator/command :init
                                              :seon.operator/development-cluster "default"
                                              :seon.source/changed-paths paths}))
                       (swap! published conj {:seon.hook/paths paths
                                              :seon.hook/publication id})
                       (when (= 1 (count @published))
                         (doseq [edit [["b.clj"] ["c.clj" "b.clj"] ["a.clj"]]]
                           (swap! successor-ids conj
                                  (with-pending-lock
                                    #(enqueue-source-unlocked! edit)))))
                       "converged: probe")]
         (let [first-id (with-pending-lock
                          #(enqueue-source-unlocked! ["a.clj"]))]
           (run-source-worker!)
           (.delete advertisement-file)
           (prn {:seon.probe/first-id first-id
                 :seon.probe/published @published
                 :seon.probe/transports @transports
                 :seon.probe/advertised advertised
                 :seon.probe/absent
                 (try
                   (operator/connected! {:seon.operator/managed-root (str operator-root)
                                         :seon.operator/command :init})
                   :unexpected-success
                   (catch Exception e (:seon.error/layer (ex-data e))))
                 :seon.probe/successor-ids @successor-ids
                 :seon.probe/results
                 (mapv #(edn/read-string (slurp %))
                       (.listFiles source-results-path))
                 :seon.probe/pending? (.exists pending-source-path)
                 :seon.probe/worker? (.exists source-worker-path)}))))))

(deftest idle-edit-starts-without-quiet-delay
  (let [directory (doto (io/file "tmp" (str "hook-drain-" (random-uuid))) .mkdirs)]
    (try
      (let [result (operator.process/run-process!
                    {:seon.operator.subprocess/argv
                     ["bb" "-e" (pr-str source-worker-probe)]
                     :seon.operator.subprocess/directory (io/file ".")
                     :seon.operator.subprocess/extra-env
                     {"SEON_HOOK_STATE_DIR" (.getCanonicalPath directory)}
                     :seon.operator.subprocess/deadline-ms
                     (* 1000 test-support/event-backstop-seconds)})
            _ (is (zero? (:seon.operator.subprocess/exit result))
                  (:seon.operator.subprocess/error-output result))
            observed (edn/read-string (:seon.operator.subprocess/output result))
            published (:seon.probe/published observed)
            successor-ids (:seon.probe/successor-ids observed)]
        (is (= [["a.clj"] ["a.clj" "b.clj" "c.clj"]]
               (mapv :seon.hook/paths published))
            "Idle admission and completion drain immediately, even with a legacy quiet setting.")
        (is (= (repeat 2 (:seon.probe/advertised observed)) (:seon.probe/transports observed))
            "Both batches use the live advertisement without a registry census prerequisite.")
        (is (= :seon.operator/operation (:seon.probe/absent observed))
            "An absent advertisement is named, never inferred to mean a dead JVM.")
        (is (= 3 (count successor-ids)))
        (is (= 1 (count (set successor-ids)))
            "Every edit during publication joins the same successor.")
        (is (not= (:seon.probe/first-id observed) (first successor-ids)))
        (is (= (set (map :seon.hook/publication published))
               (set (map :seon.hook/publication (:seon.probe/results observed)))))
        (is (every? #(= "converged: probe" (:seon.hook/feedback %))
                    (:seon.probe/results observed)))
        (is (false? (:seon.probe/pending? observed)))
        (is (false? (:seon.probe/worker? observed))))
      (finally (test-support/delete-recursively! directory)))))
