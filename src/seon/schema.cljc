(ns seon.schema
  "Malli declaration and runtime-projection boundary for Seon.

   Canonical schema forms are database facts. During module loading this
   namespace collects compiled declarations; after database reconciliation it
   validates and activates one immutable projection of those facts.

   Namespaces declare schemas here with `register!`, making them
   available for `:malli/schema` fn validation, generative testing, and
   runtime validation. The `::` syntax expands to the current namespace,
   so `::user-id` in `seon.trading.core` becomes
   `:seon.trading.core/user-id`.

     (require '[seon.schema :as schema])
     (schema/register! ::user-id   :uuid)
     (schema/register! ::user-name [:string {:min 1 :max 200}])

   Form mechanics and register!-time gates live in
   `seon.schema.internal` (kept out of agent context; grep there for the
   Malli-form helpers)."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(defn- direct-references*
  "Canonical registry keys directly referenced by one compiled schema.

   Malli's own walker distinguishes references from keyword data. Canonical
   refs are recorded but not followed; local property-registry refs are
   followed so canonical refs nested behind them are still visible."
  [compiled canonical-keys]
  (let [!references (volatile! #{})]
    (m/walk
      compiled
      (fn [schema _path _children _options]
        (when (m/-ref-schema? schema)
          (let [reference (m/-ref schema)]
            (when (contains? canonical-keys reference)
              (vswap! !references conj reference))))
        schema)
      {::m/walk-schema-refs #(not (contains? canonical-keys %))
       ::m/walk-refs #(not (contains? canonical-keys %))})
    @!references))

(defn direct-references
  "Canonical schema keys directly referenced by `form` in `projection`.

   This is a derived dependency view over Malli schema objects, not a keyword
   scan and not stored state. It works for value, entity, and function-schema
   forms and follows local recursive registries without expanding canonical
   references transitively."
  {:malli/schema [:=> [:catn [::projection :map] [::definition :any]]
                  [:set :keyword]]}
  [projection form]
  (let [forms (:seon.schema.projection/forms projection)
        registry (:seon.schema.projection/registry projection)
        compiled (m/schema form {:registry registry})]
    (direct-references* compiled (set (keys forms)))))

(defn dependent-schema-keys
  "Changed schema keys plus their reverse transitive dependents.

   The dependency graph belongs to the immutable projection. The result is
   derived with a bounded graph walk and is empty when `changed` is empty."
  {:malli/schema [:=> [:catn [::projection :map]
                             [::changed [:set :keyword]]]
                  [:set :keyword]]}
  [projection changed]
  (let [reverse-edges
        (:seon.schema.projection/reverse-schema-dependencies projection)]
    (loop [frontier (set changed)
           seen #{}]
      (if (empty? frontier)
        seen
        (let [seen' (into seen frontier)
              next-frontier
              (into #{}
                    (comp
                      (mapcat #(get reverse-edges % #{}))
                      (remove seen'))
                    frontier)]
          (recur next-frontier seen'))))))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

;; All registered domain schemas. `defonce` survives namespace reloads.
(defonce ^:private *schemas (atom {}))

;; The one disposable compiled projection of the current database schema facts.
;; During initial module loading this is nil and register! collects declarations
;; in `*schemas`; after database reconciliation it is a complete immutable
;; registry/catalog candidate.
(defonce ^:private !projection (atom nil))

;; THE one registry seon installs as malli's process-global default: malli's
;; built-in schemas + seon's mutable `*schemas` (read live on every lookup, so
;; this single instance always reflects current registrations). A SINGLE
;; memoized instance — `defonce` survives reloads — so the stomp-guard watch
;; below can `identical?`-check it cheaply.
(defonce ^:private seon-registry
  (mr/composite-registry
   (m/default-schemas)
   (mr/mutable-registry *schemas)))

(defn relink-registry!
  "Repoint Malli's convenience default to Seon's current projection.

   During initial module loading this uses the mutable declaration collector;
   after database reconciliation it uses the immutable validated candidate.
   The bootstrap load wrapper calls this after Malli bundle loads that reset
   their own default. The old private-atom watch is deliberately gone."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (mr/set-default-registry!
    (or (:seon.schema.projection/registry @!projection)
        seon-registry))
  true)

;; Initialize the global registry once at load time.
(defonce ^:private _registry-init (relink-registry!))

;; :inst as a keyword type (Malli only provides the `inst?` predicate), for
;; consistency with :string, :int, etc. The quoted predicate is pure data and
;; round-trips through the canonical database schema fact.
(defonce ^:private _inst-type
  (swap! *schemas assoc :inst 'inst?))

;; :seon.db/lookup-ref-value — the value position in a lookup-ref. Datahike
;; accepts strings, uuids, keywords, and ints as unique-attr values.
(defonce ^:private _lookup-ref-value-type
  (swap! *schemas assoc :seon.db/lookup-ref-value
         [:or :string :uuid :keyword :int]))

;; :seon.db/ref — an intra-DB :db.type/ref. At transact time datahike
;; resolves any supported form to an eid: pos-int (existing eid), neg-int
;; (numeric tempid), string (string tempid), or [k v] (lookup-ref on unique
;; attr k). Cross-DB handles are :uuid attrs with :seon.db/ref-to metadata —
;; NEVER :seon.db/ref. Reference: docs/prds/datahike-migration/ref-model-research.md.
(defonce ^:private _ref-type
  (swap! *schemas assoc :seon.db/ref
         [:or
          :int
          :string
          [:tuple :keyword :seon.db/lookup-ref-value]]))

;; Generated persistent identity syntax is owned by `seon.db.id`, which loads
;; before `seon.db` registers slots that refer to `:seon.db/id`.  Keeping an
;; older bootstrap copy here let namespace load order silently restore the
;; retired timestamp grammar, so there is deliberately no second definition.

;; Positional-arg slot shapes for this ns's register/introspection fns — each
;; named-positional `:catn` slot in a `:malli/schema` below references one of
;; these (db.cljs's `::conn`/`::tx-data` slot-schema pattern). A Malli schema
;; DEFINITION is a recursive, heterogeneous structure —
;; genuinely opaque, hence `:any` (the documented third-party-shape exception).
(defonce ^:private _registry-key-type
  (swap! *schemas assoc :seon.schema/registry-key :keyword))
(defonce ^:private _definition-type
  (swap! *schemas assoc :seon.schema/definition :any))
(defonce ^:private _namespace-name-type
  (swap! *schemas assoc :seon.schema/namespace-name :string))
(defonce ^:private _kvs-type
  (swap! *schemas assoc :seon.schema/kvs [:vector :any]))
(defonce ^:private _discarded-keys-type
  (swap! *schemas assoc :seon.schema/discarded-keys [:set :seon.schema/registry-key]))

;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn identity-attr?
  "True when the attr schema for `attr-key` carries `{:seon.db/identity true}`.

   Covers the three identity shapes Seon uses
   (plain `:string`/`:keyword` with the prop, and the `:and` id wrap).
   PUBLIC: the single identity-attr predicate — callers reuse it rather than
   re-deriving the props lookup."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [attr-key]
  (internal/identity-attr? @*schemas attr-key))

(defn enum-members
  "Members of a registered `:enum` attr schema, or an empty vector.

   Empty when the attr is not an enum (absence = empty, never nil). Reads the schema
   form directly — NO db query. PUBLIC: low-cardinality value surfaces reuse
   it. Members are Malli-form contents
   (keywords/strings/ints) — a third-party-structure boundary, hence `:any`."
  {:malli/schema [:=> [:cat :keyword] [:vector :any]]}
  [attr-key]
  (internal/enum-members (get @*schemas attr-key)))

(defn register!
  "Define a new attribute so facts using it can be saved and queried.

   Adds one canonical declaration to the current candidate collector.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns the registered keyword `k`.

   Entity-map render metadata stays in the authored form. The activated
   projection derives its id attribute and renderer catalog without persisting
   a second decomposition. Maps without `{:seon.db/entity true}` are ordinary
   request/response or view schemas and do not enter that catalog.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])
     (register! :seon.eval [:map {:seon.db/entity true
                                  :seon.render/ai 'foo}
                            [:seon.eval/id ...] ...])"
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                         [::definition ::definition]]
                  ::registry-key]}
  [k v]
  ;; CLJS-only until the JVM's legacy `:form/*` registrations are renamed.
  #?(:cljs (internal/assert-multi-segment-namespace! k)
     :clj  nil)
  (let [encoded (pr-str v)
        decoded (try
                  (#?(:clj edn/read-string :cljs reader/read-string) encoded)
                  (catch #?(:clj Exception :cljs :default) e
                    (throw
                      (ex-info
                        (str "schema/register! " k
                             ": schema forms must be readable EDN; "
                             "function objects and executable values belong "
                             "at function boundaries")
                        {:seon.schema/error :seon.schema/unreadable-form
                         :seon.schema/key k
                         :seon.schema/definition v
                         :seon.error/kind :user-input}
                        e))))]
    (when-not (= v decoded)
      (throw
        (ex-info
          (str "schema/register! " k
               ": schema form does not round-trip as EDN")
          {:seon.schema/error :seon.schema/non-round-tripping-form
           :seon.schema/key k
           :seon.schema/definition v
           :seon.error/kind :user-input}))))
  (internal/assert-compilable-schema! @*schemas k v)
  (internal/assert-non-nilable-value-schema! @*schemas k v)
  (swap! *schemas assoc k v)
  k)

(defn form-string
  "Canonical, full EDN encoding of registered schema `k`, or nil when absent.

   Registration already proves the value round-trips, so this never truncates
   or replaces runtime objects with display placeholders. This is the durable
   `:seon.schema/form` value."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  [:maybe :string]]}
  [k]
  (some-> (get @*schemas k) pr-str))

(defn build-projection
  "Build and validate one immutable runtime projection.

   `forms` is the canonical `{schema-key form}` population and optional
   `function-contracts` is `{qualified-symbol function-form}`. Every schema and
   contract compiles against the complete candidate registry, so validation is
   independent of declaration order. Schema/function dependency indexes and
   the entity catalog are derived here; none are stored as a second model.
   Pure: no atom, default-registry, database, or var mutation."
  {:malli/schema
   [:function
    [:=> [:catn [::forms :map]] :map]
    [:=> [:catn [::forms :map] [::function-contracts :map]] :map]]}
  ([forms] (build-projection forms {}))
  ([forms function-contracts]
   (let [registry (mr/composite-registry
                   (m/default-schemas)
                   (mr/fast-registry forms))
        options  {:registry registry}
        _        (doseq [k (sort (keys forms))]
                   (m/schema k options))
        schema-dependencies
        (into (sorted-map)
              (map (fn [[k form]]
                     [k (direct-references*
                          (m/schema form options)
                          (set (keys forms)))]))
              forms)
        reverse-schema-dependencies
        (reduce-kv
          (fn [reverse-edges dependent dependencies]
            (reduce (fn [edges dependency]
                      (update edges dependency (fnil conj #{}) dependent))
                    reverse-edges
                    dependencies))
          {}
          schema-dependencies)
        function-dependencies
        (into (sorted-map)
              (map (fn [[sym form]]
                     [sym (direct-references*
                            (m/function-schema form options)
                            (set (keys forms)))]))
              function-contracts)
        catalog  (->> forms
                      (keep
                        (fn [[k raw]]
                          (let [form (internal/with-entity-id-attr forms raw)
                                props (when (internal/map-shape? form)
                                        (internal/schema-properties form))
                                id-attr (:seon.entity/id-attr props)]
                            (when (and (:seon.db/entity props) id-attr)
                              (cond->
                                {:seon.schema.catalog/key k
                                 :seon.schema.catalog/id-attr id-attr
                                 :seon.schema.catalog/required-attrs
                                 (set (internal/map-required-attrs form))}
                                (:seon.render/ai props)
                                (assoc :seon.schema.catalog/render-ai
                                       (:seon.render/ai props))

                                (:seon.render/html props)
                                (assoc :seon.schema.catalog/render-html
                                       (:seon.render/html props)))))))
                      (sort-by :seon.schema.catalog/key)
                      vec)
        fingerprint
        (hash (pr-str {:seon.schema.projection/forms
                       (sort-by key forms)
                       :seon.schema.projection/function-contracts
                       (sort-by key function-contracts)}))]
    {:seon.schema.projection/forms forms
     :seon.schema.projection/registry registry
     :seon.schema.projection/schema-dependencies schema-dependencies
     :seon.schema.projection/reverse-schema-dependencies
     reverse-schema-dependencies
     :seon.schema.projection/function-contracts function-contracts
     :seon.schema.projection/function-dependencies function-dependencies
     :seon.schema.projection/catalog catalog
     :seon.schema.projection/fingerprint fingerprint})))

(defn activate-projection!
  "Atomically publish an already validated projection.

   Transition coordinators build the complete candidate before committing,
   then publish that exact object after the database accepts the matching
   facts. No validation or database read occurs here."
  {:malli/schema [:=> [:catn [::projection :map]] :map]}
  [projection]
  (reset! *schemas (:seon.schema.projection/forms projection))
  (reset! !projection projection)
  (mr/set-default-registry! (:seon.schema.projection/registry projection))
  projection)

(defn activate!
  "Validate and atomically activate a complete `{schema-key form}` set.

   The candidate is fully built before either the collector or Malli default
   registry changes. Existing canonical function contracts are revalidated
   against the replacement schema population. Returns the activated projection."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (activate-projection!
    (build-projection
      forms
      (or (:seon.schema.projection/function-contracts @!projection) {}))))

(defn current-projection
  "The active disposable projection, or nil during initial module loading."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  @!projection)

(defn entity-catalog
  "Derived renderable entity catalog for the active schema projection.

   During initial module loading, before database activation, derives once from
   the declaration snapshot on demand. After activation this is the immutable
   catalog built from canonical database forms. No catalog facts are stored."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (or (:seon.schema.projection/catalog @!projection)
      (:seon.schema.projection/catalog (build-projection @*schemas))))

(defn current-keys
  "Snapshot of all currently-registered schema keywords.

   Used by detect-and-tee in eval-batch! for atom-diff schema detection (before vs
   after an eval reveals what the form registered)."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (set (keys @*schemas)))

(defn snapshot
  "Immutable `{schema-key form}` snapshot for one eval transition."
  {:malli/schema [:=> [:cat] :map]}
  []
  @*schemas)

(defn changed-keys
  "Schema keys whose canonical form differs from `before`, including new keys."
  {:malli/schema [:=> [:catn [::before :map]] [:set :keyword]]}
  [before]
  (into #{}
        (keep (fn [[k form]]
                (when (not= form (get before k ::absent)) k)))
        @*schemas))

(defn restore!
  "Restore an exact registry snapshot after a failed eval.

   This restores redefinitions as well as removing new keys; the previous
   key-set-only rollback left failed redefinitions live."
  {:malli/schema [:=> [:catn [::before :map]] :nil]}
  [before]
  (reset! *schemas before)
  nil)

(defn register-all!
  "Register multiple schemas at once from keyword/definition pairs.

   Returns the set of registered keywords. Throws if an odd
   number of arguments is provided.

   Example:
     (register-all!
       ::user-id    :uuid
       ::user-name  [:string {:min 1}]
       ::user-email [:string {:min 5}])"
  {:malli/schema [:=> [:catn [::kvs [:* :any]]] [:set :keyword]]}
  [& kvs]
  ;; NOTE: each kv pair is a [registry-key form] pair; the variadic slot
  ;; can't enumerate them, hence `[:* :any]`.
  (assert (even? (count kvs)) "register-all! requires pairs of [key schema]")
  (let [pairs (partition 2 kvs)]
    (doseq [[k v] pairs]
      (register! k v))
    (set (map first pairs))))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "A map of all registered domain schemas (Malli's built-ins excluded)."
  {:malli/schema [:=> [:cat] :map]}
  []
  @*schemas)

(defn registered?
  "Check if a schema keyword is registered."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :boolean]}
  [k]
  (contains? @*schemas k))

(defn schema-definition
  "The raw definition for a registered schema, or nil if not registered."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :any]}
  [k]
  (get @*schemas k))

(defn schemas-in-namespace
  "The `{keyword definition}` map of schemas registered under `ns-name`.

   `ns-name` is a string, e.g. \"seon.agent\"."
  {:malli/schema [:=> [:catn [::namespace-name ::namespace-name]] :map]}
  [ns-name]
  (into {}
        (filter (fn [[k _]] (= (namespace k) ns-name)))
        @*schemas))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all!
  "Clear all registered schemas; testing only, use with caution."
  {:malli/schema [:=> [:cat] :map]}
  []
  (reset! *schemas {}))

(comment
  ;; REPL exploration
  (register! ::test-schema [:string {:min 1}])
  (registered-schemas)
  (registered? ::test-schema)
  (schemas-in-namespace "seon.schema")
  (m/validate ::test-schema "hello")
  (m/validate ::test-schema "")          ; fails — min 1
  (require '[malli.generator :as mg])
  (mg/generate ::test-schema)
  nil)
