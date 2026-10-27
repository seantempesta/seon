# seon-next growth since 2026-10-20 (read-only measurement)

Measured on main at 2026-10-24, repo /Users/sean/src/seon-next. No builds, tests or repo writes.

Two bases were used:
- **since-window**: `git log main --since=2026-10-20 --numstat`, as specified. Sums per-commit diffs of commits dated since the cutoff. It misses branch commits dated before the cutoff that merged after it.
- **endpoint**: `git diff ef7cf957d main`, where ef7cf957d is `git rev-list -1 --before=2026-10-20 main` (2026-10-20 07:46 +0700, "Submodules: every fork names its canonical branch"). This is the true net state change.

## 1. src and test growth

| measure | value |
|---|---|
| src since-window: added / removed / net | 44,020 / 24,799 / +19,221 |
| src endpoint (ef7cf to main): insertions / deletions | 18,880 / 107,450 (169 files) |
| src lines now (`find src -name '*.clj*'`, 62 files) | 18,973 |
| src at base: .clj files / lines | 125 files / 107,546 lines |
| test since-window: added / removed / net | 25,399 / 9,475 / +15,924 |
| test endpoint | 15,670 insertions / 123,589 deletions (541 files) |
| test lines now (`find test`, .clj) | 15,695 |

The since-window net (+19k) is not the state change. The endpoint says src went from about 107.5k lines in 125 files at the base to 19k lines in 62 files: a rewrite, with the old src/my and src/seon cluster layout removed. Config and procs also moved from `config/default.edn` to `resources/seon/config/default.edn`.

Top 15 src files by since-window net growth (added, removed, net):

| net | added | removed | file |
|---|---|---|---|
| 1148 | 2418 | 1270 | src/seon/root.clj |
| 1138 | 2227 | 1089 | src/seon/program.clj |
| 819 | 2690 | 1871 | src/seon/reconcile.clj |
| 791 | 1711 | 920 | src/seon/agent.clj |
| 769 | 1033 | 264 | src/seon/print/select.clj |
| 685 | 819 | 134 | src/seon/ref.clj |
| 654 | 1048 | 394 | src/seon/fx.clj |
| 593 | 2964 | 2371 | src/seon/render/ui.clj |
| 580 | 1379 | 799 | src/seon/problems.clj |
| 548 | 1321 | 773 | src/seon/context.clj |
| 528 | 1190 | 662 | src/seon/task.clj |
| 522 | 798 | 276 | src/seon/source.clj |
| 503 | 1552 | 1049 | src/seon/render/proc.clj |
| 445 | 1122 | 677 | src/seon/repl.clj |
| 423 | 645 | 222 | src/seon/print/emit.clj |

Per-file table: `/private/tmp/claude-501/-Users-sean-src-seon-next/170bd2ed-3bd9-41d6-a8aa-bbc57873a706/scratchpad/src_net.tsv`

## 2. Config keys, files, procs, routes

- **Config keys:** base `config/default.edn` has 117 distinct `:seon.config.*` keys. Now `resources/seon/config/default.edn` (688 lines) has 127. Of those, 112 are new names and 102 base names are gone. The namespaces were reorganized, not only added to. Lists: `/private/tmp/claude-501/-Users-sean-src-seon-next/170bd2ed-3bd9-41d6-a8aa-bbc57873a706/scratchpad/cfg_b.txt` and `cfg_n.txt`.
- **New src files:** 69 files were added (diff-filter=A, since-window). Base has 125 .clj files in src, now 62.
- **Procs:** no `resources/seon/system.edn` at base. Now 17 top-level procs in it: writer, fork-server, flusher, reconciler, prove, vectors, render, web, file, source, git, deps, build, tests, history, embed, model.
- **Routes:** no `src/seon/web.clj` at base. Now it has 13 string literals starting with `"/`. I did not verify that these are a route table.

## 3. New public defns with zero src callers

Scope: 633 unique `(defn NAME` lines in `git diff ef7cf957d main -- src`. Of those, 484 names are not defined at base. Callers were counted by token match, with a qualified `ns/name` counted as a reference. Definitions are excluded. Searched: src (src refs), test (test refs), resources and bin (resources/bin refs).

**19 names have zero src callers.** None of them has zero refs everywhere: each is used by a test, a resource or bin file, or both.

| name | defined in | src | test | resources/bin |
|---|---|---|---|---|
| config-panel | src/seon/render/ui.clj | 0 | 0 | 2 |
| delete-main! | src/seon/db.clj | 0 | 3 | 2 |
| echo | src/seon/fx.clj | 0 | 12 | 3 |
| file-ns | src/seon/source.clj | 0 | 5 | 1 |
| flusher-proc | src/seon/writer.clj | 0 | 2 | 4 |
| fork-server-proc | src/seon/fork_server.clj | 0 | 5 | 1 |
| next-generation | src/my/root.clj | 0 | 0 | 1 |
| refresh-tx | src/seon/source.clj | 0 | 19 | 6 |
| render-proc | src/seon/render/proc.clj | 0 | 13 | 2 |
| request-text | src/seon/context.clj | 0 | 0 | 1 |
| run-row | src/seon/render/ui.clj | 0 | 2 | 2 |
| status-tx | src/seon/task.clj | 0 | 3 | 0 |
| step-needs-binary? | src/seon/proofs.clj | 0 | 6 | 1 |
| store-clean! | src/seon/root.clj | 0 | 2 | 1 |
| store? | src/seon/db.clj | 0 | 4 | 0 |
| task-box | src/seon/render/ui.clj | 0 | 2 | 1 |
| tree-of | src/seon/fx.clj | 0 | 1 | 0 |
| vectors-proc | src/seon/vector.clj | 0 | 14 | 3 |
| worker-proc | src/seon/fx.clj | 0 | 9 | 3 |

Reading the list: the `-proc` names (flusher-proc, fork-server-proc, render-proc, vectors-proc, worker-proc) are probably referenced by system.edn or proc wiring, not called from src. Names with zero test refs and only resources refs are config-panel, next-generation and request-text. Names with zero resources/bin refs and only test refs are status-tx, store? and tree-of. Zero-test and zero-resources together: none.

Full table: `/private/tmp/claude-501/-Users-sean-src-seon-next/170bd2ed-3bd9-41d6-a8aa-bbc57873a706/scratchpad/zero_callers2.tsv` (all 484 names: `all_new_callers.tsv` in the same folder).

Caveats: token matching, not a compiler. It can over-count (name collisions), so the zero list is conservative in one direction. Dynamic references (strings, `resolve`, edn symbols outside resources) are not checked beyond the resources and bin scan.

## 4. Merges per day since 2026-10-20

```
 82 2026-10-21
 91 2026-10-22
319 2026-10-23
  5 2026-10-24 (partial day)
```
Total 497 merge commits.
