# agent-view: hand-over (2026-10-23, before a context compaction)

The owner's assessment, 2026-10-23: "this is pretty rough and isn't well tested". Read this with fresh eyes. Trust the facts below (commits, files, measured numbers) over the summaries, and re-check before acting.

## Who and where

- **Me:** worktree session agent-view, working in `/Users/sean/src/seon-jolt/next-agent-view` on branch `jolt/agent-view`. I report to root (session `local_a9a8af72-0386-4e4d-a842-4564ae3396bc`, "Root: orchestrate, review, merge").
- **Two roles:** I work root's plan, and I also answer the owner directly in this session.
- **Tip:** `d13332a`, with main `e2d69fe3` merged in at `8c9abd5`. Main's pins were restored in `86716b1` after my own merge mistake (see "Mistakes" below).
- **My root:** ports 8610 (server) and 8611 (dashboard), `SEON_PROFILE=scripted bin/root start`, fresh store under `runs/`. Running now, with the calc tasks done and a6 attached from outside to calc/abs (attempt `calc/abs#1`).
  - Stop it with `bin/root stop` when done; leave nothing running.
  - Its gitignored `config/local.edn` uses `:seon.config.root/server-port` (rename pass 2's key).
- **Rules:** AGENTS.md, and the owner's ruling of 2026-10-23: iterate at the REPL, `bin/prove` once per finished step, no `bin/test --all` or `bin/check 100` without root's go, report numbers.

## What the branch holds (built)

1. **Renders are functions of a value and its opts** (`seon.render`, commit `2814928`).
   - A render is `(render v opts)`. Opts are optional props: `:as` (`:inline`, `:block` or `:tile`), `:budget`, `:handle`, `:schema`, `:unit`, `:face`.
   - An `:html` render answers hiccup; an `:ai` render answers `{:text :truncated?}`.
   - A hiccup vector headed by a function is a call (Reagent's form), expanded by `seon.web.html/write!`. Jolt's `fn?` is true for a keyword, so keyword heads are excluded first.
   - `docs/render.md` opens with the React explanation.
2. **The printer auto-renders, both faces** (`ba3b159`). `seon.print/print-value` takes `:render`; `seon.render/nested` shows a nested value that has a declared render as that render, on one line.
3. **Thought beside forms** (`1b73475`).
   - `seon.reader/lead` gives each form its `;;` comments, kept as the reply item's and the record's `:lead`, and as a REPL defn's `:form/lead`.
   - The eval tool takes an optional `thinking`, recorded per call.
   - A turn's calls name their records by `:items` (record ids). Every item carries `:record`.
4. **The agent window** (`/agent/{id}`, and `/attempt/{id}`, which is stable across boots).
   - **Chat:** the head (why the agent is as it is, a summary sentence, cost), then the conversation.
   - **Conversation rows:** every turn and every evaluation outside the turns is its own unit appended into `#z-conv`, in write (tx) order.
   - **Each turn shows:** what it was told, its reasoning, its reply, then per form the thought → form → result; then tokens, time and proofs as it began; then the context it read, remade from its basis, with section token counts and the whole request at `/turn?view=&id=`.
   - **Input:** one textarea. Enter evaluates; a line starting with `>` sends a message; ⏹ interrupts; ▶ releases a held task.
   - **Right column:** tiles for the task, proofs, branch and problems. Each tile is its own unit whose id carries its change number, so a change sends one tile, prepended (`20dee62`).
5. **Messages are facts** (`5bff4af`, root's ruling).
   - Root writes the owner's message on main as `:seon.message/*`: from `"owner"`, to the attempt id, which root's view now keeps for each agent.
   - The agent writes the same fact on its branch when its turn takes it (`seon.turn/basis!`). The basis holds `:message-ids`.
   - `seon.context/current-state` and `seon.render.proc/messages-of` read the text from the commit.
6. **`main-info` reads only what the page shows** (`61e63a9`). There is no count of every datom. The main tile shows definitions, and the datom count is on demand at `(:main (c/state sys {:datoms true}))`.
7. **Cost proofs** (`test/seon/render_cost_test.clj`):
   - render calls: 11 calls a node, linear;
   - the printer's nested renders: 19 calls each, linear;
   - `main-info`: 26 calls, constant.
8. **The cache task** `seon.derive/one-content-keyed-derivation` (`d13332a`), sent to root for the owner's gate.
   - The line count says the general mechanism would grow `src` (+50–90 against −38–45), so it is not to be built.
   - Its net-negative first step stays: test results as facts only (delete the `../test-results` file store and its callers, about −38 lines), and `ordered`'s 1000 ms term (−3).
9. **Notes:**
   - `notes/agent-view-design-2026-10-23.md`: research and design.
   - `notes/browser-tester.md`: the reusable browser-tester brief.

## Verdicts so far

- `bin/prove` on `8c9abd5` (after the pin restore): 394 tests, 1 red (`main-info`'s proof stated only ns); laws `measures` red (`seon.root/deliver-message` unnamed). Both fixed in `5f38b08`.
- `bin/prove` on `20dee62`: **PROVE OK**. 357 tests ran, all green; 37 reused; 2 check steps green.
- Browser rounds 1–5 (Haiku facts, Sonnet usefulness) found 7 → 6 → 5 → 3 → 10 small window mismatches. All were fixed.
- **Round 6 (Haiku, on the new build): 4 window mismatches, none yet fixed.** The message-fact flow and the `;;` thought above its form both checked out live: the owner's message is a fact on main, from `"owner"` to `calc/abs#1`, and it shows under "it was told". The 4:
  1. Turns are labeled "turn 1/2" while their ids are `…/0` and `…/1`. Say the label is the context's 1-based count, or show the id.
  2. A scripted turn's prose is in its call's code as `;;` leads, and `:seon.turn/reply` is `""`. The page shows the leads as thoughts, but the tester read them as the reply. Label thoughts as thoughts, and show "no reply" when it is empty.
  3. The proofs tile says "0 pass · 3 fail" (assertions) beside "0/2 pass" (proofs). Name the unit: "2 proofs fail (3 assertions)".
  4. Branch moved above proofs after a read, though both changed. The rule (the last changed is first, ties by written order) is right but not visible. Mark the changed tile, or say the rule.
  
  Its usefulness notes: the alloc vs mem numbers are unexplained; "ui", "outside", "t …" and "fork" are unlabeled; a turn's total ms isn't shown; and observers' context reads count as reads.
- **The full run (`bin/test --all` + `bin/check 100`) has not run.** Root's order: after the Haiku verdict, once, at the end. Report each verdict to root.

## Updates the code and docs still owe (none done yet)

### Docs out of date with the code

1. `docs/render.md#the-agents-window`:
   - It still describes the message bar as in-memory delivery into the basis. Now: messages are facts, the basis holds ids, and `messages-of` reads them.
   - It still says the whole request is at `/agent/{id}/turn?id=`. Now: `/turn?view=&id=`.
   - Its tile column still lists "the agent's card". The card tile was removed; each tile is now its own unit, prepended on change.
   - It doesn't mention the textarea or the `>` routing.
2. `docs/render.md`'s "Not shown yet" line: keep the threads part; add agent-to-agent sends not yet facts (item 6 below).
3. `docs/agent.md`'s `:messages` line: say a message is a fact, read again from the commit, `{:from :body}`, with from `"owner"` or an attempt id.
4. `notes/agent-view-design-2026-10-23.md`: §4.3 (pages) and §6 (size) describe the plan before the build. Mark what was built differently:
   - no pushState: plain links do it;
   - tiles are units;
   - the conversation is units;
   - there's no `:as` declaration map;
   - messages are facts.
5. The task `seon.render/a-debug-view-of-any-agent` (`resources/seon/tasks/seon.render.edn`):
   - Its plan text is the prototype's. Rewrite it to what was built: renders as `(value opts)`, the window, messages as facts, `/attempt`.
   - Its proofs set lacks the newer tests:
     - `seon.render-test/the-printer-renders-each-nested-value-by-its-own-render`;
     - `the-conversation-appends-new-rows`;
     - `one-tiles-change-sends-one-tile`;
     - `an-attempts-window-is-its-agent-as-that-attempt-ran`;
     - `seon.context-test/a-taken-message-is-read-from-its-fact`;
     - `seon.repl-test/a-forms-lead-is-recorded-with-its-eval`;
     - `seon.model-test/the-eval-tool-takes-thinking`;
     - the three `seon.render-cost-test` proofs;
     - `seon.web-test/a-vector-headed-by-a-function-is-a-call`.
   - Check that every proof it names exists.

### Code gaps (known, not built)

6. Agent-to-agent sends (`my.agent/send-message`) are not facts. They stay inline in the basis's `:messages`. The schema says the sender writes them on its own branch.
7. `seon.render.proc/ordered` still reuses for 1000 ms, a reuse keyed by time, which §5 forbids. It's in the derive task; it's a 3-line fix in my own code.
8. The first paint of a page whose branch isn't read yet says "being read". The page is answered before the branch read lands, then patches. That's acceptable, but a one-shot fetch (curl) never sees the turns.
9. The `seon.turn/work!` record row sorts after the turns it drove, by tx order. It's labeled as root's request, but it reads oddly.
10. Threads and uptime aren't reported: task `seon.agent/an-agent-reports-its-threads`.
11. `/ns/{ns}` and `/data` aren't built: land14's task `seon.render/namespace-agent-debug-and-data-pages`.
12. `main-info` was re-measured only on the test seed (1.4–1.8 ms flat to 155k datoms), not on the owner's main, where 174 ms was profiled. Re-measure there.
13. `problems/health`'s `dead-citations` builds a set of every var name on each read: O(vars), health's code. Reported to root.
14. Main's own red: `seon.render-test/one-printer-and-no-cut-before-it` flags `seon.cost/sizes` (from `0b27347`), unnamed in `docs/render.md#one-printer`. It was green in my last prove, so check whether main fixed it.

### Weakly tested (fresh eyes: here first)

15. There's no end-to-end test that a real agent turn writes the message fact on its branch and records `:message-ids`.
    - The server test covers delivery to the inbox.
    - The context test covers ids → text on a hand-built db.
    - `seon.turn/basis!`'s transact-then-commit order is untested.
16. There's no test that the `thinking` argument goes from a model reply through `act!` into `:seon.turn/calls`. Only `call-code` and `wire-message` are tested.
17. The window's whole-page keyframe (composed with conversation and tile units) has no test. `render-step` covers root's view only.
18. The browser rounds are manual, by subagents on `notes/browser-tester.md`. None are automated.
19. Every window test runs on hand-built fixtures (`with-turns` in `render_test.clj`). The live runs used the scripted model only; no real model turn has rendered in the window.
20. The rows and tiles caveat: each pass rebuilds the whole system value and root's AI context, and compares every unit's input by equality. That's work per event that grows with the system (not polling).

## Mistakes made here (don't repeat)

- **`git add -A` after a merge staged the worktree's stale submodule checkouts**, reverting main's pins in `8c9abd5`.
  - Do: after a merge, `git diff <main> HEAD -- reference-code` must be empty, then `bin/worktree --sync agent-view` from the main tree.
  - The revert check must include `reference-code/`.
- **Rerunning my own rewrite script on an already-migrated file** turned metadata `{:seon.render/schema …}` into `{:schema …}`, and it unwrapped the layout's skeleton read. Do one transform per file, then grep what it touched.
- **`fn?` is true for a keyword on Jolt.**
- **A cost proof stating only `:ns` can't fail.** Count calls, bytes or ticks. Proofs measure only up to `:seon.config.proofs/cost-max-n` (1000).
- **Moving a clock read into a new function needs its name in `docs/clocks.md`** (laws `measures`).

## The cache task (seon.derive): handed off, not mine to build now

- `resources/seon/tasks/seon.derive.edn` at `cd924f7` is finished and sent to root, which takes it to the owner. It holds:
  - three owner rulings: reuse every unique, immutable, expensive result; caching is no excuse for slow cold code; if it's fast enough, don't cache;
  - root's design: the shared directory `<dir>/<kind>/<digest>.edn` as the content-addressed tier across checkouts, facts inside one root, and the directory gone once worktrees are agents of one store;
  - pure-tests' cold costs: `import-tx` 2.2 s and 3.5 GB; `d/db-with` 5.4-6.3 s at 48 KB a datom; `reach-index` twice a plan, 650 ms each;
  - the line table: ~70 lines of reuse in src, ~25 in test and bin.
- **Offloaded:**
  - the cold-path defects (`d/db-with`'s 48 KB a datom, `import-tx`'s allocation) are **pure-tests'** (session `local_c9ae8597-80db-412a-ac03-35194fe08e84`), working them now;
  - the gate and the owner's decision are **root's**.
- **Nothing for me to do on it** unless root sends it back.
- Reported to their owners, not mine:
  - main's own red: `seon.cost/sizes` is unnamed in `docs/render.md#one-printer`;
  - health's `dead-citations` builds a set of every var name per read.

## The next steps, in root's order

1. Read the Haiku round 6 report (or rerun it on `notes/browser-tester.md`); fix what it finds; `bin/prove` once.
2. Ask root for the full run (`bin/test --all` + `bin/check 100`, niced), run it once, report the verdict.
3. Then the doc and task updates above (1–5), and the cheap code gap 7 (`ordered`).
4. Leave nothing running: `bin/root stop`; check `ps` for `next-agent-view` processes.
