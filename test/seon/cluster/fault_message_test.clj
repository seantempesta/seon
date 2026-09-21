(ns seon.cluster.fault-message-test
  (:require [clojure.core.async.flow :as-alias async.flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.test-support :as support]))

(deftest a-recording-refusal-still-prints-the-source-fault
  (support/with-database
   (fn [connection]
     ;; The absent cluster has no recording dials. The real preparation
     ;; contract refuses before a durable fact exists; no writer is mocked.
     (doseq [fault [{:seon.error/message "original map fault"}
                    {::async.flow/ex (ex-info "original flow fault" {})}
                    (ex-info "original throwable fault" {})]
             :let [message (or (:seon.error/message fault)
                               (ex-message (if (instance? Throwable fault)
                                             fault (::async.flow/ex fault))))
                   [fact outcome reported?]
                   (#'cluster/commit-fault! connection "absent-fault-cluster"
                    "fault-message-test" (config/result-caps config/defaults) fault)
                   output (java.io.StringWriter.)]]
       (is (not= ::flow/committed outcome))
       (is (false? reported?))
       (binding [*err* output]
         (#'cluster/emit-core-fault!
          {} {::flow/fault-fact fact ::flow/commit-outcome outcome
              ::flow/core-error-mode :panic}))
       (is (str/includes? (str output) message))
       (is (str/includes? (str output) "durable record refused:"))))))
