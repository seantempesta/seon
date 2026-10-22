# Common brief for every shrink worktree session (wave 1, 2026-10-22)

You are a worktree session: an independent agent working in your own git worktree, with your own build, root and stores. Root (the orchestrating session) launched you and lands your branch after you report. You make your own decisions inside your write set.

Read first: `~/src/seon-jolt/next/AGENTS.md` (vision, who does what, laws: no guards or fences, no clocks deciding anything, only documented config, contracts on every function, effects through their owner, every definition once), `next/docs/landing.md` ("A session works in a worktree of its own"), and your items in `next/notes/shrink-convergence-2026-10-22.md` and/or `next/notes/shrink-dead-code-2026-10-22.md`.

Goal (owner): shrink the code by converging on reusable ideas; new features should take minimal updates. Every function you add names the existing system it extends. Net `src` lines go down.

Workflow:
1. From the main tree: `cd ~/src/seon-jolt/next && bin/worktree <your-lane-name>` (makes `../next-<name>` on `jolt/<name>`). Work only there.
2. `cd ../next-<name> && nice -n 15 bin/build seon` once; rebuild only when the binary must reflect src (checks, agent tests); `bin/test <ns>` runs from source.
3. Tests first: the guarding tests named in the report must be green before and after; add a test only where a mechanism is new. Run single namespaces (`nice -n 15 bin/test seon.x-test seon.y-test`) and single check modes (`SEON_STORE=$PWD/target/check-store bin/check <mode>`), never a full `bin/check 100` (root runs that on the batch).
4. Do not delete on `my.agent/callers` alone: the compiler facts miss syntax-quoted and quoted symbols and macro uses (they call 25 live dashboard fns unused). Confirm with grep across src, test, resources, bin and docs.
5. Stay inside your write set. If you need another file, stop and report instead of editing it: other worktree sessions are editing in parallel.
6. Commit on your branch with explicit paths (never `git add -A`); each commit message says what converged or was deleted, the proofs, and its net `src` lines; end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Update the wiki section your change concerns in the same commit.
7. Machine safety: nice -n 15; at most one check mode at a time; never touch `~/src/seon-jolt/next/runs/store` or the standing root (7888); kill only PIDs you started (never pkill/killall); `bin/root stop` if you started your root. Before reporting, confirm nothing of yours runs (`pgrep -fl next-<name>`).
8. Report: branch, commits, net src (and test) lines, tests run red/green, anything you could not do and why, anything you found that another worktree session or root should know. Do NOT remove your worktree: root lands it and removes it.
