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
| 4 | Transcript | messages+evals interleaved; user input as REPL events | dynamic |
| 5 | Prompt line | `seon.agent.<id>=> ; turn N` | always changing |

`root-pull` DELETED. `current-turn`/`current-session` sections folded into the
prompt line (a REPL already shows your ns; only the turn count changes).

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

- **T4** `render-namespace` (AI+HTML, recursive requires) — DEMO-CRITICAL, foundation
- **T5** context reorg + transcript + prompt line + DELETE `root-pull` — DEMO-CRITICAL
- **T6** system-prompt rewrite (CLJS-in-Node + motivated examples, no "DO NOT" blocks) — DEMO-CRITICAL
- **T7** clip guardrail on `query`/`pull`/eval output (guiding messages) — DEMO-CRITICAL (stability)
- **T8** demo loop: fs → shared read-only folder, drive a fresh agent from the webview end-to-end — DEMO-CRITICAL
- **T9** `:seon.test` entity kind + render; (later) per-agent context override — DEFERRED

Each step: implement (`seon-agent`) → verify live → commit.

### Demo definition (2026-06-12)

A fresh single agent boots into its namespace, receives rich default context
(system + capabilities + its required namespaces rendered + transcript), is
driven by typing in the webview, explores a shared read-only folder + composes a
DB query for a question we didn't pre-build a function for, and we watch its
`:seon.render/ai` + `:seon.render/html` views update live — stable, no
OOM/flood.
