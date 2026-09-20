---
type: research
status: active
created: 2026-09-21
tags: [error-model, kind-retirement, ops-effects]
---

# Kind sweep — ops/effects landing note

## Result

This lane stopped before a production commit at a held cross-family schema
boundary. The assigned family is not kind-free: the source census at HEAD
`0a63b34594` has 199 matching lines in 19 assigned source files for
`:seon.error/kind|seon.error/class|error/error?`. No claim of completion is
made.

`src/seon/operator/state.clj` was dirty before the lane reached it and remains
held/owed. The lane did not edit the default cluster, create a worktree, run a
cold gate, or edit any foreign dirty path.

## Exact stop evidence

The first coherent conversion targeted `seon.background/poll`, one of the
cold-gate wrapper refusals. Its local invalid-result and missing-result
producers were converted in a temporary draft to the pure
`seon.error.refusal/diagnostic` constructor, with exact output facets and
distinct substantive observations.

The required foreground command was:

```text
bin/test-fast --paths src/seon/background.clj resources/seon/schemas/my.background.edn -- seon.background-test
```

It launched one JVM and stopped during snapshot admission, before executing a
test. Run id `94313115737c` refused
`:my.background/invalid-result-error`: "A boolean marker alone cannot define an
error facet." The same owner resource also declares
`:my.background/invalid-call-error` using only the boolean
`:my.background/invalid-call` marker. Its producer is
`src/my/background.clj:10-25`, a held file belonging to the already-landed
my-protocol sweep; it currently supplies the authored form only inside
`:seon.error/diagnostic-evidence`, not as a required facet member. Converting
the shared resource without that producer therefore makes the held producer's
returned facet invalid. The draft was removed completely after the refusal;
the assigned production and schema files are unchanged by this lane.

This is the binding held-path boundary: the facet and its producer must change
in one publication, but this lane may not edit `src/my/*`. The owner should
return `src/my/background.clj` and `resources/seon/schemas/my.background.edn`
to one lane, adding a substantive required authored-form observation to the
invalid-call facet, before ops/effects resumes the background slice.

## Cold-gate evidence and handoffs

The starting gate evidence was read from
`tmp/orchestrator/gate-step1-2026-09-21.log` and
`tmp/orchestrator/gate-step1-reds.txt`. The named owned wrapper refusals remain
owed for `seon.problems/problems`, `seon.ai/complete`, `seon.plan/plan!`,
`seon.background/poll`, and `seon.ai/request-body`. The schedule regression at
`test/seon/schedule_test.clj:383` still queries the retired stored kind datom;
the maintenance failure at `src/seon/maintenance.clj:419` remains unconverted.

Pass-through producers owned by `seon.db`, `seon.cluster.message`,
`seon.turn`, and `seon.cluster.reply` remain handoffs to their assigned sweeps.
No new facets were landed.

## Verification and proof owed

The temporary background draft passed the direct namespace load probe before
the canonical schema authority refused it. The fast run has no tally because
snapshot admission failed. The family-wide load command, zero-kind census,
and path-limited fast namespaces remain owed after the held schema/producer
pair is repaired.

The orchestrator ultimately owes the cold command over every changed
ops/effects source, schema, and test namespace, followed by
`bin/test --platform`. No cold or platform proof was run here.

## Resumed background slice — `5a42b6f2f`

The orchestrator released `src/my/background.clj` and its test after
`9875331d1` and ruled that marker-only facets gain substantive required
observations. The resumed slice converted all three background facets and
their producers:

- invalid macro calls carry `:my.background/call-source`, the refused forms as
  a value observation;
- invalid poll arguments carry `:my.background/result-observation`;
- absent receipts carry `:my.background/missing-result-ref`, the valid lookup
  ref that found no entity.

Each new attribute is declared in `my.background.edn` with its scalar shape,
value deletion semantics, and description. The boolean markers and class
metadata were deleted. `seon.background/poll` now constructs the diagnostic
base through `seon.error.refusal/diagnostic`; `poll` and `await` declare the
exact local and pass-through facets. The R8 tests validate complete facets and
their distinguishing evidence. The fixture's effect-owner lookup now carries
the required qualified symbol rather than a string.

The ordered kind/class/predicate, inline-base, and generic-output searches
returned zero lines for the four committed paths. The HEAD load probe printed
`:loads` for `my.background` and `seon.background` before the commit.

The foreground fast command over `my.background-test` and
`seon.background-test` launched one JVM but executed no tests: snapshot
admission refused the concurrent dirty
`:seon.test.runner/invalid-marker-reason-error` because it used non-storable
`:seon.error/data` as a stored facet member. That resource is held by the
test-system sweep and was neither edited nor added to this overlay. Run
`cce3e98cf786` therefore has no tally. The background namespaces must be
included in the final foreground rerun after the runner schema lands.

## Problems slice — `3633edd55`

`seon.problems/problems` now declares the exact test-execution, database-read,
and missing-projection facets it can pass through, closing the cold gate's
undeclared-output refusal. The problems derivation no longer projects or
queries the retired kind datom. Error signatures retain their queryable
signature and complete latest fact; errored evaluations derive from the
presence of `:seon.cluster.eval/error`; routable form problems carry their
error text and provenance without pretending to be error values. HTML and log
rendering likewise omit the retired classification.

The two unused marker-only schemas were deleted rather than replaced:
evaluation failure and unbound-var status are already expressed by the
evaluation's error/interruption/admitted-value facts, and neither schema had a
producer after the derivation stopped stamping kinds. The remaining problem
row schemas now describe those derived rows instead of requiring a diagnostic
base they never carried.

The ordered source/resource searches returned zero kind, class-marker,
general-predicate, inline-base, and generic-output hits. `seon.problems`
reloaded from HEAD and printed `:loads`; clj-kondo reported zero errors and
zero warnings. Fast tally owed — admission remains held by the concurrent
dirty `resources/seon/schemas/seon.test.runner.edn`. The stale R8 fixtures and
assertions in `test/seon/problems_test.clj` remain for the final problems test
slice; they were not changed or claimed by this commit.

The runner resource briefly appeared clean, so the required accumulated pass
ran background and problems together. It executed **19 tests / 71 assertions /
5 failures / 9 errors** before result recording refused the still-live
test-runner draft; run `ff32517c9a41`. The run exposed and the next commits
fixed two owned causes: `034181b55` copies the latest complete error fact's
base summary into each signature row, and `4e62f6370` supplies symbolic effect
owner/capability facts in both background fixtures. Both corrections load from
HEAD and lint with zero warnings. The remaining problems-test failures include
retired-kind fixtures/assertions still owed by this lane; the refreshed
background/problems fast pass is owed after those test inputs change.

`d80232a4f` completed the problems R8 conversion. Error fixtures now supply
diagnostic base evidence instead of kind-bearing exceptions; errored
evaluations derive solely from their error fact; database-write and
missing-projection assertions inspect operation/layer evidence; stale function
identity is a symbol; and signature assertions use the observing operation.
The ordered kind/class/predicate search is zero and clj-kondo reports zero
errors and warnings. A plain `clojure -M` cannot load the test namespace
because `test/` is not on that classpath; execution remains the fast gate's
job. A refreshed `seon.problems-test` tally is owed after the runner resource
finishes its concurrent conversion.

## ops-effects-2 continuation — AI, 2026-09-20

Read this note, the error-conversion PRD, the my-protocol and test-system
landing notes, and error-family-1a end to end. Read AGENTS lane rules 11–16;
used data-oriented-clojure, repl, llm-providers, data-modeling and
clojure-testing. Preserved every inherited dirty path. No other lane's
session, default lifecycle, or cold gate was operated.

Default PID 24777 was alive. MCP health refused at
`seon.problems/problems`, path `[:seon.problems/error-signatures 0
:seon.error/at]`, missing the required instant. This is the already recorded
[runtime-status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md),
whose file was foreign dirty and left untouched. A read-only request-body
probe reached the live schema authority but lacked its handed projection;
it proves no changed AI behavior. No adoption or browser proof is claimed.

Dependency ledger: Malli's output validation is
`reference-code/malli/src/malli/core.cljc:2203`; the first-party facet wrapper
is `src/seon/instrument.clj:627`. The pure, field-preserving constructor is
`src/seon/error/refusal.clj:39`; the already-landed idiom is
`src/seon/background.clj:51`. No HTTP protocol, retry, provider descriptor,
or transport scheduling behavior is changed.

AI producers now construct the diagnostic base and concrete facets. Required
observations replace marker-only members: extra-body source, conflicting
builder keys, unreadable response member, unanswered reasoning count,
exhausted finish reason, interrupted text count, and missing credential
variable name. Arbitrary offending objects remain in `:seon.error/offending`
and diagnostic evidence, for the occurrence recorder's existing projection.
Unused marker-only credential/response/model/before-send descriptors had no
producer or source caller and were removed. Existing status/endpoint/bound
facets retain their substantive members and compose the base. Request-body,
parser, streaming, HTTP and complete contracts name their returned unions.
Tests inspect complete facets and the existing concrete evidence; prose no
longer embeds the transport cause chain, which remains available as data.

At starting HEAD `b5e3a5e40`, retirement matching lines changed:

| Path | Before | After |
| --- | ---: | ---: |
| src/seon/ai.clj | 26 | 0 |
| resources/seon/schemas/seon.ai.edn | 20 | 0 |
| test/seon/ai_test.clj | 25 | 0 |
| test/seon/ai_stream_fold_test.clj | 9 | 0 |

`clojure -M -e "(require 'seon.ai) (println :loads)"` printed `:loads`.
clj-kondo: **0 errors / 0 warnings**, one redundant-coercion info.
The one accumulated fast invocation selected `seon.ai-test` and
`seon.ai-stream-fold-test`. It armed 1,423 contracts, then exited 1 before
tests: `record-persistent-results!` refused
`:seon.test.accretion/install-refused-error` at
`:seon.test.accretion/arguments`, "A stored error member must have a
storable registered attribute." **Fast tally owed — admission held by
resources/seon/schemas/seon.test.accretion.edn** and its recording authority;
that held resource was clean locally, so this is not attributed to an
uncommitted draft. No tests executed and no green verdict is claimed.

Debt: `seon.db/pull` still declares `:seon.error/value` through
`:seon.db/error-result`; AI's three inherited database propagation branches
retain documented base checks. The reply/SCI producer assertions in AI tests
use the existing observed text/reader evidence; their producers remain the
other sweeps' responsibility. New facets also need the error owners' complete
pass-through manifest review. This lane does not edit those owners.

Cold proof owed for this slice (orchestrator only):

```sh
bin/test --paths src/seon/ai.clj resources/seon/schemas/seon.ai.edn \
  test/seon/ai_test.clj test/seon/ai_stream_fold_test.clj \
  -- seon.ai-test seon.ai-stream-fold-test
```

Then `bin/test --platform`, plus the earlier background/problems obligations.
New scalar observations require publication; removed marker attributes join
the wave reset. No existing attribute's type was changed in this slice.

### ops-effects-2: plan checkpoint

Converted plan producers to complete diagnostics and required substantive
facet members, including previously undeclared query-bound/query-failure
refusals. Returned contracts name the concrete facets. Completion regressions
assert the failed or unsatisfied item's identity and retain their result/state
assertions. Removed the unreachable older-completions branch; stored-comparables
now propagates a refused database read instead of interpreting it as no rows.
Database propagation retains documented base checks pending the database sweep.

Changed: src/seon/plan.clj, resources/seon/schemas/my.plan.edn,
resources/seon/schemas/seon.plan.edn, test/seon/plan_completion_test.clj.
Retirement census is zero in those paths and test/seon/plan_test.clj.
The namespace load printed `:loads`; lint reported 0 errors / 0 warnings.
The single accumulated fast invocation selected seon.plan-test,
seon.plan-completion-test and my.plan-test, armed 1,435 contracts, and exited
before test execution. Recording refused
`:seon.test.runner/invalid-marker-reason-error` at `:seon.error/offending`:
"A stored error member must have a storable registered attribute."
Fast tally owed — admission held by the seon.test.runner schema family and
recording authority (held resources/seon/schemas/seon.test*.edn and
src/seon/test/*.clj). This is not a test failure or a green verdict.

Cold command owed (orchestrator only), in addition to the AI command above:

```sh
bin/test --paths src/seon/plan.clj resources/seon/schemas/my.plan.edn \
  resources/seon/schemas/seon.plan.edn test/seon/plan_completion_test.clj \
  -- seon.plan-test seon.plan-completion-test my.plan-test
```

No live adoption or browser proof claimed. The new observation attributes and
retired marker attributes join the publication/reset boundary.

### ops-effects-2: schedule checkpoint

Changed src/seon/schedule.clj, resources/seon/schemas/seon.schedule.edn,
test/seon/schedule_test.clj. Retirement census: source 10 → 0, resource
7 → 0, test 7 → 0. Seven thrown observations now carry complete diagnostics
and substantive members: undated task entity, unexpected fire identity,
incomplete task identity, changed task identity, missing receipt identity,
invalid terminal member, unresolved handler symbol. Deleted the unused
missing-execution-handle marker declaration. The transaction wrapper preserves
the database refusal unchanged; no second error classification is introduced.
Handler regressions assert recorded operation plus the existing wake,
occurrence count and receipt relations; fixture function declarations use the
canonical analyzed helper.

Namespace load printed `:loads`; lint: 0 errors / 0 warnings.
The single accumulated fast request for seon.schedule-test stopped before
tests at the same held invalid-marker-reason-error declaration as plan.
Fast tally owed — admission held by resources/seon/schemas/seon.test*.edn
and src/seon/test/*.clj. No green or live adoption proof claimed.
Base-check debt: seon.db/transact! and maintenance/result-entity-response
still declare generic error outputs. Maintenance is next in this lane.

Cold proof owed (orchestrator only): `bin/test --paths src/seon/schedule.clj
resources/seon/schemas/seon.schedule.edn test/seon/schedule_test.clj --
seon.schedule-test`, followed by platform proof with the accumulated slices.
Skill drift is recorded in
`docs/seon/issues/flow-skill-forbids-current-turn-acquisition.md`.

### ops-effects-2: maintenance checkpoint

Changed src/seon/maintenance.clj, resources/seon/schemas/seon.maintenance.edn,
test/seon/maintenance_test.clj, test/seon/maintenance_schema_test.clj.
Retirement census is zero in all four. Projection refusals require the
matching producer count, unresolved producer symbol or failed producer symbol;
the no-collection observation requires the uncollected root. Returned
contracts name these facets. Tests query the recorded operation and concrete
collection/root evidence. The stored summary components carry base evidence
instead of a kind.

Load printed `:loads`; lint: 0 errors / 0 warnings. One accumulated fast run
selected seon.maintenance-test and seon.maintenance-schema-test, armed 1,437
contracts, then refused snapshot admission at the same held
invalid-marker-reason-error declaration. Fast tally owed — admission held by
resources/seon/schemas/seon.test*.edn and src/seon/test/*.clj.

Remaining local follow-through: schedule's polymorphic handler result check
and maintenance's nested collection/claim-error projections must be reviewed
with the operator producer conversion. The operator cleanup slot still
declares :seon.error/value; seon.db/db also declares that generic output.
No durable diagnostic evidence beyond the existing component summaries is
claimed for nested operator refusals yet.

Cold proof owed (orchestrator only): `bin/test --paths src/seon/maintenance.clj
resources/seon/schemas/seon.maintenance.edn test/seon/maintenance_test.clj
test/seon/maintenance_schema_test.clj -- seon.maintenance-test
seon.maintenance-schema-test`, then accumulated platform proof.

### ops-effects-2: config checkpoint

Changed src/seon/config.clj, resources/seon/schemas/seon.config.edn and
test/seon/config_test.clj. Retirement census zero; config_application_test.clj
also has no retirement sites. Compiler throws now use the existing complete
seon.config/rule-error facet, naming the actual member and rule with raw
offending evidence. Legacy facet names alias the canonical read/compiler
facets; the unused missing-result-cap boolean declaration is removed.
Read/compile messages describe the constraint; missing/available keys stay in
diagnostic data. Database propagation checks retain explicit external debt.

Final load printed `:loads`; lint 0 errors / 0 warnings. The single accumulated
fast request for seon.config-test armed 1,438 contracts and stopped before
tests at held seon.test.runner/invalid-marker-reason-error, offending member
`:seon.error/offending`. Fast tally owed — admission held by the test-runner
schema/recording family. No live adoption proof claimed.

Cold command owed (orchestrator only): `bin/test --paths src/seon/config.clj
resources/seon/schemas/seon.config.edn test/seon/config_test.clj --
seon.config-test seon.config-application-test`, then accumulated platform proof.

### ops-effects-2: issue checkpoint and section 6 handoff

Read every named authority end to end: this note, the error-conversion PRD,
the my-protocol and test-system sweep notes, error-family-1a, and the supplied
AGENTS.md (including lane rules 11–16). The roadmap entry was also read.
No held source was edited, no foreign session was operated, and no default
cluster lifecycle operation was performed.

Issue producers now construct complete observations with substantive subject,
detector, reference, assignment and path evidence. Returned contracts name
the corresponding local facets and the inherited database refusal contract.
Detector reads propagate database refusals with documented debt. Tests assert
positive success evidence or the concrete refusal member and retain their
database-state assertions. The recorder result consumer uses its declared
unknown/execution-refusal members.

The owned source load printed `:loads`; lint reported 0 errors / 0 warnings.
One accumulated fast invocation selected seon.issue-test,
seon.issue-generate-test, seon.issue-settlement-test,
seon.issue-deletion-test and seon.issue.detect-test. It armed **1,451**
contracts and stopped before tests at the same held
`:seon.test.runner/invalid-marker-reason-error` declaration, member
`:seon.error/offending`. **Fast tally owed — admission held by
resources/seon/schemas/seon.test*.edn and src/seon/test/*.clj.**
Final review narrowed the local returned-facet lists and preserved the
existing message-delivery error collection; final bytes were loaded and
linted afterward. The refused fast request is not claimed as proof of these
final bytes. No tests executed and no green verdict is claimed.

#### New declaration boundary — not a foreign gate failure

The reproducible declaration-only probe is
`docs/prds/steward-platform/research/kind-sweep-ops-effects-gate-facet-probe-2026-09-21.clj`.
It exited zero and printed the loaded `seon.fn/gate-sets` output:

```clojure
[:or [:map-of :seon.fn/sym [:vector :seon.test/sym]]
 :seon.db/invalid-read-error :seon.schema/missing-projection-error]
```

Derived required-member sets:

| Declared facet | Current required members | Boolean marker | Required after marker retirement |
| --- | --- | --- | --- |
| :seon.db/invalid-read-error | :seon.db/invalid-read, :seon.error/message | :seon.db/invalid-read | :seon.error/message |
| :seon.schema/missing-projection-error | :seon.schema/missing-projection, :seon.error/message | :seon.schema/missing-projection | :seon.error/message |

The assignment's marker retirement would leave identical required sets.
PRD §1.3 says the consumer must select a REQUIRED distinguishing member from
the callee's declared union and invokes §6 for that collision. Neither
`:seon.db/read-operation` nor `:seon.schema/expected-value` is required by
the declared facets. The generic-check exception does not apply because
gate-sets does not declare `:seon.error/value`; §1.4 forbids using the
message for recognition. The lane therefore stops before introducing that
collision and retains **one** explicit pending kind check in
`src/seon/issue/detect.clj`. This is a schema/contract decision at the held
`src/seon/fn.clj` seam, not the recorder failure and not dirty-source breakage.

Issue:
`docs/seon/issues/gate-selection-refusal-contracts-only-distinguish-markers.md`.

Three concrete options for the orchestrator:

1. **Recommended — correct the held gate-selection contract.** The owning lane
   traces gate-sets and its helper outputs and declares their actual substantive
   database/schema facets; this lane then converts the consumer. Cost: one
   bounded owner slice plus the detector regression/fast admission. Guarantee:
   exact declared outputs and a consumer grounded in required evidence.
2. **Permit an explicit transitional generic output.** The owner temporarily
   declares its actual database pass-through union, allowing the documented
   base-three exception. Cost: a contract edit and a later retirement follow-up.
   Gives up exact-output completion at this seam until that follow-up.
3. **Defer this one consumer by explicit scope ruling.** Continue the remaining
   owned families while recording this held seam for integration. Cost: no
   immediate owner source edit, but a later detector conversion and verification.
   Gives up a kind-free detector at this checkpoint.

#### Measured retirement census

Counts are literal occurrences of `:seon.error/kind`,
`:seon.error/class`, and `error/error?`, measured against each family's
pre-slice commit (issue against its pre-edit HEAD), then final checkout bytes.
Zero in this census does not imply an executed green test or completed live
publication.

| Path | Before | After |
| --- | ---: | ---: |
| src/seon/ai.clj | 26 | 0 |
| resources/seon/schemas/seon.ai.edn | 20 | 0 |
| test/seon/ai_test.clj | 25 | 0 |
| test/seon/ai_stream_fold_test.clj | 9 | 0 |
| src/seon/plan.clj | 5 | 0 |
| resources/seon/schemas/my.plan.edn | 13 | 0 |
| resources/seon/schemas/seon.plan.edn | 0 | 0 |
| test/seon/plan_completion_test.clj | 2 | 0 |
| src/seon/schedule.clj | 10 | 0 |
| resources/seon/schemas/seon.schedule.edn | 7 | 0 |
| test/seon/schedule_test.clj | 7 | 0 |
| src/seon/maintenance.clj | 9 | 0 |
| resources/seon/schemas/seon.maintenance.edn | 0 | 0 |
| test/seon/maintenance_test.clj | 6 | 0 |
| test/seon/maintenance_schema_test.clj | 4 | 0 |
| src/seon/config.clj | 4 | 0 |
| resources/seon/schemas/seon.config.edn | 7 | 0 |
| test/seon/config_test.clj | 3 | 0 |
| src/seon/issue.clj | 14 | 0 |
| src/seon/issue/detect.clj | 8 | 1 |
| resources/seon/schemas/seon.issue.edn | 0 | 0 |
| test/seon/issue_test.clj | 19 | 0 |
| test/seon/issue_generate_test.clj | 4 | 0 |
| test/seon/issue_settlement_test.clj | 8 | 0 |
| test/seon/issue_deletion_test.clj | 1 | 0 |
| test/seon/issue/detect_test.clj | 4 | 0 |

Remaining assigned source census at the section 6 stop:

| Path | Remaining occurrences |
| --- | ---: |
| src/seon/issue/detect.clj | 1 |
| src/seon/effect.clj | 12 |
| src/seon/flow.clj | 10 |
| src/seon/env.clj | 10 |
| src/seon/blob.clj | 5 |
| src/seon/shell/jvm.clj | 17 |
| src/seon/context.clj | 11 |
| src/seon/edit.clj | 9 |
| src/seon/bootstrap.clj | 12 |
| src/seon/operator.clj | 12 |
| src/seon/operator/state.clj | 16 |

`src/seon/store.clj` is absent. No differently owned store namespace was
substituted. `src/seon/operator/state.clj` was clean at the final census and
remains untouched; recheck before resuming.

#### Handoffs and proof still owed

- seon.db, seon.cluster.message, seon.turn and seon.cluster.reply producers
  remain the other sweeps' owners. Database generic-output debt is named at
  propagation sites. Error/refusal pass-through manifests need the new facets
  reviewed by their owners.
- Held gate-selection contract: the section 6 issue above. All assignment-held
  paths remain untouched, including fn.clj even when locally clean.
- Maintenance's nested operator refusal summaries and schedule's polymorphic
  handler-result recognition remain local follow-through with the operator
  conversion; no complete durable nested diagnostic preservation is claimed.
- The default runtime-status tool was degraded at entry by a stored occurrence
  missing `:seon.error/at`; the existing foreign dirty issue
  `docs/seon/issues/runtime-status-refuses-error-occurrence-count.md` owns it.
  No live adoption or browser proof is claimed. New attributes and removed
  marker declarations join the wave publication/reset boundary.
- Issue fixture files still contain prior symbol/fixture-generation debt
  beyond the changed retirement assertions. The existing foreign dirty issue
  `fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md`
  is not edited by this lane. Gate admission prevented their execution.
- Documentation hooks report stale Datahike gitlink citations in
  wave-3a-task-family-spec-2026-09-21.md:737 and
  wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md:750
  (`fcbd8862800e638dc0f8f5521111f999279cbcd2` versus current
  `e11845bac78e1241bca0766ddc07d978bd63d74a`). Those foreign documents were
  not edited.

Accumulated cold command owed, **orchestrator only**, after recorder admission
and the section 6 decision are resolved:

```sh
bin/test --paths \
  src/seon/ai.clj resources/seon/schemas/seon.ai.edn \
  test/seon/ai_test.clj test/seon/ai_stream_fold_test.clj \
  src/seon/plan.clj resources/seon/schemas/my.plan.edn \
  resources/seon/schemas/seon.plan.edn test/seon/plan_completion_test.clj \
  src/seon/schedule.clj resources/seon/schemas/seon.schedule.edn \
  test/seon/schedule_test.clj \
  src/seon/maintenance.clj resources/seon/schemas/seon.maintenance.edn \
  test/seon/maintenance_test.clj test/seon/maintenance_schema_test.clj \
  src/seon/config.clj resources/seon/schemas/seon.config.edn \
  test/seon/config_test.clj \
  src/seon/issue.clj src/seon/issue/detect.clj \
  resources/seon/schemas/seon.issue.edn \
  test/seon/issue_test.clj test/seon/issue_generate_test.clj \
  test/seon/issue_settlement_test.clj test/seon/issue_deletion_test.clj \
  test/seon/issue/detect_test.clj \
  -- seon.ai-test seon.ai-stream-fold-test \
  seon.plan-test seon.plan-completion-test my.plan-test \
  seon.schedule-test seon.maintenance-test seon.maintenance-schema-test \
  seon.config-test seon.config-application-test \
  seon.issue-test seon.issue-generate-test seon.issue-settlement-test \
  seon.issue-deletion-test seon.issue.detect-test
bin/test --platform
```

Earlier background/problems proof obligations remain as recorded above.

### ops-effects-2 continuation after 7721e5893

#### owed to fn.clj's release

The orchestrator ruled option 3 now, option 1 later. The previous gate-sets
condition is deferred, not a reason to stop this continuation. Held consumer
contract: `src/seon/fn.clj:1487`, `seon.fn/gate-sets`. Pending owned consumer:
`src/seon/issue/detect.clj:328`, `public-without-reaching-test`.

The substantive members proposed for the two currently marker-only facets
are, in addition to the base:

- `:seon.db/invalid-read-error`: require `:seon.db/read-operation` and
  `:seon.db.read/target`, reusing the existing `:seon.db.read/error` members
  and their declared types. The target is a substantive evidence component.
- `:seon.schema/missing-projection-error`: require
  `:seon.schema/expected-value` and `:seon.schema/refused-value`, reusing the
  existing `:seon.schema/validation-refusal` members and types. The existing
  `seon.db/projection-fallback` producer already supplies both. The expected
  key is the missing projection schema; the refused value records the caller.

The owning lane must make the producer and gate helper contracts agree in
one slice; then the detector can branch on `:seon.db/read-operation` and
`:seon.schema/expected-value`. No new boolean or per-facet EDN member is
proposed. `src/seon/fn.clj` remains untouched even when clean.

#### New section-6 boundary: polymorphic effect settlement

At inspected HEAD `61374edb8`, `src/seon/effect.clj:557` excludes a handler
refusal from ordinary effect datoms, but its admitted payload is explicitly
polymorphic (`resources/seon/schemas/seon.sci.admit.edn:68`). It declares no
refusal union whose distinguishing members could implement that decision.
This is a different condition from the deferred gate-selection collision.
Exact producer/consumer evidence, acceptance, and three priced options are
in [the effect settlement issue](../../../seon/issues/effect-settlement-cannot-classify-polymorphic-handler-results.md).

Recommended option: explicitly allow local base recognition at this genuine
polymorphic inspection boundary (1–2 hours, complete base errors excluded
from ordinary argument datoms, exception to §1.3). Alternative: carry and
validate the actual handler output contract (3–6 hours; stronger declaration
coupling, changes settlement inputs and needs a polymorphic-handler rule).
Third: expressly defer this consumer (15 minutes bookkeeping; retains kind
debt and postpones either implementation cost).

No production conversion or test edits in this continuation; remaining family
counts and the cold command above remain owed. Foreign schema and SCI files
were dirty and were not edited. No worktree, scratch cluster, or foreign
session was created or operated. No live adoption is claimed.

The one accumulated fast pass on 2026-09-20 at 08:59 UTC selected all 15
namespaces in the cold command above, with the same owned paths. Snapshot
HEAD `61374edb8507779462778113377026eb9cd91821`, published graph
`e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411`
(83 commits behind HEAD); 1454 contracts instrumented. Run
`454bb6a0f88f` refused snapshot recording before test execution:
`:seon.test.runner/invalid-marker-reason-error` includes the now-optional
`:seon.error/offending`, but the recording authority still returns
"A stored error member must have a storable registered attribute."
Fast tally owed — admission held by
`resources/seon/schemas/seon.test.runner.edn`, at that facet's offending
member. This is not a dirty-resource overlay refusal and is not evidence
that making the field optional was absent from the snapshot: the refusal
prints `{:optional true}`. Recorded tally unavailable; no tests executed.
No repeat pass was made. The launcher exited and removed its snapshot.
`clojure -M -e "(require 'seon.effect)"` then exited zero in the shared
tree. No source changed, so no new source lint or runtime proof is claimed.
