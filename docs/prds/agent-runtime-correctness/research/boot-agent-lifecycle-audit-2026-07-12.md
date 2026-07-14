---
type: research
status: completed
tags: [research, agent, database, flow]
---

# Default-cluster boot and agent lifecycle audit — 2026-07-12

## TL;DR

The default pod still has one function, `seon.client/start-agent!`, doing two
unrelated jobs: cold process/cluster startup and warm creation of one agent.
That coupling is both the latency bug and a correctness boundary failure.

The cold process observed in this audit took 10.826 seconds from `-main` to
ready. It had the connection, compiler, and 14-agent roster by 0.527 seconds;
9.270 of the next 9.823 seconds was the boot-seed/program-snapshot and ghost
comparison interval. Resuming and hosting all 14 agents plus starting the HTTP
server fit inside the final 0.125-second interval.

A single safe warm `POST /agents/new` probe then proved the coupling more
strongly than another successful timing would. Before allocating an agent, the
request entered `boot-seed!`, rebuilt the global source index, and hit a
hot-reload-inconsistent call to
`seon.analyzer-info/require-edges-from-source`. It returned HTTP 500 after
2.990 seconds and the configured core-fault policy stopped the default pod.
The writer showed basis 536871158 → 536871159 and the agent count remained 14:
the only commit was the fault record; agent birth had not begun. ACME was not
touched, and this audit did not restart the default pod.

The smallest clean correction is an in-place split with one owner per
transition:

- `seon.client` starts the cluster runtime exactly once and never mints;
- `seon.agent` atomically creates all initial durable agent facts, then hosts
  the committed agent;
- `seon.agent/resume!` reconstructs only one agent's transient namespace,
  wake listener, loop input, and runtime advertisement; and
- the web routes and programmatic spawn call that same schema'd mint function
  directly.

No compatibility path is needed. Delete `::mint?`, `agent/boot!`, the two web
creation injection atoms/setters, the global create lock, and the child-arm
hook after their callers move in the same patch.

## Scope and method

This audit covered the active CLJS pod and JVM writer for the **default**
cluster only. It used source inspection, current process state, the preserved
cold-start log, one ordinary HTTP mint request, the default writer's read-only
REPL, and the already completed Phase 0 profile in
[[phase-0-default-pod-live-baseline-2026-07-12]].

Constraints honored:

- no reset or deliberate restart;
- no ACME access or mutation;
- no production-code edits;
- one warm mint request only; and
- no inferred success after the live request failed.

Line numbers below are the working-tree snapshot inspected on 2026-07-12;
function names are the durable locator because another lane owns current
`client.cljs` edits.

## Live default state before the probe

The default runtime was healthy before the mint request:

| Fact | Observed value |
|---|---:|
| Pod PID | 26570 |
| Pod start | 2026-07-12 12:37:06 local |
| Pod RSS sample | 1,141,168 KiB |
| Default writer PID | 18949 |
| HTTP | `127.0.0.1:7890` |
| Konserve keys on open | 5,059 |
| Database basis | 536871158 |
| Durable agents | 14 |
| Derived idle/armable agents | 14 |
| Runtime-advertised agents | 14 |

The durable, armable, and advertised ID sets were identical. This is useful
evidence for the clean-start case, but it is not a general invariant today:
termination does not remove an already-hosted ID or wake-listener state in the
same process.

## Current cold boot measurement

The preserved log for PID 26570 gives this stage boundary:

| Stage | UTC timestamp | Elapsed from `-main` |
|---|---:|---:|
| `-main` | 16:37:07.992 | 0.000 s |
| cluster connection logged | 16:37:08.043 | 0.051 s |
| full-schema transaction + wire feed live | 16:37:08.282 | 0.290 s |
| compiler/recovery complete; 14-agent roster derived | 16:37:08.519 | 0.527 s |
| five global ghosts identified | 16:37:17.789 | 9.797 s |
| ghost log/retraction complete | 16:37:18.342 | 10.350 s |
| three stored namespaces replayed, one failed | 16:37:18.559 | 10.567 s |
| 698 functions globally instrumented | 16:37:18.690 | 10.698 s |
| HTTP listening | 16:37:18.815 | 10.823 s |
| ticker installed / auto-boot ready | 16:37:18.818 | 10.826 s |

The log has no marker between `boot-seed!` and the desired-snapshot half of
`prune-core-ghosts!`, so those must not be falsely separated. What is measured:

- connection, full native schema assertion, feed attach, compiler readiness,
  crash recovery, and roster query finished in 0.527 seconds;
- unconditional seed plus the two complete program-snapshot constructions
  occupied the 9.270 seconds from roster to ghost identification;
- logging and retracting five ghosts took another 0.553 seconds;
- whole-program replay took 0.217 seconds;
- complete instrumentation took 0.131 seconds; and
- all 14 per-agent resumes plus HTTP start fit inside the remaining 0.125
  seconds.

The prior CPU profile supplies the missing internal attribution. Its inclusive
samples found 7.919 seconds in `core-index-tx` and 5.776 seconds in
`prune-core-ghosts!` under profiler overhead, dominated by two complete
`index-core! → var->fn-row → extract-form-at-line` passes. The current cold log
and that profile agree on the owner even though their absolute timings are not
directly comparable.

The cold replay also remained unhealthy: `:seon.render.live-tile` failed Malli
registration, so replay reported two successes and one failure. That defect is
not caused by the lifecycle split, but mint must never repeat it.

## Warm mint failure — agent birth never began

At 20:26:55.539Z, with the pod warm and hot reload generation 128 active, this
audit sent one normal form-encoded `POST /agents/new` with an inert audit
purpose. The response was HTTP 500 at 2.989986 seconds.

The exact sequence was:

1. `seon.web.serve/handle-create-agent!` acquired `!create-in-flight` and called
   the injected creation closure.
2. The closure called `seon.client/start-agent!` with `:mint? true`.
3. `start-agent!` reused the existing connection, skipped crash recovery,
   reused the bootstrap compiler, and entered `boot-seed!`.
4. `boot-seed!` called `core-index-tx`, which called `index-core!`.
5. While building a full-source namespace row, `ns-row` called
   `analyzer-info/require-edges-from-source` at `client.cljs:1289`.
6. The runtime property was not a function. The request failed before
   `agent/mint!` was reached.
7. `seon.error/record!` persisted a core fault and the configured `:crash` dial
   exited the pod.

The preserved error is:

```text
TypeError: seon.analyzer_info.require_edges_from_source is not a function
    at f (.../cljs-runtime/seon/client.cljs:1289:15)
```

The source and current compiled JavaScript both contain that public function.
The dead runtime could not be inspected after the fact, so this audit does not
claim why generation 128 lacked a callable property. It does prove the
load-bearing lifecycle fact: a warm agent mint depended on a complete,
hot-reload-sensitive global source index before creating any agent.

The default writer remained up. A read after the crash returned:

| Fact | Before request | After crash |
|---|---:|---:|
| Basis | 536871158 | 536871159 |
| Agent count | 14 | 14 |

Thus no partial agent or context graph was created. The one basis increment was
the persisted fault record.

## Current lifecycle call graph

### Cold process/runtime start

`seon.client/-main` calls `start-agent!`, which currently performs:

1. `open-cluster-conn!` when `!agent-conn` is nil;
2. root-set `db/*conn*` and assert DB preconditions;
3. `run/recover-crashed-runs!` on that nil/non-nil heuristic;
4. `repl/ensure-bootstrap!`;
5. `agent/armable-agent-ids`;
6. `boot-seed!`;
7. `prune-core-ghosts!`;
8. optional root or requested agent creation;
9. `replay-program-graph!` for the complete stored program;
10. `instrument/instrument-from-db!` for the complete function roster;
11. sequential `init-agent!` for selected agents;
12. `web.serve/start!`;
13. `ai/sync!`;
14. `seon.web.debug/install!`, which also invokes `brand/sync!`;
15. `agent-loop/install-ticker!`; and
16. `register-arm-hook!`.

Only this transition should perform global steps 1–16. The later exact
program/config reconciler changes how steps 6–7 work; it does not make them
agent-birth work.

### Warm web birth

`POST /agents/new` follows:

```text
handle-create-agent!
  → !create-agent-fn
  → start-agent! {:mint? true}
  → every global cold-start step above
  → agent/mint!
  → init-agent! {:mint? false}
```

The `mint?` flag suppresses the existing-agent roster, but it does not suppress
any cluster work.

### `/agents/run` birth

The one-shot route already has the shape warm creation should use:

```text
!mint-agent-fn
  → agent/mint!
  → init-agent! {:mint? false}
```

It avoids `start-agent!`, but it is a second injected entry point with a
slightly different request shape and no purpose argument.

### Programmatic child birth

`seon.agent/start!` follows:

```text
start!
  → spawn-child!
  → agent/mint!
  → !arm-child-fn
  → client/init-agent! {:mint? false}
```

This is a third lifecycle seam. Its injected arm callback exists only because
`seon.agent.loop` currently requires `seon.agent` for one message predicate,
preventing `seon.agent` from calling its own loop host operation directly.

### Existing-agent resume and hot reload

Cold resume and hot-reload re-arm both call `init-agent!` with `::mint? false`.
It:

1. resolves compiler state and LLM closure;
2. runs `seval/setup-agent-ns!`;
3. conditionally installs/replaces the wake listener;
4. writes loop input into the process-local map; and
5. calls `runtime-id/host!`.

Hot reload additionally reinstalls the ticker, child-arm hook, heartbeat, and
complete DB-driven instrumentation. It does not reconcile the program graph,
which is why warm mint has been accidentally acting as a later global repair
door.

## Exact global work repeated per warm agent

| Repeated operation | Exact current call | Work per warm `/agents/new` | Correct owner |
|---|---|---|---|
| Full core snapshot | `core-index-tx → index-core!` | one complete public-var/file pass | cold boot or core reload |
| Full schema snapshot | `core-index-tx → index-schemas` | one complete Malli registry pass | cold boot or schema delta |
| Full test snapshot | `core-index-tx → index-tests` | one complete test-var/file pass | cold boot or test reload |
| Same three snapshots again | `prune-core-ghosts!` | second complete core/schema/test pass | delete with exact reconciliation |
| Native/entity schema assertions | `open-cluster-conn!`, then `boot-seed!` | full native schema at attach; full entity-schema data on every mint | fresh install/migration only |
| Core seed transactions | `boot-seed!` | entity schemas, core singleton, and program index submitted even when converged | cold desired-state transition |
| Manifest and disk scans | `boot-seed!` | manifest, routes, and skill corpus rebuilt | explicit config apply/cold boot |
| Agent context manifest read | `ctx/seed-default-ctx! → config/resolve-agent-context` | manifest read again during the same mint | DB mint template read |
| Config reconciliation | `state/reconcile!` | complete desired config population submitted | config transition only |
| Persisted program replay | `replay-program-graph!` | every agent-authored namespace loaded again | new JS runtime only |
| Global instrumentation | `instrument-from-db!` | every recorded function resolved/wrapped again | cold runtime and changed defs only |
| Router/service start | `web.serve/start!` | router rebuilt even though server is reused | process start / route delta |
| AI/brand synchronization | `ai/sync!`, `web.debug/install! → brand/sync!` | global seed checks repeated | process/config start |
| Debug listener | `web.debug/install!` | global listener replaced | process start/hot reload |
| Ticker | `agent-loop/install-ticker!` | global interval cleared/reinstalled | process start/hot reload |
| Spawn hook | `register-arm-hook!` | process callback replaced | delete after direct lifecycle call |

At the builder level, one warm mint therefore invokes `index-core!`,
`index-schemas`, and `index-tests` **twice each**. This is not a generic diff
problem. The same desired snapshot is independently constructed for additions
and deletions.

## Durable birth is still multi-transaction

The new allocator correctly commits the readable identity under the sole
writer, but `agent/mint!` is not yet an atomic *agent birth*:

1. `db.id/allocate!` commits the agent identity, purpose/default, and parent;
2. `seed-default-context!` calls `ctx/seed-default-ctx!`;
3. `ctx/install!` commits the component block tree;
4. a second context transaction may commit agent-level scalar dials; and
5. `seval/setup-agent-ns!` may commit the home namespace require-edge graph.

The current docstring saying the complete initial row is atomic overstates the
actual boundary. A crash after step 1 leaves a durable agent with incomplete
initial facts. The recovery story is currently “later config can repair it,”
which is exactly the healer model this refactor is removing.

The fresh-root branch is worse ordered: `init-agent!` executes
`setup-agent-ns!` **before** `agent/boot!` creates the known `"root"` entity.
If creation fails, the code correctly avoids the wake trigger and runtime host,
but a namespace/require-edge row may already have committed. `::mint?` is only
needed for this special branch now; ordinary web and task mints allocate before
calling `init-agent!`.

## Host and resume facts currently drift

Three distinctions need to become explicit:

- **durable agent** — presence of `:seon.agent/id` and its connected initial
  facts;
- **hosted agent** — this process has the analyzer namespace, loop input,
  optional wake listener, and MCP runtime advertisement; and
- **derived run state** — idle/running/paused/terminated from database facts.

Current code conflates them in these ways:

1. `start-agent!` uses `armable-agent-ids` (idle only) as the resume roster.
   A future restart that preserves paused or resumable running facts would fail
   to host those agents. The host roster should be all nonterminated agents;
   run continuation is a separate state decision.
2. `runtime-id/host!` has a matching `unhost!`, but production termination
   never calls it.
3. `install-wake-trigger!` stores the LLM/compiler input in `!loop-input` and
   registers a DB listener, but there is no uninstall operation that removes
   both. A terminated agent therefore remains advertised and retains process
   closures until process restart.
4. `!agent-conn` duplicates the already `defonce`, reload-stable `db/*conn*`.
   It is used as both a second connection authority and the “cold start?” flag.
   These facts can disagree and should not be separate state.

## Target state transitions

The transition data should be facts and handles, not stored algorithm status.

### Cold runtime start

```text
no attachment
  → attach one DB connection
  → validate/reconcile global durable state
  → reconstruct global compiler/program/schema/instrument state
  → recover runs
  → start global services once
  → resume every nonterminated durable agent
  → ready
```

The runtime may keep one process-local start promise/status in the existing
`client/!state` cell to coalesce accidental concurrent starts. The DB
connection itself has one authority: `db/*conn*`. Do not add another registry.

### Agent mint

```text
candidate id
  → one writer transaction commits:
       agent identity + purpose/defaults/parent
       complete context components and scalar dials
       home namespace row and require-edge components
       any safe agent-unique initial declarations
  → host the committed agent
  → return the committed id
```

A failed transaction creates nothing. A host failure after commit leaves one
complete idle durable agent; `resume!` can retry transient setup without
repairing durable facts or minting another identity.

### Agent resume

```text
existing nonterminated durable agent
  → validate/pull initial facts
  → evaluate/rebuild its analyzer namespace
  → install or replace its wake listener and loop input
  → advertise it as hosted
  → continue only the run behavior derived for its durable state
```

Converged resume writes no datoms. Terminated agents are not hosted. Re-running
resume replaces, rather than stacks, process handles.

### Agent unhost/terminate

```text
hosted agent
  → unlisten wake key
  → remove loop input closure
  → remove runtime advertisement
  → unhosted
```

The durable termination fact remains the authority for whether cold resume
selects the agent.

## Smallest in-place refactor

This is one atomic refactor, not a `v2` path.

### 1. Let the agent namespace own its lifecycle

Move `inbound-msg-datom?` from `seon.agent` to its existing data owner,
`seon.agent.message`, beside `waking-inbound?`. Then
`seon.agent.loop` no longer requires `seon.agent`, so `seon.agent` can require
the loop without a cycle.

In `seon.agent`, make these the one public lifecycle:

- `mint!` — compile all initial durable facts into the allocator transaction,
  then call the private host helper;
- `resume!` — validate an existing nonterminated entity and call the same host
  helper;
- `unhost!` — remove wake/loop/runtime process handles; and
- `start!` / `delegate!` — retain their agent-facing names but call `mint!`
  directly, with no injected arm callback.

Delete `boot!`; known-root creation can call the same initial-facts compiler
with the fixed root identity during cold genesis.

The provider-dispatch closure currently in `seon.client` must have one
non-client owner reachable by `seon.agent`. Move, do not copy, `stub-llm`,
`select-adapter`, and `current-llm-fn` into one provider-dispatch namespace or
the final chosen AI owner. This is dependency placement, not a second
lifecycle.

### 2. Make initial facts pure data

In `seon.agent.ctx`, replace creation-time `seed-default-ctx!` writes with a
pure function that returns the resolved agent scalar map plus component block
data. Keep `install!` and `remove!` for later runtime edits.

In `seon.agent.home`, add one pure home-namespace entity builder from the same
`home-requires-for` data used to generate the `(ns …)` form. The allocator
transaction includes this row and its component require edges. Then
`seval/setup-agent-ns!` becomes runtime-only and performs no database write.

The existing config singleton does not currently persist
`:seon.config/agent-context` or `:seon.config/root-context` in
`resolve-config-singleton`, while `resolve-agent-context` rereads the manifest.
The correct source is the existing DB config singleton/mint template. Do not
add another template entity or cache. If that small config projection cannot
land in the same patch, make it an explicit prerequisite rather than blessing
per-mint file reads as the target.

### 3. Make `seon.client` cold-only

Rename or rewrite `start-agent!` in place as the one runtime-start operation.
Remove `:mint?`, `purpose`, and all requested-agent branching. It always:

- opens/attaches once;
- performs cold global work once;
- ensures the fixed root only on a genuinely empty store;
- starts global services once; and
- calls `agent/resume!` for the nonterminated roster.

Use the existing `client/!state` for a coalesced start promise/status if
reentrancy must be guarded. Delete `!agent-conn`; `db/*conn*` is already
`defonce` and reload-stable.

Hot reload calls only reload-owned operations: changed declaration/schema
install, instrumentation delta when that phase lands, idempotent resume/re-arm
of affected agents, and replacement of reload-scoped services. It never calls
cold start or mint.

### 4. Delete creation indirection

`seon.web.serve` already requires `seon.agent`. Both `/agents/new` and
`/agents/run` can call the same `agent/mint!` request directly. Delete:

- `!create-agent-fn` and `set-create-agent-fn!`;
- `!mint-agent-fn` and `set-mint-agent-fn!`;
- `!create-in-flight`; and
- `agent/!arm-child-fn` plus `client/register-arm-hook!`.

The serialized writer allocator is the creation fence. A process-global
boolean is not needed for independent mints once no global boot work is in the
request.

## Exact production files and functions to change

| File | Functions/state | Required change |
|---|---|---|
| `src/seon/client.cljs` | `!agent-conn`, `init-agent!`, `register-arm-hook!`, `rearm-wake-triggers!`, `start-agent!`, both web setter calls, `-main` | make start cold-only; move one-agent lifecycle to `seon.agent`; delete mode and hook/injection paths |
| `src/seon/agent.cljs` | `inbound-msg-datom?`, `seed-default-context!`, `create!`, `mint!`, `spawn-child!`, `start!`, `delegate!`, `boot!`, `!arm-child-fn` | own atomic birth + host/resume/unhost; delete boot and injected arm |
| `src/seon/agent/message.cljs` | `waking-inbound?` area | own the datom adapter now in `seon.agent`, breaking the loop cycle |
| `src/seon/agent/loop.cljs` | `!loop-input`, `install-wake-trigger!`, wake predicate call | call message owner; add one uninstall that removes listener and loop input |
| `src/seon/agent/lifecycle.cljs` | `terminate` | unhost after durable termination/close succeeds |
| `src/seon/agent/ctx.cljs` | `upsert-ctx-tx`, `seed-default-ctx!` | add pure initial component/scalar data; delete creation-time multi-write seed path |
| `src/seon/agent/home.cljs` | `home-requires-for`, `home-ns-form` | add pure initial namespace/edge entity data from the same require specs |
| `src/seon/eval.cljs` | `setup-agent-ns!` | runtime analyzer setup only; remove creation-time durable edge write |
| `src/seon/web/serve.cljs` | both injection atoms/setters, `!create-in-flight`, `handle-create-agent!`, `run-agent-task!`, `handle-agent-run!` | call one schema'd `agent/mint!` directly and delete the global lock |
| final AI dispatch owner | `stub-llm`, `select-adapter`, `current-llm-fn` currently in `client.cljs` | move the one provider chooser out of the cold-start namespace; no duplicate copy |
| `src/seon/config.cljs` | `resolve-config-singleton`, `resolve-agent-context` | make existing config singleton carry/read the mint template; no per-mint manifest authority |

Later exact program reconciliation still changes `core-index-tx` and deletes
`prune-core-ghosts!`; Phase 2 only removes both from mint. Do not combine that
larger algorithm change with the lifecycle call-graph split unless its owner is
already ready.

## Behavioral proof points

Tests must assert facts, calls, and transitions, never context prose.

### Atomic birth

- One mint advances basis exactly once after native schemas are installed.
- That commit contains the agent identity, context component graph, scalar
  dials, home namespace, and require-edge components.
- A rejected allocation creates none of those facts.
- A forced host failure leaves exactly one complete idle durable agent; a later
  `resume!` hosts that same ID without a database transaction.

### No global work on mint

- Five sequential warm mints produce five unique IDs and zero
  `:core-seed`/`:config` transactions.
- Mint invokes zero core snapshot, prune, replay, global instrumentation,
  server start, router rebuild, provider/brand sync, debug install, ticker
  install, or runtime-start operations.
- Preserve the live regression directly: make a global index helper throw or
  be absent after runtime readiness; `agent/mint!` must still succeed because
  its call graph cannot reach it.

### Resume and hosting

- Cold resume of several existing agents changes no durable facts and hosts
  each exactly once.
- Re-running resume replaces the stable wake-listener key and loop input; one
  inbound message opens one run, never two.
- A paused nonterminated agent remains hosted; a terminated agent is not.
- Termination removes listener, loop input, and runtime advertisement in the
  same process.
- Runtime advertisement equals the DB-derived nonterminated host roster after
  cold resume and after termination.

### Concurrency and restart

- Concurrent mints need no global boolean: writer allocation returns unique
  agents with non-crossed context/home components.
- Concurrent runtime-start calls coalesce into one attachment and one set of
  global service installations.
- Restart resumes the same durable IDs and does not mint a replacement.
- Global cold steps still run in their defined order; agent birth never runs
  them.

### Live acceptance

After focused CLJS tests, use the default cluster only:

1. capture basis, agent IDs, advertised IDs, and global lifecycle counters;
2. mint five agents sequentially and at least two concurrently;
3. verify sub-second warm responses at the current store size;
4. query exact transaction origins and initial component counts;
5. terminate one created agent and verify immediate unhost;
6. restart once and verify the same IDs resume; and
7. confirm `/agents`, one agent page, and a decoded gzip feed remain healthy.

## Conclusion

The slow warm mint is not an optimization mystery. It is a wrong call graph.
The live crash showed the practical consequence: agent creation can fail and
stop the pod because an unrelated global source-index helper is temporarily
unavailable after hot reload.

Split the transitions before optimizing their internals. Once warm mint has
only “compile one durable agent transaction, commit, host,” the later exact
program/config reconciler, incremental instrumentation, and reactive-render
work can evolve behind their actual owners without affecting agent birth.
