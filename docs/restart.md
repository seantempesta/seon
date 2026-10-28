# Starting a session

How a fresh Claude session picks up the work: the owner and one session work together in one conversation ([AGENTS](../AGENTS.md) §2), and everything the session needs is in git, the wiki and the plan.

## What carries over

What carries over between sessions: git, the disk and the repo's docs; nothing else.

- **Git:** `main`, any unmerged `jolt/*` topic branch, and the forks on GitHub (``seantempesta/<lib>``). Every pin of main resolves there.
- **The disk:** the main tree, the standing store `runs/store` (test data until Seon holds a user's data) and `target/`.
- **The docs:**
  - [AGENTS](../AGENTS.md): the method and the laws;
  - [plan](plan.md): the goal, where it stands, the one step now and its known problems;
  - [decisions](decisions.md): the owner's rulings;
  - this wiki: how the system works.

Not carried over: no other Claude session, no queued message, no working paper. A finding that matters is in the wiki, a task entry or a commit message.

## The first steps

The first steps of a fresh session, in the main tree (`~/src/seon-next`):
1. Read AGENTS.md whole, then [plan](plan.md), then the newest entries of [decisions](decisions.md). The newest rulings stand over older ones.
2. Look at the state without changing anything:
   - `git log --oneline --first-parent -15`;
   - `git branch --no-merged main`;
   - `uptime`;
   - `bin/root status`;
   - `git worktree list`, which should be just the main tree, plus `.worktrees/merges`, which the standing root writes back into.
3. Tell the owner in five lines what stands and what you'll do first: the first open problem of the plan's step, unless he names another.

## The prompt for a fresh session

The prompt that starts a fresh session, in full.

```
You are working on Seon on Jolt with me, in ~/src/seon-next. First read AGENTS.md (all of it), docs/plan.md and the newest entries of docs/decisions.md, then follow docs/restart.md "The first steps". Do the work yourself; use one-use Haiku and Sonnet helpers (or a fork of yourself) only as AGENTS.md §2 says. Tell me in five lines where things stand before you change anything.
```
