(ns seon.render
  "Render surfaces — two today (`:seon.render/ai` and
   `:seon.render/html`), `:seon.render/canvas` planned (v2/v3).

   Each agent entity carries one slot per surface — a fully-qualified
   symbol naming the fn to call. `*-render` resolves the symbol via
   `seon.eval/lookup-value` and calls it; if the slot is nil,
   unqualified, or points at an unresolvable symbol, falls through to
   `seon.render.default/pretty-*` so the surface never crashes.

   See [[../prds/agent-runtime/v1.md]] §5 for the AI surface (the
   section composer producing the turn's prompt text) and
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
;; The window is "last N renderable entities by tx-time" with N
;; defaulting to 64 (agent override: `:seon.agent/window-size`).
;; (The `:seon.sticky` prefix-pinning machinery was DELETED 2026-06-10
;; with the sticky preamble — `my.kb.instruction` rows rendered by the
;; agent's `:instructions` context section superseded it.)
;;
;; Token budget is a coarse char-count heuristic for v0 (4 chars ≈ 1
;; token). Replace with a real tokenizer when measured.
;; ============================================================

(schema/register! :seon.agent/window-size [:int {:min 1 :max 512}])

(def ^:private default-window-size 64)

(defn- entity-last-tx
  "Return the latest tx eid that asserted any attr on `eid`. Used to
   sort renderable entities oldest-first by their newest assertion."
  [db eid]
  (->> (d/datoms db :eavt eid)
       (map (fn [^js d] (.-tx d)))
       (reduce max 0)))

(def ^:private render-cap
  "Hard bound on entities materialized (pulled '[*] + kind-matched)
   per render. The inspector window shows at most
   `default-window-size` (64) anyway — pulling EVERY entity on the
   file-backed store made one render take 12-30s (observed live
   2026-06-09). The newest `render-cap` win; older are elided (count
   reported via `:seon.render/elided` metadata on the entities
   vector)."
  100)

(defn- renderable-kinds
  "Datalog-driven enumeration of every RENDERABLE entity-shape
   `:seon.schema` row in the DB — rows carrying BOTH an id-attr and a
   `:seon.schema/render-fn`. Returns a seq of `{:kind <kw> :id-attr <kw>
   :ai <sym> :html <sym>}`. Each schema entity is materialized at agent
   boot from `seon.schema/all-entity-schemas-tx-data` (and on every
   subsequent `register!`), so the renderer reads schemas from
   substrate state instead of walking the in-memory
   `seon.schema/*schemas` atom.

   The render-fn clause is load-bearing, not cosmetic: rows WITHOUT a
   renderer were already dropped post-pull (`:when ai-sym` in
   [[visible-entities]]), but their id-attrs still fed the
   [[renderable-entities]] `d/datoms` scan — and `d/datoms` THROWS
   (\"Bad entity attribute … not defined in current schema\") for any
   registered-but-never-transacted id-attr, e.g. the request/response
   envelopes the registry's id-attr derivation over-matches (context-v3
   unit 2; same gate as `seon.agent/schema-catalog-section`).

   `:seon.schema/render-html-fn` stays optional — a side query merged
   in Clojure rather than datahike-cljs's `get-else` (avoids
   per-backend quirks)."
  [db]
  (let [base  (d/q '[:find ?key ?id-attr ?ai
                     :where
                     [?s :seon.schema/key ?key]
                     [?s :seon.schema/id-attr ?id-attr]
                     [?s :seon.schema/render-fn ?ai]]
                   db)
        htmls (into {} (d/q '[:find ?key ?html
                              :where
                              [?s :seon.schema/key ?key]
                              [?s :seon.schema/render-html-fn ?html]]
                            db))]
    (map (fn [[k id-attr ai]]
           {:kind    k
            :id-attr id-attr
            :ai      ai
            :html    (get htmls k)})
         base)))

;; Single-slot cache for the schema-kind lookup tables, keyed by db
;; value identity. `entity-primary-kind` used to run one datalog query
;; PER ENTITY through the FilteredDB (each datom access re-runs the
;; filter pred) — the dominant cost of an inspector render on the
;; file-backed store. The tables derive purely from `:seon.schema`
;; rows, which are immutable for a given db value, so one slot keyed
;; by `identical?` is correct and survives exactly as long as the
;; render that's using it.
(defonce ^:private !kind-cache (atom nil))

(defn- kind-tables
  "Return `{:kinds <renderable-kinds seq> :kinds-by-kw {<kw> <info>}
   :required-by-kind {<kw> #{<attr> …}}}` for `db`, computed once per
   db value (single-slot identity-keyed cache)."
  [db]
  (let [c @!kind-cache]
    (if (and c (identical? (:db c) db))
      (:tables c)
      (let [kinds    (renderable-kinds db)
            req-rows (d/q '[:find ?key ?req
                            :where
                            [?s :seon.schema/key ?key]
                            [?s :seon.schema/required-attrs ?req]]
                          db)
            required (reduce (fn [m [k req]]
                               (update m k (fnil conj #{}) req))
                             {} req-rows)
            tables   {:kinds            kinds
                      :kinds-by-kw      (into {} (map (juxt :kind identity) kinds))
                      :required-by-kind required}]
        (reset! !kind-cache {:db db :tables tables})
        tables))))

(defn- entity-primary-kind
  "Pick the most-specific `:seon.schema` kind whose required-attrs are
   ALL present on `entity`. Pure in-memory subset test against the
   per-db cached `:required-by-kind` table ([[kind-tables]]) — the
   former per-entity datalog query was the inspector's render
   bottleneck on the file store.

   A schema 'fully matches' when every required attr is present on the
   entity. Among full matches, the schema with the most required attrs
   wins (specificity). Tie-broken alphabetically by `:seon.schema/key`
   for stable output (research §D)."
  [db entity]
  (let [present (set (filter keyword? (keys entity)))]
    (when (seq present)
      (let [{:keys [required-by-kind]} (kind-tables db)
            full (keep (fn [[k req]]
                         (when (and (seq req)
                                    (every? #(contains? present %) req))
                           [k (count req)]))
                       required-by-kind)]
        (when (seq full)
          (->> full
               (sort-by (juxt (comp - second) (comp str first)))
               ffirst))))))

(def ^:private subsumed-kinds
  "Kinds never shown in the chronological window — subsumed by the
   `:seon.eval` card that created them (visible-entities step 5)."
  #{:seon.fn :seon.schema :seon.ns})

(defn- db-schema
  "The datahike schema map of `db`, FilteredDB-safe. FilteredDB (the
   inspector's per-agent view) doesn't implement ILookup — `(:schema db)`
   THROWS. The schema is conn-level (the filter can't change it), so read
   through to the wrapped db. Same guard as `seon.agent/db-schema` and
   `seon.warn/domain-attrs`."
  [db]
  (try (:schema db)
       (catch :default _
         (:schema (.-unfiltered-db ^js db)))))

(defn- renderable-entities
  "Enumerate the renderable entities visible to `agent-id`, bounded.

   Phases (cheap-first so the expensive pull runs on few entities):
     1. Walk `(d/datoms db :aevt <id-attr>)` per kind → candidate eids
        (remembering WHICH kind's id-attr discovered each eid).
     2. Per-eid last-tx + per-TX visibility verdict (memoized — a seed
        tx covers hundreds of entities): visible when the tx is
        unstamped (substrate), stamped with THIS agent, or carries
        `:seon.db/origin :substrate-seed` (the boot seed runs inside
        the booting agent's `with-agent` scope, so seed tx arrive
        stamped with ANOTHER agent's id — same clause as
        `seon.agent-view/substrate-or-mine?`; without it agents that
        didn't run the seed lose every schema card).
     3. Bound: eids discovered ONLY via subsumed-kind id-attrs are
        dropped (visible-entities drops them post-pull anyway); newest
        `render-cap` of the rest by last-tx win, older are counted as
        elided.
     4. Pull '[*] + primary-kind + ai-sym ONLY for kept rows.

   Returns `{:seon.render/rows [<row> …] :seon.render/elided <int>}`
   where each row is
     {:eid <entity-id> :last-tx <tx-eid> :agent-id <string-or-nil>
      :entity <pulled-entity-map> :kind <kw> :render/ai <sym>}

   Per-entity override: if the pulled entity carries its own
   `:seon.render/ai`, that wins over the kind's default."
  [db agent-id]
  (let [{:keys [kinds kinds-by-kw]} (kind-tables db)
        ;; Belt + suspenders to the renderable-kinds render-fn gate:
        ;; `d/datoms` THROWS on an attr the conn has never installed
        ;; (registered-but-never-transacted), so only id-attrs present
        ;; in the INSTALLED schema may reach the :aevt scan. The kind
        ;; is simply absent until its first transact installs the attr
        ;; — fail-soft, no error swallowed.
        installed   (db-schema db)
        kinds       (filter #(contains? installed (:id-attr %)) kinds)
        ;; 1. eid → set of discovery kinds (an entity could carry
        ;; id-attrs for multiple kinds — Phase 1c).
        eid->kinds  (reduce (fn [m {:keys [kind id-attr]}]
                              (reduce (fn [m ^js d]
                                        (update m (.-e d) (fnil conj #{}) kind))
                                      m
                                      (d/datoms db :aevt id-attr)))
                            {} kinds)
        ;; 2. Per-tx meta memo — agent-id + origin judged once per tx.
        !tx-meta    (atom {})
        tx-meta     (fn [tx]
                      (or (get @!tx-meta tx)
                          (let [ent (d/entity db tx)
                                m   {:tx-aid (:seon.db/agent-id ent)
                                     :origin (:seon.db/origin ent)}]
                            (swap! !tx-meta assoc tx m)
                            m)))
        candidates  (for [[eid disc-kinds] eid->kinds
                          :let [tx (entity-last-tx db eid)
                                {:keys [tx-aid origin]} (tx-meta tx)]
                          :when (or (nil? tx-aid)
                                    (= tx-aid agent-id)
                                    (= :substrate-seed origin))]
                      {:eid eid :last-tx tx :tx-aid tx-aid
                       :disc-kinds disc-kinds})
        ;; 3. Bound.
        window-cand (remove #(every? subsumed-kinds (:disc-kinds %))
                            candidates)
        newest      (take render-cap (sort-by (comp - :last-tx) window-cand))
        elided      (- (count window-cand) (count newest))
        ;; 4. Materialize only the kept rows.
        rows (for [{:keys [eid last-tx tx-aid]} newest
                   :let [ent    (d/pull db '[*] eid)
                         kind   (entity-primary-kind db ent)
                         k-info (get kinds-by-kw kind)
                         ai-sym (or (:seon.render/ai ent)
                                    (:ai k-info))]
                   :when ai-sym]
               {:eid       eid
                :last-tx   last-tx
                :agent-id  tx-aid
                :entity    ent
                :kind      kind
                :render/ai ai-sym})]
    {:seon.render/rows   (vec rows)
     :seon.render/elided elided}))

(defn- sort-window
  [rows]
  (sort-by :last-tx rows))

(defn- entity-html-sym
  "Resolve the HTML render symbol for `entity`: per-entity override wins,
   else datalog lookup against the entity's primary `:seon.schema`
   kind. nil if neither path yields a symbol."
  [db entity]
  (or (:seon.render/html entity)
      (let [{:keys [kinds-by-kw]} (kind-tables db)
            kind (entity-primary-kind db entity)]
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
      (let [{:keys [kinds-by-kw]} (kind-tables db)
            kind (entity-primary-kind db entity)]
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
   2. Sort by tx-time (oldest first).
   3. Take last N where N = `:seon.agent/window-size` (default 64).
   4. Subsumed kinds (:seon.fn/:seon.schema/:seon.ns) drop from the window
      (shown inside their :seon.eval card instead).

   Returns `{:seon.render/entities [<entity-map> ...]}` in render order."
  {:malli/schema [:=> [:cat :seon.render/visible-request]
                       :seon.render/visible-response]}
  [{:seon.agent/keys [id window-size] :seon.db/keys [db]}]
  (let [db    (or db @db/*conn*)
        n     (or window-size default-window-size)
        {:seon.render/keys [rows elided]} (renderable-entities db id)
        ;; Subsumption rule (Phase 1c): entities whose primary kind is
        ;; :seon.fn / :seon.schema / :seon.ns are NOT shown in the
        ;; chronological window — they're subsumed by the :seon.eval
        ;; that created them (the (defn …) / (schema/register! …) /
        ;; (ns …) source is already shown in the eval card).
        window        (remove #(contains? subsumed-kinds (:kind %)) rows)
        window-sorted (->> (or window []) sort-window vec)
        window-tail   (vec (take-last n window-sorted))
        ents          (mapv :entity window-tail)]
    ;; `:seon.render/elided` rides as metadata so the response schema
    ;; (a plain entities vector consumed by the agent-context path and
    ;; the inspector alike) is unchanged; the inspector surfaces it as
    ;; an "older elided" note.
    {:seon.render/entities (with-meta ents {:seon.render/elided elided})}))
