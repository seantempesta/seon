You are a single-slice lane in the Seon repo at /Users/sean/src/seon, implementing ONE slice of the owner-APPROVED plan docs/research/agent-platform/2026-10-08/repl-audit/repl-plan.md. Read the whole plan (its slice table, its delete list, and the approved policy answers below), plus CLAUDE.md, docs/research/agent-platform/2026-10-07/lane-rules.md and .agents/skills/clojure-testing/SKILL.md.

Owner-approved policies:
- a bare literal or symbol line is code;
- in a MODEL reply, words after a form on its line become that form's comment (MCP code runs everything);
- `#?` picks `:clj`;
- the namespace is kept after a throw;
- a valid contract refuses a bad return value, but a malformed contract never breaks the function;
- empty MCP input answers nil.
Laws: a REAL REPL, where any valid Clojure SCI can run works serially, and one transaction only records.

Proof:
- Your rows go in a NEW file `test/seon/repl_differential_<slice>_test.clj`, e.g. `repl_differential_p1_test.clj`, so slices stay file-disjoint.
- Reuse the table helper from `test/seon/repl_differential_test.clj` (c816e3fde). If it is private, call it through its var or copy nothing: ask root.
- Rows go through `seon.mcp/call` in SCI mode and assert DATA.
- Keep the budget: `(+ 1 1)` and a small `defn` through MCP must not get worse by more than 20% or 50 ms. Measure parent against change.

Rules:
- Do exactly your one slice, about 15 minutes. If HEAD disagrees with the plan, stop and report.
- Claim every file with `bin/lane claim LANE PATHS` BEFORE editing.
- Lint `src script test` with `--cache false`.
- Commit via `bin/lane commit LANE -m MSG --test seon.repl-differential-test --test <your new test ns> --test <other reaching tests> PATHS`, with the footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Name tests that are red on HEAD var by var and list them. Copy any database-only task a test tag names into its EDN.
- Never amend, rebase or rewrite a commit (put the footer in the message from the start).
- Never define anything in default's JVM, and never stop or reset default.

Final message: the commit, the rows, the numbers before and after, net lines, and anything that disagreed with the plan.
