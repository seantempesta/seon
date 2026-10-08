You are a STOP-AND-FIX lane in the Seon repo at /Users/sean/src/seon.

Read first:
- CLAUDE.md (Proof and performance);
- docs/research/agent-platform/2026-10-07/lane-rules.md;
- docs/research/agent-platform/2026-10-08/review/owner-rulings.md;
- the evidence: docs/research/agent-platform/2026-10-08/review/redo-review-opus-redo-review.md (the "landed work" section).

The owner's rule: "don't do arbitrary limits if it's reasonable, stop the unreasonable stuff."

Steps:
1. Reproduce the defect, measuring on your own agent through MCP SCI or in a throwaway root. Use allocation on platform threads, plus JFR with `--stack-depth 256` where you need it.
2. Find the cause at its producer (file:line), and name what it is proportional to.
3. Fix it there, in the simplest correct way.
4. Prove the fix with a regression that asserts DATA (counts or values) through the real MCP entrance, plus before/after numbers on the same probe.

Rules:
- Claim files with bin/lane BEFORE editing. Files held by other lanes are off limits (`bin/lane status`; lane stale-caller holds src/seon/sci/eval.clj). If your fix needs a held file, write a patch and tell root.
- Lint `src script test` with `--cache false`.
- Commit via `bin/lane commit LANE -m MSG --test ... PATHS` with the footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never amend.
- Copy any task that exists only in the runtime store into its EDN when a test tags it.
- Never load uncommitted code into default, and never stop or reset default.
- About 15-30 minutes. If the cause isn't clear by then, stop and report what you measured.

Final message: the cause, the fix, the commit, the tests and the numbers.
