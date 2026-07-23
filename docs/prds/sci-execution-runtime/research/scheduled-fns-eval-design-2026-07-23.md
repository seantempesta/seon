---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Scheduled functions — live failure proof and durable-turn design (2026-07-23)

Read-only probe and design deliverable for U9 blocker B2 in
[[u9-deletion-plan-2026-07-23]]. The probe used only the isolated `b2probe`
cluster and ended with `SEON_CLUSTER_DIR=data/clusters/b2probe bin/seon down`.
No source was edited.

## 1. Verdict

**B2 is live-proven broken.** A real cron fire does not execute its scheduled
function and does not record an eval receipt. The tier-less eval invocation
returns a `:seon.execution.message/error` value saying:

```clojure
{:seon.execution/message :seon.execution.message/error
 :seon.execution/protocol-version 3
 :seon.execution/error
 {:seon.error/message
  "The eval batch has no selected execution-plan tier."
  :seon.error/kind :core-bug
  :seon.error/data {:seon.error/kind :core-bug}}
 :seon.db/db
 {:db-name "b2probe"
  :t 536871414
  :datahike/commit-id
  #uuid "6a628350-193c-5e0e-80df-5cdd4a752714"}}
```

`turn/eval-parsed!` converts that host envelope to its error value
(`src/seon/agent/turn.cljs:534-586`). `turn/open-turn!` treats a returned map
as successful body completion and closes the scheduled turn `:done`; it marks
`:error` only when the body throws (`src/seon/agent/turn.cljs:663-731`).
`fire-schedule!` then ignores the awaited executor's return value and drives
the run (`src/seon/agent/schedule.cljs:413-433`). The observed outcome is
therefore:

1. the scheduled eval is **rejected as an error value**;
2. that value is **silently discarded**;
3. the scheduled turn is falsely committed `:done` with no eval receipt; and
4. the subsequent ordinary run drive can fail for an unrelated prompt-render
   reason.

The recommended U9 design is accepted in principle: **one durable eval-ready
scheduled turn plus a database wake, executed through the portable claimant
and the existing JVM receipt path**. It is the only alternative that removes
the child eval surface, survives process replacement after admission, obeys
R26, and keeps one run/claim/phase/receipt state machine. It is not
implementation-ready until the owner rules on the open contracts in §8,
especially the scheduled payload, post-eval run behavior, fire identity, and
missed-fire policy.

## 2. Grounding and dependency ledger

The live probe began from Seon `a362c16e0` while shared-tree commits continued;
current citations were reverified at `39b2527866d4dce4b0960a154d50e13505a506fe`.
Across that interval, the B2 behavior owners
`agent/{schedule,loop,driver}*`, `execution/host.cljs`, and the behavioral
parts of `agent/turn.cljs` did not change. The only relevant diff relocated
turn/eval schema registration to its portable owner.

Exact dependency basis:

- Clojure `1.12.0` and Malli `0.20.0` are the root/JVM basis
  (`deps.edn:6-7`, `deps.edn:20-21`).
- The writer and pod use maintained Datahike source at
  `reference-code/datahike`, current SHA
  `9c356e32a0f2b0afcd41ce5000cba2a575a59a8a`; both aliases select that
  checkout (`deps.edn:23-26`, `deps.edn:167-173`). The relevant dependency
  mechanism is serialized transaction order plus Datahike CAS; Seon's
  first-party precedent is `run/open-run!`, whose one transaction creates the
  run and CASes the agent's absent run pointer
  (`src/seon/agent/run.cljs:440-527`).
- JVM SCI uses `reference-code/sci`, current SHA
  `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; the host and CLJS aliases both
  select that checkout (`deps.edn:53-64`, `deps.edn:124-137`). Scheduled code
  must enter SCI only through the already-guarded JVM eval door.
- ClojureScript is `1.12.145`, Shadow is pinned at
  `c98bf60f70c102abda0fd385f78cc0fcd9c25408`, and the pod's Datahike
  override remains the maintained source checkout (`deps.edn:137-149`,
  `deps.edn:167-173`).
- No external cron scheduler is involved. Seon parses five-field cron facts
  itself and a pod `setInterval` checks them every 30 seconds
  (`src/seon/agent/loop.cljs:630-690`).

First-party mechanisms that constrain the design:

- schedule definitions are component entities owned through
  `:seon.agent/schedules` (`src/seon/agent.cljs:105-119`) with a qualified
  function symbol, cron, timezone, and concurrency policy
  (`src/seon/agent/schedule.cljs:24-45`;
  `docs/seon/architecture/data-model.md:723-731`);
- run opening is already one writer-serialized CAS boundary
  (`src/seon/agent/run.cljs:440-527`);
- claim arbitration and work execution are portable database-driven state
  (`src/seon/agent/driver.cljc:49-107`, `src/seon/agent/driver.cljc:371-469`);
- the JVM claimant always advertises eval and runs it on the bounded eval pool
  (`src/seon/agent/driver/host.clj:60-66`,
  `src/seon/agent/driver/host.clj:272-316`);
- pod claimants deliberately advertise render/LLM/publish, not eval
  (`src/seon/agent/driver/pod.cljs:32-67`); and
- every form's `:running` receipt commits before SCI executes it
  (`src/seon/host/eval.clj:335-380`;
  `src/seon/eval/receipt.cljc:69-99`).

The accepted U9 plan already identifies the exact orphaned consumer and
requires this live falsifier before the re-point
(`docs/prds/sci-execution-runtime/research/u9-deletion-plan-2026-07-23.md:91-96`,
`:177-188`, `:213-220`). The high-level ledger likewise leaves B2's live proof
and owner decision pending
(`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:843-874`).

## 3. Live probe

### 3.1 Setup and real runtime path

The cluster was started with:

```bash
SEON_CLUSTER_DIR=data/clusters/b2probe \
  bin/seon up >> tmp/orchestrator/b2probe-up.log 2>&1
```

The operator logged readiness in
`tmp/orchestrator/b2probe-up.log:529`. A concurrent hot reload caused one
database timeout and configured core-fault exit; the cluster was reaped by its
own supervisor and restarted without reset. A transient `clojure -P` JDK
`HashMap` exception and one stale b2probe web-render record required clean
supervisor retries. No other cluster was addressed.

The probe:

1. created `bright-hoops-cross` through `seon.agent/start!`, the normal
   capability-gated child birth path (`src/seon/agent.cljs:1145-1167`);
2. allocated schedule identity `j5gyhv9zixiu` through the managed identity
   allocator (`src/seon/db/id.cljc:1444-1474`);
3. attached the real component schedule to the agent:
   `* * * * *` →
   `my.agent.bright-hoops-cross/b2-scheduled-probe`; and
4. waited for the installed runtime ticker rather than calling
   `fire-due-schedules!` directly.

The schedule transaction committed at basis transaction `536871310`,
`2026-07-23T21:04:54.571Z`. The ticker had been installed at
`2026-07-23T21:03:30.771Z`
(`logs/operator/pod/b1887016-39de-4f05-b1b0-e9d3c092940c.log:288`).
The actual ticker injects `exec-scheduled-fns!` and `drive-run!`
(`src/seon/agent/loop.cljs:654-669`), so the probe covered the disputed
production path.

There is no `my.plan` cron-registration API at current HEAD. Two mechanisms
must not be conflated:

- agent lifecycle resume installs each agent's stable inbound-message wake
  trigger (`src/seon/agent/lifecycle.cljc:133-195`;
  `src/seon/agent/loop.cljs:560-598`);
- `start-runtime!` separately restores generated-code root schedulers, then
  installs the one cron/deadline ticker
  (`src/seon/client.cljs:2345-2395`).

Cron truth is the schedule component data queried on every tick
(`src/seon/agent/schedule.cljs:314-377`), not a process-local `my.plan`
registration. The probe used that real data path.

### 3.2 Committed result

The ticker opened schedule run `quf041ifqyoj` at
`2026-07-23T21:05:00.928Z`:

```clojure
{:seon.agent.run/id "quf041ifqyoj"
 :seon.agent.run/trigger :schedule
 :seon.agent.run/status :open
 :seon.agent.run/claimant "3980@2026-07-23T21:00:46.179Z"
 :seon.agent.run/claim-epoch 1
 :seon.agent.run/turn-limit 1}
```

It opened scheduled turn `gg72n1o8ulpl` at
`2026-07-23T21:05:01.271Z`. Historical datoms show:

```clojure
[:seon.agent.turn/status :running 536871312 true]
[:seon.agent.turn/scheduled? true 536871312 true]
[:seon.agent.turn/run 6483 536871312 true]
[:seon.agent.turn/status :running 536871313 false]
[:seon.agent.turn/status :done 536871313 true]
```

The resulting turn pull was:

```clojure
{:seon.agent.turn/id "gg72n1o8ulpl"
 :seon.agent.turn/run {:seon.agent.run/id "quf041ifqyoj"}
 :seon.agent.turn/scheduled? true
 :seon.agent.turn/prompt-chars 0
 :seon.agent.turn/rendered-tx {:db/id 536871311}
 :seon.agent.turn/status :done}
```

There was no `:seon.agent.turn/phase`, no
`:seon.agent.turn/evals` connection, and an exact query for eval links
returned `#{}`. An exact query for a persisted
`"The eval batch has no selected execution-plan tier."` fault also returned
`#{}`. This falsifies the old comments' promise that every broken scheduled
function records a failed eval without crashing the ticker
(`src/seon/agent/loop.cljs:488-507`,
`src/seon/agent/schedule.cljs:226-236`).

### 3.3 Exact routing envelope

The scheduled executor builds its synthetic source and calls
`turn/eval-parsed!` without a selected tier
(`src/seon/agent/loop.cljs:515-558`). That function builds the eval request and
calls `invoke-compiled!`, again without a tier
(`src/seon/agent/turn.cljs:534-553`). `invoke-now!` rejects exactly that shape
before any tier is entered
(`src/seon/execution/host.cljs:977-989`).

After the real fire, a non-mutating diagnostic called this private routing
decision directly with a compiled tier-less eval invocation. It returned the
exact envelope in §1 at database value `t=536871414`. This direct route call
was necessary because the real executor discards the returned value.

The envelope is an ordinary host error map constructed with the invocation id
and immutable database value (`src/seon/execution/host.cljs:125-135`).
`eval-parsed!` extracts only `::execution/error`
(`src/seon/agent/turn.cljs:583-586`), and `close-turn!` selects no error keys
from that returned map before asserting `:done`
(`src/seon/agent/turn.cljs:663-705`). This explains every observed datom.

### 3.4 Downstream fault is secondary

After swallowing the eval error, `fire-schedule!` called the run driver
(`src/seon/agent/schedule.cljs:426-433`). The old scheduled turn had already
closed and had no durable phase, so the driver treated the run as
`:unstarted` and began an ordinary render phase
(`src/seon/agent/loop/core.cljc:50-79`). Prompt rendering then failed while
preparing authored call `my.plan.internal/plan-block`:

```text
SEON-CORE-FAULT No current authored source matches the invocation.
{:seon.execution/agent-id "bright-hoops-cross",
 :seon.execution/function-symbol my.plan.internal/plan-block,
 :seon.error/kind :agent} @basis-t=536871314
```

The exact log is
`logs/operator/pod/b1887016-39de-4f05-b1b0-e9d3c092940c.log:294`; line 295
records `on-core-error :crash — exiting after persisting the fault datom`.
Persisted fault entity `6487` contains:

```clojure
{:seon.error/fault :core
 :seon.error/kind :agent
 :seon.error/message "No current authored source matches the invocation."
 :seon.error/basis-t 536871314
 :seon.error/commit-id
 #uuid "6a6281fd-6b95-5f2d-8e77-61ade12cd53d"
 :seon.error/data-edn
 "{:seon.execution/agent-id \"bright-hoops-cross\",
   :seon.execution/function-symbol my.plan.internal/plan-block,
   :seon.error/kind :agent}"}
```

That is a real additional defect, but it is not the scheduled-eval cause.
The causal B2 evidence is the earlier false-`:done`, receiptless scheduled
turn plus the exact tier-less host envelope.

## 4. Current scheduler and restart boundary

The current scheduler is process-local pod machinery:

- one `setInterval` checks wall-clock facts every 30 seconds
  (`src/seon/agent/loop.cljs:630-690`);
- each tick first offers open work to the pod claimant, then queries and fires
  due schedules (`src/seon/agent/loop.cljs:654-669`);
- cold `start-runtime!` resumes every durable agent, restores generated-code
  root schedulers, starts the web surface, and only then installs the ticker
  (`src/seon/client.cljs:2345-2395`); and
- JVM claimants independently listen to committed database changes and scan
  open runs, one virtual thread per admitted run
  (`src/seon/agent/driver/host.clj:487-553`).

The architecture contract is that one scheduler derives due work and opens
runs but does not execute agent work
(`docs/seon/architecture/agent-runtime.md:253-264`). The writer cannot become
that executor: it owns transactions and committed interests only; claimant
JVMs execute agent code
(`docs/seon/architecture/agent-runtime.md:285-296`).

An important correction to the informal recommendation:

- once a scheduler **commits** a fire as a run/turn, a durable turn survives
  every process being down and any claimant can resume it later;
- if every scheduler process is down when a cron instant passes, the current
  system commits nothing. On restart it checks only the current wall-clock
  minute. A durable turn cannot preserve a fire that was never observed.

Therefore catch-up is a separate misfire-policy contract, not a property that
falls out of durable turns. Current double-fire prevention derives only from
whether any schedule run for the agent started in the current minute
(`src/seon/agent/schedule.cljs:336-343`,
`src/seon/agent/schedule.cljs:379-411`).

## 5. Design alternatives

### A. Durable eval-ready turn plus database wake — recommend

The scheduler derives the due functions, writes the fire's durable intent as
an open run plus a `:running`, `:scheduled? true`, eval-ready turn, and stops.
The commit itself wakes claimant scans. A JVM claimant plans and evals the
stored program through the normal guarded door and receipt mechanism. The pod
or another publish-capable claimant finishes the scheduled turn and applies
the ruled post-eval run behavior.

Why this is the right architecture:

- It uses the one writer CAS, run claim, held epoch, phase cursor, and eval
  receipt spine. The intended driver algorithm is explicitly database-value →
  claim → phase → fenced commit
  (`docs/seon/architecture/agent-runtime.md:41-113`).
- JVM eval already advances `:reply-ready → :evaling → :evaled`, derives an
  execution plan, and records the batch
  (`src/seon/agent/driver/host.clj:318-414`).
- Recovery is already receipt-aware: no receipt means no form was admitted
  and exact replay is safe; any running receipts are terminalized
  `:interrupted` rather than blindly replayed
  (`src/seon/agent/driver/host.clj:416-452`).
- It deletes `exec-scheduled-fns!`, `turn/eval-parsed!`,
  `invoke-compiled!`, and the child eval arm on U9's intended dependency
  boundary instead of creating a replacement transport.
- It obeys R26 because the scheduler only transacts intent and claimant JVMs
  execute SCI. The writer never runs agent code.
- Database interest is only an ephemeral hint to scan; CAS remains authority
  (`docs/seon/architecture/agent-runtime.md:77-108`).

What is not free: the current phase machine assumes every `:reply-ready` turn
came from a successful LLM attempt with a reply blob
(`src/seon/agent/driver/host.clj:193-249`). The current publish phase likewise
re-reads an LLM reply, publishes generated program facts, then closes the turn
(`src/seon/agent/turn.cljs:859-926`). A scheduled turn needs one explicit
payload contract and a scheduled publish/close branch; merely setting its phase
to `:reply-ready` is insufficient.

### B. Pod-local compat eval arm — reject

A temporary arm could attach `:bun` or locally execute the old synthetic
batch until the later leaf-host unit. It is the smallest behavioral patch,
but it preserves precisely the self-host/child eval surface U9 must delete.
It also retains process-local compiler ownership, makes a pod crash between
fire and receipt ambiguous, and creates a compatibility path that cannot prove
the JVM claimant replacement.

This is acceptable only as an explicitly time-boxed emergency rollback that
blocks U9 graduation. It is not a design answer to B2.

### C. Direct claimant RPC — reject

The scheduler could call a JVM claimant directly with the function symbols.
That creates a second admission queue and a second routing/failure protocol:
the scheduler must discover a claimant, handle its death, correlate the RPC,
and somehow reconstruct an unacknowledged request. A fire that loses the RPC
has no database work item; a retry can double-execute. Adding receipts inside
the RPC does not fix the pre-receipt delivery gap.

It violates the target rule that coordination is database data and bypasses
the existing claimant scan/CAS mechanism. It also couples scheduler liveness
to a particular claimant, contrary to replaceable claimant capacity
(`docs/seon/architecture/agent-runtime.md:293-310`).

### D. Self-message as schedule payload — insufficient

An ordinary durable message would wake the existing run machinery, but it
would turn “invoke this function before the agent reasons” into prompt prose.
It neither guarantees eval-before-LLM nor supplies eval receipts for the
scheduled program. Reusing message wakeups as a notification is fine; using
them as the execution authority is a second semantic path.

## 6. Recommended state transition

Subject to §8 rulings, the smallest one-mechanism transition is:

1. **Derive a nominal fire.** From one immutable database value, group due
   schedules for an idle agent in a deterministic order and derive a stable
   fire key from the ruled granularity (currently one agent/minute).
2. **Materialize the program.** Persist an ordinary bounded payload that can
   reconstruct the exact vector of `(qualified-symbol)` forms after restart.
   Do not rely on the scheduler's memory or re-read mutable schedule rows.
3. **Commit admission once.** In one allocation-aware writer transaction:
   assert the fire key is new, allocate/run-link the run and scheduled turn,
   CAS the absent agent-run pointer, set turn `:status :running`,
   `:scheduled? true`, the run ref, immutable basis, payload ref, and an
   eval-ready phase. The existing open-run CAS precedent is
   `src/seon/agent/run.cljs:503-527`.
4. **Wake by facts.** Return after commit. Do not call an eval helper or a
   claimant RPC. The JVM claimant's database interest scans the open run
   (`src/seon/agent/driver/host.clj:538-546`).
5. **Plan and eval normally.** The claimant parses the stored scheduled
   payload in the agent home namespace, derives `plan-execution`, advances
   `:reply-ready → :evaling`, and executes through the existing bounded SCI
   door (`src/seon/agent/driver/host.clj:318-414`).
6. **Use normal receipts.** Each form commits `:running` before evaluation and
   CASes to a terminal state afterward
   (`src/seon/eval/receipt.cljc:69-99`). A missing/unplannable function becomes
   a normal steering or failed-eval value, never a ticker rejection.
7. **Finish the scheduled turn.** A publish-capable claimant recognizes
   `:scheduled? true`, skips LLM-only generated-program publication, advances
   `:evaled → :published`, and commits `:status :done` under the run/epoch and
   phase fences.
8. **Apply the ruled run policy.** The likely compatibility choice is to leave
   the run open: the now-completed scheduled turn remains excluded from work
   count (`src/seon/agent/driver.cljc:119-130`), and the next claimant opens an
   ordinary LLM turn so the agent sees the scheduled eval in its transcript.

Use the existing phase vocabulary if the payload contract can make
`:reply-ready` truthful for “program ready for eval”; otherwise generalize the
phase name once. Do not add a parallel scheduled-phase state machine.

## 7. Restart, idempotency, and double-fire analysis

### 7.1 After admission

Once run, turn, payload, and fire identity commit atomically:

- death before claim leaves an eval-ready turn for the next claimant;
- death after `:evaling` but before any receipt permits exact replay because
  the receipt boundary guarantees no form ran;
- death after a `:running` receipt does **not** prove the form's external
  effect did not occur. Existing recovery marks running receipts interrupted
  rather than replaying them (`src/seon/agent/driver/host.clj:416-452`);
- death after terminal receipts but before scheduled publish resumes from
  `:evaled`; and
- every late claimant publication loses the run/epoch or phase CAS.

This provides once-only **admission** and no blind replay after admission. It
cannot promise exactly-once arbitrary external effects; no general system can
infer whether an effect completed between SCI execution and terminal receipt.
Scheduled functions needing retryable effects must use their capability's
own idempotency contract.

### 7.2 Fire identity

The current guard is weaker than a durable fire identity:

- it is per agent, not per schedule;
- it derives from schedule-run `started-at` in the current minute
  (`src/seon/agent/schedule.cljs:336-343`,
  `src/seon/agent/schedule.cljs:379-411`);
- run open CAS prevents two simultaneously open runs
  (`src/seon/agent/run.cljs:440-527`); but
- the due scan and run creation are separate, and no entity uniquely names
  “schedule X at nominal instant Y.”

The durable design should make the fire itself addressable. A unique identity
derived from the ruled schedule group plus nominal fire instant lets retries,
multiple scheduler processes, and restart all converge on the same event.
Its assertion, run birth, turn birth, and agent-pointer CAS belong in one
transaction. A read-then-write “already fired?” check is not sufficient.

### 7.3 Downtime

Two valid policies exist, but one must be explicit:

- **skip missed** — restart considers only the current cron minute; this
  preserves current behavior; or
- **catch up** — persist/derive the last nominal fire considered and enqueue
  bounded missed fire identities according to a misfire window.

Catch-up must be bounded to avoid replaying months of cron events and must
define how schedule edits, timezone/DST changes, and `:forbid` interact. It is
not required to solve U9 if the owner rules “skip missed,” but documentation
must stop claiming that an unobserved fire survived downtime.

## 8. Owner rulings required

### Semantics

1. Does a fire create an **eval-only scheduled turn followed by an ordinary
   LLM turn** (current intended behavior), an eval-only run that closes after
   the function, or one full LLM turn whose reply/eval includes the schedule?
2. If the scheduled eval has one or more failed receipts, does the run still
   continue to the LLM so the agent can react, or close `:error`?
3. Are all due functions for one agent/minute one ordered batch and one
   scheduled turn, as today, or is each schedule independently fireable?
4. What deterministic order applies to multiple due schedules? Schedule id is
   the natural stable tie-breaker; current query order is not a contract.
5. Does one failed form stop later scheduled forms or does the batch attempt
   all of them?
6. What is the durable result contract for a scheduled function's return
   value beyond the ordinary eval row?

### Payload and code version

7. What exact persisted payload reconstructs the program: a content-addressed
   source blob, a vector of schedule refs/symbol snapshots, or a generalized
   turn program blob?
8. May `:seon.agent.turn/reply-blob` be generalized from “raw LLM reply” to
   “eval program,” or must scheduled source use a distinct/generalized
   attribute? Current code and architecture describe reply blobs as LLM
   evidence (`src/seon/agent/turn.cljs:859-896`).
9. Is a fire frozen to the function symbols and corpus basis observed by the
   scheduler, or may the claimant late-resolve the current symbol/source after
   restart? Schedule attributes are symbol values
   (`docs/seon/architecture/data-model.md:723-731`), but deterministic recovery
   needs a ruled basis.
10. If a schedule is edited or deleted after admission but before claim, does
    its already-committed fire still execute? Recommendation: yes; the fire is
    an event, not a live view.
11. How is batch parse mode represented without a successful LLM attempt?
    Current JVM `reply-program` derives reply-evaluation from the successful
    attempt (`src/seon/agent/driver/host.clj:193-221`). `nil` must not
    accidentally select first-form semantics for a multi-function fire.

### Fire identity and time

12. Is idempotency per schedule/nominal instant or per agent/grouped minute?
13. What is the stable fire identity shape and where is it stored?
14. Is the nominal fire time the cron minute, the observed ticker time, or
    both?
15. Are missed fires skipped or caught up? If caught up, what maximum age and
    maximum count apply?
16. What are timezone and DST semantics? The schema has a timezone field, but
    the current acquisition/firing query carries only cron and function
    (`src/seon/agent/schedule.cljs:314-325`).
17. Does `:concurrency-policy :allow` remain deferred? Today every schedule is
    effectively idle-gated (`src/seon/agent/schedule.cljs:238-244`).
18. When a message wake races a schedule fire, does the losing schedule remain
    pending for catch-up or count as skipped?

### Transcript and lifecycle

19. Does the scheduled turn remain transcript-visible with the current
    narration and eval rows? Recommendation: yes; that is why
    `:scheduled? true` exists (`src/seon/agent/loop.cljs:488-503`).
20. Does the scheduled turn remain excluded from work count? Recommendation:
    yes (`src/seon/agent/driver.cljc:119-130`).
21. What prompt/reply fields are absent on an eval-only turn, and how do UI and
    historical reconstruction render that absence?
22. What transaction provenance identifies scheduler admission versus agent
    eval? The current executor writes the scheduled turn in the agent/repl
    transaction context (`src/seon/agent/loop.cljs:541-558`).
23. Scheduled turns have no human session. Context-only session injection must
    return the existing typed error, never nil or a guessed tab
    (`docs/seon/architecture/context.md:340-358`).

### Process ownership and errors

24. For U9, does the cron observer remain in the surviving Bun pod while
    execution moves to JVM claimants, or move now to a separate claimant-side
    scheduler service? It cannot move into the writer under R26.
25. Which long-term supervised process owns wall-clock observation after the
    Bun pod's remaining surfaces retire?
26. Does the scheduler need its own database-interest rescan in addition to
    its timer, or is cold-start plus periodic scan sufficient?
27. How does an unavailable JVM claimant surface? Recommendation: the durable
    turn remains pending; no scheduler timeout converts absence into failure.
28. Which failures are agent eval receipts versus core faults? A missing
    scheduled function should be an agent/program error value; a broken phase
    or missing payload is a core fault.

## 9. Implementation spec shape

After the rulings, S0b should be specified as one narrow replacement:

### Data and pure transitions

- Define one persisted scheduled-fire identity/event shape, or rule that the
  existing run identity plus a unique nominal-fire assertion is sufficient.
- Generalize one turn payload contract so a claimant can reconstruct an exact
  scheduled program without an LLM attempt.
- Add a pure allocation-aware `schedule-fire-tx-data` builder that creates the
  fire, run, and scheduled turn and CASes the agent pointer in one transaction.
- Add a pure scheduled-turn terminal transition under the existing run/epoch
  and phase fences. Do not introduce a scheduler status enum or stored
  `seen?` flag.

### Runtime

- Replace `fire-schedule!`'s `exec-fn!`/`drive!` callbacks with the one durable
  admission transaction.
- Let the existing JVM database listener wake `driver/scan!`; do not call the
  claimant.
- Extend the JVM reply-program acquisition by the one generalized turn
  payload seam; keep planning, guarded SCI execution, and receipts unchanged.
- Branch the existing publish phase on the scheduled-turn presence fact so it
  closes the turn without LLM-only generated-program publication.
- Preserve the ruled continuation behavior on the same run.

### U9 deletion boundary

- Delete `agent.loop/exec-scheduled-fns!`
  (`src/seon/agent/loop.cljs:488-558`).
- Delete `turn/eval-parsed!`
  (`src/seon/agent/turn.cljs:534-586`).
- Delete `execution.host/invoke-compiled!` and the child/tier-less eval routing
  with U9's S1 cut (`src/seon/execution/host.cljs:977-1026`,
  `src/seon/execution/host.cljs:1263-1283`).
- Remove the scheduler callback schema and injection
  (`src/seon/agent/schedule.cljs:264-276`,
  `src/seon/agent/loop.cljs:654-669`).
- Keep one JVM wire symbol/door only as long as the claimant's host invocation
  contract needs it; do not retain a pod child implementation to preserve the
  name.

## 10. Regression and live-proof list

1. **Actual ticker fire:** isolated cluster, real schedule component, real
   timer; one schedule run and one scheduled turn commit.
2. **JVM execution:** the scheduled turn progresses through the ruled
   eval-ready phase, `:evaling`, `:evaled`, and `:published`; every executable
   form has one terminal eval receipt.
3. **No pod eval:** a source/census assertion proves
   `exec-scheduled-fns!`, `eval-parsed!`, `invoke-compiled!`, and the child
   eval arm are absent after U9.
4. **No explicit claimant RPC:** the schedule transaction alone wakes the JVM
   database listener and completes.
5. **Restart before claim:** stop after the fire transaction, restart, and
   prove the same fire/turn completes once.
6. **Restart after `:evaling`, before receipt:** prove exact replay and one
   receipt per form.
7. **Restart after `:running` receipt:** prove it becomes `:interrupted` and is
   not blindly re-executed.
8. **Restart after receipts, before publish:** prove phase-cursor resume closes
   the same turn without duplicate eval.
9. **Double scheduler race:** two observations of the same nominal fire commit
   exactly one fire/run/turn.
10. **Message/schedule race:** the agent pointer CAS selects one open run and
    applies the ruled pending/skip policy to the loser.
11. **Multiple due functions:** deterministic order, ruled grouping, all
    receipts linked to the one scheduled turn when grouped.
12. **Missing or throwing function:** failed eval receipt is transcript
    evidence; the ticker/scheduler process remains alive; post-error run
    behavior matches the ruling.
13. **Turn limit:** the scheduled turn remains excluded and the subsequent
    ordinary work receives the full configured budget.
14. **Transcript:** scheduled narration, source, result/error, and no-human-
    session behavior render from database facts after reconnect.
15. **Schedule edit/delete after admission:** the committed event follows the
    ruled snapshot behavior.
16. **Downtime:** a skipped or caught-up missed fire is proven exactly as
    ruled, including the bound on catch-up.
17. **R26 topology:** writer process has no SCI/agent-code execution edge;
    claimant JVM owns scheduled eval.
18. **Full U9 gates:** focused JVM claimant/receipt tests, authoritative
    `bin/test-writer`, operator gate, then one isolated reset-boundary live
    proof ending in that cluster's `bin/seon down`.

## 11. Probe teardown and residual evidence

Before teardown, the probe schedule was changed from `* * * * *` to
`0 0 1 1 *` so it could not repeat during inspection. Final teardown used:

```bash
SEON_CLUSTER_DIR=data/clusters/b2probe bin/seon down
```

The supervisor reported the writer and web-render clean, and reaped the
incomplete pod/host workload. The durable probe database remains under
`data/clusters/b2probe`; the complete operator transcript remains at
`tmp/orchestrator/b2probe-up.log`. No other cluster was reset, started,
stopped, or queried.
