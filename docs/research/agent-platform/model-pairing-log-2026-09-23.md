---
type: research
status: running log
created: 2026-09-23
---
# Model pairing log — which pairing writes the cleanest, least buggy, smallest code

Owner 2026-09-23: "use astra for review and writing prds and diagnosing problems and once you have a good written prd or plan doc then have gpt 6 sol do the work … use whatever writes the cleanest and least buggy and smallest code". One row per implemented slice.

| slice | design by | implemented by | src +/− | test +/− | reds after | review findings (Astra) | wall | verdict |
|---|---|---|---|---|---|---|---|---|
| 1.3f #41 | Opus | Opus | +15/−33 | 2 tests converted | not run (adoption race) | — | 20 min | clean delete; key choice justified |
| sol-publication-serialize | orchestrator spec | gpt-6-sol | 0/0 (stopped) | — | — | correctly found the spec wrong: lock must wrap refresh-source! in held cluster.clj | ~10 min | good judgement, stopped instead of a wrong fix |
| nsa slice 4 PRD | gpt-6-astra | — | doc 140 lines | — | — | reused save gate; but 5 alias schemas + a 9-value reason enum (discriminator), revision needed | ~25 min | thorough, over-specified |
| audit 7e5cdea9e..HEAD | gpt-6-sol (audit) | — | 57-line report | — | 3 bugs, 2 bloat, 3 slow, 5 half-done, 1 law | — | ~15 min | caught 3 bugs the Opus lanes and the orchestrator accepted (deletion gating, reload-before-rows, two-transaction adoption); strong |
