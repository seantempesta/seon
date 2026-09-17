---
type: research
status: review
created: 2026-09-17
tags: [reset, datahike, schema, integration]
---

# Reset edge integration — worktree checkpoint

**G5 reviewed; final rebase verification. RESET NEEDED.** The implementation checkpoint is retained in
`tmp/reset-batch-wt`, branch `reset-batch`. The current base is
`c314408c0`; the final rebased checkpoint is `a7ec23fff`. The
orchestrator owns merge, the cold gate, platform proof and the reset. Default
was not restarted, reset, adopted or otherwise mutated by this lane.

The complete named Datahike modeling study was read end to end, including its
correction table and standing patterns. Its row-by-row disposition is maintained
in [the reset plan](../../steward-platform/plan/reset-batch-2026-09-17.md#modeling-study-correction-ledger--bd5923a8c-1i-controls),
not a second edit schedule here. The changes to the implementation include
identity-only removal and rename detection; exact analyzer-input provenance;
optional empty memberships; agent archive facts and a writer refusal of agent
identity retraction; canonical arity shape links replacing AST maintenance; and
the capability symbol as a final deletion obligation. Indexed symbol edges and
preserving issue status were already selected. G5 complete owning values and
the accepted absolute query-cost contract are implemented; their current
verification is recorded in the final section below. The four
maintenance constructors and their empty/partial outcomes now have armed proof.

## Iteration evidence

- Fast 16: 38 tests, 288 assertions, one parity failure, no errors. All eleven
  reset regressions passed.
- Fast 17: 97 tests, 676 assertions, 33 failures, 10 errors. The reset and program
  namespaces passed; remaining failures were in the function namespace.
- Fast 18, after rebasing the SCI acquisition change: 187 tests, 1,784 assertions,
  51 failures, 6 errors. It includes newly exposed schema/provenance and reader
  regressions; it is not a green claim.
- Fast 19 stopped on an unmatched parenthesis in a newly added regression.
- Fast 20 stopped on a duplicate key while consolidating historical fault
  function attribution onto `:seon.instrument/fn`. Both initialization defects
  were corrected before fast 21.

Commands use `bin/test-fast --paths <owned paths> --` from this
worktree, one invocation at a time. `tmp/test-slots` points at the main checkout's
slots. No cold gate was run. The logs above are local iteration records; retain
final evidence under this research directory before landing.

Fast 22 completed 189 tests and 1,811 assertions with zero failures and zero
errors. It predates the subsequent §1h edits and rebase, so it does not prove
the current complete worktree.

## Corrections discovered by the iteration

Runtime redefinition still split edge assertions from the declaration map after
edges became values. It now exact-reconciles one complete row. `declare` spans
must not attribute references to a later function of the same name; the analyzer
excludes forward declarations when selecting the containing definition while
preserving method/protocol implementation attribution. The canonical parity
regression covers that distinction.

Two-keyword value collections can trigger Datahike's lookup-ref heuristic. The
existing encoder emits explicit member assertions, including expanded transaction
function output; see [the issue](../../../seon/issues/two-keyword-value-members-are-mistaken-for-one-lookup-ref.md).
Rejected issue notes must not prime incomplete identity rows; see
[the issue](../../../seon/issues/refused-issue-notes-mint-incomplete-identity-rows.md).

Historical faults now use the existing `:seon.instrument/fn` symbol. Error
recording no longer creates function or namespace identities for an observed
stack frame. This is the plan's existing duplication correction, required by
G3's removal of identity minting. Call preparation and read replay also had
string-valued function lookups that the new installed symbol type exposes.

The program operation's already-admitted native SCI call moved into the existing
SCI evaluation owner. The program mutation owner still decides the operation;
this does not introduce another evaluation path or bypass the database writer.

## Boundaries and remaining proof

The message-wake-model lane owns `seon.message.edn` and `seon.wake.edn`; neither
was edited here. All message/wake landings are incorporated by rebase: handling `a50424f6b`,
subject/sender/protocol `57581f12f`, and origin `31ac4c05d`. Their foreign
resource edits were not changed by this lane. This removes the pending source
dependency; orchestrator cold/live publication proof remains owed. The original
`context_capture_history_test.clj` residue is tracked, clean and already landed
at `79c106925`; there is no unfinished test to discard.

G5 still needs the before/after owning-root traversal, typed child schemas,
complete traversal beyond 1,000 children, and the child-only/unlink/multiple-parent/
cycle proofs. A budget-location question was sent to the owner: a schema-projection
node budget (recommended), a budget supplied by every transaction constructor,
or a database-size-derived termination bound. No universal living-ref enforcement
or dependency fork change has been introduced.

The equivalent-population reverse-walk measurement is recorded below; reset-boundary
live proofs remain owed. Never interpret the dated live counts from the research as
measurements of this branch. Merge only after the completed group is green and
reviewed: `git merge --ff-only reset-batch` from `steward-platform`.

## Updated proof boundary

Fast 26 completed **125 tests / 900 assertions / one failure / zero errors**.
The only failure is the unchanged two-times-raw query-cost assertion. Its exact
measurements and the pending alternatives are in the reset plan. Functional
origin deletion-survival, optional empty maintenance, historical fault identity,
program history and real SCI mutation regressions passed. G5 has not been
implemented; the branch must not be merged or reset on this evidence.

The post-rebase launcher rejects setting `SEON_TEST_SLOTS`, even to its own
three-slot default. Fast 24 exited 64 without taking a slot. Subsequent runs
omit the override and use the declared three slots; the worktree's slot-directory
link still points at the shared main-checkout directory. No bound was raised.

The pinned grep is now also empty for `:seon.effect/capability-fn`; effect
receipts carry the exact dispatched handler symbol. Fast 27 verifies this follow-up: **152 tests / 1,500 assertions / zero failures
and zero errors**, across reset edges, fn, program, schema, schedule, render
entity pairs and effect. It does not include the two subsequent deletion tests.
The newer targeted run verifies those separately.


## Edge implementation checkpoint

Fast 28 passed **32 tests / 180 assertions / zero failures / zero errors**:
`seon.reset-edges-test`, `seon.reset-edge-parity-test`, `seon.effect-test`.
This includes the new test-retraction and capability-handler refusal cases.
All three reverse closures matched over 29,622 equivalent relation edges:

| Seed | Reached | Ref ms | Symbol ms |
|---|---:|---:|---:|
| seon.turn/open? | 2,095 | 45.509917 | 49.247542 |
| seon.db/q | 2,704 | 53.527750 | 55.465583 |
| seon.id/id | 2,222 | 39.164167 | 48.154334 |

The same snapshot reports 70 namespace referrers for `seon.turn` and 102
attribute/contract referrer groups for `:seon.db/connection`. These are canonical
fixture measurements, not the research note's dated live counts. Assertions
compare the complete refusal to the database-derived expected relations.

The message/wake follow-up landed at `57581f12f` during this run. Rebase and
integration verification follow this checkpoint; fast 28 does not claim that
later snapshot. G5 and the recorded relative-query-cost failure still prevent
merge/reset readiness. The owner design options are in the reset plan.


## Rebase onto the landed message and origin model

Implementation commit `357628778` is based on `b6562f1ce`, including
`57581f12f`, `31ac4c05d`, and evaluator failure-text fix `e1de7c75d`.
The four overlap conflicts were the same origin work: retained the upstream
issue-ID docstring, direct outline label derivation (deleting this lane's
now-redundant helper), simpler issue-origin query and canonical issue fixture.
No message/wake resource owned by the other lane was edited. The pinned grep
for AST, tombstones, identity minting and both capability-fn refs remains empty.

Owned paths in the implementation checkpoint (the commit is the exact byte
inventory; later documentation-only evidence is separate):

- `AGENTS.md`
- `config/default.edn`
- `docs/prds/context-generation/research/reset-edge-integration-2026-09-17.md`
- `docs/prds/steward-platform/plan/reset-batch-2026-09-17.md`
- `docs/seon/issues/README.md`
- `docs/seon/issues/historical-function-values-do-not-identify-an-error-entity.md`
- `docs/seon/issues/refused-issue-notes-mint-incomplete-identity-rows.md`
- `docs/seon/issues/archive/the-carried-query-ratio-fails-after-symbol-edge-retyping.md`
- `docs/seon/issues/two-keyword-value-members-are-mistaken-for-one-lookup-ref.md`
- `docs/seon/issues/worktree-edit-hook-publication-targets-main-root.md`
- `resources/seon/schemas/seon.agent.edn`
- `resources/seon/schemas/seon.cluster.edn`
- `resources/seon/schemas/seon.effect.edn`
- `resources/seon/schemas/seon.error.edn`
- `resources/seon/schemas/seon.fn.arity.edn`
- `resources/seon/schemas/seon.fn.ast.edn`
- `resources/seon/schemas/seon.fn.ast.entry.edn`
- `resources/seon/schemas/seon.fn.edn`
- `resources/seon/schemas/seon.instrument.edn`
- `resources/seon/schemas/seon.maintenance.result.edn`
- `resources/seon/schemas/seon.ns.edn`
- `resources/seon/schemas/seon.program.edn`
- `resources/seon/schemas/seon.schema.edn`
- `resources/seon/schemas/seon.source.edn`
- `resources/seon/schemas/seon.test.edn`
- `script/seon/fresh_operator.clj`
- `src/my/program.clj`
- `src/seon/agent.clj`
- `src/seon/ai.clj`
- `src/seon/bootstrap.clj`
- `src/seon/call_preparation.clj`
- `src/seon/cluster.clj`
- `src/seon/cluster/agent.clj`
- `src/seon/cluster/source.clj`
- `src/seon/db.clj`
- `src/seon/effect.clj`
- `src/seon/error.clj`
- `src/seon/fn.clj`
- `src/seon/fn/analyzer.clj`
- `src/seon/instrument.clj`
- `src/seon/issue.clj`
- `src/seon/problems.clj`
- `src/seon/program.cljc`
- `src/seon/render.clj`
- `src/seon/render/ns.clj`
- `src/seon/render/test.clj`
- `src/seon/render/walk.clj`
- `src/seon/render/web.clj`
- `src/seon/run.clj`
- `src/seon/schedule.clj`
- `src/seon/schema.clj`
- `src/seon/schema/datahike.clj`
- `src/seon/schema/form.cljc`
- `src/seon/schema/internal.cljc`
- `src/seon/sci/eval.clj`
- `src/seon/sci/kernel.clj`
- `src/seon/sci/reader.cljc`
- `src/seon/test.clj`
- `src/seon/test/accretion.clj`
- `src/seon/test/fast.clj`
- `src/seon/test/runner.clj`
- `src/seon/test/selection.clj`
- `src/seon/turn.clj`
- `test/fixtures/call_graph_fidelity/declarations.txt`
- `test/my/program_mutation_test.clj`
- `test/my/program_test.clj`
- `test/seon/db_test.clj`
- `test/seon/effect_test.clj`
- `test/seon/error_test.clj`
- `test/seon/fn_test.clj`
- `test/seon/maintenance_schema_test.clj`
- `test/seon/maintenance_test.clj`
- `test/seon/program_test.clj`
- `test/seon/render/entity_pairs_test.clj`
- `test/seon/reset_edge_parity_test.clj`
- `test/seon/reset_edges_test.clj`
- `test/seon/schedule_test.clj`
- `test/seon/schema_test.clj`
- `test/seon/schema_usage_guard_test.clj`
- `test/seon/sci/documentation_test.clj`
- `test/seon/test_support.clj`
- `test/seon/transact_feedback_test.clj`


### Post-rebase fixture verification

Fast 29 ran **122 tests / 684 assertions / 3 failures / 2 errors**. The reset,
effect and error namespaces passed. The five remaining assertions/errors were
stale fixtures/readers of the retyped facts, corrected without a production
contract change:

- Transcript primed incomplete test identities solely to serve as message
  subjects. Subjects are observation values, so both ordinary and generated
  histories now omit the unnecessary target priming.
- The inbox contract regression looked up a function with a string and pulled
  arity input keywords as schema refs. It now uses the symbol identity and
  compares the keyword-value set.
- The fault wake regression supplied a string function name and depended on
  fault recording to mint the target. It now supplies a symbol and creates the
  actual declaration through `program-fn-row` and the production analyzer.

These fixes are owned in `test/seon/render/transcript_test.clj`,
`test/seon/cluster/message_test.clj`, and `test/seon/cluster/wake_test.clj` in this
isolated worktree. Fast 30 reruns these three complete namespaces, one slot at
a time. No foreign session or main-tree source was modified.


### Final checkpoint evidence

Fast 30 passes **55 tests / 349 assertions / zero failures / zero errors** across
transcript, message and wake. Its exact output is retained in
[the fast-30 log](reset-edge-fast-30-2026-09-17.txt). The three fixture fixes
above are the only executable changes after fast 29; that run's reset, effect
and error namespaces had already passed on the rebased implementation.
Fast 27's broader core proof and fast 28's newest deletion/parity proof retain
their separately stated snapshot boundaries. No cold or platform gate was run.

All own runner sessions have exited. Fast snapshot roots were removed by the
launcher. The requested worktree/branch, dependency link and shared-cache/slot
links remain for continuation and review. No default operation was performed.

**Review boundary:** the edge/schema implementation and integrated fixtures are
committed, but the reset batch is not complete or ready to merge. G5 needs its
bounded complete owning-value validator and regressions; the priced budget
options are in the reset plan under the AGENTS §2.5 design gate. The unchanged
relative-query-cost assertion failed fast 26 and still needs the recorded
performance-contract decision. The old incomplete-create premise was not used
to weaken admission. After those obligations and orchestrator cold/live proof,
rebase onto current `steward-platform`, then merge with
`git merge --ff-only reset-batch`. **RESET NEEDED.**


## G5 owning values and accepted query-cost contract

The latest assignment accepts the projection-carried bound and the absolute
five-millisecond query contract. The historical decision boundary above is
superseded. The modeling study was read end to end; its G5 row changes the
implementation from flat identity-selected rows to complete owning values.
The indexed-symbol edges, rename/retraction detection, exact analyzed-input
provenance, optional many-values with positive construction facts, archived
agent fact, handler symbols, message seams, and AST replacement were already
satisfied by the reviewed edge commits. No message/wake resource is edited here.

The dependency ledger for this slice is Datahike's AVET attribute/value seek
(`reference-code/datahike/src/datahike/db/search.cljc:148`), EAVT entity seek
(`:147`), automatic ref indexing (`db/utils.cljc:307`), and final report
validation (`db/transaction.cljc:1206`). Pull's cycle placeholder
(`pull_api.cljc:238`) and default many cap (`:315`) are reasons not to use pull
as a completeness proof. The existing component submission widening
(`src/seon/schema/form.cljc:117`) remains the transaction grammar; final
validation additionally checks each expanded owned child's declared schema.

All existing component relation declarations now name their canonical child
schema through `:seon.db/component-schema`. This adds no child identities.
`seon.db/write-owned-values-error` walks owner links in before and after,
including targets of removed links, then expands final EAVT values in iterative
postorder. It refuses missing children, cycles, multiple ownership and
nonempty identity-less orphans. A same-transaction unlink plus retraction is
valid. A valid 1,001-child value is fully visited; damaging the last child's
required key while retaining another datom refuses atomically.

The work bound is declared in `seon.config.db.edn`, carried at the existing
projection construction seam, and read from final configuration facts by the
writer callback. The declaration default supplies bootstrap before config
exists. Every asserted branch policy applies (minimum if multiple rows), so
all writers share the bound. The refusal names the dial, limit, visited count
and entity; a caller cannot supply an escape. Budget exhaustion is not a
partial success or a presentation elision.

The query regression now asserts every measured query is within 5 ms and
prints the ratio. G5 fast 4 measured ten raw queries at **621,875 ns** and ten
wrapped queries at **1,636,960 ns**, ratio **2.632297487437186**; that absolute
check passed. Its overall run had 57 tests / 420 assertions / zero failures /
two fixture errors: an anonymous unrelated row and an untyped synthetic
component relation. Both fixtures are corrected rather than weakening G5.
The unrelated-write test now changes a real namespace's documentation; the
pull-evidence test declares its child shape and retracts the owned child.

Verification and rebase are in progress. No default operation, cold gate,
foreign session operation or main-tree source edit was performed. The exact
merge/reset instructions live in the reset plan: rebase onto current
`steward-platform`, review, `git merge --ff-only reset-batch`, then the
orchestrator runs ONE `bin/seon reset --force`. **RESET NEEDED.**


### G5 fast checkpoint before final rebase

Fast 7 passed **89 tests / 948 assertions / zero failures / zero errors** across
`seon.owned-value-test`, `seon.db-test`, `seon.schema-test` and
`seon.maintenance-schema-test`. [Exact log](reset-g5-fast-7-2026-09-17.txt).
Ten raw queries took **608,917 ns**, ten wrapped queries **2,904,626 ns**;
reported ratio **4.770150940111707**, all measured calls within the 5 ms contract.
The schema sweep now separates generated reference grammar from pulls of actual
canonical rows: 31 populated reference attributes out of 157 installed ref
attributes at this snapshot. It no longer misrepresents a namespace entity as
every unrelated component shape. The G5 declaration regression derives its
inventory from all canonical component properties, not a maintained roster.

Fast 6's larger run had 189 tests / 1,576 assertions / 28 failures / zero errors;
all 28 were that invalid schema fixture. Function, program, maintenance and
reset-edge checks passed. The corrected schema fixture and final ownership
checks are green in fast 7. Both the identity-less entity issue and mistaken
query-ratio issue are resolved and archived in this slice. The two subsequent
edits only clarify error/config description text; final rebased verification
will include them. Cold/platform/live proof remains the orchestrator's boundary.


### Rebase and launcher boundary

The branch rebased onto `ad75bab51`. Conflict resolution preserved upstream
batched `identity-rows` reads and publication progress reports while deleting
stub/tombstone minting; only real evidence identities need resolution now that
reach is a value. SCI documentation retains the upstream agent-only override
lookup with symbol identities. The program regression keeps the upstream
injected immutable row reader and excludes the reset provenance facts from its
semantic comparison. No foreign uncommitted changes were copied.

The rebased overlay admission (`6f80d1a4d`) refused a byte-identical HEAD fast
snapshot before any JVM/slot: no matching cold HEAD manifest. The isolated
launcher fix proves the actual snapshot has no tracked or untracked differences
and skips only that vacuous overlay check. Changed overlays retain the original
admission. The actual fast launcher now reaches its slot and armed JVM. The
main tree's `bin/test` and `src/seon/test/selection.clj` remain the runner lane's
held boundary, verified with git status; neither was edited. See
[the resolved launcher issue](../../../seon/issues/archive/a-clean-fast-snapshot-demands-a-cold-head-publication.md).
The orchestrator must reconcile the narrow launcher condition with that lane's
landing before merge. No cold preparation or default operation was run.


The rebased schema contract sweep found one missing predicate diagnostic in the
new upstream `:seon.source/progress!` declaration (`be9c90e2f`). The existing
`every-predicate-schema-declares-what-it-accepts` regression names exactly
`[:fn clojure.core/ifn?]`. This branch adds the required callable-progress
`:error/message`; it changes no accepted value or callback behavior. Other
inline function predicates are outside this registered-schema sweep and are
not attributed to this failure. The schema namespace is rerun after the patch.


### Final corrected proof and integrated launcher

Fast 10 ran **191 tests / 1,665 assertions / one failure / zero errors** on the
rebased seven-namespace selection. The only failure was the newly introduced
bare progress predicate described above; all function, program, maintenance,
G5, database and deletion/publication behavior passed. [Exact log](reset-g5-fast-10-2026-09-17.txt).

After that diagnostic-only resource correction, fast 11 passed **89 tests /
948 assertions / zero failures / zero errors** across G5, database, schema and
maintenance. [Exact log](reset-g5-fast-11-2026-09-17.txt). Ten raw queries took
**691,876 ns**, ten wrapped queries **3,424,418 ns**; ratio
**4.949467823714076**, with each wrapped sample inside the absolute 5 ms bound.
The ratio is a measured fact, not a contract.

The final rebase includes `d88837ddd` through main HEAD `5dd6ef7cc`. The runner
files were rechecked clean on the main tree; their previously named hold is
released. The rebase preserves source-matched baseline acquisition and cold
automatic preparation, while fast exact-HEAD snapshots avoid a vacuous overlay
check. Compared with the fast-11 code snapshot, only the upstream launcher,
cache, selector and runner regression changed; G5/database/schema production
and regression bytes are identical. A final `seon.owned-value-test` run checks
the combined launcher. No default lifecycle, adoption, REPL or cold gate command
was issued by this lane.


Fast 12 passed **5 tests / 68 assertions / zero failures / zero errors** on
code checkpoint `e27986b03`, with the integrated launcher after the final rebase.
[Exact log](reset-g5-fast-12-2026-09-17.txt). All own runner sessions have exited.
The requested `reset-batch` worktree, reference-code link, shared cache link and
shared slot link remain; no extra cluster or live process was created.

The G5 implementation commit is `1d9dc7c1f`; the narrow launcher fix is
`35883e11e`; the progress predicate description is `7d975fe5b`. The preceding
reviewed edge and message integration commits rebased to `5da2295d9` and
`ca6f6db6b`. The archived identity-less-entity issue is closed in the G5 slice.

**Stop for review.** G5 and the accepted query budget are fast-green; the branch
is rebased through `5dd6ef7cc`. The orchestrator owns the cold/platform gate and
reset live proofs. After review, from the main checkout use
`git merge --ff-only reset-batch`, then ONE `bin/seon reset --force`, followed by
the canonical Juniper reseed, convergence and cold/live sequence in the reset
plan. If main advances first, rebase the branch before this final publication.
**RESET NEEDED; this lane has not reset or restarted default.**

### G5 slice file inventory

Derived from `git diff --name-status ca6f6db6b e27986b03`, plus the final evidence
files below. R entries name both the previous and archived path. Conflict
resolutions during the edge rebase additionally touched
`src/seon/cluster/source.clj`, `src/seon/sci/eval.clj`, `src/seon/turn.clj` and
`test/seon/program_test.clj`; their preserved upstream behavior is described above.

```text
M	.agents/skills/data-modeling/SKILL.md
M	.agents/skills/datahike/SKILL.md
M	AGENTS.md
M	bin/test
M	docs/prds/context-generation/research/reset-edge-integration-2026-09-17.md
A	docs/prds/context-generation/research/reset-g5-fast-7-2026-09-17.txt
M	docs/prds/steward-platform/plan/reset-batch-2026-09-17.md
A	docs/seon/issues/archive/a-clean-fast-snapshot-demands-a-cold-head-publication.md
R063	docs/seon/issues/an-entity-with-no-identity-attribute-is-never-validated-at-the-writer.md	docs/seon/issues/archive/an-entity-with-no-identity-attribute-is-never-validated-at-the-writer.md
M	docs/seon/issues/archive/predicate-schema-violations-humanize-to-unknown-error.md
R066	docs/seon/issues/the-carried-query-ratio-fails-after-symbol-edge-retyping.md	docs/seon/issues/archive/the-carried-query-ratio-fails-after-symbol-edge-retyping.md
M	resources/seon/schemas/my.plan.edn
M	resources/seon/schemas/my.plan.item.edn
M	resources/seon/schemas/seon.activation.edn
M	resources/seon/schemas/seon.agent.edn
M	resources/seon/schemas/seon.cluster.eval.edn
M	resources/seon/schemas/seon.config.db.edn
M	resources/seon/schemas/seon.context.capture.edn
M	resources/seon/schemas/seon.db.edn
M	resources/seon/schemas/seon.error.edn
M	resources/seon/schemas/seon.fn.argument.edn
M	resources/seon/schemas/seon.fn.arity.edn
M	resources/seon/schemas/seon.fn.binding.child.edn
M	resources/seon/schemas/seon.fn.binding.edn
M	resources/seon/schemas/seon.fn.binding.entry.edn
M	resources/seon/schemas/seon.fn.edn
M	resources/seon/schemas/seon.issue.edn
M	resources/seon/schemas/seon.maintenance.receipt.edn
M	resources/seon/schemas/seon.maintenance.result.edn
M	resources/seon/schemas/seon.ns.edn
M	resources/seon/schemas/seon.runtime.edn
M	resources/seon/schemas/seon.schema.shape.edn
M	resources/seon/schemas/seon.source.edn
M	resources/seon/schemas/seon.test.edn
M	resources/seon/schemas/seon.test.run.edn
M	resources/seon/schemas/seon.turn.edn
M	src/seon/cluster.clj
M	src/seon/db.clj
M	src/seon/schema.clj
M	test/seon/db_test.clj
A	test/seon/owned_value_test.clj
M	test/seon/schema_test.clj
A	docs/prds/context-generation/research/reset-g5-fast-10-2026-09-17.txt
A	docs/prds/context-generation/research/reset-g5-fast-11-2026-09-17.txt
A	docs/prds/context-generation/research/reset-g5-fast-12-2026-09-17.txt
```

## Final integration rebase — 2026-09-17

Read `git log 5dd6ef7cc..steward-platform --oneline` in the main checkout and
reviewed the overlapping landed changes. Rebased onto `64d6a85cd`; code head
`d122b8590`. All landed resources remain, including stage 2 acquisition,
claim completion and reuse declarations, instrument `actual` / `actual-size`,
message/wake changes and the source progress callback's complete contract.
AGENTS §2.4 retains every-function contracts, including private functions;
the subsequent private-contract audit and its instrument regression are retained.
The newest-published-base fast-overlay policy remains beside the exact-HEAD
snapshot shortcut. No upstream production changes arrived between the two
rebases; only documentation, skills and the private-contract regression did.

Conflicts were confined to the lane's schema/edge reader overlaps. Stage 2's
new resolver and reuse reader now use symbol lookups. Analysis provenance is
an exact analyzed-input digest (including resolver context), so resolution
requires the admitted evidence and compares the acquired whole-program digest;
it does not incorrectly rehash declaration text alone. Result recording retains
existing admission and refuses to reconstruct a deleted definition. The schema
reference-grammar fixture now visits inherited `:and` map arms, including the
new claim-completion declaration, instead of manufacturing an incomplete row.

Serial worktree fast results (all with `bin/test-fast --paths`, no overrides):

- [Rebase fast 1](reset-rebase-fast-1-2026-09-17.txt), code `6ca147b40`:
  `seon.owned-value-test seon.db-test seon.fn-test seon.program-test
  seon.schema-test seon.maintenance-schema-test seon.reset-edges-test`:
  **191 tests / 1,673 assertions / 1 failure / 0 errors**. The sole failure was
  the conjunction fixture omission described above; all six other namespaces
  passed. Ten carried queries: raw 561,249 ns, wrapped 2,538,667 ns, measured
  ratio 4.52324547571577; each query passed the absolute 5 ms contract.
- [Rebase fast 2](reset-rebase-fast-2-2026-09-17.txt), code `acc23af0b`:
  `seon.schema-test seon.test-test`: **32 tests / 495 assertions / 0 failures /
  0 errors**, exit 0. Nested bounded-test and assertionless fixture diagnostics
  are intentional tested results, not failures of this namespace run.

Foreign boundary: a hook briefly rejected one read-only tool invocation naming
`bin/hook_probe_1.clj`; that file was absent from this worktree and the next read
succeeded. No foreign file or session was changed. The isolated fast snapshots
continued unaffected. Default was never accessed. Cold/platform and reset-live
proofs remain the orchestrator's work after the ff-only merge and ONE reset.

Final G5 run at rebased code `d122b8590`:
[Rebase fast 3](reset-rebase-fast-3-2026-09-17.txt),
`bin/test-fast --paths src/seon/db.clj test/seon/owned_value_test.clj -- seon.owned-value-test`:
**5 tests / 68 assertions / 0 failures / 0 errors**, exit 0.

Final documentation-only upstream catch-up: base `c314408c0`, rebased checkpoint
`a7ec23fff`. `git diff d122b8590 HEAD -- src resources bin test` is empty:
the tested implementation bytes are unchanged. Merge with
`git merge --ff-only reset-batch` from `steward-platform`; then the orchestrator
performs the single reset procedure in the plan. RESET NEEDED.
