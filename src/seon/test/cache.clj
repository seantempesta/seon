(ns seon.test.cache
  "Declared test and publication input readers: the inventory of files a
  program's evidence depends on and their content digests."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.nio.file Files]))

(def graph-roots
  "Roots containing indexed program files and nonindexed fixtures."
  ["src" "test"])

(def ^:private inventory-bound-ms
  "Bound for Git's local file inventory, matching the existing issue-history
  Git read allowance. Expiry refuses selection rather than omitting inputs."
  30000)

(defn- sha-256
  {:malli/schema [:=> [:cat :seon.blob/octet-array] :string]}
  [^bytes source-bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") source-bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn input-paths
  "Git's input paths, or the exact inventory carried by an exported snapshot."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [directory]
  (let [root (io/file directory)
        recorded (io/file root "test-input-paths.txt")]
    (if (.isFile recorded)
      (vec (enumeration-seq (java.util.StringTokenizer. (slurp recorded) (str (char 0)))))
      (let [child (process/process
               ["git" "--work-tree" (.getPath root) "ls-files"
                "--cached" "--others" "--exclude-standard" "-z"]
               {:dir (.getPath root) :out :string :err :string})]
    (try
      (let [result (deref child inventory-bound-ms ::expired)]
        (when (or (= ::expired result) (not= 0 (:exit result)))
          (throw (ex-info "Git could not enumerate test inputs."
                          {:seon.test.cache/root (.getPath root)
                           :seon.test.cache/result result})))
        (vec (enumeration-seq
              (java.util.StringTokenizer. (:out result) (str (char 0))))))
      (finally (process/destroy-tree child)))))))

(defn- file-input-digests
  "Hash Git's tracked and non-ignored files by repository-relative path.
  Directory links and submodule directories are never traversed.
  The gate snapshot carries the source Git index but hashes its own bytes:
  Git's index identity cannot detect uncommitted working-tree content."
  {:malli/schema [:=> [:cat [:string {:min 1}]]
                  [:map-of [:string {:min 1}] [:string {:min 1}]]]}
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (into
     {}
     (comp
      (map #(io/file root-file %))
      (filter #(.isFile ^File %))
      (map (fn [^File file]
             [(str/replace (str (.relativize (.toPath root-file) (.toPath file))) File/separator "/")
              (sha-256 (Files/readAllBytes (.toPath file)))])))
     (input-paths (.getPath root-file)))))

(defn changed-inputs
  "Repository-relative paths whose bytes differ from a recorded basis."
  {:malli/schema [:=> [:cat
                       [:map-of [:string {:min 1}] [:string {:min 1}]]
                       [:map-of [:string {:min 1}] [:string {:min 1}]]]
                  [:map [:seon.test.cache/changed [:vector [:string {:min 1}]]]
                   [:seon.test.cache/removed [:vector [:string {:min 1}]]]]]}
  [basis-digests current-digests]
  {:seon.test.cache/changed
   (->> current-digests
        (keep (fn [[path digest]]
                (when-not (= digest (get basis-digests path))
                  path)))
        sort
        vec)
   :seon.test.cache/removed
   (->> basis-digests
        (keep (fn [[path _]]
                (when-not (contains? current-digests path)
                  path)))
        sort
        vec)})

(defn- declared-input-roots
  "Checkout-relative roots deps.edn declares as gate inputs: `:paths`, the
  `:test` alias's `:extra-paths`, and every `:local/root` dependency (the
  vendored forks under reference-code/, whose gitlinks are inputs), without
  `.` (the checkout itself, never an input boundary) and without the
  program-graph roots."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:set [:string {:min 1}]]]}
  [root]
  (let [manifest (io/file root "deps.edn")
        ;; A checkout without a manifest declares no roots; the manifest's
        ;; own presence is a gate input file, so its removal still changes
        ;; the inventory digest.
        deps (if (.isFile manifest) (edn/read-string (slurp manifest)) {})
        local-roots (fn [dependencies]
                      (keep (fn [[_ coordinate]] (:local/root coordinate)) dependencies))]
    (into #{}
          (comp (map str)
                (remove #{"."})
                (remove (set graph-roots)))
          (concat (:paths deps)
                  (get-in deps [:aliases :test :extra-paths])
                  (local-roots (:deps deps))
                  (mapcat (fn [[_ alias]] (local-roots (:extra-deps alias)))
                          (:aliases deps))))))

(def gate-input-files
  "Files outside every root that decide what a gate runs or loads: the
  dependency manifest and the launchers. A change to one widens the gate."
  #{"deps.edn" "bin/test" "bin/test-fast" "bin/_test-slot" "bin/test-check"})

(def gate-input-directories
  "Directories outside the classpath whose files the gate or its fixtures
  read: shipped config manifests and analyzer configuration/hooks."
  #{"config" ".clj-kondo"})

(declare gitlink-digests)

(defn input-roots
  "Every checkout-relative root or file whose change is a gate input, for
  one checkout: deps.edn's declared roots and local/root dependencies, the
  pinned gitlinks Git records for that checkout (a vendored dependency is an
  input whether or not deps.edn names it), the config directory, the
  dependency manifest and the launchers. Computed once per question; a
  caller classifying many paths holds the set."
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:set [:string {:min 1}]]]}
  [root]
  (into (into gate-input-files gate-input-directories)
        (concat (declared-input-roots root)
                (keys (gitlink-digests (.getCanonicalPath (io/file root)))))))

(defn input-path?
  "True when `path` is one of `roots` or lies under one of them."
  {:malli/schema [:=> [:cat [:set [:string {:min 1}]] [:string {:min 1}]] :boolean]}
  [roots path]
  (boolean
   (some (fn [root]
           (or (= path root)
               (str/starts-with? path (str root "/"))))
         roots)))

(defn source-file?
  "True for file extensions indexed under a declared source directory.
  Shared by the publication snapshot and its exported-inventory check."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [filename]
  (or (str/ends-with? filename ".clj")
      (str/ends-with? filename ".cljc")
      (str/ends-with? filename ".edn")))

(defn widening-path?
  "True for a gate input without indexed declarations for reach selection.
  Includes declared non-graph roots and nonindexed fixtures under graph roots.
  A bare graph root is not a changed file and never widens.
  Documentation outside those input roots, scratch files and logs never widen.
  The two-argument arity reuses this checkout's already derived input roots."
  {:malli/schema
   [:function
    [:=> [:cat [:string {:min 1}]] :boolean]
    [:=> [:cat [:set [:string {:min 1}]] [:string {:min 1}]] :boolean]]}
  ([path] (widening-path? (input-roots ".") path))
  ([roots path]
   (or (input-path? roots path)
       (and (boolean (some #(str/starts-with? path (str % "/")) graph-roots))
            (not (source-file? path))))))

(defn gitlink-digests
  "Digest each submodule's Git pin; snapshot pins take precedence over the live index.
  The pin is a 40-character commit id, and each digest here is consumed as a
  64-character `:seon.fn.file/digest` of a directory input
  (`seon.cluster.source/capture-paths`), so the pin is hashed into that shape:
  a commit id is not a file digest, and a pin stored raw needs its own attribute."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (let [recorded (io/file root "dependency-pins.txt")
        text (if (.isFile recorded)
               (slurp recorded)
               (let [child (process/process
                            ["git" "--work-tree" root "ls-files" "--stage"]
                            {:dir root :out :string :err :string})]
                 (try
                   (let [result (deref child inventory-bound-ms ::expired)]
                     (when (or (= ::expired result) (not= 0 (:exit result)))
                       (throw (ex-info "Git could not enumerate input gitlinks."
                                       {::root root ::result result})))
                     (:out result))
                   (finally (process/destroy-tree child)))))]
    (into {}
          (keep (fn [line]
                  (when (str/starts-with? line "160000 ")
                    (let [tab (.indexOf ^String line "\t")
                          tokens (vec (enumeration-seq
                                       (java.util.StringTokenizer. (subs line 0 tab))))]
                      [(subs line (inc tab))
                       (sha-256 (.getBytes ^String (second tokens) "UTF-8"))]))))
          (str/split-lines text))))

(defn toolchain-dependencies
  "The gate's pinned dependencies plus the analyzer's declared configuration.
  Inputs come from the same Git/snapshot inventory; ignored caches are absent."
  {:malli/schema [:=> [:cat :string [:set :string]] [:map-of :string :string]]}
  [root configuration-roots]
  (let [root (.getCanonicalPath (io/file root))
        roots (conj configuration-roots "deps.edn")]
    (into (gitlink-digests root)
          (comp (filter #(input-path? roots %))
                (keep (fn [path]
                        (let [file (io/file root path)]
                          (when (.isFile file)
                            [path (sha-256 (Files/readAllBytes (.toPath file)))])))))
          (input-paths root))))

(defn input-digests
  "Path/content digests of snapshot files and pinned gitlinks.
  File links contribute their bytes; directory links are never traversed."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (let [root (.getCanonicalPath (io/file root))]
    (into (file-input-digests root) (gitlink-digests root))))

(defn external-input-digests
  "Select gate inputs that have no indexed declaration evidence."
  {:malli/schema [:=> [:cat [:string {:min 1}] [:map-of :string :string]]
                  [:map-of :string :string]]}
  [root inputs]
  (let [roots (input-roots root)]
    (into {}
          (filter (fn [[path _]] (widening-path? roots path)))
          inputs)))

(defn input-evidence-digest
  "Digest an already selected, sorted external-input inventory."
  {:malli/schema [:=> [:cat [:map-of :string :string]] :seon.source/digest]}
  [inputs]
  (sha-256 (.getBytes (pr-str (into (sorted-map) inputs)) "UTF-8")))

(defn test-input-digest
  "Digest the sorted inventory of gate inputs without indexed declarations,
  nonindexed graph-root fixtures and gitlinks included, for one checkout (`root` names the checkout the
  `inputs` were inventoried from; the one-argument arity is the current
  checkout)."
  {:malli/schema [:function
                  [:=> [:cat [:map-of :string :string]] :seon.source/digest]
                  [:=> [:cat [:string {:min 1}] [:map-of :string :string]] :seon.source/digest]]}
  ([inputs] (test-input-digest "." inputs))
  ([root inputs]
   (input-evidence-digest (external-input-digests root inputs))))
