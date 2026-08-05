---
type: issue
status: open
severity: friction
tags: [issue, agent, runtime, testing]
---

# Supply the starting namespace in agent lifecycle fixtures

## Problem

Seven lifecycle tests repeatedly attempt to start runs without the required
starting namespace. The transition refuses correctly, but the fixtures keep
retrying and every later assertion observes missing runs, receipts, answers,
or completion events.

## Evidence

The bare 2026-08-05 gate repeatedly logged:

```text
:datahike/write-rejected
{:kind :seon.cluster.run/refused,
 :cause "run transition refused: starting-namespace-missing"}
```

The affected vars are:

- `seon.cluster.agent-test/lint-refusals-continue-the-episode-until-the-cap`
- `seon.cluster.agent-test/n-agent-parallel-turns-property`
- `seon.cluster.agent-test/park-wake-test`
- `seon.cluster.agent-test/pause-during-in-flight-call-test`
- `seon.cluster.agent-test/restamp-recovery-test`
- `seon.cluster.agent-test/wait-closes-in-terminal-tx-test`
- `seon.cluster.agent-test/wake-routing-conservation-property`

The focused pre-rename run at `401fd300e` produced the same refusal and the
same seven failing vars. Secondary output included missing-event exceptions,
nil string NPEs, missing answers, excess provider calls, and a multi-kilobyte
prompt-vector diff; none is evidence of rename fallout.

## Owner

The direct run-opening fixtures in `test/seon/cluster/agent_test.clj`, against
the `seon.cluster.run/plan-call` starting-namespace contract.

## Acceptance

Every direct lifecycle fixture supplies the agent's declared namespace when it
opens a run. No test retries a `starting-namespace-missing` refusal, and the
seven vars reach their intended lifecycle assertions without derivative nils
or prompt dumps.
