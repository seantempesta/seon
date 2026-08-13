---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — the neighborhood renders itself as history

> **Target design** (present tense). The block/render machinery lives in
> [[ui]]; historical run reconstruction and inspection live in
> [[observability]]; dated measurements that constrain this live in PRD research.
> Implementation state lives in [[roadmap]]. A block is the informal name for
> one render-function call, never a stored data type.

The prompt is an **append-only sequence of basis-labelled render entries rooted
at one agent entity**. One Datahike pull acquires the agent's neighborhood from
one immutable database value. Its selector is generated from installed schema
ref declarations: forward refs nest, every stored ref also receives its reverse
spelling, requested distance determines selector depth, and pull `:limit`
bounds breadth. The pull result is both the neighborhood and the membership
index; stable identity exposes arrivals, changes, and removals without a second
traversal.

Every unit in that result passes through the same render-selection chain for
three projections:

- `:seon.render/form` is the form that produces the value;
- `:seon.render/ai` is the value's printed representation for the prompt; and
- `:seon.render/html` is the same value as Hiccup for the web UI.

The history orders a parent listing before a child lookup and preserves
define-before-use: a form never refers to a symbol that an earlier entry has not
introduced. Stable alphabetical ties make that ordering deterministic; live
material then follows its arrival order. A prompt entry carries its stable
logical render-call identity, observed basis transaction and commit ID, form,
and already serialized AI bytes.

The agent, cluster, current run, namespace, render profile, program graph, plan,
event membership, and ref edges are facts or pure queries over the selected
database value. Rendering the same database value (the same store ID, branch,
commit ID, and basis transaction), code revision, and explicit render arguments
for the same agent produces byte-identical entries. Filesystem state,
process-local cache membership, random ids, and map iteration order never leak
into them. Which functions are in scope, and in what order, is the entire design
— and whether that set is **complete** is what makes the agent feel stateful.

Read reuse cannot weaken that determinism. Datahike owns immutable eager-result
entries and their parsed dependency plans at exact database source identities.
A materialized committed value without retained exact attribute revisions uses
its own commit ID as a conservative cache revision; it cannot inherit a result
from another commit merely because both lack revision history. The render
therefore sees the selected database value whether a read computes, joins
single-flight, or hits cache.

Render reuse belongs to the one render proc package owner. Each process-local
latest-call entry retains its Datahike read evidence, latest output, and the
immutable serialized history entry; a reverse projection indexes logical calls
by the attributes their reads observed. An unchanged prompt acquisition returns
retained bytes with zero database reads and zero renderer calls.

When an affecting transaction makes a retained read semantically stale, the
render proc re-derives that logical call exactly once against one current
database value and appends a new basis-labelled entry. It never edits,
reserializes, deletes, or reorders an earlier entry. A byte-equal refreshed
output still records the re-derivation; an arrival appends its first
observation, and a removal appends an explicit absence observation. Therefore,
within one retained prompt generation, prompt N+1 is byte-for-byte prompt N plus
a suffix. The disposable latest-call lookup may advance to the appended entry;
the ordered history may not be replaced. This is losable performance state,
never another truth or a second general function cache. After a process crash,
facts reconstruct the current projection, but the lost process-local prefix is
not promised to be identical. The sent prompt blob remains the forensic byte
truth.

Live root telemetry is an ordinary first-party render unit with explicit
process-local inputs, not a tail outside the block system. It returns an
ordinary value, is capped at roughly 50 estimated tokens, and is omitted when normal. Whether fleet
ping summaries become durable facts remains a separate data-model decision;
the render contract does not invent persistence for them. The sent prompt blob
is the byte ground truth for process-local inputs, while the recorded database
value and code revision regenerate the database-derived calls.

## The projection must be complete — so the agent feels stateful

An agent carries no mutable interpreter history between turns. Yet it must
*behave* as though it remembered — resume its work, act on what just happened,
notice what changed. It can, because its append-only rendered history is a
**complete and faithful projection of its situation**: retained observations
preserve what it saw, and affecting facts append refreshed observations before
its next turn, with no gap left for the model to fill.

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
- **what I learned** — accumulated knowledge facts, and *only* knowledge:
  work-tracking that still carries a live lifecycle status is not a settled
  finding and never renders as one.
- **what changed since I last looked** — the basis-labelled suffix appended by
  refreshed system-authored reads. New messages, settled delegated work, and
  new failures remain ordinary facts; the retained entry and its refreshed
  successor make the observed change explicit without rewriting history.

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
what actually happened to it. The eval log is one projection of the program graph
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

**History entries are immutable observations.** Once an entry joins a prompt
generation, later fitting never rewrites its form, value, error, or serialized
bytes. The complete event, value, source, and error remain database/blob facts
reachable by targeted reads. Long continuity comes from the current plan,
schemas, namespace source, database facts, and explicit retrieval, so a
long-lived agent does not depend on transcript archaeology.

The consumer's render profile bounds the bytes it receives, including form
source, result, and error envelope. Functions return bounded, structured,
values and errors navigable by `get-in` before rendering; fitting is a projection of the
same retained entry, not a mutation of prompt history.

Failure diagnostics retain the database-configured eval output bound at every display
site. A caller may request a larger successful authored-source, stdout, or
citable-result projection within the owning bound; no display option releases
failed source, captured stdout, Malli
diagnostics, read errors, or running-system errors. Large parse failures bound the
source excerpt around the exact reported source location before the transcript
renderer applies that same display bound. Exact raw replies and error evidence remain
separate database/blob facts, so bounded agent context never weakens forensic
capture.

## The reply grammar is one instruction

The cluster instruction facts teach the grammar owned by `seon.cluster.reply`:
a model reply is read to natural completion, split into
ordered form sources, frozen on the run, and reduced in source order. Each
attempted form records its actual result or error. The reader never
regex-rewrites model output, invents a result for an unattempted form, or
treats a model-authored claim as execution evidence. Provider byte streaming
is transport behavior and does not select a second evaluation mode.

## A render function supplies three projections

A public `defn` whose declared input accepts a render unit and whose declared
output is one of the three render shapes is a **render function**:

- `:seon.render/form` → its Clojure form becomes the history entry's form.
- `:seon.render/ai` → its string joins the agent's prompt.
- `:seon.render/html` → its Hiccup is serialized for the agent's namespace page.
- the three declared contracts → the agent's history and the human's namespace page show
  the same value as a form and its two typed representations.

All three use the one selection chain. The `/form` structural floor is total:
a unit reached through an attribute uses the listing query for that attribute,
and an entity uses an identity lookup-ref pull whose identity attribute is
derived from `:seon.entity/id-attr`. `doc` and `dir` are ordinary `/form`
declarations for program-graph values, not special context-engine arms.

### [TARGET] Generalized canvas focus

The render-contract ruling reserves one focal canvas selected by an
agent-owned durable ref to a renderable value. The same value supplies its AI
and HTML projections; retracting the ref returns focus to a derived default.
The canvas schema and action/control boundary assign their attribute and
function identities before any transaction, route, or agent call names them.
No context-block manifest, captured process-local read set, or effectful in-eval UI
helper participates in that design.

These are the block's three projections. Its explicit args contain the database
value and any other declared inputs; hidden acquisition state never influences
output. First-party render functions remain pure over those inputs. Every
agent-authored render function executes only through the one SCI invocation boundary. The
boundary composes the uncatchable interrupt with wrap-and-catch: failed or
runaway code becomes a flat `:seon.error` value, enters durable problem routing,
appears in the running agent's next context, and escalates to root. No agent
exception crosses into a proc and no failure is silently dropped. The
historical prompt blob—not a re-executed effect—is the byte ground truth.

## Only system-authored reads refresh

Every run form receives required authorship from its constructor: `:agent` for
an agent-authored form and `:system` for a system-authored read. A refreshed
read points uniquely to the prior form it refreshes. The refresh transition
accepts only a terminal system-authored predecessor with no existing successor,
and the receipt owns the read evidence that justified refresh. Consequently an
agent-authored form cannot enter the refresh path and never re-executes. The
system injects a form only when it is true and the agent has not already done
the work; ordinary `require` forms execute once in the turn fork and settlement
records the resulting namespace facts.

## Shared artifact — the agent knows the human sees it

Because one render call supplies the form, AI, and HTML projections, the agent
and the human look at one retained artifact at different fits. An agent working
with an authored intent value receives its form and printed value in history
while the HTML projection puts the same value on the human's namespace page. Root's
preview of that agent uses the same retained AI bytes. The agent can rely on
"my human is seeing this" — it is structurally true, no messaging required.

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
rejection by reply. When a message targets an unowned namespace, the running system
creates an agent and assigns that namespace on demand before delivery. The
owner's context includes current source, dependents, observed calls, failures,
performance evidence, tests, and incoming change requests. Ownership is a
distributed collaboration protocol, not a private program copy.

Render membership comes only from the schema-derived pull rooted at the agent.
It discovers schema'd values, then resolves each projection through one chain:
an explicit projection symbol on the value; the unique contract-fitting public
function in an explicitly owning namespace; the schema-attached default; and
the structural floor. Ownership comes only from a real ref on the data or pull
edge, never keyword text or the viewer's namespace. Without such a ref,
resolution proceeds directly to schema metadata and then the floor. A namespace
render function is a possible projection for acquired data, never membership by
itself.

The cluster entity owns the authoritative ref set to
`:seon.cluster.instruction` rows for the system message, reply grammar,
messaging/declining grammar, and global instruction files. An agent entity
carries only genuine additive instruction refs. Editing an instruction replaces
its text datom on the same identity: no row versioning or ref repointing.
Datahike history retains the prior text. Plans, warnings, transcripts, and
other agent facts are reached from their ordinary refs in the same tree.

Rule: **derive the derivable; an override is data the same pull reads.** One
root acquisition produces one ordered sequence of render calls—never two
rendering systems.

## Explicit render inputs

Render and context functions receive ordinary namespaced request data. The
render call supplies the immutable `:seon.db/db`, the viewing
`:seon.cluster.agent/id`, the selected render profile, live-process evidence
when needed, and the pulled value. A render function declares the request it
consumes and never reaches through a process-global connection or an injectable
registry.

The database value and agent identity describe one render request; they do not
gate execution. A function's schema remains its complete contract, and
instrumentation validates it at the SCI or host boundary that invokes it.
Per-agent domain ownership is expressed by a real ref on the data, not inferred
from the presence of an injected argument.

## Render-function discovery queries the program graph

The root pull first acquires schema'd values. For each value with an explicit
owning-namespace ref, the selector queries the acquired candidates for the
unique contract-fitting public function in that namespace and runs the winner
through the same render boundary. Without an owning ref it proceeds to the
schema property and floor. Render functions consume a unit carrying
`:seon.db/db`, `:seon.cluster.agent/id`, and their domain values; their presence
alone never inserts a context unit.

### Root is a small specialization of the same mechanism

Root receives one concise role-specific block: understand the fleet, start or
select an ordinary agent, route or delegate work, and respond to recovery
notices. It does not receive a broad root manual. Its capabilities appear as
compact, fully specified namespace entries. This is a rendering choice, never a
execution gate: root can call exactly what every other agent can call, and the entries
only decide what root sees. When root enters an orchestration, database, or UI
namespace, that namespace becomes current and its source plus applicable render
functions enter context through the same root-pull and selection chain.

Each attached agent contributes the retained AI projection of its newest-basis
block to root's context. Root's namespace page presents the corresponding HTML projection
as that agent's live window. These are two fits of the same retained render
artifact, not cards or a second fleet-summary mechanism. Root itself remains a
summary-only row so the fleet projection never recursively renders itself.
Derived root-only warnings render only when their queries return facts.

### Importing a skill does not inject it

Users may import standard `SKILL.md` content as canonical database facts, but
skill-fact availability and prompt placement are independent. Default
and test context trees carry no skill ref. Namespace cards, current-namespace
source, state-gated render units, and pull references remain the normal
discovery path. Explicit selection adds an ordinary schema'd ref reachable from
the agent entity; it never installs a block or creates a second assembly path.

## Order preserves the prefix and teaches define-before-use

The initial root derivation follows the pull tree: a parent listing precedes a
child lookup, siblings may derive in parallel, and stable alphabetical ties
make their emitted order deterministic. Parsed forms enforce define-before-use:
`require` introduces an alias, `dir` introduces names, `doc` may then name one,
and a call follows its documentation. Live material follows arrival order.

Refresh never moves an old entry. One passive render pass uses one database
value and appends affected logical call identities in that established root
order. Newest-basis entries therefore sit nearest the next model turn, while
the retained prefix remains byte-identical. There are no stored priority,
stability, band, pin, or hysteresis attributes.

The V1 profile is concise-until-cap: render every acquired unit concisely in
order until the selected token cap, and let every concise value expose the form
that retrieves its deeper representation. Reference code and retrieval beyond
the current namespace remain functions the agent calls, not a separately pushed
context block.

Every context capture records each entry's logical identity, basis, content
hash, estimated tokens, and position as observability facts. Provider cache-read
usage measures actual prefix reuse. Code remains the compounding asset: as an
agent persists schemas, functions, and tests, those program facts enter its
neighborhood and render through the same acquisition and history mechanism.

## Work wakes and render refresh are separate

The one `seon.cluster.wake` listener performs two distinct kinds of routing.
Agent work wakes only for facts addressed to that agent, such as a message to it
or an error against it. A render refresh is passive: the listener offers one
payload-free signal only when transaction attributes intersect the union of
retained read interests. It never starts a turn. If a fact deserves immediate
attention, its author sends the agent a message.

The render proc responds to that signal by dereferencing the latest immutable
database value. Attribute revisions conservatively select candidate logical
calls; replaying their retained read evidence decides exact semantic staleness.
An equal replay result stops with no render-function call and no append. Stale reads
re-derive once and append even when the resulting render bytes are equal; HTML
patch equality may suppress a block morph without suppressing the historical
observation. Sliding-one signal loss is free because each pass compares retained
revisions through the latest database value. The listener carries no transaction
report, changed-attribute payload, render result, or durable invalidation log.

## Multi-agent context

Agents relate through durable `:seon.cluster.message` rows, shared namespace
ownership, and ordinary refs such as a message's optional `/about` fact. There
is no persisted parent tree or orphan state. Root's namespace page derives the
agent population by `:seon.cluster.agent/id`; an agent web surface derives received and
sent messages, runs, eval receipts, and routed errors by their current refs.
Empty queries render nothing, so visibility needs no notification or
acknowledgement state.

## Inspectability — the human twin of every position

Every agent has a read-only debug surface that begins at that agent's entity and
uses the same root pull and structural floor. It exposes every reachable
schema'd value—including system apparatus hidden from the curated namespace page—preserves
identities and refs for `get-in` path navigation, and never transacts a display choice.
The surface also shows each unit's form, AI, and HTML projections and their source
facts. Through [[observability]], the same surface reaches a run's exact
`:seon.context.capture/prompt`, database basis transaction, ordered
contributions, AI attempts, forms, eval receipts, and errors.

## Configuration

Fresh configuration is one compiled and reconciled database row per cluster.
`config/default.edn` supplies one shipped decision for every registered dial;
an explicitly selected sparse EDN overlay may replace those decisions for one
start or apply operation. Omitted overlay keys inherit defaults, extra keys are
ignored, and every declared key is rigorously validated. The caller may supply
a typed environment map while compiling that input. Running consumers never
read process environment variables or a configuration file.

`seon.config/apply!` is the one exact reconciliation mechanism and
`seon.config/effective` reads the ordinary effective map from an immutable
database value. Acquisition belongs to each consumer: process structures read
at boot, loop handles capture their dials when an agent graph arms, AI settings
resolve once per turn including the agent overlay, and render-request dials
read from that request's database value. Applying facts therefore affects a
consumer at its documented acquisition boundary; it does not silently rebuild
a graph, executor, or web server.

Routes, context membership, instruction imports, and skills are not a config
manifest. Routes are the canonical Reitit table; context is the schema-derived
root pull and retained history;
instructions are ordinary cluster or agent refs. Configuration contains only
registered decision attributes from the config section of
the family declarations under `resources/seon/schemas/`.

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

Prompt acquisition resolves the system text and the agent's retained context
generation under one selected render profile. That one ordinary result flows
unchanged through context capture, token accounting, every retry, the provider
request owner, and the debug surface. None of those consumers re-resolves live config
after the prompt database value has been chosen.

## See also

- [[ui]] — the block, its three projections, the schema-derived root pull and
  selection chain, and the live channel.
- [[data-model]] — admitted context, run, receipt, and program-graph facts.
- [[observability]] — context captures, attempts, receipts, errors, and blobs.
- Dated PRD research — cache-stability, render-prominence, and context trials.
- [[think-in-clojure]] — a fn's specced in/out is the query substrate for
  both rendering and running.
