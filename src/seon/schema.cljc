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

;; Schemas-as-queryable-data meta-schema. Every DECLARED entity-kind :map
;; (one carrying {:seon.db/entity true} → derived :seon.entity/id-attr) ALSO
;; transacts a :seon.schema entity carrying its required-attrs, id-attr, and
;; render-fn symbol; render's kind-lookup queries those entities via datalog.
;; These leaf-scalar attrs are registered here so they exist before any
;; entity ns loads. Research:
;; docs/prds/agent-runtime/research/schemas-as-queryable-data-2026-05-26.md.
(defonce ^:private _schema-required-attrs
  (swap! *schemas assoc :seon.schema/required-attrs [:vector :keyword]))
(defonce ^:private _schema-id-attr
  (swap! *schemas assoc :seon.schema/id-attr :keyword))
(defonce ^:private _schema-render-fn
  (swap! *schemas assoc :seon.schema/render-fn :symbol))
(defonce ^:private _schema-render-html-fn
  (swap! *schemas assoc :seon.schema/render-html-fn :symbol))

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
  "Build and validate one immutable runtime projection from `{key form}`.

   Every key compiles against the complete candidate registry, so validation
   is independent of declaration order. The entity catalog is derived from
   authored forms; it is never transacted as a second schema model. Pure: no
   atom, default-registry, database, or var mutation."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (let [registry (mr/composite-registry
                   (m/default-schemas)
                   (mr/fast-registry forms))
        options  {:registry registry}
        _        (doseq [k (sort (keys forms))]
                   (m/schema k options))
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
        fingerprint (hash (pr-str (sort-by key forms)))]
    {:seon.schema.projection/forms forms
     :seon.schema.projection/registry registry
     :seon.schema.projection/catalog catalog
     :seon.schema.projection/fingerprint fingerprint}))

(defn activate!
  "Validate and atomically activate a complete `{schema-key form}` set.

   The candidate is fully built before either the collector or Malli default
   registry changes. Returns the activated projection."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (let [candidate (build-projection forms)]
    (reset! *schemas forms)
    (reset! !projection candidate)
    (mr/set-default-registry! (:seon.schema.projection/registry candidate))
    candidate))

(defn current-projection
  "The active disposable projection, or nil during initial module loading."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  @!projection)

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
;;; Schemas-as-queryable-data — entity-schema decomposition into DB datoms.
;;;
;;; Every entity-shape `:map` schema with a derived `:seon.entity/id-attr`
;;; ALSO becomes a `:seon.schema` entity:
;;;   :seon.schema/key            <kw>       (identity)
;;;   :seon.schema/required-attrs [<kw> ...] (cardinality-many keyword)
;;;   :seon.schema/id-attr        <kw>
;;;   :seon.schema/render-fn      <symbol>   (the :seon.render/ai symbol)
;;;
;;; This ns MUST NOT require seon.db (cycle: db→schema). Instead
;;; `entity-schema-tx-data` returns the tx-data vector; the conn-owning
;;; caller (seon.client/start-agent!) transacts via seon.db/transact!.
;;; ---------------------------------------------------------------------------

(defn entity-schema-tx-data
  "Return the tx-data vector for one entity-shape `:map` schema.

   One `:db/add` per required-attr, plus the key/id-attr/render-fn datoms.
   Caller transacts via `seon.db/transact!`. Returns `nil` when `k` does
   not refer to an entity-shape :map (no id-attr derivable)."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :any]}
  [k]
  (let [v (some->> (get @*schemas k)
                   (internal/with-entity-id-attr @*schemas))]
    (when (and v (internal/map-shape? v))
      (let [props       (internal/schema-properties v)
            id-attr     (:seon.entity/id-attr props)
            render-ai   (:seon.render/ai props)
            render-html (:seon.render/html props)]
        (when id-attr
          (let [reqs (vec (remove #{id-attr} (internal/map-required-attrs v)))
                ;; id-attr is always required — listed separately so it's
                ;; not duplicated when the entry has no {:optional true}.
                reqs (vec (distinct (cons id-attr reqs)))
                ;; FULL keyword in the tempid — (name k) alone collides when
                ;; two kinds share a name segment (:a.b/person + :c.d/person
                ;; → one tempid → boot-fatal :transact/upsert). Tempids are
                ;; tx-local, never stored — identity is :seon.schema/key.
                tid  (str "schema-" k)]
            (cond-> [[:db/add tid :seon.schema/key k]
                     [:db/add tid :seon.schema/id-attr id-attr]]
              render-ai   (conj [:db/add tid :seon.schema/render-fn render-ai])
              render-html (conj [:db/add tid :seon.schema/render-html-fn render-html])
              :always     (into (map (fn [r] [:db/add tid :seon.schema/required-attrs r]))
                                reqs))))))))

(defn entity-schema-keys
  "Every registered keyword pointing at an entity-shape `:map` schema.

   Sorted, one per entity with a derived `:seon.entity/id-attr`. Used by
   `seon.client/start-agent!` to seed `:seon.schema` entities at boot."
  {:malli/schema [:=> [:cat] [:vector :keyword]]}
  []
  (->> @*schemas
       (keep (fn [[k raw]]
               (let [v (internal/with-entity-id-attr @*schemas raw)]
                 (when (and (internal/map-shape? v)
                            (:seon.entity/id-attr (internal/schema-properties v)))
                   k))))
       sort
       vec))

(defn all-entity-schemas-tx-data
  "Tx-data vector for every currently-registered entity-shape :map schema.
   Concatenates `entity-schema-tx-data` over `entity-schema-keys`.
   Idempotent — identity-attr upsert on `:seon.schema/key` replaces prior
   decompositions in place."
  {:malli/schema [:=> [:cat] [:vector :any]]}
  []
  (into [] (mapcat entity-schema-tx-data) (entity-schema-keys)))

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
