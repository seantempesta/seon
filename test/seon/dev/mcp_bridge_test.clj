(ns seon.dev.mcp-bridge-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io PushbackReader]
           [java.util.concurrent TimeUnit]))

(def ^:private project-root
  (io/file (System/getProperty "user.dir")))

(def ^:private server-source
  (io/file project-root "script/seon/dev/mcp.clj"))

(defn- namespace-form
  []
  (with-open [reader (PushbackReader. (io/reader server-source))]
    (loop []
      (let [form (read {:eof nil :read-cond :allow} reader)]
        (cond
          (nil? form) nil
          (and (seq? form) (= 'ns (first form))) form
          :else (recur))))))

(defn- require-targets
  [form]
  (->> (drop 2 form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))
       vec))

(deftest bridge-requires-no-application-namespace
  (let [targets (require-targets (namespace-form))
        application-targets
        (filterv #(str/starts-with? (str %) "seon.") targets)]
    (is (seq targets))
    (is (= [] application-targets)
        (str "The REPL bridge must not require application namespaces: "
             application-targets))))

(deftest bridge-loads-with-only-the-tooling-classpath
  (testing "Babashka does not need bb.edn, src/, or src-old/"
    (let [process
          (.start
           (doto
            (ProcessBuilder.
             ^java.util.List
             ["bb" "--classpath" "script" "-e"
              "(require 'seon.dev.mcp) (println :bridge-loaded)"])
             (.directory project-root)
             (.redirectErrorStream true)))
          completed? (.waitFor process 10 TimeUnit/SECONDS)
          _ (when-not completed? (.destroyForcibly process))
          output (slurp (.getInputStream process))]
      (is completed? "Babashka bridge load exceeded ten seconds.")
      (is (= 0 (when completed? (.exitValue process))) output)
      (is (str/includes? output ":bridge-loaded") output))))
