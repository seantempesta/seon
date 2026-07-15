(ns seon.dev.artifact-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.dev.artifact :as artifact])
  (:import [java.io FileOutputStream]
           [java.util.jar JarEntry JarOutputStream]))

(defn- write-test-jar! [path value]
  (fs/create-dirs (fs/parent path))
  (with-open [stream (JarOutputStream. (FileOutputStream. (str path)))]
    (.putNextEntry stream (JarEntry. "example.txt"))
    (.write stream (.getBytes (str value) "UTF-8"))
    (.closeEntry stream)))

(deftest artifact-digest-is-content-addressed
  (let [directory (fs/create-temp-dir {:prefix "seon-artifact-test-"})
        first-file (fs/path directory "a.txt")
        second-file (fs/path directory "nested/b.txt")]
    (try
      (fs/create-dirs (fs/parent second-file))
      (spit (str first-file) "alpha")
      (spit (str second-file) "beta")
      (let [digest (artifact/digest-paths directory [directory])]
        (is (= digest (artifact/digest-paths directory [directory])))
        (is (= digest (artifact/digest-paths directory ["."]))
            "relative inputs resolve against the declared digest root")
        (spit (str second-file) "changed")
        (is (not= digest (artifact/digest-paths directory [directory]))))
      (finally (fs/delete-tree directory)))))

(deftest current-client-digest-owns-output-and-runtime-closure
  (let [directory (fs/create-temp-dir {:prefix "seon-client-digest-test-"})
        output (fs/path directory "out-acme/client/main.js")
        runtime (fs/path directory
                         "shadow/builds/acme-client/dev/out/cljs-runtime/a.js")
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/client-output (str output)
                :seon.dev.config/shadow-cache-root
                (str (fs/path directory "shadow"))
                :seon.dev.config/client-build-id "acme-client"}]
    (try
      (fs/create-dirs (fs/parent output))
      (fs/create-dirs (fs/parent runtime))
      (spit (str output) "main-a")
      (spit (str runtime) "runtime-a")
      (let [digest (artifact/current-client-digest config)]
        (is (= digest (artifact/current-client-digest config)))
        (spit (str runtime) "runtime-b")
        (is (not= digest (artifact/current-client-digest config))))
      (finally (fs/delete-tree directory)))))

(deftest cljs-build-command-is-structured
  (let [plain {:seon.dev.config/environment {}}
        extended {:seon.dev.config/environment
                  {"SEON_EXTRA_SRC" "/tmp/example"
                   "SEON_EXTRA_PRELOAD" "example.pod"}}
        plain-argv (artifact/cljs-command plain "compile" "client")
        extended-argv (artifact/cljs-command extended "watch" "client")]
    (is (= ["clj" "-M:cljs" "compile" "client"] plain-argv))
    (is (= "clj" (first extended-argv)))
    (is (some #{"-Sdeps"} extended-argv))
    (is (some #{"--config-merge"} extended-argv))
    (is (not-any? #{"bash" "-c"} extended-argv))))

(deftest acme-command-keeps-cache-and-build-identities-together
  (let [config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/acme
                :seon.dev.config/shadow-cache-root
                "/checkout/tmp/shadow/acme"
                :seon.dev.config/environment
                {"SEON_EXTRA_SRC" "/checkout/acme"
                 "SEON_EXTRA_PRELOAD" "acme.pod"}}
        argv (artifact/cljs-command config "compile" "acme-client")
        merge-index (.indexOf argv "--config-merge")
        config-merge (edn/read-string (nth argv (inc merge-index)))]
    (is (= ["clj" "-Sdeps"] (subvec argv 0 2)))
    (is (= ["-M:cljs" "compile" "acme-client"]
           (subvec argv (- merge-index 3) merge-index)))
    (is (not (contains? config-merge :cache-root))
        "action config cannot select the Shadow server/cache identity")
    (is (= ['acme.pod] (get-in config-merge [:devtools :preloads])))
    (is (not-any? #{"client"} argv))))

(deftest default-command-does-not-add-a-cache-override
  (let [config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/shadow-cache-root "/checkout/.shadow-cljs"
                :seon.dev.config/environment {}}]
    (is (= ["clj" "-M:cljs" "compile" "client"]
           (artifact/cljs-command config "compile" "client")))))

(deftest source-artifact-builds-share-one-checkout-lock
  (let [directory (fs/create-temp-dir {:prefix "seon-artifact-lock-test-"})
        root (str directory)
        default-config {:seon.dev.config/root root
                        :seon.dev.config/process-dir
                        (str (fs/path directory "default"))}
        acme-config {:seon.dev.config/root root
                     :seon.dev.config/process-dir
                     (str (fs/path directory "acme"))}
        acquired (promise)
        release (promise)
        second-started (promise)
        second-entered (promise)]
    (try
      (let [first-build
            (future
              (#'artifact/with-build-lock
               default-config
               #(do (deliver acquired true) @release)))
            _ (is (= true (deref acquired 2000 ::timeout)))
            second-build
            (future
              (deliver second-started true)
              (#'artifact/with-build-lock
               acme-config
               #(do (deliver second-entered true) true)))]
        (is (= true (deref second-started 2000 ::timeout)))
        (Thread/sleep 150)
        (is (not (realized? second-entered))
            "a downstream target cannot enter while the default build owns the checkout lock")
        (deliver release true)
        (is (= true (deref second-entered 2000 ::timeout)))
        (is (= true (deref first-build 2000 ::timeout)))
        (is (= true (deref second-build 2000 ::timeout))))
      (finally
        (deliver release true)
        (fs/delete-tree directory)))))

(deftest dependency-preparation-uses-the-source-artifact-owner
  (let [directory (fs/create-temp-dir {:prefix "seon-prep-owner-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/environment {}
                :seon.dev.config/process-dir
                (str (fs/path directory "target"))}
        commands (atom [])]
    (try
      (with-redefs [artifact/run-step!
                    (fn [_ label argv]
                      (swap! commands conj [label argv])
                      {:exit 0})]
        (is (= {:seon.dev.artifact/prepared-aliases [:writer :cljs]}
               (artifact/prepare-dependencies! config [:writer :cljs])))
        (is (= [["prepare writer, cljs dependencies"
                 ["clojure" "-X:deps" "prep" ":aliases"
                  "[:writer :cljs]"]]]
               @commands)))
      (finally
        (fs/delete-tree directory)))))

(deftest downstream-cljs-preparation-includes-its-local-root
  (let [directory (fs/create-temp-dir {:prefix "seon-prep-downstream-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/environment
                {"SEON_EXTRA_SRC" "/checkout/acme"}
                :seon.dev.config/process-dir
                (str (fs/path directory "target"))}
        command (atom nil)]
    (try
      (with-redefs [artifact/run-step!
                    (fn [_ _ argv]
                      (reset! command argv)
                      {:exit 0})]
        (artifact/prepare-dependencies! config [:cljs])
        (is (= ["clojure" "-Sdeps"] (subvec @command 0 2)))
        (is (= {:deps {'seon.extra/src {:local/root "/checkout/acme"}}}
               (edn/read-string (nth @command 2))))
        (is (= ["-X:deps" "prep" ":aliases" "[:cljs]"]
               (subvec @command 3))))
      (finally
        (fs/delete-tree directory)))))

(deftest canonical-writer-reuses-and-invalidates-verified-output
  (let [directory (fs/create-temp-dir {:prefix "seon-writer-cache-test-"})
        root (str directory)
        source (fs/path directory "src/example.clj")
        output (str (fs/path directory
                             "target/seon-database-server-standalone.jar"))
        base {:seon.dev.config/root root
              :seon.dev.config/environment {}
              :seon.dev.config/writer-output output}
        default-config (assoc base :seon.dev.config/process-dir
                              (str (fs/path directory "default")))
        acme-config (assoc base :seon.dev.config/process-dir
                           (str (fs/path directory "acme")))
        build-count (atom 0)
        toolchain (atom "toolchain-a")
        run-step
        (fn [_config label _argv]
          (when (= "build canonical database server" label)
            (let [generation (swap! build-count inc)]
              (write-test-jar! output (str "writer-" generation)))))]
    (try
      (fs/create-dirs (fs/parent source))
      (spit (str (fs/path directory "build.clj")) "(ns build)")
      (spit (str (fs/path directory "deps.edn")) "{}")
      (spit (str source) "(ns example)")
      (with-redefs [artifact/capture-command! (fn [_ _] @toolchain)
                    artifact/run-step! run-step]
        (let [default-digest (#'artifact/ensure-writer! default-config)
              acme-digest (#'artifact/ensure-writer! acme-config)]
          (is (= 1 @build-count)
              "unchanged downstream build reuses the canonical writer")
          (is (= default-digest acme-digest)
              "both target manifests receive one verified writer identity")
          (is (= default-digest
                 (:seon.dev.writer-cache/writer-digest
                   (edn/read-string
                     (slurp (#'artifact/writer-cache-path acme-config)))))))

        (spit (str source) "(ns example)\n(def changed true)")
        (#'artifact/ensure-writer! acme-config)
        (is (= 2 @build-count) "a local writer input change rebuilds")

        (spit (str (fs/path directory "deps.edn"))
              "{:aliases {:writer {}}}")
        (#'artifact/ensure-writer! default-config)
        (is (= 3 @build-count) "a writer dependency change rebuilds")

        (reset! toolchain "toolchain-b")
        (#'artifact/ensure-writer! acme-config)
        (is (= 4 @build-count) "a compiler or runtime change rebuilds")

        (spit output "not a jar")
        (#'artifact/ensure-writer! default-config)
        (is (= 5 @build-count) "a corrupt cached jar rebuilds"))
      (finally (fs/delete-tree directory)))))

(deftest sequential-flavors-publish-immutable-bootstrap-roots
  (let [directory (fs/create-temp-dir {:prefix "seon-bootstrap-root-test-"})
        source (fs/path directory "out/bootstrap/example.txt")
        config {:seon.dev.config/root (str directory)}]
    (try
      (fs/create-dirs (fs/parent source))
      (doseq [relative ["src" "test" "resources"]]
        (fs/create-dirs (fs/path directory relative)))
      (spit (str source) "default-bootstrap")
      (let [default-digest (artifact/digest-paths
                             directory ["out/bootstrap"])
            default-root (#'artifact/publish-runtime-root!
                           config default-digest)]
        (is (= "default-bootstrap"
               (slurp (str (fs/path default-root
                                    "out/bootstrap/example.txt")))))

        (spit (str source) "acme-bootstrap")
        (let [acme-digest (artifact/digest-paths directory ["out/bootstrap"])
              acme-root (#'artifact/publish-runtime-root! config acme-digest)]
          (is (not= default-root acme-root))
          (is (= default-digest
                 (artifact/digest-paths default-root ["out/bootstrap"])))
          (is (= acme-digest
                 (artifact/digest-paths acme-root ["out/bootstrap"])))
          (is (= "default-bootstrap"
                 (slurp (str (fs/path default-root
                                      "out/bootstrap/example.txt"))))
              "the later ACME publication cannot mutate the default root")
          (is (= acme-root
                 (#'artifact/publish-runtime-root! config acme-digest))
              "an identical bootstrap reuses its verified content address")))
      (finally (fs/delete-tree directory)))))

(deftest manifest-v3-binds-runtime-root-and-v2-remains-readable
  (let [directory (fs/create-temp-dir {:prefix "seon-manifest-v3-test-"})
        path (str (fs/path directory "artifact.edn"))
        digest (apply str (repeat 64 "a"))
        config {:seon.dev.config/artifact-manifest path
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default}
        v3 {:seon.dev.artifact/version 3
            :seon.dev.artifact/published-at "2026-07-14T00:00:00Z"
            :seon.dev.artifact/flavor :seon.dev.artifact.flavor/default
            :seon.dev.artifact/client-build-id "client"
            :seon.dev.artifact/shadow-cache-root "/checkout/.shadow-cljs"
            :seon.dev.artifact/client-output "/checkout/out/client/main.js"
            :seon.dev.artifact/runtime-root "/checkout/runtime/content"
            :seon.dev.artifact/writer-digest digest
            :seon.dev.artifact/client-digest digest
            :seon.dev.artifact/bootstrap-digest digest
            :seon.dev.artifact/css-digest digest
            :seon.dev.artifact/application-digest digest}]
    (try
      (spit path (pr-str v3))
      (is (= v3 (artifact/read-manifest config)))
      (let [v2 (-> v3
                   (assoc :seon.dev.artifact/version 2)
                   (dissoc :seon.dev.artifact/runtime-root))]
        (spit path (pr-str v2))
        (is (= v2 (artifact/read-manifest config))))
      (finally (fs/delete-tree directory)))))

(deftest legacy-manifests-upgrade-only-as-the-default-flavor
  (let [directory (fs/create-temp-dir {:prefix "seon-legacy-artifact-"})
        path (str (fs/path directory "artifact.edn"))
        digest (apply str (repeat 64 "a"))
        legacy {:seon.dev.artifact/version 1
                :seon.dev.artifact/published-at "2026-07-14T00:00:00Z"
                :seon.dev.artifact/writer-digest digest
                :seon.dev.artifact/client-digest digest
                :seon.dev.artifact/bootstrap-digest digest
                :seon.dev.artifact/css-digest digest
                :seon.dev.artifact/application-digest digest}
        base {:seon.dev.config/artifact-manifest path
              :seon.dev.config/shadow-cache-root
              (str (fs/path directory ".shadow-cljs"))
              :seon.dev.config/client-output
              (str (fs/path directory "out/client/main.js"))}]
    (try
      (spit path (pr-str legacy))
      (let [upgraded
            (artifact/read-manifest
              (assoc base :seon.dev.config/artifact-flavor
                     :seon.dev.artifact.flavor/default))]
        (is (= 2 (:seon.dev.artifact/version upgraded)))
        (is (= :seon.dev.artifact.flavor/default
               (:seon.dev.artifact/flavor upgraded)))
        (is (= "client" (:seon.dev.artifact/client-build-id upgraded))))
      (is (thrown-with-msg?
            Exception #"cannot identify this flavor"
            (artifact/read-manifest
              (assoc base :seon.dev.config/artifact-flavor
                     :seon.dev.artifact.flavor/acme))))
      (finally (fs/delete-tree directory)))))
