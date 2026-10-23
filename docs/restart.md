# Restarting the work on a fresh account

How a fresh root picks up the work when every Claude session, memory and transcript is gone (owner, 2026-10-23: the account changes, and only the disk and git carry over); [AGENTS.md](../AGENTS.md) §2, "Root's working rules", is the method, [plan](plan.md) the queue, and this page the steps to get back to work.

## What survives

- Git: `main`, every `jolt/*` and `claude/*` branch, and the forks on GitHub (``seantempesta/<lib>``). Every pin of main resolves there.
- The disk: each worktree (`../next-<name>`, `.worktrees/<name>`), committed or not, with its `notes/handoff-*.md` on its branch, and the standing store `runs/store`.
- The repo's docs: AGENTS.md (the method and root's working rules), `docs/plan.md` (Now, In flight, THE PLAN), `docs/decisions.md` (the owner's rulings), `notes/merge-log.md` (every merge the merge session made), and `notes/root/` (root's notes, with its old memories in `notes/root/memory/`).

Nothing else does: no Claude session, its sidebar group, a running agent or a queued message.

## The first steps of a fresh root

Start the root session in the main folder itself (`~/src/seon-jolt/next`, or `~/src/seon-next` after the cutover), not in an app-made worktree. Root edits main's docs and owns the merges, and an app worktree's hook blocks writes outside it. Worktree sessions get their worktree from `bin/worktree NAME`, not from the app.

1. Read AGENTS.md whole, then `docs/plan.md`, `docs/decisions.md`, this page, and the tail of `notes/merge-log.md`.
2. Look at the state, without changing anything:
   - `git log --oneline -20`;
   - `uptime`;
   - `bin/root status`;
   - `git worktree list`;
   - the unmerged branches, with `git for-each-ref refs/heads/jolt/ refs/heads/claude/` and `git rev-list --count main..BRANCH`.
3. Read each live branch's handoff note: `git show BRANCH:notes/handoff-NAME.md`.
4. Write the owner a five-line state summary.
5. Start the sessions again, one per live branch below, in that branch's worktree, each briefed with its handoff note and AGENTS §3. Start one merge session in the main tree, briefed with AGENTS §3's merge paragraph and the queue. File them in one sidebar group, and use the model AGENTS §2 names for the task.

## Live branches

What is in flight, and on which branch, lives in one place: [plan](plan.md) "In flight". Root keeps it current, so this page doesn't repeat it.

Old branches that are probably superseded: ``jolt/land2``, ``jolt/land11``, ``jolt/land13``, ``jolt/wiki``, ``jolt/unitflake``, ``jolt/reach-scc``, ``jolt/nested-tick-budget``, ``jolt/agent-view-prototype`` and ``jolt/dry-run-merges``. The last is Seon's own merge of run #10, kept as evidence. Check one against main before reviving it. Never delete a branch: that's the owner's act.

## The order from here

1. The merge session merges health, then wiki-stale, then whatever reports ready.
2. Root holds main still, and rename-run merges; the store is erased.
3. self-contained merges, then step R: a fresh clone into `~/src/seon-next`, build, prove, a grep for `~/src/seon-jolt` that finds nothing, and the full run. Then the unmerged branches are fetched into the new repo, and work moves there (`.worktrees/<name>`).
4. Step E continues: a second paid real task, within the $50 budget, on a context that has been checked.

## The prompt for a fresh account

```
You are root for Seon on Jolt, restarting on a fresh account: no sessions, memory or transcripts carried over. Work in ~/src/seon-jolt/next (or ~/src/seon-next if docs/restart.md says the cutover is done). First read, in order: AGENTS.md (all of it), docs/restart.md, docs/plan.md, docs/decisions.md, and the tail of notes/merge-log.md. Then follow docs/restart.md "The first steps of a fresh root": look at the state, read each live branch's handoff note, write me a five-line state summary, then restart the sessions and the merge session. Delegate; investigate personally only what fits in a few tool calls.
```
