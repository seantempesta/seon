---
type: evidence
status: landed (71f14d1d4)
---
# Lane fix-boot-dependency-analysis (2026-09-25)

Boot now fills a missing clj-kondo dependency analysis in its own JVM before publication reads it
(`seon.fn.analyzer/ensure-dependency-analysis!`, called from `seon.cluster.boot/stand-boot-layers!`).
Issue: [boot-from-zero-cannot-fill-its-own-dependency-analysis](../../../seon/issues/boot-from-zero-cannot-fill-its-own-dependency-analysis.md).

## Still broken or unproven

- A new `:local/root` directory dependency carries no kondo skip mark, so this check can't see that it's missing.
  Calls into it still refuse by name with `seon.fn/dependency-analysis-command`, as before. Jars, which make up
  the whole from-zero case, are covered.
- **A from-zero boot now pays about 26 s to fill the analysis.** That's one clj-kondo run over 223 jars and 34
  directories, sized to the classpath, and paid once per empty cache. It is over the ten-second law, so it needs
  owner authorization and an issue. Root decides whether to accept it or to record it on the open issue. Ordinary
  boots pay only the check: 6.4 ms on default, 10.8 ms on the snapshot.
- **Stopping the scratch root faulted, in `seon.cluster.boot/stop!`.** Its `(Object.)` marker reaches `seon.db/loaded-builder`.
  This is outside my scope; filed as
  `docs/seon/issues/stop-marks-running-instances-with-an-object-that-projection-readers-refuse.md`.
- **RESET NEEDED: no.** Default was never stopped or nuked, and its cache already holds every jar.

## Choice

- **Option A, nuke keeps the cache: rejected.** The owner rule is "nuke nukes everything including all caches".
  A git archive or a fresh root also starts with an empty cache, and option A leaves that case broken, so it
  can't pass the regression.
- **Option B, boot fills the cache when it's missing: chosen.** It costs nothing on an ordinary boot. The check reads the
  skip directory: 6.4 ms warm on default (257 classpath entries, 223 jars, 0 unmarked). The fill cost is paid
  only when the cache is empty; it's measured below.
- **Detection.** kondo names a skip mark `<jar-name>.<config-hash>`, and the mark holds the last path linted under that
  name (`reference-code/clj-kondo` 57252e07, `src/clj_kondo/impl/core.clj:489-498,603-607`). So
  `com/lambdaisland/glogi` and `lambdaisland/glogi` share one mark, and a check by path content always found
  one jar missing. That would have meant a full re-analysis at every boot. The check therefore compares jar names only.

## Evidence

- Default, from the checkout: the path-content check reported 1 unmarked jar (glogi, the collision above).
  The name check reports `[]`. An empty cache reports all 223 jars. Both results match `[:vector :string]`.
- Scratch-namespace copy of `unanalyzed-jars` on the regression's inputs: an empty cache lists all 3 jars. One glogi
  mark covers both glogi coordinates, leaving `[cheshire]`. Inputs and outputs validate against the declared schema.
- **Scratch root, authorized for this lane.** A `git archive 71f14d1d4` snapshot booted as its own root with an empty
  `.clj-kondo/.cache` and an empty store. Each `reference-code/*` was linked to the checkout's copy, `target` was
  linked, and `dependency-pins.txt` was written from the commit's tree.
- **First attempt, archive under the scratchpad.** It passed the new fill (lock file at 23:40:19Z, 222 skip marks at
  23:40:41Z), then failed with `Git could not enumerate test inputs`. That failure comes from
  `seon.test.cache/git-output`: outside the checkout there's no `.git`, which the operator's `data/source`
  archives have. It's a snapshot-setup fault, not this defect. I stopped that JVM with its root's `bin/seon down`.
- **Second attempt, same archive under `tmp/lane-fix-boot-dep/snap`: `exit=0`.**
  - Timeline: store created at 23:41:45.0Z. kondo wrote its lock at 23:41:48 and 222 skip marks (223 jars) at 23:42:11.
  - `:seon.boot/missing-layers []`, `ready-ms 121,875`, problems `{}`, health `observed`.
  - About 95 s of the ready time is the dev class cache miss (`:no-matching-cache`, from-source compile) plus the
    first full publication, not the fill. The issue's warm-class-cache from-zero boot was 32,980 ms, so expect
    about 59 s from zero with the fill.
  - After boot, `(seon.fn.analyzer/ensure-dependency-analysis!)` at that root's REPL returned `nil` in 10.8 ms.
- **Cleanup.** I stopped the JVM with the snapshot's own `bin/seon down` and removed the snapshot.
  The checkout's `.clj-kondo` is unchanged.

## Changes

- `src/seon/fn/analyzer.clj`: `dependency-entries` is split out of `analyze-dependencies!`, and
  `unanalyzed-jars` and `ensure-dependency-analysis!` are new.
- `src/seon/cluster/boot.clj`: one call before publication.
- `test/seon/fn/analyzer_test.clj`: class regression `dependency-analysis-is-missing-exactly-for-unmarked-jars`.
- Net lines: +74 / −17 (source +40 net, test +20). Nothing is duplicated to delete: `analyze-dependencies!` keeps
  its one classpath enumeration, and `seon.fn`'s refusal stays as the diagnostic for directory dependencies.
- Commit: 71f14d1d4.

## Root runs

- `bin/test-check default --test seon.fn.analyzer-test/dependency-analysis-is-missing-exactly-for-unmarked-jars`
- `bin/test-check default --ns seon.fn.analyzer-test`
- Adopt `src/seon/fn/analyzer.clj` and `src/seon/cluster/boot.clj` at the next checkpoint. Boot runs the fill only at
  start, so default sees no behaviour change until its next start (0 unmarked jars there).
