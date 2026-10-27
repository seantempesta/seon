# Restarting the work on a fresh account

How a fresh root picks up the work when every Claude session, memory and transcript is gone (owner, 2026-10-23: the account changes, and only the disk and git carry over); [AGENTS.md](../AGENTS.md) §2, "Root's working rules", is the method, [plan](plan.md) the queue, and this page the steps to get back to work.

## What survives

What survives a restart on a fresh account: git, the disk and the repo's docs.

- Git: `main`, every `jolt/*` and `claude/*` branch, and the forks on GitHub (``seantempesta/<lib>``). Every pin of main resolves there.
- The disk: each worktree (`.worktrees/<name>`), committed or not, with its `notes/handoff-*.md` on its branch, and the standing store `runs/store`.
- The repo's docs: AGENTS.md (the method and root's working rules), `docs/plan.md` (Now, In flight, THE PLAN), `docs/decisions.md` (the owner's rulings), `notes/merge-log.md` (every merge the merge session made), and `notes/archive/` (the working papers whose facts the wiki now holds, kept as evidence). The rules root's old memories held are in AGENTS.md §8 and §9.

Nothing else does: no Claude session, its sidebar group, a running agent or a queued message.

## The first steps of a fresh root

Start the root session in the main folder itself (`~/src/seon-next`; from any worktree it is `git rev-parse --path-format=absolute --git-common-dir`, minus `/.git`), not in an app-made worktree. Root edits main's docs and owns the merges, and an app worktree's hook blocks writes outside it. Worktree sessions get their worktree from `bin/worktree NAME`, not from the app.

1. Read AGENTS.md whole, then `docs/plan.md` (Now, In flight, THE PLAN), `docs/decisions.md` (the newest entries stand: the owner's direction changed several times, so read every entry of the last two days), this page, and the tail of `notes/merge-log.md`. The newest `notes/root-handoff-*.md` is the owner-facing detail of the day before; what it held that outlives the day is below.
2. Look at the state, without changing anything:
   - `git log --oneline -20`;
   - `uptime`;
   - `bin/root status`;
   - `git worktree list`;
   - the unmerged branches, with `git for-each-ref refs/heads/jolt/ refs/heads/claude/` and `git rev-list --count main..BRANCH`.
3. Read each live branch's handoff note: `git show BRANCH:notes/handoff-NAME.md`.
4. Write the owner a five-line state summary.
5. Start the sessions again, one per live branch below, in that branch's worktree, each briefed with its handoff note and AGENTS §3. Start one merge session in the main tree, briefed with AGENTS §3's merge paragraph and the queue. File them in one sidebar group, and use the model AGENTS §2 names for the task.

## What a resuming root checks

A root resuming on the same account looks at the state once, in this order, then writes the owner five lines ([AGENTS](../AGENTS.md) §2): `git log --oneline --first-parent -15`, `uptime`, `bin/root status`, the sessions list, and the newest entries of `notes/merge-log.md`. Then the checks the earlier roots' mistakes taught:
- **After every merge, restart root with `bin/update`**, never `bin/root stop/start`, until the source tree follows main: the standing root imports main's program from the checkout `:seon.config.source/root` names, which only `bin/update` moves. Check `(seon.generation/generation)` and that a just-merged var's entity is on root's main.
- **A merge is `bin/prove --merge`, never a list of tests named by hand** ([merging](merging.md#a-merge-runs-what-the-change-reaches)); read every red it names before keeping or reverting. One red comes and goes, a three-way merge's allocation, and a digest both green and red is marked `:flaky`.
- **Main's verification goes first**: a worktree's heavy run waits on main's lock; no load-average gate stands in for it.
- **Ask when a ruling reads two ways**, and ask the session, not only `git status`, before removing a worktree a live session may use.
- **Paid model runs** wait on a free audit of each request's context that finds none of its known problems ([model](model.md#paid-runs)).

## Live branches

What is in flight, and on which branch, lives in one place: [plan](plan.md) "In flight". Root keeps it current, so this page doesn't repeat it.

Old branches that are probably superseded: ``jolt/land2``, ``jolt/land11``, ``jolt/land13``, ``jolt/wiki``, ``jolt/unitflake``, ``jolt/reach-scc``, ``jolt/nested-tick-budget``, ``jolt/agent-view-prototype`` and ``jolt/dry-run-merges``. The last is Seon's own merge of run #10, kept as evidence. Check one against main before reviving it. Never delete a branch: that's the owner's act.

## The order from here

The cutover is done (2026-10-23): `~/src/seon-next` is the repo, its main is next's history joined to Seon v1's (merge "Seon on Jolt replaces Seon v1", tag `v1-final`), and every live branch was fetched into it; the old tree `~/src/seon-jolt/next` is an archive. What is in flight is [plan](plan.md) "In flight"; THE PLAN there is the order.

## Restarting root on the same account

The sessions keep running: a new root does not restart them. They were started by the old root, so they are not linked to the new one: find them with list_sessions (group seon-jolt), message them by id, and read a session's state with list_events or by asking. What the old root learned is in the wiki, [plan](plan.md) and [decisions](decisions.md), not in its notes.

```
You are root for Seon on Jolt, taking over from an earlier root on the same account. Work in ~/src/seon-next.

Read first, in order: AGENTS.md (all of it), docs/restart.md, docs/plan.md, docs/decisions.md, the tail of notes/merge-log.md, and notes/perf-2026-10-24.md (yesterday's review: what regressed, what's slow). The sessions in docs/plan.md "In flight" are still running: find them with list_sessions (group seon-jolt), ask each for one line on where it stands, and write me a five-line state summary. Do not restart them.

Yesterday's lessons. Run the work this way from the start:

1. Fewer sessions, each with one clear job. At most 5 working sessions plus merge. Archive a session the moment its branches merge (ask me first). No session starts another without your go.

2. Every change earns its place before it merges. A branch's report states: what THE PLAN step it moves, what it replaces or deletes, its src line delta, and the measured value (turns to green, ticks, bytes, wall time). A merge that grows src with no measured value, or adds a namespace, a config key, a schema attribute, a proc, a route or a second way to do something, needs your gate first (AGENTS §3's four questions), with the evidence. "Nothing reads it yet" is a reason to wait, not to merge.

3. Review for value, not only for §9. The self-review and the merge reviewer each rate the branch: worth it, questionable, or overbuilt. For a questionable or overbuilt one, it says what to cut. Overbuilt goes back. Prefer deleting to adding; a fix that adds a layer to work around another layer is the wrong fix.

4. No regressions get through. The merge session runs bin/prove plus the process tests the change reaches, the cost proofs on any pin bump, and leaves greens stored at main's digest (check that bin/prove on main afterwards reuses everything). Once a day, and before any freeze or cutover, the full run (bin/test --all, bin/check 100) on a clean main checkout. A red on main gets an owner and a task within the hour, and nothing that touches its area merges until it is green. A flaky test is a defect: fix its cause, never re-run it.

5. Measure before and after. Every perf or cost claim carries numbers from the same input before and after; every "faster" is checked by an independent run. Keep notes/perf-<date>.md current; a regression found there is a task the same day.

6. An independent audit every 10 merges or once a day, whichever comes first: an agent that hasn't seen the work reads the merges since the last audit, against the rules above, and files what to cut. You assign the cuts.

7. THE PLAN first. The goal is Seon agents doing real work (step E), measured as turns to green. Today that means: the standard chat format with the live state at the end of each turn, built into seon.turn (agent-loop); the context render's whole-index rebuild per turn (~10x, perf note); the five corrections in the audit. Anything else waits unless it unblocks these.

8. Keep it simple, and keep me out of the loop except for rulings: one decision at a time, priced, with your recommendation. Record each ruling in docs/decisions.md the moment I give it, and put lessons in AGENTS.md so every session follows them.

Start by writing rules 1–6 into AGENTS.md §2 and §3 (one short paragraph each, citing this restart), so every session reads them. Then assign today's work.
```

## The prompt for a fresh account

The prompt that starts a fresh root on a new account, in full.

```
You are root for Seon on Jolt, restarting on a fresh account: no sessions, memory or transcripts carried over. Work in the repo's main folder (~/src/seon-next; if it does not exist, step R has not run: it is the folder `git rev-parse --path-format=absolute --git-common-dir` names, minus /.git). First read, in order: AGENTS.md (all of it), docs/restart.md, docs/plan.md, docs/decisions.md, and the tail of notes/merge-log.md. Then follow docs/restart.md "The first steps of a fresh root": look at the state, read each live branch's handoff note, write me a five-line state summary, then restart the sessions and the merge session. Delegate; investigate personally only what fits in a few tool calls.
```
