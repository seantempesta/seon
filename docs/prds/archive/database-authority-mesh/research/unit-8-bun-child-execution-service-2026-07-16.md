---
type: research
status: complete
tags: [research, agent, flow, architecture, database]
---

# Unit 8 — Bun child execution service

## Decision

Replace the three in-process execution doors with one child service: execute a
granted function for an agent at a complete database coordinate and return
ordinary data. Eval, authored AI/HTML renders, and later interactions differ
only in granted functions, input/result schemas, deadline, and publication
fence. A render-only worker is rejected.

No child exists while its agent has no invocation. The host starts one lazily,
reuses it while work is active, and closes it after the bounded idle interval.
Each child opens its own persistent session directly to the JVM database
authority. Database requests never proxy through parent IPC.

## Source ledger

- Seon `5cfceff0`: `seon.eval/eval-batch!` (`eval.cljs:5093`),
  `seon.render.sci/invoke-bounded` (`render/sci.cljs:392`), and
  `seon.web.reactive.call` (`web/reactive/call.cljs:128-179`) are the three
  current doors. Render reconstruction currently traverses local Datahike.
- Bun `be77b652`: `packages/bun-types/bun.d.ts:6843-7353` defines native
  `Bun.spawn`, structured Bun-to-Bun `ipc`, `Subprocess.send`, `exited`, kill,
  stdout/stderr pipes, and disconnect. IPC callbacks are enabled only when
  requested, so dormant agents cost no process or channel.
- Shadow `4e72595f`: `shadow.build.targets.node-script` and
  `shadow.build.node` own the single `:output-to` Node-script artifact;
  `shadow.cljs.bootstrap.node` owns bootstrap loading. Produce one child
  artifact, never compile per agent.

## Contract

Parent-to-child invocation data contains the existing agent id, invocation id,
complete database coordinate, function symbol/source identity, capability set,
ordinary namespaced input, deadline, result bound, and optional run fence.
Child-to-parent data is ready, bounded log event, resolved result/error, or
exit. Control is cancel and shutdown. There are no database values, functions,
Promises, credentials, or query results on parent IPC.

The child binds every `seon.db` read to the supplied coordinate, wraps the
function result in `Promise.resolve`, awaits it, deep-forces and validates the
resolved value, and reports captured authority dependency evidence. Independent
reads use `execute-many`; sequential authored reads remain legal. Writes keep
the existing typed transaction protocol and run CAS fence.

Cancellation closes the invocation's authority requests before returning one
`:seon/error`. Deadline does the same, then terminates a non-cooperating child.
Unexpected exit fails only that agent's active invocations; the parent records
pid, exit code/signal, stderr tail, artifact digest, and coordinate, then may
restart on the next invocation. Parent shutdown sends shutdown, waits a bounded
grace, then kills. Late results with a canceled id, newer coordinate, replaced
artifact digest, or failed run fence are discarded.

Logs are structured bounded events correlated by invocation id; stdout/stderr
are diagnostic pipes, never protocol. Ready includes Bun version, Shadow build
id, source/artifact digest, protocol version, and database-session attachment.
Digest mismatch is a startup error, not compatibility mode.

## Implementation ownership and deletions

Strengthen one owner under `src/seon/execution*` (or the existing eval internal
owner if it can remain acyclic), plus one Shadow `:node-script` entry namespace
and the existing Bun process owner. Route `eval-batch!`, `invoke-bounded`, and
reactive call through it. Delete render SCI's local `fn-source`, `require-info`,
`expose-ns`, local Datahike reconstruction, synchronous interrupt timer, and
fire-and-forget recovery. Delete direct invocation from reactive call and the
in-process eval execution branch after parity. Do not retain a local fallback.

## First cohort unlocking Unit 7

1. Build one child artifact and native spawn/ready/cancel/exit owner.
2. Implement authored AI render invocation only through the shared contract,
   including direct coordinate-pinned authority reads and Promise settlement.
3. Prove zero dormant children; one reused child; concurrent agents execute in
   parallel; cancellation and crash release all requests; artifact mismatch
   refuses; no database value crosses IPC.
4. Replace `invoke-bounded` at the existing render dispatch and delete its local
   reconstruction in the same commit.

That cohort unlocks the Unit 7 13/16-member core prompt acquisition. Eval and
interactions then migrate onto the already-real service without changing its
wire vocabulary or spawning another worker class.
