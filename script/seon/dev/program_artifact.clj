(ns seon.dev.program-artifact
  "Publish the exact first-party source strings used by one client build."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.security MessageDigest]))

(defn- canonical-file [root value]
  (.getCanonicalFile
   (let [file (io/file (str value))]
     (if (.isAbsolute file) file (io/file (str root) (str value))))))

(defn- path-within? [^File root ^File file]
  (.startsWith (.toPath file) (.toPath root)))

(defn- source-resource? [resource-name]
  (and (string? resource-name)
       (or (str/ends-with? resource-name ".cljs")
           (str/ends-with? resource-name ".cljc"))))

(defn- safe-resource-name? [resource-name]
  (and (source-resource? resource-name)
       (not (str/starts-with? resource-name "/"))
       (not-any? #{".."} (str/split resource-name #"/"))))

(defn- admitted-roots [state]
  (let [project-file (io/file (str (:project-dir state)))
        project (if (.isAbsolute project-file)
                  (.getAbsoluteFile project-file)
                  (.getAbsoluteFile (io/file "." (str project-file))))
        extra (System/getenv "SEON_EXTRA_SRC")]
    (mapv (fn [^File root]
            {:lexical (.getAbsoluteFile root)
             :canonical (.getCanonicalFile root)})
          (cond-> [project]
            (not (str/blank? extra))
            (conj (let [file (io/file extra)]
                    (if (.isAbsolute file) file (io/file project extra))))))))

(defn- lexical-file [project value]
  (let [file (io/file (str value))]
    (.getAbsoluteFile
     (if (.isAbsolute file) file (io/file project (str value))))))

(defn program-sources
  "Return a sorted resource-name to source-string map for admitted sources."
  [state]
  (let [roots (admitted-roots state)
        project (:lexical (first roots))]
    (reduce
     (fn [sources [_ resource]]
       (let [resource-name (:resource-name resource)
             file-value (:file resource)]
         (if-not (and file-value (source-resource? resource-name))
           sources
           (do
             (when-not (safe-resource-name? resource-name)
               (throw (ex-info "A program source has an unsafe resource name."
                               {:seon.dev.artifact/resource-name resource-name})))
             (let [lexical (lexical-file project file-value)
                   canonical (.getCanonicalFile lexical)
                   lexical-admitted?
                   (some #(path-within? (:lexical %) lexical) roots)
                   canonical-admitted?
                   (some #(path-within? (:canonical %) canonical) roots)]
               (cond
                 (and lexical-admitted? (not canonical-admitted?))
                 (throw (ex-info "A program source escapes its admitted root."
                                 {:seon.dev.artifact/resource-name resource-name
                                  :seon.dev.artifact/file (str lexical)
                                  :seon.dev.artifact/canonical-file
                                  (str canonical)}))

                 (not canonical-admitted?)
                 sources

                 (not (.isFile canonical))
                 (throw (ex-info "An admitted program source is not a file."
                                 {:seon.dev.artifact/resource-name resource-name
                                  :seon.dev.artifact/file (str canonical)}))

                 :else
                 (assoc sources resource-name (slurp canonical))))))))
     (sorted-map)
     (:sources state))))

(defn artifact-value
  "Return the deterministic ordinary value written by the flush hook."
  [state]
  {:seon.dev.artifact/program-sources (program-sources state)})

(defn artifact-text
  "Return deterministic EDN bytes for one program-source artifact."
  [state]
  (str (pr-str (artifact-value state)) "\n"))

(defn selected-namespaces
  "Return the exact analyzer namespaces selected by Shadow's build closure."
  [state]
  (let [get-source-by-id (requiring-resolve 'shadow.build.data/get-source-by-id)]
    (into (sorted-set)
          (keep (fn [resource-id]
                  (:ns (get-source-by-id state resource-id))))
          (:build-sources state))))

(defn inventory-value
  "Derive one canonical function inventory from Shadow's analyzed build.

   This hook owns only Shadow's exact build-source selection. Function
   classification remains the client indexer's one analyzer derivation."
  [state]
  ((requiring-resolve 'seon.client.indexing/analyzer-fn-inventory)
   (get-in state [:compiler-env :cljs.analyzer/namespaces])
   (selected-namespaces state)))

(defn inventory-text
  "Return deterministic EDN bytes for one build inventory sidecar."
  [state]
  (str (pr-str (inventory-value state)) "\n"))

(defn digest
  "Return the SHA-256 identity of one program-source artifact text."
  [text]
  (let [hasher (MessageDigest/getInstance "SHA-256")]
    (.update hasher (.getBytes ^String text StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest hasher)))))

(defn- output-file [state relative-path]
  (let [project (canonical-file (io/file ".") (:project-dir state))
        output (canonical-file project relative-path)]
    (when-not (and (string? relative-path)
                   (not (str/blank? relative-path))
                   (not (.isAbsolute (io/file relative-path)))
                   (path-within? project output))
      (throw (ex-info "The program-source artifact path must stay in the project."
                      {:seon.dev.artifact/path relative-path})))
    output))

(defn- atomic-spit! [^File target text]
  (.mkdirs (.getParentFile target))
  (let [temporary (io/file (.getParentFile target)
                           (str "." (.getName target) "."
                                (random-uuid) ".tmp"))]
    (try
      (spit temporary text)
      (Files/move (.toPath temporary) (.toPath target)
                  (into-array CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists (.toPath temporary)))))
  target)

(defn ^{:shadow.build/stage :flush} publish!
  "Atomically publish deterministic program sources after a client flush."
  [state relative-path]
  (atomic-spit! (output-file state relative-path) (artifact-text state))
  state)

(defn ^{:shadow.build/stage :flush} publish-inventory!
  "Atomically publish the analyzed inventory of one exact Shadow build."
  [state relative-path]
  (atomic-spit! (output-file state relative-path) (inventory-text state))
  state)
