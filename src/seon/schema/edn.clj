(ns seon.schema.edn
  "Loads and admits the classpath's EDN schema population.

  `load!` reads the domain EDN resources from one classpath directory, refuses
  unreadable resources and duplicate keys, and contributes their merged forms to
  `seon.schema` without activating them. It also derives the manifest,
  effective-config, and config-entity composites from registered config
  attributes.

  `admit` checks a candidate population through the shared schema
  contracts. Every `[:fn]` predicate must resolve to a registered core
  predicate and declare a generator. Refusals identify the offending
  schema key and, for loaded forms, its source resource. This namespace
  reads classpath and in-memory data only; database installation belongs
  to cluster population."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form])
  (:import [java.net JarURLConnection]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

; the one classpath resource; overridable so suites use fixture resources
(schema/register! ::resource [:string {:min 1}])
(schema/register! ::file [:string {:min 1}])
(schema/register! ::keys [:int {:min 0}])

(schema/register!
 ::load-request
 [:map
  [::resource {:optional true} ::resource]])

(schema/register!
 ::loaded
 [:map
  [::file ::file]
  [::keys ::keys]])

(defonce ^:private packaged-base-forms
  ;; Captured before any consumer namespace can register process-local test or
  ;; REPL schemas. Packaged publication later merges only schema EDN into this
  ;; bootstrap population; the ambient registry is never a build input.
  (schema/registered-schemas))

(def default-resource
  "Classpath directory backed by `resources/seon/schemas/` in a source checkout."
  "seon/schemas")

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defonce ^:private !source-files (atom {}))

(defn- config-dial?
  [identity definition]
  (and
   (qualified-keyword? identity)
   (or (str/starts-with? (namespace identity) "seon.config.")
       (true? (:seon.config/dial
               (schema.form/attr-form-properties definition))))))

(defn- config-dial-entries
  [forms]
  (->> forms
       (keep
        (fn [[identity definition]]
          (when (config-dial? identity definition)
            (let [optional?
                  (true? (:seon.config/optional
                          (schema.form/attr-form-properties definition)))
                  per-agent?
                  (true? (:seon.config/per-agent
                          (schema.form/attr-form-properties definition)))]
              {:seon.schema.edn/identity identity
               :seon.schema.edn/optional? optional?
               :seon.schema.edn/per-agent? per-agent?}))))
       (sort-by (comp str :seon.schema.edn/identity))
       vec))

(defn- config-map-entry
  [{::keys [identity optional?]} manifest?]
  (let [schema-spec
        (if manifest?
          [:or identity [:= :seon.config/absent]]
          identity)]
    (if (or manifest? optional?)
      [identity {:optional true} schema-spec]
      [identity schema-spec])))

(defn ^:no-doc derive-config-forms
  "Derive every config composite from registered config attributes."
  {:malli/schema [:=> [:cat :map] :map]}
  [forms]
  (let [dials (config-dial-entries forms)
        manifest-entries (mapv #(config-map-entry % true) dials)
        effective-entries (mapv #(config-map-entry % false) dials)
        agent-overlay-entries
        (into []
              (comp
               (filter :seon.schema.edn/per-agent?)
               (map (fn [{::keys [identity]}]
                      [identity {:optional true} identity])))
              dials)]
    (if (or (seq dials)
            (some #(contains? forms %)
                  [:seon.config/manifest
                   :seon.config/effective
                   :seon.config/agent-overlay
                   :seon.config/entity]))
      (assoc forms
             :seon.config/manifest
             (into [:map] manifest-entries)
             :seon.config/effective
             (into [:map] effective-entries)
             :seon.config/agent-overlay
             (into [:map] agent-overlay-entries)
             :seon.config/entity
             (into
              [:map {:seon.db/entity true}
               [:seon.config/cluster :seon.config/cluster]
               [:seon.config/applied-manifest-digest
                :seon.config/applied-manifest-digest]]
              effective-entries))
      forms)))

(defn ^:no-doc config-registration-defaults
  "Defaults declared directly by config attribute registrations."
  {:malli/schema [:=> [:cat :map] :map]}
  [forms]
  (into {}
        (keep
         (fn [[identity definition]]
           (let [properties (schema.form/attr-form-properties definition)]
             (when (and (config-dial? identity definition)
                        (contains? properties :seon.config/default))
               [identity (:seon.config/default properties)]))))
        forms))

(defn- duplicate-attribute
  [error]
  (when-let [[_ printed]
             (re-matches #"Duplicate key: (.+)" (ex-message error))]
    (let [value (edn/read-string printed)]
      (when (keyword? value) value))))

(defn- unreadable-file!
  [resource]
  (throw
   (ex-info
    (str "Schema EDN resource is absent: " resource)
    {::error ::unreadable-file
     ::file resource
     :seon.error/kind :user-input})))

(defn- filesystem-safe-namespace?
  [schema-namespace]
  (and (seq schema-namespace)
       (not (#{"." ".."} schema-namespace))
       (every? #(or (Character/isLetterOrDigit ^char %)
                    (#{\. \- \_} %))
               schema-namespace)))

(defn- directory-resource?
  [resource]
  (let [url (or (io/resource resource) (unreadable-file! resource))]
    (or (and (= "file" (.getProtocol url))
             (.isDirectory (io/file url)))
        (and (= "jar" (.getProtocol url))
             (not (str/ends-with? resource ".edn"))))))

(defn- directory-resource-paths
  [resource directory-url]
  (let [paths
        (case (.getProtocol directory-url)
    "file"
    (let [directory (io/file directory-url)]
      (->> (.listFiles directory)
           (filter #(.isFile ^java.io.File %))
           (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
           (map #(str resource "/" (.getName ^java.io.File %)))
           sort
           vec))

    "jar"
    (let [connection ^JarURLConnection (.openConnection directory-url)
          directory-name (.getName (.getJarEntry connection))
          prefix (if (str/ends-with? directory-name "/")
                   directory-name
                   (str directory-name "/"))]
      (->> (enumeration-seq (.entries (.getJarFile connection)))
           (map #(.getName ^java.util.jar.JarEntry %))
           (filter #(str/starts-with? % prefix))
           (filter #(str/ends-with? % ".edn"))
           (filter #(not (str/includes? (subs % (count prefix)) "/")))
           sort
           vec))

    (throw
     (ex-info
      (str "Schema resource directory uses an unsupported protocol: "
           (.getProtocol directory-url))
      {::error ::unreadable-file
       ::file (.toExternalForm directory-url)
       :seon.error/kind :user-input})))]
    (when (empty? paths)
      (throw
       (ex-info
        (str "Schema resource directory contains no EDN files: " resource)
        {::error ::unreadable-file
         ::file (.toExternalForm directory-url)
         :seon.error/kind :user-input})))
    paths))

(defn- schema-resource-paths
  [resource]
  (let [url (or (io/resource resource) (unreadable-file! resource))]
    (if (or (= "jar" (.getProtocol url))
            (and (= "file" (.getProtocol url))
                 (.isDirectory (io/file url))))
      (directory-resource-paths resource url)
      [resource])))

(defn- read-schema-resource
  [resource]
  (let [url (or (io/resource resource) (unreadable-file! resource))
        file (.toExternalForm url)
        value
        (try
            (with-open [reader (java.io.PushbackReader. (io/reader url))]
              (edn/read {:eof ::eof} reader))
            (catch IllegalArgumentException error
              (if-let [attribute (duplicate-attribute error)]
                (throw
                 (ex-info
                  (str "Schema attribute " attribute
                       " is declared twice in the schema resource.")
                  {::error ::duplicate-attribute
                   ::attribute attribute
                   ::file file
                   :seon.error/kind :user-input}
                  error))
                (throw
                 (ex-info
                  (str "Schema EDN resource is unreadable: " file)
                  {::error ::unreadable-file
                   ::file file
                   :seon.error/kind :user-input}
                  error))))
            (catch Exception error
              (throw
               (ex-info
                (str "Schema EDN resource is unreadable: " file)
                {::error ::unreadable-file
                 ::file file
                 :seon.error/kind :user-input}
                error))))]
    (when-not (map? value)
      (throw
       (ex-info
        (str "Schema EDN resource must contain one map: " file)
        {::error ::not-a-map
         ::file file
         :seon.error/kind :user-input})))
    {::file file ::resource resource ::forms value}))

(defn- resource-filename
  [resource]
  (let [separator (.lastIndexOf ^String resource "/")]
    (subs resource (inc separator))))

(defn- validate-resource-placement!
  [loaded]
  (doseq [{file ::file resource ::resource forms ::forms} loaded
          :let [filename (resource-filename resource)
                file-namespace (subs filename 0 (- (count filename) 4))]]
    (when-not (filesystem-safe-namespace? file-namespace)
      (throw
       (ex-info
        (str "Schema resource filename is not a safe verbatim namespace: "
             filename)
        {::error ::unsafe-namespace
         ::file file
         ::namespace file-namespace
         :seon.error/kind :user-input})))
    (doseq [registry-key (keys forms)
            :let [schema-namespace (when (qualified-keyword? registry-key)
                                     (namespace registry-key))]]
      (when-not (filesystem-safe-namespace? schema-namespace)
        (throw
         (ex-info
          (str "Schema key namespace is not safe as a verbatim filename: "
               registry-key)
          {::error ::unsafe-namespace
           ::attribute registry-key
           ::file file
           ::namespace schema-namespace
           :seon.error/kind :user-input})))
      (when-not (= file-namespace schema-namespace)
        (throw
         (ex-info
          (str "Schema attribute " registry-key " is declared in " file
               " but belongs in " schema-namespace ".edn.")
          {::error ::misplaced-attribute
           ::attribute registry-key
           ::file file
           ::expected-file (str schema-namespace ".edn")
           :seon.error/kind :user-input}))))))

(defn- merge-schema-resources
  [loaded]
  (reduce
   (fn [{::keys [forms files-by-key]} {file ::file incoming ::forms}]
     (when-let [attribute
                (first (sort (filter #(contains? forms %) (keys incoming))))]
       (let [first-file (get files-by-key attribute)]
         (throw
          (ex-info
           (str "Schema attribute " attribute " is declared in both "
                first-file " and " file ".")
           {::error ::duplicate-attribute
            ::attribute attribute
            ::file file
            ::files [first-file file]
            :seon.error/kind :user-input}))))
     {::forms (merge forms incoming)
      ::files-by-key
      (merge files-by-key (zipmap (keys incoming) (repeat file)))})
   {::forms {} ::files-by-key {}}
   loaded))

(defn- resource-population
  [resource]
  (let [paths (schema-resource-paths resource)
        loaded (mapv read-schema-resource paths)
        {declared ::forms files-by-key ::files-by-key}
        (merge-schema-resources loaded)]
    (when (directory-resource? resource)
      (validate-resource-placement! loaded))
    {::file (if (= 1 (count loaded)) (::file (first loaded)) resource)
     ::declared-forms declared
     ::forms (derive-config-forms declared)
     ::files-by-key files-by-key}))

(defn declaration-digest
  "Stable digest of the merged schema declaration set."
  {:malli/schema [:=> [:cat] :seon.source/digest]}
  []
  (schema/sha-256
   [(.getBytes (schema/canonical-data-string
                (::declared-forms (resource-population default-resource)))
               "UTF-8")]))

(defn packaged-forms
  "Canonical schema forms declared by Seon's bootstrap and schema resources."
  {:malli/schema [:=> [:cat] :map]}
  []
  (merge packaged-base-forms
         (::forms (resource-population default-resource))))

(defn load!
  "Read one schema resource location into candidates.
  The default is `default-resource`, physically `resources/seon/schemas/`
  in a source checkout. Each resource is one EDN map of registry key → schema
  form. Refuses `::duplicate-attribute` (one key appears twice in or across
  resources), `::unreadable-file` (resource named), and `::not-a-map` (a top
  level is not a map). Contributes
  candidates exactly as `register!` does; never activates — activation
  admits the whole population through `admit`. Returns what was
  loaded."
  {:malli/schema [:=> [:cat ::load-request] ::loaded]}
  [{::keys [resource]}]
  (let [resource (or resource default-resource)
        {::keys [file forms files-by-key]}
        (resource-population resource)]
    (schema/contribute-candidate-forms! forms)
    (swap! !source-files merge files-by-key)
    {::file file
     ::keys (count forms)}))

(defn- predicate-declarations
  [value path]
  (cond
    (and (vector? value) (= :fn (first value)))
    (let [properties (when (map? (second value)) (second value))
          predicate (get value (if properties 2 1))]
      [{::path path
        ::predicate predicate
        ::honest-generator?
        (boolean
         (and properties
              (or (contains? properties :gen/schema)
                  (contains? properties :gen/gen))))}])

    (map? value)
    (into []
          (mapcat (fn [[k v]]
                    (into (predicate-declarations k (conj path :key))
                          (predicate-declarations v (conj path k)))))
          value)

    (coll? value)
    (into []
          (mapcat (fn [[index child]]
                    (predicate-declarations child (conj path index))))
          (map-indexed vector value))

    :else []))

(defn- refusal!
  [error identity declaration extra]
  (let [file (get @!source-files identity)
        predicate (::predicate extra)
        predicate-owner (::predicate-owner extra)]
    (throw
     (ex-info
      (str "Schema population refused " identity " (" (name error) ")."
           (when (= error ::unregistered-predicate)
             (str " Predicate " predicate " is owned by namespace "
                  predicate-owner "; load or reload that namespace before "
                  "schema admission.")))
      (cond->
       (merge
        {::error error
         ::attribute identity
         :seon.schema/definition declaration
         :seon.error/kind :user-input}
        extra)
        file (assoc ::file file))))))

(defn- predicate-registered?
  "True when the `[:fn]` symbol names a registered core predicate,
  loading its owner namespace first if needed. A qualified symbol
  carries its owner; `requiring-resolve` loads that namespace, whose
  load-time `register-core-predicate!` call registers the predicate —
  the same symbols-as-data idiom as `:seon.source/populate`. This is
  the COMPUTED rule that removes load-order from admission: no
  activation site needs to require every package whose EDN file names
  a predicate. (Dormant cycle risk, stated: a predicate owner that
  itself activates the population would recurse — `load!` does not
  activate, so registering predicates at load time cannot cycle.)"
  [predicate]
  (and (qualified-symbol? predicate)
       (or (schema/core-predicate-registered? predicate)
           (and (some? (try
                         (requiring-resolve predicate)
                         (catch Throwable _ nil)))
                (schema/core-predicate-registered? predicate)))))

(defn- assert-predicates!
  [forms]
  (doseq [[identity definition] (sort-by key forms)
          {::keys [predicate honest-generator? path]}
          (predicate-declarations definition [])]
    (when-not honest-generator?
      (refusal! ::dishonest-generator identity definition
                {::predicate predicate ::path path}))
    (when-not (predicate-registered? predicate)
      (refusal! ::unregistered-predicate identity definition
                {::predicate predicate
                 ::predicate-owner (some-> predicate namespace symbol)
                 ::path path}))))

(defn admit
  "THE one admission gate over a complete candidate population.
  The population is one map of registry key → schema form. Called by activation over the loader's
  merged population, and by `register!` over the population plus its
  one new candidate — one gate, two producers. Returns the vector of
  admitted attribute declarations. Refuses, naming the key (and file
  when known): `::unresolved-reference` (a referenced registry key
  absent from the population), `::unregistered-predicate` (a `[:fn]`
  symbol with no `register-core-predicate!`), and
  `::dishonest-generator` (a `[:fn]` form carrying neither
  `:gen/schema` nor `:gen/gen`)."
  {:malli/schema [:=> [:cat [:map [:seon.schema/forms :map]]]
                  [:vector :map]]}
  [{:seon.schema/keys [forms identity admission]}]
  (let [original-forms forms
        forms (if identity (derive-config-forms forms) forms)]
    (assert-predicates! forms)
    (try
      (if identity
        ;; Runtime registration admits the new declaration against the complete
        ;; candidate registry. Unrelated bootstrap declarations may still await
        ;; their owning namespace during module loading, so they are not
        ;; recompiled merely because this producer added one independent key.
        (schema/assert-complete-contract!
         {:seon.schema/identity identity
          :seon.schema/definition (get forms identity)
          :seon.schema/forms forms
          :seon.schema/admission
          (or admission {:seon.schema.admission/source :agent})})
        ;; Activation has no distinguished producer: every declaration must
        ;; compile and resolve in the complete population before publication.
        (schema/build-projection forms))
      (catch Exception e
        (let [data (ex-data e)
              offending (or (:seon.schema/identity data)
                            (:seon.schema/key data)
                            identity)
              definition (get forms offending)]
          (if (or (:seon.schema/missing-reference data)
                  (= :malli.core/invalid-schema (:type data)))
            (refusal! ::unresolved-reference offending definition
                      {::cause data})
            (throw e)))))
    ;; `schema/register!` owns the leaf write. When that one registration
    ;; changes a derived config composite, contribute only those projections
    ;; after admission succeeds; no hand-written caller can fall between the
    ;; leaf and its manifest/effective/database shapes.
    (when identity
      (let [composites
            [:seon.config/manifest
             :seon.config/effective
             :seon.config/agent-overlay
             :seon.config/entity]
            changed
            (into {}
                  (keep
                   (fn [composite]
                     (when (not= (get original-forms composite)
                                 (get forms composite))
                       [composite (get forms composite)])))
                  composites)]
        (when (seq changed)
          ;; Contribute the leaf and all four projections in one atom update.
          ;; `schema/register!`'s following leaf assoc is then idempotent; no
          ;; observer can see either half of the registration on its own.
          (schema/contribute-candidate-forms!
           (assoc changed identity (get forms identity))))))
    (mapv (fn [[registered-identity definition]]
            {:seon.schema/key registered-identity
             :seon.schema/definition definition})
          (sort-by key forms))))
