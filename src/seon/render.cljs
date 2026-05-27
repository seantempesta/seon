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
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.eval :as eval]
    [seon.render.default :as default]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape this surface reads or writes (spec-05 §15.1).
;; ============================================================

;; Datahike db snapshot — opaque to validation; the renderer body uses it.
(schema/register! :seon.db/db :any)

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
;; recursive ref resolves.
(schema/register! :seon.render/hiccup
  [:schema {:registry {::elem [:or :string :int :nil ::node]
                       ::node [:cat :keyword
                                    [:? :map]
                                    [:* [:ref ::elem]]]}}
   ::elem])

;; Renderer return shapes — map-in / map-out per seon house rule.
(schema/register! :seon.render/ai-response
  [:map [:seon.render/text :string]])

(schema/register! :seon.render/html-response
  [:map [:seon.render/hiccup :any]])

;; System renderer input — for `seon.render.default/*` and other
;; non-agent-namespaced fns. Doesn't know which agent ahead of time;
;; carries `:seon.agent/id` and pulls the entity itself.
(schema/register! :seon.render/system-input
  [:map
   [:seon.db/db    :any]
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
  "Return a seq of `{:kind <kw> :id-attr <kw> :ai <sym> :html <sym>}`
   for every entity schema registered with both `:seon.render/ai` and
   `:seon.entity/id-attr` on its properties. The renderer enumerates
   instances by walking AEVT for each `id-attr`; the render symbols
   come from the schema's own props (no per-row stamp)."
  []
  (->> (schema/registered-schemas)
       (keep (fn [[k v]]
               (when-let [props (and (vector? v) (= :map (first v))
                                     (some (fn [x] (when (map? x) x)) (rest v)))]
                 (when (and (:seon.render/ai props)
                            (:seon.entity/id-attr props))
                   {:kind     k
                    :id-attr  (:seon.entity/id-attr props)
                    :ai       (:seon.render/ai props)
                    :html     (:seon.render/html props)}))))))

(defn- entity-primary-kind
  "Fingerprint an entity's primary kind by counting attr namespaces.
   The namespace with the most attrs wins. Returns the kind keyword
   (e.g. `:seon.eval`) or nil. Robust to multi-kind merges (Phase 1c)
   — for a single-kind entity the namespace it lives in IS the kind."
  [entity kinds-by-ns]
  (let [counts (->> (keys entity)
                    (filter keyword?)
                    (keep namespace)
                    frequencies)]
    (when (seq counts)
      (let [top-ns (->> counts (sort-by val >) ffirst)]
        (kinds-by-ns top-ns)))))

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
  (let [kinds        (renderable-kinds)
        kinds-by-ns  (into {} (map (fn [k] [(name (:kind k)) (:kind k)]) kinds))
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
                         kind   (entity-primary-kind ent kinds-by-ns)
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

(defn- render-one
  "Resolve the row's render-ai symbol (entity override or schema-level
   default — pre-computed in `renderable-entities`) and call it with
   the system-input shape. Falls back to pretty-ai when the symbol
   misses. Returns the rendered string."
  [{:seon.db/keys [db] :seon.agent/keys [id]} row]
  (let [entity (:entity row)
        sym    (or (:render/ai row) (:seon.render/ai entity))
        f      (or (eval/lookup-value sym) default/pretty-ai)
        input  {:seon.db/db    db
                :seon.agent/id id
                :seon.render/entity entity}
        out    (try (f input) (catch :default _ nil))]
    (or (:seon.render/text out) (str out))))

(defn- entity-html-sym
  "Resolve the HTML render symbol for `entity`: per-entity override wins,
   else look up the schema property for the entity's primary kind. nil
   if neither path yields a symbol."
  [entity]
  (or (:seon.render/html entity)
      (let [kinds       (renderable-kinds)
            kinds-by-ns (into {} (map (fn [k] [(name (:kind k)) (:kind k)]) kinds))
            kinds-by-kw (into {} (map (juxt :kind identity) kinds))
            kind        (entity-primary-kind entity kinds-by-ns)]
        (some-> kinds-by-kw kind :html))))

(defn- entity-ai-sym
  "Resolve the AI render symbol for `entity`: per-entity override wins,
   else schema property for the entity's primary kind."
  [entity]
  (or (:seon.render/ai entity)
      (let [kinds       (renderable-kinds)
            kinds-by-ns (into {} (map (fn [k] [(name (:kind k)) (:kind k)]) kinds))
            kinds-by-kw (into {} (map (juxt :kind identity) kinds))
            kind        (entity-primary-kind entity kinds-by-ns)]
        (some-> kinds-by-kw kind :ai))))

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
  [{:seon.render/keys [entity] :as input}]
  (when-let [sym (entity-html-sym entity)]
    (try
      (:seon.render/hiccup (html-render sym input))
      (catch :default _ nil))))

(defn render-entity-ai
  "Render `entity` to text via its resolved `:seon.render/ai` symbol.
   Per-entity override wins; else schema property for the entity's
   primary kind. Returns nil if no symbol resolves OR the fn returns
   nil. Mirror of `render-entity-html` for the AI path."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:seon.render/keys [entity] :as input}]
  (when-let [sym (entity-ai-sym entity)]
    (try
      (:seon.render/text (ai-render sym input))
      (catch :default _ nil))))

(schema/register! :seon.render/assemble-ai-request
  [:map
   [:seon.agent/id        :string]
   [:seon.db/db           {:optional true} :any]
   [:seon.agent/window-size {:optional true} :seon.agent/window-size]])

(schema/register! :seon.render/assemble-ai-response
  [:map
   [:seon.render/text     :string]
   [:seon.render/entities [:vector :any]]
   [:seon.render/token-estimate :int]])

(defn assemble-ai-context
  "Tx-log-as-context assembly.

   1. Query all entities carrying `:seon.render/ai` visible to the agent
      (substrate or own tx).
   2. Split into prefix-sticky and window.
   3. Sort prefix by `:seon.sticky/order` then tx-time; window by tx-time
      (oldest first).
   4. Take last N of window where N = `:seon.agent/window-size` (default 64).
   5. Render each via its symbol; concatenate with blank-line separator.

   Returns
     `{:seon.render/text \"...\"
       :seon.render/entities [<entity-map> ...]   ; render order
       :seon.render/token-estimate <int>}`        ; char-count / 4 v0 heuristic"
  {:malli/schema [:=> [:cat :seon.render/assemble-ai-request]
                       :seon.render/assemble-ai-response]}
  [{:seon.agent/keys [id window-size] :seon.db/keys [db]}]
  (let [db    (or db @db/*conn*)
        n     (or window-size default-window-size)
        rows  (renderable-entities db id)
        {sticks true window false} (group-by sticky? rows)
        sticky-sorted (sort-prefix (or sticks []))
        window-sorted (->> (or window []) sort-window vec)
        window-tail   (vec (take-last n window-sorted))
        ordered       (concat sticky-sorted window-tail)
        ents          (mapv :entity ordered)
        parts         (->> ordered
                           (map #(render-one {:seon.db/db db :seon.agent/id id} %))
                           (remove str/blank?))
        text          (str/join "\n\n" parts)
        token-est     (quot (count text) 4)]
    {:seon.render/text  text
     :seon.render/entities ents
     :seon.render/token-estimate token-est}))
