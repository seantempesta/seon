---
type: research
status: dig, gathering only; candidates, no causal claim
date: 2026-10-14
lane: dig-boot-publish
---

# Boot publication: time series, path, prior work, profile

Gathering only. No source edits, no tests, no default restart, no SCI request on default. Each row is marked VERIFIED (read or run in this lane) or UNVERIFIED. Causes are candidates until a probe reproduces them.

## 1. Boot time series (VERIFIED)

Source: all 220 `data/clusters/default/logs/operator-start-*.edn`. 210 carry a readiness record; 10 are failed or interrupted starts and are excluded. The `:seon.source/current` layer is read from the readiness layers (`{:layer :seon.source/current, :at-ms N}`, cumulative since boot). `adopt-ms`, `launch-ms`, `source-ms` come from `:seon.operator/phases`. No publication-phase milliseconds are recorded in the operator log; only `adopt-ms` exists.

Per-day medians of `ready-ms`: 2026-10-12 13,531 ms (n=36); 2026-10-13 10,154 ms (n=29, matches the baseline in the brief); 2026-10-14 33,578 ms (n=7).

Selected rows (date, sha, ready, source/current at-ms, launch, adopt, operator source-ms):

| start (UTC) | sha | ready ms | source/current ms | launch ms | adopt ms | source-ms |
|---|---|---|---|---|---|---|
| 10-11 10:33 | c1230efc3 | 5,128 | 494 | 12,302 | 223 | 87 |
| 10-12 15:53 | 2265d84fd | 8,747 | 7,146 | 19,078 | 2,124 | 2,252 |
| 10-13 00:14 | 7f3782700 | 19,177 | 17,733 | 30,622 | 3,136 | 2,281 |
| 10-13 01:24 | bae97ee71 | 10,154 | 8,756 | 21,576 | 2,702 | 2,263 |
| 10-13 04:32 | 1d950f730 | 8,942 | 6,886 | 18,988 | 2,075 | 2,492 |
| **10-13 13:17** | 73968333e | 30,427 | 28,783 | 49,768 | 7,295 | 3,066 |
| 10-13 17:02 | cde025f68 | 15,348 | 12,706 | 32,599 | 5,428 | 819 |
| **10-14 09:59** | de1f48c23 | 29,028 | 23,436 | 50,963 | 6,733 | 751 |
| 10-14 13:10 | 243683f1d | 33,578 | 30,666 | 66,921 | 9,514 | 2,607 |
| 10-14 13:40 | 06bbba850 | 19,306 | 17,065 | 35,849 | 4,091 | 2,644 |
| 10-14 14:16 | 86beed937 | 38,968 | 34,988 | 63,691 | 7,825 | 3,028 |
| 10-14 15:01 | 5fddc2bfd | 35,186 | 30,923 | 56,765 | 5,019 | 3,769 |
| 10-14 16:12 | ad653ff14 | 5,988 | 833 | 42,392 | 0 | 703 |
| **10-14 16:24** | e1db21f25 | 47,820 | 42,055 | 81,702 | 12,412 | 3,041 |

Observations (VERIFIED from the table):
- The source/current layer is 85 to 92 percent of `ready-ms` in every slow boot, and 88 percent in the 47,820 ms boot. Layers after it take 2 to 6 s.
- A zero-change restart (10-11 10:33, same sha as the prior start) spent 494 ms in source/current. The cost tracks changed inputs, not program size.
- Two jumps. J1: 10-13 17:02 (15.3 s) to 10-14 09:59 (29.0 s). J2: 10-14 09:59 to 10-14 13:10 (33.6 s), with the rise continuing through 16:24.
- An unexplained row: 10-14 16:12 (ad653ff14) had source/current 833 ms yet `launch-ms` 42,392, with `adopt-ms` 0. Its ready-ms (5,988) is inconsistent with its total (42,392). UNVERIFIED how this record arises. Its successor (16:24) is the 42 s boot.

### Candidate commits between the shas (CANDIDATES only, not causes)

Commit counts and changed files from `git diff --name-only A B -- src test resources script` and `git log A..B -- <listed paths>` (listed paths: `src/seon/source*.clj`, `src/seon/cluster/source.clj`, `src/seon/schema*`, `src/seon/fn*.clj`, `src/seon/db.clj`, `src/seon/cluster/boot.clj`, `resources/seon/schemas`, `resources/seon/tasks`).

- **J1 cde025f68..de1f48c23**: 46 commits, 59 changed files, 26 touching listed paths. Listed-path commits include `de1f48c23` (a REPL value def is a program row), `76599e44a` (a namespace a transaction function writes re-derives its declarations' digests), `2edaf91eb`, `0672a141b`, `249c18f9a` and `35cafc1dc` (render invocations to `seon.cache`).
- **J2 de1f48c23..243683f1d**: 90 commits, 205 changed files, 118 touching listed paths. The largest single change is the S1a rename `243683f1d` (intent to plan, across task files, schemas, src and tests). Others: `c7f0383b0`, `8b6473ed8`, `4c472c07e`, `0691cb23c`, `f87629f9b`.
- **243683f1d..06bbba850**: 27 commits, 20 changed files, 9 touching listed paths (`06bbba850` restart batch GC, Konserve and Datahike bump, `8b6473ed8`).
- **06bbba850..86beed937**: 13 commits, 6 listed (`fbf611da5`, `a80d93e3e`: `parsed-facts` memoised in `seon.cache`; `f8a2773be`: packaged schema population keyed by declaration digest; `95f4aab31`).
- **5fddc2bfd..ad653ff14**: 42 commits, 62 changed files, 29 touching listed paths (`c1dd12e6e`, `e67cb2ba0`, `fc7d5994b`).
- **ad653ff14..e1db21f25** (the 42 s jump): 1 commit, changed file `test/seon/ref_test.clj` only. Zero listed-path changes. The commit drops a task tag from two deftests. This does not explain the 42 s by itself; see the working-tree candidate in section 2.
- Earlier spike 10-13 13:17 (73968333e): 15 commits from 1d950f730, 68 changed files, 15 listed (`73968333e` one reference rule, `cb7ec1180`, `941c494ec` one proof selector).

## 2. Publication path at boot (VERIFIED by reading code)

1. `seon.cluster.boot/stand-boot-layers!` (src/seon/cluster/boot.clj) acquires the store with `seon.cluster/acquire-root-store!`, writes the advertisement, then calls `seon.cluster.boot/changed-source-paths` when `seon.source/current` is already set.
2. `changed-source-paths` (boot.clj) runs `seon.source/discover-paths` and `seon.source/path-digests` over the working directory (`fs/source-directory`), reads the published commit with `seon.source/current` and `seon.source/commit-database`, and compares digests with every `:seon.fn.file/digest` in that commit. It returns the changed paths. This is the whole-tree read and it happens on every boot, changed or not.
3. If changed is non-empty, `seon.cluster/refresh-source!` (cluster.clj:2761) runs with the changed paths. The `:seon.source/current` layer is published after this returns. That makes the whole span measured as source/current: discovery, digest compare, publication, reindex.
4. `refresh-source!` calls `publication-paths`, `acquire-root-store!` again, then `seon.cluster/full-source-refresh!` (cluster.clj:1742). Inside it:
   - `source/capture-paths` reads bytes for the inventory, or only for requested paths when partial (`partial?` is true for a boot resume).
   - `source/classify-paths` decides the analysis set. A config change (`.clj-kondo/config.edn` appears in the check notes) reselects every source (classification `:all`).
   - `seon.fn/analyze-entities` analyzes the selected paths (clj-kondo runs over them).
   - `seon.cluster/derivation-keys` (cluster.clj:1679) keys each file by code, kondo config, bytes, and the schema forms it reads, closed over `:seon.schema.projection/schema-dependencies`.
   - Keys that differ trigger a second analysis of the stale files (the rederive path, which the boot-source-current note documents as analysis done twice).
   - Transactions write the file entities, then `refresh-source!` publishes the commit.
5. Back in boot: `cluster/arm-host-program!` and `cluster/name-loaded-program!` run after the layer, then the readiness layers continue (`seon.store/branch`, `seon.source/commit-id`, config result, `seon.sci.eval/ctx`, flow graph, web).

Which steps depend on the whole program versus the changes since the last publication:
- Whole program on every boot, regardless of change: `changed-source-paths` reads and digests the full tree and loads the stored file entities. `source/current` and `commit-database` materialize the commit. This is not measured separately today.
- Whole program only when classification is `:all` (config change, or first publication): `discover-paths` inventory, `capture-paths` over all inventory, analysis of every source, derivation keys for every file.
- Proportional to changed inputs: analysis of the selected paths, derivation keys for them, transactions for their entities.
- Proportional to the schema population: `derivation-keys` builds `derivation-world` over the whole projection (`schema/handed-projection`) when the projection key changes. The cache key includes all forms, so a changed schema form invalidates the world for all files.
- Post-boot: `adopt-ms` (operator log) is a separate phase, after readiness in `adopt`, not inside source/current.

Candidate, UNVERIFIED: `changed-source-paths` reads the working directory, not HEAD. At this lane's start `git status` showed 18 modified tracked files, most of them held by active lanes. A boot therefore publishes whatever the lanes have in the working tree, which would explain a publication larger than the commit diff (for example the 42 s boot after a one-file commit). A probe would compare `changed-source-paths` output against `git diff --name-only <published sha>` on the 16:24 state. Not run here.

## 3. Prior work (VERIFIED by grep and reading)

- `docs/research/agent-platform/2026-10-14/boot-source-current.md` (boot-dup-analysis, status: ownership stop, no performance fix committed). It documents duplicate analysis in `full-source-refresh!`: analyze changed, find stale keys, discard and re-analyze both. The candidate patch `boot-source-current.patch` is unapplied; it was stopped because `src/seon/fn.clj` was held by `perf-analyzer`. Its probes are in `boot-source-current-probes.edn` (probes `seon.cluster.source/current` and `commit-database`).
- `docs/research/agent-platform/2026-10-13/boot-sentinel.md`: first measured boot regression (73968333e, 49.8 s total), `source/current` 0.38 s per changed input. Recommended logging phases inside the span; no commit recorded.
- `docs/research/agent-platform/2026-10-12/publish-cost-evidence.edn` and `publish-cost.patch` (lane publish-cost, gpt-6-astra, status `:stopped-uncommitted`). Finding: one clj-kondo invocation and two transactions per eight inputs; repeated `seon.program/source-names` parsing is material waste. Experiment: content-keyed `seon.cache/lookup` around `source-names`. Stopped uncommitted.
- `docs/research/agent-platform/2026-10-14/sentinel-e1db21f25.md`, `check-wait.md`, `cache-after-boot.md`, `sentinel-after-restart-2.md`: source/current layer 30.7 s to 42 s, measured against check JVMs at about 20 s on the same layer.
- Filed tasks (resources/seon/tasks/seon.cluster.edn lines 2, 8, 20 read by grep): (a) "A move to HEAD spends 6.4 to 9.9 s adopting after readiness" and boot's store-to-branch layer 27 to 32 s; (b) the boot resume publication has no profile cell and its phases are not logged, `*source-progress!*` is bound nowhere outside tests; (c) a schema-only edit re-derives identical entities because `derivation-keys` hashes whole schema forms (194 of 2,145 datoms asserted only the new derivation digest). Task (c) names `seon.cluster/derivation-keys` and `seon.cluster/full-source-refresh!` as owners. None shows a recorded close.
- Root queue item 13 ("Boot seon.source/current 30.7 s (Argon lane hit quota)") is in `docs/research/agent-platform/2026-10-14/root-working-state.md` line 43. The brief for it was the boot-source-current spec; no evidence of a completed Argon run exists in the tree.
- Stalls, as recorded: publish-cost stopped uncommitted; boot-dup-analysis stopped at file ownership (`src/seon/fn.clj` held by perf-analyzer); the Argon lane hit quota.

## 4. Profile evidence (VERIFIED, read-only JVM eval)

`(seon.profile/summary (seon.profile/cells) 15)` on default, JVM mode, read result `result/e2dfb53291d61`. Cells are cumulative across the running JVM's lifetime, not reset per boot, so they mix boots. Observed 1,733 calls, 91 over one second.

Top by self time:
| function | calls | self ms | total ms | max ms |
|---|---|---|---|---|
| `seon.db/transact-call` | 167 | 28,790 | 28,930 | 8,142 |
| `seon.await/await-port-operations` | 29 | 24,989 | 24,989 | 8,456 |
| `seon.schema/call-with-projection-state` | 125 | 24,965 | 32,304 | 8,455 |
| `seon.db/with-declarations` | 27,539 | 18,467 | 45,248 | 3,959 |
| `seon.db/vector-query-call` | 7,563 | 13,771 | 18,000 | 1,531 |
| `seon.db/pull-logical` | 10,936 | 6,896 | 8,064 | 1,367 |
| `seon.test/bounded-result` | 23 | 4,046 | 4,062 | 1,637 |
| `seon.cluster.source/changed-identities` | 8 | 2,496 | 2,557 | 1,653 |
| `seon.cluster/rehearse-source!` | 5 | 2,413 | 28,972 | 6,843 |
| `seon.db/declaration-content-key` | 135 | 2,090 | 2,090 | 120 |
| `seon.db/write-owned-values-error` | 189 | 1,653 | 3,029 | 902 |

Notes: `seon.db/transact-call` and `seon.await/await-port-operations` self-times include waiting, per the publish-cost note, so they must not be summed with other rows. `seon.cluster/rehearse-source!` total 28,972 ms over 5 calls is the check-side publication rehearsal, the cost the brief asked about. `seon.cluster.source/changed-identities` is among the top, which is the change-selection step. `seon.fs/delete-observed!` took 1,282 ms over 9 calls. No cell for `changed-source-paths` or `boot` appears in the top 15; it may fall under the boot's unprofiled stage (the probe note says the resume publication runs before `arm-host-program!`, so no cell observes it).

## 5. Next probes (not run; candidates)

1. Log the `source/current` span's phases (discovery, digest compare, capture, analysis, derivation keys, transactions, publish) into the operator log. This was recommended on 2026-10-13 and still is not done.
2. Compare `changed-source-paths` against `git diff --name-only <published-sha>` at the 16:24 state to test the working-tree candidate.
3. Count how many tracked files the schema projection key changes per boot, to test whether `derivation-world` rebuilds on every schema-form change.
