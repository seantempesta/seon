---
type: research
status: active
created: 2026-09-20
tags: [testing, selection, provenance]
---

# Results reuse everywhere

## Scope and grounding

Priority lane on `steward-platform`; entry HEAD `c76a161a3`. Read AGENTS.md
sections 0–5 and the test-system PRD, stage 1–3 design, Stage 1 review and
A1 landing note end to end. Read namespace-agents plan sections 7–8,
the `results-reuse-everywhere` working-edge block and error-conversion PRD
section 1.2. Applied data-oriented-clojure, clojure-testing, repl and datahike.

Preserved the inherited `bin/test` inventory/cache changes and all held
render, error, schema and Malli edits. No delegation, worktree, cold gate,
publication or cluster lifecycle command. Fast JVMs run serially.

Dependency ledger:

- Datahike `reference-code/datahike/src/datahike/db.cljc:142–152`: as-of
  includes its basis; since excludes it. Historical tested values supply
  the old reachable content without rewriting the original provenance.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1153`: the
  transaction function receives the writer's current database.
- `src/seon/test/runner.clj:2147`: `reach-entry` hashes the test's reachable
  source/contracts/schema content through the existing identity owner.
- `src/seon/test/runner.clj:2238`: `program-digest` identifies the whole
  snapshot, not one test's dependency closure.
- `src/seon/test.clj`, `select` and `admit-run`: the existing selection and
  reservation owners. No parallel selector is introduced.

The initial question about whole-program equality versus one changed member
is answered by an earlier explicit owner ruling in
[the provenance issue](../../../seon/issues/recomputed-program-digest-disagrees-with-the-stored-run-digest.md):
success uses the per-test reach digest; the whole-program digest is provenance.
The candidate retains each reused member's original tested basis, program
digest and input digest. It does not claim that old execution occurred on a
new whole-program digest.

## Selector candidate; launcher integration is not complete

Named selection retains its requested namespace/identity scope. Matching
green evidence from a different program can discharge a member only when
the current and original tested reach digests agree and external inputs
match. Reach reads are grouped by original tested basis. The existing
`reach-digests` boundary now declares and returns the exact unknown facet.

`named-selection-reuses-green-members-by-reachable-content` uses the
canonical database fixture and analyzed source rows. It positively selects
two named members, establishes terminal green selection evidence, checks
zero executable members with all three confidence values, then changes
one callee and expects exactly its test to remain executable. This is a
selector regression, not a claim that test bodies or the CLI recorder ran.

No step is claimed fully delivered: Step 1 still needs launcher wiring;
Steps 2–5 and the two-run fast proof remain owed. Bash lines deleted: **0**.

## Verification

MCP runtime status answered for default PID 41822. One read-only JVM probe
attempted `program-digest` before/after an immutable `datahike.api/with`
change. MCP returned `failure: timeout` at **20,000 ms**. No digest values
were returned, no database transaction was submitted, and no cause or
post-edit live adoption is claimed. The exact form is recorded in
[the probe issue](../../../seon/issues/test-provenance-probe-exceeds-mcp-bound.md).

Baseline command:

```sh
bin/test-fast --paths src/seon/test.clj src/seon/test/runner.clj src/seon/test/selection.clj -- seon.test.selection-test
```

At `c76a161a3`: **8 tests, 56 assertions, 2 failures, 1 error**, exit 1.
Log: `tmp/results-reuse-everywhere/baseline.log`. The selector fails before
reuse assertions on the noncanonical compiled
`seon.error/config-expectation-present?` schema; admission then receives
the refused provenance. This reproduces the boundary already recorded in
[the projection-acquisition issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
The one fixture thread sample is
`tmp/results-reuse-everywhere/baseline-threads.json`, captured with
`jcmd 78452 Thread.dump_to_file -format=json`; it shows the main thread
awaiting the canonical base and the base thread in Malli validation.
That sample does not establish a root cause.

Candidate command:

```sh
bin/test-fast --paths src/seon/test.clj src/seon/test/runner.clj test/seon/test/selection_test.clj -- seon.test.selection-test seon.test-test seon.test.runner-test seon.test-cache-test
```

Snapshot HEAD: `721b110b8`. Log:
`tmp/results-reuse-everywhere/step1-fast.log`: **45 tests, 354 assertions,
8 failures, 16 errors**, exit 1. My new fixture omitted `:seon.db/db`
when calling `complete-selection!`; that error is corrected. The result
also records the existing provenance, error-facet propagation and SCI
acquisition boundaries. It is not a green proof of the candidate.

After that correction, a second candidate runs the same three paths with
only `seon.test.selection-test`; log `step1-fast-2.log`: **9 tests,
58 assertions, 3 failures, 2 errors**, exit 1. The new fixture request
now passes its input contract; both selector regressions stop at the
noncanonical provenance refusal before their reuse assertions. The new
behavior remains unverified, rather than falsely reported green.

The required namespace load command completed with `:loads`, exit 0:

```sh
clojure -M -e "(require 'seon.test 'seon.test.runner 'seon.test.selection 'seon.test.cache) (println :loads) (shutdown-agents)"
```

Exact retained log sizes and SHA-256 values:

| Log under `tmp/results-reuse-everywhere/` | Bytes | SHA-256 |
|---|---:|---|
| `baseline.log` | 20,013,366 | `0e0b4c81b0408e37c83dfb11554b6e8503289f573e5178bafcda628368ee6648` |
| `step1-fast.log` | 214,384 | `7d67f8df9370461b872673031728033f255e90b80bd85fcd2d01d2d8cbbdc0a5` |
| `step1-fast-2.log` | 100,814 | `a84b81032a5396f1b011aae127459485b279a6c9cbeae16457be993defbbbcee` |
| `step1-load.log` | 205 | `579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18` |

Hook lint admitted the Clojure edits with warnings. Repository Markdown lint
reports existing dependency-pin citations in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md` and
other findings outside this slice; no repository-wide green is claimed.

## Snapshot authority gate

The next change crosses the source-publication owner, outside this lane's
assigned paths. This is the AGENTS.md §2.5 design gate, not a stop at a
foreign test failure.

Verified source boundaries:

- `admit-run` explicitly accepts only its own cluster branch; it refuses
  `tested-branch` and a different program digest.
- The cold coordinator's named path takes loaded Vars directly at
  `src/seon/test/runner.clj:4383`; it does not use this admission.
- `commit-persistent-results!` forwards legacy completion fields to
  `seon.cluster.source/record-results!`; it does not forward admitted
  membership or selection evidence.
- `src/seon/cluster/source.clj:436` returns `[]` immediately for an empty
  completion. A zero-execution request therefore cannot record its run
  through that owner as currently written.
- Source evidence preservation at `src/seon/cluster/source.clj:326` copies
  wildcard run maps and explicitly rewrites legacy test/failure refs. It
  does not supply the snapshot admission/cluster/member remapping contract.
- `bin/test:919–927` exits through the fast entry before publication;
  the latest published graph used for overlay admission was **62 commits
  behind** the candidate snapshot. It cannot honestly supply that snapshot's
  tested program identity.

Three options presented to the owner; estimates are not measurements:

1. **Recommended: extend the source-publication owner with immutable
   snapshot admission and complete run/member recording.** Guarantee:
   results retain the program actually tested and both launchers use the
   same recording authority, including zero-execution runs. Cost: roughly
   1–2 lane-days, including `src/seon/cluster/source.clj` and its tests
   outside the assigned paths. Give up: keeping this cut limited to test
   owners; the holding JVM must adopt the converged recording contract.
2. **Require an explicit test cluster for both launchers.** Guarantee:
   the current cluster admission owns selection and recording. Cost:
   roughly 1–2 lane-days plus CLI and snapshot preparation changes. Give up:
   the published-branch default requested by this assignment.
3. **Record fast results only for an already-published matching snapshot.**
   Guarantee: exact existing snapshot provenance; refuse unknown overlays.
   Cost: roughly half a lane-day. Give up: arbitrary unpublished `--paths`
   reuse and the required live proof, so this narrows the assignment.

The orchestrator subsequently ruled option 1 and extended ownership to the
admission/recording region of `src/seon/cluster/source.clj` and
`resources/seon/schemas/seon.source.edn`. That authority decision is settled.

Files changed by this slice:

- `src/seon/test.clj`
- `src/seon/test/runner.clj`
- `test/seon/test/selection_test.clj`
- `AGENTS.md` (section 5 only)
- this landing note
- `docs/seon/issues/test-provenance-probe-exceeds-mcp-bound.md`
- `docs/seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md`
- `docs/seon/issues/fast-overlay-admission-prints-the-complete-program-manifest.md`

All three fast invocations exited and their launchers removed their own
snapshot roots. No foreign root was swept. Logs and the one owned thread
sample remain as evidence.

## Cold proof owed

The orchestrator must run the selected cold gate over the final owned file
set, `bin/test --platform`, then bare `bin/test` twice; the second must
execute zero and report recorded confidence. The current candidate command
for the selector slice is:

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj test/seon/test/selection_test.clj -- seon.test.selection-test seon.test-test seon.test.runner-test seon.test-cache-test
```

The required two consecutive fast reuse logs do not exist yet; baseline
and candidate logs are different snapshots and are not that proof.

## Resume: recording authority settled; identity decision

Resumed at `4b3c4b5f3`. The extended source owner and source schema were
clean. Preserved the inherited `bin/test` diff (13 insertions, 4 deletions)
and all foreign edits. Also read the data-modeling skill. No worktree,
cold gate, publication, default mutation or second concurrent JVM.

The new ruling names the run identity as `(published base digest, overlay
input digest, program digest, basis)`. Before writing that tuple into the
unique `:seon.test.run/id`, the existing immutable-admission contract needs
one distinction resolved:

- `resources/seon/schemas/seon.test.run.edn:4` declares `/id` a unique identity.
- `src/seon/test.clj:1148` reads the existing row by that identity.
- `src/seon/test.clj:1200` compares the existing run with the entire new
  admission, including namespace/identity scope, timestamp and members;
  a changed replay refuses with `:seon.test.run/immutable` at line 1205.
- `src/seon/test/runner.clj`, `complete-members`, similarly refuses changed
  terminal outcomes. The canonical regression at
  `test/seon/test/runner_test.clj:339` expects that refusal.

Two requests for different namespaces can share all four snapshot values.
They cannot share one immutable run row with different scopes and members.
A retry after a red result also needs a distinct execution observation if
its terminal outcome changes. This is a source-contract finding; the
fixture refusal below prevents a new live admission proof.

Three options, with estimates rather than measured timings:

1. **Recommended: the four values identify the tested snapshot; each
   admission retains a fresh run event ID.** Guarantee: exact snapshot
   provenance, immutable runs, arbitrary scopes and red retries; green
   reuse remains a query over earlier runs. Cost: an additive snapshot
   provenance declaration within the authorized recording work, with no
   redesign of admission or completion. Give up: using the four-value
   tuple alone as the unique run ID.
2. **Add request scope to the deterministic run identity.** Guarantee:
   different named requests do not collide; identical requests address
   the same immutable run. Cost: revise identity derivation and replay
   admission (including timestamp handling), roughly half a lane-day
   beyond recording. Give up: unchanged red requests cannot produce a new
   terminal observation without an additional attempt identity.
3. **One mutable run per snapshot, with growing membership and separate
   execution attempts.** Guarantee: the four-value tuple identifies one
   aggregate containing every request and retry. Cost: replace immutable
   admission/completion, claim ownership, and tally contracts; several
   lane-days. Give up: the existing immutable run invariant.

The question is pending before production admission/recording edits. A
small base-descriptor handoff was explored and then removed using only
this lane's hunks; no partial launcher behavior is left behind. This
resume changes only documentation. Bash lines deleted: **0**. Steps 2–5
and both zero-execution fast logs remain owed.

### Resume verification

```sh
bin/test-fast --paths src/seon/test.clj src/seon/test/runner.clj test/seon/test/selection_test.clj -- seon.test.selection-test
```

Snapshot `4b3c4b5f3`, no source differences from HEAD. Published overlay
graph `c7c66f815606ca3f11a53ab24f6067df22c9449f4e8eef83d6a3b20fcef150c3`,
**71 commits behind**. Result: **9 tests, 58 assertions, 3 failures,
2 errors**, exit 1. `resume-baseline.log`: **20,062,155 bytes**, SHA-256
`16483a23d03b4973ec722a295941413eddb00c875ca858b1fe5ccc09cd9de2ef`.

The exact blocking assertion is
`named-selection-reuses-green-members-by-reachable-content`,
`test/seon/test/selection_test.clj:89`: its initial member set receives
`program-digest`'s unavailable result. The cause is
`:seon.schema.shape/noncanonical-compiled-form`, with compiled
`seon.error/config-expectation-present?` carrying a `:gen/gen` Generator
object. The pre-existing selector regression similarly fails at lines
141–142. Subsequent admission receives the refused provenance and fails
the canonical fixture writer at `test_support.clj:331`. No zero-execution
assertion is reached. This is the HEAD boundary identified by the
orchestrator, not foreign working-tree contamination, and not the reason
for the identity design gate.

One sample of the running JVM, PID 87966, is retained in
`tmp/results-reuse-everywhere/resume-baseline-threads.json`: the main
thread awaits canonical fixture acquisition; the fixture thread awaits a
database transaction. This does not attribute the transaction's cost.
The JVM exited and its launcher removed its snapshot.

The same four-namespace `clojure -M -e` load command recorded above then
completed with `:loads`, exit 0, in `resume-precommit-load.log`. Repository
Markdown lint still reports the existing dependency-pin citation findings;
no repository-wide lint success is claimed.

## Identity ruling and urgent overlay admission

The orchestrator settled the identity question: runs are fresh events. The
four-value tuple is snapshot provenance on each run, not its unique event
ID. Immutable admission and retries remain unchanged. The source recording
authority and identity gates are both closed.

The next separately committed slice changes omitted dirty callers from an
overlay refusal into an observation: each path is printed with the fact
that its HEAD bytes are in the tested snapshot. Admission writes the
observation into the snapshot's transient handoff, and both runtime entries
carry `:seon.test.run/callers-at-head`. The declared set is retained by
admission and both existing result-recording paths. Pulls disable the
cardinality-many default limit and normalize the collection for immutable
replay comparison. The checkout's dirty caller contents are never copied
or read by overlay admission.

`omitted-dirty-callers-use-head-and-carry-recordable-provenance` builds a
real Git fixture and an analyzed two-file manifest. It changes the leaf in
the selected snapshot, leaves the caller at HEAD, and makes the checkout
caller unreadable Clojure. It checks admission, the printed path, exact
HEAD bytes, unchanged dirty checkout bytes, and recorded run provenance
through `commit-results!` in the canonical database fixture. No test
result or execution count is fabricated: the recording probe has zero
test results. The candidate also checks replay of the same provenance.

First fast command:

```sh
bin/test-fast --paths src/seon/test.clj src/seon/test/runner.clj src/seon/test/selection.clj src/seon/test/fast.clj resources/seon/schemas/seon.test.run.edn test/seon/test/selection_test.clj -- seon.test.selection-test
```

`callers-head-fast.log`: **10 tests, 65 assertions, 3 failures, 2 errors**,
exit 1. All seven assertions of the new overlay regression passed. The
known HEAD noncanonical-schema refusal remains at the initial selection
assertion (now `selection_test.clj:91`) and the pre-existing selector at
lines 143–144, followed by their fixture writer errors. Those failures do
not involve the new overlay regression. A sample from the one running JVM
is retained as `callers-head-threads.json`; it shows canonical fixture
acquisition, not a diagnosis of its cost.

This slice touches `src/seon/test/selection.clj`, `src/seon/test/runner.clj`,
`src/seon/test/fast.clj`, `src/seon/test.clj`,
`resources/seon/schemas/seon.test.run.edn`,
`test/seon/test/selection_test.clj`, AGENTS section 5 and this note.
`src/seon/test/fast.clj` is the existing runtime entry needed to carry the
new field. The inherited `bin/test` diff remains unchanged and uncommitted.
Bash lines deleted: **0**. Fast durable recording itself and the two-run
zero-execution proof remain the next slice, not a claim of this commit.

The final overlay candidate was then run with the same six paths and all
four assigned namespaces: `seon.test.selection-test seon.test-test
seon.test.runner-test seon.test-cache-test`. `callers-head-fast-2.log`:
**46 tests, 368 assertions, 7 failures, 7 errors**, exit 1. The new
regression passed all eight assertions, including immutable replay. Other
failures include the HEAD provenance refusal, error-facet propagation in
`recording-distinguishes-run-replay-from-a-new-event` at
`test/seon/test_test.clj:270,277–281`, and SCI acquisition/instrumentation
refusals. The full log is the boundary evidence; no aggregate green is
claimed and no foreign source was edited.

| Log under `tmp/results-reuse-everywhere/` | Bytes | SHA-256 |
|---|---:|---|
| `callers-head-fast.log` | 101,226 | `c7010c1cada42bacaab22727e79a171f474df83b41a33f22d243c399bad74be9` |
| `callers-head-fast-2.log` | 239,098 | `e073c069c42d2a7a1d989c045fe4df145d4f599462d40abb27bebd3d07d3e46b` |

Both fast JVMs exited. The cold gate owed for this slice is the first
command above with `bin/test-fast` replaced by `bin/test`, plus the same
four-namespace set and the orchestrator's platform proof. The later bare
twice and fast twice zero-execution obligations remain unchanged.

Before committing, `clojure -M -e` requiring `seon.test`,
`seon.test.runner`, `seon.test.selection` and `seon.test.fast` completed
with `:loads`, exit 0 (`callers-head-precommit-load.log`).

The urgent overlay slice landed as `db24035ee`. Its post-commit HEAD load
also returned `:loads`, exit 0 (`callers-head-head-load.log`, 205 bytes).

## Step 2 runtime checkpoint and recording-authority prerequisite

The identity ruling is implemented for snapshot admissions: each request
supplies a fresh event ID, while the published-base digest, overlay-input
digest, program digest and tested basis are ordinary run facts. Snapshot
selection queries matching run/member facts and positive, terminated green
evidence as a set. Reused results retain the confidence values and original
execution event. Completed red members do not reserve a later snapshot
request forever; outstanding reservations remain exclusive. Immutable
same-event replay and the source owner's bounded publication retry remain.

`source/record-results!` now admits snapshot membership and records its
completion through the same source branch owner. Empty completions no
longer bypass recording. It refuses concurrent, unfinished reserved work
instead of executing a duplicate. `runner/record-snapshot!` uses the cold
recorder's existing staged-file/store-holder crossing; there is no second
store or recording authority. `cache/newest-base` exposes the resolved base
descriptor without changing declared input roots.

The canonical fixture regression
`snapshot-provenance-reuses-green-members-across-fresh-run-events` executed
the real passing fixture Var once, recorded its captured result, and then
reused it under five fresh request events without another body execution.
The four provenance values were retained. This passed in
`snapshot-selection-fast.log`. The subsequent candidate adds changed-overlay
invalidation assertions; those added assertions have not run yet.

That wider selector probe exited **124**, with no final tally: its liveness
bound fired during `selection-derives-bases-obligations-and-exact-symbol-reach`.
Before that bound it reported one stale expectation at
`selection_test.clj:197` (green platform members were expected to rerun),
and one error in the assertion headed **“A refused declared-reference read
refuses selection.”** The exact error is:

```text
seon.fn/declared-reference-edges returned a base error without a complete declared facet. Declared facets: #{}.
```

The stale platform expectation is corrected. The complete-population
regression now declares its long work and a 900,000 ms allowance; the
watchdog remains enabled. Its measured selection calls for 1, 10 and 100
seeds were 51,830.407, 56,705.330125 and 89,307.701875 ms. No cause for
that cost is inferred. The declared-reference contract is outside this
lane and remains a verification boundary. The earlier noncanonical-schema
assertion did not fail in this probe.

The mandatory fast-launcher candidate was probed serially with:

```sh
bin/test-fast --paths bin/test bin/test-fast src/seon/test.clj src/seon/test/runner.clj src/seon/test/selection.clj src/seon/test/fast.clj src/seon/test/cache.clj src/seon/cluster/source.clj resources/seon/schemas/seon.source.edn resources/seon/schemas/seon.test.run.edn resources/seon/schemas/seon.test.selection.edn test/seon/test/selection_test.clj -- seon.test.selection-test
```

It resolves the base once, hashes the snapshot's actual program and declared
inputs, and requests admission before executing any member. The first
probe exposed an exception with no message; its reporting is corrected.
The next probes exposed the recording store's old projection, which still
requires `:seon.error/kind`. The final probe returns a direct refusal from
the source owner instead of querying undeclared snapshot attributes:

```text
The published recording authority predates snapshot result admission. The orchestrator must publish the converged schema before fast recording can be enabled.
```

The recorded authority is `:current-src`, source commit
`6aaec9d7-55c6-59ac-905b-f46f93125704`. Its projection lacks all four
required declarations: `:seon.test.run/published-base-digest`,
`:seon.test.run/overlay-input-digest`, `:seon.test.run/callers-at-head`, and
`:seon.source/test-selection-request`. The final probe exited **1 before
test execution**. This is not an unchanged tally or a successful reuse
proof. The lane did not publish, reset, adopt or operate `default`.

| Log under `tmp/results-reuse-everywhere/` | Bytes | SHA-256 |
|---|---:|---|
| `snapshot-selection-fast.log` | 102,764 | `10e57da174ae6569577b6cefedc275a666b81ab0f4e64777c466042ccb22cfad` |
| `fast-recording-live-1.log` | 15,632 | `54db5a2830b9bc6c9318ba809b01e8c3ed8bb6c8e36a3ef18399a2d334ac217e` |
| `fast-recording-live-2.log` | 28,899 | `87775d2eef5fbd91a386cf7f4d52166950784d8d3fc7dfd1f287b46faf4e4055` |
| `fast-recording-live-3.log` | 55,461 | `89f4eb8c3170c66cd595ad0a9d40d231f92c696b6565012f38bba28dc5dea686` |
| `fast-recording-authority-boundary.log` | 5,909 | `caef8f9308a797151a91bbed0a80ada9cf43230ac4f1b809018df417fc1347e0` |

The runtime checkpoint touches `src/seon/test.clj`,
`src/seon/test/runner.clj`, `src/seon/test/cache.clj`, the admission/recording
region of `src/seon/cluster/source.clj`, `resources/seon/schemas/seon.source.edn`,
`resources/seon/schemas/seon.test.run.edn`,
`resources/seon/schemas/seon.test.selection.edn`,
`test/seon/test/selection_test.clj`, and this note. The mandatory launcher
candidate remains uncommitted in `bin/test`, `bin/test-fast` and
`src/seon/test/fast.clj`; the inherited `bin/test` inventory edits remain
preserved there. This prevents the checkpoint from making every other
lane's HEAD-based fast loop depend on a schema publication it cannot do.
Bash lines deleted in landed slices: **0**. Neither launcher is thin yet.

The source/identity decisions remain settled. The remaining gate is rollout
of the schema at the existing authority. Three concrete choices:

1. **Recommended: orchestrator refreshes that recording authority from a
   compatible committed program, then the lane enables fast recording.**
   Guarantee: one authority and honest snapshot evidence; no lane changes
   the owner's window. Cost: one coordinated publication and the remaining
   fast probes. Give up: enabling mandatory recording before publication.
2. **Bundle this with the orchestrator's next schema reset and reseed.**
   Guarantee: a fresh compatible authority under the same ruled model.
   Cost: the planned reset plus reseed; prior disposable store data is lost.
   Give up: immediate rollout and reuse of pre-reset evidence.
3. **Retain the runtime checkpoint and defer the launcher switch.**
   Guarantee: other lanes keep their existing iteration path. Cost: no
   publication now, but unchanged fast work continues to execute.
   Give up: the requested fast reuse proof and completion in this window.

Step 2 is not complete; steps 3–5 remain pending in their requested order.
The two consecutive successful fast runs, with the second executing zero,
are still owed. The orchestrator also owes the selected cold gate using the
complete command above with `bin/test` in place of `bin/test-fast`, then
bare `bin/test` twice (second executes zero), and its isolated platform
proof. A schema refusal before execution proves none of those outcomes.

Pre-commit loading of `seon.test`, `seon.test.runner`, `seon.test.cache`,
`seon.test.selection`, `seon.test.fast` and `seon.cluster.source` returned
`:loads`, exit 0 (`step2-precommit-load.log`). Static lint of the changed
Clojure owners reports no error-level findings. These checks do not replace
the unavailable authority-backed fast proof.
