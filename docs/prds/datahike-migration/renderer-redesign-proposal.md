---
type: prd
status: draft
tags: [prd, render, agent]
---

# Renderer Redesign Proposal — Explicit, Schema-Anchored, REPL-Bounded

Living design proposal for restoring rendering after the datahike migration. Captures Sean's 2026-05-14 direction ("simpler, explicit; no magicy auto-discovery"), inventories what already works, and proposes concrete API options for the decisions that need Sean's call before implementation.

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

## §C. `schema/register!` extension — API options

Today: `(register! [k v])` — `k` is a keyword, `v` is a Malli schema, atom is `{k v ...}`.

Three options for adding optional default renderer + example:

**Option 1 — positional**
```clojure
(register! ::position [:map ...] #'my.ns/render-position example-data)
```
Cheap but every existing call site keeps working only because the new args are optional. Becomes brittle if we later want more affordances (e.g. doc, html-renderer + ai-renderer as a pair). Reject.

**Option 2 — keyword-args**
```clojure
(register! ::position [:map ...]
  :seon.render/default #'my.ns/render-position
  :seon.render/example {::ticker "AAPL" ::quantity 100 ::price 150.0})
```
Backwards-compatible (no kwargs = today's behavior). Easy to grow (`:seon.doc/example`, `:seon.health/check`, …). Storage shape: each registered key becomes `{::schema <malli> ::affordances {kw val ...}}` rather than the bare schema. Introspection: a new `affordances-for` fn returns the map.

**Option 3 — metadata on the schema itself**
```clojure
(register! ::position
  [:map {:seon.render/default 'my.ns/render-position
         :seon.render/example {::ticker "AAPL" ...}}
   [::ticker ::ticker] ...])
```
Stays inside Malli's existing properties slot. Zero changes to `register!`. Reachable by anyone with the schema definition (including the static scanner). **But:** Malli treats `:gen/*` etc. as live properties — symbol values in properties may confuse generators. And properties don't survive `m/schema` normalization cleanly.

**Recommendation: Option 2.** Backwards compat is trivial (varargs after `[k v]`). The storage shape stays uniform. Forward-extensible to non-render affordances. Symbol-or-var values stay out of Malli's properties (which it treats as live data for generation).

On the "test(s) / ideally one great example" ambiguity in Sean's direction: support **two slots**.

- `:seon.render/example` — a literal value of the data shape. Used in two places: (1) the "surface relevant fns" agent helper renders this verbatim so the agent sees what the renderer is for, (2) the renderer is invoked on this value as a smoke-test (boot-time and `(user/reload)`-time) and warned if it throws. Single value, not a fn.
- `:seon.render/example-fn` — optional, zero-arg fn returning the example. Use when generation is parameterized / random / depends on time.

Mostly callers register an `:seon.render/example`. The fn slot is for rare cases. Skipping both is allowed — renderer registers without demo, but loses the smoke-test and the agent-facing "here's what it looks like" affordance.

## §D. Data-level renderer attachment

When a caller wants to override at the value (not the schema): two surfaces.

**Surface 1 — metadata** (preserves the existing `typed` convention from `render.clj:92`):
```clojure
(with-meta value {:seon.render/ai #'my.ns/custom-ai
                  :seon.render/html #'my.ns/custom-html})
```
Works for any `IMeta` value (maps, vectors, lists, records). Same shape as the existing `:seon/schema` metadata. **Recommended primary path.**

**Surface 2 — map key** (for maps that need to survive serialization through wire/SSE):
```clojure
{::data ... :seon.render/ai 'my.ns/custom-ai :seon.render/html 'my.ns/custom-html}
```
Lookup precedence at the boundary: metadata first, then map key.

**Fn pointer format**: support all three of `#'my.ns/f`, `'my.ns/f`, `"my.ns/f"`. Resolve via `requiring-resolve` of the qualified symbol; vars resolve to their value. String form is what serializes cleanly through SSE / nippy / datahike, so the **canonical storage form is the qualified symbol** — vars are unwrapped to symbols at write time.

## §E. Agent-REPL boundary — where rendering fires

Three options as posed:

1. **MCP eval return path** (`bin/mcp-server:492–573`). Already wired. Calls `try-render` post-eval on the value's string form. AI text comes back in a `;; AI: ...` comment.
2. **`seon.repl`'s eval-print loop**. Closer to the value (no string round-trip); fires for any nREPL caller including non-MCP. But MCP is currently the only agent path.
3. **Explicit `(render x)` in agent code**. Pure-explicit. Loses HUD-feel — the agent has to remember.

**Recommendation: keep MCP boundary (Option 1) as primary**, with a tightening: pass the live value (not its string form) through the nREPL call. Today the MCP server reads `:value` from nREPL (a string), edn-reads it, then sends *another* nREPL call with the value re-serialized inline (`(seon.render/try-render <val-str> :ai)`). Cleaner: have `seon.render` expose a `render-eval-result` that the eval-print hook can call directly with the live var-1 value. Two trips becomes one. (This is the upgrade path; the existing two-trip flow keeps working in the meantime.)

Boundary algorithm (single dispatch order, no specificity sort):

```
1. metadata :seon.render/ai on the value → resolve symbol, call, return string
2. map key :seon.render/ai on the value → same
3. value has :seon/schema meta → look up that schema's
   `:seon.render/default` affordance → call → extract :seon.render/ai
4. nothing → return nil → mcp-server prints the "No AI renderer" hint
   listing the top-level keys
```

No graph query. No specificity sort. No namespace proximity. Explicit caller-attaches-or-schema-registers-or-no.

## §F. Per-agent HUD configuration

Sean's framing: "the agent can override defaults to configure their live interface." Implies persistence across sessions.

Per `phase-3-harness-migration.md`, `:seon.session` is the datahike DB that already holds one row per agent session (id, namespace, port, status, ctx checkpoint). The HUD overrides ride along:

```clojure
(schema/register! :seon.session/render-overrides
  [:map-of :seon.schema/key       ; e.g. :seon.health.workout/log-workout-request
           [:map [:seon.render/ai {:optional true} :symbol]
                 [:seon.render/html {:optional true} :symbol]]])
```

A row update on the session entity is how the agent customizes. New helper:

```clojure
(seon.render/set-override! {::session-id "a5ba3e"
                             ::schema-key :seon.health.workout/log-workout-request
                             ::format :ai
                             ::renderer 'my.ns/my-ai-renderer})
```

At the boundary (§E), step 3 becomes: "value has `:seon/schema` meta → first check `:seon.session/render-overrides` for the current session, then the schema's default affordance." **Session lookup uses the same dispatch the eval boundary already does** — the MCP server knows the session-id; pass it through.

Atoms-per-session is the wrong layer (loses across restart, doesn't survive resume — spec-01 wants resume to be a full rehydration). Per `Forward decisions` §"`*ctx*` redesign," ctx persistence is being rebuilt anyway; overrides naturally live alongside the session row.

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

Heuristic for "surface always or surface on opt-in": ship as opt-in (`(seon.harness/suggest x)` or `*suggest-on-nil*` toggle) — the existing "no renderer" hint already does part of this job; promoting it to always-on for every result is noisy.

## §H. Cluster 2 implication — revising `remaining.md`

`remaining.md` Forward decisions §"Renderer auto-resolution: deferred" said: "Just delete the silently-dead datalevin-connection-manager paths and the `set-conn!` API (smell #16); leave the rest of `seon.render` alone for now."

This redesign keeps that direction but reframes scope-positive. Concrete cluster-2 disposition for `seon.render`:

**Delete outright:**
- `*conn-override` atom (l.54), `get-conn` (l.56), `set-conn!` (l.81) — gone.
- `[seon.db.datalevin.conn :as dl-conn]` require (l.31) — gone.
- `resolve-renderer-from-datalevin` (l.261), `call-datalevin-renderer` (l.280) — gone.
- `find-renderer` (l.142), `resolve-renderer` (l.211), `find-page-renderer` (l.691) — specificity-sort + namespace-proximity-tiebreak machinery. **This is the auto-discovery Sean is removing.**
- `resolution-cache` (l.72), `invalidate-render-cache!` (l.74) — also gone (no more graph lookup to cache).
- `namespace-proximity` (l.121) — only used inside `resolve-renderer`. Gone.

**Keep + reuse (foundation):**
- `typed` / `schema-of` (l.92–115) — value tagging convention stays.
- `render` (l.337) — rewire dispatch through new boundary (§E), keep fallback paths.
- `for-ai` (l.555), `for-html` (l.610) — recursive fallback renderers, healthy.
- `humanize` (l.391), `render-schema` (l.505), `render-seq` (l.539), `namespace-web-params` (l.187) — pure helpers, unaffected.
- `render-namespace` (l.750) — keep but rewire away from `find-page-renderer`.

Net: about 250 lines deleted from `render.clj`, the surviving ~550 lines is foundation for §I.

## §I. Implementation phases

| Phase | Goal | Files changed | Net-new vs reuse | Rough size |
|---|---|---|---|---|
| **R0** | Cluster-2 deletion above. Leaves `seon.render` as a clean foundation (no datalevin, no specificity sort). `bin/mcp-server`'s `try-render` call still works (returns `nil` always until R1). | `src/seon/render.clj` (-250 lines); also touches `seon.ns.routes`, `seon.render.code`, `seon.ns.lifecycle` (callers of `resolve-renderer` / `find-page-renderer`) | Pure deletion + caller-fixup | medium |
| **R1** | `schema/register!` extension (Option 2 kwargs). Storage shape changes from `{k v}` to `{k {::schema v ::affordances {…}}}` with back-compat (single-arity registers no affordances). Introspection helpers (`affordances-for`, `default-renderer`, `example-for`). | `src/seon/schema.clj` (extend), `src/seon/render.clj` (boundary uses affordances) | New API on existing data structure | small |
| **R2** | Boundary dispatch + data-level annotation. New `render/render-eval-result` (single-trip). Metadata/map-key/schema-affordance lookup order. Update `bin/mcp-server` to call the new entry point. Smoke-test each registered renderer against its example at boot/reload. | `src/seon/render.clj`, `bin/mcp-server`, `src/seon/repl.clj` | New entry point + boundary changes | medium |
| **R3** | Per-agent HUD overrides on `:seon.session`. New schema attr `:seon.session/render-overrides`. `set-override!` / `clear-override!`. Boundary reads override before schema default. Resume rehydrates overrides automatically. | `src/seon/session.clj` (new attr), `src/seon/render.clj` (boundary uses session-id) | All net-new, but rides on Phase 3 work | small |
| **R4** | `functions-accepting-keys` inverse query in `seon.graph.query`. `seon.harness/suggest` REPL helper that formats output for agent consumption (pulls examples from affordances). Optional `*suggest-on-nil*` toggle in the MCP boundary. Requires `:seon.runtime` to be in `:seon.db/flow` (which is the gate from `remaining.md` Forward decisions). | `src/seon/graph/query.clj`, new `src/seon/harness.clj`, `bin/mcp-server` (opt-in hook) | New query + agent-facing helper | medium |
| **R5 (post-MVP)** | HTML fragments to user. Boundary additionally streams `:seon.render/html` output through SSE to the user-facing UI. Per-agent HUD UI control. | `src/seon/web/handlers.clj`, `src/seon/web/sse.clj`, browser side | Plumbing on top of R2 | deferred |

Hard ordering: **R0 → R1 → R2 → R3 → R4 → R5**. R0 can land cleanly today (nothing references the silently-dead paths productively). R4 is the only one blocked on outside work (`:seon.runtime` migration to datahike, smell #1 of `remaining.md`).

## §J. Open questions for Sean

1. **Option 2 (kwargs) confirmed for `schema/register!`?** Or do you want Option 3 (Malli properties) for static-scanner reachability? Tradeoff: kwargs are easier for callers; properties are reachable by anything that has the schema vector (including the disk-scanning scanner with no live system).
2. **Example shape — literal value vs. zero-arg fn vs. both?** Recommendation above is both (`:seon.render/example` for the literal, `:seon.render/example-fn` for the rare parameterized case). Confirm.
3. **HUD storage on `:seon.session/render-overrides`?** Confirms it's per-session (rehydrates on resume), not per-namespace-globally. If you want a separate "user-wide defaults" layer it's a third tier.
4. **Boundary location — keep at `bin/mcp-server` (MCP-only) or push down into `seon.repl` (all-nREPL-callers)?** MCP-only is simpler and matches current agent topology. Push-down catches future nREPL clients but adds a layer.
5. **`suggest`-on-nil opt-in vs. always-on?** Recommendation: opt-in via `(seon.harness/suggest x)` plus a session-level toggle; defaults off. Always-on at the boundary is noisy.
6. **Fn-pointer canonical form — qualified symbol?** That's the recommendation (survives wire/DB). Vars/strings accepted at call sites and normalized.
7. **Cleanup of `seon.ns.view` / `seon.ui.viewer` / `seon.render.example`** — fold into this work (R0) or leave to `overlap-three-rendering.md`'s own pass? The `seon.ns.view` multimethod system overlaps with §E's boundary dispatch; resolving the overlap in this PRD avoids a second redesign.
8. **`:seon.render/example` data — also a candidate for property-based tests?** Sean said "test(s)" — if the example doubles as a test fixture (validate against the input schema, run the renderer, assert non-nil output), that's an easy win that doesn't need a separate `:seon.render/test` slot.

---

Loadbearing in this proposal: §C (the `register!` API shape), §F (HUD storage), §E (boundary location), §J Q2 (example shape). Everything else follows once those four are settled.
