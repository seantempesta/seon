---
type: issue
status: open
severity: friction
tags: [issue, test, selection]
---

# Recorded selection completion leaves changed members pending

Runs `e63e0d5af33e` and `75801c3ccaed`, 2026-09-23:
`seon.test.selection-test/selection-derives-bases-obligations-and-exact-symbol-reach`
expects no executable members after `complete-selection!`, but receives
`selection.fixture/direct`, `/indirect` and `/reference` with
`:reaches-changed`. Its earlier injected database refusal also fails the
armed return contract of `seon.fn/declared-reference-edges` rather than
propagating the expected read error. The latter is related to
[the gate refusal-contract issue](gate-selection-refusal-contracts-only-distinguish-markers.md).

Both observations predate the scoped reach optimization. The named-selection
regression passes separately; it does not prove this complete-population
sequence. Acceptance: repair the refusal fixture/contract at its owner, inspect
recorded member provenance after completion, and prove the expected repeat has
no executable changed members. The relationship between the two observed reds
has not been established.
