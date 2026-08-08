(ns seon.fs.jvm-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [my.fs]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.fs :as filesystem]
            [seon.fs.jvm])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.util.concurrent CountDownLatch]))

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- handler
  [operation]
  (deref (ns-resolve 'seon.fs.jvm operation)))

(defn- policy
  [root]
  {:seon.config.fs/working-root (str root)
   :seon.config.fs/roots [(str root)]
   :seon.config.fs/max-read-bytes (* 64 1024 1024)
   :seon.config.fs/max-inline-bytes 8192
   :seon.config.fs/max-write-bytes (* 64 1024 1024)
   :seon.config.fs/max-glob-results 64
   :seon.config.fs/max-traversal-entries 256
   :seon.config.fs/max-depth 32})

(defn- temp-tree
  []
  (let [base (io/file "tmp/my-fs-test" (str (random-uuid)))]
    (.mkdirs base)
    (.toAbsolutePath (.toPath base))))

(defn- with-temp-tree
  [f]
  (let [root (temp-tree)]
    (try
      (f root)
      (finally
        (filesystem/delete-recursively! (str root) (str root))))))

(defn- write-octets!
  [^Path path octets]
  (Files/createDirectories (.getParent path)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (with-open [output (io/output-stream (.toFile path))]
    (.write output ^bytes (byte-array octets)))
  path)

(defn- sha-256
  [octets]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest ^bytes (byte-array octets)))))

(defn- paths-in
  [root]
  (with-open [children (Files/newDirectoryStream root)]
    (vec children)))

(deftest reads-bytes-without-decoding-or-character-counts
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "binary.dat")
            octets [0 255 195 40 128]
            _ (write-octets! path octets)
            result ((handler 'read)
                    {:my.fs/path "binary.dat" :my.fs/encoding :bytes}
                    (policy root))]
        (is (= octets (:my.fs/bytes result)))
        (is (= (count octets) (:my.fs/file-bytes result)))
        (is (= (count octets) (:my.fs/bytes-read result)))
        (is (= (sha-256 octets) (:my.fs/digest result)))
        (is (true? (:my.fs/eof? result)))
        (is (not (contains? result :my.fs/text)))))))

(deftest a-window-is-bounded-by-the-window-not-by-the-file
  ;; The class: a bound measured against something other than what it bounds.
  ;; The read ceiling used to be compared against the WHOLE file even when the
  ;; request named a window, so the affordance that exists for large files was
  ;; the one large files could not use. There is now no file-sized quantity in
  ;; the windowed path to compare a window against.
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "large.bin")
            file-bytes (* 3 1024 1024)
            octets (byte-array file-bytes)
            _ (dotimes [index file-bytes]
                (aset-byte octets index (unchecked-byte (mod index 251))))
            _ (write-octets! path (vec octets))
            ;; a ceiling far BELOW the file, so a file-sized comparison
            ;; would refuse every one of these reads
            settings (assoc (policy root)
                            :seon.config.fs/max-read-bytes 65536
                            :seon.config.fs/max-inline-bytes 8192)
            tail-offset (- file-bytes 4096)
            tail ((handler 'read)
                  {:my.fs/path "large.bin"
                   :my.fs/byte-offset tail-offset
                   :my.fs/max-bytes 4096
                   :my.fs/encoding :bytes}
                  settings)
            head ((handler 'read)
                  {:my.fs/path "large.bin"
                   :my.fs/byte-offset 1048576
                   :my.fs/max-bytes 64
                   :my.fs/encoding :bytes}
                  settings)]
        (is (nil? (:seon.error/kind tail))
            "a tail window of an over-ceiling file is an ordinary read")
        (is (= 4096 (:my.fs/bytes-read tail)))
        (is (= (mapv #(bit-and 0xff %)
                     (java.util.Arrays/copyOfRange octets
                                                   (int tail-offset)
                                                   (int file-bytes)))
               (:my.fs/bytes tail)))
        (is (= file-bytes (:my.fs/file-bytes tail))
            "the window still reports the size of the file it came from")
        (is (true? (:my.fs/eof? tail)))
        (is (= 64 (:my.fs/bytes-read head)))
        (is (false? (:my.fs/eof? head)))
        (is (not (contains? head :my.fs/digest))
            "a partial window makes no whole-file claim")
        (is (= (sha-256 (java.util.Arrays/copyOfRange octets
                                                      1048576
                                                      (+ 1048576 64)))
               (:my.fs/window-digest head))
            "the window digests exactly what it returned")
        (let [refusal ((handler 'read-complete)
                       {:my.fs/path "large.bin"} settings)]
          (is (= :my.fs/read-limit (:seon.error/kind refusal))
              "only a read that demands the WHOLE file refuses for its size")
          (is (= "large.bin" (get-in refusal [:seon.error/data :my.fs/path]))
              "the refusal names the path")
          (is (= file-bytes
                 (get-in refusal [:seon.error/data :my.fs/file-bytes]))
              "the refusal names the size observed")
          (is (= 65536
                 (get-in refusal
                         [:seon.error/data
                          :seon.config.fs/max-read-bytes])))
          (is (str/includes? (:seon.error/message refusal) ":my.fs/max-bytes")
              "the refusal names the key that reads a window instead"))))))

(deftest utf8-windows-refuse-split-multibyte-characters
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "utf8.txt")
            octets (vec (.getBytes "a€b" StandardCharsets/UTF_8))
            _ (write-octets! path octets)
            request {:my.fs/path "utf8.txt"
                     :my.fs/byte-offset 1
                     :my.fs/max-bytes 1}
            text-result ((handler 'read)
                         (assoc request :my.fs/encoding :utf-8)
                         (policy root))
            byte-result ((handler 'read)
                         (assoc request :my.fs/encoding :bytes)
                         (policy root))]
        (is (= :my.fs/invalid-utf8-window
               (:seon.error/kind text-result)))
        (is (= [(bit-and 0xff (nth octets 1))]
               (:my.fs/bytes byte-result)))))))

(deftest an-attribute-change-after-the-pass-refuses-mixed-evidence
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "moving.txt")
            _ (write-octets! path (repeat 4096 65))
            read-pass-var (ns-resolve 'seon.fs.jvm 'window-pass)
            original (var-get read-pass-var)
            result
            (with-redefs-fn
              {read-pass-var
               (fn [& arguments]
                 (let [pass (apply original arguments)]
                   (write-octets! path (repeat 4097 66))
                   pass))}
              #((handler 'read)
                {:my.fs/path "moving.txt" :my.fs/encoding :bytes}
                (policy root)))]
        (is (= :my.fs/changed-during-read (:seon.error/kind result)))))))

(deftest no-operation-follows-a-final-intermediate-or-broken-link
  (with-temp-tree
    (fn [root]
      (let [outside (.resolve (.getParent root) (str (random-uuid)))
            sentinel (.resolve outside "sentinel.txt")
            inside (.resolve root "inside")
            final-link (.resolve root "final-link")
            broken-link (.resolve root "broken-link")
            intermediate-link (.resolve root "intermediate")
            config (policy root)]
        (try
          (write-octets! sentinel [83 65 70 69])
          (Files/createDirectories inside
                                   (make-array java.nio.file.attribute.FileAttribute 0))
          (Files/createSymbolicLink final-link sentinel
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (Files/createSymbolicLink broken-link (.resolve outside "missing")
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (Files/createSymbolicLink intermediate-link outside
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (doseq [request [{:my.fs/path "final-link" :my.fs/encoding :bytes}
                           {:my.fs/path "broken-link" :my.fs/encoding :bytes}
                           {:my.fs/path "intermediate/sentinel.txt"
                            :my.fs/encoding :bytes}]]
            (is (= :my.fs/path-refused
                   (:seon.error/kind ((handler 'read) request config)))))
          (doseq [path ["final-link" "broken-link" "intermediate/new.txt"]]
            (is (= :my.fs/path-refused
                   (:seon.error/kind
                    ((handler 'write)
                     {:my.fs/path path
                      :my.fs/content {:my.fs/text "changed"}
                      :my.fs/precondition {:my.fs/expected-absence? true}}
                     config)))))
          (is (= [83 65 70 69]
                 (mapv #(bit-and 0xff %)
                       (Files/readAllBytes sentinel))))
          (is (Files/exists sentinel no-follow))
          (finally
            (filesystem/delete-recursively! (str outside) (str outside))))))))

(deftest an-open-directory-handle-defeats-a-swapped-parent-link
  (with-temp-tree
    (fn [root]
      (let [parent (.resolve root "parent")
            parked (.resolve root "parked")
            path (.resolve parent "file.txt")
            outside (.resolve (.getParent root) (str (random-uuid)))
            sentinel (.resolve outside "file.txt")
            before (.getBytes "before" StandardCharsets/UTF_8)
            parent-access-var (ns-resolve 'seon.fs.jvm 'parent-access)
            original (var-get parent-access-var)]
        (try
          (write-octets! path before)
          (write-octets! sentinel (.getBytes "outside" StandardCharsets/UTF_8))
          (let [result
                (with-redefs-fn
                  {parent-access-var
                   (fn [& arguments]
                     (let [opened (apply original arguments)]
                       (Files/move parent parked
                                   (make-array java.nio.file.CopyOption 0))
                       (Files/createSymbolicLink
                        parent outside
                        (make-array java.nio.file.attribute.FileAttribute 0))
                       opened))}
                  #((handler 'write)
                    {:my.fs/path "parent/file.txt"
                     :my.fs/content {:my.fs/text "inside"}
                     :my.fs/precondition
                     {:my.fs/expected-digest (sha-256 before)}}
                    (policy root)))]
            (is (true? (:my.fs/changed? result)))
            (is (= "inside"
                   (String. (Files/readAllBytes (.resolve parked "file.txt"))
                            StandardCharsets/UTF_8)))
            (is (= "outside"
                   (String. (Files/readAllBytes sentinel)
                            StandardCharsets/UTF_8))))
          (finally
            (filesystem/delete-recursively! (str outside) (str outside))))))))

(deftest staged-write-cleanup-deletes-only-its-own-link-entry
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "target.txt")
            outside (.resolve (.getParent root) (str (random-uuid)))
            sentinel (.resolve outside "must-survive.txt")
            before (.getBytes "before" StandardCharsets/UTF_8)
            precondition-var (ns-resolve 'seon.fs.jvm 'precondition-state)
            original (var-get precondition-var)
            calls (atom 0)]
        (try
          (write-octets! path before)
          (write-octets! sentinel [83 65 70 69])
          (let [result
                (with-redefs-fn
                  {precondition-var
                   (fn [& arguments]
                     (let [state (apply original arguments)]
                       (when (= 2 (swap! calls inc))
                         (let [stage
                               (first
                                (filter
                                 #(str/starts-with?
                                   (str (.getFileName ^Path %))
                                   ".seon-write-")
                                 (paths-in root)))]
                           (Files/delete stage)
                           (Files/createSymbolicLink
                            stage sentinel
                            (make-array
                             java.nio.file.attribute.FileAttribute 0))
                           (throw
                            (ex-info
                             "simulated interruption"
                             {:seon.error/kind :my.fs/write-failed
                              :seon.error/message
                              "The staged write was interrupted."
                              :seon.error/data {:my.fs/path "target.txt"}}))))
                       state))}
                  #((handler 'write)
                    {:my.fs/path "target.txt"
                     :my.fs/content {:my.fs/text "after"}
                     :my.fs/precondition
                     {:my.fs/expected-digest (sha-256 before)}}
                    (policy root)))]
            (is (= :my.fs/write-failed (:seon.error/kind result)))
            (is (= "before"
                   (String. (Files/readAllBytes path)
                            StandardCharsets/UTF_8)))
            (is (Files/exists sentinel no-follow))
            (is (empty?
                 (filter
                  #(str/starts-with? (str (.getFileName ^Path %))
                                     ".seon-write-")
                  (paths-in root)))))
          (finally
            (filesystem/delete-recursively! (str outside) (str outside))))))))

(deftest racing-digest-fenced-writes-have-one-winner
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "race.txt")
            before (.getBytes "before" StandardCharsets/UTF_8)
            _ (write-octets! path before)
            expected (sha-256 before)
            ready (CountDownLatch. 2)
            start (CountDownLatch. 1)
            write-one
            (fn [text]
              (.countDown ready)
              (.await start)
              ((handler 'write)
               {:my.fs/path "race.txt"
                :my.fs/content {:my.fs/text text}
                :my.fs/precondition {:my.fs/expected-digest expected}}
               (policy root)))
            left (future (write-one "left"))
            right (future (write-one "right"))]
        (.await ready)
        (.countDown start)
        (let [results [@left @right]]
          (is (= 1 (count (filter :my.fs/changed? results))))
          (is (= 1 (count (filter #(= :my.fs/stale-digest
                                     (:seon.error/kind %))
                                 results))))
          (is (contains? #{"left" "right"}
                         (String. (Files/readAllBytes path)
                                  StandardCharsets/UTF_8))))))))

(deftest blob-backed-content-is-streamed-and-digest-verified
  (with-temp-tree
    (fn [root]
      (let [octets (.getBytes "blob-backed \u0000 bytes" StandardCharsets/UTF_8)
            digest (sha-256 octets)
            read-chunk
            (fn [connection requested offset length]
              (is (= ::connection connection))
              (is (= digest requested))
              (byte-array
               (take length (drop offset octets))))
            result
            (binding [db/*conn* ::connection]
              (with-redefs [blob/read-chunk read-chunk]
                ((handler 'write)
                 {:my.fs/path "from-blob.bin"
                  :my.fs/content {:seon.blob/digest digest}
                  :my.fs/precondition {:my.fs/expected-absence? true}}
                 (policy root))))]
        (is (true? (:my.fs/changed? result)))
        (is (= digest (:my.fs/after-digest result)))
        (is (= (mapv #(bit-and 0xff %) octets)
               (mapv #(bit-and 0xff %)
                     (Files/readAllBytes (.resolve root "from-blob.bin")))))))))

(deftest glob-stops-at-both-policy-ceilings-and-says-so
  (with-temp-tree
    (fn [root]
      (doseq [index (range 20)]
        (write-octets! (.resolve root (str "entry-" index ".txt")) [index]))
      (let [base-policy (policy root)
            result-limited ((handler 'glob)
                            {:my.fs/root "." :my.fs/pattern "*.txt"
                             :my.fs/max-results 3}
                            base-policy)
            traversal-limited ((handler 'glob)
                               {:my.fs/root "." :my.fs/pattern "*.txt"}
                               (assoc base-policy
                                      :seon.config.fs/max-traversal-entries 5))]
        (is (= 3 (:my.fs/returned result-limited)))
        (is (false? (:my.fs/complete? result-limited)))
        (is (<= (:my.fs/examined result-limited) 256))
        (is (<= (:my.fs/examined traversal-limited) 5))
        (is (false? (:my.fs/complete? traversal-limited)))))))

(deftest stat-reports-attributes-without-following-the-final-link
  (with-temp-tree
    (fn [root]
      (let [target (.resolve root "target.txt")
            link (.resolve root "link")]
        (write-octets! target [1 2 3])
        (Files/createSymbolicLink link target
                                  (make-array java.nio.file.attribute.FileAttribute 0))
        (let [result ((handler 'stat) {:my.fs/path "link"} (policy root))]
          (is (true? (:my.fs/symbolic-link? result)))
          (is (false? (:my.fs/regular-file? result)))
          (is (false? (:my.fs/directory? result)))
          (is (not (contains? result :my.fs/byte-size))))))))
