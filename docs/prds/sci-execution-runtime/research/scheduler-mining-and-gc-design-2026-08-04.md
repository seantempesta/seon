---
type: research
status: proposed
tags: [storage, flow, operator]
---

# Scheduler mining and root maintenance design — 2026-08-04

## Verdict

Seon had a scheduler, but none of its runtime machinery survives in fresh
`src/`. The final quarry shape was close to the right transaction model:
schedules were durable facts, one pod timer found due schedules, and a due
schedule opened an ordinary durable agent run. Its fatal carryovers were a
central polling ticker, process-local fire detection, minute-wide duplicate
suppression, missed fires after downtime, an unused timezone fact, and an
earlier direct-eval path that could lose failures.

The fresh design keeps only the lessons: schedules and tasks are declared
facts; every nominal fire has a durable identity; the fire is committed as an
ordinary outside-origin message to its owner; and that message is already the
wake for the owner's graph. There is no process-wide scheduler loop. Each
agent graph owns a small schedule proc that arms only its own next timer and
re-derives it from database facts after any change or restart.

The first system portfolio belongs to root: store GC, disk-footprint
observation, dead ephemeral-root reaping, log rotation, and orphan-process
census. These are agent work, not hidden infrastructure. Their fires enter
root's graph, their results are facts, their failures take the normal error or
core-fault path, and root can reason over both in context. The same public
functions in `seon.operator` serve scheduled execution and manual operator
commands. A reset or destructive refork is not complete until the applicable
store history, dead claimed roots, and logs have been reclaimed.

This is a design for owner review. It makes no production change.

## Scope and sources read

I read the named authorities end to end: the active plan README, the fresh
agent-runtime architecture, the disk-burn forensics report, the archived
scheduler research, the historical scheduler and loop at their last useful
revision, and the scheduled-function execution design. I also read the
current operator, registry, launcher, message, Flow, Datahike GC, and Konserve
GC seams needed to test the design against the tree.

### Dependency ledger

| Boundary | Pinned source | First-party seam |
| --- | --- | --- |
| Agent graphs and wakes | core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`: `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `flow/impl.clj`, and `flow/spi.clj` | `src/seon/flow.clj`, `src/seon/cluster/loop.clj`, `src/seon/cluster.clj` |
| Safe store collection | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`: `reference-code/datahike/src/datahike/gc.cljc` and `writer.cljc` | `src/seon/cluster/registry.clj:327` |
| Blob sweep | Konserve `89795ae1b769aafd47adf4168e2393d7b4721bc2`: `reference-code/konserve/src/konserve/gc.cljc` | the `collect!` reachability extension in `src/seon/cluster/registry.clj` |
| One operations owner | fresh tree at `7eeff3e70`: `src/seon/operator.clj` | `bin/seon` and `script/seon/fresh_operator.clj` |
| Ordinary durable wake | `resources/seon/schemas/seon.cluster.message.edn`, `src/seon/cluster/message.clj`, `src/seon/cluster/loop.clj` | a commit containing `:seon.cluster.message/to` wakes the addressed graph |

## Mining method

The quarry sweep used `rg` over `src-old/`, fresh `src/`, schemas, and docs for
`schedule`, `cron`, `timer`, `fire`, `interval`, and `recurring`. History was
searched across all refs with `git log --all --oneline -S` for each term, then
the relevant revisions were opened rather than inferred from commit subjects.
The useful history concentrates in these commits:

- `2d25aad28` introduced one ticker and schedule firing.
- `f3fdd7963` established the durable run model around which it evolved.
- `a83160e0a`, `79a533f1b`, and `ec4bd5faa` worked through recovery, run-budget,
  and repeated-crash consequences.
- `e13de97dd` replaced direct scheduled evaluation with opening a run from the
  due function facts.
- `8d5081e3d` and `f09627055` deleted the old scheduled-eval/pod machinery.

## What existed

### Declared facts

At `e13de97dd`, `src/seon/agent/schedule.cljs` queried entities with:

- `:seon.agent.schedule/id` — unique schedule identity;
- `:seon.agent.schedule/cron` — a five-field cron string;
- `:seon.agent.schedule/fn` — one or more qualified function symbols;
- optional `:seon.agent.schedule/timezone`;
- optional `:seon.agent.schedule/concurrency-policy`; and
- ownership through the agent's component-many `:seon.agent/schedules` ref.

When a schedule fired, the opened run recorded the exact ordered function
symbols in `:seon.agent.run/fns`. That was an important improvement over
looking the schedule up again later: the work admitted at fire time remained
inspectable even if the declaration changed.

### Pure cron calculation

The namespace contained a pure hand-written parser and predicates for a
standard five-field expression. It supported wildcards, lists, ranges, and
steps; treated day-of-month and day-of-week with cron's OR rule; and found the
next instant by scanning minute boundaries for at most 366 days. The functions
accepted the clock instant explicitly, which made due calculation testable.

The timezone fact was not honored. Calculation used host-local time, so the
stored declaration promised more than the implementation delivered. The new
design must use `java.time.ZoneId` and `ZonedDateTime`, and must specify DST
behavior rather than port this parser.

### Process and transaction shape

One pod-level `setInterval`, normally every 30 seconds, polled all schedules.
For each due schedule it attempted a transaction that both created the run and
CASed the agent's absent current-run pointer. The database transaction was the
contention point; the ticker did not evaluate the function itself in the final
shape. The resulting database interest woke the JVM driver.

Duplicate prevention was derived from previous schedule-run start instants for
the same agent and minute. This prevented most double polls but conflated two
tasks owned by one agent, gave no independently queryable fire, and did not
recover a nominal instant missed while the process was down. The advertised
`:allow` concurrency policy was effectively deferred by the single current-run
CAS.

An earlier shape invoked scheduled evaluation directly from the timer. The
archived research records a tier-less error result that returned `done: false`
without a receipt or durable turn. That failure is why a fire must enter the
same message/run/fault machinery as user work rather than call an evaluator.

A schedule-specific crash breaker was briefly added and later removed as the
durable run path became the owner. That is the right lesson: repeated failures
belong to the normal error/fault and root reasoning paths, not a scheduler-only
breaker with a second policy vocabulary.

## What survives in fresh Seon

No scheduler namespace, proc, timer owner, `:seon.schedule*` declaration, or
time-wake transaction exists in fresh `src/` or `resources/`. There is no
implementation to preserve.

Three pieces of current architecture already anticipate the boundary:

- `AGENTS.md` names user input, scheduled fires, and remote-call responses as
  the three transaction sources.
- `src/seon/cluster/work.clj:384` already classifies a schedule fire like a
  human message: an outside-origin message has neither `from` nor `about` and
  starts a new episode.
- `:seon.cluster.message/to` is already the durable wake attribute. The
  listener delivers an attribute-indexed, payload-free kick; the owning graph
  queries facts and claims work.

The fresh architecture text is now stale against the owner ruling:
`docs/seon/architecture/agent-runtime.md:15` and `:192` explicitly say there
is no scheduler or schedule entity. After this proposal is approved, that
target must be updated in the implementation slice. It was deliberately not
edited in this research-only lane.

## Fresh scheduler design

### Declared data

A scheduled task is an entity identified by
`:seon.schedule.task/id`, with these required connections:

- `:seon.schedule.task/owner` → `:seon.cluster.agent/id`;
- `:seon.schedule.task/function` → the qualified `:seon.fn/sym` to call; and
- `:seon.schedule.task/schedule` → a schedule entity.

The owner namespace is derived through the owner's existing
`:seon.cluster.agent/namespace` connection and checked against the function's
namespace. It is not copied onto the task. Agents in ordinary namespaces can
commit their own task facts through ambient `seon.db/transact!`; the
transaction function refuses an owner other than the calling agent. Root's
first-party portfolio is admitted by the same transaction shape during root
initialization.

A schedule entity declares an expression and an IANA timezone:

- `:seon.schedule/id` — identity;
- `:seon.schedule/cron` — five-field expression; and
- `:seon.schedule/timezone` — `ZoneId` name.

Cron parsing belongs in one pure namespace and should use a pinned maintained
parser after source review. No copy of the quarry parser and no regular
expression enter fresh production code. The contract must settle skipped and
repeated local times: a nonexistent local minute does not fire; an overlapping
local minute fires once at each distinct instant. The durable instant identity
makes that distinction unambiguous.

A fire is a fact, not a timer callback:

- `:seon.schedule.fire/id` — a unique tuple of task identity and nominal UTC
  instant;
- `:seon.schedule.fire/task` — task ref;
- `:seon.schedule.fire/nominal-at` — the scheduled instant; and
- `:seon.schedule.fire/observed-at` — when the owning proc observed it.

The same transaction upserts the fire and an ordinary
`:seon.cluster.message` addressed to the task owner. To preserve the existing
outside-trigger rule, the fire message has neither `from` nor `about`; a new
explicit `:seon.cluster.message/schedule-fire` ref connects it to the fire and
supplies system provenance without overloading `about`, which is currently
treated as error-recorder provenance. Message identity is derived from the
fire identity. The unique fire tuple makes retry idempotent at Datahike's
serial writer.

### Flow, not a central loop

Each agent graph gains one `:io` schedule proc. It owns no durable state. From
the current database value it derives that agent's earliest uncommitted nominal
instant and arms one process-local timer. Three events cause it to re-derive:

- its timer matures;
- an attribute-indexed database listener observes a relevant task, schedule,
  or config change; or
- the graph starts or resumes after process loss.

The timer callback offers a payload-free kick to that graph's fixed input. The
proc then reads the current database value and transactionally commits every
due fire up to the declared recovery bound. The resulting message is the
existing loop wake. A task does not execute on the timer thread, in the
schedule proc, or in a new dispatcher.

Catch-up is explicit per task, not accidental polling behavior. The initial
contract should be `:latest`: after downtime, commit only the newest missed
nominal instant plus any currently due instant. Later accretion may add a
bounded replay key without changing `:latest`. Unlimited replay is refused
because it can manufacture an unbounded work storm after a long outage.

Topology changes rebuild the graph. Timer state is disposable; all future work
re-derives from task and fire facts. Schedule-proc exceptions ride Flow's
`error-chan` into the fault committer. A task function's returned flat error is
recorded by the normal run path and rendered into the owner agent's context.

## Root's maintenance portfolio

The root agent declares five scheduled tasks. The task function returns a
value the run loop interprets by calling the one system-side operations owner;
the timer itself never performs maintenance.

| Task | Proposed default | Why |
| --- | --- | --- |
| Disk-footprint observation | daily at 02:00 | Cheap enough to establish a trend and detect pressure before weekly GC. It also runs after every manual cleanup. |
| Dead ephemeral-root reaping | daily at 02:15 | Owner-exit events remain the primary trigger; this is reconciliation for missed shutdowns and crashes. |
| Log rotation | daily at 02:30 | Bounds high-churn text before it becomes the next disk incident. |
| Orphan-process census | hourly at minute 5 | Process identity checks are cheap and stale children can continue consuming disk and ports between daily passes. |
| Store GC | Sunday at 03:00 | Head-only GC is high-value but materially heavier than observation; weekly limits disruption while the daily observer can request an early run under pressure. |

All local-time defaults use
`:seon.config.maintenance/timezone` (default: the process root's explicitly
applied timezone, with `UTC` as the closed bootstrap fallback). Each row has
its own config-fact cron expression:

- `:seon.config.maintenance/footprint-cron` = `"0 2 * * *"`;
- `:seon.config.maintenance/reap-roots-cron` = `"15 2 * * *"`;
- `:seon.config.maintenance/rotate-logs-cron` = `"30 2 * * *"`;
- `:seon.config.maintenance/process-census-cron` = `"5 * * * *"`; and
- `:seon.config.maintenance/store-gc-cron` = `"0 3 * * 0"`.

These config facts are the schedule entities' values, not a second copy.
Config reconciliation upserts the globally identified schedule entity each
task references. A live config transaction wakes root's schedule proc and
re-arms it immediately.

### Recorded results

Every invocation commits one `:seon.maintenance.run/id`, connected to its task
and fire, with start and completion instants. Attributes are additive and
operation-specific rather than a `type` field:

- GC: retained and reclaimed keys/bytes, branch-head commit IDs, elapsed time,
  and the second-pass zero-reclaim result;
- footprint: path claim, filesystem, apparent/allocated bytes, usable/total
  bytes, and reclaimable/legitimate classification;
- reaping: exact released/dead claims removed, refused ambiguous claims, and
  reclaimed bytes;
- rotation: exact log claims rotated/removed and reclaimed bytes; and
- census: recorded process identities, observed live identities, orphaned
  identities, and actions or refusals.

Thus “when did maintenance last run and what did it do?” is a Datalog query.
A flat error is connected to the maintenance run; a core exception follows the
fault committer and retains task, fire, root agent, namespace, process, and
proc provenance.

## Disk observation and legitimate-pressure notification

The footprint task first records a mark-only census. It classifies bytes from
facts, never from path naming conventions:

- **reclaimable** — unreachable Datahike/Konserve keys under the selected
  retention contract, released or exactly owner-dead directory claims, and
  logs beyond their declared retention;
- **legitimate Seon use** — retained branch heads and referenced blobs, active
  claimed roots, and retained logs; and
- **legitimate external use** — filesystem consumption not owned by a Seon
  claim. Root reports it but never inspects or deletes user data to explain it.

Root first invokes the applicable maintenance operations for reclaimable
bytes, then measures again. Notification is evaluated on the after-measurement
using two config facts: minimum usable space of 50 GiB and minimum usable ratio
of 10%. Falling below either threshold is pressure. The absolute bound protects
small working margins on large disks; the ratio protects smaller disks. Both
defaults are proposals and remain sparse, database-backed cluster config.

If pressure remains and the remaining cause is legitimate, root sends the
human an ordinary message:

> Your drive is filling. Here is what is using it, here is what I already
> reclaimed, and these are your options.

The message links `about` to the footprint measurement and includes retained
Seon use, external filesystem use, reclaimed bytes, current usable space, and
concrete non-destructive options. Root never silently degrades, deletes user
data, or asks the user to understand GC. Agents fix what agents own; the user
decides what to do with the user's drive.

This exposes one fresh missing contract. Current messages require `to` to be
an agent ref, while an agent completion for a human is only a surface. The
implementation slice must accrete an ordinary human-recipient arm to the same
message family and transcript—not add a notification table, alert queue, or
UI-only side channel. A root-authored human message has `from` = root, a human
recipient connection, and the measurement in `about`. The web transcript and
future external delivery render that same fact. Notification occurs on the
threshold crossing; a continuing condition remains visible from the same
measurement facts rather than generating daily duplicate messages. A new
message is warranted only after recovery above the threshold followed by a
new crossing.

## Head-only store GC and quiescence

The disk forensics report measured 374.76 GiB allocated in the shared store.
Keeping every present branch head and every fact-referenced blob key required
17.08 GiB; head-only collection predicted 357.36 GiB reclaimable (95.44%). The
existing no-cutoff `collect!` could reclaim only about 3.97 GiB because it
retains complete ancestry.

The one GC operation is `seon.operator/collect!`, delegating physical marking
to an extended `seon.cluster.registry/collect!` that accepts
`:datahike.gc/remove-before`. Its scheduled policy is head-only: retain every
present branch head, its indexes, and every referenced blob key; old commit IDs
and historical database values are not retained promises. It performs the
forensics recipe:

1. Acquire the process-root maintenance gate while the same JVM holds the
   lifetime flock and Datahike writer.
2. Commit a mark-only maintenance run containing the exact branch roster,
   branch-head commit IDs, retained/reclaimable keys, and bytes.
3. Request quiescence of every cluster graph in that process. Each graph stops
   accepting new episode kicks, finishes its current transform and terminal
   transaction, and publishes an in-process completion event. Root waits for
   those events, not a sleep or guessed timeout. Remote calls may finish; no
   new run is opened while quiesced.
4. Re-read branch heads. If any differs from the recorded mark, discard that
   mark and derive it again. Once stable, call Datahike's writer-owned
   `gc-storage` with `remove-before` equal to the census instant. Its safe point
   still protects concurrent writer mechanics; quiescence exists to make the
   before/after evidence stable, not to replace Datahike safety.
5. Reopen/query every branch, verify every recorded head and referenced blob,
   record reclaimed counts/bytes, then run the same mark a second time and
   require zero additional reclaim.
6. Resume graphs and release the maintenance gate. A process failure needs no
   remembered resume flag: boot reconstructs graphs from facts and records the
   interrupted maintenance run.

Flow's existing `pause` call alone is not an acknowledgement that an in-flight
transform settled. The implementation therefore needs one explicit graph
quiescence completion event at the cluster graph owner. This is process-local
coordination, not durable status. If a graph cannot acknowledge, GC refuses
and records the exact graph/proc boundary; it does not force deletion.

## One cleanup owner, two entry points

`src/seon/operator.clj` is the public in-JVM operations namespace. It already
uses namespaced map inputs, delegates to the physical owner, carries no
lifecycle state, and returns flat errors through its `attempt` boundary. It is
the one public owner for:

- `observe-footprint!`;
- `collect!`;
- `reap-dead-roots!`;
- `rotate-logs!`;
- `census-processes!`;
- `cleanup-cluster!`; and
- `cleanup-root!`.

There are not scheduled variants and manual variants. Root task functions call
these operations directly. `script/seon/fresh_operator.clj` asks a live JVM to
call the same functions through prepl; when all JVMs are down, it starts a
short-lived maintenance JVM under the selected process-root flock, calls the
same operation, and then continues publication/refork. The Babashka launcher
must not retain a second recursive-delete implementation.

### Exact manual seams

- `bin/seon:4-18` remains only root selection and dispatch. It adds no cleanup
  logic.
- `script/seon/fresh_operator.clj:2575` `reset!` keeps the `--force` gate and
  down/process-identity proof, then calls `seon.operator/cleanup-root!` before
  republishing and reforking default.
- `script/seon/fresh_operator.clj:2560`
  `destroy-cluster-data-with-flock!` is deleted after its behavior is absorbed
  by that operation. The script-local recursive-delete path must not survive.
- `script/seon/fresh_operator.clj:1914` `named-init-form` changes both live
  `seon.cluster/refork!` and dormant `seon.cluster.registry/reset-cluster!`
  force arms to call `seon.operator/cleanup-cluster!`.
- `src/seon/operator.clj:132` `refork!` becomes the public refork plus complete
  cleanup operation. `src/seon/cluster.clj:2205` remains lifecycle mechanics,
  not a second cleanup policy.
- `src/seon/cluster/registry.clj:224` `reset-cluster!` and `:253`
  `retire-branch!` remain physical branch operations. Their current promise
  that a later `collect!` reclaims the tail is insufficient for `--force`;
  cleanup calls the real head-only collection before reporting success.

`cleanup-cluster!` removes the retired branch's unreachable store history,
released/dead ephemeral-directory claims belonging to that cluster, and its
declared logs. `cleanup-root!` removes the selected root's store and all exact
claims/logs it owns after process shutdown. Both use no-follow deletion,
canonical containment beneath the declared root, and refuse unknown or
ambiguous paths. They never sweep `tmp/` by age or name.

Directory ownership therefore needs the facts proposed by the forensics
report: directory identity/path, claim, owning exact process identity, parent
claim, and reap-on-owner-exit declaration. A clean shutdown releases the
claim; an orphan census may reap only a released claim or one whose `(pid,
start-instant, generation)` is proven dead. This is how the daily root task and
`reset --force` share one safe definition of garbage.

Success from reset means disk was actually reclaimed. The operator response
includes store, directory, and log bytes reclaimed plus every refused claim.
Any refusal makes the command nonzero and leaves queryable evidence; it must
not print a clean-reset shape while debris remains.

## Acceptance boundary for implementation

Owner approval should precede production edits. The implementation slice is
complete only when these falsifiers pass:

- an agent commits a task fact, the owning graph arms it, one nominal instant
  produces exactly one fire and ordinary message, and restart derives the same
  next instant without duplicate execution;
- two tasks for one agent at one minute produce two distinct fires;
- timezone gap/overlap cases have explicit, tested results;
- each root portfolio task is queryable with its schedule, last run, result,
  and any error;
- the forensics head-only mark is reproduced, quiescence is acknowledged,
  every recorded branch head/blob reopens, and the second collection reclaims
  zero;
- footprint pressure first reclaims agent-owned garbage, then creates exactly
  one ordinary human message on a legitimate-use threshold crossing;
- scheduled and manual calls reach the same `seon.operator` Vars;
- `bin/seon reset --force`, live `init NAME --force`, and dormant
  `init NAME --force` reclaim store history, exact dead root claims, and logs;
- a symlinked sentinel outside a claimed root survives every cleanup; and
- no central ticker, background Datahike GC loop, notification queue, hidden
  status atom, or second recursive-delete implementation exists.

## Ugly output and unresolved truth

No ugly runtime rendering was exercised in this design-only lane. The mining
commands themselves produced very broad, truncated history output for generic
terms such as `timer` and `interval`; narrowing by commit and reading the files
resolved it without hiding a product defect.

One current product output would be dishonest if implemented without the
seams above: `reset --force` can presently report success after deleting the
cluster directory even though unclaimed scratch roots and other logs remain.
The design makes reclaimed and refused bytes part of the result so that output
cannot stay success-shaped while disk remains dirty.
