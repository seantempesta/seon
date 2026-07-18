(ns seon.dev.release-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.release :as release]))

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
    (spit (str (fs/path root "seon")) "launcher")
    (spit (str (fs/path root "system.edn")) "{}")
    (spit (str (fs/path root "babashka-license.txt")) "EPL")
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
   :seon.release.member/launcher "seon"
   :seon.release.member/config "system.edn"
   :seon.release.member/babashka-license "babashka-license.txt"})

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
   :seon.dev.release/launcher-member :seon.release.member/launcher
   :seon.dev.release/config-member :seon.release.member/config
   :seon.dev.release/babashka-license-member
   :seon.release.member/babashka-license})

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
      (doseq [directory ["bootstrap" "public" "production-node-modules/lib"
                         "production-node-modules/.bin"]]
        (fs/create-dirs (fs/path inputs directory)))
      (doseq [[path content]
              [["bun" "bun"] ["writer.jar" "writer"] ["pod.js" "pod"]
               ["execution.js" "execution"]
               ["program-sources.edn" "{}"]
               ["bb" "bb"] ["operator.jar" "operator"]
               ["seon" "launcher"] ["system.edn" "{}"]
               ["babashka-license.txt" "EPL"]
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
              ::release/launcher (str (fs/path inputs "seon"))
              ::release/config (str (fs/path inputs "system.edn"))
              ::release/babashka-license
              (str (fs/path inputs "babashka-license.txt"))
              ::release/node-modules (str node-modules)
              ::release/package-json (str (fs/path inputs "package.json"))
              ::release/bun-lock (str (fs/path inputs "bun.lock"))
              ::release/license (str (fs/path inputs "LICENSE"))})]
        (is (= manifest
               (release/read-manifest! (str (fs/path package "release.edn")))))
        (is (fs/executable? (fs/path package "runtime/bun")))
        (is (fs/executable? (fs/path package "runtime/bb")))
        (is (fs/executable? (fs/path package "bin/seon")))
        (is (fs/regular-file? (fs/path package "config/system.edn")))
        (is (fs/regular-file?
             (fs/path package "runtime-root/out/bootstrap/core.js")))
        (is (fs/regular-file?
             (fs/path package "runtime-root/resources/public/output.css")))
        (is (fs/regular-file?
             (fs/path package "node_modules/lib/index.js")))
        (is (not (fs/exists? (fs/path package "node_modules/.bin/tool"))))
        (is (not (re-find (re-pattern
                           (java.util.regex.Pattern/quote (str inputs)))
                          (slurp (str (fs/path package "release.edn")))))))
      (finally (fs/delete-tree inputs {:force true})))))
