---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Remote-read consumer classification — 2026-07-15

## Purpose and method

This report classifies production `seon.db` read consumers by the seam that
should replace synchronous access if Bun children no longer host Datahike. It is
semantic, not a search-and-replace inventory. Tests, documentation examples,
schema declarations containing `:seon.db/entity`, and the implementation of
`seon.db` itself are excluded from migration counts.

The classes are:

1. combine into one Datalog query or pull;
2. group existing operations into `execute-many` at one coordinate;
3. promote measured heavy work to a named authority projection;
4. retain as explicit JVM-local Datahike work;
5. expose as ad hoc asynchronous database access; or
6. remove with the local replica or redundant guard/path.

Exact line numbers identify the inspected revision and will drift. Function and
namespace are the durable owner.

## Frozen-coordinate law

Every production computation that combines database reads must first select one
immutable database value. Remote clients express that as one complete
coordinate. Every member of an `execute-many` request resolves against that
same value. No member may silently re-read the latest connection head.

This law already appears locally: context, render, debug, autocomplete export,
and web evidence functions commonly accept `db`/`dbv` and thread it through
their reads. The migration should preserve those pure function boundaries and
move the asynchronous wait to their caller, not make every leaf function return
a Promise.

## Classification summary

| Production area | Dominant class | Why |
|---|---|---|
| Agent context and turn prompt | Execute-many plus query/pull consolidation | Many related projections already share one `db` argument |
| Turn/run/message coordination | Combine queries; async mutation decisions with CAS | Small relational decisions, some read-before-write fences |
| Render, routes, and web UI | Execute-many; optional UI replica remains separate decision | Large synchronous render graph cannot tolerate Promise leaves |
| Debug and evidence | Named heavy projection or execute-many | Historical coordinate joins, many pulls, blob projections |
| Eval/compiler graph | Execute-many plus named code projection | Many graph/schema/source reads; hot compiler path is synchronous |
| Autocomplete export | Named heavy projection | Whole-corpus deterministic export over one coordinate |
| Embeddings and KNN | JVM-local heavy projection with async results | Provider and secondary-index resources already live at authority |
| Agent-authored `db/*` | Ad hoc asynchronous | Open-ended user query/pull/history cannot be statically batched |
| Replica attachment/cache guards | Removal | Exist only to support local Datahike ownership or duplicate guards |

## Class 1 — combine into Datalog or pull

These consumers currently perform an existence query followed by a pull, an
enumeration followed by per-row entities, or several scalar queries over one
relation. Datahike can express the work in one query/pull without a new protocol
operation.

### Context and namespace projection

- `seon.agent.ctx/pull-agent-entity` queries an agent eid and then pulls it
  (`src/seon/agent/ctx.cljs:2318-2343`). Use one pull by the already-unique
  `:seon.agent/id` lookup ref; preserve guarded absence as an ordinary nil/error
  result rather than a preflight RPC.
- `seon.agent.ctx/pull-ns-data` performs an entity existence check, a namespace
  pull, and a second tests pull (`agent/ctx.cljs:1555-1600`). One pull pattern can
  include namespace functions, schemas, requires, and tests. If tests require a
  different cap, keep two members inside one `execute-many`, not two round trips.
- `seon.agent.ctx/schema-definition-in-db` resolves a schema entity only to read
  `:seon.schema/form` (`agent/ctx.cljs:1719-1733`). A scalar query avoids entity
  realization.
- `seon.agent.ctx.namespaces/ns-block-entity`, `ns-tests-block`, and
  `render-one-ns-compact` repeat namespace identity/pull work
  (`agent/ctx/namespaces.cljs:255-310,780-820`). Consolidate around one namespace
  projection consumed by both full and compact views.
- `seon.agent.ctx.menu/public-fn-row`, `capped-functions`, and
  `ns-public-specced-fns` enumerate functions and then pull/shape rows
  (`agent/ctx/menu.cljs:215-390`). One Datalog pull expression can return the
  capped fields and namespace relationship.

Deletion consequence: remove lookup-ref existence probes and repeated entity
materialization; retain one namespaced projection shape.

### Fleet, transcript, and warnings

- `seon.render.system/fleet-summary` enumerates ids, then for every agent reads
  an entity, children, latest human message, latest agent message, and run state,
  followed by global counts/maxima (`src/seon/render/system.cljs:74-247`). This
  is the clearest N+1 hot path. Start with a bounded relational query per
  subprojection or one named fleet projection if the joined query proves
  expensive. Do not issue per-agent remote calls.
- `seon.agent.ctx.transcript/message-events` and `agent-rec` derive one ordered
  conversation and participant map (`agent/ctx/transcript.cljs:580-680`). Query
  messages and referenced agents in bulk, then perform local pure ordering.
- `seon.warn/failed-eval-rows`, `fs-denied-eval-rows`, `check-hop-exhausted`,
  `check-record-errors`, `check-slow-evals`, and `check-failing-tests` perform
  repeated warning-specific scans (`src/seon/warn.cljs:580-920`). Group the
  warning facts into a single bounded warning projection or a few attribute-
  indexed queries. Rendering warnings remains pure CLJS.
- `seon.agent.ctx.subagents/child-ids`, `child-line`, and `orphan-rows` query the
  same parent/child graph (`agent/ctx/subagents.cljs:40-205`). Return one graph
  relation and derive lines/orphans locally.
- `seon.agent.loop/activity-log` queries run rows and then realizes each run and
  cause message (`agent/loop.cljs:830-865`). One pull/query should return the
  bounded run/cause fields.

### Web request projections

- `seon.web.serve/handle-clear!` separately resolves the agent, queries message
  eids, and queries eval eids (`src/seon/web/serve.cljs:280-315`). One query can
  return the retractable entity sets; the subsequent transaction still uses an
  expected coordinate/CAS fence.
- `seon.web.serve/run-agent-task!` resolves agent/user, queries runs, turns, and
  replies, and resolves model configuration from one final `db`
  (`web/serve.cljs:1190-1310`). Return one request-window projection at the final
  coordinate. This is a production latency hot path and must not become a chain
  of leaf RPCs.
- `seon.web.serve/project-model-transport-evidence` queries attempt refs and then
  pulls every attempt and turn coordinate (`web/serve.cljs:865-925`). One query
  with pull expressions eliminates the per-attempt loop.
- `seon.web.serve/eval-evidence` queries eval rows and then pulls each eval
  (`web/serve.cljs:1120-1160`). Return the exact bounded evidence fields in one
  query/pull.

## Class 2 — execute-many at one coordinate

Use this class where several existing operations are independently useful and
combining them into one complex Datalog query would obscure ownership. The
outer caller awaits once; leaf transforms stay synchronous over returned data.

### Context construction

`seon.agent.ctx/rendered-context` is the natural outer boundary
(`src/seon/agent/ctx.cljs:2590-2640`). Its downstream context graph includes:

- run policy, escape clipping, cache breakpoint, and REPL mode singleton reads
  (`agent/ctx.cljs:368-480,710-725`);
- `current-ns`, agent turns, agent entity, installed schema, and configured
  context blocks (`agent/ctx.cljs:395-415,1080-1135,2318-2343`);
- namespace/function/schema/test data (`agent/ctx.cljs:1555-1733` and
  `agent/ctx/namespaces.cljs`);
- transcript, subagents, warnings, render functions, canvas, and menu
  namespaces under `src/seon/agent/ctx/`; and
- final entity/render slot selection (`agent/ctx.cljs:2619-2635`).

One context request should freeze a coordinate, submit a bounded set of named
ordinary query/pull members, and then run the existing pure render assembly in
the Bun child. Repeated installed-schema and singleton reads should be one
member, not copied into every block.

Async boundary: make the context entrypoint asynchronous. Do not convert every
block renderer to `^:async`; the render walker, cache gradient, clipping, and
instrumentation assume ordinary values.

### Turn execution

- `seon.agent.turn/run-turn!` constructs context, calls the provider, records
  results, and finally pulls the completed turn with evals and attempts
  (`src/seon/agent/turn.cljs:780-865`). Context uses one pre-call coordinate;
  final turn projection uses the committed post-write coordinate. They are two
  deliberate immutable points, never one ambient “latest.”
- `seon.agent.loop/next-event`, `wake-handler`, and activity decisions consume
  run/message/agent facts (`agent/loop.cljs:140-175,440-480`). Group each event
  decision over the committed transaction value already supplied to the
  handler. Do not add a network round trip when the event envelope can carry the
  required indexed facts.
- `seon.agent.run/snapshot`, `quiescence-work`, effective deadlines, stale-run
  scans, and close/resume decisions (`agent/run.cljs:170-820`) should group by
  one run-control coordinate. Writes remain separate ordered operations with
  expected-coordinate fences.
- `seon.agent.message/recent`, `recent-all`, inbound checks, and outbound hop
  derivation (`agent/message.cljs:90-170,230-255`; `message/internal.cljs`) form
  one bounded message projection reused by context and scheduling.

### Render and UI

- `seon.render.system/fleet-summary` is one `execute-many` request until evidence
  justifies a named heavy projection.
- `seon.render/surface` reads process, agent touches, conversation touches,
  canvas renderer, stored renderer symbols, and metadata
  (`src/seon/render/surface.cljs:90-330`). Freeze one coordinate and retrieve all
  render-selection inputs once.
- `seon.render.canvas/canvas-state`, `user-name`, and `welcome`
  (`render/canvas.cljs:390-585`) can be members of the same surface request.
- `seon.render.chat/provider-failure-rows` and other chat history reads
  (`render/chat.cljs:125-155`) belong in one bounded chat projection.
- `seon.web.router/route-projection` reads installed schema and all route pull
  rows (`src/seon/web/router.cljs:220-250`). Run once at a frozen configuration
  coordinate and cache by coordinate, not once per HTTP request.
- `seon.db.browser/attribute-groups`, `attribute-schema`, and `index-page`
  (`src/seon/db/browser.cljs:175-210,420-490`) are already bounded database-view
  operations. Group schema plus first page; subsequent cursor pages remain ad
  hoc async operations tied to their coordinate.

The unresolved topology choice remains whether an active UI keeps one local
replica. This classification works for both: the exact request members execute
locally against the replica or remotely through `execute-many` without a second
application interface.

## Class 3 — named heavy authority projections

A named projection is justified when the authority can materially reduce
cross-boundary data, reuse native indexes/provider resources, or execute a
stable expensive computation. It returns ordinary namespaced data and remains
independent of JVM objects.

### Debug and forensic evidence

`seon.agent.debug/turn`, `errors`, `turn-active-at-coordinate`, transaction agent
resolution, and error pulls combine history/as-of, transaction metadata, blob
refs, and many entity projections (`src/seon/agent/debug.cljs:180-500`). Define
bounded turn/error evidence projections at an explicit coordinate. This avoids
sending raw history/index ranges to Bun merely to discard most fields.

`seon.web.serve/project-model-transport-evidence` and `eval-evidence` are also
candidates when their combined Datalog/pull form remains expensive. The named
operation should be shared by web and debug consumers, not named after a route.

### Compiler and code graph

`seon.eval/reconstitute-ns-source`, omitted-function retractions, persisted
require edges, function read attributes, core boot symbols, stored namespace
source, graph function names, and REPL dispatch probes perform repeated reads of
the same `:seon.ns`, `:seon.fn`, `:seon.schema`, and `:seon.test` corpus
(`src/seon/eval.cljs:890-1000,2650-2990,3760-4040,4860-4910`).

Define a coordinate-keyed code-corpus projection containing namespace source,
require edges, function contracts/source hashes, schema forms, and tests needed
by one compiler/eval operation. Keep the self-host analyzer and compilation in
Bun; move only database selection and heavy graph extraction to the authority.

`seon.client/agent-ns-set`, `schema-forms-in-db`,
`function-contracts-in-db`, and `core-program-tx` consume the same corpus during
boot (`src/seon/client.cljs:940-1030,2100-2290`). The shared projection deletes
parallel boot versus eval scans.

### Autocomplete export

`seon.repl.autocomplete/export!` reads profile/config identity, every indexed
function symbol and record, agent ids, installed schema, candidate turns, and
rendered coordinates (`src/seon/repl/autocomplete.cljs:140-270,400-540`). This
is a deterministic whole-corpus export, not an interactive leaf read. Make it a
named, cancellable, result-byte-bounded projection keyed by coordinate and
runtime/source identity.

The interactive `fn-record` lookup remains a small async pull; the bulk export
does not justify keeping a local replica.

### Embeddings and KNN

`seon.embed.clj/current-hash-for`, `needs-embedding-eids`, entity pulls, document
composition, provider calls, and index updates already live on the JVM
(`src/seon/embed.clj:850-970`). Keep these explicit JVM-local reads inside the
addressable embedding job. Final embedding facts transact through that
database's ordered writer.

`seon.embed.cljs/where->eids` currently queries local Datahike before remote KNN,
then `enrich-hit` pulls each hit locally (`src/seon/embed.cljs:130-200`). Move
filter resolution, KNN, and bounded pull enrichment into one named authority
operation. Sending only `{eid, distance, selected entity}` avoids a candidate
eid set round trip and N per-hit pulls. It uses the separate KNN/provider work
class established by [[multidb-execute-many-proof-2026-07-15]].

`seon.diffusion.retrieval/graph-syms` and `pull-candidate`
(`src/seon/diffusion/retrieval.cljs:410-470`) should consume the same named
semantic-retrieval projection rather than rebuild a second graph/KNN path.

## Class 4 — explicit JVM-local Datahike reads

These calls are inside the authority or database maintenance implementation and
must remain synchronous over explicit immutable values:

- `seon.db.registry/durable-restore-completions!` and attachment/coordinate
  validation (`src/seon/db/registry.clj`);
- `seon.db.writer/committed-transaction`, temporary-id recovery, replay,
  transaction interpretation, and coordinate resolution
  (`src/seon/db/writer.clj`);
- `seon.db.restore/publication-proof` and restore planning
  (`src/seon/db/restore.cljc` on CLJ);
- `seon.db.coordinate/resolved` and `at` (`src/seon/db/coordinate.cljc` on CLJ);
- `seon.db.id` allocation and receipt checks on the writer boundary; and
- `seon.embed.clj` document/hash selection and embedding mutation preparation.

They must take an explicit database value and must not call the remote protocol
back into the same authority. This class is also the model for future named
heavy projections.

## Class 5 — ad hoc asynchronous reads

Some reads are intentionally open-ended and cannot be compiled into a fixed
production projection:

- agent-authored `seon.db/query`, `pull`, `entity`, `datoms`, `history`, `as-of`,
  and `since`;
- `my.data`, `my.kb`, `my.plan`, `my.skills`, and other toolkit functions whose
  caller supplies filters, ids, or pull patterns;
- database browser cursor pages after the initial projection;
- interactive autocomplete single-symbol lookups;
- agent search/inspection operations whose exact code symbol is user-selected;
  and
- arbitrary forensic coordinate requests.

The Bun API must make these honestly asynchronous. Existing agent eval already
awaits returned Promises at its outer boundary, but authored helper functions
that compose reads must be `^:async` and use `await`. A Promise must never enter
durable database data, render Hiccup, context text, or agent output.

Resource contracts apply per request: coordinate, deadline, cancellation,
work/result/value/byte limits, and an error envelope. Multiple ad hoc reads may
still be grouped into `execute-many` by an agent or library without changing
their member shapes.

## Class 6 — removal opportunities

- Remove local-replica observation/capture machinery whose only purpose is
  replaying synchronous reads after the direct authority cut. Retain semantic
  request capture, not a second database-value normalizer.
- Replace repeated `installed-schema` guards with one coordinate-keyed schema
  capability/projection. Guards in `agent.ctx`, `eval`, autocomplete, runtime,
  routes, and warnings should not each become a remote call.
- Remove query-before-pull existence probes where a guarded pull or scalar query
  has the same absence semantics.
- Remove per-row pull loops after Datalog pull expressions return the bounded
  fields directly.
- Delete duplicate code-corpus scans once boot, eval, context namespace cards,
  and autocomplete consume one projection owner.
- Delete the CLJS pre-KNN eid-set query and post-KNN N-pull enrichment after the
  JVM operation returns enriched bounded hits.
- If direct reads graduate, remove Bun Datahike connection/index/store/search/
  query-cache ownership. If the UI-replica option wins, confine that mechanism
  to the active UI owner and never expose it to agent children.

## Async contagion risks

### Render graph

`seon.render`, `seon.render.surface`, and context block functions expect ordinary
values. Making `db/entity` return a Promise transitively infects Hiccup, Datastar
morph generation, cache keys, Malli output instrumentation, and error handling.
The outer render/context call must await one projection; inner render functions
remain pure and synchronous.

### Agent loop and transactions

Read-before-write decisions in run, lifecycle, message, schedule, clear, and
eval persistence can become stale during a remote wait. Freezing a coordinate
solves consistency of the reads, not write validity. Final mutations must carry
expected coordinate/CAS/idempotent request identity. Do not “refresh and hope.”

### Eval and compiler

Self-host compilation calls many synchronous helpers. Converting them one by one
would force `await` through compiler hooks and instrumentation where Promises are
not valid results. Fetch one code projection before compilation and pass data to
the existing synchronous functions.

### Reactive handlers

Committed-event handlers already receive transaction facts and a committed
coordinate. Do not make them query the authority per datom. Extend the event's
bounded indexed projection or schedule one addressable grouped read when the
handler truly needs more data.

### Agent-facing compatibility

Node-era agent examples teach synchronous queries. A full Bun/direct cut changes
composition semantics even when names remain. Update context, toolkit functions,
and schemas atomically so agents learn `^:async`/`await`; do not keep a fake sync
adapter or block the Bun event loop.

## Ordered migration packages

### Package 1 — freeze read shapes without changing execution

- Inventory semantic callers against this report.
- Introduce pure request/result data for query, pull, index, history, and schema
  projection inside the existing `seon.db` owner.
- Add coordinate-explicit local execution so every candidate can run against the
  current replica with no network change.
- Combine existence probes and obvious N+1 pulls.

Exit: local and remote-capable request shapes return identical data at one
coordinate.

### Package 2 — context and render execute-many

- Build one context projection plan and one UI surface projection plan.
- Await once at `rendered-context` and the outer web render/feed owner.
- Keep block/render functions synchronous over returned data.
- Measure direct execution against one active UI replica before topology choice.

Exit: no context/render leaf performs an RPC; p95/p99 and retained memory are
known for direct and UI-replica modes.

### Package 3 — turn, run, message, and web request windows

- Group turn pre-call context at one coordinate and post-write evidence at the
  committed coordinate.
- Consolidate activity, quiescence, run-window, clear, and request evidence
  reads.
- Fence every subsequent mutation with existing coordinate/CAS/idempotency
  semantics.

Exit: agent children can run without a local Datahike connection and preserve
run/turn correctness under concurrent writes.

### Package 4 — code corpus and autocomplete

- Define one coordinate-keyed code-corpus projection.
- Migrate boot, eval, context namespace cards, REPL inspection, and autocomplete
  export.
- Delete duplicate scans and installed-schema probes.

Exit: compiler/eval remains synchronous after one bounded async fetch; export is
addressable and cancellable.

### Package 5 — embedding, KNN, and heavy evidence

- Move filter resolution + KNN + pull enrichment into the existing JVM
  embedding owner.
- Add named debug/eval evidence projections only where combined Datalog remains
  measurably expensive or too large across the wire.
- Run these under distinct provider/KNN/query/encode work classes.

Exit: heavy database/native/provider work never occupies Bun agent heaps or
database B's query/write/control capacity.

### Package 6 — ad hoc async surface and deletion

- Cut agent-authored/toolkit reads to honest asynchronous remote operations.
- Update context and examples atomically for `^:async`/`await`.
- Remove the superseded replica path if direct wins, or restrict one replica to
  the active UI owner if hybrid wins.
- Delete local cache/observation/existence-probe compatibility machinery.

Exit: one `seon.db` semantic API, no fake synchronous transport, and no Datahike
indexes in agent children.

## Decisions for Sean

1. Should context/render plans remain transparent `execute-many` member vectors,
   or should stable hot projections receive public names after measurement?
2. Does active UI latency justify one UI-only replica, or do grouped direct
   projections meet the modern-feeling p95/p99 target with substantially lower
   memory?
3. Should `execute-many` return every member outcome or fail fast for dependent
   context plans?
4. Which compiler/code-corpus fields are stable enough to become one named
   projection without coupling Datahike to the CLJS analyzer?
5. Is the agent-facing async cut acceptable as one coordinated breaking change,
   with no sync compatibility layer?

## Recommendation to carry into the final PRD

Do not migrate hundreds of leaf calls individually. First collapse relational
N+1 reads, then make context, render, turn windows, code corpus, and autocomplete
the asynchronous boundaries. Keep arbitrary agent reads asynchronous and keep
Datahike/JVM internals explicitly local. Move KNN and embedding enrichment closer
to their native owner. This preserves one immutable coordinate per computation,
shares query work, limits serialization, and prevents Promise contagion through
the synchronous ClojureScript render/compiler graph.
