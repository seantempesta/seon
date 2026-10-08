---
title: "SR-R0: measure what precise read evidence saves"
type: reference
status: ready
created: 2026-10-08
---

# SR-R0: Measure what precise read evidence saves (diagnosis)

Ledger row SR-R0 (group E). Feeds owner question AU-Q10 (`README-audits.md`). Model: Sonnet. No source change.

Seon captures each render's and evaluation's reads as precise evidence (`seon.db`, query capture and replay,
`db.clj:700-986`, `:1228-1370`) so a write to another entity sharing an attribute does not force a re-render. Datahike
already gives attribute-level dependency plans. The question is how often precision avoids work, against what it costs
to store (`:datahike.read/dependency-plan`, `:datahike.read/revision`, `:seon.db/read-request`: 1.6 M characters on
default, 49% repeats).

## Work (JVM-mode `eval_clj` on default, read-only)

1. Over the evaluations of the last day on default, for each stored read-evidence row: would attribute-level
   invalidation have re-run it at each later commit that touched its attributes, and did precise evidence say
   "unchanged"? Count both (re-renders avoided).
2. The bytes written per evidence row and in total, and the time precise replay took (`seon.profile` rows of the
   replay functions).
3. One table and a one-paragraph answer for AU-Q10: re-renders avoided per day, their measured cost, versus evidence
   bytes and replay time.

## Output

`docs/research/agent-platform/2026-10-08/read-evidence/precision.md` with the forms used.

## Commit

`bin/lane commit <lane> -m "SR-R0: what precise read evidence saves on default" docs/research/agent-platform/2026-10-08/read-evidence/precision.md`

## Dependencies

None.
