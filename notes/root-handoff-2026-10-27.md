# Root's handoff (2026-10-27, morning)

What a fresh root needs in one read, beyond AGENTS.md, docs/plan.md and docs/decisions.md (read every 2026-10-26 and 2026-10-27 entry: they changed the direction several times, and the latest stand). History is in notes/merge-log.md and git. The sessions that did last night's work are gone; everything they knew is in the task files, the notes named below, and their branches.

## Read first, in this order
1. AGENTS.md (all), docs/plan.md, docs/decisions.md (the 2026-10-26 night and 2026-10-27 entries).
2. This file.
3. notes/merge-audit-2026-10-27.md, notes/merge-design-review-2026-10-27.md and notes/merge-probe-2026-10-27.md (on jolt/merge-state until it merges), and notes/session-state-2026-10-27/ (each session's own last state, copied from the app's shared notes) (the merge work: an audit, a design, and an independent review that cut it).
4. The last 40 lines of notes/merge-log.md, `git log --oneline --first-parent -30`, `bin/root status`, `uptime`.

## The state
- **First act for the next root:** run `nice -n 15 bin/build && nice -n 15 bin/prove --merge` on main. The last merges (jolt/a-flaky-digest-is-red, jolt/merge-state, jolt/root-repair-playbook) passed their own sessions' runs and a plain `bin/prove`, but main's `--merge` of the flaky change was stopped to commit this handoff. `bin/update` ran after them (08:45, 2026-10-27), and root's main is current (checked: seon.ratom's 52 vars present, seon.repl/draw-part gone).
- Main is green under `bin/prove --merge` (every test and check step the change reaches, process tests included, contracts and smoke, nothing left out), except one known red that comes and goes: seon.merge-cost-test/a-three-way-costs-the-changes-not-the-program, a 96-byte runtime variance when another thread allocates (task seon.cost/a-call-allocates-the-same-bytes-each-time, held). The flaky-results change (merged last) now marks a digest that was both green and red as :flaky.
- Pins: Chez fa6a4c73; Jolt 07d9655d; Datahike cc614f4e; LMDB 15420ce; http-client e866277d.
- **The standing root's main program was stale** until `bin/update` ran at 08:45 on 2026-10-27: config/local.edn points :seon.config.source/root at .worktrees/merges (jolt/merges), and only bin/update's step 2 fast-forwards it. Last night root restarted with `bin/root stop/start` and never ran bin/update, so main's program froze at 4436167591 for 262 commits. The fix at the cause is on jolt/the-source-tree-follows-main (task in seon.root/seon.fx's file): the :source effect fast-forwards the write-back branch when it is an ancestor of main, and records a problem when it holds commits main lacks. **Until it merges, restart root after a merge with `bin/update`, never `bin/root stop/start`.**
- Paid model runs: $30 overnight approved by the owner, only after a free audit of each request's context finds none of its known problems. Two audits ran; neither was clean (notes/known-context-problems-2026-10-26.md; the second audit's file is in the live-agents session's scratchpad, its findings are in tasks). None has run. Nothing spent.
- How merges work today: root merges a branch into main with git in the main checkout, runs `nice -n 15 bin/build` then `nice -n 15 bin/prove --merge` (it holds main's lock, writes its own line to notes/merge-log.md), reads every red it names before keeping or reverting, then `bin/update`. A worktree session's own `bin/prove --merge` takes no lock.

## Branches waiting to merge (verified by their sessions; root verifies each on main with bin/prove --merge)
- jolt/an-incremental-index-equals-the-full and jolt/an-index-is-observed-at-the-cost-of-what-changed: both already merged into main (check with git branch --merged).
- jolt/merge-state (live-agents): task entries and notes only (merge audit, review, probe, both context audits). Fix the (b) line first, see below.
- jolt/root-repair-playbook (agent-loop): task entries only. A subagent may have left a root up in .worktrees/root-repair-playbook: root 39105 stopped (exit 0) there, then check for processes with that cwd.
- jolt/a-cited-vars-tests-are-its-own-query (ctx-atom): tests-reaching plus its task entries; was verifying when the session ended. Re-run bin/prove --merge in its worktree before merging.
- jolt/delete-staged-kinds: abandoned by the owner's ruling (staged kinds kept); delete it.

## What is in flight (branches; each has its task entry with what's left)
- jolt/the-source-tree-follows-main: main's program follows the checkout (above). Approved; building.
- jolt/every-page-is-its-parts (3b-root, tip ffc7b0d05d): 3530d5dfb9 fixes the three defects below, each proved red first; ffc7b0d05d is work in progress (root's page as the render of root's ctx: root-ctx, root-walk, page-chrome), compiled but never run or proved; next is to run it on that worktree's root and check every page live in the browser, then the process tile. A root built from it may still be running there: `bin/root stop` in .worktrees/every-page-is-its-parts. The plan in the task entry: root's page and /root/debug through the one walk over root's ctx; root's process numbers and its procs' defects become part of root's ctx (owner's ruling via root, option a); fixes: /agent/user/debug never answers, an outside read's turn says "in flight", a page click is recorded as a root eval. Delete seon.render.system and the old unit walk.
- jolt/a-cited-vars-tests-are-its-own-query then tests-reaching (`(my.agent/tests-reaching 'v)` answering from prove's own reach graph), and the instruction text swaps `(my.agent/callers 'ns/f)` for it (live-agents' file).
- Merge work (live-agents): the owner's ruling "drop with-merge from task files; a work task merges itself through the one gate", and the :seon.config.source/branch mismatch (default.edn jolt/seon-merges, config/local.edn jolt/merges). **Keep Seon's merge path whole** (octopus, the gate, merge tasks' machinery): owner, 2026-10-27, "we haven't used the merge code because our context was shit and agents didn't have instructions. Don't throw out the baby with the bathwater." Three experiments on a worktree root decide whether Claude worktree branches can come through Seon's merge: import fidelity, digest equality, and an end-to-end hand-back of a real branch whose write-back tree must equal the branch (file modes included).
- Root's repair playbook (jolt/root-repair-playbook): stage a conflict, a red merged tree, a :behind run, a failed write-back on a worktree root and repair each from root's REPL; the working forms go into docs/merging.md.
- jolt/the-request-is-the-page-and-its-diffs: build 4, the model's request from the render of ctx and its two diffs (deletes the ~280 ms / 300 MB per-turn ctx rebuild). Proofs written red; waits on 3b-root.

## The design to hold (all in decisions.md)
- One rendering system: ctx is the agent's data pulled by a stored pattern and patched per transaction; a render is `(fn [opts & children] hiccup)`; render-ctx is the layout; the model reads the same hiccup; nothing derived is stored; styling is the render's name as class, the style added only to the page's HTML. The render never repeats what an eval shows: prose plus the eval's handle, the value drawn when opened.
- One whole task per request by default; the task's render carries its instructions; cited set is the program's own names; a cited function's tests are the render's own query; deeper reach is `(my.agent/tests-reaching 'v)` at the REPL, not on the page.
- Merges: Seon merges by definitions, not lines. A conflict is two branches changing the same var, resolved per var (`my.branch/unmerged`, `my.branch/resolve!`), and the tests to rerun are the ones whose digest the change moved. Use that strength; don't fall back to text merges where the store can answer.
- Stability first; a red on main gets an owner within the hour; no regression without a stated reason; no timer decides anything; no allowance where a measurement is wrong (fix the measurement).

## Root's mistakes to not repeat (from last night)
- Named test lists missed render-test twice (a red sat on main): use `bin/prove --merge`, never a hand list.
- Restarted root with stop/start instead of bin/update, so main's program went stale for 262 commits and nobody noticed for hours. Check `(seon.generation/generation)` and a just-merged var's entity after every restart.
- Over-read the owner once ("not show the same eval data" became "no eval data"); ask when a ruling can be read two ways.
- Removed a session's empty experiment worktree while clearing merged ones: check with the session, not just git status, before removing a worktree a live session may use.
- A verification gate on the 15-minute load average starved main's verification for two hours: the main-first lock replaced it.
