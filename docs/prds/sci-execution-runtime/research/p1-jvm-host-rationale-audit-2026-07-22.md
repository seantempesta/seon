---
type: research
status: active
tags: [research, agent, architecture]
---

# P1 JVM-host rationale audit — 2026-07-22

## Executive answer

The question needs one framing correction: the original design did **not** put
sci execution inside the writer. It proposed a dedicated JVM `agent-host` that
used the writer's UDS protocol as an ordinary client; the writer remained the
sole Datahike authority ([design.md:26](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:26),
[child feasibility:160](/Users/sean/src/seon/docs/prds/source-cleanup/research/sci-execution-child-feasibility-2026-07-20.md:160),
[C1:54](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:54)).
Writer locality and in-process Datahike are therefore not JVM-host rationales
and do not block P3.

No original rationale makes the JVM structurally necessary for the **process
topology** of P3. A same-artifact Bun worker can provide the disposable address
space, shared sci contexts, database/corpus authority, capability isolation,
and supervisor recovery that motivated the host
([design.md:38](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:38),
[crashproof:170](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:170),
[WP-S grounding:84](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md:84)).

P3 is nevertheless **blocked as presently mechanized**, not by a JVM law but
by a missing control edge. WP-S2 can TERM then KILL an exact managed generation
when the operator drains it, but the pod's current host-lane `kill!` closes only
the UDS stream; `ensure-host!` preserves a live converged workload
([detach.py:237](/Users/sean/src/seon/script/seon/dev/detach.py:237),
[detach.py:242](/Users/sean/src/seon/script/seon/dev/detach.py:242),
[host.cljs:589](/Users/sean/src/seon/src/seon/execution/host.cljs:589),
[host.cljs:600](/Users/sean/src/seon/src/seon/execution/host.cljs:600),
[process.clj:2645](/Users/sean/src/seon/script/seon/dev/process.clj:2645)).
Before cutover, a deadline must be able to request drain/force of the exact Bun
worker generation, observe its death, close running receipts as interrupted,
and reconnect to a reconstructed worker. Otherwise a native or regex hot loop
can remain live after the session is closed
([SCI interrupt:106](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:106),
[recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351)).

With that bridge, the JVM-only benefits become explicit degradations rather
than walls: process-wide instead of per-invocation preemption, no proven
pooled-thread CPU parallelism, async rather than blocking UDS leaves, and no
JVM `eval`/HotSpot graduated root
([C1:136](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:136),
[C1:203](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:203),
[design.md:95](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:95)).

## Scope and dependency ledger

This audit treats the late-night owner ruling as current: P1 establishes one
portable capability/effect seam, P3 replaces per-agent children with a
same-artifact supervised Bun worker, and JVM preemptive interruption becomes
an optional upgrade
([program:966](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:966),
[program:982](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:982)).

The mechanisms grounding the answer are:

- Seon's maintained sci checkout and structured-resolution patch are recorded
  as `8fac6e88…`; `sci/fork`, `:interrupt-fn`, SCI vars, and context binding are
  `.cljc` seams rather than JVM-only APIs
  ([W3 grounding:29](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md:29),
  [sci/core.cljc:288](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:288),
  [sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318),
  [ctx_store.cljc:38](/Users/sean/src/seon/reference-code/sci/src/sci/ctx_store.cljc:38)).
- The writer remains the Datahike authority; both JVM and Bun clients already
  speak the typed Transit UDS protocol
  ([design.md:26](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:26),
  [C1:54](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:54),
  [uds.cljs:1](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:1),
  [uds.cljs:639](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:639)).
- WP-S2 is grounded in runtime-neutral argv/environment/dependency/readiness/
  artifact specifications plus POSIX owner/anchor/workload containment
  ([process.clj:52](/Users/sean/src/seon/script/seon/dev/process.clj:52),
  [process.clj:76](/Users/sean/src/seon/script/seon/dev/process.clj:76),
  [WP-S grounding:54](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md:54)).
- The current managed `host` member is still source-checkout/JVM-specific: its
  argv is `clojure -M:writer:host -m seon.host`, and packaged targets omit it;
  P3 must replace or generalize that member rather than claiming zero wiring
  work
  ([process.clj:556](/Users/sean/src/seon/script/seon/dev/process.clj:556),
  [process.clj:205](/Users/sean/src/seon/script/seon/dev/process.clj:205),
  [process.clj:2604](/Users/sean/src/seon/script/seon/dev/process.clj:2604)).

## What WP-S2 actually proves

| Property | Evidence | P3 consequence |
|---|---|---|
| Arbitrary workload | The process spec is argv/env/dependency/readiness/artifact data with no JVM field ([process.clj:52](/Users/sean/src/seon/script/seon/dev/process.clj:52)); the anchor launches the supplied argv in a new POSIX session ([detach.py:186](/Users/sean/src/seon/script/seon/dev/detach.py:186), [detach.py:280](/Users/sean/src/seon/script/seon/dev/detach.py:280)). | A Bun argv transfers cleanly once the managed member and artifact identity are rewired. |
| Exact identity | Each record carries generation plus owner, anchor, workload, process-group, and start-instant identity ([process.clj:76](/Users/sean/src/seon/script/seon/dev/process.clj:76)); convergence checks argv, environment, artifact digest, liveness, and readiness ([process.clj:1174](/Users/sean/src/seon/script/seon/dev/process.clj:1174)). | Worker replacement can target an exact generation rather than a reused PID. |
| Workload death | The anchor observes workload exit and TERM→KILLs its process group ([detach.py:225](/Users/sean/src/seon/script/seon/dev/detach.py:225), [detach.py:242](/Users/sean/src/seon/script/seon/dev/detach.py:242)); the live close drill observed ECONNREFUSED, a new generation, and successful later invocations ([program:881](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:881)). | This is recovery **after** worker death, not a deadline actor that kills a still-live worker. |
| Owner death | Exact surviving anchor/workload identity is classified as `orphaned-workload`, reaped with graceful then forced termination, and replaced ([process.clj:1319](/Users/sean/src/seon/script/seon/dev/process.clj:1319), [process.clj:1621](/Users/sean/src/seon/script/seon/dev/process.clj:1621), [program:887](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:887)). | The second proven kill mode is **owner death**, not per-invocation cancellation. |
| Anchor-only death | The regression reports `containment-uncertain` and makes stop/ensure fail closed while a workload may remain ([process_test.clj:2335](/Users/sean/src/seon/test/seon/dev/process_test.clj:2335)). | “Both kill modes” must not be expanded to “every containment-member failure.” |
| Readiness | Operator readiness is containment liveness plus raw UDS connectability ([process.clj:830](/Users/sean/src/seon/script/seon/dev/process.clj:830)); the per-agent session separately validates protocol, agent, build, artifact, and database identity ([host.cljs:401](/Users/sean/src/seon/src/seon/execution/host.cljs:401)). | P3 must retain the semantic ready exchange; an accepting socket is insufficient. |
| Limits | The process schema has readiness and shutdown timeouts but no CPU/RSS/heap ceiling ([process.clj:52](/Users/sean/src/seon/script/seon/dev/process.clj:52)); q18 OOME containment remains queued after WP-S2 ([program:893](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:893)). | Separate process blast-radius containment transfers, but resource bounding and OOME recovery are not already proved. |

WP-S2 supplies process physics only. It does not create sci contexts, replay a
corpus, record eval receipts, classify/replay effects, instrument vars, capture
output, enforce the run fence, or publish cross-agent namespaces; those owners
live in the execution/runtime/database paths
([wps-supervision-grounding:228](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md:228),
[host/eval.clj:255](/Users/sean/src/seon/src/seon/host/eval.clj:255),
[runtime/recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351),
[host/context.clj:732](/Users/sean/src/seon/src/seon/host/context.clj:732)).

## Original JVM-host rationale matrix

### R1. One shared base and cheap private contexts

The original economic case was to delete 180–416 MB per-agent children and
replace them with one loaded base plus cheap `sci/fork` contexts; C1 measured
117.9 KB of working marginal heap per JVM context at N=100
([child feasibility:166](/Users/sean/src/seon/docs/prds/source-cleanup/research/sci-execution-child-feasibility-2026-07-20.md:166),
[design.md:38](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:38),
[C1:110](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:110)).
`sci/fork` is a cross-platform public operation, so sharing is not a JVM
property; the measured JVM marginal cannot be asserted for JavaScriptCore
without an equivalent Bun N=100 measurement
([sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318),
[C1:131](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:131)).

**Verdict: TRANSFERS-CLEAN**, with Bun-worker N=100 footprint as acceptance
evidence rather than an architectural prerequisite.

### R2. Deadline interruption with healthy bystanders

The JVM proof uses a scheduled watchdog, `Thread/currentThread`, `.interrupt`,
`Thread/isInterrupted`, SCI's private interrupt marker, and
`Thread/interrupted` cleanup; it stopped 10/10 loops while 90/90 sibling evals
remained healthy
([host/invoke.clj:96](/Users/sean/src/seon/src/seon/host/invoke.clj:96),
[host/invoke.clj:30](/Users/sean/src/seon/src/seon/host/invoke.clj:30),
[host/context.clj:1386](/Users/sean/src/seon/src/seon/host/context.clj:1386),
[host/invoke.clj:152](/Users/sean/src/seon/src/seon/host/invoke.clj:152),
[C1:136](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:136)).
SCI's polling and private marker transfer to CLJS, so interpreted loops can
poll a synchronous deadline, but SCI explicitly has no CLJS in-thread regex
interrupt and cannot inject polling into arbitrary host calls
([sci/core.cljc:288](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:288),
[SCI interrupt:32](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:32),
[SCI interrupt:106](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:106),
[crashproof:180](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:180)).

**Verdict: BLOCKS-P3 in the current mechanism; DEGRADED-BUT-ACCEPTABLE after
the explicit deadline→exact-generation kill bridge.** The accepted degradation
is whole-worker kill, interrupted-receipt recovery, respawn, and replay of
previously committed definitions; unlike JVM thread interruption, it discards
all warm contexts and concurrent invocations in that worker
([host.cljs:1229](/Users/sean/src/seon/src/seon/execution/host.cljs:1229),
[host.cljs:1266](/Users/sean/src/seon/src/seon/execution/host.cljs:1266),
[recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351)).

### R3. Pooled concurrency, fairness, and bystander isolation

The design explicitly used pooled-thread eval, and C1's N=100 wave used ten
workers while its hostile wave used twenty threads
([design.md:64](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:64),
[C1:110](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:110),
[C1:136](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:136)).
A single Bun event loop does not inherit that proof, and process-killing one
shared worker correlates every resident context's loss; this is an inference
from the single workload identity and JVM thread-pool evidence
([process.clj:76](/Users/sean/src/seon/script/seon/dev/process.clj:76),
[C1:138](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:138)).

**Verdict: DEGRADED-BUT-ACCEPTABLE**, provided P3 separately proves N=100
queue fairness, throughput, async progress, and correlated worker-kill
recovery. “Same artifact” proves surface identity, not concurrency parity.

### R4. Heap and native-failure containment

C1 observed 20/20 JVM OOME survival and healthy siblings, but explicitly said
that shared-heap delivery is evidence rather than a guarantee; the later
dependency audit classifies reliable OOME, uncooperative host calls, and
native/JNI failure at the respawn/process boundary
([C1:157](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:157),
[C1:174](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:174),
[crashproof:183](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:183)).
A separate supervised Bun address space protects the pod and writer from worker
death, but WP-S2 provides no memory ceiling and q18 remains unproved
([process.clj:52](/Users/sean/src/seon/script/seon/dev/process.clj:52),
[program:893](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:893)).

**Verdict: DEGRADED-BUT-ACCEPTABLE.** The worker is the blast radius; P3 must
not claim in-process OOME survival or bounded RSS, and q18 remains a graduation
drill.

### R5. Disposable contexts reconstructed from database facts

The design defines a durable agent as corpus/plan/transcript/memory facts and a
context as a disposable cache restored by fork plus definition replay
([design.md:38](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:38),
[design.md:48](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:48)).
The dependency audit reaches the same portable path—fork, recreate private
namespaces, replay definitions, lazy-load required namespaces—and rejects
process-local contexts as durable authority
([crashproof:165](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:165),
[crashproof:170](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:170)).

**Verdict: TRANSFERS-CLEAN.** Supervisor restart alone is insufficient; the Bun
worker must run the corpus reconstruction owner before admitting work
([WP-S grounding:228](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md:228)).

### R6. Writer/database access and removal of Promise ceremony

The JVM host measured roughly 2 ms blocking UDS database calls and let agent
code call them synchronously
([C1:203](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:203),
[program:466](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:466)).
Bun's existing UDS client returns Promises, while the owner ruling assigns the
async/sync difference to P1's platform leaf and keeps portable logic above it
([uds.cljs:303](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:303),
[program:966](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:966)).

**Verdict: DEGRADED-BUT-ACCEPTABLE until P1/P2 complete; TRANSFERS-CLEAN at the
protocol boundary.** P3 retains the same writer semantics over UDS but does not
inherit a blocking implementation. Writer locality is not a rationale
([design.md:26](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:26),
[C1:210](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:210)).

### R7. Capabilities, Java/heavy compute, and package hosts

The original design placed Java/heavy compute behind allowlisted host binding
tables and npm/JS behind a pod capability server
([design.md:74](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:74)).
The later package ruling instead makes Maven and npm work separate disposable
package hosts and keeps third-party native code out of the core sci host
([program:361](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:361),
[program:508](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:508)).

**Verdict: TRANSFERS-CLEAN** for capability routing through P1. Direct Java
class bindings require a JVM leaf, but they are an optional capability/package
host concern rather than a reason the core execution worker must be JVM
([design.md:76](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:76),
[program:982](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:982)).

### R8. JVM bytecode/HotSpot graduation

Tier 1 explicitly compiles corpus source through JVM `eval` to bytecode and
HotSpot; the landed implementation calls `clojure.core/eval`, installs a
compiled root after differential tests, and measured a 1.661× speedup
([design.md:95](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:95),
[host/graduate.clj:170](/Users/sean/src/seon/src/seon/host/graduate.clj:170),
[host/graduate.clj:255](/Users/sean/src/seon/src/seon/host/graduate.clj:255),
[roadmap.md:543](/Users/sean/src/seon/docs/prds/sci-execution-runtime/roadmap.md:543)).
CLJS sci has its own JIT/var-epoch path, but that is not the JVM graduated root
([sci-routing:123](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/sci-routing-seam-2026-07-20.md:123),
[jit.cljs:736](/Users/sean/src/seon/reference-code/sci/src/sci/impl/jit.cljs:736)).

**Verdict: DEGRADED-BUT-ACCEPTABLE.** Bun keeps nursery SCI and may use SCI's
CLJS JIT; persisted graduated identities must explicitly fall back to nursery
or route to the optional JVM upgrade. P3 must not claim U3 transferred.

### R9. Instrumentation and binding-table isolation

The host's semantic instrumentation uses Malli `m/-instrument`, SCI vars,
`sci/alter-var-root`, and watches; Malli and SCI provide these mechanisms in
`.cljc`
([host/instrument.clj:91](/Users/sean/src/seon/src/seon/host/instrument.clj:91),
[malli/core.cljc:3110](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:3110),
[sci/lang.cljc:71](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:71),
[sci/core.cljc:249](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:249)).
The concrete host uses `ReentrantReadWriteLock`, which a Bun port must replace
with serialized generation admission or an async mutex around await boundaries
([host/instrument.clj:13](/Users/sean/src/seon/src/seon/host/instrument.clj:13),
[host/instrument.clj:19](/Users/sean/src/seon/src/seon/host/instrument.clj:19)).

**Verdict: TRANSFERS-CLEAN semantically.** The lock implementation does not;
P3 must preserve the old-old/new-new generation barrier proved by the existing
tests
([host_instrument_writer_test.clj:174](/Users/sean/src/seon/test/seon/host_instrument_writer_test.clj:174)).

### R10. Receipts, corpus tee, effect replay, and run fencing

Running receipts are committed before evaluation; Bun's current engine already
terminalizes receipts and tees successful source/analyzer changes, while the
host record owner states that it mirrors the Bun child's authoritative data
shape
([host/eval.clj:255](/Users/sean/src/seon/src/seon/host/eval.clj:255),
[eval.cljs:2957](/Users/sean/src/seon/src/seon/eval.cljs:2957),
[eval.cljs:3096](/Users/sean/src/seon/src/seon/eval.cljs:3096),
[host/record.clj:1](/Users/sean/src/seon/src/seon/host/record.clj:1)).
Writer effect receipts map capability `op-id` to durable protocol request IDs,
but effect-classified recovery is assigned to P1/P4 rather than implemented by
WP-S2 or W3
([host/context.clj:732](/Users/sean/src/seon/src/seon/host/context.clj:732),
[program:966](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:966),
[program:989](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:989),
[program:1674](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1674)).

**Verdict: TRANSFERS-CLEAN as data/protocol contracts, but not automatically.**
P3 must reuse the existing receipt, tee, recovery, run-fence, and effect-receipt
owners; “same artifact” does not make supervisor process records substitutes
for them
([runtime/recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351),
[host/eval.clj:198](/Users/sean/src/seon/src/seon/host/eval.clj:198),
[process.clj:76](/Users/sean/src/seon/script/seon/dev/process.clj:76)).

## W3 transfer audit

### W3a — interrupt and output closure

**Verdict: DEGRADED-BUT-ACCEPTABLE after the P3 kill bridge; currently part of
the BLOCKS-P3 gate.** The JVM-only bindings are `Thread/currentThread`,
`.interrupt`, `.isInterrupted`, `Thread/interrupted`, `ScheduledExecutorService`,
and `java.io.Writer`; NIO `SocketChannel` is transport/session machinery rather
than interrupt semantics
([host/invoke.clj:12](/Users/sean/src/seon/src/seon/host/invoke.clj:12),
[host/invoke.clj:30](/Users/sean/src/seon/src/seon/host/invoke.clj:30),
[host/invoke.clj:96](/Users/sean/src/seon/src/seon/host/invoke.clj:96),
[host/eval.clj:90](/Users/sean/src/seon/src/seon/host/eval.clj:90),
[host/eval.clj:154](/Users/sean/src/seon/src/seon/host/eval.clj:154)).
Bun equivalents are a synchronous deadline checked by SCI `:interrupt-fn`,
the existing Transit UDS stream, and a bounded per-invocation print sink; there
is no Bun in-thread equivalent for a running JS regex or arbitrary native call
([sci/core.cljc:288](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:288),
[uds.cljs:180](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:180),
[SCI interrupt:106](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:106)).
The current Bun print bucket is correctly attributed through
`AsyncLocalStorage` but accumulates with unbounded `swap! ... str`; P3 would
silently lose W3a's print-flood containment if it copied that sink unchanged
([eval.cljs:348](/Users/sean/src/seon/src/seon/eval.cljs:348),
[eval.cljs:389](/Users/sean/src/seon/src/seon/eval.cljs:389),
[host/eval.clj:90](/Users/sean/src/seon/src/seon/host/eval.clj:90)).

### W3b — host instrumentation

**Verdict: TRANSFERS-CLEAN.** Malli instrumentation, SCI vars/watches,
`alter-var-root`, fork, and Transit-safe error envelopes are portable; only the
JVM `ReentrantReadWriteLock` implementation does not transfer
([malli/core.cljc:2276](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2276),
[sci/lang.cljc:148](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:148),
[sci/core.cljc:249](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:249),
[error/instrument.cljc:199](/Users/sean/src/seon/src/seon/error/instrument.cljc:199),
[host/instrument.clj:13](/Users/sean/src/seon/src/seon/host/instrument.clj:13)).
The Bun implementation must retain the projection-generation publication
barrier rather than duplicate the CLJ lock shape
([host_instrument_writer_test.clj:174](/Users/sean/src/seon/test/seon/host_instrument_writer_test.clj:174)).

### W3c1 — host run fence

**Verdict: TRANSFERS-CLEAN.** The mechanism is one ordinary
`:db.fn/cas` assertion at the invocation database value before context setup,
receipt creation, or evaluation; losing the fence returns a fenced result
without running agent code
([host/session.clj:55](/Users/sean/src/seon/src/seon/host/session.clj:55),
[host/eval.clj:198](/Users/sean/src/seon/src/seon/host/eval.clj:198),
[host/eval.clj:255](/Users/sean/src/seon/src/seon/host/eval.clj:255),
[host/eval.clj:288](/Users/sean/src/seon/src/seon/host/eval.clj:288)).
Bun awaits the same writer UDS transaction; no Thread, NIO, or JVM class is
part of the fence contract
([uds.cljs:303](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:303),
[uds.cljs:639](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:639)).

### W3c2 — host repair/preflight

**Verdict: TRANSFERS-CLEAN.** Repair/reparse plus disposable `sci/fork` analysis
leave the retained context untouched and run only after the running receipt
commits
([host/preflight.clj:48](/Users/sean/src/seon/src/seon/host/preflight.clj:48),
[host/preflight.clj:97](/Users/sean/src/seon/src/seon/host/preflight.clj:97),
[host/eval.clj:315](/Users/sean/src/seon/src/seon/host/eval.clj:315)).
Replace `System/nanoTime` with `performance.now`/`Date.now`, and JVM
`Throwable.getCause` walking with CLJS exception data/cause walking; SCI `Var`
itself has a CLJS implementation
([host/preflight.clj:117](/Users/sean/src/seon/src/seon/host/preflight.clj:117),
[host/preflight.clj:222](/Users/sean/src/seon/src/seon/host/preflight.clj:222),
[sci/lang.cljc:71](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:71)).

### W3d1 — authored invocation

**Verdict: DEGRADED-BUT-ACCEPTABLE.** Digest-pinned SCI invocation transfers:
the host verifies source at the request's immutable database, materializes a
stale version in a detached fork, and invokes under the originating SCI context
([host/context.clj:1532](/Users/sean/src/seon/src/seon/host/context.clj:1532),
[host/context.clj:1571](/Users/sean/src/seon/src/seon/host/context.clj:1571),
[host/invoke.clj:37](/Users/sean/src/seon/src/seon/host/invoke.clj:37),
[ctx_store.cljc:38](/Users/sean/src/seon/reference-code/sci/src/sci/ctx_store.cljc:38)).
The native graduated root does not transfer because it binds JVM `*ns*`, calls
`clojure.core/eval`, and installs a native Clojure Var
([host/graduate.clj:170](/Users/sean/src/seon/src/seon/host/graduate.clj:170),
[host/graduate.clj:255](/Users/sean/src/seon/src/seon/host/graduate.clj:255)).

### W3d2 — cross-agent live require

**Verdict: TRANSFERS-CLEAN within one worker.** Shared SCI vars are updated by
`alter-var-root`, exact vars are linked with `sci/add-namespace!`, and a registry
miss materializes stored namespace source and its dependency closure
([host/context.clj:851](/Users/sean/src/seon/src/seon/host/context.clj:851),
[host/context.clj:900](/Users/sean/src/seon/src/seon/host/context.clj:900),
[host/context.clj:910](/Users/sean/src/seon/src/seon/host/context.clj:910)).
The JVM `:load-fn` can do a blocking UDS query, but Bun UDS returns a Promise
and SCI `:load-fn` is synchronous; Bun must pre-acquire namespace sources and
require edges asynchronously, then serve `:load-fn` from an in-memory closure
([uds.cljs:303](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:303),
[execution.cljs:669](/Users/sean/src/seon/src/seon/execution.cljs:669),
[execution.cljs:756](/Users/sean/src/seon/src/seon/execution.cljs:756)).
If P3 later uses multiple workers, process-shared SCI vars no longer provide
cross-worker visibility; P4 must use database/feed invalidation or per-invocation
acquisition, as inferred from the current in-process registry owner
([host/context.clj:851](/Users/sean/src/seon/src/seon/host/context.clj:851),
[program:989](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:989)).

### Explicit non-transfers

- `Thread/currentThread`, `.interrupt`, `.isInterrupted`, and
  `Thread/interrupted` have no same-process Bun equivalents; ordinary SCI loops
  use deadline polling, while uncooperative code requires worker process kill
  ([host/invoke.clj:30](/Users/sean/src/seon/src/seon/host/invoke.clj:30),
  [host/invoke.clj:96](/Users/sean/src/seon/src/seon/host/invoke.clj:96),
  [SCI interrupt:106](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:106)).
- JVM NIO `ServerSocketChannel`/`SocketChannel` is replaced by the existing Bun
  Transit UDS transport; NIO is not a semantic W3 contract
  ([host.clj:15](/Users/sean/src/seon/src/seon/host.clj:15),
  [host.clj:60](/Users/sean/src/seon/src/seon/host.clj:60),
  [uds.cljs:180](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:180)).
- `java.io.Writer`, `System/nanoTime`, `Throwable.getCause`, and
  `ReentrantReadWriteLock` need Bun-specific leaves or serialization, as
  described in W3a–W3c2 above
  ([host/eval.clj:90](/Users/sean/src/seon/src/seon/host/eval.clj:90),
  [host/preflight.clj:117](/Users/sean/src/seon/src/seon/host/preflight.clj:117),
  [host/preflight.clj:222](/Users/sean/src/seon/src/seon/host/preflight.clj:222),
  [host/instrument.clj:13](/Users/sean/src/seon/src/seon/host/instrument.clj:13)).
- No W3 runtime mechanism uses `io-prepl`; its concrete uses are writer REPL/
  MCP development surfaces, not the execution protocol
  ([mcp.clj:1092](/Users/sean/src/seon/script/seon/dev/mcp.clj:1092),
  [db/server.clj:474](/Users/sean/src/seon/src/seon/db/server.clj:474)).

## Silent losses P3 must make explicit

1. **Worker-wide volatile-state loss.** A hard kill drops all warm contexts and
   concurrent invocations in that worker; only facts and previously committed
   corpus definitions reconstruct
   ([design.md:48](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:48),
   [crashproof:165](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/crashproof-feasibility-2026-07-22.md:165)).
2. **No replay of the killed form.** Recovery CAS-closes running eval receipts
   as interrupted; the WP-S2 proof sent the killed invocation once and used a
   later invocation after respawn
   ([runtime/recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351),
   [host_test.cljs:1199](/Users/sean/src/seon/test/seon/execution/host_test.cljs:1199)).
3. **Crash window after SCI return.** A kill after the form returns but before
   terminal recording leaves the pre-run `:running` receipt for recovery, but
   no terminal output/corpus tee from that form; terminal recording occurs
   after evaluation
   ([host/eval.clj:122](/Users/sean/src/seon/src/seon/host/eval.clj:122),
   [host/eval.clj:407](/Users/sean/src/seon/src/seon/host/eval.clj:407),
   [runtime/recovery.cljs:351](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:351)).
4. **Effects are not made safe by process supervision.** Writer operations with
   durable op-id/request-id receipts can return recorded outcomes; external
   non-idempotent effects remain ambiguous until P1 effect classes and P4
   recovery land
   ([host/context.clj:732](/Users/sean/src/seon/src/seon/host/context.clj:732),
   [program:966](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:966),
   [program:989](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:989)).
5. **Output flood containment.** Existing Bun attribution is useful but its
   accumulation is unbounded; P3 must carry W3a's bounded capture sink
   ([eval.cljs:348](/Users/sean/src/seon/src/seon/eval.cljs:348),
   [eval.cljs:389](/Users/sean/src/seon/src/seon/eval.cljs:389),
   [host/eval.clj:90](/Users/sean/src/seon/src/seon/host/eval.clj:90)).
6. **Graduated roots.** Existing U3 JVM roots do not execute in Bun and need an
   explicit nursery fallback or optional-JVM routing policy
   ([host/graduate.clj:170](/Users/sean/src/seon/src/seon/host/graduate.clj:170),
   [roadmap.md:543](/Users/sean/src/seon/docs/prds/sci-execution-runtime/roadmap.md:543)).
7. **Cross-agent visibility is process-scoped today.** One shared worker can
   reuse the W3d2 SCI-var registry; a worker pool needs database/feed-driven
   invalidation rather than assuming shared roots
   ([host/context.clj:851](/Users/sean/src/seon/src/seon/host/context.clj:851),
   [host/context.clj:900](/Users/sean/src/seon/src/seon/host/context.clj:900)).

## Final ruling for P1/P3

The ledger supports the owner rewrite: JVM placement was a chosen way to obtain
cheap shared contexts, blocking database calls, thread interruption, pooled
parallelism, and HotSpot graduation; only the last four concrete
implementations are JVM-specific, not the disposable-host architecture
([design.md:38](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:38),
[design.md:64](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:64),
[design.md:95](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:95),
[C1:203](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20.md:203)).

P1 may proceed; it should not preserve JVM-shaped blocking or wrapper APIs
above the capability leaf. P3 may proceed to implementation only with these
explicit gates, each tied to evidence above:

1. deadline/cancel → exact managed Bun generation drain/force → observed death;
2. running-receipt interruption recovery plus reconstruction from committed
   corpus facts, without replaying the dead form;
3. N=100 memory, throughput, fairness, and correlated-kill drill;
4. bounded output capture and the complete W3 parity suite;
5. explicit nursery fallback/optional-JVM routing for graduated roots; and
6. q18 OOME plus the later U12 kill-anything graduation drill
   ([program:893](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:893),
   [program:989](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:989)).

Thus the answer is: **the original JVM-host rationale does not structurally
block P3, but the current WP-S2/P3 composition does not yet supply hot-loop
process preemption. That missing bridge is BLOCKS-P3; once supplied, the
remaining JVM losses are explicit, measurable DEGRADED-BUT-ACCEPTABLE items.**
