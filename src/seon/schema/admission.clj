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
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.internal :as internal]))

(defn tokens
  "Split a keyword, symbol, or string on its natural non-alphanumeric
  separators."
  {:malli/schema [:=> [:cat [:or :keyword :symbol :string]] [:vector :string]]}
  [value]
  (let [flush-token
        (fn [{:keys [parts token]}]
          {:parts (cond-> parts (seq token) (conj token))
           :token ""})]
    (:parts
     (flush-token
      (reduce
       (fn [{:keys [token] :as state} character]
         (if (Character/isLetterOrDigit ^char character)
           (assoc state :token
                  (str token (Character/toLowerCase ^char character)))
           (flush-token state)))
       {:parts [] :token ""}
       (str value))))))

(defn similar-identities
  "Rank qualified schema keys by shared natural name tokens. At least one
  local-name token must overlap, so a common namespace alone stays silent."
  {:malli/schema
   [:=> [:cat :qualified-keyword [:sequential :qualified-keyword]
         [:int {:min 1}]]
    [:vector [:map [:seon.schema.admission/similar-key :qualified-keyword]
              [:seon.schema.admission/shared-tokens [:int {:min 1}]]]]]}
  [candidate existing limit]
  (let [candidate-all (set (tokens candidate))
        candidate-local (set (tokens (name candidate)))]
    (->> existing
         (keep
          (fn [existing-key]
            (let [local-shared
                  (count (filter candidate-local (tokens (name existing-key))))
                  shared (count (filter candidate-all (set (tokens existing-key))))]
              (when (pos? local-shared)
                {:seon.schema.admission/similar-key existing-key
                 :seon.schema.admission/shared-tokens shared}))))
         (sort-by (juxt (comp - :seon.schema.admission/shared-tokens)
                        (comp str :seon.schema.admission/similar-key)))
         (take limit)
         vec)))

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
                     " has the same composite shape as the schemas in :seon.schema.admission/similar-keys."
                     " If this duplicates one of those schemas, delete "
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
           ;; An unqualified candidate already has its blocking finding.
           (->> (when (qualified-keyword? declaration-key)
                  (similar-identities
                 declaration-key
                 ;; Malli's own unqualified defaults (e.g. :inst) are not
                 ;; declarations an author could reuse.
                 (filter qualified-keyword?
                         (remove #{declaration-key} (keys registry)))
                 3))
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

(defn- registration-finding
  "Nil, or the finding for the refusal `schema/register!` gives
  `declaration-key`: its nilable-value check, then `schema.edn/admit` with the
  declaration's admission source."
  {:malli/schema [:=> [:cat [:map-of :keyword [:maybe :string]] :map :keyword :keyword] [:maybe :map]]}
  [files-by-key forms declaration-key source]
  (try
    (internal/assert-non-nilable-value-schema!
     forms declaration-key
     (schema/structural-schema (get forms declaration-key)))
    (let [admitted (schema.edn/admit {:seon.schema/forms forms
                                      :seon.schema/identity declaration-key
                                      :seon.schema/admission
                                      {:seon.schema.admission/source source}})]
      ;; admit answers its refusal as a value; it becomes this finding below
      (when (:seon.error/at admitted)
        (throw (ex-info (:seon.error/message admitted) admitted))))
    nil
    (catch Throwable error
      (let [data (ex-data error)
            declaration (or (::schema.edn/attribute data)
                            (:seon.schema/key data)
                            (:seon.schema/identity data))
            refusal (or (:seon.schema/error data) (::schema.edn/error data))
            unresolved? (= ::schema.edn/unresolved-reference
                           (::schema.edn/error data))]
        (finding
         (get files-by-key declaration)
         :error
         (cond unresolved? :schema-unresolved-reference
               (or (nil? refusal) (= :seon.schema/invalid-schema refusal))
               :schema-malli-compilation
               :else :schema-incomplete-contract)
         (str (if refusal
                "Schema admission refused the declaration: "
                "Schema population did not compile through Malli: ")
              (.getMessage error))
         (cond-> {}
           refusal (assoc :seon.schema/error refusal)
           declaration
           (assoc :seon.schema.admission/declaration declaration)))))))

(defn- undefined-slot-finding
  "Nil, or one advisory warning for the first `:any`/`:some` slot of a
  declaration that does not record why it accepts anything. Undefined slots
  are never refused (owner ruling 2026-09-24), whatever the source;
  the recorded polymorphic-boundary exemption, reason and generator, judged by
  `internal/permissive-positions`, silence it. A stored attribute still needs a
  Datahike value type: `seon.schema.datahike/malli->datahike-attr-in` refuses
  `:any` there. Runs only after the declaration was admitted."
  {:malli/schema [:=> [:cat [:or :nil :string] :keyword :seon.schema/value]
                  [:or :nil :map]]}
  [file declaration-key form]
  (when-let [slot (first
                   (filter #(and (= :undefined (:seon.schema.advisory/kind %))
                                 (not (:seon.schema/justified? %)))
                           (internal/permissive-positions
                            {:seon.schema/compiled (schema/structural-schema form)})))]
    (finding
     file :warning :schema-polymorphism
     (str "Schema declaration " declaration-key " accepts anything at :seon.schema/path"
          " (its definition is :seon.schema/definition). If that is deliberate,"
          " record why: {:seon.schema.admission/exemption"
          " :seon.schema.admission/polymorphic-boundary"
          " :seon.schema.admission/reason \"...\" :gen/schema ...}; otherwise"
          " name the value with a predicate schema.")
     {:seon.schema.admission/declaration declaration-key
      :seon.schema/path (:seon.schema/path slot)
      :seon.schema/definition (:seon.schema/definition slot)})))

(defn admit
  "Return editor-shaped findings for one schema admission request.

  The open request accepts `::paths`/`::sources` for file admission or
  `::declarations` plus the complete `::registry` for declaration admission.
  Contract completeness has one owner, `seon.schema.internal`, reached
  through `seon.schema.edn/admit` exactly as `seon.schema/register!` reaches
  it. `::source` is the `:seon.schema.admission/source` the declarations
  will be recorded with: `:core` for packaged resource files, `:agent` for
  `::declarations`, unless the request names one. Error findings block;
  exact-reuse, name-overlap and
  undefined-slot findings are advisory warnings."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{::keys [registry declarations source] :as request}]
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
          ;; A file request re-reads every declaration of the file; only the
          ;; ones whose form differs from the registered population are new
          ;; registrations, so cost and advice follow the changed inputs.
          published (if declarations registry (schema/registered-schemas))
          changed? #(not= (get published (key %) ::absent) (val %))
          similarity
          (into []
                (mapcat
                 (fn [{::keys [file declarations]}]
                   (let [changed (into {} (filter changed?) declarations)]
                     (concat
                      (exact-reuse-findings file changed registry)
                      (name-overlap-findings file changed registry)))))
                valid-units)
          source (or source (if declarations :agent :core))
          registration
          (into []
                (comp (filter changed?)
                      (keep (fn [[declaration-key form]]
                              (or (registration-finding files-by-key complete-forms
                                                        declaration-key source)
                                  (undefined-slot-finding
                                   (get files-by-key declaration-key)
                                   declaration-key form)))))
                (sort-by key candidate-declarations))]
      (vec (concat read-findings house collisions registration
                   similarity)))
    (catch Throwable error
      (if-let [findings (:seon.schema.admission/findings (ex-data error))]
        (vec findings)
        [(finding nil :error :schema-admission-failure
                  (str "Schema admission could not run: " (.getMessage error))
                  {})]))))

(defn -main
  "Read one admission request as EDN from stdin and print its findings."
  {:malli/schema [:=> [:cat [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics.", :gen/elements [[]]} :string]] :nil]}
  [& _]
  (prn (admit (edn/read-string (slurp *in*))))
  nil)
