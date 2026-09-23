---
type: landing
status: landed; command works and keeps store + caches; NOT seconds (holders' phases dominate)
created: 2026-09-23
---

# Lane move-to-head (fix schedule #1b, 2026-09-23)

Owner: "isn't a nuke more disruptive?" / "Then load head. Don't keep a broken
system running."

## Command: `bin/seon start --head`

Chosen from the existing grammar because `start` is the verb that launches a
root's JVM and `--head` names the program source; a running JVM's program
cannot change without a new JVM, so `--head` always replaces it (`stop`/`reset`
keep a JVM; `nuke` wipes). It takes no cluster name other than `default`.
`seon.operator/move-to-head!` (drills pass `:seon.source/revision`):

1. Before anything stops: `committed-source!` (the nuke's archive under
   `<root>/data/source/<sha>`), `share-caches!` links the archive's `target`
   and `.clj-kondo/.cache` to the checkout's and writes `dependency-pins.txt`
   (git's own stage bytes, so `dependency-digest` keys the class cache in a
   non-work-tree), and `clojure -Spath` fills the archive's `.cpcache`.
2. `down!` of the root's JVMs (graceful, then exact-identity terminate).
3. `launch-child!` from the archive WITH the class cache; boot resumes on the
   same store (`changed-source-paths`: publish only differing files).
4. `init --dev default` adopts that publication.
5. `prune-archives!`: deletes this root's archives and archived pins that no
   live process names (`-Dseon.repository.root=`); never follows a link.
6. No readiness, or a refused adoption: the replacement is terminated by exact
   identity and the full cause returned (exit 1). The store and caches stay.

Hook publication: the result and `bin/seon status` carry
`:seon.operator/source-root`, `:seon.operator/hook-publication :off` + reason;
the CLI prints `HOOK-PUBLICATION off: ...`. The by-name refusal belongs to
`refresh-source!` (cluster.clj, held by wrapper-profiling): exact hunk in
[the issue](../../../seon/issues/an-archive-booted-default-relativizes-hook-paths-outside-its-source.md),
probed in the scratch JVM (working-tree path true, archive/relative false).

`committed-source!` (nuke too): one `git status --porcelain=v2
--ignore-submodules=untracked` replaces 20 per-submodule pairs; a divergent pin
is archived once per root under `data/source/pins/<path>/<pin>` and linked;
tools.deps prep runs only while a declared `:deps/prep-lib :ensure` is absent.
Parent vs self (hot-path rule): 7,972 ms (babashka-process archived + 2,970 ms
prep with nothing to prepare) -> 1,386 ms (nuke source phase) / 2,110 ms (pin
reused); first build of a divergent datahike pin 12,387 ms, once.

## Proof (scratch root `tmp/mth-root`, deleted after; no holder)

| Run | Program | Wall | Phases (ms) |
|---|---|---|---|
| nuke `--root tmp/mth-root nuke --force` | HEAD `369c6e5d2` | 149,150 | source 1,386; launch 147,102; ready 114,967 |
| move drill 1 | `ea219a287` = `369c6e5d2` + one comment line in `src/seon/await.clj` (commit-tree, no branch) | 54,720 | source 3,241; down 830; launch 47,953; ready 14,501; adopt 2,131 **refused** ([issue](../../../seon/issues/adoption-refuses-a-publication-that-changes-no-definition.md)); JVM terminated |
| move drill 2 | `08a3227df` = drill 1 + one docstring | 85,670 | source 3,916; down 285; launch 74,883; ready 28,909; adopt 5,007; pruned 2 archives |
| CLI `start --head` | HEAD `ddd9f8edf` (8 files: db.clj, schema.clj, ...) | 185,420 | source 16,916 (datahike pin inline, pre-fix); down 862; launch 143,822; ready 91,540; adopt 22,934 |
| CLI `start --head` | HEAD `e4f280e81` (9 files: cluster.clj, instrument.clj, 2 schema resources, ...) | 152,750 | archive 9,653 (pin once); share 51; classpath 1,163; down 809; launch 117,817; ready 83,873; adopt 22,393 |

Store kept: 795 files / 119.9 MB before the moves, 823 / 127.4 MB after; an error
occurrence recorded at 00:44:01Z (before drill 2) is in drill 2's readiness
problems. Caches kept: repository `target/` and `.clj-kondo/.cache` intact; the
archive links resolved to them (`ls -la`). Class cache: miss every run,
`:no-matching-cache` with a computable digest `e2ab6c57…` (pins file works);
filling it from the archive refused after 57.4 s on the committed
babashka-process pin ([issue](../../../seon/issues/vendored-babashka-process-carries-a-local-aot-patch.md));
that attempt left `target/dev-dependency-cache-result.edn` (64 KB) in the shared
target. Probes in the scratch JVM (`tmp/mth-evidence/probe*.clj`):
`changed-source-paths` 1,729 ms with none changed; one-file boot-shaped
publication compare 1,870 + publish 7,040 ms, again 1,808 + 6,507 ms.

Regression `script/seon/operator_move_test.clj` (bb, with the nuke test):
5 tests, 28 assertions, 0 failures, 0.3 s — pins bytes equal git's
`read-tree`+`ls-files --stage`; store untouched and both cache links made then
kept; the class and analyzer caches read through the archive; pruning keeps the
running and a live-held archive and linked pins, removes the rest, follows no
link; `--head` only on `start`. Contracts touched: 11 forms, all built-in, compiled
with `malli.core/schema` in default (0 ms; script/ is not a published source
root, so the packaged projection does not arm them).

## TIMINGS (over 1 s)

| Operation | ms | Justification / defect |
|---|---|---|
| archive build, new sha, pins linked | 2,110 | 162 MB committed tree extraction (1.6 s) beside the 0.5 s git census; an APFS clone measured 1,096 ms, no better |
| divergent pin, first time in a root | 9,653–12,387 | datahike `compile-java` prep, once per pin per root |
| `clojure -Spath` in a new archive | 1,163–1,187 | tools.deps classpath resolution, once per archive, before the stop |
| JVM start to boot entry | 33,000–52,000 | DEFECT: no class cache from committed inputs + first-party compile |
| boot ready (resume + publication) | 14,501–91,540 | DEFECT: publication cost class; `changed-source-paths` 1.7 s |
| adoption | 2,131–22,934 | DEFECT: index + redundant reload |
| nuke (comparison) | 149,150 | from-zero class |
| dev-cache fill from archive | 57,380 refused | DEFECT (babashka-process) |
| moves total | 54,720–185,420 | DEFECT: [move-to-head-takes-a-minute-or-more-not-seconds](../../../seon/issues/move-to-head-takes-a-minute-or-more-not-seconds.md) |

The move is therefore NOT seconds and can be slower than a nuke for a
many-file diff; what it guarantees is the store, the data and every cache.

## Commits and paths

- `ddd9f8edf` — `script/seon/operator.clj`, `script/seon/operator_move_test.clj`.
- `e4f280e81` — same paths (pins once per root, sub-phases).
- this note; issues `adoption-refuses-a-publication-that-changes-no-definition.md`,
  `move-to-head-takes-a-minute-or-more-not-seconds.md` (new),
  `an-archive-booted-default-relativizes-hook-paths-outside-its-source.md`,
  `vendored-babashka-process-carries-a-local-aot-patch.md` (extended).

Hunks for holders: cluster.clj (wrapper-profiling) — outside-path refusal and
the zero-arming adoption guard, both exact in their issues. `bin/seon` needed no
change. Limits: `default` was not moved (lane rule); a move of a root with
sibling clusters restarts only `default`. Incident: a mistyped `git config`
read set `.git/config` `diff.ignoreSubmodules = status.submoduleSummary` for
about a minute; unset at once, verified absent. RESET NEEDED: no.
