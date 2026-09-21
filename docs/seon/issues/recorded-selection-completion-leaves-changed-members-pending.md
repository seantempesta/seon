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

## Admission ownership diagnosis and fixture repair — 2026-09-21

The combined checkpoint reproduces the pending members at
[log lines 1095–1097](../../prds/agent-platform/landing/fresh-start-combined-gate-repaired.log).
The immediate cause is the synthetic completion fixture, not established
production reuse failure. The sequence first admits an open request
(`test/seon/test/selection_test.clj`, `exercise-selection!`), then calls
`complete-selection!` to create a second request. `seon.test/admit-run`
correctly reuses outstanding reservations in the same scope as `covered-by`
refs, with no newly owned members. The old helper only marked new
`:seon.test.run/members` terminal; it never completed the original owner.
The original members therefore remained pending, as they should.

The fixture now retains and completes the original admission. Its synthetic
terminal transaction handles that admission's existing owned member IDs as
well as newly reserved members, and never completes `covered-by` members.
The production selector, reservation owner, and green-evidence rules are
unchanged. This remains synthetic selection evidence, not a claim that those
test bodies executed.

The refusal injection also targeted a superseded query clause. The current
`seon.fn/declared-reference-edges` query invokes the `declared-edge` rule;
the injection now selects that actual read, keeps the same typed refusal,
and asserts the injection was exercised. The owning function's armed return
contract remains part of the test.

The additional `worker-readiness-holds-a-usable-canonical-base` platform
member reported at log lines 1085–1088 is a distinct observation. That test
calls real worker initialization, which derives the canonical program and
contracts. Source inspection alone does not establish why its recorded and
current reach digests differ after this fixture's edits. No expected set was
changed; a comparison of those two reach inputs is still owed before deciding
whether this is legitimate dependency work or a digest defect.

Verification: `git diff --check` passed. No JVM or gate ran in this bounded
fixture correction; root owns the armed regression check. Status remains open
until that check and the extra platform-member diagnosis complete.
