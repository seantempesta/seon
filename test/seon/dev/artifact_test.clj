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

(deftest bun-identity-binds-the-executable-bytes-and-full-revision
  (let [identity (artifact/bun-identity! {})]
    (is (fs/absolute? (:seon.dev.artifact/bun-executable identity)))
    (is (re-matches #"[0-9a-f]{64}"
                    (:seon.dev.artifact/bun-executable-digest identity)))
    (is (re-matches #"[0-9]+\.[0-9]+\.[0-9]+.*"
                    (:seon.dev.artifact/bun-version identity)))
    (is (re-matches #"[0-9a-f]{40}"
                    (:seon.dev.artifact/bun-revision identity)))
    (is (artifact/bun-executable-current? identity))
    (is (not (artifact/bun-executable-current?
              (assoc identity :seon.dev.artifact/bun-executable-digest
                     (apply str (repeat 64 "0"))))))))

(deftest source-css-build-uses-the-selected-bun-executable
  (let [commands (atom [])
        bun {:seon.dev.artifact/bun-executable "/selected/bun"}
        config {:seon.dev.config/root "/checkout"
                :seon.dev.config/environment {}}]
    (with-redefs [artifact/prepare-dependencies-unlocked! (fn [_ _])
                  artifact/ensure-writer! (fn [_])
                  artifact/run-step!
                  (fn [_ label argv] (swap! commands conj [label argv]))]
      (#'artifact/build-source! config bun))
    (is (= ["build web CSS"
            ["/selected/bun" "run" "--bun" "css:build"]]
           (last @commands)))))

(deftest application-digest-includes-every-bun-identity-field
  (let [digest (apply str (repeat 64 "d"))
        config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/client-build-id "client"
                :seon.dev.config/client-output "out/client.js"
                :seon.dev.config/shadow-cache-root ".shadow-cljs"
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/execution-output "out/execution.js"}
        bun {:seon.dev.artifact/bun-executable "/bun"
             :seon.dev.artifact/bun-executable-digest digest
             :seon.dev.artifact/bun-version "1.3.14"
             :seon.dev.artifact/bun-revision (apply str (repeat 40 "a"))}
        application-digest
        (fn [identity]
          (#'artifact/derive-application-digest
           config identity [] digest digest "out/program-sources.edn" digest
           digest digest digest digest))
        initial (application-digest bun)]
    (doseq [[field changed]
            [[:seon.dev.artifact/bun-executable "/other-bun"]
             [:seon.dev.artifact/bun-executable-digest
              (apply str (repeat 64 "e"))]
             [:seon.dev.artifact/bun-version "1.3.15"]
             [:seon.dev.artifact/bun-revision
              (apply str (repeat 40 "b"))]]]
      (is (not= initial (application-digest (assoc bun field changed)))
          (str field " contributes to application identity")))))

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

(deftest source-input-digest-follows-the-selected-build-inputs
  (let [directory (fs/create-temp-dir {:prefix "seon-source-input-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/client-build-id "client"
                :seon.dev.config/execution-build-id "execution"}]
    (try
      (fs/create-dirs (fs/path directory "src"))
      (fs/create-dirs (fs/path directory "docs"))
      (spit (str (fs/path directory "src/example.cljs")) "(ns example)")
      (spit (str (fs/path directory "docs/note.md")) "outside the build")
      (let [digest (artifact/source-input-digest config)]
        (spit (str (fs/path directory "docs/note.md")) "changed documentation")
        (is (= digest (artifact/source-input-digest config))
            "unselected checkout files do not force a rebuild")
        (spit (str (fs/path directory "src/example.cljs"))
              "(ns example)\n(def changed true)")
        (is (not= digest (artifact/source-input-digest config))))
      (finally (fs/delete-tree directory)))))

(deftest current-manifest-requires-matching-inputs-and-outputs
  (let [digest-a (apply str (repeat 64 "a"))
        digest-b (apply str (repeat 64 "b"))
        output-digests
        {:seon.dev.artifact/writer-digest digest-a
         :seon.dev.artifact/client-digest digest-a
         :seon.dev.artifact/program-source-path "out/client/program-sources.edn"
         :seon.dev.artifact/program-source-digest digest-a
         :seon.dev.artifact/execution-digest digest-a
         :seon.dev.artifact/execution-runtime-digest digest-a
         :seon.dev.artifact/bootstrap-digest digest-a
         :seon.dev.artifact/css-digest digest-a
         :seon.dev.artifact/application-digest digest-a}
        config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/client-build-id "client"
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/shadow-cache-root "/repo/.shadow-cljs"
                :seon.dev.config/client-output "/repo/out/client/main.js"}
        manifest (merge output-digests
                        {:seon.dev.artifact/flavor
                         :seon.dev.artifact.flavor/default
                         :seon.dev.artifact/client-build-id "client"
                         :seon.dev.artifact/execution-build-id "execution"
                         :seon.dev.artifact/shadow-cache-root
                         "/repo/.shadow-cljs"
                         :seon.dev.artifact/client-output
                         "/repo/out/client/main.js"
                         :seon.dev.artifact/source-input-digest digest-a})
        input-digest (atom digest-a)
        observed-outputs (atom output-digests)]
    (with-redefs [artifact/read-manifest (constantly manifest)
                  artifact/source-input-digest (fn [_] @input-digest)
                  artifact/current-output-digests (fn [_] @observed-outputs)]
      (is (= manifest (artifact/current-manifest config)))
      (reset! input-digest digest-b)
      (is (nil? (artifact/current-manifest config)))
      (reset! input-digest digest-a)
      (swap! observed-outputs assoc :seon.dev.artifact/client-digest digest-b)
      (is (nil? (artifact/current-manifest config))))))

(deftest current-client-digest-owns-output-and-runtime-closure
  (let [directory (fs/create-temp-dir {:prefix "seon-client-digest-test-"})
        output (fs/path directory "out-acme/client/main.js")
        program-source (fs/path directory "out-acme/client/program-sources.edn")
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
      (spit (str program-source) "program-a")
      (spit (str runtime) "runtime-a")
      (let [digest (artifact/current-client-digest config)]
        (is (= digest (artifact/current-client-digest config)))
        (spit (str program-source) "program-b")
        (is (not= digest (artifact/current-client-digest config)))
        (spit (str program-source) "program-a")
        (spit (str runtime) "runtime-b")
        (is (not= digest (artifact/current-client-digest config))))
      (finally (fs/delete-tree directory)))))

(deftest execution-digest-is-the-exact-child-file
  (let [directory (fs/create-temp-dir {:prefix "seon-execution-digest-test-"})
        output (fs/path directory "out-acme/execution/main.js")
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/execution-output (str output)}]
    (try
      (fs/create-dirs (fs/parent output))
      (spit (str output) "execution-a")
      (let [digest (artifact/current-execution-digest config)]
        (is (= digest (artifact/current-execution-digest config)))
        (spit (str output) "execution-b")
        (is (not= digest (artifact/current-execution-digest config))))
      (finally (fs/delete-tree directory)))))

(deftest current-output-identity-rehashes-every-runtime-component
  (let [component (atom {:writer (apply str (repeat 64 "a"))
                         :client (apply str (repeat 64 "b"))
                         :program-source (apply str (repeat 64 "7"))
                         :execution (apply str (repeat 64 "c"))
                         :execution-runtime (apply str (repeat 64 "d"))
                         :bootstrap (apply str (repeat 64 "e"))
                         :css (apply str (repeat 64 "f"))})
        config {:seon.dev.config/root "/repo"
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/client-build-id "client"
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/client-output "/repo/out/client/main.js"
                :seon.dev.config/execution-output
                "/repo/out/execution/main.js"
                :seon.dev.config/shadow-cache-root "/repo/.shadow-cljs"}
        observe
        #(with-redefs-fn
           {#'artifact/current-writer-digest (fn [_] (:writer @component))
            #'artifact/current-client-digest (fn [_] (:client @component))
            #'artifact/current-program-source-digest
            (fn [_] (:program-source @component))
            #'artifact/current-execution-digest
            (fn [_] (:execution @component))
            #'artifact/current-execution-runtime-digest
            (fn [_] (:execution-runtime @component))
            #'artifact/maintained-dependencies (constantly [])
            #'artifact/digest-paths
            (fn [_ paths]
              (case (first paths)
                "out/bootstrap" (:bootstrap @component)
                "resources/public/css/output.css" (:css @component)))}
           (fn [] (artifact/current-output-digests config)))]
    (let [initial (observe)]
      (is (= (select-keys @component
                          [:writer :client :program-source :execution :execution-runtime
                           :bootstrap :css])
             {:writer (:seon.dev.artifact/writer-digest initial)
              :client (:seon.dev.artifact/client-digest initial)
              :program-source
              (:seon.dev.artifact/program-source-digest initial)
              :execution (:seon.dev.artifact/execution-digest initial)
              :execution-runtime
              (:seon.dev.artifact/execution-runtime-digest initial)
              :bootstrap (:seon.dev.artifact/bootstrap-digest initial)
              :css (:seon.dev.artifact/css-digest initial)}))
      (doseq [[component-key manifest-key]
              [[:writer :seon.dev.artifact/writer-digest]
               [:client :seon.dev.artifact/client-digest]
               [:program-source :seon.dev.artifact/program-source-digest]
               [:execution :seon.dev.artifact/execution-digest]
               [:execution-runtime
                :seon.dev.artifact/execution-runtime-digest]
               [:bootstrap :seon.dev.artifact/bootstrap-digest]
               [:css :seon.dev.artifact/css-digest]]]
        (let [original (get @component component-key)]
          (swap! component assoc component-key (apply str (repeat 64 "9")))
          (let [changed (observe)]
            (is (not= (get initial manifest-key) (get changed manifest-key)))
            (is (not= (:seon.dev.artifact/application-digest initial)
                      (:seon.dev.artifact/application-digest changed))))
          (swap! component assoc component-key original))))))

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

(deftest release-programs-use-isolated-process-cache-and-existing-builds
  (let [directory (fs/create-temp-dir {:prefix "seon-release-programs-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/environment {"EXISTING" "value"}}
        release
        {:seon.dev.artifact/release-cache-root
         (str (fs/path directory "cache"))
         :seon.dev.artifact/release-client-output
         (str (fs/path directory "runtime/pod.js"))
         :seon.dev.artifact/release-execution-output
         (str (fs/path directory "runtime/execution.js"))
         :seon.dev.artifact/release-program-source-output
         (str (fs/path directory "runtime/program-sources.edn"))}
        calls (atom [])]
    (try
      (with-redefs [artifact/run-step!
                    (fn [observed-config label argv]
                      (swap! calls conj [observed-config label argv]))]
        (is (= release (artifact/build-release-programs! config release))))
      (is (= ["build release pod" "build release execution child"]
             (mapv second @calls)))
      (doseq [[observed-config _ argv] @calls]
        (is (= "value"
               (get-in observed-config
                       [:seon.dev.config/environment "EXISTING"])))
        (is (= {:cache-root
                (:seon.dev.artifact/release-cache-root release)}
               (edn/read-string
                (get-in observed-config
                        [:seon.dev.config/environment "SHADOW_CLJS"]))))
        (is (= ["clj" "-M:cljs" "release"] (subvec argv 0 3)))
        (is (some #{"--force-spawn"} argv)))
      (let [pod-argv (nth (first @calls) 2)
            execution-argv (nth (second @calls) 2)
            pod-merge (edn/read-string (last pod-argv))
            execution-merge (edn/read-string (last execution-argv))]
        (is (= "client" (nth pod-argv 3)))
        (is (= "execution" (nth execution-argv 3)))
        (is (= (:seon.dev.artifact/release-client-output release)
               (:output-to pod-merge)))
        (is (= [['seon.dev.program-artifact/publish!
                 "runtime/program-sources.edn"]]
               (:build-hooks pod-merge)))
        (is (= {:enabled false :preloads [] :build-notify nil}
               (:devtools pod-merge)))
        (is (= (:seon.dev.artifact/release-execution-output release)
               (:output-to execution-merge)))
        (is (= {:enabled false} (:devtools execution-merge))))
      (finally
        (fs/delete-tree directory)))))

(deftest release-programs-reject-output-outside-the-build-root
  (let [directory (fs/create-temp-dir {:prefix "seon-release-root-test-"})
        outside (fs/create-temp-dir {:prefix "seon-release-outside-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/environment {}}
        release
        {:seon.dev.artifact/release-cache-root
         (str (fs/path directory "cache"))
         :seon.dev.artifact/release-client-output
         (str (fs/path outside "pod.js"))
         :seon.dev.artifact/release-execution-output
         (str (fs/path directory "runtime/execution.js"))
         :seon.dev.artifact/release-program-source-output
         (str (fs/path directory "runtime/program-sources.edn"))}]
    (try
      (is (thrown-with-msg?
           Exception #"must stay in the build root"
           (artifact/build-release-programs! config release)))
      (finally
        (fs/delete-tree directory)
        (fs/delete-tree outside)))))

(deftest release-programs-compile-a-downstream-source-and-preload
  (let [directory (fs/create-temp-dir {:prefix "seon-release-overlay-test-"})
        downstream (fs/create-temp-dir {:prefix "seon-downstream-test-"})
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/environment
                {"SEON_EXTRA_SRC" (str downstream)
                 "SEON_EXTRA_PRELOAD" "acme.pod"
                 "SEON_EXTRA_EXECUTION_MAIN" "acme.execution/-main"}}
        release
        {:seon.dev.artifact/release-cache-root
         (str (fs/path directory "cache"))
         :seon.dev.artifact/release-client-output
         (str (fs/path directory "runtime/pod.js"))
         :seon.dev.artifact/release-execution-output
         (str (fs/path directory "runtime/execution.js"))
         :seon.dev.artifact/release-program-source-output
         (str (fs/path directory "runtime/program-sources.edn"))}
        calls (atom [])]
    (try
      (with-redefs [artifact/run-step!
                    (fn [_ label argv]
                      (swap! calls conj [label argv]))]
        (artifact/build-release-programs! config release))
      (doseq [[_ argv] @calls]
        (is (= ["clj" "-Sdeps"] (subvec argv 0 2)))
        (is (= {'seon.extra/src {:local/root (str downstream)}}
               (:deps (edn/read-string (nth argv 2))))))
      (let [pod-argv (second (first @calls))
            execution-argv (second (second @calls))
            pod-merge (edn/read-string (last pod-argv))
            execution-merge (edn/read-string (last execution-argv))]
        (is (= 'acme.pod/-main (:main pod-merge)))
        (is (= {:enabled false :preloads [] :build-notify nil}
               (:devtools pod-merge)))
        (is (= 'acme.execution/-main (:main execution-merge))))
      (finally
        (fs/delete-tree directory)
        (fs/delete-tree downstream)))))

(deftest source-publication-orders-the-watcher-flush-before-one-manifest
  (let [events (atom [])
        manifest {:seon.dev.artifact/writer-digest "writer"
                  :seon.dev.artifact/application-digest "application"}
        config {:seon.dev.config/source-checkout? true
                :seon.dev.config/artifact-manifest "/unused/artifact.edn"}]
    (with-redefs-fn
      {#'artifact/with-build-lock
       (fn [_ build] (build))
       #'artifact/bun-identity! (constantly {})
       #'artifact/bun-executable-current? (constantly true)
       #'artifact/build-source!
       (fn [_ _] (swap! events conj :source))
       #'artifact/read-manifest
       (constantly nil)
       #'artifact/output-manifest
       (fn [_ _] (swap! events conj :manifest) manifest)
       #'artifact/atomic-spit!
       (fn [_ value] (swap! events conj :publish) value)}
      #(let [result (artifact/build!
                     config (fn [] (swap! events conj :watcher-flush)))]
         (is (= [:source :watcher-flush :manifest :publish] @events))
         (is (= manifest
                (dissoc result :seon.dev.artifact/changed)))))))

(deftest source-publication-requires-the-managed-watcher
  (let [config {:seon.dev.config/source-checkout? true}]
    (with-redefs-fn
      {#'artifact/with-build-lock (fn [_ build] (build))
       #'artifact/bun-identity! (constantly {})}
      #(is (= :seon.dev.artifact.failure/missing-client-owner
              (try
                (artifact/build! config)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (:seon.dev.artifact/failure (ex-data error)))))))))

(deftest source-publication-replaces-an-obsolete-manifest
  (let [published {:seon.dev.artifact/writer-digest "writer-new"
                   :seon.dev.artifact/application-digest "application-new"}
        config {:seon.dev.config/source-checkout? true
                :seon.dev.config/artifact-manifest "/unused/artifact.edn"}]
    (with-redefs-fn
      {#'artifact/with-build-lock (fn [_ build] (build))
       #'artifact/bun-identity! (constantly {})
       #'artifact/bun-executable-current? (constantly true)
       #'artifact/build-source! (fn [_ _])
       #'artifact/read-manifest
       (fn [_] (throw (ex-info "obsolete manifest" {})))
       #'artifact/output-manifest (fn [_ _] published)
       #'artifact/atomic-spit! (fn [_ value] value)}
      #(is (= (assoc published
                     :seon.dev.artifact/changed
                     #{:seon.dev.artifact/writer
                       :seon.dev.artifact/application})
              (artifact/build! config (fn [])))))))

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

(deftest sequential-flavors-publish-immutable-runtime-roots
  (let [directory (fs/create-temp-dir {:prefix "seon-bootstrap-root-test-"})
        source (fs/path directory "out/bootstrap/example.txt")
        client (fs/path directory "out/client/main.js")
        program-source (fs/path directory "out/client/program-sources.edn")
        execution (fs/path directory "out/execution/main.js")
        execution-runtime
        (fs/path directory
                 ".shadow-cljs/builds/execution/dev/out/cljs-runtime/a.js")
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/client-output (str client)
                :seon.dev.config/execution-output (str execution)
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/shadow-cache-root
                (str (fs/path directory ".shadow-cljs"))}]
    (try
      (fs/create-dirs (fs/parent source))
      (fs/create-dirs (fs/parent client))
      (fs/create-dirs (fs/parent execution))
      (fs/create-dirs (fs/parent execution-runtime))
      (doseq [relative ["src" "test" "resources"]]
        (fs/create-dirs (fs/path directory relative)))
      (spit (str source) "default-bootstrap")
      (spit (str client) "default-client")
      (spit (str program-source) "default-program-source")
      (spit (str execution) "default-execution")
      (spit (str execution-runtime) "default-runtime")
      (let [default-digest (artifact/digest-paths
                             directory ["out/bootstrap"])
            default-execution-digest
            (artifact/current-execution-digest config)
            default-runtime-digest
            (artifact/current-execution-runtime-digest config)
            default-program-source-digest
            (artifact/current-program-source-digest config)
            default-root (#'artifact/publish-runtime-root!
                           config default-digest default-execution-digest
                           default-runtime-digest
                           default-program-source-digest)]
        (is (= "default-bootstrap"
               (slurp (str (fs/path default-root
                                    "out/bootstrap/example.txt")))))
        (is (= "default-execution"
               (slurp (str (fs/path default-root
                                    "out/execution/main.js")))))
        (is (= "default-runtime"
               (slurp (str (fs/path
                            default-root
                            ".shadow-cljs/builds/execution/dev/out/cljs-runtime/a.js")))))
        (is (= "default-program-source"
               (slurp (str (fs/path default-root
                                    "out/client/program-sources.edn")))))

        (spit (str source) "acme-bootstrap")
        (let [acme-digest (artifact/digest-paths directory ["out/bootstrap"])
              acme-root (#'artifact/publish-runtime-root!
                         config acme-digest default-execution-digest
                         default-runtime-digest
                         default-program-source-digest)]
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
                 (#'artifact/publish-runtime-root!
                  config acme-digest default-execution-digest
                  default-runtime-digest default-program-source-digest))
              "identical runtime bytes reuse their verified content address")
          (spit (str execution) "changed-execution")
          (let [changed-execution-digest
                (artifact/current-execution-digest config)
                changed-root (#'artifact/publish-runtime-root!
                              config acme-digest changed-execution-digest
                              default-runtime-digest
                              default-program-source-digest)]
            (is (not= acme-root changed-root))
            (is (= "default-execution"
                   (slurp (str (fs/path acme-root
                                        "out/execution/main.js"))))
                "a watcher rebuild cannot mutate an admitted child artifact"))
          (spit (str execution) "default-execution")
          (spit (str program-source) "changed-program-source")
          (let [changed-program-source-digest
                (artifact/current-program-source-digest config)
                changed-root (#'artifact/publish-runtime-root!
                              config acme-digest default-execution-digest
                              default-runtime-digest
                              changed-program-source-digest)]
            (is (not= acme-root changed-root))
            (is (= "default-program-source"
                   (slurp (str (fs/path acme-root
                                        "out/client/program-sources.edn"))))
                "a later source publication cannot mutate an admitted root"))))
      (finally (fs/delete-tree directory)))))

(defn- git-coordinate [suffix]
  {:git/url (str "https://github.com/example/" suffix)
   :git/sha (apply str (repeat 40 suffix))})

(defn- maintained-deps
  ([] (maintained-deps {}))
  ([overrides]
   (let [datahike (git-coordinate "a")
         konserve (git-coordinate "b")]
     (merge-with
       merge
       {:aliases
        {:writer
         {:replace-deps
          {'org.replikativ/datahike datahike
           'org.replikativ/konserve konserve
           'org.replikativ/proximum (git-coordinate "c")}}
         :cljs
         {:extra-deps
          {'thheller/shadow-cljs (git-coordinate "d")}
          :override-deps
          {'org.replikativ/datahike datahike
           'org.replikativ/konserve konserve
           'org.replikativ/superv.async (git-coordinate "e")
           'is.simm/partial-cps (git-coordinate "f")}}}}
       overrides))))

(deftest maintained-dependencies-are-exact-deterministic-alias-selections
  (let [coordinates (#'artifact/maintained-dependencies-from
                      (maintained-deps))]
    (is (= ['org.replikativ/datahike
            'org.replikativ/konserve
            'org.replikativ/proximum
            'thheller/shadow-cljs
            'org.replikativ/superv.async
            'is.simm/partial-cps]
           (mapv :seon.dev.artifact/dependency-library coordinates)))
    (is (every? #(re-matches #"[0-9a-f]{40}"
                             (:seon.dev.artifact/dependency-git-sha %))
                coordinates))
    (is (= coordinates
           (#'artifact/maintained-dependencies-from (maintained-deps))))))

(deftest maintained-dependencies-reject-inexact-or-divergent-selections
  (is (thrown-with-msg?
        Exception #"lacks an exact public Git coordinate"
        (#'artifact/maintained-dependencies-from
         (assoc-in (maintained-deps)
                   [:aliases :writer :replace-deps
                    'org.replikativ/proximum]
                   {:mvn/version "0.1.26"}))))
  (is (thrown-with-msg?
        Exception #"lacks an exact public Git coordinate"
        (#'artifact/maintained-dependencies-from
         (assoc-in (maintained-deps)
                   [:aliases :cljs :extra-deps 'thheller/shadow-cljs]
                   {:git/url "ssh://github.com/example/shadow-cljs"
                    :git/sha (apply str (repeat 40 "d"))}))))
  (doseq [url ["https://user@github.com/example/shadow-cljs"
               "https://github.com/example/shadow-cljs?ref=main"
               "https://github.com/example/shadow-cljs#main"
               "https://gitlab.com/example/shadow-cljs"]]
    (is (thrown-with-msg?
          Exception #"lacks an exact public Git coordinate"
          (#'artifact/maintained-dependencies-from
           (assoc-in (maintained-deps)
                     [:aliases :cljs :extra-deps 'thheller/shadow-cljs
                      :git/url]
                     url)))))
  (is (thrown-with-msg?
        Exception #"select different commits"
        (#'artifact/maintained-dependencies-from
         (assoc-in (maintained-deps)
                   [:aliases :cljs :override-deps
                   'org.replikativ/datahike :git/sha]
                   (apply str (repeat 40 "9")))))))

(deftest maintained-local-root-publishes-its-clean-public-git-identity
  (let [root (System/getProperty "user.dir")
        coordinates (#'artifact/maintained-dependencies root)
        datahike (first coordinates)]
    (is (= 'org.replikativ/datahike
           (:seon.dev.artifact/dependency-library datahike)))
    (is (= "https://github.com/seantempesta/datahike.git"
           (:seon.dev.artifact/dependency-git-url datahike)))
    (is (= (#'artifact/command-output!
            (fs/path root "reference-code/datahike")
            "git" "rev-parse" "HEAD")
           (:seon.dev.artifact/dependency-git-sha datahike)))))

(deftest application-identity-binds-writer-and-maintained-dependencies
  (let [directory (fs/create-temp-dir {:prefix "seon-v4-identity-test-"})
        writer (fs/path directory "target/writer.jar")
        client (fs/path directory "out/client/main.js")
        program-source (fs/path directory "out/client/program-sources.edn")
        execution (fs/path directory "out/execution/main.js")
        runtime (fs/path directory
                         ".shadow-cljs/builds/client/dev/out/cljs-runtime/a.js")
        execution-runtime
        (fs/path directory
                 ".shadow-cljs/builds/execution/dev/out/cljs-runtime/a.js")
        bootstrap (fs/path directory "out/bootstrap/a.js")
        css (fs/path directory "resources/public/css/output.css")
        dependencies (atom (#'artifact/maintained-dependencies-from
                            (maintained-deps)))
        config {:seon.dev.config/root (str directory)
                :seon.dev.config/writer-output (str writer)
                :seon.dev.config/client-output (str client)
                :seon.dev.config/execution-output (str execution)
                :seon.dev.config/shadow-cache-root
                (str (fs/path directory ".shadow-cljs"))
                :seon.dev.config/client-build-id "client"
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default}]
    (try
      (write-test-jar! writer "writer-a")
      (doseq [[path value] [[client "client"] [execution "execution"]
                            [program-source "program-source"]
                            [runtime "runtime"]
                            [execution-runtime "execution-runtime"]
                            [bootstrap "bootstrap"] [css "css"]]]
        (fs/create-dirs (fs/parent path))
        (spit (str path) value))
      (with-redefs [artifact/maintained-dependencies (fn [_] @dependencies)
                    artifact/publish-runtime-root!
                    (fn [_ bootstrap-digest execution-digest runtime-digest
                         program-source-digest]
                      (str "/runtime/" bootstrap-digest "-" execution-digest
                           "-" runtime-digest "-" program-source-digest))]
        (let [bun (artifact/bun-identity! {})
              initial (#'artifact/output-manifest config bun)]
          (swap! dependencies assoc-in
                 [0 :seon.dev.artifact/dependency-git-sha]
                 (apply str (repeat 40 "9")))
          (let [dependency-change (#'artifact/output-manifest config bun)]
            (is (not= (:seon.dev.artifact/application-digest initial)
                      (:seon.dev.artifact/application-digest
                       dependency-change))))
          (reset! dependencies
                  (#'artifact/maintained-dependencies-from (maintained-deps)))
          (write-test-jar! writer "writer-b")
          (let [writer-change (#'artifact/output-manifest config bun)]
            (is (not= (:seon.dev.artifact/application-digest initial)
                      (:seon.dev.artifact/application-digest writer-change))))))
      (finally (fs/delete-tree directory)))))

(deftest only-the-current-manifest-format-is-readable
  (let [directory (fs/create-temp-dir {:prefix "seon-manifest-test-"})
        path (str (fs/path directory "artifact.edn"))
        digest (apply str (repeat 64 "a"))
        config {:seon.dev.config/artifact-manifest path
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default}
        manifest
        (merge
        (artifact/bun-identity! {})
        {:seon.dev.artifact/version artifact/current-version
         :seon.dev.artifact/published-at "2026-07-14T00:00:00Z"
         :seon.dev.artifact/flavor :seon.dev.artifact.flavor/default
         :seon.dev.artifact/client-build-id "client"
         :seon.dev.artifact/execution-build-id "execution"
         :seon.dev.artifact/shadow-cache-root "/checkout/.shadow-cljs"
         :seon.dev.artifact/client-output "/checkout/out/client/main.js"
         :seon.dev.artifact/program-source-path
         "out/client/program-sources.edn"
         :seon.dev.artifact/program-source-digest digest
         :seon.dev.artifact/execution-output "/checkout/out/execution/main.js"
         :seon.dev.artifact/runtime-root "/checkout/runtime/content"
         :seon.dev.artifact/source-input-digest digest
         :seon.dev.artifact/maintained-dependencies
         (#'artifact/maintained-dependencies-from (maintained-deps))
         :seon.dev.artifact/writer-digest digest
         :seon.dev.artifact/client-digest digest
         :seon.dev.artifact/execution-digest digest
         :seon.dev.artifact/execution-runtime-digest digest
         :seon.dev.artifact/bootstrap-digest digest
         :seon.dev.artifact/css-digest digest
         :seon.dev.artifact/application-digest digest})]
    (try
      (spit path (pr-str manifest))
      (is (= manifest (artifact/read-manifest config)))
      (spit path (pr-str (assoc-in manifest
                                   [:seon.dev.artifact/maintained-dependencies
                                    0 :seon.dev.artifact/dependency-library]
                                   'wrong/library)))
      (is (thrown-with-msg? Exception #"identity set is invalid"
                            (artifact/read-manifest config)))
      (doseq [old-version (range 1 artifact/current-version)]
        (spit path (pr-str (assoc manifest :seon.dev.artifact/version
                                  old-version)))
        (is (thrown-with-msg? Exception #"manifest is invalid"
                              (artifact/read-manifest config))
            (str "format " old-version " must be rebuilt")))
      (finally (fs/delete-tree directory)))))
