---
type: research
status: fact pack and triage; no src/test/resources edits; the design is Fable's PRD
created: 2026-09-23
tags: [agent-platform, flow, errors, triage, namespace-agents]
---

# Flow fact pack and must-fix triage (2026-09-23)

Lane `flow-plan-and-triage`, renamed by the coordinator's scope change: Opus supplies
facts, and a Fable agent writes the flow PRD. **Part A chooses no end state.** Part B
is the triage the owner asked for: "I want to prioritize getting to the namespace
agents work done first … what are the must fix parts … we should schedule soon."

**Snapshot.** HEAD `c2140df4e` plus other lanes' uncommitted hunks in `agent.clj`,
`sci/eval.clj`, `turn.clj` and `render/web.clj`. Line numbers are for that tree, and
a hunk that is not yet committed is marked *(wt)*. Pins: core.async `dc35f3e0`,
Datahike `fbd1ad2d`. `Flow` means
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`. `DH/`
means `reference-code/datahike/src/datahike/`.

**Inputs read in full:**

- AGENTS.md;
- plan README;
- the flow skill;
- `Flow`;
- the flow-usage audit;
- the error-route final design;
- the fix schedule.

**Inputs read by their findings and fix order:**

- the one-error-route draft;
- the invalidation census and its review;
- both slow-assumptions audits;
- the swallowed-errors census.

## PART A — fact pack

### A1. core.async.flow, verified at `Flow`

| # | fact | cite |
|---|---|---|
| F1 | `start` creates control (fixed 10), report and error channels (sliding 100 each), and a sliding-100 cast channel per proc. It starts every proc in `:paused`, and the run loop starts in `:paused`. | 99-102, 126-130, 271 |
| F2 | `start` does not return the proc run-loop futures, because `start-proc`'s result is discarded. No handle to a proc thread exists outside the proc. | 149-167, 323 |
| F3 | `stop` sends `::stop` to all, then **immediately closes error and report** and clears `chans`. It returns `true`. It is a command, not a join. | 174-183 |
| F4 | Control `>!!` blocks when 10 commands are queued and unread. So `stop` can block its caller while procs are busy. | 71-75, 99 |
| F5 | A proc runs one transform at a time. Control is read only between transforms. It is also read inside `send-outputs`, at each blocked output put. During a transform the proc is deaf to ping and stop. | 288-300, 229-239 |
| F6 | A step or transition Throwable: the inner catch `>!!`s a map with `pid, status, state, count, cid, msg, op :step, ex` onto error, and continues with the pre-step state. The outer catch covers transition, control and `send-outputs` failures, with `pid, status, state, count, ex`. After `stop`, `>!!` onto the closed error channel returns false, so the failure is dropped with no trace. | 301-320 |
| F7 | `:compute` wraps the step in a Future on the compute executor and calls `.get` with `compute-timeout-ms` (default 5000). A timeout throws `TimeoutException` into F6's catch while the Future keeps running. | 245, 258-260 |
| F8 | A channel `:xform`'s ex-handler `put!`s onto error and returns nil. It runs under the channel mutex. | 103-111; `impl/channels.clj:78-94` |
| F9 | `:mixed` is the default workload. Each proc loop runs on `(get-exec resolver :io\|:mixed)`. A missing `:io-exec` falls back to core.async's global memoized executor. | 247, 262, 148; `impl/dispatch.clj:98-111` |
| F10 | `ping` waits for replies under a caller timeout. A busy proc does not reply (F5). | 76-86 |
| F11 | `inject` runs on a fresh `:io` futurized task, with a `>!!` per message. | 190-197 |
| F12 | An input returning nil (a closed channel) removes that input from `read-ins`. `::flow/input-filter` in state gates which inputs are read. The work launcher already uses it. | 289-294, 309-311; `src/seon/flow.clj:529-538` |
| F13 | The fork has casts (`signal-select`, `::flow/cast` outputs) that the upstream README does not document. Seon uses none. | 126-135, 224-226 |

### A2. Datahike listeners and the committed-report seam

| # | fact | cite |
|---|---|---|
| L1 | Listeners run in the `transact!` go block. The caller's promise is delivered first, then `notify-listeners!` runs. They do **not** run in the commit loop. They run on core.async's go-dispatch pool: `:core-async-dispatch` resolves to `:mixed`, an unbounded cached pool. | `DH/writer.cljc:389-406`; `dispatch.clj:106-111` |
| L2 | **Measured:** 6 concurrent `transact!` calls on a memory DB with one listener that sleeps 50 ms gave **6 simultaneous callbacks on 6 `async-mixed-*` threads**. Five reports carried the same `:db-after` max-tx (536870918). Batched transactions share the batch's commit db as `:db-after`. There is no ordering and no per-transaction `db-after` guarantee. | probe below; `DH/writer.cljc:256-261` |
| L3 | A throwing callback is caught per listener and **logged** as `:datahike/listener-error`. Later listeners still run, and the transaction is already delivered. So "throw into Datahike" today ends in a log line, which AGENTS.md's error policy counts as swallowed. It needs a failure callback in the fork's `notify-listeners!`. | `DH/writer.cljc:379-387` |
| L4 | The fork has a **bounded committed-report source**: `datahike.committed-report`. It is opened per connection generation. The commit loop offers to it in commit order, and never blocks or invokes downstream work. Overflow marks the source `:gapped` with counters. It offers blocking `take-ready!`, fair `poll-batch!` and `close!` with drain or abandon. Seon does not use it. Seon used it before the tree split (`2b584a6f2` "Route selective committed database events", `2750c158d`), and `f25e34594` deleted it. | `DH/committed_report.cljc:66-330`; `DH/writer.cljc:260` |
| L5 | A failed commit is terminal for the writer. The queues close, then callbacks fail. | `DH/writer.cljc:262-280` |

### A3. Seon's graphs and flow seams today

- **Four graph kinds** are unchanged from audit §1:
  - the work launcher `flow.clj:656`;
  - the cluster graph `cluster.clj:3181-3190`;
  - the fault graph `flow.clj:1191-1263`;
  - the agent graph `agent.clj:519-540`.

  Every proc goes through `var-process` (`flow.clj:132-181`), which refuses a non-Var
  step, a `:mixed` workload and missing environment args. `start-graph!` joins
  fan-outs while the procs are paused and resumes afterwards (`flow.clj:71-94`). That
  is the install-before-derive seam a hook can reuse.
- **Launcher facts the audit did not state:**
  - `submit!!` accepts **compute only**, because it always injects
    `::compute-submission` (`flow.clj:903-914`).
  - Compute submissions execute on `::task-executor` = the root **`:io`** executor
    (`flow.clj:687`, `:546`). The bounded platform `:compute` executor serves only the
    capacity-observer proc (`:652`). So the compute bound is the admission count, not
    the thread pool.
  - A `submit!!` time-limit marks the work `::wedged?` and returns
    (`flow.clj:936-948`). Admission is released only on the actual terminal
    (`:559-562`), so wedged work keeps its slot. This is the "timeout includes exit"
    shape (README §3:110).
  - `start-work-launcher!` returns `::compute-executor task-executor`
    (`flow.clj:717`), which mislabels it.
- **The completion-as-message pattern already exists.** The launcher proc's
  `::completion` in-port (`flow.clj:517-521`, `:559-580`) is the shape a model-call or
  evaluation submission would reuse.
- **Listeners per connection (wt):**
  - `:seon.agent/route` (`wake.clj:505`);
  - `:seon.call-preparation/rows` (`call_preparation.clj:578`);
  - one random uuid per agent schedule proc (`schedule.clj:799`);
  - **new** `::program-identity` (`sci/eval.clj:2359-2368`, registered at `:2622`
    and `:2690`).

  That is 3 + N. Each registers from a fork with a fixed key and is never removed.
- **Stop during a model call (wt, lane mcp-and-stop):** `disarm!` now calls
  `flow/stop` → `interrupt-graph!`, which `Thread.interrupt`s every tracked proc
  thread (`agent.clj:1024-1039`). It then awaits under the backstop bound
  (`:1190-1197`), still inside `(locking routing …)`.
  - The interrupt is a request, not termination.
  - An interrupt that lands inside a Datahike `transact!` deref leaves the outcome
    unknown to the caller, because the commit may still land (L1).
  - SCI and host calls observe it only at interruptible points.
- **Error dial default.** `default` runs `:panic`, but the only committed panic
  handler is a println (fix schedule row 0). No `seon.fault` namespace exists yet.
- **Degraded tool (observed now).** MCP `runtime_status` for `default` returns
  `UnsupportedOperationException "count not supported on this type: Date"` at
  `cluster.clj:629`. Probe: `(:seon.problems/problems (boot/readiness inst))` is a
  **refusal map** (keys `:seon.error/*`, `:seon.test/execution-refusal`), not a family
  map. `mcp-runtime-observation` counts every value. Both the problems producer's
  refusal and the consumer's missing branch are defects under "Total, honest
  boundaries".
  - Filed nowhere; this lane commits only this file.
  - It is in lane mcp-and-stop's area (`cluster.clj` is free).
  - The effect is that §2.1 "reliable development access" fails today.

### A4. Audit D1–D10, re-verified

| D | state now | evidence |
|---|---|---|
| D1 launcher error-chan unread | **LANDED** `c2140df4e`. It joins into the cluster fault channel, so D5 still applies. | `boot.clj`, `test/seon/flow_launcher_error_join_test.clj` |
| D2 stop-transition errors dropped | open | F3 + F6 |
| D3 2+N listeners | open. Now **3+N** (wt), and all run concurrently (L2). | A3 |
| D4 router work plus dropped fault in the listener | open | `wake.clj:522`, `:547` |
| D5 faults cross dropping channels | open. Six `offer!`+`println` sites. | `turn.clj:5366`, `:5389`; `agent.clj:1072`; `web.clj` |
| D6 arm/disarm monitor | open. The 302 s warm-restart hang (schedule 17a) is this monitor plus 164 ms per refusal transaction. Its cure is scheduled at the cost, not the lock. | `agent.clj:948`, `:1190` |
| D7 unbounded stop waits | **in flight** (wt) for `agent.clj`. `cluster.clj:3394`, `:3413-3417` and `flow.clj:1305`, `:1309` remain unbounded. | source |
| D8a backstop watcher outside Flow | open | `turn.clj:5396-5450` |
| D8b schedule sleep thread | **LANDED** `25de4dc81` (`async/timeout`) | `schedule.clj:740-756` |
| D8c/d SSE threads not joined, http-kit executor not shut down | open | `web.clj:2882`, `:3693` |
| D9 report channels unread | open, harmless | audit P4 |
| D10 turn proc deaf during a model call | open for ping. Stop is **in flight** via interrupt. | `turn.clj:4520`; `submit-evaluation!!` `:3315` still has no caller |

### A5. The 47 audit sites: what changed since the audit

- Row 3 (timer) landed.
- Row 10 (launcher error join) landed.
- Row 18: the `acquire!` `locking` is gone in the *(wt)* `sci/eval.clj`, in lane
  sci-program-revisions.
- Rows 25-26 are in flight (mcp-and-stop).
- There is one **new** site: the `::program-identity` listener (A3). It does a
  cache-context select per commit on a mixed-pool thread.
- Every other row stands as audited (`flow-usage-audit-2026-09-23.md` §2): 14 EXPAND,
  6 DELETE, the rest KEEP as JVM or bootstrap custody.

### A6. Constraints the PRD must respect

- **Plumbing first, turn last.** Every change to `turn.clj`'s turn or context is
  cut 4, the LAST cut. Namespace agents start at the end of cut 3, "with the turn loop
  as it is" (README §4:150-160).
- **Timeout includes exit** (README §3:110-112). A timed-out Future, whether Flow
  `:compute` (F7) or a launcher `submit!!`, keeps its resources until actual exit.
  No overlapping replacement work.
- **The error policy** (AGENTS.md "The error policy"):
  - stored with the chain at the owning boundary;
  - never dropped by an overload channel;
  - delivered through the wake route;
  - `:panic` stops the failing graph visibly;
  - database down means panic in both modes.
- **Channels carry only losable data.** Durable work re-derives from facts (AGENTS.md
  "The system").
- **No central scheduler or dispatcher.** Each agent owns its graph (AGENTS.md "The
  system").
- Seconds, not minutes. No stamps. Fix the owner. Scripted mechanical sweeps. One file
  per lane (ledger `tmp/orchestrator/file-ownership.md`).

### A7. What the one-error-route final design requires of Flow (`8e21d4bb5`)

1. **A fork hook.** Add an optional error handler to graph/proc construction. It is
   called from Flow's existing step, output and outer/control catches (F6) on the
   proc thread, with the full failure map. It returns continue or terminal. Handler
   failure exits the reporting path and never recatches. Start failures use the same
   adapter and rethrow.
2. **`:panic`** stops admission to the graph, records the failure identity, exits the
   failing proc and stops its siblings through Flow control. A supervisor observes
   actual completion. Stop is not exit proof (F3).
3. **Reject channel `:xform`** at Seon graph admission (F8). Today no `:xform` exists
   in `src/seon`.
4. Error and report channels become observations that may slide. **Delete** the
   counted-dropping route (`flow.clj:999-1320`).
5. **Prerequisite:** the in-transaction fault cost. Draft P4 measured 1,099.9 ms in
   `commit-call` inside the transaction, against 26.1 ms outside. A recurrence costs a
   1.25 s transaction.
6. **Backstops:**
   - a process uncaught handler (Datahike go blocks → `dispatch.clj:63-69`);
   - http-kit and SSE callbacks;
   - listener callbacks (L3);
   - Future consumption.

### A8. Candidate slices: cost and risk (the PRD chooses)

| slice | files | cost | risk and what it touches | closes |
|---|---|---|---|---|
| S1 fork error hook + `var-process` passes it + xform refusal | `reference-code/core.async` (fork; submodule push), `flow.clj` | ~1 day with regressions | medium. Every proc's catch path. The hook runs on the proc thread and must not park on a committer that is itself a proc. | D2, F6 loss, F8 |
| S2 `seon.fault/fault!` + committer move + policy + panic stops graph | new `seon/fault.clj`, `flow.clj`, `cluster.clj` fan-out, `error.clj` | 4–6 days (final design, option 2) + the P4 cost fix, 1–2 days | high. One loadable retirement slice with every `commit-fault!` caller. The P4 cost must be fixed first. | D5, row 0, R-OFFER |
| S3 bound every stop wait | `cluster.clj`, `flow.clj` (agent.clj in flight) | <0.5 day | low | D7 |
| S4 router proc: listener only offers; proc reads `d/since` from its basis `t` (or L4's committed-report source) | `wake.clj`, `cluster.clj` graph def, `schedule.clj`, `call_preparation.clj`, `sci/eval.clj` | ~1–2 days | medium. The wake regression suite must pass unchanged. L2 means the current router already receives out-of-order, batch-shared reports. | D3, D4 |
| S5 armer owns arm/disarm | `agent.clj`, `cluster.clj`, every direct installer | ~1 day | medium. All installers convert in one slice. | D6 |
| S6 backstop proc | `turn.clj`, `agent.clj` | ~0.5 day | low, but it edits `turn.clj` (cut 4 territory) | D8a |
| S7 evaluations through `submit!!` `:compute`; an `:io` arm for capability dispatch | `turn.clj:3315`, `flow.clj`, `effect.clj` | 2–3 days | medium. Compute runs on virtual threads today (A3), so the "CPU bound" is admission only. | cross-agent bound, D10 part |
| S8 model call as a launcher submission with a completion in-port | `turn.clj` (call/resume split) | 2–3 days | high. A turn restructure, cut 4. | D10, `turn.clj:4605` sleep |
| S9 SSE join, http-kit executor shutdown, uncaught handler | `web.clj`, `boot.clj` | ~0.5 day | low | D8c/d, backstop |

### A9. The owner's router ruling ("throw into Datahike"): what it means in source

- **Today.** The router catches and `offer!`s a bare Throwable to the dropping fault
  channel, and drops the `offer!` result (`wake.clj:546-547`).
- **Rethrowing makes Datahike log it (L3).** That satisfies "the writer is not
  stranded", which the fork proved on 2026-09-18
  (`docs/prds/steward-platform/research/datahike-listener-completion-2026-09-18.md`).
  It does **not** satisfy "stored and delivered".
- To honor both, the fork's `notify-listeners!` needs a failure callback installed per
  listener or connection, which hands the failure to `fault!`. The callback must never
  transact synchronously on the go-dispatch thread while it awaits the same writer.
  - Datahike uses the same maintained fork as schedule #24h (replikativ `raise` loses
    causes).
  - An alternative is L4: the router becomes a proc that takes committed reports
    itself. The failure then lands in S1's hook and no listener catch remains.

### A10. Open questions for the PRD (each needs an answer, not a guard)

1. **Router source.** Options:
   - the listener offers a sliding-1 wake and the proc reads `d/since` from its basis
     (the audit's shape);
   - the proc takes from the committed-report source (L4, bounded, commit-ordered,
     gap-signalled, previously built by Seon).

   Which fits "one listener, no work on the writer path" with fewer mechanisms?
2. **The hook and the committer.** The hook runs on the proc thread. If `fault!`
   commits synchronously, a panic storm across graphs serializes on the writer. If it
   goes through the committer proc, the committer's own failure needs the emergency
   path (final design). Which does the fork hook call?
3. **Compute placement.** Should `submit!!` compute work move to the bounded platform
   executor (`runtime.clj:17-21`), or is admission-count bounding the intended
   guarantee? This is a behavior change for every SCI evaluation that is later
   routed through it.
4. **Interrupt as stop (wt).** Keep `Thread.interrupt` on proc threads as the stop
   mechanism until S8, or require S8? Name the outcome-unknown case for an interrupted
   `transact!` deref.
5. **Does ping need to answer during a model call** before namespace agents run?
   Oversight shows `:unknown` for every open turn (audit P2). That is honest, but it
   hides a truly wedged turn.

## PART B — triage

Criteria (owner): **MUST** = breaks correctness, blocks tests or lanes, loses errors
or data, or makes the agent loop unusable. **SOON** = right after the first namespace
agents. **LATER** = the flow restructure and the rest.

Anchors:

- Namespace agents start at the cut 3 exit (README §4:160).
- They need §2.1 access, §2.5 honest tests, §2.6 real tasks, §2.7 gated acceptance,
  §2.8 export and §2.9 the demonstration.
- They use the turn loop "as it is" (§4:150-154).
- Their population is "433 untested and 3,145 uncontracted functions" (§4:160). So
  faults, refusals and defn transactions will be frequent.

### B1. MUST fix before namespace agents

| # | fix | why it blocks namespace agents | files | state |
|---|---|---|---|---|
| M1 | **SCI context rebuilt on every commit**: 1.2 s + 3.6 GB per data-only commit (schedule #12). It needs **invalidation step 1**: receipts stop writing `:seon.fn/calls` (#24c(1), 20 of 27 program-attribute tx). | Every agent form transacts a receipt (`turn.clj:861`, `:885`), so each form re-acquires. The loop is unusable (§2.3, §4 cut 4 is not a license). | `sci/eval.clj`; `fn.clj:1001-1006`, `turn.clj:922-963` | RUNNING sci-program-revisions; receipts-no-program-edges launching |
| M2 | **Writer cost**: config reconcile 16.5 s for 2 ops; every fixture seed ~20 s (#24i). Projection memo misses across connections, fixture p50 53→422 ms (#24j). A 4-row declaration tx takes 864 ms (#24d). | Every test's fixture exceeds its 5 s bound, which blocks §2.5 and the merge gate §2.7. Each agent `defn` is a declaration tx. | `db.clj` | RUNNING writer-cost |
| M3 | **Fault write costs 1.1–1.25 s in-transaction** (draft P4, A7.5). | 3,145 uncontracted functions produce contract faults. At a second each, the loop stalls, and batching would only hide it (final design §Evidence 4). | `error.clj` (projection holder), `schema.clj:397-416` | not assigned; must precede M4 |
| M4 | **One error route, minimal**: `fault!` with synchronous commit, the Flow hook (S1), deletion of the counted-dropping path (D5, R-OFFER sites), panic stops the graph and status shows it, and a process uncaught handler (row 30). Deferred: fingerprint accretion, ack/reopen, MCP banners beyond status. | Faults are lost today (D2, D5). Agents cannot see or be woken by their own failures (§2.6 "real tasks"; AGENTS.md error policy). The owner ranks it P0 (schedule row 0). | new `seon/fault.clj`, `flow.clj`, `cluster.clj` fan-out, the core.async fork, `turn.clj:5366,5389`, `agent.clj:1072`, `web.clj` sites | design done (`8e21d4bb5`); waits on M3 and `cluster.clj` release |
| M5 | **Stop hang and unbounded stop waits** (D7; #1 rejoin blocker). | Restart and rejoin of `default` are blocked, so lanes stall (§2.1). | `agent.clj` (wt), `cluster.clj:3394,3413`, `flow.clj:1305,1309` | RUNNING mcp-and-stop (agent.clj); the rest unassigned (S3) |
| M6 | **Warm restart hangs 302 s in agent arm** (#17a). | The same §2.1 blocker, and it is multi-second arm under a monitor (D6). | `sci/eval.clj` refusal tx, `db.clj` | cause found → M1/M2 lanes |
| M7 | **MCP `runtime_status` throws** on a refusal-valued problems map (A3, new). | The orchestrator and lanes lose health visibility (§2.1). The panic surface of M4 depends on it. | `cluster.clj:617-640` + the problems producer | **unfiled, unassigned** |
| M8 | **1.3d commit 5**: in-process tests, machinery deleted (#2). Also the commit-4 P1s, LANDED `57f02fd5b`. | §2.5 honest tests, and the merge gate §2.7 runs the reaching set in process. | `bin/test*`, `src/seon/test/**` | RUNNING realities-commit-5 |
| M9 | **Development adoption leaves the loaded program at the boot commit** (#9). Also **incremental adoption refuses owned-values plans under a stale contract** (#17b, verify first). | The agent's edit is not what it experiences (§4 reprioritised, §2.4). Adoption refuses agents' changes. | `cluster.clj` reload path, `sci/eval.clj`; TBD | WAITING / TRIAGE |
| M10 | **Nuke total and fresh-branch reset** (#1, #15, #24f). | A broken store must be recoverable without lanes stopping (§2.1). #24f nukes the wrong repository from a cwd. | `script/seon/**`, `bin/seon`, `boot.clj` | RUNNING nuke-is-total |
| M11 | **Publication cost for a leaf edit** (#16 remainder: the `cluster.clj` call site) toward the cut 2 exit "docstring edit adopts ≤700 ms" (§4:159). | Cut 2's exit precedes cut 3. Export and reindex (§2.8) run through it. | `cluster/source.clj`, `cluster.clj` | RUNNING publication-work |
| M12 | **Writer-side head guard on `issue/adopt!`** (#6 remainder). | Concurrent agents adopting issues lose writes against a stale head (§2.6 trigger identity at the writer). | `issue.clj` | RUNNING publication-lock |
| M13 | **Cut 3 itself**: commit 3 candidate half; merge; write-back (#26); B3 task family. | This is the path, not a fix (§2.6-2.9). | per spec | WAITING on M8 |

### B2. SOON after the first namespace agents

| item | reason it can wait | files |
|---|---|---|
| Census R-HELPER / R-MSG / R-LOG / R-DISCARD / R-CAUSE / R-EXDATA / R-TIMEOUT (#10) | F0 landed, so chains survive. M4 routes unhandled errors. These sites lose detail, not faults. | per holder, scripted |
| Invalidation steps 2–5 (#24c), and turn-start currency A3 (`db.clj:1119-1146`) | Performance, except step 2's dependency-plan blind spots, which are a source-derived counterexample and not executed. **Promote to MUST** if a probe shows a false "current" read on the turn path. | `db.clj`, Datahike fork |
| D6 armer owns arm/disarm (S5) | M6 removes the seconds of work under the lock. Serialization stays correct. | `agent.clj`, `cluster.clj` |
| D3/D4 router proc, delete the per-agent and call-prep listeners (S4, A9 ruling) | Wakes work today. The cost is per schema datom. The lost router fault is covered minimally by M4's listener backstop. | `wake.clj`, `schedule.clj`, `call_preparation.clj`, `sci/eval.clj` |
| Evaluations and capability dispatch through the launcher (S7) | They become MUST once concurrent agents exceed CPU. Measure agent count × eval load first (A10.3). | `turn.clj:3315`, `effect.clj`, `flow.clj` |
| D8c/d SSE join, http-kit executor shutdown (S9) | A leak per web restart, not per turn. | `web.clj` |
| Resume in seconds (#14: 14.5 s wall / 5.2 s ready) | Over the 10 s law but not blocking. | `script/`, `boot.clj` |
| Page GET 330–1,100 ms, debug page 44 s (#18), page key (#24c(3)) | Observation surface, not the loop. The page-key lane is launching. | `web.clj` |
| MCP artifact tx per windowed result (#19), store GC (#21), old test bases (#22) | Disk growth. After M1, revision keys ignore artifact rows. | `script/seon/dev/mcp.clj`; orchestrator sweep |
| Last first-boot projection stamp (#11); 1.4 projection sweep (#25) | The "no stamps" law and README cut 1, but there is no correctness symptom. **README orders 1.4 before cut 3; deferring it is an owner decision.** | `db.clj`, `source.clj`, `cluster.clj`, `fn.clj`; most of `src` |
| #24a purge validation per entity, #24b raw-Malli retirement refusal, #24g "unnamed callable" refusal, #24h Datahike `raise` causes, #13 follow-ups, #17 residue, #20 capability-contract 3.2 s | Bounded costs or legibility, off the agent's per-form path | per row |
| Error-route full scope: D13 fingerprint accretion, burst batching, ack/reopen, MCP banners, the kondo `try` hook | M4 stores and delivers. These refine grouping and visibility. | `seon/fault.clj`, `error.clj`, lint config |

### B3. LATER (cut 4 and the flow restructure)

- **D10 ping during a model call and the model call as a submission (S8).** Stop is
  already via interrupt (wt). This is a turn restructure, which README §4:150-154 puts
  last.
- **The backstop proc (S6), and the retry sleep `turn.clj:4605`.** Both edit
  `turn.clj`.
- **D9** report-channel readers; **F13** casts.
- Flow-native committing with a `:compute` tier (audit option 3). The `compute-timeout`
  semantics (F7) need the SCI arm or a host-bound refusal.

### B4. Recommended must-fix order

Order by dependency; within a tier, the fix that unblocks the most first.

1. **In parallel now**, because the files are disjoint:
   - M1 (`sci/eval.clj` + `fn.clj`/`turn.clj` receipts);
   - M2 + M6 (`db.clj`);
   - M5 (mcp-and-stop, then S3 on `cluster.clj`/`flow.clj`);
   - M7 (`cluster.clj` mcp observation + problems producer; new, small);
   - M8, M10, M11, M12 (already running).
2. **M3**, the fault-cost fix in `error.clj`/`schema.clj`, measured with the draft's
   P4 form.
3. **M9**, verify #17b first, then the reload path.
4. **M4**, the minimal error route, as one loadable slice once M3 lands and
   `cluster.clj` is free. S1 (fork hook) lands first inside it.
5. **M13**, cut 3: candidates, merge and write-back on M8's runner, then the
   namespace-agent campaign.

**Minimal flow pieces needed now:**

- S1, the fork hook with xform refusal;
- S3, bounded stop waits;
- the listener backstop half of A9: a Datahike listener failure callback, **or** the
  router rethrow once the fork routes it;
- the uncaught handler from S9.

Everything else in A8 (S4–S8) is deferred to B2/B3.

## Probes and timings (this lane)

| operation | wall | notes |
|---|---:|---|
| MCP `runtime_status` default | <1 s | refused: `count not supported on this type: Date` (M7) |
| listener concurrency probe, JVM mode, memory DB (form in A2 L2; created and deleted its own DB) | 418 ms | includes a deliberate 400 ms settle sleep and 6 × 50 ms callbacks |
| readiness/problems shape probe | 42 ms | problems value is a refusal map |
| source reads, `rg` and `git log` queries | each <1 s | no JVM started, no reload, no transaction on a cluster branch |

Verification boundary: this is source and document verification plus two read-only
probes. No fix is proven here. *(wt)* claims describe uncommitted hunks of running
lanes and may change.
