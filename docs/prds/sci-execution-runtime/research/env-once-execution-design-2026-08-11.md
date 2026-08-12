---
type: research
status: active
tags: [research, agent, runtime, sci, repl, database]
---

# Execute the agent's opening environment once and rehydrate it from facts

## Scope and verdict

I read
[`agent-interface-economy-2026-08-10.md`](../plan/agent-interface-economy-2026-08-10.md)
end to end and read the complete **How this works** section of
[`repl-transcript-context-prd-2026-08-10.md`](../plan/repl-transcript-context-prd-2026-08-10.md)
before designing. I also read the named SCI and Seon owners end to end; the
dependency ledger below records the exact revisions and paths. This lane made
no production edit.

The recommendation is one mechanism:

1. An agent's opening forms and later system-derived reads are ordinary
   system-authored run forms. They execute through the ordinary run fold and
   settle ordinary immutable receipts. There is no environment-result family.
2. Every run form gains one required authorship fact. Only system-authored
   forms whose latest receipt carries stale database-read evidence are eligible
   for refresh. Agent-authored forms have no refresh transition.
3. A refresh appends a new ordinary form and receipt linked to the prior form;
   it never changes, hides, or re-evaluates the prior entry.
4. “The agent's env” is `env/scope` applied to the one per-cluster
   `seon.env` value with the existing agent-id member. Definitions, aliases,
   receipts, and read evidence remain database facts; they are not copied into
   a second environment value.
5. A fresh SCI fork reads namespace bindings and the agent's definitions from
   facts and installs roots. It never evaluates stored authored source. The
   current source-replay path is a blocker recorded at
   [`agent-definition-restore-reexecutes-authored-source.md`](../../../seon/issues/agent-definition-restore-reexecutes-authored-source.md).
6. The existing `require` seam already has the right effect shape: `require`
   changes only the turn fork; terminal settlement commits the resulting
   namespace row; the committed row is then installed into the base context.
   The agent already points to that namespace, so the walk reaches its
   requirements without duplicating them on the agent entity.

This preserves the ruled history model. Exact form source and actual settled
values are already facts (`repl-transcript-context-prd-2026-08-10.md:17-34`),
bootstrap forms execute once at creation and later render from their receipts
(`:43-54`), and rendering remains a read with no evaluation (`:36-42`).

## Dependency ledger

| Dependency or owner | Selected revision | Boundary read and first-party owner |
|---|---|---|
| Maintained SCI fork | `6ee57c9c3e73` (`v0.14.56-22-g6ee57c9`) | `reference-code/sci/src/sci/core.cljc` was read end to end. `fork` creates a new generation over the inherited immutable environment map at `:344-350`; serializable resolver bindings and complete namespace state are exposed at `:711-768`; binding installation is `:770-834`. Copy-on-write root binding is implemented at `reference-code/sci/src/sci/impl/utils.cljc:356-379`, and function bodies/captured bindings are constructed in `reference-code/sci/src/sci/impl/analyzer.cljc:316-416,450-605`. |
| Maintained Datahike fork | `10540578248eaa686c1f88a7fe57644ee4c9f993` (`0.8.1732-101-g10540578`) | Seon's existing query/pull evidence path is `src/seon/db.clj:199-270,293-415`; dependency plans and revision shapes are declared at `resources/seon/schemas/seon.db.edn:19-89`. Receipt and refresh writes continue through `seon.db/transact!`; no second writer is proposed. |
| Seon source snapshot | `4f47ae3d066ac36b7a37a8a7b602b3056f146a40` | The named owners were read end to end: `src/seon/bootstrap.clj`, `src/seon/sci/eval.clj`, `src/seon/call_preparation.clj`, `src/seon/env.clj`, and `src/seon/cluster/run.clj`, plus the `:seon.def/*` schema, settlement owner, and tests. The relevant exact boundaries are cited below. |

The SCI fork includes the copy-on-write Var pin (`72150fd`, “Make forked Vars
copy on write”) and Seon's call-preparation hook pin in the selected history.
The hook receives the context that actually executes the call and may supply
declared missing arguments (`reference-code/sci/src/sci/core.cljc:309-318`).
Seon's implementation reads both the environment and connection from that
fork, with no dynamic cluster lookup or effect request
(`src/seon/call_preparation.clj:999-1042`). This is how a stored system-derived
`doc`, `dir`, or query form receives the current database when it executes;
it is not a persistence mechanism.

## Current mechanisms and the exact gap

### The one environment already has the agent extension

`seon.env` defines one host record and carries it under one key on a SCI ctx,
flow submission, proc arguments, and requests (`src/seon/env.clj:1-24,35-61`).
The schema already declares `:seon.cluster.agent/id` as an optional turn-layer
member (`resources/seon/schemas/seon.env.edn:50-65`), and `env/scope` permits
only declared turn members to extend an existing environment
(`src/seon/env.clj:191-214`). The agent graph already scopes the cluster's
environment to its agent and carries that same value into the mailbox, turn,
and schedule procs (`src/seon/cluster/agent.clj:280-323`).

Therefore no schema member named “agent environment,” no environment entity,
and no second host record is needed. `fork-for-turn` should receive the
agent-scoped value already carried by the turn proc and associate that same
record under `:seon.env/environment` on the new fork. Resolver state and Var
roots are installed beside it from database facts. They are not members of
the environment.

### Bootstrap is already an ordinary created-once run

The bootstrap run has a deterministic per-agent id
(`src/seon/bootstrap.clj:131-136`). `bootstrap/seed-tx` builds an ordinary
namespace row and delegates its ordered sources to `run/system-run-tx`
(`:255-301`). `ensure-entity-call` invokes that seed only in the branch where
the agent does not exist (`src/seon/cluster.clj:1749-1773`). The ordinary
system-run constructor opens, claims, and plans through the same transaction
functions as every run (`src/seon/cluster/run.clj:678-710`).

The missing fact is authorship. `plan-call` currently writes form identity,
run, ordinal, source, and namespace only (`src/seon/cluster/run.clj:585-663`;
`resources/seon/schemas/seon.cluster.run.form.edn:1-24`). As a result the
database cannot distinguish an agent reply from a form the system wrote on the
agent's behalf.

### Receipts already make one occurrence execute at most once

Receipt identity is run plus ordinal, and `receipt-start-call` refuses an
existing receipt (`src/seon/cluster/run.clj:712-765`). `receipt-settle-call`
refuses a second terminal assertion and atomically commits program rows, desk
rows, and terminal receipt facts (`:1194-1255`). This is exactly the once fence
needed for an individual form occurrence.

A refreshed derived read must therefore be a **new occurrence**, not a second
attempt against the old identity. The old receipt remains immutable; the new
form gets a new run/ordinal identity and its own receipt.

### The desk violates the new rule today

Current settlement deliberately prefers source for a successful non-atom
definition and drops its stored value (`src/seon/cluster/loop.clj:220-305`).
Every fresh turn then queries the selected agent's desk, pre-interns its names,
and calls `sci/eval-form` for every source-backed row
(`src/seon/sci/eval.clj:1421-1503`). The recurring test explicitly requires
that behavior (`test/seon/sci/desk_test.clj:232-329`). Cold installation of an
agent-authored contracted function also calls `sci/eval-form` on the stored
program source (`src/seon/sci/eval.clj:1367-1400`).

Purity and determinism checks make replay less dangerous; they do not make it
reading. The new rule removes source replay as a representable restore arm.

## Recommended fact model

### Ordinary forms and receipts, with receipt-owned evidence

Keep the existing form and receipt entities. Add these declarations:

```clojure
;; resources/seon/schemas/seon.cluster.run.form.edn
:seon.cluster.run.form/author
[:enum :agent :system]

:seon.cluster.run.form/refreshes
[:and {:seon.db/unique true} :seon.db/ref]

;; resources/seon/schemas/seon.cluster.eval.edn
:seon.cluster.eval/read-evidence
[:vector {:seon.db/component true} :seon.db/ref]
```

`author` is required on every newly planned form. `refreshes` is optional and
points to the immediately previous form in one derived-read chain. Making the
ref unique permits at most one successor for a prior form; the refresh
transaction function also refuses when a successor exists. Initial bootstrap
and other first executions have no `refreshes` value.

Each `:seon.cluster.eval/read-evidence` component is an entity-shaped use of
the existing `:seon.db/read-evidence` map, not a new result family. Declare one
reusable entity schema as an `:and` over that existing map shape so the child
attributes are checked once, and give the component no identity of its own.
Its lifetime and immutability come from the settle-once receipt that owns it.
The component contains the already established source position, Datahike
dependency plan, dependency revision, and optional replay request/result
(`resources/seon/schemas/seon.db.edn:76-89`). All evidence remains queryable;
do not serialize the evidence vector into one opaque EDN string.

The receipt is still the answer to “what did this form produce?” Read evidence
answers “which facts made that answer current?” Keeping both on the same
receipt is one mechanism and makes a separate environment-result entity
unnecessary.

### Authorship is assigned by constructors, not accepted from callers

The shared transaction planner remains one function. Its external constructors
assign authorship:

- the agent reply planning surface always passes `:agent` internally;
- `system-run-tx`, including bootstrap and derived-read refreshes, always
  passes `:system` internally; and
- the author value is not a public request field a caller may choose.

`plan-call` writes that assigned value on every form in the frozen plan. Tests
enumerate its first-party call sites and prove there is no third constructor.
This is more than a rendering convention: a first-party agent form cannot be
mislabelled system-authored without changing the owning constructor.

The refresh transition accepts only a **prior form identity**. Inside its
transaction function it reads the source and namespace from that form, proves
`:author :system`, proves that its terminal receipt owns read evidence, and
proves no form already `refreshes` it. The request has no source and no author
slot. Consequently there is no transition capable of re-executing an
agent-authored form.

This is the structural fence:

```text
agent form ── ordinary receipt ── no refresh transition

system form ── ordinary receipt + read evidence
       └────── unique refreshes ref ── new system form ── new receipt
```

Raw database transaction data remains trusted system code, as everywhere in
Seon; the unrepresentable class is the public/first-party transition shape,
not a security boundary.

### No migration path

Making authorship required is intentional contract breakage for the new
program generation. Update every form fixture and constructor in the landing
change. Existing clusters remain sovereign on their older program and schema;
do not add an optional fallback, infer author from a run-id prefix, or migrate
forms at boot.

## Execution lifecycle

### Initial execution at agent creation

1. `ensure-entity-call` atomically creates the agent and seeds the existing
   bootstrap system run.
2. Every seeded form is frozen with `:author :system`.
3. The existing agent mailbox/turn graph derives that planned run from facts
   and executes it once. The call-preparation hook supplies declared database
   or connection arguments from the agent-scoped `seon.env` value.
4. Evaluation captures database reads through the existing
   `:seon.db/read-evidence-sink`; terminal settlement converts captures with
   `db/read-evidence` and commits the components with the ordinary receipt.
5. The same terminal transaction commits namespace changes, definition root
   facts, output, result, and any error. No renderer participates.

Rendering later queries the exact stored form and receipt. It does not execute
the form, the result producer, or a refresh check.

### Rehydration for every later SCI fork

The ordered path is:

1. `sci/fork` the one cluster base. SCI currently performs this by allocating
   one atom around the inherited persistent environment map and assigning a
   new generation (`reference-code/sci/src/sci/core.cljc:344-350`).
2. Associate the already carried, agent-scoped `seon.env` value with the fork.
3. Use the namespace bindings already acquired into the base from namespace
   facts. Cold acquisition reads namespace rows and calls
   `sci/install-namespace-bindings!`; a warm fork inherits them. A successful
   runtime `require` updates that base only after settlement, as described
   below.
4. Query the selected agent's current `:seon.def/*` rows. Install faithful
   ordinary values and fresh atom snapshots directly. Install supported
   function roots through the native SCI seam recommended below. Report an
   unsupported root as explicitly unrestorable.
5. Begin evaluation. There is no call to `one-event`, `parse-string`,
   `eval-form`, or `eval-string*` in steps 1–4.

The fork's generation is the isolation fence. If the turn later rebinds an
inherited Var, SCI copies that Var into the current generation before changing
its root (`reference-code/sci/src/sci/impl/utils.cljc:362-379`). Atom snapshots
are newly allocated per fork, so in-place mutation cannot cross turns.

## Derived reads: event-driven refresh through the existing graph

### Eligibility

A latest form is refreshable exactly when all of these facts hold:

- `:seon.cluster.run.form/author` is `:system`;
- its receipt is terminal and owns a non-empty read-evidence set;
- no successor has `:seon.cluster.run.form/refreshes` pointing to it; and
- `db/read-evidence-current?` can decide freshness.

The last condition uses the mechanism already proven by retained render calls:
revision equality is checked first, and a stored replay request/result is used
when revisions alone cannot prove freshness (`src/seon/db.clj:345-415`;
`src/seon/render.clj:416-462`). Evidence with `:all` dependencies is safe only
when it also has the replay request/result needed to prove that a bookkeeping
commit did not change the read's value. A non-cacheable or `:all` read with no
replay evidence is **read once, not auto-refreshed**, with a flat diagnostic;
otherwise its own new receipt would make it perpetually stale.

### Wake routing

Do not add a timer, listener per agent, central refresher, or fourth agent proc.
Reuse the current shapes:

- the one cluster `listen!` router remains the only database listener;
- a process-local interest index maps dependency attributes to the already
  armed agent mailbox channels, with a separate set for replay-on-any-commit
  evidence;
- the listener inspects only the transaction report's datom attributes and
  uses non-blocking `offer!`; it never queries, parks, or executes a read; and
- the existing sliding-1 mailbox wake says only “derive current work.” The
  existing turn proc queries every latest derived-read receipt for that agent
  and performs the real freshness test from the current database value.

This strengthens `src/seon/cluster/wake.clj:163-240`, whose current listener
already routes agent/mailbox and unconditional render wakes under those exact
no-query/no-park rules. The current graph already uses one sliding-1 mailbox
and a turn pass that derives work from facts
(`src/seon/cluster/agent.clj:148-174,191-245,280-323`).

Register interest before deriving current work:

1. At arm/cold start, register the route, query the agent's latest evidence,
   install its interest, then prime the mailbox.
2. At settlement, the system has captured evidence before the receipt commit.
   Add the new interest before transacting the receipt; after a successful
   commit, remove the superseded interest. A failed commit may leave an extra
   process-local wake route, which is harmless and is reconciled from facts on
   the next derive pass. There is no interval in which a committed dependency
   can change before its interest exists.
3. On restart the process cache disappears and is derived again from receipt
   facts. Losing it cannot lose durable work.

The index is an optional acceleration of a fact query, not durable state. Its
entries are derived from receipt evidence and current mailbox custody.

### Append one new run and receipt

On a wake, the turn proc sorts every stale latest derived form by its existing
history position and asks one transaction function to append a system run. The
transaction function:

1. rechecks author, terminal receipt, evidence presence, and absence of a
   successor;
2. copies exact source and namespace from each prior form;
3. opens one ordinary system run with those sources;
4. writes each new form's unique `refreshes` ref to its immediate predecessor;
   and
5. leaves ordinary receipt creation and settlement to the existing run fold.

The run id is deterministic from the sorted predecessor form identities and
the database commit ID observed by the planner. The transaction function's
successor check, Datahike's serialized writer, and the unique ref make two
concurrent wakes converge on one append. A later fact change sees the new
receipt as the latest chain member and may append again.

System-derived refresh work is settled before the next provider context is
acquired. It is system work, not a model turn. Multiple stale reads may share
one system run, but each remains an ordinary form/receipt pair.

### Preserve a byte-stable prefix

The current history proposal gives bootstrap its own band and orders later run
forms primarily by `opened-at` (`repl-transcript-context-prd-2026-08-10.md:123-137`).
Millisecond time plus an identity tie-break is not a strict append authority:
a newly committed run with the same millisecond can sort before an older id.

Use the transaction id of the run identity datom as the primary order for all
non-bootstrap history, with transaction ordinal and form ordinal below it:

```text
bootstrap form  [0 bootstrap-ordinal form-id]
later form      [1 run-created-t family-sub-band tx-ordinal form-ordinal form-id]
message         [1 message-created-t family-sub-band tx-ordinal message-id]
```

Every refresh is committed after its predecessor, so it sorts after it. The
original form and receipt remain byte-for-byte unchanged, and the unfitted
history before the new transaction is an exact prefix of the new history.
Profiles may elide entries for a bounded consumer, but they never mutate an
entry identity or recompute its value.

## The `require` seam

No new effect path is required.

During evaluation, `require` changes SCI resolver state in the isolated turn
fork. `namespace-context-row` compares the before/after aliases, refers,
imports, and requires and emits one namespace program row
(`src/seon/sci/eval.clj:545-647`). Terminal settlement commits that row with
the receipt, and only after a successful transaction does `install-row!` read
the exact committed namespace row and install its bindings into the live base
(`src/seon/sci/eval.clj:704-740`; `src/seon/cluster/run.clj:1246-1255`). The
restart regression proves requires, aliases, and refers survive cold acquire
without changing receipts (`test/seon/cluster/program_restart_test.clj:180-350`).

The agent already has one unique namespace ref
(`resources/seon/schemas/seon.cluster.agent.edn:70-73`), and the namespace row
owns `:seon.ns/requires`, aliases, imports, and refers
(`resources/seon/schemas/seon.ns.edn:1-24`). A walk expands:

```text
agent → assigned namespace → requires / aliases / refers → target namespaces
```

Do not duplicate those refs directly onto the agent. The known remaining
graph defect—alias and refer targets are still symbols rather than refs—is
already recorded at
[`namespace-binding-targets-are-symbols-not-refs.md`](../../../seon/issues/namespace-binding-targets-are-symbols-not-refs.md).
It does not justify a second relationship.

## Fork cost measurement

The reproducible probe is
[`env-once-fork-rehydration-benchmark.clj`](env-once-fork-rehydration-benchmark.clj).
It ran on OpenJDK 26.0.1 against the selected SCI checkout. Each sample warms
20 trials, then measures only SCI work: `sci/fork`, binding installation, and
either direct `sci/intern` roots or the current two-pass pre-intern plus source
evaluation. It excludes database query/pull, EDN/blob decode, instrumentation,
and process startup.

| Aliases | Definitions | Installation | Iterations | Median | p95 |
|---:|---:|---|---:|---:|---:|
| 0 | 0 | direct facts | 1,000 | 5.75 µs | 18.54 µs |
| 10 | 0 | direct facts | 1,000 | 7.75 µs | 14.21 µs |
| 100 | 0 | direct facts | 500 | 31.75 µs | 44.96 µs |
| 10 | 10 | direct facts | 1,000 | 22.13 µs | 56.08 µs |
| 25 | 50 | direct facts | 500 | 74.96 µs | 100.50 µs |
| 100 | 100 | direct facts | 300 | 145.04 µs | 168.38 µs |
| 10 | 10 | source replay | 300 | 485.75 µs | 1,856.54 µs |
| 25 | 50 | source replay | 100 | 1,600.88 µs | 2,070.83 µs |
| 100 | 100 | source replay | 50 | 1,679.88 µs | 2,245.63 µs |

Direct root installation was about 12–22 times faster at the paired sizes.
The direct-fact arm uses integers, while the replay arm constructs functions,
so this is a lower-bound comparison rather than a prediction for a native
function-root installer. It is enough to settle the shape: persistent-map fork
creation and alias installation are already cheap; parsing/analyzing/evaluating
N source forms is the avoidable per-turn cost.

Warm aliases should normally cost only the bare fork because committed
namespace bindings are installed into and inherited from the base. Desk roots
remain agent-selected and must be installed into each turn fork. Cache decoded
root data process-locally by the queried desk row identities and datom
transactions; a cache miss reads facts, and a restart rebuilds it. Never store
a “desk revision” fact.

## Definition rehydration options, simplest first

### Option A — faithful values and atoms only (recommended first landing)

Delete the source arm from `fork-for-turn`. Install admitted EDN/blob values
with `sci/intern` and allocate atoms from their settled snapshots. Any function
or other root that lacks a faithful stored representation gets one explicit
`:seon.def/unrestorable-reason`.

- **Guarantee:** no agent-authored form re-executes, immediately.
- **Cost/risk:** smallest change; current public SCI API is sufficient.
- **Trade-off:** later turns and cold restart lose agent-authored functions
  that cannot be represented as admitted values.
- **Capability given up:** durable `defn` until Option B lands.

This is preferable to retaining a replay fallback. Loud loss is correct;
silently running authored code twice is not.

### Option B — native SCI Var-root data (recommended target)

Add one maintained-SCI seam with two operations:

1. project selected namespace Var roots from the settled fork into ordinary,
   serializable root data; and
2. install a batch of that root data into a target fork under its current
   generation, without evaluating a form or function body.

The data must cover Var metadata, analyzed function bodies, closed-over values,
self-reference, macro metadata, faithful ordinary values, and atom snapshots.
Every captured value recursively uses the same faithful-value rules; a host or
process-local object produces an explicit unsupported-root value. SCI already
owns the required internals: `FnBody` and closure capture construction are in
`reference-code/sci/src/sci/impl/analyzer.cljc:316-416,450-605`, while
generation-aware root installation is in
`reference-code/sci/src/sci/impl/utils.cljc:356-379`. Seon must not copy those
internals into a second serializer.

Persist the projected data on the existing `:seon.def/*` row using the current
inline-EDN/blob split. Use the same native representation for cold acquisition
of agent-authored contracted function rows; otherwise the “never re-execute”
guarantee would stop at the desk boundary.

- **Guarantee:** supported definitions survive turns and cold JVM restart
  without replay; unsupported roots remain loud values.
- **Cost/risk:** a bounded maintained-SCI change plus descriptor totality and
  restart properties. The per-fork batch installer should remain near the
  measured direct-root arm.
- **Trade-off:** the stored descriptor is tied to the selected SCI program
  generation and must refuse a different format/version rather than guess.
- **Capability gained:** durable functions and closures with faithful captured
  values.

### Option C — persist or cache complete `namespace-state` (rejected)

SCI exposes the full namespace state at `sci.core/namespace-state`, but it
contains live Vars, function objects, namespace objects, and host values. A
process-local snapshot may accelerate a warm turn but cannot be the durable
fact representation, and a per-agent long-lived context would violate the one
base plus fresh turn-fork architecture.

- **Guarantee:** warm-process behavior only; no cold fact proof.
- **Cost/risk:** deceptively low until restart and mutable captures are tested.
- **Trade-off:** creates hidden process state and risks cross-turn mutation.
- **Capability given up:** honest crash recovery.

### Option D — continue source replay (rejected)

Purity predicates, replay allowlists, and source wrappers retain the forbidden
operation. They cannot prove that the original result—not merely a seemingly
equivalent new result—was rehydrated, and they preserve the measured parse and
evaluation tax on every turn.

## Recommended implementation order and proofs

1. Add required form authorship, receipt-owned evidence, and the unique
   refresh chain. Make agent/system constructors assign author internally.
2. Land Option A and the blocker regression: a side-effecting wrapper that
   returns a function is observed once across a second turn and cold restart;
   unsupported roots are explicit.
3. Capture system-form read evidence at the ordinary settlement boundary.
4. Extend the one wake router with derived attribute interest and make the
   existing turn pass append stale system reads through one transaction
   function. Prove register-before-derive, coalesced wakes, concurrent refresh,
   and `:all` self-commit stability.
5. Change history ordering to transaction-id append order and prove that the
   complete pre-refresh history bytes are an exact prefix after one and many
   refreshes.
6. Add the maintained-SCI root-data seam (Option B), then replace the temporary
   unrestorable function case. Run generative round-trips over supported roots,
   captured immutable values, recursion, atoms, aliases/refers, and a cold JVM.
7. Delete every source restore arm and obsolete replay-purity test. Keep one
   class regression for “authored form executes once.”

The integrated live proof creates an agent whose bootstrap performs a derived
`docs`/`dir` read and an agent-authored definition with an observable wrapper.
It records the opening receipts, opens several later turns without changing
facts, changes one dependency attribute, observes exactly one appended system
receipt, restarts the JVM, and observes: unchanged old receipt identities and
bytes, one wrapper execution total, restored aliases and definitions, and no
render-time evaluation.

## Defects and existing follow-ups

- New blocker:
  [`agent-definition-restore-reexecutes-authored-source.md`](../../../seon/issues/agent-definition-restore-reexecutes-authored-source.md).
- Existing namespace-graph follow-up:
  [`namespace-binding-targets-are-symbols-not-refs.md`](../../../seon/issues/namespace-binding-targets-are-symbols-not-refs.md).

No additional production defect was created for the noisy operator status
observed during orientation; it is already tracked by
[`status-floods-unreadable-external-claim-warnings.md`](../../../seon/issues/status-floods-unreadable-external-claim-warnings.md).
