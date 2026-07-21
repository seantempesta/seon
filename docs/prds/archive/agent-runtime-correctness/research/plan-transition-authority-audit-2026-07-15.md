---
type: research
status: complete
tags: [research, agent, database, flow]
---

# Plan transition authority audit

## Verdict

`my.plan` is already the one database-backed planning mechanism, but its
mutation functions do not yet form one authority. Most public transitions
accept a step id, pre-read current state, and transact an unconditional map.
That makes a known id sufficient to mutate foreign work, leaves pre-read/write
races, lets whole-forest reconciliation delete work that was absent from a
stale document, and permits completion without machine-checkable evidence.

The correction is one private transition authority in `my.plan.internal`.
Every public lifecycle and reconcile operation delegates to it with a distinct
runtime actor, target owner, expected database coordinate, expected step state,
and operation-specific evidence. It decides authorization from existing
ownership, escalation, planner, message, and transaction facts, then emits one
transaction containing Datahike compare-and-swap assertions. There is no role
enum, second queue, parallel plan ledger, or automatic retry of a stale intent.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and first-party use |
|---|---|---|
| Datahike | maintained fork `417649383c65e13f15ea41d394fb1ed742477965` in root `:writer` and `:cljs` aliases | `reference-code/datahike/src/datahike/db/transaction.cljc` implements CAS and entity retraction; `src/seon/db.cljs` exposes `cas-assert`; `test/seon/db/transact_precondition_test.cljs` proves a stale complete coordinate writes nothing |
| Malli | `0.20.0`, release commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` | the tagged `src/malli/core.cljc` map schema is open unless explicitly closed and represents absence with optional entries; Seon plan request/result schemas are in `src/my/plan.cljs` |
| ClojureScript | `1.12.145`, tag `r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | self-hosted plan functions execute through the existing pod evaluator; async verifier behavior must use the one Promise-aware function-schema owner rather than a plan-specific evaluator |
| Database coordinate | Seon `::seon.db.coordinate/coordinate` | `src/seon/db/coordinate.cljc`, `seon.db/head-coordinate`, and `:seon.db/expected-coordinate` in `src/seon/db/internal.cljs` define the existing stale-intent fence |
| Plan graph | `my.plan` and `my.plan.internal` | `src/my/plan.cljs`, `src/my/plan/internal.cljs`, and `test/my/plan_test.cljs` own step schemas, derived readiness, document/reconcile, escalation, and planner relations |
| Runtime identity injection | `seon.instrument` | `src/seon/instrument.cljc` injects a missing `:seon.agent/id`, but an explicit caller value wins; it is convenience context, not an authorization credential |
| Address creation | `seon.agent.message` | `src/seon/agent/message.cljs` and `test/seon/agent/message_test.cljs` transact an inbound message and one linked open address step per recipient atomically |

## Observation boundary

`bin/seon status --edn` reported the default development cluster down: watcher,
writer, and pod were absent. No live REPL claim is made. A focused executable
fixture is also pending because the shared canonical CLJS test owner was PID
44845 during this audit. The source audit, retained pilot evidence, and exact
dependency source are sufficient to settle the authority and test matrix; the
first implementation slice must run the fixture after that owner releases the
lock.

## Current source inventory

`src/my/plan.cljs` defines the public schemas and mutations. `plan!` performs a
same-title duplicate pre-read and accepts an explicit target agent id.
`active!`, `done!`, `reopen!`, `needs!`, `move!`, and `drop!` establish only
that a step exists before writing. `active!` pre-reads active siblings and then
updates them with ordinary transaction maps, so two concurrent activations can
both survive. `done!` neither reads `:my.plan/expect` nor accepts verification
evidence. `reopen!` retracts completion without owner or state CAS checks.

`document` returns only the raw tree or forest. `reconcile!` selects a target
through caller-supplied `:seon.agent/id`, treats the target's whole current open
forest as its baseline, and retracts baseline nodes absent from the submitted
document. The document carries no immutable database coordinate and no root or
delegation scope.

`src/my/plan/internal.cljs` already contains the relations needed for one
authority: step ownership, ready/blocked/anchor derivation, escalation,
`planner-for`, consultation message markers, and subtree traversal. Those facts
can establish a worker, planner, delegated subtree, and escalation episode
without persisting a role discriminator.

The mutation boundary must distinguish these values:

- actor: the agent executing in the selected runtime and transaction scope;
- target owner: the agent whose plan is being read or changed;
- delegated root and episode: the exact escalation subtree a planner may edit;
- expected coordinate and expected attributes: the caller's immutable basis;
- evidence: committed database entities consumed by a registered verifier.

Because instrumented injection fills only absent fields, `:seon.agent/id` is
not actor proof. Existing explicit target selection remains useful for reads,
but lifecycle authorization must obtain the actor from runtime-owned context
that a model argument cannot override.

## Existing behavioral evidence

The retained plan-preload pilot supplies three concrete failures:

- an organic planner reopened a worker's verified-done step by id;
- planner subtree reconciliation dropped two unrelated address roots because
  the baseline was the worker's whole forest;
- a per-step document captured before a mid-turn message later reconciled away
  the unseen address step;
- the ferry task prematurely closed its address step, stored the wrong value,
  and scored plan integrity `0/1`.

Existing tests prove ordinary mechanics, restart re-derivation from a
connection, reconcile shapes, and escalation relations. They do not prove
foreign mutation denial, stale-document rejection, concurrent activation,
schema'd completion evidence, or a real process-restart transition.

## One transition authority

The private authority accepts one closed namespaced map and returns either a
closed transaction intent or a structured `:seon/error` value. Its common
inputs are operation, actor, target owner, step or scoped root, complete
expected coordinate, and expected status/owner facts. Its decision is a pure
transformation over an immutable database value; the actual write remains the
one `seon.db/transact!` call.

The authority applies these laws:

1. An actor may create and mutate lifecycle state only for its own authored
   steps.
2. A planner relation grants only field reconciliation inside the exact active
   delegated subtree and escalation episode. It never grants `active!`,
   `done!`, `reopen!`, or unscoped `drop!` on worker steps.
3. Every lifecycle write asserts the expected owner and status with
   `seon.db/cas-assert`; document reconciliation additionally supplies the
   complete expected database coordinate. A stale assertion returns data and
   writes nothing. The caller must re-read and decide rather than replaying its
   old intent.
4. A repeated request observed after an uncertain response may return the
   existing equivalent result as an idempotent receipt. A conflicting request
   never overwrites it.
5. Restart performs no repair write. Current plan position and authority are
   re-derived from committed facts; any pre-restart document is stale unless
   its complete coordinate still matches.

The first source slice should route the existing public functions through this
owner rather than add new public lifecycle APIs. Superseded pre-read helpers
and unconditional transaction assembly are deleted in the same change.

## Schema'd verification evidence

Keep `:my.plan/expect` as the human-readable falsifiable outcome. Add a
`:my.plan/verifier` ref to an existing `:seon.fn` program entity. A verifier is
a normal schema'd function in the existing analyzer/program graph, invoked
through the existing Promise-aware instrumentation owner. Its closed input map
contains the immutable database value, the step ref, and a vector of evidence
entity refs. Its closed output map contains `:my.plan.verification/ok?` and a
summary string.

Successful completion adds one component verification entity and marks the
step done in the same fenced transaction. The verification entity has a compact
identity, verifier ref, cardinality-many evidence refs, and summary. Evidence
refs point to committed eval, turn, blob, test, message, or domain facts; a
self-authored boolean or repeated expectation prose is not evidence. Do not
store a derived `passed?`: the attached record plus the same-transaction done
state is the success fact, while verifier identity, evidence, summary,
transaction provenance, and transaction instant make it queryable.

Completion proceeds as follows:

1. Read one immutable database value and its complete coordinate.
2. Resolve the step owner, status, expectation, verifier, and committed evidence.
3. Invoke the verifier against that same value.
4. If it succeeds, transact owner/status/verifier CAS assertions, the component
   verification record, and done status under the expected coordinate.
5. On a stale coordinate or CAS failure, return the conflict as data without an
   internal retry. On a repeated equivalent completion, return the existing
   verification receipt; on conflicting evidence, fail closed.

An expectation-bearing authored step without a verifier cannot complete until
it is reconciled with one. Maintained address steps use one maintained address
verifier; they do not bypass the rule through prose.

## Address and reconcile laws

Address rows remain message-linked system facts and traceability anchors, but
they are excluded from the authored document deletion baseline. Reconcile
deletes only authored nodes inside the submitted owned or delegated root.
Cross-agent reconcile must baseline `pull-subtree` for that root, never the
target's whole forest.

The one existing ready/anchor derivation must omit address rows while authored
open work exists. Creating the first authored open step atomically demotes an
active address row if necessary. When no authored work remains, the address row
can again become ready and its maintained verifier can close it. This is one
query-derived queue, not a stored priority flag or a second queue.

## Deterministic transition matrix

| Transition | Required proof | Forbidden result |
|---|---|---|
| same-owner create and activate | expected coordinate; owner/status CAS; exactly one active step | duplicate same-title rows or two active siblings after concurrent calls |
| same-owner complete | verifier succeeds over committed evidence; completion and verification share one fenced transaction | done without a queryable verification record |
| same-owner reopen | expected done state and owner CAS | silent reopen from a stale view or loss of historical verification |
| foreign lifecycle mutation | actor differs from owner | any datom change merely because the id is known |
| delegated planner reconcile | active planner/escalation relation and exact subtree/episode scope | lifecycle mutation or deletion outside the delegated subtree |
| authored document reconcile | complete coordinate and exact owned root | deletion of unseen message-linked or other-root work |
| mid-turn human address | message and address facts commit; authored active work remains the derived anchor | address displacement of authored work |
| retry after lost response | equivalent existing receipt is returned | duplicate step, verification, or completion transaction |
| restart/resume | derive state from committed facts; fresh coordinate required | repair write, replayed effect, or acceptance of a stale document |

Focused fixtures must exercise both interleavings for concurrent activation,
completion versus reopen, reconcile versus message arrival, and original
request versus retry. Each denial asserts the immutable coordinate did not
advance. One process restart fixture must use the supervisor after narrow
in-memory tests are green.

## Ordered implementation slices

1. Introduce the pure actor/owner/scope decision and route lifecycle mutations
   through one fenced transition owner. Add a complete coordinate to documents,
   scope reconcile to an owned or delegated root, and exclude address rows from
   authored deletion. This is the smallest next executable slice.
2. Add the registered verifier ref and component verification receipt, then
   make completion invoke and commit it through the same authority.
3. Finish address anchor derivation and the full retry/restart matrix, then add
   deterministic Inspect scorers after runtime gates pass.

## Success measure

No tested interleaving can duplicate, reopen, displace, delete, or complete a
plan step outside schema'd authority. Every successful completion has committed
machine-checkable evidence, every rejected or stale request writes nothing,
and restart reconstructs the same plan position without replaying effects.
