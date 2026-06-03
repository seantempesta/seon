---
type: prd
status: draft
tags: [prd, agent]
---

# Renderer Redesign Proposal — Explicit, Schema-Anchored, REPL-Bounded

Living design proposal for restoring rendering after the datahike migration. Captures Sean's 2026-05-14 direction ("simpler, explicit; no magicy auto-discovery"), inventories what already works, and proposes concrete API options for the decisions that need Sean's call before implementation.

## Resolved decisions 2026-05-15

After Malli-defaults research (`malli-defaults-research.md`) and Sean's follow-up direction:

1. **`schema/register!` stays single-arity. No new kwargs.** All render affordances live in map-entry properties on the entity schema. (§C revised.)
2. **Stock Malli `:default` with polymorphic value-types.** `:seon.render/ai` is `[:or :string :symbol]`; `:seon.render/html` is `[:or :seon.render/hiccup :symbol]`. The schema's `:default` is the symbol pointing at the render fn. Boundary resolves+calls symbols on the in-flight entity at render time. No custom seon transformer — stock Malli `default-value-transformer` does the work. The symbol form doubles as a shorthand the agent can use inline when transacting to-user messages. (§C revised.)
3. **Data attachment via map keys only.** `:seon.render/ai` and `:seon.render/html` are real map entries; specced values are the polymorphic disjunctions above. Metadata path dropped. (§D revised.)
4. **`:seon.render/hiccup` is a global registration of the existing schema** at `seon.web.reactive.transform:33-40`. One source of truth. (§E revised.)
5. **Boundary stays at `bin/mcp-server`** as the live wiring; rewire to call the new transformer. (§E confirmed.)
6. **HUD = single qualified-symbol pointer on `:seon.session/hud-renderer`.** Default ships; agent overrides via `(seon.session/set-hud-renderer!)`. (§F revised.)
7. **Suggest-on-nil is on by default, with a per-session toggle to silence.** Matches Sean's "default harness env should always be surfacing relevant fns." (§G revised.)
8. **CLJS resolution will use the included compiler, not sci.** Server-side still uses `requiring-resolve`; CLJS substrate will bundle the cljs compiler into the agent pool. (§J revised.)
9. **No pruning of unused render machinery in R0.** Sean: "this repo was my experimental playground… let's prune at the end once we have a working system." R0 only deletes what's actively broken (datalevin-conn paths). Auto-discovery / specificity-sort / namespace-proximity stay dormant — possibly useful for future approaches. (§H revised.)
10. **REPL eval auto-persist** — every evaluated result is committed to datahike (subject to serializability) so the agent can re-query and so a snapshot at any timestamp restores the session for debug/resume. Unserializable values produce warnings. (New §K below.)
11. **User watches agent via `:seon.render/html` stream** — same machinery serves agent's own REPL AND user's browser view. Agent's HUD doubles as the user's view of what the agent is doing. (Captured in §F.)

## §A. Current state inventory

`src/seon/render.clj` is 798 lines. Mechanically it has four sub-systems; the first is broken on current boot, the others work.

1. **Datalevin-conn renderer resolution** (lines 31–86, 261–331, 691–725). `get-conn` (l.56–66) reads `:seon.db.datalevin/connections` from the Integrant system. That key is commented out of `resources/system.edn`, so `get-conn` returns `nil` on every call. `resolve-renderer-from-datalevin` (l.261) catches the resulting exception and caches `::no-renderer`. **Confirmed live**: `(render/try-render {...} :ai)` returns `nil`; `(render/has-renderer? {...} :ai)` returns `false`; `(gq/functions-with-output-key {::gq/db-name :seon.runtime ...})` throws "Infrastructure flow not running" (smell #1 in `remaining.md`). The cache (`resolution-cache` l.72), `set-conn!` (l.81), `invalidate-render-cache!` (l.74), `find-renderer` (l.142), `resolve-renderer` (l.211), `find-page-renderer` (l.691), `render-namespace` (l.750) all sit on this broken substrate.

2. **`call-datalevin-renderer`-routed `render`/`for-ai`/`for-html` dispatch** (l.337, 555, 610). These call into #1; on current boot they always fall through to pretty-print / hiccup fallback paths. The fallback paths themselves are good (`for-html` with humanized tables, `for-ai` with concise string recursion).

3. **Format-agnostic utilities — healthy and load-bearing**: `typed` / `schema-of` (l.92–115), `humanize` (l.391–420), `render-schema` (l.505–533) — Malli schema to hiccup field-spec table, `render-seq` (l.539). These have no datalevin dependency and are used directly by `seon.web.components` / handlers.

4. **`namespace-web-params`** (l.187) — also healthy; pure HTTP-param helper, no DB.

`bin/mcp-server` lines 492–573 — **the agent REPL boundary is already wired and live**. After every eval, the MCP server makes a second nREPL call to `seon.render/try-render <val> :ai`. If it returns a string, the agent's response includes a `;; AI: <rendered text>` comment. If it returns `nil`, the agent gets the "No AI renderer for keys [...] — Define a render fn with matching -request/-response specs..." hint. This boundary is silently no-op'ing today (try-render always nil → hint always shown) but the plumbing is in place. **The agent already knows the system was trying to render for them.**

`seon.graph.query/functions-with-output-key` (l.298–364) — the ref-join that finds candidate fns by output-key. Takes a db-name, so its API surface is fine for non-datalevin callers. Currently breaks because `:seon.runtime` isn't in `:seon.db/flow` (per `remaining.md` Forward decisions §"Renderer auto-resolution: deferred"); also the underlying entity model assumes a scanner is populating `:seon.fn/*` / `:seon.spec/*` rows, and on current boot the scanner is gated too.

`seon.schema/register!` (l.103–118) — single-arity `[k v]`. No renderer hook. Backing atom is `*schemas` (l.38). Introspection: `registered-schemas`, `schema-definition`, `schemas-in-namespace`. There are **8531** schemas registered at boot; only `:seon.render/html` (`:any`) and `:seon.render/ai` (`:string`) are render-output keys themselves, no schema currently carries a default renderer.

Production render functions: `seon.render.example/position-render`, `seon.health.workout/*-row` (`workout.clj:127–130`), `seon.getting_started.render/*` (l.338, 402), `seon.render.default-page`, `seon.render.code`. All declare `:seon.render/html` / `:seon.render/ai` in their **output Malli schema**, no caller-side annotation today.

Tracked overlaps: `seon.ns.view` (multimethod-based, parallel system per `overlap-three-rendering.md`); `seon.ui.viewer` (dead); `seon.render.example` (no callers — `dead-render-example.md`).

## §B. Mapping to Sean's direction

| Sean's requirement | Existing piece | Gap |
|---|---|---|
| `schema/register!` carries default renderer + example | `seon.schema/register!` is single-arity | extend signature |
| Data-level `:seon.render/ai` / `:seon.render/html` attachments | Currently lives in fn **output schema** (scanner-indexed), not on the data itself | new convention + boundary lookup |
| Renderer fires at agent REPL boundary | `bin/mcp-server` already calls `seon.render/try-render` post-eval | wire the new lookup paths in; keep the boundary intact |
| Per-agent HUD overrides | nothing — `*conn-override*` atom (l.54) is per-process, not per-agent | new — datahike entity per session |
| Surface "what fns can process this shape?" to the agent | `gq/functions-with-output-key` (output-side) | need input-side equivalent + nice summary format |
| Explicit, not auto | spec-driven specificity algorithm in `find-renderer`/`resolve-renderer` is the magicy part | delete those paths |
| HTML fragments to user | `for-html` works; no transport plumbing | post-MVP |

**Net**: schema-level affordance is net-new; data-level annotation is new convention with simple metadata-or-key lookup; boundary is in place; HUD storage is new; "surface fns" is mostly new query; explicit-pick replaces the specificity sort.

## §C. `schema/register!` — no API change; polymorphic value-types + stock Malli `:default`

**Resolved**: `schema/register!` stays single-arity. The Malli defaults research (`malli-defaults-research.md`) found that Malli's stock `:default/fn` is 0-arity and can't see the surrounding entity. Sean's reframe — "it's either the value or a function that returns the value that conforms" — points at a cleaner solution: make the value-type polymorphic.

```clojure
(schema/register! :seon.user-message/message
  [:map
   [:seon.user-message/text :string]
   [:seon.user-message/from :string]
   [:seon.render/ai
    {:default 'my.ns/render-user-message-ai}
    [:or :string :symbol]]
   [:seon.render/html
    {:default 'my.ns/render-user-message-html}
    [:or :seon.render/hiccup :symbol]]])

```

The `:seon.render/ai` value is **either** a literal string (pre-rendered) **or** a symbol pointing at a render fn. Same for `:seon.render/html` — hiccup-or-symbol. The schema's `:default` is a literal symbol (the most common shape; agents who want a custom literal pre-populate the key).

`my.ns/render-user-message-ai`:

```clojure
(defn render-user-message-ai
  "Renders a user-message entity for AI consumption."
  {:malli/schema [:=> [:cat :seon.user-message/message] :string]}
  [{:keys [:seon.user-message/from :seon.user-message/text]}]
  (str from ": " text))

```

Takes the entity, returns the rendered string. Standard map-in/map-out fn.

### Why stock Malli is enough

At the boundary:

```
1. (m/decode schema entity (mt/default-value-transformer))
   — Malli's stock transformer fills any missing :seon.render/* keys
     with the symbol from the schema's :default property.
2. Check (:seon.render/ai entity):
   - string?  → use directly.
   - symbol?  → (requiring-resolve sym) → call with entity → string.
3. Same for :seon.render/html (hiccup or symbol).

```

No custom seon transformer. No semantic hijack of `:default/fn`. The polymorphism — declared, typed, finite — lives in the schema's value-type. The boundary handles the disjunction in a few lines.

### Inline shorthand for agent → user messages

Sean's framing: "a nice shorthand for the agent to transact messages to the user inline." A user-facing message becomes a single transact with the agent picking a renderer from a catalog:

```clojure
(seon.db/transact!
  :seon.session
  [{:seon.message/from :assistant
    :seon.message/text "Here's the result of your query."
    :seon.render/html  'my.ns/format-as-card    ;; agent chose this renderer
    :seon.render/ai    'my.ns/format-as-line}]) ;; and this AI representation

```

The transaction listener detects the new message → emits to user's browser (via the `:seon.render/html` resolved+called). Agent can override default render per-message without committing to a particular pre-rendered form at write time. If the renderer fn evolves later, prior messages re-render with the new logic.

### Why no `:seon.render/example` slot

Original proposal had `:seon.render/example` as a separate map-entry slot for agent-facing demos. Sean's revised direction doesn't include it as a primary feature. Retire from R1; if "give the agent example data for shape X" surfaces as a real need later (e.g. as part of §G suggest), it lands as a separate concern. Smoke-testing each renderer at boot/reload hooks the existing test suite — no new schema slot needed.

## §D. Data-level renderer attachment — map keys, value-or-symbol

**Resolved**: map keys are the only surface. Metadata path dropped. Value is either the pre-rendered output OR a symbol pointing at a render fn (typed disjunction per §C).

```clojure
;; agent emits a message with a pre-rendered AI string and a symbol for HTML
{:seon.user-message/text "hi"
 :seon.user-message/from "seon"
 :seon.render/ai   "alice (custom): hi"            ;; literal string
 :seon.render/html 'my.ns/render-as-callout-card}  ;; symbol → resolved + called

```

Boundary algorithm at the dispatch step (§E):
- Hiccup/string value → use directly.
- Symbol value → `requiring-resolve` → call with the entity → produced value.

**Override mechanics**:
- Agent pre-populates the key with a literal → boundary uses it verbatim.
- Agent pre-populates the key with a symbol → boundary resolves + calls with entity.
- Agent omits the key → Malli's stock `default-value-transformer` fills in the schema's `:default` (a symbol) → boundary resolves + calls.
- Agent wants a per-session override for multiple entities of the same shape → use the HUD path (§F).

**Symbols are intentional fn pointers**, not content. The schema's `[:or :string :symbol]` documents the convention. An agent who legitimately wants a symbol as content wraps it (e.g. `[:span "page-title"]` hiccup), making the intent explicit.

**Resolver**: `requiring-resolve` for the JVM (CLJ) side. CLJS substrate (WebAssembly-hosted agents per spec-01) will bundle the CLJS compiler into the agent pool — real CLJS resolution rather than sci-interpreted. Precompile into the pool image so startup cost is amortized. Same property semantics across both runtimes.

## §E. Agent-REPL boundary — where rendering fires

**Resolved**: boundary stays at `bin/mcp-server:492-573` (already wired). Rewire to call the new transformer + look at data-attached keys first. Same boundary serves both the agent's REPL (AI text via `;; AI: ...` comment) and the user's browser view (HTML fragments via SSE — R5).

### The `:seon.render/hiccup` schema

Existing local def at `src/seon/web/reactive/transform.clj:33-40` already shapes Datastar-safe hiccup recursively:

```clojure
[:schema {:registry {::hiccup [:or
                               :keyword
                               :string
                               :int
                               :nil
                               [:sequential [:ref ::hiccup]]
                               [:vector [:cat :keyword [:? :map] [:* [:ref ::hiccup]]]]]}}
 [:ref ::hiccup]]

```

R1 promotes this to a global registration:

```clojure
(schema/register! :seon.render/hiccup
  [:schema {:registry {::node [:or :keyword :string :int :nil
                               [:sequential [:ref ::node]]
                               [:vector [:cat :keyword [:? :map] [:* [:ref ::node]]]]]}}
   [:ref ::node]])

```

`seon.web.reactive.transform`'s local def becomes a re-export of the registered name. One source of truth. Future Datastar-safety refinements (forbid `:script` tags? require `data-*` attrs? etc.) live in the registered schema.

### Boundary algorithm (single dispatch order, no specificity sort)

```
At every MCP eval result that's a map:
1. (m/decode schema result (mt/default-value-transformer))
   — Malli's stock transformer fills any missing :seon.render/* keys
     with the schema's :default symbol (no-op if value already present).
2. Look up (:seon.render/ai decoded):
   - string?   → use directly.
   - symbol?   → (requiring-resolve sym) → call on `decoded` → string.
   - nil?      → fall to step 4.
3. Per-session HUD override active (see §F) → consult agent's hud-renderer.
4. Nothing renderable → return nil → mcp-server prints the "No AI renderer"
   hint AND (if suggest-on-nil enabled, see §G) inlines a short
   "functions that accept these keys" list.

```

Schema detection: the result needs a `:seon/schema` reference to know which schema to decode against. Two mechanisms:
- The value carries `:seon/schema` metadata (existing `typed` convention from `render.clj:92`) — works for IMeta values.
- For map values, look up by key-set: scan top-level keys against the schema registry and pick the entity schema whose registered map-entries best match. (This is a tiny lookup, not the auto-discovery sort. If multiple match, defer to explicit metadata.)

No graph query at this step. No specificity sort. No namespace proximity. The auto-discovery machinery in `seon.render` (`find-renderer`, `resolve-renderer`, `namespace-proximity`) stays dormant in the codebase — Sean's call to not prune until later, since it might inform other approaches.

### Implementation note — single-trip eval

Today the MCP server reads `:value` from nREPL (a string), edn-reads it, then sends a *second* nREPL call with the value re-serialized inline (`(seon.render/try-render <val-str> :ai)`). Cleaner: have `seon.render` expose `render-eval-result` that the eval-print hook calls directly with the live var-1 value. Two trips becomes one. R2 work; not blocking R1.

## §F. Per-agent HUD — single render fn over the database

**Resolved**: per Sean, "the hud is just a render function on the database that the agent can customize by overwriting the default one we setup for it." Simpler than the prior per-shape override map.

```clojure
(schema/register! :seon.session/hud-renderer
  :symbol)   ; fully-qualified symbol pointing at the agent's HUD fn

(defn default-hud
  "Default HUD renderer. Reads the agent's session + ctx + recent tx-log,
   returns a hiccup structure summarizing what the agent is doing.
   Agent overrides by transacting a new :seon.session/hud-renderer value
   on their session entity."
  {:malli/schema [:=> [:cat :seon.session/id] :seon.render/hiccup]}
  [session-id]
  [:div.hud ...])

```

A HUD render fn signature is `(fn [session-id] -> hiccup)` — takes the agent's session id, reads whatever it needs from datahike, returns the rendered view. The agent customizes:

```clojure
(seon.session/set-hud-renderer!
  {:seon.session/id "a5ba3e"
   :seon.session/hud-renderer 'my.ns/my-custom-hud})

```

Same render machinery powers both surfaces:
- **Agent's REPL**: the HUD's `:seon.render/ai` (or a `:ai`-projection of the hiccup) renders into the agent's `;; AI: ...` line, periodically or on demand.
- **User's browser**: the HUD's `:seon.render/html` (the hiccup) is streamed to the user via SSE. The user sees the agent's customized view of itself. Doubles as a debugging surface AND as the agent's way of customizing UX for the user.

### Widgets — fns attached to the HUD

Sean: "If they attach it to their context it's like attaching a widget to their dashboard that will stick around and it can be functions where their args are spelled out then they can be called for querying and processing data."

Widgets are entries the HUD renderer pulls in. Mechanism: agent transacts `:seon.session/widgets` (vector of qualified-symbol fn pointers, or richer widget structs); HUD renderer's default behavior is to iterate, call each with the session-id, and stitch their hiccup outputs into the dashboard. Agents customize by ordering, filtering, or replacing the HUD renderer altogether.

Schema reg + per-fn arg specs (already present via seon's `:malli/schema` metadata) means widgets are introspectable — the agent knows what each widget consumes/produces.

### Storage on `:seon.session`

Per `phase-3-harness-migration.md`, `:seon.session` already holds one row per agent session. The HUD renderer pointer + widget list ride along. Resume reads them back automatically.

## §G. "Surface fns that can process this data"

Sean's framing: "default harness environment should always be trying to surface possible relevant functions to help the agent do its job."

Today's `gq/functions-with-output-key` finds fns whose **output** carries a key (the "produces shape X" direction). The new requirement is the input side: given data, find fns whose declared `input-spec` contains all the keys the data has. The scanner already pulls `:seon.fn/input-spec` with `:seon.spec/contains-keys` and `:seon.spec/optional-keys` (per `spec-driven-rendering/prd.md`), so the data is in the graph — what's missing is the inverse query.

```clojure
(seon.graph.query/functions-accepting-keys
  {::db-name :seon.runtime
   ::data-keys #{:seon.health.workout/exercise :seon.health.workout/sets}})
;; => [{:seon.fn/qualified-name "seon.health.workout/log-workout!"
;;      :seon.fn/doc "Records a workout set to the database"
;;      :required-keys #{...}, :optional-keys #{...}
;;      :seon.render/example {::exercise "Squat" ...}}]   ; pulled from schema affordances

```

Surface format at the REPL boundary (when the agent calls `(suggest x)` or types help, or — opt-in — every time `try-render` returns no-renderer): a markdown comment block under the result:

```
;; This shape (#{:seon.health.workout/exercise :seon.health.workout/sets}) has 3 possible processors:
;; - seon.health.workout/log-workout!    — Records a workout set to the database
;;     example: {:seon.health.workout/exercise "Squat" :seon.health.workout/sets 5 ...}
;; - seon.health.workout.render/workout-set — Renders a workout set for HTML+AI
;;     example: see above
;; - seon.health.workout/total-volume    — Sum total kg lifted across sets

```

**Resolved**: ship as always-on with a per-session toggle to silence. Sean's stated direction ("default harness env should always be surfacing relevant fns") wins over the noise concern. The toggle lives on `:seon.session/suggest-on-nil?` (boolean, defaults to true). Agents that find the surface noisy disable it for their session.

The "no renderer" hint at the boundary becomes the suggest output when enabled — instead of just `;; No AI renderer for keys [...]`, the agent sees the keys PLUS a short list of fns that accept them, with their docs. The agent always has the introspection affordance unless they explicitly opt out.

## §H. Cluster 2 implication — minimal cleanup, no pruning

**Resolved**: Sean's call ("let's hold off on removing unused features as of yet as they may be useful other approaches") shrinks R0 dramatically. Only delete what's actively broken and dependent on datalevin. The auto-discovery machinery stays dormant.

**Delete (broken, datalevin-dependent — must come out for cluster 4):**
- `[seon.db.datalevin.conn :as dl-conn]` require (l.31).
- `get-conn` (l.56) — references `:seon.db.datalevin/connections` which is gone.
- `set-conn!` (l.81) — only relevant when a datalevin conn-manager is active.
- `*conn-override*` atom (l.54) — sole consumer is `get-conn`.
- `resolve-renderer-from-datalevin` (l.261) — explicit datalevin caller.
- `call-datalevin-renderer` (l.280) — explicit datalevin caller.

**Stay dormant (Sean's experimental playground; may inform future approaches):**
- `find-renderer` (l.142), `resolve-renderer` (l.211), `find-page-renderer` (l.691) — auto-discovery / specificity-sort. Will return nil today; harmless.
- `resolution-cache` (l.72), `invalidate-render-cache!` (l.74) — caches nothing useful; harmless.
- `namespace-proximity` (l.121) — pure helper, no datalevin dep; keep.
- `seon.ns.view` multimethod system — separate render path; leave alone.
- `seon.ui.viewer` — dead but kept for future inspection.
- `seon.render.example` — example renderer file; kept.

**Keep + reuse (foundation):**
- `typed` / `schema-of` (l.92–115) — value tagging convention.
- `render` (l.337) — rewire dispatch through the new transformer boundary (§E).
- `for-ai` (l.555), `for-html` (l.610) — recursive fallback renderers, healthy.
- `humanize` (l.391), `render-schema` (l.505), `render-seq` (l.539), `namespace-web-params` (l.187) — pure helpers.
- `render-namespace` (l.750) — kept; rewire away from `find-page-renderer` if needed at the moment we actually call it, otherwise leave.

Net for R0: probably 40–80 lines of pure deletion (datalevin requires + datalevin-specific fns). The 250-line cleanup from the original proposal becomes a "later prune pass" once the new system is working. Sean: "let's prune at the end once we have a working system."

## §I. Implementation phases

| Phase | Goal | Files changed | Net-new vs reuse | Rough size |
|---|---|---|---|---|
| **R0** | Minimal cleanup (§H). Delete datalevin-conn paths in `seon.render`; leave auto-discovery machinery dormant. `bin/mcp-server`'s `try-render` call still works (returns `nil` until R2). | `src/seon/render.clj` (40-80 line deletion); callers of removed fns (none on current boot — they were silently dead). | Pure deletion | small |
| **R1** | Register `:seon.render/hiccup` globally (promoting the existing schema at `seon.web.reactive.transform:33-40`). Have `seon.web.reactive.transform`'s local def reference the registered name. No `schema/register!` API changes — single-arity stays. Smoke test: declare a sample entity schema with `:seon.render/ai` / `:seon.render/html` map entries (typed disjunction + `:default` symbol) and verify Malli's `default-value-transformer` fills it in. | `src/seon/schema.clj` (register the hiccup schema), `src/seon/web/reactive/transform.clj` (re-export), new test file | Registration + verification, no new code paths | small |
| **R2** | Boundary dispatch using stock Malli + symbol-resolve. New `seon.render/render-eval-result` (single-trip). Schema lookup via `:seon/schema` metadata or top-level-key matching. Rewire `bin/mcp-server` post-eval call. | `src/seon/render.clj`, `bin/mcp-server`, `src/seon/repl.clj` | New entry point; reuses Malli + existing `for-ai`/`for-html` fallbacks | medium |
| **R3** | HUD on `:seon.session/hud-renderer` (single qualified-symbol pointer). Default HUD ships; `(seon.session/set-hud-renderer! …)` writes the override; resume rehydrates. Widget convention via `:seon.session/widgets`. | `src/seon/session.clj` (new attrs), `src/seon/render.clj` (boundary uses session-id to read), default HUD impl | All net-new; rides on `:seon.session` already being a datahike namespace | small |
| **R4** | `functions-accepting-keys` inverse query in `seon.graph.query`. `seon.harness/suggest` REPL helper that formats output for agent consumption. Always-on at the boundary (suggest-on-nil); per-session toggle `:seon.session/suggest-on-nil?` to silence. Gated on `:seon.runtime` joining `:seon.db/flow`. | `src/seon/graph/query.clj`, new `src/seon/harness.clj`, `bin/mcp-server` | New query + agent-facing helper | medium |
| **R5 (post-MVP)** | HTML fragments to user. Boundary additionally streams `:seon.render/html` output through SSE. The agent's `:seon.session/hud-renderer` doubles as the user's view of what the agent is doing. Per-agent HUD UI control. | `src/seon/web/handlers.clj`, `src/seon/web/sse.clj`, browser side | Plumbing on top of R2 + R3 | deferred |

Hard ordering: **R0 → R1 → R2 → R3 → R4 → R5**. R0 can land cleanly today. R1 is small (registration + smoke test). R2 is the main implementation. R4 is the only one blocked on outside work (`:seon.runtime` migration to datahike, smell #1 of `remaining.md`).

## §J. Open questions for Sean

All eight original questions are settled in the §"Resolved decisions 2026-05-15" callout at the top of this doc:

- §C API shape → no change to `register!`; polymorphic value-type + stock Malli `:default`.
- Example slot → retired (smoke-test via the existing test suite instead).
- HUD storage → single `:seon.session/hud-renderer` symbol pointer + widget list.
- Boundary location → `bin/mcp-server` (stays).
- Suggest-on-nil → always-on, per-session toggle to silence.
- Fn-pointer form → qualified symbol; resolved with `requiring-resolve` (CLJ) or bundled CLJS compiler (substrate future).
- Cleanup of overlapping render systems → deferred; nothing pruned in R0.
- Example as property-based test → not needed; eliminated with the example slot.

Remaining open work (not blocking R0/R1):

1. **Schema detection at the boundary** (§E step 1) — for map values without `:seon/schema` metadata, the lookup matches top-level keys against registered entity schemas. If multiple match, defer to explicit metadata; if no exact match, fall back to the existing `for-ai`/`for-html` recursive renderers. Concrete algorithm to refine in R2.
2. **HUD render frequency** — on every change (debounced)? On agent-explicit refresh? Resolve when R3 implementation starts.
3. **Suggest-output formatting** — markdown-block, short list, hiccup widget? Resolve when R4 begins. Lower-stakes; the agent can iterate.
4. **CLJS compiler bundling for the agent pool** — when the WebAssembly-hosted CLJS substrate work starts. Out of scope for R0–R5.

## §K. REPL eval auto-persist — the session-snapshot mechanism

Sean's direction:

> "Yeah we could make any repl evals stick around unless they can't be persisted (objects or something not data). Restoring a session for debugging would make this amazing. Grab the right time snapshot from datahike and render that and you've got the session mostly restored (we can show warnings for values that weren't restored). Yeah that's also the resume feature so an agent can have a longer lifetime and can specialize maybe in a set of functionality or archival or maintenance, etc."

The existing `:r-NNNN` keys in `user/repl-orchestrator` (auto-saved every eval) are the seed. Extension:

- **Every eval result is committed to datahike**, attributed to the agent's session. Schema: `:seon.session.repl/result` with `:seon.session.repl.result/id` (the `:r-NNNN` key), `:seon.session.repl.result/value` (the value, serialized via nippy or similar), `:seon.session.repl.result/timestamp`, `:seon.session.repl.result/code` (the form that was evaluated).
- **Unserializable values** (raw Java objects, channels, fn values, connections, etc.) are detected at the persist step and produce a warning. The persisted form notes the type and the failure reason; in-memory `*1`/`*2`/`*3` and `user/repl-orchestrator` continue to hold the live value.
- **Session resume**: on session restart, the agent's `:r-NNNN` history rehydrates from datahike. Values that didn't persist are absent with a warning surfaced in the agent's first interaction.
- **Time-travel debug**: any datahike-as-of timestamp can render the agent's state at that moment. Useful for debugging emergent behavior in agent loops ("show me what the agent saw at the point it made decision X").
- **Long-lived specialized agents**: the persistence enables agents that outlive their JVM — archival agents that maintain long indices, maintenance agents that run periodic sweeps, etc. State survives crashes and intentional restarts.

Lifecycle: open questions for the R5+ implementation:
- Compression / GC policy: ring buffer of last N? Compress all? Agent-controlled?
- Index format for query ("find me the result from the test-run that mentioned X")?
- Storage scope: per-session vs. per-agent (across sessions of the same agent)?

This phase rides on the broader event-sourcing model (every reaction transacts an entity; listeners drive emergent behavior) — see `remaining.md` §"Forward decisions 2026-05-15" §"Everything-through-the-database + transaction-listener-driven reactions". Implementation lands after cluster 4 + the `:seon.runtime` / `:seon.ai` migrations.

---

Loadbearing in this proposal: §C (API shape — settled), §E (boundary algorithm — settled), §F (HUD storage — settled), §H (cluster 2 minimal scope — settled), §K (REPL auto-persist — design captured for later phase). R0 and R1 are dispatch-ready.
