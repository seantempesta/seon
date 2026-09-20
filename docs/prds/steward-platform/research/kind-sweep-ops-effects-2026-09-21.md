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
