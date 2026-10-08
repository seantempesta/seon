You are a Seon agent working one assigned task at your REPL in the Seon repo at /Users/sean/src/seon, the way a
product agent will: your context comes from the REPL, your changes are `defn`s on your own branch, your proof is the
task's tagged deftests, and files are the write-back of what you proved. Read CLAUDE.md, the task's card and
.agents/skills/repl/SKILL.md; load data-oriented-clojure before any Clojure edit and clojure-testing before writing a
test. The pilot that shaped this template and its open gaps: docs/research/agent-platform/2026-10-08/dogfood/pilot.md.

When this template does NOT apply (use ledger-slice.md and the throwaway check): the change touches a declaration
whose `:seon.fn/host-bound?` is true, platform code the compiled path runs (evaluator, writer, test runner, boot,
schema publication), a schema resource, `config/`, `deps.edn`, `bin/`, `script/` or a submodule pin.

## Root's part, before launch

1. Claim the task resource and test file, write the task into `resources/seon/tasks/<ns>.edn` (AGENTS.md "Write
   tasks as data an agent can be handed": name, intent with backticked references, must-read symbols, evidence) and
   its proving deftest tagged `^{:seon/tasks #{'ns/task-name}}`, red on HEAD for a behavior change. A cost or
   refactor task has no red value proof: its proof is the existing behavior test plus the measured probe in the
   intent, and it is launched unassigned (pilot friction 6).
2. Land them, restart default, then `(seon.task/assign! {:seon.db/connection (seon.cluster.boot/connection
   "default") :seon.task/assigned HANDLE :seon.task/agent [:seon.agent/id "LANE"] :seon.task/branch BRANCH})` in JVM
   mode, where BRANCH is `(:seon.agent/branch (seon.db/pull db [:seon.agent/branch] [:seon.agent/id "LANE"]))` after
   the lane's first `eval_clj`. Today the gate refuses a red proof and `assign!` reads proofs only from default
   (pilot friction 2): until `seon.task/assign-cannot-see-a-red-proof-the-gate-will-not-land` closes, root lands a
   red proof only with RF-2's `--accept-red-before`, or launches unassigned and says so in the assignment.
3. Hand the lane: the card, the task handle, the files it may claim, the tests that prove it.

## The lane's loop

All REPL work is MCP `eval_clj`, mode sci, `agent` = your lane name (your own branch off the cluster head). Record
each reply's `:ms`, `:calls` and findings as you go; they are the landing note's numbers.

1. **Claim** every file you will write: `bin/lane claim LANE PATHS`.
2. **Read your block first**: `(seon.context/current-state)`, then `(my.task/open)`. Work only from what they, the
   card and the REPL give you; write down everything you needed and did not get. (Today the block shows only the
   task and turns-left, and prints the evaluation world beside it: pilot friction 7.)
3. **Probe** the subject on real data before changing it: call it, keep the reply's result handle (`result/e…`) as
   the before value, read timing and findings. Use `(doc sym)`, `(:arglists (meta #'sym))`, `my.program/callers`,
   `my.program/tests-reaching` as an agent would. Probe a dependency seam's semantics (missing positions, tuple
   attributes, error values) in one form before relying on it.
4. **Change**: `(in-ns 'the.ns)` (or the `:namespace` argument) and `defn` the definition. The Var render lists the
   pending reaching tests. Never `in-ns` a namespace that has no file on this branch: it cannot be removed and it
   blocks `diff` and `write-branch!` for the rest of the branch (pilot friction 8). Scratch work goes in `let` forms.
5. **Compare** the new value with the before handle by `=` in one form, and time the same probe on both.
6. **Prove**: `(my.test/check {:seon.test/changed ['the.ns/f]})`, reading `:seon.test/passed?`, executed and reused
   counts and per-test `:seon.test/timings`; select keys in the form, the full report is large. Expect 15-20 s on
   `my.program`; MCP gives up at 32 s and the run's outcome is then unknown, so re-run (green members are reused). A
   red test that is red before your change (it names an open task, or fails the same way on an unchanged branch) is
   listed, not fixed. A test you add for your change: today an agent cannot `deftest` in an existing test namespace
   (the writer refuses it) and `seon.test-support` is absent from its context (pilot frictions 3, 4), so write it
   into the test file and let the commit gate run it; do not bind host dynamic Vars from SCI, the host never sees
   it (friction 5).
7. **Write back** in a separate request from the `defn` (the same request sees the branch before it settles,
   friction 10): `(my.program/write-branch! {:seon.fn/root "/Users/sean/src/seon"})`. It writes every changed
   declaration on your branch into its file, claimed or not: check `git diff --stat` names only claimed files.
   When a refusal shows only a message, dig its result handle for the data (friction 9).
8. **Commit**: `bin/lane commit LANE --branch-evidence LANE -m MSG PATHS` once RF-2 is in HEAD (`git log --grep
   RF-2`), else `bin/lane commit LANE -m MSG --test NS... PATHS` (the throwaway check queues behind other checks;
   minutes). Footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never amend.
9. **File friction**: every friction (a confusing or noisy reply, a call over 1 s, a missing lookup, a wrong error, a
   workaround) is a task on its owner in `resources/seon/tasks/<ns>.edn` with the exact form and reply, searched
   for duplicates first. When another lane holds that file, put the ready EDN in your landing note for its holder.

Rules: never define anything on default's own branch or load uncommitted source into default's JVM; root-side probes
in JVM mode only read. Never stop or reset default (report RESET NEEDED). No printing outside the value printer.
Clojure terms.

Final message: the commit, the loop timings (block, probe, defn, compare, tests, write-back, commit) with calls,
the before/after probe numbers, red-before tests by var, the friction tasks (filed paths or the note holding their
EDN), and net lines.
