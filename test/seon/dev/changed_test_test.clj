(ns seon.dev.changed-test-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

(defn- run-babashka [expression]
  (let [root (System/getProperty "user.dir")
        process (.start (ProcessBuilder.
                         ^java.util.List
                         ["bb" "--config" (str root "/bb.edn")
                          "--deps-root" root "-e" expression]))
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))
        exit (.waitFor process)]
    {:exit exit :out @out :err @err}))

(deftest operator-tests-use-the-fresh-jvm-gate
  (let [expression
        (str
         "(require 'seon.dev.changed-test) "
         "(let [calls (atom []) run (ns-resolve 'seon.dev.changed-test "
         "'run-command!) operator (ns-resolve 'seon.dev.changed-test "
         "'run-operator!)] "
         "(prn (with-redefs-fn {run (fn [root boundary command environment] "
         "(swap! calls conj [root boundary command environment]) "
         "{:seon.dev.changed-test/status :passed})} "
         "#(do (operator \"/checkout\" "
         "['seon.dev.fresh-operator-test 'seon.dev.mcp-bridge-test]) @calls))))")
        {:keys [exit out err]} (run-babashka expression)]
    (is (zero? exit) err)
    (is (= [["/checkout" :operator
             ["/checkout/bin/test"
              "seon.dev.fresh-operator-test"
              "seon.dev.mcp-bridge-test"]
             {}]]
           (edn/read-string out)))))

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))
