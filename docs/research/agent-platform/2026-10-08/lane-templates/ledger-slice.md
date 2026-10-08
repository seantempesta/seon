You are a single-slice lane in the Seon repo at /Users/sean/src/seon. Your slice is one row of the ledger, docs/seon/schedule/agent-loop-ledger.md.

Read:
- your row, and every design section it cites, including the redo designs in docs/research/agent-platform/2026-10-08/redo/;
- docs/research/agent-platform/2026-10-08/review/owner-rulings.md (the authority);
- CLAUDE.md;
- docs/research/agent-platform/2026-10-07/lane-rules.md.

Rules:
- Do exactly your one row, about 15-30 minutes. Make no design decision beyond the row. If HEAD disagrees with the row, stop and report the options.
- Claim every file with `bin/lane claim LANE PATHS` BEFORE editing or creating it. A claim refuses a file that already has content, even yours.
- Lint `src script test` with `--cache false`.
- Commit via `bin/lane commit LANE -m MSG --test X --test Y PATHS`, one `--test` per name, with the footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never amend.
- Name tests that are red on HEAD var by var, and list them.
- If a test tags a task that exists only in the runtime store, copy that task into its EDN.
- Prove the slice with tests asserting DATA through the real MCP entrance (`seon.mcp/call`) wherever agent-visible behavior changes.
- Measure the row's budget, parent against change.
- Never run prototype code in default's JVM, and never stop or reset default.
- No clipping or printing outside the value printer. Use Clojure terms.

Final message: the commit, the tests and their results, the numbers, net lines, and anything that disagreed with the row.
