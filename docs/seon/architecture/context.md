---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — functions applied to the db

> **Target design** (present tense). The block/render machinery lives in
> [[ui]]; historical run reconstruction + inspection in [[observability]]; the measured laws
> that constrain this in [[laws]]. Implementation state lives in [[roadmap]]. This doc keeps
> to Clojure primitives — `ns`, `defn`, `require`, var metadata, and a db value.
> A block is the informal name for one render-function call in either
> projection, never a stored data type.

The prompt is a **flat sequence of render-call units rooted at one agent
entity**. Schema'd data are renderable values and refs are traversal edges. A
bounded walk discovers the units from one immutable database value; the
display projection orders them and joins their AI bytes. Recursive traversal
state is private to the walk and never becomes another public render envelope.

Every model call re-derives the walk from one frozen db value. Nothing is accumulated.
The agent, cluster, current run, namespace, window policy, tool/schema graph,
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

Render reuse belongs to the one render proc package owner. Each process-local
fragment entry retains its dependency evidence and serialized bytes; the same
state retains the latest revisioned package. A pass reuses a fragment when its
evidence is equal, compares newly produced bytes when it is not, and builds the
keyframe from retained bytes. This is losable performance state, never another
truth or a second general function cache.

Live root telemetry is an ordinary first-party render unit with explicit
process-local inputs, not a tail outside the block system. It returns an
ordinary value, is capped at roughly 50 estimated tokens, and is omitted when normal. Whether fleet
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
- **what I am waiting on** — outstanding messages, delegated work, and blocked
  facts.
- **what I just did** — my last turn's actions and their outcomes, from the
  transcript.
- **what I learned** — accumulated knowledge (`my.kb`), and *only* knowledge:
  work-tracking that still carries a live lifecycle status is not a settled
  finding and never renders as one.
- **what changed since I last looked** — the **delta**, derived between the
  previous model call's context-capture basis and the current database value.
  New messages, settled delegated work, and new failures are queries over those
  values, not new stored state. A lineage change renders a reset/diff boundary
  instead of pretending a bare basis transaction is globally continuous.

**Situation, never the answer.** The projection renders the agent's operational
situation and the operations available on it — "this open run has no process
custody," "three plan items are open and independent; any may be delegated" —
because a continuous agent would know
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
4. evaluation resolved to **values** — a form shows its admitted result, flat
   error, interruption, or a legible `result/<id>` handle for a process-local
   value.

**Additive, not optional.** The spine is bounded and blind, so two additive
roles are load-bearing: derived sections **crystallize what the transcript
will lose** as it decays (the plan is the durable form of intent, findings of
knowledge — what would otherwise scroll off), and they **surface what the spine
is blind to** — derived state the agent never eval'd (for example, an open run
without custody) and non-event changes between model calls. Because the transcript already
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
site. A caller may request a larger successful authored-source, stdout, or
citable-result projection within the owning bound; no display option releases
failed source, captured stdout, Malli
diagnostics, read errors, or runtime errors. Large parse failures window the
source excerpt around the exact reported source location before the transcript
renderer applies that same cap. Exact raw replies and error evidence remain
separate database/blob facts, so bounded agent context never weakens forensic
capture.

## The reply grammar is one current instruction

The cluster instruction facts teach the grammar implemented by
`seon.cluster.reply`: a model reply is read to natural completion, split into
ordered form sources, frozen on the run, and reduced in source order. Each
attempted form records its actual result or error. The reader never
regex-rewrites model output, invents a result for an unattempted form, or
treats a model-authored claim as execution evidence. Provider byte streaming
is transport behavior and does not select a second evaluation mode.

## A render fn supplies twin projections

A public `defn` whose declared input accepts a render unit and whose declared
output is one of the two render shapes is a **renderer**:

- `:seon.render/ai` → its string joins the agent's prompt.
- `:seon.render/html` → its Hiccup is serialized for the agent's page.
- both declared contracts → twins: the agent's context and the human's screen
  show the same value through two typed functions.

### [TARGET] Generalized canvas focus

The render-contract ruling reserves one focal canvas selected by an
agent-owned durable ref to a renderable value. The same value supplies its AI
and HTML projections; retracting the ref returns focus to a derived default.
The canvas schema and action/control boundary assign their attribute and
function identities before any transaction, route, or agent call names them.
No context-block manifest, captured runtime read-set, or effectful in-eval UI
helper participates in that design.

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

Render membership comes only from the walk. It discovers schema'd values, then
resolves each through one chain: an explicit producer symbol on the value; the
unique contract-fitting public function in an explicitly owning namespace; the
schema-attached default; and the structural floor. Ownership comes only from a
real ref on the data or traversal edge, never keyword text or the viewer's
namespace. Without such a ref, resolution proceeds directly to schema metadata
and then the floor. A namespace renderer is a possible projection for
discovered data, never membership by itself.

The cluster entity owns the authoritative ref set to
`:seon.cluster.instruction` rows for the system message, reply grammar,
messaging/declining grammar, and global instruction files. An agent entity
carries only genuine additive instruction refs. Editing an instruction replaces
its text datom on the same identity: no row versioning or ref repointing.
Datahike history retains the prior text. Plans, warnings, transcripts, and
other agent facts are reached from their ordinary refs in the same tree.

Rule: **derive the derivable; an override is data the same walk reads.** One
walk produces one flat sequence of render-call units—never two rendering systems.

## Explicit render inputs

Render and context functions receive ordinary namespaced request data. The
walk supplies the immutable `:seon.db/db`, the viewing
`:seon.cluster.agent/id`, admission caps, distance, live-process evidence when
needed, and the pulled entity. A renderer declares the request it consumes and
never reaches through an ambient connection or an injectable registry.

The database value and agent identity describe one render request; they are not
execution grants. A function's schema remains its complete contract, and
instrumentation validates it at the SCI or host boundary that invokes it.
Per-agent domain ownership is expressed by a real ref on the data, not inferred
from the presence of an injected argument.

## Renderer discovery — the walk queries the program graph

The walk first discovers schema'd values. For each value with an explicit
owning-namespace ref, it queries the program graph for the unique
contract-fitting public function in that namespace and runs the winner through
the same render boundary. Without an owning ref it proceeds to the schema
property and floor. Renderer functions consume a unit carrying `:seon.db/db`,
`:seon.cluster.agent/id`, and their domain values; their presence alone never
inserts a context unit.

### Root is a small specialization of the same mechanism

Root receives one concise role-specific block: understand the fleet, start or
select an ordinary agent, route/delegate work, and respond to recovery notices.
It does not receive a broad root manual. Its capabilities appear as compact,
fully specified namespace cards. This is a RENDERING overlay by namespace
identity, never a grant: root can call exactly what every other agent can
call (ruling #20), and the cards only decide what root SEES first. Root-authored
definitions never land in framework code. The resolved vector is persisted
with root's home namespace; there is no runtime role registry or renderer
allowlist. When root moves into an orchestration, database, or UI namespace,
that namespace becomes current and its source plus applicable same-schema renderers
enter context through the same walk-and-query rule above. The root canvas's bounded AI twin
provides current fleet facts through the ordinary canvas block: every agent is
listed compactly, while running, erroring, and recently active agents receive
bounded recent-message, failed-eval, and canvas-AI detail. Root itself remains a
summary-only agent row because its canvas is the fleet view; recursively
materializing its own surface or canvas-AI detail is forbidden. The fleet is not
copied into a second context block. Derived root-only warnings still render only
when their queries return facts.

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
else. The render proc's retained fragment entry carries the basis at which its
bytes last transitioned; a no-op reassertion does not move it. Display order is ascending
across every unit regardless of tree position, so the prompt reads
longest-unchanged → most-recently-changed and the provider prefix-cache survives
most turns. Near-equal changes cluster by branch so related units remain
together; the target does not invent a threshold here. The key is derived by
the retained fragment state at render time and is never a stored timestamp, stability
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

Banding, priority, and hysteresis do not exist in this contract. Every context
capture records each block's identity, content hash, estimated tokens, and
position as observability facts, and provider cache-read usage measures real
reuse. Evidence of oscillation would require a new ordering ruling; it does not
reserve the retired attributes or a dormant second mechanism.

Root-only live telemetry is an ordinary first-party render unit described at
the top of this document. Its bytes participate in the same last-changed order;
because its explicit process-local inputs change often, it naturally moves
toward the end. Delegated-work outcomes remain database facts in the ordinary
tree, while genuinely live progress may be another explicit process-local
input to that same bounded unit.

Two content policies survive the retirement of the bands, because they are
about what a block contains, not where it sits:

- **the transcript** is acquired newest-first under one total token budget. A
  fixed recent tail retains full detail when it fits; older entries use their
  derived summary projection when it fits; everything omitted contributes to
  one explicit elision count. The same immutable database value and budget
  produce the same bytes. What must outlive the window is already a database
  fact or blob, never transcript residue.
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

## Multi-agent context

Agents relate through durable `:seon.cluster.message` rows, shared namespace
ownership, and ordinary refs such as a message's optional `/about` fact. There
is no persisted parent tree or orphan state. Root's namespace page derives the
agent population by `:seon.cluster.agent/id`; an agent page derives received and
sent messages, runs, eval receipts, and routed errors by their current refs.
Empty queries render nothing, so visibility needs no notification or
acknowledgement state.

## Inspectability — the human twin of every position

Every agent has a read-only debug view that begins at that agent's entity and
walks the same tree through the one merged structural floor. It exposes every
reachable schema'd value—including system apparatus hidden from the curated
page—preserves identities and refs for drill navigation, and never transacts a
display choice. The view also shows each unit's AI/HTML projections and their
source facts. Through [[observability]], the same surface reaches a run's exact
`:seon.context.capture/prompt`, database basis transaction, ordered
contributions, AI attempts, forms, eval receipts, and errors.

## Configuration

Fresh configuration is one compiled and reconciled database row per cluster.
`config/default.edn` supplies one shipped decision for every registered dial;
an explicitly selected sparse EDN overlay may replace those decisions for one
start or apply operation. Omitted overlay keys inherit defaults, extra keys are
ignored, and every declared key is rigorously validated. The caller may supply
a typed environment map while compiling that input. Runtime consumers never
read ambient environment variables or a configuration file.

`seon.config/apply!` is the one exact reconciliation mechanism and
`seon.config/effective` reads the ordinary effective map from an immutable
database value. Acquisition belongs to each consumer: process structures read
at boot, loop handles capture their dials when an agent graph arms, AI settings
resolve once per turn including the agent overlay, and render-request dials
read from that request's database value. Applying facts therefore affects a
consumer at its documented acquisition boundary; it does not silently rebuild
a graph, executor, or web server.

Routes, context membership, instruction imports, and skills are not a config
manifest. Routes are the canonical Reitit table; context is the visible walk;
instructions are ordinary cluster or agent refs. Configuration contains only
registered decision attributes from the config section of
`resources/seon/schema.edn`.

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

Prompt acquisition resolves the system text and the agent's selected context
inside one compiled acquisition operation over one immutable database value. That one
ordinary result flows unchanged through context capture, token accounting, every
retry, the provider adapter, and the debug view. None of those consumers
re-resolves live config after the prompt database value has been chosen.

## See also

- [[ui]] — the block, its two renders, the surface catalog, the derived
  entity walk and its resolution chain, and the live channel.
- [[data-model]] — admitted context, run, receipt, and program-graph facts.
- [[observability]] — context captures, attempts, receipts, errors, and blobs.
- [[laws]] — cache-stability, render-prominence, always-on-beats-skills.
- [[think-in-clojure]] — a fn's specced in/out is the query substrate for
  both rendering and running.
