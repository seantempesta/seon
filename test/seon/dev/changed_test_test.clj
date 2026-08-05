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

(deftest cleanup-awaits-the-process-owners-exit-event
  (let [python
        (str
         "import signal,subprocess,sys\n"
         "def stop(*_):\n"
         " child=subprocess.Popen([sys.executable,'-c','import signal; signal.pause()'])\n"
         " print('child='+str(child.pid),flush=True)\n"
         " child.terminate()\n"
         " child.wait()\n"
         " print('child-reaped',flush=True)\n"
         " sys.exit(0)\n"
         "signal.signal(signal.SIGTERM,stop)\n"
         "print('ready',flush=True)\n"
         "signal.pause()\n")
        expression
        (str
         "(require '[clojure.java.io :as io] 'seon.dev.changed-test) "
         "(let [process (.start (doto (ProcessBuilder. ^java.util.List "
         (pr-str ["/usr/bin/python3" "-c" python])
         ") (.redirectErrorStream true))) "
         "reader (io/reader (.getInputStream process)) "
         "ready (.readLine reader) "
         "terminate (var-get (ns-resolve 'seon.dev.changed-test "
         "'terminate!)) "
         "terminated? (terminate process) "
         "child-line (.readLine reader) "
         "reaped-line (.readLine reader) "
         "child-pid (parse-long (subs child-line 6)) "
         "child (some-> (java.lang.ProcessHandle/of child-pid) "
         "(.orElse nil))] "
         "(prn {:ready ready :terminated? terminated? "
         ":child-reaped reaped-line "
         ":child-alive? (boolean (and child (.isAlive child)))}))")
        {:keys [exit out err]} (run-babashka expression)]
    (is (zero? exit) err)
    (is (= {:ready "ready"
            :terminated? true
            :child-reaped "child-reaped"
            :child-alive? false}
           (edn/read-string out)))))

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))
