---
type: landing
status: landed; cache counts on the publication result pending fn.clj and seon.source.edn
created: 2026-09-23
tags: [agent-platform, publication, clj-kondo, cache, seconds-not-minutes]
---

# Lane publication-work: leaf publication work proportional to the change (2026-09-23)

Fix schedule #16 (`docs/research/agent-platform/fix-schedule-2026-09-23.md`); measured
row 6 and row 8 of `docs/research/agent-platform/slow-assumptions-audit-runtime-2026-09-23.md`;
issue `docs/seon/issues/publication-analysis-reads-a-stale-kondo-cache-entry-for-an-unindexed-caller-target.md`.
Base HEAD `d58e000fe`. Opus 5.5 lane.

## What changed

| Path | Change |
|---|---|
| `src/seon/cluster/source.clj` | `snapshot-test-input-digest`: the test-input digest from content already held. A partial publication whose changed paths are all indexed program files reuses the published digest (no read). Otherwise Git's inventory names the external inputs; every digest the capture/stored map holds is reused and only the rest are read. `classify-paths` refuses a `deps.edn`/gitlink change as `::restart-needed` ("JVM RESTART NEEDED ... `bin/seon down` then `bin/seon start` on the same store; no reset"), not RESET NEEDED. `resolve-population` and `retire-scratch!` no longer swallow their failure (cause chained / attached as suppressed). |
| `src/seon/fn/analyzer.clj` (the kondo cache owner) | The whole-cache walk (`discard-obsolete-cache-entries!`, run before and after every analysis, plus a second `run!` whenever it deleted) is gone. After one `run!`, `stale-cache-entries` examines only the entries of the namespaces the analysis used — the only ones clj-kondo loads (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:127-144`, `load-when-missing`, rev `57252e07`). Stale = source modified after the entry was written (stat check), source gone, stdin, a defined namespace moved file, or (shared cache only) a source inside the checkout outside every declared source and `:local/root` root. Stale entries are deleted, checkout-owned ones rebuilt from current bytes, and the analysis runs once more; still-stale after that refuses loudly. `analyze` returns `::cache {::examined ::stale ::rebuilt}` — the cache hit/miss count. The swallowing `deps.edn` read (`catch Throwable _ nil`) is gone. |
| `test/seon/cluster/publication_inputs_test.clj` | Regressions: stale kondo entry (issue class), derived test-input digest equals the whole-checkout digest and reads only unheld inputs, partial reuse, restart refusal rule; pin fixtures use 64-hex (this repository's Git object format is SHA-256 — `reference-code/sci` pin is `da9db3d1…61492`), which fixes `changed-paths-hash-only…`, red at HEAD under armed contracts. |
| `docs/prds/agent-platform/landing/lane-publication-work-measure-2026-09-23.clj` | The committed measurement script for the rows below. |

## NOT landed — held file, exact change for its holder

`src/seon/cluster.clj` (held by schema-changes-in-place). In `full-source-refresh!`, against
the working tree at this lane's end (applies to HEAD `d58e000fe` with offset; the patched
file loads — proven by the armed test run below, which requires `seon.cluster`):

```diff
-                             :else (source/classify-paths changed (set (keys (test.cache/gitlink-digests directory)))))
+                             ;; A changed directory input is a gitlink: capture digests
+                             ;; directories only through their recorded pins.
+                             :else (source/classify-paths
+                                    changed (into #{} (filter #(.isDirectory (io/file directory %))) changed)))
@@
             inputs (merge (if partial? (merge (apply dissoc stored requested) observed) observed) additional)
+            published-input-digest (when committed
+                                     (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]]
+                                           committed))
+            _ (when (map? published-input-digest)
+                (refused! "The published test-input digest could not be read." published-input-digest))
+            test-inputs (source/snapshot-test-input-digest
+                         (cond-> {:seon.fn/root directory
+                                  :seon.cluster.source/roots input-roots
+                                  :seon.source/relative-file-digests inputs}
+                           partial? (assoc :seon.source/changed-paths (vec (into changed requested-paths)))
+                           published-input-digest (assoc :seon.cluster.source/published published-input-digest)))
             snapshot {:seon.source/relative-file-digests inputs
                       :seon.source/digest (id/digest 64 [(into (sorted-map) inputs)])
-                      :seon.source/test-input-digest
-                      (test.cache/test-input-digest directory
-                                                    (test.cache/input-digests directory))}
+                      :seon.source/test-input-digest (:seon.source/test-input-digest test-inputs)}
```

`input-roots` is the value `full-source-refresh!` already holds (one `git ls-files --stage`).
Carrying `::analyzer/cache` and the `test-inputs` counts into `:seon.source/publish-result`
needs `seon.fn/analyze-rows` (`src/seon/fn.clj`, held by deletion-caller-edge) to return the
analyzer's `::cache`, and the result key declared in `resources/seon/schemas/seon.source.edn`.
Until then the counts are observable on `analyze`'s value only.

## Evidence

Measurement boundary: no store was available to this lane. The published test base
(`target/test-published-bases/d73e0a6c…`) and `.clj-kondo/.cache`, `.cpcache` were deleted
mid-lane by `76b42f90f` ("Nuke wipes every derived cache"), so the end-to-end leaf publication
on a private store clone could not run. Rows are the source-build phases the fix touches,
measured by the committed script in two `git archive` snapshots of `d58e000fe` (reference-code
symlinked, shared `.clj-kondo/.cache` symlinked, `GIT_DIR` = the repository so Git reads are
real): *before* = HEAD, *after* = HEAD + this lane's files + the cluster.clj patch. Leaf =
`src/seon/eval.clj` (requires `seon.db`, `seon.error`, `seon.schema.edn`). Shared cache 420
entries (the audit's 2,352-entry cache was wiped; the walk cost scales with it: 313 ms/walk
measured there). Five warm rounds each; ms.

| phase (per leaf publication) | before | after | hits / misses |
|---|---|---|---|
| kondo analysis of the leaf | 309, 116, 107, 87, 91 | 145, 40, 35, 24, 20 | after: 4 entries examined, 0 stale, 0 rebuilt |
| test-input digest, partial (hook names the leaf) | 437, 416, 372, 359, 345 (`input-digests` over the whole checkout) | 0.08, 0.02, 0.02, 0.02, 0.01 (published digest reused) | 1 hit, 0 files read |
| test-input digest, full (no named paths) | same whole-checkout digest | 83, 83, 83, 78, 78; equal to the whole digest in every round | 287 held, 21 read |
| gitlink read for classification | 27–50 (`git ls-files --stage`) | 0 (directory check on the changed set; with the patch) | — |
| leaf capture | 0.15–0.35 | 0.16–0.28 | unchanged |

The no-change publication is unchanged: `full-source-refresh!` returns the published head
before the test-input digest, classification or analysis run, and none of those lines moved.

Before this lane (default on old source, read-only MCP probe, 2026-09-22T21:2xZ):
`(seon.test.cache/input-digests ".")` 738 ms (3,404 files), whole test-input digest 257–294 ms
warm; derived form equal (`:equal? true`), 94–110 ms, 20 missing inputs read.

Stale-entry class, reproduced with clj-kondo directly (default JVM, private cache dir):
callee `pa/f [x]`, caller `(pa/f 1 2)`; after editing the callee to `[x y]` and linting only the
caller, clj-kondo reported `pa/f is called with 2 args but expects 1` (4.6 ms). Regression
`a-caller-is-never-checked-against-a-cache-entry-older-than-its-callee`: with HEAD's analyzer
it FAILS (4 failures: stale arity finding, no stale report, no rebuild, finding again); with
this lane's analyzer it passes.

Tests (armed contracts, `seon.test.arm/initialize-contracts!`, clojure.test, run from the after
snapshot by `tmp/publication-work/run-tests.clj`): 7 tests, 30 assertions, 0 failures, 0 errors;
test time 863 ms; JVM wall 24.9 s. Named: the stale-entry regression, the two test-input-digest
regressions, `publication-classifies-configuration-and-loaded-dependencies`,
`changed-paths-hash-only-the-named-files-and-use-recorded-pins`,
`captured-source-is-the-analysis-and-digest-authority`,
`schema-resource-paths-are-inputs-without-a-derived-merged-entry`. The three canonical-fixture
tests in the namespace were not run: HEAD's `bin/test-fast` fails them with "The canonical
fixture needs an executing test handle" (run `a23d38a0dafb`, before the base was deleted), and
the working tree's `bin/test-fast` is deleted by realities-commit-5.

## Timings over 1 s

| operation | wall | note |
|---|---|---|
| test JVM (arm + load + 7 tests) | 24.9–33 s | defect: `docs/seon/issues/` note from `7fbe59d9a` (focused test JVM spends ~20 s before its first test) |
| cold analysis of all src+test into an empty kondo cache | 12.4–14.3 s | defect: whole-program analysis after a cache wipe; belongs to the from-zero cost note |
| measurement JVMs | 23–41 s | same JVM start class as above |

## Limits and open items

- The mtime check is a stat heuristic: bytes changed with an older mtime (e.g. a copy that
  preserves times) are not detected. Content keys need the entry to record its source digest,
  which is a clj-kondo fork change (`reference-code/clj-kondo`, gitlink → JVM restart).
- A kondo cache shared by checkouts holding different bytes for one namespace is incoherent:
  clj-kondo keys entries by namespace name. The shared cache now refuses entries from a
  snapshot inside the checkout (`::foreign`), but a snapshot trusts an unchanged main-tree
  entry. Linking one cache across snapshots needs content-keyed entries (same fork change).
- Not verified: an end-to-end leaf publication, the patch's behavior under
  `full-source-refresh!`, and `publish!`/`record-results!` after the cleanup change (their
  tests need the canonical fixture).
- `publication-input-digest!` (whole checkout) remains for `publish!` callers that supply no
  test-input digest: only the packaged install in `src/seon/artifact.clj`.

## Commits

- `ea9a4e3e9` — the five paths above. HEAD proof: `git archive ea9a4e3e9` snapshot
  (reference-code and `.clj-kondo/.cache` linked), armed run of the 7 tests: 30 assertions,
  0 failures, 0 errors, 738 ms test time, 21.5 s JVM wall; `seon.cluster`, `seon.cluster.source`
  and `seon.fn.analyzer` load.

## Follow-up (same lane, after acceptance)

### Restart refusal no longer loops (issue `restart-needed-refuses-the-publication-a-restart-should-make`)

`classify-paths` now takes the changed paths' current digests and
`seon.cluster.source/loaded-dependencies`, the `deps.edn` and gitlink digests this
JVM read when `seon.cluster.source` first loaded at launch (a `defonce`; a process
outside a checkout reports `{}`). A changed dependency refuses only when current ≠
loaded. So the publication a freshly started JVM makes of the files it loaded goes
ahead, and a running JVM whose files changed underneath it refuses.

Drill, `lane-publication-work-restart-drill-2026-09-23.clj`, on the private root below:

- `running`: `deps.edn` was appended to under the JVM, then `refresh-source! root ["deps.edn"]`
  was published. It refused `::restart-needed`, "JVM RESTART NEEDED: loaded dependencies
  changed (deps.edn)…", in 963 ms. JVM wall 18.2 s.
- `restarted`: a new JVM launched on the changed file and published the same request.
  It returned `:seon.source/built? true` in 4,411 ms. JVM wall 21.3 s. `deps.edn` was then
  restored, and the restore matched HEAD.

### Call site landed in `src/seon/cluster.clj`

The diff above landed with the new `classify-paths` arguments. The owed publication-lock
hunk also landed: `development-source-refresh!` passes `expected-head` to
`seon.issue/adopt!` (4-arity from `3c55bb0f6`).

### Shared-cache recognition

Publication always hands the analyzer an explicit `<root>/.clj-kondo/.cache`
(`seon.fn/analyzed-files`). The first cut compared that string with the default one,
so publication never treated its cache as the checkout's own, and a snapshot rebuilt a
main-tree entry (`src/seon/render/web.clj`, run 3). The analyzer now compares canonical
cache roots. Regression `the-checkout-cache-refuses-a-scratch-copy-however-its-root-is-spelled`
fails with the previous analyzer (stale `[]`) and passes with this one.

### End-to-end leaf publication on a held store

- Root: `tmp/publication-work/snap/tmp/leaf-root`, a `git archive` of `72fa85fc3` plus this
  lane's files. Built from nothing once in-process with `(cluster/refresh-source! root [] nil dir)`:
  **63,688 ms**. Of that, analysis took 15,022 ms (kondo 10,199 ms, 299 entries examined,
  0 stale), and `source/publish!` took 48,104 ms, of which 9 transactions took 35,876 ms.
  98,131 population entities. This is over 10 s and belongs with the from-zero cost note.
- Script: `lane-publication-work-leaf-2026-09-23.clj`. Contracts were not armed, and the
  machine load average was about 11.
- Leaf `src/seon/eval.clj`, one private defn appended per edit. Run 3 totals were 4,766,
  4,492 and 5,445 ms; run 1 totals were 4,676, 4,446 and 4,698 ms.
- Unchanged leaf publication: 312–333 ms. The unchanged full publication was 925 ms in run 1.

Phase breakdown of run 3, edit 1 (4,492 ms), against the reload lane's 2,886 ms leaf:

| phase | this lane | reload lane |
|---|---|---|
| store acquisition | 41 | — |
| source build: capture, classify, test-input digest, analysis | 2,064. `analyze-rows` 1,385 (kondo 11; `file-rows` 4 calls 1,272; capability contracts 473). Test-input digest reused, 0 ms; git gitlinks 0 ms | analysis 908 |
| program rows to reconciliation | 147 | indexing 520 |
| reconciliation (2 transactions) | 2,119 (`transact!` 2,048) | 576 |
| seal and branch head | 118 | — |

Cache hits and misses per leaf: kondo examined 4 entries, 0 stale, 0 rebuilt. The
test-input digest scored 1 hit and 0 reads.

The whole-repository work this lane owned is gone from the leaf. What remains is
`seon.db/carried-projection` (173–178 calls, 1.3–1.6 s, inside `file-rows` and the
transactions) and the reconciliation transaction. Both are outside this lane.

This store logs datahike's "Index lacks precomputed subtree counts (old database format)".
That may explain slower transactions than the reload lane's, which was not verified.

A five-input publication on the same root took 56,893 ms, 43,845 ms of it in the
reconciliation transaction, with 5,634 `carried-projection` calls. Filed as
`docs/seon/issues/a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction.md`.

### Remaining change (not this lane's files)

Carrying `::analyzer/cache` and the test-input counts into `:seon.source/publish-result`
needs two changes:

- `seon.fn/analyze-rows` (`src/seon/fn.clj`, receipts lane) returns the analyzer's `::cache`.
- The result key is declared in `resources/seon/schemas/seon.source.edn`.

RESET NEEDED: no.
