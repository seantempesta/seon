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
    (spit (str (fs/path root "web" "nested" "style.css")) "css")
    root))

(def members
  {:seon.release.member/bun "bun"
   :seon.release.member/writer "writer.jar"
   :seon.release.member/pod "pod.js"
   :seon.release.member/execution "execution.js"
   :seon.release.member/runtime-assets "web"
   :seon.release.member/program-source "program-sources.edn"})

(def runtime-identity
  {:seon.dev.release/bun-version "1.4.0"
   :seon.dev.release/bun-revision
   "d8ecf098572e2b8265b23e40c04efb4067e516cc"
   :seon.dev.release/database-protocol-version 10
   :seon.dev.release/execution-protocol-version 3
   :seon.dev.release/bun-member :seon.release.member/bun
   :seon.dev.release/writer-member :seon.release.member/writer
   :seon.dev.release/pod-member :seon.release.member/pod
   :seon.dev.release/execution-member :seon.release.member/execution
   :seon.dev.release/runtime-assets-member :seon.release.member/runtime-assets
   :seon.dev.release/program-source-member :seon.release.member/program-source})

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
