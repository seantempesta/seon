---
title: Overnight plan 2026-10-08
type: plan
status: active
created: 2026-10-08
---

# Overnight plan, 2026-10-08

Goal by morning: live Seon agents work real tasks end to end. An agent goes green on its branch, root reviews the
change, writes it back to files and commits it. Every run is graded from the database, and context strategies are
compared by those grades rather than picked.

## Priorities

| # | Priority | Why it ranks here | Done when |
|---|---|---|---|
| P0 | Platform stays up: memory gate, boot, check tooling | It blocks every other priority | No refusal while lanes run; react only when it blocks |
| P1 | The end-to-end loop on real tasks | The product | Run 4 is clean and green. Then 2-3 parallel agents on real backlog tasks, and root merges at least one to files with a commit |
| P2 | Honest measurement and grading | We can't judge P1 or P3 without it | Every reply carries the request's real ms, calls, reads and allocation; one function grades a run |
| P3 | Context quality, objectively | It decides whether agents succeed | Scorecard before/after committed. First strategy experiment run in parallel agents and graded |
| P4 | Structural fixes that unblock P1, one per pattern | Root causes, not symptoms | F11 (an unchanged red is answered from its record, about 700 ms per request); SCI-2/3 after REPL/step parity; agent-entity cards; completion split from merge (R-2) |
| P5 | Plan hygiene | Keep the plan true | Sol's review folded in; dual research only on re-specs that block P1 (R-2 completion, R-6 pending tests) |

Not tonight:
- triaging reds that don't block a running lane or the loop (they are filed);
- the codec decision, the errors cut, the recording redesign, renames;
- micro cost fixes outside the pattern cards.

## Schedule (waves; a wave starts as the lanes before it land, never by the clock)

1. **Now (5 lanes):**
   - live run 4: clean repeat;
   - bench: re-spec, then the grading function;
   - agent-entity design;
   - feedback-2: per-request cost;
   - context-refine: scorecard loop.
2. **When run 4 is clean:**
   - fan-out of 3 agents on small real tasks with red proofs, each on its own branch;
   - a root lane reviews each green branch, runs `write-branch!` and commits with `--branch-evidence`.
   - In parallel: F11 (once feedback-2 frees `turn.clj`), the SCI-2 parity check, folding in Sol's review, and dual research on R-2 and R-6.
3. **When grading lands:**
   - the first bench experiment, 2 strategies × several repetitions as parallel agents (wire format first);
   - then widen to the other strategies one variable at a time.
   - Also the F5 completion slice and the first AE cards.
4. **Before morning:** one report (below).

## Rules I hold every lane to

- **At most about 5 implementation lanes at once,** each in its own files. Research lanes only write docs, so they don't count.
- **A check-in at 15 minutes.** At 30 minutes a lane lands what it has and the rest is split into new cards. A square peg means stop, write it up, research it.
- **Platform first:** a memory, database or tooling defect pauses agent lanes until it is fixed.
- **No red-chasing.** A red is fixed only if it blocks a running lane's proof or the loop. Otherwise it is filed, and classified later with `check --at`.
- **Findings go to their pattern** (P1-P6 in `cost-design/merged.md`) or to the context scorecard, never to one-off lanes.
- **Restarts are batched at safe points** (no lane mid-run on default). A reset follows schema deletions.
- **Paid runs are deepseek-flash only,** capped per agent, with the provider turned back off and verified after each run.
- **Every commit names its tests by var.** No `git add -A`. Nothing pushed except our own forks.

## Stop conditions (pause, write up, wait for the owner)

- A design contradiction with no simple, clearly correct answer.
- Repeated platform refusals that resist one diagnosis lane.
- Any model calls nobody started.

## Morning report

- **Scoreboard:** live runs graded (turns, errors, success, tokens), context scorecard before/after, per-request cost
  of `:ping`, a `defn` and a check, lines deleted, commits.
- **What landed, what is in flight, what stopped and why.**
- **Decisions needed,** each with options and a recommendation.
