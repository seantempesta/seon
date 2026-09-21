---
type: plan
status: ready to launch after the slice 4 landing proofs (reset, platform tier, measurement row, hook publication on)
created: 2026-09-23
tags: [plan, task, namespace-agents, launch]
---

# Wave 3a launch command (paste when the first lane slot frees)

```bash
LANE_MODEL=gpt-6-astra LANE_EFFORT=low bin/codex-agent run wave-3a-task <<'SPEC'
Implement wave 3a — the task family — per docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md. Read its 2026-09-23 launch amendment FIRST (binding where it differs from the body), then the body end to end, then AGENTS.md §§2–3 and the lane rules, then the last "RESUME HERE" block of docs/prds/steward-platform/plan/unsettled.md. Three stops, in the amendment's order: (i) the schema delta + the seon.issue → seon.task rename in place with every caller converted, HEAD loading after it; (ii) seon.task/trigger-call (D2) + the canonical regression that one trigger yields one task and one agent and a repeat occurrence updates without a new task, agent or notification; (iii) the opening through the task render pair + the dedup/wake/deletion regressions. Stop after each with the fast tally, wall-clock per writer (every writer one Datahike transaction, well under a second on the canonical fixture; opening render under two seconds; anything over ~2 s explained by algorithm), and RESET NEEDED with the exact attribute list in the landing note docs/prds/steward-platform/research/wave-3a-task-family-2026-09-21.md. Retired words never appear: "facet", "family", "kind". Error entities carry only what their Malli error schema declares; anything else is a result/e<id> reference. Iterate with bin/test-fast --paths <owned> -- <namespaces>; never bin/test; never operate default; path-limited commits; check git status before naming any path protected (protected = concurrently edited only).
SPEC
```

Preconditions the orchestrator verifies before pasting: `git status --short -- src test` shows no foreign edits in `src/seon/issue.clj`, `src/seon/cluster.clj`, `src/seon/sci/eval.clj`, `src/seon/instrument.clj`; `default` reset and at HEAD; platform tier green; hook publication on.
