---
type: research
status: active
tags: [test, database, schema, render]
---

# Reach closure and structured test failures — 2026-09-16

Bounded lane `reach-closure-facts`, implementing the owner's A/A/A decisions in
[test-failure-facts-2026-09-16.md](test-failure-facts-2026-09-16.md), read end to
end. Bases `f2d537187` and `3402913f3` are ancestors of the inherited HEAD.
No test JVM is launched and this lane never restarts default.

## Dependency ledger

- `src/seon/test/runner.clj`: the incremental reach index owns both membership
  and digest; the completion carries portable lookup refs across stores.
- `reference-code/datahike/src/datahike/db/transaction.cljc`: `:db.fn/call`
  supplies the writer's database. Latest result replacement belongs there.
- `reference-code/datahike/src/datahike/db.cljc`: history/as-of/since supply
  temporal evidence; since excludes its basis.
- `src/seon/cluster/source.clj`: publication transports result facts through
  a private branch and preserves them on full builds. New result attributes
  must survive that existing seam too.
- `src/seon/blob.clj` stage!/commit-staged!, and `src/seon/turn.clj`
  stage-reply!: staged payloads are committed outside the transaction function.
- `clojure.test` reporter events carry assertion claims and source position;
  the canonical `seon.test-support/with-database` fixture owns test databases.

## Slice 1

`:seon.test/reach` is the latest tested function closure, many refs, replaced
atomically with every result. `:seon.test/reach-digest` remains the equality
key. Both derive from the same incremental index of the tested database;
cross-JVM completions carry lookup refs rather than foreign entity numbers.
Full publication preserves the membership. Regression:
`seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced`.

Verification pending in-process adoption. Initial default: PID 53378,
basis 536871483, branch `cluster-default`. MCP answered; the new reach
attribute was absent. First hook publication met a concurrent head change
(`:stale-branch-head`); a subsequent publication is queued. This is not a
test verdict. Foreign issue-lane edits remain untouched.

## Slice 2

`seon.test/changed-since-green` derives run transitions from result-row
history and counts in force at each transition. Repeated green runs need
not reassert unchanged zero counts. Source and spec changes include
retractions and are intersected with recorded function refs. Missing test,
closure, or green history returns a typed unknown. The test pair offers the
query in AI source and links the named functions in HTML.

Publication exposed a prerequisite defect: `exact-replacement-tx` supplied
an entire cardinality-many tuple set to Datahike's tuple validator before
`retractAttribute` dispatch. It now supplies one existing value; the operation
still retracts the complete attribute. Regression covers a three-tuple set
replaced by one tuple on the canonical database. This extends owned files to
`src/seon/program.cljc`; no protected file was edited.

## Slice 3

The result writer records its destination branch; `:seon.test.run/tested-branch`
retains a different execution branch. `basis-t` still identifies the tested
value. The source publisher explicitly hands the durable `:current-src`
destination while it commits on a disposable branch. Direct cluster writes
derive their destination from the writer's database. The schema states this
rule, and retries compare normalized immutable provenance.

## Priority correction — named completion recording

The gate session reported `run.HwsG9I` rejected solely because membership was
unavailable. That root was already absent when this lane tried to read it;
the reported log bytes cannot be independently quoted here. The recorder's
all-or-nothing refusal was wrong. A completion now commits its available
digest and a declared `:seon.test/reach-unknown` diagnostic, clearing previous
membership. A later known closure clears that marker. The canonical completion
path derives both digests and memberships from its tested fixture database in
every selection mode; an unavailable or mismatching fixture cannot replace
the completion's original digest with today's graph.

Regression: `explicit-namespace-completion-commits-with-membership-unknown`.
Default was down during the orchestrator's reset; no in-process test was
started after the reset warning.

On the new default PID 27828 the direct named-namespace completion committed
in 502 ms, preserving digest
`0d054f795a82eda25f40fd8e3055f922ca67d0f86feb401d343155d8b1ae376f`
and the membership-unknown diagnostic. The recorded outcome remains an error,
not a fabricated green: fixture acquisition hit the 100000 ms test bound.
The fixture boundary is recorded in
[canonical-fixture-roster-permit-remains-held.md](../../../seon/issues/canonical-fixture-roster-permit-remains-held.md).

The priority correction lands with the assertion capture/writer work already
in progress; slice 4's render/read conversions and full regressions follow.
Capture keeps exact claims, normalized site/ordinal identity, staged blobs,
and total component replacement. Ordered contexts are ordinal/text tuples:
Datahike cardinality-many cannot preserve vector ordering by itself.

## Batch 34 corrections

Read the gate report `tmp/orchestrator/gate-results/batch-34/named.md` and
the interrupted-fixture issue end to end. Default PID 37572 answers MCP;
the source adoption comparison remains a required precondition for testing.

- Expiry bounds the observation without interrupting daemon fixture work.
  Both `run` and `check` leave resource acquisition/cleanup uninterrupted.
  `seon.test-expiry-test` holds the canonical fixture store's roster permit,
  expires a nested test, then verifies its completion and a later fixture.
  This removes this caller's interrupt leak; it does not claim to repair
  Datahike's interrupt handling for other callers.
- Rebuilding must preserve the destination branch, tested branch, membership
  diagnostic, and failure components. The prior run equality expected the
  publication branch despite the schema's explicit destination rule. The
  regression now compares portable component facts across rebuilding.
- Failure upserts resolve existing component identities in the transaction
  writer, including surviving components whose parent was retracted.
- Raw captured reports and durable result projections are distinct stages.
  The public result equals the committed selector, including `reach-unknown`
  and nested failure facts. Gate report elisions are presentation evidence,
  not evidence that elision objects were committed.
- Source recording retries each stale head from the fresh publication within
  the declared test allowance. Three successive real competing publications
  replace the regression's former expectation of a second-conflict refusal.

Verification is pending below; no green result is inferred from these edits.

### In-process verification, PID 37572

Adoption and publication both named
`6aaa3a4f-2026-53b5-91f3-64eb5ab33f71` before the daemon-thread runs.
`latest-test-evidence-survives-rebuilding-from-an-older-base` passed
**24 assertions, 0 failures, 0 errors**. Its real competing publications
produced four expected stale-head observations across its two cases, and
both recordings committed. This also verifies preserved membership unknowns,
portable failure components, and normalized run provenance.

The expiry regression returned **0 pass / 0 fail / 1 error** before its
assertions: the shared canonical base had already cached
`:seon.sci.eval/namespace-unloadable` for
`seon.dev.dependency-cache-test`, caused by absent
`clojure.tools.build.api`. This is the established boundary in
[in-process-test-runs-poison-the-shared-fixture-base.md](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md),
not a new permit leak. The owning fix subsequently landed as `653d4d4ef`.
No foreign source or test file was edited by this lane.

Priority commits: `d2a0ad636` (expiry), `3c6a6bb8f` (failure identities),
`800fa67af` (committed comparisons), `02ef8370a` (rebuild evidence),
`e2eb91fcd` (repeated contention), `e8a017620` (allowance from published
schema facts). The gate request excludes the unfinished working-tree readers.

### Reader slice and publication repair, 2026-09-16

Read the assignment addenda end to end. Item i is withdrawn; a–f are closed
by the owner's batch 41 at cfac8275c (platform and all five named namespaces
green). The structured failure readers now cover problems, test/check,
accretion, and the test render pair. The new problems regression supplies
its declared two-argument API's empty request map; the one-argument call
was the whole-tree publication refusal. No API arity change is needed.

The failure-facts regressions previously passed 74 assertions across eleven
serial in-process tests with armed contracts. The additional reader assertion
and blob SCI read require the next gate. Publication verification follows
this commit. Items h (verify deletion tuple failure) and g (retry failed shared
fixture construction outside caller bounds) remain. Protected gate-session
files and recording-notice code are excluded.

### h: deletion bypassed tuple-aware replacement

The hypothesis blaming `program/exact-replacement-tx` is refuted. The live
`seon.turn/row-tx` emitted `[:db.fn/retractAttribute eid :seon.fn/form-span]`
and the same three-slot operation for `:seon.fn/call-arities`. Datahike's
`db/transaction.cljc:1279` checks tuples before operation dispatch; its
`:1033` rejected both with the gate's exact “expecting 2 values, got 0”.
Deletion did not call the tuple-aware replacement function at all.

Deletion now calls that existing owner with an identity-only desired row,
preserving the namespace ref. The added canonical `seon.program-test`
regression runs deletion inside the writer, as terminal settlement does,
and checks both tuple shapes and the exact surviving identity.

PID 45917, reloaded test namespaces through the runner loader, daemon thread,
100000 ms per test: the two named cluster tests reproduced 3/4/0 and 1/1/0.
The live proposed function made the first 7/0/0 (48070 ms). With the file
changed, both assertion bodies passed (7 and 2); concurrent development
adoption changed instrumentation during those runs, so their final results
were 7/0/1 and 2/0/1, explicitly reporting worker-global instrumentation
drift. This is a verification boundary, not a green gate. The small new
regression initially transacted the generated rows outside the writer and
was corrected to use `:db.fn/call`; its final rerun is pending.

The reader repair passed whole-tree analysis and branch publication twice.
The explicit publication command exited 1 because source changed during
adoption, not because of arity analysis. A background retry is queued behind
other publications; no foreign session was operated. The gate request adds
`src/seon/turn.clj`, `test/seon/program_test.clj`, `seon.program-test`, and
`seon.cluster.turn-test`.
