(ns seon.agent.shell-portable-test
  "Portable shell policy and frozen child-call contract tests."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.agent.shell.core :as core]
   #?(:cljs [seon.agent.shell :as shell])))

(deftest portable-request-builders-and-interpreters
  (testing "run preserves every frozen child option"
    (is (= {:seon.subprocess/cmd ["git" "status"]
            :seon.subprocess/timeout-ms 99
            :seon.subprocess/kill-grace-ms 1000
            :seon.subprocess/max-output-bytes 200
            :seon.subprocess/cwd "/repo"
            :seon.subprocess/stdin "input"}
           (core/run-request {:seon.agent.shell/cmd "git"
                              :seon.agent.shell/args ["status"]
                              :seon.agent.shell/cwd "/repo"
                              :seon.agent.shell/stdin "input"
                              :seon.agent.shell/timeout-ms 99}
                             {:seon.config.shell/default-timeout-ms 30000
                              :seon.config.shell/kill-grace-ms 1000}
                             200))))
  (testing "a missing portable default is a loud flat config error"
    (is (= :seon.config.shell/default-timeout-ms
           (get-in
            (core/run-request {:seon.agent.shell/cmd "git"} {} 200)
            [:seon.error/data :seon.config/key]))))
  (testing "Python is a pure stdin specialization"
    (is (= {:seon.agent.shell/cmd "python3"
            :seon.agent.shell/args ["-" "x"]
            :seon.agent.shell/stdin "print(1)"}
           (core/py-request {:seon.agent.shell/source "print(1)"
                             :seon.agent.shell/args ["x"]}))))
  (testing "run interpretation and output cursors are portable"
    (is (= 3 (:seon.agent.shell/next-since (core/slice-since "abc" 1))))
    (is (= 7 (:seon.agent.shell/exit
              (core/ran-envelope 7 "out" "err" false false)))))
  (testing "denial names the governing config key"
    (is (= :seon.config/shell-enabled?
           (get-in (core/ungranted) [:seon.error/data :seon.config/key])))))

#?(:cljs
   (deftest public-entry-effects
     (doseq [[v effect]
             [[#'shell/grants :read] [#'shell/run :external]
              [#'shell/py-run :external] [#'shell/run-bg! :external]
              [#'shell/list-jobs :read] [#'shell/job-status :read]
              [#'shell/job-output :read] [#'shell/job-stop! :external]]]
       (is (= effect (:seon.capability/effect (meta v)))
           (str (:name (meta v)) " effect")))))
