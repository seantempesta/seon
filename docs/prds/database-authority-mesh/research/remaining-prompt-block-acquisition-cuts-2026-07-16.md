---
type: research
status: complete
tags: [research, database, flow, agent]
---

# Remaining prompt block acquisition cuts — 2026-07-16

## Result

The remaining prompt blocks do not require a global block registry, a fixed
prompt catalog, or a second database interface. Each selected symbol-backed
block keeps its current namespace as owner, acquires the smallest ordinary
data it needs through asynchronous `seon.db` functions at the prompt's
inherited coordinate, and hands that data to its existing formatting logic.

The most important cut is to stop carrying a Datahike database value through
the formatting functions. The replacement is not one huge prompt query. It is
a small number of block-owned acquisitions with dependent reads only where an
earlier result determines the next input. Independent members in one owner may
share `seon.db/execute-many`; unrelated owners do not gain a shared registry.

This audit covers canvas, function-menu and typeahead instructions, subagents
and orphaned agents, warnings and the two root diagnostics, `my.plan`, and
stored or derived authored prompt functions. Namespace and transcript
acquisition are owned by their separate audits.

## Dependency ledger

| Owner | Selected source | Constraint used |
|---|---|---|
| Seon | `65d8c62c`, with prompt entrypoint work visible in `src/seon/execution/runtime.cljs` | `seon.db` remains the application API; the child inherits one coordinate and returns ordinary data. |
| Datahike | `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | Query maps support `:order-by`, `:offset`, and `:limit`; `pull-many` parses one selector and returns input-aligned eager values, including nil for missing well-formed refs. |
| Seon database protocol | current protocol version 6 in `src/seon/db/protocol.cljc` | `execute-many` accepts 1–64 independent query, pull, pull-many, schema, or index-page members at one coordinate, with a 4 MiB aggregate result bound. |
| ClojureScript | `946d75f3483c0c8e784e6668bff2c71a25619a77` | `^:async` plus `await` is the honest child-side composition seam; synchronous formatting continues over ordinary values. |

First-party owners read for this audit:

- `src/seon/agent/ctx.cljs:2260-2776`;
- `src/seon/agent/ctx/canvas.cljs` and `src/seon/render/canvas.cljs:397-477`;
- `src/seon/agent/ctx/render_fns.cljs:92-430`;
- `src/seon/agent/ctx/menu.cljs`;
- `src/seon/agent/ctx/typeahead_steps.cljs:50-175`;
- `src/seon/agent/ctx/subagents.cljs` and `src/seon/derive.cljs`;
- `src/seon/agent/ctx/warnings.cljs`, `src/seon/warn.cljs`, and
  `src/seon/instrument.cljc:914-1038`; and
- `src/my/plan/internal.cljs:800-1360`.

Dependency-native evidence is in
`reference-code/datahike/src/datahike/query.cljc:96-116` and
`reference-code/datahike/src/datahike/pull_api.cljc:369-383`. Datahike already
owns bounded ordered query execution and eager ordered pull-many. Seon should
use those operations rather than reproduce entity traversal in Bun.

## Common cut

The compiled prompt entrypoint first discovers the agent entity, stored
blocks, profile, current namespace, and cache breakpoint. That result decides
which owners run. An owner is never called merely because its function is in a
shipped seed.

Every owner follows the same existing names:

1. An `^:async` acquisition function accepts the agent id and ordinary block
   input. It does not accept a Datahike value.
2. Its `seon.db` calls inherit the surrounding `db/with-tx-context`
   coordinate. It does not resolve head again.
3. Independent queries or pulls in that owner use one `execute-many` request.
   A result-derived follow-up remains an ordinary second async request.
4. The owner returns namespaced ordinary data or an existing `:seon/error`
   value.
5. The existing renderer becomes a pure function of that acquired data.

The prompt coordinator may start different selected owners concurrently. It
must not merge their request descriptions into a symbol-to-query registry.
Datahike already shares exact identical query computation at the same
coordinate, so duplicated identical reads are a measurement problem, not a
reason to centralize ownership preemptively.

## Canvas

### Smallest acquisition

Canvas resolution should branch from data already present in the discovery
pull:

- explicit `:seon.render.canvas/content` on the agent wins;
- the configured value on the stored `:canvas` block is next;
- only an unpinned canvas needs last-updated-surface discovery; and
- only an agent-authored selected symbol needs its source for the awareness
  text and the authored program loader.

`seon.render.canvas/wired-content` is already the pure resolution function and
should remain so. `canvas-state` becomes unnecessary in prompt execution once
the discovery entity carries the same exact fields.

The unpinned derived case has two bounded database phases:

1. One query returns this agent's REPL-authored public function rows with
   symbol, spec, source transaction, privacy, and declared read attrs. Local
   `output-twin-keys` keeps only Hiccup renderers and unions their declared
   attrs.
2. When that union is non-empty, one history query returns the maximum
   agent/REPL transaction per watched attr. Local `last-updated-surface`
   selection keeps the current source-transaction fallback and symbol
   tie-break.

This preserves the current derive-don't-store rule without N queries per
renderer. The existing implementation already performs the attr history scan
as one aggregate; the remote cut retains that good seam and replaces its
per-symbol `fn-row` pulls with the first relation.

After selecting the wired value, a literal Hiccup value renders locally. A
compiled symbol calls the compiled function. An authored symbol uses the one
authored program loader and runs in the same child. The function's own
asynchronous `seon.db` calls remain open-ended and coordinate-pinned; trying to
pre-plan them would recreate a query language.

### Pure tail and deletions

Keep `wired-content`, `wired-label`, token clipping, error-to-text formatting,
and the final canvas prose. Split `canvas-block` immediately before
`render/render-agent-canvas`: the async head resolves and invokes; the pure
tail formats `{hiccup, ai, error, wired, source}`.

Delete prompt-path calls to `canvas-state`, `render-agent-canvas` with a local
db value, `wired-fn-source`, and the prompt-side `@db/*conn*` fallbacks. The
human HTML owner may use the same async canvas result later; it must not be
computed by the prompt when only `:ai` was requested.

## Function menu and typeahead

These optional blocks run only when discovery selected them. The menu's glyph
vector, source parser, alias resolution, ranking, round-robin-per-namespace,
headers, and callable-contract formatting are already pure and stay in
`seon.agent.ctx.menu`.

### Function menu phases

The first owner request can share these independent members:

- pull the optional `[:seon.typeahead/id "policy"]` row;
- read the agent's newest 30 successful eval projections;
- read the cluster's newest 200 successful eval projections; and
- read current-namespace require targets and their public specced function
  rows.

Use Datahike query maps with `:order-by` and `:limit` at the authority. The
current `seval/recent` calls request 200 and then reverse/filter/take 30, while
`recent-all` requests 200; the remote relation should return those exact
bounded windows rather than materialize unbounded history.

Eval source parsing determines called symbols and the distinct eval
namespaces. A second request therefore reads:

- persisted require edges for the union of those namespaces; and
- candidate public function rows for the resolved fully qualified symbols not
  already present in the toolkit projection.

The second phase is a real dependency: aliases cannot be resolved until the
eval namespace edges are known. It can still be one owner request. Do not send
one pull per called symbol. A query with a collection input returns all named
function rows, or `pull-many` reads their lookup refs with one parsed selector.

The menu currently performs one entity lookup per called function and one
pull per public function in every toolkit namespace. Those are the N+1 paths
to delete. `ns-public-specced-fns` becomes a relation over all selected
namespace names, and `public-fn-row` becomes a lookup into the acquired map.

### Typeahead prompt instructions

`steps-ai` needs only the resolved provider. Its acquisition is one query or
pull of the agent provider override plus global provider config, followed by
the existing pure precedence logic. If the function menu is also selected,
the provider/config facts and policy row may share the menu owner's first
`execute-many` because both are owned by the same optional typeahead family.

`steps-surface-html` and its step rows are human-only and must not be acquired
by the compiled prompt. This removes the largest optional typeahead projection
from every agent turn while preserving it for the web UI.

## Subagents and orphaned agents

The current subagent renderer first queries child IDs, then performs repeated
agent entity, state, current-run, turn-count, latest-closed-run, and crash-count
reads per displayed child. The output is capped at 20, but the database work
is still proportional to helpers times children.

### Direct children

Use two bounded phases:

1. One query map returns direct child id and purpose in stable id order with
   limit 21. Twenty rows render; the extra row proves that the existing hidden
   footer is needed without counting the whole child set.
2. One `execute-many` for the at-most-20 ids returns independent relation
   projections for:
   - termination, open-run id/status/pause/turn-limit/last beat;
   - work-turn counts grouped by run id;
   - closed-run summaries for those agents, ordered newest first so local code
     keeps one per child; and
   - crashed-run counts inside the one supplied breaker cutoff.

The second phase uses collection inputs and grouped queries, not one member per
child. The current `derive/state-from-primitives` remains the sole state rule;
it receives acquired primitives. Existing age, result, breaker, clipping, and
line formatting remain pure.

### Orphaned agents

One query returns live child id, purpose, parent id, child termination fact,
and current open-run pause/status facts for every child whose parent is
terminated. That relation contains everything `orphaned-agents-block` needs;
`derive-state` no longer re-reads each row. Apply an explicit result bound and
render a loud overflow footer if the root fleet exceeds it.

When both functions are selected in one prompt, their relationship queries
may share one `execute-many` because `seon.agent.ctx.subagents` owns both. They
remain separate members and separate pure outputs.

## Warnings, core faults, and instrumentation gaps

`seon.agent.ctx.warnings` owns all three selected root/agent diagnostics, so it
may acquire their shared data once. This is a legitimate owner-local sharing
cut, not a prompt-wide registry.

### Warning acquisition

The first request contains:

- the latest user-message instant, once;
- the scoped program-function relation used by all six corpus checks;
- installed schema;
- persisted canonical schema/catalog rows needed by domain checks; and
- core-fault rows plus their frame-zero rows.

After the cutoff is known, one second `execute-many` contains the independent
runtime relations for failed evals, record errors, filesystem denials,
hop-exhausted messages, slow evals, failing tests, and canvas pins. Each query
uses the cutoff as an input rather than every check re-running
`latest-user-at`. The same cutoff filters core faults locally.

Parallel-attribute counts must not issue one query per candidate attribute.
After phase one derives candidate attrs, one grouped query with a collection
input returns `[attr count]` for all candidates. Canvas-unresolved remains a
local lookup against functions actually loaded in this child after the
canvas/authored program phase; no database read is needed after the pin
relation arrives.

The sixteen check functions should become pure transformations over a shared
namespaced acquired map. Keep `checks`, per-check failure isolation, urgency,
dev-only filtering, and cluster rendering. Delete their direct `db/query`,
`db/entity`, `db/installed-schema`, and `latest-user-at` calls.

Two live-registry dependencies require deliberate correction:

- `check-unmarked-entity-kinds` reads `schema/entity-catalog` and
  `schema/registered-schemas` from process memory. The database already owns
  canonical schema facts, so the remote implementation must derive the same
  catalog projection from acquired database rows. Loading a second mutable
  schema authority into every child would violate the selected boundary.
- `instrumentation-gaps-block` is not a database-only check. It compares the
  persisted function/spec census with live JS vars and Malli wrappers. The
  compiled child should reuse the corpus relation, then run
  `instrument/coverage-gaps` over that child's live artifact and authored vars.
  A child that does not ship a function must preserve the existing `:no-var`
  exclusion. This tests the process that will execute the prompt; it must not
  claim to census a removed Node pod.

The instrumentation check must run after compiled instrumentation and any
authored program loading required by selected slots. Otherwise first-call
authored functions appear falsely absent. Its local result can share the
warning formatting but not the database request.

## `my.plan`

The plan block should preserve Datalog as the definition of ready, blocked,
unfinished, and descendant relationships. Moving those rules into Bun loops
would create a parallel plan engine. The optimization is to return bounded
relations, not to reimplement graph semantics.

### Normal path

The first plan-owned `execute-many` uses the agent lookup ref directly and
contains:

- active steps with id, title, expectation, creation time, and the active
  assertion transaction;
- ready steps via `my.plan.internal/rules`, ordered oldest first and limited to
  `frontier-limit + 1`;
- recent completed steps ordered by completed time descending and limited to
  `recent-done-limit`; and
- the one agent id/eid projection needed by later joins.

This replaces `open-steps`, which currently pulls every unfinished entity and
then calls `ready?` per row. A 1,000-step plan should perform bounded authority
work and return at most the visible frontier plus one overflow witness.

After local selection of active step or oldest ready step, one dependent
`execute-many` can run these independent members using the selected step id:

- an ancestor relation or recursive parent pull for the ordered root-to-step
  chain; and
- one Datalog rollup that finds the selected step's root through the existing
  descendant rules and returns done/total leaf counts.

The root need not be pulled first: both queries can start from the selected
step and traverse to the root inside Datahike. This keeps the normal plan path
at two frames without duplicating ancestor semantics in the child.

### Escalation path

Only an active step can escalate. When phase one returned one, phase two also
queries that agent's eval projections after the active assertion transaction.
The existing pure `wedge` parser derives the episode, failure root, and latest
error locally.

Only when a live wedge exists does a third request read frontier-provider
candidates, authorship of the flagged step, and whether a message contains the
exact existing `consult-marker`. This dependency is honest: the marker cannot
exist until `wedge` produces the episode. Most plan renders pay no third
request. Do not read all worker messages to avoid that dependency.

Keep `state-from-primitives`, the Datalog `rules`, `wedge`, anchor/frontier/
done/escalation text, timestamps, constant limits, and empty-plan teaching.
Delete prompt-path entity walks in `agent-eid`, `active-steps`,
`ancestor-chain`, `open-steps`, `recent-done`, `escalation`, `planner-for`, and
`consult-sent?` after their acquisition equivalents own those reads.
Agent-facing plan functions are a separate migration and continue to use the
same `seon.db` names; this prompt cut does not mechanically rewrite them.

## Authored and custom prompt slots

There are three data-driven cases, not a catalog:

- an agent-level `:seon.render/ai` symbol replacing the whole prompt;
- a stored block whose `:seon.render/ai` value is a symbol; and
- a derived current-namespace render function discovered by the namespace
  owner.

Compiled shipped symbols resolve from the execution artifact. Authored symbols
trigger the existing authored-program acquisition exactly once on first use in
a fresh child, at the same coordinate and checked by source digest. Later
authored slots reuse that compiler state. Slots execute sequentially because
they share authored globals and may have effects; different agent children run
in parallel.

An authored function receives the ordinary render input and can call any
asynchronous `seon.db` function. Those calls inherit the coordinate and are
captured by the existing async operation-capture owner. No block acquisition
planner attempts to inspect or predict them.

The function result is reduced to the existing AI string or render response,
then passed through the same per-block cap and bracket logic. A timeout,
missing symbol, compile failure, or returned wrong shape becomes the existing
block-local error. One bad authored slot must not erase literal or compiled
siblings.

Once this path works on the first invocation in a fresh child, delete the SCI
prompt invocation and local Datahike render input together. Process deadline
and child retirement become the runaway-loop boundary; retaining SCI would be
a second execution mechanism.

## Request sharing without coupling

The allowed grouping is narrow:

| Owner | Members that may share one request |
|---|---|
| Canvas | Candidate function rows and, after discovery, one grouped history-touch query. |
| Menu/typeahead | Policy, provider facts, bounded eval windows, require edges, and candidate function rows. |
| Subagents | Direct-child primitives, run counts/outcomes, crash counts; orphan relation when selected. |
| Warnings | Shared cutoff, corpus/schema rows, runtime-check relations, core faults; instrumentation remains local. |
| Plan | Active/ready/recent-done relations; then ancestor/rollup/evals for the selected active step. |

Different rows in this table start concurrently from the prompt coordinator.
They do not share a request-description map, result registry, symbol switch, or
fixed member position across owners. If measurement later shows framing is a
material fraction of prompt latency, add a small composition function that
concatenates already-built member vectors and returns slices to their owners;
do not move query definitions out of those owners.

## Resource bounds

- Keep every owner below the protocol's 64-member limit; the designs above are
  well under it even before combining owner-local members.
- Use query-map ordering and limiting at the JVM for visible windows: 21 child
  ids, 30 agent evals, 200 cluster evals, 8 ready plan rows, and 5 completed
  plan rows under current constants.
- Set per-query `max-results` to the visible bound plus deliberate join
  expansion, not the facade ceiling of 50,000. A bound failure is a block-local
  error, never a partial relation silently formatted as complete.
- Set pull/pull-many result weight for the named projection; never use `[*]`
  for menu, subagent, or plan cohorts.
- Keep the outer `execute-many` weight below the protocol's 4 MiB frame. Large
  authored source travels only when an authored slot is selected and through
  the existing authored loader, not every prompt.
- Carry one `now` into subagent breaker/age and warning slow-eval filters so all
  rows in one prompt use the same display instant.
- Record member count, encoded request/result bytes, queue/run time, cache and
  single-flight evidence, child CPU/RSS, and cold/warm end-to-end prompt time.

## Dependency order

1. Freeze the compiled prompt root and ordinary literal formatting contract.
2. Integrate namespace and transcript acquisition from their owning audits.
3. Add the default canvas and plan acquisitions; prove the ordinary seeded
   prompt at one coordinate.
4. Add warning/core-fault/instrumentation and orphan acquisition for root.
5. Add optional function-menu/typeahead and dormant subagent blocks without
   making them unconditional.
6. Prove authored whole-prompt, stored authored block, and derived authored
   first-call execution.
7. Wire turn and debug to the one child result, then atomically remove the
   local replica, SCI prompt path, and Node prompt owner.

## Parity and performance falsifiers

1. At one coordinate, default and root prompt block names, priorities, text,
   token estimates, brackets, and cache boundary are byte-identical within the
   existing windows.
2. An explicit canvas pin skips last-updated-surface reads. An unpinned canvas
   chooses the same authored renderer and touch ordering as current code.
3. Twenty and 200 direct children perform a constant number of database
   requests; only 20 detail rows render and overflow remains truthful.
4. A 1,000-step plan returns at most the configured frontier/recent windows,
   chooses the same anchor and rollup, and issues no entity-per-step pulls.
5. Clean warnings render empty. Every existing warning fixture produces the
   same cluster/order, while `latest-user-at` executes once and parallel-attr
   counting is one grouped query.
6. Instrumentation gaps classify live wrapped, live unwrapped, async, and dead
   vars exactly as the current focused test, against the Bun child artifact.
7. Function-menu glyph order and `function-offers` are identical from the same
   acquired rows; a cluster with long eval history returns only the two bounded
   windows.
8. Non-typeahead agents perform no step-row read. Installing the optional
   typeahead block changes only its selected prompt instructions.
9. A never-before-loaded authored custom block and whole-prompt override both
   work on the first prompt in a fresh child. A runaway authored function
   retires only that child.
10. Every nested read records the inherited coordinate; no child resolves head
    or receives a Datahike value, entity, Datom, history wrapper, or lazy
    sequence.
11. Compare request count, exact wire bytes, JVM query work, Bun CPU/RSS, and
    cold/warm latency against the current local render. Reject any acquisition
    that lowers frames by returning materially more rows or retaining a second
    registry.

## Final judgment

This cut is smaller than a codebase-wide async rewrite. It changes the prompt
composition root and the database-reading heads of the selected block owners.
Their formatting, names, schemas, tests, and agent-facing `seon.db` vocabulary
stay recognizable. Datahike performs graph selection, aggregation, ordered
windows, and pull-many once; Bun performs pure parsing, ranking, formatting,
and isolated authored execution. That is the closest seam that removes the
replica and N+1 traversal without replacing either Datahike or Clojure with a
new abstraction.
