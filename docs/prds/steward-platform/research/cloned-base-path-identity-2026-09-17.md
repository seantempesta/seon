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
