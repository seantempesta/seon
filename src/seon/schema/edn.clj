(ns seon.schema.edn
  "Loads and admits the classpath's EDN schema population.

  `load!` reads the EDN maps directly beneath a classpath resource
  directory from file or jar URLs, refuses unreadable files and
  duplicate keys, and contributes the merged forms to
  `seon.schema` without activating them. It also derives the manifest,
  effective-config, and config-entity composites from registered config
  attributes.

  `admit` checks a candidate population through the shared schema
  contracts. Every `[:fn]` predicate must resolve to a registered core
  predicate and declare a generator. Refusals identify the offending
  schema key and, for loaded forms, its source file. This namespace
  reads classpath and in-memory data only; database installation belongs
  to cluster population."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form])
  (:import [java.net JarURLConnection URL]
           [java.util.jar JarFile]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

; where the loader looks; overridable so suites use fixture directories
(schema/register! ::resource-dir [:string {:min 1}])
(schema/register! ::files [:vector [:string {:min 1}]])
(schema/register! ::keys [:int {:min 0}])

(schema/register!
 ::load-request
 [:map {:closed true}
  [::resource-dir {:optional true} ::resource-dir]])

(schema/register!
 ::loaded
 [:map {:closed true}
  [::files ::files]
  [::keys ::keys]])

(defonce ^:private packaged-base-forms
  ;; Captured before any consumer namespace can register process-local test or
  ;; REPL schemas. Packaged publication later merges only schema EDN into this
  ;; bootstrap population; the ambient registry is never a build input.
  (schema/registered-schemas))

(def default-resource-dir
  "Classpath directory backed by `resources/seon/schema/` in a source checkout."
  "seon/schema")

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
             (into [:map {:closed true}] manifest-entries)
             :seon.config/effective
             (into [:map {:closed true}] effective-entries)
             :seon.config/agent-overlay
             (into [:map {:closed true}] agent-overlay-entries)
             :seon.config/entity
             (into
              [:map {:closed true :seon.db/entity true}
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

(defn- resource-files
  [resource-dir]
  (let [loader (clojure.lang.RT/baseLoader)]
    (into []
          (mapcat
           (fn [^URL directory-url]
             (case (.getProtocol directory-url)
               "file"
               (let [directory (io/file (.toURI directory-url))]
                 (->> (or (.listFiles directory) (make-array java.io.File 0))
                      (filter #(.isFile ^java.io.File %))
                      (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                      (sort-by #(.getName ^java.io.File %))
                      (map (fn [file]
                             {:seon.schema.edn/file
                              (.toExternalForm (.toURL (.toURI ^java.io.File file)))
                              :seon.schema.edn/url (.toURL (.toURI ^java.io.File file))}))))

               "jar"
               (let [^JarURLConnection connection
                     (.openConnection directory-url)
                     ^JarFile jar (.getJarFile connection)
                     prefix (str (str/replace resource-dir #"/+$" "") "/")]
                 (->> (enumeration-seq (.entries jar))
                      (map #(.getName ^java.util.jar.JarEntry %))
                      (filter #(and (str/starts-with? % prefix)
                                    (str/ends-with? % ".edn")
                                    (not (str/includes?
                                          (subs % (count prefix)) "/"))))
                      sort
                      (map (fn [entry]
                             (let [url (URL. (str "jar:"
                                                   (.toExternalForm
                                                    (.getJarFileURL connection))
                                                   "!/" entry))]
                               {:seon.schema.edn/file (.toExternalForm url)
                                :seon.schema.edn/url url})))))

               (throw
                (ex-info
                 "Schema resource directory uses an unsupported URL protocol."
                 {:seon.schema.edn/resource-dir resource-dir
                  :seon.schema.edn/file (.toExternalForm directory-url)
                  :seon.error/kind :core-bug})))))
          (enumeration-seq (.getResources loader resource-dir)))))

(defn- read-schema-file
  [{::keys [file url]}]
  (let [value
        (try
          (with-open [reader (java.io.PushbackReader. (io/reader url))]
            (edn/read {:eof ::eof} reader))
          (catch Exception e
            (throw
             (ex-info
              (str "Schema EDN file is unreadable: " file)
              {::error ::unreadable-file
               ::file file
               :seon.error/kind :user-input}
              e))))]
    (when-not (map? value)
      (throw
       (ex-info
        (str "Schema EDN file must contain one map: " file)
        {::error ::not-a-map
         ::file file
         :seon.error/kind :user-input})))
    value))

(defn- merge-schema-files
  [resources]
  (reduce
   (fn [{::keys [forms files-by-key] :as population} resource]
     (let [file (::file resource)
           file-forms (read-schema-file resource)]
       (reduce-kv
        (fn [result attribute definition]
          (if-let [prior-file (get (::files-by-key result) attribute)]
            (throw
             (ex-info
              (str "Schema attribute " attribute
                   " is declared by two classpath resources.")
              {::error ::duplicate-attribute
               ::attribute attribute
               ::files [prior-file file]
               :seon.error/kind :user-input}))
            (-> result
                (assoc-in [::forms attribute] definition)
                (assoc-in [::files-by-key attribute] file))))
        population
        file-forms)))
   {::forms {} ::files-by-key {}}
   resources))

(defn- resource-population
  [resource-dir]
  (let [resources (resource-files resource-dir)
        {::keys [forms files-by-key]} (merge-schema-files resources)]
    {::resources resources
     ::forms (derive-config-forms forms)
     ::files-by-key files-by-key}))

(defn packaged-forms
  "Canonical schema forms declared by Seon's bootstrap and schema resources."
  {:malli/schema [:=> [:cat] :map]}
  []
  (merge packaged-base-forms
         (::forms (resource-population default-resource-dir))))

(defn load!
  "Merge every `<resource-dir>/*.edn` on the classpath into candidates.
  The default is `default-resource-dir`, physically
  `resources/seon/schema/` in a source checkout. Each file is one EDN map of
  registry key → schema form. Refuses
  `::duplicate-attribute` (one key contributed by two files, both
  named), `::unreadable-file` (not EDN, file named), and
  `::not-a-map` (a file whose top level is not a map). Contributes
  candidates exactly as `register!` does; never activates — activation
  admits the whole population through `admit`. Returns what was
  loaded."
  {:malli/schema [:=> [:cat ::load-request] ::loaded]}
  [{::keys [resource-dir]}]
  (let [resource-dir (or resource-dir default-resource-dir)
        {::keys [resources forms files-by-key]}
        (resource-population resource-dir)]
    (schema/contribute-candidate-forms! forms)
    (swap! !source-files merge files-by-key)
    {::files (mapv ::file resources)
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
  (let [file (get @!source-files identity)]
    (throw
     (ex-info
      (str "Schema population refused " identity " (" (name error) ").")
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
                {::predicate predicate ::path path}))))

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
