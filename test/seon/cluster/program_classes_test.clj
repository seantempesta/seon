(ns seon.cluster.program-classes-test
  "A launch loads our program from the classes the archive's first launch
  compiled (`seon.operator/launch-form`, `start-classpath`). A namespace loaded
  from those classes keeps its protocol and record identity when development
  adoption reloads it (`require :reload`), because the reload loads the same
  classes again."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.id :as id]))

(deftest a-namespace-loaded-from-its-classes-keeps-protocol-and-record-identity-across-reload
  (let [root (.getCanonicalFile (io/file "tmp" (str "program-classes-" (id/id))))
        namespace-name (symbol (str "seon.program-classes-fixture-" (id/id)))
        relative (str/replace (munge (str namespace-name)) "." "/")
        source (io/file root "src" (str relative ".clj"))
        classes (io/file root "classes")
        _ (do (io/make-parents source) (.mkdirs classes))
        resolve! #(deref (ns-resolve namespace-name %))]
    (try
      (spit source (str "(ns " namespace-name ")\n"
                        "(defprotocol P (p [x]))\n"
                        "(defrecord R [a] P (p [_] a))\n"))
      ;; The first launch: its own JVM compiles the namespace as it loads it.
      (let [child (.start (doto (ProcessBuilder.
                                 ^java.util.List
                                 ["java" "-cp" (str (io/file root "src") java.io.File/pathSeparator
                                                   (System/getProperty "java.class.path"))
                                  "clojure.main" "-e"
                                  (pr-str `(binding [*compile-path* ~(str classes) *compile-files* true]
                                             (require '~namespace-name)))])
                            (.redirectErrorStream true)))]
        (is (.waitFor child 15 java.util.concurrent.TimeUnit/SECONDS))
        (is (zero? (.exitValue child))))
      (is (.isFile (io/file classes (str relative "__init.class"))))
      ;; A promoted directory's classes carry a far-future time, so a later
      ;; archive's source (extracted after the compile) still loses to them.
      (doseq [file (file-seq classes)] (.setLastModified ^java.io.File file 4102444800000))
      (.setLastModified source (+ (System/currentTimeMillis) 60000))
      ;; The next launch: the classes precede the source and are newer, so they load.
      (let [loader (clojure.lang.DynamicClassLoader.
                    (java.net.URLClassLoader.
                     (into-array java.net.URL [(.toURL (.toURI classes)) (.toURL (.toURI (io/file root "src")))])
                     (.getContextClassLoader (Thread/currentThread))))
            load! (fn [& flags]
                    (with-bindings {clojure.lang.Compiler/LOADER loader}
                      (apply require namespace-name flags)))]
        (load!)
        (let [before ((resolve! '->R) 1)]
          (is (= 1 ((resolve! 'p) before)))
          ;; Development adoption reloads the namespace.
          (load! :reload)
          (let [after ((resolve! '->R) 2)]
            (is (identical? (class before) (class after))
                "the record class is the one the classes defined, before and after the reload")
            (is (satisfies? (resolve! 'P) before) "an instance made before the reload still satisfies the protocol")
            (is (= 1 ((resolve! 'p) before)))
            (is (= 2 ((resolve! 'p) after))))))
      (finally
        (remove-ns namespace-name)
        (dosync (commute @#'clojure.core/*loaded-libs* disj namespace-name))
        (doseq [file (reverse (file-seq root))] (.delete ^java.io.File file))))))
