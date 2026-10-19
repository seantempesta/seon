(ns seon.dev.publication-launch-test
  (:require [clojure.test :refer [deftest is]]
            [seon.operator :as operator]))

(deftest launch-loads-the-cluster-without-an-inlined-input-inventory
  (let [text (#'operator/launch-form {:seon.operator/managed-root "tmp/publication-launch" :seon.boot/cluster-name "publication"} 12345 nil)
        forms (tree-seq coll? seq (read-string text))]
    ;; Clojure's generated method must fit the JVM's 64 KiB ceiling.
    (is (< (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8)) 16384))
    (is (some #(and (seq? %) (= 'clojure.core/require (first %))
                    (= '(quote seon.cluster.boot) (second %))) forms))
    (is (not-any? #(= 'clojure.core/*compile-files* %) forms)
        "the program loads from source, so `require :reload` re-evaluates it")))

(deftest ^{:seon/tasks #{'seon.operator/a-test-launched-jvm-exits-when-its-launcher-exits}}
  a-test-launched-jvm-exits-when-its-launcher-exits
  (let [launcher (.start (ProcessBuilder. ["sleep" "600"]))
        form (pr-str (list 'do (operator/exit-with-launcher-form (.pid launcher)) '(Thread/sleep 600000)))
        child (.start (ProcessBuilder. ["java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" form]))]
    (try
      (Thread/sleep 3000)
      (is (.isAlive child) "the child lives while its launcher lives")
      (.destroyForcibly launcher)
      (is (some? (.get (.onExit child) 10 java.util.concurrent.TimeUnit/SECONDS))
          "the child exits within the bound after its launcher exits")
      (finally (.destroyForcibly child) (.destroyForcibly launcher)))))

(deftest a-jvm-under-an-isolated-root-is-bound-to-its-launcher
  (is (some? (operator/launcher-pid {:seon.operator/managed-root "tmp"} false)))
  (is (nil? (operator/launcher-pid {:seon.operator/managed-root (operator/repository-root)} false))))
