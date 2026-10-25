# Worktree inventory, 2026-10-24 08:24 (+0700)

Read-only. Nothing deleted, killed, checked out, reset, stashed or committed. No builds or tests run.

Classification rules applied, in this order:
- LIVE: a session or process cwd is in the worktree, or a commit landed in the last 3 hours (after 05:24 today), or a config names it (AGENTS.md names `.worktrees/merges`).
- HAS-UNSAVED-WORK: uncommitted changes, or commits whose patches are not in seon-next main (the branch ref keeps them, but they are not merged).
- MERGED-CLEAN: branch tip is an ancestor of seon-next main, or every patch is in main by content (`git cherry`), and the worktree is clean.
- UNKNOWN: none of the above.

Disk is `du -sh`. "ahead" for seon-next means `rev-list --count main..BRANCH`. For the old repo it means whether the tip exists in seon-next, is an ancestor of seon-next main, and how many of its commits are not in main, with `git cherry` patch counts.

## A. seon-next (/Users/sean/src/seon-next)

| path | branch | ahead of main | uncommitted | disk | last commit | handoff | class |
|---|---|---|---|---|---|---|---|
| /Users/sean/src/seon-next | main | 0 | 0 | 33G | 2026-10-24 08:21 | yes | LIVE (owner's main tree, root on 7888) |
| /Users/sean/src/seon-next/.worktrees/a-cost-proof-is-the-same-under-load | jolt/a-cost-proof-is-the-same-under-load | 1 (1 patch unmerged) | 2: `M reference-code/chez`, `M test/seon/profile_test.clj` | 2.4G | 2026-10-24 08:10 | yes | LIVE (recent commit); also HAS-UNSAVED-WORK |
| /Users/sean/src/seon-next/.worktrees/a-killed-root-recovers-main-in-the-durable-read-check | jolt/a-killed-root-recovers-main-in-the-durable-read-check | 0 | 0 | 2.5G | 2026-10-24 08:13 | yes | LIVE (recent) |
| /Users/sean/src/seon-next/.worktrees/acts-by-second-move | jolt/acts-by-second-move | 18 (16 patches unmerged) | 2: `M src/my/root.clj`, `M src/seon/turn.clj` | 2.8G | 2026-10-24 08:20 | yes | LIVE (recent); also HAS-UNSAVED-WORK |
| /Users/sean/src/seon-next/.worktrees/durable-read-runs-alone | jolt/durable-read-runs-alone | 1 (1 unmerged) | 0 | 2.5G | 2026-10-24 08:13 | yes | LIVE (recent) |
| /Users/sean/src/seon-next/.worktrees/git-is-read-through-fx | jolt/git-is-read-through-fx | 4 (2 unmerged) | 0 | 2.7G | 2026-10-24 08:20 | yes | LIVE (recent; test processes PID 3012, 5681, 5684, 5685 have this path in their command line) |
| /Users/sean/src/seon-next/.worktrees/mcp-check | jolt/mcp-check | 0 | 0 | 2.4G | 2026-10-24 08:21 | yes | LIVE (recent; build PID 10222 has this path in its command line) |
| /Users/sean/src/seon-next/.worktrees/merges | jolt/merges | 0 | 0 | 2.4G | 2026-10-23 20:47 | yes | LIVE (named by config: AGENTS.md says `.worktrees/merges` is the merge worktree). Merged and clean, but not a candidate |
| /Users/sean/src/seon-next/.worktrees/prove-key | jolt/prove-key | 1 (1 unmerged) | 0 | 2.6G | 2026-10-24 08:21 | yes | LIVE (recent) |
| /Users/sean/src/seon-next/.worktrees/ticks-are-exact-2 | jolt/ticks-are-exact-2 | 0 | 0 | 4.0G | 2026-10-24 08:04 | yes | LIVE (recent) |

## B. Old repo (/Users/sean/src/seon-jolt/next, branches are in the old repo)

"in seon-next" means the tip SHA exists in seon-next. "anc" means the tip is an ancestor of seon-next main. "not in main" is the number of commits not in seon-next main, and "patches" is the `git cherry` count of those patches not in main.

| path | branch | in seon-next | anc | not in main (patches) | uncommitted | disk | last commit | handoff | class |
|---|---|---|---|---|---|---|---|---|---|
| /Users/sean/src/seon-jolt/next | main | yes | yes | 0 | 0 | 55G | 2026-10-23 18:57 | yes | LIVE (session PID 37621 cwd; shell 37665; relay 37656) |
| /Users/sean/src/seon-jolt/next-agent-view | jolt/agent-view | yes | yes | 0 | 1 untracked: `notes/context-reader.md` | 3.3G | 2026-10-23 15:07 | yes | LIVE (session PID 41345 and shell 53023 cwd) |
| /Users/sean/src/seon-jolt/next-land2 | jolt/land2 | yes | no | 1 (0 patches: a merge commit, content already in main) | 0 | 2.1G | 2026-10-22 19:01 | no | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next-merges | jolt/merges | yes | yes | 0 | 0 | 88M | 2026-10-23 18:54 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next-rename | jolt/rename-run | yes | yes | 0 | 0 | 5.6G | 2026-10-23 17:11 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next-self-contained | jolt/self-contained | yes | no | 8 (8 unmerged) | 0 | 2.6G | 2026-10-23 19:10 | yes | HAS-UNSAVED-WORK (8 commits not in main) |
| /Users/sean/src/seon-jolt/next-writeback-order | jolt/writeback-order | yes | yes | 0 | 0 | 2.2G | 2026-10-23 13:46 | no | LIVE (session PID 41056 cwd) |
| /Users/sean/src/seon-jolt/next/.worktrees/a-kept-store-is-keyed-by-its-schema | jolt/a-kept-store-is-keyed-by-its-schema | yes | yes | 0 | 0 | 2.9G | 2026-10-23 18:29 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/acts-by-second-move | jolt/acts-by-second-move | yes | no | 14 (13 unmerged) | 0 | 3.7G | 2026-10-24 08:01 | yes | LIVE (recent commit); HAS-UNSAVED-WORK |
| /Users/sean/src/seon-jolt/next/.worktrees/an-outside-run-writes-its-end | jolt/an-outside-run-writes-its-end | yes | yes | 0 | 0 | 2.9G | 2026-10-23 17:57 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/datom-cost | jolt/datom-cost | yes | yes | 0 | 0 | 2.7G | 2026-10-23 14:48 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/evaluations | jolt/evaluations | yes | no | 15 (11 unmerged) | 0 | 4.1G | 2026-10-23 17:15 | yes | HAS-UNSAVED-WORK |
| /Users/sean/src/seon-jolt/next/.worktrees/examples | jolt/examples | yes | no | 8 (8 unmerged) | 0 | 2.4G | 2026-10-23 17:28 | yes | HAS-UNSAVED-WORK |
| /Users/sean/src/seon-jolt/next/.worktrees/fixed-change-cost | jolt/fixed-change-cost | yes | yes | 0 | 0 | 2.5G | 2026-10-23 18:14 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/one-content-keyed-derivation | jolt/one-content-keyed-derivation | yes | no | 14 (10 unmerged) | 1: `M reference-code/jolt` | 2.2G | 2026-10-23 15:08 | yes | HAS-UNSAVED-WORK |
| /Users/sean/src/seon-jolt/next/.worktrees/one-root-launcher | jolt/one-root-launcher | yes | yes | 0 | 0 | 2.8G | 2026-10-23 19:13 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/relevant-carries-no-date | jolt/relevant-carries-no-date | yes | yes | 0 | 0 | 2.5G | 2026-10-23 18:14 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/the-api-uses-flows-own-channels | jolt/the-api-uses-flows-own-channels | yes | yes | 0 | 0 | 2.6G | 2026-10-23 18:05 | yes | MERGED-CLEAN |
| /Users/sean/src/seon-jolt/next/.worktrees/ticks-are-exact | jolt/ticks-are-exact | yes | yes | 0 | 0 | 2.5G | 2026-10-23 18:35 | yes | MERGED-CLEAN |

## C. Processes with cwd inside /Users/sean/src/seon-jolt

| PID | command (first 120 chars) | cwd | note |
|---|---|---|---|
| 37620 | /Applications/Claude.app/Contents/Helpers/disclaimer --pgroup -- .../claude-c... | .../seon-jolt/next | LIVE SESSION (wrapper) |
| 37621 | .../claude-code/2.1.308/.../claude --outpu... | .../seon-jolt/next | LIVE SESSION |
| 37656 | perl -MIO::Socket::INET -MJSON::PP -e ... (stdin relay) | .../seon-jolt/next | live helper of that session |
| 37665 | /opt/homebrew/bin/fish -l | .../seon-jolt/next | interactive shell of that session |
| 41055 | /Applications/Claude.app/Contents/Helpers/disclaimer --pgroup -- .../claude-c... | .../seon-jolt/next-writeback-order | LIVE SESSION (wrapper) |
| 41056 | .../claude-code/2.1.308/.../claude --outpu... | .../seon-jolt/next-writeback-order | LIVE SESSION |
| 41344 | /Applications/Claude.app/Contents/Helpers/disclaimer --pgroup -- .../claude-c... | .../seon-jolt/next-agent-view | LIVE SESSION (wrapper) |
| 41345 | .../claude-code/2.1.308/.../claude --outpu... | .../seon-jolt/next-agent-view | LIVE SESSION |
| 53023 | /bin/zsh -c source /Users/sean/.claude/shell-snapshots/... | .../seon-jolt/next-agent-view | shell of that session |
| 69131 | /opt/homebrew/bin/fish -l | .../seon-jolt/next-agent-view | interactive shell of that session |

No process other than these has cwd inside seon-jolt. The seon-next processes (3012, 5681, 5684, 5685, 9245, 9599, 9779, 9780, 10222) were checked by command line only: `lsof` returned no cwd for them.

## D. Summary totals

- seon-next worktrees: 10 (main plus 9 under `.worktrees/`). All are LIVE or config-named. No seon-next worktree is a candidate.
- Old repo worktrees: 21 (main plus 20). LIVE 4 (`next`, `next-agent-view`, `next-writeback-order`, `acts-by-second-move`). HAS-UNSAVED-WORK 4 (`next-self-contained`, `evaluations`, `examples`, `one-content-keyed-derivation`). MERGED-CLEAN 11.
- MERGED-CLEAN candidates (old repo, 11): `next-land2`, `next-merges`, `next-rename`, `a-kept-store-is-keyed-by-its-schema`, `an-outside-run-writes-its-end`, `datom-cost`, `fixed-change-cost`, `one-root-launcher`, `relevant-carries-no-date`, `the-api-uses-flows-own-channels`, `ticks-are-exact`.
- Total disk of MERGED-CLEAN candidates: 30,667,184 KB, about 29.2 GiB (`du -sk`).
- Not included in that total: `/Users/sean/src/seon-next/.worktrees/merges` (2.4G), which is merged and clean but named by config as live.

## E. seon-next jolt/* and claude/* branches (no `claude/*` branches exist)

Branches with no worktree. "ahead" is `rev-list --count main..BRANCH`. "unmerged" is the `git cherry` `+` count.

Merged by ancestry (ahead 0, safe to consider for deletion after the owner's go): a-kept-store-is-keyed-by-its-schema (2026-10-23), a-predicate-that-throws-is-an-error (10-23), a-search-allocates-in-proportion (10-23), a-tick-measure-allows-one-quantum (10-23), a-vanished-file-is-a-fact (10-23), an-outside-run-writes-its-end (10-23), fixed-change-cost (10-23), gc-sweepers-declared (10-23), image-registration-is-opt-in (10-23), judge-context-require (10-23), one-root-launcher (10-23), relevant-carries-no-date (10-23), the-api-uses-flows-own-channels (10-23), the-emitter-builds-one-buffer (10-23), ticks-are-exact (10-23), vocab-followups (10-23), wiki-true-2 (10-23), viewer (10-24 08:16), a-killed-root-recovers-main-in-the-durable-read-check (10-24, worktree), mcp-check (10-24, worktree), merges (10-23, worktree), ticks-are-exact-2 (10-24, worktree), an-outside... (listed).

Merged by content (ahead > 0, unmerged 0): land11 (1 commit, 2026-10-23 02:06), land13 (2, 10-23 06:09), land2 (1, 10-22 19:01), nested-tick-budget (1, 10-23 05:46).

Not merged (ahead > 0, unmerged > 0): a-cost-proof-is-the-same-under-load (1/1, 10-24 08:10, worktree), acts-by-second-move (18/16, 10-24 08:20, worktree), agent-view-prototype (4/4, 10-23 05:24), dry-run-merges (2/2, 10-23 12:14), durable-read-runs-alone (1/1, 10-24 08:13, worktree), eval-record-keeps-a-failing-form-whole (8/8, 10-23 17:28), evaluations (15/11, 10-23 17:15), examples (8/8, 10-23 17:28), git-is-read-through-fx (4/2, 10-24 08:20, worktree), one-content-keyed-derivation (14/10, 10-23 15:08), prove-key (1/1, 10-24 08:21, worktree), reach-scc (4/4, 10-23 07:24), self-contained (8/8, 10-23 19:10), unitflake (5/4, 10-21 13:13), wiki (13/12, 10-21 14:02).

## Notes for the reader

- The old-repo tip SHAs were checked against seon-next main by ancestry and by `git cherry`, so the classifications do not depend on the old repo's own main.
- Several old-repo tips match seon-next branches of the same name (for example `jolt/evaluations` at cdf12058 in both repos).
- `next-writeback-order` and `next-land2` have no `notes/handoff-*.md`. The handoff column shows `no` for both.
- The 3-hour rule is measured against 08:24. Anything committed after 05:24 counts as LIVE.
