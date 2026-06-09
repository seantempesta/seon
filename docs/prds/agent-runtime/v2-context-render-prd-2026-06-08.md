---
type: prd
status: draft
tags: [prd, agent, flow, schema]
---

# Context rendering — derived, never stored (rev 2, 2026-06-08)

> Context is ALWAYS a pure render of the database; it is NEVER stored as the
> thing you render from. Section layout is code. Per-agent customization is a
> written render function (code-as-data), not stored config. The only thing
> persisted is immutable history (what was actually sent). Written after
> live-verifying the running V0 pod.

## CORRECTION to rev 1 (read this)

Rev 1 claimed schema drift + missing schema persistence + missing per-kind
renders. **Verified FALSE against the running pod (2026-06-08):**

- **Schema persistence EXISTS and works.** 10 entity-shape schemas registered,
  10 persisted as `:seon.schema` entities (keyed by `:seon.schema/key`) via
  `seon.schema/all-entity-schemas-tx-data`, seeded at boot by
  `seon.client/start-agent!`. The "0 persisted / 215-vs-87 drift" was a
  **misdiagnosis** — I queried `:seon.schema/name` (doesn't exist) instead of
  `:seon.schema/key`, and counted all 215 registered schemas (req/resp + leaf
  attrs) against the 87 that are genuinely datahike attrs. Of the 10 ENTITY
  schemas, all 10 persist + install. No drift.
- **Per-kind renders EXIST.** `:seon.fn`, `:seon.ns`, `:seon.schema`,
  `:seon.eval`, `:seon.message` all carry `:seon.schema/render-fn` +
  `:seon.schema/render-html-fn`. Do NOT rebuild these.

**Do NOT build a `seon.server.corpus` that re-implements persistence/install/
renders — that machinery exists. Reuse it.**

## The real bug (verified live, agent `LZL-2605271732`)

Context assembly depends on **stored** per-agent config and silently renders
empty when it is absent; and there are two divergent context paths.

- `agent/assemble-ctx` (`agent.cljs:1298`) reads its section LIST from the
  agent's stored `:seon.agent/ctx` component entities. The agent has **none**
  (`:seon.agent/ctx` = nil) → 0 sections → **~22 chars** of context. The agent
  ran blind.
- The same default sections, when actually run, produce **~12.5K chars**
  (system 1098, messages 1510, warnings 77, recent-evals 9790, prompt 38;
  current-ns **0** — a separate bug). So the content renders fine; the agent
  just never got the recipe.
- The inspector left pane (`inspector.cljs:114`) calls a **different** fn,
  `render/assemble-ai-context`, which does NOT read stored ctx → **6129 chars**.
  So the webview shows a context the agent never receives. **Divergence.**
- `:seon.turn/prompt-text` is persisted **empty** — we send blind AND keep no
  record of what was sent.

Root cause: **context depends on accumulated stored state (`:seon.agent/ctx`)
that can be absent**, instead of being a pure function of the DB with a code
default. Two paths exist precisely because one trusts storage and one
re-derives.

## Principle (locked by the user, 2026-06-08)

- **Context is always rendered, never stored as source.** A pure function of the
  DB at render time. Fix the data → the context follows (self-healing).
- **Section layout is code**, not stored entities. The default section list is a
  plain function. Absence of any stored config = the code default. Never empty.
- **Customization is a written render fn** (persisted as `:seon.fn`
  code-as-data), resolved via the agent's `:seon.render/ai` slot. An agent
  rewrites its context by writing a function, not by storing config entities.
- **The only thing stored is immutable history** — `:seon.turn/prompt-text` =
  the exact bytes sent that turn (provenance). Never read back to build context.
- **One mechanism.** The agent's prompt path AND the inspector call the SAME
  render fn. Divergence becomes impossible.
- Public fns: **maps in / maps out**. Private helpers: positional.

## Goals

1. **`assemble-context`** — ONE pure fn `{:seon.db/db :seon.agent/id} →
   {:seon.render/ai <text> :seon.server.context/sections [...] :…/token-estimate}`.
   Section list from a **code default** (`substrate-default-ctx`); optional
   per-agent override is a *resolved render fn*, not `:seon.agent/ctx` entities.
   Does NOT depend on stored `:seon.agent/ctx`.
2. **Solid, individually-testable section functions** (system, messages,
   current-ns, warnings, recent-evals, prompt) — each a pure
   `(input) → string`, reading DB facts, storing nothing. (They mostly exist;
   the fix is decoupling the section LIST from stored ctx.)
3. **Unify the two paths.** `render-prompt` (agent) and the inspector left pane
   both resolve+call `assemble-context`. Collapse `assemble-ai-context` into it
   (no `assemble-ctx` vs `assemble-ai-context`).
4. **Persist provenance.** The turn open-tx stores the EXACT rendered prompt on
   `:seon.turn/prompt-text` (non-empty when sections have content).
5. **Guard tests** (catch this class early):
   - context is non-empty when sections have content;
   - agent-path output ≡ inspector-path output ≡ persisted `prompt-text` for the
     same (db, id);
   - each section fn renders non-blank given seeded data;
   - assembling context for an agent with NO stored `:seon.agent/ctx` still
     yields the full default context (the regression that bit us).

## Non-goals / reuse (do NOT rebuild)

- Schema persistence (`all-entity-schemas-tx-data`) — works, used at boot.
- Per-kind renders (`:seon.schema/render-fn`) — exist for fn/ns/schema/eval/msg.
- The reactive `d/listen` → datastar SSE two-pane webview — works (no polling).

## Genuinely-missing / separate (own issues, smaller, AFTER the core)

- **`:seon.test` entity kind** — tests are NOT persisted as data (no `:seon.test`
  in `entity-schema-keys`). Add the entity kind + a render, mirroring `:seon.fn`.
- **map-in calling-convention primer** — the live agent failed **4/7** evals
  calling `seon.db/query` positionally; it's map-in. The system section must
  teach the convention (derive a worked example from the fn's persisted
  arglist).
- **`current-ns-section` returns 0** — renders empty for this agent; investigate
  (render-recon flagged a stale docstring claiming detect-and-tee "hasn't
  shipped"; it has).

## Where this lives

The running runtime is the **V0 pod** (`.cljs`). These solid section functions
are exactly what the V2 host inherits, so build/fix them here, in the pod, where
they are testable against the live agent — then port. Files:
`src/seon/agent.cljs` (assemble-ctx, the 6 section fns, render-prompt),
`src/seon/render.cljs` (assemble-ai-context), `src/seon/web/inspector.cljs`
(left pane) + tests.

## Plan (delegated, verified)

- **T1 research/validate** — independently confirm the bug findings, map the
  full context subsystem + every caller of the two paths, and produce the
  precise refactor spec (how to collapse `assemble-ctx`/`assemble-ai-context`
  into one derived `assemble-context`; how `render-prompt` + inspector both call
  it; where prompt-text is persisted). Write to disk. (Research before impl —
  and I have misdiagnosed once, so validate before touching the running system.)
- **T2 implement** — the derived `assemble-context` (code-default layout, no
  stored recipe), unify the paths, persist prompt-text, the guard tests. Run
  targeted tests; full suite at the end.
- **T3 verify** — `seon-verifier` against the guard criteria + a live REPL check
  that the agent now receives ~12.5K of real context and prompt-text is stored.
- **Follow-ups** — `:seon.test` kind; map-in primer; `current-ns-section` = 0.

## Success criteria

- `assemble-context` is the single source; agent-prompt ≡ inspector ≡
  persisted prompt-text (guard test green).
- An agent with no stored `:seon.agent/ctx` gets the full default context
  (regression test green) — context never depends on stored config.
- No `:seon.agent/ctx` lookup is required for context to render.
- Public fns map-in/out with registered schemas; full suite green; 0 warnings.

---

## Phase 2 — default namespace rendering + context layout (MVP, demo 2026-06-12)

> Phase 1 (the divergence fix + caps) is DONE: commits `5f2a564`, `03d2294`,
> `448d936`. Phase 2 builds the DEFAULT context every agent gets — efficient
> namespace rendering + a static→dynamic layout — so a fresh agent dropped in a
> near-empty namespace operates from its REQUIRED namespaces' rendered context.
> Agents do NOT manage their own context yet (per-agent override is a later
> phase); we ship ONE good default in `seon.agent` for all agents.

### Principles (user, 2026-06-08)

- **Default now, override later.** One good default in `seon.agent`.
- **Order most-static → most-dynamic** (prompt-cache friendly).
- **Agents don't fiddle with context** — they `require` what they need, we
  re-render those namespaces next turn, it's just there. They focus on working.
- **No invented verbs.** It's a REPL: `(result <id>)` returns the value; agents
  use normal Clojure (`get-in`/`filter`/…) to inspect. Custom
  `seon.db/query`/`pull` is for novel USER questions (no existing fn) —
  clip-guarded.
- **They are a ClojureSCRIPT agent in a Node pod**, not JVM Clojure — say so +
  flag platform-access differences (`js/` interop, node requires, no `java.*`).

### The context layout (static → dynamic)

| # | Section | Source | Cache |
| --- | --- | --- | --- |
| 1 | System prompt | CLJS-in-Node + conventions + REPL contract | static |
| 2 | Capabilities | core API worked examples (call → result → recovery) | static |
| 3 | Namespace context | `render-namespace` of required nses (depth 1, prepended) + own ns | mostly static (busts on ns edit) |
| 4 | Warnings | current cross-agent problems (failed/slow evals, failing tests); reactive | dynamic |
| 5 | Transcript | messages+evals interleaved; user input as REPL events | dynamic |
| 6 | Prompt line | `seon.agent.<id>=> ; turn N` | always changing |

`root-pull` DELETED. `current-turn`/`current-session` sections folded into the
prompt line (a REPL already shows your ns; only the turn count changes). The
`:warnings` section is RETAINED (user, 2026-06-08): a reactive cross-agent
surface that queries the DB for current problems (failed/slow evals, failing
tests) and vanishes when the underlying state is fixed — nothing stored,
self-healing. It sits between namespace-context and transcript (dynamic, but the
specific problems change less often than the transcript).

**T5 SHIPPED (2026-06-08).** `substrate-default-ctx` is the 6-section
static→dynamic layout above. `root-pull` (fn + system-section advertisement)
DELETED; superseded `messages-section`/`recent-evals-section`/`current-ns-section`
DELETED (replaced by `transcript-section` + `namespace-context-section`).
`namespace-context-section` calls the shipped `render-namespace` (depth 1).
`transcript-section` merges `:seon.turn/messages` + `:seon.turn/evals` by `:at`
into one chronological REPL stream (messages as `<role>>` events, evals via
`format-eval-row`). Prompt line extended to `seon.agent.<id>=>  ; turn N`.
Verified live (fresh agent `zqc-2606082040`): sections in order, root-pull gone,
transcript interleaved, agent-path ≡ inspector-path, `:seon.turn/prompt-text`
persisted non-empty (16K-capped). Tests: `agent-context-test` 35/35,
`agent-render-namespace-test` 25/25, `render-test` 29/29.

### `render-namespace` — the foundational fn (`seon.agent`)

`(render-namespace {:seon.ns/name <kw> :seon.render/depth <int=1> :seon.render/format :ai|:html}) → text|hiccup`

- Renders: ns source + each `:seon.fn` (sym, arglist, doc) + each `:seon.schema`
  (key, form) + each `:seon.test`, grouped + readable.
- **Recursive on requires:** parse the ns's `(:require …)`, render required nses
  FIRST (prepended, so references resolve), to `:depth` (default 1). Dedup,
  bound depth.
- **AI (text) + HTML (hiccup)** forms. Reuse the per-kind renders where sensible;
  surfaces the missing `:seon.test` kind.
- Bounded per-member; the clip guardrail (T7) backstops.

### Default verbs (composable, bounded — `seon.agent`)

`agent-entity` (own scalar attrs), `messages {:n}`, `evals {:n}` (clipped result
inline), `current-turn`/`current-session`/`current-ns`, `result <id>` (live
value; drill with normal Clojure). NO `root-pull`, NO `probe`.

### Work cycle (toward 2026-06-12) — serialize (all touch `agent.cljs`)

- **T4** `render-namespace` (AI+HTML, recursive requires) — DEMO-CRITICAL, foundation — SHIPPED (b3c06b8)
- **T5** context reorg + transcript + prompt line + DELETE `root-pull` — DEMO-CRITICAL — SHIPPED (2026-06-08)
- **T6** system-prompt rewrite (CLJS-in-Node + motivated examples, no "DO NOT" blocks) — DEMO-CRITICAL — SHIPPED (cafca26). Positive/motivated framing, CLJS-in-Node platform reality (js/ interop, no java.*), REPL-channel mechanism explained (not scolded), re-synced to the T5 section names. Plus capabilities-section prose fixed to acknowledge T15 positional db ops (1ef6b1c).
- **T7** clip guardrail on `query`/`pull`/eval output (guiding messages) — DEMO-CRITICAL (stability) — SHIPPED (2026-06-08). Two surfaces, no double-noising: (1) STORE boundary `seon.eval/render-result-edn` row-count guards a large COLLECTION result (`result-row-cap` = 50) into a bounded preview with a PREPENDED guiding "+N more clipped, narrow your query" message (survives the downstream display cap); (2) DISPLAY surface `seon.agent/cap-result-body` (used by `format-eval-row`) appends a guiding "narrow it: aggregate/limit/tighter :where/fewer attrs; (result :<eid>) holds the full value" message when a huge SCALAR clips at `eval-render-cap` (1500). Collections are bounded upstream so their small preview never re-trips the size guide — exactly one guide fires. Pure render: stores nothing new; the full value stays in the globalThis `(result <id>)` stash and the 16K `cap-edn` blob. Live-verified through the real `record-eval!` pipeline (4000-row result → 390-char stored preview + row guide; full 4000-elem value intact via stash). Tests: `memory-safety-test` 7/20, `agent-context-test` 15/55.
- **T10** GLOBAL schema-catalog context section — DEMO-CRITICAL (user 2026-06-08
  night) — SHIPPED (2026-06-08). `schema-catalog-section` (`seon.agent`) renders
  EVERY entity KIND in the system, grouped by owning namespace, REGARDLESS of the
  agent's current ns — the agent's answer to "what data exists." DERIVED from the
  `:seon.schema` entities seeded at boot (`all-entity-schemas-tx-data`): a kind is
  a `:seon.schema` entity carrying a `:seon.schema/render-fn` (a renderable `:map`
  entity-shape, NOT a req/resp map). Per-attr SHAPE (type + optional flag) pulled
  from the live registry (`schema/schema-definition`); identity attr flagged; one
  AEVT count per kind gives live instance counts (defined-but-empty kinds still
  list). Inserted at priority 25 in `substrate-default-ctx` — between `:capabilities`
  (static) and `:namespace-context` (the deep per-ns view): the BROAD cross-ns view
  precedes the DEEP current-ns view, and is more static (busts only on schema
  register). Pure derived render — stores nothing. Live-verified on fresh boot
  (agent `tOx-2606082135`, near-empty home ns): all 8 substrate kinds
  (`:seon.conventions :seon.eval :seon.fn :seon.message :seon.ns :seon.schema
  :seon.system-prompt :seon.test`) listed with attrs + counts, 2.3K chars;
  derived-not-hardcoded proven (register throwaway kind → appears; retract → vanishes);
  agent-path ≡ inspector-path holds. Tests: `agent-context-test` 77/77 (added 4
  catalog tests + extended 4 existing); `agent-render-namespace-test` 25/25,
  `render-test` 29/29. Blocks the fresh-agent-Q&A half of the e2e demo.
- **T8** demo loop: fs → shared read-only folder, drive a fresh agent end-to-end —
  DEMO-CRITICAL. Refined to the full E2E test (user 2026-06-08 night): (1) a live
  DeepSeek agent READS + INDEXES seon's own docs (not in DeepSeek's training data →
  real read/digest/learn signal), defining hard SCHEMAS + FUNCTIONS + TESTS and
  STORING the learned data; (2) a FRESH agent on the SAME conn (DB is `:memory`, so
  same pod process — new agent identity + clean context, shared substrate DB) is
  asked NON-TRIVIAL questions that require digging through the stored
  schemas/fns/datoms to answer. Needs `DEEPSEEK_API_KEY` (present) + `seon.fs`
  read-allowlist on `docs/`. T7 clip guardrail protects against flood/OOM. Run
  BOUNDED + observe COMPACTLY.
  **FIRST LIVE RUN (2026-06-08, agents rxH-2606082150 / KUK-2606082153) — findings
  in `research/e2e-demo-findings-2026-06-08.md`.** Result: the loop WORKS (a smoke
  agent read derived context incl. the schema-catalog and correctly eval'd +
  answered). DeepSeek READ + DIGESTED docs well (designed a coherent deeply-nested
  `:seon.kb.*` 15-schema model from ~10 real docs). BUT Phase 1 stored NO facts and
  Phase 2 never ran, due to three substrate bugs (T11/T12/T13 below). Re-run after
  those land.
- **T11** TURN-KILLER fix — DEMO-CRITICAL, BLOCKS ALL TURNS — SHIPPED (7ed87ef).
  `seon.analyzer-info/snapshot-defs` emitted a `nil` ns key (cljs.js
  `register-constant!` writes the keyword-constants table under a nil ns when a
  bare keyword is analyzed with no enclosing ns) → `:malli.core/invalid-output`
  aborted every turn. Fix: `:when (symbol? ns-sym)` drops the def-less nil entry.
  Live: 53 post-fix turns `:done`, 0 errors.
- **T12** SURFACE EVAL ERRORS to the agent — DEMO-CRITICAL — SHIPPED (751ec4e).
  record-eval! stored the whole seon.error/->map (~2271 chars, #error broke the
  agent-side read-string). New render-error-string persists a legible
  :seon.eval/error (deepest message via :seon.error/cause + structured data, ~227
  chars); format-eval-row renders `;; ERROR <msg>` (plain elision, not the
  narrow-guide). The agent now sees + reacts to its failures.
- **T13** ADD `seon.schema/register!` to "## What you can do" — DEMO-CRITICAL —
  SHIPPED (751ec4e). Added register! to the capability syms + a "Storing a NEW
  KIND of data: register FIRST" register→transact→query worked example. PLUS the
  root-cause fix `seon.db/ensure-datahike-attrs!` (in transact!*): register! only
  updates the Malli registry, but the datahike conn (:schema-flexibility :write)
  still rejected new attrs — transact!* now auto-derives + transacts the datahike
  declaration for newly-registered attrs (schema before data), so register!→
  transact! actually works. Verified register→store→read end-to-end.
- **T14** PHANTOM EMPTY-NS DEFS lose eval records — DEMO-CRITICAL, NEXT. Surfaced
  during T12/T13 (sibling of T11): `seon.analyzer-info/defs-since` returns stale
  defs from a `nil`/`""`-keyed ns bucket (leftovers from prior agents' evals) →
  `build-tee-entities` builds `:seon.fn/ns [:seon.ns/name :]` (empty-string ns →
  `(keyword "")` → `:`) → `record-eval!`'s atomic tx fails ("Nothing found for
  entity id [:seon.ns/name :]") → the eval record itself is LOST. T11 fixed
  `snapshot-defs`'s nil-KEY exposure; `defs-since`/the detect-tee path still
  surface phantom defs. This is why a clean register→store didn't fully complete
  live. Fix in `seon.analyzer-info` (filter non-symbol/blank ns in defs-since +
  guard build-tee-entities).
- **T16** SCOPE `<warnings>` to the agent — surfaced during T12/T13. The
  substrate-wide "N failed evals across agents" warning leaks OTHER (and prior
  demo) agents' failures into a FRESH agent's context, derailing it onto
  "investigate the failures" instead of the user's task. Scope the warnings
  section per-agent (or to the current agent's recent evals).
- **T9** `:seon.test` entity kind + render; (later) per-agent context override — DEFERRED

Each step: implement (`seon-agent`) → verify live → commit. (T15 = positional db
ops, already shipped on the db track — distinct numbering; see git log.)

### Demo definition (2026-06-12)

A fresh single agent boots into its namespace, receives rich default context
(system + capabilities + its required namespaces rendered + transcript), is
driven by typing in the webview, explores a shared read-only folder + composes a
DB query for a question we didn't pre-build a function for, and we watch its
`:seon.render/ai` + `:seon.render/html` views update live — stable, no
OOM/flood.
