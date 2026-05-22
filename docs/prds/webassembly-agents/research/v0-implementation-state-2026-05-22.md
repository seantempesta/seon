---
type: research
status: active
tags: [research, agent, cljs]
---

# V0 Implementation State vs MVP Spec — 2026-05-22

Survey of the V0 CLJS pod against the
[[agent-repl-mvp]] spec. Goal: identify what exists in code, what is
spec-only, and the smallest set of additions needed to drive the agent
loop end-to-end against a real LLM (deepseek).

Branch: `webassembly-agents`. Spec last touched recently; prior state
snapshot lives at [[research/v0-state-2026-05-20]] (~2 days old).

## TL;DR

The **agent loop is fully wired and runnable today** —
`seon.client/start-agent!` boots a datahike conn, primes a
home-namespace, installs a kick-listener, and drives
`render → LLM → parse → eval-batch → record` cycles. DeepSeek adapter
plugs in via `start-agent-with-deepseek!`. Messages, evals, and the
agent record are all real schemas with real datahike attrs.

**But the *persistent program* layer is entirely missing.** No
`:seon.fn/*`, no `:seon.schema/*`, no `:seon.test/*`, no `:seon.ns/*`,
no `:seon.ctx/*`. The composer described in the spec (section entities
with `:seon.ctx/fn` symbol slots) does not exist; instead a single
hard-coded `seon.render.default/ctx` concatenates 8 fragment fns. No
`forget!`, no `reset-defaults!`, no `bootstrap.edn`, no resume walk —
all program state lives in defns evaluated through `cljs.js` whose vars
die with the pod (the agent's home-ns is the only thing that survives,
and only because Node defonces hold it).

**The agent DB does NOT enable history** (`:keep-history? false` at
`client.cljs:285`), which breaks the spec's whole "tx-meta IS the
eval-id pointer" trick. Only the `seon.repl/dev-init!` iteration conn
sets `:keep-history? true` (`repl.cljs:179`) — that's the conn the
spec was verified against, not the conn the agent actually runs on.

## 1. Agent entity

**SPEC** ([[agent-repl-mvp]] §"Agent record is the hub"):
`:seon.agent/id` (12-char `:seon.id/id`),
`:seon.agent/state` `[:enum :idle :running]`,
`:seon.agent/turn-count :int`,
`:seon.agent/turns-since-user :int`,
`:seon.agent/interrupted? :boolean {:optional true}`,
`:seon.agent/current-ns :keyword {:optional true}`,
`:seon.agent/ctx [:vector :seon.db/ref]` (D11 — cardinality-many refs
to per-agent `:seon.ctx` entities).

**CODE**: `src/seon/agent.cljs:92-96` registers everything EXCEPT
`:seon.agent/current-ns` and `:seon.agent/ctx`.

- `:seon.agent/id` is plain `:string` (no 12-char constraint, no shared
  `:seon.id/id`); the generator at `agent.cljs:75-82` produces
  **10-char base62** (4-time + 6-rand), not 12 (8-time + 4-rand) as the
  spec specifies.
- `:seon.agent/state`, `turn-count`, `turns-since-user`,
  `interrupted?` are all registered.
- Datahike side at `client.cljs:180-192` declares `:seon.agent/id`
  (string/identity), `state` (keyword), `turn-count` (long),
  `turns-since-user` (long). Note: `interrupted?` is NOT in the
  datahike bootstrap schema — registering it in Malli without a
  matching datahike attr means a transact would fail at write time.
- `:seon.agent/current-ns` is NOT registered anywhere; the renderer
  derives current-ns from agent-id via `home-ns` (`agent.cljs:127`,
  `render/default.cljs:196`) — no per-form upsert as spec requires.
- `:seon.agent/ctx` (the D11 hub multi-ref) does not exist.

**GAP**: Add `current-ns` and `ctx` attrs (Malli + datahike); switch
ID generator to 12-char; share an `:seon.id/id` schema; wire
per-form `current-ns` upsert in `eval-batch!`. Current code only
maintains `!current-ns` as an atom IN the agent's home-ns
(`eval.cljs:460-467`) — not on the DB entity.

## 2. Message entity

**SPEC**: only mentioned in passing ("messages tagged via
`:seon.message/agent`"); the spec lists message accessors but doesn't
re-define the entity.

**CODE**: `agent.cljs:105-109` registers:

- `:seon.message/id` :string
- `:seon.message/role` `[:enum :user :assistant :system]`
- `:seon.message/content` :string
- `:seon.message/agent` `:seon.db/ref`
- `:seon.message/at` :inst

Datahike side at `client.cljs:195-210` mirrors all five attrs.

**GAP**: Implementation is ahead of spec here — fully wired and used
by `chat` (`agent.cljs:378-391`), `replies-after`
(`agent.cljs:393-412`), the kick handler (`agent.cljs:285-309`), and
the `recent-conversation` ctx fragment (`render/default.cljs:349`).
The spec should adopt this shape rather than redefine it.

## 3. Eval entity

**SPEC** ([[agent-repl-mvp]] §"Eval log"): nine attrs including
`:seon.eval/turn :long`, `:seon.eval/at :long` (epoch-ms),
`:seon.eval/duration-ms :long`, `:seon.eval/ns :keyword`,
`:seon.eval/narration`, `:seon.eval/source`, `:seon.eval/ok?`,
`:seon.eval/result-edn {:optional true}`,
`:seon.eval/error {:optional true}`.

Critical mechanic: each eval transacts ITS persistent datoms in the
same tx with `:tx-meta {:seon.eval/id <id>}`, so the eval entity IS the
tx entity. "What did this eval touch?" → `(d/history db)` query.

**CODE**: `agent.cljs:111-119` registers id/agent/at/turn/narration/
source/ok?/result-edn/error (all 9 conceptually present). Datahike side
at `client.cljs:212-280` mirrors.

What's wired in `record-eval!` (`eval.cljs:505-530`):

- writes `:seon.eval/id`, `:seon.eval/agent`, `:seon.eval/at`,
  `:seon.eval/turn`, `:seon.eval/narration`, `:seon.eval/source`,
  `:seon.eval/ok?`
- conditionally writes `:result-edn` (on success) or `:error` (on
  failure)

What's MISSING:

- **`:seon.eval/at` is `:inst` (java.util.Date), not `:long`
  (epoch-ms)**. Datahike side: `:db.type/instant` at
  `client.cljs:212-213` + `client.cljs:260-262`. Spec wants epoch-ms.
- **`:seon.eval/duration-ms` not registered, not captured.** Spec calls
  for `(- (js/Date.now) start)` per form. `eval-batch!`
  (`eval.cljs:554-595`) snaps `at` once but never measures duration.
- **`:seon.eval/ns` not registered, not captured.** Spec calls for the
  ENDING ns to be recorded per eval. The code DOES compute the ending
  ns (`raw-result :ns` at `eval.cljs:573-574`) and pushes it to the
  agent's atom but never persists it.
- **No tx-meta wiring.** `record-eval!` calls
  `db/transact! {:seon.db/tx-data [...]}` — no
  `:seon.db/opts {:tx-meta {:seon.eval/id ...}}`. Looking at
  `db/transact!` (`db.cljs:424-462`), it passes `opts` through to
  `d/transact!` as `opts`, so the plumbing exists; nothing calls it.
- **Eval entity and persistent-entity datoms are NOT in the same tx.**
  Each successful form's defn/schema/test would run via `cljs.js/eval-
  str` (which doesn't transact at all — those defns just become
  globalThis vars), then `record-eval!` transacts the eval entry
  separately. There are no persistent entities to share a tx with
  because no persistent-entity layer exists (see §5).

**GAP**: Add `duration-ms` + `ns` attrs (Malli + datahike), switch
`at` from `:inst`/`:db.type/instant` to `:long`/`:db.type/long`, wire
tx-meta — but the bigger gap is §5: there are no persistent entities
for the tx-meta trick to point at.

## 4. Section / ctx entity & the composer

**SPEC** ([[agent-repl-mvp]] §"Rendering — sections compose strings",
§"Initial default context"): section entities carry `:seon.ctx/name`,
`:seon.ctx/priority`, `:seon.ctx/fn` (qualified symbol). The composer
queries `[?e :seon.ctx/name _]`, sorts by priority, resolves each
`:seon.ctx/fn` via `resolve-symbol`, calls it, joins strings. D11
makes this per-agent via `:seon.agent/ctx` multi-ref. Six default
section fns: `system-section`, `related-ns-section`,
`current-ns-section`, `warnings-section`, `recent-evals-section`,
`prompt-section`.

**CODE**: NONE of this exists.

- No `:seon.ctx/*` schema registrations anywhere in `src/seon/*.cljs`
  or `src/seon/*.cljc`. (`grep "seon\.ctx/" src/seon/*.cljs` returns
  zero hits.)
- No composer that walks section entities. Instead,
  `seon.render.default/ctx` (`render/default.cljs:426-444`) hard-codes
  a concat of 8 fragment fns: `repl-state-header`, `how-you-respond`,
  `what-you-can-do`, `conventions`, `recent-conversation`,
  `recent-evals-block`, `recent-errors-block`, `schema-reference`.
- None of the 6 spec section names (`system-section`,
  `related-ns-section`, `current-ns-section`, `warnings-section`,
  `recent-evals-section`, `prompt-section`) exist. The fragment fns
  serve a different purpose and produce different output (markdown
  headings, not XML wrappers).
- Dispatch through symbol slots IS wired: `render/ai-dispatch`
  (`render.cljs:159-166`) resolves the agent's `:seon.render/ai`
  symbol and falls through to `pretty-ai`. But the slot points at ONE
  fn (`'seon.render.default/ctx` default at `agent.cljs:223`), not at
  a vector of section fns.
- `:seon.render/ai` schema (`render.cljs:40`) is `[:fn symbol?]` —
  function-only, no literal slot path. Datahike side
  (`client.cljs:231-236`) is `:db.type/symbol`.

**GAP**: Build the entire section-composer machinery from scratch:
register `:seon.ctx/{name,priority,fn}` (Malli + datahike), write the
composer fn, write the 6 default section fns. D11's `:seon.agent/ctx`
multi-ref is also missing. Replace
`seon.render.default/ctx`'s hard-coded fragment list with the composer.

## 5. Persistent entities (`:seon.fn`, `:seon.schema`, `:seon.test`, `:seon.ns`)

**SPEC** ([[agent-repl-mvp]] §"Persistent entities"): the database IS
the program. Every `(defn ...)`, `(schema/register! ...)`,
`(deftest ...)`, `(ns ...)` form the agent emits transacts an entity
with its source text. Replay rebuilds runtime vars from these
entities. Identity attrs: `:seon.fn/sym` (string),
`:seon.schema/key` (keyword), `:seon.test/sym` (string),
`:seon.ns/name` (keyword) — each with `{:seon.db/identity true}`.

The references graph (D8): `:seon.fn/input-spec`,
`:seon.fn/output-spec`, `:seon.fn/refs`, `:seon.test/target`,
`:seon.schema/refs`.

The auto-registration is wired by `eval-batch!`: parse the form, if
it's `(defn ...)`/`(schema/register! ...)`/`(deftest ...)`/`(ns ...)`,
extract source + transact the persistent entity in the same tx as the
eval entry.

**CODE**: NONE of this exists.

- No `:seon.fn/sym`, `:seon.fn/source`, `:seon.fn/ns` schemas
  anywhere in CLJS source. The only `:seon.fn/*` references are in
  `src/seon/render/code.clj` (JVM-side graph indexer, completely
  separate). That code uses `:seon.fn/qualified-name`, NOT the spec's
  `:seon.fn/sym`, confirming the two pipelines diverge.
- No `:seon.schema/key` / `:seon.schema/source` schemas.
- No `:seon.test/sym` / `:seon.test/target` schemas.
- No `:seon.ns/name` / `:seon.ns/source` schemas.
- `eval-batch!` (`eval.cljs:532-595`) does NOT inspect the parsed form
  to detect define-class forms. Every form is run through
  `cljs.js/eval-str` and its return value stashed via
  `stash-result-raw!` (`eval.cljs:383-393`); nothing is persisted
  about WHAT the form defined.
- `seon.code/check` (`src/seon/code.cljc`) exists as a structural
  gate but is not invoked from the CLJS eval pipeline as of this
  checkpoint.

**GAP**: This is the single largest missing chunk. To get even minimal
persistence, you need register the entity attrs, write a
form-classifier (rewrite-clj walk for `defn`/`schema/register!`/
`deftest`/`ns`), and tee the source + parsed-form data into a
post-eval transact paired with the eval entry. Without this, agent
work doesn't survive a pod restart even in principle.

## 6. Bootstrap + resume

**SPEC** ([[agent-repl-mvp]] §"Boot sequence"): `init-bootstrap!` →
`bootstrap-phase!` (only if DB empty) → `resume-phase!` → render. The
bootstrap reads `resources/seon/bootstrap.edn` (a build-time emitted
ordered vector of substrate entities) and transacts it. Resume walks
all `:seon.fn`/`:seon.schema`/`:seon.test`/`:seon.ns` entities sorted
by tx-id and re-evals each's `:source`. The first resume eval carries
`:seon.eval/resume-marker? true`.

**CODE**:

- `seon.eval/init-bootstrap!` (`eval.cljs:163-188`) exists and is
  load-bearing — it sets up the CLJS analyzer-cache load described in
  the spec's "analyzer-cache load" section. This IS the
  `out/bootstrap/ana/*.transit.json` walk. Works.
- `seon.eval/setup-agent-ns!` (`eval.cljs:395-430`) primes the
  agent's home-ns with `!session-id`, `!current-ns`, `session-id`,
  and `result` helpers. Idempotent.
- NO bootstrap-edn emission. No `resources/seon/bootstrap.edn` exists
  in the project (checked).
- NO `bootstrap-phase!` / `resume-phase!` separation. `start-agent!`
  always opens a fresh in-memory conn (`open-agent-conn!`,
  `client.cljs:282-289`) and transacts `agent-bootstrap-schema`
  (datahike schema only — no entity data); nothing replays prior
  state because there's nothing to replay (no persistent entities,
  see §5).
- NO `:seon.eval/resume-marker?` attr.

**GAP**: Resume is meaningless until persistent entities exist (§5).
Bootstrap.edn emission is D10, marked as defer. The analyzer-cache
load is the one piece that IS in place.

## 7. History on the agent DB

**SPEC** ([[agent-repl-mvp]] §"What's NOT in the model"): "the default
V0.5 conn uses `:keep-history? false`; turning history on for the
agent DB is a prerequisite for [the tx-meta-IS-eval-id model]".

**CODE**:

- `open-agent-conn!` at `client.cljs:282-289` uses `:keep-history?
  false`. The CONN THE AGENT ACTUALLY RUNS ON has no history.
- `seon.repl/ensure-conn!` at `repl.cljs:170-183` uses
  `:keep-history? true` — but this is the iteration-surface conn used
  by `mcp__seon_cljs__eval` probes via `dev-init!`, distinct from the
  agent conn at `client.cljs:169 !agent-conn`.
- The smoke-test conn (`client.cljs:124-127`) and
  `wasm_smoke.cljs:44` also use `:keep-history? false`.

**GAP**: One-line fix at `client.cljs:285` — flip to `true`. But this
unlocks tx-meta retrieval that nothing currently uses (§3 has no
tx-meta writes, §5 has no entities to tx-meta about).

## 8. The harness loop

**SPEC** ([[agent-repl-mvp]] §"Goal"): one turn = rendered ctx → LLM
→ parse → eval-batch.

**CODE**: This works end-to-end TODAY.

`seon.agent/run-turn-once!` at `agent.cljs:203-276` does exactly the
spec sequence:

1. `bump-turn!` increments counters, flips state to `:running`
   (`agent.cljs:146-159`).
2. Resolves the agent's `:seon.render/ai` symbol (default
   `'seon.render.default/ctx`) and calls
   `render/ai-dispatch` to build the ctx string
   (`agent.cljs:222-226`).
3. Calls `llm-fn` (await), gets back `{:text "..."}`
   (`agent.cljs:229-231`).
4. `seon.repl/parse-forms` (`repl.cljs:112-148`) splits the LLM reply
   into `{:narration :source :form}` triples using cljs.tools.reader
   (NOT rewrite-clj — spec calls for rewrite-clj but tools.reader is
   what's running).
5. `seon.eval/eval-batch!` (`eval.cljs:532-595`) iterates each parsed
   pair: reads current-ns, evals via `cljs.js/eval-str`, auto-awaits
   Promise returns (`maybe-await-value`, `eval.cljs:469-503`),
   stashes raw value on globalThis, transacts an eval entity.
6. `end-turn!` flips state to `:idle` (`agent.cljs:161-164`).
7. Multi-turn loop: if no `:assistant` message landed and
   `turns-since-user < 20`, schedules another `run-turn-once!`
   (`agent.cljs:243-268`).

The kick handler at `agent.cljs:289-309` fires on every `:user`
message tx (state-guarded; doesn't kick during `:running`).

The DeepSeek adapter (`seon.ai.deepseek/agent-adapter`,
`deepseek.cljs:202-209`) returns `(fn [ctx-string]) → Promise<{:text}>`
— matches the `llm-fn` signature exactly. `start-agent-with-deepseek!`
(`client.cljs:381-385`) wires it.

`-main` (`client.cljs:391-421`) auto-boots: if `DEEPSEEK_API_KEY` is in
`process.env`, uses the real adapter; else `stub-llm` (an inline canned
response at `client.cljs:291-316` that transacts an assistant message
and flips state).

**GAP**: The loop runs and routes messages. What it DOESN'T do —
because §1-§7 are missing — is produce a context that mentions the
spec's persistent-entity world (no current-ns block, no
recent-evals XML-wrapped section, no warnings, no
section-customization point). The current ctx is good but is the
**substrate-teaching** ctx, not the **REPL-scrollback** ctx the spec
describes. An agent running today sees `repl-state-header`,
`how-you-respond`, `what-you-can-do`, etc — instructions for using
seon.db/seon.fs primitives — but does not see a window into a
persistent program it's building up. That's the spec gap, not a loop
gap.

## Killer gaps — smallest set to make the agent loop run end-to-end against deepseek

The loop already runs against deepseek today.
`start-agent-with-deepseek!` works. What's missing is making the
result MATCH THE SPEC, not making the loop functional. Ordered by
"smallest change that unlocks the most spec":

1. **Flip `open-agent-conn!` to `:keep-history? true`**
   (`client.cljs:285`). One line; prerequisite for tx-meta-as-history
   trick. Cost: storage overhead for the agent's DB.
2. **Wire `tx-meta {:seon.eval/id <id>}` in `record-eval!`**
   (`eval.cljs:505-530`). Use `:seon.db/opts` to pass it through;
   `db/transact!` already plumbs `opts` to `d/transact!`
   (`db.cljs:455-458`). Minor: ~5 lines.
3. **Capture `:seon.eval/duration-ms` and `:seon.eval/ns`**
   (`eval.cljs:554-595`). Snap `(js/Date.now)` before eval, diff
   after auto-await; carry `(:ns raw-result)` into `record-eval!`.
   Register both attrs in Malli AND datahike (`agent.cljs:111-119`,
   `client.cljs:212-280`). Convert `:seon.eval/at` from
   `:inst`/`:db.type/instant` to `:long`/`:db.type/long` (or accept
   the spec drift on this).
4. **Register + persist `:seon.agent/current-ns`**: Malli attr
   (`agent.cljs:92-96`), datahike attr (`client.cljs:180-192`),
   upsert in `eval-batch!` whenever the ending-ns changes.
5. **Define the persistent-entity attrs** (§5): minimum is
   `:seon.fn/sym`, `:seon.fn/source`, `:seon.fn/ns`. Skip
   `:seon.schema/*` / `:seon.test/*` / `:seon.ns/*` for the very
   first cut. Register in Malli + datahike.
6. **Detect-and-tee defns in `eval-batch!`**: parse the `:form` (rewrite-clj
   parse already happens for the LLM reply, but `parse-forms` uses
   tools.reader and produces evaluated forms — switch to rewrite-clj OR
   re-parse the `:source` string). When the form's head is `defn`,
   transact a `:seon.fn` entity carrying `:source` in the SAME tx as
   the eval entry (via tx-meta-tagged combined tx).
7. **Build the `:seon.ctx/*` composer + the 6 default section fns**
   (§4). This is the biggest single piece, but the rendered ctx today
   already concatenates 8 fragments — refactor those into named
   section entities. Default section fns (`system-section` etc.) can
   wrap existing fragments (`repl-state-header` ≈ `system-section`,
   `recent-evals-block` ≈ `recent-evals-section`, etc.).
8. **D11 — `:seon.agent/ctx` multi-ref**: per-agent ctx-entity refs on
   the agent record. Bootstrap creates the default 6 in
   `create!` (`agent.cljs:328-335`).

Items 1-4 are mechanical and small (probably ~2 hours of
careful edits). Items 5-8 are the substantial design work — that's
where "v0 implementation" actually becomes "MVP implementation."

Resume (§6), forget!, reset-defaults!, bootstrap.edn (§D10),
warnings-section + warning predicates (§D4 targeted test auto-run,
§D2 spec-violation, §D3 def-not-persisted), and per-kind
redefinability gates are NOT on this critical path — they layer on
top of §5+§7.

## Files inventory

CLJS pod (agent runtime, what's loaded by `node out/client/main.js`):

| File | Role |
|---|---|
| `/Users/sean/src/seon/src/seon/client.cljs` | Node entry, datahike-smoke-test, `start-agent!`, `start-agent-with-deepseek!`, agent conn lifecycle, hand-written datahike bootstrap schema (`agent-bootstrap-schema`). |
| `/Users/sean/src/seon/src/seon/agent.cljs` | Agent loop: `new-id!`, schemas for agent/message/eval, `run-turn-once!`, `install-kick!`, `create!`, `chat`, `boot!`. |
| `/Users/sean/src/seon/src/seon/eval.cljs` | Eval pipeline: `init-bootstrap!`, `eval` (safe-by-default), `eval-batch!`, `setup-agent-ns!`, `record-eval!`, `stash-result-raw!`, timeout/budget primitives. |
| `/Users/sean/src/seon/src/seon/repl.cljs` | `parse-forms` (cljs.tools.reader-based, NOT rewrite-clj despite spec), `dev-init!`/`!compile-state`/`!conn` for the iteration surface. |
| `/Users/sean/src/seon/src/seon/db.cljs` | Sole DB API: `transact!` (with Malli validation gate), `query`, `pull`, `entity`, `listen!`, `unlisten!`; `*conn*` dynvar; full docstring example of the user-message-kick reaction. |
| `/Users/sean/src/seon/src/seon/render.cljs` | `ai-dispatch`, `html-dispatch`, `resolve-symbol` (globalThis walker + late-bound compile-state); `:seon.render/ai`/`html` slot schemas. |
| `/Users/sean/src/seon/src/seon/render/default.cljs` | Default `ctx` (the agent's prompt — concat of 8 fragments), `view` (HTML tile), plus `pretty-ai`/`pretty-html` fallbacks and DB-query helpers (`recent-messages`, `recent-evals`, `recent-errors`, `all-running-agents`). |
| `/Users/sean/src/seon/src/seon/ai/deepseek.cljs` | DeepSeek HTTP client (`complete`, AbortController timeout, `agent-adapter`). |
| `/Users/sean/src/seon/src/seon/schema.cljc` | Malli registry: `register!`, `registered?`, `schema-definition`, baseline types (`:inst`, `:seon.db/ref`, etc.). |
| `/Users/sean/src/seon/src/seon/code.cljc` | Structural defn gate (referenced by spec; NOT invoked from agent eval pipeline today). |
| `/Users/sean/src/seon/src/seon/error.cljs` | Error → map normalization (`->map`, `->message`). |
| `/Users/sean/src/seon/src/seon/fs.cljs` | Default-deny filesystem capability surface. |
| `/Users/sean/src/seon/src/seon/log.cljs` | Pod-side logging. |
| `/Users/sean/src/seon/src/seon/platform.cljs` | Host detection (`:node` / `:wasi`). |
| `/Users/sean/src/seon/src/seon/web/serve.cljs` | HTTP+SSE server with project-local port file. |
| `/Users/sean/src/seon/src/seon/web/broadcast.cljs` | tx-listener → SSE morph for browser dev loop. |

JVM-side (NOT loaded by the V0 CLJS pod — separate `.clj` codebase):

| File | Role |
|---|---|
| `/Users/sean/src/seon/src/seon/render/code.clj` | JVM-side graph indexer renderers; uses `:seon.fn/qualified-name` not `:seon.fn/sym` — different pipeline, do not conflate. |
| `/Users/sean/src/seon/src/seon/agent/env.clj` | Agent toolkit for JVM nREPL agents — graph search, ctx-save/load. |
| `/Users/sean/src/seon/src/seon/render/default_page.clj` | JVM default-page renderer; comments reference deleted `:seon.ctx/*` schemas — those `:seon.ctx/*` keys are NOT the spec's `:seon.ctx/*` section entities. |

WASM/pod-host (stubbed for Phase 3; not relevant to the loop today):

| Path | Role |
|---|---|
| `/Users/sean/src/seon/pod-host/wasm-tauri/` | WIT-typed Rust+CLJS pod skeleton; `src-wit/seon-pod.wit` defines the eventual `eval-form` export. Out of scope for MVP loop. |
| `/Users/sean/src/seon/src/seon/wasm_smoke.cljs`, `wasm_eval_smoke.cljs` | Smoke harnesses for the WASM build. Stubs. |

Spec / status:

| Path | Role |
|---|---|
| `/Users/sean/src/seon/docs/prds/webassembly-agents/agent-repl-mvp.md` | The spec this survey is against. |
| `/Users/sean/src/seon/docs/prds/webassembly-agents/STATUS.md` | Resume notes; lists next priorities D11/D5/D4/D2/D3/D7 (in order). |
| `/Users/sean/src/seon/docs/prds/webassembly-agents/research/v0-state-2026-05-20.md` | Prior pod-state snapshot. |
