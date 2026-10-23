# Restarting the work on a fresh account

How a fresh root picks up the work when every Claude session, memory and transcript is gone (owner, 2026-10-23: the account changes, and only the disk and git carry over); [AGENTS.md](../AGENTS.md) §2, "Root's working rules", is the method, [plan](plan.md) the queue, and this page the steps to get back to work.

## What survives

- Git: `main`, every `jolt/*` and `claude/*` branch, and the forks on GitHub (``seantempesta/<lib>``). Every pin of main resolves there.
- The disk: each worktree (`.worktrees/<name>`), committed or not, with its `notes/handoff-*.md` on its branch, and the standing store `runs/store`.
- The repo's docs: AGENTS.md (the method and root's working rules), `docs/plan.md` (Now, In flight, THE PLAN), `docs/decisions.md` (the owner's rulings), `notes/merge-log.md` (every merge the merge session made), and `notes/root/` (root's notes, with its old memories in `notes/root/memory/`).

Nothing else does: no Claude session, its sidebar group, a running agent or a queued message.

## The first steps of a fresh root

Start the root session in the main folder itself (`~/src/seon-next`; from any worktree it is `git rev-parse --path-format=absolute --git-common-dir`, minus `/.git`), not in an app-made worktree. Root edits main's docs and owns the merges, and an app worktree's hook blocks writes outside it. Worktree sessions get their worktree from `bin/worktree NAME`, not from the app.

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

The cutover is done (2026-10-23): `~/src/seon-next` is the repo, its main is next's history joined to Seon v1's (merge "Seon on Jolt replaces Seon v1", tag `v1-final`), and every live branch was fetched into it; the old tree `~/src/seon-jolt/next` is an archive. What is in flight is [plan](plan.md) "In flight"; THE PLAN there is the order.

## The prompt for a fresh account

```
You are root for Seon on Jolt, restarting on a fresh account: no sessions, memory or transcripts carried over. Work in the repo's main folder (~/src/seon-next; if it does not exist, step R has not run: it is the folder `git rev-parse --path-format=absolute --git-common-dir` names, minus /.git). First read, in order: AGENTS.md (all of it), docs/restart.md, docs/plan.md, docs/decisions.md, and the tail of notes/merge-log.md. Then follow docs/restart.md "The first steps of a fresh root": look at the state, read each live branch's handoff note, write me a five-line state summary, then restart the sessions and the merge session. Delegate; investigate personally only what fits in a few tool calls.
```
