(ns seon.adoption-diagnostic-test
  (:require [clojure.core.server :as server]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.operator :as operator]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(deftest row-refusal-describes-the-cause-without-copying-the-projection
  (let [projection {:seon.schema.projection/function-contracts
                    (apply str (repeat 99000 "x"))}
        refusal (#'eval/acquisition-refusal
                 {:seon.fn/sym "adoption.probe/broken"}
                 (ex-info "The declared namespace is unavailable."
                          {:seon.error/kind ::missing-namespace
                           :seon.schema/projection projection}))
        offense {:seon.schema/projection projection
                 :seon.sci.eval/acquisition-refusals [refusal]}
        failure (try (#'cluster/refused! "Acquisition failed." offense)
                     (catch clojure.lang.ExceptionInfo failure failure))
        shown (pr-str (ex-data failure))
        byte-count (alength (.getBytes shown "UTF-8"))]
    (is (str/includes? shown "adoption.probe/broken"))
    (is (str/includes? shown "The declared namespace is unavailable."))
    (is (str/includes? shown "missing-namespace"))
    (is (not (str/includes? shown "function-contracts")))
    (is (< byte-count 2048) (str "diagnostic bytes=" byte-count))
    (println "adoption diagnostic bytes=" byte-count)))

(deftest operator-reports-the-typed-refusal-over-a-real-prepl
  (let [server-name "adoption-diagnostic-prepl"
        socket (server/start-server {:name server-name :port 0
                                     :accept 'clojure.core.server/io-prepl})]
    (try
      (let [form (pr-str
                  `(throw (ex-info "Cannot install adoption.probe/broken."
                                   {:seon.boot/refused true
                                    :seon.error/message "Cannot install adoption.probe/broken."
                                    :seon.boot/offense
                                    {:seon.error/kind ::missing-namespace
                                     :seon.error/message "Namespace adoption.probe is unavailable."}})))
            failure (try
                      (#'operator/prepl-value!
                       {:seon.boot/prepl-host "127.0.0.1"
                        :seon.boot/prepl-port (.getLocalPort socket)}
                       form (* 1000 test-support/event-backstop-seconds))
                      (catch clojure.lang.ExceptionInfo failure failure))
            shown (str (ex-message failure) " " (pr-str (ex-data failure)))
            byte-count (alength (.getBytes shown "UTF-8"))]
        (is (str/includes? shown "adoption.probe/broken"))
        (is (str/includes? shown "Namespace adoption.probe is unavailable."))
        (is (not (str/includes? shown "seon.operator/events")))
        (is (< byte-count 1024))
        (println "operator refusal bytes=" byte-count))
      (finally (server/stop-server server-name)))))
