---
type: issue
status: open
severity: blocker
tags: [issue, render, database, wave/live-drive-render]
---

# Preserve a preview execution when its run changes the inspected graph

## Evidence — 2026-09-06

A fresh isolated cluster at `tmp/render-source-live-2026-09-06`, built with
`5083a373e`, showed at least 49 closed runs containing the same identity source
between 21:18:04 and 21:18:23 UTC. Root personally opened the AI debug page
for Juniper entity 32288 on port 7722. Its graph grew from 11 reference
assertions to 44; the rendered result remained `nil`, and found-value
acquisition eventually reported result weight 4006 exceeding 4000.

Root independently queried the stored run identities through MCP. The proof
agent compared their exact source bytes and stopped the isolated cluster.
`bin/seon --root tmp/render-source-live-2026-09-06 status` confirms no live
clusters, no orphan JVM, and a retained 0.27 GiB root. The shared user cluster
on port 7773 was not changed. Preserve the stopped root until the regression
and repeated-render proof capture the needed evidence.

The existing render invocation evidence includes its acquired argument.
Submitting a source run adds a reverse run ref to that agent argument. Source
production can therefore become stale even when the produced source and its
evaluated database reads still describe the same result. Discarding the stored
execution identity together with producer-input evidence allows another run.
The exact invalidating evidence must be verified by the correction; do not
infer it solely from the number of runs.

## Second live falsifier and correction

The next isolated build included `bffaa3779`, preserving the prior call's run
identity across changes to its acquired argument. It still created six source
runs/evaluations during the bounded eight-second feed probe and was stopped.
Inspection identified another invalidation owner: each ordinary evaluation
announces `:seon.render.web/runtime-eval`, whose handler cleared all retained
calls and invocation entries before the following settlement wake.

`ab558c858` retains source execution references through that event while
discarding output, producer-input reuse evidence, and invocation caches. The
next pass must regenerate the producer's source and validate its stored run's
program, starting namespace, agent, and evaluation read evidence. Retaining a
complete reusable output entry here would instead mask live code changes.
This correction still needs a successful fresh live proof; its focused test
is not closure of this issue.

The third fresh proof still produced seven runs/evaluations and was stopped.
Its stored evaluation has 15 read-evidence entries. Three have dependency plan
`:all`: the history-view construction and the current/historical maximum
transaction queries in `seon.call-preparation/newest-row-transaction`. Their
attribute input is exactly `:seon.call-preparation/key`, `/schema`, and
`/supplier`; they maintain call-preparation cache coherence. The evaluated
identity pull itself has precise attribute dependencies. A result-settlement
transaction therefore invalidates the broad machinery observations even when
the identity data is unchanged.

The next correction belongs at that existing metadata observation boundary.
It must preserve real supplier/declaration dependencies while preventing
internal cache-coherence reads from claiming that an identity result depends
on every database fact. Ignoring false read validity or returning a permanent
stale-result refusal would not fix the requested behavior. Do not classify this
as another presentation-key mismatch.

## Browser reopen falsifier after metadata correction

`cd0c4e03b` excludes the internal maximum-transaction coherence reads from
evaluation evidence while retaining supplied-default declaration and supplier
dependencies. A fresh isolated cluster at
`tmp/render-source-live-2026-09-06-metadata` initially retained one execution
through a bounded feed close/reopen probe.

Root's subsequent in-app browser inspection of the namespace debug route on
port 7928, subject 32317, produced a second identical identity execution:
`source:9f1bbfe3-1a5f-4420-a8a2-ad25a533e852`, opened at
21:48:48.027 UTC, following
`source:f9da2695-c9f1-4b29-b527-b1621aa067ea`. This falsifies reuse across the
actual browser registration. The registration differences and retained call
lifetime still need examination; two executions alone do not establish another
unbounded loop. The owning agent stopped this isolated cluster to preserve
evidence. The issue remains open.

The same inspection found that the lower AI result serialized the entire
transcript as a quoted string with escaped newlines, while the run reference
preview displayed its multiline text correctly. The selected paired preview
still showed authored source without its evaluated result. Those presentation
boundaries must consume the existing terminal text directly.

## Owner and acceptance

Root's next personal browser review on 2026-09-06 used the stable namespace
URL on port 7766 (`tmp/juniper-context-live`, Juniper entity 32367). The AI
preview remained pending and the complete found-values section became an
`:seon.db/invalid-read` from `pull-many`: result weight 4056 exceeded 4000.
`acquire-debug-data` applies this single bound to the entire related-entity
batch. This is a failed user-facing result, not acceptable partial success
because the identity HTML still renders. Removing duplicate source execution
must be verified on this actual page; the remaining acquisition limits also
need reconciliation with the owner's unrestricted rendering experiment.

The more specific page-refresh cache deletion is tracked in
`archive/runtime-evaluation-reexecutes-retained-render-source.md`. Its regression
reproduces the duplicate clearing after the earlier invalidation correction.

Correct the existing retained call/invocation cache in `seon.render.web` and
`seon.render`. Preserve pending and terminal execution identity across producer
refreshes when source, program, agent namespace, and execution read evidence
remain valid. Use direct cache lookups; do not scan every invocation or add a
second cache. Missing identity or dependency evidence cannot mean valid.

Prove a real debug feed's initial render, pending transaction wakes, terminal
wake, repeated refresh, and second presentation reuse one stored execution.
Then change an actual source dependency and verify the existing invalidation
mechanism produces the appropriate new result. A mocked execution count with
an immutable acquired argument did not cover this failure.

## Missing viewing owner and per-value provenance — 2026-09-07

After the owned `tmp/juniper-context-live` reset, only the root agent existed.
Old Juniper debug registrations reconnected before Juniper was seeded. The
render proc repeatedly called `submit-source!` with an absent agent id. Fault
32895 records `:seon.instrument/args "[#:seon.cluster.agent{:id nil}]"` and
signature `b27a6a361b15520ca59e9745aae7f2cd7d5c72e74c723f7b0f24bbea76a9eb6f`.
Fault transactions woke the same invalid interest again: root stopped web at
1,396 identical faults, four runs, and 5,772,632 KiB of store. Counts remained
stable after stopping. The source itself was not nil; source capture admits
only strings.

`render-source-call` now retains an ordinary `owner-not-ensured` preview
refusal when its carried request has no viewing agent id, before querying
execution reuse or submitting a run. `debug-page-result` no longer substitutes
the handle's agent for an unowned viewing namespace. This checks required
request data, not an existence pre-read. The source-contract regression calls
the same unowned request twice and asserts equal refusals and no additional
submission. Root's first live reopen returned HTTP 404 for the absent namespace
and retained the four-run, 1,396-fault baseline. That proves the absent route,
not fresh browser paint: the existing browser still showed its old DOM.

The found-value regression passes a page root of 101 with a stale
`[:seon.cluster.agent/run]` cursor and offset 17. Before correction, all three
preview sources reused that exact root and cursor: incoming contribution 202,
outgoing namespace 303, and scalar title on entity 404. The focused gate
reproduced this failure in an independent confirmation worker (five tests,
97 assertions, one failure, no errors). The correction derives provenance
at `debug-found-value`: reference previews use the referenced entity and an
empty cursor; scalar previews use the datom's entity and attribute path with
offset zero. No history-name exclusion or separate cache is introduced.
This matters for execution as well as display: accidentally pulling the
viewing agent observes its temporary current-run pointer, whose removal at
settlement is a real change to the query result. The next gate passed all
render-source assertions and the namespace race regression; its sole failure
was `settlement-mints-rows-for-unindexed-call-targets`, whose expected
`seon.bootstrap/help` call edge was absent (25 tests, 242 assertions).
That failure is retained at `tmp/test-runs/run.9YhxTH`; its cause is not yet
attributed.

Root's next browser inspection of seeded Juniper 34620 found an
`owner-not-ensured` refusal on its id attribute despite valid viewing custody.
Producer arguments merge entity attributes with render custody: an entity's
agent-id attribute can make an unowned request's producer argument identical
to a later owned request's argument. The same invocation then reused the
execution refusal. Existing invocation evidence now also carries the request's
agent id and render namespace, independently of producer arguments. The
regression exercises absent then present custody with the identical entity
value and retained invocation cache. This is also required to prevent source
execution reuse across distinct viewing namespaces.

The race audit separates admission from reuse. Datahike's transaction executes
`run/open-call`, which resolves the agent and refuses an existing current run.
`run/plan-call` compares the requested system starting namespace with the
agent's current namespace inside that same transaction. Existing regressions
cover busy admission (`seon.db-test/transaction-wrappers-cannot-hide-a-classified-refusal`)
and stale namespace refusal
(`seon.cluster.run-test/system-run-refuses-a-concurrent-namespace-reassignment`).
The render proc serially reduces all watched registrations and carries the
same `::invocations` value across them and subsequent passes. There is no
second per-feed execution cache. Distinct submissions after a prior run has
closed are still distinct intents at run admission; avoiding unnecessary
preview submissions relies on retained source and read evidence.

Web previously rendered against a carried database and namespace, while
`submit-source!` derived its parse-time namespace from a later database value.
The transactional namespace fence covered changes after that later read but
missed reassignment between rendering and submission. The submission request
now optionally accepts the existing `:seon.cluster.run/starting-ns`, which web
passes from its rendering namespace. Supplied namespace decisions win; callers
omitting the field retain the ordinary agent default. The existing transaction
fence decides whether the assignment still matches, without another lock or
pre-read check. The submission regression changes assignment before the call
and checks refusal, no run, and no wake; its ordinary default call still runs.

The issue remains open. During subsequent source publications root observed
roughly 20 repeated identity runs and stopped web again at 34 total runs;
the original nil-id fault count stayed at 1,396. A quiet-source live proof is
still required after these corrections, including terminal paint and locking.

The custody/submission gate completed with 27 tests, 230 assertions, no
failures and one error in
`install-gate-failure-settles-commits-and-cancels-the-turn-backstop`; the
render-source and ordinary source submission tests passed. The retained root
is `tmp/test-runs/run.wjDMHy`; no broader gate was rerun for this separate
error.

The quiet-source browser falsifier still grew from 34 to 49 runs. All 12
stored read-evidence entries of the inspected latest floor evaluation were
current. The bounded live probe in
`docs/prds/context-generation/research/preview_reuse_predicates_2026_09_07.clj`
temporarily observed the existing reuse function and restored it after ten
seconds. Across repeated floor calls for alias entity 32367, source,
producer, program identity, and projection identity compared equal, but the
retained call's `:seon.render.call/source-run-id` was absent on every pass.
The failure precedes execution-read validation: the page loses its execution
reference. Web was stopped again after this capture. Correcting that loss is
the next acceptance step; these earlier fixes do not establish a stable UI.

`render-call` reconstructed a presentation entry from a reusable invocation
using only output, basis, and read evidence. It omitted that invocation's
execution reference. `render-source-call` then returned its already terminal
output before enrichment, and the next runtime invalidation had no per-call
run reference to retain. The correction accretes the valid invocation entry
into the valid prior call instead of selecting a subset of its fields; an
invalid prior call contributes nothing, and current invocation facts win.

The existing source-reuse regression now follows the missing order: pending
execution, runtime wake, terminal execution, cached second presentation, then
another runtime wake for that same presentation id. It asserts both the
presentation's run reference and no additional submission. The focused
`seon.render-simplification-test` plus `seon.render-source-test` gate completed
with 25 tests, 217 assertions, zero failures and zero errors
(`tmp/render-source-reference-gate.log`). Quiet-source browser acceptance is
still required before closing this issue.

## Classified transaction refusal lost its message — 2026-09-07

The next live reopen reached a `submit-source!` invalid-output fault. Its
representative diagnostic showed `{:seon.cluster.run/id nil}`. This was not
the returned value: `instrument/offending-value` reconstructs the first Malli
problem's path, and the missing run-id key in the success arm of an `:or`
appears as that synthetic fragment. Root verified the installed original
callable was the real `seon.cluster.agent$submit_source_BANG_`, not a test mock.

The bounded
`docs/prds/context-generation/research/source_submission_return_probe_2026_09_07.clj`
observed the existing instrumentation reporter before opening the existing
debug feed and restored it afterward. In 972 ms it captured the actual value:
`:seon.error/kind :seon.cluster.run/refused`, transition `open-call`, rule
`:seon.cluster.run/agent-already-running`, and a request containing the valid
string run id `source:3a7709d2-976d-45b5-bf94-458d570bf89a`. The map lacked
`:seon.error/message`. Thus run admission correctly refused contention, but
the returned map failed the required error contract before web's transient
busy handling could consume it.

The shared `seon.error.refusal/refusal` cause-chain reader now retains the
matching classified exception's message when its data omits the message.
An explicit data message still wins; classification, rule, and request remain
unchanged. The existing database contention regression asserts both that
message and conformance to `:seon.error/value`. No parallel catch or retry was
added to web or agent submission. The diagnostic's synthetic absent-key
fragment is still misleading and needs a separate correction at its owner;
it must not be described as proof that an actual function returned nil.

The focused database and shared transaction/refusal gate completed with
46 tests, 350 assertions, zero failures and zero errors
(`tmp/classified-refusal-message-gate.log`).
