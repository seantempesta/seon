---
type: issue
status: open
tags: [issue, render, error, sci]
---

# An internal contract violation renders its whole candidate-forms registry as the error payload

## Evidence

Curriculum research probe, 2026-08-03
([bootstrap-curriculum-2026-08-03.md](../../prds/sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md)
§Gaps): the first-defn failure's error value is 276,363 characters because
the violation dumps the complete schema registry / candidate forms into the
payload, allocating ~264 MB en route. Ugly-output standing order applies:
the agent face of an error must be one to three honest lines with bounded
evidence, never a registry dump.

## Expected owner

The error construction site in the contract-violation path (instrument /
install seam). The error-model wave's default renderer plus the
`:seon.instrument/contract-violated` override (the offending key/value
pair) is the target face; the payload must be bounded at CONSTRUCTION, not
merely windowed at print time — a 264 MB allocation happens before any
renderer runs.

## Acceptance

A contract violation's error value is bounded at construction (the
offending key, value, expected shape, and a capped context), renders
through the declared class face, and the 276 KB reproduction from the
research doc returns a value under the admitted inline ceiling.
