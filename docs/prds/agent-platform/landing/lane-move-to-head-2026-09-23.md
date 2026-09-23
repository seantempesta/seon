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

## Follow-up: a failed move stays revertible (P0 0j)

Incident (orchestrator, `tmp/orchestrator/move/`): `start --head` of `default` to
`ad41853a0` failed readiness after its boot had published HEAD's schema resources
onto `:current-src` and `:cluster-default`; a move back to `bfcce39ad` then refused
at `changed-source-paths` ("Predicate seon.profile/snapshot? has no admitted
callable"), and `default` had to be nuked.

Fix, option (b) of the follow-up (option (a), a candidate branch the boot publishes
onto, needs `src/seon/cluster/boot.clj`, held by m9-adoption):

- **Capture.** A move's child, before its boot writes, reads every roster branch's
  head (`seon.cluster.registry/roster`, `branch-commit-id`) and sends it to the
  operator as the callback's second line (`head-capture-form`). The store it opened
  stays held, so boot's own `acquire-root-store!` reuses it (holder count) and the
  store is opened once, as before; the launch form drops that holder when boot
  answers.
- **Restore.** No readiness, or a refused adoption: through the failed child's REPL
  (it holds the flock), every branch whose head moved is put back on its EXACT
  captured commit: every connection this JVM holds to it is released
  (`datahike.connections/*connections*`, `connections.cljc:3`; `release` all,
  `connector.cljc:468`), the branch is unlinked (`versioning.cljc:279`) and branched
  again from the captured commit (`versioning.cljc:212`, which stores that commit's
  own record as the head). A branch the attempt created is unlinked. `force-branch!`
  (`versioning.cljc:323`) was tried first and rejected: it writes a NEW commit, so
  the older program's adoption saw `prior ≠ published` and refused (zero-arming
  [issue](../../../seon/issues/adoption-refuses-a-publication-that-changes-no-definition.md)).
  The result is `:restored` only when every head equals its captured commit again;
  an exited child answers `:unknown` with the captured heads.
- **Found on the way, fixed:** a nuke archive carried no `dependency-pins.txt`, so
  `git ls-files` inside it answered the ENCLOSING checkout's index and the nuke
  published the checkout's gitlink pins (datahike, bumped in `cc1aa4f00`), not the
  commit's; every archive now records its commit's pins at build time.
- **Found on the way, fixed:** linking an archive's `.clj-kondo/.cache` to the
  checkout's was wrong. clj-kondo keys an entry by namespace name only, so it is
  valid only for the bytes last linted; the checkout's cache follows the working
  tree. A scratch move to `ad41853a0` refused publication on `Unresolved var:
  cache/worker-checkout!` (entries of the working tree's `seon.test.cache`). Each
  root now has one analyzer cache, `data/source/analysis-cache`, seeded once by
  copy from the replaced JVM's own cache (the program the store published), linked
  from every archive of that root. **The checkout's `.clj-kondo/.cache` received
  entries linted from committed archives (default's `start --head` runs and this
  lane's scratch runs, which linked it): its owner (publication-work) should
  re-lint the working tree into it.**

### Proof (scratch root `tmp/mth2-root`, deleted after; no holder)

Evidence `tmp/mth-evidence/revertible/` (`round1`, `round2` are the superseded
`force-branch!` and pre-pins attempts).

| Step | Program | Result | Wall | Phases (ms) |
|---|---|---|---|---|
| 1 | nuke at `bfcce39ad` | ready | 227,530 | launch 214,163; ready 168,413 |
| 2 | move to `ad41853a0` | publishes, adoption refused (`write-render-target-error` arity); heads restored exactly: `:current-src` `6ab32d5e…`→`6ab32cba…`, `:cluster-default` `6ab32d6a…`→`6ab32cd7…` = captured | 163,540 | source 5,110; down 3,602; launch 140,946; ready 107,272; adopt 12,769; restore 386 |
| 3 | move back to `bfcce39ad` | **ready, adoption converged** (`6ab32cba…` = captured `:current-src`); `ad41853a0` archive pruned | 49,420 | launch 45,274; ready 10,497; adopt 1,938 |
| 4 | move to `f96dd9c0e` (= `bfcce39ad` + one docstring) | ready, **adopted** `6ab32e00…` | 90,590 | launch 79,708; ready 31,751; adopt 5,287 |
| 7 | final code: move to `bfcce39ad` | ready, adopted | 59,330 | launch 50,907; ready 18,311; adopt 3,316; capture 31,315 of which store read 118 |
| 8 | final code: move to `ad41853a0` | refused adoption; restored; heads-after = captured | 146,310 | ready 99,019; adopt 10,058; restore 135 |
| 9 | final code: move back to `bfcce39ad` | ready, adoption converged on the captured commit | 46,460 | launch 44,115; ready 10,123; adopt 1,405 |

Store unchanged except the failed attempt: the `:db` head is the same commit
(`6ab32c23…`) in every capture; `:unlinked []` (the failed attempts created no
branch); the failed commits are unreachable for the retention sweep.

### Hot-path timing (successful move, parent `e4f280e81` vs this)

The success path adds one store read in the child: 118–135 ms (`store-read-ms`),
which is the store open plus three head reads; the store stays held into boot, so
boot's own open (measured 119 ms by lane resume-in-seconds) becomes a holder
increment: net ≈ 0 by construction. `capture-ms` (31–47 s) is the `require` of
`seon.cluster` that boot performs next anyway (dependency class miss). Whole
moves on the same one-docstring shape: parent 85,670 ms (drill 2 above, load 17)
vs this 90,590 / 59,330 ms (load 23 / 15): machine load dominates; no slowdown is
attributable to the change. A parent run from an extract was not possible: the
operator derives its checkout from its own file location and refuses outside a Git
top level.

| Operation | ms | Justification / defect |
|---|---|---|
| head capture store read | 118–135 | store open + roster + 3 head records; replaces boot's own open |
| head restore | 135–386 | per moved branch: release, unlink, branch (2 branches) |
| nuke at an older commit | 196,320–227,530 | DEFECT, from-zero class (lane-owned issue) |
| failed move | 146,310–163,540 | DEFECT: 99–107 s boot publication of HEAD's diff ([move-to-head issue](../../../seon/issues/move-to-head-takes-a-minute-or-more-not-seconds.md)) |
| move back / success moves | 46,460–90,590 | DEFECT, same issue: JVM start 44–80 s with no class cache |

Commits: this follow-up's commit (paths `script/seon/operator.clj`,
`script/seon/operator_move_test.clj`, this note). Regression: 7 tests, 39
assertions, 0 failures (bb, 0.4 s), adding the per-root analysis cache and the
capture/restore form structure (exact-commit branch!, never force-branch!).
Limit: the restore runs only in a live failed child; a child that exits after
writing leaves the heads captured in the result for a manual restore.
