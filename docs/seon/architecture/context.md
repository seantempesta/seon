---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — functions applied to the db

> **Target design** (present tense). The block/render machinery lives in
> [[ui]]; historical turn reconstruction + inspection in [[observability]]; the measured laws
> that constrain this in [[laws]]. Implementation state lives in [[roadmap]]. This doc keeps
> to Clojure primitives — `ns`, `defn`, `require`, var metadata, and a db value.
> A block is the informal name for one render-function call in either
> projection, never a stored data type.

The prompt is a **tree of render units rooted at one agent entity**. Schema'd
data are renderable leaves and refs are branches. The recursive walk discovers
the tree from one immutable database value; a separate display projection
orders the resulting units and joins their AI bytes.

Every turn re-derives the tree from one frozen db value. Nothing is accumulated.
The agent, cluster, current turn, namespace, window policy, tool/schema graph,
plan, event membership, and tree edges are facts or pure queries over that
value. Rendering the same database value (the same store ID, branch, commit ID,
and basis transaction), code revision, and explicit render arguments for the
same agent produces a byte-identical body.
Filesystem state, process-local cache membership, random ids, and map iteration
order never leak into it. Which functions are in scope, and in what order, is
the entire design — and whether that set is **complete** is what makes the agent
feel stateful.

Read reuse cannot weaken that determinism. Datahike owns immutable eager-result
entries and their parsed dependency plans at exact database source identities.
A materialized committed value without retained exact attribute revisions uses
its own commit ID as a conservative cache revision; it cannot inherit a result
from another commit merely because both lack revision history. The render
therefore sees the selected database value whether a read computes, joins
single-flight, or hits cache.

Render caching is **per function call**: `(renderer fn × explicit args) →
bytes`. Each process-local entry retains the bytes and digest, the call's
attribute dependency set, last-seen per-attribute commit IDs, the conservative
database revision, the process-local code revision, and the basis at which its
bytes last changed. A call is stale when any dependency commit ID differs by
`not=`, the conservative revision moves, or the code revision moves. The walk
may therefore call renderers dumbly and compose independently cached results;
the cache is losable performance state, never another truth.

Live root telemetry is an ordinary first-party render unit with explicit
process-local inputs, not a tail outside the block system. It is comment-shaped,
capped at roughly 50 estimated tokens, and omitted when normal. Whether fleet
ping summaries become durable facts remains a separate data-model decision;
the render contract does not invent persistence for them. The sent prompt blob
is the byte ground truth for process-local inputs, while the recorded database
value and code revision regenerate the database-derived calls.

## The projection must be complete — so the agent feels stateful

An agent carries nothing between turns; every turn is a cold start from one db
value. Yet it must *behave* as though it remembered — resume its work, act on
what just happened, notice what changed. It can, because a stateless process is
indistinguishable from a stateful one exactly when its rendered context is a
**complete and faithful projection of its situation**: everything a continuous
being would carry forward, re-derived each turn from the db, with no gap left
for the model to fill.

**Confabulation is the diagnostic.** When the render omits or garbles part of
the agent's situation, the model does not fail loudly — it patches the hole
from its training prior: it invents a restart that did not happen, a user
instruction never sent, a task already finished. Every such ungrounded
self-claim is the visible tell of an incomplete projection, and it names the
section to fix. The invariant a correct context satisfies: **an agent makes no
claim about its own state or history that the rendered datoms did not
contain.** This is measurable — replay the byte-exact prompt ([[observability]])
and ground every self-claim against it — and it is the standing acceptance
test for context, not a one-time check.

**The completeness model.** A continuous agent always knows, so the projection
always renders:

- **what just happened** — the event that opened this run (an inbound message,
  a child's outcome notice, a schedule), in the present tense. Absent this, the
  most salient standing frame in the prompt wins by default — so evergreen
  advice ("after a restart, resume your plan") is **conditional/derived**,
  rendered only when its condition actually holds, never planted every turn.
  A self-healing cluster JVM replacement is one such condition: the next
  turn names the interrupted eval and process failure, confirms that current
  functions/schemas/tests were reconstructed from database program facts, and
  states that live `result/<id>` values and other process-local state
  were lost. It does not imply that committed transactions were rolled back or
  that the interrupted form was replayed.
- **where I am** — the plan, rendered with unambiguous status. The plan is not
  a checklist; it is **externalized intent** — the one thing a stateless
  boundary cannot reconstruct unless it was written down as data. An open step
  never renders as a settled fact; a node whose only remaining action is
  verify-and-close renders as actionable, not invisible. (See [[data-model]].)
- **what I am waiting on** — delegated children and their live state (the
  multi-agent sections below), blocked items.
- **what I just did** — my last turn's actions and their outcomes, from the
  transcript.
- **what I learned** — accumulated knowledge (`my.kb`), and *only* knowledge:
  work-tracking that still carries a live lifecycle status is not a settled
  finding and never renders as one.
- **what changed since I last looked** — the **delta**, derived from the
  previous turn's rendered database value: the
  datoms transacted since I last saw the view when that commit is an ancestor
  of the current head (new messages, newly-completed children, newly-failed
  items). A series of independent snapshots becomes a felt continuity because
  each turn can name what is new. The exact database value is already recorded per
  turn ([[observability]]); the delta is a query over it, not new state. A
  lineage change renders a reset/diff boundary instead of pretending bare t is
  continuous.

**Situation, never the answer.** The projection renders the agent's operational
situation and the operations available on it — "a child is idle at its
turn-limit; continue it or release it," "three plan items are open and
independent; any may be delegated" — because a continuous agent would know
these. It never renders the answer to the agent's task; that is the line
between context and coaching. Making the situation legible makes the right
action obvious without prescribing it.

## The transcript is the spine — the REPL narrative the rest attaches to

The completeness rows are not peers. One is the **spine**: the transcript —
the agent's own eval log rendered as a REPL session ("I evaluated X, got Y; a
message arrived; I evaluated Z"). A snapshot section (plan, findings,
subagents) is a photo of *now* with no story; a REPL narrative is inherently
stateful, because it is the ordered record of what the agent actually did and
what actually happened to it. The eval log is one view of the program graph
(code-as-data); the transcript is its faithful render, and it is the agent's
primary memory. Everything else is **additive**.

**Precedence — the transcript is authoritative for "what happened."** A
derived section that implies something the transcript contradicts is the bug,
not the transcript. (The findings-renders-open-plan-as-fact defect was exactly
this: a snapshot claimed work the eval log never did.) So the
confabulation-audit grounds an agent's self-claims against the **transcript
first**; a section that fought it is the defect to fix.

**"Nail the REPL" = four faithfulness invariants.** The transcript renders a
byte-faithful REPL session:

1. every form the agent evaluated, in order, with its **actual** return — not
   truncated or summarized into something that reads differently;
2. errors rendered **as** the failed eval (errors are data — a throw shows as
   "I tried X and it threw", never silently absent);
3. events interleaved at the point they occurred and **attributable** — an
   inbound message is unmistakably distinct from the agent's own eval;
   mis-attribution is the fake-instruction confabulation;
4. async resolved to **values**, not dangling Promises — a form that returned
   a pending computation shows its resolved value (or a legible "value now at
   `result/<id>`"), never a Promise the agent can't tell finished.

**Additive, not optional.** The spine is bounded and blind, so two additive
roles are load-bearing: derived sections **crystallize what the transcript
will lose** as it decays (the plan is the durable form of intent, findings of
knowledge — what would otherwise scroll off), and they **surface what the spine
is blind to** — derived state the agent never eval'd (a child at its
turn-limit) and non-event changes between turns. Because the transcript already
carries event-deltas (a message that arrived is already a line), the delta
surface is only the *non-event* changes. Additive sections layer on the spine;
they never contradict it.

**Bounded raw history, never synthetic compaction.** Recent transcript events
render verbatim within a short working window. Result bodies then decay through
byte-stable age bands to a useful diagnostic stub, and older eval events share
one fixed total budget spent newest-first. This makes the transcript approach a
plateau instead of growing by one permanent stub per eval. The complete event,
value, source, and error remain database/blob facts reachable by targeted
reads; leaving an event out of the prompt neither deletes nor summarizes it.
The runtime never replaces conversation history with a model-written compacted
story. Long continuity comes from the current plan, schemas, namespace source,
database facts, and explicit retrieval, so a long-lived agent does not depend
on transcript archaeology.

The budget is over the bytes that actually render, including form source,
narration, result, and error envelope. Per-result caps alone are insufficient:
an unlimited number of individually small stubs is still unbounded context.
Likewise, transcript clipping is a final guardrail; functions return bounded,
structured, drillable values and errors before rendering.

Failure diagnostics retain the database-configured eval cap at every display
site. `:seon.render/full?` and the agent's escape-clipping policy may release
successful authored source and stdout, while `full?` may release a successful
citable result; neither flag releases failed source, captured stdout, Malli
diagnostics, read errors, or runtime errors. Large parse failures window the
source excerpt around the exact reported source location before the transcript
renderer applies that same cap. Exact raw replies and error evidence remain
separate database/blob facts, so bounded agent context never weakens forensic
capture.

## The reply mode is a datom — and it teaches its own grammar

Two orthogonal database facts govern a reply. `:seon.ai/wire-stream?` selects
the provider transport only — whether bytes arrive as a stream — and never
changes what is evaluated. `:seon.ai/reply-evaluation` selects `:batch` or
`:first-form` as the evaluation semantics; all four combinations are legal,
and a named launch variant may copy either fact onto an agent, whose value
wins over the cluster default. `:batch` reads the reply to natural completion,
preserves the raw bytes, and parses the complete program once. Ordinary forms
retain source order, while explicit generated namespace sections run in
derived requirement order with recognized schemas before the remaining forms
in their namespace. Every attempted form records its real value or error with
its original source position. It never
regex-rewrites model output, invents a result for an unattempted form, or treats
a model-authored claim as execution evidence. `:first-form` ends the turn after
the first complete top-level form and counts attempted forms as work; it is an
explicit per-agent choice for models that work better under immediate
evaluation, not a consequence of the transport streaming.

The transcript masthead derives its grammar instruction from the same
evaluation fact, so only the applicable instruction is present. Turn facts
retain the forms and token usage needed to compare modes without changing
prompt truth.

## A render fn supplies twin projections

A `defn` whose input accepts the db and whose output carries a render key is
a **renderer**, and the keys present decide where it goes:

- `{:seon.render/ai …}` → a **block**: its string joins the agent's prompt.
- `{:seon.render/html …}` → a **surface**: its own HTML projection on the
  agent's page.
- **both keys → twins**: one value, two projections — the agent's context
  and the human's screen showing the same thing.

The **canvas** is a distinct, focal surface. Its shared **agent-derived focus**
shows the **last-updated surface** by default — a pure function of the db
(`seon.render.surface/last-updated-surface`): among the agent's own
authored surface fns (its `:seon.fn` rows whose tx provenance names the agent
through the REPL process and whose output schema declares the hiccup twin), the
one most recently *touched*—redefined, or an agent-user/REPL transaction that
touches a scoped input captured by the surface's **current** runtime-observed
database reads. Initial/cold
render captures actual `seon.db` query/pull/entity calls (including current
helpers/conditional branches), then runs a bounded indexed history lookup for
the newest matching user+entity/attr datom. It does not reconstruct every past
conditional dependency or evaluate every historical before/after result. A
broad/unknown observation gets definition recency only; the agent can make its
read selective or pin the canvas. No literal keyword read-set is stored. So the
human's focus follows what the current surface most plausibly received from the
agent's recent work
with zero ceremony: author a plan surface, write plan data, and that surface
is the canvas. Resolution is one ordered chain: an explicit agent-entity pin
(`:seon.render.canvas/content`), then the configured `:canvas` context-block
default, then the derived last-updated surface, then the core welcome. The
explicit pin is the deliberate override; retracting it resumes the configured
or derived default. The renderer returns the exact resolved value and
provenance with its projections, so the canvas context block explains the same
render instead of performing a second lookup.
(Unknown/dynamic reads are conservatively compared for live invalidation, but do
not claim precise historical focus.) A browser session may temporarily select a
different page-focused surface; that tab-local database fact neither changes
the agent-derived focus nor propagates into root fleet cards.

The canvas context block is the bounded AI projection of that same resolved
canvas, not a second resolution or render. Its AI twin and the agent-authored
renderer source are clipped independently by
`:seon.config.render/render-fn-token-cap`, with a token-denominated marker at
either cut. A short operational footer names the existing `my.canvas` controls.
This preserves enough local source to repair a broken renderer without letting
one verbose canvas duplicate an unbounded surface or program into every later
prompt.

The process qualifier prevents root-owned boot/config facts from masquerading
as root-agent authorship. `:seon.db/user` answers who; `:seon.db/process`
answers which durable ingress. Deliberate canvas recency needs both facts.

These are the block's two renders (`:seon.render/ai` /
`:seon.render/html`). Its explicit args contain the db value and any other
declared inputs; hidden walk state never influences output. First-party
renderers remain pure over those inputs. Every agent-authored renderer executes
only through the one SCI door. The boundary composes the uncatchable interrupt
with wrap-and-catch: failed or runaway code becomes a flat `:seon.error` value,
enters durable problem routing, appears in the running agent's next context,
and escalates to root. No agent exception crosses into a proc and no failure is
silently dropped. The historical prompt blob—not a re-executed effect—is the
byte ground truth.

## Shared view — the agent knows the human sees it

Because the *same* function feeds both the prompt and the surface catalog, the
agent and the human look at one derived value. An agent working in `my.plan`
runs its plan-view `defn`: the `:ai` twin puts the full plan in its own
context, the `:html` twin puts the full plan on the human's page. The agent
can rely on "my human is seeing this" — it is structurally true, no
messaging required. Planning in full detail *is* showing the human the plan.

## How tree values find renderers

The program graph is cluster-shared. A function written by one agent is a
committed `:seon.fn` fact with its namespace, schema, source, dependencies, and
source-transaction provenance. Other agents do not replay the author's eval;
every execution scope receives the accepted program delta and can resolve the
same function with normal Clojure semantics. Context then selects relevant
namespace source and function contracts from that one graph. Capabilities
therefore accumulate as the application grows instead of remaining private to
the process that first authored them. These facts use the settled top-level
`:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` attribute namespaces.

Every namespace has one owner agent, identified by the namespace's unique
`:seon.cluster.agent/namespace` ref. An agent that needs a symbol changed in
another namespace sends its owner a durable message and receives a commit or
rejection by reply. When a message targets an unowned namespace, the runtime
creates an agent and assigns that namespace on demand before delivery. The
owner's context includes current source, dependents, observed calls, failures,
performance evidence, tests, and incoming change requests. Ownership is a
distributed collaboration protocol, not a private program copy.

Render membership comes only from the tree walk. The walk discovers schema'd
values, then resolves each value through one chain: explicit render keys on the
value; a same-schema renderer found by a program-graph query in the viewing
agent's namespace and then the data's owning namespace; the schema-attached
default; and the structural floor. A namespace renderer is a possible
projection for discovered data, never membership by itself.

The cluster entity owns the authoritative ref set to
`:seon.cluster.instruction` rows for the system message, reply grammar,
messaging/declining grammar, and global instruction files. An agent entity
carries only genuine additive instruction refs. Editing an instruction replaces
its text datom on the same identity: no row versioning or ref repointing.
Datahike history retains the prior text. Plans, warnings, transcripts, and
other agent facts are reached from their ordinary refs in the same tree.

Rule: **derive the derivable; an override is data the same walk reads.** One
walk produces one tree of render units—never two rendering systems.

## Explicit dependencies — injected at the eval boundary

A tool or render fn's dependencies (the db, the calling agent, the current
time) are **declared in its request schema and injected once at the eval
boundary** — never read from an ambient dynamic var deep in the body. The
contract:

- A map-in fn declares an injectable as an **optional** request key —
  `:seon.db/db`, `:seon.agent/id` ("me"), `:seon.render/at` (the branch-local
  basis-transaction display aid; the database value carries the store ID,
  branch, commit ID, and basis transaction), and
  `:seon.web.session/id` (the browser tab attached to the human message this
  turn is answering),
  and whatever else the registry grows to hold. It is `{:optional true}` in the
  request shape, and the registry decides whether an explicit caller value is
  allowed; the wrapper guarantees it *present in the body*.
- On an agent call, the eval boundary inspects the fn's request schema and the
  one injectable registry. Each registry entry declares its caller policy.
  Ordinary inspectable dependencies may accept an explicit value; a
  context-only dependency may not. For every declared key the caller omitted,
  the boundary fills the current value from the eval context.
- The injectable **registry** is one explicit map of
  `injectable-key → {resolver, caller-policy}`: `:seon.db/db` → the turn's frozen
  db, `:seon.agent/id` → whose turn is running, and
  `:seon.web.session/id` → the session ref reached through
  current turn → `:seon.agent.turn/cause-message` → web session. The session
  entry is context-only: an
  agent-supplied value is rejected as a typed error rather than accepted or
  silently replaced. Trusted unit code may call the pure implementation with a
  validated explicit request; that is not an agent-eval override or a second
  injection wrapper. Adding a dependency means adding one registry entry and
  having fns declare the key. If a declared injectable has no current value—for
  example a scheduled root turn has no human session—the boundary returns a
  typed error envelope and never invokes the function with nil or guesses
  another tab.
- The injectable contract has **one named request shape**:
  `:seon.render/section-request` (registered in `seon.render`) — an OPEN map
  naming exactly the registry's keys, each `{:optional true}` and referencing
  its registered schema. Every block/section/converter fn the render engine
  calls declares `[:cat :seon.render/section-request]`, never a bare
  `[:cat :map]` — the contract is greppable, and a wrong-shaped injectable
  (e.g. a string `:seon.render/at`) rejects at the instrumented boundary
  naming the schema. Open on purpose: the engine composes extra per-call
  keys (`:seon.render/node`, `:seon.agent/entity`, …); a semantically
  richer request (e.g. `:seon.agent.debug/request`) stays its own schema.

This rides the one program-publication instrumentation layer. Boot reconstructs
wrappers once from committed program facts; a definition or schema transition
then instruments only changed definitions and schema dependents. The structural
exceptions are explicit in that transition. A candidate that cannot be
validated and instrumented does not publish or pass readiness; it records one
bounded core fault. Context may show that current fault concisely, while the
detailed coverage diagnosis is pulled on demand through [[observability]].
Coverage diagnostics are never standing context blocks. Injection happens before input
validation, so the filled map satisfies the declared request shape and remains
reproducible from the captured database value.

The **scope-by-signature** rule falls out: a fn that declares `:seon.agent/id`
reads/writes **per-agent** data (it stamps `:my.plan/agent me` and filters by
it); a fn that does not is **global** (`my.kb`). You know where data goes by
reading the arglist — not from an invisible binding.

## Renderer discovery — the walk queries the program graph

The walk first discovers schema'd values. For each value, it queries the
program graph for a same-schema renderer in the governing namespace and runs
the winning function through the same injecting boundary. Renderer functions
are map-in functions declaring `:seon.db/db`, `:seon.agent/id`, and their
domain arguments; their presence alone never inserts a context unit. This keeps
membership in the entity tree while allowing the current namespace to override
how discovered data renders with zero registry ceremony.

### Root is a small specialization of the same mechanism

Root receives one concise role-specific block: understand the fleet, start or
select an ordinary agent, route/delegate work, and respond to recovery notices.
It does not receive a broad root manual. Its capabilities appear as compact,
fully specified home-required namespace cards. Root's require vector is an
additive overlay by namespace identity on the complete ordinary/downstream
workbench, so filesystem, shell, web, database, skills, canvas, and consumer
product capabilities remain present while root refers its small orchestration
surface directly into the safe `my.agent.root` home namespace. Root-authored
definitions never land in framework code. The resolved vector is persisted
with root's home namespace; there is no runtime role registry or renderer
allowlist. When root moves into an
orchestration, database, or UI-session namespace, that
namespace becomes current and its source plus applicable same-schema renderers
enter context through the same walk-and-query rule above. The root canvas's bounded AI twin
provides current fleet facts through the ordinary canvas block: every agent is
listed compactly, while running, erroring, and recently active agents receive
bounded recent-message, failed-eval, and canvas-AI detail. Root itself remains a
summary-only agent row because its canvas is the fleet view; recursively
materializing its own surface or canvas-AI detail is forbidden. The fleet is not
copied into a second context block. The originating human session's normalized
route is also derived into that root view, so root knows what the user is seeing.
Derived root-only warnings still render only when their queries return facts.

This is the quality bar for restoring historical root context: keep a statement
only when it names root's irreducible role or an actual derived state; move
operational detail into the namespace that owns the functions; delete generic
advice and duplicated instructions. Behavior is measured before more standing
text is admitted.

### Importing a skill does not inject it

Users may import standard `SKILL.md` content into canonical `my.skills` database
facts, but skill-fact availability and prompt placement are independent. Default
and test context trees carry no skill ref. Namespace cards, current-namespace
source, state-gated render units, and pull references remain the normal
discovery path. Explicit selection adds an ordinary schema'd ref reachable from
the agent entity; it never installs a block or creates a second assembly path.

## Order = last change, so the cache holds

Render units are ordered by **when their bytes last changed** and by nothing
else. Each cached function call carries the basis at which its digest last
transitioned; a no-op reassertion does not move it. Display order is ascending
across every unit regardless of tree position, so the prompt reads
longest-unchanged → most-recently-changed and the provider prefix-cache survives
most turns. Near-equal changes cluster by branch so related units remain
together; the target does not invent a threshold here. The key is derived by
the per-call cache at render time and is never a stored timestamp, stability
field, or authored priority. Stable render-call identity is the final
deterministic tie-break within a branch cluster.

There are **no bands, no pins, and no hysteresis**. A block that never
changes — the system message, the role instruction, an imported instruction
file — reaches the front because its facts are the oldest, not because
something declared it fixed. A block that changes every turn sinks on its own.
"Static" is a property of the facts, never a mechanism: everything a prompt
contains is one block from one render fn over the db, and there is no second
assembly path for anchors (owner ruling 2026-07-31 — see
`docs/prds/sci-execution-runtime/plan/README.md`).

Banding, priority, and hysteresis do not exist in this contract. Every captured
turn already records each block's identity, content hash, estimated tokens, and
position as observability facts, and provider cache-read usage measures real
reuse. Evidence of oscillation would require a new ordering ruling; it does not
reserve the retired attributes or a dormant second mechanism.

Root-only live telemetry is an ordinary first-party render unit described at
the top of this document. Its bytes participate in the same last-changed order;
because its explicit process-local inputs change often, it naturally moves
toward the end. Active-child outcomes remain database facts in the ordinary
tree, while genuinely live progress may be another explicit process-local
input to that same bounded unit.

Two content policies survive the retirement of the bands, because they are
about what a block contains, not where it sits:

- **the transcript** is acquired through a fixed-work newest-turn window, with
  per-band caps, eval-result decay, and a fixed total budget for older retained
  events. The same immutable database value and policy produce the same bytes;
  the leading edge may evict the oldest retained turn whenever a newer one
  arrives. Eviction never rewrites retained events into summaries, and every
  truncated older tail is marked honestly. What must outlive the window goes to
  the DB (plan, kb, blobs), not transcript residue; a large inbound payload
  clips to a blob ref.
- **relevance retrieval is pull-first, not a block.** Reference code and
  retrieval beyond the current namespace are explicitly inspected or called
  when needed. Functions whose *input* specs match the shapes the agent is
  holding (a graph query — [[think-in-clojure]] §1) and embedding neighbors for
  the current activity are a search the agent CALLS, not a pushed block. It
  becomes a recompute-every-step block (a capped token budget, config dial)
  only if a drive proves the need, and vanishes when its queries return empty.
  ([[context-rebuild]] §"Deliberately NOT blocks".)

Code grows slowly against tokens spent running things, so the code blocks are
the compounding asset: as the agent persists schemas, fns, and tests, its own
code becomes the majority of its context — self-reinforcing, cheap, and, being
the least recently changed, naturally cached at the front.

## Multi-agent sections — subagents + orphaned-agents

Two derived sections make the spawn tree visible without any registry or
notification state (both vanish when their query is empty). Their renderer and
tests exist, but the general block remains dormant until the solo-agent proof
closes; multi-agent visibility must not obscure the solo navigation signal:

- **`:subagents`** (general agent-context, volatile tail near the transcript) —
  the **direct** children the rendering agent spawned (`:seon.agent/parent` =
  me; NOT the whole subtree). One compact line each: id · derived state · purpose
  · and, running → `turn i/limit` + last-beat age; idle with a completed latest
  run → the run's `:seon.agent.run/result` (+ a ref pointer); closed abnormally →
  the `closed-reason` (a parent MUST see a child that DIED, not just one that
  succeeded). A breaker-tripped child shows it. This is the parent's monitoring
  surface: completion is a **fact in the DB**, so a parent that was mid-turn or
  restarted still sees every child result — no acknowledgement, nothing to clear.
  After that proof closes, childless agents render empty → it costs them zero;
  the compact running-progress view occupies the root telemetry render unit while
  persisted outcomes remain in the database-derived body.
- **`:orphaned-agents`** (root-only, config-injected via
  `:seon.config/root-context` like `:core-faults`) — live agents whose
  `:seon.agent/parent` is **terminated**. One line each (id · state · purpose ·
  parent id). No action machinery — root (or the human) decides per case with the
  existing functions (no cascade-terminate, no reparenting: observe first).

## Inspectability — the human twin of every position

Every agent has a read-only debug view that begins at that agent's entity and
walks the same tree through the one merged structural floor. It exposes every
reachable schema'd value—including system apparatus hidden from the curated
page—preserves identities and refs for drill navigation, and never transacts a
display choice. The view also shows each unit's AI/HTML projections, token
counts, cache dependencies/revisions, code revision, digest, last-bytes-changed
basis, and error routing. Through [[observability]], the same surface reaches
the exact historical context of any turn (`agent-debug/turn`, `turn-diff`, the
prompt at its resolved commit, and the prompt blob as byte ground truth).

## Configuration

Every effective dial is database data (`:seon.config/*`): namespace projection
policy, transcript age schedule + decay, render caps, invocation-class
`time-limit` defaults, the predicted-relevance token cap, and per-agent
overrides in agent scope. A manifest is
an optional desired-state input explicitly selected for one startup/apply
operation; no selection preserves DB facts and never falls back to
`config/system.edn`. Environment overrides are captured only while compiling a
selected input and never become a hidden runtime dependency.

**Config-through-DB (the whole surface, not one dial).** A selected manifest is
a transition input, not a runtime dependency. At a selected startup/apply,
`seon.config/resolve-config-singleton` resolves every knob (environment overrides
manifest, which overrides defaults), and the exact population reconciler
restores the declared config singleton/routes/root-context/skill-import subset. Omitted managed attributes and
stale exclusive rows retract; outside facts remain untouched; equal state emits
no transaction. A config-free boot skips this transition. The write is
`{user root, process config}`. After database acquisition runtime startup
acquires the singleton once, decodes its EDN slots, and installs that ordinary
map in the existing async transaction context alongside the current database
value and provenance. Descendant work inherits it without another database
read or configuration argument. Pure accessors such as
`config/eval-render-cap`, `config/on-core-error`, `config/web-policy`, and
`config/namespaces-policy` read the acquired map; a central operation boundary
may merge an explicit request override over it. No accessor owns an atom,
additional async context, injected reader, manifest fallback, or second
projection cache. Explicitly selected manifest data or resolved code defaults
are valid only before database initialization; afterward missing required
config is a typed readiness error.

Database read resource fields are safety ceilings rather than result-shaping
controls. Datahike `max-work` counts charged execution steps, `max-results`
counts retained result nodes (including nested pull values), and
`max-result-weight` counts shallow scalar/container weight rather than bytes.
Normal query and pull work inherits generous database configuration values;
an individual operation may explicitly request a smaller ceiling. Pagination,
top-level row limits, and application semantics use their own query shape or
API fields and never overload these resource counters.
Collection knobs
(`:seon.config/always`, `:seon.config.repair/classes`,
`:seon.agent.web/allowed-domains`) ride the mixed-`:or` EDN-slot bridge (the
`home-requires` precedent) — one cardinality-one datom that upsert replaces.

Two payoffs this unlocks: a dial is now **history-visible** (a `cluster fork`
at the resolved commit before a dial change renders with the old value—config lives in
history) and **live-tunable** (a `db/transact` of the singleton changes the
next prompt with no file edit). `:seon.ai/reply-evaluation` and the
transcript's tier/decay datoms are the precedents this generalizes to the
whole config surface.

**Instructions are ordinary cluster facts.** The cluster entity's
`:seon.cluster/instructions` ref set reaches named
`:seon.cluster.instruction` rows for the system message, reply grammar,
messaging/declining grammar, and imported global instruction bytes. Every agent
points at the cluster and may carry an additive instruction ref set for genuine
per-agent additions. An edit replaces `:seon.cluster.instruction/text` on the
same identity; all agents see the new bytes on their next render, and Datahike
history preserves the old value. There is no prompt-text singleton fallback,
instruction versioning, complete block-tree manifest, or static prepend path.
The manifest-owned config singleton remains a separate entity reached by
`:seon.cluster/config`.

The skills manifest section is an import input, never a default loadout. Apply
freezes and validates the selected files, stores their canonical source facts,
and then forgets the path. A later config-free boot reads those facts from the
database and adds no agent instruction ref on its own.

Prompt acquisition resolves the system text and the agent's selected context
inside one compiled acquisition operation over one immutable database value. That one
ordinary result flows unchanged through turn capture, token accounting, every
retry, the provider adapter, and the debug view. None of those consumers
re-resolves live config after the prompt database value has been chosen.

## See also

- [[ui]] — the block, its two renders, the surface catalog, the derived
  entity walk and its resolution chain, and the live channel.
- [[data-model]] — `my.plan` (the worked example: its plan-view `defn` is the
  twin an agent sees and the human watches), the `my.*` schemas.
- [[observability]] — turn record, replay functions, the blob archive.
- [[laws]] — cache-stability, render-prominence, always-on-beats-skills.
- [[think-in-clojure]] — a fn's specced in/out is the query substrate for
  both rendering and running.
