(ns seon.schema.admission
  "Editor and accretion admission findings for schema declarations.

  `admit` is the one data-returning boundary. The edit hook supplies paths and
  prospective source now; the later agent declaration seam supplies parsed
  declarations and the cluster's complete registry through the same request.
  Publication remains owned by `seon.schema.edn`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader.edn :as reader.edn]
            [clojure.tools.reader.reader-types :as reader-types]
            [seon.search :as search]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(def ^:private polymorphism-exemption
  :seon.schema.admission/polymorphic-boundary)

(def ^:private generator-properties
  #{:gen/schema :gen/elements :gen/gen :gen/return})

(defn- finding
  [file level finding-type message extra]
  (merge {:filename (or file "<schema-declaration>")
          :row 1
          :col 1
          :level level
          :type finding-type
          :message message}
         extra))

(defn- parse-source
  [file source]
  (try
    (let [input (reader-types/indexing-push-back-reader source)
          eof (Object.)
          declarations (reader.edn/read {:eof eof} input)
          trailing (reader.edn/read {:eof eof} input)]
      (cond
        (identical? eof declarations)
        {:findings
         [(finding file :error :schema-edn-read
                   "Schema source must contain one declaration map."
                   {})]}

        (not (identical? eof trailing))
        {:findings
         [(finding file :error :schema-edn-read
                   "Schema source contains more than one EDN form."
                   {})]}

        (not (map? declarations))
        {:findings
         [(finding file :error :schema-edn-read
                   "Schema source must be an EDN map of declaration key to Malli form."
                   {})]}

        :else
        {:declarations declarations}))
    (catch Throwable error
      (let [{:keys [line col]} (ex-data error)]
        {:findings
         [(merge
           (finding file :error :schema-edn-read
                    (str "Schema EDN is unreadable: " (.getMessage error))
                    {})
           (when line {:row line})
           (when col {:col col}))]}))))

(defn- schema-file?
  [file]
  (and (.isFile ^java.io.File file)
       (str/ends-with? (str/lower-case (.getName ^java.io.File file)) ".edn")))

(defn- schema-files
  [path]
  (let [file (.getCanonicalFile (io/file path))]
    (if (.isDirectory file)
      (->> (.listFiles file)
           (filter schema-file?)
           (sort-by #(.getName ^java.io.File %))
           vec)
      [file])))

(defn- request-units
  [{::keys [declarations file path paths source sources]}]
  (cond
    declarations
    [{::declarations declarations ::file file}]

    source
    [{::file (or file path) ::source source}]

    :else
    (mapv
     (fn [^java.io.File candidate]
       (let [candidate-path (.getPath candidate)]
         {::file candidate-path
          ::source (or (get sources candidate-path)
                       (get sources (.getCanonicalPath candidate))
                       (slurp candidate))}))
     (mapcat schema-files (or paths (when path [path]) [])))))

(defn- parse-units
  [units]
  (mapv
   (fn [{::keys [declarations file source]}]
     (if declarations
       {::file file ::declarations declarations}
       (let [{:keys [declarations findings]} (parse-source file source)]
         (cond-> {::file file}
           declarations (assoc ::declarations declarations)
           findings (assoc :findings findings)))))
   units))

(defn- default-schema-directory
  []
  (some-> schema.edn/default-resource io/resource io/file .getCanonicalFile))

(defn- canonical-path
  [path]
  (some-> path io/file .getCanonicalPath))

(defn- read-declarations
  [^java.io.File file]
  (let [{:keys [declarations findings]}
        (parse-source (.getPath file) (slurp file))]
    (if (seq findings)
      (throw
       (ex-info "An existing schema resource is unreadable."
                {:seon.schema.admission/findings findings}))
      declarations)))

(defn- default-registry-excluding
  [candidate-files]
  (let [excluded (into #{} (keep canonical-path) candidate-files)
        resource-directory (default-schema-directory)
        resource-files (schema-files resource-directory)
        {excluded-files true other-files false}
        (group-by (fn [^java.io.File file]
                    (contains? excluded (.getCanonicalPath file)))
                  resource-files)
        ;; The live registry ALREADY holds every published declaration,
        ;; including the candidate file's own. Skipping that file in the disk
        ;; walk therefore cannot remove its keys, and without this subtraction
        ;; every edit to an existing schema resource — even adding one new key
        ;; — collides with its own published self and is refused.
        registered
        (apply dissoc
               (schema/registered-schemas)
               (mapcat (comp keys read-declarations) excluded-files))
        existing
        (reduce (fn [forms file] (merge forms (read-declarations file)))
                registered
                other-files)]
    (schema.edn/derive-config-forms existing)))

(defn- form-properties
  [form]
  (when (and (vector? form) (map? (second form)))
    (second form)))

(defn- form-body
  [form]
  (let [body (rest form)]
    (if (map? (first body)) (rest body) body)))

(defn- schema-children
  [form]
  (when (vector? form)
    (let [tag (first form)
          body (form-body form)]
      (cond
        (contains? #{:enum := :fn :ref :re} tag) []
        (= :map tag) (mapv last body)
        (= :multi tag) (mapv last (rest body))
        :else (vec body)))))

(defn- schema-nodes
  [definition]
  (tree-seq #(seq (schema-children %)) schema-children definition))

(defn- recorded-polymorphism-exemption?
  [form]
  (let [properties (form-properties form)]
    (and (= polymorphism-exemption
            (:seon.schema.admission/exemption properties))
         (not (str/blank?
               (:seon.schema.admission/reason properties)))
         (some #(contains? properties %) generator-properties))))

(defn- polymorphic-form?
  [form]
  (or (= :any form)
      (= :some form)
      (and (vector? form)
           (contains? #{:any :some :maybe} (first form)))))

(defn- direct-database-ref?
  [form]
  (some #(= :db.type/ref %)
        (vals (or (form-properties form) {}))))

(defn- file-namespace
  [file]
  (when file
    (let [filename (.getName (io/file file))]
      (when (str/ends-with? (str/lower-case filename) ".edn")
        (subs filename 0 (- (count filename) 4))))))

(defn- house-findings
  [file declarations]
  (let [expected-namespace (file-namespace file)]
    (into []
          (mapcat
           (fn [[declaration-key definition]]
             (let [base-extra
                   {:seon.schema.admission/declaration declaration-key}
                   nodes (schema-nodes definition)]
               (concat
                (when-not (qualified-keyword? declaration-key)
                  [(finding file :error :schema-unqualified-key
                            (str "Schema declaration key " (pr-str declaration-key)
                                 " must be a fully namespaced keyword.")
                            base-extra)])
                (when (and expected-namespace
                           (qualified-keyword? declaration-key)
                           (not= expected-namespace
                                 (namespace declaration-key)))
                  [(finding file :error :schema-misplaced-key
                            (str "Schema declaration " declaration-key
                                 " belongs in " (namespace declaration-key)
                                 ".edn, not " (.getName (io/file file)) ".")
                            base-extra)])
                (keep
                 (fn [node]
                   (when (and (polymorphic-form? node)
                              (not (recorded-polymorphism-exemption? node)))
                     (finding
                      file :error :schema-polymorphism
                      (str "Schema declaration " declaration-key
                           " uses " (pr-str node)
                           " without the recorded polymorphic-boundary exemption, reason, and bounded generator.")
                      base-extra)))
                 nodes)
                (keep
                 (fn [node]
                   (when (and (vector? node)
                              (= :fn (first node))
                              (str/blank?
                               (:error/message (form-properties node))))
                     (finding
                      file :warning
                      :schema-predicate-missing-error-message
                      (str "Predicate schema " declaration-key
                           " must declare a nonblank `:error/message` "
                           "saying what value "
                           (pr-str (first (form-body node)))
                           " accepts.")
                      base-extra)))
                 nodes)
                (keep
                 (fn [node]
                   (when (and (vector? node)
                              (= :map (first node))
                              (true? (:closed (form-properties node))))
                     (finding file :error :schema-closed-map
                              (str "Schema declaration " declaration-key
                                   " uses {:closed true}; Malli maps remain open for accretion.")
                              base-extra)))
                 nodes)
                (keep
                 (fn [node]
                   (when (direct-database-ref? node)
                     (finding file :error :schema-direct-database-ref
                              (str "Schema declaration " declaration-key
                                   " encodes :db.type/ref directly; declare the reference through :seon.db/ref.")
                              base-extra)))
                 nodes))))
           (sort-by key declarations)))))

(defn- collision-findings
  [file declarations registry]
  (into []
        (keep
         (fn [declaration-key]
           (when (contains? registry declaration-key)
             (finding
              file :error :schema-key-collision
              (str "Schema key " declaration-key
                   " is already registered; exact-key redefinition is refused.")
              {:seon.schema.admission/declaration declaration-key
               :seon.schema.admission/similar-key declaration-key}))))
        (sort (keys declarations))))

(defn- exact-reuse-findings
  [file declarations registry]
  (into []
        (keep
         (fn [[declaration-key definition]]
           (let [structureless?
                 (or (keyword? definition)
                     (and (vector? definition)
                          (= := (first definition))))
                 matches
                 (when-not structureless?
                   (into []
                         (keep (fn [[existing-key existing-definition]]
                                 (when (and (not= declaration-key existing-key)
                                            (= definition existing-definition))
                                   existing-key)))
                         (sort-by key registry)))]
             (when (seq matches)
               (finding
                file :warning :schema-exact-reuse
                (str "Schema " declaration-key
                     " has the same composite shape as " (pr-str matches)
                     ". If this duplicates one of those schemas, delete "
                     declaration-key " and reuse that existing key. Create a"
                     " parallel schema only when the user explicitly chooses"
                     " a separate system.")
                {:seon.schema.admission/declaration declaration-key
                 :seon.schema.admission/similar-keys matches}))))
         (sort-by key declarations))))

(defn- name-overlap-findings
  [file declarations registry]
  (into []
        (mapcat
         (fn [[declaration-key _]]
           (->> (search/similar-identities
                 declaration-key
                 (remove #{declaration-key} (keys registry))
                 3)
                (mapv
                   (fn [{similar-key :seon.schema.admission/similar-key
                         shared :seon.schema.admission/shared-tokens}]
                     (finding
                      file :warning :schema-name-overlap
                      (str "Schema " declaration-key " shares " shared
                           " name token" (when (not= 1 shared) "s")
                           " with existing " similar-key ". If this duplicates "
                           similar-key ", delete " declaration-key " and reuse "
                           similar-key ". Create a parallel schema only when the user explicitly chooses a separate system.")
                      {:seon.schema.admission/declaration declaration-key
                       :seon.schema.admission/similar-key similar-key
                       :seon.schema.admission/shared-tokens shared})))))
         (sort-by key declarations))))

(defn- compilation-finding
  [files-by-key forms]
  (try
    (schema.edn/admit {:seon.schema/forms forms})
    nil
    (catch Throwable error
      (let [data (ex-data error)
            declaration (or (::schema.edn/attribute data)
                            (:seon.schema/key data)
                            (:seon.schema/identity data))
            unresolved? (= ::schema.edn/unresolved-reference
                           (::schema.edn/error data))]
        (finding
         (get files-by-key declaration)
         :error
         (if unresolved?
           :schema-unresolved-reference
           :schema-malli-compilation)
         (str "Schema population did not compile through Malli: "
              (.getMessage error))
         (cond-> {}
           declaration
           (assoc :seon.schema.admission/declaration declaration)))))))

(defn admit
  "Return editor-shaped findings for one schema admission request.

  The open request accepts `::paths`/`::sources` for file admission or
  `::declarations` plus the complete `::registry` for declaration admission.
  Error findings block; predicate-message, exact-reuse, and name-overlap
  findings are advisory warnings."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{::keys [registry] :as request}]
  (try
    (let [parsed-units (parse-units (request-units request))
          read-findings (into [] (mapcat :findings) parsed-units)
          valid-units (remove :findings parsed-units)
          candidate-declarations
          (reduce merge {} (map ::declarations valid-units))
          candidate-files (mapv ::file valid-units)
          registry
          (or registry
              (if (seq candidate-files)
                (default-registry-excluding candidate-files)
                (schema.edn/packaged-forms)))
          complete-forms
          (schema.edn/derive-config-forms
           (merge registry candidate-declarations))
          files-by-key
          (into {}
                (mapcat
                 (fn [{::keys [file declarations]}]
                   (map (fn [declaration-key] [declaration-key file])
                        (keys declarations)))
                 valid-units))
          house
          (into []
                (mapcat
                 (fn [{::keys [file declarations]}]
                   (house-findings file declarations)))
                valid-units)
          collisions
          (into []
                (mapcat
                 (fn [{::keys [file declarations]}]
                   (collision-findings file declarations registry)))
                valid-units)
          similarity
          (into []
                (mapcat
                 (fn [{::keys [file declarations]}]
                   (concat
                    (exact-reuse-findings file declarations registry)
                    (name-overlap-findings file declarations registry))))
                valid-units)
          compilation
          (when (seq candidate-declarations)
            (compilation-finding files-by-key complete-forms))]
      (vec (concat read-findings house collisions
                   (when compilation [compilation]) similarity)))
    (catch Throwable error
      (if-let [findings (:seon.schema.admission/findings (ex-data error))]
        (vec findings)
        [(finding nil :error :schema-admission-failure
                  (str "Schema admission could not run: " (.getMessage error))
                  {})]))))

(defn -main
  "Read one admission request as EDN from stdin and print its findings."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& _]
  (prn (admit (edn/read-string (slurp *in*))))
  nil)
