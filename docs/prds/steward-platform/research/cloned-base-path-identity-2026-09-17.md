---
type: research
status: active
tags: [test, publication, path-identity]
---

# Cloned-base path identity — implementation and verification

Dated 2026-09-17. The initial protected-file pause below was lifted by the
owner. Interim commit `f1e93fd02` landed first; the root implementation and
verification follow the initial boundary record.

## Evidence and dependency boundary

Read the assigned issue, the peer's complete note from `86cda05b6`, and
`tmp/orchestrator/wave2/repl-rule.txt` end to end. The issue's measured
baseline remains 2,048 absolute digest keys, 337 discarded file analyses,
then a complete analysis. No new analysis-count experiment was run.

`bin/seon status` and MCP runtime status observed default alive, PID 30138,
prepl 56009. A read-only MCP JVM probe read `build/current-src.edn`:

```clojure
{:manifest-roots ["/Users/sean/src/seon/src"
                  "/Users/sean/src/seon/test"]
 :file-count 332
 :sample ["/Users/sean/src/seon/test/seon/fixtures/run7_token_observations.edn"
          "aa85932dc6b0517922a63df9e96451f9ad91a81f6a028853547ccd4cece2382d"]}
```

The same probe's `seon.fn/tests-reaching` query used a dereferenced explicit
connection without carrying its projection and returned
`:seon.schema/missing-projection`. It selected no tests and proves no reach.
Future selection must use `seon.db/db` with the appropriate projection.

`seon.fn/artifact` currently emits canonical absolute file identity;
`seon.fn/build-manifest` emits canonical absolute manifest roots.
`seon.cluster.source/snapshot` emits canonical absolute digest-map keys.
The new relative identity must replace the old identity key under the
assignment's schema-breakage rule. That requires these protected consumers:

* `src/seon/effect.clj:286`, `seon.effect/write-back-adds`:
  `(db/pull database [:db/id] [:seon.fn.file/path path])` resolves effect
  write-back provenance. It must resolve the new relative identity with
  the publication's root; keeping the old lookup silently loses provenance.
* `src/seon/test.clj:32`, `seon.test/failure-text`:
  `(get-in failure [:seon.test.failure/file :seon.fn.file/path])` must read
  the replacement key or the displayed source site disappears.

These are explicitly protected by the assignment even though neither file
had an uncommitted edit when inspected. No other lane was contacted or
modified. Existing edits in `src/seon/instrument.clj`,
`test/seon/instrument_test.clj`, `test/my/test_test.clj`, and
`test/seon/test_support.clj` were preserved.

## Verification and remaining work

No functions redefined, adoption attempted, tests run, test JVM launched,
scratch root created, or default lifecycle operation performed. There are
no before/after counts beyond the cited baseline, no passing regression,
and no implementation commit to queue for the cold gate. RESET NEEDED is
anticipated for the requested identity replacement, but no schema changed.

Resume requires assignment of the two protected consumer hunks. Then land
the interim root-mismatch guard and canonical count regression first;
land root-relative identity and clone no-op/one-file regressions second.
The cold boot-test proof remains the orchestrator's responsibility.

Files touched by this boundary report: this landing note and
`docs/seon/issues/a-cloned-published-base-names-a-checkout-that-is-gone.md`.

## Interim implementation after consumer release

The owner released both consumers. The interim guard compares the manifest's
canonical roots with the publication's canonical roots before snapshot/diff
or file analysis. The canonical boot regression
`seon.cluster.boot-test/relocated-manifest-requires-one-complete-analysis`
observes the real analyzer, uses a fresh analysis cache scoped to the test,
and requires exactly one call covering every manifest artifact. It does not
replace the analyzer or publication with a stand-in. This destructive
regression awaits the orchestrator's cold gate; its analysis count is an
assertion, not a claimed measurement.

The revised function was evaluated in default before the source edit.
`bin/seon init --dev default --changed src/seon/cluster.clj --changed
test/seon/cluster/boot_test.clj` converged at source commit
`6aaabdcf-c6ee-5559-bb4a-9ff9a367755e`.

After reloading only `seon.fn-test` through `seon.test/with-test-loader`,
this in-process form ran in a future and was polled to completion:

```clojure
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)]
  (seon.test/run
    (#'seon.test/resolve-test
      'seon.fn-test/file-artifacts-and-manifests-are-byte-digested-and-deterministic)
    connection
    {:seon.db/db database
     :seon.test.run/provenance (seon.test.runner/provenance database)
     :seon.test/remaining-ms 180000}))
```

Result: 19 passes, zero failures, zero errors, run entity 51388, basis
536871086. `seon.fn/tests-reaching` selected this test through
`seon.fn/build-artifact`; no `seon.program-test` test reached that function.
This validates the unchanged artifact mechanism, not the cold regression.
Interim files: `src/seon/cluster.clj`, `test/seon/cluster/boot_test.clj`,
and this note. No schema change in this slice.

## Root-relative implementation

RESET NEEDED: the root slice replaces stored `:seon.fn.file/path` with
`:seon.fn.file/relative-path` and stored `:seon.fn.file/root` with
`:seon.fn.file/relative-root`. The artifact replaces
`:seon.fn.manifest/roots` and `:seon.source/file-digests` with
`:seon.fn.manifest/relative-roots` and
`:seon.source/relative-file-digests`. Physical indexing requests use the
separate `:seon.fn/source-path`; the manifest records its physical checkout
once as `:seon.fn.manifest/root`. Findings retain their source coordinates
with `:seon.fn.finding/relative-path`. Old identity definitions are deleted,
not reinterpreted. The orchestrator owns default's reset.

Dependency ledger: canonical resolution and relativization use
`java.io.File.getCanonicalPath` and `java.nio.file.Path.relativize` in
`seon.fs`. Clojure's file construction is grounded in
`reference-code/clojure/src/clj/clojure/java/io.clj`; indexing still sends
captured absolute source filenames to `seon.fn.analyzer/analyze`, whose
mirror protocol requires them. Only the resulting artifact identities and
findings become relative. Datahike clone identity remains the existing
`seon.cluster.export/reidentify!` mechanism, grounded in
`reference-code/datahike/src/datahike/connector.cljc`; no store copier or
second clone-specific path mapper was added.

The production incremental owner rebinds only the manifest's root when
reading a relocated artifact, and persists that root on an unchanged
refresh. All per-file keys, refs, finding identities, and digests survive
unchanged. The existing test-support clone therefore needs no modification.
The previously uncommitted fixture hunk landed independently in `0ea0a5518`
and is not part of this slice.

`--changed` paths are normalized against the current publication's root and
filtered against actual digest differences. An unchanged report no longer
forces an analysis. With no differences the current published head returns
without running complete analysis. The separate conversion in
`seon.test.selection/manifest-relative-artifacts` is deleted: its input now
already carries that identity.

The issue citation contract remains suffix matching on the new file identity
attribute. Effect write-back lookup and test failure rendering use the new
identity; declaration-root queries and test selection follow the same facts.

Scratch verification uses `tmp/cloned-base-root`, cluster `lane`, for this
schema replacement. Its first published artifact had 2,054 digest keys and
337 file artifacts; absolute digest keys = 0, absolute file identities = 0,
manifest roots = `["src" "test"]`, physical root =
`"/Users/sean/src/seon"`. This was queried through MCP JVM mode, not inferred
from text replacement. Default has not been reset, stopped, or reforked.

## In-process root verification

Final scratch adoption converged at source commit
`6aaac290-d438-5ff6-aec2-8caee202c49c`, digest
`2f8cec2f45d6423b1d855af3a4756cbef895f29c0a77c5f38fd3701396203713`.
Because the identity schema is replaced, these runs used the assigned scratch
application JVM (`lane`), rather than replacing default's schema. No test JVM
was launched. Test namespaces alone were reloaded through
`seon.test/with-test-loader`; `seon.test-support` was never reloaded.

Each listed test ran serially through this exact form, inside a future polled
by short MCP evaluations:

```clojure
(let [connection (seon.operator/connection "lane")
      database (seon.db/db connection)]
  (seon.test/run (#'seon.test/resolve-test sym) connection
    {:seon.db/db database
     :seon.test.run/provenance (seon.test.runner/provenance database)
     :seon.test/remaining-ms 180000}))
```

The `seon.fn/tests-reaching` queries selected the artifact tests via
`seon.fn/build-artifact`, and program tests via `seon.program/canonical-row`.
The extra consumer checks cover the migrated effect lookup, failure site,
and test selector directly. Final distinct results: **11 tests, 99 passing
assertions, zero failures and zero errors**.

| Test symbol | Passes |
| --- | ---: |
| `seon.fn-test/relocated-artifacts-preserve-path-identity` | 5 |
| `seon.fn-test/file-artifacts-and-manifests-are-byte-digested-and-deterministic` | 19 |
| `seon.fn-test/indexed-declarations-carry-exact-file-bytes` | 12 |
| `seon.fn-test/static-findings-are-replaced-with-their-program-rows` | 9 |
| `seon.fn-test/the-walked-root-travels-with-its-file-relative-to-the-publication` | 3 |
| `seon.fn-test/an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart` | 7 |
| `seon.program-test/declaring-an-attribute-on-a-program-row-schema-is-sufficient` | 5 |
| `seon.program-test/reader-events-have-one-canonical-declaration-row` | 16 |
| `seon.edit-test/form-edit-refs-the-program-entity-it-changed` | 12 |
| `seon.test-failure-facts-test/failures-render-their-site-and-claim` | 6 |
| `seon.test.selection-test/a-changed-file-selects-exactly-the-tests-that-reach-it` | 5 |

An earlier artifact run reported 17 passes, one failure and one error because
two test assertions still looked up absolute keys. Those assertions were
updated to relative identity, reloaded, and the final run above passed 19/0/0.
The dependency cache refresh and targeted lint completed; targeted lint of
12 changed owners/tests/probe files reported zero errors. Whole-tree lint
still reports `parser.type/->Variable` unresolved at `src/seon/db.clj:559`;
the live JVM resolves that constructor and compiled the namespace. This is a
lint boundary, not evidence of a runtime failure, and is recorded in the
existing edit-hook kondo issue. Existing shadowing/unused warnings remain.

The destructive boot namespace was not run in process. Its two count
regressions and full namespace gate remain explicitly queued for the
orchestrator; no cold pass is claimed here.

## Measured clone result

The committed probe is
[cloned_base_path_identity_probe_2026_09_17.clj](cloned_base_path_identity_probe_2026_09_17.clj).
It exports the real published store through the existing store exporter,
copies its artifact and source roots, then observes the actual analyzer
while refreshing against the relocated checkout. The added source bytes are
exactly `"\n; Relocated checkout edit.\n"`. The saved result is
[cloned-base-path-identity-results-2026-09-17.edn](cloned-base-path-identity-results-2026-09-17.edn):

```clojure
{:probe/unchanged-built? false
 :probe/unchanged-analysis-paths []
 :probe/edited-analysis-paths ["src/seon/ai/tokens.cljc"]
 :probe/changed-built? true
 :probe/unchanged-commit #uuid "6aaac290-d438-5ff6-aec2-8caee202c49c"
 :probe/changed-commit #uuid "6aaac38b-f161-50e6-8faf-34a63317ec0e"}
```

Thus relocation plus an unchanged absolute `--changed` path performs **zero
file analyses**, keeps the exact published commit, and reports `built? false`.
One real edit performs **one file analysis**, for precisely the edited file,
and publishes a new commit. The peer's pre-fix baseline was 337 discarded
per-file analyses followed by a complete analysis. The interim one-full-pass
regression remains a cold-gate assertion; it is not mislabeled a measurement.

The database itself was also queried: 337 file/root rows, zero absolute file
paths, roots exactly `#{"src" "test"}`. Neither the stored source locations
nor failure refs require the original checkout prefix.


## Juniper and final ownership

The assigned scratch cluster was seeded through the existing Juniper installer
(`juniper-fixture-2026-09-06/install! "lane"`), with providers disabled by
the installer's default. It returned system turn `e2f0348f8737` and the four
expected declaration keys: `:example/amount`, `:example/customer`,
`:example/order`, `:example/order-row`.

Root-slice owned paths (the removed issue path and archived replacement are
both named):

- `docs/prds/steward-platform/research/cloned-base-path-identity-2026-09-17.md`
- `docs/prds/steward-platform/research/cloned-base-path-identity-results-2026-09-17.edn`
- `docs/prds/steward-platform/research/cloned_base_path_identity_probe_2026_09_17.clj`
- `docs/seon/issues/a-cloned-published-base-names-a-checkout-that-is-gone.md`
- `docs/seon/issues/archive/a-cloned-published-base-names-a-checkout-that-is-gone.md`
- `docs/seon/issues/edit-hook-kondo-false-positives-on-seon-db-dynamic-vars.md`
- `resources/seon/schemas/seon.fn.edn`
- `resources/seon/schemas/seon.fn.file.edn`
- `resources/seon/schemas/seon.fn.finding.edn`
- `resources/seon/schemas/seon.fn.manifest.edn`
- `resources/seon/schemas/seon.issue.edn`
- `resources/seon/schemas/seon.program.edn`
- `resources/seon/schemas/seon.source.edn`
- `resources/seon/schemas/seon.test.failure.edn`
- `src/seon/cluster.clj`
- `src/seon/cluster/source.clj`
- `src/seon/effect.clj`
- `src/seon/fn.clj`
- `src/seon/fs.clj`
- `src/seon/issue.clj`
- `src/seon/issue/detect.clj`
- `src/seon/issue/opening.clj`
- `src/seon/problems.clj`
- `src/seon/program.cljc`
- `src/seon/render/test.clj`
- `src/seon/test.clj`
- `src/seon/test/runner.clj`
- `src/seon/test/selection.clj`
- `test/seon/adoption_rows_test.clj`
- `test/seon/cluster/boot_test.clj`
- `test/seon/cluster/source_test.clj`
- `test/seon/edit_test.clj`
- `test/seon/fn_test.clj`
- `test/seon/issue_generate_test.clj`
- `test/seon/issue_test.clj`
- `test/seon/program_test.clj`
- `test/seon/schema/program_test.clj`
- `test/seon/schema_test.clj`
- `test/seon/test/runner_test.clj`
- `test/seon/test/selection_test.clj`
- `test/seon/test_failure_facts_test.clj`

Cleanup completed: the Juniper query returned four order entities and the
`juniper` agent. `bin/seon --root tmp/cloned-base-root down` completed with
`flock free`; the scratch root, exported clone, and lane scratch files were
deleted. No worktree was created. Every launched shell completed. Final
`bin/seon status` reports default PID 30138 alive; this lane never stopped,
reforked, or restarted it. The cold boot gate remains pending.

## Batch 96 follow-up: reported paths and concurrent owner boundary

Read the replacement AGENTS.md instructions and the two named failure blocks
in `tmp/orchestrator/gate-results/batch-96/named.log`. The reported-path failure
is a production defect, not missing fixture roots: `changed-source-paths`
filters the concatenation of reported paths and digest keys, discarding any
reported path whose digest compares equal (including two absent digests).
The helper does not consult roots.

Exact read-only MCP JVM probe on default PID 53320:

```clojure
(#'seon.cluster/changed-source-paths
 {"src/a.clj" "a1" "src/b.clj" "b1"}
 {"src/a.clj" "a1" "src/b.clj" "b2"}
 ["src/a.clj" "../outside.clj"])
;; => ["src/b.clj"]
```

Both reported paths were silently dropped. The fix must preserve the union
of reported paths and independently derived digest changes, with a regression
for a reported path absent from both digest maps. The unchanged-clone proof
must distinguish an unreported no-op refresh from an explicit analysis request.

Stopped under the assignment's explicit protected-file rule: `git status`
shows concurrent uncommitted changes in `src/seon/cluster.clj`; its diff includes
`require-reopenable-declarations!`, its startup call, and `declaration-forms`
inside `incremental-source-refresh!` passed to `build-artifact`. Required owned
hunks are `changed-source-paths` and its incremental caller (around lines
1977–2027 at observation). No foreign hunk was changed or staged. The second
failure's one transaction has NOT yet been captured or attributed. No test
JVM, scratch root, or worktree was started; default remains alive and untouched.
This documentation checkpoint is not a fix and requests no cold rerun.

## Batch 96 transaction capture (follow-up)

The concurrent cluster.clj hunks remain uncommitted. Asked the owner whether
separate owned hunks may proceed despite the assignment's protected-file stop
rule; no answer had arrived before this checkpoint. No production edit made.

Independent observation used an isolated application JVM under
`tmp/cloned-base-root`, never a test JVM. The exact MCP form is preserved in
`cloned_base_batch96_capture_2026_09_17.clj`; its returned value is in
`cloned-base-batch96-capture-2026-09-17.edn`. Explicit provider-disabled config
was supplied to old-world3. This capture is NOT the cold regression: publication
rebuilt because the shared tree changed, and the observation interval includes
three transactions instead of one. It cannot establish the exact cold interval.

After bootstrap closure at 536870948, transaction 536870949 writes:
`:db/txInstant`, `:seon.turn/id`, `:seon.turn/agent`,
`:seon.turn.work/situation`, `:seon.turn/opened-tx`, `:seon.turn/trigger`,
`:seon.runtime/turns`. It opens root's ordinary turn, triggered by message
entity 47221. Transaction 536870950 freezes its empty virtual reply, and
536870951 closes it and removes the trigger from the inbox. Source ownership:
`bootstrap/seed-tx` creates the initial task message in the inbox and assigns
it as the opening turn's trigger. Bootstrap closure does not settle an ordinary
reply. The running loop can therefore open the task turn after the fixture's
`await-bootstrap!` returns. The publication regression must isolate publication
from that running producer before capturing its basis; simply waiting for
bootstrap closure is insufficient. This is evidence for the fixture race, not
proof that no other write can occur in the cold run.

Capture corrections: the first listener attempted `vec` on Datahike datoms
and threw; it was removed. A second scratch attempt allowed provider activity,
so its observations were discarded and its application JVM shut down. The
preserved capture reads explicit datom fields and disables providers. The
initial old-world health timeout was downstream of that faulty listener;
no default MCP session was altered. No passing test is claimed here.

Both scratch application JVMs were downed through the operator, which reported
`flock free`; the scratch root and temporary probe files were deleted. No
worktree was created, all shells completed, and no default restart occurred.

## Batch 96 corrections after release of cluster.clj

The owner released the file after 849bbce0b. The follow-up changes one production
owner: `changed-source-paths` unions reported paths with independently filtered
digest differences. Equal or absent digests cannot discard an explicit report.
The pure regression moved from destructive `seon.cluster.boot-test` fixtures
to `seon.cluster.source-test`, retaining its four assertions and adding a fifth
for a reported path absent from both maps. The cold clone regression also
removes the reported file from both snapshots and observes the REAL analyzer:
exactly that file must be analyzed. Its ordinary unchanged-relocation case now
supplies no reported paths; explicit reports are requests to analyze, so the
previous zero-analysis claim for an explicitly reported path is superseded.
The reusable clone probe was adjusted to that same distinction.

The publication-isolation regression calls `cluster/disarm-agents!` after
bootstrap closure and BEFORE recording the old connection's basis. This owner
removes routing, quiesces the armer, joins each agent's stop acknowledgement,
and joins the cluster graph. The connection stays open. The assertion remains
EXACT basis equality, with no tolerated extra transaction. Its final ordinary
`cluster/stop!` still owns connection/store cleanup.

Read-only default MCP attribution of the preserved listener datoms returned:
536870949 opens an agent turn; all three captured transactions have no
`:seon.db/process` datom. The first transaction's attributes are
`:db/txInstant`, `:seon.turn/id`, `:seon.turn/agent`,
`:seon.turn.work/situation`, `:seon.turn/opened-tx`, `:seon.turn/trigger`,
`:seon.runtime/turns`. No default graph was stopped for this probe. The stopped
fixture's complete publication proof remains the destructive cold gate.

Follow-up owned paths: `src/seon/cluster.clj`,
`test/seon/cluster/boot_test.clj`, `test/seon/cluster/source_test.clj`,
`docs/prds/steward-platform/research/cloned_base_path_identity_probe_2026_09_17.clj`,
and this note. Targeted clj-kondo: 0 errors, 15 existing warnings.

Default adoption converged at source commit
`6aaacff8-f5bb-5e26-ba96-5bd48ba02640`, digest
`c45a5537e4219d4dc85eb41e72cb08ed2842a5f09d46c8e818748ffffe9bbc84`.
The operator retried once because the final cold-regression edit arrived while
its first publication was being adopted. The second pass completed schema,
program, loaded-definition, SCI, and instrumentation steps. Default remained
PID 53320 throughout. The test namespace alone was reloaded through
`#'seon.test/with-test-loader`; test-support was not reloaded.

Exact in-process run, launched in a future and polled by later MCP evaluations:

```clojure
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)]
  (seon.test/run
   (#'seon.test/resolve-test
    'seon.cluster.source-test/incremental-source-refresh-includes-unreported-changes)
   connection
   {:seon.db/db database
    :seon.test.run/provenance (seon.test.runner/provenance database)
    :seon.test/remaining-ms 180000}))
```

Before convergence, the REPL-installed definition passed 5 assertions with
0 failures and 0 errors (run entity 51499, basis 536871136). The separate
post-adoption result below is the adopted-definition proof.

Post-adoption result: **5 passing assertions, 0 failures, 0 errors**, run
entity 51500, basis 536871140, at `2026-09-16T17:22:29Z`. No test JVM was
launched and no destructive boot fixture was run in default. No new schema
meaning changed in this follow-up, so it adds no reset requirement. All
launched shells completed; this follow-up created no scratch root/worktree.
The cold request includes boot-test and the relocated source-test namespace.

## Batch 98: establish the regression's own baseline

Read the `cloned-publication-analyzes-only-changed-files` block in
`tmp/orchestrator/gate-results/batch-98.log`: 2 failures; the analyzer list
contains 337 inputs in the first assertion and 338 in the second. This is not
zero work attributable solely to relocation. Read-only examination of the
retained root found the current run checkout and pool-1 checkout have equal
`cluster/source-snapshot ["src" "test"]` values and zero changed paths. The
cached publication artifact was no longer present in that retained root.
Therefore this follow-up does not attribute the mismatch to any named test or
claim the retained bytes prove the suspected earlier rewrite.

The regression now calls `(cluster/refresh-source! root [])` on its own cloned
published root BEFORE copying the worker checkout and BEFORE installing the
analysis observer. That gives this test a publication and artifact for the
current worker bytes, independent of the pooled fixture's earlier publication.
The relocated no-op must preserve that baseline's exact commit as well as
report `built? false` and ZERO analyzer inputs. The reported path and edited
path assertions still require EXACTLY the one expected file. No tolerance,
production behavior, shared worker file, or foreign retained root was changed.

Only the boot regression and this landing note are owned by this follow-up.
The destructive fixture is not run in default; its complete proof is queued
for the cold gate. In-process checks exercise the existing real artifact
relocation regression and reported-path selection unit, through the canonical
three-argument runner, with test namespaces reloaded through its own loader.

Adoption converged at source commit `6aaad49e-a55e-52ad-981d-4df826c4bb8f`,
digest `40884038572031ee1da343735d44cfaeb49041117d51223c5bbc2ea6d941b14b`.
The two post-adoption in-process results are **10 pass, 0 fail, 0 error**:

- `seon.fn-test/relocated-artifacts-preserve-path-identity`: 5 pass;
  result entity 51505, basis 536871147, at `2026-09-16T17:41:50Z`.
- `seon.cluster.source-test/incremental-source-refresh-includes-unreported-changes`:
  5 pass; result entity 51506, basis 536871149, at `2026-09-16T17:41:56Z`.

Each test used the exact three-argument form recorded above with its symbol,
explicit default connection/database/provenance, and remaining-ms 180000;
runs were serial in one future, polled through short MCP evaluations. This
proves artifact relocation and path selection, not the destructive cold test's
complete publication baseline. The new cold assertion additionally requires
its relocated refresh to preserve the exact freshly established commit.

`git diff --check` passed; edit-hook lint found no errors and five existing
shadowing warnings. Default remained PID 53320. No test JVM, scratch cluster,
or worktree was created. Retained root inspection was read-only; every shell
completed and the temporary adoption log was deleted. No RESET NEEDED is
introduced by this test-only correction.
