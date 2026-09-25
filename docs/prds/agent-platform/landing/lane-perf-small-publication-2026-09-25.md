---
type: landing
status: measured; producer fix handed to root as a patch (file not owned)
created: 2026-09-25
tags: [agent-platform, publication, performance, measured]
---

# Lane perf-small-publication (2026-09-25)

A small `:selected` publication's 14.9 s is mostly (63 %) relinting 180 caller files that did not change.
The cause is one producer, `seon.program/arity-row` (`program.cljc:995-1041`). It writes
`:seon.fn.arity/arguments []` for every zero-argument arity. Storage keeps no empty component set,
so a stored zero-argument arity never has the key. Whenever a file holding such a function is
republished, the rows compare unequal, the function counts as a signature change, and
`seon.fn/caller-files` selects every caller file. `program.cljc` and `program_test.clj` are not
this lane's files, so the fix is a ready patch for root. **No production code changed in this lane.**

## Broken first

- **Not fixed, needs a file this lane does not own:** `src/seon/program.cljc` (free in the ledger) plus
  its regression in `test/seon/program_test.clj`. The patch is `tmp/perf-small-publication/program-empty-arguments.patch`
  (+14/−1; `git apply --check` passes). It omits the key when `arguments` is empty, which follows the
  "absence is no key" law. The new deftest `zero-argument-arity-states-no-empty-argument-set` fails on HEAD today:
  on default, `program/contract-facts` for `(defn answer [] 1)` returns `{:argument-count 0, :arguments []}`.
- The fix probably changes the analyzer digest, because `seon.program` has 16 functions inside the reach. The
  first publication after it lands would then be classified `:all`, a full reanalysis of 16-25 s paid once.
  Root should expect that and read its classification.
- Still over one second after the fix (projected ≈ 5.5 s), and outside this lane's files:
  - The reconciliation transaction takes 3.47 s. Of that, 1.34 s is `seon.db/write-report-error` →
    `report-arity-comparison` → `arity-base`. On the scratch branch's first `:db-before` it misses its cache,
    so it derives the whole program's arity comparison, which reads every call edge (`db.clj:3738-3772`, owner:
    cut-l2's db.clj). Another 0.28 s is `reconcile-tx-in`. The remainder is the Datahike transaction and its
    ~3,000 `pull-call`s.
  - `seon.fn/analyze-rows` on the two changed files takes 978 ms, and `invoke-kondo` is 3.9 s inclusive across
    both analyses in the window.
- Analyzer reach (task 3): I found no honest derivation that can be made without a ruling. The options are
  below. Nothing was implemented.

## Evidence (default, pid 67823, JVM mode, session `perf-small-publication`, namespace `perf-small-publication.probe`)

Probe: `tmp/perf-small-publication/probe-change.clj`. It is the `:selected` path of `full-source-refresh!` +
`source/publish!` with the same arguments, run as phases. `populate-source!` runs on a scratch branch
forked from `current-src` commit 6ab5c6d8… (`registry/branch!` → `store/open-branch!` → retire). There is
no seal, no `force-branch!` and no adoption, so default and `current-src` are unchanged. The inputs are the
bytes of the real 2-file change, reversed: `git show a90c3005e^:` of `src/seon/db.clj` and
`test/seon/cluster/program_change_test.clj`, against the published head that holds a90c3005e's bytes.
This is the diff behind the reported 14,617 ms `publish!`. A control run, `probe.clj`, used the current bytes
(no row change).

| phase | control (bytes unchanged) ms | real 2-file change ms |
|---|---:|---:|
| `loaded-analyzer-digest` (whole call-edge walk) | 160 | 103 |
| `declaration-projection` (refresh-source!) | 129 | 95 |
| capture + stored/all path digests + test-input + aggregate + source db | 12 | 11 |
| `analyze-rows` (the 2 changed files) | 1,340 | 978 |
| previous findings | 13 | 6 |
| `declaration-projection` again (publish! `source.clj:597`) | 155 | 111 |
| open scratch branch | 13 | 10 |
| `populate-source!` | 561 | **13,310** |
| — contract projection + 170 contract rows | | 322 |
| — reconciliation transaction (175 identities) | | 3,466 |
| — "analysis callers: 179 files" relint | | **9,439** |
| total elapsed (profile) | 2,603 | **14,889** |

The real-change run reproduces the reported number: 14,617 ms `publish!`, and `full-source-refresh!` 16,352 ms.
The control shows that the fixed overhead of a small publication is about 0.6 s outside analysis. The
explain window (`seon.profile/explain`, limit 60) is stored as blob `762745ab…`: `analyzed-artifacts`
29,051 ms x6, `populate-source!` 13,310, `index!` 13,308, `transact!` 3,163, `write-report-error` 1,344,
`arity-base` 1,197, `with-declarations` 1,766 x7,085, `canonical-value-string` 1,122 x806,847. Its
concurrent-work caveat applies: `with-database` cells from a test ran during the control.

What the relint is proportional to: the callers of the "changed" signatures. Read-only diffs of the desired
rows against the stored rows (normalized with `seon.fn/normalized-index-row`, compared with
`program/changed-attributes`) show the following:

- Four rows change `:seon.fn/arities`: `seon.db/db`, `commit-id`, `committed-value-identity` and `history`.
  Each is a zero-argument arity whose only difference is `:arguments #{}` desired against the key absent
  when stored.
- The commit's db.clj diff changes no signature (`git show a90c3005e -- src/seon/db.clj`: a private memo body
  and a docstring).
- Their caller files: **180**. After the empty `:arguments` is stripped, the signature-changed rows are `[]`
  and the caller files are **0**.
- So the 9.4 s term vanishes. Any republication of a file with a zero-argument contracted function that has
  callers currently pays it, because the file's row positions move (`:seon.fn/form-span`, 138 of 159 rows
  changed).

## Analyzer-digest reach: options (task 3; nothing implemented)

The walk from `seon.fn/analyze-rows` (`cluster.clj:2366-2379`) reaches 346 defined functions: `seon.db` 84,
`seon.schema` 83, `seon.fn` 75, `seon.fn.analyzer` 23, `seon.schema.internal` 18, `seon.schema.edn` 18,
`seon.program` 16, `seon.schema.datahike` 14, and a few others. Row content really does depend on
`seon.schema` (shapes) and `seon.program` (row constructors: the patch above is one such change), and
through `db/carried-projection` it depends on `seon.db` too. Stopping at "the database layer" therefore drops
real inputs. The candidate I checked, "namespaces with a direct `datahike.api` edge", also catches
`seon.schema`.

1. **Simplest (constraint): keep the reach and move the cost.** Root runs the checkpoint publication before
   restarts (boot-regression note, fix 1). Each db.clj edit keeps paying one `:all`. This costs 0 lines. It
   gives exact staleness safety and gives up 16-25 s per edit of the 346.
2. **Recommended: include only the signature of callees outside the analyzer's own files.** The analyzer's
   own files are those holding `analyze-rows`' own namespace and its `seon.fn.*` children, a boundary derived
   from the root symbol. Those files contribute definition digests. Every other callee contributes only
   `caller-files`' `signature-attributes` (the codebase's existing rule for what a caller reads from a callee,
   `fn.clj:2458-2462`). The change is about 20 lines in `cluster.clj` `analyzer-digest` (owned). Body-only edits
   to db/schema/program stop forcing `:all`. What it gives up: a body-only change in `seon.program` or
   `seon.schema` that changes rows (like the patch above) leaves each file's rows stale until that file
   republishes. That trade needs the owner's ruling.
3. **A declared partition.** Namespaces carry declared metadata (for example `:seon.program/analysis-input`) and
   the walk stops at undeclared ones. The change is about 30 lines plus a schema key. It is exact where it is
   declared, but it is a maintained declaration, which the law asks to avoid.

## Changed paths, commits, sizes

- `docs/prds/agent-platform/landing/lane-perf-small-publication-2026-09-25.md` (this note): the only committed file.
  Net source/test lines: 0.
- Uncommitted evidence (tmp/, ignored): `tmp/perf-small-publication/{probe.clj,probe-change.clj,program-empty-arguments.patch}`.
- Released paths: `src/seon/cluster/source.clj`, `src/seon/cluster.clj`, `src/seon/fn.clj` (not edited).

## Verification limits, and what root runs

- Load rule: this lane ran two scratch-branch publication probes (the control, then the real change, the
  control's first attempt refused a vector `:changed-paths` by contract), and four small read-only probes. No test
  request, no `bin/seon init`, no extra JVM. The scratch branches were retired in `finally`, and the store holder
  was released. The seal, `force-branch!` and scratch retirement of a real `publish!` were not timed.
- Nothing is installed. The projected ≈ 5.5 s after the fix is the measured total minus the measured relint
  phase. It has not been observed.
- Root runs, after assigning `src/seon/program.cljc` + `test/seon/program_test.clj` and applying the patch:
  1. `bin/test-check default --test seon.program-test/zero-argument-arity-states-no-empty-argument-set`
  2. `bin/test-check default --ns seon.program-test`
  3. The checkpoint publication, where the expected classification is `:all` once. Then re-run
     `tmp/perf-small-publication/probe-change.clj` through MCP `eval_clj` (the same session/namespace). Expect
     `populate-source!` without the "analysis callers: 179 files" progress line, and a total of about 5.5 s.
- Proposed issue, which root files after searching `docs/seon/issues/`: "Republishing a file with a zero-argument
  contracted function relints every caller". Also add a line to
  `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md` saying that the `:selected` path
  is covered here.
