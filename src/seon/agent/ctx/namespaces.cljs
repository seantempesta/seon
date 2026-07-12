(ns seon.agent.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt. COMPACT
   EVERYTHING EXCEPT THE CURRENT NS: no hardcoded namespace list, no `my.*`
   pinning, no `:always` allow-list. THREE rules, nothing else
   ([[namespaces-block]]):

     - FULL — the agent's CURRENT ns (its complete working code) PLUS any ns in
       the per-agent `::full-source` presence-set. Nothing else renders full.
       The current ns is gated by the `::current-full?` flag (default true).
     - COMPACT CARD — every ns the CURRENT ns `:require`s (ALL of them), that
       isn't already full: its `register!` block + one-line `defn` heads (body
       elided). Self-healing on the `:seon.ns/require-edges` rows — write a real
       `(:require [x …])` and `x` joins as a card; drop the require → it
       vanishes ([[render-one-ns-compact]]).
     - DROPPED — everything else. Still INDEXED (its `:seon.ns/name` +
       `:seon.fn` / `:seon.schema` / `:seon.test` rows) and readable in FULL on
       demand via [[seon.agent.ctx/render-namespace]], just not resident in the
       section. `*.internal` / `*-test` and empty cards are always omitted.

   Each rendered ns is a comment-block delimited by per-ns
   `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─` brackets
   ([[seon.agent.ctx/ns-demarc]]).

   Symbol-wired into the composer layout (`seon.agent.ctx/default-seed-blocks`) as
   `'seon.agent.ctx.namespaces/namespaces-block`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   The section NEVER re-reads files at render time (code-as-data): the boot
   indexer (`seon.client/ns-row`) is the ONE file-reader. It stores the REAL
   full file text for the nses that CAN render full ([[full-source-ns?]] — the
   BOOT-STORAGE rule, all `my.*` incl. the agent's home ns + the config
   `:always` nses + the extra-src roots) and leaves the rest a `(ns x)` stub.
   Boot storage is a SUPERSET of what any one agent renders full — it governs
   WHICH source is available, not the per-agent SELECTION above. So a full row
   here is always real file source; a compact card reads only indexed
   fn/schema rows (no stored source needed)."
  (:require
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.schema :as schema]))

;; The compact card renderer is defined at the BOTTOM of this file (its
;; helpers cluster there); [[namespaces-block]] above it dispatches the long
;; tail to it, so forward-declare it here.
(declare render-one-ns-compact)

;; ============================================================
;; Config interface — the namespaces-block render dials, as reactive
;; datoms on the namespaces block entity (config-driven-agent-init lane's
;; two-level model: a block ref off the agent record). The config loader
;; transacts these onto the block; `db/transact!` refuses unregistered
;; attrs, so they live HERE, colocated with the render fn that reads them.
;;
;; Attribute-PRESENCE is the config (decision 22/23): a ns present in a set
;; IS its config; compact is the ABSENCE. The two set attrs mirror the
;; proven cardinality-many pattern of `:seon.agent.ctx/render-namespaces`;
;; the element type is `:seon.ns/name` (a keyword, matching the identity
;; attr's shape) — the bridge derives the SAME cardinality-many keyword
;; column as a bare `:keyword` would, but names the shape. A keyword (not a
;; `:db.type/ref`) tolerates configuring a not-yet-indexed ns (a fresh
;; `my.agent.*` home ns): an unmatched name simply no-ops in the render.
;; Defaults ride each spec (malli-native, decision 4) — a fresh block is
;; compact-everywhere + full-current-ns purely from these defaults.
;; ============================================================

(schema/register! ::full-source   [:vector {:default []} :seon.ns/name]) ; ns present → render FULL (absent → compact)
(schema/register! ::with-tests    [:vector {:default []} :seon.ns/name]) ; ns present → also show its tests
(schema/register! ::current-full?  [:boolean {:default true}])           ; the agent's current ns renders full
(schema/register! ::current-tests? [:boolean {:default true}])           ; …and its current ns shows its tests

;; ============================================================
;; The namespace-display rules. Two SEPARATE concerns, both pure
;; string/keyword/symbol fns (no dependency on anything in `seon.agent.ctx`):
;;
;;   - WHICH rows are indexable/renderable at all — [[included-ns?]]
;;     (`*.internal` / `*-test` excluded). Shared by the boot indexer and
;;     [[namespaces-block]].
;;   - The BOOT-STORAGE rule — [[full-source-ns?]] — which rows the boot
;;     indexer (`seon.client/ns-row`, the one file reader) stores REAL FULL
;;     FILE TEXT for, so they CAN be rendered full later. A SUPERSET of what
;;     any one agent renders full; it does NOT drive per-agent SELECTION
;;     (that is [[namespaces-block]]'s three-rule model: current ∪ requires
;;     ∪ ::full-source).
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace is indexed but never rendered.

   Applies to the ns or any of its children — the naming convention IS
   the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn my-ns-name?
  "Rule 2: `my.*` is the human's world — always shown.

   Provenance is not consulted (one name rule, no special cases)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (= s "my") (str/starts-with? s "my.")))))

(defn test-ns-name?
  "Rule 1b: a `*-test` namespace is indexed but never rendered.

   Never enters the agent prompt — its `deftest`s are noise to the working
   agent, and the per-fn `:test` usage example already rides the fn's attr-map in
   the compact head. Full tests stay reachable on demand via
   [[seon.agent.ctx/render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the
   suffix IS the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/ends-with? s "-test")))

(defn included-ns?
  "The ONE selection rule for the namespace sections.

   EVERY indexed :seon.ns row renders EXCEPT *.internal (hidden-ns-name?) and *-test
   (test-ns-name?) ones — both STRUCTURAL naming conventions that apply
   to seon, my.*, and downstream code alike. No prefix allow-list: the
   library gate lives on the INDEX side (only first-party + SEON_EXTRA_SRC
   code ever gets a :seon.ns row — seon.indexing/first-party-file?)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (and (not (hidden-ns-name? s))
                  (not (test-ns-name? s))))))

(defn- base-ns-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(defn- always-full?
  "True when `ns-name` (string/keyword/symbol) is in the resolved config
   policy's `:seon.config/always` set. BOOT-STORAGE only ([[full-source-ns?]]):
   the boot indexer stores these nses' real file source so a per-agent
   `::full-source` pin CAN promote them to full. It does NOT itself render
   anything full — per-agent SELECTION lives in [[namespaces-block]]. The
   policy read is memoized — no per-call file read."
  [ns-name]
  (contains? (:seon.config/always (config/namespaces-policy))
             (if (keyword? ns-name) ns-name (keyword (str ns-name)))))

(defn always-full-my-nses
  "The `my.*` members of the config `:seon.config/always` set.

   The toolkit exemplars `my.kb`/`my.data`/`my.ui`/`my.canvas` by default. DERIVED from
   config, never hardcoded. NOT a render-selection input (the `:namespaces`
   section no longer pins `:always`); used by the GYM harness
   (`seon.gym.driver`) to derive the toolkit aliases it seeds."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (into #{} (filter my-ns-name?) (:seon.config/always (config/namespaces-policy))))

(defn full-source-ns?
  "True when `ns-name` carries its REAL FULL FILE TEXT as `:seon.ns/source`.

   Accepts a string, symbol, or ns-name keyword. Every `my.*` ns (the
   human's world — always inlined), INCLUDING `.internal` siblings and
   `-test` siblings (the `-test` suffix is stripped to the subject ns
   first), AND every non-hidden seon.* ns the config policy lists in
   `:seon.config/always` ([[always-full?]] — e.g. `:seon.agent.message`, so
   its REAL body is stored; for seon.* the `.internal` suffix beats the
   config policy). Used by the boot indexer (`seon.client/ns-row`) to
   decide which rows get the file read: it stores source for a SUPERSET of
   what any one agent renders full, so a per-agent `::full-source` pin (or
   the current ns) has real source to show. It does NOT drive per-agent
   SELECTION — that is [[namespaces-block]]'s three-rule model
   ([[included-ns?]] keeps `.internal` out of the prompt regardless of what
   is stored). `my.*.internal` MUST store real source even though it never
   renders: its fns are agent-editable render fns
   (`seon.error/agent-authored-sym?` routes every `my.*` fn through
   the SCI cage) and the cage rebuilds a fn's lexical environment — its
   `:require` `:as` aliases — from the stored `:seon.ns/source`; a
   `(ns x)` stub loses the aliases and the fn cannot run BOUNDED.
   Third-party (`acme`) roots are full-source too, gated separately by
   `seon.client/extra-src-ns-strs` (the same file read). Every other ns gets
   the minimal `(ns x)` stub at boot (still indexed + searchable)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (or (my-ns-name? base)
                 (and (not (hidden-ns-name? s))
                      (always-full? base))))))

(defn- seon-framework-ns?
  "True when `ns-name` (string/keyword/symbol) is a `seon.*` framework ns —
   used to route a STABLE seon.* required ns into the name-sorted cache PREFIX
   vs the recency BODY (the agent's churning my.* / current ns)."
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/starts-with? s "seon.")))

(defn- required-ns-set
  "The nses the CURRENT ns `:require`s — ALL of them (no full-source gate),
   each rendered as a COMPACT CARD. Pure fn of the DB: a real
   `(:require [x …])` on the current ns pulls `x` into context as a card; drop
   the require → it leaves the set (self-healing on the
   `:seon.ns/require-edges` rows, via `seon.eval/stored-require-targets`).
   `*.internal` / `*-test` are excluded ([[included-ns?]]). Empty when
   `cur-ns` is nil."
  [db cur-ns]
  (if-not cur-ns
    #{}
    (into #{}
          (filter included-ns?)
          (seval/stored-require-targets db cur-ns))))

(defn- full?
  "True when an included ns `nm` renders FULL (its whole real source); false
   means it renders as a COMPACT CARD ([[render-one-ns-compact]]). ONE rule, no
   second full control:

     full? ⇔ (nm = current-ns ∧ ::current-full?) ∨ (nm ∈ ::full-source)

   The current ns honors the per-agent `::current-full?` flag (default true)
   and the config policy's `:current-ns` off-switch (`:off` → compact). Every
   OTHER included ns — the current ns's `:require`s — renders compact."
  [policy nm cur-ns full-source current-full?]
  (boolean
    (or (contains? full-source nm)
        (and (= nm cur-ns)
             current-full?
             (not= :off (:seon.config/current-ns policy))))))

(defn- ns-block-entity
  "The agent's `:namespaces` block entity (raw datahike Entity, lazy ILookup),
   or nil when the agent has no id / no such block. Mirrors
   [[seon.agent.ctx.canvas/block-content]]: read the agent's
   `:seon.agent/ctx` set and find the block named `:namespaces`. The
   config-driven-agent-init lane transacts the render-dial datoms
   (`::full-source` / `::with-tests` / `::current-full?` / `::current-tests?`)
   onto THIS entity; the render reads them reactively (a `db/transact!`
   re-derives next render, no apply step). If the lane doesn't instantiate a
   `:namespaces` block yet, this is nil and the caller falls back to the agent
   datom then the malli default — byte-parity for the current full set holds
   either way."
  [db id]
  (when id
    (some (fn [b] (when (= :namespaces (:seon.agent.ctx/name b)) b))
          (:seon.agent/ctx
            (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})))))

(defn- resolve-cfg
  "Resolve render-dial attr `k` for the agent: the value on its `:namespaces`
   BLOCK entity if present, else the value on its AGENT entity (datom
   fallback), else `default`. `some?` (not truthiness) draws the present/absent
   line so a legit `false`/empty value overrides. Mirrors
   [[seon.agent.ctx.canvas/canvas-block]]'s block→agent→default read."
  [block agent-ent k default]
  (let [bv (get block k)]
    (if (some? bv)
      bv
      (let [av (get agent-ent k)]
        (if (some? av) av default)))))

(defn- ns-tests-block
  "The indexed test SOURCE for ns `nm`, as a `; tests:`-headed block appended
   after the ns's full/compact render — or nil when the ns owns no
   `:seon.test` rows. Code-as-data: reads the stored `:seon.test/source`
   (keyed off the subject ns via `:seon.test/_ns`), never a file read. Drives
   the `::with-tests` presence-set (an ns in the set → its tests ride along)
   and the current ns's `::current-tests?` flag. nil when the ns has no
   `:seon.ns/name` entity (a brand-new current ns not yet indexed — `db/pull`
   would throw on the missing lookup ref)."
  [db nm]
  (let [tests (->> (when (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.ns/name nm]})
                     (db/pull {:seon.db/db db
                               :seon.db/ref [:seon.ns/name nm]
                               :seon.db/pull-pattern
                               '[{:seon.test/_ns [:seon.test/sym :seon.test/source]}]}))
                   :seon.test/_ns
                   (sort-by :seon.test/sym))
        srcs  (keep (fn [{:seon.test/keys [source]}]
                      (when-not (str/blank? source) (str/trim source)))
                    tests)]
    (when (seq srcs)
      (str "\n\n; tests:\n" (str/join "\n\n" srcs)))))

(def ^:private namespaces-header
  ;; Block-specific teaching, COLOCATED (the namespaces surface teaches its
  ;; own functions AND its own render policy — owner rulings 2026-07-10; runtime =
  ;; seon.eval/dispatch-repl-form!): movement/update functions + the full-vs-cards
  ;; distinction + "more namespaces exist in the db". Under minimal context
  ;; the system-text §"THE NAMESPACES BELOW" never renders, so this header is
  ;; the ONE place the policy is taught. Keep it tight.
  (str "; The loaded namespaces below, ordered by recency"
       " (most-recently-modified last).\n"
       "; Namespaces are PLACES — (in-ns 'the.ns) moves you there (your state\n"
       "; is preserved; a NEW name is created with your toolkit requires).\n"
       "; (ns the.ns (:require …)) declares/UPDATES a namespace's requires.\n"
       "; A bare (require '[x :as y]) adds a dependency now AND records it in\n"
       "; the declaration. Redefining a fn/schema/test IS how you update it;\n"
       "; (ns-unmap 'name) removes one.\n"
       "; Your CURRENT namespace renders in FULL; its required namespaces\n"
       "; render as COMPACT CARDS (fn head + docstring line 1 + :malli/schema,\n"
       "; bodies elided as …). More namespaces exist in the db than render\n"
       "; here — query rather than guess."))

(defn- cur-ns-workspace-stub
  "The never-omit block for the agent's CURRENT ns when it has no members
   defined yet (GI-2). A fresh home ns (`my.agent.<id>`) carries a
   `:seon.ns/name` row but no stored `:seon.ns/source` and no fns/schemas, so
   [[seon.agent.ctx/render-namespace]] yields an empty body that would be omitted
   — breaking the system prompt's promise that YOUR OWN namespace renders in
   full. This stub keeps that promise: it shows the REAL `(ns … (:require …))`
   form [[seon.eval/setup-agent-ns!]] actually installed — `[seon.agent.message
   :as message]` / `[seon.agent.lifecycle :refer [wait complete …]]` / … WITH
   the aliases + refers — straight from the ONE canonical
   [[seon.eval/home-ns-form]], NOT a bare-name reconstruction from the
   stored edges. No hidden aliasing: the agent reads the form and knows
   `message/user`, `db/transact!`, `schema/register!`, `wait`, `complete`
   exist and how to call them. `nm` is a ns-name keyword whose `:seon.ns/name`
   row the caller already matched (an included, current-ns row). `id` is the
   agent id, threaded so the stub prose shows THIS agent's actual configured
   requires ([[seon.eval/home-requires-for]]) — not the const default."
  [_db nm id]
  (ctx/ns-demarc
    nm
    (str (seval/home-ns-form nm (seval/home-requires-for id)) "\n"
         "; (your workspace — nothing defined here yet; define schemas + fns and they appear here.\n"
         ";  a defn whose :malli/schema output declares :seon.render/ai (and/or :seon.render/hiccup)\n"
         ";  AUTO-RUNS every turn: its output becomes a live section of your context + a tile on your page)")))

(defn- render-one
  "Render ONE included ns FULL through the SINGLE renderer
   ([[seon.agent.ctx/render-namespace]]), flat (depth 0 — no require-recursion;
   the section renders each ns once): the whole-ns view, real file source +
   members, unclipped.

   The agent's CURRENT ns (`cur-ns`) ALWAYS renders, even when empty: an
   empty current ns becomes a [[cur-ns-workspace-stub]] (GI-2) so the prompt's
   'YOUR OWN namespace renders in full' promise holds — this covers BOTH a home
   ns with a `:seon.ns/name` row but no source AND one with NO row at all (a
   brand-new agent whose home ns was never indexed;
   [[seon.agent.ctx/render-namespace]] throws on the missing entity, so
   short-circuit to the stub). Every OTHER ns with nothing real to show is
   omitted (nil). `id` threads through to the workspace stub so its prose
   reflects THIS agent's configured requires."
  [db nm cur-ns id]
  (if-not (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.ns/name nm]})
    ;; No `:seon.ns/name` entity — the current ns still keeps its promise via
    ;; the workspace stub; any other row with no entity is omitted.
    (when (= nm cur-ns) (cur-ns-workspace-stub db nm id))
    (let [txt    (-> (ctx/render-namespace
                       {:seon.ns/name       nm
                        :seon.render/depth  0
                        :seon.render/detail :full
                        :seon.db/db         db})
                     :seon.render/text
                     str/trim)
          ;; render-namespace brackets even an empty ns, whose body is then
          ;; `; (no recorded source/fns/schemas)` (entity present, no
          ;; source/members) or `; requires: x (not in db)` (the home ns —
          ;; a :seon.ns/name row whose sparse pull returns nil). Both mean
          ;; "nothing real to show."
          empty? (or (str/blank? txt)
                     (str/includes? txt "(no recorded source/fns/schemas)")
                     (str/includes? txt "(not in db)"))]
      (cond
        (= nm cur-ns) (if empty? (cur-ns-workspace-stub db nm id) txt)
        empty?        nil
        :else         txt))))

(defn- compact-block
  "Render ns `nm` as a COMPACT CARD, or nil when the card would carry no real
   content (a `; (nothing indexed)` / `; (not in db …)` stub) — a required ns
   with nothing indexed adds noise, not signal, so it stays dropped rather than
   emit an empty card. The compact SIBLING of [[render-one]] (the full
   wrapper); delegates to [[render-one-ns-compact]]."
  [db nm]
  (let [card (render-one-ns-compact {:seon.ns/name nm :seon.db/db db})]
    (when-not (or (str/includes? card "(nothing indexed)")
                  (str/includes? card "(not in db"))
      card)))

(defn namespaces-block
  "The namespaces body — the CURRENT ns full, its requires as cards.

   COMPACT EVERYTHING EXCEPT THE CURRENT NS — no hardcoded
   list, no `my.*` pinning, no `:always` allow-list. THREE rules:

     - FULL — the agent's CURRENT ns + any ns in the per-agent `::full-source`
       presence-set. A `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─`
       bracketed block carrying its REAL FULL FILE SOURCE, unclipped.
     - COMPACT CARD — every ns the CURRENT ns `:require`s ([[required-ns-set]])
       that isn't already full ([[render-one-ns-compact]]): its `register!`
       schema block + every public fn's one-line `defn` head (body elided),
       ~3–5× smaller than full. Self-healing on the `:seon.ns/require-edges` rows.
     - DROPPED — everything else, reachable via grep /
       [[seon.agent.ctx/render-namespace]]. (`*.internal` / `*-test` excluded
       outright, [[included-ns?]]; empty cards dropped.)

   DRIVEN BY THE PER-AGENT CONFIG DIALS, read reactively off the agent's
   `:namespaces` BLOCK entity, falling back to the agent datom, then the malli
   default ([[resolve-cfg]] — a `db/transact!` re-derives next render, no apply
   step):

     - `::full-source` — a presence-set of ns keywords to force FULL;
     - `::current-full?` (default true) — whether the agent's CURRENT ns
       renders full (false → its compact card);
     - `::with-tests` — a presence-set of ns keywords whose indexed test SOURCE
       rides along under the ns's block; the current ns joins this set when
       `::current-tests?` (default true) is on.

   ORDER: the STABLE `seon.*` required nses render FIRST, name-sorted, as a
   cache PREFIX; then the agent's churning BODY (my.* / current ns) ordered by
   RECENCY (tx of the `:seon.ns/name` datom, name tie-break) so the stable core
   forms a stable prefix and the current ns sits nearest the tail.

   NEVER a render-time file read — the boot indexer is the one reader; both the
   full renderer and the compact card read only indexed rows."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [policy (config/namespaces-policy)
        ;; The agent's current ns (latest successful eval's ns) → rendered per
        ;; the policy's :current-ns even if it is a framework ns. nil id
        ;; (web UI path) → nil → no ns is forced current.
        cur-ns (when id
                 (try (when-let [c (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                        ;; current-ns yields a KEYWORD from a recorded eval but
                        ;; a SYMBOL from the (home-ns id) fallback (a fresh agent
                        ;; with no successful evals yet) — normalize to a keyword
                        ;; so the `(= nm cur-ns)` match against the keyword ns
                        ;; rows holds in BOTH cases (GI-2 fires even on turn 0).
                        (keyword (name c)))
                      (catch :default _ nil)))
        ;; The per-agent render dials, read off the :namespaces BLOCK entity
        ;; (config-driven-agent-init), falling back to the agent datom then the
        ;; malli default. Presence-sets arrive as cardinality-many keyword
        ;; columns → sets; the two booleans default true (current ns full +
        ;; its tests).
        block          (ns-block-entity db id)
        agent-ent      (when id (db/entity {:seon.db/db db
                                            :seon.db/ref [:seon.agent/id id]}))
        full-source-cfg (set (resolve-cfg block agent-ent ::full-source #{}))
        with-tests-cfg  (set (resolve-cfg block agent-ent ::with-tests #{}))
        current-full?   (resolve-cfg block agent-ent ::current-full? true)
        current-tests?  (resolve-cfg block agent-ent ::current-tests? true)
        ;; The set of nses whose indexed test source rides along: the explicit
        ;; ::with-tests members ∪ (the current ns when ::current-tests? is on).
        tests-set      (cond-> with-tests-cfg
                         (and cur-ns current-tests?) (conj cur-ns))
        ;; The nses the CURRENT ns :require's — each a COMPACT card.
        required-set   (required-ns-set db cur-ns)
        ;; The INCLUDE set — PURELY: the current ns ∪ its requires ∪ the
        ;; per-agent ::full-source pins. Everything else is DROPPED.
        include-set    (cond-> (into required-set full-source-cfg)
                         cur-ns (conj cur-ns))
        ;; The included ns rows, recency-ordered. One :seon.ns/name datom per
        ;; ns carries its tx; filter to the include set.
        scanned (->> (db/query
                       {:seon.db/db db
                        :seon.db/query
                        '[:find ?nm ?tx
                          :where
                          [?n :seon.ns/name ?nm ?tx]]})
                     (filter (fn [[nm _]] (and (included-ns? nm)
                                               (contains? include-set nm))))
                     (sort-by (fn [[nm tx]] [tx (name nm)])))
        ;; The current ns ALWAYS renders (the "YOUR OWN namespace renders in
        ;; full" promise — an empty home ns becomes a workspace stub in
        ;; [[render-one]]). A fresh home ns (`my.agent.<id>`) may carry no
        ;; `:seon.ns/name` row yet, so synthesize a tail row when the scan
        ;; missed it.
        rows   (if (and cur-ns (not (some (fn [[nm _]] (= nm cur-ns)) scanned)))
                 (conj (vec scanned) [cur-ns js/Number.MAX_SAFE_INTEGER])
                 scanned)
        ;; Each row + its full? flag + PHASE: :prefix for a STABLE seon.*
        ;; required ns (name-sorted cache prefix); :body for the agent's
        ;; churning nses (my.* / current ns), recency-ordered nearest the tail.
        selected (mapv (fn [[nm _tx]]
                         (let [prefix? (and (not= nm cur-ns)
                                            (seon-framework-ns? nm))]
                           [nm
                            (full? policy nm cur-ns full-source-cfg current-full?)
                            (if prefix? :prefix :body)]))
                       rows)
        ;; Render ONE row: full → render-one (omitted when empty); else a
        ;; COMPACT card (it is a required ns). A card is nil when nothing is
        ;; indexed. Append the ns's indexed test source when it is in tests-set.
        render-row (fn [[nm full? _phase]]
                     (when-let [block-txt (if full?
                                            (render-one db nm cur-ns id)
                                            (compact-block db nm))]
                       (str block-txt
                            (when (contains? tests-set nm)
                              (ns-tests-block db nm)))))
        prefix-rows (->> selected
                         (filter (fn [[_ _ phase]] (= phase :prefix)))
                         (sort-by (fn [[nm _ _]] (name nm))))
        body-rows   (filterv (fn [[_ _ phase]] (= phase :body)) selected)
        prefix-blocks (keep render-row prefix-rows)
        body-blocks   (keep render-row body-rows)
        blocks        (concat prefix-blocks body-blocks)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))

;; ============================================================
;; The COMPACT card renderer — a SIBLING detail-level to
;; [[seon.agent.ctx/render-one-ns-ai]]'s full-source block, NOT a
;; replacement. A card is 3–5× smaller than full source: it keeps the
;; whole `register!` data model + every public fn's `:malli/schema`
;; I/O contract + its arglist, and elides only the fn BODY (`…`) and
;; the deep multiline prose (all but docstring line 1). This is the
;; coverage lever — for the budget of ~11 full nses the agent instead
;; sees its ENTIRE function surface as cards.
;;
;; It reads INDEXED ROWS ONLY (`:seon.fn/_ns`, `:seon.schema/_ns`),
;; never a file read — code-as-data, the boot indexer is the one reader.
;; Every helper is errors-as-values: a bad row degrades one line, never
;; throws into the render.
;;
;; WIRED into [[namespaces-block]]: the current ns + `::full-source` pins
;; render full, the current ns's `:require`s render here as compact cards.
;; ============================================================

(defn- abbrev-ns-kws
  "Rewrite every fully-qualified keyword whose namespace is `ns-str`
   (`:my.kb/claim`) to its `::`-abbreviated form (`::claim`) in string
   `s`. A literal prefix replace of `\":<ns>/\"` → `\"::\"`: the trailing
   `/` means a SIBLING namespace (`:my.kb.source/rating`) is left intact.
   No regex — the ns dots are literal."
  [s ns-str]
  (str/replace s (str ":" ns-str "/") "::"))

(defn- soft-clip
  "Return `s` unchanged when ≤ `n` chars, else clipped to `n` chars with a
   trailing `…` (the last char is the ellipsis). Interim guard until the
   corpus's docstring line 1 reliably complies with the ≤78 convention."
  [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- compact-schema-line
  "One `(register! <key> <form>)` line for a schema row the ns OWNS.
   `<form>` is the LIVE registry definition ([[seon.schema/schema-definition]]),
   falling back to the persisted `:seon.schema/source`, then a `<not
   registered>` note — kept VERBATIM (real runnable `register!`), with
   ns-local keywords abbreviated to `::`. Errors-as-values: a lookup that
   throws degrades to the source/`<not registered>` fallback."
  [ns-str {:seon.schema/keys [key source]}]
  (let [key-str (if (= (namespace key) ns-str)
                  (str "::" (name key))
                  (pr-str key))
        def     (when (keyword? key)
                  (try (schema/schema-definition key) (catch :default _ nil)))
        form    (cond
                  (some? def)               (pr-str def)
                  (not (str/blank? source)) (str/trim source)
                  :else                     "<not registered>")]
    (str "(register! " key-str " " (abbrev-ns-kws form ns-str) ")")))

(defn compact-arities
  "The arity portion of a compact fn head, from an arglists string.

   Derived from the stored `:seon.fn/arglists` string
   (`\"([{:my.kb/keys [a]}])\"`). Single arity → `[args] …`; multi-arity →
   `([a] …) ([a b] …)`. Errors-as-values: an unreadable arglists string
   falls back to its raw text (outer parens stripped) with an elided
   body. Public: the `:function-menu` menu section
   (`seon.agent.ctx.menu`) renders its glyph entries with the SAME
   arity grammar as these compact cards."
  {:malli/schema [:=> [:catn [::arglists [:maybe :string]]] :string]}
  [arglists]
  (let [parsed (try (edn/read-string arglists) (catch :default _ nil))]
    (cond
      (and (seq? parsed) (seq parsed) (every? vector? parsed))
      (if (= 1 (count parsed))
        (str (pr-str (first parsed)) " …")
        (str/join " " (map (fn [v] (str "(" (pr-str v) " …)")) parsed)))
      :else
      (let [s (str/trim (or arglists ""))
            inner (if (and (str/starts-with? s "(") (str/ends-with? s ")"))
                    (subs s 1 (dec (count s)))
                    s)]
        (str inner " …")))))

(defn- compact-fn-head
  "One public fn condensed to a single-line `defn` HEAD: `(defn name
   \"<doc line 1>\" {:malli/schema <spec>} [args] …)` — real Clojure, body
   elided with `…`. Docstring line 1 is soft-clipped at 78; a fn with no
   docstring omits the string; a fn with no `:malli/schema` omits the
   metadata map; ns-local keywords in the spec + arglist abbreviate to
   `::`. Multi-arity specs/arglists pass through unchanged."
  [ns-str {:seon.fn/keys [sym arglists doc spec]}]
  (let [nm      (if-let [i (str/index-of sym "/")] (subs sym (inc i)) sym)
        doc-1   (when (and doc (not (str/blank? doc)))
                  (soft-clip (str/trim (first (str/split-lines doc))) 78))
        docpart (if doc-1 (str " " (pr-str doc-1)) "")
        specpart (if (and spec (not (str/blank? spec)))
                   (str " {:malli/schema " spec "}")
                   "")
        arities (compact-arities arglists)
        head    (str "(defn " nm docpart specpart " " arities ")")]
    (abbrev-ns-kws head ns-str)))

(schema/register! ::render-one-ns-compact-request
  [:map
   [:seon.ns/name :seon.ns/name]
   [:seon.db/db   :seon.db/db]])

(defn render-one-ns-compact
  "Render ONE namespace as a COMPACT CARD string.

   The ns's `register!` schema block (KEPT verbatim) plus every PUBLIC fn
   condensed to a one-line `defn` head with the body elided (`…`), inside the standard
   `;;; ┌─/└─` demarcation ([[seon.agent.ctx/ns-demarc]]).

   Reads INDEXED ROWS ONLY (`:seon.schema/_ns` / `:seon.fn/_ns` off the
   `:seon.ns/name` entity) — NEVER a file read (code-as-data). A sibling
   detail-level to [[seon.agent.ctx/render-one-ns-ai]]'s full block, ~3–5×
   smaller. Errors-as-values: a ns with no `:seon.ns` entity renders a
   one-line note; a bad row degrades one line, never throws.

   Map-in: `{:seon.ns/name <keyword> :seon.db/db <db-value>}`. Returns the
   card string."
  {:malli/schema [:=> [:cat ::render-one-ns-compact-request] :string]}
  [{ns-kw :seon.ns/name db :seon.db/db}]
  (let [ns-str (name ns-kw)]
    (if-not (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.ns/name ns-kw]})
      (ctx/ns-demarc ns-kw "; (not in db — not indexed)")
      (let [pull    (db/pull
                      {:seon.db/db db
                       :seon.db/ref [:seon.ns/name ns-kw]
                       :seon.db/pull-pattern
                       '[{:seon.fn/_ns     [:seon.fn/sym :seon.fn/arglists
                                            :seon.fn/doc :seon.fn/spec
                                            :seon.fn/private?]
                          :seon.schema/_ns [:seon.schema/key :seon.schema/source]}]})
            schemas (->> (:seon.schema/_ns pull)
                         (filter (fn [{:seon.schema/keys [key]}]
                                   (= (namespace key) ns-str)))
                         (sort-by (comp str :seon.schema/key)))
            fns     (->> (:seon.fn/_ns pull)
                         (remove :seon.fn/private?)
                         (sort-by :seon.fn/sym))
            reg-lines (map #(compact-schema-line ns-str %) schemas)
            fn-lines  (map #(compact-fn-head ns-str %) fns)
            parts (cond-> []
                    (seq reg-lines) (into reg-lines)
                    (and (seq reg-lines) (seq fn-lines)) (conj "" "; fns (body elided):")
                    (seq fn-lines)  (into fn-lines))
            body  (if (seq parts)
                    (str/join "\n" parts)
                    "; (nothing indexed)")]
        (ctx/ns-demarc ns-kw body)))))
