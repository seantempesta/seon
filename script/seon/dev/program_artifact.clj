(ns seon.dev.program-artifact
  "Publish deterministic program artifacts for one exact client build."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.dev.config :as config]
            [seon.dev.program-inventory :as program-inventory])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption DirectoryNotEmptyException Files
            StandardCopyOption]
           [java.security MessageDigest]
           [java.time ZoneId]))

(def ^:private program-row-marker "SEON_PROGRAM_ROWS_EDN ")
(def ^:private base-projection-marker "SEON_BASE_PROJECTION_EDN ")
(def ^:private base-load-plan-marker "SEON_BASE_LOAD_PLAN_EDN ")
(def ^:private page-plan-marker "SEON_PAGE_PLAN_EDN ")
(def ^:private prepared-program-rows ::prepared-program-rows)
(def ^:private prepared-program-sources ::prepared-program-sources)
(defonce ^:private !prepared-program-rows
  ;; Shadow reconstructs release build state between :optimize-prepare and
  ;; :flush. Keep the exact pre-optimization proof in this build-process cache.
  (atom {}))
(defonce ^:private !prepared-program-sources (atom {}))
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

(defn- prepared-key [state relative-path]
  ;; `:mode` changes as Shadow advances from optimization into flush.
  [(:shadow.build/build-id state) relative-path])

(defn- prepared-file [state relative-path]
  (let [target (output-file state relative-path)]
    (io/file (.getParentFile target)
             (str "." (.getName target) ".prepared"))))

(defn- prepared-program [state relative-path]
  (or (get-in state [prepared-program-rows relative-path])
      (get @!prepared-program-rows (prepared-key state relative-path))
      (let [file (prepared-file state relative-path)]
        (when (.isFile file)
          (edn/read-string (slurp file))))))

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

(defn- program-row-build-js [config-manifest-digest page-rows]
  (str
   "var seon$program$manifest = seon.config.load_manifest();\n"
   "var seon$program$configuration = "
   "seon.config.resolve_config_singleton(seon$program$manifest);\n"
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
   "var seon$program$row$text = "
   "\"{:seon.dev.artifact/program-rows \" + "
   "cljs.core.pr_str(seon$program$rows) + \"}\\n\";\n"
   "var seon$program$row$digest = require(\"crypto\")"
   ".createHash(\"sha256\").update(seon$program$row$text,\"utf8\")"
   ".digest(\"hex\");\n"
   "var seon$base$projection = "
   "seon.client.build_base_projection(seon$program$rows);\n"
   "var seon$page$plan = seon.client.build_page_plan("
   "cljs.core.PersistentArrayMap.createAsIfByAssoc(["
   "cljs.core.keyword(\"seon.db/program\"),seon$program$rows,"
   "cljs.core.keyword(\"seon.execution/artifact-digest\"),"
   "seon$program$row$digest,"
   "cljs.core.keyword(\"seon.db.initialization/config-manifest-digest\"),"
   (pr-str config-manifest-digest) ","
   "cljs.core.keyword(\"seon.db.initialization/page-rows\"),"
   page-rows "]));\n"
   "process.stdout.write(\"\\n" program-row-marker "\" + "
   "cljs.core.pr_str(seon$program$rows) + \"\\n\");\n"
   "process.stdout.write(\"\\n" base-projection-marker "\" + "
   "cljs.core.pr_str(seon$base$projection) + \"\\n\");\n"
   "process.stdout.write(\"\\n" page-plan-marker "\" + "
   "cljs.core.pr_str(seon$page$plan) + \"\\n\");\n"))

(defn- build-environment []
  (cond-> (into {} (System/getenv))
    (str/blank? (System/getenv "SEON_HOST_TIMEZONE"))
    (assoc "SEON_HOST_TIMEZONE" (str (ZoneId/systemDefault)))))

(defn- derive-base-load-plan
  [state]
  (let [root (canonical-file (io/file ".") (:project-dir state))
        expression
        (str "(require 'seon.host.context) "
             "(print \"" base-load-plan-marker "\") "
             "(prn (seon.host.context/base-load-plan))")
        result
        (shell/sh "clojure" "-M:writer:host" "-e" expression
                  :dir (.getCanonicalPath ^File root)
                  :env (build-environment))
        output (:out result)
        marker-index (str/last-index-of output base-load-plan-marker)]
    (when-not (zero? (:exit result))
      (throw
       (ex-info "The host base-load plan derivation failed."
                {:seon.dev.artifact/exit (:exit result)
                 :seon.dev.artifact/error (str/trim (:err result))})))
    (when-not marker-index
      (throw
       (ex-info "The host base-load plan derivation returned no plan."
                {:seon.dev.artifact/output (str/trim output)
                 :seon.dev.artifact/error (str/trim (:err result))})))
    (edn/read-string
     (str/trim
      (subs output (+ marker-index (count base-load-plan-marker)))))))

(defn- resolved-build-configuration [state]
  (let [root (canonical-file (io/file ".") (:project-dir state))
        environment (build-environment)
        configured-path (get environment "SEON_RESOLVED_MANIFEST_PATH")
        expected-sha-256
        (get environment "SEON_RESOLVED_MANIFEST_SHA_256")
        selected
        (when-not (str/blank? configured-path)
          (canonical-file root configured-path))
        page-rows
        (some-> (get environment "SEON_DB_INITIALIZATION_PAGE_ROWS")
                parse-long)]
    (when-not (and selected
                   (.isFile ^File selected)
                   (re-matches #"[0-9a-f]{64}" (or expected-sha-256 ""))
                   (pos-int? page-rows))
      (throw
       (ex-info "The page-plan build has no admitted resolved manifest."
                {:seon.dev.artifact/resolved-manifest-path configured-path
                 :seon.dev.artifact/resolved-manifest-sha-256
                 expected-sha-256
                 :seon.db.initialization/page-rows page-rows})))
    (let [actual-sha-256
          (config/config-manifest-digest (slurp selected))]
      (when-not (= expected-sha-256 actual-sha-256)
        (throw
         (ex-info "The admitted resolved manifest digest changed."
                  {:seon.dev.artifact/resolved-manifest-path
                   (.getCanonicalPath ^File selected)
                   :seon.dev.artifact/expected-sha-256 expected-sha-256
                   :seon.dev.artifact/actual-sha-256 actual-sha-256}))))
    {:seon.dev.artifact/config-manifest-digest expected-sha-256
     :seon.dev.artifact/config-manifest-path (.getCanonicalPath ^File selected)
     :seon.dev.artifact/page-rows page-rows
     :seon.dev.artifact/environment
     (assoc environment "SEON_CONFIG" (.getCanonicalPath ^File selected))}))

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
          build-configuration (resolved-build-configuration state)
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
              (assoc-in
               [:output append-id :js]
               (program-row-build-js
                (:seon.dev.artifact/config-manifest-digest
                 build-configuration)
                (:seon.dev.artifact/page-rows build-configuration))))]
      (try
        (.mkdirs build-root)
        (spit program-source-file program-source-text)
        (let [flushed
              ((requiring-resolve 'shadow.build.node/flush-unoptimized)
               build-state)]
          ((requiring-resolve 'shadow.build.async/wait-for-pending-tasks!)
           flushed))
        (let [environment
              (assoc (:seon.dev.artifact/environment build-configuration)
                     "SEON_PROGRAM_SOURCE_PATH"
                     (.getCanonicalPath ^File program-source-file)
                     "SEON_PROGRAM_SOURCE_DIGEST" program-source-digest)
              executable (or (get environment "SEON_BUN_EXECUTABLE") "bun")
              result
              (shell/sh executable (.getCanonicalPath build-output)
                        :env environment)
              output (:out result)
              row-marker-index (str/last-index-of output program-row-marker)
              base-projection-marker-index
              (str/last-index-of output base-projection-marker)
              page-plan-marker-index (str/last-index-of output page-plan-marker)]
          (when-not (zero? (:exit result))
            (throw
             (ex-info "The compiled program-row derivation failed."
                      {:seon.dev.artifact/exit (:exit result)
                       :seon.dev.artifact/error (str/trim (:err result))})))
          (when-not row-marker-index
            (throw
             (ex-info "The compiled program-row derivation returned no rows."
                      {:seon.dev.artifact/output (str/trim output)
                       :seon.dev.artifact/error (str/trim (:err result))})))
          (when-not page-plan-marker-index
            (throw
             (ex-info "The compiled program-row derivation returned no page plan."
                      {:seon.dev.artifact/output (str/trim output)
                       :seon.dev.artifact/error (str/trim (:err result))})))
          (when-not base-projection-marker-index
            (throw
             (ex-info
              "The compiled program-row derivation returned no base projection."
              {:seon.dev.artifact/output (str/trim output)
               :seon.dev.artifact/error (str/trim (:err result))})))
          (let [program-row-text
                (str/trim
                 (subs output
                       (+ row-marker-index (count program-row-marker))
                       base-projection-marker-index))
                base-projection-text
                (str/trim
                 (subs output
                       (+ base-projection-marker-index
                          (count base-projection-marker))
                       page-plan-marker-index))
                page-plan-text
                (str/trim
                 (subs output
                       (+ page-plan-marker-index (count page-plan-marker))))
                program-row-artifact-text
                (str "{:seon.dev.artifact/program-rows "
                     program-row-text "}\n")]
            {:seon.dev.artifact/program-rows
             (edn/read-string program-row-text)
             :seon.dev.artifact/program-row-text program-row-text
             :seon.dev.artifact/program-row-artifact-digest
             (digest program-row-artifact-text)
             :seon.dev.artifact/base-projection
             (edn/read-string base-projection-text)
             :seon.dev.artifact/base-projection-text base-projection-text
             :seon.dev.artifact/page-plan
             (edn/read-string page-plan-text)
             :seon.dev.artifact/page-plan-text page-plan-text
             :seon.dev.artifact/config-manifest-digest
             (:seon.dev.artifact/config-manifest-digest build-configuration)}))
        (finally
          (delete-tree! build-root))))))

(defn ^{:shadow.build/stage :flush} publish!
  "Atomically publish deterministic program sources after a client flush."
  [state relative-path]
  (let [prepared-source
        (or
         (get-in state [prepared-program-sources relative-path])
         (get @!prepared-program-sources (prepared-key state relative-path))
         (let [file (prepared-file state relative-path)]
           (when (.isFile file) (slurp file)))
         (some
          (fn [[_ prepared]]
            (when (= relative-path
                     (:seon.dev.artifact/program-source-relative-path prepared))
              (:seon.dev.artifact/program-source-text prepared)))
          (get state prepared-program-rows)))]
    (atomic-spit! (output-file state relative-path)
                  (or prepared-source (artifact-text state))))
  state)

(defn ^{:shadow.build/stage :flush} publish-inventory!
  "Atomically publish the analyzed inventory of one exact Shadow build."
  [state relative-path]
  (atomic-spit! (output-file state relative-path) (inventory-text state))
  state)

(defn- prepare-program
  "Derive the one prepared program value used by release and watch flushes."
  [state program-source-relative-path relative-path program-source-text]
  (assoc (derive-program-rows state program-source-text
                              (output-file state relative-path))
         :seon.dev.artifact/base-load-plan (derive-base-load-plan state)
         :seon.dev.artifact/program-source-digest
         (digest program-source-text)
         :seon.dev.artifact/program-source-relative-path
         program-source-relative-path
         :seon.dev.artifact/program-source-text program-source-text))

(defn ^{:shadow.build/stage :optimize-prepare} prepare-program-rows!
  "Prepare exact compiled boot rows before release optimization rewrites code."
  [state program-source-relative-path relative-path
   _base-projection-relative-path _page-plan-relative-path]
  (let [program-source-text (artifact-text state)
        prepared
        (prepare-program state program-source-relative-path relative-path
                         program-source-text)
        _ (swap! !prepared-program-rows
                 assoc (prepared-key state relative-path) prepared)
        _ (swap! !prepared-program-sources
                 assoc (prepared-key state program-source-relative-path)
                 program-source-text)
        _ (atomic-spit! (prepared-file state relative-path)
                        (pr-str prepared))
        _ (atomic-spit! (prepared-file state program-source-relative-path)
                        program-source-text)]
    (-> state
        (assoc-in [prepared-program-rows relative-path] prepared)
        (assoc-in [prepared-program-sources program-source-relative-path]
                  program-source-text))))

(defn ^{:shadow.build/stage :flush} publish-rows!
  "Publish the exact compiled boot-program rows from the client artifact."
  [state program-source-relative-path relative-path]
  (let [program-source-file (output-file state program-source-relative-path)
        target (output-file state relative-path)
        prepared-before (prepared-program state relative-path)
        _ (when-let [prepared-source
                     (:seon.dev.artifact/program-source-text prepared-before)]
            ;; Flush-stage state has already been rewritten for optimization.
            ;; Republish the exact pre-optimization source proof here so rows
            ;; and sources cannot observe different reconstructed hook states.
            (atomic-spit! program-source-file prepared-source))
        _ (when-not (.isFile program-source-file)
            (throw
             (ex-info "The program-source artifact must publish before rows."
                      {:seon.dev.artifact/path
                       program-source-relative-path})))
        program-source-text (slurp program-source-file)
        program-source-digest (digest program-source-text)
        prepared
        (if prepared-before
          prepared-before
          (when-not (= :release (:mode state))
            (let [prepared
                  (prepare-program state program-source-relative-path
                                   relative-path program-source-text)]
              (swap! !prepared-program-rows
                     assoc (prepared-key state relative-path) prepared)
              prepared)))
        rows (:seon.dev.artifact/program-rows prepared)
        compiled-row-text (:seon.dev.artifact/program-row-text prepared)]
    (when-not prepared
      (throw
       (ex-info "The pre-optimization program rows are absent."
                {:seon.dev.artifact/path relative-path
                 :seon.dev.artifact/prepared-path
                 (.getCanonicalPath
                  ^File (prepared-file state relative-path))
                 :seon.dev.artifact/prepared-file?
                 (.isFile ^File (prepared-file state relative-path))
                 :seon.dev.artifact/build-id
                 (:shadow.build/build-id state)})))
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
    (assoc-in state [prepared-program-rows relative-path] prepared)))

(defn ^{:shadow.build/stage :flush} publish-base-projection!
  "Publish the preproved EDN-only base projection for one exact row artifact."
  [state program-row-relative-path relative-path]
  (let [program-row-file (output-file state program-row-relative-path)
        _ (when-not (.isFile program-row-file)
            (throw
             (ex-info
              "The program-row artifact must publish before its base projection."
              {:seon.dev.artifact/path program-row-relative-path})))
        program-row-digest (digest (slurp program-row-file))
        prepared (prepared-program state program-row-relative-path)
        projection (:seon.dev.artifact/base-projection prepared)
        base-load-plan (:seon.dev.artifact/base-load-plan prepared)
        page-plan (:seon.dev.artifact/page-plan prepared)
        initialization-fingerprint
        (:seon.db.initialization/fingerprint
         (first (:seon.db/initialization-pages page-plan)))]
    (when-not prepared
      (throw
       (ex-info "The pre-optimization base projection is absent."
                {:seon.dev.artifact/path relative-path})))
    (when-not (= program-row-digest
                 (:seon.dev.artifact/program-row-artifact-digest prepared))
      (throw
       (ex-info
        "Program rows changed after the base projection was prepared."
        {:seon.dev.artifact/path program-row-relative-path
         :seon.dev.artifact/expected
         (:seon.dev.artifact/program-row-artifact-digest prepared)
         :seon.dev.artifact/actual program-row-digest})))
    (when-not (and (map? projection)
                   (map? base-load-plan)
                   ;; The compiled CLJS hash is an integer, but the EDN
                   ;; boundary materializes it as java.lang.Long on the JVM.
                   ;; Validate its data contract, never the reader's concrete
                   ;; numeric representation.
                   (integer?
                    (:seon.schema.projection/fingerprint projection))
                   (string? initialization-fingerprint))
      (throw
       (ex-info "The compiled boot derivation returned an invalid base projection."
                {:seon.dev.artifact/value-type (type projection)
                 :seon.dev.artifact/fingerprint
                 (:seon.schema.projection/fingerprint projection)
                 :seon.dev.artifact/fingerprint-type
                 (some-> projection
                         :seon.schema.projection/fingerprint
                         type)
                 :seon.dev.artifact/base-load-plan-type (type base-load-plan)
                 :seon.dev.artifact/initialization-fingerprint
                 initialization-fingerprint})))
    (atomic-spit!
     (output-file state relative-path)
     (str
      (pr-str
       {:seon.dev.artifact/base-projection projection
        :seon.host.context/base-load-plan base-load-plan
        :seon.db.initialization/fingerprint initialization-fingerprint})
      "\n"))
    state))

(defn ^{:shadow.build/stage :flush} publish-page-plan!
  "Publish the precomputed initialization pages for one exact row artifact."
  [state program-row-relative-path relative-path]
  (let [program-row-file (output-file state program-row-relative-path)
        _ (when-not (.isFile program-row-file)
            (throw
             (ex-info "The program-row artifact must publish before its page plan."
                      {:seon.dev.artifact/path program-row-relative-path})))
        program-row-digest (digest (slurp program-row-file))
        prepared (prepared-program state program-row-relative-path)
        page-plan (:seon.dev.artifact/page-plan prepared)
        page-plan-text (:seon.dev.artifact/page-plan-text prepared)]
    (when-not prepared
      (throw
       (ex-info "The pre-optimization page plan is absent."
                {:seon.dev.artifact/path relative-path})))
    (when-not (= program-row-digest
                 (:seon.dev.artifact/program-row-artifact-digest prepared))
      (throw
       (ex-info "Program rows changed after the page plan was prepared."
                {:seon.dev.artifact/path program-row-relative-path
                 :seon.dev.artifact/expected
                 (:seon.dev.artifact/program-row-artifact-digest prepared)
                 :seon.dev.artifact/actual program-row-digest})))
    (when-not (map? page-plan)
      (throw
       (ex-info "The compiled boot derivation returned an invalid page plan."
                {:seon.dev.artifact/value-type (type page-plan)})))
    (atomic-spit!
     (output-file state relative-path)
     (str "{:seon.dev.artifact/page-plan " page-plan-text "}\n"))
    (swap! !prepared-program-rows
           dissoc (prepared-key state program-row-relative-path))
    (swap! !prepared-program-sources
           dissoc
           (prepared-key
            state (:seon.dev.artifact/program-source-relative-path prepared)))
    (Files/deleteIfExists
     (.toPath (prepared-file state program-row-relative-path)))
    (Files/deleteIfExists
     (.toPath
      (prepared-file
       state (:seon.dev.artifact/program-source-relative-path prepared))))
    state))
