(ns seon.schema
  "Global Malli schema registry for Seon — the SINGLE SOURCE OF TRUTH for
   every attribute schema.

   Namespaces register their schemas here with `register!`, making them
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
            [seon.schema.internal :as internal]))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

;; All registered domain schemas. `defonce` survives namespace reloads.
(defonce ^:private *schemas (atom {}))

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
  "(Re)point malli's global default registry at [[seon-registry]].

   Combines malli's default schemas + seon's mutable `*schemas`. Idempotent;
   registered schemas live in `*schemas` and survive the relink.

   Re-callable, not just load-time init: `malli.core` runs
   `(mr/set-default-registry! …)` as a TOP-LEVEL load side effect. In the
   CLJS pod, the bootstrap self-host compiler can re-execute it (an agent
   `(require '[malli.core :as m])` goog.globalEvals the bundle's
   `malli.core$macros.js`), stomping the registry with a default-only
   snapshot and severing every `:seon.*` schema process-wide
   (`:malli.core/invalid-schema`). `seon.eval`'s bootstrap `:load` wrapper
   calls this after every load to re-assert the invariant; in CLJS the
   stomp-guard watch installed here makes the invariant hold even BETWEEN
   loads — see the watch comment below.

   CLOSE THE STOMP WINDOW (CLJS pod). A foreign `(mr/set-default-registry!
   …)` — the bootstrap load of `malli.core$macros.js` re-running
   malli.core's top-level registry init — `(reset! malli.registry/registry*
   …)`s seon's schemas away. A watch on that atom re-asserts
   [[seon-registry]] the INSTANT it is replaced: ClojureScript runs
   `add-watch` fns SYNCHRONOUSLY inside the stomping `reset!`, before any
   other form observes the severed registry — so there is no window to be
   resilient to, no severed state to heal. The `identical?` guard makes the
   re-assert's own `reset!` a no-op (one bounce, no recursion). Installed
   HERE (not a top-level `defonce`) and keyed by keyword so it is idempotent
   AND re-attaches to the live `registry*` on every relink — a stale watch
   can never be orphaned onto a replaced atom. This is the structural form
   of the per-load relink: the relink covers boot ordering, the watch covers
   any stomp from a path that never routes through the `:load` wrapper."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (mr/set-default-registry! seon-registry)
  #?(:cljs
     (add-watch malli.registry/registry* ::seon-stomp-guard
       (fn [_ _ _ new-registry]
         (when-not (identical? new-registry seon-registry)
           (mr/set-default-registry! seon-registry))))
     :clj nil)
  true)

;; Initialize the global registry once at load time.
(defonce ^:private _registry-init (relink-registry!))

;; :inst as a keyword type (Malli only provides the inst? predicate), for
;; consistency with :string, :int, etc.
(defonce ^:private _inst-type
  (swap! *schemas assoc :inst (m/-simple-schema {:type :inst :pred inst?})))

;; :seon.flow/dynamic — a wire-protocol field validated dynamically at the
;; message boundary (validate-fn-args!/value!/payload! in seon.flow.msg).
;; Used for ::args, ::value, ::payload, whose type depends on the target;
;; static schema can only assert non-nil.
(defonce ^:private _dynamic-type
  (swap! *schemas assoc :seon.flow/dynamic
         (m/-simple-schema
          {:type :seon.flow/dynamic
           :pred some?
           :type-properties {:gen/schema [:or :int :string :keyword :boolean
                                          [:vector :int] [:map-of :keyword :string]]
                             :gen/fmap identity}})))

;; :seon.db/namespace — the entity-namespace stamp seon.db/transact! attaches
;; when routing through the datahike flow. Values are db-name keywords.
;; Registered here so the transact! validation gate treats it as known.
(defonce ^:private _db-namespace-type
  (swap! *schemas assoc :seon.db/namespace :keyword))

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
;; these (db.cljs's `::conn`/`::tx-data` slot-schema pattern). `:seon.schema/form`
;; (a Malli schema DEFINITION) is a recursive, heterogeneous structure —
;; genuinely opaque, hence `:any` (the documented third-party-shape exception).
(defonce ^:private _registry-key-type
  (swap! *schemas assoc :seon.schema/registry-key :keyword))
(defonce ^:private _form-type
  (swap! *schemas assoc :seon.schema/form :any))
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
   PUBLIC: the single identity-attr predicate — callers (the inventory
   section, etc.) reuse it rather than re-deriving the props lookup."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [attr-key]
  (internal/identity-attr? @*schemas attr-key))

(defn enum-members
  "Members of a registered `:enum` attr schema, or an empty vector.

   Empty when the attr is not an enum (absence = empty, never nil). Reads the schema
   form directly — NO db query. PUBLIC: low-cardinality value surfaces
   (the inventory section) reuse it. Members are Malli-form contents
   (keywords/strings/ints) — a third-party-structure boundary, hence `:any`."
  {:malli/schema [:=> [:cat :keyword] [:vector :any]]}
  [attr-key]
  (internal/enum-members (get @*schemas attr-key)))

;;; --- register! self-tee hook -----------------------------------------------
;;; The registry is in-memory and dies with the process. Durability for
;;; RUNTIME registrations (agent evals, REPL-scope register! via MCP eval)
;;; comes from a `:seon.schema` program-graph row whose `:seon.schema/source`
;;; is the replayable `(seon.schema/register! …)` call — the same row shape
;;; detect-and-tee writes, identity-upsert on `:seon.schema/key`.
;;;
;;; This ns must not require seon.db (cycle: db→schema), so the tee is a
;;; late-bound hook: `seon.eval` installs [[set-tee-fn!]] at load. The
;;; installed fn is conn-gated (no bound `seon.db/*conn*` → no-op), so the
;;; ~500 boot-time register! calls from compiled-ns loads run untouched;
;;; boot indexing (seon.client/index-schemas) owns the core rows.

(defonce ^:private !tee-fn (atom nil))

(defonce !last-tee
  ;; The most recent tee invocation's return (a Promise in CLJS, nil when
  ;; the tee skipped). register! stays synchronous — tests and live proofs
  ;; `await` this to observe the row land deterministically.
  (atom nil))

(defn set-tee-fn!
  "Install the registration self-tee hook, called once at load.

   The conn-owning side (seon.eval) installs it. `f` is `(fn [k form] …)`;
   it must never throw (a tee/durability failure must not fail the in-memory
   registration). Idempotent."
  {:malli/schema [:=> [:cat fn?] :nil]}
  [f]
  (reset! !tee-fn f)
  nil)

(defn register!
  "Define a new attribute so facts using it can be saved and queried.

   Registers a single schema in the global registry.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns the registered keyword `k`.

   When `v` is a `:map` schema DECLARED a stored entity kind via
   `{:seon.db/entity true}` in its properties (same opt-in family as
   `{:seon.db/identity true}`), the stored schema is rewritten to carry
   `{:seon.entity/id-attr <k>}` pointing at its identity-attr entry — so
   the renderer enumerates instances by walking the AEVT index for that
   id-attr, no per-row `:seon.entity/kind` stamp. Maps WITHOUT the marker
   (request/response envelopes, view inputs) never become catalogued kinds;
   register! is silent about them. The nudge toward the marker lives where
   rows exist: `seon.warn/check-unmarked-entity-kinds`.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])
     (register! :seon.eval [:map {:seon.db/entity true
                                  :seon.render/ai 'foo}
                            [:seon.eval/id ...] ...])  ;; →
       ;; stored props {:seon.db/entity true
       ;;               :seon.render/ai 'foo
       ;;               :seon.entity/id-attr :seon.eval/id}"
  {:malli/schema [:=> [:catn [::registry-key ::registry-key] [::form ::form]] ::registry-key]}
  [k v]
  ;; CLJS-only until the JVM's legacy `:form/*` registrations are renamed.
  #?(:cljs (internal/assert-multi-segment-namespace! k)
     :clj  nil)
  (internal/assert-compilable-schema! k v)
  (internal/assert-non-nilable-value-schema! @*schemas k v)
  (swap! *schemas assoc k (internal/with-entity-id-attr @*schemas v))
  ;; Self-tee (durability): hand the ORIGINAL form to the hook — replay
  ;; re-derives :seon.entity/id-attr by re-running register!. No hook
  ;; installed (JVM, pure-registry, boot ns-loads before seon.eval) →
  ;; registers exactly as before, no tee, no error.
  (when-some [f @!tee-fn]
    (reset! !last-tee (f k v)))
  k)

(defn current-keys
  "Snapshot of all currently-registered schema keywords.

   Used by detect-and-tee in eval-batch! for atom-diff schema detection (before vs
   after an eval reveals what the form registered)."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (set (keys @*schemas)))

(defn discard-registrations!
  "Drop `ks` from the in-memory registry.

   The schema analog of
   `analyzer-info/remove-phantom-defs!`. A FAILED eval that ran
   `register!` must define NOTHING: the DB self-tee already DEFERS to
   the gated detect-and-tee (so nothing persisted), and this removes the
   in-session registry entries too, so a re-eval of the fixed form
   registers cleanly. `ks` is the keys NEWLY registered during the
   failed eval (post-eval `current-keys` minus the pre-eval snapshot),
   so a pre-existing key is never in `ks`. Returns nil."
  {:malli/schema [:=> [:catn [::discarded-keys [:set :keyword]]] :nil]}
  [ks]
  (swap! *schemas #(apply dissoc % ks))
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
  (let [v (get @*schemas k)]
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
       (keep (fn [[k v]]
               (when (and (internal/map-shape? v)
                          (:seon.entity/id-attr (internal/schema-properties v)))
                 k)))
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

   `ns-name` is a string, e.g. \"seon.ai.gemini\"."
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
