# Old code in ~/src/seon-jolt: what to keep, what to extract, what to archive (2026-10-23)

A read-only survey of the top-level directories of `~/src/seon-jolt` beside `next/`, its `next-*` worktrees, `RESUME.md`, `notes/` and `bin/`. Nothing was edited, deleted or built.

## How it was checked

- Size and age: `du -sh`, and the newest file outside `.git`.
- References from next: a grep of `next/bin`, `next/src`, `next/docs`, `next/resources`, `next/AGENTS.md`, `next/deps.edn` and `RESUME.md` for each name, plus `next/.gitmodules`' URLs.
- Unique git work, for all 255 nested repositories. A repository is a git worktree of a live repo, or each local branch tip is looked up in `next`, every `next/reference-code/*` module repo and the live fork repos, then `git log --branches --not --remotes`.
- Value: each directory against its `notes/option3-*.md`, `docs/parity.md`'s **missing** rows and the task files.

## Three facts that change how deletion is done

1. **`next/.gitmodules` URLs point into experiment directories.** The URLs are `jolt/`, `e14/chez-share`, `libs/{datahike,konserve,crypto,jolt-overlay}`, `e4/pss-a`, `e9/jolt-fressian` and `l1/wt/malli`. Deleting any of these breaks a fresh clone of next and any new `git submodule update`. Worktrees don't depend on them, because `bin/worktree` uses `next/.git/modules`.
2. **Many subdirectories are git worktrees of those live repos.** These are `a1/wt/*`, `e14/jolt-ctl`, `rc1/{jolt,upstream}`, `u1/wt/*`, `l1/wt/{datahike,konserve,pss-a}`, `l1/qc/*`, `l2/konserve*`, `l3/{dh-*,datahike*,pss}` and `e9/jolt-fressian-orig`. After removing them, run `git -C <repo> worktree prune`. Their branches stay in the parent repo.
3. **Much of the old prototype source is tracked by the top-level `seon-jolt` git repo.** Examples: em1 has 42 tracked files, proto 607, e9 542, e15 471, l1 190, decision 168. That source survives deletion in git history. The bulk on disk is untracked build output. Nested repos are gitignored, though, so their unique commits are not covered: bundle those first (see EXTRACT 6).

## Table

Classes: **LIVE** means next uses it, so keep it. **EXTRACT** means it has value to capture first. **ARCHIVE** means it is superseded and nothing in it is unique, so it can be deleted once the owner agrees.

| item | what it is | size | newest | class | why |
|---|---|---|---|---|---|
| test-results | test results by reach digest | 194M | 10-23 | LIVE | `default.edn:262` `test-results-dir`; docs/machine, source, merging |
| worktree-ports, worktree-ports.lock | port registry | 4K | 10-23 | LIVE | `bin/ports`, `bin/worktree:126` |
| tmp | `tmp/agy/<name>` state of `bin/agy-agent` | 4.6M | 10-21 | LIVE | `bin/agy-agent`; RESEARCH-async cites `tmp/agy/research-*` |
| flowlab | Flow/core.async lab, `docs_check.clj` | 256K | 10-21 | LIVE (+EXTRACT 3) | docs/flow.md, docs/core-async.md run `flowlab/run.sh docs_check`; all 10 tasks in `flowlab/tasks.edn` are now in next's task files |
| jolt | Jolt clone; submodule URL | 996M | 10-19 | LIVE | `.gitmodules`; holds 4 branches not in next's module (`upstream/fiber-park`, `upstream/remote-chan`, `upstream/fork-reprotect-jit`, `pf1-f2only`), plus the worktrees listed above |
| libs/{datahike,konserve,crypto,jolt-overlay} | fork repos | 76M | 10-20 | LIVE (+EXTRACT 1) | submodule URLs, docs/forks.md. datahike has 5 branches not in next's module (`jolt/merge-diff`, `qcache-lineage`, `qcache-noinherit`, `pss-cursors`, `upstream/datoms-diff-portable`); konserve has `l1/lockwait-probe` (a probe) |
| libs/{malli,sci,edamame,rewrite-clj,persistent-sorted-set,superv.async} | plain clones | 40M | 10-20 | ARCHIVE | not submodule URLs; superv.async's one patch is recorded as dropped (forks.md "Dropped: superv.async") |
| e14 | E14: forked children share compiled code; Chez builds | 995M | 10-19 | ARCHIVE except `e14/chez-share` (77M, chez submodule URL) | patch 0007 is in the pinned Chez; `chez-wx` is a rejected checkpoint |
| e4 | E4: PSS on Jolt, Rust B+tree (dhix) | 24M | 10-19 | ARCHIVE except `e4/pss-a` (pss URL) | `e4/rust` and `e4/datahike-b` hold 6+5 local commits of the rejected Rust route (bundle them) |
| e9 | E9: a JVM writer with Jolt readers | 701M | 10-19 | ARCHIVE except `e9/jolt-fressian` (URL) | `*-src` checkpoints are 2 commits each (bundle them) |
| l1 | L1: library profile, Phase 1 | 250M | 10-20 | ARCHIVE except `l1/wt/malli` (13M, malli URL) | its findings are in notes/option3-L1-libs.md |
| l3 | L3: scaling the libraries; merge by index diff | 269M | 10-20 | **EXTRACT 1** | `l3/next` 0823517 is the only copy |
| em1 | EM1: embeddings, C kNN kernel, embed server | 924K | 10-20 | **EXTRACT 2** | parity row `search`/`similar` is missing; the decision is taken; no task cites it |
| vendor-c | LMDB clone | 2.0M | 10-19 | ARCHIVE (+EXTRACT 4) | superseded by `reference-code/lmdb` (`mdb.master3` is in the module); `docs/failure-points.md:141` still cites it |
| proto | the first prototype (core, flow, agents, seon-min, web, demo) | 3.0G | 10-20 | ARCHIVE (+EXTRACT 4, 6) | ported into `next/src`; `next/src/README.md` still says code is copied from `../proto`; `proto/core` (20) and `proto/flow` (21) hold commits found nowhere else |
| seon | an older clone of v1 | 1.1G | 10-19 | ARCHIVE | its HEAD a780860c3 is in `~/src/seon`; 0 unpushed commits |
| jolt-combined, -e0, -e14, -e4, -e9, -f1, -final, -j2, -j3, -pf1, -tap, -u1 | per-experiment Jolt clones | 3.4G | 10-19..21 | ARCHIVE | every branch tip is in `jolt/`; clean |
| jolt-l1 | Jolt clone for L1 | 325M | 10-20 | ARCHIVE | 6 unique branches: 2 landed by subject; `jolt/record-method-fastpath` and `jolt/print-method-dispatch` were dropped for no gain (option3-L1-libs.md:350–352, 500) |
| l2 | L2: Jolt/Chez vfasl and codec | 526M | 10-20 | ARCHIVE (bundle `l2/jolt`) | `l2/jolt` holds 19 unpushed commits, e.g. `jolt/stdlib-var-cells`; the measured ones landed upstream (`htable-tag-read`, `array-ctor-copy`); the vfasl fixes are in the pinned Chez |
| u1 | U1: Chez kernel and patch variants | 2.2G | 10-20 | ARCHIVE | chez-k* hold the 0001–0007 patches, now in the pin; `wt/*` are worktrees of `jolt/` |
| scratch | f1, f2, j2 and m1 scratch | 1.6G | 10-20 | ARCHIVE (bundle) | `f1head` and `f2pg` hold 21 commits of the F2 prototype, which became next's core (80797ce) |
| r3 | R3: incremental reconciler, merge trains | 687M | 10-20 | ARCHIVE | landed as next b7459c3; octopus merge replaced trains (3432fb7) |
| r5 | R5: trains that hold, a judge that observes | 414M | 10-20 | ARCHIVE | next b50640b retired `jolt/r5-port`: "the one idea main lacks is filed"; its patches are tracked |
| r4 | R4: learning loop prototype | 5.3M | 10-20 | ARCHIVE | cited by task seon.context (context-v2); its patches are tracked |
| fx1 | FX1: effect owner | 307M | 10-20 | ARCHIVE | my.fx exists in next; its 2 patches are tracked |
| rc1 | RC1: remote channels | 205M | 10-20 | ARCHIVE | worktrees of `jolt/`; the branch is in `jolt` |
| decision | decision bench (Option 3) | 264M | 10-19 | ARCHIVE | notes/decision-bench.md; source tracked |
| e0 | E0: reliable fork() | 226M | 10-19 | ARCHIVE | its fork fix is in Jolt and Chez pins |
| e8 | E8: Jolt and Rust FFI costs | 151M | 10-19 | ARCHIVE | notes/option3-E8.md |
| e6 | E6: Java-gap list | 44M | 10-19 | ARCHIVE | `jolt-libs/*` are clean GitHub clones |
| a1 | A1 lane worktrees (jolt, datahike) | 35M | 10-21 | ARCHIVE | worktrees only |
| pf1 | PF1: proto/core perf (stopped) | 13M | 10-20 | ARCHIVE (bundle `core-fixed`) | F1 landed via G1; F4/F5 were never measured end to end (PF1 note) |
| scratch-ecosystem | awesome-jolt libs | 10M | 10-21 | ARCHIVE | clean GitHub clones; notes/option3-ECOSYSTEM.md |
| e15 | E15: instrumentation and tx cost | 4.1M | 10-20 | ARCHIVE | 2 modified tracked files (numbers already in notes/option3-E15.md) |
| e12 | E12: web on Datastar | 2.9M | 10-19 | ARCHIVE | next's web is W2 (08348cb) |
| web | glimmer-datastar, router, ring adapter | 1.9M | 10-19 | ARCHIVE | clean GitHub clones |
| em1, k1, j3, x1, d1, v1, n1, r2, bench, konserve-jolt, datahike-jolt, w1, ipc, flowagent, datarepl, datahike-jvm, bench-window | small prototypes, each with its note (K1, J3, X1, D1, V1, R2…) | ≤620K each | 10-19..20 | ARCHIVE (em1: EXTRACT 2) | J3's try fix is in the pin (00a3b321); X1's codec is in konserve (forks.md:55); W1 was superseded by W2 |
| *.log, bench/build/check.lock | old merge and build logs | 160K | 10-19..21 | ARCHIVE | no script reads them (`worktree-ports.lock` is LIVE) |

## Reclaimable

- **14.0 GiB** right away: every row marked ARCHIVE, including em1 after EXTRACT 2. Of this, proto is 3.0G, the jolt-* clones 3.7G, u1 2.2G, scratch 1.6G and seon 1.1G.
- **+1.84 GiB** once EXTRACT 5 repoints the submodule URLs: the rest of e14, e9, l1 and e4.
- **+40 MB** of the extra clones in libs.
- **Total about 15.9 GiB.** What stays: jolt (1.0G, or nothing after EXTRACT 5), test-results (194M), the four libs forks (76M), tmp and flowlab.

## EXTRACT details

1. **Merge by index diff (L3).**
   - What exists: `l3/next` holds commit `0823517` "seon.merge/three-way-db: the program merge from Datahike's index diff, O(Δ log V)", measured at the library level in `notes/option3-L3-libs.md` (Item 1). It needs `libs/datahike` `jolt/merge-diff` (021e876, 8311254), which makes `datahike.db/datoms-diff` run on Jolt; that branch isn't in next's datahike module yet.
   - What's missing: the open task `seon.merge/program-cost-follows-what-changed` (resources/seon/tasks/seon.merge.edn) asks for exactly this but cites neither.
   - Do: put the commit, the branch and the L3 note into that task's plan. Keep the code with `git -C next fetch ../l3/next main:refs/archive/l3-three-way-db` and fetch `jolt/merge-diff` into the datahike module.
   - This is THE PLAN's merge-cost step: about 1 s of whole-program reads per attempt.
2. **Embeddings (EM1).**
   - What exists:
     - `em1/knn.c` and `em1/csrc/proto_knn.c`: a brute-force kNN in C, 10k×768 in 0.39 ms;
     - `em1/server` and `src`: the embed service;
     - `em1/patches/{agents,core}-01-embed.patch`;
     - `em1/out/*`: the measurements, and the R@10 corpus behind DECISIONS "Embeddings join the context";
     - `tmp/agy/embed-research`.
   - What's missing: parity rows `my.program/search` and `similar` are **missing**, and the context-v2 task in seon.context.edn says "next has no embedding index yet". No task cites em1.
   - Do: file a task (seon.context or seon.program, `similar`) whose plan cites these files and DECISIONS:257. The code stays recoverable from seon-jolt git once the directory is deleted.
3. **The cron proc (FLOWLAB R7).**
   - What exists: `flowlab/r7_cron_door_merge.clj` and `kit/cron-step`, a working prototype of AGENTS §4's one timer.
   - What's missing: parity "scheduled work (`seon.schedule`)" is **missing**, and no task names cron.
   - Do: file a task citing both files and notes/option3-FLOWLAB-2.md.
4. **Stale citations to fix (wiki edits):**
   - `next/src/README.md:2` still says code is copied from `../proto/*`;
   - `next/docs/failure-points.md:141` says `../vendor-c/lmdb`, which is now `reference-code/lmdb`;
   - `docs/forks.md:38–77` names `~/src/seon-jolt/libs/*` and `l1/wt/datahike` as homes (update this after step 5).
5. **The submodule URLs (owner decision, §5 forks).**
   - Point `.gitmodules` at one stable home per fork: the GitHub forks, or a single `forks/` directory.
   - First fetch the stray branches (those in `jolt`, `libs/datahike` and `libs/konserve` listed above) into `next/.git/modules/*`.
   - Then e14, e4, e9, l1 and the clones in libs are fully archivable, and `jolt/` can go too.
6. **Bundle before deleting.** Nested repos with commits found nowhere else:
   - `proto/core`, `proto/flow`;
   - `scratch/f1head`, `scratch/f2pg`;
   - `l2/jolt`, `l3/next` (covered by 1);
   - `pf1/core-fixed`;
   - `e4/rust`, `e4/datahike-b`;
   - `e9/{datahike,konserve}-jolt-src`;
   - `r3/{next,base0,rebase}`, `r5/next`, `fx1/next`.

   `git bundle create notes/archive/<name>.bundle --all` for each is a few MB in total, and keeps history without the build trees. Per AGENTS §9, deleting a record is the owner's act.
