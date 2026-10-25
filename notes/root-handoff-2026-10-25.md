# Root's handoff, 2026-10-25

What root did on 2026-10-24/25, what is unfinished, the owner's newest ideas, and the lessons; the rulings themselves are in docs/decisions.md ("2026-10-24 rulings", "2026-10-25 rulings"), the plan in docs/plan.md, the research in `notes/research-2026-10-25/`.

## Merged to main (each verified: bin/prove with its plan line compared to main's, process tests, smoke; see notes/merge-log.md)
- Fixes: context render observes what changed (92M → 1.3M ticks a turn); no-op `bin/build` 31 s → 1.4 s; `bin/check mcp-proof`; cost proofs one verdict under load (Chez fork, pin fa6a4c73); prove runs keyed by the digest of what they read.
- Root one-system cases 1-2: the dead merge path deleted; one prove path (one per-test tick bound); a platform error closes when its cause is gone; reds reused by digest.
- The agent view and context (design `notes/design-agent-view-2026-10-24.md` r3): function-calling conversation, prose-first eval call, screen evals, records store values printed once by the one printer, render once per set of reads (Datahike `with-reads`, pin 6dc9ce64), `/agent/{id}` chat and `/agent/{id}/debug` read back from `:seon.turn/sent`, the short system prompt `docs/prompt.md` (3,329 → 405 tokens a request; a 7-turn run 45.6k → 25.1k prompt tokens), short stage instructions, the five fit tasks as user requests (`seon.fixtures/fit-requests`, not run).
- `bin/check --steps` seeds when `steps-binary` is missing (the "durable red" was this).

## Unfinished, with owners
- **Agent loop** (session "Agent loop"): design `notes/design-agent-loop-2026-10-25.md` on ``jolt/agent-loop``; waits for the owner's rulings (docs/plan.md "Waiting on the owner"), then 6 parts: static fork server, session-scoped records, park and wake, one send, resume, screen diffs.
- **Root as one system** (session "Root as one system"): the judge asks the test service (proving); then the LMDB open flake (`seon.test-store/a-process-root-opens-the-shared-store-under-load`); then cases 3-4 after the loop's park and send parts. Case 4's approved plan: task `seon.reconcile/root-work-is-tasks` (root tasks admitted like any task, started first; `:root-merge` messages the parked session; measure `seon.problems/caused` per merge first).
- **Measurement:** the request pilot and 5 × 3 runs (~$0.50) wait for the owner's go. Step 6's numbers: `notes/research-2026-10-25/` and the agent-conversation measure notes (merged under `notes/measure/`).
- **Filed follow-ups** (task entries): `seon.program/a-contract-check-does-not-reach-every-render` (247 pure tests left out of bin/prove), `seon.program/a-kept-trees-program-is-its-trees-import` (a broken import carried forward), `seon.db/a-one-eval-commit-costs-what-it-writes` (3 MB per commit), `seon.render/a-walk-counts-its-rows-flat` (~6 KB a row), `seon.render/a-late-agent-gets-its-screen` follow-up (browser pages read a lagging main), the agent branch's own reds rerun at its judge.
- Parked branch: ``jolt/durable-read-runs-alone`` (half-merged by an archived session; main has most of it).

## New ideas from the owner (2026-10-25), not yet specified
1. **A `ctx` atom:** each session mirrors the agent's data tree from the db as a schema-validated map in an atom; a listen on the db swaps in just the diff; the agent changes its context by `swap!` on it, written back atomically through an eval; removing a context chunk is removing a key. Questions to research at the REPL: the db ↔ map conversion (pull patterns from the schema's `:seon.render/units`? `d/datoms-diff` to a map patch?), validation on swap, how a swap becomes facts (a transaction from the diff), how the screen and the request render from the atom, cost per turn.
2. **Self-modifying views and context:** an agent (or the user) overrides a render or its screen forms and sees it at once in the UI and in its context; both get instructions to improve their environment when it helps. Today a view override applies on the agent's branch and reaches the page when merged; the owner wants it immediate.
3. **Simple tasks from a fork:** start a task from a fork of an agent: "follow the request the agent gives and reply back when done". Likely a task kind made by the one send plus `seon.template/request`.
4. **Docs in the db at levels of detail:** every doc and its references a data pull and a render away, kept in sync; docs structured so a section has a summary, a body and its references; the UI renders the levels; agents pull their own docs.
5. **Dogfood:** test with Claude subagents through the `seon` MCP server as if they were Seon agents, to find what is confusing or undescribed; feed the gaps into the help, the prompt and the references.
6. **Related data pulled in explicitly:** context shrank 45% with turns to green at parity, so the screen likely lacks references; find ways to pull related data explicitly (cited references of on-screen strings, callers, the write set) and measure.

7. **Customizable errors** as an easy first request through design → implement → merge (owner: "I want the system to evolve in smart ways"). The stages are separate steps in one session (decisions 2026-10-25).

## Lessons (also in AGENTS §3 and docs/decisions.md)
- A green `bin/prove` counts only with its plan line compared to main's (662 tests were silently left out after a merge).
- A view renders what exists; it never recomputes it (the debug page that rebuilt requests was rejected twice).
- Briefs state principles and cite the design; a brief that prescribes a mechanism the design doesn't name is how drift starts.
- Look in the browser before measuring: the debug page would have shown broken tasks and a thin screen before paid runs.
- Check tasks before measuring with them: their proof exists and is red on main for the right reason.
- Correctness and performance before lines of code.
- Use `bin/worktree`, never `git submodule` in a worktree (root broke this once on 2026-10-25).

## State at root's context limit (2026-10-25, ~11:30)
- Merged since this note began: platform errors close (0595c749d), reds reused by digest (52b374f02), the judge asks the test service (330f01988; its verification run was in progress: build, prove plan line, agent/prove/reconcile/test-store tests, smoke; re-run it if its log is empty).
- Ready to merge, not merged: ``jolt/dogfood`` (docs, tasks, notes; src 0) and ``jolt/a-section-reads-at-each-level`` (docs at levels, help never nil, 54 leads; src +120/-68; needs dogfood first). Compare each plan line with main's.
- Gate pending: ``related-sections`` (session related-sections) brings a new config key (related-section count).
- Agent loop: design revision 2 approved (one loader; only `:seon.run/taken` new; `my.agent/message!`; no restrictions; roots are tasks); owner chose Flow on the agent's main thread (jolt/main-carrier, prototype 382k values/s, forks safe); build of parts 1-4 is go. Open: reconcile the message path with the ctx-atom note §11 (messages delivered by merging a fact from the sender's branch).
- ctx-atom: notes/design-ctx-atom-2026-10-25.md on ``jolt/ctx-lab`` (revision 3 + §11); the session waits on the owner.
- Dogfood rulings recorded (update! takes proofs; each form's value under one handle; stale on meaning changes; pilot budget up to $5).
- Owner, at the end of 2026-10-25's root context: message delivery is to be compared (main-first vs merged from the sender's branch; prefer independent processes only if not worse in stability, performance, complexity); the related-section config key and the docs-levels bound raise are approved; root continues in the same session after a compaction.

## For the owner's morning (2026-10-26)
- Ruling to confirm: no-progress. Root provisionally approved (live-agents): the first exact repeat of a turn's calls is told to the agent, the second ends the run :no-progress (the open owner question "does a byte-identical repeated reply end the run, or is it only recorded?").
- Old branches: the printer session's triage table (notes/branch-triage-2026-10-25.md) marks which are carried forward or superseded; deleting them is the owner's call.
- Archive finished sessions (full-run-reds, and others as they finish)?
