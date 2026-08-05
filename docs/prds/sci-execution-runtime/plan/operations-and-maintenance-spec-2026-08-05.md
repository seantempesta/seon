---
type: prd
status: proposed
tags: [prd, operator, maintenance, scheduling, flow, storage]
---

# Operations and maintenance specification — 2026-08-05

## Status and sources read

This is planning only. It makes no production change.

I read the repository `AGENTS.md` end to end; P3, P4, and P20 in
`state-of-the-program-2026-08-05.md`; the complete
[scheduler mining and GC design](../research/scheduler-mining-and-gc-design-2026-08-04.md);
the complete second ruling batch in [the active plan](README.md), including
the R8 turn-free-maintenance ruling; and the current, complete
`script/seon/fresh_operator.clj`, `resources/seon/operator/state.clj`, and
`src/seon/operator.clj`. The operator sources were read at their present tree
state after `7dacba8ba` and `61cbb93ed`; this specification does not reuse the
older line references from the mining report.

I also read the landed scheduler at `fa0095a26` through its current
`src/seon/schedule.clj`, schedule/task/fire schemas, tests, and agent-graph
integration, plus the complete
[initial-forms research](../research/agent-context-mechanism-2026-08-05.md)
needed to place root's report without restoring bootstrap-only machinery.

### Dependency ledger

| Boundary | Selected source | First-party seam |
| --- | --- | --- |
| Per-agent schedule proc and Flow lifecycle | core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; `reference-code/core.async/.../flow.clj`, `flow/impl.clj`, and `flow/spi.clj` | `src/seon/schedule.clj`, `src/seon/cluster/agent.clj` |
| Cron and nominal instants | cron-utils `a3d31f7445376b19d1337c604d3d3b7e986302cc` (9.2.1) | `seon.schedule/next-nominal-after`, `latest-nominal-at-or-before` |
| Branch lifecycle and physical collection | Datahike `c15272730e74fb3f8bba91f6361c268492a99ba7`; `datahike/gc.cljc`, `writer.cljc` | `src/seon/cluster/registry.clj` |
| Blob reachability | Konserve `89795ae1b769aafd47adf4168e2393d7b4721bc2`; `konserve/gc.cljc` | `seon.cluster.registry/collect!` |
| External lifecycle claims | current `resources/seon/operator/state.clj` after `7dacba8ba` | `data/operator/claims/{roots,processes}` |
| Operator readiness and exact process actions | current `script/seon/fresh_operator.clj` after `61cbb93ed` | event-driven prepl/process/socket observations and the one silence backstop |
| Durable errors and wake delivery | `src/seon/error.clj`, `src/seon/flow.clj`, `src/seon/cluster/wake.clj` | `seon.error/commit-tx` creates the existing explanation message; `:seon.cluster.message/to` is the existing wake |

## Decisions

1. `seon.operator` is the single public operations namespace. Scheduled work,
   REPL calls, and `bin/seon` reach the same Vars and the same low-level
   owners. There are no scheduled variants.
2. A scheduled task's existing `:seon.schedule.task/function` is a plain
   one-map handler. A due fire claims a maintenance receipt, invokes that Var
   directly on the owning agent's existing `::schedule` proc, and commits the
   terminal receipt. It creates no model-addressed message on success.
3. A fire executes at most once. A process death after claim produces an
   interrupted receipt on recovery; Seon never repeats the function.
4. A handler failure is committed with the receipt through the existing
   `seon.error/commit-tx` path. Its existing explanation message to root is the
   wake that opens a real turn. The source includes the fire and receipt
   identities, so a distinct failed invocation gets its wake while
   re-observation of the same fire remains idempotent. No maintenance wake
   channel or alert table is added.
5. Root's maintenance report is a query result rendered by a declared
   producer. Receipt facts are stored; report prose is not.
6. Root and process reaping is claim-driven and exact-identity-driven. A path
   name, age, parent PID, or directory pattern never authorizes action.
7. `collect!` calls the exclusive-sweep seam being designed separately. This
   document specifies its public operation contract and result only; it does
   not specify the sweep, its exclusivity mechanism, or the independent
   pre-reference blob guard.

## Boundaries and non-goals

- This design does not settle the exclusive-sweep implementation. The seam is
  `seon.operator/collect!` → `seon.cluster.registry/collect!`, with branch
  creation and collection mutually exclusive in the surviving registry/Datahike
  owner. The parallel design owns how that guarantee is made true.
- This design does not invent the missing human-recipient contract from P5.
  Low disk space may wake root for judgment, but delivery to a human remains a
  separate ruled boundary.
- This design does not add a central scheduler, a maintenance graph, a polling
  loop, a cleanup service, or a second recursive-delete function.
- This design does not restore a bootstrap-only initial-forms path. It supplies
  one ordinary report form for the generic initial-forms declaration to carry
  when that already-planned mechanism lands.
- This design does not add retention clocks. Cron facts say when to inspect;
  exact claims and current observations say what may be changed.

## The operations contract

### Common rules

Every public operation accepts one open namespaced map and returns either its
declared ordinary result map or a flat `:seon.error/value`. Required keys are
validated; extra keys from the common scheduled-fire request are ignored.

The public map carries paths and identities, not live Datahike or Java objects.
When a live JVM owns the selected managed root, `seon.operator` resolves its
held store and instances from `seon.operator.runtime`. When no JVM owns it,
the existing short-lived maintenance-JVM entry acquires the root flock and
calls the same Var. The caller never chooses a different implementation.

All mutating functions obey the installation lifecycle lock already owned by
`seon.operator.state`. A lock-held call is an internal composition detail, not
an agent-authored boolean capability. The implementation slice should replace
the public `:seon.operator/control-lock-held?` assertion with an internal
under-lock call path; a caller must not be able to claim that it holds a lock.

Results contain ordinary data only. A successful result is complete and
truthful. Partial work or a refused target returns a flat error whose
`:seon.error/data` contains the partial result. No function prints success and
then hides debris in a log.

### `collect!`

Contract:

```clojure
(collect!
 {:seon.operator/repository-root <canonical repository root>
  :seon.operator/managed-root <canonical operator root>})
;; => :seon.operator.collect/result | :seon.error/value
```

Successful result:

```clojure
{:seon.operator.collect/store-id <store UUID>
 :seon.operator.collect/managed-root <canonical root>
 :seon.operator.collect/branches
 [{:seon.store/branch <branch keyword>
   :seon.source/commit-id <head commit UUID>} ...]
 :seon.operator.collect/objects-before <non-negative integer>
 :seon.operator.collect/objects-after <non-negative integer>
 :seon.operator.collect/swept-objects <non-negative integer>
 :seon.operator.collect/bytes-before <non-negative integer>
 :seon.operator.collect/bytes-after <non-negative integer>
 :seon.operator.collect/reclaimed-bytes <non-negative integer>
 :seon.operator.collect/verification-pass-swept <non-negative integer>
 :seon.operator.collect/complete? true}
```

`complete?` requires every recorded branch head and referenced blob to reopen
after collection and the immediate verification pass to sweep zero. A failure
returns `:seon.operator/collection-incomplete` with this partial map in error
data. The operation records exact branch/head evidence; it never says only
“GC ran.”

This strengthens the existing physical owner
`seon.cluster.registry/collect!`. The registry continues to perform the
Datahike/Konserve mark and sweep. `seon.operator/collect!` owns selection,
before/after observation, verification, and the public result. It does not
reimplement reachability.

The blocking seam is the separately drafted exclusive sweep: the physical
call must enter the same exclusion boundary as `registry/branch!`,
`ensure-cluster!`, and reset/refork branch creation. This document neither
chooses that lock nor restates the sweep algorithm.

### `census-processes!`

Contract:

```clojure
(census-processes!
 {:seon.operator/repository-root <canonical repository root>
  :seon.operator/managed-root <canonical caller root>})
;; => :seon.operator.process-census/result | :seon.error/value
```

Successful result:

```clojure
{:seon.operator.process-census/observed-at <instant>
 :seon.operator.process-census/roots
 [{:seon.operator.claim/id <root-claim UUID>
   :seon.operator.claim/root <canonical path>
   :seon.operator.claim/creator
   {:seon.dev.process/pid <long>
    :seon.dev.process/start-instant <instant string>}
   :seon.operator.claim/reap-on-owner-exit? <boolean>} ...]
 :seon.operator.process-census/processes
 [{:seon.dev.process/generation <UUID>
   :seon.dev.process/pid <long>
   :seon.dev.process/start-instant <instant string>
   :seon.dev.process/root <canonical path>
   :seon.operator.process-census/alive? <boolean>
   :seon.operator.process-census/responsive? <boolean>
   :seon.operator.process-census/advertisements [<cluster names> ...]} ...]
 :seon.operator.process-census/dead [<exact process identities> ...]
 :seon.operator.process-census/unresponsive [<exact process identities> ...]
 :seon.operator.process-census/unclaimed [<observed exact identities> ...]
 :seon.operator.process-census/claim-errors [<flat error values> ...]
 :seon.operator.process-census/complete? true}
```

“Alive” means PID plus recorded start instant still matches. “Responsive”
means a prepl associated with that exact process answered. Generation remains
part of a managed process record even though OS liveness is the `(pid,
start-instant)` identity. Observation of an unclaimed JVM may use its explicit
`-Dseon.operator.root` process property, but may not authorize signaling or
deletion.

Unreadable claims make the census incomplete and return
`:seon.operator/process-census-incomplete` with the partial result. Finding a
dead, unresponsive, or unclaimed process is data, not itself a thrown core
fault. The scheduled policy may classify an actionable anomaly as its flat
error result so root is woken.

This strengthens `seon.operator.state/existence` and the current process and
advertisement observations in `script/seon/fresh_operator.clj`. The
implementation consolidates those observations under
`seon.operator.state`; `bin/seon status`, `down`, the scheduled census, and
the reaper consume the same result. The script-local parallel census is then
deleted.

### `reap-dead-roots!`

Contract:

```clojure
(reap-dead-roots!
 {:seon.operator/repository-root <canonical repository root>
  :seon.operator/managed-root <canonical caller root>})
;; => :seon.operator.reap/result | :seon.error/value
```

Successful result:

```clojure
{:seon.operator.reap/observed-at <instant>
 :seon.operator.reap/census <process-census result>
 :seon.operator.reap/eligible-root-claims [<root-claim UUIDs> ...]
 :seon.operator.reap/stopped-processes
 [{:seon.dev.process/generation <UUID>
   :seon.dev.process/pid <long>
   :seon.dev.process/start-instant <instant string>
   :seon.operator.reap/stop-path :prepl|:sigterm|:sigkill|:already-exited} ...]
 :seon.operator.reap/roots
 [{:seon.operator.claim/id <root-claim UUID>
   :seon.operator.claim/root <canonical path>
   :seon.operator.cleanup/reclaimed-bytes <non-negative integer>} ...]
 :seon.operator.reap/refused
 [{:seon.operator.claim/id <root-claim UUID>
   :seon.operator.reap/reason <qualified keyword>
   :seon.error/message <string>} ...]
 :seon.operator.reap/reclaimed-bytes <non-negative integer>
 :seon.operator.reap/complete? true}
```

One root is eligible only when all of these facts and observations agree:

1. an external root claim exists and parses;
2. `:seon.operator.claim/reap-on-owner-exit?` is true;
3. the claim's exact creator `(pid, start-instant)` is dead;
4. the root is not the managed root executing this reaper;
5. every process associated with the root has a readable external process
   claim carrying generation, PID, and start instant; and
6. after exact shutdown, no matching process identity remains alive and no
   advertisement answers.

The reaper first asks a responsive claimed JVM to stop through the same prepl
path as `down`. A non-responsive but still-live JVM is signaled only after
re-reading and matching its exact process record; TERM and KILL retain the
current event-driven process-exit observation and the declared silence
backstop. It then calls the existing `seon.operator/cleanup-root!`. A root is
never deleted while a matching process remains alive.

This closes both P20 arms. A lane-abandoned ephemeral root with a responsive
JVM is stopped normally. A test-harness child that no longer answers is still
addressable because the consolidated process claim lives under
`data/operator/claims/processes`, outside the vanished test root. Scratch
roots under `tmp/test-runs` are included only because they have explicit root
claims; no `tmp/test-runs` name or age rule exists.

The current claim call is not yet sufficient for that promise:
`fresh_operator.clj` passes `ephemeral? false`, and
`claim-root-under-lock!` otherwise records the short-lived calling process as
creator. The implementation must make ephemeral ownership explicit before
enabling the reaper. A harness or lane creating an ephemeral root supplies its
actual owner `(pid, start-instant)` in the root-claim request; the claim owner
verifies that identity is live when it publishes the claim. The short-lived
`bin/seon` wrapper must not substitute its own identity. A human or durable
operator root remains non-ephemeral by absence of that declaration. This is a
normal claim field/CLI request, not a path-derived default, heartbeat, or
lease.

For tests, the owner is the test JVM and the external claim survives deletion
of its managed directory. For a collaboration lane, the lane launcher passes
the lane-owning process identity when it creates its isolated root and invokes
the same exact cleanup on normal exit. The scheduled reaper is reconciliation
when that exit cleanup is missed. Until a root has this honest creator claim,
it is ineligible and reported rather than guessed.

An observed JVM without a readable exact process claim is reported and
refused. The design does not recreate a record from a process-name match at
the destructive boundary. The harness must publish the external claim before
launch and reap the child before dropping that claim; the regression plants a
child whose managed directory disappears while its external claims remain.

This strengthens three existing owners without duplicating them:

- `seon.operator.state` remains the claim and control-lock authority;
- the exact stop mechanics now private in `fresh_operator.clj` become the one
  process action used by `down` and reaping; and
- `seon.operator/cleanup-root!` remains the only root deletion owner.

If any eligible root is refused, `complete?` is false and the public return is
`:seon.operator/reap-incomplete` with the partial result. That error is what
wakes root when scheduled.

### `cleanup-cluster!`

Contract:

```clojure
(cleanup-cluster!
 {:seon.operator/repository-root <canonical repository root>
  :seon.operator/managed-root <canonical operator root>
  :seon.boot/cluster-name <cluster name>})
;; => :seon.operator.cluster-cleanup/result | :seon.error/value
```

Successful result:

```clojure
{:seon.operator.cluster-cleanup/managed-root <canonical root>
 :seon.boot/cluster-name <cluster name>
 :seon.store/branch <cluster branch>
 :seon.operator.cluster-cleanup/live-instance-stopped? <boolean>
 :seon.operator.cluster-cleanup/branch-retired? <boolean>
 :seon.operator.cluster-cleanup/removed [<canonical claimed paths> ...]
 :seon.operator.cluster-cleanup/collection <collect result>
 :seon.operator.cluster-cleanup/remaining [<canonical paths> ...]
 :seon.operator.cluster-cleanup/reclaimed-bytes <non-negative integer>
 :seon.operator.cluster-cleanup/complete? true}
```

The operation is destructive cleanup, not refork. It stops the exact live
instance when present, retires the branch through
`seon.cluster.registry/retire-branch!`, removes only the cluster's claimed
directory/log paths through `seon.fs/delete-recursively!`, invokes the one
`collect!`, and verifies absence. An absent branch or path is already clean.
A connected branch, unknown path, ambiguous claim, remaining path, failed
collection verification, or live process produces a flat incomplete error.

`init NAME --force` then has one composition for both live and dormant
clusters:

1. `seon.operator/cleanup-cluster!`;
2. `seon.cluster.registry/ensure-cluster!` at the selected published commit;
3. start only when the command's existing contract requires a live instance.

This strengthens the existing `cluster/stop!`,
`registry/retire-branch!`/`ensure-cluster!`, `registry/collect!`, and
`seon.fs/delete-recursively!` owners. The cleanup policy moves to the public
operation. The cleanup body in `seon.cluster/refork!`, the direct recursive
delete in `named-init-form`, and any dormant/live split are deleted. Public
`seon.operator/refork!` becomes only the above composition; it does not own a
second cleanup implementation.

### `cleanup-root!` and reset after consolidation

`cleanup-root!` remains the unconditional whole-managed-root deletion after
all exact processes are gone. The reset command becomes:

1. `census-processes!`;
2. stop every exact recorded process through the same action the reaper uses;
3. verify the census has no live identity and the flock is free;
4. `cleanup-root!`;
5. publish `current-src` and refork `default`.

The scheduled reaper and reset therefore share both process action and root
cleanup. `cleanup-root!` and `cleanup-cluster!` share the same no-follow,
canonical-containment deletion primitive. They differ only in the declared
target: the complete managed root versus one cluster's exact claims.

## Turn-free scheduled fires

### The task declaration

The landed facts remain the one declaration model:

```clojure
{:seon.schedule.task/id <identity>
 :seon.schedule.task/owner [:seon.cluster.agent/id "root"]
 :seon.schedule.task/function [:seon.fn/sym "seon.operator/collect!"]
 :seon.schedule.task/schedule [:seon.schedule/id <identity>]}
```

`:seon.schedule.task/function` is the handler; no second `/handler` attribute
is added. The referenced public function must have a complete one-map Malli
contract and an ordinary admitted return contract. The fire transaction still
proves that the task belongs to the requested agent and snapshots the exact
function ref declared by the task. It deletes the current
function-namespace-equals-agent-namespace check: every agent may call every
function in its cluster's program graph, so that equality is an obsolete
callability restriction. This is one general rule, not a root exception or a
function hand list.

The proc builds one open request map from the claimed fire, the owning cluster
handle, and the effective config database value:

```clojure
{:seon.schedule.task/id <task id>
 :seon.schedule.fire/id <fire id>
 :seon.schedule.fire/nominal-at <instant>
 :seon.schedule.fire/observed-at <instant>
 :seon.cluster.agent/id "root"
 :seon.boot/cluster-name <cluster name>
 :seon.operator/repository-root <canonical repository root>
 :seon.operator/managed-root <canonical operator root>
 :seon.boot/log-dir <canonical log directory>
 ;; effective maintenance dials required by this handler
 ...}
```

Each operator function validates only its required keys. The exact ordinary
request is stored on the receipt as a component value so a later query can
explain the result without reconstructing old config.

### Fire, claim, execute, settle

The existing agent graph and `::schedule` proc stay. There is no fourth proc.
On a due instant, its transform performs this sequence in stable task-id
order:

1. In one transaction, upsert the existing unique fire identity and claim a
   unique receipt derived from that fire. If either already exists, return no
   claim and do not call the function.
2. The claim transaction records the task, fire, exact handler function,
   ordinary request, and `started-at`. It does not create a
   `:seon.cluster.message/to` datom.
3. Resolve the declared Var and call it directly with the recorded request on
   the existing `:io` schedule proc. There is no prompt, model call, run, turn,
   SCI eval, or generated Clojure form.
4. On success, one terminal transaction attaches the ordinary result and
   `completed-at` to the receipt.
5. On a returned flat error or thrown failure, one terminal transaction
   attaches the error fact and `completed-at`, using
   `seon.error/commit-tx`. Its existing root explanation message is committed
   in that same transaction.
6. Re-derive the next nominal instant from current task/fire facts and arm the
   same disposable timer.

If the claim transaction fails, no handler runs. If the process dies after
claim, boot marks a receipt with no terminal as `interrupted-at`; the unique
fire remains and the handler is not called again. If the success/error
terminal transaction itself cannot commit, the proc throws into its existing
Flow error channel so the fault committer records the core failure. External
side effects are therefore at-most-once, consistent with Seon's crash model;
the design does not pretend to provide exactly-once effects.

### Receipt facts

One receipt entity carries these common attributes:

```clojure
{:seon.maintenance.receipt/id <identity derived from fire id>
 :seon.maintenance.receipt/fire <fire ref>
 :seon.maintenance.receipt/task <task ref>
 :seon.maintenance.receipt/handler <function ref>
 :seon.maintenance.receipt/request <component request ref>
 :seon.maintenance.receipt/started-at <instant>
 ;; exactly one terminal arm
 :seon.maintenance.receipt/completed-at <instant>
 :seon.maintenance.receipt/result <component result ref>
 ;; or
 :seon.maintenance.receipt/error <error ref>
 ;; process-loss arm
 :seon.maintenance.receipt/interrupted-at <instant>}
```

There is no status, kind, green, red, acknowledged, or reported attribute.
State is derived from which terminal connection exists. Operation-specific
result entities carry their own qualified attributes, so Datahike can query
reclaimed bytes, refused roots, low-space observations, and process identities
without parsing EDN or prose. The receipt's handler ref preserves what was
actually called even if the task declaration changes later.

### Failure and the existing wake

A maintenance handler is first-party mechanical execution. Its flat error is
normalized as the escaped failure source for `seon.error/commit-tx`, with the
root agent and current process attached. This makes the current error rule do
exactly what R8 requires:

```text
handler error
  → receipt + seon.error fact + existing explanation message
  → existing :seon.cluster.message/to listener
  → root mailbox
  → actual model turn
```

The maintenance receipt does not create the wake. The existing error message
does. Green receipts contain no message and consume no turn. The existing
recurrence fence remains the storm bound; maintenance adds no retry or alert
policy.

## Root's maintenance report

### Query and initial form

The generic root initial-forms declaration carries one ordinary form calling
`seon.maintenance/report`. That function uses the ambient database value to
query maintenance receipts owned by root. It selects the latest claimed
receipt per declared task, ordered by task id, and includes any currently
unterminated receipt. It returns ordinary data shaped as
`:seon.maintenance/report`.

The `:seon.maintenance/report` schema declares
`:seon.render/ai seon.maintenance/render-report-ai` and the corresponding HTML
producer. The producer renders from receipt/result/error facts. Neither the
green line nor red detail is stored.

This form lands through the generic initial-forms declaration and resolver. It
must not be added to the current bootstrap resource as a special maintenance
band. The dependency is sequencing, not a second mechanism: maintenance
receipts can land before generic initial forms, but root's automatic report
form graduates with that owner.

### Exact presentation

When every latest receipt completed with a result and none of those results
is incomplete or pressure-classified, the AI projection is one line:

```text
Maintenance: 5 tasks succeeded; latest 2026-08-05T12:34:00Z; 0 errors.
```

When there are no receipts yet:

```text
Maintenance: no task has run yet.
```

When any latest receipt has an error, interruption, incomplete result,
refusal, unresponsive process, or low-space classification, the first line
remains concise and subsequent lines give one fact-linked result per affected
task:

```text
Maintenance: 3 succeeded; 2 need attention.
reap-dead-roots!: 1 root refused; error <id>; root claim <id>.
observe-footprint!: 42.1 GiB usable (4.2%); error <id>.
```

The report value retains all affected receipts and evidence refs. Consumer
render profiles and ordinary elision values fit long detail; the producer does
not hide rows behind a literal cap. A later successful receipt makes the
latest-per-task report green without retracting historical evidence.

An error has already woken root before this initial form runs. The initial
form is the concise situational report in that turn, not the trigger. A red
non-error observation remains visible in the next ordinary root turn; an
operator function that cannot complete safely returns a flat error and thus
wakes immediately.

## Dead-root reaper safety proof

The reaper's destructive predicate is a conjunction over claims and fresh
observations. It never infers ownership from a path. The implementation and
tests must prove:

- PID reuse: a matching PID with a different start instant is never signaled.
- Generation: a stale process record cannot authorize action against a newer
  generation even when root and cluster names match.
- Reachability: a responsive exact JVM gets the normal graceful stop; an
  unresponsive exact JVM gets TERM/KILL only after identity recheck.
- Missing evidence: unreadable or absent exact claims produce a refusal, not a
  best-effort cleanup.
- Current root: the root executing the reaper cannot reap itself.
- Containment: every target is canonical and beneath the exact claimed root.
- Links: recursive deletion uses `NOFOLLOW_LINKS`; an external symlink sentinel
  survives both cluster and root cleanup.
- Crash: a process death after exact stop but before cleanup leaves claims for
  the next fire to re-derive; cleanup is idempotent.
- Harness order: deleting a managed test directory cannot delete its external
  root/process claims. The reaper can still identify the child without
  process-name matching.
- Claim origin: the short-lived operator wrapper is never recorded as the
  creator of an ephemeral root; the harness/lane owner identity is live at
  claim publication and dead in the reaper falsifier.

## Declared portfolio and cadences

The schedule entities below are ordinary initialization rows in each cluster
that owns a root agent. Cron and timezone live only on those schedule facts;
there is no config-fact copy to reconcile. Changing cadence is an ordinary
transaction replacing the schedule's cron/timezone values, which wakes the
existing per-agent schedule proc.

| Task id | Schedule id | Recommended fact values | Handler | Scope and result |
| --- | --- | --- | --- | --- |
| `root/maintenance/footprint` | `root/maintenance/footprint-schedule` | `0 2 * * *`, explicit IANA timezone | `seon.operator/observe-footprint!` | Managed-root footprint and pressure classification |
| `root/maintenance/reap-dead-roots` | `root/maintenance/reap-dead-roots-schedule` | `15 2 * * *`, same timezone | `seon.operator/reap-dead-roots!` | Installation claims; current managed root excluded |
| `root/maintenance/rotate-logs` | `root/maintenance/rotate-logs-schedule` | `30 2 * * *`, same timezone | `seon.operator/rotate-logs!` | This cluster's declared live log |
| `root/maintenance/process-census` | `root/maintenance/process-census-schedule` | `5 * * * *`, same timezone | `seon.operator/census-processes!` | External claims, exact OS identities, advertisements |
| `root/maintenance/compact` | `root/maintenance/compact-schedule` | `0 3 * * 0`, same timezone | `seon.operator/collect!` | Per-cluster declared task; physical action is the one whole-store collection |

“Per-cluster compaction” means every cluster declares and can inspect its root
task and receipt. It does not mean a branch-local sweep: the physical store is
shared and `registry/collect!` is whole-store by construction. The public
operation serializes calls for one store and reports the exact store and branch
heads it covered. A later task may truthfully record a zero-sweep result; this
specification adds no cached “last collected” state and no clock-window
suppression. Whether the exclusive-sweep design can also collapse concurrent
identical marks without another durable mechanism remains a choice for that
design, not an assumption here.

### Open choices from the mining report

#### Cadence authority

1. **Schedule facts only — recommended.** The table above is initialized as
   schedule/task rows. One fact is both declaration and runtime input.
2. Config cron facts projected into schedule facts. This leaves two durable
   representations and a reconciliation question, so it is rejected.
3. Hard-coded cron strings in Clojure. This is not queryable and is rejected.

#### Turn-free execution placement

1. **The existing per-agent schedule proc — recommended.** It already owns
   the timer, database listener, fire identity, `:io` workload, error channel,
   and crash re-derivation.
2. A fourth maintenance proc. It duplicates fire claiming and error handling
   and is rejected.
3. The current fire message and model turn. R8 explicitly rules it out for
   mechanical maintenance.

#### Result storage

1. **Queryable receipt plus operation-specific result attributes —
   recommended.** It preserves exact inputs/results and lets the report be a
   query.
2. One serialized result EDN string or blob. It is less queryable and turns
   every report into parsing; rejected for these bounded results.
3. Stored green/red prose. It is derived presentation and is rejected.

#### Reaper eligibility

1. **Explicit reap-on-exit claim plus dead creator, exact process shutdown,
   and cleanup verification — recommended.** It closes both P20 causes.
2. Reap only roots whose JVMs were already dead before the fire. It leaks
   lane-abandoned detached JVMs and does not close P20.
3. Reap by age or path pattern. It cannot prove ownership and is rejected.

#### Collection cadence

1. **Weekly collection plus daily footprint observation — recommended.** It
   keeps the mining report's cost split; an explicit operator call may collect
   sooner after pressure.
2. Daily collection. It is simpler but repeats a materially heavier whole-store
   operation without evidence that daily churn needs it.
3. Pressure-only collection. It makes reclaim reactive and permits another
   large accumulation before action.

## One-owner call graph

```text
schedule fire ───────────────┐
operator REPL call ──────────┼─> seon.operator/<operation>!
bin/seon live prepl ─────────┤              │
bin/seon maintenance JVM ────┘              ├─> seon.operator.state claims/lock
                                             ├─> seon.cluster lifecycle
                                             ├─> seon.cluster.registry branches/collect
                                             └─> seon.fs no-follow deletion

reset ─> census/exact stop ─> cleanup-root! ─> publish/refork
init NAME --force ──────────> cleanup-cluster! ─> ensure-cluster!
reaper ─> census/exact stop ─> cleanup-root!
```

The arrows are composition, not alternate implementations. The implementation
wave is incomplete while `fresh_operator.clj`, `cluster/refork!`, or a test
harness retains a second census, exact-stop, branch-cleanup, or recursive-delete
policy.

## Implementation order and acceptance

1. Declare the receipt/request/result schemas and the five root schedule/task
   rows. Prove open maps and queryability.
2. Convert the landed scheduler's fire transaction from fire+model message to
   fire+receipt claim, then add direct Var execution and terminal settlement.
   Preserve nominal identity, `:latest` recovery, DST behavior, and the current
   per-agent graph.
3. Land `census-processes!` and consolidate the script's observation logic
   under `seon.operator.state`.
4. Accrete explicit ephemeral creator identity into root claims and every
   harness/lane root launcher. Then land `reap-dead-roots!` using the census,
   exact stop, and existing `cleanup-root!`; delete the script-local duplicate
   action paths.
5. Land `cleanup-cluster!`; route live/dormant force-init and refork through it;
   delete both old cleanup bodies.
6. Land `collect!` only after the parallel exclusive-sweep design supplies the
   named registry seam and its falsifiers pass.
7. Add the maintenance report value and declared render producers; add its one
   form to the generic root initial-forms declaration when that mechanism
   lands.
8. Seed the portfolio and run the live proof on a newly forked cluster from the
   published source commit.

Graduation requires all of these observations:

- a nominal instant produces exactly one fire, one claimed receipt, one direct
  handler call, and no model run or message on success;
- restart after receipt claim marks it interrupted and never calls the handler
  again;
- a flat handler error and a thrown handler failure each commit one receipt,
  one durable error fact, and the existing root-addressed message that wakes a
  real turn;
- the root initial form renders the specified green and red shapes from facts;
- scheduled and manual invocations resolve to the identical operator Vars;
- two clusters' compaction tasks serialize safely at one store, preserve every
  branch head, and record each actual result;
- `reset --force`, live `init NAME --force`, and dormant force-init use the
  same exact-stop and cleanup owners and leave no second recursive delete;
- a lane-abandoned ephemeral root and a vanished `tmp/test-runs` root are
  reaped from external claims, while an unclaimed process is refused;
- PID reuse and generation mismatch cannot be signaled;
- the symlink sentinel outside the claimed root survives;
- collection reopens every recorded head/blob and its verification pass sweeps
  zero; and
- no central ticker, maintenance proc, stored report prose, name-pattern
  reaper, retry loop, or second cleanup implementation exists.

## Ugly output observed

No web or REPL render was exercised in this planning-only slice. Source review
did expose two current output defects relevant to this design:

- `seon.schedule/fire-call` stores a mechanical instruction string (“Scheduled
  task … Call …”) as message content, which becomes transcript noise and buys
  an unnecessary model turn. The turn-free conversion deletes that output.
- `reset!` currently prints reclaimed storage as an unformatted raw byte count,
  while `status!` formats the same domain in GiB. The shared cleanup result is
  good data; the operator's human projection should use the existing concise
  GiB shape rather than expose the integer directly.

These are recorded here because this lane's writable surface is this design
document only.
