---
type: research
status: proposed (redo of docs/seon/specs/merge.md §1-7, lane redo-merge, 2026-10-08)
---
# Merge repair and acceptance (redo)

What exists: an agent proposes (`my.program/propose-merge!` src/my/program.clj:416). Root prepares a fresh branch S
of its head, runs the proofs there (`seon.cluster.source/prepare-merge!` src/seon/cluster/source.clj:847), then
accepts what S tested (`accept-merge!` source.clj:941). The writer checks the carried rows' digests
(`declarations-held-tx` source.clj:795). The approach stays: functions of database values, the three-way digest
value as the diff, and a Datahike merge commit. What changes: S always exists and is root's repair world. Integrity is
checked by the writer against the head that actually receives the change. Data moves only through migration functions
the merge carries. One proc runs the auto-accept policy without a model. Authority: owner-rulings.md lines 12-14 and
38; CLAUDE.md "Merge is a root task". HEAD at the time of writing: aeb692d87.

## 1. What a merge carries

- **Program declarations only.** These are the rows of `:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name` and
  `:seon.schema/key`. `replacement` copies them through `fn/published-index-entities` and `fn/reconcile-tx`
  (source.clj:751-757). Branch data (evaluations, messages, the agent's own `transact!` rows) never merges. Schema
  keys are refused today (`program/changed-identities` program.cljc:700, G2). Slice MA-2 admits them.
- **Data on the target moves through a migration.** A migration is an ordinary contracted function
  `[:=> [:cat :seon.db/database-value] :seon.db/tx-data]`. It is pure: it reads a database value and returns the
  transaction data that brings that value's data to the new schema. It is program, so it merges like any `defn`.
  The merge task names it in `:seon.task/migrations`, a set of symbols. Without that name, a function with this
  shape is only a function.
  - **Who writes it.** The agent writes it on its branch and names it in its proposal:
    `(my.program/propose-merge! {:seon.task/migrations #{comp.user/split-name}})`. Root may also define one on S
    during repair and name it with `(my.program/take! {… :seon.task/migrations #{…}})`. The agent migrates its own
    branch's data by calling the same function with `transact!`, so the function is tested where it was written.
  - **When it runs.** At prepare, the migration runs on S after the delta. S is the head's data plus the new program,
    so the proofs and `:schema-holds` judge migrated data. At accept, it runs **inside the writer**, in the
    transaction function that carries the replacement. It reads the head as it is at that moment, so rows added to
    the head after prepare (T12's two users) are migrated too.
  - **Expand, then contract.** Datahike may refuse a transaction that installs an attribute and asserts it at the
    same time. This is the first probe of MA-8. If Datahike does refuse it, accept writes the schema rows that ADD
    attributes in one transaction, guarded by the head commit (`registry/head-guard-tx` registry.clj:157), before the
    main transaction. Adding an attribute is harmless on its own. Retractions, the replacement and the migration then
    commit together, so the head never holds the new program beside unmigrated data.
  - **How it is proven.** (1) The agent's proof deftest calls the migration on a fixture value and asserts the data.
    (2) Prepare's `:schema-holds` on S is checked after the migration. (3) The writer's final report check
    (`write-report-error` src/seon/db.clj:5268) validates every owning value the migration wrote, transaction-function
    output included. (4) Accept re-checks `:schema-holds` in the writer on the final value (§3).
  - **Bound.** A migration writes at most `:seon.program/max-datoms` (10,000, the comparison bound at
    source.clj:834) per accept. A larger migration refuses by name (Q3).

## 2. S, the repair world

- **Prepare always creates S.** It applies the non-conflicting delta. Each conflicted identity keeps the head's row,
  and the conflict is recorded as `:seon.task/unresolved` on the merge task. Today prepare refuses a conflict before
  S exists (source.clj:905, `(seq (:seon.program/conflict three)) three`), so there is nothing to repair. Prepare
  then runs any migrations and runs the run on S. A red or refused run keeps S.
- **S is named on the merge task** as `:seon.task/candidate`. It retires when the task closes, whether accepted or
  declined (G9).
- **The merge task selects custody of S.** Root is any agent holding a merge task. When that task is its current
  assignment, its evaluations read and write S, as a work task selects the agent's own branch (CLAUDE.md "An agent's
  context"). Repair is therefore ordinary REPL forms: `in-ns`, `defn`, `my.program/ns-unmap!`. No second entrance.
- **The repair operations** (each one form):
  - **Take** `(my.program/take! {:seon.task/merge m :seon.program/identities #{id}})`: copy the branch's row for
    `id` onto S.
  - **Drop** `(my.program/drop! …)`: restore the head's row on S, or retract an added row.
  - **Redefine**: any `defn` or `ns-unmap!` on S.
  - Each of the three removes `id` from `:seon.task/unresolved` and marks the run stale. The stale check already
    exists: `program-written-since?` src/seon/test/runner.clj:1187.
- **Selection is no longer an admission check.** Today `unselected-reach` (source.clj:759) refuses a take that
  reaches an untaken change. Under the new design, the dangling caller this produces is a finding in the diff value,
  and acceptance checks it (§3). Deleted: the `unselected` and `reach` refusals in prepare (source.clj:906-909).
- **Rebase.** S is a branch, so preparing S itself as the candidate against the current head carries root's repairs
  forward. Prepare runs `compare-branch` (source.clj:824), with S's fork commit as the merge base. Root's repairs
  are never lost when the head moves.

## 3. Acceptance: integrity in the writer, policy outside it

Accept's **integrity** list holds for every acceptance, by root or by policy. It cannot be overridden:

| Invariant | Checked | Today |
|---|---|---|
| every task proof green in the named run on S, and S unwritten since | before the transaction | yes, source.clj:1005-1009 |
| `:seon.task/unresolved` empty | before | new, MA-3 |
| every carried function contracted; every task handle admitted | before | yes, source.clj:1010-1018 |
| carried rows unmoved on the head | writer, `declarations-held-tx` | yes |
| **no program write on the head since prepare's basis** | writer: `program-written-since?` on the in-transaction value (the docstring at runner.clj:1188 names this use) | new, MA-9 |
| no dangling caller on the final value | writer, after the migration | new, MA-6/MA-9 |
| `:schema-holds` on the final head data | writer, after the migration | new, MA-7/MA-9 |
| owning values valid | writer's report check, db.clj:5268 | yes |

- **Revalidation.** A program write on the head since prepare refuses as `:seon.merge/head-moved`. The refusal offers
  the rebase form (§2). Data writes on the head, such as messages and proposals at the home, do not stale a merge:
  `program-written-since?` reads only program attributes. Data-dependent invariants are re-run in the writer on the
  actual head. A new caller that lands on the head after prepare is a program write, so it refuses. A new user row
  is data, so the migration takes it in.
- **Deleted:** the `uncovered` gate (source.clj:996-998, refused at 1014). It refuses a carried function no green
  member reaches, which contradicts CLAUDE.md "reaching-test counts are shown, not gated". Coverage stays in the
  diff value.
- **Policy criteria** decide only whether the policy accepts without root. Root may accept by hand when one fails:

| Criterion | Holds when |
|---|---|
| `:integrity` | every row above holds at prepare (always required by the policy) |
| `:no-conflict` | the three-way conflict set was empty at prepare. Root's resolution does not count for the policy |
| `:no-new-red` | every reaching member of the run is green on S, or red on both S and the head. A member that is unknown on the head counts as red (Astra §11) |

## 4. Who runs `review!`

- **Policy data.** `:seon.config.merge/auto-accept` is a set of criteria in the target cluster's configuration
  facts. It is root-owned and never part of `seon.config/defaults`. Absent means hold everything. Nothing by that
  name exists today (checked: no match in src or resources).
- **`my.program/review!`** takes `{:seon.task/merge m}`. It prepares (or rebases a kept S) and evaluates the
  criteria. When all hold, it accepts with `:seon.task/accepted-by :policy`. A `:seon.merge/head-moved` refusal
  rebases and retries once. Otherwise it writes `:seon.task/held` (the failed criteria) and
  `:seon.task/review-run`, and sends one message to root.
- **The runner is a proc, not a model.** `:seon.merge/review` is an `:io` proc on the target cluster's Flow graph.
  It listens with `d/listen` for new proposal rows at the home. For each one, it evaluates
  `(my.program/review! {:seon.task/merge m})` through root's one REPL path, as a system evaluation of root, recorded
  in root's history. With no provider, this is all that happens. A held merge waits for the orchestrator through MCP.
  With a provider, root's model wakes on the held message and repairs (§5). The policy never needs a model. Opus §1
  found the gap: a provider-less root could not auto-accept.

## 5. Competency T16 `root-repairs-a-held-merge`

A model agent is assigned the merge task of a held T10 proposal. T10's setup holds it with
`#{:no-dangling :no-new-red}`: `refund` still calls the renamed `subtotal`. The task's custody is S. Its tools are
the ordinary forms only: `diff`, `declaration-diff`, `defn` on S, `my.test/check`, and `accept-merge!` or `decline!`.
No script performs the repair.

The score comes from stored facts:
- the merge task is `:accepted` with `:accepted-by :root`;
- the target's `comp.inv/refund` calls `line-total`;
- `refund-test` is green in the accept's run.

A deterministic oracle test of the same flow (MA-5b) proves the API. T16 proves that a model can use it.

## 6. Costs

Measured in merge.md §8 at 81020da6a. Today's probe: `proposals` took 31 ms with no open proposals (agent
`redo-merge-probe`).

| Operation | Measured | Target | Reached by |
|---|---|---|---|
| diff (4 ids) | 174 ms | < 100 ms; cost grows with identities changed since the fork | MA-16. Suspect: `merge-base` materializes each visited commit (source.clj:739) |
| prepare beyond test bodies | 1.7 s (1 proof), 175k calls | < 500 ms | MA-16. Suspect: the branch fork; health shows `registry/branch!` at 1.66 s mean over 150 calls. Also `projection-cache-value` (24k-89k calls) |
| accept | 1.78 s, 6.2k calls | < 300 ms + migration datoms | MA-16. Profile the checked writer (`seon.db/the-checked-writer-is-slow-on-a-fresh-branch`) and the retire submission |
| writer revalidation | — | < 20 ms: `program-written-since?` grows with datoms since basis; dangling with the callers of removed symbols; schema check walks at most 200 entities (db.clj:4020) | MA-9 |
| migration | — | grows with migrated datoms, at most 10k | MA-8 |

## 7. Slices (each ~15 min; proofs assert data through `seon.mcp/call` eval_clj on a disposable target branch or the competency cluster)

Order: M8 (landed), then MA-0 to MA-17.

| # | Was | Slice | Files | Proof test |
|---|---|---|---|---|
| M8 | G6 | **LANDED** 76daa8fd5. Residual: close task `a-defn-rewrites-its-namespace-row` (resources/seon/tasks/seon.turn.batch.edn:52) in MA-0 | — | `seon.turn.batch-test/a-defn-keeps-its-namespace-row` |
| MA-0 | M9 G4 G5 | returning tools return their error; a test-only namespace prepares | program.clj, source.clj | `my.program-test/merge-tools-return-their-errors` |
| MA-1 | M7 G1 | a new contracted function arms on S | source.clj, seon/cluster.clj | `seon.cluster.source-test/a-new-function-in-a-new-namespace-prepares-green` |
| MA-2 | M10 G2 G3 | schema keys compared (program.cljc:700); proofs see branch schemas | program.cljc, test.clj | `my.program-test/a-branch-schema-shows-in-the-diff` |
| MA-3 | M4 G9 | S always created; `:seon.task/candidate`, `:seon.task/unresolved`; S retires on close | source.clj, task.clj, seon.task.edn | `seon.cluster.source-test/a-conflict-stays-on-the-merge-branch` |
| MA-4 | M3 G7 | `diff`/`declaration-diff` for `{:seon.task/merge m}` from root | program.clj | `my.program-test/root-reads-a-proposals-declaration-diff` |
| MA-5a | M11 | `take!`/`drop!` on S resolve unresolved ids; `unselected-reach` refusal deleted | program.clj, source.clj | `my.program-test/take-and-drop-move-one-identity` |
| MA-5b | new | a merge task's holder evaluates with custody S; repair by `defn` | seon/turn.clj, task.clj | `my.program-test/root-repairs-a-conflict-by-defn-on-the-merge-branch` |
| MA-6 | M5 | dangling callers in the diff and the accept integrity | source.clj | `seon.cluster.source-test/a-renamed-callee-names-its-surviving-caller` |
| MA-7 | M6 | `:schema-holds` on S data and readers | source.clj, db.clj caller only | `seon.cluster.source-test/a-retracted-attribute-names-the-head-data-and-readers` |
| MA-8 | new | probe same-transaction attribute install; `:seon.task/migrations`; run on S at prepare and in the writer at accept; expand-then-contract if the probe needs it | source.clj, program.clj, seon.task.edn | `seon.cluster.source-test/a-split-attribute-migrates-head-rows-written-after-prepare` |
| MA-9 | new | writer revalidation: head program moved refuses `:seon.merge/head-moved`; dangling and schema re-checked; rebase | source.clj | `seon.cluster.source-test/a-caller-added-after-prepare-refuses-the-accept` |
| MA-10 | new | delete the `uncovered` gate | source.clj | `seon.cluster.source-test/an-uncovered-carried-function-is-shown-not-refused` |
| MA-11 | M1 | `review!`, the dial, `:accepted-by`, `:held`, `:review-run` | program.clj, seon.config.edn, seon.task.edn | `seon.merge-policy-test/auto-accepts-green-and-holds-red` |
| MA-12 | new | `:seon.merge/review` proc evaluates `review!` through root's path, no provider | seon/cluster.clj graph, flow | `seon.merge-policy-test/a-proposal-auto-accepts-with-no-provider` |
| MA-13 | M2 | `:no-new-red`, with unknown on the head counted as red | program.clj | `seon.merge-policy-test/a-head-test-turned-red-holds` |
| MA-14 | M12 | `decline!` with a reason that messages the agent; `:seon.task/target` | program.clj, task.clj | `my.program-test/a-declined-merge-tells-its-agent` |
| MA-15 | M13 G8 | accept retires a hosted proposer's branch | program.clj | `my.program-test/accepting-a-hosted-proposal-retires-its-branch` |
| MA-16 | G10 | profile diff, prepare and accept; fix the owners to the §6 targets | owners found | `seon.merge-cost-test/prepare-and-accept-within-budget` |
| MA-17 | new | competency T16 (§5) | test/seon/competency | `seon.competency-oracle-test/t16-root-repairs-a-held-merge` |

Competency M0-M6 and X1-X4 reference these ids instead of repeating them. T12's expected column changes from
"hold `#{:schema-holds}`" to "**accept** when the agent names its migration; hold without one".

## 8. Owner questions (each with a recommendation)

1. **Where is a migration named?** On the merge task (`:seon.task/migrations`), or as `:seon.merge/migration`
   metadata on the function? **Recommend the task.** Running data changes is a merge decision, not a property of
   the function.
2. **Does the review proc go through root's one REPL path?** **Recommend yes.** It records in root's history, and a
   model root and the policy then do the same job the same way. The cost is one pass per proposal. The alternative
   is a direct function call from the proc.
3. **How large may a migration be?** **Recommend 10,000 datoms per accept.** A larger migration refuses by name and
   becomes its own root task, run in batches before the merge.
4. **Which row does S keep for a conflicted identity?** **Recommend the head's row, marked unresolved.** Accept
   refuses until root resolves it. The alternative is the branch's row.
5. **Delete the `uncovered` acceptance gate** under "reaching-test counts are shown, not gated"? **Recommend
   delete.** `:no-new-red` and the proofs carry the evidence.
6. **What happens when the head moves after prepare?** **Recommend that `review!` rebases and retries once, then
   holds `#{:head-moved}`.**

Findings outside scope:
- Default reports `:faulted` with 6 open faults and 36 errored receipts (`runtime_status` at basis 536871791).
- The `def` note repeats about 60 tokens on every `def`. This is already in agent-messages.
