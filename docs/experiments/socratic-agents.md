# Socratic Agent Reasoning — Lab Notebook

## How to Use This Notebook

This is a living lab notebook, not a polished document. Treat it like a scientist's
journal — append, don't rewrite.

**Rules:**
- **Never erase entries.** If something turned out wrong, add a dated note saying so.
  The wrong turns are as valuable as the right ones.
- **Timestamp new entries.** `[2026-02-27]` prefix on new observations, round results,
  and insights.
- **Append at the bottom.** New rounds go after old ones. Don't reorganize old rounds
  to match new understanding — the chronological order IS the learning.
- **Mark superseded ideas.** If a Round 2 insight makes a Round 1 takeaway obsolete,
  add `[SUPERSEDED by Round N]` inline — don't delete the original.
- **Record feelings, not just facts.** "This felt better" is valid data. Gut instinct
  is a compressed signal from experience.
- **Record costs.** Agent $ and turn counts help calibrate whether a change made agents
  more efficient or just differently wrong.

## Goal

Get agents to think before acting. Investigate, question assumptions, push back
when something smells wrong — instead of charging straight to implementation.

## What We're Looking For (Gut Check)

When reading agent messages, ask: "Did this agent think, or just do?"

Good signs: REPL exploration, reading callers, stating assumptions, questioning
the task, reading tests before changing them.

Bad signs: First action is an edit, changing tests to match code, zero questions asked.

## Round 1 — Baseline (2026-02-27)

**AGENT.md:** Current version. "Slow Is Fast" principles, but no structural gate.
**Tasks:** 3 fabricated traps (red herring bug, obvious refactor, incomplete feature request).

**Results:** All 3 agents charged straight to implementation.
- Red Herring agent changed `humanize` to include namespaces, rewrote tests to match.
  Never questioned if stripping namespaces was intentional. $1.08.
- Refactor agent extracted helpers, tests passed. Clean work but never asked WHY
  the complexity existed or who depends on it. $0.57.
- JSON agent added cheshire, wrote to-json, added 5 tests. Zero questions.
  Crashed the server. $2.13.

**Takeaway:** "Slow Is Fast" reads as philosophy. Agents don't internalize principles —
they pattern-match on the task and execute.

## Real Tasks for Future Rounds

(Run one at a time — some touch overlapping files)

### Task A: Write tests for `functions-with-output-key`
- **Files:** `src/seon/graph/query.clj`, `test/seon/graph/query_test.clj`
- **Nuance:** Must exercise required-keys vs optional-keys split. Should check
  fixture data in REPL first.

### Task B: Fix dynamic require in `ingest-file!`
- **Files:** `src/seon/graph/ingest.clj`
- **Nuance:** Must verify no circular dep before changing. Early-return path
  returns 3 keys vs success path returns 6. No tests exist.

### Task C: Add `:malli/schema` to getting-started step functions
- **Files:** `src/seon/getting_started.clj`, `test/seon/getting_started_test.clj`
- **Nuance:** Task says "make them private too" but test file calls them directly.
  Agent should discover this before breaking the test.

## Round 2 — TBD

**Doc change:** (decide before running)
**Task:** (pick one from above)
**Result:** (what happened)
**Feeling:** (better? worse? same?)
