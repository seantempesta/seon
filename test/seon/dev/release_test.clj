(ns seon.dev.release-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.release :as release])
  (:import [java.util.jar JarEntry JarOutputStream]))

(defn- write-jar! [path timestamp]
  (with-open [output (JarOutputStream. (io/output-stream (str path)))]
    (doseq [[name content] [["META-INF/MANIFEST.MF" "Manifest-Version: 1.0\n"]
                            ["example.txt" "same bytes"]]]
      (let [entry (JarEntry. name)]
        (.setTime entry timestamp)
        (.putNextEntry output entry)
        (.write output (.getBytes content "UTF-8"))
        (.closeEntry output))))
  path)

(defn- fixture! []
  (let [root (fs/create-temp-dir {:prefix "seon-release-test-"})]
    (fs/create-dirs (fs/path root "web" "nested"))
    (spit (str (fs/path root "bun")) "bun")
    (spit (str (fs/path root "writer.jar")) "writer")
    (spit (str (fs/path root "pod.js")) "pod")
    (spit (str (fs/path root "execution.js")) "execution")
    (spit (str (fs/path root "program-sources.edn")) "{}")
    (spit (str (fs/path root "bb")) "bb")
    (spit (str (fs/path root "operator.jar")) "operator")
    (spit (str (fs/path root "detach.py")) "detach")
    (spit (str (fs/path root "seon")) "launcher")
    (spit (str (fs/path root "system.edn")) "{}")
    (spit (str (fs/path root "babashka-license.txt")) "EPL")
    (spit (str (fs/path root "bun-license.txt")) "MIT and linked licenses")
    (spit (str (fs/path root "datahike-license.txt")) "EPL")
    (spit (str (fs/path root "SOURCE.edn")) "{}")
    (spit (str (fs/path root "sbom.cdx.json")) "{}")
    (spit (str (fs/path root "THIRD_PARTY_NOTICES.md")) "# Notices")
    (spit (str (fs/path root "web" "nested" "style.css")) "css")
    root))

(def members
  {:seon.release.member/bun "bun"
   :seon.release.member/writer "writer.jar"
   :seon.release.member/pod "pod.js"
   :seon.release.member/execution "execution.js"
   :seon.release.member/runtime-assets "web"
   :seon.release.member/program-source "program-sources.edn"
   :seon.release.member/babashka "bb"
   :seon.release.member/operator "operator.jar"
   :seon.release.member/detach-helper "detach.py"
   :seon.release.member/launcher "seon"
   :seon.release.member/config "system.edn"
   :seon.release.member/babashka-license "babashka-license.txt"
   :seon.release.member/bun-license "bun-license.txt"
   :seon.release.member/datahike-license "datahike-license.txt"
   :seon.release.member/source "SOURCE.edn"
   :seon.release.member/sbom "sbom.cdx.json"
   :seon.release.member/notices "THIRD_PARTY_NOTICES.md"})

(def runtime-identity
  {:seon.dev.release/bun-version "1.4.0"
   :seon.dev.release/bun-revision
   "d8ecf098572e2b8265b23e40c04efb4067e516cc"
   :seon.dev.release/babashka-version "1.12.218"
   :seon.dev.release/babashka-source-revision
   "0fb349c414e717800be775ba9cb77c95a9eb700d"
   :seon.dev.release/babashka-asset
   "babashka-1.12.218-macos-aarch64.tar.gz"
   :seon.dev.release/babashka-asset-sha-256
   "5bc992f39692b707403fc322e860fc82017da7de4a84a32267abb4d50a0c5f9d"
   :seon.dev.release/database-protocol-version 10
   :seon.dev.release/execution-protocol-version 3
   :seon.dev.release/bun-member :seon.release.member/bun
   :seon.dev.release/writer-member :seon.release.member/writer
   :seon.dev.release/pod-member :seon.release.member/pod
   :seon.dev.release/execution-member :seon.release.member/execution
   :seon.dev.release/runtime-assets-member :seon.release.member/runtime-assets
   :seon.dev.release/program-source-member :seon.release.member/program-source
   :seon.dev.release/babashka-member :seon.release.member/babashka
   :seon.dev.release/operator-member :seon.release.member/operator
   :seon.dev.release/detach-helper-member :seon.release.member/detach-helper
   :seon.dev.release/launcher-member :seon.release.member/launcher
   :seon.dev.release/config-member :seon.release.member/config
   :seon.dev.release/babashka-license-member
   :seon.release.member/babashka-license
   :seon.dev.release/bun-license-member :seon.release.member/bun-license
   :seon.dev.release/datahike-license-member
   :seon.release.member/datahike-license
   :seon.dev.release/source-member :seon.release.member/source
   :seon.dev.release/sbom-member :seon.release.member/sbom
   :seon.dev.release/notices-member :seon.release.member/notices})

(deftest manifest-is-relocatable-deterministic-and-closed
  (let [root (fixture!)]
    (try
      (let [manifest (release/create-manifest (str root) members runtime-identity)
            copied (fs/create-temp-dir {:prefix "seon-release-copy-"})]
        (is (= manifest
               (release/create-manifest (str root) members runtime-identity)))
        (is (not (re-find (re-pattern (java.util.regex.Pattern/quote (str root)))
                          (pr-str manifest))))
        (fs/copy-tree root copied {:replace-existing true})
        (is (= manifest (release/verify-package! (str copied) manifest)))
        (is (= (str (fs/canonicalize (fs/path copied "pod.js")))
               (release/package-path (str copied) manifest
                                     :seon.release.member/pod)))
        (is (thrown-with-msg? Exception #"manifest is invalid"
                              (release/validate-manifest!
                               (assoc manifest :seon.dev.release/host "/tmp"))))
        (fs/delete-tree copied {:force true}))
      (finally (fs/delete-tree root {:force true})))))

(deftest manifest-requires-every-declared-runtime-member
  (let [root (fixture!)]
    (try
      (is (thrown-with-msg?
           Exception
           #"required runtime member is not declared"
           (release/create-manifest
            (str root)
            (dissoc members :seon.release.member/writer)
            runtime-identity)))
      (finally (fs/delete-tree root {:force true})))))

(deftest manifest-file-is-verified-relative-to-its-new-directory
  (let [root (fixture!)]
    (try
      (let [manifest (release/create-manifest (str root) members runtime-identity)
            path (fs/path root "release.edn")]
        (spit (str path) (pr-str manifest))
        (is (= manifest (release/read-manifest! (str path)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest manifest-rejects-non-relative-and-non-normalized-paths
  (let [root (fixture!)]
    (try
      (doseq [path ["/pod.js" "../pod.js" "web/../pod.js" "./pod.js"
                    "web//nested" "C:/pod.js" "web\\nested"]]
        (is (thrown? Exception
                     (release/create-manifest
                      (str root) {:seon.release.member/bad path}
                      runtime-identity))
            path))
      (finally (fs/delete-tree root {:force true})))))

(deftest package-verification-rejects-missing-and-changed-members
  (let [root (fixture!)]
    (try
      (let [manifest
            (release/create-manifest (str root) members runtime-identity)]
        (spit (str (fs/path root "pod.js")) "changed")
        (is (thrown-with-msg? Exception #"digest does not match"
                              (release/verify-package! (str root) manifest)))
        (fs/delete (fs/path root "pod.js"))
        (is (thrown-with-msg? Exception #"member is missing"
                              (release/verify-package! (str root) manifest))))
      (finally (fs/delete-tree root {:force true})))))

(deftest package-verification-rejects-undeclared-entries
  (let [root (fixture!)]
    (try
      (let [manifest
            (release/create-manifest (str root) members runtime-identity)]
        (spit (str (fs/path root "undeclared.txt")) "not in the release")
        (is (thrown-with-msg? Exception #"entry is not declared"
                              (release/verify-package! (str root) manifest))))
      (finally (fs/delete-tree root {:force true})))))

(deftest package-verification-rejects-symlinks-at-every-member-level
  (let [root (fixture!)
        outside (fs/create-temp-dir {:prefix "seon-release-outside-"})]
    (try
      (spit (str (fs/path outside "outside.js")) "outside")
      (testing "the declared member"
        (fs/delete (fs/path root "pod.js"))
        (fs/create-sym-link (fs/path root "pod.js")
                            (fs/path outside "outside.js"))
        (is (thrown-with-msg? Exception #"symbolic link"
                              (release/create-manifest
                               (str root) members runtime-identity))))
      (testing "a directory inside a declared member"
        (fs/delete (fs/path root "pod.js"))
        (spit (str (fs/path root "pod.js")) "pod")
        (fs/delete-tree (fs/path root "web" "nested") {:force true})
        (fs/create-sym-link (fs/path root "web" "nested") outside)
        (is (thrown-with-msg? Exception #"symbolic link"
                              (release/create-manifest
                               (str root) members runtime-identity))))
      (finally
        (fs/delete-tree root {:force true})
        (fs/delete-tree outside {:force true})))))

(deftest application-digest-binds-path-name-and-content-only
  (let [root (fixture!)]
    (try
      (let [manifest
            (release/create-manifest (str root) members runtime-identity)
            member-list (:seon.dev.release/members manifest)]
        (doseq [changed
                [(assoc-in manifest [::release/identity ::release/bun-version]
                           "1.4.1")
                 (assoc-in manifest [::release/members 0 ::release/sha-256]
                           (apply str (repeat 64 "a")))]]
          (is (thrown-with-msg? Exception #"application digest does not match"
                                (release/validate-manifest! changed))))
        (is (= member-list
               (:seon.dev.release/members
                (release/create-manifest (str root) (into {} (reverse members))
                                         runtime-identity)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest source-free-package-assembly-publishes-only-declared-runtime-files
  (let [inputs (fs/create-temp-dir {:prefix "seon-release-inputs-"})
        package (fs/path inputs "published")
        node-modules (fs/path inputs "production-node-modules")]
    (try
      (doseq [directory ["bootstrap" "public" "config"
                         "production-node-modules/lib"
                         "production-node-modules/.bin"]]
        (fs/create-dirs (fs/path inputs directory)))
      (doseq [[path content]
              [["bun" "bun"] ["writer.jar" "writer"] ["pod.js" "pod"]
               ["execution.js" "execution"]
               ["program-sources.edn" "{}"]
               ["bb" "bb"] ["operator.jar" "operator"]
               ["detach.py" "detach"]
               ["seon" "launcher"] ["config/system.edn" "{}"]
               ["brand.css" ".brand {}"]
               ["babashka-license.txt" "EPL"]
               ["bun-license.txt" "MIT and linked licenses"]
               ["datahike-license.txt" "EPL"]
               ["SOURCE.edn" "{}"]
               ["sbom.cdx.json" "{\"bomFormat\":\"CycloneDX\"}"]
               ["THIRD_PARTY_NOTICES.md" "# Notices"]
               ["bootstrap/core.js" "bootstrap"]
               ["public/output.css" "css"]
               ["production-node-modules/lib/index.js" "module"]
               ["package.json" "{\"license\":\"AGPL-3.0-only\"}"]
               ["bun.lock" "lock"] ["LICENSE" "AGPL"]]]
        (spit (str (fs/path inputs path)) content))
      (fs/create-sym-link (fs/path node-modules ".bin/tool")
                          (fs/path node-modules "lib/index.js"))
      (let [manifest
            (release/assemble-package!
             {::release/package-root (str package)
              ::release/bun (str (fs/path inputs "bun"))
              ::release/bun-version "1.4.0"
              ::release/writer (str (fs/path inputs "writer.jar"))
              ::release/pod (str (fs/path inputs "pod.js"))
              ::release/execution (str (fs/path inputs "execution.js"))
              ::release/bootstrap (str (fs/path inputs "bootstrap"))
              ::release/public-assets (str (fs/path inputs "public"))
              ::release/program-source
              (str (fs/path inputs "program-sources.edn"))
              ::release/babashka (str (fs/path inputs "bb"))
              ::release/babashka-asset
              {:seon.dev.release/asset
               "babashka-1.12.218-macos-aarch64.tar.gz"
               :seon.dev.release/sha-256
               "5bc992f39692b707403fc322e860fc82017da7de4a84a32267abb4d50a0c5f9d"}
              ::release/operator (str (fs/path inputs "operator.jar"))
              ::release/detach-helper (str (fs/path inputs "detach.py"))
              ::release/launcher (str (fs/path inputs "seon"))
              ::release/config (str (fs/path inputs "config/system.edn"))
              ::release/brand-css (str (fs/path inputs "brand.css"))
              ::release/babashka-license
              (str (fs/path inputs "babashka-license.txt"))
              ::release/bun-license
              (str (fs/path inputs "bun-license.txt"))
              ::release/datahike-license
              (str (fs/path inputs "datahike-license.txt"))
              ::release/source (str (fs/path inputs "SOURCE.edn"))
              ::release/sbom (str (fs/path inputs "sbom.cdx.json"))
              ::release/notices
              (str (fs/path inputs "THIRD_PARTY_NOTICES.md"))
              ::release/node-modules (str node-modules)
              ::release/package-json (str (fs/path inputs "package.json"))
              ::release/bun-lock (str (fs/path inputs "bun.lock"))
              ::release/license (str (fs/path inputs "LICENSE"))})]
        (is (= manifest
               (release/read-manifest! (str (fs/path package "release.edn")))))
        (is (fs/executable? (fs/path package "runtime/bun")))
        (is (fs/executable? (fs/path package "runtime/bb")))
        (is (fs/executable? (fs/path package "bin/seon")))
        (is (fs/regular-file? (fs/path package "runtime/detach.py")))
        (is (fs/regular-file? (fs/path package "sbom.cdx.json")))
        (is (fs/regular-file? (fs/path package "SOURCE.edn")))
        (is (fs/regular-file?
             (fs/path package "THIRD_PARTY_LICENSES/bun-LICENSE.md")))
        (is (fs/regular-file?
             (fs/path package "THIRD_PARTY_LICENSES/datahike-EPL-1.0.txt")))
        (is (fs/regular-file? (fs/path package "config/selected.edn")))
        (is (fs/regular-file?
             (fs/path package "runtime-root/out/bootstrap/core.js")))
        (is (fs/regular-file?
             (fs/path package "runtime-root/resources/public/output.css")))
        (is (= ".brand {}"
               (slurp (str (fs/path package
                                    "runtime-root/resources/public/seon-brand.css")))))
        (is (fs/regular-file?
             (fs/path package "node_modules/lib/index.js")))
        (is (not (fs/exists? (fs/path package "node_modules/.bin/tool"))))
        (is (not (re-find (re-pattern
                           (java.util.regex.Pattern/quote (str inputs)))
                          (slurp (str (fs/path package "release.edn")))))))
      (finally (fs/delete-tree inputs {:force true})))))

(deftest sdk-manifest-closes-and-verifies-the-source-inventory
  (let [root (fs/create-temp-dir {:prefix "seon-sdk-manifest-"})
        source (fs/path root "source")
        revision (apply str (repeat 40 "a"))]
    (try
      (fs/create-dirs source)
      (spit (str (fs/path source "deps.edn")) "{}\n")
      (let [manifest {:seon.dev.sdk/version release/sdk-version
                      :seon.dev.sdk/source-revision revision
                      :seon.dev.sdk/datahike-revision revision
                      :seon.dev.sdk/bun-revision revision
                      :seon.dev.sdk/babashka-revision revision
                      :seon.dev.sdk/source-sha-256
                      (#'release/member-sha-256 source)}]
        (spit (str (fs/path root "sdk.edn")) (pr-str manifest))
        (is (= manifest (release/read-sdk-manifest!
                         (str (fs/path root "sdk.edn")))))
        (spit (str (fs/path source "deps.edn")) "{:changed true}\n")
        (is (thrown-with-msg? Exception #"source digest does not match"
                              (release/verify-sdk! (str root) manifest))))
      (finally (fs/delete-tree root {:force true})))))

(deftest sdk-source-inventory-registers-the-existing-development-tools
  (let [source-paths (set @#'release/sdk-source-paths)]
    (is (contains? source-paths "AGENTS.md"))
    (is (contains? source-paths ".mcp.json"))
    (is (contains? source-paths ".codex/config.toml"))
    (is (not (contains? source-paths ".shadow-cljs")))))

(deftest jar-normalization-removes-packaging-time-from-executable-bytes
  (let [root (fs/create-temp-dir {:prefix "seon-normalized-jar-"})
        first-jar (write-jar! (fs/path root "first.jar") 1000000000000)
        second-jar (write-jar! (fs/path root "second.jar") 2000000000000)]
    (try
      (#'release/normalize-jar! first-jar)
      (#'release/normalize-jar! second-jar)
      (is (= (seq (fs/read-all-bytes first-jar))
             (seq (fs/read-all-bytes second-jar))))
      (finally (fs/delete-tree root {:force true})))))

(deftest release-metadata-inventories-the-production-closures
  (let [root (fs/create-temp-dir {:prefix "seon-release-metadata-"})
        node-modules (fs/path root "node_modules/example")
        writer (fs/path root "writer.jar")]
    (try
      (fs/create-dirs node-modules)
      (spit (str (fs/path node-modules "package.json"))
            "{\"name\":\"example\",\"version\":\"1.2.3\",\"license\":\"MIT\"}")
      (with-open [output (JarOutputStream. (io/output-stream (str writer)))]
        (let [entry (JarEntry.
                     "META-INF/maven/org.example/library/pom.properties")]
          (.putNextEntry output entry)
          (.write output
                  (.getBytes "groupId=org.example\nartifactId=library\nversion=4.5.6\n"
                             "UTF-8"))
          (.closeEntry output)))
      (is (= [{:seon.release.component/ecosystem "npm"
               :seon.release.component/name "example"
               :seon.release.component/version "1.2.3"
               :seon.release.component/license "MIT"
               :seon.release.component/purl "pkg:npm/example@1.2.3"}]
             (#'release/npm-components node-modules)))
      (is (= "pkg:maven/org.example/library@4.5.6"
             (:seon.release.component/purl
              (first (#'release/writer-components writer)))))
      (finally (fs/delete-tree root {:force true})))))
