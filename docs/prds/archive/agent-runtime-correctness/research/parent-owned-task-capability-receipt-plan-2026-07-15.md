---
type: research
status: completed
tags: [research, agent, flow, architecture]
---

# Parent-owned task capability and eval receipt plan — 2026-07-15

## Decision

Strengthen the existing `seon.eval` execution service. Do not add a job table,
task registry, second evaluator, child-owned database client, or parallel plan
API. The pod opens and terminalizes the existing `:seon.eval` component entity;
the disposable child only computes a candidate. A parent closure retains the
task capability and derives actor, run, turn, receipt, artifact, and database
scope from it. No child field is authority.

This plan deliberately separates two dependencies:

- receipt data, pure transition compilation, CAS laws, compatibility reads,
  and recovery can be designed and tested without launching a child; and
- actor enforcement, framed operations, process disposal, and eval/provider
  cutover depend on the unit-1 operator/launch containment owner.

The first group is a reviewable source slice, not permission to implement a
second temporary authority. The current shared tree has active changes in
`seon.launch`, the database protocol, and operator-related files, so this audit
changes documentation only.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source and constraint |
|---|---|---|
| ClojureScript self-host | `1.12.145`, official tag `r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` plus `src/seon/eval.cljs`: analyzer and runtime state are process-local. Only a parent-accepted candidate may become durable program truth. |
| Datahike | maintained commit `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/db.cljc`, `transactor.clj`, and `src/seon/db.cljs`: use one `seon.db/transact!`, `db/cas-assert`, and complete expected coordinates. Do not call Datahike directly. |
| Node child process | `v26.4.0`, release commit `2022edf3e32ce28ee08b17f8566243a090dacd95` | Official `child_process` and permission source: retain the `ChildProcess` handle, remove listeners once, and wait for `close`. A PID or request-supplied token is not authority. |
| Pod eval owner | `src/seon/eval.cljs` | `eval-batch!`, `eval-form-entry!`, and `record-eval!` are the one execution and evidence path. Split their phases in place; do not route isolated eval through `seon.worker-eval`. |
| Turn and provider owner | `src/seon/agent/turn.cljs` | `open-turn!` creates the running turn; `call-llm!` is the sole retry owner; `ask-and-eval-reply!` owns the committed reply-to-eval edge. |
| Database provenance | `src/seon/db.cljs` | `with-agent` and `with-tx-context` are public process-local scopes. Nested agent code can replace them. A parent handler may use explicit transaction context only after selecting values from its retained capability, never from a child frame. |
| Recovery | `src/seon/runtime/recovery.cljs` | `recover!` already closes open runs and running turns in one fenced, idempotent transaction. It is the sole restart owner and must also terminalize running eval receipts. |
| Plan authority | `src/my/plan.cljs`, `src/my/plan/internal.cljs` | Pure actor/owner/scope decisions may land before process isolation. The actor passed to that authority is not unforgeable until parent capability dispatch replaces child-selected `:seon.agent/id`. |
| Launch and artifact identity | `src/seon/launch.cljc`, `src/seon/client.cljs`, `bin/seon` | Unit 1 owns child launch, artifact/runtime digest, lifecycle inverse, hard memory backend, and readiness. Receipt work must not invent those mechanisms. |

## Current seam and falsifiable defect

`open-turn!` durably allocates a running turn before provider/eval work. In
contrast, `record-eval!` allocates `:seon.eval/id` only after a form returns and
stores the outcome, turn component edge, and tee in one transaction. Therefore
a killed form leaves no fact that it began. Its transcript-only fallback can
also accept evidence after the program tee fails, which is tolerable only while
all state lives in one process. With a disposable child, a rejected candidate
means the child's analyzer/runtime state is ahead of database truth and the
child must be discarded.

The actor boundary is independently forgeable today. `db/current-agent-id`
reads public AsyncLocalStorage, and agent code can nest `db/with-agent`.
`my.plan` accepts an injected `:seon.agent/id`. Those are useful call context,
not credentials.

The falsifiable failure is:

1. agent A starts form 2 of a three-form batch;
2. the child hangs, exhausts memory, or sends a candidate containing agent B;
3. the pod kills or rejects it; and
4. the database either lacks form-2 start evidence, contains form 3, attributes
   a mutation to B, or lets a late candidate commit.

Graduation requires form 2 to be durably `:interrupted`, form 3 to be absent,
all accepted effects to be stamped as A by the parent, and every late frame to
be refused without touching the writer.

## One receipt entity and its schema

The existing turn component remains the receipt container:

```clojure
{:seon.agent.turn/evals
 [{:seon.eval/id     "..."
   :seon.eval/status :running
   :seon.eval/at      #inst "..."
   :seon.eval/source  "(...)"
   :seon.eval/ns      :my.agent.a}]}
```

Add one attribute:

```clojure
[:seon.eval/status [:enum :running :done :error :interrupted]]
```

Keep the existing attributes and meanings:

- `:seon.eval/at` is the start time, not terminal time;
- `:seon.eval/ok?` is absent while running, `true` for `:done`, and `false`
  for `:error` or `:interrupted`;
- result, output, duration, error, and error-data are terminal projections;
- `:seon.eval/ns` is the namespace at dispatch, then the accepted ending
  namespace on terminal success/error; and
- `:seon.eval/agent` remains a query projection only if current consumers need
  it. The authoritative owner is the eval's component turn and run/agent refs.

Do not copy the four-part database coordinate onto every eval. The owning turn
already records the frozen render/read coordinate. The start and terminal
transactions use complete `::db/expected-coordinate` requests plus run and
receipt CAS assertions. Duplicating the coordinate would create a second value
that could drift. A later observability requirement may store the committed
transaction coordinate as transaction metadata, not as an authority supplied
by the child.

Historical rows have no status. The compatibility projection derives terminal
status only when reading old rows: present `:seon.eval/ok? true` means `:done`;
present false means `:error`. New transitions always write status. Do not run a
bulk migration and do not interpret absent status plus absent `ok?` as running
unless the installed schema and receipt were created by the new start path.

## Parent task capability

The capability is process-local, immutable data closed over by the parent
message handler. It is not transacted and is never serialized wholesale:

```clojure
{::agent-id           "..."
 ::run-id             "..."
 ::turn-id            "..."
 ::eval-id            "..."
 ::artifact-digest    "..."
 ::read-coordinate    {::db/id "..." ::db/branch "..."
                       ::db/commit-id "..." ::db/t 0}
 ::absolute-deadline  0
 ::spawn-token         "..."}
```

The child sees a bounded opaque request correlation id and the exact data
needed to compute. Possession of that id is not sufficient: the handler also
checks that the request arrived on the retained live child handle, the receipt
is still `:running`, the run pointer still names the captured run, the artifact
matches, and the deadline has not passed. Keep one in-flight capability in the
child-owner closure; do not add a global token registry.

For every mutation, the parent:

1. parses a closed operation schema and rejects unknown authority fields;
2. supplies actor/run/turn/eval from the retained capability;
3. runs the existing owner under explicit parent transaction provenance;
4. includes run and receipt CAS assertions and the current complete expected
   coordinate; and
5. acknowledges only the committed writer response.

The child may send a target entity as ordinary operation data. It may never
send or override transaction user/process, actor, run, turn, eval receipt,
artifact identity, coordinate, deadline, or operation allowlist. In particular,
`{:seon.agent/id "B"}` and `::db/user` in a frame are rejected rather than
merged.

`db/with-tx-context` need not become a security primitive. It remains the one
provenance mechanism, invoked in the parent handler with capability-derived
values. Child code has no database connection and its own AsyncLocalStorage
cannot cross the process boundary.

## Exact receipt transitions

| Event | Preconditions | One parent transaction | Result |
|---|---|---|---|
| Start form | turn running; captured run current; current complete coordinate | allocate `:seon.eval/id`; add component row with status `:running`, start time, source, narration, and dispatch namespace; assert run CAS | Exactly one durable claim that this form began. |
| Accept success | same run; receipt running; candidate within bounds | CAS status `:running` → `:done`; add `ok? true`, duration, output/result, ending namespace, tee/program facts, and provenance atomically | Program state and evidence advance together. |
| Accept ordinary failure | same run; receipt running; structured failure within bounds | CAS status `:running` → `:error`; add `ok? false`, duration, bounded error/error-data, output, and accepted failure evidence | Failure is data; child may advance only after commit ack. |
| Cancel/deadline/process exit | receipt running | fence capability, CAS status `:running` → `:interrupted`; add `ok? false` and bounded parent-observed reason/exit diagnostics | No candidate is accepted and no later form is dispatched. |
| Restart recovery | open run/turn and running receipt found from one frozen db | existing recovery tx CASes run, closes run/turn, and CASes every running receipt to `:interrupted` | One idempotent restart transition. |
| Duplicate terminal message | receipt no longer running | no write; re-read terminal receipt and return already-terminal refusal | The terminal transition occurs once. |
| Lost terminal acknowledgement | parent commit succeeded, child did not observe ack | fence and discard child; re-read receipt | Never replay the candidate or allocate another eval. |
| Tee or writer rejection | receipt running, candidate unaccepted | terminalize interrupted/error only through a fresh parent decision; discard child | Never use the old transcript-only fallback to keep this child alive. |

Run CAS and receipt CAS solve different races. The run fence prevents work from
an obsolete lifecycle; the receipt fence prevents duplicate or late terminal
commits within the same run. Expected coordinate protects the writer request
against a stale parent read. A coordinate conflict causes re-read and a fresh
parent decision; it never licenses the child to retry a mutation.

Provider attempts initially use the existing running turn plus committed reply
blob acknowledgement rather than a new provider-attempt entity. A process
death after an external request may have incurred cost or effects, so it is not
automatically retried unless the parent can prove dispatch never began.
`call-llm!` remains the only retry owner.

## Source slices and ownership

### Slice A — dependency-independent contract, for root review

This is the only source work that can safely be considered before child launch
ownership settles. It must still be taken by one owner because boot schema and
recovery touch shared runtime files.

- `src/seon/eval.cljs`: colocate `:seon.eval/status` schema, a compatibility
  `eval-status` projection, pure closed schemas for start/terminal inputs, and
  pure transaction-data builders. Split allocation/start from completion in
  the existing `record-eval!` path; do not add a public alternate evaluator.
- `src/seon/client.cljs`: register the new boot attribute only after the active
  launch owner releases this overlapping file.
- `src/seon/runtime/recovery.cljs`: include running eval components in the one
  frozen recovery read and one CAS-fenced recovery transaction.
- `test/seon/eval/receipt_test.cljs`: prove the pure transition table against a
  fresh in-memory Datahike connection.
- `test/seon/runtime/recovery_test.cljs`: prove recovery interruption and
  second-run idempotence.

The existing in-pod evaluator may adopt the same two-phase receipt before child
cutover only if focused proof shows that every current consumer tolerates the
temporary absence of `ok?` and that a failed start transaction prevents form
execution. This is a semantic change, not a mechanical schema addition.

The plan lifecycle audit exposes a separate pure slice in
`src/my/plan/internal.cljs`: compile one actor/owner/scope/CAS decision from
explicit actor, target, complete coordinate, and plan facts. Tests may prove
authored/addressed/cross-agent decisions now. Do not change the public surface
to call that slice as an authorization claim until capability dispatch supplies
the actor. This audit identifies that slice for root review; it does not
implement it or create a temporary actor mechanism.

### Slice B — after unit-1 launch containment lands

- `src/seon/eval.cljs`: retain the public execution seam and orchestrate
  start → child candidate → parent acceptance → acknowledgement.
- A private subordinate child adapter under `src/seon/eval/` may own spawn,
  framing, bounds, handle identity, kill, and reap. It is an implementation
  detail of `seon.eval`, not another evaluator or lifecycle service.
- `src/seon/agent/turn.cljs`: route provider and eval tasks through the same
  capability-selected execution owner while retaining `call-llm!` retry and
  committed-reply ownership.
- `src/seon/client.cljs` and `src/seon/agent/runtime.cljs`: attach the one child
  owner to the existing lifecycle inverse; close admission, fence receipts,
  kill/reap, then release database state.
- `src/seon/launch.cljc` and `bin/seon`: consume the unit-1 artifact/runtime and
  hard-backend descriptor. Do not redefine its readiness or process tree here.
- `src/my/plan.cljs` and `src/my/plan/internal.cljs`: parent-side capability
  operations call the one private plan transition authority with the retained
  actor. Public agent functions remain ergonomic requests, not credentials.

No provider or application eval cutover occurs until the non-production child
experiment proves denial, memory death, reap, and parent health.

## Deterministic denial and recovery matrix

| Test | Injection | Required evidence |
|---|---|---|
| Foreign actor | child frame includes agent B, `::db/user`, and process fields | closed schema rejects frame; no writer call; no B-attributed datom. |
| Foreign coordinate/run | child supplies a newer coordinate or run id | fields are rejected/ignored; retained A/run/receipt values drive the request. |
| Late candidate | terminalize or cancel receipt before candidate arrives | receipt CAS fails before writer effect; child is discarded. |
| Duplicate candidate | deliver identical terminal frame twice | one terminal transaction; second response reports already terminal. |
| Stale run | replace agent run pointer after receipt start | run CAS refuses terminal effect; receipt becomes interrupted by lifecycle owner. |
| Three-form kill | form 1 commits; form 2 loops or dies; form 3 is parseable | form 1 done, form 2 interrupted, no form-3 eval entity or effect. |
| Recovery between start and candidate | commit start, restart pod | recovery closes run/turn and interrupts receipt in one tx; rerun writes nothing. |
| Tee rejection | candidate contains invalid program publication | no program fact/result admission; child discarded; receipt records honest terminal failure/interruption. |
| Commit-ack loss | writer commits terminal row, parent-child ack is severed | re-read finds terminal row; no candidate replay or new identity. |
| Parent death | kill pod with child alive | supervisor/backend reaps subtree; recovery observes running receipt and interrupts it. |
| Ambient capability denial | child probes file write/read, TCP/UDP, secrets, spawn, worker, addon, inspector, sqlite/symlink/debug escape | the unit-1 denial matrix passes; only bounded framed capability IPC succeeds. |

The focused database tests must inspect datoms, transaction provenance, and
component reachability, not only returned maps. The destructive process tests
must additionally show pod and writer health after every killed child.

## Ordered integration boundary

1. Unit 1 settles launch ownership, exact Node/artifact identity, framed pipe,
   hard memory backend, lifecycle handle, and denial experiment.
2. In parallel, the root may take Slice A's pure receipt/status/CAS and plan
   decision work, provided one owner coordinates `client.cljs` and recovery.
3. Integrate receipt start/terminal semantics into the current in-pod eval and
   prove compatibility before changing where computation runs.
4. Add the parent capability handler over the settled child adapter; prove the
   foreign-actor, late-frame, duplicate, and stale-run matrix.
5. Cut over eval batches first. Prove three-form death, reconstruction, result
   handles, hot reload, and recovery.
6. Cut over provider attempts only after committed-reply acknowledgement and
   external-dispatch uncertainty are deterministic.
7. Run Inspect transition scorers only after the deterministic runtime gates;
   model trials measure usability, not correctness.

## Exit measure

This unit is ready for source implementation when unit 1 publishes one stable
child launch/capability descriptor and the root assigns one owner for the
overlapping boot-schema/recovery files. It graduates only when every accepted
effect is parent-stamped from a retained capability, every begun form has one
durable terminal receipt, a killed form cannot create a later eval or effect,
recovery is idempotent, and hostile process death leaves the pod and writer
healthy.
