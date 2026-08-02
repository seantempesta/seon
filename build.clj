(ns build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.security MessageDigest]))

(def lib 'seon/seon)
(def version
  (format "0.1.%s"
          (or (System/getenv "SEON_BUILD_REVISION_COUNT")
              (when (.exists (io/file ".git"))
                (b/git-count-revs nil))
              0)))

(def basis (b/create-basis {:project "deps.edn"}))
(def class-dir "target/artifact-classes")
(def artifact-file "target/seon-standalone.jar")
(def artifact-digest-file (str artifact-file ".sha256"))
(def launcher-file "target/seon")
(def initialization-resource
  "seon/artifact/current-src.edn")
(def initialization-build-file
  "target/current-src-build.edn")

(def jvm-options
  ["--add-modules" "jdk.incubator.vector"
   "--enable-native-access=ALL-UNNAMED"
   "-XX:+UseG1GC"
   "-XX:MaxRAMPercentage=12.5"
   "-XX:G1PeriodicGCInterval=30000"])

(defn- checked-process!
  [params message]
  (let [result (b/process params)]
    (when-not (zero? (:exit result))
      (throw (ex-info message
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- sha-256-file
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream path)]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [bytes-read (.read input buffer)]
            (when (pos? bytes-read)
              (.update digest buffer 0 bytes-read)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- initialization-pages
  []
  (let [output (.getCanonicalPath (io/file initialization-build-file))
        _ (.mkdirs (.getParentFile (io/file output)))
        _ (b/delete {:path output})
        expression
        (str
         "(do "
         "(require 'clojure.java.io 'clojure.string "
         "'seon.fn 'seon.cluster.source) "
         "(let [root# (.toPath (.getCanonicalFile (clojure.java.io/file \".\"))) "
         "manifest# (seon.fn/build-manifest "
         "{:seon.fn/roots seon.fn/source-roots}) "
         "artifacts# (mapv "
         "(fn [artifact#] "
         "(update artifact# :seon.fn.file/path "
         "(fn [path#] "
         "(clojure.string/replace "
         "(str (.relativize root# (.toPath (clojure.java.io/file path#)))) "
         "\"\\\\\" \"/\")))) "
         "(:seon.fn.manifest/artifacts manifest#)) "
         "portable# (seon.fn/replace-manifest-artifacts "
         "(assoc manifest# "
         ":seon.fn.manifest/roots seon.fn/source-roots "
         ":seon.fn.manifest/artifacts []) artifacts#)] "
         "(let [pages# {:seon.source/digest "
         "(seon.cluster.source/digest "
         "{:seon.source/roots [\"src\" \"test\" \"resources\"]}) "
         ":seon.fn/manifest portable#}] "
         "(spit " (pr-str output) " (str (pr-str pages#) \"\\n\")) "
         "(prn {:seon.source/digest (:seon.source/digest pages#) "
         ":seon.fn/artifact-count (count artifacts#)}))) "
         "(shutdown-agents))")
        result
        (checked-process!
         {:command-args ["clojure" "-M:dev" "-e" expression]
          :out :capture
          :err :capture}
         "Fresh initialization-page generation failed")]
    (when-not (str/includes? (:out result) ":seon.fn/artifact-count")
      (throw (ex-info "Fresh initialization pages returned no summary."
                      {:out (:out result)
                       :err (:err result)})))
    (edn/read-string (slurp output))))

(defn- write-artifact-resources!
  [pages]
  (let [initialization-file (io/file class-dir initialization-resource)
        metadata-file (io/file class-dir "META-INF" "seon-artifact.edn")]
    (.mkdirs (.getParentFile initialization-file))
    (.mkdirs (.getParentFile metadata-file))
    (spit initialization-file (str (pr-str pages) "\n"))
    (spit metadata-file
          (str
           (pr-str
            {:seon.artifact/version version
             :seon.artifact/main-class "seon.ArtifactMain"
             :seon.artifact/jvm-options jvm-options
             :seon.source/digest (:seon.source/digest pages)
             ;; The stock JDK CDS archive remains enabled. The measured custom
             ;; AppCDS archive was 132.6 MiB and had no meaningful startup win.
             :seon.artifact/app-cds :skipped})
           "\n"))))

(defn- write-launcher!
  []
  (spit launcher-file
        (str "#!/bin/sh\n"
             "set -eu\n"
             "exec java "
             (str/join " " (map #(str "'" % "'") jvm-options))
             " -jar \"$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)/"
             (.getName (io/file artifact-file))
             "\" \"$@\"\n"))
  (.setExecutable (io/file launcher-file) true false))

(defn clean
  "Delete build outputs."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the ordinary library jar from fresh source."
  [_]
  (let [jar-file (format "target/%s-%s.jar" (name lib) version)]
    (b/delete {:path class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})
    (println "jar →" jar-file)))

(defn uber
  "Build the standalone fresh-system artifact."
  [_]
  (let [pages (initialization-pages)]
    (b/delete {:path class-dir})
    (b/delete {:path artifact-file})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/javac {:basis basis
              :src-dirs ["java"]
              :class-dir class-dir
              :javac-opts ["--release" "11"]})
    (write-artifact-resources! pages)
    (b/uber {:class-dir class-dir
             :uber-file artifact-file
             :basis basis
             :main 'seon.ArtifactMain})
    (let [digest (sha-256-file artifact-file)]
      (spit artifact-digest-file
            (str digest "  " (.getName (io/file artifact-file)) "\n"))
      (write-launcher!)
      (println "artifact →" artifact-file)
      (println "sha256   →" digest)
      (println "launcher →" launcher-file))))
