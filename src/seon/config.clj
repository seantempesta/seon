(ns seon.config
  "The one compiler and database reconcile boundary for configuration.

  `config/default.edn` makes one explicit decision for every registered config
  attribute. A selected manifest is a sparse overlay. The caller may pass one
  more explicit, typed environment map; compilation applies exactly the
  precedence defaults → overlay → environment, validates the closed result,
  and derives one canonical effective map, digest, and desired row.

  Runtime consumers read only the database row. Omission from a sparse overlay
  inherits the shipped decision; it does not retract a defaulted optional
  attribute. `:seon.config/absent` is the one explicit retraction form, is
  refused for required attributes, and never becomes nil or a datom."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [datahike.api :as d]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form])
  (:import [java.nio.charset StandardCharsets]))

(def default-manifest-path
  "The one repository/artifact-relative shipped defaults document."
  "config/default.edn")

(def managing-process-identity
  "The opaque reconcile scope owned by configuration."
  "seon.db.process/config")

(def absent
  "The sole explicit retraction decision for an optional config attribute.

  Omitting an overlay entry inherits its shipped decision. This marker instead
  removes a defaulted optional attribute from the effective map and desired
  database row; the marker itself is never stored."
  :seon.config/absent)

(def ^:private available-processors
  :seon.config/available-processors)

(def ^:private result-cap-attributes
  [:seon.config.eval.result/max-depth
   :seon.config.eval.result/max-collection
   :seon.config.eval.result/max-string
   :seon.config.eval.result/max-nodes])

(defn result-caps
  "Derive the value-admission caps from one effective configuration."
  {:malli/schema
   [:=> [:cat :seon.config/effective] :seon.sci.admit/caps]}
  [effective]
  (select-keys effective result-cap-attributes))

(defn- map-attributes
  [schema-key]
  (into #{}
        (comp (filter vector?) (map first))
        (schema/schema-definition schema-key)))

(defn- dial-attributes
  []
  (map-attributes :seon.config/manifest))

(defn- required-dial-attributes
  []
  (into #{}
        (comp
         (filter vector?)
         (keep
          (fn [entry]
            (when-not (and (map? (second entry))
                           (:optional (second entry)))
              (first entry)))))
        (schema/schema-definition :seon.config/effective)))

(defn- registration-defaults
  []
  (let [required (required-dial-attributes)]
    (into {}
          (keep
           (fn [attribute]
             (let [properties
                   (schema.form/attr-form-properties
                    (schema/schema-definition attribute))]
               (cond
                 (contains? properties :seon.config/default)
                 [attribute (:seon.config/default properties)]

                 (not (contains? required attribute))
                 [attribute absent])))
          (dial-attributes)))))

(defn- refuse!
  [rule data cause]
  (throw
   (ex-info
    (str "Configuration refused: " (name rule) ".")
    (merge {:seon.error/kind ::refused
            ::rule rule}
           data)
    cause)))

(defn- read-edn-map
  [path]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader path))]
      (let [eof (Object.)
            value (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when-not (and (map? value)
                       (identical? eof trailing))
          (throw
           (ex-info
            "A manifest must contain exactly one EDN map."
            {::path path})))
        value))
    (catch Throwable error
      (refuse! ::manifest-unreadable {::path path} error))))

(defn- validate-layer
  [layer]
  (let [dials (dial-attributes)]
    (doseq [key (keys layer)]
      (when-not (contains? dials key)
        (refuse! ::unknown-key {::key key} nil)))
    (doseq [[key value] layer]
      (when-not (or (= absent value)
                    (schema/valid-candidate-value? key value))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation (schema/explain-candidate-value key value)}
         nil)))
    layer))

(defn default-decisions
  "Read the complete shipped decision map.

  An optional registration's generic floor is explicit absence; a
  registration-attached default overrides that floor. The shipped EDN document
  must decide every production attribute explicitly; symbolic machine and
  absence decisions are resolved only by `compile-manifest`."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [document (read-edn-map
                  (or (io/resource default-manifest-path)
                      default-manifest-path))
        dials (dial-attributes)
        decisions (merge (registration-defaults) document)
        missing (set/difference dials (set (keys decisions)))]
    (doseq [key (keys document)]
      (when-not (contains? dials key)
        (refuse! ::unknown-key {::key key} nil)))
    (when (seq missing)
      (refuse!
       ::missing-default
       {::explanation {:seon.config/missing missing}}
       nil))
    (doseq [[key decision] decisions]
      (when-not (or (= absent decision)
                    (and (= key :seon.config.flow.compute/concurrency)
                         (= available-processors decision))
                    (schema/valid-candidate-value? key decision))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation (schema/explain-candidate-value key decision)}
         nil)))
    decisions))

(defn read-manifest
  "Read and validate one sparse plain-EDN overlay without compiling it."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (validate-layer (read-edn-map path)))

(defn- resolve-smart-decision
  [key decision]
  (if (and (= key :seon.config.flow.compute/concurrency)
           (= decision available-processors))
    (long (.availableProcessors (Runtime/getRuntime)))
    decision))

(defn compile-manifest
  "Compile defaults + sparse manifest + explicit typed environment map once.

  The optional cluster name defaults to `default`. The digest covers only the
  canonical effective config, so equal configs in distinct clusters have the
  same digest."
  {:malli/schema
   [:=> [:cat :seon.config/compile-request] :seon.config/compiled]}
  [request]
  (let [manifest (validate-layer (or (:seon.config/manifest request) {}))
        environment
        (validate-layer (or (:seon.config/environment request) {}))
        decisions (merge (default-decisions) manifest environment)
        required (required-dial-attributes)]
    (doseq [[key decision] decisions]
      (when (and (= absent decision) (contains? required key))
        (refuse! ::required-absent {::key key} nil)))
    (let [effective
          (into {}
                (comp
                 (remove (comp #{absent} val))
                 (map
                  (fn [[key decision]]
                    [key (resolve-smart-decision key decision)])))
                decisions)]
      (when-not (schema/valid-candidate-value?
                 :seon.config/effective effective)
        (refuse!
         ::invalid-value
         {::explanation
          (schema/explain-candidate-value
           :seon.config/effective effective)}
         nil))
      (let [digest
            (schema/sha-256
             [(.getBytes
               ^String (schema/canonical-data-string effective)
               StandardCharsets/UTF_8)])
            row
            (assoc effective
                   :seon.config/cluster
                   (or (:seon.boot/cluster-name request) "default")
                   :seon.config/applied-manifest-digest digest)]
        {:seon.config/effective effective
         :seon.config/applied-manifest-digest digest
         :seon.config/desired-row row
         :seon.config/resolved-attributes (set (keys decisions))}))))

(defn defaults
  "Compile the zero-overlay shipped defaults into one effective config."
  {:malli/schema [:=> [:cat] :seon.config/effective]}
  []
  (:seon.config/effective (compile-manifest {})))

(defn apply!
  "Compile once and exact-reconcile the one desired config row."
  {:malli/schema
   [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]}
  [request]
  (let [compiled
        (compile-manifest
         (select-keys
          request
          [:seon.config/manifest
           :seon.config/environment
           :seon.boot/cluster-name]))]
    (reconcile/reconcile!
     (:seon.config/connection request)
     {::reconcile/desired [(:seon.config/desired-row compiled)]
      ::reconcile/process managing-process-identity
      ::reconcile/adopt-identities
      #{[:seon.config/cluster
         (:seon.config/cluster (:seon.config/desired-row compiled))]}})))

(defn effective
  "Read the effective config for one cluster; absent cluster means `default`."
  {:malli/schema
   [:function
    [:=> [:cat :any] [:or :seon.config/effective [:map {:closed true}]]]
    [:=> [:cat :any :seon.boot/cluster-name]
     [:or :seon.config/effective [:map {:closed true}]]]]}
  ([db]
   (effective db "default"))
  ([db cluster-name]
   (select-keys
    (dissoc
     (or
      (d/pull
       db
       '[*]
       [:seon.config/cluster (or cluster-name "default")])
      {})
     :db/id
     :seon.config/cluster
     :seon.config/applied-manifest-digest)
    (dial-attributes))))
