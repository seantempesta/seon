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
                         :execution (apply str (repeat 64 "c"))
                         :bootstrap (apply str (repeat 64 "d"))
                         :css (apply str (repeat 64 "e"))})
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
            #'artifact/current-execution-digest
            (fn [_] (:execution @component))
            #'artifact/maintained-dependencies (constantly [])
            #'artifact/digest-paths
            (fn [_ paths]
              (case (first paths)
                "out/bootstrap" (:bootstrap @component)
                "resources/public/css/output.css" (:css @component)))}
           (fn [] (artifact/current-output-digests config)))]
    (let [initial (observe)]
      (is (= (select-keys @component
                          [:writer :client :execution :bootstrap :css])
             {:writer (:seon.dev.artifact/writer-digest initial)
              :client (:seon.dev.artifact/client-digest initial)
              :execution (:seon.dev.artifact/execution-digest initial)
              :bootstrap (:seon.dev.artifact/bootstrap-digest initial)
              :css (:seon.dev.artifact/css-digest initial)}))
      (doseq [[component-key manifest-key]
              [[:writer :seon.dev.artifact/writer-digest]
               [:client :seon.dev.artifact/client-digest]
               [:execution :seon.dev.artifact/execution-digest]
               [:bootstrap :seon.dev.artifact/bootstrap-digest]
               [:css :seon.dev.artifact/css-digest]]]
        (let [original (get @component component-key)]
          (swap! component assoc component-key (apply str (repeat 64 "f")))
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

(deftest source-publication-orders-the-watcher-flush-before-one-manifest
  (let [events (atom [])
        manifest {:seon.dev.artifact/writer-digest "writer"
                  :seon.dev.artifact/application-digest "application"}
        config {:seon.dev.config/source-checkout? true
                :seon.dev.config/artifact-manifest "/unused/artifact.edn"}]
    (with-redefs-fn
      {#'artifact/with-build-lock
       (fn [_ build] (build))
       #'artifact/build-source!
       (fn [_] (swap! events conj :source))
       #'artifact/read-manifest
       (constantly nil)
       #'artifact/output-manifest
       (fn [_] (swap! events conj :manifest) manifest)
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
      {#'artifact/with-build-lock (fn [_ build] (build))}
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
       #'artifact/build-source! (fn [_])
       #'artifact/read-manifest
       (fn [_] (throw (ex-info "obsolete manifest" {})))
       #'artifact/output-manifest (constantly published)
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
        execution (fs/path directory "out/execution/main.js")
        runtime (fs/path directory
                         ".shadow-cljs/builds/client/dev/out/cljs-runtime/a.js")
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
                            [runtime "runtime"]
                            [bootstrap "bootstrap"] [css "css"]]]
        (fs/create-dirs (fs/parent path))
        (spit (str path) value))
      (with-redefs [artifact/maintained-dependencies (fn [_] @dependencies)
                    artifact/publish-runtime-root!
                    (fn [_ digest] (str "/runtime/" digest))]
        (let [initial (#'artifact/output-manifest config)]
          (swap! dependencies assoc-in
                 [0 :seon.dev.artifact/dependency-git-sha]
                 (apply str (repeat 40 "9")))
          (let [dependency-change (#'artifact/output-manifest config)]
            (is (not= (:seon.dev.artifact/application-digest initial)
                      (:seon.dev.artifact/application-digest
                       dependency-change))))
          (reset! dependencies
                  (#'artifact/maintained-dependencies-from (maintained-deps)))
          (write-test-jar! writer "writer-b")
          (let [writer-change (#'artifact/output-manifest config)]
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
        {:seon.dev.artifact/version artifact/current-version
         :seon.dev.artifact/published-at "2026-07-14T00:00:00Z"
         :seon.dev.artifact/flavor :seon.dev.artifact.flavor/default
         :seon.dev.artifact/client-build-id "client"
         :seon.dev.artifact/execution-build-id "execution"
         :seon.dev.artifact/shadow-cache-root "/checkout/.shadow-cljs"
         :seon.dev.artifact/client-output "/checkout/out/client/main.js"
         :seon.dev.artifact/execution-output "/checkout/out/execution/main.js"
         :seon.dev.artifact/runtime-root "/checkout/runtime/content"
         :seon.dev.artifact/maintained-dependencies
         (#'artifact/maintained-dependencies-from (maintained-deps))
         :seon.dev.artifact/writer-digest digest
         :seon.dev.artifact/client-digest digest
         :seon.dev.artifact/execution-digest digest
         :seon.dev.artifact/bootstrap-digest digest
         :seon.dev.artifact/css-digest digest
         :seon.dev.artifact/application-digest digest}]
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
