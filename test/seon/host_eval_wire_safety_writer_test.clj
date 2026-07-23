(ns seon.host-eval-wire-safety-writer-test
  "JVM execution-host persistence admission and session-survival proofs."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host :as host]
            [seon.host-conformance-writer-test :as conformance]
            [seon.host.context :as context]
            [seon.host.preflight :as preflight]
            [seon.host.session :as session]))

(def ^:private socket-path
  (var-get #'conformance/socket-path))
(def ^:private start-fake-writer!
  (var-get #'conformance/start-fake-writer!))
(def ^:private open-session!
  (var-get #'conformance/open-session!))
(def ^:private send!
  (var-get #'conformance/send!))
(def ^:private recv!
  (var-get #'conformance/recv!))
(def ^:private close!
  (var-get #'conformance/close!))
(def ^:private invoke-value
  (var-get #'conformance/invoke-value))
(def ^:private form
  (var-get #'conformance/form))
(deftest terminal-envelope-is-wire-safe-before-persistence-and-session-survives
  (let [writer-socket (socket-path "host-wire-safe-writer")
        host-socket (socket-path "host-wire-safe")
        writer (start-fake-writer! writer-socket)
        started (host/start!
                 {::host/socket-path host-socket
                  ::context/writer-socket-path writer-socket
                  ::context/database-name "host-conformance"})]
    (try
      (with-bindings
        {#'conformance/*host* started
         #'conformance/*host-socket* host-socket}
        (let [[execution-session _ready] (open-session! "wire-safe-agent")]
          (try
            (with-redefs
              [preflight/preflight!
               (fn [& _arguments]
                 {:seon.host.preflight/status :terminal
                  :seon.host.preflight/envelope
                  {:seon.eval/ok? false
                   :seon/error
                   (session/error-value
                    "The submitted form contains an unsafe value."
                    :agent
                    {:seon.host.test/unsupported-function *})}})]
              (send! execution-session
                     (invoke-value
                      "wire-safe-agent" "unsafe-terminal-envelope"
                      [(form "(unresolved)")]))
              (let [response (recv! execution-session)
                    envelope
                    (get-in response
                            [:seon.execution/result :seon.host/results 0])
                    projected
                    (get-in envelope
                            [:seon/error :seon.error/data
                             :seon.host.test/unsupported-function])]
                (is (= :seon.execution.message/result
                       (:seon.execution/message response))
                    (pr-str response))
                (is (= :agent
                       (get-in envelope [:seon/error :seon.error/kind])))
                (is (string? projected))
                (is (and (string? projected)
                         (re-find #"clojure\.core\$_STAR_" projected)))
                (is (protocol/ordinary-wire-value? response))
                (is (= response (uds/decode (uds/encode response))))))

            ;; The unsupported nested value is a tier-local error, not a
            ;; transport failure: the same physical host session remains live.
            (send! execution-session
                   (invoke-value "wire-safe-agent" "after-unsafe-envelope"
                                 [(form "(inc 41)")]))
            (let [response (recv! execution-session)]
              (is (= 42
                     (get-in response
                             [:seon.execution/result :seon.host/results 0
                              :seon.eval/value]))))
            (finally
              (close! execution-session)))))
      (finally
        (host/stop! started)
        (uds/close-request-server! writer)))))
