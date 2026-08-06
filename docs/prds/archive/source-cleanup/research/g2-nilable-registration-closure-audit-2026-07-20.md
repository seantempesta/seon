---
type: research
status: complete
tags: [prd, research, schema]
---

# G2 nilable-registration closure audit

## Question and verdict

The remaining register wording says that “`[:maybe]` regs inside G2” still
await work. That wording is stale as a description of the maintained schema
registrations, but it points at an issue that is not yet closable.

Commit `0b991436` already removed all three top-level nilable registrations
named by [[../../../seon/issues/compiled-program-contains-nilable-value-schemas]]
and made `seon.schema/register!` reject any top-level `[:maybe X]` before
candidate mutation. No source work remains in those three semantic owners or
in the registration gate.

One test-harness source change and its behavioral proof still remain. The real
two-child driver in `test/seon/execution/integration_driver.cljs` continues to
drop every top-level `[:maybe ...]` form before transacting the compiled schema
population. A current corpus with zero such forms makes that filter a no-op in
practice, but the acceptance condition is stronger: the proof must transact
the complete population *without a filtering escape hatch*. Until the filter
is deleted and the process proof passes, the issue remains open.

## Current-source census

The three original registrations and their current contracts are:

| Original registered value | Current source after `0b991436` | Status |
|---|---|---|
| `:my.plan/tree-response` registered as `[:maybe [:or :map [:vector :map]]]` | `:my.plan/tree-result` is registered once as the non-nil `[:or :map [:vector :map]]`; `my.plan/tree` and `my.plan/document` express nullable returns only in their function schemas as `[:maybe ::tree-result]` | Implemented |
| `:seon.web.datastar/optional-view-id` registered as `[:maybe ::view-id]` | The registration is deleted; `request-view-id` expresses the nullable return only at its function-output slot as `[:maybe ::view-id]` | Implemented |
| `:seon.agent.home/id` registered as `[:maybe ::agent-id]` | The registration is deleted; both `home-requires-for` arities express nullable `id` arguments only at their `:catn` function slots | Implemented |

A repository-source search at audit HEAD `8eec9e15` found no
`schema/register!` call whose registered value begins with `[:maybe`. The
remaining `[:maybe ...]` forms at the three call sites above are function-slot
contracts, not persisted value registrations. They are the intended way to
describe a nilable argument or return while keeping stored attribute values
non-nil.

## Registration-gate implementation

`0b991436` moved the shared nilable-value predicate to the front of
`seon.schema/register!`, before encoding, compilation, or candidate mutation.
The focused regression in `test/seon/schema_test.cljs` proves both relevant
forms:

- `[:maybe :int]` is rejected as
  `:seon.schema/nilable-value-schema`, leaves no candidate behind, and names
  the copyable base registration plus field-level `{:optional true}` idiom;
- `[:maybe :schematest.slot/base]` is rejected even when the inner schema is a
  registered domain alias.

This is stricter than the earlier projection-time behavior from `e63bb161` and
matches G2: a bad agent form fails at the causal `register!` call rather than
appearing to succeed and failing only during later projection or transaction.
The database-side conversion uses the same predicate in
`seon.db.internal/form->datahike-value-type`, so registration and persistence
do not define competing nilability rules.

## Evidence ledger

| Evidence | What it proves | Limitation |
|---|---|---|
| `0b991436` | Implements the registration gate, the three registration migrations, directive database messages, and focused schema regressions | Does not alter the two-child driver’s population filter |
| `810cad74` roadmap entry | Records focused schema, database-remote, home, Datastar, and plan tests as green after `0b991436` | Records no per-command counts or retained raw logs |
| Frozen CLJS checkpoint `286180f7` | Contains `0b991436` and passed 1,331 tests / 6,151 assertions with zero failures, errors, or warnings, covering the maintained CLJS suites including the affected semantic namespaces | Predates later tracked edits and is not the final program graduation run; it also does not run the JVM-hosted real-child process proof |
| `test/seon/execution/integration_driver.cljs` current source | Shows the two-child proof still wraps `schema/registered-schemas` in a `keep` that rejects forms whose first element is `:maybe` | Contradicts the issue’s complete-population/no-filter acceptance condition |
| `test/seon/execution_process_test.clj` | Owns `two-real-children-reconstruct-one-current-program`, the JVM-hosted process proof that launches the integration driver | No post-`0b991436` durable pass is recorded against an unfiltered complete schema population |

## Exact remaining work

The G2 implementation should not reopen `src/my/plan.cljs`,
`src/seon/web/datastar.cljs`, `src/seon/agent/home.cljs`, or the
`seon.schema/register!` mechanism. The remaining source boundary is only the
real-child proof:

1. Delete the top-level-`:maybe` exclusion from
   `test/seon/execution/integration_driver.cljs`; construct transaction data
   from every keyword-keyed entry in `schema/registered-schemas`.
2. Add or retain a structural assertion that the submitted schema row count is
   exactly the current keyword-keyed compiled population count. Merely getting
   a successful process exit is weaker because a future selector could again
   omit rows silently.
3. Rebuild the execution and integration artifacts at one frozen revision and
   run `two-real-children-reconstruct-one-current-program`. Record the command,
   revision, schema-row count, and green result.
4. At the same frozen revision, rerun the focused schema, plan, Datastar, and
   agent-home namespaces. The prior focused claim and `286180f7` are useful
   historical evidence, but a current rerun makes the issue closure
   self-contained.
5. Perform the Stage-1.6 live steering probe: submit a top-level nilable
   registration through a fresh agent evaluation and observe the directive
   error at that call; then submit the named non-nil base registration and
   observe success. This closes the roadmap’s separate fresh-start/live G2
   gate rather than inferring agent behavior from a direct unit call.

The issue may then move to `docs/seon/issues/archive/` with `0b991436` plus the
unfiltered driver commit and frozen behavioral evidence. Before those steps,
its `status: open` and blocker classification are correct.

## Ledger wording correction

The register row should no longer say “`[:maybe]` regs inside G2,” because that
implies the three semantic-owner edits remain. The accurate residual is:

> G2 unfiltered compiled-population two-child proof plus fresh-agent rejection
> and corrected-call evidence.

This is a proof-boundary correction, not a new architecture decision and not a
reason to redo the already-implemented registration migrations.
