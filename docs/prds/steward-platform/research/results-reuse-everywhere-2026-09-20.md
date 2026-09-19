---
type: research
status: design-gate
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

No implementation of these alternatives has begun.

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
