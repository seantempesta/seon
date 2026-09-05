---
type: research
status: active
tags: [prd, research]
---

# Quarry gold inventory

## Question and verdict

The owner asked: **What else is missing from the original system that we can
mine for gold?**

The answer is not another execution engine. The fresh tree has already rebuilt
the hard substrate: cluster boot, one-store ownership, schema admission,
configuration reconciliation, run custody, the Flow run loop, model calls,
errors-as-values, problems, instrumentation, SCI interruption, and result
admission. N4 is designing the render transport and N5 is designing the
program-graph round trip. The richest remaining quarry is the layer that made
those primitives feel like a long-lived agent system:

- durable turn evidence plus the blob archive;
- complete derived context, including a faithful transcript and cache-stable
  ordering;
- database-backed plan and knowledge facts;
- outbound messaging, agent refs, and orchestration views;
- scheduled fires through the same run-opening transaction;
- the agent-facing canvas and capability toolkit; and
- the production `/agents/run` bridge that lets Inspect AI measure the whole
  system.

Those are **gold** because the target architecture still requires them and the
fresh nucleus does not yet provide them. The quarry contributes data shapes,
queries, projections, failure lessons, and acceptance cases. It does **not**
contribute its CLJS pod, self-host evaluator, retained contexts, socket
database client, runtime phase machinery, or duplicate render paths. Those are
lead.

The recommended post-N4/N5 sequence is:

1. evidence archive and turn capture;
2. context continuity plus `my.plan` and `my.kb`;
3. collaboration and outbound messaging;
4. agent-facing canvas and protected capability families;
5. schedules; then
6. the `/agents/run` + Inspect AI graduation gate.

Semantic embeddings, packages, richer debug/data views, and skills remain
valuable later. The complete ordering and falsifiers are in
[[#Recommended mining order]].

## Scope, census, and method

The inventory covers every production `.clj`, `.cljc`, and `.cljs` file below
`src-old/seon/` and `src-old/my/`: **155 files and approximately 75,052 lines**.
Counts are physical lines from `wc -l` on 2026-07-28. They are intentionally
approximate measures of quarry mass, not estimates of code to port.

The ownership maps were read first:

- `src-old/seon/AGENTS.md:13-45` names the one old owner for database,
  reactive reads, schema, context, rendering, errors, config, search,
  embeddings, execution, capabilities, blobs, and evaluation.
- `src-old/seon/agent/AGENTS.md:17-51` decomposes the old engine into loop,
  run, turn, context, filesystem, search, home/runtime, messages, schedules,
  lifecycle, shell, and web.
- `src-old/my/AGENTS.md:16-43` names the editable toolkit: `my.data`, `my.ui`,
  `my.canvas`, `my.kb`, `my.plan`, `my.ns`, `my.skills`, and `my.blob`.

The fresh comparison was made against the live tree, not a cached state file.
The current `src/` has 12,788 lines. Its principal owners are
`seon.cluster`, `seon.cluster.{store,registry,ancestor,run,loop,wake,prompt}`,
`seon.ai`, `seon.error`, `seon.problems`, `seon.instrument`, `seon.config`,
`seon.reconcile`, `seon.flow`, `seon.render`, `seon.sci.{eval,admit}`,
`seon.schema`, and only `my.run` under `src/my/`.

The working edge says N3 is green except for integration proofs and evaluator
adoption, N4's revised contracts await owner decisions, and N5's revised plan
awaits its owner-decision batch
(`plan/unsettled.md:235-249,506-526`). The authoritative ladder defines N4 as
the in-process render pipeline, N5 as the corpus round trip, and N6 as proofs,
gates, and leaves (`plan/README.md:792-835`).

Status terms in this report mean:

- **REBUILT** — a fresh owner exists now and is named.
- **IN FLIGHT** — N4 or N5 owns the active design/implementation boundary.
- **PLANNED** — the N-ladder or a current research plan names the successor,
  but it is not fresh code yet.
- **UNMINED** — neither fresh code nor a current implementation plan owns the
  target behavior.

Where one old family spans several statuses, the row is split by behavior.

## Complete subsystem crosswalk

| Quarry subsystem and entry namespaces | Approximate quarry size | Fresh status and owner | Value |
|---|---:|---|---|
| Database API, writer, schema bridge, IDs, branches, registry, restore, executor, and UDS (`seon.db`, `seon.db.writer`, `seon.db.id`, `seon.db.registry`, `seon.db.transport.uds`) | 20,527 | **REBUILT** in the smaller `seon.cluster.store`, `seon.cluster.registry`, `seon.cluster.ancestor`, `seon.cluster.export`, `seon.schema`, and Datahike `:self` ownership. Old remote writer/session/UDS machinery is lead. | gold lessons, lead implementation |
| Boot, launch, client lifecycle, runtime admission/state (`seon.client`, `seon.launch`, `seon.runtime.*`, `seon.host`) | 5,093 | **REBUILT** by `seon.cluster/start!`/`stop!`, `seon.config`, `seon.reconcile`, `seon.sci.admit`, and the composed boot sequence. The pod lifecycle and child phases die. | rebuilt |
| Run custody, receipts, recovery, run loop, prompt, and model turn (`seon.agent.driver`, `.run.core`, `.turn`, `.prompt`, `seon.ai.*`, `seon.retry`) | about 3,600 | **REBUILT** by `seon.cluster.{run,work,loop,prompt,reply,wake}`, `seon.ai`, and `my.run`. | rebuilt |
| SCI evaluation, interruption, output admission (`seon.sci.*`, old eval surfaces) | 387 plus condemned eval machinery | **REBUILT** by `seon.sci.eval` and `seon.sci.admit`; N3 integration proof remains. Retained contexts and self-host evaluation die. | rebuilt |
| Error normalization, warnings, instrumentation (`seon.error*`, `seon.warn`, `seon.instrument`) | 3,579 | **REBUILT** at the fact/value layer by `seon.error`, `seon.problems`, and `seon.instrument`. The derived warnings context and HTML surface are still part of context/N4. | gold projection |
| Program graph, source parser, edge graph, indexing (`seon.code`, `seon.ns.source`, `seon.program.edge`, `seon.client.indexing`, program portions of `seon.db`) | about 1,500 plus client indexing | **IN FLIGHT N5**. `seon.schema` already owns canonical rows and projections; N5 owns function publication, acquisition, and restart. The custom edge walker and runtime indexers die (`n5-plan-2026-07-27.md:452-460`). | in flight |
| Rich reply parser and repair (`seon.repl.parse`, `.repair`) | 1,940 | **REBUILT SIMPLER** for the current need by `seon.cluster.reply/sources`. Rich auto-repair is not in the ladder. Keep malformed-reply cases as lessons; do not restore mutable preflight splicing. | lead/silver |
| Core rendering, handlers, values, canvas pin, surfaces (`seon.render*`) | 5,094 including root `seon.render` | **IN FLIGHT N4**, with fresh `seon.render` already owning open projection kinds. N4 explicitly adapts the canvas pin and deletes duplicate walkers/validators (`n4-plan-2026-07-27.md:481-489`). | in flight |
| Reactive scheduling and database-interest delivery (`seon.reactive`, web reactive transforms) | 1,248 | **IN FLIGHT N4**. Pure transition ideas survive; generic Promise/timer execution and CLJS transforms die (`n4-plan-2026-07-27.md:102-106,260-299`). | gold lessons |
| HTTP, reitit routes, Datastar SSE, pages, debug/data (`seon.web.*`, `seon.route`) | 4,345 | **IN FLIGHT N4**, but route scope remains an owner decision. N4 names root, header, canvas, context, debug, and `/data` as representable units and must either own their recurring routes or name successors (`n4-plan-2026-07-27.md:185-194,772-781`). | gold lessons |
| Context block core and acquisition (`seon.agent.ctx`, `.acquisition`, `.format`, `.render-fns`) | 2,143 | **UNMINED**. N3 prompt is intentionally minimal; no fresh block collection, selection, or context acquisition exists. | **gold** |
| Namespace/schema context (`seon.agent.ctx.namespaces`) | 1,292 | **PLANNED BY N5 + UNMINED presentation**. N5 supplies program facts and acquisition; no current plan owns relevant-source selection or compact/full context. | **gold** |
| Transcript and token-usage context (`seon.agent.ctx.transcript`, `.usage`) | 1,786 | **UNMINED**. Fresh receipts exist, but there is no faithful bounded REPL narrative, age bands, or usage render. | **gold** |
| Plan context and durable planning toolkit (`my.plan`, `my.plan.internal`, `.generation`) | 4,012 | **UNMINED**. `my.run` is dispositions only. No fresh plan facts, queries, reconciliation, or AI/HTML twin exist. | **gold** |
| Knowledge/memory toolkit (`my.kb`) | 268 | **UNMINED**. No fresh memory namespace or facts exist. | **gold** |
| Messages and outbound collaboration (`seon.agent.message*`) | 770 | **PARTLY REBUILT** for inbound trigger/reply facts in N3; **UNMINED** for agent-authored send, recent history, recipients/hops, and idempotent replay. | **gold** |
| Subagent/orchestration context (`seon.agent.ctx.subagents`, agent parent refs and lifecycle surfaces) | 392 plus lifecycle code | **UNMINED** beyond root seeding and run ownership. Fresh code has no child summary, parent/namespace stewardship surface, spawn/pause/resume/terminate API, or collaboration view. | **gold** |
| Schedules/ticker/breaker | no surviving `schedule.cljs`; remnants in `seon.agent.core`, `seon.derive`, config, turn, and context | **UNMINED**. The target requires a schedule proc, but the quarry no longer contains the claimed entry namespace. | **gold target, sparse quarry** |
| Blob API, host archive, overlays, restore support (`my.blob*`) | 807 | **PLANNED** generically by N3's later capability owners, but no rung owns it. Turn capture and forensics need it. | **gold** |
| Turn capture, replay, diff, reproduction (`seon.agent.debug`, `seon.web.debug`, turn/blob projections) | 833 plus turn schemas | **PARTLY REBUILT** as receipts/errors/problems; **UNMINED** for exact prompt/reply blobs, turn bundle, turn diff, reproduction, and historical context. | **gold** |
| Literal search (`seon.agent.search`, folded into old agent family) | quarry owner documented but entry file already absent | **UNMINED**. No fresh `grep`/`grep-graph`; toolkit architecture still requires protected literal search. | silver/gold |
| Semantic embeddings (`seon.embed`, `.preflight`) | 1,493 | **UNMINED**. No fresh embedding attribute, Vertex/Gemini batcher, index lifecycle, transaction augmentation, backfill, or KNN. | **silver** |
| Filesystem and anchored edit protocol (`seon.agent.fs.core`, `.match`, `.leaf`) | 977 | **PLANNED** as an ordinary later capability owner, not implemented. Its portable exact/near/conservative matcher is valuable; its env/atom grant leaf needs redesign under `seon.effect`. | gold tool, not core |
| Shell capability (`seon.agent.shell.*`) | 444 | **PLANNED** as a later leaf. No fresh effect owner or shell API. | silver |
| Web fetch/search capability (`seon.agent.web*`) | 719 | **PLANNED** as a later leaf. No fresh protected web tool. | gold |
| Browser interaction receipts/callback gate (`seon.agent.interaction*`, `seon.web.reactive.call`) | 975 | **PLANNED/IN FLIGHT N4** for the browser gate, but agent interaction history is not fresh. | gold after N4 |
| Agent canvas/UI toolkit (`my.canvas`, `my.ui`) | 575 | **PLANNED N6** by N4, which explicitly excludes it from N4 (`n4-plan-2026-07-27.md:488`). No fresh controls or canvas write API. | **gold** |
| Skills (`my.skills`) | 106 | **UNMINED**. Config currently has no skill population or explicit list/import/load surface. | silver |
| Namespace discovery (`my.ns`, behavior formerly embedded in context/render code) | no standalone file in this snapshot | **PLANNED BY N5**, but compact/full selection and discovery UI remain unowned. | gold |
| Small data composition (`my.data`) | no standalone file in this snapshot | **UNMINED**, but only as a convenience layer. The target names small data transformation/presentation composition at `docs/seon/architecture/toolkit.md:62-79`; no unique runtime mechanism depends on it. | silver/lead |
| Config manifest and context profiles (`seon.config`, `seon.config.resolve`) | 3,537 | **REBUILT** for the small nucleus by fresh `seon.config` + `seon.reconcile`; **UNMINED** for context blocks, namespace policy, skills, routes, render caps, profiles, and home requirements. | gold subset |
| Token estimator (`seon.ai.tokens`) | 253 | **UNMINED**. The architecture still requires estimated-token display, but fresh `seon.ai` has no estimator. | silver, context dependency |
| Packages and native leaves (`seon.packages`) | 435 | **PLANNED N6** as disposable leaves. Old pod/package-host details are lead. | silver |
| Inspect AI production integration (`POST /agents/run`, `src-inspect-ai`) | endpoint embedded in old web server; external package remains | **UNMINED IN FRESH RUNTIME**. N6 names proofs/gates, but no fresh HTTP endpoint currently satisfies the existing solver. | **gold graduation gate** |
| Developer markdown/docstring/test tooling (`seon.dev.*`, `seon.test.runner`) | 3,344 | **PLANNED N6 / external script owners**. Keep Markdown/docstring laws and JVM test selection; the CLJS runner dies. | silver |
| Diffusion/typeahead worker experiment (`seon.diffusion.*`, optional provider pieces) | at least 73 here, larger code already outside this quarry | **NOT ON THE NUCLEUS LADDER**. Owner explicitly preserved it experimentally, but main runtime must not require it. | lead for this wave |
| CLJS-only client, route, derive, log, platform, test runner, web UI and database session machinery | more than 15,000 across rows above | **DEAD BY GREAT DELETION**. Browser is static; cluster JVM owns database, loop, render, and HTTP. | lead |

The table intentionally separates old implementation mass from product value.
For example, the 20,527-line database family does not justify mining a second
database API; its surviving gold is already expressed by the fresh 475-line
store owner and the schema/branch owners.

## Unmined gold and silver

### 1. Complete derived context — gold

**What the quarry did.** A context was an ordered collection of
`:seon.agent.ctx/block` values with name, priority, token cap, and AI/HTML
renders (`src-old/seon/agent/ctx.cljc:49-78`). The old owner selected an
agent's complete block collection (`:1619-1629`), rendered it from one acquired
database value (`:1779-1817`), split stable and volatile text for provider-cache
control (`:1499-1539`), and derived block-chain cache keys (`:1876-1959`).
Context families supplied namespace source, transcript, warnings, canvas,
menu, subagents, and usage. The 7,481-line combined `ctx` family was too large,
but it encoded the product's continuity model.

**Why it existed.** An agent is cold at every turn. It feels continuous only
when the prompt includes what opened the run, where it is, what it waits on,
what it did, what it learned, and what changed. The target makes this explicit
at `docs/seon/architecture/context.md:54-117`. The transcript is the
authoritative narrative spine, while plan, findings, subagents, and deltas are
additive projections (`context.md:119-187`).

**Fresh gap.** `seon.cluster.prompt/prompt` currently derives a deliberately
minimal message/receipt prompt. There is no fresh block schema, selector,
current-namespace renderer discovery, stable ordering, cache boundary, or
complete-context acquisition. N4 supplies the shared render execution and N5
supplies admitted program facts, but neither owns context composition.

**Mine, do not port.** Keep:

- one immutable database value per prompt;
- complete situation rows;
- deterministic order and byte stability;
- bounded transcript age bands rather than summaries;
- current-namespace relevant source and schema contracts;
- AI/HTML twins from the same function; and
- derived warnings/subagent/delta sections that omit themselves when empty.

Delete by ignoring:

- async CLJS acquisition batches and member envelopes;
- separate child compilation;
- file-based identity blocks;
- context-chain caches before measurement;
- parked capability enums; and
- any block output stored as durable state.

The fresh form should be a small pure context plan over `db`, evaluated through
N4's one render owner and populated from N5's program facts. This needs an owner
ruling on seed-copy versus purely derived current-namespace membership because
the architecture currently preserves both explicit `install!` overrides and
derived auto-run (`context.md:287-324`).

### 2. Faithful transcript, turn capture, and reproduction — gold

**What the quarry did.** The transcript family acquired evals and messages,
interleaved them, rendered namespace transitions and results, clipped by turn
window and stable decay tiers, and emitted both AI and HTML
(`src-old/seon/agent/ctx/transcript.cljc:118-287,381-634,703-776,1121-1277,
1434-1612`). `seon.agent.debug/turn` retrieved exact prompt/reply blobs and
token counts (`src-old/seon/agent/debug.cljs:23-33,51-139`);
`turn-diff` compared prompt lines and basis transactions (`:141-219`); `repro`
built a runnable expression from persisted error data (`:423-527`).

**Target.** A turn record connects the exact database value, trigger, model
attempts, eval receipts, prompt blob, reply blob, and usage. The archive stores
large evidence by SHA-256 and keeps queryable database projections
(`docs/seon/architecture/observability.md:23-173`). Replay, diff, and search are
first-class operations (`observability.md:175-210`).

**Fresh gap.** Fresh receipts and provider-attempt facts are a strong spine,
and `seon.error`/`seon.problems` make failures queryable. But prompt/reply
bytes are not in blobs; there is no complete turn bundle, historical context
re-render, diff, or reproduction bundle. Without this, N6 cannot prove
byte-grounded context or Inspect transport evidence.

**Mine, do not port.** The old debug `.cljs` files die. Mine their query
contracts and the invariant that the debugger reads the same persisted evidence
the loop used. Land capture at the run-loop commit boundaries, with large bytes
in the blob archive. Do not restore a debug file tree or a second capture path.

### 3. Blob archive and three-tier storage — gold

**What the quarry did.** `my.blob` exposed bounded `put`, `get`, `concat`,
`text`, and `stat` shapes (`src-old/my/blob.cljc:123-194,286-425`). The JVM
leaf published SHA-256-named bytes durably with directory/file fsync and atomic
rename, then verified reads (`src-old/my/blob/host.clj:65-155,155-220`). The
pure core selected overlay-first sources, paged lines, concatenated chunks, and
derived retained hashes (`src-old/my/blob/core.cljc:6-79`). Branch restore used
a writable overlay plus ordered read-only bases
(`src-old/my/blob/schema.cljc:10-45`).

**Target.** The database keeps hash, estimated tokens, media hint, and time;
large bytes live in the content-addressed archive. Reads are bounded and honest.
Normal clusters have no bases; lifecycle branches write an overlay and verify
source bytes (`docs/seon/architecture/toolkit.md:272-285`;
`observability.md:130-173`).

**Fresh gap.** No `my.blob`, protected blob family, blob schema, or archive
owner exists. N3 merely says blob arrives as an ordinary capability when
needed (`plan/README.md:792-801`). It is needed now by turn capture, bounded
stack evidence, Inspect, and any large agent result.

**Mine, do not port.** Mine the content identity, durable publisher, overlay
read order, bounded line view, and database projection. Replace dynamic leaf
binding and CLJS async ceremony with a plain JVM owner behind `seon.effect`.
Compression, remote placement, garbage collection, and promotion remain
explicitly outside the target and should not block the first archive.

### 4. Durable plan and database-backed memory — gold

**What the quarry did.** `my.plan` modeled per-agent plan nodes with identity,
status, parent, dependency refs, goal, falsifiable expectation, and pace
(`src-old/my/plan.cljc:24-43`). Its pure internal owner derived blocked/ready
state, rollups, active steps, ancestors, trees, and reconciliation
(`src-old/my/plan/internal.cljc:77-233,679-740`). It rendered AI and HTML twins
(`:1658-1814,2035-2120`). The public surface reconciled a tree, listed and
positioned work, and connected generated-program evidence
(`src-old/my/plan.cljc:282-384,390-527`).

`my.kb` demonstrated schema-first durable memory: `remember` wrote a finding
with claim, sources, line ranges, verification time, and confidence
(`src-old/my/kb.cljc:16-54,81-159`), while ordinary queries retrieved titles,
authors, detail, and source entities (`:205-266`).

**Why they existed.** The plan stores externalized intent that cannot be
reconstructed after the transcript decays; knowledge stores settled facts, not
live work state. The target names both as completeness requirements
(`context.md:74-104`) and explicitly retains `my.plan` and `my.kb`
(`toolkit.md:233-245`).

**Fresh gap.** `my.run` only returns `wait`/`complete` disposition values.
There is no plan or memory schema, query, transaction function, reconciliation,
or context render.

**Mine, do not port.** Start smaller than the 4,012-line old plan:

- one plan-node schema with parent and needs refs;
- one transaction function for reconcile;
- pure `ready?`, rollup, active focus, and next-work derivations;
- one AI/HTML twin; and
- one `my.kb` worked example with `remember` and `recall`.

Do not port generated-code scheduler state, consult escalation, async
acquisition batches, compatibility markdown, or duplicate status fields.
Plan status should remain the minimum non-derivable intent; readiness and
rollups are derived.

An owner ruling is needed on whether explicit `:active` remains stored or
whether active focus is another ref/position fact from which status derives.

### 5. Outbound messaging and collaboration refs — gold

**What the quarry did.** `seon.agent.message` read recent messages, normalized
recipients, built message transactions, determined whether an inbound message
should wake an agent, and sent messages through one leaf
(`src-old/seon/agent/message.cljc:114-212,291-329,329-434,434-581`). Its
internal acquisition resolved participants and bounded human-message context
(`src-old/seon/agent/message/internal.cljc:30-113`). The subagents block joined
direct children, open/closed runs, crash counts, and breaker state into one
derived section (`src-old/seon/agent/ctx/subagents.cljc:95-240`).

**Target.** Parent refs form the orchestration tree; root creates, controls, and
messages agents through transactions. Messages and due schedules open runs
through the same writer; messages arriving during a run become consumed-input
edges (`docs/seon/architecture/agent-runtime.md:287-305`). The target context
must show delegated children and their live state (`context.md:88-91,506-530`).

**Fresh gap.** N3 has the minimal inbound message facts and one
message-triggered run. It does not expose agent-authored send, recipients,
parent/child management, recent message queries, consumed-input edges, or
idempotent send replay. The N3 audit itself warns that its
`:seon.cluster.message/*` vocabulary may collide with the later messaging
design (`n3-plan-2026-07-27.md:803-811`).

**Mine, do not port.** Keep messages as durable facts and open runs through the
same transaction owner. Give send the run/form/effect identity so crash
re-execution cannot double-send. Derive delivery and child summaries; do not
store read/ack/state flags or recreate a collaboration channel.

Owner rulings are required for:

- the final message attribute namespace and identity;
- how an in-run message extends the work window;
- root/ordinary-agent authority for birth, termination, and reassignment; and
- whether parent and namespace-steward refs land in the same rung.

### 6. Scheduled fires — gold target, sparse quarry

**Honesty finding.** `src-old/seon/agent/AGENTS.md:48-50` names
`message.cljs / schedule.cljs / lifecycle.cljs`, but there is no
`src-old/seon/agent/schedule.cljs` in this quarry. It was deleted before this
snapshot or the ownership map drifted. A complete old scheduler cannot be
inventoried honestly.

What survives:

- an agent's schedule refs in `src-old/seon/agent/core.cljc:14-31`;
- scheduled-turn facts in `src-old/seon/agent/turn.cljc:22-86`;
- schedule exclusions and the derived crash-window breaker in
  `src-old/seon/derive.cljs:88-101,219-270`;
- configuration for breaker count/window in
  `src-old/seon/config/resolve.cljc:462-470,928-941`; and
- transcript/subagent logic that distinguishes scheduled turns
  (`src-old/seon/agent/ctx/transcript.cljc:789-806`;
  `src-old/seon/agent/ctx/subagents.cljc:88-93`).

**Target.** One schedule proc derives due facts and opens runs through the same
Flow graph; it owns neither execution nor heartbeat
(`agent-runtime.md:287-297`).

**Fresh gap.** There are no schedule schemas, due query, proc, or run-opening
transition. This is not in N4, N5, or a detailed successor plan.

**Mine.** Mine the facts and breaker lesson, not a missing implementation.
Design fresh around one schedule identity, recurrence data, next due instant,
and the existing N3 run-opening transaction. Derived breaker state should omit
itself when healthy; never store a tripped flag.

An owner ruling is needed on the minimum recurrence language. A one-shot
instant plus a data-driven recurrence successor is safer than reconstructing a
cron subsystem from memory.

### 7. Canvas, UI controls, routes, and browser actions — gold after N4

**What the quarry did.** `my.canvas` pinned a literal or function-backed focal
view, read and wrote canvas-local state, and supplied buttons, inputs, selects,
toggles, and forms (`src-old/my/canvas.cljc:42-73,118-200,214-303`).
`my.ui` composed status lines, key/value tables, badges, bullets, progress,
tables, and sections (`src-old/my/ui.cljc:57-272`). The callback gate validated
an admitted handler request before opening/settling interaction facts
(`src-old/seon/agent/interaction.cljc:24-50,115-300`). Old route facts mapped
method/pattern/name/owner/handler/middleware
(`src-old/seon/route.cljs:43-89`), and `db->routes` derived reitit data
(`src-old/seon/web/router.cljs:183-215`).

**Target.** The agent page, root page, debug page, and apps share one render and
route tree (`docs/seon/architecture/ui.md:222-331`). Browser actions enter one
`/agent/{id}/call` authorization boundary; controls are ordinary Clojure
handlers and Datastar signals (`ui.md:362-417`).

**Fresh gap.** N4 handles render/SSE mechanics and may land core routes, but
explicitly marks `my.canvas` and `my.ui` as later N5/N6 surface
(`n4-plan-2026-07-27.md:488`). There is no fresh browser callback gate or
agent-facing control API.

**Mine, do not port.** After N4 settles route scope, mine:

- the durable canvas pin only;
- pure Hiccup constructors;
- one browser action endpoint authorized from program facts;
- controls rewritten to that endpoint; and
- optional interaction receipts only where they are non-derivable evidence.

Do not port the CLJS router/server, canvas structural validator, renderer
registry, whole-page duplicate walker, or stored selected-view projections.

### 8. Protected capability breadth and anchored file edits — gold/silver

**What the quarry did.** The filesystem core registered one bounded result
vocabulary and pure policy decisions (`src-old/seon/agent/fs/core.cljc:10-81`).
The JVM leaf default-denied outside configured roots, paged reads, refused
malformed Clojure writes, and offered exact line-range or unique-string edits
(`src-old/seon/agent/fs/leaf.clj:21-78,105-165,174-246`). The portable matcher
made deterministic exact, nearby, and conservative whitespace-normalized
decisions, returning ambiguity candidates rather than fuzzy-authorizing a
mutation (`src-old/seon/agent/fs/match.cljc:1-87,115-180`). Parallel old
families provided bounded shell and web calls.

**Target.** `my.fs`, `my.shell`, and `my.web` are flat tools whose protected
family cores enter `seon.effect/request!`; bounds and policy belong at their
owning edges (`docs/seon/architecture/toolkit.md:62-79,113-181`).

**Fresh gap.** No `seon.effect` or flat capability namespaces exist. N3 names
these as ordinary future owners, while N6 names leaves generally; neither is a
sealed plan.

**Mine, do not port.** The anchored matcher is real gold for safe external
repository edits. Keep its pure decision data and ambiguity refusal. Rebuild
grants as config/database facts and execute through the one effect receipt
boundary. Never allow these tools to write the protected base program graph to
disk: agent overrides remain program-fact transactions.

Mining priority inside this family is blob → web read/fetch → filesystem
read/edit → shell. Each family needs a separate replay/receipt decision, but
not a separate dispatch protocol.

### 9. Embeddings and semantic search — silver

**What the quarry did.** `seon.embed` registered one 768-dimensional
`:seon/embedding`, a Proximum index, configured embeddable trigger attributes,
batched Vertex/Gemini requests, normalized vectors, augmented transactions,
backfilled missing rows, and performed KNN
(`src-old/seon/embed.clj:104-187,269-380,463-503,503-590,629-842,
943-1058,1086-1277`). Preflight checked Java vector support, credentials,
round trip, and KNN (`src-old/seon/embed/preflight.clj:30-107,178-202`).

**Target.** Literal regex and one semantic index are the two search ends; no
second FTS/index system (`docs/seon/architecture/observability.md:197-210`).
Embedding work follows committed facts and the same claim/effect laws
(`docs/seon/architecture/architecture.md:315-320`).

**Fresh gap.** Nothing fresh owns embeddings or semantic search.

**Mine later.** The single attribute/index, compose functions, source hash,
bounded batching, and backfill are valuable. The 1,288-line owner also contains
old direct Datahike/Vertex lifecycle coupling and banned `:any` schemas
(`src-old/seon/embed.clj:902-930`), so it must be redesigned, not moved.
Prioritize it only after exact turn/blob/context search exists and a measured
retrieval task shows semantic search pays.

### 10. Config-driven context, skills, and home requirements — gold subset

**What the quarry did.** The manifest selected agent/root context, namespace
policy, skills, routes, render/database bounds, model variants, and home
requirements. Context blocks merged by block name
(`src-old/seon/config/resolve.cljc:827-861,1007-1108,1404-1456,1557-1593`);
the CLJS owner loaded and resolved one manifest
(`src-old/seon/config.cljs:132-176,1089-1176`).

**Fresh state.** `seon.config/read-manifest`, `desired-rows`, `apply!`, and
`effective` rebuild the right desired-state pattern
(`src/seon/config.cljc:81-201`). The current schema is intentionally small.

**Mine.** Accrete only the facts demanded by the new context/toolkit:
block overrides, home requirements, namespace detail selections, skills
population, route rows, and token/render caps. Do not restore the old 39-dial
singleton, runtime env readers, profiles before a measured user, or code
fallback constants. This is an accretion to the fresh config/reconcile owner,
not a config port.

### 11. Inspect AI bridge — gold graduation gate

**What the quarry did.** The old web owner exposed `POST /agents/run`: create or
reuse an agent in the real cluster, deliver through the real wake path, await
derived idle, and return reply plus termination metadata
(`src-old/seon/web/AGENTS.md:15-24`). The target defines it as the one-shot
composition endpoint and requires transport evidence for Inspect
(`docs/seon/architecture/observability.md:325-355`).

The external harness remains real and extensive. Its solver is explicitly the
`/agents/run` bridge (`src-inspect-ai/src/seon_inspect/solver.py:1-52`), and
the package README says Inspect never manages the agent's internal turns
(`src-inspect-ai/README.md:1-15`).

**Fresh gap.** There is no HTTP server or `/agents/run` endpoint in the fresh
tree. N4 may land the web shell, but its route decision focuses on recurring
root/canvas/debug/data proof; N6 says proofs/gates without sealing this
endpoint. The existing Inspect package therefore cannot currently measure a
fresh cluster.

**Mine, do not port.** Build a thin Ring endpoint over the fresh run-opening
and completion facts after N4/N5 and collaboration/context exist. It must not
run a private FSM, scratch database, or alternate evaluator. Update Inspect
source admission from the old `src` assumptions at the same boundary; the
known stale admission risk is already recorded at
`testing-story-2026-07-27.md:732-756`.

### 12. Lead — delete by ignoring

The following old mechanisms should not receive successor rungs:

- `seon.client`'s CLJS pod launch/index/runtime orchestration
  (`src-old/seon/client.cljs`, 2,735 lines);
- the CLJS database session/fiber/UDS client and separate writer protocol;
- `seon.runtime.admission` and `.state` phase machines;
- self-host `cljs.js`, retained per-agent contexts, replay-every-eval resume,
  result/shared-var plumbing, and Promise/async ceremony;
- `seon.web.datastar`/`serve`/`router` as CLJS implementations;
- stored rendered output, duplicate render walkers, and renderer-local
  listeners;
- custom call-graph placement heuristics superseded by N5's admitted program
  facts and derived projections;
- schedule state flags, acknowledgement flags, warning queues, or stored
  rollups; and
- the optional diffusion/typeahead worker path as part of the nucleus.

Their behavior is either rebuilt more simply, condemned explicitly by the
great-deletion doctrine, or outside the current program. Source can still
teach failure cases, but none of it should be renamed into fresh `src/`.

## Recommended mining order

This ordering begins only after N4 and N5 close. It is a proposed successor
wave, not a competing order for the active N-ladder.

### Q1 — Evidence archive and exact turn record

**Dependency edge:** N3 receipts/attempts and N5 program reconstruction;
N4's projection kind/rendering is useful but not required for the first
database/blob capture.

**Mine from:** `src-old/my/blob{.cljc,/core.cljc,/host.clj}`;
`src-old/seon/agent/debug.cljs`; `src-old/seon/agent/ctx/transcript.cljc`;
`docs/seon/architecture/observability.md:23-210`.

**Deliver:** one JVM blob archive, prompt/reply refs on the turn, exact turn
bundle, and turn-diff/reproduction query values.

**Falsifier:** run one real model turn, kill and restart the cluster JVM, then
retrieve byte-identical prompt and reply by turn id and reproduce every
terminal eval input/result from database facts plus blobs.

**Owner ruling:** whether the first archive includes branch overlays now or
lands the normal-cluster writable directory first. Recommendation: implement
the storage-view shape and normal path now; prove overlays in the first branch
consumer.

### Q2 — Continuity: context spine, `my.plan`, and `my.kb`

**Dependency edge:** Q1 exact turn evidence; N4 one render execution; N5
current-namespace program facts.

**Mine from:** `src-old/seon/agent/ctx.cljc`;
`ctx/{transcript,namespaces,warnings,usage}.cljc`;
`src-old/my/plan{.cljc,/internal.cljc}`; `src-old/my/kb.cljc`.

**Deliver:** one deterministic context plan over an immutable database value,
a bounded faithful transcript, minimal durable plan facts/reconciliation, and
a minimal knowledge remember/recall example. AI/HTML twins ride N4.

**Falsifier:** after 100 synthetic turns and a process restart, the next prompt
is byte-identical for the same database value, names the current plan focus and
last action correctly, recalls a fact stored before the transcript window, and
contains no unsupported self-state claim.

**Owner rulings:** seed-copy versus derived membership boundary; minimum stored
plan state; and whether cache-chain keys wait for measurement. Recommendation:
explicit overrides plus derived current namespace, minimum intent facts, no
cache until measured.

### Q3 — Collaboration, messages, and agent refs

**Dependency edge:** N3 run opening and effect identity; Q2 context sections;
N5 shared program functions.

**Mine from:** `src-old/seon/agent/message{.cljc,/internal.cljc}`;
`ctx/subagents.cljc`; lifecycle and root-view queries.

**Deliver:** idempotent agent-authored send, recipient/parent refs, consumed
input edges, root child lifecycle operations, and one derived collaboration
context/surface.

**Falsifier:** kill the cluster process after a send commits but before the
sending run closes; after restart the recipient observes exactly one message,
opens at most one run, and both sender and root derive the same child/message
state without an acknowledgement flag.

**Owner rulings:** final message vocabulary, in-run input behavior, lifecycle
authority, and namespace stewardship scope.

### Q4 — Agent-facing canvas and capability families

**Dependency edge:** N4 routes/render/SSE and callback boundary; N5 callable
program facts; Q1 `seon.effect`/blob receipt precedent; Q3 caller identity.

**Mine from:** `src-old/my/{canvas,ui,blob}.cljc`;
`src-old/seon/agent/{fs,shell,web,interaction}`; `src-old/seon/route.cljs`;
the anchored matcher.

**Deliver:** `my.canvas` + `my.ui`, one authorized browser action endpoint,
`seon.effect/request!`, and the first flat protected families in priority order:
blob, web read/fetch, filesystem read/edit, then shell.

**Falsifier:** an agent authors a schema-complete handler and canvas function,
the human invokes it from two tabs, one durable fact changes once, both tabs
converge through N4, and a crash at every effect boundary neither double-fires
nor silently retries an ambiguous external mutation.

**Owner rulings:** first effect-family replay rows and which routes N4 has
already closed. Do not start until N4's route-scope decision is final.

### Q5 — Scheduled fires

**Dependency edge:** N3 run-opening transaction and Flow graph; Q2 context;
Q3 message/run consumed-input semantics.

**Mine from:** the schedule remnants in `agent/core.cljc`, `agent/turn.cljc`,
`derive.cljs`, config, transcript, and subagents. There is no complete old
schedule owner.

**Deliver:** one schedule fact shape, one due-work query/proc, one run-opening
transition, and a derived crash-window breaker/problem.

**Falsifier:** schedule 1,000 one-shot fires including equal instants, restart
before and after due time, and prove each schedule opens at most one run while
an already-open agent consumes the input without a second concurrent run.

**Owner ruling:** minimum recurrence language. Recommendation: seal one-shot
first and add recurrence as a pure next-instant function over data.

### Q6 — Production composition endpoint and Inspect graduation

**Dependency edge:** N4 HTTP; N5 corpus; Q1 evidence; Q2 continuity; Q3
messaging; Q4 usable tools. Q5 is required for long-horizon scheduled evals but
not for the first one-shot endpoint.

**Mine from:** the behavioral contract in `src-old/seon/web/AGENTS.md:15-24`,
the remaining `src-inspect-ai` solver/tasks/scorers, and
`docs/seon/architecture/observability.md:325-355`.

**Deliver:** the fresh `/agents/run` endpoint over production run facts,
updated source-admission locks, and the existing Inspect tasks pointed at a
fresh named cluster.

**Falsifier:** run the long-term planning + memory task across a real cluster
restart: phase two queries a schema'd fact written in phase one, resumes the
same plan, and the scorecard accepts only evidence returned by the production
endpoint.

**Owner ruling:** whether this is the closing part of N6 or the final successor
rung. Recommendation: make it the graduation rung; a runtime that cannot be
measured through its production behavior is not complete.

## Later silver and measured triggers

After Q6, mine only on evidence:

- **Embeddings/search:** when literal retrieval fails a frozen context or
  knowledge benchmark, rebuild one `:seon/embedding` path from the old batch,
  source-hash, backfill, and KNN lessons.
- **Skills:** when an Inspect A/B demonstrates explicit skill import improves a
  task, add canonical skill facts and explicit load/list through the context
  block mechanism. Do not inject a standing skills manual.
- **Packages/disposable leaves:** when a required capability cannot run in the
  cluster JVM, add its native package manifest and one leaf behind
  `seon.effect`; do not restore a package-host subsystem first.
- **Rich parser repair:** when measured model replies fail on repairable syntax
  often enough to matter, add one pure pre-freeze repair transform. Never
  splice a committed plan during execution.
- **Fine-grained debug/data views:** accrete routes and render units after the
  Q1 query values exist; no debug page should define a second evidence path.

## Coverage limits and unresolved evidence

- The inventory is complete by production source file and major namespace
  family, but it is not a per-public-var ledger. The earlier execution
  capability census already covers the deleted runtime surface in
  [[capability-ledger-2026-07-26]]; duplicating its 49 rows would obscure the
  product-layer gaps this report was asked to find.
- The claimed schedule entry namespace is absent. Schedule conclusions are
  limited to surviving schemas, derived logic, config, context, and the target
  architecture. No implementation behavior is inferred.
- `seon.agent.search`, standalone `my.ns`, and standalone `my.data` owners
  named by ownership/architecture maps are absent as files in this snapshot.
  Their target behavior is grounded in architecture and surviving
  callers/docs, not a source implementation that is no longer here.
- Diffusion code is split across other top-level source trees and archived
  artifacts. This report inventories only the `src-old/seon` and `src-old/my`
  scope the owner requested; it does not claim a complete diffusion census.
- N4's route-scope and N5's seven owner decisions were unresolved at the
  working edge. Rows depending on them are deliberately labeled
  planned/in-flight rather than pretending an owner has been selected.
- Approximate sizes include schemas, docs, and platform branches inside each
  source file. They measure review surface, not retained value.

## Final answer

The original system's remaining gold is not hidden in its old engine. It is in
the **continuity and composition layer** that the fresh engine has not reached:
evidence, context, plan, knowledge, collaboration, canvas/tools, schedules, and
measurement through Inspect. Mine those in that dependency order.

The strongest immediate successor after N4/N5 is Q1, the evidence archive and
exact turn record. It unlocks honest context, forensics, large results, and the
Inspect bridge at once. The strongest deletion conclusion is equally clear:
ignore every pod/self-host/CLJS-only mechanism even when it once implemented a
valuable behavior. Keep the behavior's data contract and falsifier; rebuild it
on the fresh JVM owners or do not rebuild it at all.
