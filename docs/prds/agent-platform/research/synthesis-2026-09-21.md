---
type: research
status: draft (synthesis of the eight 2026-09-21 audits; the plan is written after the owner's decisions)
created: 2026-09-21
tags: [synthesis, deletion, agent-platform, seams]
---

# What the eight audits say, together

Sources, all in this directory, all read-only, no JVM run:
[datahike](deletion-audit-datahike-2026-09-21.md) ·
[malli](deletion-audit-malli-2026-09-21.md) ·
[publication and operator](deletion-audit-publication-operator-2026-09-21.md) ·
[test system](deletion-audit-test-system-2026-09-21.md) ·
[sci, turn, render](deletion-audit-sci-turn-render-2026-09-21.md) ·
[errors, issues, config](deletion-audit-errors-issues-config-2026-09-21.md) ·
[instructions and process](instructions-and-process-audit-2026-09-21.md) ·
[durable goals and rulings](durable-goals-and-rulings-2026-09-21.md).
Every number below is a count from source or a declaration read from the
tree, never a measurement taken this session.

## 1. The one disease

Every audit found the same shape: **a mirror kept beside the authority that
already holds the fact**, then machinery to keep the mirror current, then
tests to police the machinery's cost.

| Authority that already holds the fact | The mirror we built beside it | Lines |
|---|---|---|
| the database value carries its projection (`seon.db/carried-projection`) | nine separate projection derivations, four digest schemes, three cache holders | ~300 + 138 callers |
| Datahike's query cache context answers "is this read current" | three mechanisms in one function, the third re-runs the read to decide whether to re-run it | ~405 |
| clj-kondo's own namespace cache | a whole-cache sweep, a second lint run, a 130-line hook diagnostic, `build/analysis` | ~480 |
| the OS process table and the prepl | process records, advertisements, claim files, repair, phase logs | ~1,300 |
| Datahike commit ids and branch pointers | `current-src.edn`, `ready.edn`, manifests, a test-base store with its own GC | ~480 |
| the walk over stored evaluations (turn PRD §15) | 1,586 lines of hand-assembled transcript views, ruled deleted and never cut | 1,586 |
| core.async.flow `futurize` + `:compute` workload + timeout | a 704-line work launcher and a per-turn watchdog thread | ~900 |
| hyperlith's whole-view-per-batch over a dropping buffer + Datastar morph | keyframe/delta revisioning and a per-tab drain feed | ~690 |
| `seon.error/facet-keys` derives the error union | fourteen hand-copied ~100-member unions | ~630 |
| `:seon.error/operation`, `/layer`, offending value | the `diagnostic-*` restatement at 275 sites (85 % literal repeats) | ~1,600 |
| the base + facet error model (ruled D3/D12) | 799 surviving `:seon.error/kind` and `/class` stamp sites — two error models at once | (conversion) |
| the in-process run path `seon.test/check` → `run-owned` | 1,213 shell lines and a 682-line worker pool that copy the tree to other processes | ~1,900 |

## 2. The totals

| Area | src (+shell/script/hook) | test | Notes |
|---|---:|---:|---|
| Datahike seam | ~1,740 | ~1,470 | `db.clj` 4,638 → ~3,300 |
| Malli seam | ~1,700 | ~525 | schema population is clean: 3,333 keys, 0 unreferenced |
| Publication + operator | ~2,800 | ~900 | 660 lines of `fn.clj` have no production caller |
| Test system | ~4,627 | ~4,700 | corpus only ~5 % deletable; lever is 46 lines/test of repeated setup |
| SCI, turn, render | ~4,800 | ~4,500 | six deleted mechanisms have no test; seven namespaces test one turn loop |
| Errors, issues, config | ~4,100 | ~1,500 | 43 of 93 config dials are tuned constants |
| **Total** | **~19,800 of 90,162 (22 %)** | **~13,600 of 98,985 (14 %)** | |

Docs: 791 K of 868 K lines (91 %) are history and move under an archive
directory in eleven `git mv` moves. Issue notes: ~200–220 of 492 archivable;
18 of 20 sampled open notes are still live. AGENTS.md: 827 lines of product
law survive, 351 of lane choreography and 44 of anecdote go, 11 sentences
contradict the file's own laws. Memory index: 24 superseded handovers deleted,
32 product rules folded into AGENTS.md.

## 3. The 128 time escapes

Each body read (test-system audit): **72 algorithm defects, 18 process
machinery, 31 duplicates, 7 genuinely long.** Process machinery is 78 % of
the 18.2-hour declared budget; genuinely long is 0.1 %. One file grants 28
tests 30 minutes each (12.5 hours) and is the regression suite for the
launcher being deleted. A ten-minute test proves "an unchanged digest
performs zero transactions", which the law says is two commit ids compared.
Latent defect: 38 tests declare a reason without a number and silently keep
the 5 s default (`runner.clj:369-371`); hidden because declared-long tests
are excluded from ordinary selection.

## 4. What is already right (do not touch)

- The fork premise is built and measured: a Datahike branch + SCI context
  fork in **37 ms p50** (`test_support.clj:946`), down from 189 s after four
  O(program) steps were removed. One remains: the projection rebuild at
  ~4 s, the same item the Malli audit names as its largest deletion.
- `source.clj` routes every mutation through `datahike.versioning`;
  `blob.clj` sits directly on konserve; the `flock` is genuinely ours.
- The in-process run path exists and works: `seon.test/check` over
  `run-owned`, forked branch + ctx, recorded results.
- Skills: nine product skills keep; zero cite a missing file.
- `bin/issues-index` is derived from the database, not a hand list.

## 5. Fork changes (all repositories we own)

| Fork | Change | Deletes |
|---|---|---|
| datahike | admit `:db.type/any` (exists at `schema.cljc:87`); refuse it for indexed/unique | EDN-string codec + 260-line decode walker |
| datahike | pull `+default-limit+` (`pull_api.cljc:16`) stops silently cutting at 1,000 | our elision workarounds |
| datahike | EAV-granularity dependency walker beside `query-dependency-plan` (`query.cljc:2877`); export `branch-commit-id`; typed `:keep-history-mismatch`; GC dry-run; Integer→Long in `transaction.cljc` | read-currency mechanisms 2 and 3 |
| konserve | size in `k/keys` metadata | footprint scans |
| clj-kondo | `from-cache-1` (`impl/cache.clj:26-35`) skips a `:disk` entry whose file is gone (~3 lines) | 37-line sweep, second `run!`, 130-line hook diagnostic |
| malli | keyword-type entries in `default-errors` (`error.cljc:44-181`) | the parallel case table `error.clj:977-1000` |
| sci | arm contract wrappers at the base so `:sci/generation` alone discriminates the private layer | `base-bindings`/`same-program-root?` O(program) snapshot per turn fork, two ctx atoms |

## 6. Live defects found in passing (file regardless)

- `store.clj:564` vs `registry.clj:165`: connection liveness asked two ways;
  a legitimate reopen is refused during the release drain.
- `store.clj:121-124`: `file-lock-generator` takes a real OS flock under
  `tmp/` and never releases it.
- `cluster.clj` holds 25 `:seon.error/kind` stamps `error.clj` no longer
  understands.
- `render/ns.clj:412-833` clips HTML through a three-tier budget ladder
  (violates §2.4 "HTML has no presentation clipping").
- `^#:seon.test{:long …}` reader-map metadata is invisible to a
  `':seon.test/long'` grep (`source_lineage_test.clj:20,149`).
- AGENTS.md and the redesign plan cite `instrument.clj:593` for the re-arm
  comparison; the seam is `current-wrapper?` at `:844-858`.
- The uncommitted one-jvm lane draft (preserved as
  `one-jvm-lane-items-6-8-draft-2026-09-21.patch`) has the right algorithm
  for caller lint but lands as a second analysis pass and second
  transaction; do not apply as written.

## 7. The three largest gaps to the product (goals note)

1. Contracts: 16 of 3,161 private functions carry one; 352 private
   `seon.db` read-consumers uncontracted.
2. Publication: 5.4 s for a docstring edit (target < 1 s); 117 s compiling
   30 K entities at reset (~4 ms each).
3. Linking data: `:seon.test/subject` on 0 of 1,753 tests,
   `:seon.test/platform` on 0 of 1,833, responsible agents on 2 of 411
   namespaces, no `:seon.fn/file` + form span for write-back.

The archived schedules forecast **47–75 lane-days** before the first live
agent. 55 functionality targets, 89 product rulings, 36 retired directions
and 22 open decisions are preserved in the goals note.

## 8. Order that follows from the evidence

1. **Instructions first** (they are loaded by every agent, including the
   ones about to run): AGENTS.md to the laws + vocabulary + one page of
   state; docs archived; memory index pruned.
2. **The one seam deletion that unblocks everything:** the projection is
   read from the database value, never rebuilt. It is the largest Malli
   deletion, the last 4 s in the fixture fork, the per-turn snapshot in
   SCI, and a whole-population rebuild per arming pass.
3. **The first live namespace agent** (owner's choice: contract coverage)
   on a forked branch + ctx through the existing `run-owned` path, with
   the task's linked facts being the uncontracted functions of one
   namespace and the tests reaching them.
4. **Deletion slices by seam,** each net-negative, gated only by the
   reaching tests in-process; fork changes land with the deletion they
   enable; every time escape in the slice's files is converted or deleted.
5. **Profiling** (owner, 2026-09-21): samples in the armed wrapper on
   `LongAdder`, flushed on a bound as facts keyed by (symbol, definition
   digest, branch); bottlenecks a Datalog query over `:seon.fn/calls`;
   findings become tasks by ruling D2.
