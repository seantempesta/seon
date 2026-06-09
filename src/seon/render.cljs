(ns seon.render
  "Render surfaces — two today (`:seon.render/ai` and
   `:seon.render/html`), `:seon.render/canvas` planned (v2/v3).

   Each agent entity carries one slot per surface — a fully-qualified
   symbol naming the fn to call. `*-render` resolves the symbol via
   `seon.eval/lookup-value` and calls it; if the slot is nil,
   unqualified, or points at an unresolvable symbol, falls through to
   `seon.render.default/pretty-*` so the surface never crashes.

   See [[../prds/agent-runtime/v1.md]] §5 for the AI surface (six-section
   composer producing `:seon.turn/prompt-text`) and
   [[../prds/agent-runtime/v2.md]] 'Per-section HTML composer' for the
   HTML mirror (section fns grow `:seon.render/hiccup` in their
   return map alongside `:seon.render/text`).

   ## Naming note

   `ai-render` / `html-render` are thin: resolve symbol → call fn →
   fall back to pretty-print. They are NOT multimethod dispatch.
   V2's per-entity Malli-specificity dispatch is the real
   data-shape-driven pick-the-renderer — it lives in `seon.eval`
   alongside the program-graph queries it needs.

   ## Late-bound symbol lookup

   `seon.eval/lookup-value` walks `js/globalThis` with `cljs.core/munge`
   per segment — works for substrate fns (shadow-cljs precompiled
   bundle) AND agent-defined fns (written by `cljs.js/eval-str` at the
   same munged paths). Single path, no boot-time wire-up needed."
  (:require
    [datahike.api :as d]
    [seon.db :as db]
    [seon.eval :as eval]
    [seon.render.default :as default]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape this surface reads or writes (spec-05 §15.1).
;; ============================================================

;; Datahike db snapshot + conn handle — both opaque to validation
;; (genuinely runtime-opaque values; `:any` is the canonical Malli
;; idiom for "I am a runtime handle, validate by presence only").
;; Registered in ONE place so every reference is `:seon.db/db` or
;; `:seon.db/conn` instead of inlined `:any`.
(schema/register! :seon.db/db   :any)
(schema/register! :seon.db/conn :any)

;; :seon.render/ai is SYMBOL-ONLY at storage (literal strings would
;; deprive the agent of dynamic ctx — current convo, recent evals,
;; schema ref, …). Force-symbol ensures the default ctx fn is always
;; the baseline. The Malli :symbol type validates identically to
;; `[:fn symbol?]` AND maps cleanly through the seon.db schema bridge
;; to `:db.type/symbol`; the previous `[:fn symbol?]` shape didn't.
(schema/register! :seon.render/ai :symbol)

;; :seon.render/html — symbol-only at entity storage (V0.5 limitation;
;; see seon.client/agent-bootstrap-schema for the datahike side). The
;; in-process dispatch path still accepts literal hiccup at call sites
;; (e.g. when a render fn returns a vector that wraps another).
(schema/register! :seon.render/html :symbol)

;; Hiccup data shape — recursive vector starting with keyword, optional
;; attrs map, children. Defined via Malli local registry so the
;; recursive ref resolves. Kept registered for documentation purposes;
;; instrumentation uses `valid-hiccup?` below (a `[:fn ...]` predicate)
;; because Malli's recursive seqex schemas trip
;; `:malli.core/potentially-recursive-seqex` when referenced inside a
;; function schema.
(schema/register! :seon.render/hiccup
  [:schema {:registry {::elem [:or :string :int :nil ::node]
                       ::node [:cat :keyword
                                    [:? :map]
                                    [:* [:ref ::elem]]]}}
   ::elem])

(declare valid-hiccup?)

(defn- valid-hiccup-elem?
  "True if `x` is a valid hiccup ELEMENT — string, int, nil, or a
   nested vector that starts with a keyword. Mirrors the recursive
   `::elem` shape in the :seon.render/hiccup schema."
  [x]
  (or (string? x)
      (int? x)
      (nil? x)
      (valid-hiccup? x)))

(defn valid-hiccup?
  "True if `x` is a valid hiccup VECTOR: starts with a keyword tag,
   optional second-position attrs map, zero or more children where
   each child is a valid hiccup element. Non-recursive Malli idiom —
   handles arbitrary-depth nesting without :malli.core/potentially-
   recursive-seqex.

   Used as the [:fn valid-hiccup?] validator on
   `:seon.render/html-response` since Malli's recursive seqex
   schemas don't compose with instrumentation."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (let [rest-x (rest x)
             [_maybe-attrs children]
             (if (map? (first rest-x))
               [(first rest-x) (rest rest-x)]
               [nil rest-x])]
         (every? valid-hiccup-elem? children))))

;; Renderer return shapes — map-in / map-out per seon house rule.
(schema/register! :seon.render/ai-response
  [:map [:seon.render/text :string]])

;; `[:fn valid-hiccup?]` bypasses Malli's recursive-seqex limitation by
;; using a Clojure predicate — composes with fn-schema instrumentation.
;; `:nil` accepts render fns that explicitly return
;; `{:seon.render/hiccup nil}` to mean "render nothing"
;; (entity-html-sym callers already handle nil via `or`).
(schema/register! :seon.render/html-response
  [:map [:seon.render/hiccup [:or :nil [:fn valid-hiccup?]]]])

;; System renderer input — for `seon.render.default/*` and other
;; non-agent-namespaced fns. Doesn't know which agent ahead of time;
;; carries `:seon.agent/id` and pulls the entity itself.
(schema/register! :seon.render/system-input
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])

;; ============================================================
;; Renderers — one per surface. Both fall through to pretty-print
;; on miss; html-render additionally accepts literal hiccup.
;; ============================================================

(defn ai-render
  "Materialize an :seon.render/ai slot. Slot is a qualified symbol;
   if it doesn't resolve (or is nil / unqualified), fall through to
   seon.render.default/pretty-ai so the agent always gets some ctx."
  {:malli/schema [:=> [:cat :any :map] :seon.render/ai-response]}
  [sym input-map]
  (let [f (or (eval/lookup-value sym) default/pretty-ai)]
    (f input-map)))

(defn html-render
  "Materialize an :seon.render/html slot. Slot is either a symbol
   (resolved + called), a literal hiccup vector (used as-is), or
   anything else (pretty-printed)."
  {:malli/schema [:=> [:cat :any :map] :seon.render/html-response]}
  [slot input-map]
  (cond
    (qualified-symbol? slot)
    ((or (eval/lookup-value slot) default/pretty-html) input-map)

    (vector? slot)
    {:seon.render/hiccup slot}

    :else
    (default/pretty-html input-map)))

;; ============================================================
;; tx-log-as-context (PRD docs/prds/agent-runtime/tx-log-as-context-v1.md).
;;
;; Renderable entities are simply entities carrying a `:seon.render/ai`
;; symbol. We don't curate a separate `:seon.ctx/*` namespace — the
;; producer of the entity (eval recorder, message writer, user-message
;; transactor) attaches the symbol directly.
;;
;; Per-agent scoping uses tx-meta `:seon.db/agent-id`: the query reads
;; the agent-id stamped on each entity's most-recent assertion. Without
;; an agent-id stamp the entity is substrate-wide (always visible).
;;
;; Sticky entities (`:seon.sticky/position :prefix`) are always
;; included and sorted by `:seon.sticky/order` (sparse int, manual).
;; The window is "last N renderable entities by tx-time" with N
;; defaulting to 64 (agent override: `:seon.agent/window-size`).
;;
;; Token budget is a coarse char-count heuristic for v0 (4 chars ≈ 1
;; token). Replace with a real tokenizer when measured.
;; ============================================================

(schema/register! :seon.sticky/position [:enum :prefix :suffix])
(schema/register! :seon.sticky/order    :int)
(schema/register! :seon.sticky/id       [:string {:seon.db/identity true}])

(schema/register! :seon.agent/window-size [:int {:min 1 :max 512}])

(def ^:private default-window-size 64)

(defn- entity-last-tx
  "Return the latest tx eid that asserted any attr on `eid`. Used to
   sort renderable entities oldest-first by their newest assertion."
  [db eid]
  (->> (d/datoms db :eavt eid)
       (map (fn [^js d] (.-tx d)))
       (reduce max 0)))

(defn- tx-agent-id
  [db tx-eid]
  (:seon.db/agent-id (d/entity db tx-eid)))

(defn- renderable-kinds
  "Datalog-driven enumeration of every entity-shape `:seon.schema` row
   in the DB. Returns a seq of `{:kind <kw> :id-attr <kw> :ai <sym>
   :html <sym>}`. Each schema entity is materialized at agent boot
   from `seon.schema/all-entity-schemas-tx-data` (and on every
   subsequent `register!`), so the renderer reads schemas from
   substrate state instead of walking the in-memory
   `seon.schema/*schemas` atom.

   `:seon.schema/render-fn` and `:seon.schema/render-html-fn` are
   optional — split into two side queries and merge in Clojure rather
   than relying on datahike-cljs's `get-else` (avoids per-backend
   quirks)."
  [db]
  (let [base  (d/q '[:find ?key ?id-attr
                     :where
                     [?s :seon.schema/key ?key]
                     [?s :seon.schema/id-attr ?id-attr]]
                   db)
        ais   (into {} (d/q '[:find ?key ?ai
                              :where
                              [?s :seon.schema/key ?key]
                              [?s :seon.schema/render-fn ?ai]]
                            db))
        htmls (into {} (d/q '[:find ?key ?html
                              :where
                              [?s :seon.schema/key ?key]
                              [?s :seon.schema/render-html-fn ?html]]
                            db))]
    (map (fn [[k id-attr]]
           {:kind    k
            :id-attr id-attr
            :ai      (get ais k)
            :html    (get htmls k)})
         base)))

(defn- entity-primary-kind
  "Pick the most-specific `:seon.schema` kind whose required-attrs are
   ALL present on `entity`. Uses the `:in [?req ...]` collection-
   binding idiom — the only form that works in datahike-cljs (see
   docs/prds/agent-runtime/research/schemas-as-queryable-data-2026-05-26.md
   §C). The natural join on `?req` is implicit: a schema's required
   attr matches an entity's present attr iff they share a value.

   A schema 'fully matches' when its matched-count equals the cached
   total required-count (`seon.schema/schema-required-count`). Among
   full matches, the schema with the most required attrs wins
   (specificity). Tie-broken alphabetically by `:seon.schema/key` for
   stable output (research §D)."
  [db entity]
  (let [present (vec (filter keyword? (keys entity)))]
    (when (seq present)
      (let [matched (d/q '[:find ?key (count ?req)
                           :in $ [?req ...]
                           :where
                           [?s :seon.schema/key ?key]
                           [?s :seon.schema/required-attrs ?req]]
                         db present)
            full    (filter (fn [[k matched-n]]
                              (= matched-n
                                 (or (schema/schema-required-count k) 0)))
                            matched)]
        (when (seq full)
          (->> full
               (sort-by (juxt (comp - second) (comp str first)))
               ffirst))))))

(defn- renderable-entities
  "Enumerate all renderable entities visible to `agent-id`. Walks
   `(d/datoms db :aevt <id-attr>)` for each kind whose schema declares
   `:seon.render/ai`, pulls each, and attaches resolved render symbols.

   Returns a seq of:
     {:eid <entity-id>
      :last-tx <tx-eid>
      :agent-id <string-or-nil>
      :entity <pulled-entity-map>
      :kind <kw>
      :render/ai <sym>}

   Per-entity override: if the pulled entity carries its own
   `:seon.render/ai`, that wins over the kind's default."
  [db agent-id]
  (let [kinds        (renderable-kinds db)
        kinds-by-kw  (into {} (map (juxt :kind identity) kinds))
        ;; Distinct eids across all kind indices (an entity could in
        ;; principle carry id-attrs for multiple kinds — Phase 1c).
        eids         (->> kinds
                          (mapcat (fn [{:keys [id-attr]}]
                                    (->> (d/datoms db :aevt id-attr)
                                         (map (fn [^js d] (.-e d))))))
                          distinct)
        rows (for [eid eids
                   :let [tx     (entity-last-tx db eid)
                         tx-aid (tx-agent-id db tx)
                         ent    (d/pull db '[*] eid)
                         kind   (entity-primary-kind db ent)
                         k-info (get kinds-by-kw kind)
                         ai-sym (or (:seon.render/ai ent)
                                    (:ai k-info))]
                   :when (and ai-sym
                              (or (nil? tx-aid) (= tx-aid agent-id)))]
               {:eid       eid
                :last-tx   tx
                :agent-id  tx-aid
                :entity    ent
                :kind      kind
                :render/ai ai-sym})]
    rows))

(defn- sticky?
  [row]
  (= :prefix (:seon.sticky/position (:entity row))))

(defn- sort-prefix
  [rows]
  (sort-by (juxt #(or (:seon.sticky/order (:entity %)) 0)
                 :last-tx)
           rows))

(defn- sort-window
  [rows]
  (sort-by :last-tx rows))

(defn- entity-html-sym
  "Resolve the HTML render symbol for `entity`: per-entity override wins,
   else datalog lookup against the entity's primary `:seon.schema`
   kind. nil if neither path yields a symbol."
  [db entity]
  (or (:seon.render/html entity)
      (let [kinds-by-kw (into {} (map (juxt :kind identity)
                                      (renderable-kinds db)))
            kind        (entity-primary-kind db entity)]
        ;; NOTE: `(get kinds-by-kw kind)`, NOT `(some-> kinds-by-kw kind …)`
        ;; — the latter invokes `kind` as a fn and throws a TypeError
        ;; when entity-primary-kind returns nil (no kind matched).
        (some-> (get kinds-by-kw kind) :html))))

(defn- entity-ai-sym
  "Resolve the AI render symbol for `entity`: per-entity override wins,
   else datalog lookup against the entity's primary `:seon.schema`
   kind."
  [db entity]
  (or (:seon.render/ai entity)
      (let [kinds-by-kw (into {} (map (juxt :kind identity)
                                      (renderable-kinds db)))
            kind        (entity-primary-kind db entity)]
        ;; Same nil-kind guard as entity-html-sym.
        (some-> (get kinds-by-kw kind) :ai))))

(defn render-entity-html
  "Render `entity` to hiccup via its resolved `:seon.render/html` symbol.
   Per-entity override wins; else falls back to the entity-kind schema's
   default html symbol (Phase 1 schema-property pattern). Returns nil
   when no symbol resolves OR the resolved fn returns nil.

   `input` is the system-input shape every render fn receives:
     {:seon.db/db    <db>
      :seon.agent/id <agent-id>
      :seon.render/entity <entity-map>}"
  {:malli/schema [:=> [:cat :map] [:maybe :any]]}
  [{:seon.db/keys [db] :seon.render/keys [entity] :as input}]
  (let [db (or db @db/*conn*)]
    (when-let [sym (entity-html-sym db entity)]
      (try
        (:seon.render/hiccup (html-render sym input))
        (catch :default _ nil)))))

;; ============================================================
;; Agent tile (unit 1.4) — the agent's ONE always-visible HTML
;; surface. Resolution order: per-entity `:seon.render/html` override
;; on the agent entity → `:seon.agent` kind default (the schema-map
;; property, seeded as a `:seon.schema` entity at boot) → the
;; hardcoded `default-agent-tile-sym` floor so the tile renders even
;; on conns booted BEFORE the `:seon.agent` kind schema existed.
;; ============================================================

(def default-agent-tile-sym
  "Fallback tile renderer symbol — `seon.render.default/view`. Used
   when neither a per-entity override nor the `:seon.agent` kind
   schema entity yields a symbol."
  'seon.render.default/view)

(schema/register! :seon.render/tile-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db    {:optional true} :seon.db/db]])

(defn render-agent-tile
  "Render the agent's OWN tile — the one HTML surface the agent
   dynamically rewrites (by transacting `:seon.render/html '<fn-sym>`
   onto its own agent entity; the override wins over the kind
   default `seon.render.default/view`).

   Returns `{:seon.render/hiccup <vec-or-nil>}` — nil hiccup when the
   agent entity doesn't exist or the renderer throws (the tile must
   never crash its caller)."
  {:malli/schema [:=> [:cat :seon.render/tile-request] :seon.render/html-response]}
  [{:seon.agent/keys [id] :seon.db/keys [db]}]
  (let [db  (or db @db/*conn*)
        ent (try (d/pull db '[*] [:seon.agent/id id])
                 (catch :default _ nil))]
    (if (nil? (:seon.agent/id ent))
      {:seon.render/hiccup nil}
      (let [slot  (or (entity-html-sym db ent) default-agent-tile-sym)
            input {:seon.db/db         db
                   :seon.agent/id      id
                   :seon.render/entity ent}]
        (try
          (html-render slot input)
          (catch :default _ {:seon.render/hiccup nil}))))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary kind. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:seon.db/keys [db] :seon.render/keys [entity] :as input}]
  (let [db (or db @db/*conn*)]
    (when-let [sym (entity-ai-sym db entity)]
      (try
        (:seon.render/text (ai-render sym input))
        (catch :default _ nil)))))

(schema/register! :seon.render/visible-request
  [:map
   [:seon.agent/id          :string]
   [:seon.db/db             {:optional true} :any]
   [:seon.agent/window-size {:optional true} :seon.agent/window-size]])

(schema/register! :seon.render/visible-response
  [:map [:seon.render/entities [:vector :any]]])

(defn visible-entities
  "The ordered set of program-graph / message / eval entities visible to
   `agent-id`, in render order — the entities BEHIND the agent's context
   (the inspector's right-pane html cards drill into these). This is the
   tx-log entity-selection machinery; it does NOT produce the agent's
   prompt text. Prompt text is `seon.agent/assemble-context` (the ONE
   composer; the inspector left pane and the agent both call it).

   1. Query all entities carrying `:seon.render/ai` visible to the agent
      (substrate or own tx).
   2. Split into prefix-sticky and window.
   3. Sort prefix by `:seon.sticky/order` then tx-time; window by tx-time
      (oldest first).
   4. Take last N of window where N = `:seon.agent/window-size` (default 64).
   5. Subsumed kinds (:seon.fn/:seon.schema/:seon.ns) drop from the window
      (shown inside their :seon.eval card instead).

   Returns `{:seon.render/entities [<entity-map> ...]}` in render order."
  {:malli/schema [:=> [:cat :seon.render/visible-request]
                       :seon.render/visible-response]}
  [{:seon.agent/keys [id window-size] :seon.db/keys [db]}]
  (let [db    (or db @db/*conn*)
        n     (or window-size default-window-size)
        rows  (renderable-entities db id)
        ;; Subsumption rule (Phase 1c): entities whose primary kind is
        ;; :seon.fn / :seon.schema / :seon.ns are NOT shown in the
        ;; chronological window — they're subsumed by the :seon.eval
        ;; that created them (the (defn …) / (schema/register! …) /
        ;; (ns …) source is already shown in the eval card). They DO
        ;; appear when sticky (substrate-shipped at the front).
        subsumed-kinds #{:seon.fn :seon.schema :seon.ns}
        {sticks true window false} (group-by sticky? rows)
        window (remove #(contains? subsumed-kinds (:kind %)) window)
        sticky-sorted (sort-prefix (or sticks []))
        window-sorted (->> (or window []) sort-window vec)
        window-tail   (vec (take-last n window-sorted))
        ordered       (concat sticky-sorted window-tail)
        ents          (mapv :entity ordered)]
    {:seon.render/entities ents}))
