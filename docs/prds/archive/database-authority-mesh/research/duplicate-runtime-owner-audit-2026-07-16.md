---
title: Duplicate runtime owner audit
type: research
status: completed
tags: [research, prd, database, agent, cljs, web]
---

# Duplicate runtime owner audit

## Result

The remaining high-impact duplication is not named `v1`, `v2`, or `v3`.
Those matches are predominantly wire or stored-format tags. The dangerous
versions are two simultaneous behavioral contracts:

1. `seon.db` now exposes asynchronous ordinary database values, native
   transaction reports, eager read results, and direct `:seon.error/message`
   values, while production code and tests still implement the deleted
   connection-and-envelope API;
2. the execution child now owns prompt, page, and eval execution, while the pod
   still contains the old SCI canvas/page path and process-global eval
   fixtures; and
3. the maintained CLJS test corpus still constructs a second embedded
   Datahike system that production no longer has.

These are deletion and consumer-migration problems. Adding `remote`, `compat`,
`v2`, or fallback adapters would preserve both systems and make the recovery
harder. The shortest route is to settle one result contract, migrate complete
behaviors to one captured database value, prove the child entrypoints, and
delete the obsolete implementation and its implementation-specific tests in
the same ordered cuts.

This audit updates [[single-owner-duplication-audit-2026-07-16]] after the
artifact and allocator unification commits. The artifact reader is now one
current-format reader, and generated identity allocation has one CLJS
implementation. Neither remains a duplicate owner.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Recovery program | `docs/prds/database-authority-mesh/roadmap.md` and [[system-recovery-graduation-plan-2026-07-16]] | One implementation per behavior; correctness and deletion precede tuning. |
| Database facade | `src/seon/db.cljs` | One asynchronous session, ordinary database maps, native transaction reports, eager reads, and direct error values. |
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `transact!` accepts Datahike transaction arguments and returns the transaction report produced by the writer; the report shape is `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and optional `:tx-meta`. |
| Execution child | `src/seon/execution.cljs` and `src/seon/execution/runtime.cljs` | One retained compiler per isolated child; compiled prompt, page, and eval functions are the process boundary. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | A supervised child is an isolation and lifecycle boundary, not another database owner. |
| Renderer | `src/seon/render.cljs`, `src/seon/render/sci.cljs`, and `src/seon/web/datastar.cljs` | The guarded recursive formatter may remain pure; database acquisition and authored function execution belong in the child. |
| Test surfaces | `bin/test-cljs`, `bin/test-writer`, and `script/seon/dev/changed_test.clj` | Tests must exercise the retained owner, not recreate a deleted production architecture. |

The Datahike checkout is three local commits beyond the roadmap's recorded
`670cd1ad` base. Those commits extend query caching and input evidence; they do
not alter the transaction-report conclusion. The direct evidence is
`reference-code/datahike/src/datahike/api/impl.cljc:30-42` and
`reference-code/datahike/src/datahike/api/types.cljc:82-96`.

## Method and current census

The audit searched maintained `src/`, `test/`, `script/`, `config/`, and
`shadow-cljs.edn` for numbered names and for `legacy`, `compat`, `remote`,
`local`, `fallback`, connection construction, result envelopes, eval/compiler
entrypoints, renderer entrypoints, routes, process launchers, and test runners.
It then followed definitions to production callers and direct tests. A lexical
`new-value`, an external `/v1` endpoint, or a single serialized version field
is not counted as a duplicate owner.

At the audited shared-worktree snapshot:

- 19 production CLJS files contain 44 `db/*conn*` references even though the
  public `seon.db` namespace has no such var;
- 28 production files contain 108 `:seon.db/ok?` references, while the public
  transaction function returns a native report or direct error value;
- 50 CLJS test files contain 331 `db/*conn*` references;
- 34 CLJS test files require or call `datahike.api` directly; and
- 33 CLJS test files call the deleted `seon.client/open-agent-conn!` helper.

The counts include comments where the comments explicitly document and teach
the removed contract. They are therefore useful deletion-scope evidence, not a
claim that every match is an executable call.

## Ranked deletion and unification candidates

### 1. Native database results versus the removed success envelope

**Canonical owner:** `seon.db`.

`submit-transaction!` returns either a direct error value or a Datahike-shaped
report and updates the session's cached database from `:db-after`
(`src/seon/db.cljs:521-550`). `transact!` publishes that report contract
(`src/seon/db.cljs:552-590`). Reads likewise return the eager result directly
or a direct error value (`src/seon/db.cljs:621-749`). This follows Datahike's
own transaction API and report shape rather than inventing a second wrapper.

**Superseded contract:** production consumers still branch on
`:seon.db/ok?` and unwrap `:seon.db/error`. Representative owners include:

- eval receipt and fallback transaction logic in
  `src/seon/eval.cljs:3240-3460`;
- agent admission and lifecycle envelopes in `src/seon/agent.cljs:425-431`,
  `src/seon/agent/internal.cljs:18-66`, and
  `src/seon/agent/lifecycle.cljs:48-78`;
- state and restore collision parsing in `src/seon/state.cljs:445-453` and
  `src/seon/db/restore.cljc:318-419`; and
- toolkit consumers in `src/my/plan.cljs:507-516` and
  `src/my/plan/internal.cljs:104-107`.

Generated identity allocation already moved toward the canonical result:
`allocate-attempt!` recognizes a direct error value and otherwise validates
the returned entity IDs (`src/seon/db/id.cljc:1330-1365`). That is the model
for the rest of the cut; do not restore an envelope around `seon.db/transact!`.

**Deletion boundary:** define one small predicate for the existing
`:seon.error/message` value only where a behavior needs to branch. On success,
consume the native report. On failure, propagate the direct error unchanged.
Delete obsolete `:seon.db/ok?` schemas, comments, destructuring, and tests with
their last consumer. Domain functions may retain their own namespaced result
maps when those maps describe domain outcomes; they must not impersonate the
database transport result.

**Shortest falsifier:** make one valid transaction and one deliberate
constraint failure through `seon.db/transact!`. The first must have
`:db-before`, `:db-after`, `:tx-data`, and `:tempids` with no
`:seon.db/ok?`; the second must have `:seon.error/message` with no nested
`:seon.db/error`. Then run the generated-ID collision proof and one eval receipt
proof against those exact values.

**Impact:** highest. Until this split closes, every write behavior can silently
treat a successful report as failure or a direct error as success. It also
encourages adapter code at every call site.

### 2. Ordinary database values versus ambient local connections

**Canonical owner:** `seon.db` captures and caches the current ordinary
database value for one multiplexed authority session. Public query, pull,
entity, schema, temporal, and listener operations are asynchronous and accept
an explicit `:seon.db/db` where a stable value matters
(`src/seon/db.cljs:486-514`, `src/seon/db.cljs:595-779`, and
`src/seon/db.cljs:871-911`).

**Superseded contract:** production files still dereference `db/*conn*` or call
removed helpers such as `db/head-coordinate`, `db/at-coordinate`, and
`db/entity-lazy`. Examples on active paths include:

- agent control and derived state in `src/seon/agent.cljs:344-347`,
  `src/seon/agent/run.cljs:230-235`, and `src/seon/derive.cljs:458`;
- eval operation capture in `src/seon/eval.cljs:4535-4556`;
- render fallbacks in `src/seon/render.cljs:657-672` and
  `src/seon/render.cljs:1108-1115`;
- autocomplete historical export in
  `src/seon/repl/autocomplete.cljs:536-601`; and
- web and feed paths in `src/seon/web/serve.cljs:804-818` and
  `src/seon/web/datastar.cljs:1590-1610`.

This is not a second usable facade. It is source-reachable old behavior whose
owner has already been deleted. Recreating `*conn*` would restore local
Datahike indexes, connection mutation, synchronous lazy entities, and a second
database lifetime to every Bun process.

**Deletion boundary:** migrate one complete behavior at a time. Acquire one
database value at the outer async boundary, group independent reads with the
existing `execute-many`, thread ordinary rows into pure derivations, and pass
the same value to every dependent read. Delete ambient fallbacks and obsolete
arities in that same behavior cut. Temporal behavior uses `as-of`, `since`, or
the existing transaction-resolution operation over database maps; it does not
rehydrate a client connection.

**Shortest falsifier:** compile the maintained client and require that
production `src/**/*.cljs` has zero references to `db/*conn*`,
`open-agent-conn!`, `db/head-coordinate`, `db/at-coordinate`, and
`db/entity-lazy`. Then run one captured-value behavior while a later
transaction commits and prove all of its reads use the original `:t`.

**Impact:** highest architectural simplification. Closing it removes the
largest remaining path back to per-child indexes and turns the authority mesh
from a partial adapter into the only database system.

### 3. Child-owned authored execution versus the pod SCI renderer

**Canonical owner:** the execution child acquires prompt data and renders
selected prompt functions (`src/seon/execution/runtime.cljs:188-225`), acquires
one page projection and resolves its surfaces
(`src/seon/execution/runtime.cljs:318-380`), and evaluates authored forms in
its retained compiler (`src/seon/execution/runtime.cljs:383-417`). Datastar's
page render calls that child entrypoint through
`execution.host/invoke-compiled!`
(`src/seon/web/datastar.cljs:1588-1602`).

**Duplicate owner:** `seon.render/render-agent-canvas` still pulls local
database state and invokes authored canvas functions through SCI
(`src/seon/render.cljs:657-750`). The generic renderer also chooses SCI for
agent-authored symbols (`src/seon/render.cljs:970-1009`). Its direct integration
tests construct a local connection and call the old entrypoint
(`test/seon/render/canvas_test.cljs:369-430`), including an SCI-specific error
loop contract (`test/seon/render/canvas_test.cljs:591-625`).

The pure recursive `seon.render/render` formatter is not the duplicate. The
duplicate is database acquisition plus authored function execution in the pod.
Those responsibilities now belong to the child, where a runaway function can
be terminated with the process instead of sharing the web host's event loop.

**Deletion boundary:** make root, agent, canvas, and selected block pages enter
only through `render-agent-view!`; make prompt creation enter only through
`render-prompt!`; keep pure formatting over already acquired ordinary data;
then delete `render-agent-canvas`, authored-symbol SCI invocation, SCI recovery
state, and implementation-specific tests together. Do not introduce a
`render-v2` namespace or retain SCI as fallback.

**Shortest falsifier:** instrument the old entrypoints to fail and prove root,
agent, canvas, and prompt paths still work through one child. Kill a child
during a runaway authored render, prove the web host remains responsive, and
prove the next render reconstructs the child from the current program.

**Impact:** high CPU, resilience, and cognitive payoff. This removes a second
interpreter, a second timeout model, and the single-threaded pod failure mode.

### 4. Production authority tests versus embedded-Datahike tests

The test corpus is currently a stronger duplicate owner than production.
Thirty-three CLJS test files call a deleted helper to create a local connection,
34 require or call `datahike.api`, and 50 mutate or bind `db/*conn*`. For
example, `test/seon/render/canvas_test.cljs:369-377` installs a root connection
around each Promise, and `test/my/kb_test.cljs:60-80` explicitly re-pins the
global after each async boundary.

These tests preserve local connection identity, synchronous entity traversal,
and mutable-root behavior that cannot exist in the target system. Restoring a
test-only database facade would make obsolete semantics look maintained.

**Deletion boundary:** retain pure transformation tests with ordinary data;
move Datahike engine semantics to focused writer tests; run public CLJS
database behavior through the existing controlled authority session; and
delete assertions whose only subject is local connection identity, lazy
entities, or removed wrapper shapes.

**Shortest falsifier:** the focused CLJS selection must compile with no
`datahike.api` import and no local connection fixture, while the paired writer
test proves the transaction or query invariant inside Datahike.

**Impact:** necessary for honest graduation. A green suite built on the old
system would not prove the new runtime.

### 5. Agent-scoped call route versus flat compatibility route

`src/seon/web/router.cljs:267-327` retains both the canonical
`/agent/{id}/call` route and a flat `/call` route explicitly labeled
back-compat. Both dispatch the same capability handler, but they expose two
public address contracts and preserve caller ambiguity.

**Deletion boundary:** prove generated client markup, Datastar calls, and
downstream consumers use the agent-scoped route, then delete the flat route and
its route assertions. No redirect or compatibility handler is needed on this
feature branch.

**Shortest falsifier:** search generated hiccup and maintained consumers for a
literal flat `/call`, remove the route, and prove agent-scoped calls still pass
same-origin and capability checks.

**Impact:** medium-low. It simplifies the public interface but does not block
database recovery.

### 6. Managed changed-test execution versus one-shot fallback

`script/seon/dev/changed_test.clj:584-597` runs the selected compiled test
artifact directly, while `run-pod-fallback!` invokes the full `bin/test-cljs`
gate when no current manifest becomes available
(`script/seon/dev/changed_test.clj:614-620`, selected at lines 690-717). The
fallback is fail-safe, but it is a second build-and-run path with different
selection, timing, and failure evidence.

**Deletion boundary:** after the sole watcher reliably publishes one current
manifest or an explicit build-unavailable result, remove the one-shot fallback
from changed-test. Keep `bin/test-cljs` as the intentional full checkpoint, not
an implicit alternate selected-test implementation.

**Shortest falsifier:** with the watcher absent or its build broken, changed
test must return one explicit `:build-unavailable` result and must not start a
second compiler.

**Impact:** lower and ordered after application recovery. It prevents surprise
CPU spikes during edits but does not justify interrupting the runtime spine.

### 7. Downstream worker eval versus the core execution child

`src/seon/worker_eval.cljs` and the `:worker-oracle-eval` Shadow build remain a
separate stateful `cljs.js` service. Python consumers in `src-diffusion/` and
`src-inspect-ai/` launch it directly. This is real compiler/eval duplication,
but it is not a production pod fallback and is outside the current startup
critical path.

**Deletion boundary:** either route the supported oracle operation through the
canonical execution child protocol or move the specialized worker entirely to
its downstream repository. Retain shared pure repair-candidate functions in
their current owner. Do not merge this work into the database recovery cut.

**Shortest falsifier:** enumerate maintained gates that launch
`out/worker-oracle-eval/main.js`. If none is a core Seon gate, moving the build
and source downstream must leave all root gates green.

**Impact:** lower for runtime recovery, meaningful later for package and
maintenance simplification.

## Dependency order

1. Finish the direct native transaction-result contract, beginning with the
   allocator and one eval receipt. This prevents every later consumer cut from
   rebuilding the old wrapper.
2. Complete turn, eval, and lifecycle migration to one captured ordinary
   database value. These are prerequisites for honest child execution.
3. Prove prompt, page, canvas, and eval behavior through the child, then delete
   pod SCI execution and old canvas/page entrypoints.
4. Convert each behavior's tests with the behavior. Do not defer a large
   mechanical test rewrite or restore local fixtures temporarily.
5. Close the Datastar feed's stale coordinate calls against the same database
   value and child render contract; retain the one feed registry.
6. Delete the flat call route, changed-test fallback, and downstream worker
   duplication only after the running application and full gates are green.
7. Run the frozen-source fresh start, converged restart, browser/feed,
   child-crash, multi-agent, multi-database, and modest-hardware proof; only
   then measure optimization candidates.

## Explicit non-issues

- `script/seon/dev/artifact.clj` has one `current-version` and one manifest
  schema. `test/seon/dev/artifact_test.clj:486-525` deliberately proves older
  versions are rejected and require rebuild. That negative loop is not an
  upgrade implementation.
- `seon.db.protocol/current-version` and
  `seon.execution/protocol-version` are single wire constants. A stale test
  expecting protocol version 8 while production is 10 is an obsolete
  assertion, not grounds for a version dispatcher.
- `:seon.dev.restore.canonical/v1`, restore intent version 1, browser cursor
  version 1, and autocomplete export `/v1` are stored-format tags. They need an
  explicit data-format decision, not a mechanical rename.
- External provider URLs such as OpenAI `/v1`, Runpod `/v2`, and Gemini
  `v1beta` describe third-party contracts. `openai-compat` is the name of one
  provider category, not a prior Seon generation.
- `new-intent`, `new-view-id`, `new-projection`, and similar names describe new
  values in one transition. They do not select implementations.
- Babashka process supervision and `Bun.spawn` are intentionally nested:
  Babashka owns the outer application processes; Bun owns agent children.
  Agent database lifecycle and OS process lifecycle are likewise different
  contracts.
- The one Datastar registry's remaining `head-coordinate` call is stale, but
  there is not a second feed registry. Fix the existing registry in place.
- The pod `seon.repl/!compile-state` currently has only development and test
  callers. It is a deliberate host REPL surface, not evidence that production
  agents still eval in the pod. Keep it out of runtime admission, and reconsider
  it only if the development surface itself should move to a child.
- Legacy database-layout refusal and legacy process retirement protect
  discoverable on-disk or live-process evidence. They are not normal startup
  owners. Delete them only after an operator checkpoint proves no retained
  legacy state remains; do not let them interrupt application recovery.

## Graduation condition

One frozen source digest must prove that no production file names a removed
database helper or old transaction envelope; every agent prompt, page, canvas,
and eval enters through the supervised child; the pod owns no local Datahike
connection or authored renderer; CLJS tests exercise the authority contract;
and the full live recovery matrix passes without a compatibility route,
fallback runtime, or numbered replacement implementation.
