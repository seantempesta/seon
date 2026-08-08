(ns seon.test.selection
  "Select the tests one change can reach, from the one program graph.

  The gate's default tier answers a single question: given the files whose
  bytes differ from the last GREEN basis, which tests can observe that
  difference? The answer is derived from `:seon.fn/calls` edges in the
  manifest `seon.fn` already builds — the same facts `seon.fn/tests-reaching`
  queries once a program graph is published. The gate runs before any cluster
  exists, so it reads those edges from the manifest value rather than from a
  database; the edges, the identities, and the reachability relation are the
  same ones.

  Nothing here consults a file modification time, a filename convention, or a
  maintained list. A file is changed when its SHA-256 differs from the digest
  recorded by the last green run."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.security MessageDigest)))

(def graph-roots
  "Roots whose files are represented in the program-graph manifest."
  ["src" "test"])

(def widening-inputs
  "Declared gate inputs that no `:seon.fn/calls` edge can reach.

  A schema resource, a config default, an operator script, a dependency
  manifest, or the gate itself can change the behaviour of any test without
  changing one indexed call edge. A change below these paths widens the
  default tier to every non-long test rather than guessing."
  ["resources" "config" "script" "bin/test" "bb.edn" "deps.edn"
   ".clj-kondo/config.edn"])

(defn- sha-256
  [^bytes source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- relative-path
  [^File root ^File file]
  (str/replace (str (.relativize (.toPath root) (.toPath file)))
               File/separator "/"))

(defn- digestible?
  [^File file]
  (and (.isFile file)
       (Files/isRegularFile (.toPath file)
                            (into-array LinkOption
                                        [LinkOption/NOFOLLOW_LINKS]))
       (not (str/includes? (.getPath file) "/__pycache__/"))))

(defn input-digests
  "SHA-256 by repository-relative path for every declared gate input."
  {:malli/schema [:=> [:cat [:string {:min 1}]]
                  [:map-of [:string {:min 1}] [:string {:min 1}]]]}
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (into
     (sorted-map)
     (comp
      (mapcat (fn [input]
                (let [file (io/file root-file input)]
                  (if (.isDirectory file)
                    (file-seq file)
                    [file]))))
      (filter digestible?)
      (map (fn [^File file]
             [(relative-path root-file file)
              (sha-256 (Files/readAllBytes (.toPath file)))])))
     (concat graph-roots widening-inputs))))

(defn changed-inputs
  "Repository-relative paths whose bytes differ from a recorded basis."
  {:malli/schema [:=> [:cat
                       [:map-of [:string {:min 1}] [:string {:min 1}]]
                       [:map-of [:string {:min 1}] [:string {:min 1}]]]
                  [:map [:seon.test.selection/changed [:vector [:string {:min 1}]]]
                   [:seon.test.selection/removed [:vector [:string {:min 1}]]]]]}
  [basis-digests current-digests]
  {:seon.test.selection/changed
   (->> current-digests
        (keep (fn [[path digest]]
                (when-not (= digest (get basis-digests path))
                  path)))
        sort
        vec)
   :seon.test.selection/removed
   (->> basis-digests
        (keep (fn [[path _]]
                (when-not (contains? current-digests path)
                  path)))
        sort
        vec)})

(defn widening-path?
  "True when a changed path is a gate input outside the program graph."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :boolean]}
  [path]
  (boolean
   (some (fn [input]
           (or (= path input)
               (str/starts-with? path (str input "/"))))
         widening-inputs)))

(defn- row-identities
  [row]
  (keep identity [(some->> (:seon.fn/sym row) (vector :seon.fn/sym))
                  (some->> (:seon.test/sym row) (vector :seon.test/sym))]))

(defn- row-edges
  "Identity pairs `[caller called]` this row contributes."
  [row]
  (let [callers (row-identities row)]
    (for [caller callers
          called (concat (:seon.fn/calls row)
                         (when-let [subject (:seon.test/subject row)]
                           [subject]))
          :when (vector? called)]
      [caller called])))

(defn reaching-tests
  "Test symbols reaching any identity defined in `changed-paths`.

  `artifacts` are manifest file artifacts; `changed-paths` are the same
  repository-relative paths. Seeds are every identity DEFINED in a changed
  file — a require-only edit changes no function body yet must still select
  that namespace's dependents — and the walk follows `:seon.fn/calls` and
  `:seon.test/subject` edges backwards to their callers."
  {:malli/schema [:=> [:cat
                       [:vector [:map
                                 [:seon.fn.file/path [:string {:min 1}]]]]
                       [:sequential [:string {:min 1}]]]
                  [:vector [:string {:min 1}]]]}
  [artifacts changed-paths]
  (let [changed (set changed-paths)
        rows (mapcat :seon.fn.file/rows artifacts)
        seeds (into #{}
                    (comp (filter #(contains? changed
                                              (:seon.fn.file/path %)))
                          (mapcat :seon.fn.file/rows)
                          (mapcat row-identities))
                    artifacts)
        callers-of (reduce
                    (fn [index [caller called]]
                      (update index called (fnil conj #{}) caller))
                    {}
                    (mapcat row-edges rows))
        reached (loop [reached seeds
                       frontier seeds]
                  (if (empty? frontier)
                    reached
                    (let [next-frontier
                          (into #{}
                                (comp (mapcat #(get callers-of %))
                                      (remove reached))
                                frontier)]
                      (recur (into reached next-frontier) next-frontier))))]
    (->> reached
         (keep (fn [[attribute value]]
                 (when (= :seon.test/sym attribute) value)))
         sort
         vec)))

(defn manifest-relative-artifacts
  "Manifest artifacts with repository-relative paths."
  {:malli/schema [:=> [:cat [:string {:min 1}] [:map]]
                  [:vector [:map [:seon.fn.file/path [:string {:min 1}]]]]]}
  [root manifest]
  (let [root-file (.getCanonicalFile (io/file root))]
    (mapv (fn [artifact]
            (update artifact :seon.fn.file/path
                    (fn [path]
                      (relative-path root-file
                                     (.getCanonicalFile (io/file path))))))
          (:seon.fn.manifest/artifacts manifest))))

(defn- basis-file
  "The recorded green-basis artifact below one checkout root.

  Private on purpose: the gate runs before any cluster exists, so this
  namespace must stay free of load-time schema registration."
  [source-root]
  (io/file source-root "tmp" "test-basis" "green-basis.edn"))

(defn read-basis
  "The last recorded green basis, or nil when none exists or it is unreadable."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:maybe [:map]]]}
  [source-root]
  (let [file (basis-file source-root)]
    (when (.isFile file)
      (try
        (edn/read-string (slurp file))
        (catch Throwable _ nil)))))

(defn write-basis!
  "Record one green basis atomically below the checkout root."
  {:malli/schema [:=> [:cat [:string {:min 1}] [:map]] :nil]}
  [source-root basis]
  (let [file (basis-file source-root)
        temporary (io/file (.getParentFile file)
                           (str ".green-basis." (random-uuid) ".edn"))]
    (.mkdirs (.getParentFile file))
    (spit temporary (pr-str basis))
    (.renameTo temporary file)
    nil))
