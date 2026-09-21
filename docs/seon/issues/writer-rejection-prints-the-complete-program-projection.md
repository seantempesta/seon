---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [errors, writer, rendering, class, unbounded-output]
---

# A writer rejection prints the complete program projection

## Evidence

The slice-4 lane's selected `seon.turn-test` fast run at HEAD `4078d04ee`
reached `a-refused-turn-write-is-bounded-and-commits-exactly-one-fault`.
Datahike's writer printed its request `:args`, including the projection passed
through `seon.schema.datahike/encode-call-output-in`; the resulting log grew to
432 MiB. The intended refusal was `run transition refused: no-such-run`.
The operator-facing diagnostic contained the entire program contract/registry
population repeatedly, instead of the refused turn identity.

The preceding independent failure was `a-refused-generated-form-records-its-refusal`:
`seon.error/diagnostic` called by `seon.turn` at `turn.clj:2091` lacked required
`:seon.error/at`, replacing the expected `generated-read-depends-on-turns`
diagnostic. These are protected turn/error owners, not publication changes.
The lane terminated its own launcher (PID 40926), which reaped JVM 41184;
no test tally or proof of the later edited unresolved-settlement test is claimed.

The [bounded excerpt and original byte count/hash](../../prds/steward-platform/research/one-jvm-publication-turn-boundary-2026-09-23.txt)
retain the evidence. The original large log was disposable lane exhaust.

## Owner and acceptance

The database/error writer boundary owns the rejection diagnostic. Preserve the
actual refusal and its identity, without passing the complete projection into
the logger's error representation. Do not add a second presentation clipping
mechanism. A real refused writer transaction must produce a readable diagnostic
without embedding its carried program world. The error/test-system owners need
to route the two existing turn regressions; this lane did not edit their code.
