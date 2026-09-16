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

### g: failed fixture construction cannot poison the JVM

Dependency ledger: Clojure `reference-code/clojure/src/jvm/clojure/lang/Delay.java`
retains its caught throwable; ordinary promises report completion without
cancelling their producer. The existing canonical constructor and cleanup
remain `test/seon/test_support.clj/create-base` and `close-base!`.

`retrying-base` now shares a daemon construction, carrying the supplied
schema projection and selecting the system classloader. Failure returns a
typed diagnostic and clears that attempt; success stays shared. Callers
retain their own bounds without interrupting construction. Both fixture
consumers check the result before destructuring it. `defonce` preserves an
already successful base on reload; verified identical before/after reload
on default, with no shared-base reconstruction or restart.

After evaluating the proposed function and exercising failure/retry in the
JVM, the real canonical regression passed all eleven assertions. The first
run reported instrumentation drift during concurrent adoption; after source
reload and explicit re-arming, the final serial run was fully green:

| Regression | Pass/fail/error | Elapsed ms |
| --- | --- | --- |
| runtime-deletion-preserves-identity-through-tuple-retractions | 4/0/0 | 9845 |
| failure-readers-use-the-structured-claims | 6/0/0 | 12304 |
| failed-base-construction-retries-without-caller-interruption | 11/0/0 | 39435 |

All ran via `seon.test/run`, daemon thread, one at a time, remaining-ms
100000, PID 45917. The g regression constructs a separate real canonical
base and closes it; its first constructor throws, the second continues
after its requesting thread is interrupted, and later requests reuse it.
Its 11 assertions include actual program rows and the SCI context.
The gate request adds the two test-support paths and namespace. The
protected operator files and runner recording-notice path remain untouched.

### Final live evidence and gate handoff

Reader slice: `5a9de3185`; h: `fec3918dd`; g: `9f0771cfc`.
The explicit background `bin/seon init --dev default --changed
src/seon/problems.clj` completed **exit 0**, publishing and adopting
`6aaa467f-f15d-5bc6-a123-c0092a6d8c3c`, digest
`c91f9025c0727997051dd95d730c38fb1057f85bbdbc14e997766caf5a46ed1c`.
Subsequent source publications can advance that head; this records the
observed successful adoption, not a claim that the moving head stopped.

After adoption the two original h tests were clean: **7/0/0, 20997 ms**
(`ns-unmap-retracts-the-owned-function-after-the-terminal-commit`) and
**2/0/0, 19959 ms** (`qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`).
Together with the three rows above, the final priority/readers proof is
**five tests, 30 assertions, zero failures/errors**. Exact counts are in
[regression evidence](reach-closure-regression-evidence-2026-09-16.edn);
[the serial probe](reach-closure-regression-probe-2026-09-16.clj) repeats
these tests through the runner's loader and the canonical harness.

The [live proof](reach-closure-live-proof-2026-09-16.clj) ran on default
PID 45917 using hot-reloaded, armed definitions. Its
[exact evidence](reach-closure-live-evidence-2026-09-16.edn) records:

- Baseline green, then one red assertion with a failure entity and a file
  **ref** to the proof file, line 21; expected `(= 1 (value))`, actual
  `(not (= 1 2))`.
- `changed-since-green` returned exactly `reach-closure.live-proof/value`
  in **3.414583 ms**.
- The final green run retracted the failure entity. Reach membership
  replaced `old-dependency` with `new-dependency`, retaining `value`.
- A subsequent live pull after the latest fixes still read **1/0/0**, no
  failures, and those exact two reach members.

All seven requested failure classes are covered in
`test/seon/test_failure_facts_test.clj`; its twelve tests also cover explicit
namespace completion, addressable result branches, closure replacement,
structured readers, and cardinality-many tuple replacement. The oversized
blob regression additionally read its committed blob through real SCI and
passed **6/0/0**. The earlier eleven-test proof was **74 assertions green**;
the new reader regression passed **6/0/0** above.

No test JVM was launched and default was never restarted. The gate request
is `tmp/orchestrator/gate-requests/reach-closure-facts.txt`; the orchestrator's
path-limited named and platform gate remains the final integration proof.
Items a–f remain closed by batch 41, and withdrawn i's operator/recording
files were not edited. Markdown lint reports existing stale gitlink citations
in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`;
those foreign audit findings were not changed by this lane.

The two retained proof scripts pass clj-kondo with **0 errors, 0 warnings**.
All lane probe threads have completed, and both explicit publication shells
have exited. Disposable lane logs, classpath file, thread dump, and HTML
preview were removed after their evidence was retained here.

### Batch 45 red: the entity-pair expectation was stale — 2026-09-16

Bounded lane `test-entity-pair`, triaging
`seon.render.entity-pairs-test/test-entity-pair-is-total-through-the-issue-walk`
(8 failing assertions at `test/seon/render/entity_pairs_test.clj:80-81` on
`8d5a7bcea`). Verdict: **(a) the expectation was stale**; the pair did not
regress.

Evidence, PID 45917, base already realized:

- The fixture test entity carries only `:seon.test/sym` and `:seon.fn/calls`
  — it genuinely has no recorded result, so `unrun or incomplete` is the
  truthful line for the `{}` case, and the `pass`/`fail`/`error` cases each
  produced their own state from the merged counts. A live
  `(seon.render.test/render-ai {:seon.render/value {…:pass-count 1…}})`
  returned `"Test entity-pairs.fixture/test: pass"` plus the evidence pull and
  the `changed-since-green` call.
- Both failing assertions were written against the pre-`5a9de3185` shape:
  `ac34ce5a3`'s `render-ai` emitted `;;`-prefixed prose ending in a period and
  exactly one form (the pull), so `": pass."` and `(= 2 (count (do …)))` held.
  `5a9de3185` moved the summary out of comment prose into
  `(clojure.core/identity text)` — the ruled grammar, rendered output carries
  no comment-prefixed prose — and added the `changed-since-green` form. A form
  count is not a behaviour; the period was an artefact of the deleted prose.

The expectation now asserts what each form does: no comment-prefixed line in
the rendered source; form 1 is `clojure.core/identity` whose first line is
`Test <sym>: <state>`; form 2 is a `seon.db/pull` keyed by
`[:seon.test/sym <sym>]`; form 3 equals
`(seon.test/changed-since-green (seon.db/db) <sym>)`; the HTML projection is
hiccup.

The regression reach-closure was asked for did not exist — the closest,
`failures-render-their-site-and-claim`, asserts substrings and covers neither
reach nor `changed-since-green`. Added
`seon.test-failure-facts-test/a-recorded-test-renders-its-sites-and-changed-dependencies`:
a green probe run, then a red one, then a recorded `:seon.test/reach` member
whose `:seon.fn/source` datom lands after the green basis. It asserts, as data,
that `changed-since-green` names exactly that function, that the AI summary's
first line is the state line and carries each recorded failure's `path:line`,
that the third AI form is exactly the `changed-since-green` call, that the set
of HTML `:data-file`/`:data-line` link attributes equals the set derived from
the recorded failure entities, and that the changed function is linked by name.

In-process proof (daemon thread, `seon.test`'s own loader, 100000 ms,
armed contracts, PID 45917):

- `seon.render.entity-pairs-test/test-entity-pair-is-total-through-the-issue-walk`
  — **40 pass / 0 fail / 0 error**
- `seon.render.entity-pairs-test/function-entity-pair-is-selected-through-the-issue-walk`
  — **17 / 0 / 0**
- `seon.test-failure-facts-test/a-recorded-test-renders-its-sites-and-changed-dependencies`
  — **11 / 0 / 0**

Verification boundary: test-only edits, proven in process against the adopted
source of `src/seon/render/test.clj`; no src file was changed, no test JVM was
launched, default was never restarted, and the batched gate
(`tmp/orchestrator/gate-requests/test-entity-pair.txt`) remains the proof.
The new regression was not run against a deliberately broken pair, so its
falsifying power is argued from its derived expectations, not observed.

### Recording and preservation are total at an absent identity — 2026-09-16

Bounded lane `recorder-absent-identity`. Read AGENTS.md, the wave-2 REPL rules,
and this note end to end. Batch 49 (`d4a201237`, `seon.fn-test` green) failed to
record with `:seon.db/rejected Nothing found for entity id [:seon.fn/sym
"seon.fn/source-files"]`; adoption on `default` then failed with the mirror
image, `[:seon.fn/sym "seon.fn/rooted-source-files"]`
([issue](../../../seon/issues/adoption-refuses-when-test-evidence-names-a-deleted-declaration.md)).

#### Attribution — neither a stale reach digest nor an indexing gap

Three hypotheses were offered; the third is the cause, and the first two are
refuted by evidence:

- **Not an indexing gap.** `seon.fn/source-files` is a private `defn-` at
  `src/seon/fn.clj:100` at that HEAD, and a live whole-file analysis on
  `default` (PID 45917) returned it with `::analyzer/private true`, row 100,
  among 105 var definitions. Private functions are indexed; `default` holds
  2 939 of them.
- **Not a stale reach digest.** The rename is real and symmetric.
  `925ca19fe` renamed `source-files` → `rooted-source-files`; `0eba4b8c3`
  renamed it back and split `containing-root` out. Both names are therefore
  correct members of the basis that recorded them.
- **The recorder resolved reach members against a database it did not write
  into.** `reach-memberships` derives `[:seon.fn/sym …]` lookup refs from the
  TESTED value, and `record-tx` emitted them unchanged into the writer's
  database. Measured on `default`: the `cluster-default` branch holds
  `{:db/id 6069 :seon.fn/sym "seon.fn/source-files"}` — two datoms, a genuine
  tombstone whose `:seon.fn/source` was retracted at `t` 536871468 and never
  re-asserted — while every neighbour (`containing-root`, `under-root?`,
  `rooted-file`, `sha-256`, `many-or-component-attributes`) carries 11–12
  attributes. The recording destination, branch `:current-src` at basis
  536870925, has **no row at all** for `seon.fn/source-files` and a LIVE row
  for `seon.fn/rooted-source-files`: it was published inside the rename
  window. Ruling 47 keeps a ref stable only where the identity was once
  minted; a database built fresh from later source has nothing to tombstone,
  so evidence crossing a publication in EITHER direction names an identity
  the destination lacks, and Datahike rejects the whole transaction on the
  first one.

#### The fix — mint the identity at the writer

`seon.cluster.source` now owns the one decision, made against the writer's own
database value inside the transaction function: `absent-program-identities`,
`identity-tombstone-rows` (the identity, its namespace, admission source and
nothing else), and `identity-ref` (a tempid for a minted row, because a lookup
ref would be resolved against the value before the mint). Both writers use it:
`seon.test.runner/record-tx` for `:seon.test/reach`, and the new
`seon.cluster.source/preserved-evidence-tx`, which the rebuild's
`[:db.fn/call …]` now invokes in place of `(fn [_] evidence)`.

A file identity is NOT minted: `:seon.fn.file/file` requires the digest of the
file the indexer walked, and inventing one would be a lie. An unresolvable
failure site therefore keeps its line and reports its path as the typed
`:seon.test.failure/reported-file` — an accretive optional key on the durable
failure entity — instead of a dangling ref. Nothing is dropped silently.

Both writers also ask the destination whether it declares
`:seon.test.failure/reported-file` before using it; a database whose schema
lacks the attribute keeps the failure and its line without it.

Regressions in `test/seon/test_failure_facts_test.clj`, on the canonical
`support/with-database` harness with armed contracts:

| Regression | Pass/fail/error |
| --- | --- |
| `recording-mints-an-absent-identity-instead-of-rejecting-the-completion` | **8 / 0 / 0** |
| `preserved-evidence-survives-a-rebuild-that-deleted-a-declaration` | **5 / 0 / 0** |

The first carries a completion whose reach names one symbol the recording
database has no row for: the counts commit, BOTH members are queryable
afterwards, the minted row is exactly `{:seon.fn/sym …}` plus its namespace
ref and admission source, and no `:seon.fn/source` is fabricated. It carries
its own reach because `commit-results!` re-derives membership from
`:seon.db/db` whenever the completion still holds the tested value. The second
drives `preserved-evidence-tx` on the writer's database and checks both the
minted reach member and the unresolvable site, which keeps line 7 and reports
its path.

A recorder-side unresolvable FILE site is not constructible inside one
fixture: `prepare-failures!` derives the site from the tested test row's own
`:seon.fn/file`, so an absent file ref only arises across databases. That case
is asserted in the preservation regression, not in the recorder one.

#### Live proof and verification boundary

`bin/seon init --dev default` **exits 0** on PID 45917, publishing and adopting
`:current-src` commit `6aaa5523-5055-5df1-bcd7-d944ce8a43fc`, digest
`beda48b738dae0028f17d3a02b680b5bbd9cfc580a10060272b7e8114febf5db`, past the
`initialization rows` step that previously died. The first attempt of the same
command, before the fix was loaded, exited 1 at that exact step with
`Nothing found for entity id [:seon.fn/sym "seon.fn/rooted-source-files"]`.
Because adoption itself was the broken seam, the fix had to be hot-reloaded
into the running JVM (`require :reload` of `seon.cluster.source` and
`seon.test.runner`) before the publication that adopts it could run.

Boundary: both regressions ran in process on a daemon thread, one at a time,
through `seon.test`'s own loader against the canonical harness; no test JVM
was launched and default was never restarted. The batched path-limited gate
(`tmp/orchestrator/gate-requests/recorder-absent-identity.txt`, platform tier
WITH recording) remains the proof.
