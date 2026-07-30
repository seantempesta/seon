(ns seon.fn
  "Build-time indexing of the Clojure program graph through the one reader."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader])
  (:import [java.nio.file FileVisitOption Files LinkOption Path]))

(schema.edn/load! {})

(def source-roots
  "The Clojure source roots admitted to the program graph."
  ["src" "test"])

(defn- project-root
  []
  (let [resource (io/resource "seon/fn.clj")]
    (when-not (= "file" (.getProtocol resource))
      (throw
       (ex-info
        "Program indexing requires a source checkout."
        {:seon.error/kind ::index-refused
         ::resource (str resource)})))
    (-> resource
        .toURI
        io/file
        .getParentFile
        .getParentFile
        .getParentFile
        .getCanonicalFile)))

(defn- rooted-file
  [root]
  (let [file (io/file root)]
    (.getCanonicalFile
     (if (.isAbsolute file)
       file
       (io/file (project-root) root)))))

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- source-files
  [roots]
  (let [files
        (into []
              (mapcat (fn [root]
                        (->> (file-seq (rooted-file root))
                             (filter source-file?)
                             (sort-by (fn [file]
                                        (.getCanonicalPath
                                         ^java.io.File file))))))
              roots)]
    files))

(defn- actual-reader-context
  [namespace-object]
  {:seon.sci.reader/ns (ns-name namespace-object)
   :seon.sci.reader/aliases
   (into {}
         (map (fn [[local target]] [local (ns-name target)]))
         (ns-aliases namespace-object))
   :seon.sci.reader/refers
   (into {}
         (keep (fn [[local target]]
                 (let [{target-ns :ns target-name :name} (meta target)]
                   (when (and target-ns
                              (not= 'clojure.core (ns-name target-ns)))
                     [local (symbol (str (ns-name target-ns))
                                    (str target-name))]))))
         (ns-refers namespace-object))})

(defonce ^:private inspected-rows (atom {}))
(defonce ^:private resolved-index-classpaths (atom {}))

(def ^:private default-jvm-imports
  (let [name (symbol (str "seon.fn.jvm-defaults." (random-uuid)))
        namespace-object (create-ns name)]
    (try
      (ns-imports namespace-object)
      (finally
        (remove-ns name)))))

(defn- namespace-row
  [namespace-object source]
  (let [{namespace-name :seon.sci.reader/ns
         aliases :seon.sci.reader/aliases
         refers :seon.sci.reader/refers}
        (actual-reader-context namespace-object)
        imports
        (let [current (ns-imports namespace-object)]
          (into {}
                (concat
                 (keep (fn [[local target]]
                         (when-not (= target (get default-jvm-imports local))
                           [local (symbol (.getName ^Class target))]))
                       current)
                 (keep (fn [[local _target]]
                         (when-not (contains? current local)
                           [local nil]))
                       default-jvm-imports))))
        target-namespaces (into #{} (concat (vals aliases)
                                             (keep (comp symbol namespace)
                                                   (vals refers))))]
    (cond-> {:seon.ns/name namespace-name
             :seon.ns/source source
             :seon.ns/aliases
             (into #{}
                   (map (fn [[local target]]
                          {:seon.ns.alias/local local
                           :seon.ns.alias/target-ns target}))
                   aliases)
             :seon.ns/imports
             (into #{}
                   (map (fn [[local target-class]]
                          (cond-> {:seon.ns.import/local local}
                            target-class
                            (assoc :seon.ns.import/target-class
                                   target-class))))
                   imports)
             :seon.ns/refers
             (into #{}
                   (map (fn [[local target]]
                          {:seon.ns.refer/local local
                           :seon.ns.refer/target-ns
                           (symbol (namespace target))
                           :seon.ns.refer/target-name
                           (symbol (name target))}))
                   refers)}
      (seq target-namespaces)
      (assoc :seon.ns/requires target-namespaces))))

(defn- var-state
  [namespace-objects]
  (into {}
        (mapcat
         (fn [namespace-object]
           (map
            (fn [[local var]]
              (let [metadata (meta var)
                    root (when (bound? var) @var)
                    qualified (symbol (str (ns-name namespace-object))
                                      (str local))]
                [qualified
                 {:var var
                  :metadata metadata
                  :root root}]))
            (ns-interns namespace-object))))
        (distinct namespace-objects)))

(defn- qualified-var-roots
  [states]
  (into {}
        (keep (fn [[qualified {:keys [root]}]]
                (when (ifn? root) [qualified root])))
        states))

(defn- var-row
  [qualified {:keys [var metadata]} source predicate-functions]
  (let [namespace-name (some-> metadata :ns ns-name)
        declaration-name (:name metadata)
        qualified (if (and namespace-name declaration-name)
                    (symbol (str namespace-name) (str declaration-name))
                    qualified)]
    (cond
      (:test metadata)
      {:seon.test/sym (str qualified)
       :seon.test/ns [:seon.ns/name namespace-name]
       :seon.test/source source}

      (and (:arglists metadata)
           (not (:macro metadata))
           (bound? var)
           (fn? @var))
      (cond->
       {:seon.fn/sym (str qualified)
        :seon.fn/ns [:seon.ns/name namespace-name]
        :seon.fn/source source
        :seon.fn/arglists (pr-str (:arglists metadata))
        :seon.fn/private? (boolean (:private metadata))}
        (:doc metadata) (assoc :seon.fn/doc (:doc metadata))
        (:malli/schema metadata)
        (assoc :seon.fn/spec
               (pr-str
                (schema/canonical-definition
                 (:malli/schema metadata)
                 predicate-functions)))
        (contains? #{:io :compute} (:seon.workload metadata))
        (assoc :seon.fn/workload (:seon.workload metadata)))

      :else nil)))

(defn- inspect-source-file
  [file observation]
  (let [eof (Object.)
        canonical-path (.getCanonicalPath ^java.io.File file)]
    (swap! observation update :files (fnil conj #{}) canonical-path)
    (with-open [input (clojure.lang.LineNumberingPushbackReader.
                       (io/reader file))]
      (binding [*file* canonical-path
                *ns* (the-ns 'user)
                *read-eval* false]
        (loop []
          (let [starting-ns *ns*
                context (actual-reader-context starting-ns)
                [form source]
                (read+string {:eof eof
                              :read-cond :allow
                              :features #{:clj}}
                             input)]
            (when-not (identical? eof form)
              (let [events
                    (reader/read
                     (merge context
                            {:seon.sci.reader/text source
                             :seon.sci.reader/features #{:clj}
                             :seon.sci.reader/tags {'inst identity
                                                    'uuid identity}}))]
                (when (map? events)
                  (throw
                   (ex-info (:seon.error/message events)
                            (assoc (:seon.error/data events)
                                   :seon.fn/file canonical-path))))
                (let [event (first events)
                      start-line (or (:line (meta form))
                                     (:seon.sci.reader/line event))
                      end-line (+ start-line
                                  (count (re-seq #"\n" source)))]
                  (swap! observation update-in [:spans canonical-path]
                         (fnil conj [])
                         {:start start-line :end end-line :source source})
                  (when-let [namespace-name (:seon.ns/name event)]
                    (swap! observation assoc-in
                           [:namespace-sources namespace-name] source))
                  (eval form)
                  (recur))))))))))

(defn- source-at
  [observation metadata]
  (let [file (:file metadata)
        line (:line metadata)]
    (when line
      (some (fn [{:keys [start end source]}]
              (when (<= start line end) source))
            (get-in observation [:spans file])))))

(defn- observed-rows
  [observation]
  (let [namespace-objects (all-ns)
        vars (var-state namespace-objects)
        predicate-functions (qualified-var-roots vars)
        indexed-vars
        (into {}
              (filter (fn [[_ {:keys [metadata]}]]
                        (contains? (:files observation) (:file metadata))))
              vars)
        var-rows
        (into []
              (keep (fn [[qualified state]]
                      (let [source (source-at observation (:metadata state))]
                        (when-not source
                          (throw
                           (ex-info
                            "An evaluated declaration has no source span."
                            {:seon.error/kind ::index-refused
                             :seon.fn/sym qualified
                             :seon.fn/file (:file (:metadata state))
                             :seon.fn/line (:line (:metadata state))})))
                        (var-row qualified state source
                                 predicate-functions))))
              indexed-vars)
        namespace-names
        (into (set (keys (:namespace-sources observation)))
              (keep (fn [qualified]
                      (some-> qualified namespace symbol)))
              (keys indexed-vars))
        namespace-rows
        (into []
              (keep (fn [namespace-name]
                      (when-let [namespace-object (find-ns namespace-name)]
                        (let [source
                              (or
                               (get-in observation
                                       [:namespace-sources namespace-name])
                               (some
                                (fn [row]
                                  (when (= [:seon.ns/name namespace-name]
                                           (or (:seon.fn/ns row)
                                               (:seon.test/ns row)))
                                    (or (:seon.fn/source row)
                                        (:seon.test/source row))))
                                var-rows))]
                          (when-not source
                            (throw
                             (ex-info
                              "An evaluated namespace has no source span."
                              {:seon.error/kind ::index-refused
                               :seon.ns/name namespace-name})))
                          (namespace-row namespace-object source)))))
              namespace-names)
        schema-rows
        (into []
              (map (fn [[schema-key definition]]
                     {:seon.schema/key schema-key
                      :seon.schema/form
                      (pr-str
                       (schema/canonical-definition
                        definition predicate-functions))}))
              (schema/registered-schemas))]
    (into [] cat [namespace-rows var-rows schema-rows])))

(defn- inspect-rows!
  [request output-path]
  (let [observation (atom {:files #{}
                           :spans {}
                           :namespace-sources {}})]
    (try
      (doseq [file (source-files (:seon.fn/roots request))]
        (inspect-source-file file observation))
      (spit output-path
            (pr-str {:seon.fn/rows (observed-rows @observation)}))
      (catch Throwable error
        (spit output-path
              (pr-str {:seon.error/message (or (ex-message error)
                                               (str error))
                       :seon.error/data
                       (merge {:seon.error/kind ::index-refused}
                              (ex-data error))}))
        (throw error)))))

(defn- start-process
  [arguments redirect-error-stream?]
  (.start
   (doto (ProcessBuilder. ^java.util.List arguments)
     (.redirectErrorStream redirect-error-stream?)
     (.directory (project-root)))))

(defn- resolve-index-classpath
  []
  (let [process (start-process ["clojure" "-Spath" "-M:test"] false)
        error-output (promise)
        error-reader
        (Thread/startVirtualThread
         #(deliver error-output (slurp (.getErrorStream process))))
        output (slurp (.getInputStream process))
        exit (.waitFor process)
        _ (.join error-reader)
        error-output @error-output]
    (when-not (zero? exit)
      (throw
       (ex-info
        "Could not resolve the program index classpath."
        {:seon.error/kind ::index-refused
         ::inspector-output error-output
         ::inspector-exit exit})))
    (into []
          (map (fn [path]
                 (.getCanonicalFile
                  (let [file (io/file path)]
                    (if (.isAbsolute file)
                      file
                      (io/file (project-root) path))))))
          (str/split (str/trim output)
                     (re-pattern
                      (java.util.regex.Pattern/quote
                       java.io.File/pathSeparator))))))

(defn- regular-files
  [file]
  (cond
    (.isFile ^java.io.File file) [file]
    (not (.isDirectory ^java.io.File file)) []
    :else
    (with-open [paths (Files/walk (.toPath ^java.io.File file)
                                  (make-array FileVisitOption 0))]
      (->> (iterator-seq (.iterator paths))
           (filter (fn [^Path path]
                     (Files/isRegularFile
                      path
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
           (map #(.toFile ^Path %))
           (sort-by #(.getCanonicalPath ^java.io.File %))
           (into [])))))

(defn- repo-local?
  [file]
  (.startsWith (.toPath ^java.io.File file)
               (.toPath ^java.io.File (project-root))))

(defn- dependency-manifests
  [classpath]
  (let [root (project-root)]
    (into #{}
          (mapcat
           (fn [entry]
             (when (repo-local? entry)
               (loop [directory (if (.isDirectory ^java.io.File entry)
                                  entry
                                  (.getParentFile ^java.io.File entry))
                      manifests []]
                 (if (or (nil? directory)
                         (not (.startsWith (.toPath ^java.io.File directory)
                                           (.toPath ^java.io.File root))))
                   manifests
                   (let [manifest (io/file directory "deps.edn")]
                     (recur (.getParentFile ^java.io.File directory)
                            (cond-> manifests
                              (.isFile manifest)
                              (conj (.getCanonicalFile manifest))))))))))
          classpath)))

(defn- manifest-state
  [manifests]
  (into (sorted-map)
        (map (fn [file]
               [(.getCanonicalPath ^java.io.File file) (slurp file)]))
        manifests))

(defn- unchanged-manifests?
  [expected]
  (every? (fn [[path content]]
            (let [file (io/file path)]
              (and (.isFile file)
                   (= content (slurp file)))))
          expected))

(defn- resolved-index-classpath
  []
  (let [root-manifest (io/file (project-root) "deps.edn")
        root-content (slurp root-manifest)
        cached (get @resolved-index-classpaths root-content)]
    (if (and cached (unchanged-manifests? (:manifests cached)))
      (:classpath cached)
      (let [classpath (resolve-index-classpath)
            manifests (manifest-state (dependency-manifests classpath))]
        (swap! resolved-index-classpaths
               assoc root-content
               {:classpath classpath :manifests manifests})
        classpath))))

(defn- update-digest-file!
  [digest file]
  (.update digest
           (.getBytes (.getCanonicalPath ^java.io.File file)
                      java.nio.charset.StandardCharsets/UTF_8))
  (.update digest (byte-array [(byte 0)]))
  (with-open [input (io/input-stream file)]
    (let [buffer (byte-array 8192)]
      (loop []
        (let [read (.read input buffer)]
          (when-not (= -1 read)
            (.update digest buffer 0 read)
            (recur))))))
  (.update digest (byte-array [(byte 0)])))

(defn- content-digest
  [request]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        classpath (resolved-index-classpath)
        requested-files (source-files (:seon.fn/roots request))
        local-classpath-files
        (into []
              (comp (filter repo-local?)
                    (mapcat regular-files))
              classpath)
        manifests (dependency-manifests classpath)
        files (->> (concat manifests
                            requested-files
                            local-classpath-files)
                   (distinct)
                   (sort-by #(.getCanonicalPath ^java.io.File %)))]
    (doseq [entry classpath]
      (.update digest
               (.getBytes (.getCanonicalPath ^java.io.File entry)
                          java.nio.charset.StandardCharsets/UTF_8))
      (.update digest (byte-array [(byte 0)])))
    (doseq [file files]
      (update-digest-file! digest file))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- isolated-rows
  [request]
  (let [directory (io/file (project-root) "tmp" "program-index")
        digest (content-digest request)
        id (str (random-uuid))
        request-file (io/file directory (str id ".request.edn"))
        output-file (io/file directory (str id ".result.edn"))]
    (.mkdirs directory)
    (spit request-file
          (pr-str (select-keys request [:seon.fn/roots])))
    (try
      (if-let [cached (get @inspected-rows digest)]
        cached
        (let [process
            (start-process
             ["clojure" "-M:test" "-m" "seon.fn"
              "--inspect" (.getCanonicalPath request-file)
              (.getCanonicalPath output-file)]
             true)
            output (slurp (.getInputStream process))
            exit (.waitFor process)
            result (when (.isFile output-file)
                     (edn/read-string (slurp output-file)))]
        (when (or (not (zero? exit)) (:seon.error/message result))
          (throw
           (ex-info
            (or (:seon.error/message result)
                "Isolated program inspection failed.")
            (merge {:seon.error/kind ::index-refused
                    ::inspector-output output
                    ::inspector-exit exit}
                   (:seon.error/data result)))))
          (let [rows (:seon.fn/rows result)]
            (swap! inspected-rows assoc digest rows)
            rows)))
      (finally
        (.delete request-file)
        (.delete output-file)))))

(defn rows
  "Canonical program rows produced by isolated sequential source evaluation."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :map]]}
  [request]
  (isolated-rows request))

(defn -main
  [& [operation request-path output-path]]
  (when-not (= "--inspect" operation)
    (throw (ex-info "Unknown seon.fn operation." {::operation operation})))
  (inspect-rows! (edn/read-string (slurp request-path)) output-path))

(defn- row-identity
  [row]
  (program/row-identity row))

(defn- ref-value
  [db identity-attr value]
  (when value
    (if (and (vector? value) (= identity-attr (first value)))
      value
      [identity-attr
       (or
        (when (symbol? value) value)
        (when (map? value) (get value identity-attr))
        (d/q '[:find ?identity .
               :in $ ?entity ?identity-attr
               :where [?entity ?identity-attr ?identity]]
             db
             (if (map? value) (:db/id value) value)
             identity-attr)
        (throw
         (ex-info
          "Source indexing could not resolve a program reference."
          {:seon.error/kind ::index-refused
           ::identity-attr identity-attr
           ::reference value})))])))

(defn- process-identity
  [db process]
  (when process
    (d/q '[:find ?process-id .
           :in $ ?process
           :where [?process :seon.db.process/id ?process-id]]
         db
         (if (map? process) (:db/id process) process))))

(defn- component-binding
  [binding]
  (dissoc binding :db/id))

(defn- canonical-row
  [db row]
  (let [row (program/canonical-row row)
        row
        (cond-> row
          (:seon.fn/ns row)
          (update :seon.fn/ns #(ref-value db :seon.ns/name %))

          (:seon.test/ns row)
          (update :seon.test/ns #(ref-value db :seon.ns/name %))

          (contains? row :seon.ns/aliases)
          (update :seon.ns/aliases
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/imports)
          (update :seon.ns/imports
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/refers)
          (update :seon.ns/refers
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/requires)
          (update :seon.ns/requires set))]
    (into
     {}
     (remove
      (fn [[attribute value]]
        (or (nil? value)
            (and (contains? #{:seon.ns/requires
                              :seon.ns/aliases
                              :seon.ns/imports
                              :seon.ns/refers}
                            attribute)
                 (empty? value)))))
     row)))

(defn- current-rows
  [db shape]
  (let [identity-attr (:seon.program/identity-attribute shape)
        source-attr (:seon.program/source-attribute shape)
        provenance
        (into
         {}
         (map (fn [[entity process-id]] [entity process-id]))
         (d/q '[:find ?entity ?process-id
                :in $ ?identity-attr ?source-attr
                :where
                [?entity ?identity-attr _]
                [?entity ?source-attr _ ?tx]
                [?tx :seon.db/process ?process]
                [?process :seon.db.process/id ?process-id]]
              db
              identity-attr
              source-attr))]
    (into
     {}
     (map
      (fn [[entity identity]]
        (let [row
              (d/pull
               db
               [:db/id
                identity-attr
                source-attr
                :seon.ns/doc
                :seon.ns/requires
                {:seon.ns/aliases
                 [:db/id
                  :seon.ns.alias/local
                  :seon.ns.alias/target-ns]}
                {:seon.ns/imports
                 [:db/id
                  :seon.ns.import/local
                  :seon.ns.import/target-class]}
                {:seon.ns/refers
                 [:db/id
                  :seon.ns.refer/local
                  :seon.ns.refer/target-ns
                  :seon.ns.refer/target-name]}
                {:seon.fn/ns [:db/id :seon.ns/name]}
                :seon.fn/arglists
                :seon.fn/doc
                :seon.fn/private?
                :seon.fn/spec
                :seon.fn/workload
                {:seon.test/ns [:db/id :seon.ns/name]}]
               entity)]
          [[identity-attr identity]
           {::entity entity
            ::process-id (get provenance entity)
            ::row (canonical-row db row)
            ::alias-eids (into [] (keep :db/id) (:seon.ns/aliases row))
            ::import-eids (into [] (keep :db/id) (:seon.ns/imports row))
            ::refer-eids (into [] (keep :db/id) (:seon.ns/refers row))}])))
     (d/q '[:find ?entity ?identity
            :in $ ?identity-attr
            :where [?entity ?identity-attr ?identity]]
          db
          identity-attr))))

(defn- assert-one-row-per-identity!
  [desired]
  (when-let [duplicate
             (some (fn [[identity n]] (when (> n 1) identity))
                   (frequencies (map row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {:seon.error/kind ::index-refused
       ::identity duplicate}))))

(defn- assert-populated!
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " rows; refusing a partial program graph.")
        {:seon.error/kind ::index-refused
         ::missing-population identity-attr})))))

(defn- changed-row-tx
  [shape identity desired current]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current-row (::row current)
        changed-attrs (program/changed-attributes current-row desired)
        binding-attrs #{:seon.ns/aliases :seon.ns/imports :seon.ns/refers}]
    (when (seq changed-attrs)
      (let [edge-retracts
            (into []
                  (map (fn [eid] [:db.fn/retractEntity eid]))
                  (concat
                   (when (some #{:seon.ns/aliases} changed-attrs)
                     (::alias-eids current))
                   (when (some #{:seon.ns/imports} changed-attrs)
                     (::import-eids current))
                   (when (some #{:seon.ns/refers} changed-attrs)
                     (::refer-eids current))))
            retracts
            (into
             (vec edge-retracts)
             (keep
              (fn [attribute]
                (when (and (not (contains? binding-attrs attribute))
                           (contains? current-row attribute)
                           (not= (get current-row attribute)
                                 (get desired attribute)))
                  [:db.fn/retractAttribute identity attribute])))
             changed-attrs)
            additions
            (select-keys desired (conj changed-attrs identity-attr))]
        (cond-> retracts
          (> (count additions) 1) (conj additions))))))

(defn- shape-plan
  [db process-id shape desired]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current (current-rows db shape)
        desired
        (into {}
              (map
               (fn [row]
                 (let [identity (row-identity row)]
                   [identity (canonical-row db row)])))
              (filter identity-attr desired))
        changes
        (into
         []
         (mapcat
          (fn [[identity desired-row]]
            (if-let [current-row (get current identity)]
              (when (or (= process-id (::process-id current-row))
                        (not (contains? (::row current-row)
                                        (:seon.program/source-attribute shape))))
                (changed-row-tx shape identity desired-row current-row))
              [desired-row])))
         desired)
        stale
        (into
         []
         (keep
          (fn [[identity current-row]]
            (when (and (= process-id (::process-id current-row))
                       (not (contains? desired identity)))
              [:db.fn/retractEntity (::entity current-row)])))
         current)]
    (into changes stale)))

(defn- desired-program-rows
  [request]
  (let [source-rows (rows request)
        canonical-schemas (schema/canonical-schema-rows (java.util.Date. 0))
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-only
        (remove (fn [row]
                  (contains? canonical-keys (:seon.schema/key row)))
                source-rows)]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.error/kind ::index-refused
                   :seon.schema/key schema-key}))))
    (into (vec source-only) canonical-schemas)))

(defn- digest-plan
  [db desired-digest]
  (if-not desired-digest
    []
    (let [current
          (d/q '[:find ?ancestor ?digest
                 :where [?ancestor :seon.ancestor/digest ?digest]]
               db)]
      (case (count current)
        0 [{:seon.ancestor/digest desired-digest
            :seon.ancestor/built-at (java.util.Date.)}]
        1 (let [[ancestor current-digest] (first current)]
            (if (= current-digest desired-digest)
              []
              [[:db.fn/retractAttribute ancestor :seon.ancestor/digest]
               {:db/id ancestor :seon.ancestor/digest desired-digest}]))
        (throw
         (ex-info
          "Source indexing requires at most one recorded ancestor digest."
          {:seon.error/kind ::index-refused
           ::recorded-digests (into #{} (map second) current)}))))))

(defn index!
  "Exact-reconcile source-owned program rows and preserve authored facts.

  Rows whose current defining datom carries `:seon.db/process` are owned
  only when that process matches this request. Agent-authored rows and all
  non-program facts are therefore outside the reconciled slice. Namespace,
  function, schema, and test rows absent from the desired population are
  removed. The desired schema population is the canonical evaluated registry
  plus source-only declarations, so canonical rows do not need a family-wide
  stale-removal exemption.

  When `:seon.ancestor/digest` is supplied, its one current value advances
  only after the program rows commit. After priming, that value means “this
  cluster was explicitly synchronized from this source digest, preserving
  agent-authored overrides,” not “this branch was originally forked from
  the ancestor branch named by this digest.” A converged call performs no
  transaction."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.reconcile/result]}
  [{connection :seon.store/branch-connection
    process :seon.db/process
    :as request}]
  (let [program-rows (desired-program-rows request)
        _ (assert-one-row-per-identity! program-rows)
        _ (assert-populated! program-rows)
        process-id (process-identity @connection process)
        _ (when (nil? process-id)
            (throw
             (ex-info
              "Source indexing requires a resolvable process identity."
              {:seon.error/kind ::index-refused
               ::process process})))
        transaction
        (fn [operations]
          (when (seq operations)
            (d/transact
             connection
             (cond-> {:tx-data operations}
               process (assoc :tx-meta {:seon.db/process process}))))
          (count operations))
        namespace-plan
        (shape-plan @connection process-id
                    (program/shape :seon.ns/name)
                    program-rows)
        namespace-operations (transaction namespace-plan)
        declaration-plan
        (into
         []
         (mapcat
          #(shape-plan @connection process-id % program-rows))
         (keep (fn [identity-attribute]
                 (when-not (= :seon.ns/name identity-attribute)
                   (program/shape identity-attribute)))
               program/identity-attributes))
        final-plan
        (into declaration-plan
              (digest-plan @connection (:seon.ancestor/digest request)))
        declaration-operations (transaction final-plan)
        operations (+ namespace-operations declaration-operations)]
    {:seon.reconcile/converged? (zero? operations)
     :seon.reconcile/operations operations}))
