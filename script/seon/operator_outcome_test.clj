(ns seon.operator-outcome-test
  "An operator command's outcome leads its output: one short line, FAILED with
  the cause and a distinct exit code, the whole diagnostic in a named file; a
  start's program is a committed archive. Run with babashka:
  bb --config bb.edn --deps-root . --classpath script:src:resources \\
     -e \"(require 'seon.operator-outcome-test) (clojure.test/run-tests 'seon.operator-outcome-test)\""
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.dependency-digest :as dependency-digest]
            [seon.operator :as operator]))

(def ^:private move-request
  {:seon.operator/command :start :seon.operator/head? true :seon.boot/cluster-name "default"})

(defn- failed-move
  "A failed move's result as `move-to-head!` builds it: the child's boot value
  is the launch form's caught failure, whose root cause is two links down."
  [resumed]
  (assoc (operator/diagnostic
          "The JVM from abc did not reach readiness; it was terminated."
          {:seon.operator/value
           {:seon.error/message "SEON CORE FAULT (panic): no live cluster database to record this failure in."
            :seon.error/cause (Throwable->map
                               (ex-info "SEON CORE FAULT (panic): no live cluster database to record this failure in."
                                        {} (ex-info "Static program analysis found blocking errors." {})))}
           :seon.operator/resumed resumed
           :seon.operator/phases {:seon.operator/total-ms 1}
           :seon.operator/blob (apply str (repeat 400000 "x"))}
          :client-failed)
         :seon.operator/process-exit? (not (:seon.operator/ready? resumed))))

(deftest a-failed-move-leads-with-one-line-naming-its-cause-and-what-runs
  (let [resumed (failed-move {:seon.operator/ready? true})
        nothing (failed-move nil)
        line (operator/headline move-request resumed "/root/logs/operator-start.edn"
                                (operator/exit-code resumed true))]
    (is (= 4 (operator/exit-code resumed true)) "failed, the previous program runs again")
    (is (= 5 (operator/exit-code nothing false)) "failed, and no JVM of this root runs (observed)")
    (is (= 1 (operator/exit-code nothing true)) "failed while a JVM of this root still runs")
    (is (str/starts-with? line "SEON start --head FAILED (exit 4, previous program runs): The JVM from abc"))
    (is (str/includes? line "CAUSE: Static program analysis found blocking errors.")
        "the root cause beneath the launch form's panic, not the panic")
    (is (str/includes? line "DIAGNOSTIC: /root/logs/operator-start.edn"))
    (is (not (str/includes? line "\n")) "one line")
    (is (< (count line) 1000) "the 400 KB diagnostic stays out of the headline")
    (is (str/includes? (operator/headline move-request nothing nil 5) "FAILED (exit 5, no JVM runs)"))))

(deftest a-successful-outcome-is-one-line-and-keeps-its-codes
  (let [ready {:seon.boot/readiness {:seon.boot/missing-layers [] :seon.boot/ready-ms 9000}
               :seon.operator/source {:seon.source/git-sha "abc"}
               :seon.operator/phases {:seon.operator/total-ms 12000 :seon.operator/class-fill-ms 111000}
               :seon.dev-cache/dependency-classes {:seon.dev-cache/status :miss
                                                   :seon.dev-cache/reason :no-matching-cache}}]
    (is (= 0 (operator/exit-code ready true)))
    (is (= "SEON start --head OK abc total-ms 12000 class-fill-ms 111000 ready-ms 9000 classes miss no-matching-cache"
           (operator/headline move-request ready nil 0))
        "a class-cache fill and a miss are visible on the outcome line")))

(deftest a-failure-diagnostic-is-written-whole-to-the-file-its-line-names
  (let [root (fs/create-temp-dir {:prefix "seon-outcome"})]
    (try
      (let [result (failed-move nil)
            path (#'operator/write-diagnostic! (assoc move-request :seon.operator/managed-root (str root)) result)]
        (is (str/starts-with? path (str (fs/canonicalize (fs/path root "data" "clusters" "default" "logs")))))
        (is (= (:seon.error/message result)
               (:seon.error/message (edn/read-string {:default tagged-literal} (slurp path))))))
      (finally (fs/delete-tree root)))))

(deftest the-class-cache-key-ignores-launch-options
  (let [manifest {:deps {'a/b {:mvn/version "1"}}
                  :aliases {:dev {:extra-deps {'c/d {:mvn/version "2"}} :jvm-opts ["-Xmx1g"]}}}]
    (testing "a launch option compiles the same dependency classes"
      (is (= (dependency-digest/dependency-declarations manifest)
             (dependency-digest/dependency-declarations
              (assoc-in manifest [:aliases :dev :jvm-opts] ["-XX:HeapDumpPath=/elsewhere"])))))
    (testing "a dependency declaration does not"
      (is (not= (dependency-digest/dependency-declarations manifest)
                (dependency-digest/dependency-declarations
                 (assoc-in manifest [:aliases :dev :extra-deps 'c/d :mvn/version] "3")))))))

(deftest a-start-runs-the-committed-archive-never-the-checkout
  (let [root (fs/create-temp-dir {:prefix "seon-outcome-start"})]
    (try
      (let [source (#'operator/prepared-source! (str root) "HEAD" nil)
            sha (:seon.source/git-sha source)
            source-root (:seon.operator/source-root source)]
        (is (re-matches #"[0-9a-f]{40}" sha))
        (is (= (str (fs/canonicalize (fs/path root "data" "source" sha))) source-root)
            "the program directory is HEAD's archive under the managed root")
        (is (not= (operator/repository-root) source-root))
        (is (fs/sym-link? (fs/path source-root "target")) "the class cache is shared, not rebuilt")
        (is (fs/exists? (fs/path source-root ".cpcache")) "tools.deps computed its classpath before any stop"))
      (finally (fs/delete-tree root)))))
