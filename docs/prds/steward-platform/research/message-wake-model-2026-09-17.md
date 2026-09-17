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


### Coupled subject and origin preparation

The subject/protocol seam removes target resolution and stores the supplied
nonempty string. `:seon.message/from` alone marks inside; error notifications
now supply their population sender. `:seon.message/assignment` stores the
assigned evaluation identity and joins declinations independently of subjects.
The canonical regressions exercise absent subjects, subject deletion, sender-only
classification and protocol correlation. The origin seam stores the issue ID
unchanged through generation, recording and carry-forward; the outline labels
that value without resolving the issue. Its regression renders before and after
issue deletion.

**RESET NEEDED** is folded into the reset batch. These are review commits for
one orchestrator publication, not successive live adoptions. The default
read-only form is retained in
[the route probe](message-wake-route-probe-2026-09-17.clj).
Static clj-kondo inspection reported zero errors. The first combined fast run
uses a HEAD-plus-owned-paths snapshot and a 2,400-second foreground bound;
no slot/silence override, cold gate or default mutation was used.

That run exposed an additional path in the owned opening class: the automatic
system-read pass attempted recording while the agent was already open, before
`open-call` could return normally. The existing system-turn writer now declines
that stale append at its final history/open-state decision. Explicit submitted
source retains its busy refusal. The state-machine oracle now expects duplicate
opens to commit no change; genuinely conflicting identities still refuse.
No since-diff selection, comparison or supersession algorithm is changed.

Foreign source boundary during preparation: `src/seon/sci/eval.clj` and
`test/seon/sci/eval_test.clj` are held and excluded from the snapshot. The stage-2
runner/test changes landed while this lane was working; none was edited or
operated here. Final tallies and review commits follow below.

A second read-only census at basis **536871060** (3 ms) measured current datoms:
about **0**, origin **0**, inbox **0**, read-tx **2**. Thus read-tx deletion is
also explicitly in the coordinated reset batch; it is not a zero-datom deletion
on this reseeded cluster. The query was `(into {} (map (fn [attribute]
[attribute (count (seon.db/datoms database :aevt attribute))])
[:seon.message/about :seon.eval/origin :seon.message/inbox :seon.message/read-tx]))`.

### First combined remaining-seam iteration

**225 tests / 2,037 assertions / 16 failures / 0 errors**, exit 1. Exact output:
[remaining-seam first run](message-wake-model-remaining-first-2026-09-17.txt).
The message owner (20 tests), agent message surface (10), wake (17), transcript
(18), assignment/subject (4), error notification (36) and plan completion (1)
namespaces had zero failures/errors. The new settlement/history and
subject-deletion/inside-marker regressions passed. Issue opening also retained
the new origin value.

Six failed assertions are addressed in the next serial iteration: the automatic
system append while busy (2), duplicate-open model oracle (1), outline's old
agent-origin expectation (1), and the routing fixture's old about/protocol and
started-at shapes (2). Ten remaining assertions are outside the changed
mechanisms: eight program deletion/admission-provenance assertions, the existing
installation bound (**414.536458 ms** against 300 ms), and the existing issue
AI requery-form expectation. See [retained identities](../../../seon/issues/retained-identities-have-no-declared-retirement-state.md),
[installation timing](../../../seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md),
and [issue render expectation](../../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md).
No baseline run establishes a new attribution for those failures.

The obsolete cross-family string-uniqueness regression is removed: subject
observations no longer resolve across identity families. Its issue is marked
superseded by the ruling, and the subject regression now admits a token shared
by two distinct identity families.

### Second serial iteration

**91 tests / 1,207 assertions / 0 failures / 4 errors**, exit 1. Exact output:
[second run](message-wake-model-remaining-second-2026-09-17.txt). Turn (30),
agent message surface (10), subject/assignment (4), web debug/outline (17),
data shapes (9), read evidence (3), and HTML views (11) complete without
failures/errors. This proves the busy-turn correction, writer no-op state
machine, permanent handling history, subject deletion independence, and the
outline origin label surviving actual issue retraction under armed contracts.

Three errors occur in the pre-existing generative fixture's `inst-ms` on a
transaction ID, before its routing assertions. The fourth is the routing
fixture's attempted `/at` omission, refused by the canonical schema. It is
corrected to cover the six constructible stored states; a final focused run
follows. The unresolved fixture/state-reader mismatch is recorded in
[one lifecycle-fixture issue](../../../seon/issues/evaluation-fixtures-still-read-retired-lifecycle-shapes.md).
These observations do not change the production evaluation lifecycle.

### Final routing correction and review boundary

The focused canonical rerun passes **4 tests / 19 assertions / 0 failures /
0 errors**, exit 0, with contracts armed. Exact output:
[routing rerun](message-wake-model-routing-2026-09-17.txt).

Review commits: handling **a50424f6b**; subject/sender/protocol plus the busy
system-append correction **57581f12f**; the following origin commit contains
only the issue-ID schema, generating writer, turn carry-forward, outline
consumer and their tests, together with this note. Both mixed owner files were
split by their owned hunks; no foreign changes entered either commit.

All requested remaining behavior has canonical fast evidence. The full combined
run remains red at the explicitly recorded broader boundaries; no cold-gate
or platform success is claimed. Final foreign edits are
`test/seon/test_runner_test.clj` and the lane-guardrails research note. The SCI
owner's changes landed during iteration and were not edited by this lane.

**Publish these remaining seams together, under §§1h–1i. RESET NEEDED.** Default
was never stopped, reset, adopted or otherwise mutated. Its measured before
query is `[]`; the same real route datom yields an answered message under the
ruled `to` derivation. A deployed after query on default remains explicitly
**unperformed** because this lane was authorized to observe it read-only. The
orchestrator must run the retained probe after the one publication/reset and
reseed, then run the cold namespace gate and platform proof. The previously
integrated refresh deletion `78cc3b9b7` is unchanged.


## Batch 119 residue — fixture contracts, 2026-09-17

The approved seams (`a50424f6b`, `57581f12f`, `31ac4c05d`) and integrated
refresh deletion are unchanged. This follow-up reads the complete two test
owners, the attributed batch-119 failures, program-facts §§1h–1i, the message
schema, the answering/continuation derivations, and the current canonical
turn regression. The earlier named authorities remain read end to end as
recorded above; no new production mechanism or schema change is introduced.

`planner-census` now orders numeric `opened-tx` values directly, selects the
planner's provider turn rather than its generated opening, and counts current
terminal evidence (`seon.eval/shown`, error, interruption). Its execution
assertion likewise reads shown text. Once the exception was removed, the
old fixture exposed expectations from the retired automatic repair router.
Ledger ruling 67 says a failed form “becomes a flat error result and the rest
still run” and results are written “in ONE transaction at the end”. The
[2026-09-05 owner note](../../../seon/issues/archive/a-run-pays-two-and-a-half-seconds-between-every-form.md#left-behind-by-the-batched-turn-orchestrator-2026-09-05-night)
explicitly defers automatic owner routing; the canonical
`seon.cluster.turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues`
already asserts no automatic assignment. Therefore the generative fixture
now checks retained errors, continued sibling execution, one settlement
transaction, and completion prose leaving error evidence intact. It no longer
parses the retired vector evaluation identity out of old rendered prose.
The dependent-computation case injects an explicit exception and computes
with that failed definition, independent of Java class admission.

The first suspicion that the fold's `:seon.error/kind` predicate was a new
routing defect was withdrawn after reading that owner note and regression.
No production change to re-enable routing is warranted by this assignment.
Explicit subject/assignment/declination protocol coverage from the approved
seams remains intact.

The property counterexample `[true true true true nil []]` is **legal**. In
its generator, `nil` means no lint ordinal and `[]` means no settled
evaluations; neither is a message subject. The message has no `about` key,
as §1h permits. Its closed accepted reply has no disposition, so the ruled
`continuing-reply?` derives `:open` even though the wake is answered. The
property's idle oracle was wrong. It now expects continuation when planned;
a named canonical regression replays the minimal case, proves the subject is
absent and wake answered, then proves a completed disposition makes it idle.
Seed **2026072829**, **200 trials**, and the generator are unchanged.

### Iteration and boundary

The first serial path-isolated fast run reached **15 tests / 95 assertions /
7 failures / 1 error**: all remaining failures were the stale generative
routing oracle; the corrected totality property passed. Exact output:
[first residue run](message-wake-residue-first-2026-09-17.txt).
An intermediate invocation stopped at a test-source parenthesis error before
running tests; the source reader error was corrected before the next run.
The final fast run passes **16 tests / 100 assertions / 0 failures / 0 errors**,
exit **0**, including the unchanged **200-trial** property and the named
counterexample. Exact output: [final residue run](message-wake-residue-final-2026-09-17.txt).
Snapshot base: **c772db2d343ff6c29c3029d96573b49f59e6d8bf**; contracts armed
in panic mode, **1,123 registered / 1,123 instrumented**. The serial command
uses the foreground 2,400-second bound and no `SEON_TEST_*` overrides:

```sh
timeout 2400 bin/test-fast --paths test/seon/gen/loop_test.clj test/seon/turn_work_test.clj -- seon.gen.loop-test seon.turn-work-test
```

Foreign edits observed by `git status` include `src/seon/turn.clj` (program
retraction and turn-budget hunks), the adoption owners, and the guardrail
owners. They were preserved and excluded by `bin/test-fast --paths`;
no foreign session was operated or contacted. The two owned test files were
free at entry. No held production hunk is required for these fixture fixes.

**LIVE WAKE-FLIP PROOF OWED.** The owner reports default was reforked for the
origin type (PID **94566**) but is not adopted while the adoption hold-bound
blocker is repaired by its lane. This follow-up never connects to, adopts,
stops, resets, or mutates default. The retained real-message before/after
probe must still be run after the owner releases adoption. Canonical fixture
history/answeredness is not a deployed default proof. Cold namespace and
platform proof remain the orchestrator's responsibility. Stop for review at
this checkpoint.

## Live wake flip completed — 2026-09-17, adopted default PID 94566

The previously owed live proof is complete on the owner-adopted default,
PID **94566**, using explicit `(seon.operator/connection "default")` custody.
Four MCP evaluations were used. The first refused at compilation because
`ProcessHandle` required Java qualification; it changed nothing.
[Exact successful forms](message-wake-live-proof-2026-09-17.clj) and
[complete tool envelopes](message-wake-live-proof-2026-09-17.json) are retained.

Exactly one message was sent through the canonical Juniper `submit!` wrapper
evaluating `my.message/send`. Its identity is **3168e0a55d3d**. The subsequent
ordinary no-provider turn settled naturally; no second reply was injected.
The sender evaluation was a submitted system turn. The answering turn was
the ordinary no-provider turn: reply-t is later than opening-t.
No raw transaction, reload, adoption, stop/start, or reset was performed.
`seon.cluster.message/unanswered` is absent from current source; the owner
actually queried is `seon.turn/unanswered-wakes` with
`seon.turn/latest-answering-turn-t`.

Verbatim pre-send and before output:

```clojure
#:probe{:phase :pre-send, :pid 94566, :basis 536871178, :no-provider true}
#:probe{:pid 94566, :unanswered [{:db/id 85457, :seon.wake/attribute :seon.message/to, :seon.wake/t 536871180, :seon.message/id "3168e0a55d3d"}], :answering-t 536871113, :message "3168e0a55d3d", :listened true, :claims #{}, :basis 536871182, :phase :before, :route #{[85457 81760 536871180]}, :all [{:db/id 85457, :seon.wake/attribute :seon.message/to, :seon.wake/t 536871180, :seon.message/id "3168e0a55d3d"}]}
```

Verbatim after output:

```clojure
#:probe{:pid 94566, :unanswered [], :answering-t 536871184, :no-provider true, :message "3168e0a55d3d", :open nil, :claims #{["3ded5c0bc8a4" 536871184 536871185 536871186 536871186]}, :basis 536871186, :phase :after, :route #{[85457 81760 536871180]}, :all [{:db/id 85457, :seon.wake/attribute :seon.message/to, :seon.wake/t 536871180, :seon.message/id "3168e0a55d3d"}]}
```

Verbatim temporal corroboration:

```clojure
#:probe{:pid 94566, :basis 536871186, :open-at-536871184 "3ded5c0bc8a4", :claims-at-opening #{}, :attempts #{}, :answered-by-t true, :error-signatures nil}
```

The listened route remains **[85457 81760 536871180]** before and after.
Before, the answering basis **536871113** precedes the message wake and
the unanswered result contains it. Afterwards, unanswered is `[]`, while
`:answered? :any` still returns the same historical message wake.

The claim tuple is **[turn-id opening-t reply-t closed-t claim-t]**:
**["3ded5c0bc8a4" 536871184 536871185 536871186 536871186]**.
The as-of query confirms the turn was open at **536871184**, without a claim.
Its settlement writes the handling claim and close together at **536871186**.
Answering is independently **536871180 ≤ 536871184**, not claim presence.
Datahike's inclusive as-of predicate is grounded at
`reference-code/datahike/src/datahike/db.cljc:142–147`.

Provider suppression is true before and after; the handling turn has **zero
provider attempts**. The error-signature aggregate returned literal `nil`
(no matching rows), preserved verbatim rather than rewritten as zero.
Runtime status likewise reported no error-signature family; its other
reported families were four errored evaluations and 23 failed-test facts.
This note does not claim those broader families are empty.

Git status was clean before this evidence-only slice. No foreign session
or file was touched. No test JVM or cold gate was run. This proves the
adopted pre-reset cluster above, not any subsequent reset's new store.
Stopped for review.


## Batch 122 B — message documentation, 2026-09-17

The attributed failure in `tmp/orchestrator/gate-results/batch-122.log`
(lines 3567–3581, reported HEAD `5dd6ef7cc`) compares documentation of two
*different functions*: `doc` returns `my.message/send`, but the invalid
recipient reaches the JVM owner `seon.cluster.message/send!`, whose contract
refuses it and whose documentation is correctly attached to that refusal.
Copying the public docstring onto that owner would conceal the fixture defect
and duplicate its source of truth.

The owning docstring now states the §1h grammar: optional nonempty string
subject token stored verbatim without resolution; independent evaluation
assignment token; sender supplied from agent identity, with `from` alone
marking an inside wake. Both `doc` and mistake documentation still use the
existing `function-doc-map` / `docstring-parts` projection of that one
indexed docstring (`src/seon/sci/eval.clj:1250`, `:1319`, `:2240`).
No second documentation text or alternate error projection was added.

The regression now seeds the canonical cluster with `:on-core-error :panic`
before acquiring its SCI context and supplies explicit connection/agent
custody. Previously the request's panic dial did not configure the database
used by `install-function-contract!`: `instrumentation-config` falls back
to `:record` without cluster config, and `wrap-interpreted` then returns the
unwrapped function (`src/seon/sci/eval.clj:661–685`,
`src/seon/instrument.clj:492–532`). The armed JVM owner consequently caught
what the unarmed interpreted surface missed. The test additionally asserts
that the refusal names `my.message/send`, verifies all three grammar terms,
and retains whole-document equality, example equality, and no-message-write
assertions. Malli's wrapper contract was read at
`reference-code/malli/src/malli/core.cljc:3119–3142`.

The current message owner, schema and relevant evaluator/instrumentation
seams, program-facts §1h, and canonical testing skill were read for this fix.
The diagnosis and outstanding verification are recorded in
[the fixture issue](../../../seon/issues/documentation-fixture-omits-interpreted-contract-configuration.md).

**Verification blocked before any JVM, not a passing test:**

```sh
timeout 2400 bin/test-fast --paths src/my/message.clj test/seon/sci/documentation_test.clj -- seon.sci.documentation-test
```

At snapshot HEAD **1b6fb159829ae302d16efd9c0c42b7f973134ed7**, the command
exited **64** with: `No published program graph matches the source snapshot;
orchestrator must run: bin/test --prepare-head-base`.
[Exact launcher output](message-documentation-fast-2026-09-17.txt).
AGENTS §5/§7 reserves baseline preparation to the orchestrator; no lane
publication, unisolated fast run, or cold gate was used to bypass it.
The proposed fix and regression are committed for review, **not yet verified**.

`git status` confirmed both owned code paths free at entry. Foreign changes
in operator state, store, filesystem, operator tests and program tests were
preserved. A transient shared-tree syntax refusal named only the foreign
`test/seon/operator_test.clj:654–684`; no edits were made there. The actual
fast verification boundary is the absent source-matching published graph,
not that foreign syntax. No default connection, evaluation, adoption or
lifecycle operation was performed. The prior live wake proof remains complete.


## Batch 123 B — canonical arming order, 2026-09-17

**Root fix pending at the held runner file; cold remains red.** Batch 123 B,
`tmp/orchestrator/gate-results/batch-123b.log` at `312f60560`, lines
1545–1556, reports the inner `seon.cluster.message/send!` instead of
`my.message/send`. This falsifies the Batch 122 diagnosis above: adding
cluster configuration cannot replace an already copied core callable.

The input schema is complete: `resources/seon/schemas/my.message.edn`
requires `:my.message/to`, whose grammar is a nonempty string. The published
manifest on log line 5 records `my.message/send` as `:core`, with contract
`[:=> [:cat :my.message/send-request] [:or :seon.message/message :seon.error/value]]`.
There is no permissive input union admitting recipient 42. The existing
subject/assignment/from docstring is the wanted single source; neither the
schema nor documentation formatter needs a change.

Dependency and first-party trace: SCI `copy-var*` stores `@clojure-var`
(`reference-code/sci/src/sci/core.cljc:112–138`).
`install-first-party-namespaces!` uses that operation to acquire core
functions. `test/seon/test_support.clj`'s `create-base` builds the cached SCI
context. The cold `worker-command-loop!` in `src/seon/test/runner.clj`
primes that fixture before its command loop initializes contracts. JVM
arming then replaces roots; the SCI outer function remains unarmed while
its inner JVM Var is armed. `src/seon/test/fast.clj` initializes contracts
before any fixture, which explains why the same test passes fast.

The exact root repair calls the existing `initialize-contracts!` with the
worker's packaged projection before base acquisition. It is recorded in
[the pending hunk](message-documentation-arming-pending-2026-09-17.patch).
`git status --short` and `git diff --numstat` confirmed concurrent changes
in `src/seon/test/runner.clj` (118 additions, 82 deletions at observation),
as well as `src/seon/sci/eval.clj`; neither was edited. This is the assignment's
held-item boundary, not a claim that foreign code caused this failure.

The existing documentation regression now uses
`preserving-instrumentation-state` and asserts that the acquired SCI callable
carries the canonical host wrapper. It never calls an arming function.
The earlier diagnostic fast iteration observed verbatim:

```text
MESSAGE-CONTRACT-EVIDENCE {:host #:seon.instrument{:var #'my.message/send, :authored [:=> [:cat :my.message/send-request] [:or :seon.message/message :seon.error/value]]}, :sci #:seon.instrument{:var #'my.message/send, :authored [:=> [:cat :my.message/send-request] [:or :seon.message/message :seon.error/value]]}, :row {:seon.fn/spec [:=> [:cat :my.message/send-request] [:or :seon.message/message :seon.error/value]], :seon.schema.admission/source :core}}
```

That iteration passed 9 tests / 104 assertions. The diagnostic print was
replaced by the persistent wrapper assertion for the final iteration.
Default was not observed or operated. No publication, reset, provider call,
cold gate, test override, or foreign session operation was performed.

Final iteration (exit 0), with only the owned test path over HEAD:

```sh
timeout 2400 bin/test-fast --paths test/seon/sci/documentation_test.clj -- seon.sci.documentation-test
```

```text
bin/test: snapshot differences from HEAD b939728cbfb7bb6391e83a4ebea534d74a4b6323:
Ran 9 tests containing 105 assertions.
0 failures, 0 errors.
```

`git apply --check` accepts the pending runner patch against the current
shared tree; it was not applied. `git diff --check` passes on the owned
changes. No root-fix or cold-pass claim is made. The pending hunk and cold
verification remain the only unresolved item in this bounded follow-up.
