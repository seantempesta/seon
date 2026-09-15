(ns seon.dev.hook-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.operator.state :as operator.state]
            [seon.test-support :as test-support]))

(def ^:private source-worker-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}")
               *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [published (atom [])
           successor-ids (atom [])
           config {:current-source {:quiet-seconds 86400 :timeout-seconds 1}}]
       (io/make-parents source-worker-path)
       ;; This process owns the worker. Exercise its real file lock, pending
       ;; batch, admission and completion without launching a second worker.
       (spit source-worker-path
             (pr-str {:seon.hook/pid (.pid (java.lang.ProcessHandle/current))}))
       (with-redefs [load-config (constantly config)
                     publish-source-paths
                     (fn [paths _ id]
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
           (prn {:seon.probe/first-id first-id
                 :seon.probe/published @published
                 :seon.probe/successor-ids @successor-ids
                 :seon.probe/results
                 (mapv #(edn/read-string (slurp %))
                       (.listFiles source-results-path))
                 :seon.probe/pending? (.exists pending-source-path)
                 :seon.probe/worker? (.exists source-worker-path)}))))))

(deftest idle-edit-starts-without-quiet-delay
  (let [directory (doto (io/file "tmp" (str "hook-drain-" (random-uuid))) .mkdirs)]
    (try
      (let [result (operator.state/run-process!
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
