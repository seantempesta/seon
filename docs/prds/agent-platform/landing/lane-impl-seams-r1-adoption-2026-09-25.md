---
type: landing
status: proof recorded (read-only, d/with); no source change
created: 2026-09-25
scope: lost-error seams R1, proving its schema retirement adopts into default before default moves to HEAD
---
# Landing: R1 schema retirement adopts

What exists: boot adopts packaged schema in two steps through `seon.cluster/accrete-schema-population!`. First
`declaration-changes`, which retires attributes via `retired-attributes`. Then `schema-row-changes`. Each write goes
through `seon.db/transact!`, which validates with `write-error` and encodes with `encode-transaction-in`.

## Broken or unproved

- **Retired core schema rows survive adoption.** 14 `:core` `:seon.schema/key` rows remain, including
  `:seon.flow/fault-channel-overflow-error`. Filed as
  `docs/seon/issues/schema-row-adoption-never-retracts-a-retired-core-schema-row.md`. The owner is
  `schema-row-changes` in `src/seon/cluster.clj`, which I do not hold. This is not a refusal, so the A/B two-step
  is not needed.
- The proof ran on `d/with`, not on boot itself. Default reads schemas from its snapshot
  `data/source/f240fd666.../resources/seon/schemas`, so HEAD's forms were read from a `git archive` extraction.

## Evidence

The form is `tmp/impl-seams-r1-schema/adoption-probe.clj`, run on default (pid 94554) in JVM mode, read-only,
through `(seon.cluster.boot/connection "default")`.

```clojure
{:declaration-ops 5, :declarations-refused nil,   ; 4 retractEntity + :seon.print/rendered-token-ceiling
 :row-ops 10, :rows-refused nil,
 :attributes-after [],                             ; :seon.error/dropped-fault-{count,digest} gone (0 datoms before)
 :key-rows-before {:seon.error/dropped-fault-count 1, :seon.error/dropped-fault-digest 1,
                   :seon.flow/fault-channel-overflow-error 1},
 :key-rows-after  {... same, 1 each ...},          ; the issue above
 :ms 334.8}
```

An earlier run called `datahike.api/with` on the raw rows. It refused on `:seon.print/default 50000` against the
installed `:db.type/string`. That was an artifact of the probe, which skipped `encode-transaction-in`: the stored
rows hold `"50000"`. With the owner's encoding, the rows converge, and the ceiling row reads
`{:seon.config/dial true, :seon.print/default "50000"}`.
