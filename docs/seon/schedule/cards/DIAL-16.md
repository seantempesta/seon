---
title: "DIAL-16: Calibration window is a dial, if prompt selection survives"
type: reference
status: waits on S4-07b3
created: 2026-10-08
---

# DIAL-16: Calibration window is a dial, if prompt selection survives

Ledger row DIAL-16 (group D). Source: `dials/dials.md` D30. Model: Sonnet.

## Site (verified at `1fcdbf47a`)

`seon.cluster.prompt/calibration-for` (`src/seon/cluster/prompt.clj:74-104`) passes the literal `10` (samples) to
`tokens/recent-calibration` (`:98-104`).

## Rule

S4-07b1..3 delete prompt selection. **First**: `rg -n "calibration-for|recent-calibration" src`. If
`cluster/prompt.clj` or `calibration-for` is gone, this row is dropped (mark it so in the ledger). Otherwise:

- `resources/seon/schemas/seon.config.ai.edn`:
  `:calibration-samples [:int {:min 1 :seon.config/dial true :seon.config/default 10 :description "Recent completions a model's token calibration averages."}]`.
- `calibration-for` reads it from the agent's cluster row (`config/agent-cluster-dial database agent-id :seon.config.ai/calibration-samples`,
  `src/seon/config.clj:905`; a nil answer takes the shipped decision through `config/effective`, never a literal).
- Proof `seon.cluster.prompt-test/calibration-window-comes-from-config`: with the dial 2 and five recorded
  completions, the calibration averages the last two.
- `--test seon.cluster.prompt-test --test seon.config-defaults-test`
- Commit: `bin/lane commit <lane> -m "DIAL-16: the calibration window is a dial" --test seon.cluster.prompt-test --test seon.config-defaults-test resources/seon/schemas/seon.config.ai.edn src/seon/cluster/prompt.clj test/seon/cluster/prompt_test.clj`

## Cost

D: one pull per calibration (already reading the agent's rows).

## Dependencies

S4-07b3.
