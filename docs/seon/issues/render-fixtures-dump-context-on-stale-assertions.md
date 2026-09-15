---
type: issue
status: open
severity: friction
tags: [issue, test, render, wave/contract-gate]
---

# Render fixture failures print the entire SCI context

At HEAD `3e84110bc`, `SEON_TEST_WORKERS=3 bin/test-fast --paths AGENTS.md --
seon.render-simplification-test` reproduced **21 tests / 116 assertions,
17 failures / 5 errors**. The HEAD-only log was **1,143,148,974 bytes**.
The cookbook overlay reproduced the same failures; its focused canonical
opening, faults, and schema suite passed **23 tests / 266 assertions**.

`attribute-declared-producers-select-for-every-projection` still expects the
plan pair on `:my.plan/steps` and a raw set argument, while the plan pair
belongs to its component and consumes a render unit. Failed equality assertions
print that unit's full SCI context. Other failures include missing fixture
config refs in `seed-cluster!`, the compiled resolver fixture, and retired nested
value projections. This is a verified HEAD boundary, not a green broad render
suite.

Update the canonical fixtures and assertions to the current contracts; compare
the relevant argument fields without printing the context. Acceptance: the
complete namespace passes and a deliberately failed fixture assertion produces
a useful bounded diagnostic. The exact reproduction command above is retained;
the gigabyte scratch log is disposable after extracting these observations.

## Re-observation — 2026-09-15, page-speed-and-estimate

Clean HEAD `131fa2a56`, `bin/test-fast --paths AGENTS.md --
seon.render-simplification-test`: **21 tests / 122 assertions, 13 failures /
2 errors**. The same failures occur with the retained-call performance slice.
They include the retired plan-attribute argument, nested projections, cap
expectation, compiled resolver trap, and stored-source-run oracle described
above. Whole SCI contexts still appear in failed argument equalities.
This lane leaves the existing suite unchanged; the new immutable-database
reuse regression lives in `seon.render.retained-test` with the canonical
database and real SCI context. See the
[landing note](../../prds/context-generation/research/page-speed-and-estimate-landing-2026-09-15.md).
