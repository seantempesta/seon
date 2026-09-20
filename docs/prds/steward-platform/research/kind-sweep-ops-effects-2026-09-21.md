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
