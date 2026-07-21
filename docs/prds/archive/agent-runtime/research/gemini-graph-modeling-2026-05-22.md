---
type: research
status: active
tags: [research, agent, database]
---

# Gemini graph-modeling critique — 2026-05-22

## TL;DR (for the next agent)

1. **Persist the rendered prompt per turn.** The highest-leverage gap. Without `:seon.turn/prompt` as a stored string, "play back turn N" is impossible because the renderer is non-deterministic across time — re-running it later sees newer code and newer data.
2. **Introduce a first-class `:seon.turn` entity** (id, index, prompt-snapshot, messages, evals, status). It's the causality container that ties "what the agent saw" to "what the agent thought" to "what the agent did" in one pull.
3. **Flip the dominant ref direction from backref to forward-ref components.** Agent → Sessions → Turns → Messages/Evals via cardinality-many `:db/isComponent` refs. This lets the agent walk its own state with a nested pull pattern instead of memorizing reverse-ref syntax or wrapper fns.
4. **Eliminate the `seon.agent/my-*` helper layer.** Replace with one root-pull helper plus a short library of example pull-patterns the agent reads in its system instructions and copies. The graph itself is the API.
5. **Auto-tag every transaction with `seon.db/*tx-context*`** carrying `:seon.db/{agent,session,turn,eval}-id` + `:seon.db/origin`. This makes every datom in history forensically traceable without manual plumbing on each `transact!` site.
6. **Model namespaces as entities** (`:seon.ns`) with function entities ref'ing them. Stops the spec's SQL-flavored use of `"ns/name"` strings as composite identifiers.

## The full prompt sent to Gemini

The complete prompt (~140 KB, 2978 lines) including the verbatim spec, STATUS, v0 survey, CLAUDE.md Data Rules, and the structured deliverable instructions is preserved below.

````text
You are a senior Datomic / Datalog data modeler reviewing a spec for an AI agent harness.
The harness embeds a CLJS REPL pod inside a WASM-Tauri container, with a persistent Datahike (LMDB) DB.
The agent reads and writes the DB; every turn, every tool call, every eval is captured.

Your job: critique the data model and propose concrete fixes.
The spec below is ~2200 lines, please read it carefully.

============================================================
USER GOALS (verbatim — these are the most important constraints):
============================================================

> Focus on v1 where we can get the agent actually running in a harness (deepseek 4 pro) and
> building / accessing real data. Build the harness around watching its actions — the DB must
> capture EVERYTHING so we can playback sessions and see what the agent was doing and what it
> was seeing. Initial set of functions must be so simple and intuitive that the agent
> immediately learns the right patterns for creating new schemas and storing/querying/retrieving
> data and surfacing it to its context. The current schema isn't well thought out for an agent
> to query and store info about its own environment. The agent shouldn't have to write
> helper-fn-per-thing to discover its own state — pulls should just work because the graph is
> fully connected. Patterns should make it easy to find and discover information, not have
> entities be self-contained with refs so you have to do reverse references just to find info.
> Don't reinvent capabilities datahike already provides (tx-meta, history, lookup-refs, etc.)
> — document the right PATTERNS instead.

============================================================
PROJECT CONVENTIONS (from CLAUDE.md — these are HARD constraints):
============================================================

## Data Rules

All data flowing through Seon must be safe at every boundary: Malli validation, core.async channels, Nippy serialization, Datalevin transact/pull.

**Maps with namespaced keywords. Every key. No exceptions.** This is the load-bearing rule the rest of the system depends on:

- **Every public function** takes one map and returns one map. Every key in both maps is fully namespaced (`:seon.runtime/status`, never `:status`). Both the request and response map are themselves named Malli schemas (`::foo-request`, `::foo-response`) registered via `seon.schema/register!`. The `:malli/schema` metadata on the fn points at them.
- **Every datom persisted to the DB** uses a fully-namespaced attribute keyword whose Malli schema is registered. `seon.db/transact!` enforces this at the boundary — unregistered or unspec'd attrs throw before the tx reaches the DB.
- **Every map handed to a callback** (tx-listener handlers, trigger handlers, flow step-fns, async channel envelopes) — fully namespaced. The reason: a single Datalog query should be able to join function specs to the data those functions operate on. `:tx-data` carries no information about which fn owns it; `:seon.db/tx-data` does.
- **Specificity, not single keywords.** Bare keywords (`:status`, `:ok`, `:tx-data`, `:e`, `:a`, `:v`) are banned in any seon-authored map. If a key feels too generic to namespace, namespace it anyway — that's a signal the schema isn't precise enough yet.

**Keyword namespaces = real code namespaces.** Use `::subject` freely — it correctly expands to `:seon.email.message/subject` when you're in `seon.email.message`. This is the intended pattern: **schemas live in the namespace that owns the data, alongside the fns that process it.** Colocation isn't strict (fns will mix data across namespaces — that's fine), but the schema for a piece of data lives with the namespace whose name it carries. Never invent keyword namespace prefixes that don't correspond to actual code namespaces.

**Concrete types only.** Every persisted field has a specific type (`:string`, `:int`, `:keyword`, `:inst`, etc.).

**Optional = absent.** Use `{:optional true}` for fields that may not be present. If the key is present, it must have a valid value. Never store nil.

**Retraction is explicit.** To clear a field, use `[:db/retract eid :attr]`. Omitting a key from a transact map means "leave unchanged."

### Schema Registration

`schema/register!` is the **single source of truth** for all attribute schemas. Register the type, and the system auto-derives everything needed for database storage. You never write Datalevin schema directly.

```clojure
;; Inside src/seon/foo.clj — use :: for namespace-local keywords
(schema/register! ::name :string)
(schema/register! ::id [:string {:seon.db/identity true}])
(schema/register! ::tags [:vector :keyword])
(schema/register! ::parent :seon.db/ref)

(db/transact! :seon [{::id "abc" ::name "hello"}])

```

See `/datalevin` skill for bridge details, persistence properties, refs, and banned types.

Additional non-negotiable constraints (from MEMORY.md):
- No `:any` in Malli schemas (acceptable only for genuinely opaque Java objects with :default/fn)
- No `[:maybe X]` — use `{:optional true}` instead. Absent key = no value. Never store nil.
- Concrete types only: `:string :int :keyword :inst :boolean` etc.
- Refs use `:seon.db/ref` type (lives in seon.schema)
- All identity attrs use `{:seon.db/identity true}` in register! calls
- Every public fn: map-in/map-out, fully-namespaced keys
- Keyword namespace must equal a real code namespace (`:seon.agent.message/role` = ns seon.agent.message)

============================================================
STATUS.md — where the project is right now:
============================================================

---
type: reference
status: active
tags: [reference, prd]
---

# agent-runtime — working state

Resume notes for the two parallel tracks. Read this first when picking
the project back up.

## Tracks

- **MVP track** (this session's owner): the agent eval surface — design
  in [[agent-repl-mvp-pre-2026-05-22]]. Currently in REPL-verification phase against
  the V0 CLJS pod (Node, not WASM yet).
- **Platform track**: the WASM-Tauri containment — design in
  [[platform]]. Owned by the platform agent.

## Where we are

### Spec status

[[agent-repl-mvp-pre-2026-05-22]] has a "Verified live in the V0 pod" section listing
what's been REPL-confirmed end-to-end. As of this checkpoint:

- ✅ rewrite-clj parses comments + forms as ordered nodes
- ✅ Bare-symbol unbound-var rejects loudly (via warning-handler set!)
- ✅ Unqualified core vars resolve (analyzer-cache load fixed)
- ✅ tx-meta = eval-id; eval entity AND tx entity coincide
- ✅ Topological replay = sort-by tx-id (no analyzer walk needed)

Dashboard has 10 open Ds (see [[agent-repl-mvp#decisions-pending]]).
Known Issues at the bottom of the spec list 6 implementation bugs that
need triage (KI-1 through KI-6).

### Next priorities for the MVP track

In rough order:

1. **D11 — per-agent ctx set on the agent record.** The agent record
   becomes the hub: `:seon.agent/ctx` is a cardinality-many vector
   of refs to that agent's `:seon.ctx` entities. Defaults point at
   `seon.agent/*` substrate fns; customization writes a fn in
   `seon.agent.<id>` and re-points refs. V1 has NO dynamic dispatch
   — symbols resolve at call time, period. V2 layers per-entity
   specificity dispatch on top.
2. **D5 — explicit remove-spec / remove-fn / remove-test** verbs.
   Small surface; high agent-utility; gates the "agent can curate
   without accumulating cruft" story.
3. **D4 — targeted test auto-run** wiring. Trigger on `:seon.fn`
   touches; query tests via `:seon.test/target`; stash full output
   via eval-id; surface failures as warnings. Verifies the whole
   reference-graph mechanic.
4. **D2 — per-kind redefinability rules**. Specs must be accretive
   when data exists; fns redefine freely; tests redefine freely.
   Implementation-only; spec already settled.
5. **D3 — `(def …)` detection via rewrite-clj AST**. No regex.
   Small, well-bounded.
6. **D7 — `<name>-example` test convention** + the "no-test-coverage"
   warning predicate.

Defer: D1 (older-DB upgrade), D8 (reference-graph attrs — confirm
shape once we actually populate refs), D9 (forgiving parse recovery —
edge case), D10 (bootstrap.edn emission — separate work item).

### Queued simplifications (not yet decisions)

Round-2 cuts I surfaced earlier in the session but haven't applied.
Each follows the same "use the primitive" pattern that paid off for
`:touches` → tx-meta:

- **Drop `:seon.test/last-passed-at` / `:last-failed-at` /
  `:last-failure`.** A test run IS an eval; tag the run's tx with
  `:seon.eval/test [:seon.test/sym "..."]` in tx-meta. "Latest pass/
  fail" becomes a history query. Three stored attrs collapse to zero.
- **Drop `:seon.fn/refs` extraction.** cljs.analyzer's compile-state
  ALREADY has the AST per `defn` with var references. We can query
  `(get-in @compile-state [:cljs.analyzer/namespaces ns :defs fn :body])`
  for free; no separate walk + storage needed.
- **Audit the Reversibility classifier table.** It existed to power a
  generic `undo`; we replaced that with explicit remove-* verbs. The
  classifier may now be dead code in the spec. Confirm or remove.

These should be promoted to D-decisions if they survive a closer look.

### Known Issues (need triage)

See [[agent-repl-mvp#known-issues]] for the full list. Quick summary:

- KI-1: `seon.db/transact!` invocation shape (wrong shape crashes Node).
- KI-2: `defonce !compile-state` holds pre-fix state across hot-reloads.
- KI-3: Eval error envelope is 4-levels-deep; promote useful keys.
- KI-4: Shadow watcher gets confused after ~3 Node restart cycles.
- KI-5: `start-agent!` and `dev-init!` race for `!compile-state`.
- KI-6: `ws` npm dep was missing for fresh checkout (now in package.json).

KI-1 may already be fixed by another agent's parallel work — check
when integrating.

## Cross-track touchpoints

The MVP and Platform tracks share infrastructure. Coordination points:

- **Eval surface contract.** [[agent-repl-mvp-pre-2026-05-22]]'s spec describes what
  `eval` returns; [[platform]] §"Eval surface" wires it into the WIT
  `eval-form` export. Changes to error envelope shape (KI-3) affect
  both.
- **tx-meta as eval-id pointer.** Verified in the V0 Node pod
  ([[agent-repl-mvp-pre-2026-05-22]]). Platform agent needs to confirm it still
  works under wasmtime + the WIT shim.
- **Analyzer-cache load.** V0 pod loads from `out/bootstrap/ana/`.
  Platform's WASM build needs the same caches packaged into the
  Component bundle (see [[research/m2-findings-2026-05-21]] for the
  bundle structure).

## Iteration surface

- Bring up the V0 pod: `clj -M:cljs watch client` (terminal 1) +
  `node out/client/main.js` (terminal 2). See
  [[../../seon/pod/REPL-WORKFLOW]].
- MCP tools: `mcp__seon_cljs__eval` for host-side eval (the
  substrate's `:client` runtime). `(seon.repl/dev-init!)` once per
  pod boot brings up `@!compile-state` and `@!conn`.
- WASM iteration: reserve for confidence runs. See
  [[research/m2-findings-2026-05-21]] §"Iteration cadence".

## Layout

```text
docs/prds/agent-runtime/
├── STATUS.md           ← you are here
├── agent-repl-mvp.md   ← MVP track design
├── platform.md         ← Platform track design
└── research/
    ├── m2-findings-2026-05-21.md   (WASM landmines, owned by platform)
    ├── v0-state-2026-05-20.md      (V0 Node pod state snapshot)
    └── wasm-spike-2026-05-20.md    (earlier spike report)

```

The operational doc [[../../seon/pod/REPL-WORKFLOW]] stays under
`docs/seon/pod/` because it's substrate-wide (used by both tracks
and by anything else iterating against the V0 pod).

============================================================
V0 IMPLEMENTATION SURVEY (current state of the CLJS pod):
============================================================

---
type: research
status: active
tags: [research, agent, cljs]
---

# V0 Implementation State vs MVP Spec — 2026-05-22

Survey of the V0 CLJS pod against the
[[agent-repl-mvp-pre-2026-05-22]] spec. Goal: identify what exists in code, what is
spec-only, and the smallest set of additions needed to drive the agent
loop end-to-end against a real LLM (deepseek).

Branch: `feature/agent-runtime`. Spec last touched recently; prior state
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Agent record is the hub"):
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Eval log"): nine attrs including
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Rendering — sections compose strings",
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Persistent entities"): the database IS
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Boot sequence"): `init-bootstrap!` →
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"What's NOT in the model"): "the default
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

**SPEC** ([[agent-repl-mvp-pre-2026-05-22]] §"Goal"): one turn = rendered ctx → LLM
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
| `/Users/sean/src/seon/docs/prds/agent-runtime/agent-repl-mvp.md` | The spec this survey is against. |
| `/Users/sean/src/seon/docs/prds/agent-runtime/STATUS.md` | Resume notes; lists next priorities D11/D5/D4/D2/D3/D7 (in order). |
| `/Users/sean/src/seon/docs/prds/agent-runtime/research/v0-state-2026-05-20.md` | Prior pod-state snapshot. |

============================================================
THE SPEC TO CRITIQUE — agent-repl-mvp.md (full text, ~2200 lines):
============================================================

---
type: concept
status: draft
tags: [concept, agent, cljs]
---

# Agent REPL MVP — Spec

The shape of the LLM-facing REPL: the data model, the eval pipeline, the
rendering layer, and the defaults that make the loop work out of the
box. Reference for `seon.repl`, `seon.render`, `seon.eval` namespace
work.

## What we're strict about (everything else is flexible)

We're building this to work **with** the agent. Understand intent;
ship good defaults; let them experiment. We're only strict about
the few things that protect the substrate from incoherence.

**Hard rules (eval-batch enforces; failures land as `:ok? false`):**

1. **Forms must be parseable.** rewrite-clj parses the input; on
   failure we recover (skip to the next balanced top-level form;
   continue). Genuinely malformed code becomes an `:ok? false` eval
   entry with the parse error. We do not silently drop or guess.
2. **Functions must have `:malli/schema`.** The existing
   `seon.code/check` gate enforces this (`src/seon/code.cljc`).
3. **Spec changes against persisted data must be accretive.** If
   `(schema/register! ::foo new)` would invalidate existing
   `[?e ::foo _]` datoms, reject with a warning naming the affected
   entities. Accretive changes (add optional key, loosen constraint,
   widen union) go through. `seon.repl/remove-spec` is the explicit
   escape hatch.
4. **Datahike attribute schemas are mostly append-only.** Under
   `:schema-flexibility :write`, `:db/valueType` is immutable, and
   `:db/unique` cannot be removed once set. `:db/cardinality
   :one→:many` is permitted only when no `:db/unique` is set;
   `:db/doc`, `:db/noHistory`, and `:db/isComponent` are always
   updatable. In practice the parts we use (valueType + identity)
   are immutable; rename to change. New attrs welcome.

**Soft rules (warnings only; never reject):**

- **Functions without tests don't fully count.** A `:seon.fn/*`
  entity transacts normally. The warnings tile derives the no-test
  warning on the fly: query every `:seon.fn` entity, left-join
  against `:seon.test/target`, emit a warning for any fn with no
  matching test. Pure derivation — nothing on the fn entity says
  "I have no tests"; the absence is computed. Convention: every
  public fn ships with a `<name>-example` test exercising the
  documented happy path; the warning vanishes the moment one
  exists. See [[#^d7]].
- **`(def …)` outside `defn`/`schema/register!`/`deftest` is
  scratch.** Warning surfaces; the value lives in the process but
  won't survive restart ([[#^d3]]).
- **Slow forms get flagged.** Default 500ms; agent can retune.

**Flexible everywhere else.** Functions redefine freely. Tests
redefine freely. Refactors break things in flight; the warnings
tile is the always-current picture of what's broken; the agent
decides when to fix.

## Reference graph (specs / fns / tests link together)

Every `:seon.fn` / `:seon.schema` / `:seon.test` entity is reachable
from the others via explicit refs. The agent and the warning
predicates can ask any direction of the question:

- `:seon.fn/input-spec`, `:seon.fn/output-spec`: `:seon.db/ref` →
  `:seon.schema`.
- `:seon.fn/refs`: cardinality-many `:seon.db/ref` → other
  `:seon.fn` entities this fn calls. Extracted by the analyzer
  walk at define time.
- `:seon.test/target`: `:seon.db/ref` → the `:seon.fn` the test
  exercises.

The reverse index is what makes targeted test auto-run cheap
([[#^d4]]) and what makes "who depends on X?" answerable in one
query.

## ID conventions

**All ids use the same encoding.** Eval-id, message-id, log-id,
agent-id — every generated id has identical shape. Defined once as
a Malli schema and referenced from each identity attr:

```clojure
;; in seon.id
(schema/register! ::seon.id/id [:string {:min 12 :max 12}])
;; 12-char base62: 8-char non-wrapping time prefix + 4-char random.
;; 62^8 ≈ 2.2 × 10^14, covers epoch-ms past year 9000.
;; Lex-sort = creation-time sort.

(defn new-id! []  ; the one generator
  …)

;; every identity attr references the shared shape
(schema/register! :seon.eval/id    [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.message/id [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.agent/id   [:seon.id/id {:seon.db/identity true}])
(schema/register! :seon.log/id     [:seon.id/id {:seon.db/identity true}])

```

No per-kind variants of the generator. Agent home-ns is
`seon.agent.<id>` — e.g. `seon.agent.AbCdEfGh1234`. URLs use the
same id verbatim.

- **`:seon.eval/at`**: `:long` epoch-millis. Indisputable; doesn't
  require id-decoding. The canonical timestamp.
- **Entity references in maps**: identity-attr **values** are
  strings (`:seon.fn/sym "seon.foo/bar"`). References use lookup-refs
  `[:seon.fn/sym "seon.foo/bar"]`.
- **Function references in slot attrs** (`:seon.ctx/fn`,
  `:seon.render/ai`): fully-qualified **symbols**, e.g.
  `'seon.render.default/system-section`. Stored as `:symbol`. The
  dispatcher resolves at call time.

When this doc says "symbol" it means the Clojure symbol type
(resolvable reference). "string" / "keyword" mean those types.

## Decisions pending

Each links to the detail block at the bottom via Obsidian block-id.
Refer by id ("yes D3, defer D7"). The body of the spec reflects the
current design; only items below remain open.

**Open — design questions:**

- **[[#^d1]]** — Older-DB-on-newer-runtime upgrade. Deferred; focus
  on bootstrap-from-compiled-code first ([[#^d10]]).
- **[[#^d2]]** — Per-kind redefinability rules (specs / fns /
  tests).
- **[[#^d3]]** — Detect `(def …)` via rewrite-clj AST (no regex).
- **[[#^d4]]** — Targeted test auto-run wiring + warning predicate
  + runtime-var stash.
- **[[#^d5]]** — `(forget!)` for whole namespaces.
- **[[#^d6]]** — Explicit `seon.repl/remove-spec`, `remove-fn`,
  `remove-test`.
- **[[#^d7]]** — `<name>-example` test convention as the documented-
  happy-path stub.
- **[[#^d8]]** — Reference-graph attrs (`:seon.fn/refs`,
  `:seon.fn/input-spec`, `:seon.test/target`) — confirm shape +
  cardinality.
- **[[#^d9]]** — Forgiving parse recovery on parse-error; advance
  to next balanced top-level form.
- **[[#^d10]]** — Topological `bootstrap.edn` emission at substrate
  build time. (Resume topo at runtime is solved: sort by datahike
  tx-id; see Resume phase.)
- **[[#^d11]]** — Per-agent ctx set as `:seon.agent/ctx` multi-ref on
  the agent record. Each agent's record carries cardinality-many refs
  to ITS section entities. Defaults point at `seon.agent/*` fns.
  Customization writes a fn in `seon.agent.<id>` and re-points refs.
  Defers all dynamic dispatch to V2.

The detailed notes for each live in [Decision details](#decision-details) at the
bottom. [Known issues](#known-issues) at the very bottom tracks bugs
the implementation needs to chase that aren't spec questions.

## Verified live in the V0 pod

Every claim below has been exercised end-to-end against the running
CLJS pod via `mcp__seon_cljs__eval`. Source citations + REPL probes
recorded in commit history.

- **rewrite-clj preserves comments + forms as ordered nodes.**
  `(rewrite-clj.parser/parse-string-all ";; hi\n(+ 1 2)\n")` returns a
  `:forms` root whose `:children` include `:comment` and `:list` nodes
  in source order, with full text including trailing newlines.
- **Bare-symbol undeclared-var rejects loudly.**
  `(seon.eval/eval state "Let")` returns
  `{:ok false :error "undeclared undeclared-var: cljs.user/Let"}`.
  Powered by a `set!`+restore on
  `cljs.analyzer/*cljs-warning-handlers*` + a `truly-undeclared?`
  resolver that checks globalThis. See `src/seon/eval.cljs:117-243`.
- **Unqualified core vars resolve.**
  `(seon.eval/eval state "(reduce + (range 10))")` returns
  `{:ok true :value 45 :ns cljs.user}`. Powered by an explicit
  `cljs.js/load-analysis-cache!` call in `init-bootstrap!` (see
  [analyzer-cache load](#analyzer-cache-load) below).
- **tx-meta is the eval-id pointer.** A datahike transact with
  `:tx-meta {:seon.eval/id "..."}` makes the eval-id queryable as
  a datom on the tx-id entity:
  `(d/q '[:find ?tx :where [?tx :seon.eval/id ?eid]] db "...")`
  returns the tx-id. The eval entity and tx entity coincide.
- **Topological replay is `sort-by tx-id`.** Datahike tx-ids are
  strictly monotonic; entity ordering follows creation order, which
  is a valid topo order by construction (lookup-ref forward
  references would have failed at write time). No analyzer-walk
  needed for resume.

## Goal

Deliver an MVP where an LLM agent can:

- Eval one or many Clojure forms per turn
- See a structured, always-current view of its world after each eval
- Write functions, schemas, and tests that accrete in the database
- Curate **any namespace** in the project — not just the agent's own — by
  adding, modifying, and forgetting entities, organized around whatever
  mission the user assigned
- Customize how the rendered context looks, with a guaranteed fallback
- Restart the system and have its persistent work replay in the right order

The defaults must be **simple to explain, simple to understand, simple to
use**. Power comes from the agent extending the system, not from the
defaults being clever.

## Agent + namespace lifecycle

An agent has an identity. The DB stores a session reference under that
identity at `seon.agent.<agent-id>`. That's where the agent **starts**,
but the agent's job is to grow the system: define new namespaces around
whatever data their mission requires (`seon.trading.signals`,
`seon.notes.calendar`, `seon.email.inbox` — whatever the work calls for),
populate them with schemas / fns / tests, and curate them over time.

There is no ownership boundary. Any agent can `(in-ns 'seon.foo)` and
work there. Naming hygiene is a social convention enforced through
rendering (warnings on cross-namespace edits, etc.), not through ACL.

## The agent record is the hub

The library is built around serving agents. The mental model is
**start at your record; everything you own is reachable from there.**

```clojure
(seon.agent/my)
;; => {:seon.agent/id          "AbCdEfGh1234"
;;     :seon.agent/state       :idle
;;     :seon.agent/turn-count  7
;;     :seon.agent/current-ns  :seon.trading
;;     :seon.agent/ctx         [<ctx-ref> <ctx-ref> ...]
;;     ;; …other scalars
;;     }

```

What an agent owns and where it lives:

| State | Location | How agent reaches it |
|---|---|---|
| **Scalars** (`:state`, `:turn-count`, `:current-ns`, etc.) | on the agent record | `(seon.agent/my)` |
| **Ctx set** (their context render layout) | `:seon.agent/ctx` cardinality-many ref on agent record | `(seon.agent/my-ctx)` |
| **Conversation** (`:seon.message/*`) | separate entities tagged via `:seon.message/agent` | `(seon.agent/my-messages 20)` |
| **Eval log** (`:seon.eval/*`) | separate entities tagged via `:seon.eval/agent` | `(seon.agent/my-evals 20)` |
| **Errors** (`:seon.log/*`) | separate entities tagged via `:seon.log/agent` | `(seon.agent/my-logs 20)` |
| **Home ns** | `seon.agent.<id>` (deterministic) | already in it; `(in-ns)` to switch back |
| **Result stash** | globalThis keyed by eval-id | `(result :<eval-id>)` |

Scalars + the actively-mutated ctx set live ON the agent record
(one pull, no joins). High-volume tails (messages, evals, logs)
live as separate entities with a back-ref to the agent record; the
`seon.agent/my-*` helper fns wrap the queries so the agent doesn't
need to know the back-ref idiom.

What's NOT agent-owned and stays globally shared:

- `:seon.fn/*`, `:seon.schema/*`, `:seon.test/*`, `:seon.ns/*` — the
  namespace graph. All agents collaborate on the same fns / schemas
  / tests. "No ownership boundary" per the lifecycle rule above.

### Self-recovery

If an agent borks their context (their custom ctx fn throws, returns
non-string, returns nothing useful), reset is a single transact on
their own record:

```clojure
(seon.agent/reset-my-ctx!)
;; equivalent to:
(seon.db/transact!
  {:seon.db/tx-data [{:seon.agent/id <my-id>
                      :seon.agent/ctx <default-refs>}]})

```

The default ctx refs all point at `seon.agent/*` fns (substrate-
shipped, always present). Nothing the agent does to their home ns
`seon.agent.<id>` can break that. Render mechanism never crashes;
worst case the agent gets the substrate's default view back.

### Helper fns in `seon.agent`

The substrate ships ergonomic accessors so the agent doesn't have
to know datahike idioms. (V1 surface; expand later as needed.)

```clojure
(seon.agent/my)                ; → my agent entity (full pull)
(seon.agent/my-ctx)            ; → [{:seon.ctx/priority _ :seon.ctx/fn _} …] sorted
(seon.agent/my-messages n)     ; → last n messages, oldest-first
(seon.agent/my-evals n)        ; → last n evals, oldest-first
(seon.agent/my-logs n)         ; → last n log entries, newest-first

(seon.agent/reset-my-ctx!)     ; revert ctx to substrate defaults
(seon.agent/update-my-ctx! f)  ; transact (f current-ctx) onto my record

```

These are taught in the system-section ctx so the agent learns them
on turn one. Datalog queries still work for everything not covered
by a helper; the helpers are convenience, not gatekeepers.

## Mental model

```text
┌──────────────────────────────────────────────────────────────────┐
│  REPL conversation = data exchange                               │
│                                                                  │
│  agent → {forms-source}                                          │
│  pod  → {rendered context, fully refreshed}                      │
│                                                                  │
│  The reply IS the context. No separate "eval envelope" type.     │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Database = the program                                          │
│                                                                  │
│  Persistent entities (fns, schemas, tests, requires) accrete     │
│  attribute changes. Replay rebuilds the runtime from them.       │
│                                                                  │
│  Eval log records what was typed and what came back. Never       │
│  replayed; consumed by the renderer for scrollback context.      │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Rendering = section entities → section fns → strings            │
│                                                                  │
│  composer queries :seon.ctx entities, sorts by priority,         │
│  resolves each :seon.ctx/fn symbol, calls it with ctx, joins     │
│  the resulting strings. Empty strings are elided.                │
│                                                                  │
│  Agent extends by rewriting section fns and transacting          │
│  different :seon.ctx/fn symbols on the section entities.         │
└──────────────────────────────────────────────────────────────────┘

```

## Data model

Single database. Two logical layers.

### Persistent entities — "the things that stick"

Built on the `:seon.fn/*` / `:seon.schema/*` / `:seon.test/*` taxonomy.

```clojure
;; Functions
::seon.fn/sym         [:string {:seon.db/identity true}]    ; "seon.trading/analyze"
::seon.fn/ns          :keyword
::seon.fn/source      :string                                ; current source text

;; Schemas
::seon.schema/key     [:keyword {:seon.db/identity true}]
::seon.schema/source  :string                                ; full register! call text

;; Tests
::seon.test/sym             [:string {:seon.db/identity true}]
::seon.test/target          :seon.db/ref                     ; → :seon.fn
::seon.test/source          :string
::seon.test/last-passed-at  :inst {:optional true}          ; most recent successful run
::seon.test/last-failed-at  :inst {:optional true}          ; most recent failed run
::seon.test/last-failure    :string {:optional true}        ; ex-message of most recent failure

;; Agent (the hub for everything one agent owns)
::seon.agent/id          [:seon.id/id {:seon.db/identity true}]   ; 12-char id
::seon.agent/state       [:enum :idle :running]
::seon.agent/turn-count  :int
::seon.agent/turns-since-user :int
::seon.agent/interrupted? :boolean {:optional true}
::seon.agent/current-ns  :keyword {:optional true}                ; falls back to home-ns
::seon.agent/ctx         [:vector :seon.db/ref]                   ; → :seon.ctx entities, cardinality-many.
                                                                  ; Each agent has its OWN ordered set.
                                                                  ; Customize by transacting different refs.

;; Namespaces (one entity per agent-defined or substrate ns)
::seon.ns/name    [:keyword {:seon.db/identity true}]        ; :seon.trading.signals
::seon.ns/source  :string                                    ; "(ns seon.trading.signals (:require [seon.db :as db]))"

```

A namespace is one entity carrying the full `(ns …)` form as source —
that includes the `:require` clause and anything else inside the ns
declaration. Replaying the entity = evaluating the source = the
namespace and its dependencies become available in one step. There
is no separate `:seon.require/*` entity; per-clause storage would
duplicate what `(ns …)` already structures.

**The database IS the system after first boot.** The seon substrate is
compiled CLJS that knows how to interpret what's in the DB and how to
seed it. On a brand-new DB the substrate transacts an ordered vector of
entity maps (the bootstrap data — see "First boot" below). From that
point on the DB is authoritative. A new runtime version paired with an
older DB still resumes — the runtime brings the eval machinery; the DB
brings the source.

**An entity is in the DB iff it passed its gates.** A function that
fails to compile is never persisted in the first place; nothing to
quarantine. A function that fails on replay surfaces the failure
through the eval log (`:ok? false` on that replay's eval entry) —
and is rendered as a warning the next turn. No persistent quarantine
flag.

**No `:touched-tx` attribute.** Datahike attaches `:db/txInstant` and a
tx-id to every datom — provided the conn was opened with
`:keep-history? true`. "What changed since tx T" comes from datahike's
history API (`d/history` + `d/q` with the 5-tuple datom pattern). The
default V0.5 conn uses `:keep-history? false`; turning history on for
the agent DB is a prerequisite for this part of the model, and a
deliberate tradeoff (storage cost vs render power).

**Entity kind is implicit in attribute presence.** No `:seon.X/kind`
discriminator. An entity is "a function" by carrying `:seon.fn/sym`; it
is "a schema" by carrying `:seon.schema/key`. Queries match on the
attrs they need, not on a type tag. The same principle drops
test-status as an enum: a test is "passing" when its `:last-passed-at`
is more recent than its `:last-failed-at` (or `:last-failed-at` is
absent).

### Eval log — "the REPL scrollback"

```clojure
;; Identity + context
::seon.eval/id              [:seon.id/id {:seon.db/identity true}]   ; shared id shape; see "ID conventions"
::seon.eval/agent           :seon.db/ref                          ; → :seon.agent entity (owning agent)
::seon.eval/turn            :long                                 ; the agent's turn-counter at eval time
::seon.eval/at              :long                                 ; epoch-millis at eval start (canonical timestamp)
::seon.eval/duration-ms     :long                                 ; wall-clock elapsed for this form (eval + auto-await)
::seon.eval/ns              :keyword                              ; namespace the form LEFT the agent in (= ending ns)

;; Form text + result
::seon.eval/narration       :string                               ; leading ;; comments captured by parse-forms
::seon.eval/source          :string                               ; the form text (or the unparseable chunk)
::seon.eval/ok?             :boolean                              ; reader + eval both succeeded
::seon.eval/result-edn      :string {:optional true}             ; pr-str of result on success (truncated)
::seon.eval/error           :string {:optional true}             ; pr-str of error payload on failure

```

Effects on persistent state are not stored on the eval entity. Each
eval transacts its persistent datoms in a single tx with `:tx-meta
{:seon.eval/id <id>}`. Datahike records the metadata as datoms on the
tx-id, so **the eval entity IS the tx entity**. "What did this eval
touch?" is answered by querying `(d/history db)` for datoms in that
tx; "what did it retract?" is the same query filtered to retractions.
No denormalized ref-vectors.

`:seon.eval/ns` is the namespace the agent ended in after this form
ran — i.e. the live `!current-ns` value seon.eval/eval-batch! writes
after each form. A `(ns other)` form's eval entry carries `:ns :other`
even though the form itself was parsed in the previous ns.

The agent's **current namespace** also lives on the agent entity as
`:seon.agent/current-ns :keyword {:optional true}`, upserted on
every form that changes the ns. Two writes (eval-log + agent entity)
sound redundant, but the agent-entity attr is the one renderers
pull on every render — turning that into a "sort eval log, take
most recent" query at render time would be a measurable cost for no
gain. The eval-log `:ns` is per-form history; the agent attr is the
current value. Both load-bearing, both cheap. Falls back to
`seon.agent/home-ns` when absent.

Ns-switch events are derivable from consecutive eval entries
(`:ns` differs from prior eval's `:ns`); no `:switched-ns-to`
attr needed.

The "kind" of an eval is read from `:ok?` and from the history-query
answer "what datoms did this eval's tx write?". The renderer never
branches on a discriminator field.

- `:ok?` false → look at `:error`. The kind of failure (parse vs runtime
  vs timeout) lives in the error payload, not as a separate attr.
- History query for the eval's tx returns asserted datoms on a
  `:seon.fn` / `:seon.schema` / `:seon.test` entity → the eval created
  or updated persistent state.
- History query returns retraction datoms → the eval retracted
  entities.
- `:ns` differs from the previous eval's `:ns` → the form switched
  namespace. Renderer compares; no discriminator attr.
- None of the above → the eval ran successfully but produced no
  persistent change (an expression like `(+ 1 2)` or `(d/q …)`).

### What's NOT in the model

- No separate `:read-error` / `:exception` attrs. The kind of failure
  lives in the `:seon.eval/error` payload, not as a top-level attr.
- No `:seon.eval/touches` or `:seon.eval/forgot` ref-vectors. The
  tx that wrote the eval entity IS the tx that wrote (or retracted)
  the persistent datoms — datahike's `:tx-meta` attaches the eval-id
  to the tx-id, so the history query recovers both directions of
  the answer.
- No `:reversible?` boolean. Reversibility is derived per-render from
  the history datoms the eval's tx wrote (see the table in "Forget"
  below).
- No `:session-id` and no monotonic `:seq`. The eval-id is already
  time-prefixed base62 and unique; ordering is by `:at` (or by id).
  "This session" is the suffix of evals after the most recent
  resume-marker (see "Resume phase").
- No `:seon.eval/grades` storage. Grades are computed on render.
- No `:seon.warning/*` persistent entities. Warnings are pure functions
  of current DB state — every warning is recomputed at render time by
  whichever predicate registers itself. Storing them would just risk
  the stored warning going stale relative to the live data it refers
  to. See `warnings-section` below.

## The eval batch

### Input

```clojure
{::seon.eval/agent-id  "agent-alpha"
 ::seon.eval/source    "(defn analyze ...)\n(deftest analyze-test ...)"}

```

One string containing N top-level forms.

### Processing

The agent's response is a single string of **valid ClojureScript** —
forms interleaved with `;;` comments. Nothing else. The reader does
the heavy lifting: we ask it for every form AND every comment, then
pair them up in order.

**No reordering.** Forms run in input order. `(in-ns 'foo)` mid-batch
switches the namespace for subsequent forms.

#### Parse: forms-and-comments

Use `rewrite-clj.parser/parse-string-all`, which parses Clojure
source as a tree of nodes that **includes** whitespace and `:comment`
nodes alongside form nodes (verified against
`reference-code/rewrite-clj/src/rewrite_clj/parser.cljc` and
`node/comment.cljc`). Walk the top-level children, filtering
whitespace, and you get the ordered vector below — no extra
machinery, no hand-rolled scanner.

(Aside: Edamame, the other parser in our deps, drops comments at the
reader — `edamame.impl.parser/parse-comment` reads the line and
returns the reader. rewrite-clj is the right tool here.)

```clojure
[{:kind :comment :text "## Plan"}
 {:kind :comment :text "Schema first, then the function."}
 {:kind :form    :form  '(schema/register! ::ticker :string) :source "(schema/register! ::ticker :string)"}
 {:kind :comment :text "Now the analyzer:"}
 {:kind :form    :form  '(defn analyze …) :source "(defn analyze …)"}
 {:kind :comment :text "Sanity-check it returns the right shape:"}
 {:kind :form    :form  'analyze :source "analyze"}]

```

Notice the last entry: a bare symbol `analyze`. **Bare symbols are
forms.** They eval like any other expression — return the value, or
throw an unbound-symbol error if the agent referenced something that
doesn't exist. That's standard REPL behavior; we don't try to
disambiguate at parse time.

**Markdown lives inside `;;` comments.** Multi-line markdown is
just multiple `;;` lines in a row:

```clojure
;; ## Plan
;;
;; 1. Register the ticker schema
;; 2. Build analyze
;; 3. Verify by evaling `analyze`

```

The reader sees these as four/five consecutive comments. The pairer
joins consecutive comments into one narration block. The agent
formats with markdown freely (`##`, `-`, code-spans, whatever);
those characters are valid inside a `;;` line.

**Comments are how the agent talks to the user.** The web view (a
later milestone) renders `:seon.eval/narration` as Markdown — the
user sees the same `## Plan` / bullets / code-spans the agent wrote,
formatted. So `;;` is two channels at once: the agent's thinking
captured in the eval log, AND the agent's explanation to the user.
Teach this in the system-section so the LLM uses comments as a
first-class communication device, not an afterthought.

#### Pair: comments → narration → form

Walk the vector front to back:

- Comments accumulate into pending-narration, separated by `\n`.
- The next form's `:seon.eval/narration` = the accumulated text;
  pending-narration resets.
- If the vector ends with pending narration and no following form,
  emit a **thinking-only** eval entry: `{:seon.eval/source ""
  :seon.eval/narration <accumulated> :seon.eval/ok? true}`. The
  agent's closing thoughts survive in scrollback.

#### Per-form loop

For each entry classified as a form:

1. **Snap a start timestamp.** `(js/Date.now)` before the eval call.
2. **Eval** the parsed form in the agent's current ns (the value of
   the `!current-ns` atom seon.eval/eval-batch! already maintains).
   On success, record `:ok? true` + `:result-edn`. On any failure
   (compile, runtime, timeout, unbound-symbol), record `:ok? false` +
   `:error` (pr-str'd map carrying `:kind :compile | :runtime |
   :timeout`).
3. **Record `:seon.eval/ns`** as the ending ns returned by
   `cljs.js/eval-str`'s `:ns` field — i.e. where the form left the
   agent. Same value the existing pipeline already writes back into
   `!current-ns`. If `:ns` differs from the agent entity's
   `:seon.agent/current-ns`, upsert the new value onto the entity
   in the same tx as the eval entry.
4. **Record `:seon.eval/duration-ms`** = `(- (js/Date.now) start)`.
   Covers the form's eval AND any auto-await — i.e. what the agent
   actually waited for. Cheap (two `Date.now()` calls); always on.
5. **Tag the tx with the eval-id.** The eval entity and any
   persistent-entity datoms produced by the form go in a single
   `d/transact` call with `:tx-meta {:seon.eval/id <id>}`. Datahike
   records `:seon.eval/id` as a datom on the tx-id, so the eval
   entity IS the tx — no separate denormalized "what did this touch"
   attr. Effect classification at render time is a history query
   over that tx. Ns switches need no special handling — `:ns`
   already captured it.
6. **Independent transact per form** — one tx per form. A failure on
   form 5 doesn't roll back forms 1-4.

After every entry is processed, render the full context.

#### Parse failures

If the parser throws (the agent emitted something that isn't valid
ClojureScript and isn't a `;;` comment — e.g. raw markdown outside
a comment), we record an `:ok? false` eval entry with `:kind :read`,
advance to the next balanced top-level boundary, and continue. The
error message tells the agent what went wrong; the system-section
reminds them prose belongs in `;;` comments. Bare prose tokenizes
into unbound-symbol errors the agent sees in the next turn's
`recent-evals` tile — a loud, self-correcting signal, no
prose-detection heuristic needed.

#### Undeclared-var surfacing

`cljs.js/eval-str` is permissive by default: an undeclared var
emits a `:undeclared-var` warning to stderr but the form still
compiles. At runtime the unresolved reference becomes `nil`, and
the eval returns `{:ok true :value nil}`. That's wrong for our
contract — bare prose like `Let me read` would tokenize into four
silent `nil`-valued evals.

The fix is configuration, not design. `seon.eval/raw-eval` installs
a `cljs.analyzer/*cljs-warning-handlers*` binding that throws on
`:undeclared-var` / `:undeclared-ns` / `:undeclared-ns-form`:

```clojure
(set! cljs.analyzer/*cljs-warning-handlers*
  [(fn [type _env extra]
     (when (#{:undeclared-var :undeclared-ns :undeclared-ns-form} type)
       (throw (ex-info (str "undeclared-var: "
                            (:prefix extra) "/" (:suffix extra))
                       {:kind :compile
                        :seon.eval/warning-type type
                        :seon.eval/extra extra}))))])

```

cljs.js wraps the throw as `{:tag :cljs/analysis-error}` and returns
it via `:error`. `raw-eval`'s existing `:error` branch then yields
`{:ok false :error ...}` — and the agent's `recent-evals` tile
shows the loud feedback the design depends on. Verified in REPL.

**Partial-success principle.** If the agent sends 10 forms and 9
succeed, the database keeps 9 successes. Read failures and eval
failures both land in the eval log as `:seon.eval` entries with
`:ok? false` and a structured `:error` payload — same partial-success
shape, no special batch-level handling. Dependents of a failed form
(later forms that referenced what it would have defined) get their own
runtime errors naturally and appear in the log as such. No rollback
machinery anywhere.

### Output

The reply IS the next-turn context render. Same shape, same renderer.

## Rendering — sections compose strings

The whole context is a concat over **section entities** queried from
the database. Each section function reduces the DB into a chunk of
text. The composer joins them by priority.

This is deliberately small: no per-entity-shape dispatch in the MVP.
The agent extends rendering by writing more section functions and by
overriding the symbol stored in each section's `:seon.ctx/fn` slot. A
later milestone adds specificity-based per-entity dispatch on top
(see "Future: per-entity dispatch" below); the MVP doesn't need it.

### Sections are entities with section-functions

A section is an entity in the database. Presence of these attrs makes
something a section — there's no separate "section type":

```clojure
{:seon.ctx/name      :current-ns
 :seon.ctx/priority  30
 :seon.ctx/fn        'seon.render.default/current-ns-section}

```

The `:seon.ctx/fn` slot holds a **fully-qualified symbol**. At render
time the existing `seon.render/resolve-symbol` (CLJS) or
`requiring-resolve` (CLJ) resolves it to a function; the function
takes the render context and **returns a string** (possibly empty).

That contract is fixed:

- **Return value is always a string.** An empty string elides the
  section in the composer's output (no double newlines).
- **The function decides its own internal structure.** It can run
  multiple DB queries, sort/group/paginate, call helper renderers, or
  branch on `ctx`. The MVP imposes no schema on the function body —
  whatever returns a string is valid.
- **Symbol misses fall through to pretty-print.** If a slot points at
  a symbol that doesn't resolve (typo, agent retracted the fn), the
  composer renders the section entity itself via the universal
  `pretty-ai` fallback. The render never crashes.

### The composer

The composer IS the function pointed at by the agent's
`:seon.render/ai` slot (currently `seon.render.default/ctx`). It
returns the map-shape the existing `ai-dispatch` expects
(`{:seon.render/text "..."}`), so it plugs into the agent surface
already in code:

```clojure
(defn assemble-ctx
  {:malli/schema [:=> [:cat :seon.render/system-input]
                  :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  {:seon.render/text
   (->> (db/query
          {:seon.db/db db
           :seon.db/query '[:find [(pull ?e [*]) ...]
                            :where [?e :seon.ctx/name _]]})
        (sort-by :seon.ctx/priority)
        (map (fn [section]
               (let [f (seon.render/resolve-symbol (:seon.ctx/fn section))
                     ctx-in {:seon.db/db    db
                             :seon.agent/id id}]
                 (if f
                   (f ctx-in)
                   (str (default/pretty-ai section))))))
        (remove str/blank?)
        (str/join "\n\n"))})

```

The composer is the only piece that knows about "sections". Section
functions are ordinary Clojure — the agent can read, write, or replace
any of them by transacting a different symbol into the slot.

### Future: per-entity dispatch (post-MVP)

The CLJ side of seon already has a specificity-based renderer
discovery (see `seon.render/find-renderer` and `resolve-renderer` in
`src/seon/render.clj`): functions whose `:malli/schema` output is
`:seon.render/ai` are queried out of the codebase graph, then ranked
by how many of their required input keys match the data's keys.

A natural follow-on for the agent REPL: lift that dispatch into the
CLJS pod so section functions can `(render entity)` and get the most
specific renderer for whatever shape `entity` has. That's strictly
additive — the section function still returns a string, it just
delegates more of the work to dispatch. Reserved for a later
milestone; the MVP ships without it.

### Agent customization, two levers

1. **Change what a section returns**: write a new section function and
   transact `:seon.ctx/fn 'my.ns/my-section` on the section entity, or
   change priority by transacting `:seon.ctx/priority` on it. Add a
   new section by transacting a new `:seon.ctx` entity. The body of
   the section function is unconstrained — query the DB, format the
   string, return it.
2. **Override the composer**: rare; needed only if the agent wants a
   completely different top-level layout. Transact a different
   `:seon.render/ai` symbol on the agent entity.

### Initial default context — what ships

These are the section entities transacted on first boot and the
functions that drive them. The agent can override or replace any of
them by transacting different attrs on the same entity (lookup by
`:seon.ctx/name`) or by retracting and adding a different one.

```clojure
;; --- Section entities (baseline) ---

{:seon.ctx/name :system  :seon.ctx/priority 10
 :seon.ctx/fn 'seon.render.default/system-section}

{:seon.ctx/name :related-ns  :seon.ctx/priority 20
 :seon.ctx/fn 'seon.render.default/related-ns-section}

{:seon.ctx/name :current-ns  :seon.ctx/priority 30
 :seon.ctx/fn 'seon.render.default/current-ns-section}

{:seon.ctx/name :warnings  :seon.ctx/priority 40
 :seon.ctx/fn 'seon.render.default/warnings-section}

{:seon.ctx/name :recent-evals  :seon.ctx/priority 50
 :seon.ctx/fn 'seon.render.default/recent-evals-section}

{:seon.ctx/name :prompt  :seon.ctx/priority 99
 :seon.ctx/fn 'seon.render.default/prompt-section}

```

```clojure
;; --- Section functions (baseline, in seon.render.default) ---
;; Each takes :seon.render/system-input and returns a string.
;; Empty string = section omitted. `current-ns` is read directly
;; off the agent entity; eval-batch! upserts it on every (ns …) form.

(defn- agent-current-ns [db id]
  (or (-> (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
          :seon.agent/current-ns)
      (seon.agent/home-ns id)))

(defn- host-timezone
  "Best-effort IANA timezone of the pod's host. POD timezone, not the
   user's — surfacing the user's tz needs a signal from outside the
   pod (browser, env var, agent entity attr). See post-MVP note below."
  []
  (.. (js/Intl.DateTimeFormat.) resolvedOptions -timeZone))

(defn system-section
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns  (agent-current-ns db id)
        now (js/Date.)]
    (str "<system agent=\"" id "\" ns=\"" ns "\">\n"
         "  Now: " (.toISOString now) "  (pod tz: " (host-timezone) ")\n"
         "  Restore defaults: (seon.render/reset-defaults!)\n"
         "</system>")))

(defn current-ns-section
  "Every persistent entity owned by the current ns: the ns entity itself
   (which carries the (ns …) form), then its fns, schemas, tests.
   Schema/test ownership is derived from the namespaced key or sym."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns       (agent-current-ns db id)
        ns-prefix (name ns)
        ns-ent   (db/entity {:seon.db/db db :seon.db/ref [:seon.ns/name ns]})
        fns      (db/query {:seon.db/db db
                            :seon.db/query '[:find [(pull ?e [*]) ...]
                                             :in $ ?ns
                                             :where [?e :seon.fn/ns ?ns]]
                            :seon.db/args [ns]})
        schemas  (->> (db/query {:seon.db/db db
                                 :seon.db/query '[:find [(pull ?e [*]) ...]
                                                  :where [?e :seon.schema/key _]]})
                      (filter #(= ns-prefix (namespace (:seon.schema/key %)))))
        tests    (->> (db/query {:seon.db/db db
                                 :seon.db/query '[:find [(pull ?e [*]) ...]
                                                  :where [?e :seon.test/sym _]]})
                      (filter #(let [s (:seon.test/sym %)
                                     slash (.indexOf s "/")]
                                 (and (pos? slash)
                                      (= ns-prefix (subs s 0 slash))))))
        parts    (concat
                   (when ns-ent     [(:seon.ns/source ns-ent)])
                   (map :seon.schema/source schemas)
                   (map :seon.fn/source fns)
                   (map :seon.test/source tests))]
    (if (seq parts)
      (str "<current-namespace name=\"" ns "\">\n"
           (str/join "\n\n" parts)
           "\n</current-namespace>")
      "")))

(defn related-ns-section
  "Symbols from namespaces referenced by the current ns, signature-only.
   Agent reaches for `:seon.fn/source` via current-ns-section when they
   switch ns and want the full body."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns      (agent-current-ns db id)
        related (compute-related-ns db ns)              ; helper, defined elsewhere
        rows    (for [other (sort related)
                      f     (db/query {:seon.db/db db
                                       :seon.db/query
                                       '[:find [(pull ?e [:seon.fn/sym]) ...]
                                         :in $ ?ns
                                         :where [?e :seon.fn/ns ?ns]]
                                       :seon.db/args [other]})]
                  (str "  " (:seon.fn/sym f)))]
    (if (seq rows)
      (str "<related-namespaces>\n" (str/join "\n" rows) "\n</related-namespaces>")
      "")))

(defn warnings-section
  "Run every registered warning-predicate over the agent's accessible
   entities. Each predicate returns either nil or a map carrying
   :seon.warning/text + :seon.warning/severity."
  [{:seon.db/keys [db] :as input}]
  (let [preds (registered-warning-predicates db)
        ws    (->> (for [p preds, w (p input) :when w] w)
                   (sort-by :seon.warning/severity))]
    (if (seq ws)
      (str "<warnings>\n"
           (str/join "\n" (map :seon.warning/text ws))
           "\n</warnings>")
      "")))

;; Example warning predicate, registered as a default. Surfaces any
;; eval in the recent-evals window that took longer than the threshold.
;; Pure derivation from :seon.eval/duration-ms — no stored state.
(def slow-eval-threshold-ms 500)

(defn slow-eval-warning
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [recent (recent-evals-rows db id 20)
        slow   (filter #(> (or (:seon.eval/duration-ms %) 0)
                           slow-eval-threshold-ms)
                       recent)]
    (for [e slow]
      {:seon.warning/severity :info
       :seon.warning/text
       (str "slow eval " (:seon.eval/id e)
            " took " (:seon.eval/duration-ms e) "ms — consider"
            " profiling: (seon.perf/profile-form …)")})))

(defn recent-evals-section
  "The last N evals (default N=20), oldest-first so it reads
   top-to-bottom like a real REPL transcript. The eval-id is
   time-prefixed base62 — sorting by id is identical to sorting by
   creation order, and cheaper than sorting by `:at`."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [rows (->> (db/query {:seon.db/db db
                             :seon.db/query
                             '[:find [(pull ?e [*]) ...]
                               :in $ ?aid
                               :where [?e :seon.eval/agent ?aid]]
                             :seon.db/args [ [:seon.agent/id id]]})
                  (sort-by :seon.eval/id)
                  (take-last 20))]
    (if (seq rows)
      (str "<recent-evals>\n"
           (str/join "\n\n" (map format-eval-row rows))
           "\n</recent-evals>")
      "")))

(defn prompt-section
  "Renders the final piece of context as a REPL prompt the agent is
   typing into. The current ns appears exactly as a real Clojure REPL
   shows it, so the LLM is primed to continue the conversation as
   the next form in that ns. Always present — never empty."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent  (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        ns   (or (:seon.agent/current-ns ent) (seon.agent/home-ns id))
        turn (or (:seon.agent/turn-count ent) 0)]
    (str ns "=>  ; turn " turn)))

```

That's the whole default surface: 6 section entities + 6 section
functions, ~100 lines of straightforward Clojure. Adding or modifying
any of it = writing one function. Nothing is hidden; nothing is
special-cased.

### Data shapes

```clojure
;; Render-context: every section fn receives this as its sole argument.
;; Matches the existing :seon.render/system-input shape (src/seon/render.cljs)
;; so a section fn can also be called as an agent's :seon.render/ai slot.
:seon.render/system-input
  [:map
   [:seon.db/db    :any]
   [:seon.agent/id :string]]

;; Section entity (persisted). Identified by :seon.ctx/name.
::seon.ctx/entity
  [:map
   [:seon.ctx/name      :keyword]
   [:seon.ctx/priority  :long]
   [:seon.ctx/fn        :symbol]]   ; ns-qualified, resolves to a section fn

;; Warning record (transient; produced by warning predicates at render time).
::seon.warning/record
  [:map
   [:seon.warning/text     :string]                ; rendered text
   [:seon.warning/severity :keyword]]              ; :error | :warn | :info

```

Note: section fns read the agent's current ns off the agent entity
(`:seon.agent/current-ns`), which the eval pipeline upserts on every
ns-changing form. The render input schema stays identical to the
existing surface — no extra parameter, no extra lookup.

### XML wrappers around structural sections

LLMs parse XML cleanly — clear start/end markers, no paren-counting
needed. The default sections use XML wrappers at the section level
for unambiguous boundaries:

- `<system>…</system>`
- `<current-namespace name=":seon.trading">…</current-namespace>`
- `<related-namespaces>…</related-namespaces>`
- `<warnings>…</warnings>`
- `<recent-evals>…</recent-evals>`

Inside the section, we **don't** wrap individual entities. Clojure
source stays as Clojure source — that's what we want the agent
emitting, and the more it looks like REPL output the more naturally
it writes the same. The XML is the scaffolding around the Clojure;
the Clojure is the content.

Exception: the per-eval row in recent-evals uses bash-style `> form`
+ `; # eval-id  Nms` because that's what a real REPL transcript
looks like. No XML around each row — too noisy.

## Worked example — DB to rendered text

Database state (illustrative):

```clojure
;; Persistent entities
{:seon.fn/sym "seon.trading/analyze"
 :seon.fn/ns :seon.trading
 :seon.fn/source "(defn analyze {:malli/schema [:=> [:cat ::analyze-req] ::analyze-resp]} [{::keys [ticker]}] {::signal :hold})"}

{:seon.schema/key :seon.trading/analyze-req
 :seon.schema/source "(schema/register! ::analyze-req [:map [::ticker :string]])"}

{:seon.schema/key :seon.trading/ticker
 :seon.schema/source "(schema/register! ::ticker :string)"}

;; Section entities (transacted at bootstrap)
{:seon.ctx/name :system        :seon.ctx/priority 10 :seon.ctx/fn 'seon.render.default/system-section}
{:seon.ctx/name :related-ns    :seon.ctx/priority 20 :seon.ctx/fn 'seon.render.default/related-ns-section}
{:seon.ctx/name :current-ns    :seon.ctx/priority 30 :seon.ctx/fn 'seon.render.default/current-ns-section}
{:seon.ctx/name :warnings      :seon.ctx/priority 40 :seon.ctx/fn 'seon.render.default/warnings-section}
{:seon.ctx/name :recent-evals  :seon.ctx/priority 50 :seon.ctx/fn 'seon.render.default/recent-evals-section}

;; Eval log (last 2 from this session). Each was transacted with
;; :tx-meta {:seon.eval/id <id>}, so the tx-id IS the eval-entity id;
;; the persistent datoms the form wrote are on the same tx.
{:seon.eval/id "K9p2x4nB7q" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(schema/register! ::ticker :string)"
 :seon.eval/result-edn "true"}

{:seon.eval/id "L4m9p1xA3v" :seon.eval/turn 7 :seon.eval/ok? true
 :seon.eval/source "(defn analyze ...)"
 :seon.eval/result-edn "#'seon.trading/analyze"}

```

Render input: `{:seon.db/db <db> :seon.agent/id "AbCdEfGh1234"}`. Each section
fn pulls the agent entity itself to learn the current ns (here:
`:seon.trading`).

Render walk:

```text
(sections-in-db ctx)  → query for entities with :seon.ctx/name + :priority + :fn
                      → 5 default section entities
(sort-by :seon.ctx/priority)

For each section:
  text ← ((resolve-symbol (:seon.ctx/fn section)) ctx)
  ; → string (possibly "")

section :system        → "<system agent=\"alpha\" ns=\":seon.trading\">…</system>"
section :related-ns    → ""   ; no cross-ns refs in this example
section :current-ns    → "<current-namespace name=\":seon.trading\">
                          (ns seon.trading)
                          (schema/register! ::analyze-req …)
                          (schema/register! ::ticker :string)
                          (defn analyze …)
                          </current-namespace>"
section :warnings      → "<warnings>analyze has no test coverage</warnings>"
section :recent-evals  → "<recent-evals>
                          ;; ## Plan
                          ;; 1. Register the ticker schema
                          ;; 2. Build analyze on top
                                                            ; # J3p8m2rA1k
                          > (schema/register! ::ticker :string)
                          true                              ; # K9p2x4nB7q  3ms
                          > (defn analyze …)
                          #'seon.trading/analyze            ; # L4m9p1xA3v  1ms
                          ;; sanity check the var resolves
                          > analyze
                          #object[seon$trading$analyze]     ; # M2q7w0vB9x  0ms
                          </recent-evals>"
section :prompt        → ":seon.trading=>  ; turn 7"

composer drops blank strings and joins the rest with "\n\n":
  <system>…</system>
  <current-namespace>…</current-namespace>
  <warnings>…</warnings>
  <recent-evals>…</recent-evals>
  :seon.trading=>  ; turn 7

```

The full path: query for section entities → resolve each section's
symbol → call the function → join the resulting strings.

## Recent-evals tile (REPL-style)

For each eval in the rendered window, emit one of two shapes
depending on whether the entry has a form or is thinking-only:

```text
> (form-source-as-typed)
result-rendered    ; # eval-id  4ms

```

```text
;; thinking: <narration text, indented as a markdown block>
                                ; # eval-id

```

The trailing `<n>ms` on a form row is `:seon.eval/duration-ms`
formatted: ms for sub-second, `1.2s` / `12.5s` / `2m 30s` for
longer. Always present on form rows; omitted on thinking-only rows
(no duration to report). Cheap, always-on per-form timing so the
agent sees the cost of every form they evaluate. Fast forms vanish
into the noise; slow forms shout.

Thinking-only entries — `:seon.eval/source` blank, `:narration`
populated — render as a quoted block. The agent's reasoning between
turns survives as scrollback context, exactly as their code does.

For the MVP, `result-rendered` is just `truncate-edn` applied to
`:seon.eval/result-edn`. Smart per-shape result rendering is the
first thing the per-entity dispatch (post-MVP) enables.

The `# eval-id` comment ALWAYS appears on the result line, regardless
of custom formatting. It's the handle the agent uses to reference past
results in subsequent forms via `(result :<eval-id>)`.

### Smart EDN truncation

`seon.render.default/truncate-edn` is a budgeted, structure-preserving
EDN truncator. Behavior:

- Hard byte cap per result (default 2 KB; configurable).
- Map: keep first N keys, then `..., #_(<n> more keys)`.
- Vector: keep first N entries, then `..., #_(<n> more)`.
- Set: same as vector.
- Nested values truncated recursively with diminishing budget.
- Trailing `...` is valid EDN — the truncated output round-trips
  through the reader (no half-open delimiters).

Prior art and a portable opt-out prefix idea (`#_:full` for "don't
truncate this form") live in `bin/mcp-server` — see the
"Prior art located" section below for the full reference.

### Result auto-save (per-eval addressable values)

Every successful eval's value is reachable by eval-id on the next
turn. `:seon.eval/result-edn` stores the **truncated** value for
display in recent-evals (default 2 KB; configurable). The full live
value lives on `globalThis` under the eval-id (implementation in
`seon.eval/stash-result-raw!`, `src/seon/eval.cljs:235`), reachable
via `(result :<eval-id>)` within the same pod session. No pr-str
round-trip — values that don't read back through `read-string`
(datahike DB tagged literals, JS objects, fns) still come back
identical.

If the agent wants a full value preserved across restart, they
transact it explicitly via `seon.db/transact!` against an attr they
register. The agent decides what's worth keeping.

Cross-session retention: `globalThis` values die with the pod. On
the next pod boot, `(result :<eval-id>)` for an eval recorded by a
prior session returns nil. The agent re-evals the source from
`:seon.eval/source` if they need the value live again — surface this
behavior in the system-section so the agent learns the pattern.

### Retro-format

Because rendering is pure-functional over current data + current
section functions, when the agent rewrites `recent-evals-section` (or
the `format-eval-row` helper it calls) the new format applies to
every entry in the window on the next turn. No replay needed —
`:seon.eval/result-edn` is stored in the log, the format is computed
at render time.

## Custom rendering

The agent customizes rendering by rewriting section functions, not by
registering per-shape renderers (post-MVP). Example: collapse the
recent-evals tile to one line per eval.

```clojure
(defn my.work/compact-recent-evals
  [{::keys [db agent-id]}]
  (let [rows (->> (db/q db '[:find [(pull ?e [:seon.eval/id :seon.eval/source]) ...]
                             :in $ ?aid
                             :where [?e :seon.eval/agent ?aid]]
                        [:seon.agent/id agent-id])
                  (sort-by :seon.eval/id)
                  (take-last 20))]
    (str "<recent-evals>\n"
         (str/join "\n" (map (fn [{:seon.eval/keys [id source]}]
                               (str id "  " (subs source 0 (min 60 (count source)))))
                             rows))
         "\n</recent-evals>")))

(db/transact! {:seon.db/tx-data
               [{:seon.ctx/name :recent-evals
                 :seon.ctx/fn 'my.work/compact-recent-evals}]})

```

If the new function throws or returns a non-string, `pretty-ai` takes
over for that section and the failure surfaces as a warning so the
agent knows what broke.

## Self-instrumentation

Two layers, cheap-by-default and precise-on-demand.

**Layer 1 — per-eval timing (always on).** `:seon.eval/duration-ms`
is captured on every eval (see "Per-form loop" #4) and rendered next
to the eval-id; `slow-eval-warning` (defined alongside the other
default warning predicates) lifts slow forms into the warnings tile.
No registry, no opt-in.

**Layer 2 — Tufte profiling (opt-in).** When timing says "slow" and
the agent wants to know *why*, they enable Tufte. The hook point is
the existing `:seon.dev/instrumentation` Integrant layer that
already wraps every `:malli/schema`-annotated public fn for runtime
validation; when profiling is enabled, the same wrapper additionally
wraps each fn with `(taoensso.tufte/p ::ns/name body)`. Every public
fn — substrate AND agent-authored — flows through this seam, because
`seon.code/check` requires `:malli/schema` before a fn is persisted.

```clojure
;; turn profiling on for the next render or for a specific form
(seon.perf/with-profiling
  (assemble-ctx input))
;; => {:value <result> :stats #tufte/PStats { … }}

;; or globally for a window of time
(seon.perf/set-enabled! true)
;; … do work …
(seon.perf/set-enabled! false)
(seon.perf/last-stats)   ; the accumulated stats since enable

```

Off by default because `tufte/p` at ~50ns per call adds up in tight
loops — cheap `Date.now()` deltas carry the routine signal, Tufte is
the precision tool the agent reaches for when routine signal points
at a problem.

### Surface section: `perf-section`

```clojure
;; ships disabled — agent enables when they want to look
(defn perf-section
  [_input]
  (let [stats (seon.perf/last-stats)]
    (if stats
      (str "<perf>\n" (tufte/format-pstats stats {:columns [:n :sum :mean :p90]})
           "\n</perf>")
      "")))

```

The agent enables this tile when they're optimizing; disables it
when they're not. Both states are one transact on the `:perf`
section entity. The section is silent when stats are empty (e.g.
profiling has been off since boot).

### Out of scope

- Per-form sampling, percentiles aggregated across sessions,
  automatic regression detection.
- Always-on Tufte — see above; the cheap default covers the common
  case.

## Restoring defaults

The runtime cannot be destroyed by agent action. The compiled CLJS
substrate is always loaded; every var seon ships with is callable
regardless of what's in the DB. The DB carries source records for those
vars (and any agent additions/overrides). Forgetting a DB entity
removes the source record, not the runtime var.

**`(seon.render/reset-defaults!)`** replays the bootstrap as an
"add-missing-only" pass. Implementation: for each entry in
`resources/seon/bootstrap.edn`, pull the entity by its identity attr;
if absent, transact the entry; if present, skip it. This preserves
every attr the agent has edited — datahike's plain upsert would
overwrite them, so the no-op-on-existing check is explicit.

- Missing-from-DB defaults are added back (section entities, default
  `seon.render.default/*` source records, etc.).
- Entries the agent has modified keep the agent's version; the
  bootstrap's version is skipped, not merged.
- Entries the agent retracted are re-added.
- Strictly additive — never destructive of agent work.

A more aggressive `(seon.render/reset-defaults! :overwrite true)`
transacts the bootstrap directly (datahike upsert), overwriting every
attr that conflicts. Always logged. The agent uses this when they've
broken their context and want the original everything back.

System-instructions tile (in every render) includes:

```text
If your context renders incorrectly, restore the defaults:
  (seon.render/reset-defaults!)            ; idempotent upsert, agent edits preserved
  (seon.render/reset-defaults! :overwrite true) ; full reset, overwrites agent renderer edits

```

Forgetting a default the agent didn't intend to: the next
`reset-defaults!` brings it back. No persistent "you can't touch this"
flag — just the substrate's right to re-seed itself.

## Provenance — "why is this in my context?"

Provenance is derivable, not stored. Each section entity carries
`:seon.ctx/fn` — the function that produced that section's text. The
agent can pull the section entity to find out which function ran:

```clojure
(seon.db/pull-by-name {:seon.ctx/name :recent-evals})
;; => {:seon.ctx/name :recent-evals
;;     :seon.ctx/priority 50
;;     :seon.ctx/fn 'seon.render.default/recent-evals-section}

```

For "what did this function emit?" the agent simply calls the section
function directly in the REPL with `(seon.render/explain-section ctx :recent-evals)`
— it runs the section-fn and returns the string with the source-symbol
annotation. No special tracing infrastructure; just two operations on
the section entity (pull + call).

## Forget — symbol deletion

```clojure
(seon.repl/forget! 'seon.trading/analyze)

```

Steps:

1. Look up the entity by identity attr (`:seon.fn/sym`, `:seon.schema/key`,
   `:seon.test/sym`, etc.).
2. Retract the entity from datahike. The retracting transact carries
   `:tx-meta {:seon.eval/id <id>}`, so the eval entry and the
   retraction datoms share a tx-id.
3. `ns-unmap` the var (or `seon.schema/unregister!` for a schema, or the
   analog for a test).
4. Surface dependents (entities whose source references the forgotten
   symbol) as warnings on the next render.

Forgetting a default brought in by bootstrap is allowed — the next
`(seon.render/reset-defaults!)` brings it back. There is no
forget-refusal.

**Reversibility is derived, not stored.** A small classifier runs at
render time over each eval entry and decides reversibility from the
datoms its tx wrote (read via `(d/history db)`):

| Eval shape | Reversible? | Mechanism |
|---|---|---|
| Tx wrote assertions on persistent entities, no atom/capability calls in `:source` | Yes | retract each asserted entity + ns-unmap (or unregister) |
| `:ns` differs from previous eval's `:ns`, tx wrote no other datoms | Yes | re-eval the previous eval's source to restore the old ns |
| Tx wrote retraction datoms | Partial | the entity can be re-defined by re-evaluating its source |
| `:source` calls `swap!`/`reset!` or a WIT capability | No | state already mutated; no recorded "before" |
| Plain expression — tx wrote no datoms beyond the eval entity itself, no mutating call | Yes | no side effects to undo |

The renderer surfaces "↶ reversible" / "✘ irreversible" alongside each
recent-evals entry so the agent always knows which steps can be cleanly
walked back. Classifier lives next to the renderer (`seon.render.default`),
not in the eval log — it can be replaced by registering a more specific
classifier without a schema change.

## Boot sequence

```text
boot:
  init-bootstrap!                              ; cljs.core analyzer-cache load (see below)
  if (database-empty? db) bootstrap-phase!    ; seed the DB
  resume-phase!                                ; rebuild runtime from DB
  render-initial-context!                      ; first turn for the agent

```

The two phases never run independently. On a brand-new DB, bootstrap
seeds and then resume eval's the freshly-seeded entries. On a persistent
DB, bootstrap is skipped and resume walks whatever the agent has built
up. Either path ends in the same place: every persistent entity has a
DB row AND a live var.

### <a id="analyzer-cache-load"></a>Analyzer-cache load ^analyzer-cache-load

Before any agent form can be evaluated, the bootstrap-CLJS compile-state
needs `:cljs.analyzer/namespaces 'cljs.core` populated with cljs.core's
defs. Without this, every unqualified core reference (`reduce`,
`map`, `inc`, even the `@` reader macro's underlying `deref`) compiles
to `cljs.user.<name>` (undefined) and fails at runtime.

`cljs.js/empty-state` calls `(dump-core)` which leaves cljs.core
entry's `:name` set but `:defs` empty. Shadow's `boot/init` then
SKIPS loading the real ~712KB `cljs.core.transit.json` analyzer
cache because its loader filter at
`shadow.cljs.bootstrap.node:104` reads "`:name` is set → already
loaded → skip." Net result: the analyzer can't resolve any
unqualified core var.

`seon.eval/init-bootstrap!` works around this by calling
`cljs.js/load-analysis-cache!` for EVERY namespace shadow emitted
into `out/bootstrap/ana/`. The helper `load-all-analysis-caches!`
walks the dir, reads each `*.transit.json`, and loads it into the
compile-state. After init,
`(count (:defs (get-in @compile-state [:cljs.analyzer/namespaces 'cljs.core])))`
should return ~980; and any namespace listed in `shadow-cljs.edn
:bootstrap :entries` is similarly analyzer-visible (e.g.
`cljs.test`, `clojure.set`, `clojure.string`, `clojure.walk` after
the entries expansion that landed alongside this fix).

The discover-and-load-all shape replaced an earlier hand-coded
load list (`[cljs.core cljs.core$macros]` only). Without the
walk, every future expansion of `:bootstrap :entries` would have
to remember a second edit — fragile. Now the bootstrap output is
the single source of truth.

This is invisible from the spec's design but load-bearing for the
eval surface. The `truly-undeclared?` resolver
(`src/seon/eval.cljs:158`) leans on the analyzer having the right
view of bundled namespaces to short-circuit false-positives; if
the cache load fails silently, every form rejects with
`undeclared-var: cljs.user/<core-fn>`.

### Bootstrap phase (runs only when DB is empty)

The substrate seeds the DB from a build-time artifact: an ordered
vector of entity maps (`resources/seon/bootstrap.edn`), emitted by the
substrate's own build process from the same source the runtime was
compiled from.

```clojure
;; resources/seon/bootstrap.edn (shape; ordered for single-transact)
[{:seon.ns/name   :seon.render.default
  :seon.ns/source "(ns seon.render.default (:require [seon.schema :as schema] [seon.db :as db]))"}
 ...
 {:seon.schema/key :seon.render/ai  :seon.schema/source "(schema/register! ::ai :string)"}
 ...
 {:seon.fn/sym "seon.render.default/render-fn"
  :seon.fn/ns  :seon.render.default
  :seon.fn/source "(defn render-fn ...)"}
 ...
 {:seon.test/sym "seon.render.default/render-fn-test"
  :seon.test/target [:seon.fn/sym "seon.render.default/render-fn"]
  :seon.test/source "(deftest render-fn-test ...)"}
 ...
 ;; Section entities — the default context layout
 {:seon.ctx/name :system  :seon.ctx/priority 10 :seon.ctx/fn 'seon.render.default/system-section}
 ...]

```

The bootstrap is a single `(d/transact! conn bootstrap)`. Datahike
resolves intra-tx lookup refs (e.g. `:seon.test/target [:seon.fn/sym
"…"]`) inside the transaction, so dependency order in the vector is
enough — no special multi-pass logic.

After bootstrap the database is the system. The substrate code is
identical to anything the agent might write: ordinary persisted
entities the agent can edit, override, or — for non-load-bearing
things — forget.

### Resume phase (runs every boot)

Restore runtime state by walking the persistent entities and evaling
each in creation order. On a freshly-bootstrapped DB this is what
makes the substrate "real" as vars; on a persistent DB this restores
the agent's accumulated work.

**No dep DAG, no analyzer walk.** Datahike tx-ids are strictly
monotonic, and an entity can only reference another via lookup-ref
if the referenced entity already exists at write time. So creation
order IS a valid topological order by construction. Replay is just
"sort by asserting tx-id and eval each entity's `:source`."

1. Compiled CLJS substrate is loaded.
2. Query all persistent entities along with the tx that asserted
   them: `[:find ?e ?source ?tx :where [?e :seon.fn/source ?source ?tx]]`
   (and similar for `:seon.schema`, `:seon.test`, `:seon.ns`).
3. Sort by `?tx`. That's the order the agent created them in.
4. For each entity, eval its `:source` in the right ns. The replay
   transact carries `:tx-meta {:seon.eval/id <id>
   :seon.eval/replay? true}` so the eval entry and the persistent
   datoms share a tx-id, and the renderer can distinguish replay
   evals from session evals.
5. If an eval throws during replay, its eval-log entry has
   `:ok? false`. The renderer surfaces it as a warning ("X failed
   to replay this session — fix or forget") with the source available
   for inspection. The entity stays in the DB unchanged; nothing is
   retracted automatically.

The first eval transacted by the resume phase carries an
`:seon.eval/resume-marker? true` attr (cheap signal, default-false so
absent on every other entry). "This session's evals" =
"entries since the most recent resume marker." That's the only
"session" demarcation the system needs.

Eval log itself is not replayed. Scratch is scratch.

### Resuming an older DB on a newer runtime

The intended contract: a database from runtime version V can be opened
by runtime version V' (V' ≥ V) and the agent sees their state restored.
Mechanisms supporting this:

- The substrate code on disk is whatever V' ships; the DB carries
  whatever source it carries; replay eval's the DB source, overwriting
  any substrate var the agent had customized.
- New substrate fns/schemas/tests that V' adds and the DB doesn't have
  yet: re-run the bootstrap procedure for entries not already present
  (lookup by identity attr; transact only missing ones).
- Substrate fns the agent had overridden in V's DB stay overridden in
  V' — agent edits beat upstream changes. Conflicts surface as
  warnings.
- Datahike attribute-schema changes are constrained: `:db/valueType`
  is immutable; `:db/unique` cannot be removed; `:db/cardinality
  :one→:many` is allowed only when no `:db/unique` is set. The
  baseline attribute schema in `seon.schema/register!` calls should
  be treated as non-negotiable across versions; extensions add new
  attrs, never re-type existing ones. See [[#^d1]].

## MVP scope

### In

- The database attributes defined above (`:seon.ns/*`, `:seon.fn/*`,
  `:seon.schema/*`, `:seon.test/*`, `:seon.eval/*`, `:seon.ctx/*`).
- `seon.repl/eval-batch!` — runs the forms-and-comments
  read/eval/transact pipeline (rewrite-clj for parse; see "Parse:
  forms-and-comments"). Trailing comments without a following form
  become a thinking-only eval entry. Bare symbols are forms.
- `seon.repl/forget!` + `seon.schema/unregister!`.
- `seon.render.default/*` — six default section functions
  (system, related-ns, current-ns, warnings, recent-evals, prompt)
  plus the `truncate-edn` helper (`pretty-ai` already exists). The
  prompt section renders the trailing `:current.ns=> ; turn N` line
  so the agent's view ends like a real Clojure REPL prompt.
- `seon.render/explain-section`, `reset-defaults!`.
- Per-eval timing + Tufte hook per "Self-instrumentation"; optional
  `perf-section` formats Tufte stats on demand.
- Current time in `system-section`. User-timezone lookup is post-v1
  (see "Out" below).
- **Targeted test auto-run** ([[#^d4]]): every eval whose tx
  asserted datoms on a `:seon.fn` entity post-fires the tests that
  target that fn (reverse ref via `:seon.test/target`). Test
  entities' `:last-passed-at` / `:last-failed-at` / `:last-failure`
  update. The `failing-test-warning` predicate surfaces any test
  whose latest run failed.
- **Spec-violation warning** ([[#^d2]]): when a schema is
  redefined, validate existing data against the new shape;
  violations become warnings. No reject.
- **Def-not-persisted warning** ([[#^d3]]): bare `(def x …)`
  outside `defn`/`schema/register!`/`deftest` surfaces a warning.
- Bootstrap + resume phases per "Boot sequence".
- Per-form independent transacts (partial-success preservation).
- Eval classification implicit via the history query over each
  eval's tx + `:ok?` boolean + cross-eval `:ns` comparison — no
  classifier enum to maintain.

### Out

- WASM-side wiring (M2 — the WIT `eval-form` export calls into this; pipeline
  itself runs in V0 Node pod first for testing)
- Multi-agent ownership (single-agent assumption for MVP)
- Baseline reconciliation (m6 capability — comes after MVP)
- Result-value retention across sessions (eval-ids reference values that
  don't survive pod restart; agent re-evals if needed)
- Token budgeting for the renderer (no compression beyond truncate-edn)
- Auto-run on **dependent**-change (i.e. fn B's tests fire when fn A
  that B depends on changes). MVP runs only tests targeting the
  directly-modified fn; transitive triggering follows. See [[#^d4]].
- Caching of section outputs (recompute every turn for MVP)
- User-timezone lookup. The system-section surfaces *pod* time and
  timezone — that's what `js/Intl.DateTimeFormat` resolves to inside
  the wasmtime/Node host. The *user's* timezone lives outside the
  pod (browser, env var, or an attr the user transacts onto their
  agent entity). Post-v1: pull `:seon.user/timezone` from the agent
  entity when present, format `now` in that zone; until then, the
  agent and user negotiate timezone in conversation if it matters.

### Acceptance criteria for MVP

A new agent session can:

1. See a default-rendered context with the relevant sections present
   (empty sections suppressed), including current pod-time in the
   system tile.
2. Eval a multi-form batch including a schema, defn, and test; see
   each form's `:duration-ms` rendered next to its eval-id.
2a. Submit a response that uses `;;` multi-line markdown
   (`;; ## Plan\n;; - step one\n;; - step two`) between forms; see
   the comments captured as `:seon.eval/narration` on the next
   form's entry, with the markdown formatting preserved verbatim.
   Trailing `;;` comments after the last form land as a
   thinking-only eval entry.
2b. Eval a bare symbol that resolves (e.g. just `analyze` after it
   was defined); see its value returned. Eval a bare symbol that
   doesn't resolve; see the natural unbound-symbol error in the
   eval log (`:ok? false`, kind `:compile`).
3. **Partial success**: send 10 forms where one fails; see 9 successes
   persisted and 1 error reported in the eval log.
4. `(in-ns 'seon.foo)` to switch namespaces mid-batch and see subsequent
   forms land in the new ns; see the next-turn context now focused on
   `seon.foo` with `seon.trading` (or wherever they came from) demoted
   to `:related-ns` digest.
5. See the new entities in the next-turn context, plus warnings for any
   missing pieces.
5a. Eval a deliberately slow form (e.g. `(do (js/Date.now)
   ; busy-wait 600ms; (js/Date.now))`); see the slow-eval warning
   surface in the warnings tile on the next render.
6. Rewrite a section function (e.g. `recent-evals-section` to a compact
   one-line-per-row form); see it applied on the next turn.
7. Forget a function and see dependents flagged.
8. Break a section function; see `pretty-ai` engage for that section
   with a warning.
9. `(seon.render/reset-defaults!)`; see defaults restored.
10. Restart the pod; see all persistent entities re-eval'd in the correct
    order; see the eval log retained as readable scrollback.

## Open questions / prior art

### Prior art located

- **Smart EDN truncator.** Not present. The closest is
  `seon.render.default/try-read-edn` (in `src/seon/render/default.cljs`)
  which slices the raw string at 400 chars — not structure-preserving.
  The new `truncate-edn` is a fresh helper for `seon.render.default`.
- **Codebase indexer.** Already exists JVM-side in
  `src/seon/graph/ingest.clj` + `src/seon/graph/analyzer.clj`: uses
  clj-kondo to extract every `defn` / `var` / schema-as-spec
  (`:seon.spec/*`) / ns-dep into datahike. The bootstrap-emission step
  is conceptually the CLJS-side equivalent — note that the existing
  attrs are `:seon.fn/qualified-name` / `:seon.fn/namespace` /
  `:seon.spec/key`, not the `:seon.fn/sym` / `:seon.fn/ns` /
  `:seon.schema/key` this spec uses. The CLJS-pod attrs run as a
  separate (parallel) family of entities for now.
- **Renderer specificity dispatch (CLJ-only).** Implemented in
  `src/seon/render.clj`: `find-renderer` (L133), `resolve-renderer`
  (L202), `namespace-proximity` tiebreak (L112). Queries
  `seon.graph.query/functions-with-output-key` to find candidates,
  filters to those whose required input keys are a subset of the
  data's keys, ranks by `(count required-keys)` descending. The
  post-MVP per-entity dispatch can lift this directly into the CLJS
  pod once the graph indexer is mirrored there.
- **CLJS renderer dispatch (today's surface).** Symbol-only slot
  resolution in `src/seon/render.cljs`: `ai-dispatch` /
  `html-dispatch` resolve a `:seon.render/ai` slot to a fn (via
  `resolve-symbol` against bootstrap compile-state OR `globalThis`)
  and fall through to `seon.render.default/pretty-ai` on miss. This
  is the surface the MVP composer uses.
- **Property-test infrastructure.** Malli 0.20.0 + test.check 1.1.3
  are in `deps.edn`. `malli.generator/generate` is reachable today.
  There is no existing property-test runner wrapper in
  `src/seon/test/*` — that helper still needs to be written.
- **Agent-source structural gate.** `src/seon/code.cljc` already
  checks "this is a `(defn name [{::keys [...]}] …)` form with
  `:malli/schema` metadata and namespaced destructure keys". The
  eval-batch's pre-eval gate (if we want one) reuses this directly.
- **`bin/mcp-server` — JVM-side analog of this pipeline.** A babashka
  script wired into Claude Code's MCP. It implements many of the
  patterns this spec describes, against the JVM nREPL instead of the
  CLJS pod:
  - Output cap: `max-eval-output-chars 2000`, `truncated-preview-chars
    1500`. Keeps the trailing portion (last N chars) rather than the
    head — different tradeoff from the spec's structure-preserving
    `truncate-edn`, both valid.
  - Opt-out prefix: forms prefixed with `#_:full ` skip truncation
    entirely (`full-output-prefix`, L92). Port this idea into
    `eval-batch!` so the agent can demand the full value on demand.
  - Result auto-save: `wrap-code-with-autosave` (L461) wraps the
    user's code so the value lands in `@user/repl-<session>` under a
    content-hash key (`:r-<hash>`). The rendered output ends with
    `;; stored as :r-1234 in @user/repl-abc1`. Same intent as the
    CLJS pod's `stash-result-raw!`, different storage.
  - Concurrent-eval guard: `orchestrator-eval-state` CAS prevents two
    evals from racing on the same nREPL session.
  - AI-render fallback: `try-ai-render` (L492) calls
    `seon.render/try-render` (CLJ) for the result's data shape; if
    no renderer matches, the response ends with a suggestion of
    which `-request/-response` specs to add. Same shape the
    post-MVP per-entity dispatch enables on the CLJS side.
- **Tufte (CLJS profiler).** Same `com.taoensso/*` family already in
  `deps.edn` (Timbre + Nippy). `taoensso/tufte` works in CLJS,
  exposes `(p ::id body)` / `(profile {…} body)` / `format-pstats`
  — exactly the surface the "Self-instrumentation" section needs.
  Add to `deps.edn`, hook into the existing
  `:seon.dev/instrumentation` registration so every Malli-validated
  fn gets a `tufte/p` wrap at the same boundary. Existing
  instrumentation code: CLAUDE.md "Function Instrumentation" calls
  out the Integrant key; the wrapper is the single seam where this
  bolts on.

## Out-of-scope but adjacent

- **Benchmarks under WASM**: `pod-host/datahike-harness` workloads
  ported to CLJS. Follows MVP.
- **Multi-agent**: when does ownership matter? `:seon.fn/owner-agent`
  attribute is the next addition; MVP single-agent.

## Decision details

The "Decisions pending" dashboard at the top is the index. Each item
below is the full discussion. Anchor IDs are stable across edits;
refer by id. Each heading uses both an HTML anchor (for GFM) and an
Obsidian block-id (for `[[#^dN]]` links in this vault).

### <a id="d1"></a>D1 — Older DB on newer runtime upgrade strategy ^d1

Sketched but not designed: detect substrate version delta, merge
missing-from-DB bootstrap entries (lookup by identity), surface
agent overrides that conflict with the new substrate as warnings
with diffs. Out of MVP scope but the data model must permit it.

Solve [[#^d10]] first — once the substrate analyzer emits a clean
ordered bootstrap vector, upgrades follow naturally (diff old
vector against new; transact the additions).

### <a id="d2"></a>D2 — Per-kind redefinability rules ^d2

The three persistent kinds have different redefine rules because
they have different relationships to stored data:

**Specs (`:seon.schema/*`).** Strict.

- No data uses the spec yet (no `[?e ::foo _]` datoms) → replace
  freely.
- Data exists AND the change is **accretive** (add optional key;
  loosen constraint; widen union; add to enum) → replace.
- Data exists AND the change is **breaking** (remove key; narrow
  type; tighten constraint; change cardinality; remove from enum) →
  **reject**. Eval entry is `:ok? false` with a clear error showing
  which entities would be invalidated. The agent's recourse is
  `seon.repl/remove-spec ::foo` first (which is itself rejected if
  data uses it; explicit migration is the only path).
- Underlying datahike attribute type (`:db.type/string` etc) is
  immutable in either case. Spec changes that would require a
  datahike-level retype are rejected with that specific message.

**Functions (`:seon.fn/*`).** Flexible.

- Always allowed to redefine. The agent is iterating.
- Targeted tests auto-run on the new definition ([[#^d4]]); failing
  tests surface as warnings.
- Callers that reference the fn keep working (the var binding
  updates).
- No data-validity check — fn definitions aren't stored data, just
  source.

**Tests (`:seon.test/*`).** Flexible.

- Always allowed to redefine.
- The new test runs on the next define-or-redefine of its target fn
  (or immediately on redefine of the test itself, since redefining
  a test = re-eval'ing it = same trigger).

The spec instrumentation layer (Malli runtime validation per
CLAUDE.md "Function Instrumentation") stays on always. After a
spec redefine, the next call to any fn using that spec will throw
at the call site if shapes don't match — that's the in-call
feedback channel. After a fn redefine, targeted tests fire. After
a test redefine, the test fires. All three give immediate
feedback without the agent asking.

### <a id="d3"></a>D3 — `(def …)` not-persisted warning (no regex) ^d3

Agents reach for `(def !x (atom …))` because of a known
bootstrap-CLJS gotcha (bare-value defs don't resolve across
eval-str calls; see `src/seon/eval.cljs` opening docstring). The
defs eval fine but **aren't persisted** — pod restart loses them.

Detection uses the parsed form, not a regex. Since rewrite-clj
already gives us the form structurally, the check is:

```clojure
(defn- def-not-persisted? [parsed-form]
  (and (seq? parsed-form)
       (= 'def (first parsed-form))
       (symbol? (second parsed-form))))

```

That's it. `def` as the head symbol, a symbol as the name. `defn`
doesn't match because `defn` isn't `def`. `(let [x …])` doesn't
match. No regex.

Warning predicate then queries recent evals where `:source` was a
plain `(def …)`, cross-checks against persisted entities'
`:source` for references to the var name, and emits two tiers:
`:warn` (sitting around) and `:error` (a persisted fn's source
references it; restart will break).

Dependent-finding ALSO uses the AST, not text search: each
`:seon.fn/source` parses via rewrite-clj; we walk the form looking
for symbol-references that match the orphan `def`'s name. (Same
analyzer surface as [[#^d8]] / [[#^d10]].)

### <a id="d4"></a>D4 — Targeted test auto-run on every define / redefine ^d4

Tests run automatically every time a function is defined or
redefined — and ONLY the tests targeting that specific function.
The agent never has to say "run tests." If the tests pass, the
warnings tile says nothing. If any fail, the warnings tile shows
a summary of what broke, with the full failure output reachable
via a runtime var the agent can dig into.

**Triggering.** A `:seon.eval` entry whose tx asserted datoms on a
`:seon.fn` entity fires a post-eval hook. The hook queries every
`:seon.test` entity whose `:seon.test/target` ref resolves to that
fn. Run each. Update each test's `:last-passed-at` /
`:last-failed-at` / `:last-failure`.

**Why this works cheaply.** Tests target one fn each via
`:seon.test/target → :seon.fn`. The reverse index is one datalog
query. Most fns have a couple tests. Post-eval cost is "run the
few tests for the one fn that just changed."

**Runtime var stash.** Full output of the test run lives on
globalThis under a stable id (same mechanism as eval-id results).
The agent fetches via `(result :<test-run-id>)` if they want to
see assertion-by-assertion detail. The warnings tile just shows
the summary: "3 of 4 tests passed for analyze; 1 failed
(analyze-empty-ticker)" + the failure's `:last-failure` message.

**Warning predicate.** `failing-test-warning` queries every test
where `:last-failed-at > :last-passed-at` (or `:last-passed-at`
is nil). Each becomes one warning. Pure derivation from
current test-entity state.

**Manual runs.** `(seon.test/run-all)` runs every persisted test.
`(seon.test/run-fn 'fn-sym)` runs just that fn's tests.
`(seon.test/run 'test-sym)` runs one by name. But the common
case — write a fn, tests run, warnings render next turn — needs
nothing from the agent.

**Instrumentation interplay.** Spec instrumentation (Malli
runtime validation per CLAUDE.md "Function Instrumentation")
stays on always. That's the in-call feedback: "you called fn X
with wrong shape → throws at the call site." Test auto-run is
the post-define feedback. Both always on; the agent doesn't ask
for either.

In MVP, default-on.

### <a id="d5"></a>D5 — `(forget!)` for namespaces ^d5

Currently `(forget! 'sym)` works on functions / schemas / tests via
their identity attr. Should it also work on a whole `:seon.ns/*`
entity? Semantics:

- Retract the `:seon.ns` entity.
- Cascade: retract every `:seon.fn`/`:seon.schema`/`:seon.test`
  entity whose ns-prefix matches.
- `ns-unmap` each member; `goog.object/remove` the ns namespace
  object from globalThis.
- The whole cascade transacts in one tx with `:tx-meta
  {:seon.eval/id <id>}` so the retractions and the eval entry
  share a tx-id.

Useful when the agent decides an entire experiment-namespace is
trash. MVP-include or defer?

### <a id="d6"></a>D6 — Explicit remove-spec / remove-fn / remove-test ^d6

Three explicit verbs, one for each persistent kind. Each takes a
map specifying what's being removed and refuses (with a clear
warning) when the removal would break invariants.

```clojure
(seon.repl/remove-spec {:seon.schema/key ::ticker})
;; -- if no datoms use the spec: retract the :seon.schema entity,
;;    unregister from the Malli registry.
;; -- if data exists: refuse with the list of affected entities.
;;    "Cannot remove ::ticker; 12 entities use it. Migrate or
;;     accept-cascade-retract first."

(seon.repl/remove-fn {:seon.fn/sym "seon.trading/analyze"})
;; -- retract the :seon.fn entity, ns-unmap the var, retract any
;;    :seon.test entities whose :target was this fn (the targets
;;    no longer exist; the tests are stale).
;; -- the retracting transact carries :tx-meta {:seon.eval/id <id>},
;;    so the eval entry and the retraction datoms share a tx-id.

(seon.repl/remove-test {:seon.test/sym "seon.trading/analyze-example"})
;; -- retract the :seon.test entity, ns-unmap the deftest var.

```

Explicit verbs are clearer to the agent and easier to teach in the
system-section (one example each).

The reversibility classifier in the "Forget" section is used by the
targeted-test auto-run and the warning predicates to explain what
would break if you removed something, not as a do-anything `undo`.

### <a id="d7"></a>D7 — `<name>-example` test convention ^d7

A function persists as `:seon.fn/*`. The warnings tile runs a
no-test predicate at render time — for every `:seon.fn`, check
whether any `:seon.test/target` resolves to it; if not, emit a
warning. Nothing about "no test coverage" is stored on the fn
entity; the warning is the result of the query against the current
graph. The moment a test targeting the fn lands, the next render
omits the warning.

Convention to clear the warning fast:

For a fn `seon.trading/analyze`, the agent writes a test named
`seon.trading/analyze-example` that exercises the documented happy
path. The pattern is:

```clojure
(defn analyze
  "Compute the trading signal for a ticker."
  {:malli/schema [:=> [:cat ::analyze-req] ::analyze-resp]}
  [{::keys [ticker]}]
  {::signal :hold ::confidence 0.5})

(deftest analyze-example
  (is (= {:seon.trading/signal :hold
          :seon.trading/confidence 0.5}
         (analyze {:seon.trading/ticker "AAPL"}))))

```

Why `-example`: it's a documented use-case the agent (and future
agents reading the persistent entities) can look at. It demonstrates
shape, intent, and at least one passing input. Edge-case tests live
under other names; `*-example` is the canonical "this is what calling
this fn looks like."

The warning predicate looks for `:seon.test/target` matches; it
doesn't care about the name. The convention is for human + LLM
readability, not enforcement.

### <a id="d8"></a>D8 — Reference-graph attrs ^d8

Confirming the schema shape:

```clojure
;; Function entity gains reference attrs
::seon.fn/input-spec   :seon.db/ref                          ; → :seon.schema entity
::seon.fn/output-spec  :seon.db/ref                          ; → :seon.schema entity
::seon.fn/refs         [:vector :seon.db/ref] {:optional true}  ; other :seon.fn entities this fn calls

;; Test entity already has
::seon.test/target     :seon.db/ref                          ; → :seon.fn entity

```

`:seon.fn/refs` is populated at define-time by the analyzer walk:
parse `:seon.fn/source` with rewrite-clj, walk the AST, collect
every qualified symbol that resolves to a `:seon.fn`, emit those
as refs. Reverse-index via datalog gives "who calls X" for free.

Spec dependencies (a `:seon.schema/source` like
`(schema/register! ::foo [:map [::bar :string]])` references
`::bar`) are similarly extracted — the analyzer walks the
registered Malli schema for keyword references to other registered
schemas. New attr:

```clojure
::seon.schema/refs  [:vector :seon.db/ref] {:optional true}  ; other :seon.schema entities this schema uses

```

The reference graph is what makes the targeted-test auto-run
([[#^d4]]) and the spec-violation check ([[#^d2]]) cheap: every
"who is affected by changing X" question is one datalog query
over the reverse index.

### <a id="d9"></a>D9 — Forgiving parse recovery ^d9

The parser surface needs to be helpful when the agent writes
something partially-broken. Current spec already says
"continue with the next top-level form" on parse-fail (in §"Parse
failures"); this decision is about how aggressively we recover.

Proposal:

- rewrite-clj parses the top-level form; if it throws, capture the
  source up to the next balanced top-level boundary as the failing
  chunk's `:source`.
- Recovery boundary detection: scan forward token-by-token tracking
  paren/bracket/brace depth; when depth returns to 0 AND a newline
  follows, that's the next boundary.
- The failing chunk becomes one `:ok? false` eval entry with the
  parse error as `:error`. Subsequent forms parse normally.

A more ambitious recovery — try to extract sub-forms from a chunk
that contains both valid and invalid pieces — is out of MVP scope.
The simple "skip to next boundary" path covers the common case
(agent wrote a typo in form N; forms N+1, N+2 are fine).

Open question: when the recovery boundary detection itself runs
out of input (the agent's whole response after the broken form is
an unclosed paren spanning EOF), the whole tail becomes one
`:ok? false` entry. Is that the right behavior? I think yes
(the agent sees one error and knows where to look) but worth
confirming.

### <a id="d10"></a>D10 — Topological bootstrap emission ^d10

The substrate is compiled CLJS. To seed the agent's DB on first
boot, we need a `bootstrap.edn` containing every substrate
`:seon.ns` / `:seon.schema` / `:seon.fn` / `:seon.test` /
`:seon.ctx` entity in correct dependency order. The "topological"
problem: an entity can't reference (via `:seon.fn/refs` or a
spec-key reference) something that hasn't been transacted yet.

Solve in two passes:

1. **Walk the substrate source.** For each `.cljs` file we ship,
   parse with rewrite-clj, extract every top-level `(defn)` /
   `(schema/register!)` / `(deftest)` / `(ns …)` form. Build a
   provisional entity for each, with a placeholder `:refs` list.
2. **Resolve references and toposort.** Walk each entity's
   parsed source AST; turn each name-reference into a ref to the
   provisional entity. Toposort by depends-on. Output the ordered
   vector to `resources/seon/bootstrap.edn`.

If we do (1) and (2) right, indexing future agent work is the
same code — the substrate is just data the analyzer happens to
see first. No special "compile vs runtime" path.

Solve this BEFORE worrying about [[#^d1]] (older-DB-on-newer-runtime).
Once we have the analyzer walk producing a clean ordered vector,
upgrades follow naturally (diff old vector against new; transact
the additions).

### <a id="d11"></a>D11 — Per-agent ctx set as a multi-ref on the agent record ^d11

The agent record is the hub. Each agent owns a cardinality-many
`:seon.agent/ctx` vector of refs to `:seon.ctx` entities. The
section entities themselves carry no `:agent` field — ownership
is reverse-implied by the ref direction.

```clojure
;; on the agent
::seon.agent/ctx  [:vector :seon.db/ref]    ; → :seon.ctx, cardinality-many

;; on the ctx entity (no :agent attr — it's owned via the back-ref)
::seon.ctx/priority  :long
::seon.ctx/fn        :symbol                ; ns-qualified, resolves to a section fn

```

Why this shape:

- **The agent's record IS the index.** `(seon.db/entity [:seon.agent/id
  id])` returns the agent map; `:seon.agent/ctx` resolves to the
  ordered vector of ctx entities. One pull, no joins.
- **Customization is "transact a different ref onto my own record."**
  Agent writes their custom fn in their home ns (`seon.agent.<id>`),
  transacts a new `:seon.ctx` entity, and either replaces an existing
  ref in `:seon.agent/ctx` or appends to the vector. No global
  side-effect on other agents.
- **Recovery from a borked context.** Reset is "rewrite
  `:seon.agent/ctx` to the substrate defaults" — exactly one transact
  on the agent record. No cleanup of orphan section entities needed
  for correctness (the agent's ctx vector no longer references them);
  GC them later if you want.
- **No global registry.** Section entities are per-agent by
  construction. Two agents can have completely different ctx sets
  with zero interference.

#### V1 default — substrate fns, no dispatch

V1 ships with default ctx entities pointing at fns in the
**`seon.agent`** namespace (substrate-shipped, shared across
agents). The substrate's bootstrap creates one default ctx set
per agent at agent-create-time:

```clojure
;; substrate bootstrap creates these as part of (seon.agent/create! …)
[{:seon.ctx/priority 10 :seon.ctx/fn 'seon.agent/system-section}
 {:seon.ctx/priority 20 :seon.ctx/fn 'seon.agent/related-ns-section}
 {:seon.ctx/priority 30 :seon.ctx/fn 'seon.agent/current-ns-section}
 {:seon.ctx/priority 40 :seon.ctx/fn 'seon.agent/warnings-section}
 {:seon.ctx/priority 50 :seon.ctx/fn 'seon.agent/recent-evals-section}
 {:seon.ctx/priority 99 :seon.ctx/fn 'seon.agent/prompt-section}]
;; agent gets {:seon.agent/ctx [<ref> <ref> <ref> <ref> <ref> <ref>]}

```

Agent customizes by writing their own fn in `seon.agent.<id>`
(their home ns) and re-pointing one ref:

```clojure
;; in their home ns
(defn my-compact-evals [input] ...)

;; transact onto own record
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id "AbCdEfGh1234"
     :seon.agent/ctx [<refs to all but recent-evals>
                      <ref to new section with :seon.ctx/fn 'seon.agent.AbCdEfGh1234/my-compact-evals>]}]})

```

#### V1 default — section fn signature

A section fn takes one map and returns a string. The map is the
agent's `:seon.render/system-input` (no change from earlier spec):

```clojure
{:seon.db/db    <datahike db>
 :seon.agent/id <agent-id string>}

```

Section fn handles the rest (queries DB, formats, returns string).
Empty string omits the section.

#### Dynamic dispatch is V2 / V3

The earlier spec described "per-entity dispatch by Malli specificity"
as a post-MVP enhancement. V1 explicitly defers that AND defers any
sophisticated `:seon.render/ai` / `:seon.render/html` symbol-slot
resolution. V1 has:

- A direct vector of section refs.
- Section fns that return strings.
- One composer that walks the vector and joins.

V2 layers per-entity-shape dispatch on top of that (sections can
return entity maps; a registry resolves a specific render-fn for each
shape). V3 explores cross-agent collaboration features, profile
dashboards, etc.

This split is intentional. The MVP must work without any dynamic
resolution infrastructure. If everything compiles to a known symbol
that's part of `:seon.agent` or `seon.agent.<id>`, the contract is
"resolve the symbol at call time" — the simplest possible mechanism.

## <a id="known-issues"></a>Known issues

Implementation problems known to me as of the latest REPL-verification
round. Not spec decisions — bugs / quirks to triage.

### KI-1 — `seon.db/transact!` invocation shape is non-obvious

The fn signature is `[{::keys [tx-data opts conn]}]` — single map
argument with namespaced keys. The common-mistake forms

```clojure
(seon.db/transact! @conn {:tx-data ...})       ; positional — crashes inside seon.db with
                                               ; "ILookup$_lookup$arity$3 is not a function"
(seon.db/transact! {:conn @conn :tx-data ...}) ; unqualified keys — "no conversion to symbol"

```

both fail badly. The correct call is

```clojure
(seon.db/transact! {:seon.db/conn @conn
                    :seon.db/tx-data [...]})

```

…OR rely on the dynamic `seon.db/*conn*` and omit `:conn`. The
crash on form 1 takes the whole Node process down because the
TypeError isn't caught at the boundary. Triage: (a) add a precondition
that throws a clean validation error on bad-shape inputs, OR (b)
accept positional `[conn tx-data]` as a backward-compatible
alternative. The current crash mode is hostile to agents who
mis-call the API.

### KI-2 — `defonce` atoms can hold pre-fix state across hot-reloads

`seon.repl/!compile-state` is `defonce`'d to survive hot-reload. When
the substrate's auto-boot ran the OLD `init-bootstrap!` (before the
analyzer-cache fix landed) and populated the atom, a subsequent
hot-reload of `seon.eval` to the NEW `init-bootstrap!` left the
stale state in place. `seon.repl/dev-init!`'s
`(or @!compile-state ...)` short-circuits on the stale atom.

Workarounds:

- Manually `(reset! seon.repl/!compile-state nil)` before
  `(seon.repl/dev-init!)`.
- Add a `^:dev/before-load` handler in `seon.repl` that nils the
  atoms (loses cached state but pays for hot-reload correctness).
- Stamp the atom with a `:seon.eval/init-version` and re-init if
  the running code's version differs.

Triage: pick one. The third option preserves cached state during
benign reloads but rebuilds when the init code itself changed.

### KI-3 — Eval error envelope is deeply nested

`seon.eval/eval` returns errors with a nested cause chain four
levels deep:

```text
:error
  :seon.error/message  "Could not eval seon.dynamic"
  :seon.error/cause
    :seon.error/message  "<wrap>"
    :seon.error/cause
      :seon.error/message  "undeclared-var: cljs.user/Let"
      :seon.error/ex-data  {:kind :compile, :seon.eval/warning-type :undeclared-var}

```

The actionable info (`:seon.eval/warning-type`,
`:seon.eval/undeclared`) lives at the bottom. The recent-evals
renderer needs to walk the chain to surface it. Worth promoting
the key fields to the top-level `:error` map so renderers don't
have to descend.

### KI-4 — Shadow watcher gets confused after multiple Node restart cycles

After ~2-3 cycles of `pkill node && node out/client/main.js`, shadow's
nREPL piggyback says "no available JS runtime" even though the new
Node process is running and the watcher is up. Recovery requires
restarting the watcher (`Ctrl-C` then `clj -M:cljs watch client`).

This blocks autonomous REPL-iteration loops where an agent might
need to restart Node programmatically. Triage: investigate shadow
runtime-tracker state; possibly an idle-timeout or a runtime-id
collision. Not blocking MVP; manual recovery is fine for now.

### KI-5 — `start-agent!` auto-boot runs init before `dev-init!`

The pod's `seon.client/start-agent!` runs at module load and uses
its own init path. If an iteration session wants a clean
`init-bootstrap!` (e.g. after editing eval.cljs), `dev-init!`
won't run a fresh init because `!compile-state` is already
populated by start-agent's path (see KI-2). Triage: align
start-agent! with `seon.repl/ensure-bootstrap!` so they share the
same atom + same init path, OR decouple `!compile-state` between
the iteration surface and the agent loop entirely.

### KI-6 — `npm install ws` was required for first run

Shadow's hot-reload websocket import expects the `ws` module but
it wasn't in `package.json`. Fresh checkouts will hit this. Either
add `ws` to `package.json` dev deps, or document the install in
the REPL workflow.

============================================================
YOUR DELIVERABLE
============================================================

Please return a structured critique with these eight sections. Use Markdown headings (## a) ..., ## b) ...).
Be specific, cite line numbers / attr names from the spec, and propose concrete fixes.
Total target length: 2000–5000 words. Prioritize concrete, actionable directives over generalities.

## a) Graph-modeling critique
What's SQL-flavored in this spec that should be graph-flavored? What entities should exist that
don't? What entities exist that shouldn't? Look for: bag-of-attributes entities, missing
intermediate entities that would let you join, attrs that should be refs, refs that should be
components.

## b) Observability / playback gaps
To 'play back turn N' as a single pull, what entities and refs do we need? Currently the spec
has no first-class `:seon.agent.message` entity defined (only `:seon.message` referenced in
passing), and there is no stored snapshot of 'what the LLM saw' per turn (the full rendered
prompt at that moment). Confirm or contradict that as the highest-priority gap. List every other
gap that prevents single-pull playback.

## c) Backref vs forward-ref strategy
The spec backref-joins agent-message, agent-eval, agent-log via :seon.agent.message/agent etc.,
and hides them behind `seon.agent/my-messages`-style helper fns. Senior Datomic perspective:
is this the right tradeoff? Should the agent record have explicit forward-ref vectors for the
most-recent-N tails to make pulls one-step? What's the most discoverable pattern for an LLM
agent that needs to write its own pulls?

## d) Helper-function inflation
The spec ships ~6 default section fns + several `seon.agent/my-*` helpers. The user wants the
agent to learn graph patterns directly, not memorize wrapper-fn-per-thing. What's the minimum
set of helpers that earns its keep? Where should we strip back to raw pulls / raw queries that
the agent reads in their context once and learns?

## e) Rendering model
Section entities point at symbols; the composer resolves at call time. Is this the right
separation in a graph DB? Should the rendered output also persist as an entity (for playback)?
Or is it always re-derivable from the DB at the relevant tx? Take a strong opinion.

## f) Tx-meta payload
What should EVERY transaction carry as tx-meta so a future query can answer:
  'what turn / what message / what agent / what eval-id caused this datom?'
List the recommended tx-meta attrs with Malli types. Make sure the names follow the seon
namespacing convention. Show the smallest set that lets you reconstruct causality.

## g) Concrete schema rewrite
Given everything above, propose the v1 entity schemas as datahike-flavored register! calls the
spec should ship with. Cover at least: :seon.agent, :seon.agent.session, :seon.agent.turn,
:seon.agent.message, :seon.agent.eval, :seon.agent.log, :seon.ctx.section, :seon.ctx.snapshot
(if you think it should exist), :seon.tool.call, :seon.tool.result. For each attr give:
  - the type (:string, :int, :inst, :seon.db/ref, etc.)
  - cardinality (:db.cardinality/one or /many) if non-default
  - :db/isComponent where appropriate
  - identity props where appropriate
  - one-line rationale
Prefer fewer entities with more refs over many narrow entities.

## h) Anti-patterns to forbid in CLAUDE.md / spec
What would a junior Datomic modeler do wrong here that we should explicitly forbid in writing?
List 5–10 anti-patterns specific to this codebase (not generic database advice). Each should
include: the bad pattern, why it bites in this context, and the correct alternative.

````

## Gemini's raw response (verbatim, no editing)

````markdown
Ripgrep is not available. Falling back to GrepTool.
I have completed the review of the `agent-runtime` data model. The following critique identifies structural gaps, proposes a more idiomatic Datalog-first architecture, and provides a concrete schema rewrite to satisfy the goal of "capturing everything" for seamless session playback and agent discovery.

## a) Graph-modeling critique

The current spec and V0 implementation treat the database as a **side-effect log** (SQL-flavored) rather than a **connected program graph** (Datomic-flavored).

1.  **Composite Strings vs. Entity Nodes:**
    *   **The Problem:** Attributes like `:seon.fn/sym "seon.trading/analyze"` use string-concatenation for namespacing. This forces the agent to use regex or string-prefix matching to find "all functions in this namespace."
    *   **The Fix:** Model **Namespaces** as first-class entities (`:seon.ns/name`). A function entity should have a `:seon.fn/ns` ref to a namespace entity and a `:seon.fn/name` symbol. This allows joining on the namespace node to find dependencies, metadata, and documentation in one recursive pull.
2.  **The "Turn" as a Scalar, not an Entity:**
    *   **The Problem:** The "turn" currently exists only as an integer counter on the agent and a field on the eval. It has no identity.
    *   **The Fix:** A **Turn** is a load-bearing lifecycle event. It is the container for "what the agent saw" (prompt), "what the agent thought" (narration), and "what the agent did" (messages/evals). Without a `:seon.turn` entity, you cannot "Pull Turn 5" to see the causality bundle.
3.  **Bag-of-Attributes for Agent State:**
    *   **The Problem:** `turn-count` and `turns-since-user` are scalars on the agent record.
    *   **The Fix:** These are transient properties of the **current session**. Moving them to a `:seon.agent.session` entity prevents the agent record from becoming a dumping ground for transient counters.

## b) Observability / playback gaps

To "play back turn N," we must be able to reconstruct the exact state of the agent's context at that moment.

1.  **The "Ghost" Prompt (Highest Priority):**
    *   The current spec derives the rendered context at turn start but **never persists it**. If the agent updates a rendering function in Turn 6, re-playing Turn 5 by re-running the renderer will produce a *different* string (or fail if the new code references data that didn't exist yet).
    *   **Fix:** Every `:seon.turn` entity MUST persist its `:seon.turn/rendered-context` as a string. This is the literal "what did the LLM see?" snapshot.
2.  **Causality Disconnection:**
    *   Currently, if an eval in Turn 5 transacts data, and `record-eval!` separately transacts the log entry, they land in **two different transactions**. There is no hard link between the "side-effect data" and the "log data."
    *   **Fix:** The `eval-batch!` loop must wrap the entire batch (or each form) in a way that captures every transaction made *during* that execution and tags them with the same `eval-id` and `turn-id` in `tx-meta`.

## c) Backref vs forward-ref strategy

The current spec relies heavily on **backrefs** (`:seon.message/agent`). While Datalog can query these via `_agent`, it is an anti-pattern for agent discovery.

1.  **Discovery via Forward Walk:**
    *   Senior Datomic perspective: If you want an agent to "immediately learn the right patterns," make the data reachable by **walking forward**.
    *   **Fix:** The Agent entity should have a cardinality-many ref to its **Sessions**. A Session has a cardinality-many ref to its **Turns**. A Turn has refs to its **Messages** and **Evals**.
    *   **Benefit:** An agent can type `(db/pull {::db/ref [:seon.agent/id "alpha"] ::db/pull-pattern '[{:seon.agent/sessions [{:seon.session/turns [*]}]}]})` and see their entire history as a nested map. This is 10x more intuitive for an LLM than writing a `:where [?m :seon.message/agent ?a]` join.
2.  **The "Tails" Problem:**
    *   Cardinality-many refs can grow too large. Use a **Session** entity to bound the history. An agent doesn't need to pull *all* messages ever; it needs to pull the *current session's* turns.

## d) Helper-function inflation

The user wants to avoid "helper-fn-per-thing." The current set of `seon.agent/my-*` helpers exists because the graph is flat and backref-heavy.

1.  **Helper Erasure:**
    *   `replies-after` → Replaced by pulling the current Turn or Session.
    *   `latest-message-role` → Replaced by `(-> session :seon.session/turns last :seon.turn/messages last :seon.message/role)`.
    *   `my-evals` → Replaced by `(-> agent :seon.agent/current-session :seon.session/turns last :seon.turn/evals)`.
2.  **The "One True Helper":**
    *   The only helper that earns its keep is one that returns the **Agent's Context Root**. Everything else should be a standard Datalog query or Pull that the agent reads once in their system-instructions and copies.

## e) Rendering model

The "symbols in slots" model is excellent for runtime dispatch but incomplete for persistence.

1.  **Derivation vs. Snapshot:**
    *   **Opinion:** Rendering should remain functional at runtime (symbols in slots), but the **output** of the composer must be persisted to the `:seon.turn` entity.
    *   **Rationale:** In a graph DB, "playback" is a data walk. If you have to "re-run code" to see what happened, you have a debugger, not an observability log.
2.  **Section Entities as Data:**
    *   The spec's idea of `:seon.ctx` section entities is correct. It makes the "layout" of the prompt something the agent can query and edit as data, rather than a hard-coded list of fns.

## f) Tx-meta payload

Every transaction in Seon should carry a **Causality Bundle**. This allows a developer (or verifier agent) to look at any single datom in history and know exactly *why* it exists.

**Recommended `tx-meta` attributes:**
- `:seon.db/agent-id` (`:string`) - Who did this?
- `:seon.db/session-id` (`:string`) - In which session?
- `:seon.db/turn-id` (`:string`) - In which turn?
- `:seon.db/eval-id` (`:string`, optional) - Which specific form caused this?
- `:seon.db/origin` (`:enum [:user :agent :system]`) - Did the agent's code do this, or was it a manual system bootstrap?

**Propagation Pattern:**
Bind these IDs to a dynamic var `seon.db/*tx-context*` during the `eval-batch!` loop. `seon.db/transact!` should automatically merge this context into the `:tx-meta` of every transaction it performs.

## g) Concrete schema rewrite

This schema replaces the loose attributes in the spec with a hierarchical, fully-connected graph.

```clojure
;; --- Agent & Session Hierarchy ---

(schema/register! :seon.agent/id            [:string {:seon.db/identity true}]) ; 12-char
(schema/register! :seon.agent/sessions      [:vector :seon.db/ref]) ; component

(schema/register! :seon.session/id          [:string {:seon.db/identity true}])
(schema/register! :seon.session/turns       [:vector :seon.db/ref]) ; component
(schema/register! :seon.session/at          :inst)

(schema/register! :seon.turn/id             [:string {:seon.db/identity true}])
(schema/register! :seon.turn/index          :int) ; 0, 1, 2...
(schema/register! :seon.turn/prompt         :string) ; The full rendered context snapshot
(schema/register! :seon.turn/messages       [:vector :seon.db/ref]) ; user msg + assistant msg
(schema/register! :seon.turn/evals          [:vector :seon.db/ref]) ; component
(schema/register! :seon.turn/status         [:enum :running :done :error])

;; --- The REPL "Tool Call" (Eval) ---

(schema/register! :seon.eval/id             [:string {:seon.db/identity true}])
(schema/register! :seon.eval/at             :inst)
(schema/register! :seon.eval/source         :string)
(schema/register! :seon.eval/narration      :string)
(schema/register! :seon.eval/ok?            :boolean)
(schema/register! :seon.eval/result-edn     :string) ; Truncated for display
(schema/register! :seon.eval/error          :string)
(schema/register! :seon.eval/duration-ms    :int)

;; --- The Program Graph (Persistent Entities) ---

(schema/register! :seon.ns/name             [:keyword {:seon.db/identity true}])
(schema/register! :seon.ns/source           :string)

(schema/register! :seon.fn/sym              [:string {:seon.db/identity true}])
(schema/register! :seon.fn/ns               :seon.db/ref) ; -> :seon.ns
(schema/register! :seon.fn/name             :keyword) ; simple name: :analyze
(schema/register! :seon.fn/source           :string)
(schema/register! :seon.fn/refs             [:vector :seon.db/ref]) ; -> :seon.fn

(schema/register! :seon.test/sym            [:string {:seon.db/identity true}])
(schema/register! :seon.test/target         :seon.db/ref) ; -> :seon.fn
(schema/register! :seon.test/source         :string)

;; --- The Rendering Logic ---

(schema/register! :seon.ctx/name            [:keyword {:seon.db/identity true}])
(schema/register! :seon.ctx/priority        :int)
(schema/register! :seon.ctx/fn              :symbol) ; 'seon.render.default/system-section

```

## h) Anti-patterns to forbid

1.  **The "Untagged Side-Effect":**
    *   **Bad:** Calling `db/transact!` without linking it to the current `eval-id`.
    *   **Why:** Breaks playback. You see data appeared, but you don't know which code form created it.
    *   **Alternative:** Use `seon.db/*tx-context*` to auto-tag all transactions in an eval scope.
2.  **Composite String Keys:**
    *   **Bad:** `:seon.fn/sym "my.ns/my-fn"`.
    *   **Why:** SQL thinking. Prevents graph joins on the namespace entity.
    *   **Alternative:** Use a ref to a `:seon.ns` entity.
3.  **`:any` in Schemas:**
    *   **Bad:** `(schema/register! ::data :any)`.
    *   **Why:** "Safe-at-every-boundary" rule (CLAUDE.md) demands precision. `:any` is a black hole that hides bugs.
    *   **Alternative:** Use `:seon.db/ref`, `:string`, or a specific union of types.
4.  **Storing `nil`:**
    *   **Bad:** `{:seon.eval/error nil}`.
    *   **Why:** Datahike/Datomic semantics: absent key = no value. Storing `nil` creates ambiguous queries.
    *   **Alternative:** Use `{:optional true}` in Malli and simply omit the key.
5.  **Positional DB API:**
    *   **Bad:** `(db/transact! conn tx-data)`.
    *   **Why:** Brittle; easy for agents to swap args and crash the pod (KI-1).
    *   **Alternative:** Strict map-in/map-out: `(db/transact! {::db/conn c ::db/tx-data d})`.
6.  **Reverse-Ref Discovery:**
    *   **Bad:** Teaching agents to find their evals via `[:seon.eval/_agent]`.
    *   **Why:** High cognitive load for LLMs; reverse syntax is error-prone.
    *   **Alternative:** Provide forward ref vectors (`:seon.agent/sessions`) for natural walks.

````

## Synthesis: design directives we should adopt

Priority order — top items unblock the spec rewrite the most.

### P0 — Adopt before any further harness work

1. **Introduce `:seon.turn` as a first-class entity.** Spec currently treats a turn as `:seon.agent/turn-count` (a scalar). Make it an entity with:
   - `:seon.turn/id` (string, identity)
   - `:seon.turn/index` (int)
   - `:seon.turn/prompt` (string) — the literal rendered context
   - `:seon.turn/messages` (cardinality-many ref, component)
   - `:seon.turn/evals` (cardinality-many ref, component)
   - `:seon.turn/status` (enum)
2. **Persist the rendered prompt snapshot.** Every turn must store the exact string the LLM saw. Re-deriving from a (possibly mutated) renderer + DB-at-tx is not equivalent — code mutates faster than data, and the V1 we already shipped lets the agent rewrite renderers mid-session.
3. **Introduce `:seon.agent.session`** as the container for a run. Agent → Sessions → Turns. Moves transient counters (`turn-count`, `turns-since-user`) off the agent entity and onto the session where they belong.
4. **Forward-ref component vectors top-down.** `:seon.agent/sessions`, `:seon.session/turns`, `:seon.turn/messages`, `:seon.turn/evals`. Use `:db/isComponent true` so a recursive pull on the agent reaches everything in one call.
5. **Move from `seon.agent/my-*` helpers to documented pull patterns.** Replace the helper fns with a `seon.agent.examples` namespace containing literal pull patterns, plus ONE helper (`seon.agent/root-pull`) that returns the current agent's nested context.

### P1 — Adopt for V1.1 / shortly after

6. **`seon.db/*tx-context*` dynamic var + auto tx-meta.** `eval-batch!` binds `{:seon.db/agent-id ... :seon.db/session-id ... :seon.db/turn-id ... :seon.db/eval-id ...}`; `db/transact!` merges into every tx's `:tx-meta`. No manual plumbing.
7. **First-class `:seon.ns` and `:seon.fn` entities.** Replace `"my.ns/my-fn"` composite strings with `:seon.fn/ns` ref + `:seon.fn/name` keyword. Enables namespace-scoped queries without string parsing.
8. **`:seon.db/origin` enum on every tx.** Lets playback distinguish system bootstrap from agent action from user injection.

### P2 — Worth keeping in mind but lower urgency

9. Section entities as data (already in spec — keep it, just confirm `:seon.ctx/fn` is a symbol, not a string).
10. Strict map-in/map-out for `db/transact!` itself (spec already uses positional; consider migrating before agents have learned the positional shape).

## Synthesis: ideas to consider but not blindly adopt

- **Gemini suggests making `:seon.fn/refs` a cardinality-many ref.** Useful for static analysis, but the V0 pod doesn't have a code analyzer yet, and this attribute will be empty until one exists. Defer to V1.2 or whenever we wire up the analyzer-cache.
- **Gemini's `:seon.test/sym` and `:seon.test/target` entities** are nice but premature — the MVP harness isn't running tests as first-class observations yet. Park for V2.
- **Reverse-ref as anti-pattern is overstated for V1.** Datahike supports `_attr` syntax fine; the issue is *discoverability for LLMs*, not correctness. Forward-ref components are better for the agent's primary access path, but we shouldn't forbid backrefs — schema migration entities or audit-log walks will still need them.

## Synthesis: things Gemini missed or under-emphasized

- **WASM persistence boundary.** Gemini didn't address the Phase 3 WASM-Tauri move. Component refs and recursive pulls are great until the DB lives in the Rust host and the pod calls in via WIT — a recursive pull traversing 5 levels deep means a big serialized result crossing the WIT boundary. We should benchmark before assuming `[* {:seon.agent/sessions [{:seon.session/turns [*]}]}]` is cheap.
- **Schema evolution.** Spec lets the agent register new schemas mid-session. None of Gemini's schema proposal addresses how an old turn's pull pattern remains valid after the agent has added/renamed attrs. Need to think about whether the playback layer pins to as-of tx.
- **Tx-meta size.** Gemini recommends 5 string attrs on every tx. For a session with 10K txs, that's ~500KB of meta overhead. Worth measuring on Datahike before committing.

## Open questions for Sean

1. **Component refs vs. plain refs for the session/turn/message chain.** Components mean "deleting the session deletes its turns." Do we want that, or do we want forensic history to survive session deletion? (Datalog history retains either way, but the *current* DB shape changes.)
2. **`:seon.turn/prompt` storage cost.** Each turn snapshot is plausibly 20–50KB of context. Over 10K turns that's 200–500MB just in prompt snapshots. Acceptable, or do we want a content-addressed `:seon.blob` entity with refs?
3. **Tx-meta auto-tagging via dynamic var — does this survive the WASM boundary cleanly?** `binding` inside CLJS works, but if a transaction is enqueued and applied on a different "thread" (we don't really have threads in wasm32, but the flow message-passing model fakes it), the binding may not propagate. Worth a spike before committing.
4. **Do we keep `seon.agent/my-messages` and friends as thin shims for backward compat with already-written agent prompts, or rip-and-replace now while no agent has memorized them?** User's "no legacy code" memory leans rip-and-replace, but worth confirming.
5. **Is `:seon.eval/result-edn` as a truncated string the right model, or do we want `:seon.eval/result-ref` pointing at a `:seon.blob` so we can preserve the full result?** Gemini chose truncation; the user's "DB must capture EVERYTHING" goal argues for full preservation.
