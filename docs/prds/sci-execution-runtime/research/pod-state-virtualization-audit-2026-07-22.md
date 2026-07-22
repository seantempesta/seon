---
type: research
status: active
tags: [research, architecture, agent]
---

# Pod-state virtualization audit — sol read-only pass (2026-07-22)

Orchestrator-accepted; grounds the owner virtual-agents north star. Gap:
durable run truth exists, but exclusive next-turn ownership, input
consumption, LLM-attempt progress, and the reply/eval phase cursor live in
one pod. Two autonomous pods are UNSAFE today (cold recovery would close
the peer's runs; open-turn! old→old CAS admits duplicate turns). Verdict
inventory + the five-point in-flight-turn loss boundary feed the post-W5
virtual-agents series.

# Verdict inventory

| Process-resident state | What it holds; writers/readers | Restart/derivation status | Verdict |
|---|---|---|---|
| `client/!state` lifecycle | Boot/reload/heartbeat, launch capability, autonomous flag, runtime phase, writer-replacement payload, quiesce progress. Startup/stop use the phase as a same-process serialization fence. [client.cljs:249](/Users/sean/src/seon/src/seon/client.cljs:249) [client.cljs:2429](/Users/sean/src/seon/src/seon/client.cljs:2429) [client.cljs:2639](/Users/sean/src/seon/src/seon/client.cljs:2639) | Fresh on restart; these describe this process/operator transition, not agent progression. | **LEGITIMATELY-PROCESS-LOCAL**. Important caveat: `autonomous?` is a launch choice, not a cluster-wide driver claim. |
| Client advertisement and database session | Cached resumable agent IDs, listener ownership/refresh Promise, latest database values, interest handlers, socket session. [client.cljs:314](/Users/sean/src/seon/src/seon/client.cljs:314) [client.cljs:347](/Users/sean/src/seon/src/seon/client.cljs:347) [db.cljs:179](/Users/sean/src/seon/src/seon/db.cljs:179) | Agent IDs are reconstructed by querying the database, with resynchronizing interests installed before refresh. Handles and cached immutable values are reopened/reacquired. [client.cljs:458](/Users/sean/src/seon/src/seon/client.cljs:458) | Agent projection: **DERIVABLE-NOW**. Socket, Promise and listener handles: **LEGITIMATELY-PROCESS-LOCAL**. |
| Client build/eval `defonce`s | `!extra-core-vars` holds downstream compiled vars; `program-sources` lazily loads the digest-verified build artifact; `!orig-shadow-node-eval` retains the patched developer-eval conduit. [client.cljs:1138](/Users/sean/src/seon/src/seon/client.cljs:1138) [client.cljs:1203](/Users/sean/src/seon/src/seon/client.cljs:1203) [client.cljs:2958](/Users/sean/src/seon/src/seon/client.cljs:2958) | Recreated from the admitted artifact/module preload. They are build capabilities, not agent facts. W5 is already specified to remove production child/`cljs.js` bands. [program-synthesis:354](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:354) | **LEGITIMATELY-PROCESS-LOCAL**; mostly disappears or shrinks at W5. |
| Admission `!state` | Local status, publication occurrence, prepared/accepted projection fingerprint and failure reason. All loop/ticker executable boundaries consult it. [admission.cljs:37](/Users/sean/src/seon/src/seon/runtime/admission.cljs:37) [loop.cljs:313](/Users/sean/src/seon/src/seon/agent/loop.cljs:313) | Schemas/function contracts are reacquired from DB facts, reconciled and activated before agents are hosted. [admission.cljs:269](/Users/sean/src/seon/src/seon/runtime/admission.cljs:269) [client.cljs:2313](/Users/sean/src/seon/src/seon/client.cljs:2313) | Accepted generation: **DERIVABLE-NOW**. Wrappers/readiness: **LEGITIMATELY-PROCESS-LOCAL**. It does not elect an agent driver. |
| `loop/!loop-input` and wake closures | Agent ID → live input containing the LLM function closure; wake listener captures the same closure. `drive-run!` refuses without it. [loop.cljs:74](/Users/sean/src/seon/src/seon/agent/loop.cljs:74) [loop.cljs:972](/Users/sean/src/seon/src/seon/agent/loop.cljs:972) [loop.cljs:1096](/Users/sean/src/seon/src/seon/agent/loop.cljs:1096) | Cold resume reconstructs the closure from loaded provider code plus DB configuration, then reinstalls the interest and drives committed work. [client.cljs:2365](/Users/sean/src/seon/src/seon/client.cljs:2365) | Closure/listener: **LEGITIMATELY-PROCESS-LOCAL**. Making its presence a prerequisite for advancement is **DERIVABLE-WITH-WORK**; a scheduler should resolve it after winning a durable claim. |
| `loop/!run-loop-promises` | One `{run-id, Promise}` per agent. It serializes wake replay, reload reconciliation and turn recurrence only inside one pod. [loop.cljs:90](/Users/sean/src/seon/src/seon/agent/loop.cljs:90) [loop.cljs:547](/Users/sean/src/seon/src/seon/agent/loop.cljs:547) | An open run is already rediscovered from DB facts and re-driven after listener resynchronization. [loop.cljs:636](/Users/sean/src/seon/src/seon/agent/loop.cljs:636) [loop.cljs:964](/Users/sean/src/seon/src/seon/agent/loop.cljs:964) | **MISPLACED-DURABLE as the correctness serializer**. It may remain as a local optimization, but the unique next-turn claim must move to DB facts. |
| Loop recurrence locals | FSM state, no-forms streak and previous eval observation live in the async recursion. [loop.cljs:382](/Users/sean/src/seon/src/seon/agent/loop.cljs:382) | Run, turns and eval observations are durable, but restart initializes the streak/observation to `0`/`nil`, changing post-restart no-forms behavior. [loop.cljs:123](/Users/sean/src/seon/src/seon/agent/loop.cljs:123) [loop.cljs:407](/Users/sean/src/seon/src/seon/agent/loop.cljs:407) | **DERIVABLE-WITH-WORK**. Fold the trailing turn/eval log; no new counter fact is necessary. |
| Queued wake/renew/supersede/recovery callbacks | Zero-delay timers retain agent/run/cause IDs and semantic next actions. [loop.cljs:729](/Users/sean/src/seon/src/seon/agent/loop.cljs:729) [loop.cljs:781](/Users/sean/src/seon/src/seon/agent/loop.cljs:781) [loop.cljs:826](/Users/sean/src/seon/src/seon/agent/loop.cljs:826) | General open work can be rediscovered, but input “consumption” is inferred from later run closes. A crash close can therefore cover a message whose queued supersede callback never executed. [loop.cljs:620](/Users/sean/src/seon/src/seon/agent/loop.cljs:620) | **MISPLACED-DURABLE**. The consumed input must be connected to the advancement claim transaction. |
| Ticker, deadline and watchdog | `!ticker` is one interval per pod. Each pass scans overdue/stale runs and due schedules. [loop.cljs:1176](/Users/sean/src/seon/src/seon/agent/loop.cljs:1176) [loop.cljs:1200](/Users/sean/src/seon/src/seon/agent/loop.cljs:1200) | Deadline, heartbeat freshness and schedule due-ness are database-derived and survive restart. [run.cljs:945](/Users/sean/src/seon/src/seon/agent/run.cljs:945) | Timer: **LEGITIMATELY-PROCESS-LOCAL**. Scans: **DERIVABLE-NOW**. Cross-pod close/fault/notice convergence still needs a claim fence. |
| Run lifecycle and run fence | Runs, bounds, status, pause, heartbeat and close reasons are DB facts. `open-run!` CASes an absent agent run pointer; work fences assert the pointer still names that run. [run.cljs:389](/Users/sean/src/seon/src/seon/agent/run.cljs:389) [run.cljs:418](/Users/sean/src/seon/src/seon/agent/run.cljs:418) | Run lifecycle already reconstructs from facts. | **DERIVABLE-NOW**. The fence establishes authority *to the run*, not exclusive authority for one driver’s next turn. |
| Running turn bracket | `open-turn!` commits a `:running` turn with run ref, rendered basis and prompt evidence before invoking the body; the live body Promise then owns LLM/eval progress until terminal close. [turn.cljs:448](/Users/sean/src/seon/src/seon/agent/turn.cljs:448) [turn.cljs:479](/Users/sean/src/seon/src/seon/agent/turn.cljs:479) | Current cold recovery does not resume it: it marks the turn interrupted and closes the run crashed. [recovery.cljs:143](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:143) [recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351) | **DERIVABLE-WITH-WORK**. This is the main restart-visible boundary. |
| LLM attempt/retry machinery | Per-attempt `AbortController`, Promise, provider stream and timeout; `call-llm!` keeps completed attempt rows in local `!attempts`. [turn.cljs:859](/Users/sean/src/seon/src/seon/agent/turn.cljs:859) [turn.cljs:906](/Users/sean/src/seon/src/seon/agent/turn.cljs:906) | Attempt rows already model provider/model/config/outcome/request evidence, but they are persisted only by final turn close. A crash loses prior completed retries, current ordinal, backoff cursor, partial response and knowledge of remote acceptance. [turn.cljs:769](/Users/sean/src/seon/src/seon/agent/turn.cljs:769) [turn.cljs:513](/Users/sean/src/seon/src/seon/agent/turn.cljs:513) | Controllers/streams: **LEGITIMATELY-PROCESS-LOCAL**. Buffered attempt provenance: **MISPLACED-DURABLE**. |
| Reply → parse → eval frontier | Successful raw reply is best-effort blobbed and eagerly linked before parsing/eval; parsed entries, remaining-form cursor and plan-publication phase remain locals. [turn.cljs:619](/Users/sean/src/seon/src/seon/agent/turn.cljs:619) [turn.cljs:649](/Users/sean/src/seon/src/seon/agent/turn.cljs:649) | The reply bytes may survive, but no durable phase says whether to parse, continue remaining forms, publish the plan or close the turn. | **DERIVABLE-WITH-WORK**. Reuse the reply blob and add a CAS-governed phase/cursor. |
| Per-form eval receipt | Source, namespace, turn and `:running` status commit before execution. Terminal outcome and accepted program rows commit behind a receipt CAS. [eval.cljs:2957](/Users/sean/src/seon/src/seon/eval.cljs:2957) [receipt.cljc:36](/Users/sean/src/seon/src/seon/eval/receipt.cljc:36) [eval.cljs:3096](/Users/sean/src/seon/src/seon/eval.cljs:3096) | Recovery marks a still-running receipt interrupted. A late result losing the CAS cannot republish. It cannot prove whether arbitrary side effects occurred before death. [eval.cljs:3147](/Users/sean/src/seon/src/seon/eval.cljs:3147) | Detection and settle-once: **DERIVABLE-NOW**. Resumable execution: **DERIVABLE-WITH-WORK**. |
| Execution supervisor `!host` | Generation/config plus per-agent children and host sessions: process/socket controls, readiness, active invocation, timers, output tails and retained eval ownership. [execution/host.cljs:101](/Users/sean/src/seon/src/seon/execution/host.cljs:101) [execution/host.cljs:352](/Users/sean/src/seon/src/seon/execution/host.cljs:352) | Launch configuration is reinstalled and child/session entries are recreated lazily. Active invocation continuations are lost. [execution/host.cljs:668](/Users/sean/src/seon/src/seon/execution/host.cljs:668) [client.cljs:2179](/Users/sean/src/seon/src/seon/client.cljs:2179) | Controls/sockets/timers: **LEGITIMATELY-PROCESS-LOCAL**. The semantic “this turn is being advanced” claim is **MISPLACED-DURABLE**. |
| `!invocation-tails`, `::active`, generation/child IDs | Promise tails serialize an agent locally; entry-local `::active` and generation/child/invocation IDs reject stale local responses. [execution/host.cljs:723](/Users/sean/src/seon/src/seon/execution/host.cljs:723) [execution/host.cljs:986](/Users/sean/src/seon/src/seon/execution/host.cljs:986) [execution/host.cljs:1030](/Users/sean/src/seon/src/seon/execution/host.cljs:1030) | No reconstruction. Lost queued calls resolve nowhere; another pod has independent tails and active cells. | **LEGITIMATELY-PROCESS-LOCAL** correlation/queue, but not a valid cluster-wide serializer. |
| Compiler/SCI contexts | Bun child compiler/program state; developer `!compile-state`; JVM host cached SCI contexts and session workers. [repl.cljs:78](/Users/sean/src/seon/src/seon/repl.cljs:78) [host.clj:227](/Users/sean/src/seon/src/seon/host.clj:227) | Bun authored program is reacquired from DB. JVM host replays recorded home-namespace function sources into a fresh context. [execution.cljs:805](/Users/sean/src/seon/src/seon/execution.cljs:805) [host/context.clj:1405](/Users/sean/src/seon/src/seon/host/context.clj:1405) | Program/functions: **DERIVABLE-NOW**. Runtime objects: **LEGITIMATELY-PROCESS-LOCAL**. Full lossless reconstruction of arbitrary non-function defs and every namespace is **NOT GROUNDED**. |
| `globalThis.result`, pending Promises, live values, shell jobs | Bounded raw eval values plus analyzer handles; pending Promise slots; volatile background child/output table. [eval.cljs:1038](/Users/sean/src/seon/src/seon/eval.cljs:1038) [eval.cljs:1386](/Users/sean/src/seon/src/seon/eval.cljs:1386) [shell/internal.cljs:147](/Users/sean/src/seon/src/seon/agent/shell/internal.cljs:147) | Eval rows and bounded display survive, not JS identity. Lookup explicitly says evicted/prior-process values must be recomputed. Background jobs die with the process group. [eval.cljs:1400](/Users/sean/src/seon/src/seon/eval.cljs:1400) | **LEGITIMATELY-PROCESS-LOCAL**, with an honest invalidation story. W5 removes the production `cljs.js` tier. |
| SSE/reactive/web sessions | Feed registry, socket controllers, normalized subscriptions, render cache, pending newest morph, backpressure/zlib state, heartbeat, server and compiled router. [datastar.cljs:76](/Users/sean/src/seon/src/seon/web/datastar.cljs:76) [datastar.cljs:729](/Users/sean/src/seon/src/seon/web/datastar.cljs:729) [router.cljs:64](/Users/sean/src/seon/src/seon/web/router.cljs:64) [serve.cljs:58](/Users/sean/src/seon/src/seon/web/serve.cljs:58) | Browser reconnect opens a fresh feed and renders the current database value. Routes are rebuilt from route facts. Lost intermediate morphs are irrelevant. [datastar.cljs:778](/Users/sean/src/seon/src/seon/web/datastar.cljs:778) | **LEGITIMATELY-PROCESS-LOCAL**; rendered value/router projection **DERIVABLE-NOW**. |
| Canvas | There is no authoritative process-local canvas session. Selection is `:seon.render.canvas/content`; domain state is qualified DB attrs; renderers consume an injected immutable DB value. [canvas.cljc:79](/Users/sean/src/seon/src/my/canvas.cljc:79) [canvas.cljc:185](/Users/sean/src/seon/src/my/canvas.cljc:185) | Selection and state survive; SSE reconnect rerenders them. | **DERIVABLE-NOW**. |
| Optional provider/typeahead/diffusion state | Provider function registry and typeahead backing are loaded-code registries. Typeahead keeps a large live frontier; DiffusionGemma keeps remote job ID/cancel state locally. [dispatch.cljs:35](/Users/sean/src/seon/src/seon/ai/dispatch.cljs:35) [typeahead.cljs:190](/Users/sean/src/seon/src/seon/ai/typeahead.cljs:190) [gemma.cljs:578](/Users/sean/src/seon/src/seon/diffusion/gemma.cljs:578) | Registries reload. Exact typeahead replay is **NOT GROUNDED**. A remote Diffusion job may outlive the lost local job ID. | Registries: **LEGITIMATELY-PROCESS-LOCAL**. Remote job identity: **MISPLACED-DURABLE**. Typeahead frontier: either an indivisible abandoned attempt or **DERIVABLE-WITH-WORK** via an explicit checkpoint. |

## Bottom line

The current pod is restart-recoverable, but not restart-invisible.

A cold autonomous startup runs cluster-wide recovery before hosting agents. It treats every nonterminated run pointer as interrupted ownership, retracts it, closes the run `:crashed`, and marks running turns/evals `:interrupted`; only afterward does it resume durable agents. [client.cljs:2266](/Users/sean/src/seon/src/seon/client.cljs:2266) [client.cljs:2307](/Users/sean/src/seon/src/seon/client.cljs:2307) [recovery.cljs:429](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:429)

Hot reload is materially better: it republishes admission, rehosts DB-derived agents, reinstalls listeners/ticker, and can re-drive an open run without destructive recovery. [client.cljs:628](/Users/sean/src/seon/src/seon/client.cljs:628) [client.cljs:1863](/Users/sean/src/seon/src/seon/client.cljs:1863)

Thus the exact gap to the north star is:

> Durable run truth exists, but exclusive next-turn ownership, input consumption, LLM-attempt progress, and the reply/eval phase cursor still live in one pod’s closures, Promises and atoms.

## Exact in-flight-turn loss boundary

1. **Before provider dispatch:** the DB already contains a running turn, run link, rendered basis transaction and prompt blob. [turn.cljs:448](/Users/sean/src/seon/src/seon/agent/turn.cljs:448)

2. **During the provider request:** there is no durable attempt-open receipt. The DB cannot say whether the provider never saw the request, is still processing it, or completed it. The abort controller, retry ordinal, partial stream and prior buffered retries are lost. [turn.cljs:859](/Users/sean/src/seon/src/seon/agent/turn.cljs:859)

3. **After response link, before eval completion:** raw reply bytes may be recoverable from the blob, but attempt provenance and the parse/eval cursor are not. [turn.cljs:619](/Users/sean/src/seon/src/seon/agent/turn.cljs:619)

4. **During a form:** the running eval receipt proves which source began. A database effect might already have committed before the process died. Recovery marks the eval interrupted and does not rerun it. [eval.cljs:2957](/Users/sean/src/seon/src/seon/eval.cljs:2957) [recovery.cljs:389](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:389)

5. **After eval terminal CAS, before turn close:** the eval result/program facts survive, but attempt rows and terminal turn status may still be missing because attempt rows wait for turn close. [eval.cljs:3096](/Users/sean/src/seon/src/seon/eval.cljs:3096) [turn.cljs:513](/Users/sean/src/seon/src/seon/agent/turn.cljs:513)

The existing capability receipt helps only when the caller supplies and later reuses a stable `:seon.capability/op-id`; the wrapper otherwise generates a fresh UUID. [host/context.clj:766](/Users/sean/src/seon/src/seon/host/context.clj:766) Provider-side request deduplication or result lookup for OpenAI-compatible/Anthropic calls is **NOT GROUNDED**.

## Multi-driver verdict

Two autonomous pods are unsafe today.

1. **The first break is startup recovery.** Pod B’s cold-start recovery has no durable ownership/lease test and can close Pod A’s legitimate active runs as crashed. [client.cljs:2266](/Users/sean/src/seon/src/seon/client.cljs:2266) [recovery.cljs:134](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:134)

2. **If that recovery is bypassed, both pods can advance the same run.** Each installs its own listener, ticker, `!run-loop-promises`, host/session and invocation tail.

3. **Run opening is safe.** The absent-pointer CAS prevents two idle wakes from creating two open runs. [run.cljs:418](/Users/sean/src/seon/src/seon/agent/run.cljs:418)

4. **Turn opening is not safe.** Both drivers can observe the same open run. `open-turn!` performs an old→old CAS that only asserts the agent still points to that run; it does not consume or mutate a next-turn claim. Both transactions can succeed and allocate different running turns. [turn.cljs:466](/Users/sean/src/seon/src/seon/agent/turn.cljs:466)

5. **Receipt CAS does not repair this.** It settles one newly allocated eval ID exactly once. Duplicate drivers have different turn IDs and different eval IDs, so both can terminalize successfully. [receipt.cljc:57](/Users/sean/src/seon/src/seon/eval/receipt.cljc:57)

6. **Host/session CAS is also local.** Each socket session has its own active cell; two pods do not contend on it.

Therefore:

- Run fence CAS: **detects stale/superseded run authority; does not claim advancement**.
- Eval receipt CAS: **makes recovery-versus-late-settlement safe for one eval; does not deduplicate two turns**.
- Double advancement is neither prevented nor reliably collapsed. It becomes visible as multiple durable turns/evals after the damage.

Duplicate watchdog notices/fault records under two stale scans are plausible, but a two-autonomous-pod behavioral proof was not found: **NOT GROUNDED**.

## Ranked post-W5 unit series

W5 should land first because it deletes the child/production-`cljs.js` bands and shrinks the state surface, exactly as the active ledger requires. [program-synthesis:354](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:354) The following series directly implements the recorded virtual-agent north star. [program-synthesis:1108](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1108)

1. **VA-1 — durable run-advancement claim**

   Atomically create/claim exactly one next-turn identity for an open run. Include claim epoch/lease, admitted program fingerprint and consumed input identity in the same transaction. `!run-loop-promises` and invocation tails become optimizations only.

   Exit: two drivers race one run; exactly one turn identity commits.

2. **VA-2 — reconstruct the loop fold and input frontier**

   Derive the no-forms streak and prior observation from trailing durable turns/evals. Replace close-time message coverage with an explicit input-consumption connection written by VA-1.

   Exit: losing every queued timer cannot lose or falsely consume a message.

3. **VA-3 — durable LLM attempt receipt**

   Before network I/O, commit deterministic attempt identity, ordinal, frozen request/config digest, prompt/rendered basis, absolute deadline and client request key. Immediately CAS-terminalize each response and atomically link its reply blob; stop buffering completed attempts until turn close.

   An unknown provider request must remain explicitly `:unknown`/`:interrupted` unless provider lookup or idempotency is proven. Exactly-once remote inference/billing is **NOT GROUNDED**.

4. **VA-4 — resumable reply/eval phase fold**

   Make `prompt-ready → attempt-open → reply-ready → eval-running → eval-complete → plan-published → turn-terminal` explicit CAS/idempotency boundaries. Store the reply/parsed-form frontier and next unconsumed form ordinal. Reuse the existing prompt/reply blobs and eval receipt CAS.

5. **VA-5 — stable effect operation IDs**

   Derive effect operation IDs from durable turn/eval/effect ordinals and require effectful capability wrappers to reuse them. Existing writer receipts can then replay acknowledged DB writes safely. External/package effects need equivalent receipts or must remain honestly non-replayable.

6. **VA-6 — lease-aware takeover and recovery**

   Replace unconditional boot recovery with:

   - live claim: leave it alone;
   - expired claim: CAS-take over and resume the last durable phase;
   - irrecoverably ambiguous effect: terminalize honestly;
   - only then close the run `:crashed`.

   This is the boundary that makes **pod restart invisible at turn granularity**.

7. **VA-7 — driver-neutral scheduler/watchdog**

   Wake listeners and tickers become disposable hints. Any process queries “open run + unconsumed input,” contends on VA-1, and only the winner performs work. Fence schedule firing, watchdog close, fault and outcome notice on the exact claim epoch.

   This is the boundary that makes **any process able to advance any agent**.

8. **Graduation gate**

   Two autonomous pods, one cluster; race wake, schedule, resume and watchdog. Kill the winning pod:

   - before provider dispatch;
   - after possible provider acceptance;
   - after reply receipt;
   - during an eval after a committed effect;
   - after eval terminal CAS but before turn close.

   Require one consumed-input edge, one turn identity, one terminal attempt result, one domain effect, one terminal eval receipt, and takeover without operator intervention. No existing proof covers this: **NOT GROUNDED**.

## What stays process-local forever

Sockets, database interests, SSE controllers, zlib/backpressure state, timers, AbortControllers, provider SDK clients/iterators, Promise continuations, local queues, runtime controls, thread pools, compiler/SCI runtime objects, ALS print/warning buckets, live `result/<id>` values and opaque handles should remain volatile.

Their loss must discard only connectivity, acceleration and object identity. Durable facts must answer: what work exists, what input was consumed, which phase committed, which effect receipt exists, and which CAS transition may happen next.