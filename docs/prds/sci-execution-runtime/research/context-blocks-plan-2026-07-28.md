---
type: research
status: active
tags: [prd, research]
---

# Context blocks — convergence plan

This plan designs the context-block rung. It does not sequence the wider
program; `../plan/README.md` remains the only program ordering. The package
table below records only dependency edges and falsifiable exits inside this
rung.

The contract to close is:

```text
one immutable database value + one agent + one effective cap set
  + declared trusted runtime inputs
  → one ordered block derivation
  → one router request per declared output kind
  → :seon.render/ai contributions folded into the prompt
  → :seon.render/html contributions placed on the root or agent page
```

Root is an agent with different block data. A warning is a renderer that queries
facts and contributes nothing while the condition is absent. The prompt is not
a second formatter: it is the ordered reduction of the AI projections from the
same blocks whose HTML projections the human sees.

The shortest falsifier is deliberately structural:

> At one exact database value, replace one installed block's projection symbol.
> The next prompt contribution and the next human surface must both change
> through `seon.render/render`, with no edit to `seon.cluster.prompt`, a route,
> or a page consumer.

The feels-stateful graduation claim is stronger: given the same database value,
agent, caps, and trusted-input snapshot, the complete ordered block projection
is byte-identical; given new inputs containing a real situation change, the
relevant block alone changes; and an agent makes no claim about its situation
that the rendered evidence did not support.

## Dependency ledger

| Dependency or mechanism | Selected source | Existing Seon proof | Use in this rung |
|---|---|---|---|
| Clojure | 1.12.5, `deps.edn:15` | fresh `src/` | Immutable block values, reductions, stable sorting |
| Malli | 0.20.0, `deps.edn:16` | `src/seon/schema/*.edn` through `seon.schema.edn` | Block, contribution, and rendered-context contracts; honest generators |
| Datahike | `357ffc87c800`, `reference-code/datahike` | `seon.render.block/blocks`, N2 transitions, `:seon.db/trigger` transaction metadata | One immutable database value, component refs, history-derived request cause, exact database identity |
| SCI | `8fac6e88f32d`, `reference-code/sci` | `seon.sci.eval`, `seon.sci.admit` | Bounded evaluation of agent-authored projections after N5 acquisition |
| core.async Flow | 1.10.874-alpha3, `deps.edn:19` | `seon.flow`; N4 plan C2–C8 | HTML push pipeline, one active evaluation per block/kind, equality suppression and fan-out |
| Generic render router | `44435f07b`, `src/seon/render.clj:95-147` | late-resolved projection, flat error values | The only output-kind routing entry |
| Block family, packages 1–2 | `5e71715c1`, `fa4cb38f4`, `492f2a17e`; `src/seon/schema/block.edn:1-169`, `src/seon/render/block.clj:149-567,628-754` | ordered pull, unit, surface, single-map bounded expansion, entity/ref following, page, generic panel, whole-block replacement | The durable unit and the exact four-cap, per-path-visited, depth-first expansion contract this rung must preserve |
| Current prompt facts | message landing `722adb18e`; `src/seon/cluster/prompt.cljc:230-275` | prompt tests cover identity, peers, sender, interruption, prior-wait continuity, trigger, and execution grammar | Every current prose contribution becomes a named block without loss |
| Run cause | `src/seon/cluster/loop.cljc:540-559`; `src/seon/cluster/message.cljc:70-86` | run open records `:seon.db/trigger`; `message/trigger` reads that run's creating transaction | The held run identifies its recorded opener; later turn cause remains a separate observability fact |
| Error projections | `1c7abb6a7`; `src/seon/error.clj:367-396` | facts select generic or specialist projections through `seon.render/render` | A failed block encloses the existing flat error value; context adds no error family or keys |
| N4 live render plan | `n4-plan-2026-07-27.md` and `n4-contracts-2026-07-27.md` | package 1 green; block-targeted benchmark | Exact-value render registration, guarded evaluation, block-targeted morphs |
| N5 program graph | `n5-plan-2026-07-27.md` | reviewed and revised, owner decisions still required | `:seon.fn` and `:seon.ns` facts, acquisition at a basis, current-namespace render-function discovery |
| Context target | `docs/seon/architecture/context.md` | complete projection, render twins, cache gradient | Intended behavior; not evidence that source has landed |
| Prompt-router issue | `docs/seon/issues/prompt-assembly-bypasses-the-render-router.md` | open blocker | Closed by the core prompt-convergence package |

No new dependency is required. Datastar SDK, http-kit, and `resources` are
already on the default classpath after `b8601fabe`. In particular, this rung needs no context
registry, renderer table, acknowledgement state, notification queue, or stored
render.

## Quarry verdict

### What worked

The quarry's durable gold is smaller than its implementation:

- **The agent owned its block set.** `src-old/seon/agent/ctx.cljc:1619-1639`
  pulled one component collection and sorted it by priority plus name. Root did
  not receive a second catalog; its different tree was copied at creation.
- **Presence selected placement.** `:seon.render/ai` meant prompt,
  `:seon.render/html` meant human surface, and both meant twins. An AI-only
  warning cost no pixels; an HTML-only widget cost no prompt tokens
  (`src-old/seon/agent/ctx.cljc:1675-1744`).
- **The renderer owned the condition.** Warning, plan, transcript, canvas, and
  namespace families queried their facts and returned no contribution when
  absent. Nothing stored a seen flag or a rendered warning.
- **One rendered-block value served assembly and inspection.**
  `rendered-child-blocks` carried name, order, estimated tokens, AI text, and
  hiccup together; the prompt assembler reused the already-rendered AI strings
  rather than rerunning every renderer
  (`src-old/seon/agent/ctx.cljc:1710-1777`).
- **Per-agent versus shared was a query property.** A renderer using the agent
  id scoped its query. A renderer omitting that filter saw cluster-wide facts.
  There was no stored scope enum.
- **Root was already mostly data.** `/` and `/agent/{id}` shared the same shim
  and feed shape; root selected agent `"root"` and differed mainly through its
  fleet/canvas and root-only blocks
  (`src-old/seon/route.cljs:64-72`,
  `src-old/seon/web/datastar.cljs:929-935`).
- **Instruction colocation worked.** The plan block taught decomposition only
  when the plan was empty, then removed that teaching once a real plan existed
  (`src-old/my/plan/internal.cljc:1743-1812`). Its HTML twin was colocated with
  the same plan derivation. This is the owner's 2026-07-10 feedback made
  executable: the state that needs an instruction and the instruction are one
  renderer.
- **The cache insight was correct.** Stable blocks before volatile blocks
  preserve provider prefix reuse, and a chain hash identifies the first changed
  block (`src-old/seon/agent/ctx.cljc:1857-1959`).

These are the lessons to adopt. None requires the old namespace, acquisition
executor, codec, CLJS page model, or block-specific orchestration.

### What was brittle

- **Priority pretended to be measured stability.** A hand-set integer and one
  cache breakpoint classified every future block
  (`src-old/seon/agent/ctx.cljc:1762-1777`). The target instead derives
  within-band order from recorded content changes and token cost.
- **The page was a second acquisition/composition path.**
  `src-old/seon/agent/ctx/driver.cljs:205-338` separately pulled the agent and
  config, special-cased canvas, invoked HTML renderers, rebuilt surfaces, and
  assembled a whole page. Prompt and page shared ideas, not one execution.
- **Every change morphed the whole page.** N4 measured the consequence: a
  one-row block morph was 287 bytes and 0.004 ms while the equivalent
  250-event whole-page morph was 82,893 bytes and 0.460 ms
  (`n4-contracts-2026-07-27.md:98-131`).
- **Async acquisition leaked into every family.** Warnings, canvas, namespace,
  transcript, and plan each grew member batches, paging, error translation,
  and injected invocation choreography. The fresh JVM can query the immutable
  database value directly; a block should be a pure function over its unit.
- **Stored symbols needed an EDN codec.** The old mixed string/symbol/literal
  slots required encode/decode on read. N4 package 1 narrowed durable slots to
  qualified symbols stored natively.
- **A whole-prompt escape hatch bypassed blocks.**
  `src-old/seon/agent/ctx.cljc:1820-1854` could replace the block fold with one
  literal or symbol. The fresh `seon.cluster.prompt` currently repeats the same
  mistake directly.
- **Manifest copies aged in place.** Root overlays were elegant desired-state
  input, but existing agents retained creation-time copies. A renderer bug or a
  new standing block therefore needed explicit reconciliation or per-agent
  installation. Core block declarations need one idempotent seed/reconcile
  owner, while render content remains derived.
- **Literal standing prose drifted from state.** The quarry root-role literal
  and broad system instructions survived even when no corresponding condition
  held. The plan-block colocation result showed the better rule: irreducible
  role text may be a block; operational teaching belongs beside the function
  and state that make it relevant.
- **Auto-run depended on the program graph but was bolted on.**
  `src-old/seon/agent/ctx/render_fns.cljc:19-81` synthesized blocks from
  acquired function rows, while the stored block path separately claimed to be
  complete. The target needs one explicit selection boundary joining installed
  anchors and derived current-namespace renderers before either kind is routed.
- **File blocks violated the final data authority.** Fresh per-render
  filesystem reads made a prompt depend on working-directory state, not one
  immutable database value. Initialization pages or imported source facts are
  the surviving route for durable instruction content.

### Colocation rule

The quarry supplies one binding design rule:

> A block owns both the facts that make guidance relevant and the guidance
> projection. When the facts are absent, the block contributes nothing.

Examples:

- no plan → the plan block teaches plan creation;
- an open plan → the same block shows anchor, frontier, and recent completion,
  not the empty-plan lesson;
- a recovered interrupted run → the interruption block states exactly what
  the facts prove;
- no interruption → no warning;
- a reply-evaluation mode → the execution block teaches only that mode's
  grammar;
- root fleet facts → the root fleet block teaches what the human and agent can
  act on, not a standing orchestration manual.

Instructions do not become their own universal block merely because they are
prose. They remain colocated with the function/data owner that can prove when
they apply.

## Fresh-tree map and dependency fence

### Landed shape

The fresh tree already supplies the right nucleus:

- `:seon.block/{name,priority}` and
  `:seon.cluster.agent/blocks` are registered in
  `src/seon/schema/block.edn`.
- `seon.render.block/blocks` derives one ordered stored collection;
  `unit` attaches the exact database value and agent id
  (`src/seon/render/block.clj:131-199`). It does **not** yet attach caps or
  live-process input.
- `surface` and `surfaces` route one declared kind through
  `seon.render/render` and isolate failures by block
  (`src/seon/render/block.clj:201-336`).
- `expand` accepts one closed `:seon.render/expansion` map. Slot filling and
  entity/ref following share the same four admission caps, independent
  node/depth budgets, per-path visited set, and deterministic depth-first,
  left-to-right elision (`src/seon/render/block.clj:337-508`).
- `page` supplies that exact expansion map and derives top-level layout rather
  than storing a layout kind (`src/seon/render/block.clj:510-567`).
- `install-tx` replaces a same-name block wholesale within one agent and emits
  no transaction for an empty request
  (`src/seon/render/block.clj:713-754`).
- `seon.cluster.prompt/prompt` still concatenates identity, peer/message
  grammar, interruption, prior-`my.run/wait` continuity, sender-aware trigger,
  and execution grammar directly (`src/seon/cluster/prompt.cljc:230-275`).
- Domain errors already select generic or specialist projections and enter the
  generic router as the closed flat `:seon.error/value`; blocks must enclose,
  not reshape, that value (`1c7abb6a7`).

The landed N4 slices prove durable mechanics, bounded expansion, generic data
panels, and HTML placement. They do not yet
prove a context, prompt fold, state-gated omission, N5-authored renderer, or
root/ordinary-agent page through the live registration/SSE pipeline. No
`seon.cluster.render.registration`, equality snapshot, or completed-result
cache exists in fresh `src/`; those remain dependencies, not present evidence.

### What can land before N5

The pre-N5 context package can use only compiled core projection symbols and
facts that already exist:

| Block | Scope | Facts | AI projection | HTML projection |
|---|---|---|---|---|
| `:identity` | per-agent | agent id; `sci.eval/agent-namespace` derivation | identity and where definitions already land | optional compact agent header |
| `:peers` | shared query, per-agent exclusion | agent rows | peer population plus `my.message/send` grammar, omitted when alone | optional peer summary |
| `:trigger` | per-agent/current run | held run's creating transaction, `:seon.db/trigger`, message content and sender | the exact current request with sender when present | later transcript/chat surface; may be AI-only initially |
| `:interruption` | per-agent | prior run, forms, receipts, run error | exact interrupted/failed-run notice, or omitted | error/recovery card, or omitted |
| `:continuity` | per-agent | preceding run's terminal `my.run/wait` value | the promised wait note, or omitted | optional continuity card |
| `:execution` | per-agent/config | reply-evaluation fact and available `my.run` dispositions | only the applicable reply grammar | usually omitted |
| `:problems` | shared derivation, installed selectively | current `seon.problems` facts plus the process owner's captured `:seon.cluster.run/live-processes` set | concise actionable problems | landed problems HTML |
| `:fleet` | root membership only | agent rows and derived work/problem state | concise cluster summary | root cards/page |

The current prompt helper functions should move or reduce into these
projection owners. `seon.cluster.prompt` keeps only block selection, AI
contribution validation, stable reduction, and the returned rendered-context
value. No temporary program registry or fake namespace row is needed.

### What must wait for N5

These depend on committed `:seon.fn`/`:seon.ns` facts, acquisition at the exact
basis, and callable SCI Vars:

- the current namespace's source and compact function/schema context;
- discovery of functions whose output contract declares AI or HTML;
- automatically derived block descriptors for current-namespace renderers;
- agent-authored renderers surviving restart and becoming visible to another
  agent;
- hot redefinition of an authored projection through program facts;
- namespace steward context and cross-agent program-graph visibility.

The dependency is semantic, not scheduling conservatism. Before N5 there is no
durable fact proving that an agent-authored symbol exists, has a complete
contract, belongs to a namespace, or can be installed into an SCI context.
Building a temporary atom/Var registry would be the old system in a new name.

## Designed mechanism

### The block unit

A durable installed block remains:

```clojure
{:seon.block/name :interruption
 :seon.block/priority 40
 :seon.render/ai 'seon.context.interruption/ai
 :seon.render/html 'seon.context.interruption/html}
```

The landed `seon.render.block/unit` currently adds only the database value and
agent id. The core context contract extends one request/unit builder—not each
projection call site—to add the effective inputs:

```clojure
{:seon.db/db <exact immutable database value>
 :seon.cluster.agent/id "agent-a"
 :seon.sci.admit/caps <the four effective database-config caps>
 :seon.cluster.run/live-processes <process-owner snapshot>}
```

Caps are required for every projection, not only `page`: the builder threads the
same set into AI, HTML, generic data-panel, and authored units, and the router
admits the returned value with those caps before AI reduction or HTML
expansion. HTML then calls the landed two-argument
`(expand hiccup {:seon.render/surfaces ... :seon.sci.admit/caps ...
:seon.db/db ...})`; context does not invent another budget, visited rule, or
elision order.

The live-process set is the one honest non-database input. The cluster process
owner captures it once beside the selected database value and supplies it
through this builder; prompt, page, debug, and capture reuse that snapshot.
If it is absent, a membership containing `:problems` refuses at request
construction—never substitutes `#{}` or “assume alive.” The contribution
capture records the input snapshot's sorted process identities so the
projection can be reproduced. Until the N4 process owner exposes that seam,
`:problems` stays out of core prompt/page membership even though its compiled
projection already exists.

The unit does not carry a connection, latest-value callback, retained output,
scope flag, page route, or renderer result. Projections query the exact
database value plus only their declared trusted runtime inputs. Per-agent
versus shared behavior remains visible in the query: using the agent id scopes;
omitting it shares.

Installed blocks are component children because their membership is genuinely
per-agent: root receives fleet/problems; an ordinary agent does not. Projection
functions and program rows are cluster-shared. No renderer source or
instruction body is copied onto each block.

Current-namespace renderers are derivable membership, not durable children.
After N5, one selection function produces the ordered candidate vector from:

- installed component blocks; and
- render-capable functions in the agent's current namespace, derived from the
  program graph.

That function is the only join. Both candidates become the same ordinary block
shape before routing. It must reject a derived name collision with an installed
name or apply one explicit precedence ruling; it may not silently merge maps.
A stable derived name is the qualified keyword corresponding to the function
symbol.

### State-gated contribution

Key presence answers whether a block can render a kind. The projection result
answers whether it has content at this database value.

The clean contract should have three disjoint results:

```text
rendered: kind + output
omitted:  kind, no output key
failed:   flat :seon.error value
```

This avoids storing nil, avoids using blank prose as a sentinel, and lets a
warning renderer query facts and explicitly omit itself when clean. The
rendered-context fold keeps only successful, non-omitted AI contributions.
A failed AI block contributes a bounded, block-named error statement so the
agent knows its context is incomplete; silent omission would turn a system
failure into confabulation fuel.

The exact omitted-result shape is an owner decision below because it touches
the landed router union and the standing question about nil in function
contracts.

### Prompt convergence

`seon.cluster.prompt` becomes an assembler, not a prose owner:

```text
blocks(db, agent)
  → request :seon.render/ai for each AI-declaring block
  → validate string | omitted | flat error
  → retain ordered contribution records
  → reduce their exact bracketed text
  → return {text, contributions, database identity}
```

Each contribution record carries at least:

- block name;
- effective position;
- projection symbol;
- admitted AI text or flat error;
- estimated tokens;
- content hash;
- semantic cache band.

The loop consumes only the returned text for the model call, but turn capture,
debug, cache measurement, and the human context inspector consume the same
contribution vector. No consumer reruns a projection to reconstruct metadata.
The historical prompt blob remains the byte ground truth; the captured database
value plus contributions explains how it was built.

This is an explicit revision of the sealed N3 interface. The core-context
package changes these four owners atomically:

- `src/seon/schema/prompt.edn`: the request names the held
  `:seon.cluster.run/id`, agent id, effective caps, and required trusted runtime
  inputs; the result is a closed rendered-context map containing non-empty
  text, ordered contributions, and exact database identity;
- `seon.cluster.prompt/prompt`: returns that map and owns no situation prose;
- `seon.cluster.loop`: passes the held run id, extracts the exact
  `:seon.cluster.prompt/text`, and alone places that string in
  `:seon.ai/prompt`; and
- `test/seon/cluster/prompt_test.clj` plus
  `test/seon/cluster/turn_test.clj`: preserve every current
  presence/absence invariant while adding contribution/router invariants.

The prompt-router issue's acceptance rows must name this schema/function/loop/
suite revision; “loop handoff” alone is not a seal.

Trigger content is not selected by asking for unanswered work again. The
`:call` path currently does that and can choose a later message after run open
has already answered the original. The revised request names the held run, and
the trigger block calls `seon.cluster.message/trigger` to follow that run's
creating transaction to its exact `:seon.db/trigger`. It then derives message
content and sender at the same database value. The run opener is the correct
cause for this pre-turn prompt contract, not a timeless law: when turns become
durable, `:seon.agent.turn/cause-message` records the exact inbound message a
turn absorbed, including later input (`observability.md:55-57`).

The interruption block reuses the proven rule that excludes the held run and
inspects the preceding run. This keeps prompt and page capable of deriving the
same situation without a prompt-only message argument.

### Root and agent pages

There is no root branch in the block mechanism:

```text
GET /
  → agent id "root"
  → root's installed + derived blocks
  → :seon.render/html

GET /agent/{id}
  → that agent id
  → that agent's installed + derived blocks
  → :seon.render/html
```

Root's fleet page is one or more root-installed blocks, not a separate system
view model. Ordinary agent identity, current request, recovery, plan,
transcript, and canvas arrive as their domain blocks land. The route chooses an
agent; it never chooses a renderer.

HTML remains pushed by N4's block-targeted pipeline. AI remains pulled when a
turn needs a prompt. Both ask the same block selection for a kind at an exact
database value and both enter the same router. A browser tab is never a
prerequisite for an AI projection.

### Projection execution

Routing and execution are related but not identical:

- the router reads the output-kind key and projection symbol;
- the projection executor resolves and invokes that symbol;
- admission bounds the returned value before the evaluation boundary disarms.

Compiled core projections can land before N5. Agent-authored projections cannot
be host-`requiring-resolve`d; N5 acquires them into an SCI `ctx`. Therefore the
one router needs one computed invocation seam, not a second authored-render
router. Provenance/program-graph facts decide compiled core versus acquired
agent function. Both paths return the same router result union and pass through
the same admission owner.

This is an explicit correction to the current fresh implementation:
`seon.render/render` directly `requiring-resolve`s and invokes a host Var
(`src/seon/render.clj:120-147`). That is sufficient for package 1 fixtures but
cannot execute an N5-only SCI Var. The contract author must settle this seam
with the N5 evaluator owner before sealing authored context.

### Cache gradient

The baseline stores no completed render results. N4's remaining live pipeline
may coordinate one active evaluation, join concurrent consumers, replace a
pending request with the newest database value, and equality-suppress identical
HTML morphs. Those are delivery/coordination invariants, not evidence that
retaining a settled result benefits a later prompt or tab.

Before adding process-local result retention, instrument each
`(agent, block name, output kind, exact database identity)` with:

- misses, concurrent joins, later hits, and invalidations;
- projection evaluation wall time and admitted result weight; and
- retained lifetime and the consumer that reused the value.

The N4 registration package publishes these observations without retaining
settled values first. The keep/delete experiment is predeclared: enable bounded
retention only for a measured class whose later-hit time saved exceeds its
retained byte-time cost over the sealed workload; otherwise delete the layer.
The report records thresholds and raw distributions before any “cache win.”
AI and HTML have independent active/result slots under one invalidation owner;
no result crosses kinds.

The second, separate layer is **database-derived ordering evidence**. Turn
capture records each sent AI contribution's name, content hash, estimated
tokens, effective position, and semantic band. Later ordering derives observed
change risk within a band. Nothing writes `stable?`, `last-changed`, or a
mutable score onto the block.

The initial deterministic order uses semantic bands and the stored priority as
a prior, with name as final tie-break:

- fixed role and installed anchors;
- namespace/program context;
- current-namespace render functions;
- transcript/active continuity;
- free dynamic tail.

Bands do not cross. Learned ordering within a band waits until turn evidence can
show that it improves real cached tokens and does not degrade task outcomes.
An explicit epoch and hysteresis margin freeze the learned order long enough
for prefix caching to benefit. Until that measurement exists, priority is an
honest prior, not mislabeled observed stability.

One correction to the N4 seal is required: a block can declare independent AI
and HTML projection functions, so one completed value cannot serve both kinds.
The minimum correct coordination shape is one invalidation owner with
independent per-kind active/result slots. Retaining those settled slots across
requests remains disabled until the measurement above earns it.

## Package boundaries and falsifiable exits

| Package boundary | Can start when | Owns | Must not own | Falsifiable exit |
|---|---|---|---|---|
| Core context contract and N3 seal revision | Landed N4 bounded expansion plus message lane `722adb18e` | rendered-context/contribution/request shapes, one caps/liveness unit builder, omission/error behavior, AI reduction; explicit prompt schema/function/loop/test revision | N5 program discovery, web transport, plan/transcript domains | Identity, peers/message grammar, interruption, wait continuity, sender-aware trigger, and execution grammar are routed named blocks; the loop sends the returned exact text; deleting an AI key removes only that contribution |
| Core block projections | Core context contract sealed | compiled renderers over exact db and declared trusted input; colocated instructions | prompt concatenation, ambient config, stored renders | Clean state omits interruption/continuity; planted interruption and wait facts render independently; the trigger follows the held run; missing trigger produces the one sealed refusal with no database change |
| Seed and membership | Core block projections sealed; agent creation owner identified | default installed block data, root sparse membership, whole-block replacement/reconcile | renderer source copies, root page branch, derived N5 membership | Fresh root and fresh ordinary agent have the intended different component sets; reapply converges with zero transaction; same-name replacement removes a deleted kind |
| Prompt convergence | Core context and seed contracts green | reduction over routed AI contributions; held-run trigger selection; exact-text loop handoff; current prompt/turn invariant migration | bespoke identity/peer/wait/sender/warning/trigger prose | The open issue names and passes the prompt schema/function/loop/suite revision; a later queued message cannot replace the held run's trigger; changing one projection symbol changes the sent prompt without consumer edits |
| Problems input seam | Cluster process owner exposes one captured live-process set | trusted input construction and capture for `:seon.cluster.run/live-processes` | a default set, stored liveness, a problems-only builder | Missing liveness refuses before rendering a membership containing `:problems`; supplied liveness reproduces the same AI/HTML results and is recorded in sorted capture evidence |
| N4 live composition | N4 registration/evaluator/HTTP contracts green | per-block/kind active coordination, HTML push, AI pull, equality suppression, cache measurements | retained completed results before evidence, prompt socket waits, separate AI renderer, whole-page morph | Thirty-two tabs join one active HTML eval; one simultaneous prompt uses the independent AI slot; unrelated commits emit neither; misses/joins/hits/cost/weight are reported before any retention experiment |
| Program-graph context | N5 round trip green | namespace block, current-namespace render discovery, SCI invocation through the router | temporary registry, stored auto-run blocks, author filter | Agent A defines a contracted twin; Agent B in that namespace sees it after restart; changing the definition changes both requested kinds through the same router |
| Cache observations | turn evidence/context capture facts exist | per-contribution hash/token/position/band capture, result-retention measurements, and candidate within-band order | stored stability fields, provider-specific ordering code, assumed cross-consumer reuse | Re-rendering one database value is byte-identical; retention is kept or deleted by the predeclared cost/hit threshold; a separate experiment compares static prior versus learned order on cached tokens and outcome |
| Completeness audit | core + N5 context green; later domain facts available | replay/confabulation scorer and missing-block diagnosis | model-written summaries, coaching answers | Every self-state claim in a replayed answer grounds to transcript first or another rendered contribution; each failure names the missing or contradictory block |

Plan, transcript, memory, collaboration, schedules, and canvas remain their
domain owners. When their facts land, each adds a renderer and installed or
derived membership through this contract. The context package must not absorb
their schemas or reimplement their queries.

## Owner decisions required before contract seal

The following are already ruled and are implementation/documentation
alignment, not morning choices:

- installed anchors plus derived current-namespace renderers
  (`context.md:308-324`); only collision precedence remains open;
- run-opening `:seon.db/trigger`, read back from the held run; durable
  turn-cause is separately `:seon.agent.turn/cause-message`;
- qualified-symbol-only durable projections; strings/vectors from
  `492f2a17e` are runtime declarations, not durable block attributes;
- fixed semantic bands, priority as prior, and measurement before learned
  order;
- one invalidation owner with independent AI/HTML slots; this is a correctness
  correction to N4, with completed-result retention still measurement-gated;
  and
- byte-exact prompt blob plus rendered transaction and per-block
  hash/token/position/band capture (`observability.md:34-59`,
  `context.md:440-446`).

Domain errors likewise keep the landed flat `:seon.error/value`; an enclosing
contribution may name its block but cannot add keys to that closed shape.

### Decision 1 — how a clean conditional block omits itself

- **Option A:** return nil or blank text and let consumers filter it. This is
  quarry-compatible but weakens function contracts and makes blank a sentinel.
- **Option B:** omit the block before routing by duplicating its condition in the
  selector. This keeps output schemas simple but moves the condition away from
  its instruction/render owner.
- **Option C:** add a closed router success alternative carrying kind but no
  output key; rendered, omitted, and flat error remain disjoint by key presence.

**Recommendation: Option C.** It keeps the query and guidance colocated, stores
nothing, and avoids nil. This decision also resolves the standing question about
`[:maybe]` in function return contracts for this mechanism.

### Decision 2 — authored projection invocation

- **Option A:** keep `requiring-resolve` only. Works for compiled core Vars and
  cannot execute N5-only SCI Vars.
- **Option B:** add an authored-render path outside `seon.render`. Works
  mechanically and creates the forbidden second router.
- **Option C:** give the one router one projection-invocation seam whose
  implementation is computed from program-graph provenance: compiled core Var
  or N5-acquired SCI Var, both admitted and returning the same result union.

**Recommendation: Option C.** Seal it jointly with the N5 evaluator owner. The
router still owns kind selection and errors; invocation becomes explicit
instead of assuming every qualified symbol is a host Var.

### Decision 3 — installed/derived name collision precedence

- **Option A:** refuse a derived current-namespace renderer whose qualified
  block name collides with an installed block. Loud and deterministic, but an
  agent cannot intentionally replace a standing anchor.
- **Option B:** installed membership wins explicitly. Supports an administrative
  pin, but can hide a same-named authored renderer.
- **Option C:** derived membership wins explicitly. Makes local authoring
  immediate, but lets namespace code shadow root/standing anchors.

**Recommendation: Option A.** Refuse with both sources named. Add precedence
later only with a concrete use case; silent map merge is never an option.

### Decision 4 — exact capture schema and transaction owner

- **Option A:** a turn-owned pre-provider transaction commits refs to the exact
  prompt blob plus a component vector of contribution evidence and the
  trusted-input snapshot. It makes “what was sent” durable before the
  unobservable remote call, but requires the turn owner to accept the block
  schema.
- **Option B:** the turn's terminal transaction writes the same evidence after
  the provider call. It adds no pre-call transition, but process loss can leave
  an attempted remote call without its structured prompt explanation.

**Recommendation: Option A.** Before the provider call, the turn owner commits
the prompt bytes, rendered database transaction, held-run/turn cause, ordered
contribution records, and sorted live-process snapshot together. Large text
remains blob-backed; contribution rows carry name, kind, projection, hash,
tokens, position, band, and the existing flat error value when failed.

## Sealed-suite sketch

The next contract author writes these tests only after an independent review
falsifies or accepts this plan and the owner rules Decisions 1–4. **Every
proof named anywhere in this plan becomes a recurring, `bin/test`-discovered
JVM `deftest`; a benchmark, manual browser drive, or one-off script can measure
cost but cannot close an exit.**

### Suite owners and deterministic identities

- `test/seon/context_test.clj` owns example contracts and properties over a
  fresh in-memory database created **inside every trial**.
- `test/seon/context_live_test.clj` owns the reset/current-ancestor,
  HTTP/SSE, 32-client, process-loss, reconnect, and post-N5 acquired-renderer
  interaction. It launches a child JVM from the current
  `java.class.path`—no built artifact or running operator—and uses only
  `tmp/context-live/<seed>/<trial>/`. It reuses `seon.cluster/start!` inside the
  child, following the existing boot tests; the parent owns and replaces the
  child and reports startup/runtime cost.
- Property seeds are fixed: determinism `2026072801`, placement `2026072802`,
  isolation `2026072803`, reduction `2026072804`, coordination `2026072805`,
  scope `2026072806`, membership `2026072807`, bounded expansion/caps
  `2026072808`, held-run trigger selection `2026072809`, and completeness
  scoring `2026072810`.
- A shared test helper derives database/cluster names, agent/message/run IDs,
  instants, UUIDs, ports, and project-local paths solely from `(seed,
  trial-index)`. No `random-uuid`, wall clock, random free port, or unordered
  map/set iteration participates in an oracle. Failure output prints seed,
  trial index, size, generated value, schema explanation, deterministic
  identities, and the complete shrunk check.

### Deterministic recurring oracles

| Contract class | Planted evidence | Independent oracle |
|---|---|---|
| Current prompt census | Named block facts for identity, peers/message grammar, interruption, continuity, trigger/sender, and execution | The test derives expected ordered block names directly from planted membership and conditions, records each projection invocation/result in a test ledger, and compares both returned contributions and exact prompt text with that ledger—not with each other |
| Placement and omission | All AI-only, HTML-only, twin, and clean conditional declaration combinations | Expected invocations come from declaration-key presence in planted facts; omitted success is asserted as its one ruled union arm, never nil/blank or a selector-side duplicate |
| Error isolation | Successful neighbours around unresolved, throwing, and returned flat-error projections | Expected neighbour bytes come from planted literals; the failed contribution names its block while its nested error equals the existing closed `:seon.error/value` exactly |
| Held-run trigger | Run A opens on message A; message B arrives before A's `:call` pass | `message/trigger` independently establishes A from the run transaction; the invocation ledger must read A/sender A, omit B, and the provider request must contain A's planted content |
| Missing trigger refusal | Held run whose creating transaction has no trigger | Deepest non-empty `ex-data` equals the sealed `:seon.cluster.prompt/no-trigger` refusal rule, no partial rendered-context exists, and before/after sorted datoms are identical; if the seal chooses a flat error instead, schema and oracle change atomically to that one exact alternative |
| Scope | Distinct, tagged rows for agents A/B plus tagged shared rows | The oracle enumerates exact datom IDs each projection may read: A omits B and B omits A; shared includes the planted shared set. Byte equality alone never passes scope |
| Membership/collision | Installed rows plus generated N5 render-function rows | Expected names and provenance derive independently from planted rows; result is the exact sorted vector or the one ruled collision refusal, and a database census proves no derived descriptor was transacted |
| Caps and expansion | Generated slot/ref graphs with the four explicit caps | A small test-only depth-first, left-to-right counter computes admitted node positions and expected elision/cycle paths from the planted graph; results must match the landed single-map `expand` contract, with the same caps on AI admission, generic panels, and HTML expansion |
| Exact database identity | Values differing separately in basis transaction, branch, `as-of`, `since`, history, or commit ID | Invocation ledger and returned contribution identity must name the requested value; no result produced for one identity may satisfy another |
| Coordination, not assumed cache | Generated invalidate/begin/join/settle/newest-pending schedules | Event ledger proves at most one active evaluation per kind, concurrent joins only, independent AI/HTML slots, and newest pending value; settled later reuse remains a measured feature, not a correctness assumption |
| Colocation | Empty then non-empty plan-style facts, without any acknowledgement write | Expected teaching/content alternative derives directly from planted state; a sorted datom diff proves only domain facts changed |
| Root/agent symmetry | Root and ordinary agent memberships with uniquely tagged rows | Both route through the same selected functions; exact expected names derive from each planted membership, so equal omission cannot masquerade as symmetry |
| Feels-stateful completeness | Fixed replay replies containing grounded, contradictory, and unsupported self-state claims plus the exact transcript/contribution evidence | A test-owned expected claim-to-evidence ledger names every supported block and every unexplained claim; the pure scorer must return that ledger exactly, so a model-written explanation cannot grade itself |

Every generated value validates against the exact registered schema before use.
Generators create representation-valid states; the properties assert semantic
invariants with the independent ledgers above. Shrinking preserves identity
derivation and schema validity.

### Recurring live oracle

`test/seon/context_live_test.clj` performs one event-driven scenario:

1. start the child cluster from the current ancestor and wait for explicit
   database, graph, and HTTP readiness;
2. create root and an ordinary agent through the real initialization path,
   connect `/` and `/agent/{id}` over real HTTP/gzip SSE, and assert exact
   planted block IDs and morph targets;
3. attach 32 client feeds to one agent, request AI at the same database value,
   commit one relevant fact, and assert from the invocation/event ledger one
   joined HTML evaluation, one independent AI evaluation, no unrelated
   projection calls, and no whole-page morph;
4. stop the child mid-render, replace it from the same current ancestor,
   reconnect, and compare repaint bytes and exact block IDs with a fresh
   derivation from current facts—no stored render or acknowledgement; and
5. after N5, transact one contracted twin, replace the child, enter its
   namespace from another agent, and assert both kinds pass the same router and
   guarded invocation seam.

Readiness/completion latches and observed SSE events synchronize the test;
there are no sleeps. A clock is only a loud foreign-process backstop whose
firing fails with child stdout/stderr and the last readiness event. The parent
always stops the child it owns in `finally`, and the suite reports child-JVM
startup and scenario duration without treating either measurement as a
correctness oracle.

## Independent falsification gate

Before contracts are drafted, an independent reviewer must try to disprove:

- that the held run's creating transaction selects exactly its trigger even
  when a later unanswered message exists;
- that the installed-plus-derived membership can stay collision-free without
  a stored auto-run row;
- that one router invocation seam can serve host Vars and N5 SCI Vars without
  weakening the guarded-eval contract;
- that the omission alternative composes with the existing router and N4
  surface unions;
- that the one request/unit builder propagates the exact four caps and trusted
  live-process snapshot to every projection and preserves the landed
  single-map bounded expansion;
- that per-kind active coordination matches N4's flow/equality-suppression
  contracts without assuming completed-result retention;
- that every pre-N5 block uses already-installed live-boot attributes rather
  than fixture-only schema;
- that the contribution vector is sufficient for turn capture, prompt replay,
  token accounting, and later cache-gradient measurement without another
  projection pass; and
- that every exit is claimed by the deterministic recurring suite owner and an
  oracle independent of the production assembler.

The review returns evidence and dispositions against this document. It does not
write contracts. Only after the owner rules the decision batch and the plan is
revised to absorb that falsification should a separate contract agent seal
schemas, stubs, and suites.

## Graduation evidence

This rung is complete only when all of the following are true:

- production prompt assembly contains no identity, peer/message, sender,
  trigger, interruption, wait-continuity, or reply-grammar concatenation
  outside block projection owners;
- the prompt request/result schemas, prompt function, held-run loop handoff,
  prompt suite, and turn suite have revised together without losing a current
  presence/absence invariant;
- a later queued message cannot replace the held run's recorded trigger;
- a census finds no ordinary prompt/page consumer calling a projection
  function directly;
- root and ordinary agent routes select an agent and use the same block/page
  mechanism;
- every advertised projection symbol resolves through the one invocation seam;
- prompt, page, debug, and capture consume the same ordered block derivation at
  one immutable database value, effective cap set, and captured trusted-input
  snapshot;
- conditional guidance appears exactly with its owning state and disappears
  when that state disappears;
- N5-authored render functions survive restart and become cross-agent visible
  from program facts;
- per-kind invocation counts prove active fan-out without claiming that AI and
  HTML outputs are the same value or that later reuse is beneficial;
- completed-result retention, if enabled, passed its predeclared
  miss/join/hit/cost/weight threshold; otherwise it is absent;
- cache-order changes, if enabled, are backed by recorded contribution history
  and a measured improvement rather than a priority rename;
- `test/seon/context_live_test.clj` recurrently proves current-ancestor boot,
  real HTTP/SSE, 32-client fan-out, process loss, reconnect repaint, and child
  ownership with no stored renders;
- the open prompt-router issue is closed with the contract and live evidence;
  and
- a replayed feels-stateful audit finds every agent self-state claim grounded
  in the transcript or another rendered block, with no unexplained gap.

## Review 2026-07-28 (independent)

Verdict labels are the same narrow classes used by the N4/N5 reviews:

- **CONFIRMED-DEFECT** — the proposed contract or cited support is false or
  internally incomplete.
- **STALE-VS-TODAY** — the statement described an earlier tree, but no longer
  describes the committed or concurrently landing tree reviewed here.
- **QUESTION** — an owner-taste decision remains implicit and must be sealed
  before an implementation lane can infer it safely.

### Citation audit

I read every cited `src-old/` and fresh-tree range, plus the cited N4 contracts,
the prompt-router issue, the current architecture, and the relevant tests. The
dependency checkouts match the ledger: Datahike
`357ffc87c8009f342b239145802e1385d4a18ca9`, SCI
`8fac6e88f32d53a5fd82ebe80640881e317b84fd`, and core.async
`dc35f3e0d7bc2eef502e77982f48641f025c8051`.

The quarry conclusions are supported. The cited ranges contain the one pulled
and sorted block set, presence-based AI/HTML placement, one combined rendered
block value, the whole-prompt escape hatch, the block-chain hash, the second
page/acquisition path, current-namespace auto-run derivation, root's shared shim
shape, and plan-teaching colocation. The fresh `seon.render` and package-1 block
ranges likewise contain the late-resolved router, ordered pull, exact-database
unit, per-kind surfaces, derived page, and whole-block replacement. The N4
measurement at `n4-contracts-2026-07-27.md:98-131` supports the quoted
287-byte/0.004-ms versus 82,893-byte/0.460-ms comparison.

The exceptions and newly landed collisions follow.

1. **STALE-VS-TODAY — the dependency line citations moved when package 2
   promoted the web dependencies.** Clojure, Malli, and core.async are now at
   `deps.edn:15-19`, not `:13`, `:14`, and `:17`. Commit `b8601fabe` also moved
   Datastar SDK/http-kit into default `:deps` and added `resources` to
   `:paths`. The conclusion “no new dependency is required” is correct; the
   three line citations are not.

2. **STALE-VS-TODAY — the prompt and run-open citations describe committed
   `b15d3c418`, not the active message lane.** In the reviewed working tree,
   `prompt` is at `src/seon/cluster/prompt.cljc:230-275` and run-open trigger
   metadata is at `src/seon/cluster/loop.cljc:540-559`. More importantly, the
   prompt now derives sender identity, peer population, and a previous
   `my.run/wait` note in addition to identity, interruption, trigger, and
   execution grammar (`prompt.cljc:245-273`). The plan's pre-N5 block inventory,
   core-context exit, prompt-router issue acceptance, and graduation census omit
   those three contributions.

   No block/message schema collision was found: `my.message/send` returns the
   disjoint `:my.message/{to,content}` value
   (`src/my/message.cljc:72-100`), while delivery produces existing
   `:seon.cluster.message/*` facts. The collision is ownership: message sender,
   peer discovery, pause continuity, and message grammar are now prompt prose
   that the convergence package must move into named blocks without losing.
   Seal only after that lane commits, then re-grep the final shapes.

3. **CONFIRMED-DEFECT — the proposed unit carries caps that the landed unit
   does not.** The plan says `seon.render.block/unit` adds
   `:seon.sci.admit/caps` (§“The block unit”), but the function adds only
   `:seon.db/db` and `:seon.cluster.agent/id`
   (`src/seon/render/block.clj:181-199` in the reviewed tree). The bounded N4
   expansion contract is exact: the same four admission caps, per-path visited
   refusal, independent node/depth budgets, and deterministic depth-first,
   left-to-right elisions (`render/block.clj:337-508`; `fa4cb38f4`). Passing
   caps only to `page`/`expand` does not make them available to an AI projection,
   a generic data panel, or an authored projection.

   Revise the context contract to name one request/unit builder that threads the
   effective caps into every projection and then admits the returned value
   before either AI reduction or bounded HTML expansion. Do not create
   context-specific caps, a second visited set rule, or a different elision
   order.

4. **STALE-VS-TODAY — N4 package 2 has landed its first slice, not the
   registration/cache owner the plan presumes.** Commit `492f2a17e` landed
   literal runtime declarations and entity/ref expansion in the existing
   router and block owner. Ref-following is now part of the same depth-first
   walk, with the same node/depth caps and per-path visited set, and `expand`
   now takes one `:seon.render/expansion` map
   (`src/seon/render/block.clj:337-508`). The context plan must consume that
   committed shape rather than seal the earlier three-argument signature.

   No `seon.cluster.render.registration`, live render graph, SSE owner, or
   equality snapshot exists under fresh `src/` yet. Therefore “N4 registration
   memory holds” in the cache section must still be future tense and a
   dependency on the remaining package-2 contract, not present evidence.

### Prompt convergence and current-trigger falsifier

5. **CONFIRMED-DEFECT — prompt convergence requires an explicit revision of the
   sealed N3 interface and suite.** Today `prompt/prompt` has output
   `:seon.cluster.prompt/text`, the schema is a non-empty string
   (`src/seon/schema/prompt.edn:4-5`), the loop passes that value directly as
   `:seon.ai/prompt` (`src/seon/cluster/loop.cljc:640-664`), and
   `prompt_test.clj` asserts directly on strings. The proposed
   `{text, contributions, database identity}` result cannot land without
   revising all four together:

   - `:seon.cluster.prompt/request` and output schemas;
   - `seon.cluster.prompt/prompt`;
   - the loop handoff that extracts the exact text for `seon.ai`;
   - `prompt_test.clj` and `turn_test.clj`, preserving every current
     presence/absence invariant while adding router/contribution invariants.

   The existing prompt suite deliberately does not pin prose bytes
   (`test/seon/cluster/prompt_test.clj:2-9`), so the migration is implementable;
   it is still a seal revision. The package table's generic “loop handoff” is
   not enough. Name this revision explicitly in the core-context package and in
   the open prompt-router issue before sealing.

6. **CONFIRMED-DEFECT — the current call path can answer the wrong trigger, and
   the plan does not name the repair.** Run-open correctly records the selected
   message as `:seon.db/trigger` transaction metadata
   (`loop.cljc:540-559`). But the subsequent `:call` branch asks
   `work/unanswered-triggers` again and passes its first result to `prompt`
   (`loop.cljc:640-645`). The opening transaction has already made the original
   message “answered”; if a second message arrived while the held run awaited
   its call pass, that later message is now the first unanswered one. The prompt
   can therefore describe a message different from the run's recorded cause.

   The active message lane already contains the correct pure read:
   `seon.cluster.message/trigger` follows the run identity datom to its creating
   transaction and returns that transaction's trigger
   (`src/seon/cluster/message.cljc:70-86`). The convergence package must make the
   prompt request identify the held run (or pass its already-derived trigger)
   and use that one cause. It must also reconcile the longer target model:
   `docs/seon/architecture/observability.md:55-57` says a future turn records
   `:seon.agent.turn/cause-message` because a run may absorb later input. Do not
   seal “run opener is always turn cause” as a timeless context law.

7. **CONFIRMED-DEFECT — the proposed pre-N5 `:problems` unit is missing its one
   non-database input.** `seon.problems/block` explicitly refuses when
   `:seon.cluster.run/live-processes` is absent because neither `#{}` nor
   “assume alive” is truthful (`src/seon/problems.clj:264-296`). The plan's unit
   contains only database value, agent id, and caps, while its block table lists
   problems as ready before N5. Either:

   - define the one trusted unit-input injection seam and include live processes
     in its contract and capture story; or
   - keep the problems block out of core prompt/page convergence until the N4
     process owner supplies that input.

   It cannot be described as a pure database-derived block while silently
   depending on process liveness.

The remaining pre-/post-N5 boundary is otherwise honest. Identity is a pure
derivation from agent id, trigger/interruption/execution use already installed
run/message/config facts, and fleet uses agent/work/problem facts. None requires
program-graph facts. Current-namespace source, render-function discovery,
durable authored definitions, SCI acquisition at a basis, cross-agent authored
visibility, and hot redefinition genuinely require N5. Plan, transcript,
memory, schedules, collaboration, and canvas wait on their domain facts, not on
N5 merely because they are later.

The local Qwen proof does not add a context mechanism: it confirms the ordinary
four-fact OpenAI-compatible target and that the live loop consumes one prompt
string (`local-provider-2026-07-28.md:72-102,128-166`). It reinforces finding 5:
the rendered-context map must be reduced to its exact string before the one
provider boundary; no local-provider branch belongs in context assembly.

### Cache gradient and owner decisions

8. **CONFIRMED-DEFECT — one cache layer is admitted by assumption rather than
   measurement.** The ordering half is disciplined: the plan keeps fixed bands,
   treats priority as a prior, records per-contribution evidence, and defers a
   learned order until real cached-token and outcome measurements exist. That
   matches `docs/seon/architecture/context.md:440-466`.

   The process-local reuse half is different. It proposes a retained exact-value
   result cache and cross-consumer reuse without a measurement of projection
   cost or repeat frequency. N4 measured serialization/admission and block
   morph size, not the cost or hit rate of AI/HTML projection evaluation. The
   reactive rule permits caching only measured expensive derivations.
   Single-flight, newest-pending replacement, and the equality snapshot may
   remain as pipeline coordination; retaining completed results for later
   prompt/tab reuse needs a predeclared measurement and a keep/delete threshold.
   Record misses, joins, hits, evaluation cost, and retained weight before
   calling the layer a cache win.

9. **STALE-VS-TODAY — five of the eight “owner decisions” are already ruled,
   and one is a correctness correction rather than taste.**

   - **A:** installed overrides plus derived current-namespace renderers is
     already the target rule (`context.md:308-324`). Strike A's three-way
     choice; retain only the real collision-policy question (refuse versus an
     explicit precedence). Reconcile `ui.md`'s older complete-seed wording.
   - **B:** run-opening `:seon.db/trigger` metadata is already owner-ruled and
     implemented. Strike the choice; fix finding 6 and state the future
     turn-cause boundary.
   - **C:** durable projection attributes are already qualified-symbol-only.
     Commit `492f2a17e` adds strings/vectors only as runtime declarations.
     Strike the choice and cite that durable/runtime split.
   - **D:** real owner decision. The current router has rendered or flat-error
     results, while block surfaces turn an HTML nil into a not-hiccup failure.
     Seal the explicit omitted-success alternative and its behavior for AI and
     HTML.
   - **E:** not a taste choice. One completed value shared across unlike AI and
     HTML projections is false; N4's earlier one-value/one-registration prose
     must be revised. One invalidation owner with per-kind active/result slots
     is the minimum correct shape; measure retained reuse per finding 8.
   - **F:** already ruled by the architecture's observed-stability design.
     Strike the choice; the enablement experiment remains required.
   - **G:** real owner decision, jointly with N5. Seal one router invocation
     seam for compiled Vars and acquired SCI Vars without a second router.
   - **H:** capture is already ruled: the architecture requires the rendered
     transaction, byte-exact prompt blob, and per-block hash/token/position/band
     evidence (`observability.md:34-59`; `context.md:440-446`). Strike the
     yes/no choice. The remaining contract work is the exact contribution
     schema and which turn transaction/blob owner persists it.

   The morning owner batch can therefore shrink to D, G, block-name collision
   precedence, and the exact capture schema/transaction owner. A, B, C, F, and
   H are documentation/implementation alignment; E is an upstream N4 seal
   correction.

Commit `1c7abb6a7` also removes an apparent choice: domain errors already enter
the generic router as flat error values with producer-selected generic or
specialist projections (`src/seon/error.clj:367-396`). A failed context block
may add block identity in its enclosing contribution, but must not invent a
second error shape or merge block keys into the closed `:seon.error/value`.

### Sealed-suite findings

The sketch gets several constitutional points right: discovered JVM `deftest`s,
one fresh database inside every generated trial, exact-schema validation,
complete shrink reports, sorted iteration, real sockets/process loss in the
recurring suite, event-driven readiness, supervisor-owned stop, and a
reset-from-current-ancestor path.

10. **CONFIRMED-DEFECT — “fixed recorded seeds” still does not seal any seed or
    deterministic trial identity.** Name the numeric seed for every property
    and derive database IDs, times, agent/message/run IDs, and filesystem paths
    from that seed plus trial index. A fresh `random-uuid` database inside each
    trial satisfies isolation but violates replayability. The failure report
    must print seed, size, generated value, schema explanation, and complete
    shrunk check.

11. **CONFIRMED-DEFECT — two proposed oracles compare outputs produced by the
    same producer.** “Prompt text equals the reduction of the returned
    contributions” can let one broken assembler manufacture two mutually
    agreeing wrong outputs. “Shared renderers return byte-identical output for
    both agents” can pass because both calls omit the same required fact. Keep
    those as relations, but the acceptance oracle must independently derive the
    expected ordered names from planted block facts and record each projection
    invocation/result separately; then compare both returned fields with that
    independent ledger. Scope must assert the exact rows read/omitted, not only
    equality of two producer outputs.

12. **CONFIRMED-DEFECT — the missing-trigger example does not satisfy the
    refusal constitution.** “Produces no partial prompt” is necessary but not
    sufficient. Assert the named refusal rule from the deepest non-empty
    `ex-data` and independently prove the database is unchanged. If convergence
    converts this boundary to a flat error value, revise the sealed N3 refusal
    contract explicitly and assert that exact alternative instead of accepting
    either throw or value.

13. **QUESTION — the live proof's ownership must be named before the suite is
    drafted.** “Start a real child cluster” is compatible with `bin/test` only
    if the discovered test owns a project-local path, needs no built artifact or
    running operator, observes readiness, and reports the child-JVM cost. Name
    the `test/**/*_test.clj` owner and whether the proof uses the existing
    cluster boot helper or a child JVM. The required interaction is good:
    current-ancestor boot + real HTTP/SSE + 32-tab fan-out + process loss +
    reconnect repaint in one recurring surface.

### Ready-to-seal verdict

**Ready to seal? no.** First revise the plan to:

1. absorb the final committed message and N4 package-2 shapes;
2. thread the one cap set through every unit and preserve bounded N4 expansion;
3. name the prompt schema/function/loop/suite seal revision and repair trigger
   selection from the held run;
4. resolve the problems block's live-process input;
5. measure before retaining completed render results;
6. reduce the owner batch to the genuinely open decisions above; and
7. seal numeric seeds, deterministic trial identities, independent oracles,
   refusal assertions, and the exact live-test owner.

After those changes, the core direction—one ordered block derivation, one
router, AI reduction for the agent and HTML placement for the human—is ready
for contract authoring.

### Dispositions

1. Fixed in the dependency ledger: current `deps.edn` lines and the promoted web/resources dependencies are cited.
2. Fixed in the fresh-tree census, pre-N5 inventory, core-context exit, and graduation gate: committed message lane `722adb18e` contributes peers, sender, wait continuity, and message grammar.
3. Fixed in “The block unit” and the caps oracle: one builder threads the same four caps through every projection, admission, generic panel, and landed expansion map.
4. Fixed in the dependency ledger and landed-shape section: `492f2a17e`'s single `:seon.render/expansion` map and ref-following contract replace the stale signature, while registration remains future work.
5. Fixed in “Prompt convergence” and the first package boundary: prompt request/result schemas, prompt, loop extraction, prompt tests, and turn tests revise as one N3 seal.
6. Fixed in “Prompt convergence,” the package exits, and the held-run oracle: the request names the held run and `message/trigger` selects its creating transaction; future turns own `:cause-message`.
7. Fixed in “The block unit” and “Problems input seam”: the process owner supplies and capture records live processes, and missing input refuses before rendering `:problems`.
8. Fixed in “Cache gradient”: completed-result retention is absent initially and survives only a predeclared miss/join/hit/cost/weight experiment.
9. Fixed in “Owner decisions”: A, B, C, F, H, and the per-kind cache correction are struck as choices; only omission, invocation, collision precedence, and capture ownership remain.
10. Fixed in “Suite owners and deterministic identities”: ten numeric seeds and seed/trial-derived databases, times, IDs, UUIDs, ports, and paths are sealed with complete shrink reporting.
11. Fixed in “Deterministic recurring oracles”: planted membership and invocation/read ledgers independently derive expected names, bytes, and exact scoped rows.
12. Fixed in the missing-trigger oracle: deepest refusal data and unchanged sorted datoms are required, with any flat-error migration forced to revise schema and oracle atomically.
13. Fixed in “Suite owners” and “Recurring live oracle”: `test/seon/context_live_test.clj` owns a current-classpath child JVM, project-local paths, event readiness, cost reporting, HTTP/SSE fan-out, process loss, and reconnect.
