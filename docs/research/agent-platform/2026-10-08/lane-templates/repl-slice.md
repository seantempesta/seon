You are a single-slice lane in the Seon repo at /Users/sean/src/seon, changing ordinary (non-host-bound) functions
REPL-first: by `defn` on your own branch in the main JVM, proven there by the reaching tests, then written to files.
Authority: docs/research/agent-platform/2026-10-08/review/owner-rulings.md (last bullet, "REPL-first proving") and
docs/research/agent-platform/2026-10-08/repl-first/repl-first.md. Read CLAUDE.md,
.agents/skills/repl/SKILL.md and .agents/skills/clojure-testing/SKILL.md.

When this template does NOT apply (use ledger-slice.md and the throwaway check): your slice changes a declaration
whose `:seon.fn/host-bound?` is true, platform code the compiled path runs (the evaluator, the writer, the test runner,
boot, schema publication), a schema resource, `config/`, `deps.edn`, `bin/`, `script/` or a submodule pin.

Loop (MCP `eval_clj`, mode sci, `agent` = your lane name, which gets its own branch off the cluster head):
1. Claim every file you will write with `bin/lane claim LANE PATHS` before editing.
2. Probe first: call the function on real data, read the reply's timing, findings and errors.
3. `(in-ns 'the.ns)` then `defn` the changed definition. Read its Var render: it names the reaching tests.
4. Run them: `(my.test/check {:seon.test/changed ['the.ns/f]})` (automatic once F4a lands). Unchanged green members
   are reused. A red test that is also red on an unchanged branch (run the same form as a second agent, e.g.
   `LANE-base`) is red before you: list it, do not fix it unless it is your slice.
5. A changed function no test reaches is unproven: add the deftest (a `deftest` evaluated on your branch, then
   written to its test file) that asserts its DATA through the real entrance.
6. Write back: edit the file so each changed declaration's text equals your branch's
   (`(my.program/declaration-diff 'the.ns/f)` shows both), or `my.program/write-branch!` once RF-1 lands.
7. Commit: `bin/lane commit LANE --branch-evidence LANE -m MSG PATHS` once RF-2 lands (it checks file = branch
   digest and green evidence for the reaching tests, lints, and boots no JVM). Until then `bin/lane commit LANE -m
   MSG --test NS... PATHS`. Footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never amend.

Required: friction is a deliverable. Every friction you hit at the REPL becomes a task on its owner with the exact
form and reply: a confusing reply, wasted tokens, a call over 1 s, a missing lookup, a wrong error, or a workaround
you needed. Record each in the owner's `resources/seon/tasks/<ns>.edn` (or a card row when you do not hold that
file) and list them in your landing report.

Rules: never define anything on default's own branch, never load uncommitted source into default's JVM, never stop
or reset default (report RESET NEEDED). No printing outside the value printer. Clojure terms.

Final message: the commit, the branch evidence (run ids, reused and executed counts), the loop timings (defn, tests,
write-back, commit), red-before tests by var, the friction tasks, and net lines.
