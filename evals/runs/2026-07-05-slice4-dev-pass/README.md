---
type: research
status: draft
tags: [research, agent]
---

# Slice 4 — PAUSED mid-smoke (owner token throttle, 2026-07-05)

Owner paused all live testing while the tooling lane rebuilds the agent
tool surface (language tools in flight — smoke numbers would measure a
stale substrate). State at pause:

- **Frozen dev slice: DONE and durable** — seeded n=10 selection in
  `freeze.py` + `evals/datasets.lock` (`dev-ids.txt`; excludes the two
  spent smoke instances). Images: see `pull-stats.txt`.
- **Smoke attempt 1** (`smoke1-console.txt`): the scorer never produced a
  verdict → classified `harness_error` flake; the harness fix was applied
  in `swebench_arm.py`, verified live + unit-green (attempt 1b/2 consoles).
  The re-run was killed mid-flight by the pause — NO ledger rows appended
  (nothing scored; nothing to record).
- **Resume:** the full n=10 dev pass is owner-gated. When tooling's new
  verbs land: rebuild/repin the seon image, re-run per the design §8
  slice 4, THEN the mini-swe-agent baseline arm on the same frozen slice.
