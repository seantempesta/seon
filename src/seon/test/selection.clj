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
            [clojure.string :as str]
            [seon.test.cache :as cache]))

; Remaining callers in the held runner migrate with selection admission.
(def graph-roots cache/graph-roots)
(def ^:private inventory-bound-ms 30000)

(defn input-digests
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (cache/input-digests root))

(defn source-inputs
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string :string]]}
  [digests]
  (cache/source-inputs digests))

(defn widening-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (cache/widening-path? path))

(defn changed-inputs
  {:malli/schema [:=> [:cat [:map-of :string :string] [:map-of :string :string]]
                  [:map [:seon.test.selection/changed [:vector :string]]
                   [:seon.test.selection/removed [:vector :string]]]]}
  [before after]
  (let [delta (cache/changed-inputs before after)]
    {:seon.test.selection/changed (:seon.test.cache/changed delta)
     :seon.test.selection/removed (:seon.test.cache/removed delta)}))

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
          :when (qualified-symbol? called)]
      [caller [:seon.fn/sym called]])))

(defn declaration-closure
  "Walk supplied declaration adjacency, retaining removed seeds."
  {:malli/schema [:=> [:cat [:map-of :seon.program/identity [:set :seon.program/identity]]
                       [:set :seon.program/identity]] [:set :seon.program/identity]]}
  [callers-of seeds]
  (loop [reached seeds frontier seeds]
    (if (empty? frontier)
      reached
      (let [next-frontier (into #{} (comp (mapcat #(get callers-of %))
                                        (remove reached)) frontier)]
        (recur (into reached next-frontier) next-frontier)))))

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
                  [:vector :seon.test/sym]]}
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
        reached (declaration-closure callers-of seeds)]
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
  "Admit the selected snapshot and record omitted dirty callers at HEAD."
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
    (doseq [path missing]
      (println "bin/test: caller" path "uses HEAD bytes in this snapshot; checkout edits are excluded"))
    (let [file (io/file snapshot "tmp" "test-overlay-provenance.edn")]
      (io/make-parents file)
      (spit file (pr-str (if (seq missing)
                          {:seon.test.run/callers-at-head (set missing)} {}))))
    nil))

(defn overlay-provenance
  "Read the launcher observation carried by this immutable snapshot."
  {:malli/schema [:=> [:cat :string]
                  [:map [:seon.test.run/callers-at-head {:optional true}
                         :seon.test.run/callers-at-head]]]}
  [snapshot]
  (let [file (io/file snapshot "tmp" "test-overlay-provenance.edn")]
    (if (.isFile file) (edn/read-string (slurp file)) {})))
