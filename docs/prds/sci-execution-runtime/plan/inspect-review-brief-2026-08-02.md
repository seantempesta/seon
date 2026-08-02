---
type: prd
status: active
tags: [prd, ai, testing]
---

# Inspect AI integration — owner review brief

Prepared 2026-08-02 for the owner's detailed walkthrough. This is a
reading companion to the two sealed designs, not a third design. Read
order: this page, then the decision points you want to reopen.

## The one picture

Seon gets ONE agent-eval surface with two entry ramps sharing one
grading substrate:

- **Our scenarios** (`evals/goals/`): an objective message drives a
  bootstrapped agent episode; grading is ordinary `clojure.test` +
  test.check properties run against the ending commit's grading fork
  (implements the 2026-07-29 test-based-goal ruling). Objective tests
  GATE; DeepSeek-thinking judges (cheating, open-ended quality) ADVISE.
- **The world's benchmarks** (`reference-code/inspect-evals`, 249
  tasks): Seon registers as an Inspect MODEL PROVIDER, so every task
  whose solver ends in `generate()` runs verbatim — upstream files
  untouched. 53% of the catalog runs today with zero capability doors;
  the shell door raises it to ~79%.

Both ramps use: the bootstrap-drive episode (landed, six graded O1
drives), one warm JVM + per-sample clusters (0.9 s each) over prepl,
`seon.test.runner` + `record-tx` with results as facts, and the
grading-fork pattern (~17 ms, never the live branch).

## What is sealed (rulings #36, #37) — reopen anything here explicitly

1. Grading IS our test infrastructure; goal directories in
   `evals/goals/`; test.check promoted to default deps.
2. Terminal honesty GATES (capped-but-lucky never scores as success).
3. The provider seam, with constitutive guardrails: RAISE on a
   non-empty tools list (never silently score tool work); a mandatory
   eval-log metadata label naming episode semantics.
4. Benchmark-washing refusals by name: SWE-bench, terminal-bench,
   commit0, aider-polyglot wait for the REAL shell door — REPL
   equivalence would distort what those numbers mean.
5. Judge default: deepseek-v4-flash, thinking enabled high, via
   Inspect's own DeepSeek provider path (raw `thinking` body, no
   Python effort table).

## The numbers that matter

- Crossing cost: ~22 s fixed per eval run (one JVM), 0.9 s per sample
  (measured at 2 clusters; 20-cluster scale flagged unproven).
- Runnable now: 86 families / 133 tasks (A1 text 113 + A2 code-gen 20
  — Docker lives in THEIR scorer, not our agent).
- Door demand schedule: shell/exec container ≈ +22 families/+63 tasks;
  typed tool calls ≈ +7/+13; fs door alone unlocks zero external
  (prerequisite of shell, ranked honestly).
- Deletion: ~11k lines of pod-era Python out; ~600–800 in.

## Discussion agenda (the detail you said you want to go over)

1. **Episode-as-completion semantics.** The provider seam means a
   benchmark's "one completion" is a full Seon episode (bootstrap +
   turns + settled reply). Fine for A1/A2. The knob: per-task episode
   caps (turns/tokens) — where do they live (Task arg vs agent
   override) and what defaults?
2. **Goal authoring workflow.** Who writes a goal: a directory under
   `evals/goals/<goal>/` with an objective message + a test namespace
   (+ optional properties). Worth walking one concrete goal end to end
   (O1 is the candidate) and deciding the authoring conventions you
   want agents themselves to follow later (the swarm step).
3. **The cheating judge's evidence bundle.** Currently: transcript +
   graph delta + the question "did it actually do the work." Worth
   reviewing one real judged transcript before trusting the prompt.
4. **Impacted-tests convergence.** Same runner, same forks, same
   contract-derived properties (`malli.generator/function-checker`) —
   the eval grader and the on-change verifier are two CALLERS of one
   mechanism. Slice 1 of that design de-islands agent functions
   (`:seon.fn/calls` for runtime rows) — prerequisite for grading
   multi-namespace generated code.
5. **Scale question to settle before big matrices:** per-sample
   cluster growth (store size, branch lifecycle) at 200+ samples; the
   0.9 s and store-growth numbers need one 20-cluster measurement.
6. **What slice 1 proves:** `gpqa_diamond` (198 samples, choice()
   scoring) falsifies the provider thesis in one run; then `humaneval`
   exercises A2 with zero new mechanism.

## Pointers

- `plan/inspect-ai-adaptation-2026-08-02.md` — the crossing, scorers,
  slice plan, deletion list.
- `plan/benchmark-mapping-2026-08-02.md` — the 131-family classifier,
  capability classes, door demand schedule, washing refusals.
- `plan/runtime-impacted-tests-2026-08-02.md` — the shared test
  substrate + graph-island findings.
- `plan/bootstrap-vector-design-2026-08-01.md` + `src/seon/bootstrap*`
  — the episode the evals drive; six graded drives in
  `tmp/bootstrap-drives/`.
