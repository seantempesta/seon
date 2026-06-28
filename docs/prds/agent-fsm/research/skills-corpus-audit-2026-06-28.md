---
type: research
status: active
tags: [research, agent, index]
---

# Skills Corpus Audit — every token should be there

Read-only audit of the agent + dev skill corpus against the owner directive
"every token should be there, or refine it to return better tokens." Skills are
loadable on demand, but (1) their always-on **catalog descriptions** are paid
every render, and (2) overlapping bodies waste tokens whenever loaded and double
the surface that bit-rots. This audit maps who-teaches-what, finds the
duplicated facts, lists the stale claims, and ranks the fixes. **No skill was
edited.** Any later content fix is IN-PLACE (no `skill-v2`).

## TL;DR

- **Top overlap:** the **no-kinds + FIND/IDENTIFY/RELATE/SCOPE** four-moves block
  and the **Malli→datahike bridge derivation table** are each taught in full in
  **three / two** skills respectively. The NEW `data-modeling` skill re-teaches
  large chunks of `datahike` (bridge table, banned types, register! scalars,
  no-kinds moves) and the no-kinds principle from `data-oriented-clojure` —
  despite cross-link headers that say "don't duplicate." One fact, one home is
  not yet enforced.
- **Top staleness:** `ui-live-tiles` "Honest scope — NOT yet interactivity"
  (lines 270-283) is going stale: `/agent/{id}/call` is a live committed route
  and `my.tile` (interactive controls calling your own fns back) exists in the
  tree (currently UNTRACKED / mid-flight). The skill flatly says "no onClick, it
  isn't there" and "`my.tile/show!` is not in the live system."
- **Catalog cost:** the catalog renders each skill's full `description:`
  frontmatter VERBATIM as an always-on line. Two descriptions are essays
  (`ui-live-tiles` ~238 tok, `data-oriented-clojure` ~231 tok); the six-skill
  catalog is ~960 always-on tok. The description is forced to double as both the
  Claude-Code trigger (verbose is fine) AND the always-on catalog line (verbose
  is pure cost) — a structural tension.
- **Gap:** `my.data` (aggregation verbs `rows`/`sum-by`/`max-by`/`group-sum`,
  shipped) and `my.tile` (interactive canvas, mid-flight) are not covered by any
  skill.

## How the catalog cost works (grounds finding 3)

`src/my/skills.cljs` `catalog-block` (priority 12, cached prefix, ALWAYS-ON)
emits one `catalog-line` per skill = `:my.skills/description` **verbatim**
(`skills.cljs:277-292`). The SKILL.md `description:` frontmatter IS the always-on
catalog text. Measured description lengths (chars → ~tok at chars/4):

| Skill | desc chars | ~tok | catalog-line quality |
|---|---|---|---|
| `repl` | 378 | ~95 | tight — single trigger clause |
| `datahike` | 484 | ~121 | good — verb list is the trigger |
| `clojurescript` | 488 | ~122 | good |
| `data-modeling` | 631 | ~158 | borderline — long "use when" tail |
| `data-oriented-clojure` | 924 | ~231 | ESSAY — full reflex catalog inline |
| `ui-live-tiles` | 951 | ~238 | ESSAY — narrates the whole skill |

## Per-skill table

| Skill | Lane | Purpose | Overlaps with | Stale? | Catalog line | Grounded? |
|---|---|---|---|---|---|---|
| `data-oriented-clojure` | UI | The mindset (why no-kinds, namespaced keys, errors-as-values, derive-don't-store, fix-in-place) | datahike (no-kinds moves), data-modeling (no-kinds, banned types, fn-spec shapes) | clean | essay (~231 tok) — trim | yes (clojure-idioms doc + datahike-primer, file:line) |
| `datahike` | Core | DB mechanics: query/transact/pull/upsert/retract, runtime split, bridge table, discovery | data-modeling (bridge table, banned types, register! scalars, no-kinds moves), data-oriented (no-kinds) | clean | good (~121 tok) | yes (db.cljs, my.kb, todo, reference-code) |
| `data-modeling` | Core | Design decisions: pick type, identity/ref/component, map-in/out as generation target, schema-as-generator | datahike + data-oriented (heavy — see below) | clean | borderline (~158 tok) | yes (schema.cljc, internal.cljs, my.kb, malli source) |
| `clojurescript` | Core | Pod CLJS: `^:async`/await, self-host eval, Promise auto-await, instrument wedge | data-oriented (brief async cross-ref only) | verify the "Promise dropped on timeout" gap (lines 80-82) | good (~122 tok) | yes (cljs.cljc/analyzer/compiler line refs, eval.cljs) |
| `repl` | UI | How forms are read/repaired/run (parinfer, `;` vs `;;`, what evaluates vs drops) | none | clean | tight (~95 tok) | yes (seon.repl.internal, seon.repair) |
| `ui-live-tiles` | UI | Render a live VIEW to your canvas; my.ui compose; block renderer; safelist | data-oriented (derive-don't-store, cross-linked) | **STALE** — interactivity scope (270-283); MISSING my.tile + my.data | essay (~238 tok) — trim | yes (live_tile.cljs, render.cljs, input.css) |
| `datastar-web-ui` (dev) | UI | Pod web stack: view=f(db) gzip morph, reitit-from-datoms, render/block+slot, Phosphor theme | browser-automation (SSE-server-side note, cross-linked) | clean | n/a (dev, not in agent catalog) | yes (datastar.cljs, router.cljs, reference-code/datastar) |
| `browser-automation` (dev) | UI/dev | Verify pod UI in Chrome MCP; SSE 503 limit; tab ownership | datastar-web-ui (cross-linked) | clean | n/a | yes (serve/datastar/router.cljs) |
| `clojure-testing` (dev) | shared | Pod-first CLJS tests: fresh conn, root set! not binding, async envelope | clojurescript/datahike/data-oriented (cross-linked handoffs) | clean | n/a | yes (db_test, ctx_test, kb_test, skills_test) |
| `seon-context-config` (dev) | Core/config | What an agent SEES: blocks, skill loadout, render caps, manifest | none | self-flagged "DESIGN IN FLIGHT" (74-82) — accurate | n/a | yes (config.cljs, system.edn, ctx.cljs) |

Symlink note: `.claude/skills/` symlinks `clojurescript`, `data-oriented-clojure`,
`datahike`, `repl` (dev+agent). `ui-live-tiles` and `data-modeling` are
**AGENT-ONLY** — not visible to Claude Code dev lanes. `data-modeling` in
particular is directly relevant to Claude Code schema work; consider symlinking
both (zero cost — agent corpus loads by folder, not by `.claude/skills`).

## Overlap / dedup — who owns what fact going forward

The corpus design intends three non-overlapping homes: **data-oriented-clojure =
the WHY (mindset)**, **data-modeling = the DESIGN decision (what shape, why)**,
**datahike = the MECHANICS (query/transact + the live-verified bridge)**. In
practice the same facts appear in two or three. The single-ownership target:

### 1. no-kinds + FIND/IDENTIFY/RELATE/SCOPE — taught in THREE (top finding)

- `data-oriented-clojure` lines 42-65 — full block + WRONG/RIGHT + the 4 bullets.
- `datahike` lines 49-75 — full block + the 4 moves (richest: provenance vs
  ownership split).
- `data-modeling` lines 22-39 — full block + the 4 moves AGAIN ("Step 0").

**Own it:** the **principle** ("entity = attributes + connections, no kinds")
lives ONCE in `data-oriented-clojure` (the mindset skill). The **datahike-applied
four moves** (attr-presence query / identity upsert / ref cascade / origin scope,
with the provenance-vs-ownership nuance) live ONCE in `datahike`. `data-modeling`
should state it in ONE sentence + cross-link both — drop its FIND/IDENTIFY/
RELATE/SCOPE re-list (it already cross-links them in its own intro, lines 17-20).

### 2. Malli→datahike bridge derivation table — taught in TWO

- `datahike` lines 137-146 ("Bridge derivation (live-verified)").
- `data-modeling` lines 83-95 (nearly the same table).

**Own it:** the **lookup table** belongs in `datahike` (it's a "what gets
installed" mechanics reference, live-verifiable with `malli->datahike-schema`).
`data-modeling` keeps the DESIGN-INTENT column (which shape expresses which
intent) but points at datahike for the installed-facet table instead of
reproducing it.

### 3. Banned types (`:any`/`:maybe`, optional=absent) — taught in THREE

`data-oriented-clojure` 104-107, `datahike` 153-156, `data-modeling` 99-101.
**Own it:** the rule's *rationale* in `data-oriented-clojure`; the *enforcement*
(transact! rejects, register! rejects at startup) in `datahike`. `data-modeling`
references, doesn't restate.

### 4. register! scalar examples + "single source of truth" — taught in TWO

`datahike` 120-131 and `data-modeling` 48-78 both open with a `register!`
scalar-types block. **Own it:** `datahike` keeps the terse mechanics block;
`data-modeling` keeps ONLY the design-annotated version (the "constrain the
VALUE" / enum / cardinality commentary that is genuinely design guidance), not a
second plain type list.

### Cross-links that are already correct (keep)

- map-in/out vs `:catn` — `data-modeling` owns the deep version (151-216);
  `data-oriented` keeps the 3-line summary + cross-ref. Good.
- async — `data-oriented` 170-177 brief + cross-ref to `clojurescript`. Good.
- derive-don't-store — `data-oriented` principle, `ui-live-tiles` applies it to
  the human surface with a cross-ref. Acceptable (different application).
- shared-shapes-once — in both `data-modeling` (112-126) and `datahike`
  (148-150); minor, leave or collapse datahike's to a pointer.

## Staleness list

1. **`ui-live-tiles` lines 270-283 — interactivity scope is going STALE.**
   `/agent/{id}/call` is a live committed route (`router.cljs:8,274`) and
   `src/my/tile.cljs` exists (interactive controls that call your own fns back
   via the `/call` gate). The skill says "NOT yet: interactivity… no built-in
   way to put a working button… `my.tile/show!` is not in the live system… it
   isn't there." `my.tile` is currently UNTRACKED (mid-flight) — so the claim is
   true for the committed state TODAY, but flips to wrong the moment my.tile
   lands. **Update this section in place when my.tile commits** (don't add a
   second skill).
2. **`ui-live-tiles` covers `my.ui` but not `my.data`.** `my.data` (shipped,
   committed `1929644c`) gives aggregation verbs that dodge the datalog
   `(sum)`-dedup + argMax footguns — directly relevant to "I have rows, show the
   number." Not mentioned anywhere.
3. **`clojurescript` lines 80-82 — verify the "pending Promise dropped on
   timeout" gap.** Memory notes a stash-on-timeout / re-reference design + a
   parse-recovery effort in flight; confirm whether the gap still holds before
   trusting the claim.
4. **`seon-context-config` lines 74-82 — self-flagged "DESIGN IN FLIGHT"
   (named-profiles → explicit lists).** Accurate and honestly marked; refresh
   when the `:seon.config/load` + `:seon.config/namespaces` shape lands.

No skill describes RETIRED mechanics (`/world` is current, no `:kind`, tokens not
chars, the config-context model is current). The `.clj` JVM stack is correctly
fenced as "paused track" everywhere it appears.

## Ranked action list

1. **[Core] Dedup the no-kinds four-moves + bridge table + banned types across
   `datahike` / `data-modeling` / `data-oriented-clojure`.** One fact, one home
   (mappings above). Trims the biggest cross-skill redundancy; `data-modeling`
   shrinks most. In-place edits to all three.
2. **[UI] Fix `ui-live-tiles` interactivity staleness + add `my.tile` /
   `my.data`.** Rewrite the "Honest scope" section to reflect the live `/call`
   route + `my.tile` controls (gate on my.tile landing), and add a short
   my.data "show the number" pointer. Same skill, in place.
3. **[UI + Core] Trim the two essay catalog descriptions** (`ui-live-tiles`
   ~238, `data-oriented-clojure` ~231 tok) to tight trigger clauses — and decide
   the structural fix: either accept the dual-purpose description, or add a
   separate short `catalog:`/one-line field so the always-on line ≠ the verbose
   Claude-Code trigger. ~250+ always-on tok recoverable.
4. **[Core] Symlink `data-modeling` (and `ui-live-tiles`) into `.claude/skills/`**
   so Claude Code dev lanes can trigger them (zero runtime cost; data-modeling is
   high-value for schema work).
5. **[Core] Verify + refresh the `clojurescript` timeout-Promise gap** (item 3
   above) against the current `seon.eval` so the one explicitly-dated claim isn't
   stale.

## Key files

| File | Why |
|---|---|
| `src/my/skills.cljs` | `catalog-block` (always-on line = verbatim `description:`), `skill-block` (loaded body) |
| `seon-skills/*/SKILL.md` | the audited agent corpus |
| `.claude/skills/**` | dev corpus + symlinks (data-modeling/ui-live-tiles NOT symlinked) |
| `src/my/data.cljs` · `src/my/tile.cljs` · `src/my/ui.cljs` | the shipped/mid-flight toolkits driving the gaps |
| `src/seon/web/router.cljs` | confirms `/agent/{id}/call` is live (interactivity) |
