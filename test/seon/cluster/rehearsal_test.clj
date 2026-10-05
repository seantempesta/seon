(ns seon.cluster.rehearsal-test
  "`bin/seon check`'s rehearsal (`seon.cluster/rehearse-source!`) publishes and
  adopts a candidate on an overlay of the live store: it answers what boot would
  refuse and writes nothing to the store."
  (:require [babashka.fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.fs :as fs]
            [seon.operator.runtime :refer [running-instances]]
            [seon.test-support :as support]))

(defn- rehearse-edited
  "Rehearse a copy-on-write clone of this JVM's source with `edits` (relative
  path to new content) written into it, as `bin/seon check` rehearses a
  candidate on the running default cluster: the answer, and the store files
  modified while it ran."
  {:malli/schema [:=> [:cat [:map-of :string :string]]
                  [:map [::answer :seon.schema/value] [::written :int]]]}
  [edits]
  (let [root (get-in @running-instances ["default" :seon.boot/config :seon.boot/root])
        store-dir (:seon.boot/store-dir (cluster/resolve-bootstrap {:seon.boot/root root}))
        parent (str (babashka.fs/create-temp-dir {:prefix "seon-rehearsal-test-"}))
        candidate (str (io/file parent "candidate"))]
    (try
      (#'support/clone-directory! (fs/source-directory) candidate)
      (doseq [[path content] edits] (spit (io/file candidate path) content))
      (let [since (System/currentTimeMillis)
            answer (cluster/rehearse-source! root candidate "default")]
        {::answer answer
         ::written (count (filter #(and (.isFile ^java.io.File %) (<= since (.lastModified ^java.io.File %)))
                                  (file-seq (io/file store-dir))))})
      (finally (fs/delete-recursively! (str (babashka.fs/parent parent)) parent)))))

(defn- source-text
  "The text of `path` in this JVM's source."
  {:malli/schema [:=> [:cat :string] :string]}
  [path]
  (slurp (io/file (fs/source-directory) path)))

;; Before the overlay the one-file rehearsal below wrote 126 store files
;; (0.8 MB) and left four dead branch heads in the live store.

(deftest a-changed-file-rehearses-without-writing-the-store
  (let [path "src/seon/cluster/registry.clj"
        {::keys [answer written]} (rehearse-edited {path (str (source-text path) "\n;; rehearsal probe\n")})]
    (is (true? (:seon.source/built? answer)) "the changed file publishes on the overlay")
    (is (= 1 (:seon.cluster/adopted-identities answer)))
    (is (zero? written) "the rehearsal writes nothing to the store")))

(deftest a-task-file-that-is-not-one-vector-refuses
  (let [{::keys [answer written]} (rehearse-edited {"resources/seon/tasks/my.task.edn" "{:not \"a vector\"}\n"})]
    (is (= :seon.source/publication (:seon.error/layer answer)))
    (is (zero? written))))

(deftest a-malformed-schema-resource-refuses
  (let [path "resources/seon/schemas/seon.cluster.registry.edn"
        {::keys [answer written]}
        (rehearse-edited {path (str/replace (source-text path) ":retire-request" ":retire-request [:map")})]
    (is (= :seon.source/publication (:seon.error/layer answer)))
    (is (zero? written))))
