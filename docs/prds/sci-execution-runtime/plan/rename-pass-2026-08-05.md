---
type: prd
status: active
tags: [prd, naming, schema, rulings]
---

# The rename + reset + rebuild pass — spec (2026-08-05)

One frozen-tree pass executing the owner-ruled renames PLUS the
[naming-coherence audit](../research/naming-coherence-audit-2026-08-05.md)'s
units (owner ruling 2026-08-05: "Add the naming audit's remaining
units"), reconciled with the rulings that postdate the audit. **NO
MIGRATION, NO PARALLEL CODE** (owner verbatim): zero compatibility
reads, zero old spellings anywhere in `src/`, `resources/`, `test/`,
active `docs/`, or `.agents/skills/` afterwards, verified by search.
The pass ends in ONE reset → complete `current-src` republication →
fresh refork → boot + query proof. Because reset wipes, every
"cluster-data migration" cost in the audit is void — renames land
outright.

## Reconciliations (where newer rulings override the audit)

1. **Audit U4 inverts**: the owner ruled the database-value MAP KEY is
   `:seon.db/db` (declared; schema stays `:seon.db/database-value`).
   The unit becomes: DECLARE `:seon.db/db` in the registry as the key
   carrying a database value; no 42-file key rename.
2. **Audit U1/U12 are absorbed**: `:seon.code.*` is retired in favour
   of `:seon.def/*` (ruled 08-05), and the agent's defs PRD
   ([agent-desk-and-checkout-prd-2026-08-05.md](agent-desk-and-checkout-prd-2026-08-05.md))
   later replaces the write path. THIS pass renames the fact family
   `:seon.code.def/*` → `:seon.def/*` including
   `/unrestorable` → `/unrestorable-reason`; the mechanism redesign is
   NOT this pass.
3. **Operator units (audit U11/U13) land on TODAY'S code** — the
   operator-consolidation lane (`7dacba8ba`, `61cbb93ed`) already
   rewrote records and waits; audit line numbers there are stale.
   `filesystem-space` vs `footprint` already split; the `/bytes` →
   `/file-bytes` and cleanup `/reclaimed-bytes` →
   `/removed-file-bytes` renames still apply. Claim files are simply
   re-recorded after reset — no atomic rewrite machinery.

## Ordered units (one lane, one path-limited commit each, in order)

| # | Unit | Content |
|---:|---|---|
| 1 | Stale-doc kill | Delete the `seon.code.fn/.ns/.schema/.test` rename note from the AGENTS.md vocabulary row and the two stale parse-plan references (audit U1's doc half). |
| 2 | Ruled renames | `:seon.code.def/*` → `:seon.def/*` (+ `/unrestorable-reason`); `:seon.store/branch-connection` → `:seon.db/connection` everywhere (~55 files); lifecycle union → `:seon.store/connection-object`; `:seon.config/connection` dies into `:seon.db/connection`; `:seon.boot/cluster-connection` keeps its role key, validated as the connection-object. |
| 3 | Declare `:seon.db/db` | Registry declaration for the map key; verify the eleven map-schema slots validate against `:seon.db/database-value`. |
| 4 | Program-owned shapes (audit U2) | Declare `:seon.program/identity`, `/declaration-row`, `/deletion-row`, `/row`, `/rows`, `/shape`, plus the file-artifact and manifest shapes; `:seon.sci.eval/program-row` and `:seon.source/rows` die into them; canonical schema-row declarations move to the registry behind the bootstrap admission probe. |
| 5 | Proof shapes (audit U3) | Declare `:seon.cluster.curate/declaration`, `/proof-receipt`, `:seon.eval.drive/terminal-state`; plural keys defined in terms of them. |
| 6 | False "context" names (audit U5–U7) | `:seon.bootstrap.plan.form/context` → `/help-text`; `:seon.db/capture-context` + `db/*capture-context*` → `/read-evidence-sink` (+ declare `:seon.db/captured-read`, `/read-evidence`); `effect/*context*` → `*request-context*`; `:my.edit/context` → `/source-window` (+ `-complete?`). |
| 7 | Bootstrap + schedule leaves (audit U9/U10) | `:seon.ns/name-designation` → `:seon.bootstrap.plan.form/namespace-source`; `:seon.schedule/cron` → `/expression`; `/timezone` → `/zone-id`; delete the unused fire transaction-data alias. Free under reset. |
| 8 | Umbrella nouns (audit U8) | `:my.background/descriptor` → `/receipt`; `:seon.source/population-data` → `/populate-request` (declared); operator existence/log-result/claim-record shapes declared under `:seon.operator.process-record/*` etc. (audit R3's family), replacing `:seon.dev.process/*`. |
| 9 | Footprint + profile (audit U13/U14) | `/bytes` → `/file-bytes`; cleanup `/reclaimed-bytes` → `/removed-file-bytes`; declare the footprint-observation shape carrying `low-space?`; drop the stranded render-profile `/blob-threshold` and its vocabulary claim. |
| 10 | The proof | Full-tree search proves zero old spellings; `bin/seon reset --force`; `bin/seon init`; fresh refork; boot READY; the graduation queries (below); fast test tier green; vocabulary table rows updated in the same beat. |

## Graduation (the boot + query proof bar, owner-ruled)

- search finds no `:seon.code.`, `:seon.store/branch-connection`,
  `:seon.dev.process/`, `capture-context`, `name-designation`,
  `:seon.schedule/cron`, `population-data`, or `program-row` in
  current source, schemas, tests, active docs, or skills;
- the reset root boots READY and a query answers, at minimum: a
  `:seon.def/*` row read-back, a `:seon.db/connection`-contracted
  function found via `:seon.fn.arity/input-refs`, a schedule row under
  `/expression` + `/zone-id`, and the program-row union validating a
  real declaration;
- the fast test tier is green on the frozen tree;
- the operator claim records re-recorded under the declared family.
