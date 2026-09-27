# Stability sweep — 2026-09-27 (diagnosis only)

Lane `stability-sweep-opus`. All times are UTC. The default JVM prints `java.util.Date` in
America/Mexico_City (UTC-6). The brief's "~23:30Z" and the thread dump's `12:00:33` are both
about 18:00Z. Probes ran from 17:57Z to 18:06Z on two JVMs: pid 66629 (archive 95f44cbc3,
started 17:36:08Z) and, after root restarted, pid 75447 (HEAD 9e61340b2, started 18:00:49Z).
Every probe was a read-only JVM-mode query on `default`, plus three SCI runs on the disposable
branch `stab-sweep`, which has since been retired. Wall time in probes was about 9 minutes. The
slowest reply was one timed-out 30 s query I wrote over history with no bound, and I dropped it.
Every other probe answered in under 120 ms. This sweep proposes no new error store, surface or
reporting path. Every finding below is a query over the existing facts: `seon.fault/fault!`,
`:seon.error/notified`, `seon.problems/open-faults` and `seon.error/resolution`.

## Broken things, ranked by the stability each fix buys

| # | finding | fix at the producer | stability bought |
|---|---|---|---|
| 1 | Two escape paths drop the fault from the caller's reply: a refused refusal-settlement throws, and a parked proc closes replies. The caller sees only "awaited channel closed". | Return the value; answer the reply with the error | Every agent failure on the REPL path comes back as data in the same reply |
| 2 | A transient refusal parks the turn proc until a restart. Root was parked from 02:35Z by memory pressure. | A submission to a parked proc gets one attempt and is answered with the park error | No permanent wedge from a transient cause |
| 3 | The resolution rule is unsound both ways. 7 of the 10 open faults are stale, 3 record-tx signatures were marked resolved while the defect persisted, and the settle-batch-refusal fault stays open after its fix. | Scope declared by the producer, closed at process end; attribute to the failing function, not the reporter | Health stops crying wolf; `:faulted` means something |
| 4 | A stop's interrupt, wrapped by Datahike, escapes `turn-step` as a core fault that can never resolve | Catch the declared case by cause chain | Removes a class of permanent fake faults at every stop |
| 5 | Collection root verification compares a live branch's head for equality: a probable race that gives a false "root not preserved" | Verify that the recorded commit reopens, not that the head is unchanged | No false data-loss alarms |
| 6 | `runtime_status` threw at boot instead of answering `:unknown` | Guard the database value | The health check is total |
| 7 | Interrupt faults lose their agent attribution on the occurrence | Carry `:seon.agent/id` onto the occurrence | The failing agent sees its fault |

Items 8 to 10 are verified observations that need no new task: the profile artifact, the fixed
nested `in-ns` defect, and linkage.

---

## 1. Faults escape the caller's reply (panic class)

**Evidence.**
- Open fault `317111871b50`: `seon.turn/settle-batch-refusal!`, 3 occurrences on branches
  `:p0-probe` (16:17Z), `:corpus-2` (17:26Z) and `:safety-probe` (17:51Z). Its whole cause chain
  is in the evidence blob `e6601ae5…`, a 107 KB string.
  - The outer link is `ex-info "Batch refusal settlement was refused."` at `turn.clj:4161`, in
    the old JVM.
  - `:seon.turn.loop/settlement` holds the writer refusal "Nothing found for entity id
    [:seon.ns/name safety.nested]" (`:entity-id/missing`), from `seon.db/transact!` through
    `call_preparation.clj:1033` → `db.clj:4855/4864`.
  - `:seon.turn.loop/refused-outcome` is the first settlement's refusal. The same lookup ref was
    refused, and its tx-data was `[absent-call receipt-settle-batch-call close-folded-call
    plan/settle-call]`.
- So the refusal settlement was refused for the same reason as the settlement it records. It
  reuses the receipts' `:seon.cluster.eval/ns` lookup ref for a namespace that only the refused
  batch would have created.
- The root cause, nested `in-ns`, is fixed in HEAD 9e61340b2. I verified it on pid 75447: on
  branch `stab-sweep`, `(do (in-ns 'stab.nested) (+ 1 2))` → `{:value 3 :ns stab.nested :ms 3}`.
- **The escape path remains.** `settle-batch-refusal!` still throws when its own write is
  refused (`src/seon/turn.clj:4079`, throw at `:4166`). The pass's catch-all rethrows after
  `close-escaped-run!` (`turn.clj:5728`). `turn-step` does not catch it
  (`src/seon/cluster/agent.clj:515-558`). Its `finally` closes the reply, and
  `evaluate-source!` answers `:seon.await/closed-error` through its `:else` arm
  (`agent.clj:826`). This is the "awaited channel closed" that root's safety-slice probe
  observed.
- `fault!` recorded a receipt, but nothing hands it to the reply. The causing agent
  (`mcp/safety-probe`) meets the fault only through `seon.error/for-agent` in its next run.
  Root reports that it shows up in the next, unrelated run. The fault's one notification went
  to root.
- **A parked proc** (item 2) answers every submission with `async/close!`
  (`turn.clj:6044-6051`): "its caller reads the parked fault in status". `runtime_status` shows
  only the newest 3 open faults, so the caller does not get it either.

**Cause (verified):** a failure in a late stage replaces the value instead of travelling beside
it. This violates AGENTS "the path is total" and "deliver every error".

**Smallest fix.**
1. `settle-batch-refusal!` returns
   `{:refused-outcome refusal :refusal-refused outcome}` instead of throwing. The run is closed
   by boot recovery, as for any run whose terminal write failed.
2. The pass answers that value on the reply.
3. Where a pass escapes after `fault!` committed, the `finally` in `turn-step` offers the fault
   receipt (`:seon.fault/recorded`, already carried on the throw's `ex-data`) on the reply
   before closing it.
4. A parked proc answers `(:seon.turn.loop/parked state)`, the write-refusal error value, on
   the reply instead of closing it.

**Proposed tasks.**

`resources/seon/tasks/seon.turn.edn`:

```clojure
#:seon.task{:name "a-refused-refusal-settlement-throws-past-the-reply"
            :kind :function
            :intent "settle-batch-refusal! throws 'Batch refusal settlement was refused.' when its own write is refused (turn.clj ~4166); the pass rethrows, turn-step's finally closes the reply and evaluate-source! answers :seon.await/closed-error, so the caller sees 'awaited channel closed' while the fault (317111871b50, 3 occurrences 16:17-17:51Z 2026-09-27) goes only to root and to the agent's NEXT run. Return both refusals as the value and answer them on the reply; boot recovery closes the run."
            :must-read #{seon.turn/settle-batch-refusal! seon.cluster.agent/evaluate-source!}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

`resources/seon/tasks/seon.cluster.agent.edn`:

```clojure
#:seon.task{:name "an-escaped-pass-closes-the-reply-without-its-fault"
            :kind :function
            :intent "When a turn pass escapes after seon.fault/fault! committed, turn-step's finally closes the reply and the caller gets :seon.await/closed-error with no fault; the receipt is on the throw's ex-data (:seon.fault/recorded). Offer the recorded receipt (or the fault value) on the reply before closing, so the REPL reply that caused a fault carries it."
            :must-read #{seon.cluster.agent/turn-step seon.cluster.agent/evaluate-source! seon.fault/carried-receipt}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

## 2. A transient refusal parks the turn proc permanently (wedge)

**Evidence.**
- Open fault `c2268b2f` (`seon.turn/write-refusal-error`, 02:35:09Z, process
  `88762-1790475797337`, agent root): "3 consecutive turn writes refused (bound 3); the turn
  proc is parked and will not re-fire. Latest refusal: The selected renderer did not return an
  observation."
- Its chain, and the chain of open fault `504062b8` (`seon.render/unknown`, 3 occurrences,
  02:35Z), has the cause: `:seon.sci.kernel/...` "This JVM is under memory pressure: G1 Old Gen
  holds 10094537088 of 10997465088 bytes".
- Memory pressure refused the renderer's arm three times. The bound parked root's turn proc,
  and it stayed parked until that JVM died.
- Neither fault was messaged to anyone (`:seon.error/notified nil`). The occurrence agent is
  root, and the recipient rule removes the failing agent from its own recipients
  (`src/seon/error.clj:1516-1520`). Root would read them through `for-agent` in its next
  context, but its proc was parked, so there was no next context.

**Cause (verified by code):** `turn.clj:6044-6051` and `:6157-6184`. A park is left only by
disarm/arm. That is right for a deterministic refusal storm and wrong for a transient cause
such as memory pressure, which clears below its resume watermark.

**Smallest fix.**
- Wakes stay suppressed. This keeps the storm guard.
- A *submission*, which a caller explicitly asked for, gets one attempt. If it is refused again,
  the refusal is answered on its reply (item 1). A committed write clears the park, as it
  already resets `write-refusals`.

**Proposed task** (`seon.turn.edn`):

```clojure
#:seon.task{:name "a-transient-write-refusal-parks-the-agent-until-restart"
            :kind :function
            :intent "At 02:35Z 2026-09-27 memory pressure refused root's renderer three times; the write-refusal bound parked root's turn proc (fault c2268b2f) until the JVM died, and every submission was answered by closing its reply. Keep wakes suppressed while parked, but give an explicit submission one attempt, answer its refusal on the reply, and clear the park on a committed write."
            :must-read #{seon.turn/step seon.turn/write-refusal-bound seon.turn/record-write-refusal-fault!}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"
                        "docs/seon/issues/a-parked-turn-proc-pings-unknown-on-a-healthy-agent.md"}}
```

## 3. The resolution rule keeps health `:faulted` on stale faults and resolves live ones

`seon.error/resolution` (`src/seon/error.clj:1309-1354`) closes a fault when the definition
digest of `:seon.instrument/fn` changed after its latest occurrence, or when `resolve!` was
recorded. `seon.problems/open-faults` (`src/seon/problems.clj:137`) keeps the rest.
`runtime_status` health is `:faulted` whenever any fault is open (`src/seon/cluster.clj:656-657`).

**Evidence: the 10 open faults at 18:01Z on pid 75447.** None occurred in this JVM. All came
from dead processes. The query was
`(seon.problems/open-faults db (#'seon.problems/error-signatures db))` plus a per-signature pull
of the latest occurrence. It took 40 ms.

| sig | operation / `:seon.instrument/fn` | last | process alive? | state | why still open |
|---|---|---|---|---|---|
| 93e4a1e2 | record-tx / `seon.test/run` | 16:09Z | no | **live defect** (`:seon.test/report-conflict`) | correct |
| 31711187 | settle-batch-refusal! / same | 17:51Z | no | root cause fixed in 9e61340b2 (`receipt-settle-batch-tx`); the throw path (item 1) remains | the fixed function is not the fn the fault names, so its digest never changes |
| d2082881 | stop-work-launcher! / `seon.test/run` | 03:09Z | no | live class at every restart (task eb0fdb7e0ffd) | correct |
| a0323a94 | maintenance collection / `seon.maintenance/exception-value` | 03:06Z | no | probable false alarm (item 5) | the fn is the error *builder*, whose digest never changes |
| b76fae17 | acquire-arm / `seon.cluster.boot/memory-pressure-crossed!` | 17:27Z | no (OOM, pid 63809) | process condition, gone | the fn is the reporter |
| 504062b8 | `seon.render/unknown` | 02:35Z | no | caused by memory pressure, gone | same |
| c2268b2f | write-refusal-error / `record-write-refusal-fault!` | 02:35Z | no | proc park, gone with its process | same |
| 477a97dc, 67029fe7 | `superv.async/throw-if-exception-` | 02:25Z, 02:08Z | no | interrupt at stop (item 4) | no function entity: **can never resolve** except by `resolve!` |
| 721e82de | turn-completion-error / backstop | 02:08Z | no (boot process) | test exhaust; fixed by stability-opus's copy rule; resolved earlier under 5 older signatures | a new signature from frame numbering, never `resolve!`d |

**Resolved while the defect persisted.**
- `017bd6fe`, `104b2c82` and `28ce3d56` (record-tx report conflict) show
  `:resolved-by :seon.program/definition-digest` because `seon.test/run` changed digest
  (`b6027a9c…` → `1de836da…`).
- `record-tx`, the function that refuses, was not fixed. The same refusal recurred at 16:09Z as
  a new signature, `93e4a1e2`.
- `:seon.instrument/fn` names whichever armed function the fault escaped through. That is often
  an outer wrapper (`seon.test/run`) or the function that builds the error. It is never
  necessarily the function the fix touches.

**Cause (verified):**
- The rule has one resolving event, "the named fn's digest changed", and three failure modes:
  1. The named function is a wrapper or reporter, so an unrelated edit resolves the fault and
     the real fix does not.
  2. Faults with no function entity are permanent. This is also task 3d1e36226ec4 for deleted
     functions.
  3. Process-scoped conditions outlive their process: memory pressure, a parked proc, an
     interrupt at stop, a launcher stopped at shutdown.
- Nobody calls `resolve!` as part of landing a fix.

**Smallest fix at the producer.**
- **(a)** The error schema declares whether a fault is process-scoped, as a property on the
  declared schema (for example `:seon.error/scope :process` on
  `:seon.sci.kernel/memory-pressure-error`, the write-refusal error, the flow interrupt case and
  `:seon.flow/launcher-stopped`). `resolution` adds one clause: a process-scoped fault is
  resolved when no occurrence's `:seon.error.occurrence/process` is the live process. The
  producer declares this; there is no hand list.
- **(b)** `fault!` records the failing operation's own symbol (`:seon.error/operation`, already
  present) as the resolution subject, instead of the outermost armed Var.
  `turn-completion-error`, `exception-value` and `record-write-refusal-fault!` are builders,
  not failures.
- **(c)** The fix workflow calls `seon.problems/resolve!` for the signatures a landing names.
  That is a workflow line, not code.

**Proposed task** (`resources/seon/tasks/seon.error.edn`):

```clojure
#:seon.task{:name "health-stays-faulted-on-dead-process-faults"
            :kind :function
            :intent "At 18:01Z 2026-09-27 all 10 open faults came from dead processes and 7 were stale: resolution only fires when the digest of :seon.instrument/fn changes, and that fn is often the reporter (memory-pressure-crossed!, record-write-refusal-fault!, maintenance/exception-value) or an outer wrapper (seon.test/run resolved three record-tx signatures while record-tx still refused; settle-batch-refusal! stays open after its cause was fixed elsewhere), or absent (superv.async). Let the producer's error schema declare process scope and resolve such a fault once none of its occurrences' processes is live; resolve by the failing operation, not the outermost armed Var."
            :must-read #{seon.error/resolution seon.problems/open-faults seon.fault/fault!}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

## 4. A stop's interrupt escapes `turn-step` as a core fault

**Evidence.**
- Open faults `477a97dc` and `67029fe7`: operation `superv.async/throw-if-exception-`,
  `:seon.flow/exception-error`, on test member branches `:agent-cbd293587857` and
  `:agent-77dace1d10a8`.
- Their chains show `clojure.lang.ExceptionInfo` whose message is
  `java.lang.InterruptedException`, raised under `seon.cluster.agent/turn-step` in
  `:seon.flow/proc`.
- `turn-step` catches only a bare `InterruptedException`
  (`src/seon/cluster/agent.clj:551`: "the declared case … this is no fault"). Datahike's async
  layer wraps the interrupt in `ex-info` (superv.async), so it misses the catch and becomes a
  Flow proc exception. That exception is a core fault with no function entity (item 3), so it
  is permanent.
- The data carries `:seon.agent/id`, but the occurrence's `:seon.error.occurrence/agent` is nil
  (item 7). The interrupted agent never reads the fault.

**Smallest fix:** in `turn-step`, treat any throwable whose cause chain contains an
`InterruptedException` while the holder shows the stop served this thread as the declared
case. Walk the chain with a bound (task 465ff60ccda0 covers a cyclic chain).

**Proposed task** (`seon.cluster.agent.edn`):

```clojure
#:seon.task{:name "a-wrapped-stop-interrupt-is-recorded-as-a-core-fault"
            :kind :function
            :intent "turn-step catches only a bare InterruptedException (agent.clj:551); a stop that interrupts a Datahike call surfaces as superv.async ExceptionInfo(InterruptedException) and is recorded as a core fault with no function entity, which never resolves (477a97dc, 67029fe7, 2026-09-27 02:08/02:25Z). Recognise the stop's interrupt anywhere in the bounded cause chain."
            :must-read #{seon.cluster.agent/turn-step}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

## 5. Collection root verification races a live branch (probable false alarm)

**Evidence.**
- Open fault `a0323a94` (03:06Z, process `91336-…`, agent root): "Collection did not preserve
  and verify every recorded root. Branch :cluster-default did not reopen at its recorded commit
  ID."
- `collection-evidence` (`src/seon/maintenance.clj:602`) reads each roster branch's head. It
  then walks every branch's digests, which is slow. Only after that does `root-verification`
  (`:649`) call `branch-reopens?` (`:617`), which requires `(= expected (d/commit-id
  (d/branch-as-db …)))`, the head *now*.
- `:cluster-default` is the live cluster branch and keeps committing during a collection. Any
  commit between the two reads fails the check without any loss.

**Cause:** inferred from code and **not reproduced**. Reproducing it needs a whole-store
collection, which this lane may not run. A real loss cannot be ruled out from this evidence
alone: no later collection verified clean in the fault facts I read.

**Smallest fix:** verify that the *recorded commit* still reopens (`d/as-of`/commit-id load of
`expected`), not that the head is unchanged.

**Proposed task** (`seon.maintenance.edn`):

```clojure
#:seon.task{:name "root-verification-compares-a-moving-head"
            :kind :function
            :intent "branch-reopens? requires the live branch's current head to equal the commit recorded earlier in collection-evidence; :cluster-default commits during a collection, so a clean collection can fault 'did not reopen at its recorded commit ID' (a0323a94, 03:06Z 2026-09-27). Verify the recorded commit loads, not that the head did not move; prove with a write between the two reads."
            :must-read #{seon.maintenance/collect! seon.cluster.registry/branch-commit-id}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

## 6. The health check threw at boot

**Evidence.** The first `runtime_status` on pid 75447, at 18:01:08Z (19 s after start, with
`ready-ms` 12428), answered `state degraded` with a thrown contract refusal instead of an
observation: "seon.problems/open-faults refused database at []: expected must be an immutable
Datahike database value, got a map". The frame was `seon.cluster/mcp-runtime-observation`,
`cluster.clj:623`. The next call, seconds later, was fine.

**Cause (unverified):** `(db/db connection)` answered its declared `:seon.db/invalid-read-error`
map in the boot window (`src/seon/db.clj:2246-2264`). `problems/problems` did not refuse on it,
and it was then passed to `open-faults`.

**Smallest fix:** bind one database value in `mcp-runtime-observation`, and treat an error value
there as `:seon.dev.mcp/problems-unavailable` with health `:unknown`.

**Proposed task** (`seon.cluster.edn`):

```clojure
#:seon.task{:name "runtime-status-throws-on-a-boot-window-read"
            :kind :function
            :intent "At 18:01:08Z 2026-09-27, 19 s into pid 75447, runtime_status threw open-faults' contract refusal ('got a map') instead of answering: mcp-runtime-observation calls db/db several times and passes its error value on. Read one database value; an error value makes problems unavailable and health :unknown."
            :must-read #{seon.cluster/mcp-runtime-observation}
            :evidence #{"docs/research/agent-platform/stability-sweep-2026-09-27.md"}}
```

## 7. Fault occurrences lose the agent they happened to

Faults `477a97dc`, `67029fe7`, `b76fae17` and `93e4a1e2` have `:seon.error/agent nil`.
The two interrupt faults carry `:seon.agent/id` inside their recorded source, because `fault!`
assoc's it into `:seon.error/source` (`src/seon/fault.clj:286-292`). The occurrence's
`:seon.error.occurrence/agent` is set only from the recording request (`error.clj:1492`).
Without it, `for-agent`'s occurrence clause (`error.clj:2066`) cannot reach the agent. This is a
one-line fix at `fault!`, and it belongs with item 4's task: pass `agent-id` into the recording
request.

## 8. `seon.schema/call-with-projection-state`: 3,558 s total and 522 s maximum are proc lifetimes, not work

**Evidence.**
- On pid 66629 at 17:57:59Z, uptime was 1,311 s. `seon.profile/top` gave
  `[seon.schema/call-with-projection-state 218 calls 3558513 ms total 522075 ms max 1 threw]`.
- A maximum of 522 s inside 1,311 s of uptime, with a total 2.7× the uptime, can only be
  overlapping long-lived calls.
- On pid 75447, `jcmd 75447 Thread.dump_to_file`, which includes virtual threads, shows exactly
  5 virtual threads inside `seon.schema$call_with_projection_state` (`schema.clj:1256-1263`).
  Each was entered from `seon.flow$graph_executor` (`flow.clj:96`) and parked in
  `clojure.core.async.flow.impl` proc `run` (`impl.clj:282-295`).
- Every Flow proc thread runs its whole life inside this armed function. The profile counts one
  call when the graph stops, whether by a test member, a branch agent or a restart. The one
  throw is a loop that exited by throwing.

**Cause (verified):** measurement artifact. **Existing task `5f54ae50d841`**
("scope-wrappers-report-callback-time-as-work", `seon.profile`) already names this exact site.
No new task is needed. Root should add this evidence line.

The 83 s `evaluate-source!`/`await!` spans fall inside the GC chain:
- `registry/collect!` max was 82.8 s, under the Datahike sweep permit
  (`reference-code/datahike/src/datahike/gc.cljc:131-177`, `gc_guard.cljc` `:sweep` excludes
  `:roster`).
- That blocked `registry/branch!` (82.3 s) ← `acquire-context!` ← `disposable-copy` ←
  `evaluate-candidate` ← `gate-function-install` ← `evaluate-source!`.
- That chain belongs to perf-audit-opus. The store is 27 GB (`du data/store`). On pid 75447 the
  same shape recurred during root's test round:
  - `collect!` max 66.6 s;
  - `branch!` 65.2 s;
  - `blob/put!` 56.6 s;
  - `seon.cluster/mcp-valf` 56.6 s. An MCP reply waited on blob publication, so an `eval_clj`
    reply can stall behind a test's collection.

## 9. Verified fixed: nested `in-ns` settlement (existing task "a-nested-in-ns-loses-the-whole-run")

HEAD 9e61340b2 upserts the observed namespaces. The probe above answered in 3 ms with
`:ns stab.nested`. Root can close that task (`seon.turn.edn`, name
`a-nested-in-ns-loses-the-whole-run`). The fault it caused (`31711187`) stays open under item 3's
rule until `resolve!` is called.

## 10. Linkage and delivery of every open fault

A link is either `:seon/tasks` on the failing defn or `:seon.error/issue` on the fact. No fact
carries `:seon.error/issue`. The table below reads `(meta (resolve sym))` for the operation and
for `:seon.instrument/fn`.

| sig | linked task | notified (message to) | causing agent saw it in its reply? |
|---|---|---|---|
| 93e4a1e2 record-tx | yes: `record-tx` `:seon/tasks` 80aa14c43291 (report conflict), 32db32ffe15c | root (once, 02:44Z opening; the 16:09Z recurrence re-notifies nobody, by design) | no agent (root's central run) |
| 31711187 settle-batch-refusal! | **none** → item 1 task | root (from `mcp/p0-probe`) | **no**: "awaited channel closed"; appears in the agent's next run |
| d2082881 stop-work-launcher! | yes: `seon.test/run` → eb0fdb7e0ffd, `stop-work-launcher!` → f12bf9eda5f1 | root | no agent |
| b76fae17 memory pressure | **no `:seon/tasks`** on `memory-pressure-crossed!`; task d287c254b094 names it only in `:must-read` | root | no agent recorded |
| 504062b8 render/unknown | **none**: its cause is memory pressure; d1efccd3ab7e (fixed OOM walk) is the root cause | nobody (agent = root, removed from its own recipients) | root parked; never saw it |
| c2268b2f parked | **none** → item 2 task | nobody | root parked; never saw it |
| 477a97dc, 67029fe7 interrupt | **none** → item 4 task | root | interrupted agent not attributed (item 7) |
| 721e82de fired-bound | stability-opus fixed the copy rule; no task | root | test exhaust |
| a0323a94 collection | **none** → item 5 task | nobody (agent = root) | root operator call |

Item 4 of the brief asked whether b76fae17 maps to d287c254b094. It does: the fault's function
`seon.cluster.boot/memory-pressure-crossed!` is in that task's `:must-read`, and the refusal
text "In flight when it began: no open run" is the task's subject. The defn lacks the
`:seon/tasks #{"d287c254b094"}` mark, so the link is not queryable. Root should add the mark
with the task's owner.

Delivery summary:
- Every notification in the store (26 of 27 fault messages) goes to root.
- Faults whose occurrence agent *is* root are messaged to nobody. They depend on root's next
  context, which a parked root never builds.
- Agent-caused faults reach the causing agent only in its next run, never in the reply that
  caused them (item 1).

## Other observations (smaller)

- **Stored evidence is printed nodes, capped at the first cause link.** The inline
  `:seon.error/data-edn` of `31711187` keeps only the outer link plus
  `:seon.sci.admit/remainder {:reason :over-bound :bytes 7826}`. The whole chain is a 107 KB
  *string* of `#:seon.print{:face …}` nodes, not a value. This is existing task
  `57f97689b302` (`storage-edn-is-print-nodes`); this sweep adds one more case to it.
- **Guidance in `:out`.** An admitted `defn` reply carried `:out "No example test gates
  stab.nested/stab-inc; add a deftest …"`: prose in the output stream, not a data key. AGENTS
  "Return data, not comments" applies. No task yet; this is the same class as `c7bc0c506aa4`,
  where explain-slow text is appended to `:out`.
- **The profile of the MCP value path** is in item 8: `mcp-valf` waited 56 s. That belongs to
  perf-audit-opus.

## Checks and limits

- No source or test edits and no restarts. The only data written was on branch `stab-sweep`,
  which is retired. The three SCI runs there were a nested `in-ns`, `(/ 1 0)` and one
  contracted `defn`.
- **Not reproduced:**
  - the refusal-of-refusal on HEAD, where the nested `in-ns` trigger is fixed and no other
    trigger was found;
  - item 5's race, which needs a collection;
  - item 6's cause.
- The escape path in item 1 is verified from code and from root's safety-slice probe, not from
  a new occurrence.
- Scratch evidence (open-fault dumps, the evidence blob, the virtual-thread dump) is in the
  session scratchpad, not the repo. The forms are quoted above so they can be re-run.
