---
type: research
status: review
created: 2026-09-17
tags: [message, wake, evaluation, since-diff, deletion]
---

# Message wake model — first review seam

This checkpoint implements the independent supersession deletion (assignment
seam 4) on `message-wake-model-review`, based on `8dff32220`. It does not
claim that message handling or the origin retype has landed. Shared-tree
`src/seon/turn.clj`, `test/seon/db_test.clj`, `src/seon/render/transcript.clj`
and `AGENTS.md` were held, so implementation and verification use a disposable
HEAD worktree. No foreign edit is included, and default PID 33583 was never
stopped, reset, reloaded or reforked by this lane.

## Authorities read

Read AGENTS.md sections 0–7 in full; `.claude/skills/datahike/SKILL.md`
and `.claude/skills/data-modeling/SKILL.md` end to end; the Clojure, REPL and
testing skills; [the complete modeling spec](message-wake-and-provenance-modeling-2026-09-17.md),
[program-facts PRD §1h](../plan/program-facts-are-the-runtime-prd-2026-09-17.md),
[turn PRD §3 and §§13–15](../../context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md),
[ledger rulings 64 and 70](../../context-generation/plan/design-ideas-ledger-2026-08-13.md),
and [what listening is](what-listening-is-2026-09-16.md) end to end.
The specified message, wake, schema, turn transition/derivation, origin writer
and origin reader seams were read before editing. The context plan README and
latest working-edge checkpoint were also read.

Ruling 64:

> Messages get NO `read-at` fact: the owner wants a message POPPED into the REPL — removed from the agent's working set as it is handled, retained in history (Datahike keeps history) so counts remain derivable

Ruling 70:

> messages, faults, and schedule firings are three peer components each routed by its own attribute through the one Datahike listener ("I never said one wake queue"); handled = a claim ref from the handling run, because retracting a routed edge would wake.

The later explicit §1h ruling and this assignment require the handling claim
at settlement, although older PRD prose says no claim. Answering remains the
`:t` derivation; a claim must not become a second answering authority.

## Dependency ledger and archaeology

- Datahike gitlink `73afe78271a289861da236c5ac3457e64349653f`:
  `reference-code/datahike/src/datahike/db/transaction.cljc:26` rejects a
  duplicate unique datom; `:1153` calls a transaction function against its
  current database. The removed successor pre-read repeated that authority.
  `:998` sweeps incoming refs on entity retraction. The remaining since-diff
  uses ordinary observations, not a successor relation.
- `reference-code/datahike/src/datahike/db.cljc:142–152` makes `as-of`
  inclusive and `since` exclusive. First-party owners are
  `seon.turn/latest-evaluations`, `system-plan`, and `system-turn`.
- `reference-code/datahike/src/datahike/writer.cljc:393–417` invokes listeners
  only on a transaction report, before delivering the promise. This explains
  why the pending routed-edge repair must eliminate the retraction itself.
- `reference-code/sci/src/sci/core.cljc:331–357` supplies the real context,
  fork and evaluation operations used by `seon.test-support/fork-cluster-ctx`
  and the existing `seon.rereads-test` fixture.
- History: `7296d173b` renamed the turn owner. Reading its parent
  `src/seon/cluster/run.clj:760–830` shows the same explicit successor
  mechanism predating the present `source-key` fold. It is removed, not ported.

## Exact change

Delete `seon.turn/refresh-tx`, `refresh-call`, their private `refresh-run-id`
and forward declaration; delete `:seon.cluster.eval/refreshes` and its entity
entry. No production since-diff expression changes, including the unrelated
local binding named `refresh-tx` in `system-turn`.

Delete the obsolete refresh transition regression. Strengthen
`seon.rereads-test/stale-read-with-equal-value-refreshes-evidence-without-emitting`:
40 → 39 → 38 must append once per changed result, reconstruct the newest
shown value, and leave the next unchanged pass without a transaction. Its
canonical database must not install the deleted edge.

The caller census found two generic uniqueness regressions using the deleted
attribute, beyond the spec's named transition test. The fuller
`seon.db-test/unique-rejection-names-the-existing-owner-as-data` now collides
two canonically constructed evaluation identities, asserts refusal, unchanged
basis and surviving contender identity, and retains owner/rendering checks.
The narrower duplicate in `seon.transact-feedback-test` is removed.

The three requested vocabulary rows are recorded as TARGET, so the review
commit does not misrepresent the still-held message and origin seams.

## Live before/after query

A fresh read-only MCP query against `default`, PID 33583, reproduced the
spec's baseline at 2026-09-17 ~03:03Z. The first attempt could not return a
value through the degraded result projector. The second emitted the bounded
census with `prn` through the SAME MCP JVM tool (explicit root/cluster,
`read_only: true`, `timeout_ms: 20000`); no raw REPL sender or state repair.
Its complete stdout was available even though the return value still carried
`:seon.config/missing-effective` for 68 keys. Evaluation took **14 ms**.

The exact second form is committed in
[the census script](message-wake-model-census-2026-09-17.clj).

| Fact | Before | After |
|---|---|---|
| historical `:seon.message/inbox` assertions/retractions | 5 / 5 | not measured; this seam does not change messages |
| historical `:seon.message/to` assertions/retractions | 5 / 2 | not measured; this seam does not change messages |
| `unanswered-wakes` with `answered? :any` | 0 for each of five agents | message-seam live proof still owed |
| default unread wake count | 0 for each agent | message-seam live proof still owed |
| live `:seon.cluster.eval/refreshes` datoms | 0 | default adoption not attempted |

The latest answering `:t` was 536871054 for Juniper, 536871014 for root,
536871174 for `s3-provenance-c`, and zero for `s3-provenance-a/b`.
The listened set was `#{:seon.effect/to :seon.issue/agent
:seon.message/inbox :seon.schedule.fire/agent}`.

This refines the existing open
[effective-configuration issue](../../../seon/issues/the-default-clusters-effective-configuration-lost-every-required-fact.md):
MCP return projection is degraded, but the assertion that every form refuses
BEFORE evaluation is false for this observation. Stdout carried all the
counts above. An absence of successful return projection is not an absence
of execution. No message wake count has flipped yet, and no default adoption
or browser paint is claimed.

## Verification and remaining boundary

Production/test diff: 36 insertions and 219 deletions across six files;
the vocabulary adds three TARGET rows. Reader syntax and `git diff --check`
pass. A byte comparison against base `8dff32220` confirms `source-key`,
`latest-evaluations`, `system-plan` and `system-turn` are unchanged.
The exact verification command (shared slot directory linked to the main
checkout, so the isolated worktree cannot evade the three-slot limit):

```bash
SEON_CODEX_LANE=message-wake-model SEON_TEST_SLOTS=3 bin/test-fast --paths \
  src/seon/turn.clj resources/seon/schemas/seon.cluster.eval.edn \
  test/seon/turn_test.clj test/seon/rereads_test.clj \
  test/seon/db_test.clj test/seon/transact_feedback_test.clj -- \
  seon.cluster.message-test my.message-test seon.turn-test \
  seon.cluster.wake-test seon.render.transcript-test seon.cluster.turn-test \
  seon.rereads-test seon.db-test seon.transact-feedback-test
```


Fast iteration finished with exit **1**: **214 tests / 1,942 assertions /
21 failures / 1 error**. Full exact output is committed in
[the fast-run log](message-wake-model-fast-2026-09-17.txt). Admission waited
1,134 seconds for a shared slot. Contracts armed at 03:19:52Z with 1,109
instrumented functions; the last namespace finished at 03:32:38Z.

| Namespace | Tests | Failures | Errors |
|---|---:|---:|---:|
| seon.cluster.message-test | 20 | 0 | 0 |
| my.message-test | 10 | 0 | 0 |
| seon.turn-test | 29 | 0 | 0 |
| seon.cluster.wake-test | 17 | 0 | 0 |
| seon.render.transcript-test | 18 | 0 | 0 |
| seon.cluster.turn-test | 59 | 9 | 1 |
| seon.rereads-test | 2 | 0 | 0 |
| seon.db-test | 51 | 0 | 0 |
| seon.transact-feedback-test | 8 | 12 | 0 |

Both changed regressions pass under the canonical armed fixture, real SCI
context and real database. The complete iteration is red. No baseline run
was performed, so unchanged failing assertions are a verification boundary,
not independent proof that every failure predates this commit.

The runtime failures are missing admission provenance/identity-survival
expectations (eight assertions), a missing schema namespace (one error),
and installation measured at **464.287208 ms** against **300 ms** (one
assertion). Relevant existing records are
[retained identities](../../../seon/issues/retained-identities-have-no-declared-retirement-state.md),
[the missing namespace](../../../seon/issues/schema-declaration-regression-disagrees-with-current-row-shape.md),
and [the timing bound](../../../seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md).
The latter two receive this run's observations in this commit. The retained
identity note is concurrently held in the shared tree and is not edited.
Transaction-feedback failures are recorded in
[one validation-boundary issue](../../../seon/issues/transaction-feedback-regressions-disagree-with-final-report-validation.md);
its twelve failed assertions are unchanged by the removed duplicate test.
Their database/schema/evaluation owners are held in the shared tree.

No cold gate was launched. Default adoption and browser paint are unclaimed. This deletion
adds no schema type or migration; the fresh MCP stdout census measured zero live `refreshes`
datoms. No successful default adoption is claimed.

Held main-tree hunks at entry: `turn.clj:4885` adds the agent SCI context to
`install-evaluated-rows!`; `render/transcript.clj` carries the outline changes;
`db_test.clj` and AGENTS.md carry separate in-flight work. Only the isolated
review branch changes these files. Message handling requires `close-call`,
`unanswered-triggers`, delivery and its readers in one seam; origin requires
its turn carry-forward and outline consumers together. They remain for the
next review slice, as does the `about` split. No held file was edited in the
shared checkout and no foreign session was operated.

## Review handoff

This is one path-limited seam commit on the review branch, **not integrated
into the shared checkout**. The temporary worktree and its test process are
removed after committing; the branch retains the exact source and evidence.
Only copies of the committed landing note, census script, run log and new
validation-boundary issue are placed at their named main-checkout paths for review. Integration must merge
the small turn, database-test and vocabulary hunks with their current owners.
The orchestrator still owes the cold gate and platform proof. Stop here for
review as assigned; message routing/handling, subject split and origin are
not implemented or claimed. No reset is needed for this zero-datom deletion;
no reset-batch change is made.

## Remaining seams resumed — 2026-09-17

Seam 4 was integrated as `78cc3b9b7`; it is not reapplied. Entry status held
only `src/seon/test/runner.clj` and `test/seon/test_test.clj`. Default PID
66052 answers MCP. The new open-turn fault note is open. The modeling study
was read end to end, and PRD §§1h–1i and the updated modeling/Datahike skills
were read before editing. All remaining schema and consumer changes are
prepared without publication; the owner permits only read-only default access.

Dependency decisions: Datahike `db/transaction.cljc:1153–1154` supplies the
mid-transaction database to `:db.fn/call`; duplicate opening and handling
claims belong there. `:998–1015` sweeps incoming refs but not string tokens.
Message subject keeps `:my.message/about`'s existing nonempty-string grammar.
A claim records handling at close; wake answering still compares the wake's
transaction to the accepted turn's opening transaction. A genuinely newer
wake cannot be claimed by an older context.

Read-only default probe (18 ms): basis 536871047, Juniper answering basis
536871039. Current `unanswered-wakes` with `answered? :any` returned `[]`.
The same `wake/agent-wake-datoms` owner, supplied `#{:seon.message/to}`, found
message `9f84a5fe` at 536871034, answered=true. This is the ruled derivation
against a real permanent message edge, not proof of deployed code. Default
still declares inbox listened and about as ref. No adoption/reset is performed.

Handling seam prepared: listened `to`, turn `handled` refs at close, no
inbox/read-tx attributes or writers, pending inbox derives unclaimed messages.
Constructors and reverse readers use the permanent route. Open-call treats
an existing turn for the same agent as a writer no-op; explicit submitted
system source still reports busy at its own writer boundary rather than
constructing evaluations without a newly opened turn. Generic refusal tests
now exercise a genuinely missing agent. The regression covers two messages
before opening and one during it: only the former two are claimed at first
settlement, all three stay visible, and the next turn covers the third.
Reader syntax and diff whitespace pass. Fast iteration is pending completion
of the coupled §1h publication; no live implementation proof is claimed.
