(ns seon.writer-standalone-schema-test
  "Standalone writer artifact schema-compilation regression."
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.util.jar JarFile]))

(def ^:private standalone-jar
  "target/seon-database-server-standalone.jar")

(defn- jar-entry? [path]
  (with-open [jar (JarFile. standalone-jar)]
    (some? (.getEntry jar path))))

(deftest standalone-writer-compiles-core-predicate-schemas-without-sci
  (let [build (shell/sh "clojure" "-T:build" "writer-uber")]
    (is (zero? (:exit build)) (:err build))
    (is (false? (jar-entry? "sci/core.cljc"))
        "the writer artifact keeps the R26 no-SCI topology")
    (let [java (str (System/getProperty "java.home") "/bin/java")
          load-result
          (shell/sh
           java
           "--add-modules" "jdk.incubator.vector"
           "--enable-native-access=ALL-UNNAMED"
           "-cp" standalone-jar
           "clojure.main"
           "-e"
           (str "(require '[malli.core :as m] "
                "'[seon.db.protocol :as protocol]) "
                "(let [schema "
                "(m/schema :seon.db.protocol/ordinary-wire-value)] "
                "(assert (m/validate schema {:ok [1 :wire]})) "
                "(println :standalone-predicate-schema-ready))"))]
      (is (zero? (:exit load-result)) (:err load-result))
      (is (re-find #":standalone-predicate-schema-ready"
                   (:out load-result))))))
