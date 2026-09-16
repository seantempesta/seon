---
date: 2026-09-17
lane: write-storm
issue: docs/seon/issues/a-failing-turn-write-refires-without-bound-and-fills-the-store.md
---

# Bounded write retry, and a test body that owns neither the registry nor custody

Two defects, one storm. The issue
[a-failing-turn-write-refires-without-bound-and-fills-the-store](../../../seon/issues/a-failing-turn-write-refires-without-bound-and-fills-the-store.md)
filed them together; they are fixed here in two commits, one per class.

## The storm's numbers

From the issue and `tmp/orchestrator/refork/seon-log-write-storm-2026-09-17T1240Z.log`:

| measurement | value |
|---|---|
| window | 2026-09-17 ~12:20–12:40Z |
| `default` store | 2.5 GB → 19 GB |
| growth rate | ~1 GB per minute |
| new store files | 1,329 in two minutes |
| refusals observed | two `:datahike/write-error` entries inside one 150 ms probe |
| refusal | `:malli.core/invalid-schema {:schema :seon.schema-usage-guardb/entity-id}` |
| stack | `seon.schema/direct-reference-keys-in` ← `projection-with-schema` ← `seon.turn/row-tx` (turn.clj:1344) ← `[:db.fn/call retain-transaction]` (`src/seon/db.clj:2930`) ← `datahike.writer` |
| recovery | `bin/seon reset --force` — the fifth reset of the day |

Nothing committed in that window. Every gigabyte was dirty index leaves
flushed by attempts that were refused.

## Class 2 — a refused turn write is bounded (commit `f86ec57ed`)

`seon.turn/step` derives one pass, and offers a wake into the agent's own
mailbox when `more-agent-work?` is still true. A refused durable write leaves
the work exactly where it was, so `more-agent-work?` stayed true and the pass
re-fired — forever, at writer speed.

The fix, at the owner:

- **A declared dial, not a tuned constant.**
  `:seon.config.agent/write-refusal-bound`
  (`resources/seon/schemas/seon.config.agent.edn`, default `3` in
  `config/default.edn`), per-agent, in the same family as
  `:seon.config.agent/turn-completion-backstop-ms`. The observable event it
  stands in for is "this agent's durable write is refused and re-firing
  cannot change that".
- **The pass names its refused write.** `turn`'s `report` closure gained a
  3-arity carrying `:seon.turn.loop/refusal` — the flat error value of the
  refused transaction. It is set only at durable-write sites (`open-turn`'s
  open transaction, batch settlement's refused outcome, `close-turn`,
  `generate-turn`'s terminal close and append, and `call-turn`'s intent
  commit and close). A provider error, a reader error and an agent mistake
  are NOT write refusals and carry no refusal, because only a refused write
  re-fires against a writer that keeps flushing.
- **The count is the proc's own state.** `:seon.turn.loop/write-refusals` in
  the turn proc's state — the one thing here that genuinely needs
  serializing. A committed write resets it to zero.
- **At the bound the proc parks and commits ONE fault.**
  `offer-write-refusal-fault!` puts a `:seon.turn.loop/write-refusals-exhausted`
  diagnostic — naming the refusal, the agent, the consecutive count and the
  bound — on the cluster's fault channel, which is the fault committer's
  inbox, so it lands as a durable fact on the debug page's problems surface
  like any other core fault. The self-rewake is not offered. A later wake on
  a parked proc returns immediately and transacts nothing; re-arming the
  agent (disarm/arm) builds a fresh proc, which is deliberately the only way
  out, so a park stays visible until someone acts on the fault.
- **Absence is never "no bound".** A cluster whose effective config declares
  no `:seon.config.agent/write-refusal-bound` throws
  `:seon.config/required-absent` rather than silently disabling the bound —
  the exact absence-of-signal shape this class is made of.

**Regression** (`seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault`),
on the canonical `with-database` harness with a real cluster handle, a real
config row and a real fault channel. The refused write is genuine — closing a
turn that is not a fact, refused through `seon.db/transact!` exactly as the
projection failure was. It asserts:

- the proc parks at the declared bound and the parked value names the kind,
  the count and the bound;
- the fault channel carries **exactly one** fault, never one per attempt;
- a parked proc offers no self-rewake;
- **the store stops growing**: `(:max-tx (db/db connection))` is unchanged
  across five further wakes. The measurement is the database's transaction
  count, not a log line.

## Class 1 — a test body owns neither the registry nor custody

### What actually leaked

The issue's shape was "a test registered a synthetic schema into the shared
registry". The archaeology refutes the literal reading and finds a sharper
root cause:

- `seon.schema/register!` **cannot** write a process-global: it refuses
  outside an isolated candidate delta (`update-candidate-forms!`,
  `src/seon/schema.clj:985`). `test/seon/db_test.clj:20-33` is the model —
  registrations inside `call-with-registration-delta`, isolated by
  construction.
- The packaged declaration forms hold **no mutable state**:
  `seon.schema.edn/resource-population` (`src/seon/schema/edn.clj:329`) reads
  and merges the classpath resources on every call, with no cache.
- `with-branched-database` builds a **fresh** projection state per branch
  (`test/seon/test_support.clj:720-732`), and
  `seon.db/connection-projection-state` (`src/seon/db.clj:128`) matches
  running instances by `identical?` connection.

The one shared schema registry a JVM has is therefore **each running
cluster's projection state**, and the demonstrable path into it is custody,
not registration:

1. An agent evaluation binds `seon.db/*conn*` and `*read-database*` to the
   live cluster (`src/seon/sci/eval.clj:2300`).
2. `seon.test/bounded-result` runs the test Var through `bound-fn` on a
   virtual thread (`src/seon/test.clj:128`), which **conveys the whole
   caller binding frame** into the test body.
3. Nothing in the fixture stack rebinds them: `run-database-body`
   (`test/seon/test_support.clj:710`) binds only `schema/*projection-state*`.
4. So any fixture helper using an **elided** `seon.db` arity wrote
   `default`'s own datoms. `seon.schema-usage-guard-test` installs synthetic
   declarations and drives `turn/row-tx` — the writer's declaration path —
   so the synthetic rows became durable facts on the live cluster, and every
   later write there compiled a declaration referencing a key the registry
   did not hold.

That also explains the persistence: the poison was a durable row on
`default`, not a thread-local binding, which is why only a store reset
cleared it.

### The fixes, at three owners

1. **`seon.db/call-without-custody`** (`src/seon/db.clj`) — a scope with no
   ambient cluster custody bound.
2. **`seon.test.runner/run-var!`** runs every test Var inside it. This is the
   one place both the worker path and the in-process path execute a test, so
   it closes the class for every test rather than the ones that remember to
   opt in. In a `bin/test` worker nothing was bound, so it is a no-op there;
   an in-process test that silently depended on the live connection now gets
   the ordinary loud refusal naming what it needed.
3. **The drift detector reports registry drift BY KEY.**
   `seon.test.runner/ambient-snapshot` gained `::snapshot-schema-keys`,
   derived from every running instance's projection forms, with both
   directions listed in `drift-directions`. `bounded-drift` already names
   members, so a leaked key is now printed as
   `:seon.schema-usage-guardb/entity-id`, not as a count. Before this, the
   detector measured instrumentation, malli function schemas, cluster names
   and the fixture SCI base — and answered "fine" through the whole storm.
4. **`seon.test/run` restores it.** It snapshots each live cluster's
   projection state before the run and puts it back when the run changed the
   **key set**, the same way the drift detector re-arms instrumentation, and
   names the restored keys in the result's failure message rather than
   swallowing them. Only a key-set change is restored; an ordinary adoption
   that advanced the same keys is left alone.
5. **`seon.test-support/preserving-schema-registry`** — a separate owner from
   `preserving-instrumentation-state`, deliberately. The two preserve
   different shared state for different call sets (a test that arms a
   contract rarely declares a schema), and folding a projection snapshot into
   the instrumentation helper would make every instrumentation test pay for a
   projection it never touches while naming the concern wrongly. It is used
   by `seon.schema-usage-guard-test`'s own fixture bracket — the suite that
   poisoned the JVM — so the leak cannot outlive even one deftest there.
   `test/seon/db_test.clj` needs no change: its registrations are already
   inside an isolated delta.

**Regressions** (`seon.test-support-test`):

- `a-synthetic-schema-registration-leaves-the-registry-byte-identical` — a
  poisoned projection is restored byte-identically, the leak is reported
  once, and the leaked key is named; a registry that did not drift is left
  alone and reports nothing.
- `a-test-body-inherits-no-ambient-cluster-custody` — an elided write with no
  custody is a typed refusal naming what was missing, while an explicit
  connection still writes its own branch.

## Live before / after

- Before: `default` (pid 63433, fresh store, reset #5) carried 2,737
  projection forms and **no** `schema-usage-guard*` keys — the reset had
  already cleared the poison, so the leak itself could not be re-observed
  live without re-running the poisoning namespace, which the assignment
  forbids until the fix is in place.
- `:seon.config.agent/write-refusal-bound` was published, reconciled
  (`bin/seon config apply default config/default.edn`) and verified live:
  `(config/effective db "default")` returns `3` alongside the existing
  `:seon.config.agent/turn-completion-backstop-ms 600000`.

## In-process runs (pid 74930, `default`)

| run | result |
|---|---|
| `seon.test-support-test/a-synthetic-schema-registration-leaves-the-registry-byte-identical` | 7 assertions, 0 fail, 0 error |
| `seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody` | 3 assertions, 0 fail, 0 error |
| `seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault` | NOT PROVEN in process — see below |

The class-2 regression drives the bound through a genuine refused durable
write, and the live log shows it firing:
`:datahike/write-rejected {:kind :seon.turn/refused, :cause "run transition
refused: no-such-run"}`. It then fails at the contract wrapper for
`seon.turn/write-refusal-bound`, which stayed armed with the contract's
PREVIOUS shape across three converged `init --dev default` adoptions while the
caller `seon.turn/step` was reloaded to the new one. HEAD's source is coherent
(`f0cb3f692` changes the signature and the call site together, verified by
reading `git show HEAD:src/seon/turn.clj`), so this is an adoption re-arm
defect, filed as
[adoption-can-leave-a-changed-contract-armed-with-its-previous-shape](../../../seon/issues/adoption-can-leave-a-changed-contract-armed-with-its-previous-shape.md).

Two earlier iterations of that same regression found real fixture defects and
were fixed: it now supplies every declared input the turn proc names — the
handle's SCI ctx, its IO executor, the completion allowance and the bound —
instead of the handful it happens to read.

## Batch 73 and the reset JVM (pid 88182)

Batch 73 cold returned three reds in this lane's files. All three were FIXTURE
defects; none was in the shipped code. Fixed in `b7c7edf5e` and `006e7e450`
and proven in process on the reset JVM, which has no stale armed wrapper:

| run | result |
|---|---|
| `seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault` | 14 assertions, 0 fail, 0 error |
| `seon.schema-usage-guard-test/unregister-stages-removal-in-the-evaluation-delta` | 5, 0, 0 |
| `seon.schema-usage-guard-test/generic-schema-deletion-refuses-committed-dependencies` | 8, 0, 0 |
| `seon.schema-usage-guard-test/schema-removal-refuses-schema-and-function-dependencies` | 4, 0, 0 |

**Class 2 is proven.** The earlier in-process failures on pid 74930 were the
stale armed contract masking two ordinary test bugs: the regression read the
fault through `::flow/ex`, which in `seon.turn-test` aliases `seon.flow` while
the fault carries core.async.flow's own `:clojure.core.async.flow/ex`; and it
polled the wake mailbox without draining what the passes below the bound had
legitimately offered.

**The guard suite's reds were one disease, twice.** A projection built from a
bare `{probe-key form}` map is not a smaller world but a broken one: every
instrumented `seon.schema` call made against it has to compile its OWN
contract out of a population holding one probe key. And a fixture that writes
a declaration ROW without advancing its projection hands the writer a world
its own facts contradict — which is the write storm's disease exactly, one
layer down. Both now go through one seam.

**Class 1 proved itself in production conditions, unplanned.** The class-2
run's two reported errors were the new drift check and restore acting on a
REAL foreign leak: `:example/order` and `:example/order-row` —
`context_blocks_fixture`'s registrations — were sitting in `default`'s own
schema projection. They were named by key and restored. Before this commit
the check would have said nothing. Filed as
[context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster](../../../seon/issues/context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster.md).

## Verification boundary

- The two class-1 regressions are proven in process, against adopted
  definitions, on pid 74930.
- The class-2 regression is proven in process on pid 88182 (14 assertions),
  after the reset cleared the stale armed contract. The three guard-suite
  regressions are proven there too.
- The isolated proof is the orchestrator's batched gate; the request is at
  `tmp/orchestrator/gate-requests/write-storm.txt`.
- **Not gated by this lane:** every other live cluster or fixture root needs
  `bin/seon config apply` for the new required dial, and the custody change
  in `run-var!` will surface — loudly, as intended — any in-process test that
  was relying on the live cluster's connection.
- **Not this lane's:** batch 72's
  `seon.config-test/the-default-document-has-one-canonical-complete-location`
  red was attributed to this pair. It is not: the declared dial set (94) and
  `config/default.edn`'s keys (93) differ by exactly
  `:seon.config.render/issue-opening`, declared in
  `resources/seon/schemas/seon.config.render.edn:3` by `ef7770785` with no
  value in the manifest. The write-refusal pair is balanced — both sides carry
  the key. Measured live:
  `(clojure.set/difference dials manifest) => [:seon.config.render/issue-opening]`.

## A new REQUIRED dial must ship with its decision in ONE publication

This lane's own cost, recorded because it is a class. The schema declaration
(`resources/seon/schemas/seon.config.agent.edn`) reached `default` through the
edit hook before `config/default.edn`'s value was reconciled. Between the two,
`config/effective` refused naming `:seon.config.agent/write-refusal-bound`, and
that refusal killed **every io-prepl connection** on the cluster — so every
lane's MCP tool went down until `bin/seon config apply` landed. The coordinator
filed `a-missing-required-dial-kills-every-io-prepl-connection` and restarted
`default` (pid 74930) to reconcile the committed dial.

The rule this earns: a NEW REQUIRED effective-config fact and its shipped
decision are ONE publication, applied before the declaration is adopted — or
the declaration carries a default so the effective config is never half-built.
An intermediate edit on a shared cluster is not a private inconvenience.

## Shared-tree note

`resources/seon/schemas/seon.turn.loop.edn` carries this lane's
`:seon.turn.loop/refusal`, `:write-refusals` and `:parked` declarations, but
they were swept into another lane's commit `7f99fe695` while uncommitted.
The content is correct and already on the branch; it is recorded here so the
class-2 commit's file list is not read as incomplete.
