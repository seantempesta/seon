(ns seon.schema.edn
  "Schema definitions as EDN data: the classpath loader and the ONE
  admission gate.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — B2 wave, from the
  sealed schema-EDN ruling and b2-plan §6). The schemas and function
  contracts are SEALED: the implementation lane fills the stub bodies
  until test/seon/schema/edn_test.clj is green and may not loosen a
  schema or a test.

  The model (the sealed ruling, verbatim where it matters):

  - Attribute/entity schemas live as EDN maps (registry key → schema
    form) in `seon/schema/*.edn` resources on the classpath. The
    population is GLOBAL: file boundaries are editorial convenience
    with zero semantic meaning; the loader merges every file and
    REFUSES a duplicate attribute across files, naming the key and
    both files.
  - `register!` already guarantees forms are readable, round-tripping
    EDN — moving them to `.edn` files is a relocation of the same
    values, not a new format. The loader contributes the merged
    population through the same candidate route `register!` uses; it
    never activates.
  - ONE admission gate, `admit`, shared by both producers: the loader's
    merged files at boot/build, and agents' `register!` at runtime.
    It validates a COMPLETE candidate population: every reference
    resolves; every `[:fn]` names a registered core predicate; every
    `[:fn]` carries an honest generator (`:gen/schema` or `:gen/gen` —
    an opaque platform predicate is honest by constructing a real
    instance). Refusals name the offending key and, for loaded forms,
    the contributing file.
  - LOAD ORDER IS NOT A HAZARD BY CONSTRUCTION: the `[:fn]` predicate
    and honesty checks run at ACTIVATION over the whole population,
    never per-file at load — namespaces register their core predicates
    first, then `load!` merges, then activation admits everything at
    once. Sixteen registrations stay in code by a COMPUTED rule, never
    a list: exactly the schemas the `seon.schema.*` namespaces
    themselves need before any EDN file can be validated.
  - Classpath enumeration handles both `file:` (dev source classpath)
    and `jar:` (publish) resource URLs.

  Crash walk: `load!` and `admit` are pure reads over classpath
  resources and in-memory populations — no durable state, nothing to
  recover. Committing admitted schema FACTS to a store is the
  ancestor build's job, not this namespace's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.schema :as schema])
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

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defonce ^:private !source-files (atom {}))

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

(defn load!
  "Merge every `<resource-dir>/*.edn` on the classpath into candidates.
  Default resource-dir is \"seon/schema\". Each
  file is one EDN map of registry key → schema form. Refuses
  `::duplicate-attribute` (one key contributed by two files, both
  named), `::unreadable-file` (not EDN, file named), and
  `::not-a-map` (a file whose top level is not a map). Contributes
  candidates exactly as `register!` does; never activates — activation
  admits the whole population through `admit`. Returns what was
  loaded."
  {:malli/schema [:=> [:cat ::load-request] ::loaded]}
  [{::keys [resource-dir]}]
  (let [resource-dir (or resource-dir "seon/schema")
        resources (resource-files resource-dir)
        {::keys [forms files-by-key]} (merge-schema-files resources)]
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
  the same symbols-as-data idiom as `:seon.ancestor/populate`. This is
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
  (mapv (fn [[identity definition]]
          {:seon.schema/key identity
           :seon.schema/definition definition})
        (sort-by key forms)))
