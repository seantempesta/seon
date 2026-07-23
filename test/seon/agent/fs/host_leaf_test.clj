(ns seon.agent.fs.host-leaf-test
  "Localized JVM filesystem leaf contract tests."
  (:require
   [clojure.test :refer [deftest is testing]]
   [seon.agent.fs.leaf :as leaf])
  (:import
   (java.nio.file Files Path)
   (java.nio.file.attribute FileAttribute)))

(deftest real-file-roundtrip-preserves-the-child-envelope
  (let [root (Files/createTempDirectory
              "seon-u8-fs-" (make-array FileAttribute 0))
        path (.resolve ^Path root "roundtrip.txt")
        path-text (str path)]
    (try
      (leaf/configure!
       {:seon.agent.fs/allowed-roots [(str root)]
        :seon.agent.fs/read-only? false})
      (is (= {:seon.agent.fs/ok? true
              :seon.agent.fs/path path-text}
             (leaf/write-file
              {:seon.agent.fs/path path-text
               :seon.agent.fs/content "alpha\nbeta\n"})))
      (let [read-result
            (leaf/read-file {:seon.agent.fs/path path-text
                             :seon.agent.fs/from-line 2
                             :seon.agent.fs/max-lines 1})]
        (is (:seon.agent.fs/ok? read-result))
        (is (= "beta" (:seon.agent.fs/content read-result)))
        (is (= 2 (:seon.agent.fs/total-lines read-result)))
        (is (re-matches #"[0-9a-f]{64}"
                        (:seon.agent.fs/file-sha read-result))))
      (testing "the same grant guards metadata and mutation calls"
        (is (true? (leaf/file-exists? {:seon.agent.fs/path path-text})))
        (is (true? (:seon.agent.fs/ok?
                    (leaf/replace!
                     {:seon.agent.fs/path path-text
                      :seon.agent.fs/find "beta"
                      :seon.agent.fs/replace "gamma"}))))
        (is (= "alpha\ngamma\n"
               (:seon.agent.fs/content
                (leaf/read-file {:seon.agent.fs/path path-text})))))
      (finally
        (Files/deleteIfExists path)
        (Files/deleteIfExists root)))))

(deftest default-deny-is-an-error-value
  (leaf/configure!
   {:seon.agent.fs/allowed-roots []
    :seon.agent.fs/read-only? false})
  (let [result (leaf/read-file {:seon.agent.fs/path "/not/granted"})]
    (is (false? (:seon.agent.fs/ok? result)))
    (is (= :allowlist (:seon.agent.fs/denial result)))
    (is (string? (:seon.agent.fs/error result)))))
