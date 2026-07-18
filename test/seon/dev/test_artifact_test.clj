(ns seon.dev.test-artifact-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.program-artifact :as program-artifact]
            [seon.dev.test-artifact :as artifact]))

(deftest successful-flush-publishes-an-immutable-bundle-and-current-manifest
  (let [root (fs/create-temp-dir {:prefix "seon-test-artifact-"})
        source (fs/path root "src/example/core.cljs")
        test-source (fs/path root "test/example/core_test.cljs")
        output (fs/path root "out/test/test.js")
        runtime (fs/path root ".shadow-cljs/builds/test/dev/out/cljs-runtime")
        runtime-file (fs/path runtime "example/core_test.js")
        execution-manifest (fs/path root "tmp/test-execution.edn")
        source-id [:shadow.build.classpath/resource "example/core.cljs"]
        test-id [:shadow.build.classpath/resource "example/core_test.cljs"]
        state
        {:project-dir (.toFile root)
         :shadow.build/config
         {:output-to (str output)
          :seon.dev.test-artifact/execution-manifest
          (str execution-manifest)}
         :shadow.build.test-util/test-namespaces ['example.core-test]
         :sources
         {source-id {:resource-id source-id
                     :resource-name "example/core.cljs"
                     :file (.toFile source)
                     :cache-key ["source-digest"]
                     :ns 'example.core}
          test-id {:resource-id test-id
                   :resource-name "example/core_test.cljs"
                   :file (.toFile test-source)
                   :cache-key ["test-digest"]
                   :ns 'example.core-test
                   :requires #{'example.core}}}
         :output {test-id {:used-var-namespaces #{'cljs.test}}}}]
    (try
      (doseq [path [source test-source output runtime-file]]
        (fs/create-dirs (fs/parent path)))
      (spit (str source) "(ns example.core)\n")
      (spit (str test-source) "(ns example.core-test)\n")
      (spit (str runtime-file) "global.example_test = true;\n")
      (spit (str output)
            (str "var SHADOW_IMPORT_PATH = __dirname + "
                 "'/../../.shadow-cljs/builds/test/dev/out/cljs-runtime';\n"
                 "if (__dirname == '.') { SHADOW_IMPORT_PATH = \""
                 (str runtime) "\"; }\n"
                 "console.log('tests');\n"))
      (is (identical? state (artifact/publish! state)))
      (let [manifest (artifact/read-current root)
            artifact-path (fs/path root
                                   (:seon.dev.test.artifact/path manifest))
            program-source-path
            (fs/path root
                     (:seon.dev.test.artifact/program-source-path manifest))]
        (is (fs/regular-file? artifact-path))
        (is (fs/regular-file? program-source-path))
        (is (= manifest (read-string (slurp (str execution-manifest)))))
        (is (= (:seon.dev.test.artifact/program-source-digest manifest)
               (program-artifact/digest (slurp (str program-source-path)))))
        (is (str/includes? (slurp (str artifact-path))
                           "__dirname + '/cljs-runtime'"))
        (let [snapshot-runtime
              (fs/path (fs/parent artifact-path)
                       "cljs-runtime/example/core_test.js")]
          (is (= "global.example_test = true;\n"
                 (slurp (str snapshot-runtime))))
          (spit (str runtime-file) "global.example_test = false;\n")
          (is (= "global.example_test = true;\n"
                 (slurp (str snapshot-runtime)))))
        (is (= ['example.core-test]
               (:seon.dev.test.artifact/test-namespaces manifest)))
        (is (= ["src/example/core.cljs" "test/example/core_test.cljs"]
               (mapv :seon.dev.test.resource/path
                     (:seon.dev.test.artifact/resources manifest))))
        (is (= ['cljs.test 'example.core]
               (:seon.dev.test.resource/requires
                 (second (:seon.dev.test.artifact/resources manifest)))))
        (fs/delete-if-exists (fs/path root "out/test/artifacts/current.edn"))
        (fs/delete-if-exists execution-manifest)
        (artifact/publish!
         (assoc-in state
                   [:shadow.build/config :seon.dev.test-artifact/publish?]
                   false))
        (is (not (fs/exists? (fs/path root "out/test/artifacts/current.edn")))
            "a runner publication does not replace the watcher pointer")
        (is (fs/regular-file? execution-manifest)
            "the invocation-unique runner pointer is still published"))
      (finally (fs/delete-tree root {:force true})))))

(deftest one-shot-build-can-decline-authoritative-publication
  (let [state {:shadow.build/config
               {:seon.dev.test-artifact/publish? false}}]
    (is (identical? state (artifact/publish! state)))))
