---
type: landing
lane: platform-boot-heap-opus
status: "defects 2 and 3 committed (RESET NEEDED); defect 1 retainer unnamed, protocol for root"
created: 2026-09-26
---
# Landing: platform-boot-heap-opus (2026-09-26)

RESET NEEDED: default runs the 5bed888d0 archive. Neither commit is loaded
until root runs `bin/seon start --head`. The operator change takes effect on
the next launch.

## Broken or unproven, first

1. **Defect 1 (the OOM retainer) is NOT named.** The 14:20Z dump is lost. No
   test request has run on the current JVM, so I have no histogram pair
   around a request. I did not run any requests myself (LOAD RULE). The
   evidence, the leading candidate and a bounded protocol are in the new issue
   `docs/seon/issues/default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live.md`.
   The leading candidate is a timed-out member's world, retained by its live
   thread, while later requests build their own worlds (`src/seon/test.clj:287-309`,
   `:2419-2424`). This is not verified. Owner: `seon.test` (not my file).
2. The boot/start timing after the change is not measured. It needs a
   restart. The baseline is ready in 30.3 s cold at 5bed888d0 (`bin/seon status`,
   `:seon.boot/ready-ms 30276`).
3. Seen in passing, not mine: default's log panics repeatedly with "Cannot
   classify host binding: clj-kondo holds no analysis of str". That is an
   agent form using an alias the analyzer does not see. It is recorded as a
   CORE FAULT (panic) at `seon.turn/analyze-submitted` (turn.clj:3477). An agent
   mistake should be returned to the agent, not recorded as a core fault.
   Related open issue:
   `an-alias-required-by-an-earlier-form-of-a-batch-refuses-later-forms-as-unanalyzed.md`.

## Defect 3: boot cannot see a changed directory dependency (d6e8a3588)

The producer was not `unanalyzed-jars`. `stale-cache-entries`
(`src/seon/fn/analyzer.clj`) found a directory dependency's entry older than
its source (the submodule bump rewrote `reference-code/sci/src`, which the
archive symlinks). It classified the entry `::modified` and deleted it. It
rebuilt the entry only for checkout source roots. After that, every analysis
reported the namespace as unanalyzed, and boot refused. Kondo keeps no skip
mark for directories (`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:489-506`
marks only `.jar` paths). Directories are re-read from their bytes whenever
they are linted, so the right trigger is the analysis that reads the entry,
not a boot-time mark.

The fix:

- `classpath-source` now answers the directory file the system classloader
  loads for any namespace: first-party, or a directory dependency (a
  `:local/root` or a gitlib). Jar entries still answer nil, because the skip
  mark owns jars.
- A stale entry for a namespace that this run does not define is rebuilt from
  that file. This one rule replaces the old `#{::stdin ::source-absent ::foreign}`
  special case.
- A missing entry is linted from the same file in `invoke-kondo`, as
  first-party namespaces already were.

REPL proof on real data. Default JVM, scratch namespace
`platform-boot-heap-opus.analyzer` loaded from the edited file, caller
`src/seon/effect.clj` (which requires `sci.impl.utils`), shared cache:

| case | old code (`seon.fn.analyzer`) | new code |
|---|---|---|
| entry mtime set to 0 | stale `::modified`; entry **deleted**; next run `unanalyzed #{sci.impl.utils}` | stale `::modified`, `::source` = `reference-code/sci/src/sci/impl/utils.cljc`, rebuilt, entry present, unanalyzed `#{}` |
| entry deleted | unanalyzed `#{sci.impl.utils}` | entry rewritten, unanalyzed `#{}` |
| time | baseline 107 ms | modified 142 ms, missing 99 ms |

The shared cache holds a current `sci.impl.utils` entry again (rebuilt by the
new code). Contracts: `classpath-source` and `stale-cache-entries` keep their
schemas. The real outputs matched them (`[:maybe :string]`, and the `::reason`
enum member with `::source` a string).

Regression:
`seon.fn.analyzer-test/a-directory-dependency-entry-older-than-its-source-is-rebuilt-not-deleted`.
It runs over the checkout's own cache and restores the entry's bytes if it
is left missing.

## Defect 2: heap dump path (c2b757cb3)

`launch-child!` (`script/seon/operator.clj`) creates `<root>/data/heap` and
passes `-J-XX:HeapDumpPath=<canonical dir>`, so the JVM writes
`java_pid<pid>.hprof` inside it. `deps.edn` keeps
`-XX:+HeapDumpOnOutOfMemoryError` and drops the relative path, so there is
only one source of the path. Every launch prints `HEAP-DUMPS [...]` when dumps
exist. Both child-exit refusals carry `:seon.operator/heap-dump` when that
child's dump exists (`clojure` execs java, so the child's pid names it).
Regression:
`seon.operator-test/heap-dumps-are-read-from-the-managed-roots-heap-directory`.
Keeping only the newest dump is still the open
`oom-heap-dumps-accumulate-in-the-data-footprint.md`.

## Numbers

- Net lines: src +27/−25 (analyzer.clj is mostly docstrings; code lines are
  net −4), script +23/−3, deps.edn +3/−1, tests +57.
- Pre-commit static checks: contracts-compile passed (3.6 s); schema edn passed
  (1.5 s, reused).
- Live heap at idle: 1.36–1.45 GB of 10.2 GB (`tmp/platform-boot-heap-opus/histo-{0,1}.txt`).

## Root runs

```
bin/seon start --head                       # adopt d6e8a3588 + c2b757cb3; record ready-ms vs 30.3 s
ps -o args= -p <new pid> | tr ' ' '\n' | grep HeapDumpPath   # expect <root>/data/heap
bin/test-check default --test seon.fn.analyzer-test/a-directory-dependency-entry-older-than-its-source-is-rebuilt-not-deleted
bin/test-check default --test seon.operator-test/heap-dumps-are-read-from-the-managed-roots-heap-directory
bin/test-check default --ns seon.fn.analyzer-test
# Defect 1 protocol (issue file): heap-watch.sh around the host-test member, then registry-test
```

Commits: d6e8a3588 (analyzer), c2b757cb3 (operator/deps), plus this note and
the issue. Released paths: src/seon/fn/analyzer.clj,
test/seon/fn/analyzer_test.clj, script/seon/operator.clj, deps.edn,
test/seon/operator_test.clj.
