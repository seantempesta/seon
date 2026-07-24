(ns seon.dev.program-artifact
  "Publish deterministic program artifacts for one exact client build."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.dev.program-inventory :as program-inventory])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption DirectoryNotEmptyException Files
            StandardCopyOption]
           [java.security MessageDigest]))

(def ^:private program-row-marker "SEON_PROGRAM_ROWS_EDN ")
(def ^:private prepared-program-rows ::prepared-program-rows)
(def ^:private prepared-program-sources ::prepared-program-sources)
(def ^:private shadow-node-devtools-client
  'shadow.cljs.devtools.client.node)

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
   classification remains the program inventory's one structural derivation."
  [state]
  (program-inventory/analyzer-fn-inventory
   (get-in state [:compiler-env :cljs.analyzer/namespaces])
   (selected-namespaces state)))

(defn inventory-text
  "Return deterministic EDN bytes for one build inventory artifact."
  [state]
  (str (pr-str (inventory-value state)) "\n"))

(defn digest
  "Return the SHA-256 identity of one program artifact's text."
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
      (throw (ex-info "The program artifact path must stay in the project."
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

(defn- delete-tree! [^File root]
  ;; A clean release flush can still finish materializing source maps for a
  ;; moment after `flush-unoptimized` returns. Re-scan rather than treating one
  ;; lazy file-tree snapshot as complete.
  (loop [attempt 0]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (try
          (Files/deleteIfExists (.toPath ^File file))
          (catch DirectoryNotEmptyException _)))
      (when (.exists root)
        (when (= attempt 99)
          (throw
           (ex-info "The temporary program-row build could not be removed."
                    {:seon.dev.artifact/path (.getCanonicalPath root)})))
        (Thread/sleep 10)
        (recur (inc attempt))))))

(defn- main-append-resource-id [state]
  (some (fn [[resource-id resource]]
          (when (= 'shadow.module.main.append (:ns resource))
            resource-id))
        (:sources state)))

(defn- program-row-build-js []
  (str
   "var seon$program$configuration = "
   "seon.config.resolve_config_singleton"
   "(cljs.core.PersistentArrayMap.EMPTY);\n"
   "var seon$program$raw$rows = cljs.core.concat("
   "seon.client.index_core_BANG_(seon$program$configuration),"
   "seon.client.index_schemas());\n"
   "var seon$program$rows = cljs.core.vec("
   "cljs.core.sort_by.cljs$core$IFn$_invoke$arity$2("
   "seon.client.compiled_program_sort_key,"
   "cljs.core.map.cljs$core$IFn$_invoke$arity$2(function(row){"
   "return cljs.core.apply.cljs$core$IFn$_invoke$arity$3("
   "cljs.core.dissoc,row,"
   "seon.client.compiled_program_wall_clock_attrs);"
   "},seon$program$raw$rows)));\n"
   "process.stdout.write(\"\\n" program-row-marker "\" + "
   "cljs.core.pr_str(seon$program$rows) + \"\\n\");\n"))

(defn- unoptimized-build-state [state]
  (if-not (= :release (:mode state))
    state
    (let [resources (mapv #(get-in state [:sources %])
                          (:build-sources state))
          goog (filterv #(= :goog (:type %)) resources)
          js (filterv #(= :js (:type %)) resources)
          unconverted-ids (mapv :resource-id (concat goog js))
          convert-goog (requiring-resolve 'shadow.build.closure/convert-goog)
          convert-sources
          (requiring-resolve 'shadow.build.closure/convert-sources)]
      (cond-> (update state :output #(apply dissoc % unconverted-ids))
        (seq goog) (convert-goog goog)
        (seq js) (convert-sources js)))))

(defn- disable-shadow-devtools-config
  [state]
  (let [module-config-key :shadow.build.modules/config
        main-entries-path [module-config-key :main :entries]]
    (-> state
        (assoc-in [:shadow.build/config :devtools :enabled] false)
        (update-in main-entries-path
                   (fn [entries]
                     (into [] (remove #{shadow-node-devtools-client}) entries))))))

(defn- disable-shadow-devtools
  [state]
  ((requiring-resolve 'shadow.build.api/analyze-modules)
   (disable-shadow-devtools-config state)))

(defn- derive-program-rows
  [state program-source-text target]
  (let [state
        ((requiring-resolve 'shadow.build.async/wait-for-pending-tasks!)
         state)
        state (-> state
                  disable-shadow-devtools
                  unoptimized-build-state)
        append-id (main-append-resource-id state)]
    (when-not append-id
      (throw
       (ex-info "The client build has no generated main append resource."
                {:seon.dev.artifact/build-id
                 (:shadow.build/build-id state)})))
    (let [parent (.getParentFile ^File target)
          build-root (io/file parent
                              (str ".program-rows-build-" (random-uuid)))
          build-output (io/file build-root "program-rows.js")
          program-source-file (io/file build-root "program-sources.edn")
          program-source-digest (digest program-source-text)
          build-state
          (-> state
              (assoc-in [:node-config :output-to] build-output)
              (assoc-in [:build-options :output-dir] build-root)
              (assoc-in [:build-options :cljs-runtime-path] "cljs-runtime")
              (assoc-in [:output append-id :js] (program-row-build-js)))]
      (try
        (.mkdirs build-root)
        (spit program-source-file program-source-text)
        (let [flushed
              ((requiring-resolve 'shadow.build.node/flush-unoptimized)
               build-state)]
          ((requiring-resolve 'shadow.build.async/wait-for-pending-tasks!)
           flushed))
        (let [environment
              (assoc (into {} (System/getenv))
                     "SEON_PROGRAM_SOURCE_PATH"
                     (.getCanonicalPath ^File program-source-file)
                     "SEON_PROGRAM_SOURCE_DIGEST" program-source-digest)
              executable (or (get environment "SEON_BUN_EXECUTABLE") "bun")
              result
              (shell/sh executable (.getCanonicalPath build-output)
                        :env environment)
              output (:out result)
              marker-index (str/last-index-of output program-row-marker)]
          (when-not (zero? (:exit result))
            (throw
             (ex-info "The compiled program-row derivation failed."
                      {:seon.dev.artifact/exit (:exit result)
                       :seon.dev.artifact/error (str/trim (:err result))})))
          (when-not marker-index
            (throw
             (ex-info "The compiled program-row derivation returned no rows."
                      {:seon.dev.artifact/output (str/trim output)
                       :seon.dev.artifact/error (str/trim (:err result))})))
          (let [program-row-text
                (str/trim
                 (subs output (+ marker-index (count program-row-marker))))]
            {:seon.dev.artifact/program-rows
             (edn/read-string program-row-text)
             :seon.dev.artifact/program-row-text program-row-text}))
        (finally
          (delete-tree! build-root))))))

(defn ^{:shadow.build/stage :flush} publish!
  "Atomically publish deterministic program sources after a client flush."
  [state relative-path]
  (atomic-spit! (output-file state relative-path)
                (or (get-in state [prepared-program-sources relative-path])
                    (artifact-text state)))
  state)

(defn ^{:shadow.build/stage :flush} publish-inventory!
  "Atomically publish the analyzed inventory of one exact Shadow build."
  [state relative-path]
  (atomic-spit! (output-file state relative-path) (inventory-text state))
  state)

(defn ^{:shadow.build/stage :optimize-prepare} prepare-program-rows!
  "Prepare exact compiled boot rows before release optimization rewrites code."
  [state program-source-relative-path relative-path]
  (let [program-source-text (artifact-text state)
        prepared
        (assoc (derive-program-rows
                state program-source-text (output-file state relative-path))
               :seon.dev.artifact/program-source-digest
               (digest program-source-text))]
    (-> state
        (assoc-in [prepared-program-rows relative-path] prepared)
        (assoc-in [prepared-program-sources program-source-relative-path]
                  program-source-text))))

(defn ^{:shadow.build/stage :flush} publish-rows!
  "Publish the exact compiled boot-program rows from the client artifact."
  [state program-source-relative-path relative-path]
  (let [program-source-file
        (output-file state program-source-relative-path)
        _ (when-not (.isFile program-source-file)
            (throw
             (ex-info "The program-source artifact must publish before rows."
                      {:seon.dev.artifact/path
                       program-source-relative-path})))
        program-source-text (slurp program-source-file)
        program-source-digest (digest program-source-text)
        target (output-file state relative-path)
        prepared
        (or (get-in state [prepared-program-rows relative-path])
            (when-not (= :release (:mode state))
              (assoc (derive-program-rows state program-source-text target)
                     :seon.dev.artifact/program-source-digest
                     program-source-digest)))
        rows (:seon.dev.artifact/program-rows prepared)
        compiled-row-text (:seon.dev.artifact/program-row-text prepared)]
    (when-not prepared
      (throw
       (ex-info "The pre-optimization program rows are absent."
                {:seon.dev.artifact/path relative-path})))
    (when-not (= program-source-digest
                 (:seon.dev.artifact/program-source-digest prepared))
      (throw
       (ex-info "Program sources changed after program rows were prepared."
                {:seon.dev.artifact/path program-source-relative-path
                 :seon.dev.artifact/expected
                 (:seon.dev.artifact/program-source-digest prepared)
                 :seon.dev.artifact/actual program-source-digest})))
    (when-not (and (vector? rows) (every? map? rows))
      (throw
       (ex-info "The compiled boot derivation returned invalid program rows."
                {:seon.dev.artifact/value-type (type rows)})))
    (atomic-spit!
     target
     (str "{:seon.dev.artifact/program-rows " compiled-row-text "}\n"))
    state))
