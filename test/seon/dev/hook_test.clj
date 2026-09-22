(ns seon.dev.hook-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as process]
            [seon.operator :as operator]
            [seon.test-support :as support]))

(def ^:private terminal-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}") *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [accepted {:seon.source/commit-id #uuid "b2b94a47-7638-4187-9a8f-b71b95532880"
                     :seon.source/reloaded-namespaces '[my.note]
                     :seon.source/arming-identities #{[:seon.fn/sym 'my.note/create]}}
           refused {:seon.error/message "probe refusal"
                    :seon.error/operation 'seon.cluster/refresh-source!
                    :seon.error/layer :seon.cluster/publication}
           requests (atom [])
           config {:current-source {:enabled true :root "." :cluster "default"
                                    :timeout-seconds 2}}
           event {:hook_event_name "PostToolUse" :tool_name "Edit"
                  :tool_input {:file_path "src/my/note.clj"}}]
       (with-open [server (java.net.ServerSocket. 0)]
         (.setSoTimeout server 2000)
         (let [worker
               (future
                 (doseq [result [accepted refused {}]]
                   (with-open [socket (.accept server)
                               reader (java.io.PushbackReader. (io/reader socket))
                               writer (io/writer socket)]
                     (.setSoTimeout socket 2000)
                     (swap! requests conj (edn/read reader))
                     (.write writer (str (pr-str {:tag :out :val "arbitrary progress {[\n"}) "\n"
                                         (pr-str {:tag :ret :val (pr-str result)}) "\n"))
                     (.flush writer)
                     (when-not (= ::eof (edn/read {:eof ::eof} reader))
                       (throw (ex-info "More than one request on the connection" {}))))))
               endpoint {:seon.boot/prepl-host "127.0.0.1"
                         :seon.boot/prepl-port (.getLocalPort server)}
               replies
               (with-redefs [operator/advertisement (fn [& _] endpoint)]
                 [(current-source-feedback event config [])
                  (current-source-feedback event config ["src/seon/id.clj"])
                  (current-source-feedback event (assoc-in config [:current-source :enabled] false) [])
                  (current-source-feedback event config [])])]
           @worker
           (prn {:seon.probe/replies replies :seon.probe/requests @requests
                 :seon.probe/accepted accepted :seon.probe/refused refused}))))))

(deftest one-request-returns-terminal-evidence-and-pause-sends-nothing
  (let [result (process/run-process!
                {:seon.operator.subprocess/argv ["bb" "-e" (pr-str terminal-probe)]
                 :seon.operator.subprocess/directory "."
                 :seon.operator.subprocess/deadline-ms
                 (* 1000 support/event-backstop-seconds)})]
    (is (zero? (:seon.operator.subprocess/exit result))
        (:seon.operator.subprocess/error-output result))
    (when (zero? (:seon.operator.subprocess/exit result))
      (let [observed (edn/read-string (:seon.operator.subprocess/output result))
            [accepted refused paused degraded] (:seon.probe/replies observed)
            requests (:seon.probe/requests observed)]
        (is (= 3 (count requests)))
        (is (= (str "accepted: " (pr-str (:seon.probe/accepted observed))) accepted))
        (is (= (str "refused: " (pr-str (:seon.probe/refused observed))) refused))
        (is (nil? paused))
        (is (str/starts-with? degraded "degraded:"))
        (is (every? #(= 1 (count (filter (fn [form] (and (seq? form) (= 'seon.cluster/refresh-source! (first form))))
                                        (tree-seq coll? seq %)))) requests))
        (is (str/includes? (pr-str (second requests)) "src/seon/id.clj"))
        (is (not (str/includes? accepted "arbitrary progress")))))))

(deftest output-does-not-extend-the-hook-terminal-bound
  (with-open [server (java.net.ServerSocket. 0)]
    (.setSoTimeout server 1000)
    (let [worker (future
                   (try
                   (with-open [socket (.accept server)
                               reader (java.io.PushbackReader. (io/reader socket))
                               writer (io/writer socket)]
                     (edn/read reader)
                     (try
                       (dotimes [_ 30]
                         (.write writer (str (pr-str {:tag :out :val "still working"}) "\n"))
                         (.flush writer)
                         (Thread/sleep 10))
                       (catch java.io.IOException _ nil)))
                   (catch java.io.IOException _ nil)))
          began (System/nanoTime)
          failure (try
                    (operator/prepl-value!
                     {:seon.boot/prepl-host "127.0.0.1"
                      :seon.boot/prepl-port (.getLocalPort server)} "(+ 1 1)" 1000 nil 50)
                    nil
                    (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
          elapsed (/ (- (System/nanoTime) began) 1e6)]
      @worker
      (is (map? failure))
      (is (str/includes? (:seon.error/message failure) "outcome unknown"))
      (is (< elapsed 500) "Output cannot renew the hook's total wait bound."))))
