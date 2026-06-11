(ns seon.schema
  "Global Malli schema registry for Seon.

   Provides a centralized, mutable registry for domain schemas. All namespaces
   register their schemas here using `register!`, making them available for:
   - Function schema validation via `m/=>` or `:malli/schema` metadata
   - Generative testing via `malli.generator`
   - Runtime validation via `malli.core/validate`

   Usage:
     (require '[seon.schema :as schema])

     ;; Register schemas using auto-namespaced keywords
     (schema/register! ::user-id :uuid)
     (schema/register! ::user-name [:string {:min 1 :max 200}])

     ;; Or register multiple at once
     (schema/register-all!
       ::order-id :uuid
       ::order-total [:double {:min 0}])

   The `::` syntax expands to the current namespace, so `::user-id` in
   `seon.trading.core` becomes `:seon.trading.core/user-id`.

   This design makes it easy for LLM agents to:
   - Parse individual `register!` forms
   - Update single schema definitions
   - Track which schemas are defined in which namespace"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr]))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

;; Atom holding all registered domain schemas.
;; Use `defonce` to survive namespace reloads.
(defonce ^:private *schemas (atom {}))

(defn relink-registry!
  "(Re)point malli's process-global default registry at the composite of
   malli's default schemas + seon's mutable `*schemas` atom. Idempotent —
   safe to call any number of times; registered schemas live in `*schemas`
   and survive the relink.

   Why this is a public, re-callable operation and not just load-time
   init: `malli.core` runs `(mr/set-default-registry! …)` as a TOP-LEVEL
   side effect of namespace load (malli/core.cljc `default-registry`).
   In the CLJS pod, the bootstrap self-host compiler can re-execute that
   side effect against the LIVE `malli.registry` — e.g. an agent eval of
   `(require '[malli.core :as m])` goog.globalEvals the bootstrap
   bundle's `malli.core$macros.js`, whose macro-mode compile of
   malli/core.cljc still calls the live `set-default-registry!`. That
   stomps the registry with a default-schemas-only snapshot (and
   macro-land IntoSchema instances), severing every seon-registered
   schema: `m/schema` of any `:seon.*` keyword throws
   `:malli.core/invalid-schema` process-wide (live incident 2026-06-10,
   logs/pod.log 15:21–15:22). `seon.eval`'s bootstrap `:load` wrapper
   calls this after every load to re-assert the invariant."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry *schemas)))
  true)

;; Initialize the global registry once at load time.
(defonce ^:private _registry-init (relink-registry!))

;; Register :inst as a keyword type (Malli only provides inst? predicate).
;; This lets schemas use :inst instead of inst? for consistency with :string, :int, etc.
(defonce ^:private _inst-type
  (swap! *schemas assoc :inst (m/-simple-schema {:type :inst :pred inst?})))

;; Register :seon.flow/dynamic — a wire protocol field validated dynamically.
;; Used for ::args, ::value, and ::payload in flow message envelopes.
;; These fields carry data whose type depends on the target function or
;; payload key, so static schema can only assert non-nil. Real validation
;; happens at the message boundary via validate-fn-args!, validate-fn-value!,
;; and validate-payload! in seon.flow.msg.
(defonce ^:private _dynamic-type
  (swap! *schemas assoc :seon.flow/dynamic
         (m/-simple-schema
          {:type :seon.flow/dynamic
           :pred some?
           :type-properties {:gen/schema [:or :int :string :keyword :boolean
                                          [:vector :int] [:map-of :keyword :string]]
                             :gen/fmap identity}})))

;; Register :seon.db/namespace — the entity-namespace stamp attached by
;; `seon.db/transact!` when routing through the datahike flow (Decision 7 of
;; the datahike-migration PRD). Values are db-name keywords (:seon.weather,
;; :seon.phase2.demo, etc). Registered here so the validation gate in
;; seon.db/transact! treats it as a known attr without domain code needing
;; to register it explicitly.
(defonce ^:private _db-namespace-type
  (swap! *schemas assoc :seon.db/namespace :keyword))

;; Register :seon.db/lookup-ref-value — the value position in a lookup-ref.
;; Datahike accepts strings, uuids, keywords, and ints as unique-attr values.
(defonce ^:private _lookup-ref-value-type
  (swap! *schemas assoc :seon.db/lookup-ref-value
         [:or :string :uuid :keyword :int]))

;; Register :seon.db/ref — an intra-DB :db.type/ref.
;; At transact time, datahike resolves any of the supported forms to an eid:
;;   - pos-int  : an existing entity-id
;;   - neg-int  : a numeric tempid
;;   - string   : a string tempid
;;   - [k v]    : a lookup-ref against unique attribute k with value v
;; Cross-DB handles are :uuid attrs with :seon.db/ref-to metadata; they are
;; NEVER labeled :seon.db/ref.
;; Reference: docs/prds/datahike-migration/ref-model-research.md.
(defonce ^:private _ref-type
  (swap! *schemas assoc :seon.db/ref
         [:or
          :int
          :string
          [:tuple :keyword :seon.db/lookup-ref-value]]))

;; Register :seon.db/id — the canonical id-string shape used by every
;; entity identity attr and every tx-meta scalar id. Locked 2026-05-23
;; to <3-letter-random>-<YYMMDDHHmm> (14 chars; generated by
;; seon.db/new-id!). Single source of truth: bump this and every
;; identity-attr length constraint updates.
;;
;; Reference patterns (per CLAUDE.md "Shared schema shapes"):
;;   - Bare-keyword reference for plain scalars:
;;       (schema/register! :seon.db/agent-id :seon.db/id)
;;   - :and wrap for identity attrs (adds {:seon.db/identity true}):
;;       (schema/register! :seon.agent/id [:and {:seon.db/identity true} :seon.db/id])
;;
;; Bridge handles both shapes today; see seon.db/malli->datahike-attr.
(defonce ^:private _id-type
  (swap! *schemas assoc :seon.db/id [:string {:min 14 :max 14}]))

;; --- Schemas-as-queryable-data meta-schema (research:
;; docs/prds/agent-runtime/research/schemas-as-queryable-data-2026-05-26.md).
;;
;; Every DECLARED entity-kind `:map` schema registered via `register!`
;; (one carrying `{:seon.db/entity true}` in its own properties, which
;; gives it a derived `:seon.entity/id-attr`) ALSO transacts a
;; `:seon.schema` entity carrying its required-attrs set, id-attr, and
;; the symbol naming its AI render fn. Kind-lookup in `seon.render`
;; queries those entities via datalog — the schema registry becomes
;; queryable substrate state.
;;
;; These attrs are leaf scalars; they have no chicken-and-egg with the
;; entity-shape :seon.schema map (which references :seon.schema/key as
;; an identity entry). Registered here in seon.schema so they exist
;; before any entity ns loads.
(defonce ^:private _schema-required-attrs
  (swap! *schemas assoc :seon.schema/required-attrs [:vector :keyword]))
(defonce ^:private _schema-id-attr
  (swap! *schemas assoc :seon.schema/id-attr :keyword))
(defonce ^:private _schema-render-fn
  (swap! *schemas assoc :seon.schema/render-fn :symbol))
(defonce ^:private _schema-render-html-fn
  (swap! *schemas assoc :seon.schema/render-html-fn :symbol))

;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn- attr-form-properties
  "Extract the Malli props map from an attr-schema form. Mirrors
   `seon.db/form-properties` (we don't depend on db.cljs from here to
   avoid a require cycle)."
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn- attr-has-identity?
  "True when the registered attr schema for `attr-key` carries
   `{:seon.db/identity true}` directly or through one keyword
   indirection. Covers the three shapes Seon uses today:
     [:string  {:seon.db/identity true}]
     [:keyword {:seon.db/identity true}]
     [:and {:seon.db/identity true} :seon.db/id]"
  [attr-key]
  (let [form (get @*schemas attr-key)]
    (boolean (some-> form attr-form-properties :seon.db/identity))))

(defn- map-shape?
  "True if `v` looks like a Malli `:map` schema form."
  [v]
  (and (vector? v) (= :map (first v))))

(defn- map-entries
  "Return the entries of a `:map` schema form — vector of
   `[entry-key (props?) entry-schema]`. Strips the head and the
   optional schema-level props map."
  [v]
  (let [body (rest v)
        body (if (and (seq body) (map? (first body))) (rest body) body)]
    (vec body)))

(defn- schema-properties
  "Return the :map schema's properties map (the optional second slot
   between the head and the entries), or nil."
  [v]
  (when (map-shape? v)
    (let [body (rest v)]
      (when (and (seq body) (map? (first body)))
        (first body)))))

(defn- map-identity-entry-key
  "The first entry key of `:map` schema `v` that is itself a registered
   attr carrying `{:seon.db/identity true}`, or nil."
  [v]
  (when (map-shape? v)
    (some (fn [entry]
            (when-let [k (and (vector? entry) (first entry))]
              (when (attr-has-identity? k) k)))
          (map-entries v))))

(defn- derive-entity-id-attr
  "If `v` is a `:map` schema DECLARED as a stored entity kind via
   `{:seon.db/entity true}` in its own properties, return its
   identity-attr entry key. Otherwise nil.

   Entity-kind-ness is DECLARED, never inferred (user decision
   2026-06-10): a request/response envelope that happens to carry an
   id entry must NOT become a catalogued kind — the old
   contains-an-id-key inference stamped eight phantom wrapper kinds
   into the live store. Same opt-in property family as
   `{:seon.db/identity true}`.

   The derived id-attr makes a declared entity schema self-describing
   for the renderer's discovery walk (no separate `:seon.entity/kind`
   stamp needed — the schema's own props point at the attr that
   identifies instances of that kind)."
  [v]
  (when (:seon.db/entity (schema-properties v))
    (map-identity-entry-key v)))

(defn- map-required-attrs
  "Return the set of `[entry-key ...]` whose props do NOT carry
   `{:optional true}`. Used by schemas-as-queryable-data to compute
   the required-attrs index entry for an entity-shape :map. Excludes
   the special `::m/default` Malli sentinel if it shows up."
  [v]
  (when (map-shape? v)
    (into []
          (keep (fn [entry]
                  (when (vector? entry)
                    (let [k     (first entry)
                          props (let [p (second entry)]
                                  (when (map? p) p))]
                      (when (and (keyword? k)
                                 (not= k :malli.core/default)
                                 (not (:optional props)))
                        k)))))
          (map-entries v))))

(defn- with-entity-id-attr
  "Attach `{:seon.entity/id-attr <k>}` to a `:map` schema's properties
   when the schema is a DECLARED entity kind (`{:seon.db/entity true}`)
   with an identity-attr entry. Preserves any existing props (including
   author-declared `:seon.render/ai`, etc)."
  [v]
  (if-let [id-attr (derive-entity-id-attr v)]
    (let [head     (first v)
          body     (rest v)
          [props body] (if (and (seq body) (map? (first body)))
                         [(first body) (rest body)]
                         [{} body])
          props'   (assoc props :seon.entity/id-attr id-attr)]
      (into [head props'] body))
    v))

(defn- assert-compilable-schema!
  "Gate (Run-5 / A4): reject invalid Malli forms AT register! time so an
   agent never 'successfully' registers something the system can't use.
   `:number` (not a Malli type) used to slip into the registry and only
   explode later; `:double` IS valid but lacked datahike-bridge support
   (now added — seon.db's bridge supplies the transact-side half of the
   invariant: register! success ⇒ the attr is transactable).

   Compiles `v` against the live registry (`m/schema`); a failure throws
   a legible `:user-input` ex-info naming the key, the bad form, and the
   common storable types. NOTE: this requires any registered schema a
   form references to already be registered — which is the existing
   load-order convention (CLAUDE.md 'Schema load ordering matters')."
  [k v]
  (try
    (m/schema v)
    nil
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info
               (str "schema/register! " k ": " (pr-str v)
                    " is not a valid Malli schema (" (ex-message e) "). "
                    "Common storable attr types: :string :int :double "
                    ":float :boolean :keyword :inst :uuid :symbol "
                    ":seon.db/ref, [:enum :a :b], or a container "
                    "[:vector <type>] / [:set <type>]. (:number is NOT "
                    "a type — use :int or :double.) If the form "
                    "references another schema keyword, register that "
                    "keyword first.")
               {:seon.schema/error :seon.schema/invalid-schema
                :seon.schema/key   k
                :seon.schema/form  v
                :seon.error/kind   :user-input}
               e)))))

(defn- assert-multi-segment-namespace!
  "Gate (gym S-21 / run-paid finding, 2026-06-10): reject attrs whose
   keyword NAMESPACE is single-segment (`:workout/date`) at register!
   time, with a guiding error. Seon's rule (CLAUDE.md Data Rules):
   keyword namespaces are DOMAINS with at least two segments — a
   single-segment namespace collides with code-namespace roots and
   fragments the reuse surface (`:workout/date` landed in a paid run
   beside the established `:seon.workout/date`). The fix is a
   domain-prefixed namespace: `:kb.workout/date`,
   `:fitness.workout/date`, or reuse the existing attr.

   Same failure mode as [[assert-compilable-schema!]] — register!'s
   established error shape is a thrown `:user-input` ex-info (the eval
   pipeline surfaces it as an error-envelope value to the agent).

   CLJS (pod) only for now: the JVM substrate still carries the legacy
   single-segment `:form/*` registrations in `seon.repl` (clj) — a
   reported smell in ANOTHER lane; enforcement goes cljc once those are
   renamed. Every CLJS substrate registration is multi-segment
   (verified by grep + the full suite, 2026-06-10)."
  [k]
  (let [ns-str (namespace k)]
    (when (and ns-str (not (str/includes? ns-str ".")))
      (throw (ex-info
               (str "schema/register! " k ": single-segment keyword "
                    "namespace " (pr-str ns-str) " is not allowed. "
                    "Keyword namespaces are data DOMAINS and need ≥2 "
                    "segments — e.g. :" ns-str "/" (name k) " → :kb."
                    ns-str "/" (name k) " or :fitness." ns-str "/"
                    (name k) ". FIRST check the schema-catalog's "
                    "domain-attrs block: if an attr for this fact "
                    "already exists, reuse its EXACT keyword instead "
                    "of registering a new one.")
               {:seon.schema/error :seon.schema/single-segment-namespace
                :seon.schema/key   k
                :seon.error/kind   :user-input})))))

(defn register!
  "Register a single schema in the global registry.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns:
     The registered schema keyword.

   When `v` is a `:map` schema DECLARED as a stored entity kind via
   `{:seon.db/entity true}` in its properties (same opt-in family as
   `{:seon.db/identity true}`), the stored schema is rewritten to carry
   `{:seon.entity/id-attr <k>}` pointing at its identity-attr entry.
   This lets the renderer enumerate instances of a kind by walking the
   AEVT index for that id-attr — no per-row `:seon.entity/kind` stamp
   required. Maps WITHOUT the marker (request/response envelopes, view
   inputs) never become catalogued kinds — register! is silent about
   them. The nudge toward the marker lives where rows actually exist:
   `seon.warn/check-unmarked-entity-kinds` fires when an identity attr
   has stored datoms but no marked schema declares its kind.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])
     (register! :seon.eval [:map {:seon.db/entity true
                                  :seon.render/ai 'foo}
                            [:seon.eval/id ...] ...])  ;; →
       ;; stored with props {:seon.db/entity true
       ;;                    :seon.render/ai 'foo
       ;;                    :seon.entity/id-attr :seon.eval/id}"
  [k v]
  ;; CLJS-only until the JVM's legacy `:form/*` registrations are
  ;; renamed — see [[assert-multi-segment-namespace!]].
  #?(:cljs (assert-multi-segment-namespace! k)
     :clj  nil)
  (assert-compilable-schema! k v)
  (swap! *schemas assoc k (with-entity-id-attr v))
  k)

(defn current-keys
  "Snapshot of all currently-registered schema keywords. Used by
   detect-and-tee in eval-batch! for atom-diff schema detection
   (compare before vs after an eval to see what the form registered)."
  []
  (set (keys @*schemas)))

(defn register-all!
  "Register multiple schemas at once.

   Arguments:
     Pairs of keyword and schema definition.

   Returns:
     Set of registered schema keywords.

   Throws:
     AssertionError if odd number of arguments provided.

   Example:
     (register-all!
       ::user-id :uuid
       ::user-name [:string {:min 1}]
       ::user-email [:string {:min 5}])"
  [& kvs]
  (assert (even? (count kvs)) "register-all! requires pairs of [key schema]")
  (let [pairs (partition 2 kvs)]
    (doseq [[k v] pairs]
      (register! k v))
    (set (map first pairs))))

;;; ---------------------------------------------------------------------------
;;; Schemas-as-queryable-data — entity-schema decomposition into DB datoms.
;;;
;;; Every entity-shape `:map` schema with a derived `:seon.entity/id-attr`
;;; ALSO becomes a `:seon.schema` entity carrying:
;;;   :seon.schema/key            <kw>       (identity)
;;;   :seon.schema/required-attrs [<kw> ...] (cardinality-many keyword)
;;;   :seon.schema/id-attr        <kw>
;;;   :seon.schema/render-fn      <symbol>   (the :seon.render/ai symbol)
;;;
;;; A process-local atom caches the required-count per kind for
;;; specificity scoring in `entity-primary-kind` — derived deterministically
;;; from the registry at decomposition time.
;;;
;;; This namespace MUST NOT require seon.db (cycle: db→schema). Instead,
;;; we expose `entity-schema-tx-data` that returns the tx-data vector;
;;; the conn-owning caller (seon.client/start-agent!) transacts via
;;; seon.db/transact! after the conn is bound.
;;; ---------------------------------------------------------------------------

;; Cache of {schema-key required-attr-count} populated alongside the
;; tx-data produced by `entity-schema-tx-data`. Read by render's
;; kind-lookup for specificity scoring.
(defonce *schema-required-counts (atom {}))

(defn entity-schema-tx-data
  "Return the tx-data vector (one :db/add per required-attr, plus the
   key/id-attr/render-fn datoms) for one entity-shape `:map` schema.
   Caller transacts via `seon.db/transact!`. Side-effect: caches the
   required-count in `*schema-required-counts`. Returns `nil` when
   `k` does not refer to an entity-shape :map (no id-attr derivable)."
  [k]
  (let [v (get @*schemas k)]
    (when (and v (map-shape? v))
      (let [props       (schema-properties v)
            id-attr     (:seon.entity/id-attr props)
            render-ai   (:seon.render/ai props)
            render-html (:seon.render/html props)]
        (when id-attr
          (let [reqs (vec (remove #{id-attr} (map-required-attrs v)))
                ;; id-attr is always required — listed separately so it's
                ;; not duplicated when the entry has no {:optional true}.
                reqs (vec (distinct (cons id-attr reqs)))
                ;; FULL keyword in the tempid — (name k) alone collides
                ;; when two kinds share a name segment (:a.b/person +
                ;; :c.d/person → one tempid string → two upsert targets
                ;; → boot-fatal :transact/upsert; aria repro 2026-06-11).
                ;; Tempids are tx-local placeholders, never stored — the
                ;; row's identity is :seon.schema/key — so no migration.
                tid  (str "schema-" k)]
            (swap! *schema-required-counts assoc k (count reqs))
            (cond-> [[:db/add tid :seon.schema/key k]
                     [:db/add tid :seon.schema/id-attr id-attr]]
              render-ai   (conj [:db/add tid :seon.schema/render-fn render-ai])
              render-html (conj [:db/add tid :seon.schema/render-html-fn render-html])
              :always     (into (map (fn [r] [:db/add tid :seon.schema/required-attrs r]))
                                reqs))))))))

(defn entity-schema-keys
  "Snapshot of every registered keyword pointing at an entity-shape
   `:map` schema (i.e. one with a derived `:seon.entity/id-attr`).
   Iteration order is deterministic by sort. Used by
   `seon.client/start-agent!` to seed `:seon.schema` entities at boot."
  []
  (->> @*schemas
       (keep (fn [[k v]]
               (when (and (map-shape? v) (:seon.entity/id-attr (schema-properties v)))
                 k)))
       sort
       vec))

(defn all-entity-schemas-tx-data
  "Tx-data vector for every currently-registered entity-shape :map
   schema. Concatenates `entity-schema-tx-data` over `entity-schema-keys`.
   Idempotent — identity-attr upsert on `:seon.schema/key` replaces
   prior decompositions in place."
  []
  (into [] (mapcat entity-schema-tx-data) (entity-schema-keys)))

(defn schema-required-count
  "Look up the cached required-attr count for `k`. Returns nil if `k`
   was never decomposed (e.g. not an entity-shape :map). Populated as
   a side-effect of `entity-schema-tx-data`."
  [k]
  (get @*schema-required-counts k))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "Return a map of all registered domain schemas.

   Does not include Malli's built-in schemas."
  []
  @*schemas)

(defn registered?
  "Check if a schema keyword is registered."
  [k]
  (contains? @*schemas k))

(defn schema-definition
  "Get the raw definition for a registered schema.

   Returns nil if not registered."
  [k]
  (get @*schemas k))

(defn schemas-in-namespace
  "Get all schemas registered under a specific namespace.

   Arguments:
     ns-name - String namespace name (e.g., \"seon.ai.gemini\")

   Returns:
     Map of {keyword definition} for schemas in that namespace."
  [ns-name]
  (into {}
        (filter (fn [[k _]]
                  (= (namespace k) ns-name))
                @*schemas)))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all!
  "Clear all registered schemas. USE WITH CAUTION - only for testing."
  []
  (reset! *schemas {}))

(comment
  ;; REPL exploration

  ;; Register a test schema
  (register! ::test-schema [:string {:min 1}])

  ;; Check what's registered
  (registered-schemas)
  (registered? ::test-schema)

  ;; Get schemas for a namespace
  (schemas-in-namespace "seon.schema")

  ;; Validate data against registered schema
  (m/validate ::test-schema "hello")
  (m/validate ::test-schema "")  ; fails - min 1

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::test-schema)

  nil)
