---
type: research
status: active
tags: [research, agent, architecture]
---

# Host robustness audit — sci execution runtime (2026-07-21)

Read-only audit of the U1–U5 production source on
`codex/runtime-reliability-refactor`: `src/seon/host.clj`,
`src/seon/host/context.clj`, `src/seon/host/record.clj`,
`src/seon/host/graduate.clj`, `src/seon/execution/host.cljs`,
`src/seon/db/transport/uds.{cljc,cljs}`, the writer conformance/registry
tests, and the pinned sci checkout (`reference-code/sci`). Question:
can one agent's eval accidentally take down the host, another agent, or
the cluster, and where does every failure become a `:seon/error` value.

## Summary table

| Mechanism | Classification | Evidence |
|---|---|---|
| Message contract validation (startup/invoke/cancel/shutdown/value-sample) | STRONG | `src/seon/host.clj:102-174,866-1002`; conformance tests `test/seon/host_conformance_writer_test.clj:263-303,570-578` |
| One active invocation per session; second invoke errors `:core-bug` | STRONG | `src/seon/host.clj:797-804`; test `:501` |
| Result byte bounding (`result-limit-bytes` enforced pre-frame) | STRONG (bound), WEAK (transient allocation) | `src/seon/host.clj:370-394`; test `:557` |
| Wire frame cap 4 MB both directions | STRONG | `src/seon/db/protocol.cljc:102`; `src/seon/db/transport/uds.cljc:213-241`, `uds.cljs:19-24,134-159` |
| Deadline → `Thread/interrupt` → `:interrupt-fn` → `sci.interrupt` for sci-interpreted code | STRONG for interpreted loops | `src/seon/host.clj:713-720`; `src/seon/host/context.clj:928-931`; per-iteration check `reference-code/sci/src/sci/impl/fns.cljc:52,77`; test `:580` |
| Interrupt coverage of host-native iteration (`reduce`/`range`/`into`/`re-*` …) | **PoC** | overrides exist (`reference-code/sci/src/sci/interrupt.cljc:289-306`) but are NOT installed — `sci/init` at `src/seon/host/context.clj:926-931` passes no `:namespaces` merge; only `sci.interrupt/interrupt!` is used |
| Eval thread pool | **WEAK** | fixed 10 (`src/seon/host.clj:176,1036-1038`); unbounded submit queue; no `future.cancel` on queued work (`:818-847`) → ghost execution + pool exhaustion by stuck native calls |
| Per-eval memory/allocation bound | **WEAK** | none anywhere; only process-level `-Xmx` + C1's "OOME containment is strong evidence not kill-certainty" (roadmap `c1` section) |
| Cross-agent shared-base var isolation | **PoC** | sci `fork` copies the env atom only (`reference-code/sci/src/sci/core.cljc:318-323`); `eval-def` reuses and `bindRoot`s the pre-existing shared Var object (`reference-code/sci/src/sci/impl/evaluator.cljc:25-46`); `alter-var-root` exposed to evaluated code (`reference-code/sci/src/sci/impl/namespaces.cljc:1476`); registry vars deliberately `identical?` across contexts (`src/seon/host/context.clj:468-474`) |
| Global `seon.schema` registry under concurrent agent evals | **WEAK** | snapshot/diff/restore is process-global, per-form, unsynchronized across pool threads (`src/seon/host.clj:607,616-618`) |
| Shared writer connection + call lock | **WEAK** | one channel, one `call-lock` for all agents (`src/seon/host/context.clj:213-229`); agent `db/query` sends no `:datahike.resource/*` caps (`:281-314`), unlike the host's own projection reads (`:1082-1087`); writer applies caps only when supplied (`src/seon/db/writer.clj:818-827`) |
| Errors-as-values inside eval/invocation | STRONG with two escapes | `eval-form!` catches Throwable (`src/seon/host.clj:501-515`); `run-invocation!` catch (`:763-767`); escapes: `settle!` frame write outside the try (`:771-779`), uncapped error-message text can exceed the 4 MB frame |
| Accept loop / session loop survivability | **WEAK** | one Throwable from `.accept`/thread start permanently and silently ends the acceptor (`src/seon/host.clj:1052-1066`); session errors swallowed with no fault record (`:1003`); no startup-read timeout (`:957`) with one unpooled thread per connection (`:1057-1063`) |
| Cancel semantics (settle-once, context survives, session ends) | STRONG | `src/seon/host.clj:694-700,828-861`; tests `:606,:637`; pod side `src/seon/execution/host.cljs:1142-1193` |
| Interrupt-status hygiene around recording | **WEAK** | cleared only when the envelope says interrupted (`src/seon/host.clj:630-637`); a watchdog firing between value return and `deadline-task .cancel` closes the shared NIO writer channel mid-record and misclassifies the next form |
| Interrupt classification by message regex | **WEAK** | `#"deadline exceeded|interrupt"` over the thrown message (`src/seon/host.clj:509-511`) — an agent throwing text containing "interrupt" ends its own batch as a timeout |
| Recording: receipt-before-run, CAS-fenced terminal, tee parity | STRONG | `src/seon/host/context.clj:1241-1335`; `src/seon/host/record.clj:305-397`; parity test in `bin/test-writer` per roadmap U4 |
| Persisted result capping | STRONG cap, WEAK realization | `cap-edn` 8192 chars (`src/seon/host/record.clj:336-348`) but `pr-str` fully realizes the value first (`:371-373`); same for the transit probe in `wire-safe-value` (`src/seon/host.clj:409-424`) |
| Restore = fork base + replay corpus defs | STRONG | `src/seon/host.clj:895-918`; `src/seon/host/context.clj:964-1002`; kill drills PASS (roadmap U1/U4/U5) |
| Retained live values (drill/sample) | STRONG | bounded admission + oldest-first cap (`src/seon/host.clj:426-461`), cleared on session end (`:1005`) |
| Idempotent writes (op-id → protocol request-id receipt) | STRONG | `src/seon/host/context.clj:334-419`; registry crash-drill test (roadmap U2) |
| Cluster scoping (one host = one database) | STRONG | `::start-request` names one database (`src/seon/host.clj:156-164`); foreign startup refused (`:884-889`, test `:289`); pod ready validation checks db-name (`src/seon/execution/host.cljs:393-405`) |
| Pod tier dispatch (fact-driven, loud failure, no silent fallback) | STRONG | `src/seon/execution/host.cljs:807-832,858-883`; tests `:1058,:1122,:1191` |
| Run-fence host-side | **PoC (absent)** | invoke schema accepts it (`src/seon/host.clj:141`) but nothing reads it; only the pod's result-time check exists (`src/seon/execution/host.cljs:407-416`) |
| ALS print capture | **PoC (absent)** | `::output` seam exists (`src/seon/host/record.clj:364-365,393-394`) but is never fed (`src/seon/host.clj:640-652`); agent prints go raw and unbounded to host stdout |
| Instrumentation over sci vars (U6) | absent as ledgered | no malli instrumentation anywhere in `src/seon/host*`; roadmap U6 open |
| Renders/authored calls for host-tier agents | per design, still Bun child | routing at `src/seon/execution/host.cljs:800-806,858-869`; host answers renders with a steering error (`src/seon/host.clj:756-762`) |

## 1. Can one agent's eval take down the host or another agent?

### 1a. Uninterruptible runaways — the biggest containment hole

The interrupt chain is real and tested for interpreted code: the
watchdog interrupts the worker at the absolute deadline
(`src/seon/host.clj:717-720`), `:interrupt-fn` converts the thread flag
into an uncatchable sci interrupt (`src/seon/host/context.clj:928-931`),
and sci checks it on every fn entry and every `recur` iteration
(`reference-code/sci/src/sci/impl/fns.cljc:52,77`). The conformance test
proves `(loop [i 0] (recur (inc i)))` dies in <5 s with a healthy
context (`test/seon/host_conformance_writer_test.clj:580-604`).

But the check fires only when the *interpreter* regains control. Any
iteration that happens inside a single host-native call never sees it:

- `(reduce + (range))`, `(count (range))`, `(into [] (range))`,
  `(doall (repeat 1 1))`, `(vec (cycle [1]))`;
- catastrophic-backtracking `re-find`/`re-matches`;
- realization of an infinite lazy seq by the *envelope machinery itself*
  (`wire-safe-value`'s transit probe `src/seon/host.clj:419`,
  `bounded-result`'s encode `:377`, `pr-str` in `terminal-tx-data`
  `src/seon/host/record.clj:371-373`).

The pinned sci provides interrupt-aware replacements for exactly this
class (`sci.interrupt/clojure-core` + `clojure-string`,
`reference-code/sci/src/sci/interrupt.cljc:289-315` — range, repeat,
cycle, iterate, doall, dorun, count, into, reduce, re-find, re-matcher,
re-matches, re-seq, string replace/split), and its own docstring says to
merge them via `:namespaces {'clojure.core interrupt/clojure-core}`.
`build-base!` does not install them (`src/seon/host/context.clj:926-931`
passes only `:load-fn` and `:interrupt-fn`). No test exercises a
native-iteration runaway. **Classification: PoC** — the single most
likely accidental form an agent writes (`(reduce + (range))`) permanently
consumes one of ten pool threads.

Blast radius: `cancel-active!` interrupts and then waits at most 2 s for
the future (`src/seon/host.clj:835-841`), settles the error, and gives
up — the thread itself is never reclaimed. Ten such forms freeze the
entire host's eval capability for every agent in the cluster (see 1b).
Reconnecting sessions reuse the same contexts map, so the stuck thread
also keeps mutating the same agent context concurrently with any later
session's evals.

### 1b. Thread pool exhaustion and ghost execution of queued work

The pool is `Executors/newFixedThreadPool` of 10
(`src/seon/host.clj:176,1036-1038`) with the default unbounded queue.
At the U12 target (N=100 agents), >10 concurrent eval batches queue
routinely. Two consequences:

1. **Head-of-line latency**: an agent's invocation can sit queued behind
   other agents' slow batches with no fairness or per-agent bound. The
   pod's deadline eventually fires, but the work was never started.
2. **Ghost execution**: `cancel-active!` never calls `.cancel` on the
   submitted future (`src/seon/host.clj:828-847` interrupts only a
   *realized* worker and `.get`s with a 2 s bound). A queued-but-unrun
   invocation that is canceled/timed out is settled as canceled, its
   session usually closes — and then the task still runs the complete
   batch later (receipts, `db/transact!` side effects and all) with
   nobody listening; `settle!`'s CAS (`:697-700`) merely suppresses the
   frame. "Canceled" work is not canceled unless it had already started.

**Classification: WEAK** (correct under light load, structurally unsafe
at fleet scale). Fix shape: bound the queue or use per-session claim
tokens checked at task start, and `future.cancel(true)` on cancel.

### 1c. Memory: no per-eval allocation bound

There is no allocation ceiling anywhere between sci and the JVM heap. A
single `(vec (range 3e8))` or a giant string drives the shared heap; the
only containment is process `-Xmx` plus the C1 evidence that survivors
kept working through OOME bombs (20/20 process survivals — explicitly
carried as "strong evidence not kill-certainty"). Worse, the bounded
paths themselves realize before they cap:

- `wire-safe-value` transit-encodes the whole value just to test
  encodability (`src/seon/host.clj:417-419`);
- `bounded-result` encodes the full result before comparing to
  `result-limit-bytes` (`:377-383`) — a 500 MB result allocates ~1 GB
  transient before it is rejected;
- `terminal-tx-data` `pr-str`s the full value, then truncates to 8192
  chars (`src/seon/host/record.clj:371-373,336-348`).

GC pressure from one agent degrades every agent (shared heap, shared
pool threads). **Classification: WEAK** — acceptable for the current
drill scale, needs a print-limit/counting-writer style bound (bounded
`pr-str` via a capped Writer, size-limited transit probe) before a
fleet.

### 1d. Stack overflow, printing, blocking IO

- Deep recursion → `StackOverflowError` on the worker, caught by
  `eval-form!`'s `catch Throwable` (`src/seon/host.clj:508`) →
  `:seon/error` value. STRONG in practice.
- `(println (apply str …))` and any agent print go to the host process
  stdout unbounded and uncaptured (no ALS bridge — see §3). A print
  flood is disk/log pressure on the host, invisible to the transcript.
- Blocking IO inside an eval: the `seon.agent.ctx/read-file-text`
  wrapper slurps arbitrary paths whole (`src/seon/host/context.clj:620-628`
  — no size cap; `(my.… "/dev/zero")`-class accidents or a multi-GB file
  land on the heap); `Thread/interrupt` does stop NIO channel reads on
  the writer path but not ordinary `FileInputStream` reads.

### 1e. Result/error frames

Results are properly bounded (`:557` test). Error text is not: an
agent-thrown single-line message of >4 MB flows into
`error-value` → `error-frame` → `send-frame!` where `write-frame!`
*throws* on the frame cap (`src/seon/db/transport/uds.cljc:221-224`).
That throw happens inside `settle!`, which runs *after* `run-invocation!`'s
try/finally (`src/seon/host.clj:771-779`) — it escapes, the pool task
dies, the invocation is never answered, and the pod's queue-level timer
eventually synthesizes a cancel and retires the session. Bounded by the
pod deadline but a real errors-as-values escape; the fix is capping
`:seon.error/message` (token-clip) before framing. Same pattern applies
to `error-frame`s sent from the reader loop, where the throw ends the
session via the outer `catch Throwable _ nil` (`:1003`).

## 2. Cross-agent and cross-cluster isolation

### 2a. Cluster scoping — sound

One host process serves exactly one database: the start request names
one `::context/database-name` (`src/seon/host.clj:156-164`), startup
frames naming another database are refused before any context exists
(`:884-889`; test `:289`), and the pod's ready validation independently
checks the database name (`src/seon/execution/host.cljs:393-405`). A
host cannot be tricked into serving two clusters; two hosts per cluster
is possible but each still scopes to that one database. STRONG.

### 2b. Shared-base var mutation — the isolation story has a hole

The isolation claim is "forked defs stay private". True for *new* names:
`sci/fork` copies the env atom (`reference-code/sci/src/sci/core.cljc:318-323`),
so a `def` introducing a new name updates only the fork's env map. False
for *existing* names: `eval-def` looks up the previous var and, when one
exists, **mutates that shared Var object's root in place**
(`vars/bindRoot prev init`, `reference-code/sci/src/sci/impl/evaluator.cljc:33-41`).
Every var created before the fork — the whole loaded `my.*` portable
slice and every registry capability var (deliberately `identical?`
across contexts, `src/seon/host/context.clj:468-474` and the U2 identity
test) — is one shared mutable object in all 100 contexts.

Concrete accidental takedown vectors, none requiring malice:

1. An agent evals `(in-ns 'my.data)` (or any base ns — `in-ns` is the
   documented batch mechanism, `src/seon/host.clj:610`) and `defn`s a
   name that already exists there. Every agent's `my.data/<fn>` is now
   the new body. This is the *same* mechanism graduation uses on purpose
   (`sci/alter-var-root` in `register-wrappers!`,
   `src/seon/host/context.clj:454-456`), so it cannot be dismissed as
   theoretical.
2. `alter-var-root` is exposed to evaluated code
   (`reference-code/sci/src/sci/impl/namespaces.cljc:1476`):
   `(alter-var-root #'seon.db/transact! (constantly nil))` disables
   writes for the entire fleet until host restart.
3. Redefining anything in `clojure.core`'s shared namespace object, if
   reachable the same way.

There is no guard: no read-only marking of base/registry vars, no
copy-on-redefine, no denylist of `in-ns` targets outside `my.agent.<id>`
and scratch. The sandbox is stated as "catches model mistakes, not a
security boundary" — but this is exactly a model mistake (an agent
"fixing" a toolkit fn it can see the source of) with fleet-wide blast
radius. **Classification: PoC for the isolation requirement.**

### 2c. Process-global state shared across concurrently evaluating agents

Distinct sessions evaluate concurrently on pool threads. Shared mutable
state they all touch:

- **The host's own `seon.schema` registry.** Each form does
  `schema/snapshot` → eval → on failure `schema/restore!` → diff
  (`src/seon/host.clj:607-620`). Two agents registering concurrently
  race: agent A's failed-form `restore!` reverts to a snapshot that
  predates agent B's registration, silently unregistering it; diff
  attribution can also tee B's key into A's transaction. Unsynchronized,
  untested. WEAK.
- **One writer channel + one `call-lock`**
  (`src/seon/host/context.clj:213-229`): every agent's db call, every
  receipt, every terminal record serializes through one lock. A slow
  agent query blocks all recording and all other agents' reads for its
  duration. Agent-facing `db/query`/`pull` attach **no**
  `:datahike.resource/*` caps (`:291-314`), while the host's own
  projection reads do (`:1082-1087`) and the writer applies caps only
  when supplied (`src/seon/db/writer.clj:818-827`). One accidental
  cross-product query is unbounded writer work holding the fleet-wide
  lock — and the writer serves the whole cluster including the pod.
  WEAK; the missing piece is default resource caps on the agent-facing
  wrappers.
- A deadline interrupt landing while the worker holds `call-lock` in an
  NIO call closes the shared channel (`ClosedByInterruptException`);
  `writer-call!` reconnects once (`:222-229`), so this is transient —
  but see §5's interrupt-status race.
- `::projection-state` and the contexts map are atoms with monotonic /
  CAS discipline — sound.

### 2d. Can eval'd code reach the host's Clojure runtime?

`sci/init` passes no `:classes`, so evaluated code cannot resolve Java
classes, `System/exit`, reflection, or `clojure.core/eval`-the-host;
sci's own `eval` evaluates inside the sandbox. The reachable host
surface is exactly the registry wrappers (db, schema, tokens, io reads,
`content-hash`, `time`, `repair`, `canvas` signal) plus whatever the
portable `my.*` slice closes over. Two soft edges: the file wrappers
read any path the host process can read (information exposure + the
unbounded slurp above), and `graduate!`'s `clojure.core/eval` is host
compilation *outside* sci by design — gated by the trust gate, but the
gate has no symbol allowlist (`src/seon/host/graduate.clj:99-117` and
the roadmap's honest boundary), so graduation input hygiene is the real
boundary there, not the sandbox.

## 3. Punch-list verification and blast radius

1. **No run-fence CAS host-side — confirmed.** The invoke schema admits
   `:seon.execution/run-fence` (`src/seon/host.clj:141-142`) and nothing
   in `run-invocation!`/`eval-batch-result` reads it. The only fence
   check is the pod's at result time
   (`src/seon/execution/host.cljs:407-416`), which discards the *frame*.
   Blast radius: a fenced-out (superseded run) invocation still executes
   on the host — receipts commit, `db/transact!` side effects commit,
   defs mutate the context — and only its answer is dropped. Stale-run
   writes are durable. Owner: U6/U10 parity as ledgered.
2. **No ALS print capture — confirmed.** `terminal-tx-data` accepts
   `::output` (`src/seon/host/record.clj:364-365,393-394`) but the eval
   path never produces it (`src/seon/host.clj:640-652` passes no
   output; no `sci/with-out-str`/print-fn binding anywhere in
   `host*.clj`). Blast radius: `:seon.eval/output` is absent for every
   host eval — printed diagnostics vanish from the transcript and
   corpus, land unbounded on host stdout, and debugging an agent that
   communicates via `println` requires host log access.
3. **No instrumentation over sci vars (U6) — confirmed.** No malli
   instrumentation exists in the host namespaces; `:malli/schema`
   metadata is recorded (`:seon.fn/spec`) but never enforced at call
   time. Blast radius: wrong-shaped calls between agent fns fail deep
   and late instead of at the boundary; the graduation gate's
   schema-valid check (`src/seon/host/graduate.clj:153-160`) validates
   the *schema form*, not calls.
4. **Renders still per-agent Bun child — confirmed.** Only
   `eval-batch!` consults the tier fact
   (`src/seon/execution/host.cljs:800-806,858-869`); render/authored
   invocations take the child lane, and the host answers render symbols
   with a steering `:core-bug` (`src/seon/host.clj:756-762`). Blast
   radius: a "host-tier" agent still pays a full Bun child (~400 MB
   class of footprint) for rendering, so U1.5's memory win is not yet
   realized per agent; a child crash still interrupts renders even for
   host-tier agents. Test `:1191` proves the routing.

## 4. Error paths — throws that can escape

Every eval-path failure is a value (`eval-form!` `src/seon/host.clj:501-515`;
`run-invocation!` catch `:763-767`; wrapper `register!` catch
`src/seon/host/context.clj:557-565`; replay envelopes `:950-960`;
graduate catch-alls `src/seon/host/graduate.clj:224-231,281-282`).
Remaining escapes:

1. **Acceptor death** (`src/seon/host.clj:1052-1066`): the
   `catch Throwable` wraps the *whole loop*, so a single exception from
   `.accept` or `Thread.` construction ends accepting forever, silently
   — the host process stays up, every new session hangs until the pod's
   ready timeout, and nothing records a fault. The loop should catch
   per-iteration and log/record.
2. **`settle!` frame write escapes `run-invocation!`** (`:771-779`,
   detailed in §1e): oversize/failed frame encode after the CAS kills
   the pool task with the invocation unanswered.
3. **Session loop swallow** (`:1003` `catch Throwable _ nil`): correct
   shape (session death is the pod's child-exited contract, test
   `:1122`), but zero observability — no log line, no fault datom; a
   recurring codec bug would be invisible host-side.
4. **`serve-value-sample!` runs on the reader thread** and its
   `render.value/drill-value` over a retained value is bounded by
   admission, but a throw there propagates to the session `catch` and
   kills the session rather than answering a sample error.
5. `-main` blocks on a promise (`:1103`) with no supervision of the
   acceptor thread — combined with (1), a dead acceptor leaves a
   healthy-looking process.

## 5. Deadline/cancel chain and post-interrupt state

Chain verified in source: watchdog `schedule` → `.interrupt worker`
(`src/seon/host.clj:717-720`) → `:interrupt-fn` polls
`.isInterrupted` and raises `interrupt/interrupt!`
(`src/seon/host/context.clj:928-931`) → uncatchable-by-sandbox signal
(sci evaluator lets the interrupt win over `finally`/`catch`,
`reference-code/sci/src/sci/impl/evaluator.cljc:149-172`) → caught by
`eval-form!`, classified by message regex (`src/seon/host.clj:509-511`),
batch ends, `run-invocation!` maps it to timeout or canceled
(`:749-753`), `finally` cancels the watchdog and clears the flag
(`:768-770`).

What runs after the interrupt lands, in order: `schema/restore!` of the
pre-form registry (`:616-617`), explicit `Thread/interrupted` clear so
the terminal receipt's NIO calls survive (`:630-637`), the terminal
record with `:seon.eval/status :interrupted`
(`src/seon/host/record.clj:374-381`), then batch summary. The context is
*not* rolled back: any defs, atom swaps, or partial `db/transact!`
sequence the form completed before the interrupt remain — the context
survives *consistent at the engine level but partially mutated at the
agent level*. That is the documented favorable divergence and is
acceptable; nothing can leave sci's own env corrupt because the
interrupt is raised only between instructions.

Two real weaknesses in the chain:

1. **The late-interrupt race.** The watchdog can fire in the window
   after `sci/eval-string*` returns and before the `finally` cancels the
   task. The envelope is then *not* marked interrupted, so the `:630-637`
   clear does not run, the terminal record's NIO write dies with
   `ClosedByInterruptException` (closing the shared writer channel; the
   one retry `src/seon/host/context.clj:222-229` also fails while the
   flag is set), the outcome surfaces as `::record-error`, and the next
   form in the batch is spuriously interrupted at its first fn entry.
   Small window, fires per-eval at scale. Fix shape: clear
   `Thread/interrupted` unconditionally before recording, and treat the
   flag (not only the envelope) as the interruption fact.
2. **Regex classification** (`#"deadline exceeded|interrupt"`,
   `:509-511`): an agent whose own exception message contains
   "interrupt" has its batch misreported as a timeout/cancel. The
   interrupt should be classified by exception *type*
   (`utils/interrupt-ex?` is public enough via the sci API surface) or a
   marker, never message text.

Cancel specifically: settle-once is CAS-guarded (`:694-700`), the
2-second bounded wait keeps a wedged native call from wedging the reader
(`:838-841`), the session ends while the context survives (test `:606`),
and the pod synthesizes the exact contract error on session death
(`src/seon/execution/host.cljs:262-293`, test `:1122`). The residues are
§1b's un-reclaimed thread and ghost queued execution.

## Ranked gaps to close before a fleet is safe

1. **Install the sci interrupt-aware core overrides**
   (`:namespaces {'clojure.core sci.interrupt/clojure-core, 'clojure.string sci.interrupt/clojure-string}`
   in `build-base!`, `src/seon/host/context.clj:926-931`) and add a
   native-runaway conformance test (`(reduce + (range))` under a short
   deadline). Without this, one ordinary agent mistake permanently eats
   a pool thread. (PoC → the direct "agent takes down the host"
   requirement.)
2. **Protect the shared base and registry vars from in-place
   redefinition** — the `eval-def`/`alter-var-root` bindRoot path on
   pre-fork vars is fleet-global mutation (§2b). Options: refuse
   `in-ns`/`ns` into non-agent, non-scratch namespaces at the batch seam
   (`declared-next-ns`/`ensure-context-ns!` already own that boundary),
   or mark base/registry vars and reject rebinding outside the
   registry's own upgrade path. Must not break graduation's intentional
   var swap, which goes through `register-wrappers!` only.
3. **Cancel must cancel queued work and reclaim workers**: keep the
   future, `.cancel(true)` it, and re-check the claim token at task
   start so a settled invocation can never ghost-execute its receipts
   and writes (`src/seon/host.clj:818-847`). Consider a bounded queue +
   busy answer instead of unbounded queuing at N=100.
4. **Default resource caps on agent-facing db wrappers**
   (`src/seon/host/context.clj:291-314`): attach
   `:datahike.resource/max-work`/`max-results`/`max-result-weight`
   (config-fact-derived) so one query cannot hold the fleet-wide writer
   lock unbounded.
5. **Cap error text and stop realizing before bounding**: token-clip
   `:seon.error/message` before framing (fixes the `settle!` escape,
   `src/seon/host.clj:771-779`), bound `pr-str` with a counting writer
   in `terminal-tx-data`, and size-limit the transit probes in
   `wire-safe-value`/`bounded-result` so a huge value costs O(cap), not
   O(value).
6. **Make the acceptor immortal and observable**: per-iteration catch +
   fault record in the accept loop (`src/seon/host.clj:1052-1066`); a
   startup-read timeout on new sessions; a log/fault line on session
   Throwables (`:1003`).
7. **Serialize or scope the global schema snapshot/restore** across
   concurrent sessions (`src/seon/host.clj:607-620`) — today two
   agents' concurrent registrations can silently lose one.
8. **Fix interrupt hygiene**: classify by exception type, clear the
   thread flag unconditionally before recording (§5) — closes the
   shared-channel kill and the spurious next-form interrupt.
9. **Host-side run-fence CAS** (ledgered U6/U10): until it lands, a
   superseded run's writes commit durably even though its frames are
   discarded; at minimum the roadmap should carry this write-side blast
   radius explicitly, not only the read-side discard.
10. **ALS print capture** (ledgered): bind sci's print-fn per invocation
    into a token-capped buffer feeding `::output` — closes both the
    missing `:seon.eval/output` and the unbounded stdout flood.
11. **Per-eval allocation posture**: accept process-OOME containment for
    now (C1 evidence), but pair item 5 with a documented `-Xmx` +
    OOME-restart supervision expectation for the host process; the kill
    drill already proves fleet recovery in ~10 s + replay.
