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
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files)
           (java.security MessageDigest)))

(def graph-roots
  "Roots whose files are represented in the program-graph manifest."
  ["src" "test"])

(def ^:private inventory-bound-ms
  "Bound for Git's local file inventory, matching the existing issue-history
  Git read allowance. Expiry refuses selection rather than omitting inputs."
  30000)

(defn- sha-256
  [^bytes source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- relative-path
  [^File root ^File file]
  (str/replace (str (.relativize (.toPath root) (.toPath file)))
               File/separator "/"))

(defn- input-paths
  [^File root]
  (let [child (process/process
               ["git" "--work-tree" (.getPath root) "ls-files"
                "--cached" "--others" "--exclude-standard" "-z"]
               {:dir (.getPath root) :out :string :err :string})]
    (try
      (let [result (deref child inventory-bound-ms ::expired)]
        (when (or (= ::expired result) (not= 0 (:exit result)))
          (throw (ex-info "Git could not enumerate test inputs."
                          {:seon.test.selection/root (.getPath root)
                           :seon.test.selection/result result})))
        (vec (enumeration-seq
              (java.util.StringTokenizer. (:out result) (str (char 0))))))
      (finally (process/destroy-tree child)))))

(defn input-digests
  "Hash Git's tracked and non-ignored files by repository-relative path.
  Directory links and submodule directories are never traversed.
  The gate snapshot carries the source Git index but hashes its own bytes."
  {:malli/schema [:=> [:cat [:string {:min 1}]]
                  [:map-of [:string {:min 1}] [:string {:min 1}]]]}
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (into
     (sorted-map)
     (comp
      (map #(io/file root-file %))
      (filter #(.isFile ^File %))
      (map (fn [^File file]
             [(relative-path root-file file)
              (sha-256 (Files/readAllBytes (.toPath file)))])))
     (input-paths root-file))))

(defn source-inputs
  "The program-source part of a gate's recorded input digests."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string :string]]}
  [digests]
  (into (sorted-map)
        (filter (fn [[path _]]
                  (some #(str/starts-with? path (str % "/")) graph-roots)))
        digests))

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
  (not
   (some (fn [input]
           (or (= path input)
               (str/starts-with? path (str input "/"))))
         graph-roots)))

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
                         (:seon.fn/references row)
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
  `:seon.test/subject` edges backwards to their callers. References always
  participate alongside resolved calls. Unresolved file references select
  tests from that file."
  {:malli/schema [:=> [:cat
                       [:vector [:map
                                 [:seon.fn.file/relative-path [:string {:min 1}]]]]
                       [:sequential [:string {:min 1}]]]
                  [:vector [:string {:min 1}]]]}
  [artifacts changed-paths]
  (let [changed (set changed-paths)
        rows (mapcat :seon.fn.file/rows artifacts)
        seeds (into #{}
                    (comp (filter #(contains? changed
                                              (:seon.fn.file/relative-path %)))
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
    (let [by-file (mapcat
                   (fn [artifact]
                     (let [file-rows (:seon.fn.file/rows artifact)]
                       (when (some (fn [row]
                                     (some #(contains? reached [:seon.fn/sym %])
                                           (:seon.fn/unresolved-references row))) file-rows)
                         (keep :seon.test/sym file-rows)))) artifacts)]
      (->> (concat by-file
                   (keep (fn [[attribute value]]
                           (when (= :seon.test/sym attribute) value)) reached))
           distinct sort vec))))

(defn- source-forms [source]
  ;; Selection also loads in the dependency tool's minimal JVM. Use its own
  ;; non-evaluating reader, without adding another parser to that classpath.
  ;; Unresolvable reader aliases conservatively select every declaration in
  ;; this file; they never make an uncertain declaration appear unchanged.
  (try
    (binding [*read-eval* false]
      (with-open [input (java.io.PushbackReader. (java.io.StringReader. source))]
        (loop [forms []]
          (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} input)]
            (if (= ::eof form) forms (recur (conj forms form)))))))
    (catch Exception _ nil)))

(defn missing-overlay-callers
  "Changed caller files omitted from an overlay of changed public declarations."
  {:malli/schema [:=> [:cat [:vector :seon.fn.file/artifact]
                       [:map-of :string :string] [:sequential :string]
                       [:sequential :string]] [:vector :string]]}
  [artifacts overlay-texts overlay-paths dirty-paths]
  (let [overlay (set overlay-paths)
        dirty (set dirty-paths)
        changed
        (into #{}
              (mapcat
               (fn [artifact]
                 (let [path (:seon.fn.file/relative-path artifact)]
                   (when (overlay path)
                     (let [forms (set (source-forms (get overlay-texts path "")))]
                       (for [row (:seon.fn.file/rows artifact)
                             :when (and (:seon.fn/sym row)
                                        (:seon.fn/arglists row)
                                        (not (:seon.fn/private? row))
                                        (or (not (:seon.fn/source row))
                                            (not (contains? forms
                                                            (first (source-forms (:seon.fn/source row)))))))]
                         [:seon.fn/sym (:seon.fn/sym row)]))))))
              artifacts)]
    (->> artifacts
         (keep (fn [artifact]
                 (let [path (:seon.fn.file/relative-path artifact)]
                   (when (and (dirty path) (not (overlay path))
                              (some (fn [row]
                                      (some #(contains? changed
                                                       (if (vector? %) % [:seon.fn/sym %]))
                                            (:seon.fn/calls row)))
                                    (:seon.fn.file/rows artifact)))
                     path))))
         distinct sort vec)))

(defn- changed-working-paths [source git-sha]
  (mapcat
   (fn [arguments]
     (let [child (process/process arguments {:dir source :out :string :err :string})]
       (try
         (let [result (deref child inventory-bound-ms ::expired)]
           (when (or (= ::expired result) (not= 0 (:exit result)))
             (throw (ex-info "Git could not enumerate changed overlay callers."
                             {::result result})))
           (vec (enumeration-seq (java.util.StringTokenizer. (:out result) (str (char 0))))))
         (finally (process/destroy-tree child)))))
   [["git" "diff" "--no-renames" "--name-only" "-z" git-sha "--"]
    ["git" "ls-files" "--others" "--exclude-standard" "-z"]]))

(defn assert-complete-overlay!
  "Refuse changed caller files missing from the immutable selected snapshot."
  {:malli/schema [:=> [:cat :seon.fn.manifest/manifest :string :string :string
                       [:sequential :string]] :nil]}
  [manifest source snapshot git-sha overlay-paths]
  (let [artifacts (:seon.fn.manifest/artifacts manifest)
        paths (set overlay-paths)
        texts (into {}
                    (keep (fn [artifact]
                            (let [path (:seon.fn.file/relative-path artifact)
                                  file (io/file snapshot path)]
                              (when (and (paths path) (.isFile file))
                                [path (slurp file)]))))
                    artifacts)
        missing (missing-overlay-callers artifacts texts overlay-paths
                                         (changed-working-paths source git-sha))]
    (when (seq missing)
      (throw (ex-info
              (str "Incomplete --paths overlay; add changed caller files: "
                   (str/join " " missing))
              {::missing-callers missing})))
    nil))

(defn- basis-file
  "The recorded green-basis artifact below one checkout root.

  Private on purpose: the gate runs before any cluster exists, so this
  namespace must stay free of load-time schema registration."
  [source-root]
  (io/file source-root "tmp" "test-basis" "green-basis.edn"))

(defn read-basis
  "The last recorded green basis, or nil when none has been recorded.

  Absence is an honest nil, which `seon.test.runner` announces on the gate
  output as its selection reason before widening to every eligible test. A
  file that exists but does not read as a basis carrying input digests is a
  different fact and is refused: treating a corrupt artifact as absence would
  quietly widen, and treating it as a basis would quietly narrow."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:maybe [:map]]]}
  [source-root]
  (let [file (basis-file source-root)]
    (if (.exists file)
      (try
        (let [basis (edn/read-string (slurp file))
              digests (:seon.test.basis/digests basis)]
          (when-not (and (map? basis) (map? digests)
                         (every? (fn [[path digest]]
                                   (and (string? path) (seq path)
                                        (string? digest) (seq digest)))
                                 digests))
            (throw (ex-info "Green basis must carry input digests." {})))
          basis)
        (catch Throwable failure
          (throw (ex-info "Cannot read the recorded green basis."
                          {:seon.error/kind ::invalid-basis
                           :seon.error/message "Cannot read the recorded green basis."
                           :seon.error/data
                           {::path (.getPath file)
                            ::cause (let [message (ex-message failure)]
                                      (if (or (nil? message)
                                              (= "" (.trim ^String message)))
                                        (.getName (class failure))
                                        message))}}
                          failure))))
      nil)))

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
