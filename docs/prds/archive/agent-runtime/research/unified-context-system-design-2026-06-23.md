---
type: research
status: active
tags: [research, agent, schema, flow]
---

# Unified Context System Design

One render mechanism for the entire agent context, so context problems become
structurally detectable instead of grep-hunted.

## TL;DR

The render layer is ALREADY converged at the plumbing level: one composer
(`seon.ctx/assemble-context`, ctx.cljs:1705), one `ai-render`/`html-render`
late-bound resolver pair (render.cljs:158/167), one dynamic section register
(`core-default-ctx` merged with the agent's `:seon.agent/ctx` by
`merge-sections`). KEEP all of that. What is NOT converged is the CONTENT each
section emits: four section bodies are bare prose or bare-data that would throw
a reader error if pasted (`:system` ~14k chars, `:your-entity`, `:live-tile`,
`:inventory`), the `:namespaces` body double-renders every full-source ns and
emits schemas as non-eval'able `[schema :k]` labels AFTER the fns instead of
`(register! ...)` forms deps-first ABOVE them, there is no `:seon.ctx/cache-tier`
field (the cache boundary is hard-coded to "index of `:namespaces`"), and only
ONE section (`:transcript`) declares an HTML twin.

The fix is entirely in-place and additive. Make EVERY section body valid
Clojure: prose lines get `;;`/`;;;` prefixes, in-band data becomes real forms
or `#seon/elided` tagged literals, and every section is wrapped in one shared
`;;; ┌─ name ─` / `;;; └─ end name ─` bracket pair emitted by `render-section`
at the composer (turtles: one rule for all sections, including agent and
third-party slots). Replace the `:namespaces` source-dump + `[schema]`/`[fn]`
member labels with a single graph-driven generator (D6) that reads indexed-code
DB rows, walks each shown fn's `:seon.fn/spec` for referenced registered schema
keywords, takes the dep-closure, topo-sorts, and emits `(register! :k <form>)`
deps-first above the fn. Add five pure read-fns to `seon.schema` to power that
walk. Add `:seon.ctx/cache-tier` to the section schema and split the prefix
programmatically after the last non-`:live` section. Then build the
CONTEXT-HEALTH CHECKER — a pure fn of the rendered section-texts that parses
each bracketed region with the reader and asserts the invariants, surfaced both
as a reactive render section (the agent sees its own context problems) and a
debug-viewer panel (the human does). Because the context is eval'able Clojure,
"is this section healthy" becomes `(read-string section-body)` succeeding plus a
handful of structural assertions — not grep.

Thin slice: `seon.db` first, graph-generated colocated + bracketed + Tier-A,
live-driven on DeepSeek, measured by elision delta. No `*-v2`, no parallel path.

Two scope boundaries set up front, because they shaped the design (see
"Verification & open risks"):

- The SOUL system message (`my.soul/system-prompt-text`, ~16.8k chars,
  `effective-system-prompt` ai.cljs:357) is block 1 of every real prompt and
  lives OUTSIDE `assemble-context`. It is prose identity BY DESIGN — the
  honest boundary, not a context bug. It is scoped OUT of the eval'able-context
  thesis, but the health checker carries it as an input so it is at least
  MEASURED and its soul-boundary marker asserted present. The thesis "no
  wall-of-text AGENTS.md" applies to the runtime-authored CONTEXT, not the
  human/agent-authored identity prompt.
- The context-health checker is REGISTRY-AWARE and ARTIFACT-AWARE, not a pure
  textual `read-string`. Two invariants (`:reads-as-clojure`,
  `:schemas-above-fns`) need the live schema registry and a known grammar of
  runtime-written artifact lines (the readline cursor, `;;=>` result lines,
  `#seon/elided`). This was forced by the live transcript — see C5/critic.

## Current state — the assembled-context pipeline as one list

The composer assembles sections in priority order, renders each through a
throw-guarded text twin (and an HTML twin if present), drops blanks, splits at a
hard-coded boundary into a cacheable prefix + volatile tail. Source of truth:
`assemble-context` ctx.cljs:1705, `core-default-ctx` ctx.cljs:1485,
`merge-sections` ctx.cljs:1592, `render-section` ctx.cljs:1613, boundary at
ctx.cljs:1768 `(set (take (inc (.indexOf names :namespaces)) names))`.

Each row below: section name, priority, producing fn, current evalability of
its body in the live prompt, its TRUE cache-tier (what it should be declared as
under D5), and the headline problem.

- `:system` (masthead) — priority 10 — `system-section` ctx.cljs:1015 ->
  `system-text` ctx.cljs:762. Evalability: BARE-PROSE. Cache-tier:
  `:cluster-static`. Problem: ~14,041 chars of un-commented English
  (rendered-prompt.clj:2-238). Opens `;; ── system ──` then line 2 `You are at
  a live Clojure REPL...` — `You` reads as an unresolved symbol. THE biggest D2
  violation, and it sits ABOVE the cache boundary. Has correct cache discipline
  otherwise (no counts/timestamps/ids). The COMMON DB OPS cheat-sheet inside it
  (rendered-prompt.clj:106-141) is already `;;`+forms.
- `:namespaces` (THE BODY) — priority 20 — `namespaces-section`
  namespaces.cljs:222 -> `render-namespace` ctx.cljs:1308 -> `render-one-ns-ai`
  ctx.cljs:1184. Evalability: PARTIAL. Cache-tier: `:cluster-static` (but see
  hazard: current-ns force-full makes it agent-specific). Problems: (1) FULL
  blocks dump verbatim `:seon.ns/source` (which already contains the real
  `register!`/`defn` forms) AND THEN re-emit the same members as
  `[fn sym]`/`[schema :k]` labels — a DUPLICATE render, one eval'able and one
  not (rendered-prompt.clj:514-768 verbatim, :770-824 labels). (2) Schemas are
  `[schema :k] <clipped form>` labels rendered AFTER the fns, not
  `(register! ...)` forms deps-first above them (D1 violation). (3) SIGNATURE
  blocks (~60 nses, rendered-prompt.clj:837+) are `[fn sym] (sig) :spec [...]`
  bare-data with specs truncated by ` …` (reader-poison), no schemas at all.
  (4) Recency-ordered by `:seon.ns/name` datom tx — any define reshuffles the
  cluster-static prefix, busting byte-stability.
- `:your-entity` — priority 30 — `your-entity-section` your_entity.cljs:14.
  Evalability: PARTIAL (clean `;;` header, then a BARE EDN map
  `{:db/id 94, :seon.agent/id "Zut-..." ...}` rendered-prompt.clj:1590 that the
  masthead itself teaches is an inert NO-OP). Cache-tier: PRD calls it
  `:agent-stable` but the binary split forces it `:live` today (its priority 30
  > `:namespaces`' 20). `:db/id 94` is reset-unstable.
- `:live-tile` — priority 35 — `live-tile-section` live_tile.cljs:18.
  Evalability: BARE-PROSE (rendered-prompt.clj:1595-1601: `Wired: ...`, the
  `Welcome card — ...` paragraph, `To change it: ...`). Cache-tier: `:live`.
- `:warnings` — priority 40 — `warnings-section` warnings.cljs:10 ->
  `seon.warn/render-warnings` warn.cljs:1038. Evalability: source already opens
  `;;; ── WARNINGS ──` (runtime voice) — closest precedent for the bracket, but
  no close marker. Cache-tier: `:live`. Blank this render (reactive, vanishes
  when empty).
- `:open-todos` — priority 45 — `open-todos-section` todo.cljs:758 ->
  `open-todos-block` todo.cljs:740. Evalability: BARE-PROSE in its with-data
  state (header + `<id> [age] <title>` lines un-`;;`'d). Cache-tier: `:live`.
  Blank this render (a detectability gap: only surfaces with todos).
- `:relevant-source` — priority 48 — `relevant-source-section`
  relevant.cljs:98. Evalability: PARTIAL, bare-prose `;;` header. Cache-tier:
  `:live`. Env-gated (SEON_EMBED off -> blank byte-identical path). Separate
  render path — does NOT reuse `render-namespace` or the schema graph.
- `:inventory` — priority 97 — `inventory-section` inventory.cljs:141.
  Evalability: DATA-but-not-eval'able. Cache-tier: `:live`. Header is the best
  D3 example (embeds a real `(seon.db/query ...)` template) but the body
  (rendered-prompt.clj:1619-1623) is a bespoke `seon.agent: id 1 ⟨"..."⟩ ...`
  DSL using `⟨…⟩` and `…` glyphs — neither a form nor a `;;` comment.
- `:transcript` (+ HTML twin) — priority 100 — `transcript-section`
  transcript.cljs:273 / `transcript-section-html` :398; rows via
  `format-eval-row` ctx.cljs:475. Evalability: COMPLETE-EVAL. Cache-tier:
  `:live`. THE D2/D3 north star already (rendered-prompt.clj:1625-1656): `;;;`
  masthead, `;;; ── turn N ──` headers, `;;; ◀` inbound, `;;` narration, forms,
  `;;=> value ;; result/<id>`, folded `ns=>` readline. ONLY missing piece: a
  `;;; └─ end` close marker; uses a `════` banner not the `┌─/└─` pair.

Reconciled contradictions across the 6 maps:

- `:your-entity` cache-tier: maps disagree (`:agent-stable` vs `:live`). RESOLVED:
  it is `:agent-stable` by intent (D5) but is FORCED `:live` today by the binary
  split. Strip `:db/id` from its render so it can join the agent-stable prefix.
- `:namespaces` cache-tier: declared `:cluster-static` but the current-ns
  force-full (namespaces.cljs:253) makes the block agent-specific — it is
  effectively `:agent-stable`. RESOLVED: move agent-current-ns promotion BELOW
  the cluster-static catalog into a separate agent-stable region.
- "Never dump `:seon.ns/source`" (D6) vs full-source nses storing the whole file
  there: RESOLVED below in the unified mechanism (emit the `(ns ...)` head from
  `:seon.ns/source`, the bodies from per-fn `:seon.fn/source` rows).
- Cross-ns "deps-before-dependents" ordering (D6) vs the `:seon.ns/requires`
  index: VERIFIED that `:seon.ns/requires` is populated for only 1 of 82 indexed
  nses (the agent's OWN home ns); the 81 boot-indexed nses have NIL requires
  (the MEMORY-flagged "index `:seon.ns/requires` at tee" unblock is still open).
  `render-namespace` works around this TODAY by re-parsing the `(ns ...)` source
  string via `parse-require-syms` (ctx.cljs:1057). RESOLVED: D6's cross-ns
  ordering may lean on the source-parse path today (it works), but Phase 0 fixes
  the tee to populate `:seon.ns/requires` so ordering becomes data-driven, not
  re-parsed. This is a HARD prerequisite for any DB-query-based cross-ns topo.

## The unified mechanism

ONE render path. A section is a map
`{:seon.ctx/name :seon.ctx/priority :seon.render/ai (+ optional :seon.render/html :seon.ctx/cache-tier)}`.
The register is `core-default-ctx` merged with the agent's `:seon.agent/ctx` by
`merge-sections`' single priority sort with override-by-name (ctx.cljs:1592).
`render-section` (ctx.cljs:1613) resolves the `:seon.render/ai` slot (string =
verbatim, qualified symbol = late-resolved via `seon.eval/lookup-value`),
THEN wraps the result in the shared bracket pair. Output is split into a
cacheable prefix (every non-`:live` section, in render order) and a volatile
tail. KEEP every word of that — it is the dynamic register + ordering + cache
mechanism the PRD wants preserved (D5). The work is to make the BODIES uniform.

The single mechanism, end to end:

1. graph-generated bodies (the indexed-code DB is the source) ->
2. colocated schemas deps-first, emitted as real `(register! :k <form>)` ->
3. D2 paired `;;; ┌─/└─ end` brackets wrapping every section (composer-level) ->
4. cache-tiered split by `:seon.ctx/cache-tier` (see cache topology below) ->
5. curated by plain DATA (per-ns fn/test sets; DEFAULT = everything when absent) ->
6. third-party-OVERRIDABLE (no curation config -> default everything -> overrides
   seon by the SAME generator; an agent `:seon.agent/ctx` entry wins by name; a
   string `:seon.render/ai` slot fully replaces a section).

### Cache topology — the three scopes do NOT collapse into one prefix

This is the design change the critic forced. There are THREE genuinely different
cache scopes, and `split-context` (ctx.cljs:1470) recovers exactly ONE
in-band boundary marker:

- `:cluster-static` — the masthead + namespaces catalog + colocated schemas.
  SHARED across all agents in the cluster. The whole point of D5: ONE cached
  copy of the big core.
- `:agent-stable` — `:your-entity` (sans `:db/id`), agent-current-ns promotion.
  Stable turn-to-turn but DIFFERS PER AGENT.
- `:live` — everything that changes per turn.

The earlier draft promoted `:your-entity`/current-ns into "the prefix" and said
"prefix = every non-`:live` section". That is WRONG if there is only one cache
breakpoint: appending per-agent (`:agent-stable`) bytes into the same prefix
that holds cluster-static bytes makes the WHOLE prefix per-agent, so the big
cluster-static core can no longer be cached once and shared — caching gets
WORSE, not better. RESOLVED, two acceptable topologies (pick by provider
capability, stated as an open decision):

- (A) SINGLE breakpoint (today's `split-context`): the prefix is
  `:cluster-static` ONLY. `:agent-stable` sections render into the VOLATILE tail
  (they are cheap — `:your-entity` is one small map). Cluster-static stays
  shared across agents. This is the safe default and what the thin slice
  targets.
- (B) TWO breakpoints: `split-context` emits a second in-band marker so the
  cached region is `[cluster-static][agent-stable]` with two cache scopes the
  provider can key independently. Only adopt if the provider supports
  multi-breakpoint prompt caching; otherwise (A).

The boundary is therefore computed by TIER, contiguously from the top: the
cluster-static prefix ends at the FIRST section whose tier is not
`:cluster-static` (a misplaced `:live` or `:agent-stable` section forces the
boundary up — this is the `:tiers-monotonic` invariant). It is NOT
`(.indexOf names :namespaces)` and NOT "last non-`:live`".

How each current path converges onto it (in-place edits, deletions named):

- The signatures whitelist (`render-full?` boolean + `full-source-whitelist`
  `#{:seon.agent.todo}`, namespaces.cljs:101-169): DELETED. Replaced by a
  plain-DATA curation config `{ns -> #{fn-syms} | :full | :curated}`, default
  everything when the ns has no entry. This is the structural unlock — selection
  becomes per-fn, not whole-file, so "is this fn shown with its schema above it"
  is answerable.
- `full-source-ns?` / the source-dump (`render-one-ns-ai` :full branch
  ctx.cljs:1216-1226): EDITED in place. Stop dumping `:seon.ns/source` as the
  body and stop appending member labels. Emit the `(ns ...)`/`(in-ns ...)` head,
  then per curated fn: its dep-closure schemas (real `register!` forms, topo,
  deps-first) then the fn's verbatim `:seon.fn/source`. This kills the
  double-render at its root.
- `schema-block-ai` (ctx.cljs:1159, the `[schema :k]` label emitter): DELETED.
  Its job moves to the schema read-layer emitter (below). `fn-block-ai`
  (ctx.cljs:1123, the `[fn sym]` label emitter, a parallel renderer that diverges
  from `seon.handlers.fn/render-ai` which the HTML path already uses): COLLAPSED
  into the graph generator so there is ONE producer of a fn block for both `:ai`
  and `:html`. The clip thresholds (240/280/80/200) are DELETED in favor of
  verbatim forms + `#seon/elided` for genuinely large bodies (D4-measurable, not
  a silent prose clip).
- `system-text` (ctx.cljs:762): EDITED in place — every prose line gets a `;;;`
  prefix (runtime voice; the runtime explaining the runtime). Zero token-shape
  change beyond the prefix. The half that is already `;;`+forms stays.
- The section framework (`core-default-ctx`, `merge-sections`, `render-section`):
  KEPT. Add `:seon.ctx/cache-tier` to each entry; add the bracket wrap to
  `render-section`; compute the boundary from the FIRST non-`:cluster-static`
  section (contiguous-from-top, `:tiers-monotonic`) instead of
  `(.indexOf names :namespaces)` (ctx.cljs:1768). The bracket wrap happens INSIDE
  `render-section` BEFORE the bytes leave the composer, so the health checker and
  the HTML twin both see the post-wrap text (that is what the agent sees). A
  verbatim string `:seon.render/ai` slot may opt OUT of auto-bracketing via a
  per-section flag (see open decisions) — needed so a third-party section that
  already carries its own header is not double-bracketed.
- The render dispatch (`ai-render`/`html-render`, render.cljs:158/167): KEPT
  unchanged — already converged. Give every section an HTML twin (a generic
  "render this section's `:seon.render/ai` text in a foldable code card" default
  for prose-y sections, rich panels for `:namespaces`/`:inventory`) so the
  inspector right pane genuinely mirrors the left.

The `:relevant-source` section (separate render path today) converges by calling
the SAME graph generator per shown fn rather than its bespoke
`render-hit` — one fn-block producer everywhere.

## The context-health checker

This is the user's core ask: make context problems detectable WITHOUT grep.
Because the rendered context is (after the unification) valid eval'able Clojure
delimited by paired `;;;` brackets, a structural validator can PARSE it and
assert invariants.

Where it lives: a new pure fn `seon.ctx/context-health` (the composer ns, which
already owns the section-texts). It is NOT a pure textual `read-string` — the
live transcript and the registry forced three extra inputs (see "Verification &
open risks"): the soul text, the registered-key set, and a known grammar of
runtime-written artifact lines.

The input schema MUST match `assemble-context`'s actual return shape. Verified
(C4 + critic): `assemble-context` returns `:seon.render/section-texts` as a
VECTOR OF MAPS (`[{:seon.ctx/name _ :seon.render/text _}]`), NOT tuples — the
earlier `[:tuple name string]` shape would throw at the composer→checker
boundary under instrumentation. Each section-text entry also carries its
`:seon.ctx/cache-tier` (added in Phase 6) so the checker classifies prefix
membership from declared tier, not a re-derived boundary heuristic.

```clojure
;; one canonical section-text shape, reused by assemble-context AND the checker
(schema/register! ::section-text
  [:map
   [:seon.ctx/name       :seon.ctx/name]
   [:seon.render/text     :string]
   [:seon.ctx/cache-tier :seon.ctx/cache-tier]])   ;; :enum, see cache-tier below

(schema/register! ::health-request
  [:map
   [:seon.render/section-texts [:vector ::section-text]]
   [:seon.render/stable-text   :string]
   [:seon.render/volatile-text :string]
   [:seon.ai/system-prompt     :string]            ;; the SOUL — measured, marker asserted
   [:seon.health/registered-keys [:set :keyword]]]) ;; the live register, for classification

(schema/register! ::health-finding
  [:map
   [:seon.ctx/name        :seon.ctx/name]
   [:seon.health/invariant :seon.health/invariant]   ;; :enum (below)
   [:seon.health/detail   :string]
   [:seon.health/severity [:enum :error :warn :info]]])

(schema/register! ::health-response
  [:map [:seon.health/findings [:vector ::health-finding]]])

(defn context-health
  {:malli/schema [:=> [:cat ::health-request] ::health-response]}
  [{:seon.render/keys [section-texts stable-text volatile-text]
    :seon.ai/keys      [system-prompt]
    :seon.health/keys  [registered-keys]}] ...)
```

The `:reads-as-clojure` parse contract (PRECISE, because the cleanest section —
the transcript — contains runtime artifacts that are neither comments nor forms
and would crash a naive strip-and-read). A line is VALID iff it is one of:

1. a COMMENT line — first non-space chars are `;;` (this covers `;;` agent voice,
   `;;;` runtime voice, AND `;;=> <edn> ;; result/<id>` result lines, which start
   with `;;`);
2. part of a top-level FORM (the residual after removing 1/3/4 reads as a
   balanced form sequence);
3. a recognized RUNTIME ARTIFACT — the readline cursor line matching
   `^\S+=>\s*$` (e.g. `my.agent.Zut-2606232034=>`, rendered-prompt.clj:1656), or
   a bracket line `;;; ┌─`/`;;; └─` (already a comment, listed for clarity);
4. a line whose forms contain the `#seon/elided {…}` tagged literal — VALID only
   because the checker's `read-string` shares the SAME `:readers` map that sci
   eval uses (registered in Phase 0). Without that shared reader the tag THROWS
   and the checker crashes on exactly the sections the design prescribes it for.

The `:seon.health/invariant` enum is the checklist the validator asserts:

- `:reads-as-clojure` — apply the parse contract above per section: strip
  comment + recognized-artifact lines, then `(read-string (str "(do "
  remaining ")"))` with the shared `:readers` must succeed. Catches bare prose
  (`:system`, `:live-tile`), bare EDN (`:your-entity`), the bespoke `⟨…⟩` DSL
  (`:inventory`), reader-poison `[fn]`/`[schema]` labels, and truncating ` …`.
  Replaces grepping for un-commented lines.
- `:brackets-balanced` — every `;;; ┌─ X ─` has a matching `;;; └─ end X ─`,
  correctly nested, names paired. Stack walk over the bracket lines. A torn
  section is what makes a debug viewer unable to fold or elide-measure.
- `:schemas-above-fns` — REGISTRY-AWARE (not pure-textual). Within each ns block,
  for each `(defn f ...)`, classify a keyword in its body/spec as a SCHEMA REF
  only if it is a member of `registered-keys` (this excludes `:optional`,
  `:db/id`, datalog `:find`/`:where`, plain map keys). Assert every such
  schema-ref appears in a `(register! :k ...)` form at a LOWER line in the same
  block, or is a built-in (`:inst`). Without the registry input this invariant
  over-reports every `:foo/bar`; the registry is what makes it precise.
- `:no-dangling-schema-ref` — every keyword in `registered-keys` that a shown
  spec/fn references resolves to a `register!` form somewhere in the prefix or a
  `;; <built-in>` note. Catches orphan refs the agent can't define.
- `:prefix-stable-across-turns` — NOT determinism-within-one-snapshot (that is
  green by construction even with the recency bug present — refuted, see C4 +
  critic). Render the prefix under TWO cache-EQUIVALENT states (snapshot N, then
  N+1 after a volatile-only change, AND after the agent navigates ns) and assert
  the `:cluster-static` prefix bytes are identical. Equivalently: assert the
  cluster-static prefix render is a pure fn of cluster-static inputs only (no
  agent-id, no tx-recency, no current-ns). Catches recency-order churn,
  current-ns leakage, and non-deterministic `dep-closure` iteration order.
- `:no-volatile-above-boundary` + `:tiers-monotonic` — no
  counts/timestamps/entity-ids/`result/<id>` patterns above the boundary, AND
  the prefix contains ONLY a contiguous run of non-`:live` sections from the top:
  a `:live` section appearing before any non-`:live` section forces the boundary
  UP to that first `:live` section (regardless of later non-`:live` sections).
  This closes the mis-tiered-agent-section hole: a third-party section injected
  at low priority but `:live` tier cannot strand cached bytes after it. The
  boundary is computed from (tier, priority), not priority alone.
- `:section-measured` — every section has a non-zero char/token weight and is
  individually attributable (D4); flags empty-but-present sections and lets the
  viewer rank by weight. The soul block is measured here too (so its 16.8k chars
  are visible in the weight ranking even though it is exempt from
  `:reads-as-clojure`).
- `:soul-boundary-present` — the soul-boundary marker (`soul-boundary`,
  ai.cljs:383 region) is present between the system prompt and the assembled
  context. This is the ONLY soul invariant — the soul is prose by design; the
  checker asserts the honest boundary exists, it does not demand the soul read as
  Clojure.
- `:has-html-twin` — every text-twin-non-blank section has an `:seon.render/html`
  slot (the inspector-mirror invariant). The HTML twin is a pure fn of the FINAL
  (bracket-wrapped) AI text, so twin bytes == left-pane bytes.

Explicit scope: these are STRUCTURAL/format invariants. SEMANTIC context
problems — a `(message/user …)` call that returned `:seon.db/ok? false`
(rendered-prompt.clj:1652, missing `:seon.user/id "user"` seed entity), a stale
`;;=>` result that contradicts the live DB, an empty
`(my.kb.system/instructions) ;;=> []` — are NOT covered here. They are a
separate reactive-warnings concern (`seon.warn` already exists). The
context-health checker green-lighting a context does NOT mean every shown eval
succeeded; it means the context is well-formed eval'able Clojure with a stable
cacheable prefix.

Output shape: `{:seon.health/findings [...]}`, a vector of findings, EMPTY when
the context is healthy. This is reactive-context doctrine: nothing stored,
nothing acknowledged — when the underlying body is fixed the finding's check
returns clean and the finding vanishes.

How it surfaces (two derived views, derive-don't-store):

- A render section `:context-health` (priority ~5, `:live`) whose
  `:seon.render/ai` body renders the findings as `;;` lines — so the AGENT sees
  its own context problems and can fix the offending fn. Renders blank (and the
  section drops) when findings are empty — self-healing, no stored state.
- A debug-viewer panel in `seon.web.inspector` (a top card in
  `ai-pane-fragment`, inspector.cljs:321) computing `context-health` over the
  same `snapshot` section-texts and rendering findings + per-section weight bars.
  The viewer ALSO learns to fold on the in-section `;;; ┌─/└─ end` brackets (one
  regex pair, since seon owns the viewer) so the human can collapse per-ns and
  per-schema sub-blocks — the granularity where bloat hides. The right pane must
  include the `:soul-system` pseudo-section (the inspector's `ctx-preview`
  already prepends it on the LEFT, inspect.cljs, but the twin/health coverage
  does not include it today): add `:soul-system` to the twin so the right pane
  genuinely mirrors block 1, and show its weight bar (16.8k chars) so the human
  sees how much of the prompt is the non-eval'able identity prose.

## The schema read-layer

Five pure, additive read-fns in `seon.schema` (the ns that owns `*schemas`, so
zero require-cycle risk — `db`/`warn`/`ctx` all already depend on it). None
exist today (verified: only `registered-schemas`/`schema-definition`/
`schemas-in-namespace`/`current-keys`/`enum-members`/`identity-attr?` are
public). Generalizes `db/internal.cljs:147 resolve-malli-form` from "resolve to
one datahike type" to "collect all referenced keys".

```clojure
(schema/register! ::schema-form     [:or :keyword :vector])      ;; form-storable shape
(schema/register! ::schema-key      :keyword)
(schema/register! ::closure         [:map-of ::schema-key ::schema-form])
(schema/register! ::ordered-defs    [:vector [:tuple ::schema-key ::schema-form]])

(defn all-schemas
  "The form-storable subset of the global register: {key form}, dropping the
   opaque IntoSchema entries (:inst, :seon.flow/dynamic) that cannot round-trip
   as register! forms."
  {:malli/schema [:=> [:cat] ::closure]}
  [] ...)   ;; (into {} (filter (fn [[_ v]] (or (keyword? v) (vector? v))) (registered-schemas)))

(defn immediate-deps
  "Every REGISTERED schema key referenced directly by `form` (a stored shape),
   excluding the form's own key. Walks (tree-seq coll? seq form) collecting
   keywords whose stored value is itself a form (keyword?/vector?). Props
   keywords like :optional/:seon.db/component are not registered -> filtered."
  {:malli/schema [:=> [:cat [:cat ::schema-form]] [:set ::schema-key]]}
  [form] ...)

(defn spec-deps
  "Schema deps referenced by a stored :seon.fn/spec STRING. Reads the string to
   a form (never m/form, which inlines refs and erases keyword names), then
   immediate-deps. Returns #{} on read failure — a render must never throw."
  {:malli/schema [:=> [:cat [:cat :string]] [:set ::schema-key]]}
  [spec-string] ...)

(defn dep-closure
  "Transitive closure {key form} over immediate-deps from a seed key set.
   Returns a sorted-map for deterministic iteration (cache byte-stability)."
  {:malli/schema [:=> [:cat [:cat [:set ::schema-key]]] ::closure]}
  [ks] ...)

(defn topo-order
  "deps-before-dependents ordering of a closure, tie-broken by key string.
   On a cycle (agent-authored recursive schema) emits the remaining keys in
   sorted order and continues — NEVER throws (display safety)."
  {:malli/schema [:=> [:cat [:cat ::closure]] ::ordered-defs]}
  [closure] ...)
```

Plus one emitter (owned by `seon.schema` so the dep-walk + render-form live
together, reusable by the HTML twin and the embedding-ranked path; `ctx` just
interleaves):

```clojure
(defn render-register-forms
  "Emit eval'able '(seon.schema/register! :k <shape>)' STRINGS in topo order
   for the dep-closure of `ks`. The single source of D1 schema text. For an
   opaque/built-in referenced key (:inst), emits a ';; :inst is a runtime
   built-in' note instead of a broken register! form."
  {:malli/schema [:=> [:cat [:cat [:set ::schema-key]]] [:vector :string]]}
  [ks] ...)
```

Follow-up (not a blocker): refactor `db/internal.cljs:147 resolve-malli-form`
to consume `immediate-deps` so the registry graph is walked in ONE place.
(Verified C3: `resolve-malli-form` is a single-step RESOLVER returning one
resolved form, NOT a dep collector — it stops at IntoSchema built-ins and
special-cases `:seon.db/ref`. The new fns are additive and a strict superset.)

VERIFIED GAP (C3) — drive the dep-walk off the per-FN `:seon.fn/spec`, never off
the entity-`:map` form. The stored entity-map form for a kind (e.g.
`:seon.agent.todo/todo`) lists only `[::id ::title ::created-at ::description]`
and OMITS the `::owner`/`::from` `:seon.db/ref` attrs that exist in source as
SEPARATE scalar registrations. So `immediate-deps` over the entity-map form
alone UNDERCOUNTS — it misses ref-typed attrs registered independently. D6's
per-fn `:malli/schema` walk (`spec-deps`) avoids this because a fn's spec names
the attrs it actually touches. RULE: a "show all attrs of a kind from the entity
map" shortcut is BANNED for dep discovery; always walk the fn spec.

Verified live (C3): the inline prototype of `immediate-deps`/`dep-closure` over
the live registry works — closure of `:seon.db/ref` pulls
`:seon.db/lookup-ref-value`; closure of `:seon.agent.todo/todo` chains
`::id -> :seon.db/id`. The mechanics are buildable as pure reads of the existing
`*schemas` atom; `schema-definition` already returns raw forms with refs
PRESERVED (not inlined), which is exactly what `immediate-deps` needs.

## Thin-slice plan

`seon.db` first — it is the canonical schema-shape-reference example in CLAUDE.md
(`:seon.db/ref` referenced by `:seon.session/turns` etc.), so its colocated
render is the proof that schemas-deps-first + repetition-across-blocks reads
well. Live-driven on DeepSeek (standing permission), measured by elision delta.

Steps:

1. Land the schema read-layer (`all-schemas`, `immediate-deps`, `spec-deps`,
   `dep-closure`, `topo-order`, `render-register-forms`) in `seon.schema` with
   unit + generative tests, including a hand-built cyclic closure asserting
   `topo-order` degrades to sorted output (no throw). Verify against a REAL
   `:seon.fn/spec` row from the pod (confirm the string read-parses and yields
   the expected keyword set; confirm `:seon.db/ref` chains through to
   `:seon.db/lookup-ref-value`).
2. Add a `:seon.db` curation config entry (plain data: a curated fn set, e.g.
   `{:seon.db #{transact! query pull-by-name entity-lazy}}`) and route ONLY
   `seon.db` through a new branch of `render-one-ns-ai` that: emits the
   `(ns seon.db ...)` head, then per curated fn `(-> spec spec-deps dep-closure
   topo-order render-register-forms)` deps-first, then the fn's verbatim
   `:seon.fn/source`. Wrap the block in the `;;; ┌─/└─ end` brackets. Leave all
   other nses on the existing path (no breakage).
3. Add `:seon.ctx/cache-tier :cluster-static` to the `:namespaces` entry and the
   `context-health` `:prefix-byte-identity` check; render the slice twice on one
   snapshot and assert byte-identity of the `seon.db` block.
4. Drive DeepSeek live: a turn that needs `seon.db/transact!`. Confirm the agent
   sees `(register! :seon.db/ref ...)` ABOVE `transact!`, no `[schema]` label,
   no double-render.

How to measure (D4 weight-by-elision):

- Before/after char + token count of the `seon.db` block (the duplicate
  source-dump + label rows should drop materially; verify the colocated
  register! forms are a net reduction, not addition).
- `context-health` findings count on the slice: target `:reads-as-clojure` and
  `:schemas-above-fns` both green for the `seon.db` block.
- Live behavior delta: does the agent call `transact!` with a correctly-shaped
  namespaced map on first try more reliably than with the label format.

## Phased rollout

Each phase live-proven on DeepSeek, never breaks the loop (sections that don't
change keep rendering identically). Mapped to the #-queue.

0. PREREQUISITES (must land before any phase that depends on them). (a) Register
   the `#seon/elided` tagged-literal reader in ONE place that BOTH sci eval
   (render/sci.cljs reader ctx) and the checker's `read-string` consume, plus a
   `:seon.elide/*` schema set. Without this, every section that adopts the tag
   crashes `:reads-as-clojure` (circular prerequisite — the validator dies on the
   tag the design prescribes). (b) Build the prose-vs-form-vs-comment per-line
   CLASSIFIER (needed by both Phase 1 and `:reads-as-clojure`). (c) Fix the tee
   to populate `:seon.ns/requires` (verified NIL for 81/82 nses) so cross-ns
   ordering can be data-driven rather than re-parsed. (#13 #17)
1. Comment-fix the bare-prose offenders in place using the Phase-0 classifier
   (NOT a blanket prefixer): `system-text` is MIXED content — the COMMON DB OPS
   cheat-sheet (rendered-prompt.clj:106-141) is already real `(register! …)`
   forms; a blanket `;;;`-prefix would COMMENT OUT the masthead's only runnable
   teaching block. Apply per-line: prose -> `;;;`, leave existing forms +
   `;;`-comments untouched. `your-entity`/`live-tile`/`inventory`/`open-todos`
   bodies -> `;;` or real forms. The whole prompt becomes reader-valid. (#13 #15)
2. Schema read-layer + emitter in `seon.schema` (thin-slice step 1). No render
   change yet. (#17)
3. `seon.db` colocated slice via the graph generator (thin-slice steps 2-4);
   delete `schema-block-ai`'s use for this ns. (#13 #15 #17)
4. DECIDE seon's own curation config FIRST (open decision below), THEN generalize.
   The curation default is EVERYTHING (third-party rule); if seon authors no
   curation config, rolling all ~60 framework nses to FULL source balloons the
   CACHED prefix and can exceed the window. So either (a) author seon's per-ns
   curated subsets as part of this phase, or (b) keep a compact-manifest render
   mode (the renamed `:signature`) as the default for un-curated framework nses.
   Do NOT delete the `:signature`/`fn-block-ai`/`schema-block-ai` label paths
   until their replacement covers the 60-ns budget case. State a byte target for
   the Tier-A body before deleting. Once decided: curation config replaces
   `render-full?`/`full-source-whitelist`; roll the generator out; delete the
   source-dump + label paths + clip thresholds; collapse `:ai` member render onto
   the per-kind handlers. (#13 #15 #18)
5. Composer-level brackets: shared `(section-block name body)` helper, wrap every
   section in `render-section` (BEFORE bytes leave the composer); decide the
   verbatim-string opt-out FIRST (a raw third-party slot must not be
   double-bracketed); add `;;; └─ end` to the transcript. (#13 #18)
6. Cache-tier: add `:seon.ctx/cache-tier` to the section schema + every
   `core-default-ctx` entry + each section-text return entry; compute the
   boundary contiguously-from-top at the first non-`:cluster-static` section
   (`:tiers-monotonic`). Adopt topology (A) — prefix = `:cluster-static` only;
   `:agent-stable` (`:your-entity` sans `:db/id`, current-ns) renders into the
   VOLATILE tail so the cluster-static core stays SHARED across agents. (Only
   move to topology (B) two-breakpoint if the provider supports it.) Make
   namespaces ordering deterministic. (#18 #25)
7. Context-health checker: `seon.ctx/context-health` (registry-aware,
   artifact-aware, soul-carrying) + the `:context-health` render section + the
   inspector panel + bracket-fold in the viewer; add `:soul-system` to the right
   pane; give every section an HTML twin (pure fn of the post-bracket AI text).
   (#9 #10 #25)
8. Dedup the two `effective-cap` mirrors (fsm.cljs:134 vs ctx.cljs:117) and the
   transcript `inbound-msg?` copy into a cycle-free leaf ns. (#7 #9)

## Ranked sharp-edges (deduped across lanes)

1. `:system` masthead is ~14k chars of bare prose — reader error if pasted, sits
   ABOVE the cache boundary. ctx.cljs:762-1013 / rendered-prompt.clj:2-238.
   THE #1 violation. Fix: `;;;`-prefix every prose line. (#13 #15)
2. `:namespaces` FULL blocks double-render every fn/schema (verbatim source AND
   `[fn]`/`[schema]` labels). ctx.cljs:1216-1226 /
   rendered-prompt.clj:514-768+770-824. Fix: graph-generate, stop dumping
   source. (#13 #15 #17)
3. Schemas render as non-eval'able `[schema :k]` labels AFTER the fns, not
   `(register!)` forms deps-first above them (D1). ctx.cljs:1159. Fix:
   `render-register-forms` from the dep-closure. (#13 #15 #17)
4. SIGNATURE blocks (~60 nses) are bare-data `[fn]` lines with truncated specs,
   no schemas. ctx.cljs:1135 / rendered-prompt.clj:837+. Fix: curated fn render
   with colocated schemas; drop or repurpose `:signature`. (#13 #15 #18)
5. Cache boundary hard-coded to `(.indexOf names :namespaces)`; no
   `:seon.ctx/cache-tier` exists (zero src hits, verified). ctx.cljs:1768. Fix:
   declare tiers, split after last non-`:live`. (#13 #18 #25)
6. Recency-ordered FULL ns blocks + current-ns force-full make the
   "cluster-static" prefix non-byte-stable and agent-specific. namespaces.cljs:
   253,259-270. Fix: deterministic order; move current-ns to agent-stable region;
   add prefix byte-identity check. (#18 #25)
7. `:your-entity` body is a bare EDN map the masthead calls inert; `:live-tile`
   and `:inventory` bodies are bare prose / bespoke `⟨…⟩` DSL.
   your_entity.cljs:54, live_tile.cljs:82-89, inventory.cljs:132-178. Fix: `;;`
   or live-template + `#seon/elided`. (#13 #18)
8. No section carries a paired close-bracket; two banner styles coexist
   (`;; ── ──` vs `;;; ════` vs `;;; ── WARNINGS ──`). Fix: one composer-level
   `;;; ┌─/└─ end` helper. (#13 #15 #18)
9. Right pane shows only the transcript twin — "mirror the left" is false; debug
   viewer folds only at whole-section granularity; NO context-health panel.
   ctx.cljs:1517-1539, inspector.cljs:285-319. Fix: HTML twin per section,
   bracket-fold, health panel. (#13 #15 #17 #25)
10. `:seon.fn/spec` is a STRING (must read-string, never m/form) and is ABSENT
    for downstream source-scanned fns. agent.cljs:205, client.cljs:1394-1398.
    Fix: `spec-deps` reads the string; fall back to parsing `{:malli/schema}`
    out of `:seon.fn/source` for third-party fns. (#13 #25)
11. Two opaque registry entries (`:inst`, `:seon.flow/dynamic`) are IntoSchema
    objects, not forms — `all-schemas` must filter them; emitter emits a built-in
    note. schema.cljc:74-90. (#13)
12. Duplicate `effective-cap` (fsm.cljs:134 vs ctx.cljs:117) and transcript
    `inbound-msg?` (transcript.cljs:88) — displayed cap can silently disagree
    with enforced cap. Fix: cycle-free leaf ns. (#7 #9)
13. Stale docstrings reference the DEAD XML-tag format (`<namespaces>`,
    `<namespace>`, `<open-todos>`, `<system>`) and render into context.
    ctx.cljs:200, namespaces.cljs:80, todo.cljs:10/28, render.cljs V2/v3
    breadcrumbs. Fix: rewrite to current-state. (#15 #7 #9)
14. Clip thresholds (240/280/80/200) silently drop body/contract with no marker
    and bust Tier-A byte-stability on unrelated source growth. ctx.cljs:1041/
    1047/1144/1167. Fix: verbatim + `#seon/elided`. (#13 #15 #18)
15. `#seon/elided` reader does not exist in the pod (zero hits) — D2/D3 depend on
    it AND the checker's `read-string` would crash on it. Now scheduled as a
    Phase 0 prerequisite: register ONCE in the `:readers` map shared by sci eval
    AND `context-health`. (#13 #17)
16. THE SOUL system message (`my.soul/system-prompt-text`, ~16.8k chars,
    `effective-system-prompt` ai.cljs:357) is block 1 of every real prompt, lives
    OUTSIDE `assemble-context`, and is BIGGER than the masthead. It is prose
    identity by design — scoped OUT of the eval'able thesis (the honest
    boundary), but it was INVISIBLE to every invariant. Fix: carry it as
    `:seon.ai/system-prompt` in `::health-request`, measure it
    (`:section-measured`), assert its boundary marker (`:soul-boundary-present`),
    and add `:soul-system` to the inspector twin. Do NOT silently orphan it. (#9
    #25)
17. `:seon.ns/requires` is populated for only 1 of 82 indexed nses (verified) —
    the boot-indexed nses have NIL requires. Cross-ns deps-before-dependents
    today works only because `render-namespace` RE-PARSES the source via
    `parse-require-syms` (ctx.cljs:1057). HARD BLOCKER for DB-query-based topo;
    fix the tee (Phase 0c). (#17 #25)

## Open decisions for the user

- seon's OWN curation config (BLOCKS Phase 4 — must decide before deleting the
  signature path): D6 defaults to EVERYTHING (third-party case), but showing all
  ~60 framework nses in full blows the budget AND lands in the cached prefix.
  Does the whitelist expand toward most of seon (D1 "expand until nothing
  orphaned") and the signature-manifest disappears, or does a per-ns
  curated-subset mode replace it (keeping a renamed compact-manifest render mode
  as the un-curated default)? This decides whether
  `render-full?`/`full-source-whitelist`/`:signature` are deleted vs reshaped.
  State a byte target for the Tier-A body as part of this decision.
- Where the curation config lives: (a) a registered `:seon.ns/curation` attr
  (queryable, per-cluster, third-party-settable by transact — most consistent
  with DB-is-the-system), (b) a field on the `:seon.ctx/section` entity, or (c)
  the generalized `render-full?` logic. Recommend (a).
- Is `context-health` a real render SECTION the agent sees (reactive doctrine) OR
  a debug-viewer-only human aid? Recommend BOTH (agent section blank when clean).
- Bracket application site: RESOLVED to composer-level `render-section` (one
  rule, third-party sections inherit brackets free, brackets applied before the
  bytes the checker/twin see). STILL OPEN: the verbatim-string opt-out flag — a
  raw third-party slot that already carries its own header must be able to opt
  out so it is not double-bracketed. Must be decided before Phase 5.
- Cache topology (A) single-breakpoint vs (B) two-breakpoint: RESOLVED to (A) as
  the safe default (cluster-static prefix only; agent-stable to the volatile
  tail, so the shared core stays cacheable across agents). Open only if the
  provider gains multi-breakpoint prompt caching — then revisit (B).
- The soul's status: RESOLVED — scoped OUT of the eval'able thesis (prose
  identity by design), but carried into the health checker for measurement +
  boundary assertion, and mirrored into the inspector right pane. Not reopened.
- register! per-fn vs `register-all!` in emitted text (~30% fewer tokens but
  weaker write-API teaching). Recommend the emitter supports BOTH via a
  render-mode arg; pick the default by live A/B.
- `:db/id` in the `:your-entity` agent-stable render — strip it (reset-unstable,
  like counts/timestamps) or keep it for lookup? Recommend strip; render the
  producing pull form + `;;=>` result instead.
- Does `:signature` mode survive at all under D6 curation, or is the bare-arglist
  dump strictly worse than a curated subset and therefore deleted?

## Verification & open risks

Live read-only evals on the pod (agent Zut-2606232034) against the three settled
docs. ~80% of the substrate exists; build ON it.

### Confirmed live (build on these)

- The cache layering (D5) is SHIPPED and working. `assemble-context`
  (ctx.cljs:1705) splits at `stable-boundary` into a byte-stable prefix +
  volatile tail; two renders on one snapshot returned byte-identical prefixes
  (91,447 chars, first-diff nil). `split-context`/`core-default-ctx`/priority
  ordering all exist. The boundary is `(inc (.indexOf names :namespaces))` —
  hard-tied to the section NAME, with NO per-section `:seon.ctx/cache-tier` (D5
  tier field is greenfield, verified zero src hits).
- The DB rows D6 needs are stored: `:seon.fn/spec` is a form-STRING equal to the
  fn's `:malli/schema` verbatim (multi-arity `:function` shape; `:has-source?`
  true), `:seon.fn/source`, `:seon.schema/key|ns|source` (the `register!` shape),
  `:seon.ns/source` all confirmed. D6 reads `:seon.fn/spec` as a string and walks
  it (never `m/form`).
- The schema-research mechanics (§ read-layer) RUN as pure reads of the live
  `*schemas` atom. `schema-definition` returns raw forms with refs PRESERVED. The
  `immediate-deps`/`dep-closure` prototype works: `:seon.db/ref` closure pulls
  `:seon.db/lookup-ref-value`; `:seon.agent.todo/todo` chains `::id ->
  :seon.db/id`. None of `all-schemas`/`immediate-deps`/`spec-deps`/`dep-closure`/
  `topo-order` exist yet (greenfield, additive).
- The `:transcript` section (priority 100, below the boundary) is ALREADY
  eval'able and correctly demarcated `;;;` runtime / `;;` agent. The D2 north
  star is half-built exactly as research said.

### Refuted — design changed to match reality

- "`:seon.ns/requires` carries cross-ns deps for ordering" — REFUTED. Populated
  for 1 of 82 nses (the agent's own home ns). 81 boot-indexed nses are NIL.
  Forced: Phase 0c (fix the tee) added as a hard prerequisite; until then D6
  leans on the source-parse path (`parse-require-syms`, ctx.cljs:1057) that works
  today. Sharp-edge #17.
- "`:reads-as-clojure` = strip `;;` lines and `read-string` the rest" — REFUTED
  as written. The transcript (the cleanest, already-blessed section) contains the
  readline cursor line `my.agent.…=>` and `;;=> <edn> ;; result/<id>` lines that
  a naive strip leaves behind and that throw. Forced: the precise parse contract
  (comment OR top-level form OR recognized runtime artifact) now in the checker
  section. Verified C5: reading `system-text` as a full program throws
  `"Invalid symbol: tool:."`.
- "`:schemas-above-fns` is a pure textual `read-string` check" — REFUTED. A fn
  body/spec has many non-schema keywords (`:optional`, `:db/id`, datalog
  `:find`). Forced: the checker takes `:seon.health/registered-keys` and
  classifies a keyword as a schema-ref only if it is a registered member. The
  checker is registry-aware, not pure-textual.
- "`:prefix-byte-identity` = render twice on one snapshot" — REFUTED as the wrong
  axis. One frozen snapshot is deterministic by construction and greens even with
  the recency bug present. Forced: renamed `:prefix-stable-across-turns`, asserts
  identity across cache-equivalent states (turn N vs N+1, before/after ns nav) or
  purity-of-cluster-static-inputs.
- "`::health-request` takes `[:tuple name string]` section-texts" — REFUTED.
  `assemble-context` returns a VECTOR OF MAPS (`[{:seon.ctx/name _
  :seon.render/text _}]`, ctx.cljs:1806). Forced: `::section-text` map shape,
  reused by producer and checker; instrumentation would have thrown at the
  composer→checker boundary otherwise.
- "`assemble-context` carries the whole prompt" — REFUTED. Block 1 is the SOUL
  (`effective-system-prompt` ai.cljs:357, ~16.8k chars) OUTSIDE the composer.
  Forced: soul scoped out of the thesis but carried into the checker for
  measurement + boundary assertion + inspector twin (sharp-edge #16).

### Residual break-risks

- Topology-(B) two-breakpoint caching is unproven against the live provider;
  default to (A) until proven, or promoting agent-stable into the prefix
  DEGRADES the shared cluster-static cache.
- `dep-closure` MUST iterate deterministically (sorted-map) or the emitted
  `register!` order varies run-to-run and busts the cluster-static prefix
  turn-to-turn — and `:prefix-stable-across-turns`, not the old one-snapshot
  check, is what catches it.
- `#seon/elided` reader registered in only one of (sci eval, checker read-string)
  = the checker crashes on the tag. Phase 0a must register it in the SHARED
  `:readers` map.
- Phase 1 blanket-prefixing `system-text` would comment out the COMMON DB OPS
  live forms — the per-line classifier (Phase 0b) is mandatory, not optional.
- Phase 4 deleting `:signature` before seon's curation config exists =
  default-everything blows the cached prefix; gated behind the curation decision.
- Auto-bracketing a verbatim third-party slot double-brackets it; the opt-out
  flag must ship with Phase 5.
- The entity-`:map` form omits ref-typed attrs (C3) — dep discovery must walk the
  per-fn `:seon.fn/spec`, never the entity-map shortcut.
- The checker covers FORMAT only; semantic problems (errored `message/user`,
  stale `;;=>`, missing `:seon.user/id` seed) belong to `seon.warn`. A green
  context-health does not mean every shown eval succeeded.
