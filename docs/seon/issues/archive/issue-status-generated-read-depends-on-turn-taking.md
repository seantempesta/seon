---
type: issue
status: resolved
severity: friction
created: 2026-09-16
resolved: 2026-09-16
tags: [issue, agent, test, database, turn]
---

# The live issue status read is refused as depending on turn-taking

## Problem

A system turn for the issue-family worker returns
`:seon.turn/generated-read-depends-on-turns` before reaching settlement.
The offending generated form is `(my.issue/status {:seon.issue/id …})`.

## Cause, isolated 2026-09-16 on default PID 53378

The read has no turn dependency. `seon.turn/generated-read-fault` FABRICATED
one. Measured on default with an explicit `seon.db/*read-evidence-sink*`
around `(seon.issue/status {:seon.db/db db :seon.issue/id
"evaluation-reader-refuses-pulled-renderer-ref"})`: seven reads, of which four
report `:datahike.read/attributes :all`. Those four come from
`seon.test/verified?` → `seon.test/reach-digest` →
`seon.test.runner/reach-digests` → `reach-refresh`, whose identity scan is
`'[:find [?e ...] :in $ [?a ...] :where [?e ?a]]` — the attribute is a query
VARIABLE, so no index pattern names an attribute and the revision reports the
unknown set.

`generated-read-fault` answered that UNKNOWN with `inert`, the whole
`:seon.wake/context-inert` set. The refusal's evidence was therefore
`(= (set offending) (set (seon.cluster.wake/inert-attributes db)))` — 68
attributes, verified equal, none of them read by the form. That is the
"68 attributes" this note previously recorded: not a dependency list, the
inert roster itself.

The refusal was then DROPPED. `evaluate-sources` throws the fault,
`resume-turn`'s `phase` turns it into a turn-level fault, and `settle!` was
called with no `:seon.cluster.eval/ordinal` — so the opening form the same
pass had just appended kept its source with NEITHER `:seon.eval/shown` NOR
`:seon.cluster.eval/error`, `next-ordinal` walked past it, and the turn
closed looking healthy. `seon.issue-test/issue-worker-opening-links-its-issue`
was red at `issue_test.clj:109` and `:112` for exactly this.

## Fix

`src/seon/turn.clj`, both at the owner:

- `generated-read-fault` faults only on attributes the evidence NAMES. An
  `:all` attribute set states no dependency on any particular attribute;
  whether an unnarrowed read must regenerate is decided by its authority —
  the shown-value comparison in `system-turn` and `db/read-evidence-current?`.
  A read that names a turn attribute still refuses, unchanged.
- `resume-turn`'s evaluation-failure arm passes
  `:seon.cluster.eval/ordinal`, so `refusal-terminal-data` records the
  refusal as that evaluation's shown text and error before closing.

## Verification

In-process on default, one test at a time, test namespaces reloaded through
`seon.test`'s own loader, each run on a daemon thread. No test JVM launched.

- `seon.issue-test/issue-worker-opening-links-its-issue` — was 6/2/0; now
  8/0/0 when the opening is given wall time. All four opening evaluations
  carry `:seon.eval/shown`; ordinal 3 is `(my.issue/status …)` and its shown
  text names the issue's test.
- `seon.turn-test/generated-read-evidence-rejects-turn-activity` — 6/0/0. A
  read naming `:seon.turn/id` still refuses with evidence `#{:seon.turn/id}`.
- `seon.turn-test/a-refused-generated-form-records-its-refusal` (new) — 5/0/0.
  Pins the class: an appended form whose evaluation is refused carries a
  terminal fact.

## Residual, filed separately

At the declared 20 s `seon.test-support/event-backstop-seconds` the issue
test is marginal on a loaded machine: the opening is now four evaluations
instead of three-plus-a-silent-drop, at roughly 3.5–4 s per turn-loop pass.
The cold `reach-digests` build is NOT the cost — measured 714 ms cold, 0 ms
warm, on the canonical fixture. See
[opening-turn-pass-costs-seconds-per-form](opening-turn-pass-costs-seconds-per-form.md).
