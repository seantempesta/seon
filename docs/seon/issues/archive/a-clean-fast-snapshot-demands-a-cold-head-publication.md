---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [issue, test, reset, performance]
---

# A clean fast snapshot demands a cold HEAD publication

The reset branch rebased the overlay-admission change `6f80d1a4d`. A
`bin/test-fast --paths` snapshot whose printed difference from HEAD was empty
then exited 64 before acquiring a slot or starting a JVM: "No published
program graph matches HEAD ...; orchestrator must run: bin/test --prepare-head-base".
Lanes cannot run that cold preparation, and a committed reset branch cannot use
the incompatible live publication as its source graph.

The launcher now checks the bytes of the actual snapshot against HEAD,
including untracked files. An exact HEAD snapshot has no overlay closure to
establish and skips that admission only. A changed snapshot retains the existing
published-graph requirement and caller-completeness check. Slot admission,
contract arming, tests and cold gates are unchanged. No new bypass flag exists.

This fix is isolated on reset-batch. At the first refusal, the main tree's bin/test and selection.clj
were another lane's uncommitted boundary; neither was edited. Final reset G5
fast verification exercises the actual clean-snapshot path. The runner lane subsequently landed `d88837ddd`; reset-batch rebased through
`5dd6ef7cc` and reconciled this condition with its source-matched manifests and
automatic cold preparation. Fast iterations still never start preparation.
