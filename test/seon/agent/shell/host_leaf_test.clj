(ns seon.agent.shell.host-leaf-test
  "Localized JVM shell leaf contract tests."
  (:require
   [clojure.test :refer [deftest is]]
   [seon.agent.shell.leaf :as leaf]))

(deftest real-echo-preserves-the-child-run-envelope
  (binding [leaf/*granted?* true]
    (let [result
          (leaf/run {:seon.agent.shell/cmd "/bin/echo"
                     :seon.agent.shell/args ["u8-shell"]
                     :seon.agent.shell/timeout-ms 1000})]
      (is (:seon.agent.shell/ok? result))
      (is (= 0 (:seon.agent.shell/exit result)))
      (is (= "u8-shell\n" (:seon.agent.shell/out result)))
      (is (= "" (:seon.agent.shell/err result)))
      (is (false? (:seon.agent.shell/timed-out? result)))
      (is (false? (:seon.agent.shell/truncated? result))))))

(deftest timeout-kills-the-child-and-returns-data
  (binding [leaf/*granted?* true]
    (let [result
          (leaf/run {:seon.agent.shell/cmd "/bin/sh"
                     :seon.agent.shell/args ["-c" "sleep 2"]
                     :seon.agent.shell/timeout-ms 10})]
      (is (:seon.agent.shell/ok? result))
      (is (true? (:seon.agent.shell/timed-out? result)))
      (is (integer? (:seon.agent.shell/exit result))))))

(deftest default-deny-is-the-portable-core-envelope
  (binding [leaf/*granted?* false]
    (let [result (leaf/run {:seon.agent.shell/cmd "/bin/echo"})]
      (is (false? (:seon.agent.shell/ok? result)))
      (is (= :seon.config/shell-enabled?
             (get-in result [:seon.error/data :seon.config/key]))))))
