(ns seon.operator-cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.operator :as operator]))

(deftest command-help-uses-the-command-usage-before-reading-options
  (doseq [[arguments usage]
          [[["check" "--help"] "check [PATH... | --at REV] [--test NS | --test NS/NAME]... [--include-long] (check --at REV runs the named tests on a throwaway JVM booted from git archive REV; each test needs its own --test)"]
           [["start" "--config" "does-not-exist.edn" "-h"] "start [NAME] [--config PATH] | start --head"]
           [["reset" "--help"] "reset [NAME] --force"]
           [["nuke" "-h"] "nuke --force"]
           [["config" "--help"] "config apply [NAME] PATH [--branch B | --chains]"]
           [["config" "apply" "--help"] "config apply [NAME] PATH [--branch B | --chains]"]]]
    (is (= {:seon.operator/command :help :seon.operator/help (str "seon [--root PATH] " usage)}
           (select-keys (operator/parse-argv arguments) [:seon.operator/command :seon.operator/help])))))

(deftest general-help-keeps-its-command-list-for-every-alias
  (let [expected "seon [--root PATH] start [NAME] [--config PATH] | start --head | init [NAME --force | --dev NAME] [--changed PATH...] | status [--verbose] | open [NAME] | stop [NAME] | down [--force] | reset [NAME] --force | nuke --force | logs [NAME] | config apply [NAME] PATH [--branch B | --chains] | check [PATH... | --at REV] [--test NS | --test NS/NAME]... [--include-long] (check --at REV runs the named tests on a throwaway JVM booted from git archive REV; each test needs its own --test)"]
    (doseq [arguments [[] ["help"] ["--help"] ["-h"]]]
      (is (= expected (:seon.operator/help (operator/parse-argv arguments)))))))

(deftest command-help-exits-successfully-through-the-cli
  (let [root (operator/program-root)
        output (#'operator/command!
                ["bb" "--config" "/dev/null" "--classpath"
                 (str/join java.io.File/pathSeparator (map #(str root "/" %) ["script" "src" "resources"]))
                 "-m" "seon.operator" "--seon-root" root "check" "--help"]
                root (operator/operator-silence-backstop-ms {}))]
    (is (= (:seon.operator/help (operator/parse-argv ["check" "--help"]))
           (str/trim output)))))

(deftest config-apply-names-one-branch-or-every-chain
  (let [document (.getPath (java.io.File/createTempFile "branch-apply" ".edn"))
        _ (spit document "{}")
        parse #(select-keys (operator/parse-argv (into ["config" "apply"] %))
                            [:seon.boot/cluster-name :seon.operator/branch :seon.operator/chains?])]
    (is (= {:seon.boot/cluster-name "default" :seon.operator/branch :chain-f1f6dc8e6652}
           (parse [document "--branch" "chain-f1f6dc8e6652"])))
    (is (= {:seon.boot/cluster-name "default" :seon.operator/chains? true} (parse [document "--chains"])))
    (is (thrown? Exception (parse [document "--chains" "--branch" "chain-a"])))))
