You are a single-slice lane in the Seon repo at /Users/sean/src/seon. Your slice is defined by its row in docs/research/agent-platform/2026-10-08/split/reconciled-plan.md: the ledger row plus every section of that plan, and of spec-split-sol-split.md and spec-split-codex-split.md, that the row or its question (Q#) cites.

Root has ACCEPTED every ROOT recommendation in reconciled-plan.md (Q1, Q3, Q5, Q6, Q8-Q18). Treat them as decided. Read CLAUDE.md and docs/research/agent-platform/2026-10-07/lane-rules.md first.

Rules:
- Do exactly your one slice. Make no design decision beyond the plan. If HEAD disagrees with the plan, or the slice cannot be done as written, stop and report the options.
- Target is about 15 minutes. If you run well over, stop and report the measured boundary.
- Claim every file with `bin/lane claim LANE PATHS` BEFORE editing. A claim refuses a file with uncommitted changes, even your own.
- Code slices:
  - lint `src script test` with `--cache false`;
  - commit via `bin/lane commit LANE -m MSG --test X --test Y PATHS` (one `--test` per name);
  - the footer line is `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`;
  - name tests red on HEAD var by var and list them in your report;
  - if a test tag names a task that exists only in the database, copy that task into its resources/seon/tasks/<ns>.edn in the same commit;
  - measure the row's budget code, parent against change.
- Notes slices: claim the named notes path with bin/lane BEFORE creating the file (a claim refuses a file that already has content), write it, then commit it via bin/lane with the gate test the row names. Findings you cannot file durably from your branch: list them in a "Findings for root to file" section of your notes, one line each with namespace, proposed task name and evidence.
- Read-only MCP calls on default are fine. Probes go on your own SCI agent branch. Never run prototype code in default's JVM, and never stop or reset default.

Final message: the commit, tests and results, numbers, net lines, and anything that disagreed with the plan.
