(ns seon.dev.hook-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as process]
            [seon.operator :as operator]
            [seon.test-support :as support]))

;; Saves never publish (D1 O0): every route to default — its advertisement,
;; the prepl and a live-root evaluation — records instead of answering.
(def ^:private save-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}") *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [calls (atom [])
           record (fn [route] (fn [& _] (swap! calls conj route) nil))
           config {:lint {:enabled false} :markdown-lint {:enabled false}
                   :docstring-lint {:enabled false} :shell-writes {:enabled false}
                   :schema-admission {:enabled false}
                   :contract-compile {:enabled false}}
           events [{:tool_name "Edit" :tool_input {:file_path "src/seon/id.clj"}}
                   {:tool_name "Write" :tool_input {:file_path "resources/seon/schemas/error.edn"}}
                   {:tool_name "Edit" :tool_input {:file_path "src/seon/cluster/boot.clj"}}
                   {:tool_name "Bash" :tool_input {:command "true"}}]
           replies (with-redefs [load-config (constantly config)
                                 operator/advertisement (record :advertisement)
                                 operator/prepl-value! (record :prepl)
                                 operator/live-root-value! (record :live-root)]
                     (mapv (fn [event]
                             (binding [*in* (java.io.StringReader.
                                             (json/generate-string
                                              (assoc event :hook_event_name "PostToolUse")))]
                               (with-out-str (-main))))
                           events))]
       (prn {:seon.probe/calls @calls :seon.probe/replies replies
             :seon.probe/publisher (resolve 'current-source-feedback)}))))

(deftest named-and-pathless-saves-never-publish
  (let [result (process/run-process!
                {:seon.operator.subprocess/argv ["bb" "-e" (pr-str save-probe)]
                 :seon.operator.subprocess/directory "."
                 :seon.operator.subprocess/deadline-ms
                 (* 1000 support/event-backstop-seconds)})]
    (is (zero? (:seon.operator.subprocess/exit result)))
    (when (zero? (:seon.operator.subprocess/exit result))
      (let [observed (edn/read-string (:seon.operator.subprocess/output result))]
        (is (= [] (:seon.probe/calls observed)))
        (is (nil? (:seon.probe/publisher observed)))
        (is (= 4 (count (:seon.probe/replies observed))))
        (is (not-any? #(str/includes? % "accepted") (:seon.probe/replies observed)))))))

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
