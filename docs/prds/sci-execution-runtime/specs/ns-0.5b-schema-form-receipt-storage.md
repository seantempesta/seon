---
type: prd
status: active
tags: [prd, architecture]
---

# NS-0.5b — schema.form extraction, eval receipt promotion, db.storage

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

## Goal

The second NS-0.5 wave from the accepted review (read its §1:
`docs/prds/sci-execution-runtime/research/ns05-ns5-design-review-2026-07-21.md`).
Three moves, in dependency order inside one unit:

1. **Extract `seon.schema.form`** (`.cljc`) from `seon.schema.internal`:
   the reusable Malli-form inspection functions — primitive forms,
   property extraction, map inspection, enum members, nilability
   (review cites `src/seon/schema/internal.cljc:18-67,154-162`).
   Registration admission and entity-shape derivation STAY in
   `seon.schema.internal` (parent-only). Rewire the six external
   consumers to the new owner: `src/seon/db/datahike/schema.clj:26,298`,
   `src/seon/host/record.clj:25,283-299`,
   `src/seon/db/writer.clj:36,472-475`, `src/seon/client.cljs:111`,
   `src/seon/db/internal.cljs:12`, `src/seon/eval.cljs:77` (death-row
   file — require-line edit only). Also replace the duplicate
   form-property implementation the internal itself documents at
   `src/seon/schema/internal.cljc:22-27` if it is the trivial
   consolidation the review implies; stop and report if it is not.
2. **Promote `seon.eval.internal` → `seon.eval.receipt`** (`.cljc`):
   the file is one coherent receipt contract (receipt state +
   start/terminal transaction builders, `src/seon/eval/internal.cljs:11-67`).
   Rewire `src/seon/runtime/recovery.cljs:20,392`. Promotion to `.cljc`
   only needs to COMPILE on both tiers — do NOT rewire
   `seon.host.record` onto it in this unit (its richer terminal row
   composes around the receipt later, at W3; the review's CORRECTION
   confirmed record does not require the internal today).
3. **Extract `seon.db.storage`**: `edn-encoded-attr?` +
   `encode-edn-slot-values` (`src/seon/db/internal.cljs:235-264`)
   move to the new narrow owner, which now consumes the Malli-form
   functions from `seon.schema.form` (this is why the unit is ordered
   1→3). Rewire `src/seon/db.cljs:825` and
   `src/seon/client.cljs:1065-1087` (client drops its `db.internal`
   require at `:72`); `seon.db.internal` keeps everything else.

## Owned paths (touch nothing else)

- `src/seon/schema/internal.cljc`, new `src/seon/schema/form.cljc`
- `src/seon/eval/internal.cljs` → `src/seon/eval/receipt.cljc`
- new `src/seon/db/storage.cljs` (or `.cljc` ONLY if a JVM consumer
  exists today — check, don't speculate), `src/seon/db/internal.cljs`,
  `src/seon/db.cljs`
- require/alias-line edits only: `src/seon/db/datahike/schema.clj`,
  `src/seon/host/record.clj`, `src/seon/db/writer.clj`,
  `src/seon/client.cljs`, `src/seon/eval.cljs`,
  `src/seon/runtime/recovery.cljs`
- test files requiring the renamed/moved names (enumerate in summary;
  `test/seon/internal_boundary_test.cljs` likely needs its allowlist/
  expectations updated to the new owners — keep its law intact).

Protected: everything else. The cluster is UP — you may run tests but
NOT `bin/seon up/down/restart/reset`; expect the watcher to hot-rebuild
as you move files (a transiently broken intermediate state is normal;
make the moves in compile-coherent steps where possible). No commits.

## Gates (run them; report honest results)

- `bin/test-cljs` FULL once at the end (client/eval/recovery seams).
- `bin/test-writer` FULL once at the end (datahike.schema, writer, and
  host.record consume `seon.schema.form` on the JVM tier).
- rg proof: `seon.schema.internal` required only by `seon.schema`;
  zero `seon.eval.internal` tokens; `seon.db.internal` required only
  by `seon.db`. Show the commands + output.
