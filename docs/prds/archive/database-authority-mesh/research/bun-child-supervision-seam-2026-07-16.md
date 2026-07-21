---
type: research
status: complete
tags: [research, prd, agent, flow]
---

# Bun child supervision seam — 2026-07-16

## Decision

Replace the shared in-process agent host with one Bun cluster host and one
Bun.spawn child for each agent that is currently doing work. The cluster host
owns Bun.serve, the one cluster ticker, child-count and inference admission,
child control IPC, logs, and terminal classification. Each child owns exactly
one agent's compiler/eval/LLM/tool state and one direct native database session.
No database request or result passes through Bun IPC.

The important improvement over a literal process extraction is where waking
lives. The cluster host, not every durable agent, owns one gap-safe selective
database interest for facts that can require an agent process. It derives the
affected :seon.agent/id, starts a child only when one is needed, and sends a
small control message when that child is already running. The child then reads
the exact current database value through its direct authority session. This
keeps dormant agents process-free, removes the current create-before-listener
race, and sends one committed trigger to one host instead of broadcasting it to
all children.

Use Bun IPC only for bounded lifecycle messages: start, ready, database-changed,
inference admission, cancel, quiesce, and terminal evidence. Use ordinary
namespaced data and the existing :seon.db.protocol/request-id. The durable child
identity remains :seon.agent/id; an operating-system PID is evidence, not
identity. The supervisor rejects a late callback by comparing the callback's
actual Subprocess object with the one currently stored for that agent, so it
does not need another durable generation name.

Do not use Bun.spawn's timeout as the supervision policy, do not use
Subprocess.killed to infer why a process exited, do not set detached true, and
do not use unref. Keep children in the outer containment tree, request a
cooperative stop over IPC, then signal TERM and finally KILL after one bounded
grace period. Enable Bun's no-orphans mode as a second parent-loss backstop, but
retain Seon's existing process-group containment as the primary outer inverse.

## Dependency ledger

- Seon checkout reviewed at d3eca240 plus concurrent uncommitted Unit 5 work;
  this report does not depend on or edit that work.
- Bun be77b652884b16a103cfaa4af3c1102f72f2dcd3, executed with Bun 1.3.14
  on macOS arm64:
  packages/bun-types/bun.d.ts:6714-6736,6783-7021,7191-7318;
  src/runtime/api/bun/js_bun_spawn_bindings.rs:350-410,568-694,747-790,
  1503-1593,1632-1694;
  src/runtime/api/bun/subprocess.rs:367-433,647-815,888-1136,1312-1438;
  src/jsc/ipc.rs:828-927,1096-1151,1245-1385;
  src/runtime/ipc_host.rs:73-185;
  src/jsc/ProcessAutoKiller.rs:17-105; and
  src/io/ParentDeathWatchdog.rs:1-33,202-287,290-362.
- Bun's retained parent-loss fixtures:
  test/cli/run/no-orphans.test.ts:1-225.
- Babashka process 16a84e0af0da51b8c84e289970f6b7cc35b35d18
  (v0.6.25): src/babashka/process.cljc:117-173,367-453,675-710.
- Current outer owner: script/seon/dev/process.clj:703-830,1138-1230 and
  script/seon/dev/detach.py. It already records PID plus operating-system start
  instant, owns a process group, accepts a generation-fenced drain, and reports
  clean/forced terminal evidence.
- Current in-process owners:
  src/seon/client.cljs:2311-2337,2583-2847,3013-3235;
  src/seon/agent/runtime.cljs;
  src/seon/agent/loop.cljs:109-123,401-542,662-800; and
  src/seon/agent.cljs:330-353,592-746.
- Settled direct data plane: src/seon/db/transport/uds.cljs and
  [[selector-session-source-proof-2026-07-16]].
- Prior evidence:
  [[bun-jvm-parallel-transport-2026-07-15]],
  [[direct-vs-broker-density-2026-07-15]],
  [[shadow-bun-runtime-internals-2026-07-16]], and
  [[../../bun-native-runtime-simplification/research/removal-first-integration-audit-2026-07-15]].

## What Bun actually provides

Bun.spawn is the stronger interface than Bun's node:child_process adapter, but
it is a primitive rather than the whole policy. It accepts an argv vector,
explicit cwd/environment/stdio, a Bun-to-Bun IPC callback, abort signal,
timeout, kill signal, output maximum, and exit callback. The returned
Subprocess exposes its PID, exited Promise, exit and signal code, stdio, send,
disconnect, kill, ref/unref, and post-exit resourceUsage.

The implementation establishes two constraints that should shape Seon:

- onExit may run before Bun.spawn returns. Bun's declaration warns of this, and
  the implementation installs the callback before process watching. Publish
  the child in the supervisor's map, then attach to proc.exited; a resolved
  Promise callback runs after the current stack. Treat onDisconnect as an early
  observation only because Bun permits IPC disconnect and exit in either order.
- Subprocess.send serializes synchronously and appends to a native
  Vec<SendHandle>. Adjacent messages without handles can merge, but there is no
  byte ceiling. The implementation returns true for immediate progress, false
  for backoff after the message was already accepted, and throws on failure;
  the TypeScript declaration currently says void. There is no drain event.
  Seon must allow only small validated control messages, retain at most one
  unanswered control request of each required class per child, and treat false
  as accepted-with-pressure rather than retrying a duplicate.

This is why IPC must not carry database results, logs, prompt bodies, or model
streams. The direct authority socket already has framing, request IDs, byte
bounds, backpressure, cancellation, and independent failure cleanup. Repeating
those mechanisms over IPC would be a broker and an unbounded second queue.

## The process arrangement

One active cluster has these operating-system owners:

    Babashka operator containment
      JVM/Datahike authority (shared across clusters)
      Bun cluster host
        Bun child for active agent A -> direct authority UDS
        Bun child for active agent B -> direct authority UDS
        Bun child for active agent C -> direct authority UDS

The authority is not a child of any one cluster host. An agent-child crash
therefore closes only its IPC channel and database session. It does not signal
the parent, siblings, another cluster host, or the JVM. Prior executable
evidence completed four 500 ms CPU loops in about 515 ms, and a SIGABRT child
did not stop its sibling or parent. Separate JSC processes provide the
multi-core execution that the current one event loop cannot.

A cluster-host loss intentionally takes down that host's children. Keeping
them alive would leave inference permits, logs, artifact identity, control
requests, and recovery ownership with no owner. The Babashka containment
restarts or retires the host as one outer process. Bun no-orphans mode closes
the SIGKILL/reparenting gap on Linux with PR_SET_PDEATHSIG and on macOS with an
EVFILT_PROC parent watch; its clean-exit callback also kills descendants. The
mode is opt-in through --no-orphans, BUN_FEATURE_FLAG_NO_ORPHANS=1, or Bun
config, so the packaged host must select it explicitly and the release gate
must prove it with the exact shipped Bun.

Keep agent children non-detached and referenced. detached true calls setsid and
is designed to let a child outlive its parent; unref lets the event loop exit
without waiting. Both oppose Seon's ownership contract. Bun's lower spawn layer
already contains a useful new_process_group option, but it is marked “Not
exposed to JS yet.” If individual agent grandchildren survive a forced child
KILL in proof, the smallest dependency improvement is to expose that existing
option and group signal through Bun rather than add a Seon process-tree scanner.
Do not patch Bun preemptively: cooperative child cleanup plus the existing outer
group may pass.

## Gap-safe waking and zero-process dormant agents

The current topology resumes every nonterminated agent at cluster boot and
installs one transaction listener per agent. start! must also resume the new
agent before its first message because a reactive-only listener cannot recover
a message committed before listener installation. That mechanism conflicts
with process-free dormant agents.

Replace it at the existing database-reactive seam:

1. The cluster host opens its direct authority session and registers one
   selective interest for message targets, agent termination/pause/resume
   changes, and schedule facts. Source-before-ack and coordinate-pinned reads
   close the install race.
2. At the interest's source coordinate, derive the existing facts that require
   work: a waking inbound newer than the agent's last action, a due schedule,
   or an explicitly resumable open run from a planned transition. Process
   capacity is not a database fact.
3. A committed event identifies the affected agent from existing attributes.
   If its child exists, send one database-changed control message. Otherwise,
   start it when child capacity is available.
4. When capacity becomes available, query current facts again and choose the
   oldest existing trigger. Do not retain another in-memory work queue and do
   not add pending, seen, or acknowledged facts.
5. The child opens its own direct authority session, resolves exact current
   facts, and uses the existing CAS run-open/work fences. Duplicate host events
   therefore cannot open two runs or let a stale child commit work.
6. When the database says the agent is idle, paused, or terminated and all its
   owned requests are settled, the child closes its session, sends terminal
   evidence, and exits. The durable agent remains in Datahike with no process,
   listener, compiler state, socket, or supervisor entry.

This permits a simpler birth path. start! mints the idle agent and returns; it
does not create process state. delegate! writes the task normally and the
host's gap-safe interest observes it. The current mint-then-runtime/resume!
sequence, boot-wide resumable-agent-ids host pass, per-agent listener map, and
create-before-message explanation become deletable.

The cluster host is a control router here, not a database broker. It receives a
small addressed committed trigger once and sends a small child nudge. The child
queries, pulls, transacts, pages, and cancels directly. The alternative—one
host interest plus one interest in every active child—duplicates delivery and
creates a handoff race without adding information.

## Child start and terminal contract

Use a dedicated Shadow :node-script-shaped agent-child artifact executed by
Bun, alongside the cluster-host artifact. These are two process roles in one
system, not old and new runtime paths. A single dispatcher artifact would make
every child load the web host graph and prevent Closure from removing it; that
works as a falsifier but is the wrong density target.

Spawn argv contains only the selected Bun executable, child artifact, and role.
After Bun.spawn returns, the host stores the exact Subprocess, starts
stdout/stderr drains, and sends one namespaced start map containing the existing
request ID, agent ID, database assignment, artifact digest, and non-secret
capability data. The child does no agent work before that message. Its ready
reply means its direct database session, exact program projection, home
namespace, and cancellation owner are usable—not merely that JavaScript loaded.

For every exit, the host combines rather than orders these observations:

- the last accepted control request and its reason;
- ready, quiesced, or terminal IPC evidence, when delivered;
- IPC disconnect;
- proc.exited, exitCode, and signalCode;
- fully drained stdout and stderr; and
- post-exit resourceUsage.

Use proc.exited as the exit authority. A local Bun 1.3.14 probe observed IPC,
disconnect, then exit for one child, but Bun's documented order is deliberately
unspecified. The same probe showed Subprocess.killed true after an ordinary exit
code 7, so that property cannot identify a supervisor kill. Record the
supervisor's cancel/TERM/KILL decision before signaling and report it with the
real exit and signal fields.

## Logs and output pressure

Give stdout and stderr pipe and consume both immediately with the one planned
incremental stream-pump implementation. Prefix records with the durable agent
ID and current PID, write them to the cluster host's existing log destination,
bound a single line and retained diagnostic tail, and discard retained bytes
after terminal publication. Never call text on a long-lived stream and never
leave a pipe unread; Bun keeps reading after direct-child exit because a
grandchild can still hold the pipe open.

Terminal evidence waits for proc.exited and both pump completions, with a
bounded drain deadline. This preserves late output without letting a leaked
grandchild prevent cleanup forever. Do not use Bun's maxBuffer as the logging
bound. It kills with one signal, buffers inside the subprocess implementation,
and prior installed-Bun evidence overshot a 1,000-byte maximum to 532,350 bytes.

The same pump should replace Seon's foreground shell/search wrappers in the
larger Bun cut. Agent supervision supplies attribution and retention policy;
it should not create a second byte-pump implementation.

## Cancellation, timeout, and restart policy

One request identity follows an operation through control and direct database
cancellation. The host sends cancel for that request; the child aborts the LLM
attempt, sends the existing authority cancel for admitted database work, stops
accepting a new turn, closes its direct session after settlement, and replies
with ordinary data. Session close is the final cleanup: it releases interests,
pending reads, queued frames, and attachment ownership; an accepted mutation
still resolves by its durable receipt and is never described as rolled back.

If cooperative cancellation does not finish within the existing run deadline,
the host sends TERM. After one short monotonic grace period it sends KILL and
then applies scoped crash recovery. Do not pass the run deadline as Bun.spawn
timeout: Bun's timeout callback sends only killSignal once. In a local probe, a
child that ignored SIGTERM was still alive after a 30 ms Bun timeout and
required an explicit SIGKILL.

Restart policy follows durable work truth:

- An expected idle, paused, terminated, planned-reload, or planned-quiesce exit
  removes the child and does not create a process until facts require one.
- A failure before ready and before an open run exists may retry child startup
  with the same trigger/request identity under a small bounded exponential
  backoff. Nothing agent-visible ran.
- An abnormal exit after a run opened never replays that run. The host invokes
  the existing scoped crash transaction as root: fence the exact open run,
  mark a running turn interrupted, close it :crashed, retract the pointer, and
  record the existing recovery fact. A later human or agent message may start
  a fresh process and run; the supervisor does not guess.
- A planned artifact replacement quiesces at a turn boundary. When an open run
  is intentionally preserved, the successor child re-drives that exact run
  through its existing CAS fence. It is not classified as a crash.
- Repeated pre-ready failures stop retrying and surface one bounded core error.
  The durable trigger remains queryable; no separate failed-child registry is
  required.

This restarts disposable infrastructure without repeating uncertain effects.

## Parallelism and admission

The host bounds active children independently from the JVM's fair database
workers. It never holds a database permit while waiting for a child or an LLM.
One child per active agent gives separate JSC heaps and event loops, so CPU-bound
evals can overlap across cores. The JVM still serializes writes per Datahike
connection while reads across agents and databases use bounded shared workers.

When all child slots are occupied, no process is spawned and no database write
marks work pending. Existing message, run, and schedule facts are the work
source. On a slot release the host re-queries them and selects the oldest
trigger. This is simpler than a process-local round-robin queue, survives host
restart, and cannot lose a queued agent.

Inference admission is small control-plane state in the host: a child requests
a permit with the existing request ID, makes the provider call itself after the
grant, and releases on completion. Disconnect or exit releases every permit
owned by that exact Subprocess. Database execution remains direct and is not
gatekept by the Bun host.

Start with immediate process release at durable idle. If measured child cold
start exceeds the interactive latency budget, Sean should choose between:

- keeping the simple immediate-release rule and optimizing the child artifact
  and compiler bootstrap; or
- retaining a bounded number of recently idle children for a short measured
  interval, evicting them first under child pressure.

The second option trades memory for conversational latency and should not land
without a distribution, because a timer and warm-child policy are real new
complexity. --smol is likewise a density benchmark arm, not the default: Bun
documents more frequent GC and lower memory at a speed cost.

## Resource measurement and platform limits

resourceUsage is useful only after exit. Its getters are non-enumerable, so
serializing the object itself produces an empty map; read each documented field
explicitly. CPU times may be BigInts even though the declaration says numbers.
A local child performing CPU work reported about 30.4 MB maximum RSS, 28.5 ms
CPU, and 69 involuntary context switches once fields were accessed and BigInts
were normalized.

There is a cross-platform source issue to prove before using maximum RSS as one
unit. Bun's Unix implementation returns raw ru_maxrss. macOS defines that field
in bytes, while Linux defines it in KiB; Bun's type documentation calls the
value bytes and the reviewed Rust accessor performs no Linux scaling. Either
patch the vendored accessor to return bytes consistently or normalize by
platform in the one measurement owner, then retain a Linux fixture. Do not let
each report invent its own conversion.

Bun's public spawn API does not expose a hard RSS or CPU limit. Child-count,
request, output, inference, deadline, and artifact bounds are available now;
Linux cgroup limits and macOS process limits require an outer platform owner or
a Bun dependency improvement. resourceUsage is terminal measurement, not live
enforcement. Unit 8 must not claim hard per-child memory limits until an
executable macOS/Linux proof names the real owner.

## Atomic source replacement

The exclusive implementation cut should strengthen and split the current
owners, then delete the shared-host path in the same branch:

1. Make one native subprocess/stream-pump owner from the already planned
   Bun.spawn replacement. Agent supervision consumes it; shell and search
   retain only capability policy and result interpretation.
2. Add the agent-child Shadow entry artifact and immutable launch identity.
   Keep Shadow's existing server/CommonJS target shape and execute it with Bun.
3. Turn seon.client into the cluster host: cluster boot, web UI, one ticker,
   selective process-demand interest, child admission, Bun IPC, and shutdown.
4. Turn seon.agent.runtime into the one-child owner: direct authority session,
   one agent home namespace and compile state, existing loop, provider/tool
   work, and ordered cleanup. Delete its agent-id-to-input registry because the
   process itself is the scope.
5. Change start! and delegate! so durable birth and message facts are
   sufficient. Delete local runtime/resume! from birth and the boot-wide resume
   pass.
6. Move ticker execution into child dispatch while keeping due and overdue
   derivation in the one host ticker. Do not add a timer per child.
7. Move seon.runtime.recovery from whole-pod-only recovery to the same scoped
   transaction callable for one failed child and for host-loss reconciliation.
8. Delete every per-agent in-host wake listener, shared compiler/eval registry,
   process-local hosted-agent registry, and Node child adapter. Do not leave a
   feature flag or local-agent fallback.
9. Package host and child artifacts with the exact Bun revision and no-orphans
   policy. Babashka continues to own the outer JVM/host process graph; Bun owns
   only its agent children.

## Proof order

Run these proofs in dependency order after a source freeze:

1. **Artifact proof:** host and child release artifacts start under the exact
   packaged Bun with no Node or source checkout; mapped CLJS failures name the
   correct source. Record cold child ready latency and RSS.
2. **Trigger proof:** transact a message before host interest installation,
   during installation, while capacity is full, and while a child is exiting.
   Each target runs once; unrelated agents do not start. An idle durable agent
   has no process, socket, interest, or compiler state.
3. **Parallel proof:** 1, 2, 4, and 8 CPU-bound children overlap according to
   available cores while web p99, authority control, and an unrelated database
   remain bounded. Compare ordinary Bun and --smol.
4. **Child failure proof:** normal exit, thrown error, process.abort, ignored
   TERM, forced KILL, malformed IPC, bounded IPC pressure, database disconnect,
   and output overflow close only that child and session. Authority work and
   inference permits return to zero.
5. **Parent-loss proof:** TERM and SIGKILL the cluster host on macOS and Linux.
   Every child and grandchild becomes absent, the JVM and other cluster hosts
   remain, and recovery closes only interrupted runs. Prove the shipped
   no-orphans behavior rather than trusting a development Bun.
6. **Cancellation proof:** cancel during queued inference, provider fetch,
   Datalog query, output delivery, transaction reply uncertainty, and eval.
   The one request ID explains every outcome and no work is replayed.
7. **Density proof:** at 1, 4, 16, and 32 active children and many dormant
   agents, record host/child/JVM RSS, cold/ready/turn latency, CPU, context
   switches, FDs, IPC counts, direct UDS bytes, GC, and cleanup. Repeat across
   multiple cluster hosts sharing the JVM.
8. **Reachability proof:** no active path resumes all agents in one process,
   uses Node child APIs, brokers database values over IPC, installs one dormant
   listener per agent, or retains a local-agent compatibility mode.

## Consequential tradeoffs for Sean

Two decisions should remain visible rather than be hidden in implementation:

1. **Immediate idle exit versus a small warm set.** Immediate exit is the
   simplest and lowest-memory rule. A warm set may make human follow-ups feel
   faster if compiler/bootstrap cost is material, but adds retained memory and
   eviction policy. Measure the dedicated child artifact first.
2. **Dependency improvement for forced child trees.** Keep children
   non-detached in the first proof. If a forced child KILL can leave its own
   grandchildren until the outer host stops, either expose Bun's existing
   new_process_group control plus group termination or add a native killTree
   operation in the maintained Bun source. Do not solve that with a shell
   wrapper, process-name scan, or detached orphan.

The rest of the seam is not a close call: direct authority sessions beat a Bun
database broker; one cluster-host trigger interest beats per-dormant-agent
processes and listeners; proc.exited beats callback ordering; and explicit
cancel, TERM, then KILL beats Bun's one-signal timeout.

## Safe executable evidence

Only isolated Bun subprocesses ran; no Seon lifecycle, database, build, or test
process was touched.

- Bun 1.3.14 advanced IPC round-tripped one object. send returned true; the
  child exited 7; the parent remained alive. The observed callbacks were IPC,
  disconnect, exit, but the design does not depend on that order.
- The same normally exited child reported killed true, confirming that
  supervisor intent must be latched separately.
- A child that ignored SIGTERM remained alive after timeout 30 and required
  SIGKILL; its final exit was 137 with SIGKILL.
- Explicit post-exit resource getters returned maximum RSS, CPU time, context
  switches, message and IO counters, signals, swaps, and shared-memory fields.
  Serializing the wrapper without touching getters returned an empty map.
- A disposable shell-to-Bun tree with BUN_FEATURE_FLAG_NO_ORPHANS=1 lost its
  Bun child after the shell was SIGKILLed. Bun's retained suite covers Bun and
  non-Bun grandchildren on both supported platforms. The packaged exact binary
  must repeat that proof.
