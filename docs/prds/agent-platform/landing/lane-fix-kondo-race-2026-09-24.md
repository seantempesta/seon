---
type: landing
status: landed
lane: fix-kondo-race
created: 2026-09-24
report: docs/research/agent-platform/fail-loud-dev-resilient-prod-2026-09-24.md (table rows 8 and 15)
---

# Lane fix-kondo-race — 2026-09-24

The analyzer's cache check already rebuilds stale clj-kondo entries and reruns once
(`seon.fn.analyzer/invoke-kondo`). An entry still stale after that rebuild means a
concurrent source edit. That case now throws the existing declared analysis
source-change refusal instead of a raw `ex-info`. No new schema, retry or machinery.

## Still broken or not installed

- **Not installed on default.** pid 90471 runs the committed archive
  `data/source/ce7384…` with hook publication off, so this edit cannot reach the
  running JVM. **RESET NEEDED** (or `start --head`) to install it.
- **The regression did not run through `seon.test/run`.** It ran as plain
  `clojure.test` in a `git archive` snapshot (see Evidence). `bin/test-check default`
  would have run the archive's code, which does not have this regression.
- **Row 15 (`fn.clj:127`) is not fixed.** `fn.clj` is held by another lane; the
  matching fix is described below.
- **Span refusal gap (not mine).** `seon.fn/span-refused!` (`fn.clj:146`) is the same
  race class. It has `:seon.cluster.source/phase :analysis` but no
  `:seon.cluster.boot/disposition`, so `request!` still records it as a core fault.
  Whoever owns `fn.clj` should add the member there too, or the cluster owner should
  classify any `:seon.cluster.source/phase` refusal at the publication path.

## Change

`src/seon/fn/analyzer.clj` `invoke-kondo`: the remaining-stale throw now carries these members:
- `:seon.fn/index-refused true`: the `:seon.fn/index-refused-error` schema,
  `resources/seon/schemas/seon.fn.edn:240`, which the analysis-span refusal also uses
- `:seon.cluster.source/phase :analysis`: the declared enum,
  `seon.cluster.source.edn:1`, added by 320c73adc for exactly this class
- `:seon.error/message`
- `:seon.source/changed-paths`: each remaining entry's canonical source path, or its
  cache file when the source is gone
- `:seon.cluster.boot/disposition :refused`: the member `seon.cluster.boot/request!`
  already reads (`boot.clj:662`) to return a declared refusal and not call `fault/fault!`
- the old `::stale` and `::lint` evidence, kept as it was

**The caller path.** `analyze` and the `seon.fn` indexers (`fn.clj:2286,2364`) let the
throw through. `source/publish!` catches it, retires the scratch branch, and
`stale-publication-error` rethrows the identical Throwable (`source.clj:419-428,694-696`).
`refresh-source!` reaches `request!`, which returns it as `:refused` and records no
fault. There is no retry: dcc15e5b3 removed the one-retry wrapper deliberately. The
publication refuses by name, and the next edit's publication converges.

## Evidence

- **Before (live JVM, installed HEAD code, scratch namespace `scratch.kondo-race`).**
  The callee's mtime was set one hour ahead. The raced `invoke-kondo` threw
  `"clj-kondo cache entries changed again during analysis."` in 11.06 ms. Its data was
  only `::lint`/`::stale`: no declared member, phase or disposition. Probe dir removed.
- **Caller (live JVM, read-only).** The new ex-data validates against
  `:seon.fn/index-refused-error` under the carried default projection. Without
  `:seon.fn/index-refused` it does not validate. `stale-publication-error` rethrows the
  identical object, disposition `:refused` intact: `[true true false true]`.
- **Snapshot.** `git archive` of HEAD `43addd46c` plus this diff at `tmp/fix-kondo-race/snap`,
  with `reference-code` and `.clj-kondo/.cache` symlinked, run with
  `clojure -M:test` under `seon.schema/call-with-projection` of the packaged projection:
  - `seon.fn.analyzer-test`: **9 tests, 45 assertions, 0 failures, 0 errors**. The new
    test took 620.7 ms (two real kondo analyses, cold JVM).
  - Falsification: the same test against HEAD's `analyzer.clj` gives **4 failures**
    (schema, phase, disposition, paths).
  - Process: 46 s wall, 3.67 GB max RSS for the whole JVM run.
- **Cost.** The non-race path is unchanged. The race path adds one vector
  construction over the remaining entries.

## Row 15 matching fix (for the `fn.clj` owner, not applied)

`seon.fn/source-context` reads a listed file with `Files/readAllBytes` (`fn.clj:127`).
When the file is deleted between listing and reading, `NoSuchFileException` escapes as a
core fault (via `full-source-refresh!`). The fix is to catch only
`java.nio.file.NoSuchFileException` there and throw the same shape:
`{:seon.fn/index-refused true :seon.cluster.source/phase :analysis
:seon.cluster.boot/disposition :refused :seon.error/message … :seon.source/changed-paths [path]}`,
with the exception as its cause. This is about 6 lines. The alternative is to treat
the file as removed, but the caller's listing is the owner of removal, so the refusal is
the smaller and more honest fix.

## Sizes

- `src/seon/fn/analyzer.clj`: +15/−3
- `test/seon/fn/analyzer_test.clj`: +25 (one class regression,
  `a-source-changed-during-analysis-is-the-declared-refusal`)
- Net source: +12 lines

## Verification limits

- The race was reproduced deterministically with a future mtime, not a real concurrent
  editor. The stale rule (`::modified` = source mtime after entry mtime) is the same one
  that fires.
- `request!` classification was verified by reading `boot.clj:662` and by the ex-data
  check, not by a full operator request.
