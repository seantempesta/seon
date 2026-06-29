(ns seon.agent.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt: CURATED,
   not render-everything. Each rendered ns is a full-source comment-block
   delimited by per-ns `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─`
   brackets ([[seon.agent.ctx/ns-demarc]]). ONLY the curated set renders:

     - FULL source (whole file, NO clipping) for the few nses an agent
       actually USES or OWNS:
         (a) the agent's CURRENT ns    — its complete working code;
         (b) every THIRD-PARTY ns      — non-seon, non-my (the
             `SEON_EXTRA_SRC` `acme` business logic the agent needs whole);
         (c) the kept `my.*` toolkit exemplars
             ([[canonical-full-my-ns]] — `my.kb` the runnable
             register→transact→query manual + the `my.data`/`my.ui`/`my.tile`
             canvas toolkit, each read end-to-end);
         (d) a small curated whitelist of `seon.*` framework tools
             ([[full-source-whitelist]]).
     - SIGNATURE-trimmed for every OTHER `my.*` ns — public verb signatures
       only (name + full arglist + one-line doc, bodies elided), the
       always-on cost-cut. Full body on demand via
       [[seon.agent.ctx/render-namespace]]. The render-vs-trim decision is
       one rule: [[body-detail]] (which `:seon.render/detail` each row gets).
       NOTE the boot indexer still STORES the full source for every `my.*`
       ns (the shared [[full-source-ns?]] rule) so the on-demand full render
       and the kept example both have real text — the trim is render-only.
     - Every OTHER `seon.*` framework ns is DROPPED from the rendered
       section — it is NOT shown here at all. It stays INDEXED (its
       `:seon.ns/name` + `:seon.fn` / `:seon.schema` / `:seon.test` rows)
       and SEARCHABLE — discoverable via `seon.agent.search` (ripgrep) or
       readable on demand via [[seon.agent.ctx/render-namespace]]. There is no
       blanket signature manifest: passive name-listing is replaced by active
       grep/query, taught in the `<system>` prose.
     - EXCEPTION — the framework nses the agent's CURRENT ns actually
       `:require`s render their PUBLIC API (fn signatures + one-line doc,
       bodies elided) so adding a require teaches the dep
       ([[required-api-blocks]]). The targeted opposite of the blanket dump:
       only the deps the agent reached for, capped
       ([[seon.config/requires-api-cap]]) + elided-with-note, self-healing on
       the `:seon.ns/requires` edges (drop the require → the API vanishes).

   Symbol-wired into the composer layout (`seon.agent.ctx/default-seed-blocks`) as
   `'seon.agent.ctx.namespaces/namespaces-block`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   The section NEVER re-reads files at render time (code-as-data): the
   boot indexer (`seon.client/ns-row`) is the ONE file-reader, and it
   stores the REAL full file text for exactly the nses rendered full
   (the same [[full-source-ns?]] rule + the extra-src roots),
   leaving the framework bulk a `(ns x)` stub — which this section never
   renders (those nses are dropped from the body; the stub still feeds the
   on-demand `render-namespace` path). So the full rows here are always
   real file source, never a reconstructed stub."
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]
    [seon.eval :as seval]))

;; ============================================================
;; The namespace-display selection rules — the ONE home for which
;; indexed :seon.ns rows render, and which render in FULL. Shared by
;; the boot indexer (`seon.client/ns-row`, the one file reader) and
;; [[namespaces-block]] (the curated namespaces prompt body):
;; one rule, one writer, no drift. Pure string/keyword/symbol fns —
;; no dependency on anything in `seon.agent.ctx`.
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace (or any of its children) is
   indexed but NEVER rendered — the naming convention IS the filter.
   String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn my-ns-name?
  "Rule 2: `my.*` is the human's world — always shown, provenance not
   consulted (one name rule, no special cases)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (= s "my") (str/starts-with? s "my.")))))

(defn test-ns-name?
  "Rule 1b: a `*-test` namespace is indexed but NEVER rendered into the
   agent prompt — its `deftest`s are noise to the working agent, and the
   per-fn `:test` usage example already rides the regular fn's attr-map in
   the compact head. Full tests stay reachable on demand via
   [[seon.agent.ctx/render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the
   suffix IS the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/ends-with? s "-test")))

(defn included-ns?
  "The ONE selection rule for the namespace sections: EVERY indexed
   :seon.ns row renders EXCEPT *.internal (hidden-ns-name?) and *-test
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

(def full-source-whitelist
  "The LEAN whitelist of `seon.*` FRAMEWORK namespaces shown to every agent
   IN FULL — kept deliberately tiny so the agent is not buried in framework
   code. Just one set-up tool, shown as an EXAMPLE (already aliased in the
   agent's home ns), not an exhaustive API dump. This is the clear EDITABLE
   def to extend.
     - `:seon.agent.todo` — the work-list tool an agent calls directly:
       `register!` per attr, map-in/map-out `:malli/schema` fn shapes,
       error-as-value envelopes, the todo verbs the system prompt teaches
       by name.
   EVERYTHING ELSE in the framework is curated OUT — the rest of `seon.*`
   (db, search, fs, message, lifecycle, schema, render, …) is NOT dumped
   here. It stays INDEXED and grep-able via `seon.agent.search` and readable
   on demand via [[seon.agent.ctx/render-namespace]] — one search away, never a
   wall of code the agent must wade through. `my.*` nses are ALREADY rendered
   full by the `my.*` rule in [[full-source-ns?]] — including `my.kb`, the
   worked, runnable DB manual — so they do NOT belong here; this whitelist is
   ONLY for the seon.* tool example. Shared by the boot indexer (which stores
   their real file source — see `seon.client/ns-row`) and
   [[namespaces-block]] (which renders them FULL while the rest of the
   framework is DROPPED from the rendered section — still indexed +
   searchable)."
  #{:seon.agent.todo})

(def verb-signature-whitelist
  "The seon.* VERB namespaces whose PUBLIC SIGNATURES (arglists + doc line,
   bodies elided — `:seon.render/detail :signature`) render in every agent
   prompt so the verb is DISCOVERABLE — a bare home-ns alias is not enough; the
   agent must SEE the arglist to call `(message/user content)`, `(complete
   result)`, or `(agent/start! {…})`. A MAP `ns → selector`:
     - `:all` — surface the WHOLE public API of that ns (the dedicated verb
       nses `seon.agent.message` / `seon.agent.lifecycle`, aliased/refer'd into
       the home ns by [[seon.eval/setup-agent-ns!]], matched to
       [[seon.eval/home-ns-require-specs]]).
     - `#{\"fn\" …}` — surface ONLY those fns' signatures. `seon.agent` is a
       large framework ns (boot!, the wake predicates, …) so dumping it whole
       is noise; we surface JUST the spawn verbs `start!`/`create!`/`delegate!`
       — the discovery gap that left an agent able to `terminate` a child
       (rendered) but not SPAWN one (hidden). `agent/` is already aliased into
       the home ns.
   These are NOT in [[full-source-whitelist]] (no full-body dump — just the API
   surface). `seon.db` / `seon.schema` / `seon.agent.todo` self-document
   elsewhere (todo is full source; db/schema via grep + the system prose)."
  {:seon.agent.message   :all
   :seon.agent.lifecycle :all
   :seon.agent           #{"start!" "create!" "delegate!"}})

(def canonical-full-my-ns
  "The `my.*` namespaces kept rendered in FULL under the signature
   render-trim — the worked exemplars a cold agent must see END TO END (so it
   learns the complete patterns, not just isolated signatures). Every OTHER
   `my.*` ns render-trims to its public verb SIGNATURES (name + full arglist
   + one-line doc — rich enough to CALL the toolkit correctly) via
   `:seon.render/detail :signature`; their full bodies stay one
   `(seon.agent.ctx/render-namespace {:seon.ns/name … :seon.render/detail
   :full})` away. The seeded `my.*` toolkit is kept full (worked end-to-end):
   - `my.kb` — the DB manual: runnable RECIPES covering schema-register,
     write, upsert/retract, datalog query, pull, and an aggregate, with a
     single end-to-end `build-kb-example!`.
   - `my.data` — the aggregation TOOLKIT. Its value is the worked composition
     chain `(reducer (merge (producer …) {:my.data/key k}))` tying
     `rows → group-sum → max-by`, which lives in the verb-body + ns
     docstrings — NOT in the signatures. DRIVE-PROVEN (2026-06-28,
     `namespaces-trim-validation`): with `my.data` signature-only the agent
     never called it and hand-rolled `db/query`+`group-by`, re-opening the
     dedup footgun `my.data` exists to close.
   - `my.ui` — the STATIC canvas pieces (status-line / table / section to the
     dual-render `:seon.render/html-response` envelope); the worked move is
     COMPOSING pieces into a section, then transacting its hiccup onto the
     live tile.
   - `my.tile` — the INTERACTIVE canvas controls (button / input wired to one
     of the agent's OWN fns via the `/agent/<id>/call` gate); the callback
     mechanism lives in the verb bodies, not the signatures.
   Toolkit USABILITY is kept full; only framework BULK is trimmed.
   The clear EDITABLE def to change which exemplars are kept full
   (config-wiring is a separate later step)."
  #{:my.kb :my.data :my.ui :my.tile})

(defn in-full-source-whitelist?
  "True when `ns-name` (string, keyword, or symbol) is one of the curated
   seon.* framework [[full-source-whitelist]] nses. String/keyword/symbol
   tolerant — the indexer hands a string, the renderer a keyword."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (contains? full-source-whitelist
             (if (keyword? ns-name) ns-name (keyword (str ns-name)))))

(defn full-source-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) carries its
   REAL FULL FILE TEXT as `:seon.ns/source`: every `my.*` ns (the
   human's world — always inlined), including `-test` siblings (the
   `-test` suffix is stripped to the subject ns first), AND every curated
   [[in-full-source-whitelist?]] seon.* tool (so a framework tool like
   `:seon.agent.todo` gets its REAL body stored — private helpers and
   comments included). Used by the boot indexer (`seon.client/ns-row`) to
   decide which rows get the file read; the SAME rule decides which rows
   [[namespaces-block]] renders FULL — one rule, one writer, no drift.
   Third-party (`acme`) roots are full-source too, gated separately by
   `seon.client/extra-src-ns-strs` (the same file read). Every other ns
   gets the minimal `(ns x)` stub at boot and is DROPPED from the rendered
   section (still indexed + searchable)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (or (my-ns-name? base)
                      (in-full-source-whitelist? base))))))

(defn- third-party-ns?
  "A render-time structural rule: an included ns that is NEITHER `seon.*`
   framework NOR `my.*` is THIRD-PARTY business logic (the `acme`
   `SEON_EXTRA_SRC` code) — rendered FULL, no clipping. `my.*` is full via
   [[full-source-ns?]] already; this catches the remaining
   non-seon roots. String/keyword tolerant."
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (not (str/starts-with? s "seon."))))

(defn- body-detail
  "The DETAIL LEVEL at which an included ns renders in the namespaces BODY,
   or nil when the ns is NOT a body ns (a dropped `seon.*` framework ns —
   still indexed + grep-able, just never dumped here). The ONE place the
   full-vs-signature-trim decision lives:
     - `:full` — the agent's CURRENT ns (`cur-ns`), every THIRD-PARTY
       (`acme`) ns, the curated [[full-source-whitelist]] seon.* tools, and
       the kept [[canonical-full-my-ns]] `my.*` toolkit exemplars. Whole
       real source, unclipped.
     - `:signature` — every OTHER `my.*` ns: its public verb signatures
       (name + full arglist + one-line doc, bodies elided) — the API-surface
       view, a fraction of the always-on cost yet rich enough to call the
       toolkit. Full body on demand via [[seon.agent.ctx/render-namespace]].
   `my.*` is decided BEFORE [[third-party-ns?]] (which also matches non-seon
   `my.*`) so the signature-trim wins; only genuinely non-seon, non-my acme
   roots fall through to the third-party `:full` branch."
  [nm cur-ns]
  (cond
    (= nm cur-ns)                       :full
    (my-ns-name? nm)                    (if (contains? canonical-full-my-ns nm)
                                          :full
                                          :signature)
    (third-party-ns? nm)                :full
    (in-full-source-whitelist? nm)      :full
    :else                               nil))

(def ^:private namespaces-header
  ;; Block-specific cue ONLY — the FULL-vs-queryable policy (what renders in
  ;; full, what stays indexed/searchable) lives once in
  ;; `seon.agent.ctx/system-text` (§"THE NAMESPACES BELOW"); don't re-teach it.
  (str "; The loaded namespaces below, ordered by recency"
       " (most-recently-modified last)."))

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
   [[seon.eval/home-ns-form]], NOT a bare-name reconstruction from
   `:seon.ns/requires`. No hidden aliasing: the agent reads the form and knows
   `message/user`, `db/transact!`, `schema/register!`, `wait`, `complete`
   exist and how to call them. `nm` is a ns-name keyword whose `:seon.ns/name`
   row the caller already matched (an included, current-ns row)."
  [_db nm]
  (ctx/ns-demarc
    nm
    (str (seval/home-ns-form nm) "\n"
         "; (your workspace — nothing defined here yet; define schemas + fns and they appear here)")))

(defn- render-one
  "Render ONE included ns through the SINGLE renderer
   ([[seon.agent.ctx/render-namespace]]) at the chosen detail LEVEL, flat (depth
   0 — no require-recursion; the section renders each ns once). `:full`
   yields the whole-ns view (real file source + members); `:signature`
   yields the `(signatures)` API-surface block.

   The agent's CURRENT ns (`cur-ns`) ALWAYS renders, even when empty: an
   empty current ns becomes a [[cur-ns-workspace-stub]] (GI-2) so the
   prompt's 'YOUR OWN namespace renders in full' promise holds. Every OTHER
   full ns with nothing real to show is omitted (nil) — the empty-store
   edge; the boot indexer guarantees real text for every other full row.

   `members` (optional) narrows a `:signature` render to JUST those fn names —
   how a large ns surfaces a FEW verbs (the spawn verbs of `seon.agent`)
   without dumping its whole public API."
  ([db nm detail cur-ns] (render-one db nm detail cur-ns nil))
  ([db nm detail cur-ns members]
   (let [txt    (-> (ctx/render-namespace
                      (cond-> {:seon.ns/name      nm
                               :seon.render/depth 0
                               :seon.render/detail detail
                               :seon.db/db        db}
                        (seq members) (assoc :seon.ns/members (vec members))))
                    :seon.render/text
                    str/trim)
         ;; render-namespace brackets even an empty ns, whose body is then
         ;; `; (no recorded source/fns/schemas)` (entity present, no
         ;; source/members) or `; requires: x (not in db)` (the home ns —
         ;; a :seon.ns/name row whose sparse pull returns nil). Both mean
         ;; "nothing real to show."
         empty? (or (str/blank? txt)
                    (and (= detail :full)
                         (or (str/includes? txt "(no recorded source/fns/schemas)")
                             (str/includes? txt "(not in db)"))))]
     (cond
       (= nm cur-ns) (if (and (= detail :full) empty?)
                       (cur-ns-workspace-stub db nm)
                       txt)
       empty?        nil
       :else         txt))))

(def ^:private required-api-header
  ;; Sub-cue for the required-dep API surface — the agent's OWN code renders
  ;; full above; THIS is how the deps it `:require`s actually work (public
  ;; signatures only, bodies elided). Add a require → its API appears here.
  (str "; ── API of the namespaces your current ns :requires"
       " (public signatures — add a require to learn a dep) ──"))

(defn- required-api-rows
  "The ns-name keywords whose PUBLIC API (signatures) should render because
   the agent's CURRENT ns `:require`s them, EXCLUDING everything already
   surfaced elsewhere: the `already-shown` set (full blocks + verb-sig blocks
   + the current ns), every full-source ns (`my.*` / whitelist — shown full),
   and every third-party (`acme`) root (shown full). What remains is exactly
   the `seon.*` FRAMEWORK deps the agent pulled in — `seon.db`, `seon.schema`,
   `seon.agent`, or anything it adds — that would otherwise be DROPPED. A
   required ns with no indexed `:seon.ns` row (e.g. `clojure.string` — never
   first-party-indexed) is skipped: we surface seon-authored APIs, never
   core-lib internals. Name-sorted for a stable cache prefix.

   Pure fn of the DB: drop a require → its row leaves `:seon.ns/requires` →
   this returns one fewer ns → the API section self-heals."
  [db cur-ns already-shown]
  (when cur-ns
    (->> (db/query
           {:seon.db/db db
            :seon.db/query
            '[:find ?r
              :in $ ?ns
              :where
              [?e :seon.ns/name ?ns]
              [?e :seon.ns/requires ?r]]
            :seon.db/args [cur-ns]})
         (map first)
         (filter keyword?)
         (remove already-shown)
         (filter included-ns?)
         (remove full-source-ns?)
         (remove third-party-ns?)
         distinct
         sort
         vec)))

(defn- required-api-blocks
  "Render the [[required-api-rows]] at `:signature` detail, each a bracketed
   `(signatures)` block, applying the [[seon.config/requires-api-cap]] total
   CHAR budget: name-sorted blocks accrue until the budget is spent, then the
   tail is ELIDED with a one-line note naming the dropped nses + how to read
   them (grep / `render-namespace`). No silent truncation. Returns a (possibly
   empty) vector of block strings; an empty result yields no section."
  [db cur-ns already-shown]
  (let [rows   (required-api-rows db cur-ns already-shown)
        ;; Render each candidate; skip ones with no real public API to show
        ;; (a row with no public fns, or a require not in the index).
        blocks (->> rows
                    (keep (fn [nm]
                            (when-let [txt (render-one db nm :signature cur-ns)]
                              (when-not (or (str/includes? txt "(not in db)")
                                            (str/includes? txt "no public fns indexed"))
                                [nm txt])))))
        cap    (config/requires-api-cap)]
    (if (empty? blocks)
      []
      (let [{:keys [kept used elided]}
            (reduce (fn [{:keys [kept used elided] :as acc} [nm txt]]
                      (let [n (count txt)]
                        (if (and (seq kept) (> (+ used n) cap))
                          (update acc :elided conj nm)
                          (-> acc
                              (update :kept conj txt)
                              (assoc :used (+ used n))))))
                    {:kept [] :used 0 :elided []}
                    blocks)
            elided-note (when (seq elided)
                          (str "; (+" (count elided) " more required-ns API"
                               (when (> (count elided) 1) "s")
                               " elided for space: "
                               (str/join ", " (map name elided))
                               " — grep them or (seon.agent.ctx/render-namespace"
                               " {:seon.ns/name " (pr-str (first elided))
                               " :seon.render/detail :signature}))"))]
        (cond-> (into [required-api-header] kept)
          elided-note (conj elided-note))))))

(defn namespaces-block
  "CURATED namespaces body. Routes EVERY included ns through the SINGLE
   renderer [[seon.agent.ctx/render-namespace]] — no parallel hand-rolled paths.
   The per-ns DETAIL LEVEL is the only choice the section makes
   ([[body-detail]]):

     - FULL (`:seon.render/detail :full`) for every THIRD-PARTY `acme` ns,
       the agent's CURRENT ns, the curated [[full-source-whitelist]] seon.*
       tools, and the kept [[canonical-full-my-ns]] `my.*` toolkit
       exemplars — each a `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─`
       bracketed block carrying its REAL FULL FILE SOURCE, unclipped.
     - SIGNATURE (`:seon.render/detail :signature`) for every OTHER `my.*`
       ns — its public verb signatures (name + full arglist + one-line doc,
       bodies elided): the always-on render-trim. The full body is one
       [[seon.agent.ctx/render-namespace]] (`:detail :full`) call away.
     - Every `seon.*` framework ns is DROPPED from the rendered section —
       not shown here at all. It stays INDEXED and SEARCHABLE (via
       `seon.agent.search`) and readable on demand via
       [[seon.agent.ctx/render-namespace]].

   The full blocks are ordered by RECENCY (tx of the `:seon.ns/name` datom —
   bumped by the tee's nested upsert on every define), name as the
   tie-break, so the stable core forms a stable cache prefix and the
   churning ns sits nearest the tail.

   `*.internal` and `*-test` nses are excluded outright ([[included-ns?]]).
   A full ns whose stored source/members are all empty renders nothing
   (omitted). NEVER a render-time file read — the boot indexer is the one
   reader; render-namespace reads only indexed rows."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [;; The agent's current ns (latest successful eval's ns) → rendered
        ;; FULL even if it is a framework ns. nil id (inspector path) →
        ;; nil → no ns is forced current.
        cur-ns (when id
                 (try (when-let [c (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                        ;; current-ns yields a KEYWORD from a recorded eval but
                        ;; a SYMBOL from the (home-ns id) fallback (a fresh agent
                        ;; with no successful evals yet) — normalize to a keyword
                        ;; so the `(= nm cur-ns)` match against the keyword ns
                        ;; rows holds in BOTH cases (GI-2 fires even on turn 0).
                        (keyword (name c)))
                      (catch :default _ nil)))
        ;; EVERY included ns row, recency-ordered. One :seon.ns/name datom
        ;; per ns carries its tx.
        rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?tx
                         :where
                         [?n :seon.ns/name ?nm ?tx]]})
                    (filter (fn [[nm _]] (included-ns? nm)))
                    (sort-by (fn [[nm tx]] [tx (name nm)])))
        ;; Every included ns that renders in the BODY, each tagged with the
        ;; DETAIL it renders at ([[body-detail]]): :full for the current ns,
        ;; third-party (acme) roots, the curated seon.* full-source tools,
        ;; and the kept my.* toolkit exemplars; :signature for every OTHER
        ;; my.* ns (verb signatures, bodies elided — the render-trim). Every
        ;; seon.* framework ns is nil → DROPPED from the rendered section
        ;; (still indexed + grep-able via seon.agent.search). recency-ordered.
        body-rows (keep (fn [[nm _tx]]
                          (when-let [d (body-detail nm cur-ns)] [nm d]))
                        rows)
        ;; The aliased/refer'd VERB nses (message + lifecycle) render their
        ;; public SIGNATURES — arglists, bodies elided — so the agent SEES how
        ;; to call `(message/user …)` / `(complete …)`, not just a bare alias.
        ;; Name-sorted, rendered FIRST as a stable cache prefix ahead of the
        ;; churning body blocks. No overlap with body-rows (a seon.* verb ns
        ;; is dropped by body-detail — not my/third-party/whitelist).
        sig-rows  (->> rows
                       (filter (fn [[nm _tx]] (contains? verb-signature-whitelist nm)))
                       (sort-by (fn [[nm _tx]] (name nm))))
        ;; each whitelisted ns renders its signatures; a `#{names}` selector
        ;; narrows to JUST those verbs (seon.agent → start!/create!), `:all`
        ;; surfaces the whole public API (message + lifecycle).
        sig-blocks  (keep (fn [[nm _tx]]
                            (let [sel     (get verb-signature-whitelist nm)
                                  members (when (set? sel) sel)]
                              (render-one db nm :signature cur-ns members)))
                          sig-rows)
        body-blocks (keep (fn [[nm d]] (render-one db nm d cur-ns)) body-rows)
        ;; The PUBLIC API (signatures) of the framework deps the agent's
        ;; current ns :requires but does NOT render full/verb-sig elsewhere —
        ;; so adding a require surfaces how that dep works. Everything already
        ;; surfaced (full + verb-sig + cur-ns) is excluded; capped + elided.
        ;; Rendered as a STABLE reference prefix ahead of the churning full
        ;; blocks (same cache rationale as sig-blocks).
        already-shown (into (conj (set (map first body-rows)) cur-ns)
                            (map first sig-rows))
        req-blocks  (required-api-blocks db cur-ns already-shown)
        blocks      (concat sig-blocks req-blocks body-blocks)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
