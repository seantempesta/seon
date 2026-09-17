---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test-fixture, evaluation, turn]
---

# Evaluation fixtures still read retired lifecycle shapes

## Evidence

The message-model lane's second path-isolated fast iteration ran 91 tests /
1,207 assertions with zero failures and four errors. Three errors are the
three vars in `seon.gen.loop-test`, all at `planner-census`: `inst-ms` is
called on the Long supplied by `:seon.turn/opened-tx`. The unchanged census
also checks retired `:seon.cluster.eval/result-edn` and `/result-blob` instead
of `:seon.eval/shown`, and selects the first turn without distinguishing an
opening from the provider turn whose evaluations its oracle expects. These
are source observations; the latter paths have not passed the first exception.
No successful routing proof is claimed for this namespace.

The fourth error was an attempted unstarted stored evaluation in the routing
fixture: removing `/at` was refused because the canonical stored evaluation
requires it. The corrected routing regression covers the six constructible
stored states and uses the separate assignment edge. `turn/form-settlement`
still documents an `:unevaluated` arm based on absence of `/at`; its contract
and that arm need reconciliation by the evaluation lifecycle owner, without
weakening the stored entity merely to satisfy an old fixture.

Exact output and the corrected routing rerun are linked from
[the landing note](../../prds/steward-platform/research/message-wake-model-2026-09-17.md).
The earlier [generative-loop fixture incident](archive/generative-loop-fixture-commits-no-run-facts.md)
was a different provider-selection cause and remains resolved. No foreign
session was operated or edited.

## Acceptance

The generative fixture selects its intended ordinary turn from current facts,
counts current evaluation outcomes, and reaches its routing assertions under
the canonical armed harness. The evaluation lifecycle declaration and state
reader agree on whether an unstarted stored evaluation can exist. A missing
turn or failed query remains an explicit failure, never successful settlement.
