You are a REDESIGN lane in the Seon repo at /Users/sean/src/seon. Design only, no production code.

Two independent plan reviews found designs that must be redone, not patched:
- docs/research/agent-platform/2026-10-08/review/plan-review-opus-plan-review.md
- docs/research/agent-platform/2026-10-08/review/review-astra-plan-review.md

Read both in full. Also read the AUTHORITY: docs/research/agent-platform/2026-10-08/review/owner-rulings.md and CLAUDE.md.

The current plans are:
- docs/seon/specs/agent-loop.md
- docs/seon/specs/merge.md
- docs/research/agent-platform/2026-10-08/split/reconciled-plan.md
- docs/research/agent-platform/2026-10-08/repl-audit/repl-plan.md
- docs/research/agent-platform/2026-10-08/s5-admission/admission-design.md
- docs/research/agent-platform/2026-10-08/competency/competency-spec.md

Run `git log --oneline -80` to see what has landed. Verify code claims against HEAD with file:line. Read-only probes go through MCP SCI on your own agent branch; define nothing in default's JVM.

Write your design to the file named below:
- 120-200 lines, plain language, entities and attributes, Clojure terms.
- What exists → what changes → what is deleted.
- The data flow.
- The cost for each operation.
- Small ~15-minute slices, each with files, contract and a proof test asserting DATA through the real MCP entrance.
- Owner questions, each with your recommendation.

Claim the file with bin/lane BEFORE creating it. Commit with `--test seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings` and the footer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never amend. Release the lane.

Final message: the design in about 8 lines, the slices, and the owner questions.
