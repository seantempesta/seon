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

## Refreshed recording authority

The orchestrator reports the reset and fresh overlay base
`7d2fac621d415dddecf5ba753db17519d4b451df929367ab2c2a4067ec108908`.
One read-only MCP JVM probe independently verified published source commit
`6aaf302b-5fc8-5dd4-8003-a1591809d993`: all four declarations listed above
are present, with an empty missing vector. The returned evaluation took
13,640 ms. No runtime definitions or default-cluster state were changed.
This closes the old missing-declarations gate; it does not yet prove recording.

Read-only JVM probe (one form, no reload or transaction):

```clojure
(let [held (some :seon.store/store
                 (vals @(var-get (ns-resolve 'seon.cluster 'running-instances))))
      published (seon.cluster.source/current held)
      database (seon.cluster.source/database held (:seon.source/commit-id published))
      projection (seon.schema/projection-from-database database)
      required [:seon.test.run/published-base-digest
                :seon.test.run/overlay-input-digest
                :seon.test.run/callers-at-head
                :seon.source/test-selection-request]]
  {:seon.source/commit-id (:seon.source/commit-id published)
   :seon.test/present (filterv #(get-in projection [:seon.schema.projection/forms %]) required)
   :seon.test/missing (filterv #(not (get-in projection [:seon.schema.projection/forms %])) required)})
```

The manifest-output fix returns nil after `cache/head-manifest`. A native
Babashka probe against that fresh base exited 0 with **0 stdout bytes and
0 stderr bytes** (`manifest-check-output.log`, `manifest-check-error.log`).
It read the real ready record's source inputs and manifest, without a cold gate.

Fast recording probe:

```sh
bin/test-fast --paths bin/test bin/test-fast src/seon/test/fast.clj -- seon.test.selection-test
```

Snapshot HEAD `3f31ec22093779b56197fbc6e342fb8e34ebc559`; slot wait
793 seconds; one test JVM, PID 36548. Admission and completion succeeded
against the refreshed source authority, recording run `eb10af2747d0`:
**11 executed, 0 unchanged; 139 assertions, 4 failures, 1 error**, exit 1.
`refreshed-authority-fast.log` is **22,249 bytes**, SHA-256
`13e1241d1c6cdb2ce49bf628a722ba42b8221fbe67d272c9686337b19d01aeb1`.
The snapshot-provenance regression passed, including fresh request IDs,
zero execution across policies, and changed-overlay invalidation.

Four failures were stale expectations that unchanged platform members rerun
after spec/reference edits or deletion/recreation. The owned test now asserts
only the members whose reachable content changed. Those corrected assertions
were not in this completed snapshot and need the next fast run.
The remaining error is the known foreign boundary, exactly:
“A refused declared-reference read refuses selection.”
`seon.fn/declared-reference-edges` returned a base error without a complete
declared facet, with declared facets `#{}`. No foreign source was changed.

The branch-content test spent 216 seconds constructing and using the
canonical base. A thread sample of this lane's JVM is retained at
`refreshed-authority-threads.json`: main awaited `retrying-base`, while
`seon-test-database-base` was in schema projection/instrumentation.
The long selection test completed in 608 seconds; its measured 1/10/100
seed probes took 40,906.8 / 43,981.3 / 124,813.0 ms respectively.
No watchdog or fixture bound was raised.

Launcher slice: `bin/test`, `bin/test-fast`, `src/seon/test/fast.clj`,
`test/seon/test/selection_test.clj`, AGENTS.md section 5, and this note.
The fast entry resolves one published base, admits the exact snapshot,
records completion through the cold recorder, and prints member confidence.
Shell syntax, owned diff checks and fast-entry static lint pass.
This slice deletes **17 bash lines** (15 from `bin/test`, 2 from
`bin/test-fast`); these are replacements, not the still-owed launcher thinning.
The file basis and shared tally migration remain pending. The second
identical-run proof is still owed; the known red cannot be reported green.

Cold proof owed to the orchestrator after convergence:

```sh
bin/test --paths bin/test bin/test-fast src/seon/test.clj src/seon/test/fast.clj src/seon/test/runner.clj src/seon/test/cache.clj src/seon/test/selection.clj src/seon/cluster/source.clj resources/seon/schemas/seon.source.edn resources/seon/schemas/seon.test.run.edn resources/seon/schemas/seon.test.selection.edn test/seon/test/selection_test.clj -- seon.test.selection-test seon.test-test seon.test.runner-test seon.test-cache-test
```

Then bare `bin/test` twice, with zero execution on the second green request,
and the orchestrator's isolated platform proof. This lane ran no cold gate,
worktree, publication, reset, or adoption.

The required pre-commit load of `seon.test`, `seon.test.fast`,
`seon.test.runner`, `seon.test.cache`, and `seon.cluster.source` returned
`:loads`, exit 0 (`launcher-precommit-load.log`).

Launcher commit **48e3f80d6** is followed by the same required HEAD load:
`:loads`, exit 0 (`launcher-head-load.log`).

## Recorded tally candidate

The candidate deletes `selection/read-basis`, `selection/write-basis!`,
their private file helper, `runner/record-green-basis!`, and the file-basis
round-trip test. No source, test or launcher caller of those Vars remains.
`run-results` first checks complete admitted/covered membership with the
existing `execution-members` query, then joins terminal member facts to
their owning run's confidence. Missing completion refuses rather than
disappearing from the tally. `recorded-run!` uses the existing bounded
store-holder transport and a read-only query over already-published keys;
it needs no reload of default or new stored attribute. The new result-row
and result-facts schemas describe read values only.

Both entry points use `print-recorded-tally!`. Fast reads the admitted run
after completion; the cold coordinator's still-legacy recording path uses
its queried returned results until the next launcher/admission slice.
That remaining cold migration is not claimed complete here.

One read-only MCP JVM probe of the same member/owner query verified run
`eb10af2747d0`: expected **11**, recorded **11**, counts **[134 4 1]**.
Every row carries basis **536870921**, program digest
`f25cc201a05b150368c2faf99d02e46dc788802441f1d6a1fe24ac9920b75a67`,
and input digest
`5ff9288d4fedceee558ecbf25e85e93075aa3eb86c86b9ee8721a71401028afb`.
The returned evaluation took **8,180 ms**. The query is maintained in
`runner/run-result-query`; it used `execution-members` with this run ID,
queried those member IDs, and summed row columns 1–3. No tests executed.
The printer includes errors when totaling assertion events: 134 + 4 + 1 = 139.

Two queued earlier candidate snapshots were terminated by this lane before
any test JVM launched, to include the complete membership read. No foreign
slot holder was operated. `tally-fast-3.log` is the current four-namespace
verification and includes both new read schemas in its overlay. Its outcome
is pending, not green evidence.

## Publication preservation gate — independently reproduced

Full publication uses `result-preservation-tx` at
`src/seon/cluster/source.clj:326`, then `preserved-evidence-tx` at `:378`
from `publish!` at `:606`. The run reader at `:350` is wildcard pull.
The vendored dependency's default cardinality-many limit is **1,000**
(`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`).
`force-branch!` changes the branch pointer to the supplied database value;
its parent assignment is not a merge of result datoms
(`reference-code/datahike/src/datahike/versioning.cljc:323–390`).
The existing source regression `latest-test-evidence-survives-rebuilding-from-an-older-base`
exercises legacy per-test evidence, not a large admitted member set.
The archived branch-head contention issue remains resolved; this is a
different, reproducible preservation defect in the new member model.

One read-only MCP JVM probe used the real published database, queried test
symbols, the real selection/admission owners, and Datahike's immutable
`with`. It submitted **no transaction to a connection**, published nothing,
and executed no test. Returned counts in **7,955 ms**:

```clojure
{:seon.test/requested 1001
 :seon.test/admitted 1001
 :seon.test/preserved 1000
 :seon.test/database-mutated? false}
```

Exact probe:

```clojure
(let [held (some :seon.store/store
                 (vals @(var-get (ns-resolve 'seon.cluster (symbol "running-instances")))))
      database (seon.cluster.source/database
                held (:seon.source/commit-id (seon.cluster.source/current held)))
      prior (seon.db/pull database
                          [:seon.test.run/published-base-digest
                           :seon.test.run/overlay-input-digest
                           :seon.test.run/program-digest :seon.test.run/basis-t
                           :seon.test.run/input-digest :seon.test.run/branch]
                          [:seon.test.run/id "eb10af2747d0"])
      symbols (vec (take 1001 (sort (seon.db/q
                                     '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]]
                                     database))))
      run (assoc (dissoc prior :db/id :seon.test.run/input-digest)
                 :seon.test.run/id "results-reuse-read-only-preservation-probe"
                 :seon.test.run/at (java.util.Date.))
      admission (seon.test/selection-admission
                  {:seon.db/db database :seon.test.run/provenance run
                   :seon.test.run/input-digest (:seon.test.run/input-digest prior)
                   :seon.test.run/policy :named
                   :seon.test.run/members
                   (mapv (fn [sym] {:seon.test.member/symbol sym
                                    :seon.test.member/reasons #{:named}}) symbols)})
      expanded (:db-after (datahike.api/with database (seon.test/admit-run database admission)))
      preserved ((deref (ns-resolve 'seon.cluster.source (symbol "result-preservation-tx"))) expanded)
      row (first (filter #(= "results-reuse-read-only-preservation-probe"
                             (:seon.test.run/id %)) preserved))]
  {:seon.test/requested (count symbols)
   :seon.test/admitted (count (:seon.test.run/members admission))
   :seon.test/preserved (count (:seon.test.run/members row))
   :seon.test/database-mutated? false})
```

The authorized source region is admission/recording only. No production
edit has been made to the preservation or publication region. The gate is
ownership and the durable-evidence guarantee, not a foreign red. Options:

1. **Recommended: extend this lane to the existing publication evidence
   transfer and its canonical regression.** Keep one authority; enumerate
   complete admitted evidence and verify membership, refs and confidence
   after rebuilding. Cost: one additional publication slice and an
   orchestrator-owned publication/cold proof. Give up: the current narrow
   source-region boundary, not evidence or reuse.
2. **Have the publication owner repair that transfer.** This lane retains
   admission/recording ownership and resumes integration when the canonical
   preservation proof lands. Same durability guarantee; cost: another
   coordinated owner and checkpoint. Give up: single-lane completion.
3. **Temporarily refuse full publication when it cannot preserve admitted
   evidence completely.** Guarantee: no silent member loss. Cost: a small
   fail-closed guard and deferred full publication until preservation is
   repaired. Give up: full-publication availability, not the recorded facts.

The current armed verification will be checkpointed before returning this
decision. The source-preservation repair, launcher thinning, shared host
admission and final two-run zero-execution proof remain owed.

### Tally checkpoint verification

```sh
bin/test-fast --paths bin/test src/seon/test/runner.clj src/seon/test/fast.clj src/seon/test/selection.clj resources/seon/schemas/seon.test.runner.edn resources/seon/schemas/seon.test.run.edn test/seon/test/selection_test.clj test/seon/test_runner_test.clj -- seon.test.selection-test seon.test-test seon.test.runner-test seon.test-cache-test
```

Snapshot HEAD `0d50884382ce1fa87f0c05ba0ddbca14d5258369`; slot wait
**368 seconds**, one test JVM PID **48713**. Run `86e6a9c3e1cc` completed
and recorded **46 executed, 0 unchanged; 471 assertion events, 4 failures,
6 errors**, exit 1. The post-recording member query returned all 46 members,
each printed with basis **536870921**, program digest
`d393eedc7b34293db0c36f4f0f33ef4e1aad9ebb3b7ef7be85dbc024c3ad048f`,
and input digest
`da012d953fc5ab86521297048bad47b391862262c8959c16e4c727a8855c6e86`.
The new canonical snapshot/tally regression passed, including pending-member
refusal and fresh-event reuse. The earlier four stale platform assertions
also passed after correction.

`tally-fast-3.log`: **152,367 bytes**, SHA-256
`f6128e02d44b0666f83643f3e76577f3f22b1bf26be3ea01943840f753d578e4`.
That snapshot's shared printer says **465 assertions** because it predates
the one-line inclusion of six error events in the assertion total. The
candidate adds errors to pass + fail; it does not claim that corrected line
was in this log. The later read-schema alias naming is also outside this
snapshot, with unchanged value shapes.

Observed verification boundaries (not attributed beyond their returned evidence):

- Selection's refused declared-reference assertion: the already-named
  `seon.fn/declared-reference-edges` base error lacks a declared facet.
- `check-records-only-execution-members-and-reuses-the-green-set`:
  `seon.program/declaration-row` receives nil at `test_test.clj:31`.
- `recording-distinguishes-run-replay-from-a-new-event`: four assertions
  are blocked by `seon.blob/with-publication!` returning undeclared write
  refusal facets, including the immutable-run refusal conversion.
- `recording-preserves-admission-and-refuses-a-deleted-definition`:
  `seon.fn.schema-shape/normalized-form` reports a compiled schema without
  canonical EDN shape data.
- `no-double-execution`: three assertions encounter undeclared facets at
  `seon.blob/with-publication!`, including `:seon.test/execution-error`.

The root `seon.test-runner-test` diagnostics expectations were updated for
the new unavailable-tally behavior but are not the dotted
`seon.test.runner-test` namespace in this iteration command. Its isolated
cold proof is owed; no additional nested-worker JVM suite was launched.

The required pre-commit load (including `seon.test.selection`) returned
`:loads`, exit 0 (`tally-precommit-load.log`). Owned diff checks pass.
This slice removes another **3 bash lines**, replacing the obsolete file
basis description with 2 lines; cumulative replaced/deleted bash lines are
**20**, and worker arithmetic/thinning remains pending. Files in this
checkpoint are the eight overlay paths above, AGENTS.md section 5 and this
note. No source-publication preservation function was edited.

The cold command above additionally owes
`resources/seon/schemas/seon.test.runner.edn`, `test/seon/test_runner_test.clj`
and namespace `seon.test-runner-test`. Bare twice and platform remain the
orchestrator's proofs. No two-run zero-execution CLI proof is claimed.

Tally checkpoint: **baa0afca3**. The immediate post-commit shared-tree
load exited 1 with a cyclic load dependency:

```text
seon.test -> seon.issue -> seon.plan -> seon.bootstrap -> seon.cluster.agent
-> seon.render.web -> seon.render -> seon.sci.eval -> seon.test
```

`tally-head-load.log` is **510 bytes**; the full compiler report was copied
to `shared-load-cycle.edn`. Concurrent edits were present in
`src/seon/cluster.clj`, `src/seon/instrument.clj`, `src/seon/schema.clj`,
and `src/seon/schema/internal.cljc`; no individual cause is attributed.
This slice changed no namespace require edges.

To distinguish committed HEAD from the shared edits, a plain `git archive
HEAD` was extracted to the lane's disposable `tmp/` directory, with the
vendored `reference-code` linked. No Git worktree was created. The exact
required Clojure load command there returned **`:loads`, exit 0** for
HEAD **baa0afca3** (`tally-committed-head-load.log`, **205 bytes**).
The loader exited, then that archive was removed without following its
vendored symlink. No test, lifecycle command or publication ran there.
All lane-owned source paths are committed; unrelated edits remain intact.

## Publication history gate after ownership extension

The orchestrator approved extending preservation ownership. `git status --short`
showed `src/seon/cluster/source.clj` and `test/seon/cluster/source_test.clj`
clean; `src/seon/cluster.clj` remains held with uncommitted edits. No production
file was edited in this followup.

Confirmed the truncating read at `src/seon/cluster/source.clj:350`:
`db/pull-many previous '[*] runs`. Datahike's `pull_api.cljc:16` declares
1,000 and `:315–323` takes that many before expanding component rows.
A query is required for complete membership; raising the limit is not a fix.

A second independent defect prevents a query-only repair from delivering
usable preserved admission. `seon.test.runner/execution-members` at `:2332`
compares current membership with `db/as-of` at the run's `selection-tx`.
The preservation map copies that numeric transaction ref unchanged into a
rebuilt database whose transaction history differs. Datahike
`versioning.cljc:323–390` changes the branch head and parent metadata; it
does not merge the previous parent's datoms or temporal indexes.

Read-only MCP probe against the published authority, using real admission,
existing preservation, and immutable `datahike.api/with` only (11,126 ms):

```edn
#:seon.test{:before 1,
           :selection-tx 536870931,
           :rebuilt-basis 536870934,
           :current-members 1,
           :after "The test execution evidence does not authorize this transition.",
           :database-mutated? false}
```

This probe isolates reasserting preserved evidence in a later transaction;
it is not claimed as a full `publish!` regression. It proves that preserving
the current member count alone cannot establish executable admission.
The exact reproducible form (the prior run is existing live evidence):

```clojure
(try (let [held (some :seon.store/store (vals @(var-get (ns-resolve 'seon.cluster (symbol "running-instances"))))) database (seon.cluster.source/database held (:seon.source/commit-id (seon.cluster.source/current held))) prior (seon.db/pull database [:seon.test.run/published-base-digest :seon.test.run/overlay-input-digest :seon.test.run/program-digest :seon.test.run/basis-t :seon.test.run/input-digest :seon.test.run/branch] [:seon.test.run/id "eb10af2747d0"]) run (assoc (dissoc prior :db/id :seon.test.run/input-digest) :seon.test.run/id (seon.id/id) :seon.test.run/overlay-input-digest (seon.id/digest 64 [:publication-temporal-probe]) :seon.test.run/at (java.util.Date.)) sym (first (sort (seon.db/q '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]] database))) admission (seon.test/selection-admission {:seon.db/db database :seon.test.run/provenance run :seon.test.run/input-digest (:seon.test.run/input-digest prior) :seon.test.run/policy :named :seon.test.run/members [{:seon.test.member/symbol sym :seon.test.member/reasons #{:named}}]}) expanded (:db-after (datahike.api/with database (seon.test/admit-run database admission))) preserved ((deref (ns-resolve 'seon.cluster.source (symbol "result-preservation-tx"))) expanded) row (first (filter #(= (:seon.test.run/id run) (:seon.test.run/id %)) preserved)) advanced (nth (iterate #(:db-after (datahike.api/with % [])) database) 3) rebuilt (:db-after (datahike.api/with advanced [row])) read-members (deref (ns-resolve 'seon.test.runner (symbol "execution-members")))] (pr-str {:seon.test/before (count (read-members expanded (:seon.test.run/id run))) :seon.test/selection-tx (get-in row [:seon.test.run/selection-tx :db/id]) :seon.test/rebuilt-basis (:max-tx rebuilt) :seon.test/current-members (count (seon.db/q '[:find [?member ...] :in $ ?id :where [?run :seon.test.run/id ?id] [?run :seon.test.run/members ?member]] rebuilt (:seon.test.run/id run))) :seon.test/after (try (count (read-members rebuilt (:seon.test.run/id run))) (catch Throwable e (ex-message e))) :seon.test/database-mutated? false})) (catch Throwable e (str (.getName (class e)) ": " (ex-message e))))
```

The first attempt used the wrong arity of `seon.id/digest`; MCP hid that
exception behind `seon.dev.mcp/projection-failed`. The corrected form above
returns a string and succeeds. The existing open issue
`docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`
owns the tooling defect; no tool or default runtime was changed.

### Decision required before production edits

1. **Recommended: preserve the published lineage.** Build the next publication
   from the current published database and reconcile program declarations there,
   retaining run/member identities and transaction history in place. Guarantee:
   admission, completion and immutable retries keep their existing meaning.
   Cost: publication/reconciliation change plus canonical large-membership,
   deletion and concurrency proofs. Give up rebuilding each publication from
   the older `:db` base. The population owner is `src/seon/cluster.clj:1741`
   (currently held); coordinate its release before any needed edits there.
2. **Define explicit evidence transfer between histories.** Keep the older-base
   rebuild, query all evidence, remap entity refs, and declare how original
   selection/claim/completion transactions are observed after transfer.
   Guarantee: complete evidence under a newly specified cross-history contract.
   Cost: schema and reader/writer work across admission, claims, replay and
   publication; give up leaving those contracts unchanged. Never silently
   rewrite old selection/claim refs to the publication transaction.
3. **Refuse full rebuilds while admitted runs need preservation.** Keep safe
   incremental updates and recorded results on the existing head. Guarantee:
   no truncated or temporally invalid transfer. Cost: a small exact refusal and
   postponed full publication; give up full-rebuild availability until 1 or 2.

No fast test was launched in this followup; no code changed and no
new tally is claimed. One foreground namespace-load JVM exited 1 with the
same shared-tree cycle recorded above, at `seon/issue.clj:1:1`
(`tmp/results-reuse-everywhere/publication-history-load.log`). No individual
foreign edit is attributed, and no worktree or archive was created. The
1,001-member publication regression, thin launchers,
host integration and identical-two-run zero-execution proof remain owed.

## Truncation slice after publication-lineage ruling

Read `docs/prds/steward-platform/plan/publication-dissolution-spec-2026-09-20.md`
end to end. Publication reconciliation belongs to its lane. The preservation
file was clean before editing; the held `src/seon/cluster.clj` was not edited.

`src/seon/cluster/source.clj:323` now reads evidence attributes with Datalog
and expands owned components from their queried datoms. The run wildcard pull
at former line 350 is removed. Every cardinality-many run/component attribute
is complete, without increasing a pull limit. Peer references retain their
existing identity for the publication-lineage owner to preserve.

Added `test/seon/test/publication_test.clj`, one regression using the canonical
source manifest, real `source/publish!`, and real `source/record-results!`.
It admits 1,001 canonical test identities, publishes again, and independently
queries the exact member-symbol set. This checks collection preservation;
it does not claim that copying old transaction refs fixes their history.

Attempted armed iteration:

```sh
bin/test-fast --paths src/seon/cluster/source.clj test/seon/test/publication_test.clj -- seon.test.publication-test
```

Snapshot HEAD `c12fbf1fe0c0e512b0302066fd3404ecfe4d724c`, published overlay base
`98b0449d90652e72969190ce279ca6cac9bf67c23c6cc4a06c7eeb7ed228928a`.
Waited 147 seconds for a slot. JVM 67551 loaded the namespace and armed
1,375 contracts. Admission then crossed the operator's 30,000 ms prepl silence
bound; exit 1 **before any test body**. No assertion tally or passing regression
is claimed. `publication-members-fast.log`: **8,345 bytes**, SHA-256
`56e40b53e20e0048970dbdc67b4338a816f00782aaf5d440613d7273a674c41a`.
The thread sample is `publication-members-threads.json`.

A read-only authority query (9,035 ms) independently found request
`a1c2b3344636` admitted at **536870933** despite the lost response. Its evidence
was not deleted, retried as a fresh event, or marked green. The launcher JVM
exited. This leaves admitted work without terminal evidence; transport outcome
must not be inferred from a client timeout. Subsequent independent namespace
verification will use a distinct request, not claim completion of this one.

Pre-commit owned source load:
`clojure -M -e "(require 'seon.cluster.source) (println :loads) (shutdown-agents)"`
returned **`:loads`, exit 0** (`publication-members-load.log`).

Publication-dissolution must retain these references in the same history:
`seon.test.run/selection-tx` read by `src/seon/test/runner.clj:2334`, compared
through `db/as-of` at `:2348`; `seon.test.member/claim-tx`, `/completed-tx`,
and `/terminated-tx` read at `:2359–2360`; and `/covered-by` member refs
queried at `:2344`. Reconciliation must retain their entity identities and
temporal datoms, not just copy their numeric values into a rebuilt history.

Cold proof additionally owed:
`bin/test --paths src/seon/cluster/source.clj test/seon/test/publication_test.clj -- seon.test.publication-test`.
No bash lines changed in this truncation slice. Files: source owner, new
regression, and this note. Earlier thin-launcher and host obligations remain.

## Two identical fast requests: recorded zero-execution proof

Truncation commit **44b51bab0** loaded after commit: `:loads`, exit 0,
`publication-members-head-load.log`. The loader required source, runner and
cache in that order; no running system was reloaded.

Two consecutive commands, with HEAD remaining **44b51bab0**, used the same
published base and HEAD-plus-paths snapshot. No full publication occurred:

```sh
bin/test-fast --paths bin/test src/seon/test/cache.clj src/seon/test/runner.clj test/seon/test_cache_test.clj test/seon/test_runner_test.clj -- seon.test-cache-test
```

First (`cache-reuse-first.log`, **4,207 bytes**, SHA-256
`68475a29bbe67b7114ec5c6ae77c48db26f92018c435d215a6dbdb9d515172f6`):

```text
Ran 4 tests containing 51 assertions.
0 failures, 0 errors.
Recorded 4 executed, 0 unchanged; 51 assertions.
0 failures, 0 errors.
bin/test-fast: 4 executed, 0 unchanged; run a9a52dedca74
```

Second (`cache-reuse-second.log`, **2,853 bytes**, SHA-256
`3bc8fc0ed7ab6a141386c25b6884375c0772de266994e2c6864df8a7db86a62f`):

```text
Ran 0 tests containing 0 assertions.
0 failures, 0 errors.
Recorded 0 executed, 4 unchanged; 51 assertions.
0 failures, 0 errors.
bin/test-fast: 0 executed, 4 unchanged; run a595eb0bf4f9
```

Both exit 0. All four second-run member lines have `:seon.test/unchanged true`
and these three confidence values:

```edn
{:seon.test.run/basis-t 536870921
 :seon.test.run/program-digest "d55f3f5d2d3b2f02f46895f8b692c7a87ad948b3ec1f4fdc79912485b08c499b"
 :seon.test.run/input-digest "edc7dca723bdaef7caeca417577f5279830a70aff4f39d6723322cd678c026df"}
```

The recorded 51 assertions belong to the first execution; the second executed
none. Fresh run IDs distinguish the requests without changing their snapshot
identity. The second snapshot also reported dirty `src/seon/test.clj` and
`test/seon/program_test.clj` callers as HEAD bytes; those working edits were
excluded and did not change the tested snapshot.

### Launcher duplication removed

`bin/test` no longer implements its own worker-copy function or worker arithmetic.
`seon.test.cache/worker-count` owns processor/namespace bounds and explicit
overrides; `worker-checkout!`, already used by every `start-worker!`, now
owns preparation too. The shell's worker-checkout phase and 300-second default
bound stay in place, as do silence, exchange, watchdog and isolated platform
worker paths. Bash syntax passes. This slice replaces/deletes **82 bash lines**,
adds 11, and leaves `bin/test` at 1,105 lines; cumulative replaced/deleted lines
are **102**. Snapshot/preparation shell remains; this is not a claim that the
entire cold coordinator admission migration is finished.

The two-run command verifies `seon.test-cache-test`, including the pool sizing
regression: 4 tests / 51 assertions / 0 failures / 0 errors, then 0 execution.
The root `seon.test-runner-test` expectation update still owes the isolated gate.
Files: `bin/test`, `src/seon/test/cache.clj`, `src/seon/test/runner.clj`,
`test/seon/test_cache_test.clj`, `test/seon/test_runner_test.clj`, AGENTS.md §5,
and this note. Host work is a separate slice.

Cold command owed:
`bin/test --paths bin/test src/seon/test/cache.clj src/seon/test/runner.clj test/seon/test_cache_test.clj test/seon/test_runner_test.clj -- seon.test-cache-test seon.test-runner-test`.
The orchestrator still owes bare twice (second zero) and platform. No cold
gate or lifecycle command was run by this lane.

### Publication regression completed; host caller-scope checkpoint

The canonical `seon.test.publication-test/publication-preserves-every-admitted-member`
regression completed on 2026-09-20 at 03:09:00 UTC. It published the canonical
manifest, admitted 1,001 actual test members through the source recorder,
published again, and independently queried the complete member set: all three
assertions passed. It exercises the collection preservation repair in
`44b51bab0`; it does not claim that a rebuild preserves temporal history.

The combined probe was:

```sh
bin/test-fast --paths src/seon/test.clj resources/seon/schemas/seon.test.edn test/seon/test/host_test.clj test/seon/test/publication_test.clj -- seon.test.host-test seon.test.publication-test
```

`tmp/results-reuse-everywhere/host-publication-fast.log`: **5,754 bytes**, SHA-256
`fb99ce9249ba09605abe8a7d487891f5f6d8889eba07c8cb1b27e141e5598990`.
Run `b88733a27be1`: **2 executed, 0 unchanged, 4 assertions, 0 failures,
1 error**, exit 1. The error was in this lane's unlanded host draft:
`run-owned` returned nil at the first call in `host_test.clj:27`; its armed
`:seon.test/host-result` contract refused that value. No foreign attribution is
made. The publication test ran afterward and passed. The draft source/schema
changes were removed using their exact saved diff; the draft and fixture remain
under `tmp/results-reuse-everywhere/host-integration-draft.patch` and
`host_test.clj` for diagnosis. They are not landed implementation or green proof.

Host integration cannot land atomically inside the current path assignment:
`src/seon/plan.clj:770`, `test/seon/test_failure_facts_test.clj:50`, and
`test/seon/test_expiry_test.clj:33` call `seon.test/run` without an explicit
cluster. The plan caller also shares provenance across its test set. These paths
were clean when inspected. `src/my/test.clj:43` and
`test/my/test_test.clj:74` retain the old forced-execution/no-new-event claims.
AGENTS lane rule 13 requires caller conversion in the same public behavior
slice. The ownership question was sent before the draft was withdrawn:

1. **Recommended:** extend this lane to those five direct caller/documentation/
   fixture paths. Guarantee explicit cluster custody and fresh event semantics
   in one coherent slice; cost is caller conversion plus their regressions;
   scope expands beyond the original test-owner paths.
2. Have their owner make coordinated conversions before the host slice.
   Same guarantee, with a handoff and second checkpoint; independent host
   landing is given up.
3. Defer host integration. Keep the verified fast reuse and preservation
   changes; no caller changes now, but the in-process task remains unfinished.

`a6fbf412b`'s post-commit source/runner/cache require exited 0. The worker
launcher duplication is removed, but the cold coordinator's remaining admission
and shell preparation migration is still owed, alongside host integration.
The two-run fast proof above remains valid on its named snapshot and lineage.

Publication-dissolution owns lineage reconciliation at the held
`src/seon/cluster.clj:1741`. It must preserve `selection-tx` meaning and the
membership observed by `runner.clj:2334-2348`, including covered members, plus
member claim/completion/termination transaction refs at `runner.clj:2359-2360`.
No edit was made to that held file or to default.

Cold preservation proof owed:
`bin/test --paths src/seon/cluster/source.clj test/seon/test/publication_test.clj -- seon.test.publication-test`.
The launcher cold command above, bare twice (second zero), and platform remain
the orchestrator's proof. All named authorities were read end to end, including
the publication-dissolution specification and its current-lineage ruling.

Final pre-commit require of `seon.cluster.source`, `seon.test`, `seon.test.fast`,
`seon.test.runner`, and `seon.test.cache` exited 0 (`final-load.log`, `:loads`).
This checkpoint changes only the publication test's alias-qualified activation
symbol, this note, and the existing publication-history issue's ownership/ruling.

### Host admission probe and downstream reader decision

The orchestrator extended ownership to `src/seon/plan.clj`, `src/my/test.clj`
and affected tests. Those source paths and the direct test callers were clean.
`test/my/plan_test.clj` and `test/seon/problems_test.clj` had foreign edits and
were not changed. The publication lane's source/cache/launcher owners were
not changed.

The host draft now retains native selection's covered-member refs, reads actual
reservations after admission, and prevents completed red members from being
treated as pending coverage. The canonical `run-owned` probe passed:

```sh
bin/test-fast --paths src/seon/test.clj resources/seon/schemas/seon.test.edn test/seon/test/host_test.clj -- seon.test.host-test
```

HEAD snapshot `510a9236d9b800a5856b74b71ab4b4ba4d027f2c`, published base
`98b0449d90652e72969190ce279ca6cac9bf67c23c6cc4a06c7eeb7ed228928a`.
Run `e92996f7d9d3`: **1 executed, 0 unchanged, 8 assertions, 0 failures,
0 errors**, exit 0. This outer regression called `run-owned` twice against
one canonical cluster: two fresh run events, one execution member and one
covered member. The second host result was unchanged and retained basis,
program digest and input digest. This is fixture evidence, not a live agent
turn or the orchestrator's cold gate.

`tmp/results-reuse-everywhere/host-admission-probe.log`: **2,045 bytes**, SHA-256
`1ab1cbd86c4e8b1e70024448f0a204573da19d51db9ddf113183fdda95482f8e`.
Later draft changes forwarding requested basis, checking existing admission
before repeated execution, and converting plan/my.test callers were not in
this snapshot and are not claimed tested. The host implementation is uncommitted
pending the reader decision below; the old caller fixtures are not yet converted.

The audit found a larger atomic migration dependency. The admitted recorder
stores member outcomes, while issue completion and failure discovery read the
legacy test-row result family. A read-only MCP probe of the real recorded run
`a9a52dedca74` confirmed four green members (pass counts 16, 29, 5, 1) and **zero
legacy result attributes** on their corresponding test declarations. Three
declarations existed with only symbols; the fourth was snapshot-only. The probe
completed in 7,035 ms with no elision. Its reproducible form is
[host-result-consumer-probe-2026-09-20.edn](host-result-consumer-probe-2026-09-20.edn).

Exact consumers and acceptance criteria are in
[the issue](../../../seon/issues/admitted-test-results-are-invisible-to-legacy-consumers.md).
In particular `src/seon/issue.clj:822` requires legacy counts for done,
`src/seon/problems.clj:348` discovers failures from them, and
`src/seon/render/test.clj:11` pulls them for presentation. No held reader was
edited. Switching host recording alone would make its new evidence invisible
to those consumers.

The next decision, sent as soon as this dependency was verified:

1. **Recommended:** extend the slice to migrate these readers and affected
   tests to queries over admitted member facts. Guarantee one fact authority
   and working settlement/reporting; cost is broader reader conversion and
   canonical regressions, with coordination for the held problem test.
2. Have a separate lane migrate readers first. Same guarantee with an extra
   handoff; host landing waits for that dependency.
3. Temporarily write legacy latest-result projections too. Smaller consumer
   change, but duplicates stored state and needs an explicit exception to the
   one-mechanism ruling; it is not implemented.

Cold host proof remains owed after the complete slice:
`bin/test --paths src/seon/test.clj resources/seon/schemas/seon.test.edn src/seon/plan.clj src/my/test.clj test/seon/test/host_test.clj -- seon.test.host-test my.test-test seon.test-test seon.test-failure-facts-test seon.test-expiry-test`.
That command must include the eventual reader and fixture conversions too.

## Host and reader migration, 2026-09-20

The orchestrator accepted option 1: admitted members remain the only result
authority. The host slice converts `seon.test/run`, `run-owned`, the operator
single-test request, `my.test/run`, and `seon.plan/run-issue-tests!` together.
Every host request is a fresh event; matching green evidence contributes
`covered-by` membership without executing a body. The issue runner's deadline
outcome also uses this admission/recording path rather than writing legacy rows.
An explicit unmatched historical basis refuses before admission: execution
cannot claim to have run historical code.

Converted readers:

- `src/seon/test/runner.clj:3338`, `latest-results`: query native admitted
  members by branch and selection transaction; a newer incomplete execution
  refuses instead of revealing an older green. Snapshot observations do not
  certify a different branch's current program.
- `src/seon/test.clj:1426`, `recorded-result`; `:1435`, `stale-in`; `:2086`,
  `verified?`: read that authority and compare current reachable content and
  publication input identity with the tested basis. `:61`,
  `changed-since-green`, reads admitted green history.
- `src/seon/issue.clj:642`, `status`, and `:843`, `done?`: recorded member
  outcomes determine verification; issue membership is queried, not pulled.
- `src/seon/issue/opening.clj:86`, `test-pull-form`, and `:213`, `context`:
  generated reads and opening data use `recorded-result`.
- `src/seon/problems.clj:349`, `failed-tests`: newest admitted outcomes
  determine current failures while older red members remain history.
- `src/seon/render/test.clj:15`, `evidence`, and `:35`, `failures`: render
  admitted outcomes and their immutable failure reports, including confidence.

The first reader probe executed **1 test / 22 assertions / 1 failure / 0
errors**. Reuse, green/red/green history, failure discovery, status and rendering
passed. `host_test.clj:52` falsified issue completion for a lookup reference:
`status` said verified while `done?` returned false. The query now receives the
resolved entity id rather than carrying a lookup vector through `identity`.
Log `tmp/results-reuse-everywhere/host-readers-fast.log`: **5,929 bytes**.

The next snapshot never reached assertions: this lane supplied boolean `true`
for new `my.test-test` long-test metadata, whose contract requires a reason
string. That declaration is corrected. Recovery then hit the existing fixture
diagnostic defect at `test/seon/test_support.clj:515`, missing `:seon.error/at`,
`/layer`, and `/operation`, before completing fixture construction. This lane
terminated its own invalid JVM (92770); launcher exit **143**. Log
`tmp/results-reuse-everywhere/host-readers-final-fast.log`: **286,514,790 bytes**.
The diagnostic issue now carries that evidence; no fixture-owner edit was made.

Remaining reader boundaries, not edited: the publication lane's held
`src/seon/fn.clj:1376` and `:1379` Datalog `test-currently-failing` clauses still
read legacy test-row counts (`currently-failing-functions` at `:1553`).
`src/seon/bootstrap.clj:245`, `:412`, and `:460` select demonstration/usage
evidence from legacy counts; its tests are concurrently edited. These readers
need the same authority conversion by their owners. No new legacy row writes
were introduced to accommodate them.

Corrected snapshot `run.NVcS15`, run `8258d293443c`, reached **3 tests / 38
assertions / 10 failures / 0 errors**. The host/reader regression passed its
**24 assertions**, including lookup-reference completion and historical-basis
refusal. Its body ran from `03:52:05.628343Z` to `03:56:18.240983Z` (252.613 s,
including canonical fixture and SCI acquisition). The two other fixtures
refused for `:seon.test/input-evidence-unavailable`: their canonical synthetic
source seal lacked a test-input digest. They now supply explicit fixture input
identities like the accepted host fixture. The follow-up runs only those
changed fixture namespaces plus the affected `seon.test-test` admission suite;
the green host namespace is not re-executed.

Log `tmp/results-reuse-everywhere/host-readers-corrected-fast.log`: **11,614
bytes**, SHA-256
`d16a01af361abb79360340df84c5622c19c7e5a81ad628f7e0e0c53687079f1c`.
One sample from the existing JVM (no second JVM) observed the second host
request in `changed-definition-symbols` through shared selection. The sample
is `tmp/results-reuse-everywhere/host-readers-corrected-threads.json`; no
performance cause is inferred from that single observation.

The clean issue-settlement fixture's evidence reader at
`test/seon/issue_settlement_test.clj:74` also now calls `recorded-result` and
checks the three confidence values. Its ordinary-turn integration proof is
owed with the orchestrator's cold gate; this lane did not change held turn or
cluster implementation/tests. Legacy `test_failure_facts_test.clj` and
`test_reaching_test.clj` host callers now pass explicit fixture cluster/input
identity, but their broader legacy-fact expectations are not claimed green by
the focused host proof.

This slice changes `AGENTS.md` §5, `resources/seon/schemas/seon.test.edn`,
`src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/plan.clj`,
`src/my/test.clj`, `src/seon/issue.clj`, `src/seon/issue/opening.clj`,
`src/seon/problems.clj`, `src/seon/render/test.clj`,
`test/seon/test/host_test.clj`, `test/my/test_test.clj`,
`test/seon/test_test.clj`, `test/seon/test_expiry_test.clj`,
`test/seon/test_failure_facts_test.clj`, `test/seon/test_reaching_test.clj`,
`test/seon/issue_settlement_test.clj`, this note and the diagnostic/reader issue
notes named here. **Zero bash lines change in this slice**; the previously accepted
82-line launcher deletion and 4→0 proof remain recorded above. Held launcher
and publication files are unchanged by this lane.

Cold proof owed (orchestrator only):

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj src/seon/plan.clj src/my/test.clj src/seon/issue.clj src/seon/issue/opening.clj src/seon/problems.clj src/seon/render/test.clj resources/seon/schemas/seon.test.edn test/seon/test/host_test.clj test/my/test_test.clj test/seon/test_test.clj test/seon/test_expiry_test.clj test/seon/test_failure_facts_test.clj test/seon/test_reaching_test.clj test/seon/issue_settlement_test.clj -- seon.test.host-test my.test-test seon.test-test seon.test-expiry-test seon.issue-settlement-test
```

The orchestrator additionally owes the platform proof and two bare `bin/test`
requests on one unchanged lineage, with zero executions in the second. No cold
gate, worktree, or default lifecycle operation was performed by this lane.

Follow-up `run.UTw4ok` executed **10 tests / 64 assertions / 4 failures / 2
errors**. `my.test-test` passed all **10 assertions** through real SCI, including
two events / one member / one covered member and equal three-value confidence.
`seon.test-expiry-test` passed **4 assertions**; the changed
`resolution-follows-admitted-source-and-acquisition` regression also passed.
Remaining admission-suite boundaries and the failed outer recording are named
in [the existing refusal issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
The outer run `e1d46131b83d` has **no successful durable tally**: published
recording refused error-facet propagation through `seon.blob/with-publication!`.
Log `tmp/results-reuse-everywhere/host-callers-fast.log`: **62,619 bytes**,
SHA-256 `26849939cde139e8af0cea990ec110bbb13506abf1ee2eb05ca0ec5bafecad74`.

The final host probe adds one previously uncovered reader assertion:
`seon.issue.opening/context` returns the same recorded confidence. Its linked
test identities are now queried rather than pulled, avoiding the dependency's
cardinality-many truncation. The host test's inputs changed for this additional
coverage; the already-passing SCI and expiry namespaces are not rerun.

Precommit shared-tree load passed, exit **0**, printing `:host-readers-load`:

```sh
clojure -M -e "(require 'seon.cluster.source 'seon.test 'seon.test.runner 'seon.plan 'my.test 'seon.issue 'seon.issue.opening 'seon.problems 'seon.render.test) (println :host-readers-load)"
```

The explicit held paths were preserved: `src/seon/cluster.clj`,
`src/seon/cluster/prompt.clj`, `src/seon/turn.clj`, `src/seon/schema.clj`,
`src/seon/schema/internal.cljc`, the dirty cluster/turn tests,
`src/seon/cluster/source.clj`, `src/seon/test/cache.clj`, `src/seon/fn.clj`,
`bin/test`, `bin/seon-hook`, and `script/seon/fresh_operator.clj`.
The source admission namespace was loaded, never edited. No lane session or
foreign process was operated.

During the final probe, a repository hook reported in-flight syntax errors in
the held `src/seon/fn.clj` at `:2509` and `:2718–2738`. This lane did not edit
that file; the HEAD-plus-owned-paths probe was unaffected. The final shared-tree
load must independently verify convergence. A final HTML anchor correction
uses the existing `seon.id/id` owner when rendering a transient report without
an admitted report id; it does not create a stored identity or a second result.

Final probe `run.IPE6Qo`, durable run **`c6a8fc93128b`**, passed **1 test / 25
assertions / 0 failures / 0 errors**, exit **0**. The queried durable tally is
**1 executed / 0 unchanged / 25 assertions** for the outer regression. Inside
that regression, the second identical host request executes zero and returns
unchanged with all three confidence values; issue completion, status, opening
context, failure discovery, and test rendering read the recorded members.
The intentional nested red assertion remains immutable history and does not
count as an outer failure. Log
`tmp/results-reuse-everywhere/host-readers-opening-fast.log`: **5,231 bytes**,
SHA-256 `4c91370818321cf2a2b4cedd27a11df44030b39fa91a4cb6f2a2fb09d4c0597a`.

Outer confidence: basis **536870921**, program digest
`3776edea352a2e10e110e8eefdf68b3621e4d8971ca7ee63e364dcda3602f6c9`,
input digest `91fe0748d0a928349c8f0ed6ac02b46fee3d4fed37c89b59c808956854421488`.
The small transient-report HTML anchor correction came after that snapshot;
its namespace is included in the final load check, not claimed as an armed
HTML regression. The issue runner's fresh per-member database capture and the
issue-settlement reader conversion are in the final snapshot; the turn-level
settlement regression remains a cold proof owed as named above.


## Final bounded closeout — 2026-09-20

Ownership checked again: `test/seon/bootstrap_test.clj` remains dirty in
bridge-step1-registry. Per the orchestrator, leave its paired conversion owed,
without waiting: `src/seon/bootstrap.clj:245`, `:412`, `:460` still read legacy
counts. `src/seon/fn.clj:1378`, `:1381` (formerly :1376/:1379) and the reader
formerly at :1553 are explicitly handed to publication-dissolution; this lane
has not edited that file. These are remaining consumers, not a second writer.

The four failures in `recording-distinguishes-run-replay-from-a-new-event`
are foreign-with-path: `src/seon/blob.clj:271` (`with-publication!`) declares
`:seon.schema/value` while returning the callback's typed refusal. Its armed
wrapper replaces the invalid-read/immutable evidence with undeclared-facet
errors. The three subsequent absent diagnostic assertions are consequences
of that same boundary, not expectations changed to accept a lost diagnostic.
The deletion-refusal error is foreign-with-path: `src/seon/db.clj:4121`
(`transact-call`), called at :4457 in the saved snapshot, validates the refused
write against a report requiring `:db-before`. The writer had already refused
the missing test definition. Neither owner was edited by this slice.

Cleanup evidence before deletion (all under this lane's own log directory):

| Log | Bytes | SHA-256 |
| --- | ---: | --- |
| `host-readers-final-fast.log` | 286514790 | `ba2fdaa0f661b9486e9ce395af51c3d38762ec8be50380396d8b90ba997a3f31` |
| `baseline.log` | 20013366 | `0e0b4c81b0408e37c83dfb11554b6e8503289f573e5178bafcda628368ee6648` |
| `resume-baseline.log` | 20062155 | `16483a23d03b4973ec722a295941413eddb00c875ca858b1fe5ccc09cd9de2ef` |

Deleted those three completed logs: **326,590,311 bytes**. No shared run root
or foreign log was removed. The smaller proof logs remain available.

Complete implementation/checkpoint history, derived from this note's Git
history: `b26dc7904`, `c6c72b832`, `db24035ee`, `a86344441`, `48e3f80d6`,
`baa0afca3`, `858e3053b`, `6e011707c`, `44b51bab0`, `a6fbf412b`, `3f0a92135`,
`c4be385e3`, `b0b5eedfe`; this closeout is the following path-limited commit.

The system now treats every request policy as an eligibility scope: recorded
green members with matching confidence are unchanged and execute nothing.
Every request is a fresh event; snapshot provenance records the published
base, overlay input digest, program digest and tested basis honestly. Both
launchers and agent-owned host execution use admitted immutable result facts,
with oversized payloads stored as blobs and tallies queried from those facts.
Host execution binds only its explicit cluster custody. Migrated completion
and reporting readers share that authority. The accepted fast 4→0 proof and
host two-request/one-execution proof establish reuse; the bootstrap/fn reader
handoffs and the orchestrator's cold/platform integration remain explicitly owed.

After publication-dissolution lands, the orchestrator runs the focused cold
command recorded above, then these exact integration commands (not run here):

```sh
bin/test
bin/test
bin/test --platform
```

The second bare request must execute zero and report unchanged members with
basis, program digest and input digest. Publication must preserve the recorded
selection transaction lineage described earlier in this note.


The closeout diagnostic snapshot `run.WIpHfF` ran only `seon.test-test`,
whose fixture diagnostic changed. It reproduced **8 tests / 50 assertions /
4 failures / 2 errors**, exit **1**. `admission-final-fast.log`: **57,966
bytes**, SHA-256 `210fa78796775d1434fc4fb90430c0e97d142ec6d63e1e87460b13d891d2a5bf`.
The outer recorder again refused at the published authority; this is an
execution tally, not a durable green tally. The exception reporter omitted
ex-data, so the fixture now includes the original SCI evaluation and failed
source in its exception message before calling `declaration-row`.

The read-refusal equality expectation is not stale: `src/seon/db.clj:4243`
explicitly returns a Seon transition refusal verbatim. No expectation was
weakened to accept replaced evidence. The last green host tally remains
**1 test / 25 assertions / 0 failures / 0 errors**, with the durable and
inner zero-execution evidence recorded above. Already-green host, agent-SCI,
and expiry namespaces were not rerun during closeout.


The second diagnostic run (`run.tZ049a`) exposed a successful evaluation:
`ordinary` returned `#'selection.check/ordinary`, outcome `:ok`, but no row.
The initial hypothesis was a missing explicit `:seon.db/db`; a subsequent
probe (`run.cGsGON`) supplied it and **falsified that attribution**: the same
missing-row error remained. The second diagnostic log is **59,007 bytes**,
SHA-256 `c524838c02c311dbcd956eff3f45b2f18560cf669b319331a6e503759b3d9f4f`,
and repeats the pre-fix **8 / 50 / 4 / 2** tally.

The fixture calls `acquire!` between declarations. That is a base regeneration
(`src/seon/sci/eval.clj:2141`), replacing its fork environment; authored Var
recognition needs the SCI generation (`:371`, `:381`). The dependency gives
a fresh generation to `sci/fork` (`reference-code/sci/src/sci/core.cljc:351`).
The correction uses `install-evaluated-rows!` (`src/seon/sci/eval.clj:1049`),
the existing settlement path, preserving the evaluated definitions and fork.
This is an owned fixture error, not a foreign SCI refusal. The explicit
database and visible original-evaluation diagnostic remain in the fixture.
The original six observations classify as **one owned fixture error, four
foreign blob-boundary failures, one foreign database-boundary error; zero
stale expectations among those six**.


Intermediate evidence, retained to distinguish the falsified hypothesis from
the correction:

| Log | Execution tally (tests/assertions/failures/errors) | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `admission-corrected-fast.log` | 8/50/4/2 | 58180 | `1ddd591cfe02c1477cc47b70fe183575923cd4fcefb3c740864c956a50962425` |
| `admission-settlement-fast.log` | 8/65/13/1 | 72558 | `86d5854ab16ed8b274f90b601c376b01dfd4faf73b6d6a89e0d4182ea4f7e836` |

Settlement made the declarations observable. Its newly reached assertions
then showed the initial fixture program had never acquired the namespace
setup writes. The fixture now acquires its seeded database once, forks that
base, and installs evaluated definitions without regenerating that fork.
The same regression exposed an owned selector omission: long exclusions did
not apply the named eligibility scope. `src/seon/test.clj` now applies the
same `named?` predicate to those exclusions as to admitted members. The
existing canonical check regression verifies the exclusion set, so there is
no duplicate regression. No error contracts or facets were widened.

The hook also observed foreign in-flight syntax errors at
`src/seon/fn.clj:2165` and `test/seon/fn/publication_test.clj:127`, followed
by an unmatched form at the latter file's :70/:173. Those paths were neither
edited nor included in this lane's HEAD-plus-paths snapshot. Existing kondo
shadowed-var/docstring warnings in `src/seon/test.clj` were reported by the
hook; the scope fix adds no new binding or public function.


### Final verification boundary and checkpoint

The final corrected snapshot was requested with exactly:

```sh
bin/test-fast --paths src/seon/test.clj test/seon/test_test.clj -- seon.test-test
```

It **executed no JVM and no tests**: exit **75**, after the declared **1,800 s**
slot wait. Holder 96666 retained `run.b7dtUo` (elapsed 1:20:47) and holder
15275 retained `run.O81Ldt` (elapsed 30:53). Neither foreign process/root was
operated. This is unavailable verification, **not** an unchanged/green tally.
`admission-scope-final-fast.log`: **8,978 bytes**, SHA-256
`915fbe62eef12e6c74d44fad551e14539ae7a72bb44fa43a23a8516b8af63ae1`.
The launcher's own snapshot `run.SAU7sI` was removed on exit.

The latest executed admission tally is therefore **8 tests / 65 assertions /
13 failures / 1 error**, from the intermediate fixture. Nine failures exposed
the fixture acquisition and named-exclusion corrections now in the checkpoint;
four failures and one error remain the blob/database boundaries. The final
corrections have **not been verified green**, because no slot became available.
The latest durable green host tally remains **1 / 25 / 0 / 0**, and the accepted
fast **4 executed → 0 executed / 4 unchanged** proof remains unchanged.

This checkpoint touches exactly `src/seon/test.clj`,
`test/seon/test_test.clj`, this note, and
`docs/seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md`.
The final fast command above is still owed when capacity becomes available;
then the focused cold command earlier in this note and bare `bin/test` twice
plus `bin/test --platform` remain the orchestrator's integration work after
publication-dissolution. No cold gate or lifecycle command was run here.


Precommit shared-tree namespace load passed, exit **0**, printing
`:results-reuse-closeout-load` (`closeout-precommit-load.log`). Command:

```sh
clojure -M -e "(require 'seon.cluster.source 'seon.test 'seon.test.runner 'seon.plan 'my.test 'seon.issue 'seon.issue.opening 'seon.problems 'seon.render.test) (println :results-reuse-closeout-load)"
```

This verifies loading only; it does not replace the slot-blocked fast proof.
