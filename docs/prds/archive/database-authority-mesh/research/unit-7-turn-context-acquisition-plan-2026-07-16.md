---
type: research
status: complete
tags: [research, database, flow, agent]
---

# Unit 7 turn and context acquisition plan — 2026-07-16

## Decision

Replace the turn's local Datahike value with one explicit head resolution and
one coordinate-pinned `execute-many` request. Decode its ordered ordinary
results once, render the core prompt synchronously from those values, and pass
the same resolved provider configuration and system text through every retry.
The turn's closing transaction returns the exact coordinate used for one final
remote pull of the closed turn.

This is not a remote database-value emulation. `run-turn!` no longer accepts,
dereferences, or passes `:seon.db/db`; context functions no longer perform
hidden reads. Known core reads become Datalog projections or independent
`execute-many` members. Agent-authored prompt and canvas functions remain
ordinary `^:async` ClojureScript in their owning Bun child and make explicit
`seon.db` calls at the containing turn coordinate.

The selected first cut is one request with **13 members for an ordinary agent**
and **16 for root**. Datahike resolves and retains one immutable value for the
whole group, runs up to the existing eight admitted positions concurrently,
shares exact identical query work, and returns one ordered ordinary vector.
There is no second cache, projection service, query language, or JVM context
renderer.

## Dependency ledger

| Owner | Selected revision | Exact fact used here |
|---|---|---|
| Seon | `b5947df4` plus the live Unit 7 working tree | `src/seon/db.cljs` has async `execute-many`, query, pull, and transaction responses carrying `:seon.db/coordinate`; `src/seon/agent/turn.cljs` still assumes a local value. |
| Datahike | `d21abadb9412f1b828b02ddb3c08ddc81d57c595` | `query/q-with-evidence` returns eager result, dependencies, cache evidence, and resource evidence; `pull_api/pull-many` parses once and preserves missing positions; `resource/shallow-weight-within` certifies counted ordinary structures without serialization. |
| Seon protocol | version 7 in `src/seon/db/protocol.cljc` | `execute-many` has 1–64 independent query/pull/pull-many/schema/index members, one coordinate, ordered position identity, and one aggregate result-weight bound. |
| JVM authority | `src/seon/db/writer.clj` | The outer request retains one exact database value, admits only an eight-position window, and stops later admission when the aggregate retained result cannot fit. |
| ClojureScript | `946d75f3` | `^:async`/`await` is the honest composition boundary; agent top-level eval resolves Promises to ordinary values. |
| Current core prompt | `src/seon/agent/turn.cljs`, `src/seon/agent/ctx.cljs`, `src/seon/agent/ctx/*.cljs`, `src/my/plan/internal.cljs` | Core rendering is synchronous only because a local Datahike value is threaded through every leaf. The recursive string/Hiccup render itself is already pure after reads are removed. |

## Shortest falsifier

Instrument the facade with a fake session and call one turn with a stub LLM.
The change is wrong if any of the following occurs:

- more than one `resolve-head` happens before the provider call;
- more than one core `execute-many` happens for the prompt;
- a request after acquisition omits or changes the acquired coordinate;
- retry attempt 2 sees provider configuration or system text committed after
  acquisition;
- a core render function calls `db/query`, `db/pull`, `db/entity`,
  `db/entity-lazy`, `db/installed-schema`, `db/history`, or dereferences
  `db/*conn*`;
- the agent-facing result contains a Promise, Datahike entity/database/Datom,
  lazy sequence, function, or transport value; or
- the final turn pull is not pinned to the successful close transaction's
  returned `:seon.db/coordinate`.

This falsifier is smaller and more decisive than a full cluster run. It can be
implemented entirely with the existing CLJS fake-session pattern and retained
context fixtures.

## What the turn does now

### Duplicate and drifting reads

`src/seon/agent/loop.cljs` captures `@db/*conn*` before `next-event`, then gives
that local value to `turn/run-turn!`. `run-turn!` in
`src/seon/agent/turn.cljs:783-861` uses it to perform all of these reads:

1. `ctx/repl-mode` for stream versus batch;
2. `turn-index`, which walks every run and turn through `ctx/agent-turns`;
3. `render-prompt`, which recursively reads the complete context;
4. `ai/debug-full-prompt`, whose `effective-system-prompt` re-reads the live
   config singleton rather than the frozen value;
5. `db/head-coordinate` for `rendered-*` evidence;
6. `ctx/current-ns` for the eval start namespace; and
7. a final local `db/pull` after multiple intervening writes.

The provider path then breaks the freeze. Every call to
`bounded-llm-attempt!` (`turn.cljs:652-679`) dereferences `@db/*conn*`, derives
configuration again, and records a new head coordinate. `llm-retry-strategy`
(`turn.cljs:543-555`) separately re-reads the agent retry limit.
`effective-system-prompt` is called again by the adapters and logging. A retry
can therefore use and record a different database value from the prompt the
model received.

### Prompt read graph

The core path starts at `turn/render-prompt` → `ctx/render-context` →
`ctx/rendered-context` → `ctx/context-root` and its stored/derived children.
The current database reads are:

| Current owner | Reads performed now | Selected replacement |
|---|---|---|
| `ctx/rendered-context`, `pull-agent-entity`, `context-root` | Agent entity is read at least twice; blocks are pulled; current namespace and derived render functions are rediscovered; canvas fallback scans history. | One exact agent pull, one current-namespace/program query, and one canvas query. Reuse the same agent map everywhere. |
| `ctx/repl-mode`, `run-policy`, `cache-breakpoint` | Installed schema plus config or agent entity reads; two zero-arity functions silently return to the live connection. | Values from the agent/config members; make the pure functions accept them. |
| `ctx/agent-turns`, `ctx/current-ns` | Lazy reverse entity traversal over every run/turn/eval plus a separate latest-successful-eval query. | Scalar turn summary plus a bounded recent-turn query. Current ns is returned by the namespace query and reused for eval start. |
| `ctx.transcript/transcript-block` | Repeats agent, turns, current ns, run state, config, messages, and block reads; creates all events and only then evicts old turns. | Two bounded query members, turns/evals and messages, plus the already-acquired agent/config/turn summary. Pure transcript projection receives ordinary rows. |
| `ctx.namespaces/namespaces-block` | Agent/block reads, latest ns query, require-edge existence/schema/pull, whole namespace scan, N entity probes, N namespace pulls, N test pulls, and transitive per-schema entity walks. | One joined query rooted at the latest successful ns, returning that ns and required namespace program rows, plus one parallel query of schema key/form rows. Compute schema closure from that ordinary map. |
| `ctx.render-fns/derived-blocks` | Current-ns function query; canvas fallback repeatedly queries source/read attrs and history. | Reuse namespace program rows for auto-run discovery; one history query selects the fallback canvas. |
| `ctx.canvas/canvas-block` | Canvas renderer reads through a local db; an authored function may read arbitrarily; source is fetched in a separate query. | Core canvas metadata/source rides the agent/namespace/canvas results. Authored renderer executes asynchronously at the acquired coordinate in the agent child. |
| `my.plan.internal/plan-block` | Agent lookup, active/open/recent-done/escalation queries, N pulls, N `ready?` queries, ancestor entity walk, rollups, planner candidates, config reads, and message scans. | Five bounded independent query members: position/progress, frontier, frontier count, recent done, and escalation. Pure plan text consumes their ordinary results. |
| `ctx.warnings/core-faults-block` | Latest-user query, frame query, core-error query, then CLJS filtering. | One root-only Datalog query using `not-join` to exclude a fault when a later user message exists, with frame 0 joined optionally. |
| `instrument/coverage-gaps` | Whole-program `?sym ?spec` query plus live JS wrapper inspection. | One root-only bounded program-schema query; retain live wrapper inspection in Bun. |
| `ctx.subagents/orphaned-agents-block` | Orphan query followed by one entity lookup and several derive queries per child. | One root-only Datalog projection returning child, parent, purpose, and enough run facts to derive state directly. |

`entity-lazy` is not translated. Every use in the prompt path becomes a query,
pull, or absent ordinary value and is deleted with local database traversal.

## One coordinate scope

The turn sequence is:

1. `run-loop!` calls `run-turn!` without a database value.
2. `run-turn!` resolves the current head once.
3. It calls `db/execute-many` once with that explicit coordinate and the
   members below.
4. It validates every member result before rendering anything. A failed core
   member is a turn error value; it is not replaced by a live re-read.
5. Pure core functions render ordinary member results. Agent-authored render
   functions run in the same child and receive the same coordinate through
   the existing async database context.
6. The resulting context, acquired system text, acquired provider resolution,
   acquired retry count, acquired repl mode, acquired current namespace, and
   coordinate form one immutable local turn input.
7. Blob capture and `open-turn!` may commit later facts, but they do not change
   what the model sees.
8. Every provider retry receives the same system text, config resolution, and
   coordinate. Only the abort controller, ordinal, response, and wall clock
   vary by attempt.
9. `close-turn!` returns its successful transaction envelope's coordinate.
10. `run-turn!` performs one remote pull of the turn/evals/attempts at that
    explicit close coordinate and returns the ordinary map.

The pre-provider coordinate remains the stored
`:seon.agent.turn/rendered-*` value. The final pull coordinate is not stored as
a second turn field; it is only the read point that guarantees the returned
closed entity includes the close transaction.

## Exact `execute-many` members

Member position is the identity already selected by the protocol. Keep the
member vector in one private function and destructure its results in the same
order; do not add member IDs or wire labels.

### Members for every agent

| Pos | Operation | Query/pull result and why it is independent | Suggested bound |
|---:|---|---|---:|
| 0 | pull | Exact agent projection: `:db/id`, identity, purpose, default turn limit, prompt/canvas slots, context dials, AI overrides, and `{:seon.agent/ctx [*]}`. This replaces every repeated agent/block lookup. | 128 Ki weight |
| 1 | pull | Config singleton projection: system text, repl mode, run policy, global AI config, model-transport caps, and context policy values read by the turn. | 64 Ki weight |
| 2 | query | Turn summary: all-turn count, current open run projection, current-run work count, and latest closed run facts used by readline/state. Aggregate in Datalog; do not pull all turns to count them. | 64 Ki weight |
| 3 | query | Recent turn rows with nested eval pulls, newest first under a query-map `:order-by` and `:limit`, then restored oldest first in CLJS. This is the only eval-history input to transcript/current-ns/escalation. | 1.25 Mi weight |
| 4 | query | Waking inbound plus outbound message pulls for this agent, newest-first bounded, including from/to labels. Pure code orders and marks unanswered rows. | 512 Ki weight |
| 5 | query | Current namespace plus its require edges and all included namespace, function, schema, and test rows. The query selects the latest successful eval with `not-join`, joins edge targets to `:seon.ns/name`, and returns current plus required namespaces without an N+1 pull. The passed home namespace is selected when no successful eval exists. | 1.5 Mi weight |
| 6 | query | Every `:seon.schema/key`/`:seon.schema/form` pair. Referenced-schema closure can cross beyond the current namespace and its direct requires, so this complete small dictionary is the honest one-round-trip input. | 512 Ki weight |
| 7 | query with history | Last-updated authored canvas candidate: source transaction plus max agent/REPL transaction touching its declared read attrs. Explicit pinned canvas content from member 0 wins, so pure code ignores this member when pinned. | 128 Ki weight |
| 8 | query | Plan position: active step or first ready step, ancestor chain, and root leaf done/total aggregate. Use the existing `my.plan.internal/rules`; no entity walk. | 256 Ki weight |
| 9 | query | Plan frontier: active rows plus at most `frontier-limit + 1` ready rows, already projected with `open-keys`. | 256 Ki weight |
| 10 | query | Scalar count of the same ready relation as member 9. This preserves the exact existing `N more` line without returning the unbounded frontier. | 32 Ki weight |
| 11 | query | Five most recent completed plan rows, ordered in the query map and limited to existing `recent-done-limit`. | 64 Ki weight |
| 12 | query | Escalation input: newest active assertion and bounded eval rows after its transaction, plus planner/message evidence needed by `escalation-section`. Return facts; retain `wedge` and prose as pure CLJS. | 256 Ki weight |

### Root-only appended members

| Pos | Operation | Result | Suggested bound |
|---:|---|---|---:|
| 13 | query | Core faults for which no later user-origin message exists, with frame-zero function when present. | 128 Ki weight |
| 14 | query | Canonical `:seon.fn/sym`/`:seon.fn/spec` rows. Bun compares them with live wrapper objects; no function crosses the wire. | 512 Ki weight |
| 15 | query | Orphaned live child rows with parent, purpose, current/latest run data, and counts needed for the existing state line. | 256 Ki weight |

The suggested individual limits total more than the four-MiB outer limit on
purpose: individual limits describe which member is unexpectedly large; the
outer limit remains the actual retained-response fence. Expected warm ordinary
turns are **0.2–1.2 Mi shallow weight**. Large source namespaces or unusually
dense active transcripts are expected to reach **1.2–3.5 Mi**. The exact
Transit frame remains independently capped because Datahike's shallow weight
counts string characters, not UTF-8 bytes.

The first measurement must record per-position resource evidence, aggregate
weight, encoded bytes, cold/warm latency, and cache outcome. If normal turns
regularly approach four MiB, reduce raw inputs at their owning query; do not
raise the frame or add compression to conceal over-acquisition.

## Queries to combine rather than batch

The following joins belong inside Datalog because splitting them would return
IDs only to issue dependent reads:

- agent → run → turn → eval for counts, current namespace, and recent evals;
- current namespace → require-edge → target namespace → functions/schemas/tests;
- plan step → ancestors/descendants/leaves for position and progress;
- plan step → `needs` target → unfinished leaf for ready/blocked state;
- message → from/to identity for labels and wake eligibility;
- error → transaction instant → later user-message exclusion → frame zero;
- orphan child → terminated parent plus current/latest run facts; and
- authored canvas source/read attrs → history datoms → transaction
  `:seon.db/user` and `:seon.db/process` provenance.

The members stay independent because none needs another member's result:
agent/config, transcript turns, messages, namespaces/schema forms, canvas
recency, plan position/frontier/count/done/escalation, and root diagnostics
can all run against the same database value simultaneously. Do not add result binding to
`execute-many`.

## Ordinary values passed to pure functions

Do not expose one new public “snapshot” noun. The private decoder destructures
the protocol result vector and calls existing owners with their existing
domain names:

```clojure
{:seon.db/coordinate                     point
 :seon.agent/id                          id
 :seon.agent/entity                      agent
 :seon.config/config                     config
 :seon.agent.turn/count                  turn-count
 :seon.agent.run/current                 current-run
 :seon.agent.ctx.render-fns/current-ns   current-ns
 :seon.agent.ctx/transcript              transcript-rows
 :seon.agent.ctx/namespaces              namespace-rows
 :seon.render.canvas/content             canvas
 :my.plan/plan                           plan-rows
 :seon.error/core-faults                 core-faults
 :seon.instrument/coverage-gaps          coverage-rows
 :seon.agent/orphaned-agents             orphan-rows}

```

This is process-local ordinary data, not a database entity schema and not a
wire shape. The few keys that do not already exist are private inputs named for
their owning function, not durable attributes or a second vocabulary. If the
single map proves noisier than block-specific arguments, keep only
`:seon.db/coordinate`, `:seon.agent/id`, `:seon.agent/entity`, and
`:seon.config/config` at the root and pass the remaining position results
directly to the owning pure block functions.

The exact pure boundaries are:

- `ctx/rendered-context` receives agent/config plus already-acquired block
  inputs and returns its existing `::ctx/rendered-context` map;
- transcript receives ordinary agent, turn, eval, message, run, and config
  rows and returns a String;
- namespaces receives current namespace and program rows and returns a String;
- plan receives its five query results and returns a String;
- canvas receives the resolved renderer identity/source plus ordinary renderer
  output and returns a String; and
- root warning/subagent functions receive their query rows and return Strings.

The recursive render walker still receives ordinary nodes and render handles.
It never receives `:seon.db/db`.

## Authored render functions

An agent-level prompt override, stored block function, auto-run function, or
canvas function can branch on arbitrary database values. A static acquisition
list cannot represent that without inventing a template language.

Run those functions after core acquisition inside the same Bun agent child:

- bind the acquired coordinate in the existing async transaction/database
  context;
- wrap the return with `Promise.resolve`, await it, deep-force it, then apply
  Malli validation to the resolved ordinary value;
- allow explicit independent reads to use `db/execute-many` and dependent
  reads to use normal `^:async` composition;
- include authored read evidence in the turn diagnostics/cancellation owner,
  not in a replay cache; and
- reject a Datahike value, entity, lazy collection, Promise, function, or native
  owner before it reaches the renderer or turn result.

This preserves the feature without making every core renderer asynchronous or
moving CLJS/SCI into the JVM.

## Final pull and write ordering

`close-turn!` currently discards the successful close transaction envelope and
returns only the body result. Change it to retain the envelope's existing
`:seon.db/coordinate` in its internal result. `run-turn!` then awaits:

```clojure
(db/pull {:seon.db/coordinate close-coordinate
          :seon.db/pull-pattern
          '[* {:seon.agent.turn/evals [*]}
               {:seon.agent.turn/llm-attempts [*]}]
          :seon.db/ref [:seon.agent.turn/id turn-id]})

```

No resolve-head belongs between close and pull. Other agents may commit after
close; that does not matter. The close response identifies a value containing
the turn's open row, eager reply-blob link, eval transactions, attempt rows,
and close status. If close fails, return the existing turn error and do not
pretend a later head is the closed turn.

## Deletions in this cut

Delete from the turn/context path, not adapt:

- `:seon.db/db` from `run-turn!`, `render-prompt`, context section requests,
  and render `system-input`;
- all `@db/*conn*` fallbacks in turn, context, AI config, retry, and core block
  functions;
- `db/entity-lazy` from transcript, namespaces, schema closure, and plan
  traversal;
- local `db/history`, `db/basis-t`, `db/head-coordinate <db>`, and temporal
  database wrappers from render functions;
- installed-schema probes used only to avoid local Datahike exceptions;
- repeated `entity`/`pull` loops after a query returns IDs;
- the separate retry-time provider/config/system reads; and
- the synchronous `render-prompt [agent-id db]` arity.

Retain `seon.db` as the sole I/O interface, the existing context block order,
pure formatting/clipping/coalescing, `seon.render` recursion, SCI isolation,
agent top-level Promise resolution, and the turn's one rendered coordinate.

## Smallest staged source ownership cuts

These cuts are dependency-ordered and do not introduce a lasting parallel
path. Each cut replaces its owner in place and deletes its local reads before
moving to the next.

1. **Turn/provider contract** — own `src/seon/agent/turn.cljs`,
   `src/seon/ai.cljs`, provider adapters, and focused turn/AI tests. Add one
   coordinate-pinned acquisition call, pass system/config/retry values through
   attempts, propagate close coordinate, and make final pull async. Stub block
   members initially return the existing fixture values only in tests; do not
   ship a local-db fallback.
2. **Transcript/state** — own `src/seon/agent/ctx/transcript.cljs`, the relevant
   `seon.derive` pure helpers, and transcript tests. Replace full lazy history
   walks with bounded ordinary rows and scalar counts.
3. **Namespaces/auto-run** — own `src/seon/agent/ctx/namespaces.cljs`,
   `ctx/render_fns.cljs`, the pure schema-closure helpers in `ctx.cljs`, and
   their tests. Land the one joined program query and delete N+1 pulls.
4. **Plan** — own `src/my/plan/internal.cljs` and plan tests. Add five pure
   result consumers, preserve the existing rules, and remove read-per-step
   context rendering. Public authored `my.plan` read functions migrate
   separately to async `seon.db`; they are not an excuse to retain a DB value
   in the prompt.
5. **Canvas and root-only blocks** — own `ctx/canvas.cljs`, `ctx/warnings.cljs`,
   `ctx/subagents.cljs`, `seon.instrument.cljc`, and focused tests. Keep live JS
   instrumentation inspection in Bun while all database input is ordinary.
6. **Context root cleanup** — own `src/seon/agent/ctx.cljs`, `src/seon/render*`,
   and parity tests. Remove `:seon.db/db`, zero-arity live reads, installed
   schema gates, and local observation assumptions. At this point core render
   functions are pure and the temporary migration scaffolding from cut 1 is
   deleted in the same commit.
7. **Loop caller and deletion proof** — own `src/seon/agent/loop.cljs` and loop
   tests. Stop capturing `@db/*conn*`, verify one acquisition per turn, then
   include these paths in the atomic replica/local-Datahike deletion commit.

Do not split edits to one semantic owner among agents. Transcript, namespaces,
plan, and root diagnostics are safe independent ownership lanes only after the
turn acquisition result order is frozen.

## Tests and executable falsifiers

### Focused turn/session tests

- Fake `resolve-head` returns C0; assert the sole `execute-many` carries C0 and
  all 13 or 16 members.
- Complete acquisition at C0, commit config C1 before attempt 2, and assert
  both attempts use C0 config/system text/retry count and both attempt rows
  store C0.
- Make the close transaction return C7, advance head to C8, and assert the
  final pull explicitly carries C7 and returns the C7 closed turn.
- Fail one required member and assert no prompt, provider request, or live
  fallback read occurs.
- Cancel during acquisition and during an authored read; assert the universal
  request is canceled, no member owner remains, and no late completion starts
  the provider.
- Close the session during acquisition; assert one ordinary error and zero
  retained Promises/handlers.

### Pure parity tests

Use the existing in-memory fixtures only to produce ordinary expected input,
then call the new pure functions without binding a connection:

- `test/seon/ctx_test.cljs`: model prompt equals debug prompt at the same
  acquired input, aside from the explicitly live readline time;
- `test/seon/agent/ctx/transcript_test.cljs`: window rotation, unanswered
  messages, decay caps, result handles, state/readline, and empty history;
- `test/seon/agent/ctx/namespaces_test.cljs`: fresh home namespace, require
  selection, compact/full rules, tests, referenced schema closure, and
  auto-run detection;
- `test/my/plan_test.cljs`: anchor, ready/blocked, progress, escalation,
  overflow, and recent done without any database call in formatting;
- canvas/render-fn tests: pinned and fallback canvas, authored sync/async
  results, timeout, rejection, and no Promise before validation;
- warning/subagent tests: one root query result renders the same lines and
  empty results vanish; and
- `test/seon/agent/turn_capture_test.cljs`: stored rendered coordinate and
  prompt blob are C0 while returned turn pull is the close coordinate.

Add a source-level regression scan over the migrated owners for
`db/*conn*`, `:seon.db/db`, `db/entity-lazy`, `db/history`, and synchronous
database calls. It is a deletion proof, not the behavioral proof.

### Performance proof

For empty, normal, large-source, transcript-heavy, and root contexts record:

- member count, admitted maximum, per-position queue/run time;
- per-query dependency/cache/resource evidence;
- aggregate shallow weight and exact Transit bytes;
- cold versus warm authority latency and Bun pure-render latency;
- total database calls and round trips;
- JVM heap/CPU and Bun RSS/CPU; and
- 1, 8, 32, and 128 simultaneous agent turns over one and multiple databases.

The acceptance target is one head resolution plus one acquisition round trip,
no repeated query work for identical same-coordinate prompts, no per-agent JVM
thread, bounded response memory, and parallel progress for unrelated agents and
databases. Compare against the current local fixture for output parity, not as
a production compatibility path.

## Tradeoffs Sean should decide

### 1. Maximum transcript window

Current `::turn-window-size` and `::turn-eviction-size` schemas have minimums
but no maximum. A one-round-trip bounded query cannot both honor an arbitrarily
large value fetched in member 0 and choose its query limit before member 3
runs.

**Recommendation:** give `::turn-window-size` a deliberate maximum of 200 and
require eviction size not to exceed it. Query 200 recent turns in the first
cut, then pure code applies the stored lower value. This bounds CPU/memory and
keeps one round trip. The alternative is a two-round-trip base-then-history
acquisition, which preserves unbounded customization at a permanent latency
and coordination cost.

### 2. Message bound

The AI transcript currently queries all messages before clipping, unlike the
HTML path's 200-row source bound. This is a real unbounded-read defect.

**Recommendation:** cap acquisition at the latest 400 relevant messages and
render a loud omitted-count line when more exist. If exact conversation history
beyond that is required, expose an explicit paged authored read rather than
making every prompt carry it.

### 3. Root diagnostics in every root turn

The three root-only members are independent and cheap when cached, but the
whole-program instrumentation row can be hundreds of KiB.

**Recommendation:** keep all three in the first one-request proof for semantic
parity, measure position 14, then use the existing selective-interest changed
attributes to skip reacquisition only if it is material. Do not add a second
cache before measurement.

### 4. Core versus authored `my.plan` renderer

`my.plan.internal/plan-block` is stored as an authored-looking `my.*` symbol
but is shipped core behavior with a stable known query graph.

**Recommendation:** keep the prompt's plan block in the core acquisition so it
does not pay arbitrary authored-read round trips. Other `my.*` renderers remain
authored and async in the child. This is a source-ownership distinction, not a
new runtime type.

### 5. Custom whole-prompt override

A symbol stored in `:seon.render/ai` can replace the whole core context.

**Recommendation:** when the override is a symbol, skip unused core members
and invoke it as an authored async renderer at C0; when it is a string, member
0 already contains the complete prompt and all other context members can be
omitted. This reduces work but means member count is selected after reading
the agent row. Achieving that requires two round trips or retaining a small
session-owned last agent configuration.

For the first cut, prefer one round trip and issue the normal member set; then
measure how often whole-prompt overrides exist. Do not add retained mutable
configuration without evidence.

## Exit condition

This plan is ready to implement when Sean accepts the transcript/message bounds
and the shipped-core treatment of `my.plan.internal/plan-block`. Graduation is
not “async functions compile.” It is the integrated proof that one turn uses
one exact pre-provider coordinate, one coarse acquisition, no hidden local
database reads, stable provider configuration across retries, one close-pinned
final pull, ordinary values throughout, and bounded parallel progress under
many simultaneous Bun children.
