---
type: research
status: active
tags: [research, agent]
---

# The diffusion measurements moved to the inspect-ai standard harness

**The code lives in the top-level package `src-inspect-ai/` (owner directive:
core maintained code, not PRD scratch).** This note is the PRD-side pointer +
the port's offline proof record. Setup, run matrix, parity map, and the
live-validation checklist are in `src-inspect-ai/README.md`.

## What was ported (2026-07-02)

- **E1 three-arm** (`seon_inspect.tasks.e1_spec_fn`) — the FIXED kill-gate
  harness (contract-stating prompts, oracle-liveness gate, raw-sample
  persistence; see [[e1-behavioral-zero-audit-2026-07-02]]) as an inspect task;
  arms are task params, verdict = compare arm runs' `mean`.
- **Skill-lift A/B** (`seon_inspect.tasks.skill_lift`) — the north-star ledger
  loop; conditions are task params.
- **Ladder-lift** (`seon_inspect.tasks.ladder_lift`) — runbook step 4
  (refine_loop ON vs free-gen OFF), worker metrics ride the eval log.
- **Oracle scorers** (`seon_inspect.oracle_scorers`) — persistent bb + node
  servers, the parse→structural→eval→behavioral→vacuity tier ladder, and
  `assert_oracle_live` (golden known-good must score faithful AND known-bad
  def-vs-defn must FAIL eval; loud abort otherwise — the anti-dead-bundle rule).
- **The /solve solver** promoted from the bridge spike into
  `seon_inspect.solver` (the spike dir stays as history).

## Offline proof (REAL bb+node oracles, canned worker, pass^k epochs)

```text
E1 arm1 guided_refine        mean/accuracy=1.000  pass_at_4/accuracy=1.000
E1 arm2 naked                mean/accuracy=0.250  pass_at_4/accuracy=1.000
E1 arm3 naked+oracle         mean/accuracy=0.250  pass_at_4/accuracy=1.000
skill control                mean/accuracy=0.000  pass_at_4/accuracy=0.000
skill treatment              mean/accuracy=1.000  pass_at_4/accuracy=1.000
ladder ON                    mean/accuracy=1.000  pass_at_4/accuracy=1.000
ladder OFF                   mean/accuracy=0.250  pass_at_4/accuracy=1.000
```

pytest 15/15 green (tier-discrimination table incl. the transducer
false-positive caught by behavioral-not-eval; liveness-gate abort; task
wiring). The mock encodes the live 06-29 pools — the deltas prove the HARNESS
discriminates; model numbers come from the GPU session. Report BOTH reducers:
`pass_at_k` alone saturates (arm2 = 1.000 at-least-one vs a 0.250 rate).

**Scorer fix (2026-07-02, owner correction):** the ported structural tier was
answer-shaped — it REQUIRED the named `-request`/`-response` + `register!`
idiom, failing correct inlined-schema code (behavioral_pass=true, faithful=
false). Fixed in BOTH homes (the package scorers AND
`tmp/flash-diffgemma/e1_kill_gate.py`): correctness gates are idiom-agnostic;
idiom adoption reports separately (`idiom_scorer`) and gates ONLY in
skill-lift, where teaching the preferred idiom is the measurand. The liveness
gate now demands BOTH golden idioms score faithful, and the E1 prompts state
the CALLING convention + sandbox rules without dictating the naming idiom.
Philosophy + post-fix proof: `src-inspect-ai/README.md` "Scoring philosophy".

## Consequences for this PRD

- Runbook steps 3 (E1 re-run) and 4 (ladder lift) execute AS inspect tasks
  (`-T endpoint=runpod` after `verify_fresh` → FRESH ✓, `--max-samples 1`).
- Exp D stays in `battery.py` (a knob sweep, not a benchmark).
- `bin/acme gym-diffusion` retires once each task has one real GPU scorecard.
- Pending live validation (pod smoke via acme /solve, the GPU runs) is tracked
  in the package README's checklist.
