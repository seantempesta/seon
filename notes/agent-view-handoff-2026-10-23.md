# agent-view: hand-over (2026-10-23, the owner's account ending)

A fresh root or session restarts from the repo alone. Trust commits, files and numbers here; re-check before acting.

## Where

- Worktree `/Users/sean/src/seon-jolt/next-agent-view`, branch `jolt/agent-view` (the old worktree path: `bin/worktree --sync agent-view` now looks under `.worktrees/` and fails; run its loop by hand: for each `reference-code/<sub>` in `git ls-tree HEAD reference-code/`, `git -C <wt>/<sub> checkout -q --detach <sha>`).
- Tip: the hand-over commit on top of `165f3e1a` (merge of main at its 66 newer commits; pins equal main's: `git diff main HEAD -- reference-code` empty, Datahike `1880fcc`).
- Nothing of mine is running: worktree root stopped, test run stopped.

## What the branch does

The owner's agent debug window (THE PLAN step 3, "the owner can watch each turn"; merge queue item 7):
- renders are functions `(render v opts)` (`:as :inline|:block|:tile`, `:budget`, `:handle`, `:schema`, `:unit`, `:face`); a hiccup vector headed by a function is a call (Reagent's form, `seon.web.html/write!`); the printer shows a nested value by its declared render, both faces (`seon.render/nested`);
- each form's `;;` thought is its record's `:lead` (`seon.reader/lead`; a comment on the line a form ends on is that form's); the eval tool's `thinking` is kept per call; the scripted model may answer `{:code :thinking}` (`seon.fixtures/thinking-call`);
- `/agent/{id}` and `/attempt/{id}`: chat (head, conversation of turn and evaluation units in tx order, input: Enter evaluates, `>` messages, interrupt, release) and a right column of tiles, each its own unit, the last changed first;
- messages are facts: root writes `:seon.message/*` on main (from `"owner"`, to the attempt); the turn that takes it writes it on its branch and names it by id (`:message-ids`); one reader, `seon.context/taken-messages`; an observer's context read of a model-driven agent leaves the inbox (`seon.agent/inbox`, `basis!`'s `take?`, decided in `seon.server`'s `:context` op);
- `main-info` reads only what the page shows (no count of every datom).

## Done since the last hand-over (commits 56cc871e, 165f3e1a)

- A fresh-eyes Opus review found 4 bugs, 4 §9, 5 weak tests, 5 gaps, 1 cost. Fixed: the observer read taking the inbox; the two message readers; a trailing comment leading the next form; `ordered`'s 1000 ms reuse keyed by time (gone; `docs/clocks.md` updated); `record-key`'s `(or … 0)`s (now `record-ord`); the window fixture now writes the message as a fact; `message!`'s docstring.
- Refuted: the agent's drain missing `:dead` (`next-frame!` answers only `:none`/`:eof`). Kept with reason: the writer's `:branch-db` qualifying a branch name (the writer names the store's branches); the model fallback (it is the one resolver's default).
- Round 6's 4 window mismatches fixed: "turn n of max" plus "id …/n"; reply, thinking and thoughts labeled, "no reply text" for a forms-only model turn; the proofs tile names proofs and assertions apart; the column says "the last changed first".
- New test: `seon.agent-test/a-model-turn-records-its-thinking-and-the-message-it-took` (a real agent, scripted: the fact on main from "owner" to the attempt, the basis naming it, its text from the turn's commit, the call's thinking, the form's thought).
- Docs: `docs/render.md` (agent window: messages as facts, `/attempt`, `/turn?view=&id=`, tiles as units, list rows as units), `docs/agent.md` `:messages`, `docs/parity.md` rows 38 and 74, the config comment, the design note's "as built". Task `seon.render/a-debug-view-of-any-agent`: plan as built, 21 proofs, each existing.

## Left before the full run and merge (in order)

1. **The six namespaces' result.** `bin/test seon.agent-test seon.render-test seon.context-test seon.repl-test seon.server-test seon.model-test` was running on 165f3e1a and was stopped by the account's end: no verdict. Run it first. The earlier run on the pre-merge tree had most in-agent tests red only because that main's `seon.main` built a binary without the proc namespaces (fixed on main); the reds of my own (`record-ord`'s `subs`, the lead regex) are fixed.
   - Watch `seon.render-test/one-printer-and-no-cut-before-it`: it also flagged `seon.main/shown` and `seon.check/shown` (main's names; check whether main's newer commits fixed them).
2. **The Sonnet self-review** (root's process, AGENTS §3, e389072a): two reviewers were launched over the diff (render/web; messages/turns/REPL) and ended with the account. Run them again, fix every finding, and say "self-review: N findings, all fixed".
3. **`main-info`'s cost proof** (`test/seon/render_cost_test.clj` `main-info-reads-what-the-page-shows`) counts calls only, which a walk over every datom would not change, and its 100-1000 records sit on a seed of ~5k datoms. Make each n a unit of ~20 records so records dominate (20k records, 60k datoms at 1000), and state `:ticks`/`:bytes` per n measured at the REPL so a full datom walk turns it red.
4. `bin/prove` once; `bin/check health`; one Haiku browser round on a fresh scripted root (`notes/browser-tester.md`), measuring the render proc's `:render-ms` per pass on the window (the per-event rebuild: measure before any reuse, the owner's ruling).
5. The full run (`bin/test --all` + `bin/check 100`, niced), once; report agent-view ready to the **merge session**, not root, with "self-review: N findings, all fixed", the tip and the verdicts.

Known open, stated (not blocking): root delivers a message before the writer commits its fact; another agent's sends aren't facts; the first paint before the branch is read says "being read"; the window value is rebuilt every pass (to measure).

## The owner's UI direction (2026-10-23, not built)

Root's page becomes the same window as an agent's: one main pane (the conversation and scrolling temporary data: the owner's messages, root's evaluations, agents started and ended, merges, tasks done or failed; the same input), and a right column of live tiles: one per running agent (its own tile render, compact by CSS, changing as that agent changes: task, status, its newest thought and form, turn n of max, proofs, cost; linking its window), problems, tasks, main/writer/fork-server health; ended agents collapse into one tile. One render for "an agent, now" (`:as :block` the window's head, `:as :tile` everywhere else) replaces `seon.render.ui/agent-card` and the grid (two producers of one view today, a §9 mistake). Estimate +150/−200 lines. Undecided: in this branch before the full run (recommended) or as its own task.

## The next task (root's, after agent-view merges): work done recorded per call kind

New branch off main. Every call kind records its work done as facts (ticks, bytes, calls): turns, effects, Flow steps, merges, builds and REPL evals; each REPL eval's result also says which results it reused and which it computed, with each miss's cost (the owner's ask; `seon.derive`'s second ruling). Today only cost proofs record ticks, and `bin/laws measures` counts only wall-clock reads. Root's audit: `/Users/sean/src/seon-jolt/next/.claude/worktrees/repo-overview-prioritize-5dd84e/notes/timing-audit-2026-10-23.md`. Use `jolt.host/ticks-of`, record through the one record path, render through the one printer, add a law that every call kind records all three, and write the task first.

## Handed off, not mine

- `seon.derive/one-content-keyed-derivation` (`resources/seon/tasks/seon.derive.edn`): root's and the owner's gate. Its cold-path defects (`d/db-with`'s 48 KB a datom, `import-tx`'s allocation, `reach-index` twice a plan) are pure-tests'.
- Health's `dead-citations` builds a set of every var name per read: health's.

## Mistakes not to repeat

- After a merge, `git diff main HEAD -- reference-code` must be empty (a `git add -A` once staged stale submodule checkouts over main's pins).
- One transform per file, then grep what it touched.
- Jolt's `fn?` is true for a keyword.
- A cost proof must state a dimension that can fail (calls, bytes, ticks), at sizes where n dominates.
- A clock read moved into a new function is named in `docs/clocks.md`.
- `subs` in a display namespace is a cut the one-printer test flags, even when parsing an id.
- Editing files while `bin/test` runs: "the tree changed since the plan: no result stored". Commit, then run.
