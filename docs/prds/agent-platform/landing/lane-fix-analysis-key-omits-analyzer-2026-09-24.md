# Lane fix-analysis-key-omits-analyzer (2026-09-24)

Stored program rows derive from two inputs: a file's bytes, and the analyzer
that was loaded when the file was published. Before this change, publication
keyed the rows only by the bytes (`:seon.fn.file/digest`).

The source seal now also records `:seon.source/analyzer-digest`:

- The value is the `seon.id/digest` of the sorted map `{sym → :seon.program/definition-digest}`.
- The map covers the declarations reached from `seon.fn/analyze-rows` through
  stored `:seon.fn/calls`.
- It is read from the commit this JVM loaded. With a development cluster that
  is its adopted commit; otherwise it is the published head.
- `full-source-refresh!` compares the loaded digest with the seal. If they
  differ, it classifies the publication as `:all`, and it does so before the
  unchanged-inputs shortcut.
- `publish!` treats a different analyzer as changed and writes the digest into
  the seal.

Root ruled for option A (reviewed design below). The ledger granted
`seon.source.edn` and `cluster/source.clj` to this lane.

## Still broken

- **A full reanalysis takes 24.9 s**, which exceeds the 10-second law. Issue:
  `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md`.
  - `full-source-refresh!` took 23.9 s, `seon.fn/analyze-rows` 13.2 s,
    `published-index-rows` 22.0 s and `with-declarations` ×866,175.
  - This now happens once after every edit within the analyzer's reach.
- **A callee's change of macro kind leaves callers in other files stale.**
  Found by reading only. Issue:
  `docs/seon/issues/a-callee-macro-kind-change-leaves-callers-host-bound-stale.md`.
- **The loaded commit is approximate without a development cluster.** It
  falls back to the published head. A JVM that boots and publishes an analyzer
  change records the new head's digest, although its loaded code is the files
  on disk, which are equal to that head. So the approximation errs only toward
  one extra full reanalysis, never toward silence.
- **The analyzer reach follows stored calls only.** An analyzing function that
  is reached only through a Var passed as data, with no `:seon.fn/calls` edge,
  is outside the digest. The walk from `analyze-rows` reached 338 first-party
  declarations. I did not check it for Var-as-data gaps.
- **Save-gate reds were foreign.** Each hook gate on these files was red. The
  failures are in agent/bootstrap fixture writes refused on
  `:seon.program/definition-digest`, MCP value-window tests, `my.message` and
  `my.program-mutation`. None of them names publication or `analyzer`.
- **Two reds in `seon.cluster-test` are unrelated but unverified.**
  `bin/test-check default --ns seon.cluster-test` (run affc77556ef8) shows
  `a-commit-tx-request-without-the-evidence-bound-names-the-key` (reads the
  live default's open faults) and `a-core-fault-commits-with-the-dial...`
  (NPE on a Var). Neither touches publication. I did not verify whether they
  were red before this change.

## Evidence (default pid 99265)

- Before the fix, the analyzer-changed state was: seal installed with no
  analyzer value, loaded digest `acd137c1…4735`, and `seon.cluster/serve!`
  already true (its file was republished). Every other stale row was false.
- The one-time full reanalysis was
  `(refresh-source! root ["src/seon/id.clj"] "default")`:
  - 24,876 ms, built, commit `6ab575a7-bc1b-57bd-924f-0732e7a8bfa0`;
  - reloaded `seon.render.web`, `seon.cluster.agent-test`,
    `seon.cluster.turn-test`, `seon.render.web-test` and `seon.turn-loop-test`.
- **All 11 stale rows converged.** Every one is `:seon.fn/host-bound? true` on
  both the published head and default's cluster database:
  - the six test rows,
  - `seon.cluster/serve!` and `seon.cluster/warn-low-space!`,
  - `seon.render.web/failed-page-result` and `seon.render.web/unreportable-page-result`,
  - `seon.render.retained-test/replace-renderer!`.

  The seal now carries `acd137c1…4735`.
- **After an unchanged analyzer, nothing was reanalyzed.** Later unchanged
  publications returned `built? false`. A later edit to `cluster.clj`, which is
  outside the reach, published incrementally. So did `cluster_test.clj`
  (2,821 ms, only `seon.cluster-test` reloaded).
- **Unchanged-publication cost** (5 runs, warm, same probe):
  - Before: 229 ms (264, 229, 229, 230, 228).
  - After: 249 ms (337 cold, then 249, 249, 249). That is +20 ms, or +9%.
  - `loaded-analyzer-digest` costs about 34 ms per call on the armed profile,
    of which `analyzer-digest` is about 25 ms.
  - The first draft computed the digest twice (262 ms, +14%); I fixed that
    before committing.
- Regression `seon.cluster-test/the-analyzer-digest-follows-the-analyzing-declarations-only`
  passed (run 1db3f58c43bf, 3 assertions, 2,546 ms). It checks three cases on
  an isolated branch:
  - an unchanged analyzer gives an equal digest;
  - a new digest on `seon.cluster/serve!`, outside the reach, gives an equal digest;
  - a new digest on `seon.fn/host-bound-callers` gives a different digest.
- `bin/test-check default --ns seon.cluster.publication-inputs-test` passed:
  run 3ec6fb871bc1, 45 of 45; one long test was excluded by declaration.
- Schema publication: the hook published `seon.source.edn` first (5.1 s,
  adoption identities included `:seon.source/analyzer-digest`), then
  `source.clj`, then `cluster.clj`. Each edit reached default through the hook,
  and its new code was reloaded.

## Change

- `resources/seon/schemas/seon.source.edn` (+3): the
  `:seon.source/analyzer-digest` attribute, and an optional member of the
  publish request.
- `src/seon/cluster/source.clj` (+19/−8):
  - the seal attribute joins `source-attributes`;
  - a different analyzer is not `unchanged?` (via `cond`);
  - the seal writes the value.
- `src/seon/cluster.clj` (+50/−6):
  - `analyzer-digest` and `loaded-analyzer-digest`;
  - `refresh-source!` computes the digest once and passes it in the roots map;
  - in `full-source-refresh!`, `analyzer-changed?` makes the publication
    `:all` and skips the shortcut, and the value is passed to `publish!`.
- `test/seon/cluster_test.clj` (+18): the regression.

Net source and test lines: +90/−14. Commit: see git log (this landing).
RESET NEEDED: no. No branches, background shells or scratch namespaces remain
(`tmp.fix-analysis-key` was removed).
