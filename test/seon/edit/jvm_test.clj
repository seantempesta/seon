(ns seon.edit.jvm-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.edit.jvm]
            [seon.fs :as filesystem])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- handler
  []
  (deref (ns-resolve 'seon.edit.jvm 'edit)))

(defn- policy
  [root]
  {:seon.config.fs/working-root (str root)
   :seon.config.fs/roots [(str root)]
   :seon.config.fs/max-read-bytes (* 64 1024 1024)
   :seon.config.fs/max-inline-bytes 8192
   :seon.config.fs/max-write-bytes (* 64 1024 1024)})

(defn- temp-tree
  []
  (let [base (io/file "tmp/my-edit-test" (str (random-uuid)))]
    (.mkdirs base)
    (.toAbsolutePath (.toPath base))))

(defn- with-temp-tree
  [f]
  (let [root (temp-tree)]
    (try
      (f root)
      (finally
        (filesystem/delete-recursively! (str root) (str root))))))

(defn- write-text!
  [^Path path text]
  (Files/write path (.getBytes ^String text StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0))
  path)

(defn- read-text
  [^Path path]
  (String. (Files/readAllBytes path) StandardCharsets/UTF_8))

(defn- sha-256
  [text]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of)
                (.digest digest (.getBytes ^String text
                                           StandardCharsets/UTF_8)))))

(deftest stale-digest-refuses-before-every-transform
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "source.clj")
            before "(defn target [] :old)\n"
            stale (apply str (repeat 64 "0"))
            requests
            [{:my.edit/path "source.clj"
              :my.edit/expected-digest stale
              :my.edit/form {:my.edit.form/head 'defn
                             :my.edit.form/name 'target}
              :my.edit/operation :replace
              :my.edit/source "(defn target [] :new)"}
             {:my.edit/path "source.clj"
              :my.edit/expected-digest stale
              :my.edit/old-string ":old"
              :my.edit/new-string ":new"}
             {:my.edit/path "source.clj"
              :my.edit/expected-digest stale
              :my.edit/from-line 1
              :my.edit/to-line 1
              :my.edit/old-window before
              :my.edit/new-window "(defn target [] :new)\n"}]]
        (write-text! path before)
        (doseq [request requests]
          (is (= :my.edit/stale-source
                 (:seon.error/kind ((handler) request (policy root)))))
          (is (= before (read-text path))))))))

(deftest handler-reuses-the-conditional-atomic-writer
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "source.clj")
            before (str ";; untouched\r\n"
                        "(defn target  [x]\r\n  (+ x 1))\r\n"
                        ";; still untouched\r\n")
            after (str ";; untouched\r\n"
                       "(defn target [x] (* x 2))\r\n"
                       ";; still untouched\r\n")
            request {:my.edit/path "source.clj"
                     :my.edit/expected-digest (sha-256 before)
                     :my.edit/form {:my.edit.form/head 'defn
                                    :my.edit.form/name 'target}
                     :my.edit/operation :replace
                     :my.edit/source "(defn target [x] (* x 2))"}]
        (write-text! path before)
        (let [result ((handler) request (policy root))]
          (is (true? (:my.edit/changed? result)))
          (is (= (sha-256 before) (:my.edit/before-digest result)))
          (is (= (sha-256 after) (:my.edit/after-digest result)))
          (is (= (count (.getBytes before StandardCharsets/UTF_8))
                 (:my.edit/before-bytes result)))
          (is (= (count (.getBytes after StandardCharsets/UTF_8))
                 (:my.edit/after-bytes result)))
          (is (= after (read-text path))))))))

(deftest racing-one-digest-lands-exactly-one-edit
  (with-temp-tree
    (fn [root]
      (let [path (.resolve root "source.clj")
            before "(defn target [] :old)\n"
            base {:my.edit/path "source.clj"
                  :my.edit/expected-digest (sha-256 before)
                  :my.edit/form {:my.edit.form/head 'defn
                                 :my.edit.form/name 'target}
                  :my.edit/operation :replace}
            _ (write-text! path before)
            start (promise)
            runs (mapv (fn [value]
                         (future
                           @start
                           ((handler) (assoc base :my.edit/source
                                            (str "(defn target [] " value ")"))
                            (policy root))))
                       [:first :second])]
        (deliver start true)
        (let [results (mapv deref runs)]
          (is (= 1 (count (filter :my.edit/changed? results))))
          (is (= 1 (count (filter #(= :my.edit/stale-source
                                      (:seon.error/kind %))
                                 results)))))))))
