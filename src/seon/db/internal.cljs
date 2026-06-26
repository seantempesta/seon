(ns seon.db.internal
  "Plumbing behind `seon.db` — validation gate, invocation normalization,
   error envelopes, the Malli→datahike schema bridge, tx-meta machinery,
   AsyncLocalStorage core, and listener wrapping.

   This namespace is INTERNAL: it is never rendered into agent context
   (the `*.internal` ns name IS the filter — context-v3 convention,
   2026-06-10). Agents call the public face in `seon.db`; nothing here
   is part of the taught surface. Everything is a plain `defn` — the ns
   boundary is the privacy boundary, so `seon.db` (and tests) can call
   across without `#'` gymnastics.

   All map keys remain in the `:seon.db/*` namespace (via `:as-alias`):
   the keyword namespace tracks the OWNING DATA namespace (`seon.db`),
   not the file the code happens to live in."
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [datahike.db.interface :as dbi]
    [malli.core :as m]
    [seon.db :as-alias db]
    [seon.error :as error]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; AsyncLocalStorage core — fiber-local context for tx-meta + agent-id.
;;
;; We DO NOT use a CLJS `^:dynamic` Var here, even though that's the
;; idiomatic Clojure spelling. CLJS `binding` macroexpands to
;; `(set! var :new)` + `(try … (finally (set! var orig)))` against ONE
;; global slot — it survives single-binder cases but silently clobbers
;; under overlapping awaits when two `^:async` fns each bind it (see
;; `research/impl-finding-tx-context-promise-2026-05-22.md` Probe 13).
;; v1 supports concurrent agents in one pod, so a fiber-local primitive
;; is required.
;;
;; `node:async_hooks/AsyncLocalStorage` IS fiber-local: V8 instruments
;; the async context propagation at the engine level so a `.run`-scoped
;; store survives across any `await`s (real timers, microtasks,
;; rejections, nested ^:async calls) AND does not interfere with
;; concurrent `.run`s in other fibers. Probe 14 verified this under the
;; same adversarial interleaving that broke Probe 13.
;;
;; The require is at top-level so a pod missing `node:async_hooks`
;; fails loudly at ns load instead of silently at first transact.
;;
;; `agent-id-als` is distinct from `als-instance` (tx-context) so non-DB
;; code paths (inspectors, section fns, web handlers) can read the active
;; agent-id without depending on tx-context machinery.
;; ---------------------------------------------------------------------------

(defonce als-instance
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defonce agent-id-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn current-tx-context
  "Raw ALS read for the tx-context store. nil outside a scope.
   `seon.db/current-tx-context` is the public face."
  []
  (let [store (.getStore als-instance)]
    ;; Outside a `.run` scope the JS getStore returns undefined; CLJS
    ;; treats that as nil in `when`-position. Be explicit anyway —
    ;; callers downstream `merge` on the result.
    (when (some? store) store)))

(defn current-agent-id
  "Raw ALS read for the agent-id store. nil outside a scope.
   `seon.db/current-agent-id` is the public face."
  []
  (let [store (.getStore agent-id-als)]
    (when (some? store) store)))

(defn run-with-tx-context
  "Impl of `seon.db/with-tx-context`. Nested calls MERGE: `ctx-map`
   merges on top of any already-active context. Returns whatever `f`
   returns — including a Promise, in which case the context propagates
   across `await` points inside `f`."
  [ctx-map f]
  (let [current (current-tx-context)
        merged  (merge current ctx-map)]
    (.run als-instance merged f)))

(defn run-with-agent
  "Impl of `seon.db/with-agent`. Inside `f`, `(current-agent-id)`
   returns `agent-id`; the inner scope wins under nesting."
  [agent-id f]
  (.run agent-id-als agent-id f))

;; ---------------------------------------------------------------------------
;; Tx-meta attrs (v1.md §2.3) — the causality bundle attached to every tx.
;; The attr keywords live in `:seon.db/*` and their Malli registrations
;; happen in `seon.db` at namespace load (so they're registered before
;; the first transact). This set drives `assert-preconditions!` and the
;; bootstrap-schema derivation.
;; ---------------------------------------------------------------------------

(def tx-meta-attrs
  "Set of attr keywords the tx-meta auto-merge writes. Used by
   `assert-preconditions!` to confirm registration. Update together with
   the `seon.db` registrations when adding new tx-meta attrs."
  #{::db/agent-id ::db/session-id ::db/turn-id ::db/eval-id
    ::db/origin ::db/replay? ::db/resume-marker?})

;; ---------------------------------------------------------------------------
;; Malli → datahike schema bridge.
;;
;; Datahike requires every attribute have a declared `:db/valueType` +
;; `:db/cardinality` before its first transact. seon.schema (Malli) is
;; our source of truth for attr shape; this bridge derives the datahike
;; declaration from the Malli registration so the two layers can't
;; drift.
;;
;; Currently handles the type surface v1 needs (string, keyword,
;; boolean, inst, int, uuid, enum, vector/set cardinality, ref,
;; component refs, identity attrs). Anything else throws — caller
;; decides whether to hand-write the entry or extend the bridge.
;; ---------------------------------------------------------------------------

(defn form-properties
  "Extract the Malli properties map from a schema form, or nil. Returns
   the first map-typed child.

   Malli's canonical placement is index 1 (after the head):
   `[:string {:min 12} …]` → `{:min 12}`. But some authors put bridge
   markers after the child schema for readability:
   `[:vector :seon.db/ref {:seon.db/component true}]`. The bridge
   accepts either — there's at most one props map per schema form."
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn form-children
  "The non-property children of a schema form. For
   `[:vector {:min 1} :int]` returns `[:int]`; for `[:vector :int]` also
   `[:int]`; for `:string` returns `[]`."
  [form]
  (if (vector? form)
    (let [body (rest form)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (vec body))
    []))

(defn resolve-malli-form
  "Follow a Malli schema form through seon.schema-registered keyword
   indirections until it reaches a non-keyword form OR a keyword whose
   resolved schema is a built-in `IntoSchema` (not a raw Malli form).

   Malli built-ins like `:inst`/`:string`/`:int` ARE present in the
   registry (they have to be — Malli looks them up too), but their
   `schema-definition` returns an `IntoSchema` instance rather than a
   reducible Malli form (keyword or vector). The bridge maps the
   built-in heads directly via `form->datahike-value-type`; recursing
   into the IntoSchema would lose the head and break the mapping.

   So: only recurse when the resolved definition is itself a Malli
   form (keyword or vector). Anything else (IntoSchema, compiled
   schema) means we've hit a built-in — return the form unchanged.

   `:seon.db/ref` is special: even though it's registered, the bridge
   maps it directly to `:db.type/ref` rather than following its
   `[:or ...]` registration (which describes valid value shapes, not
   the underlying datahike type)."
  [form]
  (cond
    (= :seon.db/ref form)
    :seon.db/ref

    (and (keyword? form) (schema/registered? form))
    (let [def (schema/schema-definition form)]
      (if (or (keyword? def) (vector? def))
        (resolve-malli-form def)
        form))

    :else
    form))

(def malli-type->datahike-type
  "Mapping from Malli base types to datahike `:db.type/*` keywords.
   Lookup is by the *head* of a resolved Malli form (or the form
   itself when it's a bare keyword)."
  {:string  :db.type/string
   :int     :db.type/long
   :double  :db.type/double
   :float   :db.type/float
   :keyword :db.type/keyword
   :boolean :db.type/boolean
   :inst    :db.type/instant
   :uuid    :db.type/uuid
   :symbol  :db.type/symbol})

(def bridge-supported-types
  "Human-readable list of the attr types the Malli→datahike bridge can
   store. Surfaced in the ensure-datahike-attrs! error so an agent that
   registered an unstorable type sees exactly what IS storable."
  (str ":string :int :double :float :boolean :keyword :inst :uuid "
       ":symbol :seon.db/ref, [:enum :a :b], or a container "
       "[:vector|:set|:sequential <one of those>]"))

(defn form-head
  "The head of a Malli form. For `[:string {:min 1}]` returns `:string`;
   for `:boolean` returns `:boolean`; for `[:enum :a :b]` returns
   `:enum`; for `:seon.db/ref` returns itself (special case)."
  [form]
  (cond
    (vector? form)  (first form)
    :else           form))

(defn form->datahike-value-type
  "Given a resolved (no more keyword refs) Malli form, return the
   matching datahike `:db.type/*` keyword. Throws on unmappable
   shapes — caller extends the bridge or hand-writes the entry."
  [resolved-form]
  (let [head (form-head resolved-form)]
    (cond
      (= head :seon.db/ref)
      :db.type/ref

      (= head :enum)
      ;; All current v1 enum schemas hold keyword values
      ;; (`:user`/`:agent`/`:system`/etc). Verify before mapping so a
      ;; non-keyword enum (e.g. enum of strings) doesn't silently land
      ;; as :keyword. If we add string-enums later, branch here.
      (if (every? keyword? (form-children resolved-form))
        :db.type/keyword
        (throw (ex-info (str "Malli :enum with non-keyword values not "
                             "supported by the v1 bridge: "
                             (pr-str resolved-form))
                        {::db/error :seon.db/unbridgeable-malli-form
                         ::db/form resolved-form})))

      ;; [:and base extra-constraints] — bridge on the base.
      (= head :and)
      (form->datahike-value-type (resolve-malli-form (first (form-children resolved-form))))

      ;; [:or alt-1 alt-2] — when every alt maps to ONE datahike type,
      ;; bridge on it. MIXED-type :or (e.g. the relaxed render slots
      ;; `[:or :string :symbol]` / `[:or :symbol <hiccup>]`, self-context
      ;; spec 2026-06-10) stores as `:db.type/string` carrying the
      ;; pr-str'd EDN of the value — datahike's typed schema cannot hold
      ;; a scalar union, so the bridge owns ONE representation:
      ;; `transact!*` encodes such attrs ([[encode-edn-slot-values]]),
      ;; `seon.db/decode-edn-value` is the read-side inverse. Unmappable
      ;; alts (`[:fn …]` predicates like the hiccup shape) count as
      ;; mixed.
      (= head :or)
      (let [child-types (set (map #(try (form->datahike-value-type
                                          (resolve-malli-form %))
                                        (catch :default _ ::unmappable))
                                  (form-children resolved-form)))]
        (if (and (= 1 (count child-types))
                 (not (contains? child-types ::unmappable)))
          (first child-types)
          :db.type/string))

      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info (str "Cannot map Malli type to datahike type: "
                               (pr-str resolved-form))
                          {::db/error :seon.db/unbridgeable-malli-form
                           ::db/form resolved-form
                           ::db/head head}))))))

(defn form->cardinality
  "`:db.cardinality/many` if the form is a vector/set/sequential
   container; `:db.cardinality/one` otherwise. The CHILD is the value
   type — caller resolves it separately."
  [form]
  (if (and (vector? form)
           (#{:vector :sequential :set} (first form)))
    :db.cardinality/many
    :db.cardinality/one))

(defn form->child-form
  "For container forms (`:vector`/`:set`/`:sequential`), the child
   value form. For scalar forms, returns the form unchanged."
  [form]
  (if (and (vector? form)
           (#{:vector :sequential :set} (first form)))
    (first (form-children form))
    form))

(defn malli->datahike-attr
  "Translate a single attr keyword from the seon.schema Malli registry
   into a datahike attribute declaration map (the shape datahike's
   bootstrap schema vector wants).

   Returns:
     {:db/ident       <attr-key>
      :db/valueType   <:db.type/*>
      :db/cardinality <:db.cardinality/one|many>
      :db/unique      <optional :db.unique/identity>
      :db/isComponent <optional true>}

   Properties on the Malli registration drive the optional fields:
     - `{:seon.db/identity true}` → `:db/unique :db.unique/identity`
     - `{:seon.db/component true}` → `:db/isComponent true`

   Throws on unregistered attrs or Malli forms the bridge can't map."
  [attr-key]
  (let [raw-form    (or (schema/schema-definition attr-key)
                        (throw (ex-info (str "Attr not registered in seon.schema: "
                                             (pr-str attr-key))
                                        {::db/error :seon.db/unregistered-attr
                                         ::db/attr attr-key})))
        props       (form-properties raw-form)
        outer-form  raw-form
        ;; For vectors/sets, the child is the value form; for scalars,
        ;; same as outer.
        value-form  (-> outer-form
                        resolve-malli-form
                        form->child-form
                        resolve-malli-form)
        value-type  (form->datahike-value-type value-form)
        ;; A `:db.secondary/only true` attr is a single tuple value (the
        ;; whole vector lives ONLY in the secondary/vector index, never in
        ;; the primary datahike indices). The vector wrapper that would
        ;; otherwise read as cardinality/many is a tuple here, NOT a
        ;; cardinality-many scalar. Keyed off the `:db.secondary/only`
        ;; property + a vector-of-FLOAT shape → tuple, cardinality/one.
        ;; (Locked use: `:seon/embedding`, embeddings-fn-retrieval PRD.)
        ;; This MIRRORS the CLJ bridge `seon.db.datahike.schema/schema->attr-partial`
        ;; (the `:vector` float-inner branch) — keep the two lanes in lockstep.
        float-inner? (let [vt (cond
                                (keyword? value-form) value-form
                                (vector? value-form)  (first value-form)
                                :else                 value-form)]
                       (contains? #{:float :double 'float? 'double?} vt))
        secondary-only? (boolean (:db.secondary/only props))
        _ (when (and secondary-only? (not float-inner?))
            (throw (ex-info
                    (str ":db.secondary/only attr " attr-key
                         " must be a vector of :float/:double; got value form "
                         (pr-str value-form))
                    {::db/error :seon.db/secondary-only-non-float
                     ::db/attr attr-key
                     ::db/value-form value-form})))
        cardinality (if secondary-only?
                      :db.cardinality/one
                      (form->cardinality (resolve-malli-form outer-form)))]
    (cond-> {:db/ident       attr-key
             :db/valueType   value-type
             :db/cardinality cardinality}
      secondary-only?            (assoc :db/valueType :db.type/tuple
                                        :db.secondary/only true)
      (:seon.db/identity props)  (assoc :db/unique :db.unique/identity)
      (:seon.db/component props) (assoc :db/isComponent true))))

(defn malli->datahike-schema
  "Vector form of [[malli->datahike-attr]]. Pass a sequence of attr
   keywords; get a vector of datahike-ready attr declarations.

   The vector ordering preserves the input ordering — when a
   datahike conn boot-transacts the result, attrs land in the order
   given (matters for forward references between schema entities)."
  [attr-keys]
  (mapv malli->datahike-attr attr-keys))

(defn edn-encoded-attr?
  "True when `attr`'s registered Malli form is a MIXED-type `:or` — the
   shapes the bridge stores as `:db.type/string` carrying pr-str'd EDN
   (see the `:or` branch of [[form->datahike-value-type]]). The two
   live cases are the relaxed render slots `:seon.render/ai`
   `[:or :string :symbol]` and `:seon.render/html`
   `[:or :symbol <hiccup>]` (self-context spec 2026-06-10)."
  [attr]
  (boolean
    (when (and (keyword? attr) (schema/registered? attr))
      (let [resolved (resolve-malli-form (schema/schema-definition attr))]
        (when (and (vector? resolved) (= :or (first resolved)))
          (let [child-types (set (map #(try (form->datahike-value-type
                                              (resolve-malli-form %))
                                            (catch :default _ ::unmappable))
                                      (form-children resolved)))]
            (or (> (count child-types) 1)
                (contains? child-types ::unmappable))))))))

(defn encode-edn-slot-values
  "Encode the values of EDN-string-bridged attrs ([[edn-encoded-attr?]])
   in `tx-data` to their pr-str'd form — the write half of the mixed-:or
   representation (`seon.db/decode-edn-value` is the read half). Walks
   map entities (including nested component maps) and `[:db/add e a v]`
   vector forms. ALWAYS pr-str's (strings included — `\"x\"` stores as
   `\"\\\"x\\\"\"`), so decode by `read-string` is unambiguous. Callers
   re-transacting a PULLED value must decode first (the section verbs
   do) — double-encoding is on them."
  [tx-data]
  (letfn [(encode-map [m]
            (reduce-kv
              (fn [acc k v]
                (assoc acc k
                       (cond
                         (edn-encoded-attr? k) (pr-str v)
                         (map? v)              (encode-map v)
                         (and (vector? v) (some map? v))
                         (mapv #(if (map? %) (encode-map %) %) v)
                         :else v)))
              {} m))]
    (mapv (fn [form]
            (cond
              (map? form) (encode-map form)
              (and (vector? form)
                   (= :db/add (first form))
                   (edn-encoded-attr? (nth form 2 nil)))
              (update form 3 pr-str)
              :else form))
          tx-data)))

(defn tx-meta-datahike-schema
  "The datahike schema entries for the 7 tx-meta attrs (v1.md §2.3).
   Built by running `tx-meta-attrs` through the bridge. Called by
   `seon.client/agent-bootstrap-schema` so the entries are derived
   from Malli, never hand-written."
  []
  (malli->datahike-schema (sort tx-meta-attrs)))

;; ---------------------------------------------------------------------------
;; Validation gate — mirrors `seon.db/transact!` 60–135 on JVM byte-for-byte
;; so the eventual .cljc merge is mechanical. Any change here must mirror
;; into the JVM file (and vice versa) until convergence.
;; ---------------------------------------------------------------------------

(defn system-attr?
  "True for datahike's own system attributes — the `:db/*` family AND the
   `:db.*` sub-namespaces (`:db.secondary/*`, `:db.entity/*`, `:db.valid/*`,
   …). These drive datahike's schema/secondary-index layer and are NOT
   validated against the seon Malli registry (see datahike's
   `schema.cljc` ::schema-attribute / ::secondary-index-attribute sets)."
  [k]
  (let [n (and (keyword? k) (namespace k))]
    (boolean (and n (or (= n "db") (str/starts-with? n "db."))))))

(declare ref-attr-arity)

(defn ref-slot?
  "True when `k` is a registered non-system attr whose Malli schema
   describes a ref slot (single or many — see [[ref-attr-arity]]).
   The tx-data walkers ([[extract-tx-attrs]],
   [[normalize-entity-ref-keys]]) recurse ONLY into ref-slot values:
   nested entity maps legally appear only under ref attrs, and maps
   under non-ref attrs (e.g. hiccup attribute maps inside the
   EDN-encoded render slots) must NOT be mistaken for entities."
  [k]
  (and (keyword? k)
       (not (system-attr? k))
       (schema/registered? k)
       (some? (ref-attr-arity (schema/schema-definition k)))))

(defn extract-tx-attrs
  "Walk tx-data and collect every attribute keyword that appears,
   INCLUDING attrs that only occur in NESTED entity maps (datahike's
   nested-map shorthand under ref attrs) — those reach the store too,
   so the runtime auto-installer ([[ensure-datahike-attrs!]]) must see
   them; collecting only top-level keys made register!-then-transact of
   a nested-only attr die on datahike's \"not defined in current
   schema\" (fix-everything A1, 2026-06-11). Handles entity-map forms
   (`{:attr v ...}`, recursing into [[ref-slot?]] values) and vector
   tuple forms (`[:db/add e a v]`, `[:db/retract e a v]`). Tempids and
   metadata not keyed by a true attribute are filtered out (lookup-ref
   tuples are walked but contribute no keys — only entity-map KEYS are
   collected)."
  [tx-data]
  (letfn [(collect-entity [acc ent]
            (reduce-kv
              (fn [acc k v]
                (cond-> (conj acc k)
                  (ref-slot? k) (collect-ref-value v)))
              acc ent))
          (collect-ref-value [acc v]
            (cond
              (map? v)        (collect-entity acc v)
              (sequential? v) (reduce collect-ref-value acc v)
              :else           acc))]
    (reduce (fn [acc datum]
              (cond
                (map? datum)
                (collect-entity acc datum)

                (and (vector? datum) (>= (count datum) 3))
                (let [a   (nth datum 2)
                      acc (conj acc a)]
                  (cond-> acc
                    (ref-slot? a) (collect-ref-value (nth datum 3 nil))))

                :else acc))
            #{}
            tx-data)))

(defn validate-attrs!
  "Ensure every non-`:db/*` attribute appearing in `tx-data` is registered
   in `seon.schema`. Throws `ex-info` with `{:unregistered [...]}` listing
   the offenders. Caller is expected to register schemas first."
  [attrs]
  (let [domain-attrs (remove system-attr? attrs)
        unregistered (into [] (remove schema/registered?) domain-attrs)]
    (when (seq unregistered)
      (throw (ex-info (str "Unregistered attributes in transaction: "
                           (pr-str unregistered)
                           ". Register them with seon.schema/register! first.")
                      {::db/error            :seon.db/unregistered-attrs
                       ::db/unregistered     unregistered
                       :seon.error/kind      :user-input})))))

(defn truncate-value
  "Truncate a value's `pr-str` representation to 100 chars for error
   messages — keeps error payloads readable when a malformed value is
   large (e.g. a stringified pull pattern)."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 100)
      (str (subs s 0 97) "...")
      s)))

(defn normalize-entity-ref-keys
  "Rewrite the taught entity-identity shorthand — an entity map keyed by
   `:seon.db/ref` (`{:seon.db/ref [:seon.agent/id \"…\"] :attr v}`, the
   transact-onto-your-entity pattern) — into datahike's native `:db/id`
   slot, recursively through nested entity maps and tx vectors.

   `:seon.db/ref` is a registered Malli SHAPE, not an installed datahike
   attribute. Left in the entity map it reaches the store as a junk attr
   — the wire store rejects the
   whole tx (\"Bad entity attribute :seon.db/ref …\"), and a conn that
   somehow had it installed would silently assert a junk datom on a
   junk entity. Datahike already resolves eids / tempids / lookup-refs
   natively in the `:db/id` slot, so the normalizer's whole job is the
   rename + DISSOC of `:seon.db/ref`.

   Throws `:user-input` ex-info (converted to the failure envelope by
   `transact!*`'s catch) when the ref value isn't a valid
   `:seon.db/ref` shape, or when the map carries BOTH `:db/id` and a
   conflicting `:seon.db/ref`."
  [tx-data]
  (letfn [(norm-entity [x]
            ;; Recurse ONLY into ref-slot values (see [[ref-slot?]]) —
            ;; maps under non-ref attrs (e.g. hiccup attribute maps in
            ;; the EDN-encoded render slots) are opaque VALUES, not
            ;; entities.
            (let [ent (reduce-kv (fn [acc k v]
                                   (assoc acc k (if (ref-slot? k)
                                                  (norm-ref-value v)
                                                  v)))
                                 {} x)]
              (if-not (contains? ent ::db/ref)
                ent
                (let [r (get ent ::db/ref)]
                  (when-not (m/validate :seon.db/ref r)
                    (throw (ex-info
                             (str "seon.db/transact!: `:seon.db/ref` names "
                                  "the entity a map transacts ONTO — its "
                                  "value must be an eid, a string tempid, "
                                  "or a lookup-ref like [:seon.agent/id "
                                  "\"…\"]. Got: " (truncate-value r))
                             {::db/error             :seon.db/invalid-value
                              ::db/attr              ::db/ref
                              ::db/actual-value      r
                              ::db/malli-explanation (m/explain :seon.db/ref r)
                              :seon.error/kind       :user-input})))
                  (when (and (contains? ent :db/id)
                             (not= (:db/id ent) r))
                    (throw (ex-info
                             (str "seon.db/transact!: entity map carries "
                                  "BOTH :db/id " (truncate-value (:db/id ent))
                                  " and :seon.db/ref " (truncate-value r)
                                  " — they name different entities. Keep "
                                  "exactly one.")
                             {::db/error        :seon.db/conflicting-entity-refs
                              ::db/actual-value {:db/id  (:db/id ent)
                                                 ::db/ref r}
                              :seon.error/kind  :user-input})))
                  (-> ent (dissoc ::db/ref) (assoc :db/id r))))))
          (norm-ref-value [v]
            (cond
              (map? v)        (norm-entity v)
              (sequential? v) (mapv norm-ref-value v)
              :else           v))]
    (mapv (fn [datum]
            (cond
              (map? datum)
              (norm-entity datum)

              ;; `[:db/add e a v]` — normalize a nested entity map in
              ;; the value slot when `a` is a ref slot.
              (and (vector? datum)
                   (>= (count datum) 4)
                   (ref-slot? (nth datum 2)))
              (update datum 3 norm-ref-value)

              :else datum))
          tx-data)))

;; ---------------------------------------------------------------------------
;; Identity-ident symbol→keyword coercion (#48).
;;
;; The documented/prompted "persist a tool" path leads an agent to write a
;; namespace identity as a QUOTED SYMBOL — `{:seon.ns/name 'my.agent.foo …}`
;; — because a namespace IS a symbol everywhere else in Clojure. But the
;; attr's registered schema is `[:keyword {:seon.db/identity true}]`, so the
;; natural shape fails the value-validation gate ([[validate-values!]]) and
;; the fn is defined-in-session but NEVER persisted. The system's own tee
;; (eval.cljs) always writes the keyword `(keyword (str ns))`; only the
;; hand-written agent path hits the symbol footgun.
;;
;; FIX (precise scope, per the asks triage 2026-06-22): coerce symbol→keyword
;; ONLY for KEYWORD-TYPED IDENTITY idents — the `:seon.ns/name`-class attrs.
;; A symbol arriving at a keyword-typed identity slot is unambiguously this
;; footgun (there is no legitimate symbol value for a keyword identity attr),
;; so the coercion is conservative AND data-driven: it fires for whatever
;; keyword-typed identity attrs the registry holds (today `:seon.ns/name`
;; and `:seon.schema/key`), never for string-identity attrs (`:seon.fn/sym`),
;; ref-identity attrs (`:seon.agent/id` → `:seon.db/id`), or non-identity
;; keyword attrs (`:seon.ctx/name`). The KEYWORD stays the stored canonical
;; value — we coerce on the way IN and never loosen the stored schema — so
;; datahike lookup-refs / identity resolution keep working.
;;
;; The coercion mirrors [[normalize-entity-ref-keys]]: same entity-map +
;; ref-slot recursion + `[:db/add|:db/retract e a v]` tuple handling, and it
;; ALSO rewrites lookup-ref tuples `[ident-attr 'sym]` (in `:db/id`, ref
;; values, and tuple e-/v-slots) since a symbol there fails `:seon.db/ref`
;; the same way. Runs BEFORE the ref-key normalizer + the validation gate.
;; ---------------------------------------------------------------------------

(defn keyword-identity-ident?
  "True when `attr` is a registered, non-system IDENTITY attr whose
   resolved value type is `:keyword` — the `:seon.ns/name`-class idents
   an agent naturally writes as a quoted symbol (#48). These are the ONLY
   attrs [[coerce-identity-symbol-idents]] coerces symbol→keyword for."
  [attr]
  (and (keyword? attr)
       (not (system-attr? attr))
       (schema/registered? attr)
       (schema/identity-attr? attr)
       (let [base (-> (schema/schema-definition attr)
                      resolve-malli-form
                      form->child-form
                      resolve-malli-form
                      form-head)]
         (= :keyword base))))

(defn coerce-lookup-ref-symbol
  "If `v` is a lookup-ref tuple `[ident-attr value]` whose `ident-attr` is
   a [[keyword-identity-ident?]] and whose `value` is a symbol, coerce the
   value to a keyword. Anything else passes through unchanged."
  [v]
  (if (and (vector? v)
           (= 2 (count v))
           (keyword-identity-ident? (first v))
           (symbol? (second v)))
    [(first v) (keyword (second v))]
    v))

(defn coerce-identity-symbol-idents
  "Walk `tx-data` and coerce symbol values to keywords for every
   [[keyword-identity-ident?]] slot — entity-map values, nested entities
   under ref slots, lookup-ref tuples (in `:db/id`, ref values, and the
   e-/v-slots of `[:db/add|:db/retract e a v]` tuples). The KEYWORD is the
   stored canonical value (#48)."
  [tx-data]
  (letfn [(coerce-entity [ent]
            (reduce-kv
              (fn [acc k v]
                (assoc acc k
                       (cond
                         ;; The footgun: a symbol where a keyword identity
                         ;; value is required → coerce to keyword.
                         (and (keyword-identity-ident? k) (symbol? v))
                         (keyword v)
                         ;; A `:db/id` lookup-ref carrying a symbol value.
                         (= :db/id k) (coerce-lookup-ref-symbol v)
                         ;; Recurse only into ref-slot values (entity maps
                         ;; / lookup-refs legally appear there); other map
                         ;; values are opaque (mirrors the ref-key walker).
                         (ref-slot? k) (coerce-ref-value v)
                         :else v)))
              {} ent))
          (coerce-ref-value [v]
            (cond
              (map? v)        (coerce-entity v)
              (vector? v)     (coerce-lookup-ref-symbol v)
              (sequential? v) (mapv coerce-ref-value v)
              :else           v))]
    (mapv (fn [datum]
            (cond
              (map? datum)
              (coerce-entity datum)

              ;; `[:db/add|:db/retract e a v]` — coerce a lookup-ref symbol
              ;; in the e-slot, and the v-slot when `a` is a ref slot
              ;; (nested entity / lookup-ref) OR `a` is itself a
              ;; keyword-identity ident written with a symbol value.
              (and (vector? datum)
                   (>= (count datum) 3)
                   (#{:db/add :db/retract} (first datum)))
              (let [a (nth datum 2)]
                (cond-> (update datum 1 coerce-lookup-ref-symbol)
                  (and (>= (count datum) 4) (ref-slot? a))
                  (update 3 coerce-ref-value)
                  (and (>= (count datum) 4)
                       (keyword-identity-ident? a)
                       (symbol? (nth datum 3)))
                  (update 3 keyword)))

              :else datum))
          tx-data)))

(defn ref-attr-arity
  "If the attr's resolved Malli schema describes a ref slot, returns
   `:one` (single ref) or `:many` (container of refs). Returns `nil`
   if the schema isn't a ref slot.

   Arity matters because the validation gate's nested-map-shorthand
   path branches differently:

   - `:one` — value may be a single map (validate as nested entity),
     OR a single ref-shape (eid, lookup tuple). A lookup tuple is
     itself a 2-element vector — we MUST NOT iterate it as a
     container or we'd validate the keyword + string separately.
   - `:many` — value is a sequential of mixed (maps + refs); iterate
     and validate each child."
  [schema-form]
  (let [resolved (resolve-malli-form schema-form)
        head     (form-head resolved)]
    (cond
      (= resolved :seon.db/ref) :one

      (and (#{:vector :set :sequential} head)
           (when-let [child (first (form-children resolved))]
             (= :seon.db/ref (resolve-malli-form child))))
      :many

      :else nil)))

(declare validate-entity-values!)

(defn validate-ref-child!
  "Validate one entry inside a ref-typed slot. A map is treated as
   datahike's nested-entity shorthand (recursively validated against
   the children's own per-attr schemas); anything else must satisfy
   `:seon.db/ref` (eid, lookup tuple, or temp-id keyword)."
  [parent-attr child]
  (cond
    (map? child)
    (validate-entity-values! child)

    (m/validate :seon.db/ref child)
    nil

    :else
    (throw (ex-info (str "Malli validation failed for " parent-attr
                         " child: expected map or :seon.db/ref, got "
                         (truncate-value child))
                    {::db/error              :seon.db/invalid-ref-child
                     ::db/attr               parent-attr
                     ::db/actual-value       child
                     ::db/malli-explanation  (m/explain :seon.db/ref child)
                     :seon.error/kind        :user-input}))))

(defn validate-entity-values!
  "Validate each `[attr v]` pair in an entity map against its registered
   Malli schema. Skips system attrs and unregistered attrs (the latter
   should have been caught by `validate-attrs!`).

   Special-cases ref-typed attrs (see [[ref-attr-arity]]): accepts
   datahike's nested-map shorthand in place of explicit refs. A map
   value is recursively validated against the children's own per-attr
   schemas; the outer ref-type check is skipped because datahike will
   turn the map into an entity at write time.

   Arity matters: a single-ref slot whose value is a 2-element vector
   like `[:seon.agent/id \"seon\"]` is a LOOKUP REF, not a container
   of refs. We must dispatch on schema-declared arity, not on the
   value's `sequential?` shape, or we'd iterate the lookup tuple's
   keyword + string and validate them as separate refs."
  [entity]
  (doseq [[attr val] entity]
    (when (and (not (system-attr? attr))
               (schema/registered? attr))
      (let [schema-form (schema/schema-definition attr)
            arity       (ref-attr-arity schema-form)]
        (cond
          ;; Single-card ref slot.
          (= arity :one)
          (cond
            ;; Nested-map shorthand → recurse as entity.
            (map? val)
            (validate-entity-values! val)
            ;; Anything else (eid, lookup tuple, ident) → validate as ref.
            :else
            (when-not (m/validate :seon.db/ref val)
              (throw (ex-info (str "Malli validation failed for " attr
                                   ": expected :seon.db/ref (eid, lookup "
                                   "tuple, or nested entity map), got "
                                   (truncate-value val))
                              {::db/error             :seon.db/invalid-value
                               ::db/attr              attr
                               ::db/expected-schema   schema-form
                               ::db/actual-value      val
                               ::db/malli-explanation (m/explain :seon.db/ref val)
                               :seon.error/kind       :user-input}))))

          ;; Many-card ref slot — iterate children, each may be a
          ;; map (nested entity), eid, or lookup tuple.
          (= arity :many)
          (when (sequential? val)
            (doseq [child val]
              (validate-ref-child! attr child)))

          ;; Normal scalar / non-ref path — validate against the schema.
          :else
          (when-not (m/validate attr val)
            (throw (ex-info (str "Malli validation failed for " attr
                                 ": expected " (pr-str schema-form)
                                 ", got " (truncate-value val))
                            {::db/error              :seon.db/invalid-value
                             ::db/attr               attr
                             ::db/expected-schema    schema-form
                             ::db/actual-value       val
                             ::db/malli-explanation  (m/explain attr val)
                             :seon.error/kind        :user-input}))))))))

(defn validate-values!
  "Walk tx-data and validate every entity map. Vector tuple forms
   (`[:db/add ...]`, `[:db/retract ...]`) carry only one attribute and
   are best validated through their declared Malli schema by the caller;
   this gate doesn't try to type-check vector tuples (the JVM impl makes
   the same call)."
  [tx-data]
  (doseq [datum tx-data]
    (when (map? datum)
      (validate-entity-values! datum))))

(defn resolve-conn
  "Resolve a caller-supplied or default `*conn*`. Throws a clear error if
   neither is set — that almost always means `seon.db/*conn*` hasn't been
   bound at the session-flow boundary yet, or you're calling from outside
   a session scope."
  [conn]
  (or conn
      (throw (ex-info
               (str "seon.db: *conn* is unbound and no :seon.db/conn was "
                    "passed. Bind via session-flow setup, or pass "
                    "::db/conn explicitly.")
               {::db/error       :seon.db/no-conn
                :seon.error/kind :core-bug}))))

;; ---------------------------------------------------------------------------
;; Invocation normalization + shape guard for the write path.
;; ---------------------------------------------------------------------------

(defn conn?
  "A datahike conn is an `IDeref` that is NOT a map (verified live
   2026-06-08: `(map? conn)` => false, `(satisfies? IDeref conn)` =>
   true; a db VALUE is `map?`-true). Used by `normalize-transact-args` to
   tell a positional conn slot apart from a stray request map / db value."
  [x]
  (and (satisfies? IDeref x) (not (map? x))))

(defn db-value?
  "True for a datahike db VALUE — any of DB / FilteredDB / HistoricalDB /
   AsOfDB / SinceDB, all of which implement
   `datahike.db.interface/IDB`. The read-path positional arities use this
   to tell an explicit `db` argument apart from a Datalog `:in` input, so
   the db can be auto-injected from `*conn*` when omitted (the read-side
   sibling of `conn?`)."
  [x]
  (satisfies? dbi/IDB x))

(defn normalize-transact-args
  "Normalize `transact!`'s variadic args into the canonical map-in
   request map `{::tx-data … ::opts … ::conn …}` that the rest of the
   body and `assert-invocation-shape!` already understand. T15: the
   public surface accepts BOTH shapes.

   Dispatch (the chunk-1 finding — a db/conn value must never be mistaken
   for a request map): the FIRST arg decides.
     - a map containing `::tx-data`  -> map-in (passed through verbatim).
     - otherwise                     -> positional, first arg is the conn.
   A conn is `map?`-false and tx-data is a vector, so a positional first
   arg never collides with a `::tx-data`-bearing request map.

   Positional forms (mirror datahike `(d/transact! conn tx-data)`; seon
   adds a 3-arity tx-meta convenience since it nests tx-meta under
   `::opts {:tx-meta …}`):
     (transact! conn tx-data)          ==> {::conn c ::tx-data td}
     (transact! conn tx-data tx-meta)  ==> {::conn c ::tx-data td
                                            ::opts {:tx-meta tm}}

   Throws `:user-input` ex-info (caught upstream into an envelope, never
   into agent eval) for a malformed positional call — non-conn first arg,
   missing tx-data, or non-map tx-meta. A malformed map-in call is left
   to `assert-invocation-shape!`, which already produces a clear message."
  [args]
  (let [a0 (first args)]
    (cond
      ;; map-in: one request map carrying `::tx-data`. Pass through; the
      ;; existing guard validates the rest.
      (and (map? a0) (contains? a0 ::db/tx-data))
      a0

      ;; A lone map WITHOUT `::tx-data` is a malformed map-in call — let
      ;; the guard name the missing key / unqualified-key hint.
      (and (= 1 (count args)) (map? a0))
      a0

      ;; 1-arg tx-data shape: `(transact! [{…} …])` — the taught
      ;; transact form. The conn defaults to `*conn*` at the face.
      ;; Unambiguous: tx-data is sequential, a conn is a non-map IDeref,
      ;; a request map is `map?` — no shape collides.
      (and (= 1 (count args)) (sequential? a0))
      {::db/tx-data a0}

      ;; Positional: first arg must be a conn.
      (not (conn? a0))
      (throw (ex-info
               (str "seon.db/transact!: positional call expects a datahike "
                    "CONN as the first argument (an IDeref, not a map). Got: "
                    (truncate-value a0)
                    " — call `(transact! conn tx-data)` or `(transact! conn "
                    "tx-data tx-meta)`, or use the map-in shape "
                    "`{::db/tx-data […] ::db/conn conn}`.")
               {::db/error       :seon.db/invalid-invocation-shape
                ::db/actual-shape (type a0)
                ::db/actual-value a0
                :seon.error/kind :user-input}))

      :else
      (let [[conn tx-data tx-meta & extra] args]
        (when (seq extra)
          (throw (ex-info
                   (str "seon.db/transact!: positional call takes 2 or 3 "
                        "arguments `(conn tx-data [tx-meta])`. Got "
                        (count args) " arguments.")
                   {::db/error        :seon.db/invalid-invocation-shape
                    ::db/actual-value (vec args)
                    :seon.error/kind  :user-input})))
        (when (and (some? tx-meta) (not (map? tx-meta)))
          (throw (ex-info
                   (str "seon.db/transact!: positional tx-meta (3rd arg) "
                        "must be a map. Got: " (truncate-value tx-meta))
                   {::db/error        :seon.db/invalid-invocation-shape
                    ::db/actual-value tx-meta
                    ::db/actual-shape (type tx-meta)
                    :seon.error/kind  :user-input})))
        (cond-> {::db/conn conn ::db/tx-data tx-data}
          (some? tx-meta) (assoc ::db/opts {:tx-meta tx-meta}))))))

(defn assert-invocation-shape!
  "KI-1 guard. `transact!` is map-in / map-out — every key namespaced
   under `:seon.db/*`. Positional invocations are normalized to this map
   shape by `normalize-transact-args` BEFORE this guard runs; an
   unqualified-key map (`{:tx-data […]}`) or a map missing `::tx-data`
   silently destructured to nil/empty and used to crash deep inside
   datahike with cryptic errors. This precondition catches that at the
   boundary with a clear message.

   Run BEFORE destructuring so the error message can name the actual
   shape received."
  [arg]
  (cond
    (not (map? arg))
    (throw (ex-info
             (str "seon.db/transact! expects ONE map argument with "
                  "`:seon.db/tx-data`. Got: " (truncate-value arg)
                  " — did you call positionally? "
                  "Use {::db/tx-data […]} or {:seon.db/tx-data […]}.")
             {::db/error        :seon.db/invalid-invocation-shape
              ::db/actual-shape (type arg)
              ::db/actual-value arg
              :seon.error/kind  :user-input}))

    (not (contains? arg ::db/tx-data))
    (let [unqualified-tx-data (get arg :tx-data ::db/not-present)
          hint                (if (not= unqualified-tx-data ::db/not-present)
                                " — Hint: keys must be namespaced. Use `:seon.db/tx-data`, not bare `:tx-data`."
                                "")]
      (throw (ex-info
               (str "seon.db/transact!: missing `:seon.db/tx-data` key."
                    hint
                    " Got keys: " (pr-str (vec (keys arg))))
               {::db/error       :seon.db/invalid-invocation-shape
                ::db/missing     :seon.db/tx-data
                ::db/actual-keys (vec (keys arg))
                :seon.error/kind :user-input})))

    ;; tx-data must be a sequential collection. Strings, JS objects,
    ;; numbers, nil — anything non-sequential — is a caller fault
    ;; (LLM hallucination, wrong-shape eval). Catch it here at the
    ;; shape guard so it's classified `:user-input`. Without this
    ;; check, the value flows into `extract-tx-attrs`/`mapcat` which
    ;; throws an opaque "X is not ISeqable" → outer catch tags it
    ;; `:core-bug`. That misclassification was task-9b finding 2.
    (not (sequential? (::db/tx-data arg)))
    (throw (ex-info
             (str "seon.db/transact!: `:seon.db/tx-data` must be a "
                  "sequential collection (vector or seq) of entity "
                  "maps or [:db/add ...] tuples. Got: "
                  (truncate-value (::db/tx-data arg)))
             {::db/error        :seon.db/invalid-invocation-shape
              ::db/actual-value (::db/tx-data arg)
              ::db/actual-shape (type (::db/tx-data arg))
              :seon.error/kind  :user-input}))))

;; ---------------------------------------------------------------------------
;; Tx-meta auto-merge + origin-forge guard.
;; ---------------------------------------------------------------------------

(defn merge-tx-context-into-opts
  "Merge `(current-tx-context)` AND `(current-agent-id)` into
   `opts.:tx-meta`. Explicit `(:tx-meta opts)` keys win per-key; the
   tx-context fills the next layer; the agent-id ALS fills the last.

   Precedence (highest → lowest):
     1. explicit `:tx-meta` keys passed by the caller
     2. `(current-tx-context)` keys
     3. `(current-agent-id)` → `:seon.db/agent-id` (audit P1 — every
        agent-scoped tx is auto-tagged with the originating agent)

   Returns the (possibly-updated) opts, or nil if nothing to merge AND
   nothing was passed."
  [opts]
  (let [ctx       (current-tx-context)
        agent-id  (current-agent-id)
        als-meta  (cond-> {}
                    agent-id (assoc ::db/agent-id agent-id))
        merged    (merge als-meta ctx)]
    (cond
      (and (nil? opts) (empty? merged))  nil
      (empty? merged)                    opts
      :else                              (update (or opts {}) :tx-meta
                                                 #(merge merged %)))))

;; ---------------------------------------------------------------------------
;; Origin-forge guard (verifier rec, 2026-06-09). An AGENT-scoped tx that
;; claims `:seon.db/origin :core-seed` is forging core
;; provenance — the inspector's `on-tx` fan-out trusts that origin to
;; push the tx to EVERY watching agent's pane, so a forging agent could
;; spuriously wake all of its peers' renders.
;;
;; The intended enforcement is to OVERRIDE the origin to `:agent` (the
;; honest value) and log. But TODAY the legitimate boot-seed path
;; (`seon.client/seed-core!`) still runs INSIDE the booting agent's
;; `with-agent` scope (known client.cljs issue, other lane), so the
;; override would silently re-stamp every boot-seed tx and break the
;; cross-agent visibility the seed depends on. Until the seed moves
;; outside agent scope this guard is WARN-ONLY: log + count, commit
;; unchanged.
;;
;; TODO(after client.cljs runs seed-core! OUTSIDE with-agent —
;; #23's lane): flip to enforcement — override the origin to :agent
;; (keep the warn), gated on a private `*core-seed-allowed*`
;; binding the seed path establishes.
;; ---------------------------------------------------------------------------

(defonce !seed-origin-forge-count
  ;; Public so tests can reset/read it. Counts agent-scoped tx that
  ;; claimed :core-seed origin since pod boot.
  (atom 0))

(defn warn-on-seed-origin-forge!
  "WARN-ONLY guard: when an agent scope is active and the merged
   tx-meta claims `:seon.db/origin :core-seed`, log a console
   warning and bump `!seed-origin-forge-count`. Returns `merged-opts`
   unchanged (see the enforcement TODO above)."
  [merged-opts]
  (when (and (some? (current-agent-id))
             (= :core-seed (get-in merged-opts [:tx-meta ::db/origin])))
    (swap! !seed-origin-forge-count inc)
    (js/console.warn
      "seon.db/transact!: agent-scoped tx claims :seon.db/origin :core-seed — core provenance from inside an agent scope (warn-only; see warn-on-seed-origin-forge!)"
      #js {:agent (current-agent-id)
           :count @!seed-origin-forge-count}))
  merged-opts)

;; ---------------------------------------------------------------------------
;; Error envelopes — every failure path in the write pipeline resolves to
;; `{::db/ok? false ::db/error …}` data, never a throw into agent eval.
;; ---------------------------------------------------------------------------

(defn error-envelope
  "Build a `{::ok? false ::error <error-map>}` failure envelope from a
   thrown error. Ensures `:seon.error/data` carries a `:seon.error/kind`
   tag (defaulting to `:core-bug` when the throw didn't ship one).
   `:user-input` is reserved for caller-fault paths — invocation shape,
   unregistered attr, value Malli failure. Anything else (datahike
   internals, store I/O, schema bridge bug) defaults to `:core-bug`."
  [e]
  (let [emap (error/->map e)
        data (or (:seon.error/data emap) {})
        kind (:seon.error/kind data :core-bug)
        emap (assoc emap :seon.error/data (assoc data :seon.error/kind kind))]
    {::db/ok? false ::db/error emap}))

(def ^:private verbose-data-keys
  "Keys dropped from the failure envelope's `:seon.error/data` (#46). The
   surviving message ALREADY states attr/expected/got in one line, so the
   full Malli explanation is pure duplication; the expected-schema /
   actual-value structured fields stay (short, machine-readable — they
   name WHICH attr/value failed). `:seon.db/malli-explanation` is the
   `{:schema … :value … :errors …}` blob that ballooned the envelope to
   ~3600 chars and re-stated message content a third time."
  [:seon.db/malli-explanation])

(defn compact-error-map
  "Collapse a `(error/->map e)` failure into ONE concise envelope (#46).
   A downstream-reported transact! validation failure echoed the SAME
   explanation across four keys — `:seon.error/message`,
   `:seon.error/ex-data`, `:seon.error/data`, and the embedded
   `:seon.db/malli-explanation` — plus a multi-kb `:seon.error/stack` and
   the opaque `:seon.error/raw` error instance (which re-prints
   message+ex-data on pr-str), tripping the 1500-char agent-display
   truncation on a trivial type mismatch.

   This keeps exactly what an agent needs to act — the guiding
   `:seon.error/message`, and a SHORT `:seon.error/data` carrying
   `:seon.error/kind`, the `:seon.db/error` tag, plus the
   attr / expected-schema / (truncated) actual-value naming WHICH attr
   and value failed — and drops the redundant copies:

     - `:seon.error/ex-data`  — byte-identical to `:seon.error/data`.
     - `:seon.error/raw`      — opaque error object; pr-str re-emits the
                                whole message + ex-data again.
     - `:seon.error/stack`    — a JS stack is noise for a `:user-input`
                                fault; the message says what to fix.
     - `:seon.db/malli-explanation` (inside data) — the
                                `{:schema/:value/:errors}` blob the
                                message already summarizes in one line.

   `:seon.db/actual-value` is truncated to its `pr-str` (it can be an
   arbitrarily large bad value — a giant string, a deep map — and the
   validators store it UN-truncated; the message already carries the
   truncated form). Other keys pass through, so cryptic-error translation
   ([[translate-cryptic-error]]) — which reads message + ex-data BEFORE
   this runs — and the `:seon.db/raw-error` it sets are unaffected."
  [error-map]
  (let [data  (:seon.error/data error-map)
        data' (when (map? data)
                (cond-> (apply dissoc data verbose-data-keys)
                  (contains? data :seon.db/actual-value)
                  (update :seon.db/actual-value truncate-value)))]
    (cond-> (dissoc error-map
                    :seon.error/ex-data
                    :seon.error/raw
                    :seon.error/stack)
      (some? data') (assoc :seon.error/data data'))))

(defn translate-cryptic-error
  "A4: rewrite the two known cryptic datahike commit errors inside a
   failure envelope into guiding, agent-actionable messages. The raw
   message is preserved verbatim under `:seon.db/raw-error`. Both are
   caller-fixable, so `:seon.error/kind` is retagged `:user-input`
   (datahike throws them from its internals, which the generic
   classifier would mislabel `:core-bug`). Non-matching envelopes
   pass through unchanged."
  [{::db/keys [error] :as envelope}]
  (let [msg  (:seon.error/message error)
        exd  (:seon.error/ex-data error)
        rewrite (fn [guiding]
                  (-> envelope
                      (assoc ::db/raw-error msg)
                      (assoc-in [::db/error :seon.error/message] guiding)
                      (assoc-in [::db/error :seon.error/data :seon.error/kind]
                                :user-input)))]
    (cond
      (not (string? msg))
      envelope

      ;; "Bad entity attribute :x at {...}, not defined in current schema"
      (re-find #"not defined in current schema" msg)
      (let [attr (:attribute exd)]
        (rewrite
          (str "attr " (pr-str attr) " is not installed in the database "
               "schema — register it with (seon.schema/register! "
               (pr-str attr) " <type>) BEFORE transacting. If you "
               "registered it earlier this turn and still see this "
               "error, report a core bug.")))

      ;; "Lookup ref attribute should be marked as :db/unique"
      (re-find #"Lookup ref attribute should be marked as :db/unique" msg)
      (rewrite
        (str "lookup-ref failed: a lookup-ref [attr v] only works when "
             "attr is an IDENTITY attr. Usually the fix is NOT identity: "
             "query for the entity's eid and use that, or transact the "
             "entity first (or via a tempid in the same tx). Do NOT "
             "re-register an EXISTING attr just to add "
             "{:seon.db/identity true} — that mutates a shared data "
             "model others already query. Only a NEW attr that is "
             "genuinely the kind's natural key (rows upsert by it) "
             "should be registered as an identity attr."))

      :else envelope)))

(defn commit-error-envelope
  "Failure envelope + cryptic-message translation, then COMPACTION (#46).
   Every catch in the transact path routes through this so the agent
   always sees the guiding message (with the raw one preserved at
   `:seon.db/raw-error`). Order is load-bearing: translation runs on the
   FULL error map (it reads `:seon.error/message` + `:seon.error/ex-data`),
   THEN [[compact-error-map]] drops the duplicated/verbose keys so the
   final envelope stays well under the agent-display truncation limit."
  [e]
  (let [envelope (translate-cryptic-error (error-envelope e))]
    (update envelope ::db/error compact-error-map)))

;; ---------------------------------------------------------------------------
;; Runtime datahike-schema install + the commit body.
;; ---------------------------------------------------------------------------

(defn ^:async ensure-datahike-attrs!
  "Install the datahike attribute-declaration (`:db/valueType` +
   `:db/cardinality` + identity/component flags) for any attr in `attrs`
   that is registered in `seon.schema` but NOT yet present in the conn's
   live datahike schema.

   WHY this exists: datahike runs `:schema-flexibility :write`, so every
   attr must have a datahike schema datom BEFORE its first transact —
   otherwise `d/transact!` throws \"Bad entity attribute … not defined in
   current schema\". At boot, `seon.client/open-agent-conn!` installs the
   core's attrs from a fixed list. But when an AGENT registers a NEW
   attr at runtime via `seon.schema/register!`, only the Malli registry
   learns about it — the datahike conn does not. Without this step the
   agent's register→transact flow ALWAYS failed at the datahike layer,
   even after a correct `register!` (the second half of the Phase-1 demo
   gap). This closes the loop so `register!` truly is 'register the type,
   the system derives datahike storage' (CLAUDE.md).

   Reads the conn's current schema map (`(:schema @conn)` — keyed by both
   ident keywords and eids) to find which idents are missing, derives the
   datahike entries via the Malli→datahike bridge, and transacts them in
   their OWN tx (schema before data, like boot). `:db/*` system attrs and
   `:seon.db/ref` (no standalone valueType — refs are declared via the
   attrs that USE them) are skipped.

   FAIL-LOUD (Run-5 / A4): a bridge failure here means the attr was
   `register!`'d with a type datahike can't store. The old behavior
   (console.warn + skip) silently dropped the install, and the data tx
   then died on datahike's cryptic \"Bad entity attribute … not defined
   in current schema\". Now the whole transact fails with a legible
   `:user-input` error naming the attrs, their registered forms, and the
   supported type list — which `transact!`'s catch turns into the
   `{::ok? false}` envelope the agent can SEE and act on."
  [conn attrs]
  (let [installed  (:schema @conn)
        candidates (->> attrs
                        (remove system-attr?)
                        (remove #(= :seon.db/ref %))
                        (filter schema/registered?)
                        (remove #(contains? installed %))
                        distinct)
        {:keys [entries failures]}
        (reduce
          (fn [acc attr]
            (try
              (update acc :entries conj (malli->datahike-attr attr))
              (catch :default e
                (update acc :failures conj
                        {::db/attr   attr
                         ::db/schema (schema/schema-definition attr)
                         ::db/reason (or (.-message e) (str e))}))))
          {:entries [] :failures []}
          candidates)]
    (when (seq failures)
      (throw (ex-info
               (str "These attrs are registered in seon.schema but their "
                    "types cannot be stored in datahike: "
                    (pr-str (mapv (juxt ::db/attr ::db/schema) failures))
                    ". Supported attr types: " bridge-supported-types
                    ". Re-register each with a storable type (e.g. "
                    "(seon.schema/register! "
                    (pr-str (::db/attr (first failures)))
                    " :double)) and transact again.")
               {::db/error       :seon.db/unbridgeable-attrs
                ::db/failures    failures
                :seon.error/kind :user-input})))
    (when (seq entries)
      (await (d/transact! conn (vec entries))))))

(defn transact-success-envelope
  "Build the agent-visible success envelope from a raw datahike tx-report.
   COMPACT BY DEFAULT: the agent sees a small data summary, never the raw
   report's `:db-before`/`:db-after` db-value echo or the full per-datom
   `:tx-data` (a bulk seed = thousands of datoms — dumping them into the
   eval value bloats every past-eval render).

     {:seon.db/ok? true
      :seon.db/tempids   <report :tempids>   ; LOAD-BEARING — tempid→eid
      :seon.db/tx        <max-tx of :db-after — the committed tx id>
      :seon.db/tx-count  <count of :tx-data>
      :seon.db/added     <datoms added>
      :seon.db/retracted <datoms retracted>}

   The wire success report's shape: `:db-after`
   (a datahike DB value whose `:max-tx` IS the committed tx id), `:tx-data`
   (a vector of Datoms, each `:added` true/false), `:tempids`, `:tx-meta`,
   and — on the wire path — `:datoms-added` / `:datoms-retracted`, the
   honest add/retract split the sole writer computed over the REAL `:added`
   flags (`seon.server.wire/tx-report->ok-map`).

   The counts are taken from `:datoms-added` / `:datoms-retracted` when the
   report carries them (the wire path), else counted directly off the
   datoms' `:added` flags. NEVER inferred by `tx-count - added`
   subtraction — a single `[:db/retract …]` adds tx-meta datoms whose count
   masks the retraction, so subtraction reports retracted 0 (#16).

   The FULL raw report is included at `:seon.db/tx-report` ONLY when the
   caller passes `:seon.db/return-report? true` (escape hatch for code that
   needs `:db-after` / `:db-before`). Listeners are UNAFFECTED — they
   project off the raw report independently via [[build-handler-input]],
   and the wire's `tx-report->ok-map` stays on the raw report."
  [report return-report?]
  (let [datoms    (:tx-data report)
        added     (or (:datoms-added report)
                      (count (filter :added datoms)))
        retracted (or (:datoms-retracted report)
                      (count (remove :added datoms)))]
    (cond-> {::db/ok?        true
             ::db/tempids    (or (:tempids report) {})
             ::db/tx         (:max-tx (:db-after report))
             ::db/tx-count   (count datoms)
             ::db/added      added
             ::db/retracted  retracted}
      return-report? (assoc ::db/tx-report report))))

(defn ^:async transact!*
  "The map-in commit body. `seon.db/transact!` normalizes its variadic
   args (map-in OR positional) into the canonical request map, runs the
   invocation-shape guard, resolves the default `*conn*`, then delegates
   here. `arg` is the canonical `{::tx-data … ::opts … ::conn …}` map
   with `::conn` already defaulted.

   THE ERROR CONTRACT, in one place: transact!* NEVER rejects/throws to
   its caller. Every throw on the commit path — conn resolution, the
   validation gate ([[validate-attrs!]] / [[validate-values!]] throw
   ex-info on bad input), runtime schema install
   ([[ensure-datahike-attrs!]]), and the datahike commit itself — is
   caught HERE and converted by [[commit-error-envelope]] into the
   `{::ok? false …}` failure envelope the agent reads as a VALUE.
   Success returns the COMPACT envelope ([[transact-success-envelope]]).
   The only failures the
   `seon.db/transact!` face still catches are PRE-normalization ones
   (malformed call shape, before this fn is reached)."
  [arg]
  (try
    (let [{::db/keys [tx-data opts conn return-report?]} arg
          c           (resolve-conn conn)
          ;; A symbol written where a keyword-typed IDENTITY value belongs
          ;; (`{:seon.ns/name 'my.agent.foo …}`, the prompted fn-registration
          ;; footgun) is coerced to the canonical keyword HERE, before the
          ;; validation gate, so the natural agent shape persists instead of
          ;; failing Malli (#48). The KEYWORD is the stored canonical value.
          tx-data     (coerce-identity-symbol-idents tx-data)
          ;; The taught `{:seon.db/ref <eid|lookup-ref> …}` entity-key
          ;; shorthand becomes datahike's native `:db/id` slot HERE, so
          ;; `:seon.db/ref` never reaches the store as a junk attr
          ;; (throws :user-input on a malformed ref value — caught below).
          tx-data     (normalize-entity-ref-keys tx-data)
          attrs       (extract-tx-attrs tx-data)
          merged-opts (warn-on-seed-origin-forge!
                        (merge-tx-context-into-opts opts))]
      ;; Validation gate — these THROW ex-info on bad input; the outer
      ;; catch below converts every throw to the failure envelope.
      (validate-attrs! attrs)
      (validate-values! tx-data)
      ;; Install datahike schema for any registered attr not yet in the
      ;; conn (e.g. one the agent just `seon.schema/register!`'d at
      ;; runtime). Schema-before-data in its own tx; skips attrs already
      ;; present. See `ensure-datahike-attrs!` for the why.
      (await (ensure-datahike-attrs! c attrs))
      ;; Datahike-cljs `d/transact!` takes one arg-map combining
      ;; `:tx-data` + `:tx-meta` (see datahike.api.impl/transact! L29-41).
      ;; The previous shape `(d/transact! c tx-data opts)` passed opts
      ;; as a third arg that datahike silently ignored — so user tx-meta
      ;; NEVER reached the db before this fix. The single arg-map shape
      ;; is the only supported call path.
      (let [arg-map (merge {:tx-data (encode-edn-slot-values tx-data)}
                           merged-opts)
            report  (await (d/transact! c arg-map))]
        (transact-success-envelope report return-report?)))
    (catch :default e
      ;; Validation-gate / schema-install / datahike commit failure.
      ;; Translation rewrites the known cryptic messages and retags
      ;; them :user-input; anything else stays :core-bug.
      (commit-error-envelope e))))

;; ---------------------------------------------------------------------------
;; Boot preconditions.
;; ---------------------------------------------------------------------------

(defn assert-preconditions!
  "Validate v1.md §7.1 boot preconditions. Throws ex-info on failure.

   Preconditions:
     1. Resolved conn is opened with `:keep-history? true`. Without
        history, tx-meta datoms don't persist (datahike drops them on
        compaction) — the causality bundle silently degrades.
     2. All tx-meta attrs in `tx-meta-attrs` are registered in
        `seon.schema`. Datahike's `flush-tx-meta` rejects unregistered
        keys at write time; the first tx after boot would crash.

   Called (via the `seon.db/assert-preconditions!` face) from
   `seon.client/start-agent!` before any agent work fires. Tests pass an
   explicit `:seon.db/conn` to verify against a fresh conn without
   touching `*conn*`. `conn` must be resolved by the caller — this ns
   has no access to `seon.db/*conn*`."
  [conn]
  (let [c (resolve-conn conn)]
    ;; datahike-cljs exposes the conn's config map at `(:config @conn)`.
    ;; There's no `d/get-config` on the CLJS side (it exists on JVM
    ;; only). Deref + key access is the supported path.
    (when-not (:keep-history? (:config @c))
      (throw (ex-info
               (str "seon.db: agent conn opened with `:keep-history? false`. "
                    "v1's tx-meta-as-history mechanic requires history "
                    "(see v1.md §7.1). Open the conn with "
                    "`:keep-history? true`.")
               {:kind     :seon.boot/precondition-failed
                :failure  :keep-history-off
                ::db/error :seon.boot/precondition-failed})))
    (let [unregistered (into [] (remove schema/registered?) tx-meta-attrs)]
      (when (seq unregistered)
        (throw (ex-info
                 (str "seon.db: tx-meta attrs not registered: "
                      (pr-str unregistered) ". seon.db registers these at "
                      "namespace load — if this fires, the seon.schema "
                      "registry was likely cleared after seon.db loaded "
                      "(`schema/clear-all!` in a test, or stale REPL state).")
                 {:kind          :seon.boot/precondition-failed
                  :failure       :tx-meta-attrs-unregistered
                  :unregistered  unregistered
                  ::db/error     :seon.boot/precondition-failed}))))
    true))

;; ---------------------------------------------------------------------------
;; Listener plumbing — the rich handler-input build + the safe wrapper.
;;
;; Datahike's native `listen!` stores callbacks in a per-conn atom keyed by
;; an opaque key. Same key replaces (idempotent), distinct keys coexist as
;; independent listeners that EACH receive every tx-report.
;;
;; We wrap user-supplied handlers with a transformer that pre-computes the
;; common shape — decoded datoms, attr-grouped index, pre-resolved :db /
;; :db-before — so handlers don't reach to `*conn*` and don't recompute
;; the same group-by N times.
;; ---------------------------------------------------------------------------

(defn datom->map
  "Decode a datahike Datom into a fully-namespaced plain map. We re-emit
   under `:seon.db/*` rather than passing datahike's positional Datom
   record through, so handler bodies destructure with the same
   namespaced shape the rest of seon.db uses."
  [datom]
  {::db/e      (:e datom)
   ::db/a      (:a datom)
   ::db/v      (:v datom)
   ::db/tx     (:tx datom)
   ::db/added? (boolean (:added datom))})

(defn build-handler-input
  "Build the rich handler input map from a raw datahike tx-report. Called
   once per listener invocation. Cheap: a single mapv + group-by over
   :tx-data. Output keys are all `:seon.db/*` so handler code can
   destructure with `::db/keys [...]`."
  [raw-tx-report]
  (let [datoms (mapv datom->map (:tx-data raw-tx-report))]
    {::db/tx-report  raw-tx-report
     ::db/db         (:db-after raw-tx-report)
     ::db/db-before  (:db-before raw-tx-report)
     ::db/datoms     datoms
     ::db/attr-index (group-by ::db/a datoms)}))

(defn wrap-listen-handler
  "Wrap a user handler for `d/listen`: builds the rich input map and
   guards both sync throws and async rejections (SAFE BY DEFAULT per
   spec-02 §2.5 — neither takes down the pod; errors are logged via
   `js/console.warn`). `k` is the listener key, used in log lines."
  [k handler]
  (fn [raw-tx-report]
    (try
      (let [input  (build-handler-input raw-tx-report)
            result (handler input)]
        (if (instance? js/Promise result)
          (.catch result
                  (fn [err]
                    (js/console.warn "[seon.db/listen!" (pr-str k)
                                     "] async-rejected:"
                                     (error/->message err))))
          result))
      (catch :default e
        (js/console.warn "[seon.db/listen!" (pr-str k) "] threw:"
                         (error/->message e))
        nil))))
