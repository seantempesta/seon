---
type: research
status: active
tags: [research, agent]
---

# Curated Teaching Set for Live DeepSeek Agents (2026-06-23)

## TL;DR

The agent's whole context is (aspirationally) one live, eval'able Clojure REPL
where the real source code IS the instruction manual. Today ~60 namespaces
render, but only a handful are shown as FULL source — the rest are truncated
`[fn]`/`[schema]` signature labels (manifest tail). The proposal: CURATE DOWN
to a ruthless teaching set rendered as REAL `(defn …)` + `(register! …)`
source, drop the framework-internal long tail entirely from full-source, and
refine the chosen files' comments so they teach.

A DeepSeek agent can already do a great deal from its context: talk to its
human and peers (`message/user` / `message/agent`), plan multi-step work
(`seon.agent.todo`), end its work cleanly (`agent/wait` / `complete`), query
and change the shared datahike store (`seon.db`), define data shapes
(`seon.schema/register!`), remember/recall via a schema'd knowledge base
(`my.kb.*`), and search + read + write files (`seon.agent.search` /
`seon.agent.fs`). The agent does NOT call `seon.eval` — it just writes forms;
eval is the runtime's job.

Two findings worth flagging up front:

- LIVE BUG in the ground-truth transcript: the very first `(message/user …)`
  returned `:seon.db/ok? false` — "Nothing found for entity id
  `[:seon.user/id "user"]`". The user entity was NOT seeded at boot, so the
  agent's hello-to-human FAILED on turn 1. This is the single most important
  thing to fix before any teaching effort — the deaf-on-arrival failure mode
  is back, just one layer down (seed, not wake). See Open questions.
- `seon.db/pull-by-name` (named in the task brief) does NOT exist in
  `src/seon/db.cljs`. The pull surface is `pull` + `entity` (by lookup ref or
  eid). Drop the name from any teaching.

## Capability surface

What a DeepSeek agent can actually DO from its rendered context. Each line:
namespace · key fn(s) · what it lets the agent do. (Source files read in full
for this survey: `agent/message.cljs`, `agent/todo.cljs`, `agent/fsm.cljs`,
`agent/search.cljs`, `agent/inspect.cljs`, `agent/fs.cljs` head, `agent.cljs`
lifecycle verbs, `db.cljs` key fns, `schema.cljc` register!, plus the rendered
prompt artifact.)

### Talk to people (lifecycle of conversation)

- `seon.agent.message` · `user` / `agent` (thin wrappers over `message!`) —
  say something to the one human (`(message/user "…")`) or to a named peer
  (`(message/agent "id" "…")`). Reached via the `message/` alias on the
  agent's home ns. Self→self is refused with a loud envelope. Returns a
  concise `{:seon.agent.message/ok? true …}` or an error VALUE — confirm ok?
  before claiming it landed.
- `seon.agent.message` · `message!` — the single write path; the verbs above
  are sugar. Agents rarely call it directly but it documents fan-out (`to` =
  vector of refs) and the hop/ping-pong guard.

### Plan + track work

- `seon.agent.todo` · `add!` / `complete!` / `reopen!` / `list-open` — mint one
  todo per step of any 2+-step task BEFORE starting; complete! each as it
  lands. Open todos render every turn (each line carries its id); the section
  vanishes when none remain — the done-signal. THE EXEMPLAR store/retrieve ns.

### End the work (FSM verbs)

- `seon.agent` · `wait` / `complete` / `terminate` — `(agent/wait "note")`
  parks until a message wakes you (use after asking a question);
  `(complete "result")` finishes cleanly (still wakeable; routes to a parent
  if set); `terminate` is orchestrator-only (the one UNWAKEABLE state). Each
  is a small state transact returning the new state keyword.
- `seon.agent.fsm` (the loop itself) — NOT called by the agent, but the source
  teaches the stop policy + sliding-window cap (every inbound grants +1 turn).
  Keep as reference reading, not full-source teaching.

### Query + change the world

- `seon.db` · `transact!` — commit tx-data (map-in `{:seon.db/tx-data […]}` or
  positional `[…]`); upsert by identity; retract is explicit. Returns a VALUE
  envelope (`:seon.db/ok? true|false`) — never throws.
- `seon.db` · `query` — Datalog; auto-injects the db from `*conn*` (omit it).
  Guarded against typo'd attrs (legible throw, not silent `#{}`).
- `seon.db` · `pull` / `entity` — read one entity by lookup ref `[id-attr v]`
  or eid; `'[*]` inlines component children.
- `seon.db` · `store-inventory` — what the cluster holds RIGHT NOW, one row per
  kind; `{:seon.db/system? true}` adds the core boot index. The consult-first
  surface — read it before researching or registering.
- `seon.db` · `new-id!` / `with-agent` / `current-agent-id` — id minting and
  the ambient agent scope (agents read `current-agent-id`, never thread conn).

### Define data shapes

- `seon.schema` · `register!` — the single source of truth for attribute
  schemas; register an attr BEFORE the first transact that uses it. Map schemas
  with `{:seon.db/entity true}` become catalogued kinds. Supporting reads:
  `registered?`, `registered-schemas`, `schemas-in-namespace`, `enum-members`.

### Remember + recall (the knowledge base)

- `my.kb` (+ `my.kb.<domain>` sub-nses) — the agent's KNOWLEDGE BASE is NOT a
  store!/consult API. It is `seon.db/transact!` + `seon.db/query` over
  per-domain schemas the agent designs. `my.kb` registers the SHARED provenance
  attrs (`:my.kb/source-path`, `:my.kb/source-line`, `:my.kb/source-line-end`,
  `:my.kb/verified-at`, `:my.kb/confidence`) that every domain schema
  references. The move: register `my.kb.<domain>/*` attrs, then transact ONE
  row mixing the domain attrs with the shared provenance attrs. Recall =
  `store-inventory` + datalog the listed attrs. `my.kb.system` is the worked
  example (the cluster-wide instruction singleton; `(my.kb.system/instructions)`
  reads it). This is the highest-leverage and least-obvious capability — it is
  what turns research into durable, graded facts the next agent reuses.

### Search + read + write code/files

- `seon.agent.search` · `grep` — ripgrep over the allowed roots; pattern is a
  REGEX; resolves to an envelope (no matches = success). THE EXEMPLAR npm
  wrapper. The search→read recipe: grep returns absolute allowlisted paths that
  feed `read-file` directly.
- `seon.agent.fs` · `read-file` / `write-file` / `list-dir` / `walk-dir` /
  `stat` / `grants` / `configure!` — the agent's eyes + hands on the user's
  machine, default-deny allowlist; `grants` shows what's reachable.

### Introspect itself + the codebase

- `seon.agent.inspect` · `ctx-preview` / `handlers` — "what am I seeing right
  now?" `ctx-preview` returns the EXACT bytes the LLM receives next render.
  Useful, but more a debugging verb than a daily one.
- `seon.ctx` · `render-namespace` — pull a WHOLE manifested ns (its `(ns …)`
  source + every fn/schema) on demand. THE escape hatch from the curated set:
  anything dropped from full-source is one `render-namespace` call away. This
  fn is what MAKES ruthless curation safe — teach it prominently.

## Proposed curated teaching set

Shown as REAL source (full `(defn …)` + the ns's `(register! …)` forms),
ordered roughly the way an agent meets the need. "fns" = the specific fns to
keep rendered when the ns is large; `:full` = render the whole file.

| ns | fns | why it earns full-source |
| --- | --- | --- |
| `my.kb` | `:full` | The knowledge-base doctrine + shared provenance attrs; tiny; the highest-leverage, least-obvious capability. Recall depends on agents seeing this shape. |
| `my.kb.system` | `:full` | The worked KB-domain example (singleton + append + read). Shows a real `{:seon.db/entity true}` schema, a seed fn, and a query fn end-to-end. |
| `my.soul` | `:full` | Small; teaches that identity is live-from-disk (SOUL.md/AGENTS.md), not stored — frames "who am I" vs "how do I work". |
| `seon.schema` | `register!`, `registered?`, `registered-schemas`, `schemas-in-namespace`, `enum-members`, `identity-attr?` | `register!` is the one schema verb; the rest are the discovery reads. Drop the registry-internals (`relink-registry!`, `set-tee-fn!`, `*schema-required-counts`, `clear-all!`). |
| `seon.db` | `transact!`, `query`, `pull`, `entity`, `store-inventory`, `new-id!`, `current-agent-id`, `with-agent`, `decode-edn-value` | The core read/write API + id/scope helpers. Drop listener internals (`listen!`/`listen-async!`/`unlisten!`), `core-kinds`, `bootstrap-row-ids`, `installed-schema`, `entity-lazy` — framework plumbing the agent never calls. |
| `seon.agent.todo` | `:full` | THE exemplar store/retrieve ns; small; the agent's planning surface. Already a clean teaching file. |
| `seon.agent.message` | `user`, `agent`, `message!` (+ the `:seon.agent.message/*` and `:seon.user` schemas) | The conversation verbs. Keep `message!` for the fan-out + hop-guard teaching; the two wrappers are the daily calls. Drop `waking-hops`/`user-entity?` privates from view if rendered selectively. |
| `seon.agent` | `wait`, `complete`, `terminate` (+ the `:seon.agent/state` enum schema and its block comment) | The lifecycle verbs + the state-machine doc. Drop the layout verbs (`add-section!`/`remove-section!`/`reset-ctx!`/`update-ctx!`/`set-purpose!`), `create!`/`boot!`, `fresh-wake!`/`set-state!`/`armable-agent-ids` — orchestrator/framework surface, not first-order agent verbs. (Layout verbs are a SECOND-tier teaching once the agent is fluent.) |
| `seon.agent.search` | `grep` (+ the search→read recipe in the ns doc) | The search half of search→read; the wrapper doctrine is a bonus teaching for agents that write their own wrappers. |
| `seon.agent.fs` | `read-file`, `write-file`, `list-dir`, `walk-dir`, `stat`, `grants`, `configure!` | The read half of search→read + file writes. Drop `file-exists?`/`home-dir` convenience + the why-sync block. |
| `seon.ctx` | `render-namespace` ONLY (signature + doc) | The escape hatch that makes curation safe — "anything not shown in full is one call away." Do NOT full-source the rest of seon.ctx (composer internals). |
| `my.agent.<id>` | `:full` (the agent's own home ns) | Already shown; this is the agent's scratch namespace — its own defns/atoms persist here. Keep. |

Total full-source weight is roughly: 4 small `my.*` files + `todo` + the
curated slices of `db`/`schema`/`message`/`agent`/`search`/`fs` + the single
`render-namespace` doc. That is far leaner than today's full-source set and
every shown fn is one an agent actually types.

## What to drop

Drop from FULL-source rendering (keep them in the by-name manifest so they
stay queryable via `render-namespace`):

- All `seon.ctx.*` section fns (`inventory`, `live-tile`, `namespaces`,
  `relevant`, `transcript`, `warnings`, `your-entity`) — these RENDER the
  context; the agent reads their OUTPUT, never calls them.
- `seon.ctx` composer internals (`assemble-context`, `split-context`,
  `system-section`, `neutralize-result-claims`, `retrieval-query`, caps).
- `seon.eval` (entire ns) — the eval mechanics. The agent writes forms; the
  runtime evals. Nothing here is an agent verb. (Its REPL-semantics doc IS
  worth teaching, but as prose in the `<system>` block, not as full source.)
- `seon.ai` / `seon.ai.anthropic` / `seon.ai.openai-compat` — LLM adapters; the
  agent IS the LLM, it never calls these.
- `seon.handler` / `seon.handler.match` / `seon.handlers.*` — the render/tx
  dispatch registry; framework plumbing.
- `seon.warn` + `seon.ctx.warnings` — the warning checks; the agent reads the
  derived `<WARNINGS>` section, never the check fns.
- `seon.analyzer-info`, `seon.debug`, `seon.dev.runtime-id`, `seon.flow`,
  `seon.fn`, `seon.error`, `seon.error.malli`, `seon.repair`,
  `seon.test`/`seon.test.runner` (unless a testing task is in scope),
  `seon.store.wire`, `seon.web.*`, `seon.agent-view`, `seon.agent.session`,
  `seon.agent.turn`, `seon.embed` (until KB-vector-recall is taught),
  `seon.client` — all framework-internal or boot-path; never agent verbs.
- `seon.agent.fsm` — keep as REFERENCE (the loop/cap teaching) but not as a
  full-source "API" file; the agent calls none of it.

## DeepSeek capability battery

Twelve concrete, varied tasks to drive a live DeepSeek agent and learn what the
curated context enables and where it stumbles. Each = a one-line instruction +
the observable success signal. These are the iteration yardstick.

1. Three-questions-at-once (no-deaf check). "Ask me three separate questions
   in one message, then wait for my answers." → ONE `message/user` containing 3
   questions, `:seon.agent.message/ok? true`, then `agent/wait`; agent does NOT
   go deaf after the first reply (the FSM deaf-after-one-message regression).
2. Greet-and-park. "Say hello and tell me you're ready, then park." →
   `message/user` lands `ok? true` (CATCHES the user-seed bug above), then
   `agent/wait "…"` → state `:waiting`.
3. Plan a multi-step task. "Register a my.kb.recipe schema, add one recipe, and
   confirm it stored." → 3 todos minted via `add!` BEFORE work, each
   `complete!`'d; `<open-todos>` empties by the end.
4. KB store with provenance. "Store the fact that seon.db/transact! returns a
   value envelope on failure, with where you read it." → a `my.kb.<domain>` row
   carrying domain attrs + `:my.kb/source-path` + `:my.kb/source-line` +
   `:my.kb/confidence`; `store-inventory` shows the new kind.
5. KB recall. (Fresh agent, same cluster.) "What does seon.db/transact! return
   on failure, and how do you know?" → answer derived by `store-inventory` +
   datalog of the row from task 4, citing the stored provenance — NOT
   re-researched.
6. Schema + entity transact. "Define a my.workout schema (date, type, minutes)
   and log today's run." → `register!` calls THEN a `transact!` returning
   `:seon.db/ok? true`; a follow-up `query` returns the row.
7. DB query. "How many messages have I sent you so far?" → a Datalog `query`
   over `:seon.agent.message/*` returning the right count from the REAL value
   next turn (tests write-form/read-value-next-turn discipline).
8. Codebase search. "Find where seon registers its identity-attr shape." →
   `seon.agent.search/grep` for `seon.db/identity`, then `read-file` on a hit's
   path (the search→read recipe), then a `message/user` summary.
9. File read + report. "Read the first 30 lines of SOUL.md and summarise the
   agent's identity." → `read-file` with `from-line`/`max-lines`, summary to
   human; handles default-deny gracefully if SOUL.md is outside the grant.
10. File write. "Write a one-line note to <granted-dir>/note.txt." →
    `write-file` `ok? true` (or a graceful default-deny envelope + a
    message asking for a grant — both are PASSES; a thrown error is a FAIL).
11. Multi-step autonomy (the big one). "Survey the seon.agent.* namespaces and
    tell me the three verbs I'd use most." → todos + `render-namespace` /
    `grep` across the curated set, ONE synthesized `message/user`, then
    `complete`. Watches for: does it use `render-namespace` to read dropped
    nses, or does it hallucinate?
12. Graceful completion + termination boundary. "Finish up and let me know
    you're done." → `complete "result"` → state `:completed`, result delivered;
    agent does NOT call `terminate` on itself (terminate is orchestrator-only —
    a self-terminate is a teaching miss to catch).

## Open questions

- USER-SEED BUG (blocker): the ground-truth transcript shows
  `(message/user …)` failing because `[:seon.user/id "user"]` was never seeded.
  `seon.agent.message/user-ref` is documented as "seeded at boot by
  seon.client" — is the boot seed missing, racing the first turn, or
  cluster-reset-wiped? This must be fixed before the battery means anything;
  tasks 1-2 will fail spuriously otherwise. (File: `src/seon/agent/message.cljs`
  lines 64-67; seed owner is `seon.client`.)
- `pull-by-name` named in the brief does not exist — confirm the intended read
  verb is `pull`/`entity` and scrub any docs/teaching that reference it.
- How much of `seon.agent` layout-editing (`add-section!`/`set-purpose!`/
  `update-ctx!`) is worth teaching tier-2? The live-canvas/section surface is
  powerful but unproven with DeepSeek — defer to a later battery once the core
  verbs are fluent.
- Does `render-namespace` reliably land for DeepSeek (does the model reach for
  it when it needs a dropped ns), or does it hallucinate the dropped source?
  Task 11 is the probe; if it hallucinates, the curation is too aggressive or
  the escape-hatch teaching needs to be louder in `<system>`.
- KB-vector-recall (`seon.embed/search`) is dropped for now — when retrieval is
  taught, add a task 5-style recall over embeddings vs datalog and compare.
- Comment-refinement scope: the chosen files (`db`, `schema`, `message`,
  `todo`, `agent`, `search`, `fs`, `my.kb*`) already have strong docstrings;
  the refinement pass should focus on trimming framework-internal asides that
  don't teach an agent (e.g. the `seon.db/*conn*` defonce-not-def rationale,
  the `seon.agent.fs` why-sync block) so the rendered source stays signal-dense.
