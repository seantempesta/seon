---
type: issue
status: resolved
tags: [issue, milestone, database]
---

# Frozen tool fixture uses retired database wrappers

## Evidence

The complete offline Inspect gate passed 520 tests and failed one. Its mocked
completed pod result still emitted retired database wrapper fields and omitted
the required `historical_config_valid` transport fact, so the real solver
correctly rejected the attempt as malformed before the static scorer ran.

## Acceptance

- The fixture supplies the ordinary final database value through the current
  pod-result and Inspect metadata fields.
- Transport evidence contains the current historical-configuration validity
  fact and no retired wrapper fields.
- The focused frozen-tool test and complete offline Inspect gate pass.

## Resolution

The fixture now supplies `database` to the pod result,
`pod_database_value` to direct fake-solver metadata, and the required
`historical_config_valid` transport fact. Retired wrapper fields are absent.
The focused test passes and the complete offline gate passes 521 tests with
eight environment-gated skips.
