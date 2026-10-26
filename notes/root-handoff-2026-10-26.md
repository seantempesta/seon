# Root's handoff (2026-10-26, evening)

What a fresh root needs in one read, beyond AGENTS.md, docs/plan.md and docs/decisions.md (read the 2026-10-26 entries: they changed the direction several times today, and the latest stand). History is in notes/merge-log.md and git.

## The state
- Main is green on prove, `bin/check contracts`, the process tests and smoke, except as the merge log's last lines say. The forked-child fix (a forked child exits on a throw instead of unwinding into its parent) is merged and was waiting on its verification; then merge jolt/jolt-fiber-dump (Jolt d598fc04) and jolt/the-page-holds-what-the-work-needs (D1–D5).
- Paid model runs are on hold until the owner says.
- `.worktrees` was 200 GB; merged and idle worktrees were removed (53 GB left). Remove a worktree as soon as its branch merges.

## The design to hold (owner's rulings of 2026-10-26, all in decisions.md)
- **One rendering system.** notes/render-design-2026-10-26.md is the agreed design. ctx is the agent's data pulled by a pattern stored as data (`:seon.ctx/pattern`), patched by each transaction's datoms with untouched subtrees `identical?`; nothing derived is stored in the db or in ctx. A render is `(fn [opts & children] hiccup)`; renders declare `:seon.db/db` or `:seon.ctx/ctx` in their opts contract and the walk fills them; any other value in the tree is drawn by its schema's render, else the printer. One cache: Reagent's ratom semantics ported to the server (seon.ratom), cursors inferred from what a render reads, `d/with-reads` for db reads, an `=` cutoff on outputs. The model reads the same hiccup the user sees, whole on the first turn, then ctx's diff and the page's diff. Datastar patches by component id.
- **Never a second system.** The parts layer, hand-built cache keys, a projection, an AI text face: each was built today beside the one system and is deleted or being deleted. Before approving any design, check it against decisions.md, get an independent opinion, and have it tried in a live agent's REPL with numbers first.
- **Clojure and library words only.** No "story", "zygote" or "spec"; a render is a function and the data it renders.
- **Stability first.** A wait ends on its peer's exit or answer, never a timer. A stall is recorded, shown and becomes root's task; it is never acted on by a clock. Before killing a stuck process: `kill -INFO`, `sample PID 5`, `lsof -p PID`.
- **Requests:** one whole task by default; context good enough for about 20 turns; merge on facts (main's tests on the merged tree).
- **Declared design** (the experiment): contracts carry `:seon.cost/bound`, a function of the arguments; a solution passes when what it defines and what its code references (`:form/refs`) is bounded and within the task's budget.

## Lessons from today (root's own mistakes)
- I approved designs that contradicted standing rulings (derived members, a projection, the parts layer). Read decisions.md before approving.
- I read a red as a known one without naming the failing test, twice; and logged a merge green before reading its exit codes once. Read every verdict's failing test names before logging.
- Committing during a verification makes smoke refuse a stale binary. Write notes in the scratchpad until the run ends.
- `bin/check contracts` and the process tests are left out by `bin/prove`; run them by name.
- Two branches can each be green and break together (a test using a var the other deleted): verify the merged tree, always.
