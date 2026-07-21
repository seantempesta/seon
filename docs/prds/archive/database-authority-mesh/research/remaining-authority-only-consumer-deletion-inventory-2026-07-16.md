---
type: research
status: completed
tags: [research, database, agent, pod]
---

# Remaining authority-only consumer and deletion inventory — 2026-07-16

## Result

The remaining work is not one undifferentiated compile-warning cleanup. It is
seven dependency-ordered semantic cuts followed by deletion of tests and code
that describe the removed local Datahike system.

The earliest unsettled application contract is the turn database value. One
ordinary `:seon.db/db` map must select prompt acquisition and child eval while
the turn persists only a native ref to its basis transaction. Provider attempts
derive that transaction through their parent turn and copy no database identity.
Once that is closed, run/lifecycle and web consumers can migrate without
inventing another database representation or compatibility surface.

The highest-risk later contract is the Datastar interest boundary. The new
`seon.db/listen!` returns the public listener key, while the current Datastar
consumer expects listener registration to return a database point. The correct
fix is an explicit current-database/resynchronization contract, not restoration
of the pod replica, publisher, replay cursor, or captured-read mechanism.

The current checkout is `ab98d70f28705386caa82ae7c4eb25b1e74a0299`
plus a shared dirty worktree. `src/seon/execution*`, `src/seon/web/serve.cljs`,
`src/seon/web/reactive/call.cljs`, `src/my/plan*`, `src/seon/test/runner.cljs`,
schema source, tests, the roadmap, and several sibling research reports are
being edited by other owners. This audit does not treat those in-progress diffs
as committed evidence and changed no production source.

## Dependency ledger

| Owner | Selected source | Constraint used here |
|---|---|---|
| Seon database facade | `src/seon/db.cljs`, current worktree | Pod and child reads are asynchronous and return eager ordinary values. There is no connection, lazy entity, `at-coordinate`, or local operation-capture API. |
| Seon protocol | `src/seon/db/protocol.cljc`, current worktree | Database selection is `:seon.db/db`; `execute-many` returns positional member results; `listen` acknowledges `listening?` and events carry `db-before`/`db-after`. |
| Seon execution child | `src/seon/execution{,/host,/runtime}.cljs`, current worktree | One retained Bun child owns authored compilation and invocation. Prompt and agent-view entrypoints already acquire ordinary rows through the authority. |
| Datahike | `reference-code/datahike`, maintained checkout named by the active roadmap | The JVM authority owns immutable database values, indexes, query caching, listeners, and serialized writes. No CLJS consumer needs a Datahike value. |
| Bun | `reference-code/bun`, revision recorded in the active roadmap | Per-agent process isolation, persistent native UDS sessions, and child lifecycle remain the target. |
| Datastar | `reference-code/datastar` plus `src/seon/web/datastar.cljs` | SSE sockets consume complete rendered elements. Database change selection belongs to one authority interest, not a replica replay loop. |
| Babashka process | `babashka.process` in the operator dependency graph | Outer process supervision remains the operator owner; it is independent of application consumer migration and should move only after the runtime is semantically green. |

## Canonical consumer rule

Every surviving CLJS consumer falls into one of three shapes:

1. A boundary function acquires one current `:seon.db/db` map and passes it to
   asynchronous reads.
2. Several independent reads become one bounded `execute-many` request at that
   same database value, followed by pure transformations over ordinary rows.
3. A pure helper accepts already-acquired ordinary rows and performs no I/O.

There is no fourth shape in which a synchronous function dereferences
`db/*conn*`, walks a lazy entity, or silently substitutes a newer database.
Mutations compile transaction data from one acquired projection and submit it
with the existing serialized writer fence when the operation requires one.

## Ordered implementation and deletion graph

### 1. Freeze the ordinary database value at the turn boundary

This is the first dependency because execution, debug, autocomplete, web
evidence, and retry semantics consume it.

**Migrate atomically**

- `src/seon/agent/turn.cljs`: replace `rendered-coordinate`,
  `coordinate-turn-attrs`, `db/*conn*`, `:seon.db/conn`, and the four rendered
  coordinate attributes with one native
  `:seon.agent.turn/rendered-tx` ref equal to the request database value's basis
  transaction. Attempts derive it through `:seon.agent.turn/llm-attempts` and
  store no database field.
- `src/seon/execution/runtime.cljs`: the existing prompt acquisition returns
  the current namespace and REPL mode alongside the rendered prompt. The pod
  performs no second read.
- `src/seon/agent/debug.cljs`, `src/seon/repl/autocomplete.cljs`, and
  `src/seon/web/serve.cljs`: construct an ordinary request database value with
  `as-of` equal to the stored transaction ref. Rare exact branch work resolves
  the originating commit inside the authority.

**Delete in the same cut**

- The eight turn/attempt coordinate columns and reconstruction helpers.
- All `db/at-coordinate`, `entity-lazy`, and connection arguments used only to
  emulate a local Datahike value.
- Generated reproduction text that tells an agent to dereference
  `seon.db/*conn*`.

**Proof**

- One acquired database map is byte-equal across prompt and eval invocation;
  the turn stores only its basis transaction ref.
- An interleaving transaction does not move the captured turn.
- Debug, autocomplete export, and web evidence reconstruct the same historical
  inputs through the stored transaction ref.
- No retired attribute or conversion helper remains reachable.

The exact source and test inventory is already grounded in
[[turn-database-value-observability-cut-2026-07-16]].

### 2. Finish execution and remove the last local eval-read assumptions

The execution-child architecture is real, not a prototype: prompt rendering,
authored-program acquisition, model-reply eval, scheduled eval, and selected
function invocation already cross the supervised child boundary. Remaining
eval references are old local-read assumptions.

**Migrate**

- `src/seon/eval.cljs:4172-4202`: function-name repair currently queries the
  old ambient program graph. Supply the function names from the ordinary
  authored-program projection already acquired for the child, then keep
  candidate ranking pure.
- `src/seon/ai/typeahead.cljs:539-557,987-1008`: acquire author/provenance,
  menu policy, offers, and prefill rows once before the step loop, preferably as
  one bounded group. `node-authors` and the loop then transform ordinary rows.
- `src/seon/diffusion/retrieval.cljs:417-563` and
  `src/seon/diffusion/oracle.cljs`: accept the already-acquired program symbols
  or make the top-level retrieval entry asynchronous. The pure parsing,
  distance, scoring, and injection functions remain synchronous.

**Delete**

- `src/seon/eval.cljs:4533-4562` still calls removed
  `db.internal/capture-operations!` and consumes removed
  `::db/read-observations`. The database authority already records actual
  requests and result evidence; do not rebuild a pod-local read-replay log.
- `src/seon/web/serve.cljs:975-1006` and its two test fixtures still project
  `:seon.db/read-replayable?`. Delete that field and old operation-replay
  evidence.
- The unreachable compiler/program replay implementation described by the
  active roadmap, after its last source/index helpers have moved to the single
  publication owner.

**Keep**

- The child-owned `cljs.js` compile state and ordinary authored-program
  acquisition.
- Forensic turn reconstruction from persisted data and blobs. It does not
  replay arbitrary effects and is a different concept from transaction replay.

**Proof**

- Eval, repair, typeahead, retrieval, and scheduled execution run with no
  `db/*conn*` var and no local Datahike namespace reachable from the release
  execution artifact.
- Accepted authored code still publishes once and becomes visible to every
  child on its next program acquisition; no child re-transacts initial program
  facts.

### 3. Migrate run, lifecycle, schedule, and agent operations

These are necessary semantics, not stale subsystems. They own serialized run
fences, pause/resume/terminate behavior, schedule wake races, and process
hosting.

**Migrate**

- `src/seon/agent/run.cljs`: `current-run`, `open-run!`, `close-run!`,
  `renew!`, overdue/stale closure, and outcome notification currently mix
  synchronous entity/query calls with `db/*conn*`. Each public async operation
  acquires the exact agent/run/config/message facts it needs once, derives the
  transaction purely, and preserves the existing writer CAS.
- `quiescence-work!` currently expects `execute-many` to return
  `::db/coordinate`, although the canonical facade returns only positional
  results. Move it to the ordinary `:seon.db/db` result contract selected for
  grouped acquisitions and remove the old coordinate schema.
- `src/seon/agent/lifecycle.cljs`: acquire the current run, latest test run,
  managed descendants, and agent state asynchronously. Keep complete, pause,
  resume, and terminate as the public lifecycle functions.
- `src/seon/agent/schedule.cljs`: acquire the due schedule, breaker, idle/run,
  and config facts in one database read group per scheduler pass, not three
  ambient reads per agent.
- `src/seon/agent.cljs`: migrate armable-agent discovery, spawn-depth gating,
  and purpose authorization to explicit ordinary rows.
- `src/seon/agent/home.cljs` and `src/seon/derive.cljs`: keep namespace mapping
  and pure derivations; remove ambient connection fallbacks. Pure functions
  take ordinary rows or an explicit database-derived projection.

**Delete**

- `:seon.db/conn` from allocation requests and schemas in CLJS. The current
  `db.id/allocate!` submits through the session-owned `seon.db/transact!`.
- The duplicated CLJS allocator body in `src/seon/db/id.cljc`. The file
  currently defines the CLJS generator policy, candidate round, collision
  loop, and `allocate!` twice (one block ends around line 1450 and another spans
  roughly 1472-1710). Retain one remote allocator and the separate CLJ local
  writer implementation.
- Convenience arities whose only behavior was to dereference `db/*conn*`.

**Proof**

- Two racing wakes still create one open run.
- A stale run cannot transact after losing its CAS fence.
- Pause, resume, terminate, watchdog closure, schedule wake, and quiescence
  complete through the remote facade without a local database.
- Identity collision retry reruns only the pure transaction builder and sends
  no connection value over the wire.

### 4. Complete the toolkit migration

Toolkit functions are application consumers of the same database API. They do
not justify a compatibility layer.

**Migrate**

- `src/my/plan.cljs` and `src/my/plan/internal.cljs`: finish the in-progress
  ordinary-row acquisition and pure plan derivations. Preserve one plan model;
  do not keep the local entity/tree implementation beside it.
- `src/my/blob.cljs`: keep the current authority query for retained hashes and
  ordinary blob operator requests.
- Any remaining `my.*` database function follows the same acquire once,
  transform purely, transact once shape.

**Delete**

- The three residual `db/*conn*` sites in `my.plan.internal` after every public
  caller uses the new row projection.
- Old local-plan traversal helpers and tests once their row-based equivalents
  prove the same public results. Do not retain both trees.

**Proof**

- Plan create/reconcile/status/ready/subtree/forest operations pass through the
  remote facade and preserve their public namespaced data shapes.
- Blob materialization uses the exact requested database value and never
  materializes a local Datahike value.

### 5. Collapse rendering and Datastar onto one authority-backed path

There are two different classes under `seon.render` today and they need
different dispositions.

**Keep and migrate**

- Keep the guarded recursive `seon.render/render` conversion of already
  acquired ordinary nodes. It is still used by context transcript rendering
  and entity converters.
- Keep `seon.execution.runtime/render-agent-view!` as the one compiled child
  page entry. Strengthen its existing `execute-many` acquisition so core view
  data is fetched once and its selected renderers receive ordinary input.
- `seon.render.default/view`, `seon.render.chat/conversation`, and the welcome
  renderer currently make synchronous database calls. Either include their
  required messages/turn/state rows in the one agent-view acquisition or make
  the selected renderer itself asynchronous. For core views, the existing
  architecture decision prefers one grouped acquisition and synchronous pure
  formatting.
- `src/seon/web/reactive/call.cljs`: make the capability gate asynchronous.
  Resolve the owning live agent and matching authored function in one bounded
  acquisition, then invoke the supervised child. The current pure
  `capability-check` calls asynchronous `db/query` as if it returned a
  collection, and `handle!` still supplies `@db/*conn*`.

**Delete**

- `seon.render/render-agent-canvas`: production has no surviving caller;
  `render-agent-view!` owns the page projection and child invocation. Its SCI
  timeout/fallback path is superseded by OS child supervision.
- `seon.render/slot` and `agent-ctx-block`: only tests and comments call this
  local pull-based layout mechanism. Execution runtime materializes independent
  surfaces directly.
- `seon.render.canvas/canvas-state` local pull logic after its pin/config facts
  are acquired by `render-agent-view!`.
- `src/seon/render/sci.cljs` and SCI-canvas tests after source reachability
  confirms no other active owner. A hung authored renderer is terminated with
  its isolated Bun child rather than bounded by a second interpreter.
- Stale comments in `client.cljs`, reactive transform, context canvas, and
  config that name `render-agent-canvas`, local slots, or database replay.

**Datastar semantic migration**

- Preserve one normalized feed/subscription registry and one database
  interest. Equivalent browsers continue sharing one render and serialized SSE
  event.
- `seon.db/listen!` currently returns the public listener key. Datastar
  `register-listener!` incorrectly looks for `::db/coordinate` in that value.
  Settle one contract before editing:
  - preferred: register the interest at an explicitly acquired current
    `:seon.db/db`, retain that ordinary map with the subscription, and let a
    resynchronization event replace it with `:db-after`; or
  - extend the canonical listen acknowledgement to return the accepted
    `:seon.db/db` map as well as the listener key, if writer registration can
    prove that value atomically.
- Do not restore a replay cursor. Datom/resynchronization events already carry
  `db-before`/`db-after`; affected subscriptions render at `db-after` and stale
  child completions are fenced by that map plus renderer identity.
- Replace the `"replay observed reads"` wording with dependency-based
  invalidation. `:seon.fn/read-attrs` plus fixed view dependencies are the
  active selective-interest mechanism.

**Proof**

- Initial root and agent pages render through the execution child using one
  grouped database acquisition.
- One relevant transaction causes one shared render for equivalent feeds; an
  irrelevant attribute causes none.
- Registration/change races converge through the explicit current-db or
  resynchronization rule.
- A hung renderer kills/restarts only its child and does not wedge the web host
  or another agent.

### 6. Rebuild tests around the real boundary and delete replica fixtures

Tests divide cleanly by owner.

**Keep**

- CLJ tests under `test/seon/db/` that directly test writer, registry,
  executor, protocol, receipts, restore, query admission, interests, generated
  IDs, and Datahike schema. These are authority tests and may use
  `datahike.api`.
- Pure CLJS protocol, UDS framing, render transformation, derivation, parser,
  and ordinary-row tests. They need no database process.
- Focused real boundary tests that start a controlled writer/session and prove
  the public asynchronous facade.

**Rewrite**

- Application CLJS suites that create in-memory Datahike databases or bind
  `db/*conn*`: `test/my/{blob,data,kb,ns,skills}_test.cljs`, agent run/turn/
  lifecycle/multiagent/ticker/context suites, eval and REPL suites, render and
  web-call suites, config/state/route/provenance suites, AI/typeahead/diffusion
  suites, and test-runner suites.
- Pure-owner tests should pass ordinary rows. Public database behavior tests
  should use a small test-only fake UDS session or a focused real writer; do
  not add a production test adapter or recreate local Datahike.
- Port only the relevant Datahike API semantics to Seon's remote conformance
  suite: query, pull, pull-many, entity, index paging, history/as-of/since,
  transact, listen/unlisten, eager return shapes, error values, multi-database
  selection, and resource bounds. Datahike's full internal suite remains in the
  maintained dependency and is not duplicated into every CLJS run.

**Delete**

- Replica/open-agent-connection/replay fixtures and any test whose sole claim
  is local connection identity, local lazy entity behavior, local listener
  replay, or SCI fallback.
- Tests for removed arities such as `:seon.db/conn`, `entity-lazy`,
  `at-coordinate`, `capture-operations!`, and ambient `db/*conn*`.
- Exact old implementation assertions when the public behavior is already
  covered through the remote facade.

**Test-runner migration**

- Keep `bin/test-writer`, `bin/test-cljs`, and `bin/seon test operator` as the
  three existing surfaces.
- `bin/test-cljs` and `script/seon/dev/changed_test.clj` still hard-code
  `node`. Route both through the one `SEON_JS_RUNTIME` selection already used
  by the pod operator, and execute Bun by default only after the focused Bun
  gate is green.
- Keep Node as an explicit comparison job only if compatibility measurement is
  still desired; it is not a production runtime path.

**Proof order**

1. Pure owner tests.
2. Remote facade and protocol tests.
3. Execution/turn/run/lifecycle focused suites.
4. Rendering, Datastar, and real browser journey.
5. One complete Bun CLJS gate, writer gate, and operator gate.

### 7. Simplify runtime and operator lifecycle after semantic green

Do this last. The current runtime cannot be simplified safely while consumer
functions still depend on removed connection behavior.

**Keep**

- One JVM authority process hosting many database connections and serializing
  writes per database.
- One Bun web/control host per active cluster for now, plus one supervised Bun
  child per active agent.
- `babashka.process` as the outer process owner and `Bun.spawn` as the child
  owner.
- Admission, crash recovery, restore proof, web/SSE shutdown, child draining,
  and final authority-session release.

**Simplify/delete**

- In `seon.client/start-runtime!`, the cold path becomes: open authority
  session; install complete schema before data; reconcile boot/config/current
  program once; recover durable run facts; open admission; host required
  children; attach router/interests; start web and ticker. Agents acquire the
  published program; they never re-transact it.
- The attached path validates the session and idempotently rehosts children and
  web interests. It never republishes initial facts.
- In `stop-runtime!`, close admission, web feeds/interests, ticker, and children
  before releasing the authority session. Remove replica/publisher/replay
  inverse steps and their retained state.
- Update `src/seon/AGENTS.md`: it still says `db/*conn*` is correct, names a
  Node pod/local replica, and documents the four retired turn coordinate
  attributes. That localized authority now contradicts the root instructions,
  active roadmap, and source facade.
- After the Bun path graduates, replace hard-coded Node command/help/metric
  names in `bin/test-cljs`, changed-test execution, package scripts, doctor,
  and operator status with the selected JavaScript runtime.
- The outer operator already invokes `babashka.process`, but still wraps
  workloads through a Python detachment helper. Do not rewrite containment in
  the consumer cut. Audit replacement with direct `babashka.process` only
  after start/stop/restart, parent-loss cleanup, signal propagation, log
  ownership, adoption, and restore generation tests define the complete
  behavior that must survive.

**Proof**

- Cold start installs schema before initial data, publishes program facts once,
  and admits no behavior early.
- Warm start adds no schema, initial-data, or program transactions when the
  desired state is unchanged.
- A child crash does not terminate its parent or siblings; the supervisor can
  restart it from database truth.
- Planned shutdown leaves no Bun child, listener, pending authority request, or
  Datahike acquisition.
- One, four, sixteen, and thirty-two child density runs measure RSS, CPU,
  startup latency, request latency, and release completeness on modest
  hardware.

## Exact source disposition summary

| Source owner | Migrate | Delete after migration | Keep |
|---|---|---|---|
| Ambient database reads | agent/run/lifecycle/schedule/turn/debug, typeahead, retrieval, selected renderers, web handlers, `my.plan` | every `db/*conn*`, `entity-lazy`, `at-coordinate`, and CLJS `:seon.db/conn` occurrence | explicit `:seon.db/db`, async facade, pure row functions |
| Local replica/replay | no replica migration | publisher, replay cursor/feed, operation capture, `read-replayable?`, local constructors, replay fixtures | authority events and explicit resynchronization |
| Execution | ordinary program/prompt/eval acquisitions | pod compiler/replay and operation capture | one retained compiler per child and one supervised host |
| Turns | one request database value and one stored transaction ref | eight coordinate attrs, attempt database copies, and reconstruction helpers | run fence, entity refs, turn/attempt evidence, prompt/reply blobs |
| Rendering | one grouped agent-view acquisition and pure guarded formatting | `render-agent-canvas`, local slot pulls, SCI renderer | recursive ordinary-data renderer, execution child, independent surfaces |
| Datastar | one current-db interest contract | replay wording and any replica assumptions | normalized subscriptions, selective dependencies, shared SSE bytes |
| Tests | pure rows, fake/real remote sessions, selected Datahike conformance | in-memory CLJS Datahike and compatibility assertions | JVM authority tests and pure protocol/transport tests |
| Operator | selected Bun runtime and final direct-process audit | Node-only production commands; replica lifecycle state | Babashka outer supervision, Bun child supervision, writer lifecycle |

## Graduation sequence

1. Close execution invocation/result on ordinary `:seon.db/db`.
2. Land the turn transaction-ref cut and its debug/autocomplete/web consumers.
3. Migrate eval residual reads and delete captured operation replay.
4. Migrate run/lifecycle/schedule/agent operations with preserved writer fences.
5. Finish toolkit ordinary-row migrations and delete their local paths.
6. Collapse rendering onto `render-agent-view!`; delete old canvas/slot/SCI
   mechanisms.
7. Settle and prove Datastar listen/current-db/resynchronization behavior.
8. Replace CLJS local-Datahike fixtures; run focused gates in dependency order.
9. Simplify client startup/shutdown and select Bun consistently in operator and
   tests.
10. Run real browser/agent journeys, complete correctness gates, no-local-
    Datahike reachability, restart/release proof, and the 1/4/16/32 density
    matrix.

The final gate is not “zero warnings.” It is one system in which the JVM is the
only Datahike owner, each active agent is an isolated Bun child, all database
calls use the asynchronous `seon.db` facade, shared program/schema/test facts
are published once and acquired by every child, Datastar invalidates only
affected views, no legacy database mechanism remains reachable, and cold/warm
start plus shutdown are proven end to end.
