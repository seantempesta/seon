---
type: research
status: active
tags: [research, context]
---

# Old context assembly — end-to-end quarry

## Conclusion

The old system was not one context mechanism. It was three mechanisms composed
at the last moment:

1. a separately configured system message;
2. a creation-time, name-keyed vector of manually selected context blocks; and
3. a small genuinely derived layer: current-namespace render functions
   discovered from indexed function schemas.

The block functions themselves contained substantial good data-oriented work.
They read one immutable database value, derived plan/frontier/error/namespace
projections, omitted clean or absent state, and returned ordinary values. The
namespace renderer is the richest quarry: it pulled the program graph, rendered
signatures and one-line docstrings, exposed bodies at greater requested detail,
walked requires, and later replaced parsed requires with persisted require
edges.

The scaling failure was membership and framing. A human maintained a total
ordered block list, root overlays, profiles, special-resident replacements,
per-block prose, caps, and cache priorities. That list was copied into every
agent at birth. A newly useful derived fact did not enter context because it
existed; somebody had to invent and install a named block for it. Existing
agents then retained old copies when the manifest changed. This is exactly the
owner's diagnosis: derived content lived inside a hand-built outer shell.

The old system did have prompt-caching work, including a real Anthropic stable
prefix and parallel block invocation. It did **not** measure block churn or sort
the whole context by observed change time. Top-level ordering was a static
integer convention. The namespace block alone used transaction recency to put
churning code later.

The fresh direction should therefore keep the old renderers' data-in/data-out
discipline and namespace detail gradient, but delete the copied loadout. Context
membership should fall out of `render(namespace, distance)`, routed problem
inputs, and transcript history. Only the system/REPL/AGENTS.md scaffold should
remain planted.

## Scope and historical coordinates

`src-old/` is the final quarry after the tree split. Several prompt-driving
owners were deleted immediately before or after that split, so this report uses
three explicit Git coordinates:

- `7c782e156^` — the last coherent pre-deletion prompt driver;
- `1bd1d21d^` — the large pre-minimal context loadout immediately before the
  2026-07-11 cutover; and
- `6c85a1938` — the concise namespace renderer with signature/detail/member
  drill, before the following full-source redesign killed that interface.

The current `src-old/` files are cited directly when they preserve the final
implementation. Historical citations use
`<commit>:<path>:<line>-<line>`.

Transcript aging is not re-quarried here. Its four-band prototype, shipped
three-band result decay, retained-turn window, shared settled budget, and
omission semantics are already established in
[[transcript-aging-quarry-2026-07-29]]. This report treats that work as an
input and follows only how the transcript block entered the larger prompt.

The ruled destination is recorded in the active plan:

- namespace context is `render(its namespace, distance N)`, with distance zero
  as name, one as signatures plus docstrings, and deeper views exposing bodies
  (`docs/prds/sci-execution-runtime/plan/README.md:535-585`);
- agent birth starts with its own namespace full and required namespaces
  partial (`README.md:631-640`);
- context is mostly transcript, with blocks surviving only for the static
  scaffold (`README.md:692-698`); and
- independently rendered context should be sorted by churn with transcript
  last (`README.md:699-705`).

## Assembly from trigger to provider input

### 1. An inbound fact opened or resumed a run

The immediate trigger was a committed inbound-message datom. The installed
listener subscribed to additions of `:seon.agent.message/to`; on a transaction
it acquired the agent and candidate messages from that transaction's
`:db-after`, derived whether the agent was idle/running/paused/terminated, and
opened or renewed a run
(`7c782e156^:src/seon/agent/loop.cljs:333-430`). Listener installation and
resynchronization are at
`7c782e156^:src/seon/agent/loop.cljs:484-522`.

`drive-claimed-run!` handed the run to the portable driver
(`7c782e156^:src/seon/agent/loop.cljs:97-105`). The pod leaf selected
`:render` and called `turn/render-phase!`
(`7c782e156^:src/seon/agent/driver/pod.cljs:31-43`).

This is important for the new design: context did not need a second event
queue. It was derived in response to a durable run/message transition.

### 2. The render phase pinned one database value

`render-phase!` took the held run plus its database value and called
`render-prompt` with agent ID, run ID, and that exact value
(`7c782e156^:src/seon/agent/turn.cljs:699-706`). The prompt orchestrator passed
the value into `ctx.driver/render-prompt!` and rejected a renderer that returned
a different value (`turn.cljs:477-550`).

The compiled prompt driver acquired three members together:

- the agent and its complete `:seon.agent/ctx` component set;
- the cluster configuration; and
- the AI configuration.

That acquisition and its resource bound are at
`7c782e156^:src/seon/agent/ctx/driver.cljs:274-304`. The driver resolved the
system message from the configuration row, with the code constant only as a
fallback (`driver.cljs:305-309`).

### 3. The stored block set came from the agent entity

At render time there was no global catalog merge. The agent entity already
owned the complete copied component set. `agent-blocks` decoded each component
and sorted by `(priority, name)` (`src-old/seon/agent/ctx.cljc:1619-1634`).
A named render profile replaced that selection with an ordered list of patches
merged over same-name stored blocks
(`src-old/seon/agent/ctx.cljc:1786-1803`).

An agent-level `:seon.render/ai` could bypass the block tree as a whole-prompt
override (`7c782e156^:src/seon/agent/ctx/driver.cljs:310-315`,
`src-old/seon/agent/ctx.cljc:1817-1834`). That was another exceptional
membership path.

### 4. Stored block functions were invoked in parallel

For every selected block whose `:seon.render/ai` was a symbol, the driver built
a call containing:

- agent ID and pulled entity;
- cluster configuration;
- the pinned database value;
- the block as `:seon.render/node`; and
- the current run ID.

The request is built at
`7c782e156^:src/seon/agent/ctx/driver.cljs:131-152`. All selected calls in a
batch ran through `Promise.all`
(`7c782e156^:src/seon/agent/turn.cljs:466-475`). Failures became visible
block-local error text instead of aborting the entire prompt
(`driver.cljs:157-168`).

This was real parallel rendering, but it ran in two waves: stored blocks first,
then derived blocks. It was not a global dependency-aware render graph.

### 5. The namespace result discovered additional render functions

The `:namespaces` block returned more than text. It also returned the current
namespace and its indexed function rows
(`src-old/seon/agent/ctx/namespaces.cljc:858-882`). The driver selected public
functions whose persisted output schema declared `:seon.render/ai` or
`:seon.render/hiccup`, skipped symbols already pinned by stored blocks or the
canvas, and synthesized priority-30 blocks
(`src-old/seon/agent/ctx/render_fns.cljc:19-46,66-81`).

Those derived blocks invoked the authored function at the same database value
and clipped its AI output to the configured render-function cap
(`render_fns.cljc:83-116`). The prompt driver performed this discovery after
the stored namespace result, invoked the derived blocks as a second parallel
batch, concatenated both collections, and sorted them by `(priority, name)`
(`7c782e156^:src/seon/agent/ctx/driver.cljs:321-339`).

This was the old system's clearest derived-membership success: writing an
ordinary function with the right output contract changed context without
editing a central loadout. The new renderer-discovery design should generalize
this property rather than generalize stored blocks.

### 6. The pure tail capped, omitted, bracketed, and joined

After symbol slots had become literal results, the pure composer:

- omitted HTML-only blocks from the model prompt;
- omitted blank AI results;
- applied an optional per-block estimated-token cap, keeping either head or
  tail;
- wrapped each contribution in named `;;;` brackets; and
- inserted a cache boundary between priority bands.

The omission and cap rules are at
`src-old/seon/agent/ctx.cljc:1670-1711`; block rendering is at
`ctx.cljc:1713-1745`; ordering and boundary insertion are at
`ctx.cljc:1747-1777`. `rendered-context-from-entity` returned both the joined
text and per-block token estimates (`ctx.cljc:1805-1849`).

There was no global total prompt cap in this owner. Total size was an emergent
sum of section-local bounds, namespace curation, transcript policy, and
uncapped blocks.

### 7. System message and context remained distinct provider inputs

The final old manifest's system message was a separate configuration value,
not the first ordinary block (`config/system.edn:363-410`). The render driver
returned it alongside context text and provider resolution
(`7c782e156^:src/seon/agent/ctx/driver.cljs:347-367`).

Adapters sent the system message and `:seon.ai/ctx` as distinct provider
fields. `debug-full-prompt` concatenated them with a visible boundary only for
capture/debugging; its docstring explicitly says that concatenation was never
sent to the model
(`7c782e156^:src/seon/ai.cljs:670-714`).

The render phase captured that debug string as the prompt blob and stored the
rendered basis transaction and character count on the turn
(`7c782e156^:src/seon/agent/turn.cljs:709-737`). Thus:

```text
provider request = system message + separately encoded context
captured prompt  = system message + visible boundary + context
context          = ordered, bracketed, nonblank AI block outputs
```

## How membership was decided

### Creation-time copying was the dominant rule

Manifest resolution materialized complete ordinary and root context component
trees, sorted by `(priority, name)`
(`src-old/seon/config/resolve.cljc:1557-1571`). Root started from the ordinary
tree, merged scalar overrides, merged home requires by namespace identity, and
upserted block patches by block name
(`resolve.cljc:1573-1591`).

At agent creation, `resolve-agent-context` selected root or ordinary context by
the literal identity `"root"`, detached component IDs, merged any per-mint
override, and filled defaults (`src-old/seon/config.cljs:1122-1159`).
`initial-agent-tx` then put that full context and the new home namespace in the
same birth transaction
(`7c782e156^:src/seon/agent.cljs:384-437`).

So ordinary membership was not derived per turn from “what this agent cares
about.” It was:

```text
manifest ordinary loadout
  + root-only same-name patches, if id = "root"
  + per-mint overrides
  -> copied component entities owned by the agent forever
```

The one dynamic exception was the current-namespace authored-function
discovery described above.

This copying boundary explains later drift: configuration apply changed the
template but did not update existing agents. The warnings restoration required
an explicit retrofit, and the plan-surface rename required a narrow migration
(`docs/seon/issues/archive/warnings-block-not-installed-by-manifest.md:24-34`;
`docs/seon/issues/archive/configured-plan-surface-absent-from-live-program.md:12-31`).

### The final old loadout

The final ordinary manifest declared these components
(`config/system.edn:451-501`):

| Priority | Name | Model contribution | Membership basis |
|---:|---|---|---|
| 20 | `:namespaces` | Yes | Planted for every agent |
| 30 | `:render-fn/<sym>` | Yes, zero or more | Derived each render from current-namespace function schemas |
| 35 | `:canvas` | Yes | Planted; selected canvas content derived |
| 39 | `:interaction-outcome` | No, HTML only | Planted for the human page |
| 40 | `:warnings` | Only when checks fire | Planted; content derived |
| 45 | `:plan` | Always, including empty-state teaching | Planted; plan state derived |
| 100 | `:transcript` | Yes | Planted; events derived |

Root overlaid or added these blocks (`config/system.edn:550-608`):

| Priority | Name | Effect |
|---:|---|---|
| 15 | `:root-role` | Literal root-only prose |
| 41 | `:core-faults` | Derived root-only fault query |
| 42 | `:instrumentation-gaps` | Derived root-only runtime/database check |
| 43 | `:orphaned-agents` | Derived root-only fleet query |
| 90 | `:canvas` | Same-name patch selecting the system canvas and moving it late |
| 1000 | `:free-dynamic-tail` | Live clock/load/process tail |

Because the root overlay patched the ordinary `:canvas` by name, it retained
the ordinary AI renderer while changing priority and content.

The autocomplete profile did not derive a loadout either. It manually selected
only plan and transcript, with caps of 200 and 440 tokens respectively
(`config/system.edn:515-548`).

### The larger pre-cutover loadout

The 2026-07-11 minimal cutover (`1bd1d21d`) deleted a much larger default
loadout. Immediately before it, the in-code vector was
`1bd1d21d^:src/seon/config.cljs:189-252`:

| Priority | Block | Source of content | Classification |
|---:|---|---|---|
| 5 | `:soul` | Fresh file read | Hand-selected static identity |
| 8 | `:agents` | Fresh AGENTS.md read | Hand-selected static scaffold |
| 10 | `:shared-instructions` | Database singleton rows | Derived content, hand-planted membership |
| 12 | `:skills-catalog` | Database skill facts | Derived content, hand-planted membership |
| cached band | `:skill/<name>` | Selected skill body | Presence-set expanded at birth |
| 20 | `:namespaces` | Program graph | Mostly derived |
| 35 | `:live-tile` | Current canvas/system renderer | Mixed |
| 40 | `:warnings` | Warning queries | Derived content, hand-planted membership |
| 42 | `:jobs` | Process-local shell job table | Ephemeral, not database-derived |
| 43 | `:test-failures` | Latest database test run | Derived |
| 45 | `:plan` | Database plan facts | Derived content, specialized renderer |
| 46 | `:recent-verbs` | Recent eval/function facts | Derived menu |
| 47 | `:plan-ledger` | Plan facts | Duplicate specialized plan projection |
| 48 | `:relevant-source` | Process-local prefetched embedding stash | Mixed/impure |
| 96 | `:subagents` | Child/run facts | Derived |
| 97 | `:findings` | Recent user-domain database rows | Derived by a hand-written heuristic |
| 100 | `:transcript` | Turn/eval/message facts | Derived spine |

SOUL.md and AGENTS.md were conditionally prepended only when files existed.
Always-on skill bodies were expanded from `:my.skills/load`. Both paths then
upserted by name
(`1bd1d21d^:src/seon/config.cljs:1272-1326,1388-1422`). These were clever
generic configuration mechanisms, but still mechanisms for constructing a
manual loadout.

Commit `1bd1d21d` cut the default to namespaces, plan, and transcript plus
root fault surfaces. Canvas and warnings returned later. The churn itself is
evidence of the loadout problem: deciding the “correct set of blocks” was a
continuous product-design exercise.

## Token budgets: where they actually lived

There was no single owner that could answer “how large may this prompt be?”

| Boundary | Mechanism | Evidence |
|---|---|---|
| Generic block | Optional `:seon.agent.ctx/token-cap`; estimated-token head/tail clipping | `src-old/seon/agent/ctx.cljc:52-74,1691-1711` |
| Namespace function in the concise renderer | 240-character full-body threshold; 280-character one-line doc clip | `6c85a1938:src/seon/agent/ctx.cljs:1126-1134,1208-1247` |
| Final compact namespace card | Body omitted; doc first line softly clipped at 78 characters; referenced schema closure bounded separately | `src-old/seon/agent/ctx/namespaces.cljc:1206-1248,1250-1292`; `src-old/seon/agent/ctx.cljc:1178-1361` |
| Authored render function | Configured estimated-token cap, applied by the derived wrapper | `src-old/seon/agent/ctx/render_fns.cljc:99-116` |
| Warnings/root diagnostic blocks | Manifest caps, normally 512 or 1024 estimated tokens | `config/system.edn:485-487,586-607` |
| Root canvas | 4096 estimated tokens | `config/system.edn:577-580` |
| Autocomplete profile | Plan 200; transcript 440 tail-kept | `config/system.edn:529-548` |
| Transcript | Age bands, retained turns, settled shared budget | [[transcript-aging-quarry-2026-07-29]] |
| Database acquisition | Per-query work/result/result-weight limits, independent of final displayed size | For example `src-old/my/plan/internal.cljc:1388-1451` |
| Whole prompt | **No total cap** | The composer only sums capped/uncapped block results |

The historical failure validates that reading. A fresh-reset prompt measured
about 55,362 tokens against a 50,000-token target; namespaces alone were
47,290 tokens. Function heads duplicated contracts and accounted for about
65% of namespace cost
(`docs/seon/issues/archive/context-budget-fn-head-lean.md:8-69`).

Later, transcript display bounds still did not protect acquisition. Complete
stored strings could exceed Datahike result-weight limits before render-time
clipping, causing the entire transcript block to fail
(`docs/seon/issues/archive/grown-transcript-exceeds-result-weight-budget.md:8-35`).

The lesson is not “add one bigger global clip.” Each renderer needs honest
bounded acquisition and a bounded projection, while the composer needs an
explicit whole-context allocation policy. Distance is a better semantic budget
than scattered character thresholds: it determines what representation is
requested before bulk data is acquired.

## Namespace rendering: the quarry gold

### The concise `render-namespace` interface

Commit `6c85a1938` contains the clearest version of the mechanism the owner
remembered.

#### Acquisition

`pull-ns-data` resolved a `:seon.ns/name`, then reverse-pulled:

- namespace source;
- function symbol, arglists, docstring, source, privacy, schema, and schema
  error;
- schemas; and
- tests with last-pass/last-failure data.

See `6c85a1938:src/seon/agent/ctx.cljs:1168-1206`.

That is the right conceptual input: the renderer consumed indexed program
facts, not a second handwritten API catalog.

#### Signature, docstring, and body detail

`fn-block-ai` constructed a signature from symbol plus arglists, attached
privacy/spec/schema-error flags, included the first docstring line, and chose
body detail:

- `:signature` — header, flags, doc line; never body;
- `:full` — body only when source was at most 240 characters; and
- `:full-body` — complete body regardless of size, for explicit member drill.

The complete branch is
`6c85a1938:src/seon/agent/ctx.cljs:1208-1247`.

This was not truly “bodies by token budget”; it was bodies by a fixed character
threshold. But its semantic gradient is exactly what distance needs:

```text
distance 0 -> namespace name
distance 1 -> public signatures + docstring line
distance 2 -> selected/small bodies + schemas
distance 3+ -> explicit full bodies and neighboring namespaces
```

The new walker should preserve the gradient and replace the magic 240-character
test with the request's actual remaining render budget.

#### Namespace body and demarcation

`render-one-ns-ai` gave `:signature` a public-function-only manifest—no
namespace source, schemas, or tests. In `:full`, a real stored file source was
the authority and rendered once; sparse runtime namespaces fell back to
per-member function/schema/test blocks. Missing and empty namespaces remained
visible rather than silently disappearing
(`6c85a1938:src/seon/agent/ctx.cljs:1274-1368`).

That “real source once, derived member cards only when source is absent” rule
avoided duplication and should survive.

#### Requires and recursion

The concise version parsed the namespace's stored `(ns ... (:require ...))`
source, extracted required namespace symbols, then recursively walked them
dependency-first with a seen set and a requested depth
(`6c85a1938:src/seon/agent/ctx.cljs:1142-1166,1397-1427`).

`render-namespace` exposed:

- namespace name;
- optional member;
- depth, default one;
- AI or HTML format; and
- detail, default signature.

Required namespaces rendered before the requested namespace. A member request
short-circuited recursion and returned that function's complete body. Unknown
members returned the available public names as an error value
(`6c85a1938:src/seon/agent/ctx.cljs:1429-1542`).

This is almost the ruled distance API already. The defect was that it parsed
requires again from source and used mutable atoms for an invocation-local walk.
The replacement should walk persisted code-graph edges with an immutable
accumulator.

### The final always-on namespace block

Later work improved dependency truth while making the interface more
specialized.

The final namespace block derived the current namespace from the agent,
successful eval history, and any assigned plan namespace; pulled the current
namespace's stored require-edge components; combined:

- current namespace;
- real require targets;
- explicit compact selections; and
- explicit full-source selections.

See `src-old/seon/agent/ctx/namespaces.cljc:510-595`.

Require-edge semantics were precise:

- `:refer [f g]` selected exactly `f` and `g`;
- alias, bare, or `:refer :all` selected the whole public callable surface;
- multiple refer edges unioned; and
- `:as-alias` contributed no callable card.

That reduction is at `namespaces.cljc:169-201`.

The formatter then rendered:

- current or explicitly full namespaces as real full source;
- required or explicitly compact namespaces as inert schema/function cards;
- tests only when their presence-set selected them; and
- everything else not at all.

Its selection and ordering are at `namespaces.cljc:884-1005`. Callable cards
included only public function vars with complete usable schemas and rendered
an ordinary invocation contract plus the first docstring line, not fake
definitions (`namespaces.cljc:1219-1292`).

The namespace block also carried an extensive hand-written policy header
(`namespaces.cljc:739-758`). That prose is not quarry gold. A good renderer
should make the shape self-evident, with its normal docstring available through
the same code facts.

### Historical fork and reconciliation

The history shows two opposing attempts:

- `6c85a1938` made direct namespace rendering concise by default and added
  targeted member drill because agents repeatedly requested whole namespace
  dumps when they wanted one function.
- `f01ed7e0e` then “killed signatures” and tried to bind budget through manual
  full-source curation rather than compression.
- Subsequent work restored compact cards for required namespaces and grounded
  their selection in persisted require edges.

The fresh design resolves that conflict with distance. It does not need a
global choice between signatures and source. The request expresses how far and
how deeply to render, and each namespace's renderer spends its budget on the
best representation at that distance.

## Derived contributors versus hand-built contributors

“Derived” below means the contribution's content was a pure projection of
facts or an explicitly pinned input. It does **not** mean its membership was
derived. Except for authored current-namespace render functions, every row was
still planted in a loadout.

| Contributor | Content source | Derived, hand-built, or mixed | Fresh disposition |
|---|---|---|---|
| System message | Literal config string with code fallback | Hand-built universal prose | **Keep only as scaffold**, much smaller |
| SOUL.md | Fresh file | Hand-selected identity prose | **Avoid as universal prompt machinery** unless explicitly routed |
| AGENTS.md | Fresh file | Static content, manually planted | **Keep as scaffold** with pinned content identity |
| Shared instructions | Ordered database singleton rows | Derived content; manually global membership | **Reconceive as routed/static instruction input**, not a standing peer of transcript |
| Skills catalog | Database skill rows plus loaded marker | Derived content; planted catalog | **Reconceive through namespace/data render on demand** |
| Skill bodies | Presence-set-selected file/database body | Hand selection with dynamic content | **Avoid always-on bodies**; route when relevant |
| Namespaces | Program facts, current namespace, require edges, selection sets | Strongly derived, with manual visibility/detail dials and policy prose | **Keep and generalize as `render(namespace, distance)`** |
| Current-namespace render functions | Indexed output schemas | **Derived membership and content** | **Keep the idea** as normal renderer discovery |
| Canvas/live tile | Config pin or latest authored surface, database read provenance | Mixed; specialized presentation owner | UI tabled; later consume the same renderer output, not context-specific logic |
| Interaction outcome | Render facts for HTML only | Derived human surface, no model contribution | Not an AI-context concern |
| Warnings | Registry of hand-coded checks over current facts | Derived results from a hand-maintained problem catalog | **Reconceive as generic routed problems from facts** |
| Core faults | Error facts since last user message | Derived query, root-only hand membership | **Keep the query idea**; route as root/problem context |
| Instrumentation gaps | Database program graph plus live vars | Mixed database/process projection, root-only hand membership | **Reconceive as a problem renderer**; process impurity must be explicit |
| Orphaned agents | Agent/run/parent facts | Derived query, root-only hand membership | **Keep as fleet namespace render**, not a special prompt block |
| Jobs | Process-local job table | Ephemeral and outside database truth | **Avoid in cacheable body**; explicit dynamic/routed input only |
| Test failures | Latest stored test run | Derived | **Keep as namespace problem facts** |
| Plan | Plan graph and run-cause facts | Derived state inside a heavily specialized renderer and planted workflow | **Quarry, do not automatically port**; see below |
| Recent verbs | Recent eval/function rows | Derived menu | **Avoid as a separate menu**; transcript and namespace already own the facts |
| Plan ledger | Plan rows | Derived duplicate of plan context | **Avoid** |
| Relevant source | Prefetched embedding hits in a process-local stash | Mixed and impure over the database value | **Reconceive as explicit routed retrieval context** |
| Findings | Recent user-domain rows selected by string/provenance heuristics | Derived but generic heuristic guessed salience | **Avoid standing top-N dump**; retrieve/render by schema and task |
| Inventory | Counts by attribute/domain | Derived | **Keep as the generic data floor**, lazily rendered |
| Subagents | Child/run facts | Derived | **Render through agent/fleet namespace distance**, not a planted block |
| Root role | Literal identity-specific instructions | Hand-built per-agent-role prose | **Avoid**; root's namespace and facts should make its role visible |
| Free dynamic tail | Clock/load/process memory | Intentionally live, hand-root-only | **Keep separate only if still useful**, never cacheable truth |
| Transcript | Turn/eval/message facts | Derived spine | **Keep as the majority of context**, using the existing aging quarry |
| Whole-prompt override | Literal or authored symbol on agent entity | Hand escape hatch bypassing composition | **Delete** |
| Render profiles | Manually curated block patch vectors | Hand-built alternate loadouts | **Replace with ordinary render requests/modes** |

The old `ctx` family also contains non-contributors:
`acquisition.cljc`, `format.cljc`, `ns_name.cljc`, and `usage.cljc` are helper
owners; they do not independently enter a prompt. `menu.cljc` contains retired
menu contributors, while `canvas`, `warnings`, `subagents`, `namespaces`,
`render_fns`, and `transcript` own the contributors named above.

### The hand-built boundary was wider than prose

The scaling failure was not just literal paragraphs. These were all manual
policy:

- which block names every agent received;
- which extra names root received;
- the priority and cache band of each block;
- the cap and head/tail behavior of each block;
- which home namespaces every agent required
  (`config/system.edn:502-513`);
- which alternate profile names existed;
- which renderer symbol a specialized resident installed;
- which warning checks existed; and
- which namespace presence sets were compact/full/with-tests.

Much of the **content** was derived, but the outer composition still required
somebody to know every concern in advance.

## Database-backed memory and plans

### Memory entered in three different ways

The old system never had one generic “memory projection.”

First, the large pre-cutover loadout installed
`:shared-instructions`. `my.kb.shared/instructions-block` queried an
append-only singleton's instruction rows at the turn's database value, sorted
them oldest-first, and rendered nothing when empty
(`7c782e156^:src/my/kb/shared.cljs:50-90,92-131`). This was genuine durable,
reactive shared guidance.

Second, `:findings` scanned user-domain rows, excluded rows carrying a
lifecycle `status`, selected the ten newest entities, guessed the primary
content from their string attributes, clipped each row, and attached
`my.kb` provenance
(`1bd1d21d^:src/seon/agent/ctx/findings.cljs:1-24,29-47,62-111,164-197`).
This tried to make accumulated knowledge salient, but its generic “newest
strings” heuristic was a manually invented relevance policy.

Third, the final minimal context removed both blocks. Memory remained available
through:

- `my.kb` in every home namespace's require list
  (`config/system.edn:502-513`);
- its compact namespace contract inside `:namespaces`; and
- static system-text instructions telling the model to call `recall` and
  `remember` (`src-old/seon/agent/ctx.cljc:965-985` in the fallback text).

Thus database-backed knowledge did **not** automatically flow into the final
prompt. The agent had to retrieve it, or a specialized block had to guess what
was relevant. The fresh system should prefer explicit retrieval/routed context
and schema-owned renderers over a standing top-N memory dump.

### Plans entered as one specialized block

The final manifest planted `:plan` at priority 45 with
`my.plan.internal/plan-block` (`config/system.edn:488-490`).

Its acquisition queried:

- active steps;
- ready steps;
- five recently completed steps;
- the current run's cause step;
- the selected step's ancestor chain and root rollup; and
- eval evidence used for stuck-work escalation.

The query shapes and limits are at
`src-old/my/plan/internal.cljc:1246-1430`; acquisition over one database value
begins at `internal.cljc:1561-1584`.

The renderer bounded the projection structurally:

- at most seven ready frontier steps;
- at most five recently completed steps;
- one position/ancestor anchor; and
- an optional escalation section.

Those constants and their cache-stability rationale are at
`internal.cljc:1258-1269`. Formatting is at
`internal.cljc:1690-1801`. With no plan, the block still emitted static
decompose-first workflow teaching (`internal.cljc:1743-1758`), so membership
always cost prompt tokens even when state was absent. The final `plan-block`
read one database value and returned either a failure line or that projection
(`internal.cljc:1803-1812`).

`my.plan/plan!` explicitly told the agent that the durable result would appear
in the next turn's plan block (`src-old/my/plan.cljc:907-920`). Plan facts also
affected namespace context: an assigned plan namespace participated in
current-namespace resolution and generated namespace selection
(`src-old/seon/agent/ctx/namespaces.cljc:235-245,510-595`).

The good idea is externalized intent as durable facts and a bounded frontier.
The avoidable part is a bespoke workflow/manual embedded in every prompt.
The active ruling says `my.plan` was never ported and may be replaced by
goal/test modes (`README.md:676-691`). Quarry the data shapes and frontier
projection; do not port the old block merely because it existed.

## Ordering, caching, and parallelism

### What existed

Top-level order was deterministic:

```clojure
(sort-by (juxt :seon.agent.ctx/priority
               (comp str :seon.agent.ctx/name)))
```

That rule appears at agent read, config resolution, profile selection, and
final stored-plus-derived composition
(`src-old/seon/agent/ctx.cljc:1627-1633,1786-1803`;
`src-old/seon/config/resolve.cljc:1557-1564`;
`7c782e156^:src/seon/agent/ctx/driver.cljs:334-339`).

Priority at or below the agent's cache breakpoint—default 20—became the stable
prefix. Everything later became the volatile tail
(`src-old/seon/agent/ctx.cljc:313-315,1747-1777`).

Commit `f2d8bcc08` connected that boundary to Anthropic. The adapter split
context, placed the stable context in an ephemeral-cache system block, and sent
only the volatile tail as the user message. Its source records the before/after
finding: the system-only breakpoint covered about 5.4K of 38K input tokens;
the new split live-proved 17,736 cached of 17,764 input tokens
(`7c782e156^:src/seon/ai/anthropic.cljs:35-51,158-164`).

The namespace block had its own local stability gradient. Stable required
`seon.*` namespaces rendered first, name-sorted; `my.*` and the current
namespace were ordered by the transaction of their namespace-name datom, with
the newest nearest the tail
(`src-old/seon/agent/ctx/namespaces.cljc:917-920,952-999`).

Block symbol calls were parallel within the stored and derived batches, as
described in the assembly section.

`block-chain-keys` could calculate a chain hash for every rendered prefix,
salted per agent (`src-old/seon/agent/ctx.cljc:1876-1957`). There were no
production call sites in the final old tree; the comments say worker reuse
still awaited a future integration. It was a pure experiment, not an active
ordering mechanism.

### What did not exist

No general mechanism recorded each block's content hash over turns, estimated
its change probability, or reordered it from observed churn. The archived
issue says exactly that: static priorities required a human to predict future
volatility, while no block changelog fed ordering
(`docs/seon/issues/archive/context-block-order-is-static.md:12-35`).

There was also no one parallel render followed by change-time sorting.
Stored blocks had to finish before their namespace output could discover the
second derived batch. After that dependency, results were sorted by static
priority, not by their latest fact or content change.

So the fresh direction is an accretion, not a rediscovery:

1. render independent inputs in parallel at one database value;
2. preserve semantic bands that must not cross—static scaffold first,
   transcript last;
3. derive each contribution's most recent relevant fact/change time or use
   observed content history;
4. order stable-to-churning inside those bands; and
5. capture contribution identity, content hash, estimated tokens, and chosen
   order with the prompt.

Do not store a mutable “volatility” field on renderers. Derive it from facts or
captured prior renders.

## What broke

### Wrong context could be plausible and silent

The most direct incident is preserved in source. Aero's default `#merge` was
shallow. A sparse downstream override setting only home requires replaced the
entire nested agent-context map, dropped the intended block tree, and allowed
schema defaults to quietly install the legacy tree. The source says:
“acme ran the wrong context for a day”
(`src-old/seon/config/resolve.cljc:1384-1405`). Commit `fac50bef7` added a
manifest-aware merge at that one seam.

An earlier render-path regression passed `:seon.agent/entity` to the root but
not to child block functions. `:your-entity` returned blank on nil and silently
vanished from the actual model prompt, while the inspector path rendered it.
The prompt dump therefore looked healthy but lacked a promised context section
(`docs/seon/issues/archive/context-loop-regression-sweep-2026-06-25.md:12-53,58-104`).

The pattern matters more than the old key: blank-as-omission makes a missing
required input indistinguishable from legitimately absent derived state. The
new router needs failure-as-value for acquisition/contract failure and omission
only for an explicitly valid absent projection.

Provider retries also once re-read mutable configuration after prompt
acquisition, so prompt, capture, token accounting, and provider bytes could use
different system text. The frozen system prompt was later carried with the
compiled prompt result
(`docs/seon/issues/archive/provider-bridges-dropped-frozen-system-prompt.md:8-22`;
`docs/seon/issues/archive/turn-retries-reread-provider-inputs.md:20-49`).

### Name collisions were an intended escape hatch, not a safe composition law

The old design expressly allowed an agent block with the same name as a
substrate block to override it by name
(`docs/prds/archive/agent-runtime/agent-self-context-spec-2026-06-10.md:78-91`).
The `:relevant-source` owner even documented that an authored block using the
reserved name replaced the core contributor
(`1bd1d21d^:src/seon/agent/ctx/relevant.cljs:21-26`).

The creation-time upsert built an additions map by name and merged the matching
addition over the base (`1bd1d21d^:src/seon/config.cljs:1246-1270`). It did not
refuse duplicate names in the additions collection. A collision could
therefore be deliberate customization or an accidental disappearance, with
the same representation.

No archived old-system issue establishes a separate production incident titled
“block collision.” The evidence supports a structural failure mode, not an
invented outage claim. The new renderer precedence already has a safer shape:
explicit slot redirect, viewer override, owner default, schema default, generic
floor. Every choice is provenance-captured, and same-namespace duplicate
renderers refuse (`README.md:571-585,605-620`). Keep that explicit resolution;
do not restore silent name-map replacement.

### Context could be absent because the manifest forgot it

The default manifest temporarily omitted `:warnings`, so all 15 registered
checks rendered into no agent context. The checks worked; membership did not.
Restoring the block fixed fresh agents, but existing copied contexts still
needed explicit installation
(`docs/seon/issues/archive/warnings-block-not-installed-by-manifest.md:8-34`).

This is the exact class derived membership prevents: if an in-scope namespace
owns a renderer for a present problem fact, no hand-maintained global vector
should be able to forget it.

### Copied contexts went stale

When the plan HTML renderer moved from
`my.plan.internal/plan-block-html` to `my.plan/plan-surface`, twelve existing
agents retained the obsolete symbol in copied component data. The source and
manifest were correct; live prompts/pages still selected the deleted function
until a narrow migration rewrote those stored blocks
(`docs/seon/issues/archive/configured-plan-surface-absent-from-live-program.md:8-31,33-68`).

`ctx/install!` also re-transacted every kept block. A stored symbol read back as
a string, so any agent carrying a canvas block could no longer install another
block (`docs/seon/issues/archive/ctx-install-live-tile-symbol-roundtrip.md:8-29`).

Both failures disappear when membership is derived from current code and facts
instead of copied renderer symbols.

### Budget failures occurred at every layer

- Fresh baseline exceeded its target before agent-authored growth; namespaces
  were 85% of the total
  (`context-budget-fn-head-lean.md:8-54`).
- Required compact cards exposed public implementation functions as tools.
  A live projection advertised 36 `seon.db` and 24 `seon.schema` functions.
  Tightening eligibility reduced the namespace block from 22,106 to 20,406
  tokens
  (`docs/seon/issues/archive/public-functions-leak-into-agent-tool-context.md:8-27,45-58`).
- Per-result transcript decay did not bound the number of old stubs or account
  for full rendered source/narration/error bytes
  (`docs/seon/issues/archive/transcript-decay-does-not-bound-total-context.md:8-38`).
- Render-time clipping did not prevent pre-render database result-weight
  failures (`grown-transcript-exceeds-result-weight-budget.md:8-35`).

These are all consequences of budgeting after membership. Distance must govern
selection and acquisition before materialization, while transcript aging
governs the one intentionally long historical band.

### Namespace visibility and schema publication drifted

At different times:

- fresh agents omitted the compiled core entirely until compact rendering was
  widened (`3b868599e`);
- compact cards treated all public implementation functions as agent tools;
- `my.ns/compact!` could remove an explicitly selected namespace instead of
  keeping its compact card
  (`docs/seon/issues/archive/my-ns-compact-can-hide-namespace.md:8-36`); and
- namespace-block selection attributes were registered for validation but not
  published as stored Datahike attributes, so a fresh agent's first render
  failed on an unknown attribute
  (`docs/seon/issues/archive/namespaces-block-schema-publication-regressed.md:8-38`).

The common cause was duplicated policy: indexability, callable eligibility,
stored schema, selection, and render membership were decided in different
places. The fresh code graph and renderer discovery must be the shared
authority.

## Keep, avoid, reconceive

| Old mechanism | Keep | Avoid | Reconceive in the ruled direction |
|---|---|---|---|
| Immutable database-value render | One pinned value for acquisition, projection, provider config, capture | Ambient re-reads, process cache changing historical bytes | Every context contribution is a pure render request over the same value plus explicit ephemeral inputs |
| Namespace renderer | Indexed program facts; signatures; one-line docs; real source once; missing/empty visibility; require-edge truth; targeted member drill | Parsing requires a second time; magic character thresholds; hand-curated full/compact sets; large policy header | `render(namespace, distance, budget)`: 0 name, 1 signatures/docs, deeper bodies; walk persisted code edges through the one router |
| Derived authored render functions | Output-schema-driven discovery and ordinary value return | Synthesized stored block wrappers and special pin registries | Normal renderer discovery from the living code graph, with precedence and provenance |
| Transcript | Ordered factual REPL narrative, result identity, decay, retained-window plateau, omission rather than synthetic summary | Treating it as one peer among many snapshots; per-item caps without total bound | Make it most of context and always last; adopt [[transcript-aging-quarry-2026-07-29]] |
| Static scaffold | System message, REPL grammar, AGENTS.md | SOUL/loadout/product-manual sprawl; duplicated teachings inside plan/namespace/warnings | Three tiny scaffold inputs with pinned identity; everything situational comes from renders or transcript |
| Plan | Durable intent, dependency facts, bounded frontier, recent-done anti-redo band | Automatically porting `my.plan`, empty-state workflow sermon, plan-ledger duplicate | Evaluate goal/test modes first; if plan facts survive, expose them through the owning namespace renderer |
| Memory | Durable `my.kb` facts, provenance, explicit recall | Standing newest-N string dump; global instruction wall | Retrieval becomes routed arbitrary context; facts render by their schema/owner at requested distance |
| Problems/warnings | Query current facts; omit valid clean state; root sees system faults | Hand registry plus manually installed block per problem family | Problem facts route to namespace owners/root and render through the same walker |
| Caching | Stable byte identity, deterministic tie-breaks, provider boundary, per-contribution hashes | Human-guessed forever-priorities; live state inside cacheable body | Parallel render, semantic bands, database/content-history-derived churn order, transcript last |
| Override | Viewer/owner-specific rendering is valuable | Silent block-name replacement and whole-prompt bypass | Explicit slot redirect → viewer → owner → schema default → generic floor, captured as provenance |
| AI/HTML twins | One fact projection can serve agent and human | UI-specific alternate context assembly | Keep one router; UI remains tabled until the AI-context contract settles |

## Recommended design constraints for the owner session

1. **Delete the loadout concept.** Do not translate the final seven ordinary
   blocks into seven new planted blocks. That would preserve the defect.
2. **Make transcript and routed input the outer prompt shape.** The prompt is:
   small scaffold, arbitrary routed problem/instructions, namespace-distance
   render, optional derived situation renders, transcript last.
3. **Make namespace rendering the default floor, not a special context
   block.** Its input is an ordinary namespace value plus distance and budget.
4. **Use graph edges as membership.** Own namespace, requires, schema refs,
   callers/problems, and explicit routed refs determine the walk. No
   `home-requires`-shaped hand list should double as prompt policy.
5. **Budget before acquisition.** Distance and remaining budget choose rows and
   detail before large sources/results cross the database boundary.
6. **Distinguish omission from failure.** A clean derived query may omit;
   missing required input, renderer ambiguity, acquisition failure, and invalid
   output are error values that remain visible.
7. **Derive churn.** Static scaffold and transcript bands are semantic. Within
   the middle, order from relevant fact/change time or captured content history,
   with a stable name tie-break.
8. **Capture the composition.** For each sent prompt retain contribution
   identity, selected renderer/provenance, distance, content hash, token
   estimate, position, and database value. This is enough to explain cache
   misses and reproduce wrong-context failures without another context store.
9. **Do not port `my.plan` by inertia.** Decide agent modes and settlement
   first. Only then decide which durable intent facts the namespace renderer
   needs.
10. **Treat the old namespace renderer as evidence, not source to move.** Its
    value is the detail gradient, dependency-first traversal, indexed facts,
    and targeted drill. Reimplement that simpler against the fresh code graph.

The shortest success test is the owner's stated one: a fresh agent should
understand that its context is data in and data out, and change what it sees by
writing one ordinary renderer function. If the design requires editing a
manifest block vector, choosing a magic priority, or migrating every existing
agent's copied renderer symbol, the old system has been ported rather than
understood.
