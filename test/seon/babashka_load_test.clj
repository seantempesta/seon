(ns seon.babashka-load-test
  "The edit hook runs in Babashka and loads some Seon namespaces; a merge that
  makes one of them require a JVM-only library breaks every edit.
  `{:seon/babashka true}` on an `ns` form declares the property."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]))

(def hook-roots
  "The roots `bin/seon-hook` adds to Babashka's classpath, in its order."
  ["script" "src" "resources"])

(defn- source-files
  [root]
  (->> (file-seq (io/file root))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))))

(defn declared
  "The namespaces under `roots` whose `ns` form carries `{:seon/babashka true}`."
  [roots]
  (into (sorted-set)
        (comp (mapcat source-files)
              (keep (fn [file]
                      (let [form (edamame/parse-string (slurp file) {:all true :read-cond :allow})
                            ns-sym (second form)]
                        (when (and (= 'ns (first form)) (:seon/babashka (meta ns-sym)))
                          ns-sym)))))
        roots))

(defn- classpath
  [extra roots]
  (str/join ":" (concat extra roots)))

(defn bb-failures
  "A map of namespace to the message `bb` printed, for each of `namespaces` that
  does not load alone on the classpath of `extra` then `hook-roots`."
  [extra namespaces]
  (into (sorted-map)
        (keep (fn [ns-sym]
                (let [{:keys [exit err]} @(process/process
                                           {:out :string :err :string}
                                           "bb" "--classpath" (classpath extra hook-roots)
                                           "-e" (str "(require '" ns-sym ")"))]
                  (when-not (zero? exit) [ns-sym (str/trim err)]))))
        namespaces))

(defn undeclared-closure
  "The Seon namespaces `bb` loads when it requires all of `namespaces` that are
  not themselves in `namespaces`."
  [extra namespaces]
  (let [code (str "(def before (set (loaded-libs))) (run! require '" (vec namespaces) ")"
                  " (prn (into (sorted-set) (filter #(str/starts-with? (str %) \"seon.\"))"
                  " (remove before (loaded-libs))))")
        {:keys [out]} @(process/process {:out :string :err :string}
                                        "bb" "--classpath" (classpath extra hook-roots)
                                        "-e" (str "(require '[clojure.string :as str]) " code))]
    (apply disj (edn/read-string out) namespaces)))

(deftest ^{:seon.test/reads #{:seon/babashka}} declared-namespaces-load-in-babashka
  (let [namespaces (declared hook-roots)]
    (is (contains? namespaces 'seon.edit))
    (is (= {} (bb-failures [] namespaces)))
    (is (= #{} (undeclared-closure [] namespaces)))))

(deftest a-declared-namespace-requiring-a-jvm-only-library-fails-to-load
  (let [dir (java.nio.file.Files/createTempDirectory "bb-load" (make-array java.nio.file.attribute.FileAttribute 0))
        file (io/file (.toFile dir) "seon" "fixture_jvm_only.clj")]
    (io/make-parents file)
    (spit file "(ns ^{:seon/babashka true} seon.fixture-jvm-only (:require [clojure.core.specs.alpha]))")
    (let [extra [(str (.toFile dir))]]
      (is (= #{'seon.fixture-jvm-only} (declared extra)))
      (is (= #{'seon.fixture-jvm-only} (set (keys (bb-failures extra ['seon.fixture-jvm-only]))))))))
