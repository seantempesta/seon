---
type: issue
status: resolved
severity: blocker
tags: [issue, boot, schema, projection, publication, class/p1]
---

# A publication retained a reference to a renamed predicate and refused boot

Observed 2026-09-09 14:12: `bin/seon init default --force` from `:current-src`
commit `6aa14a3f…` (HEAD at `ee9feedb4`), then `bin/seon start`:

```
boot failure: The cluster instance failed above the REPL:
Predicate my.shell/stdin? has no admitted callable in the corpus projection.
```

`105acca21` (context-blocks lane, "positional shapes move to seon.*")
renamed the predicate registration from `my.shell/stdin?` to
`seon.shell/stdin?`: `resources/seon/schemas/my.shell.edn:31` and
`src/my/shell.clj:20` both say `seon.shell/stdin?` at HEAD, and nothing in
`src`/`resources` references the old name except two comments. The failure establishes a retained reference to `my.shell/stdin?`; it does
not establish that an identity-only tombstone caused it. The exact offending
row was not retained before complete republication. Program identity rows
never retract (ruling 47), and a projection must ignore identities that no
longer carry definition facts.

The missing regression was the complete publication/rename/fork/boot
sequence. Source-only projection checks do not establish that boundary.

## Fix

- The projection build (`seon.schema/projection-from-database` and the
  corpus admission it feeds) ignores predicate identities with no current
  definition facts (tombstones), or the rename path retracts the
  registration facts of the old identity in the same publication.
- Regression: register a core predicate, publish, rename it, publish,
  fork a cluster from the publication and boot it; boot succeeds and the
  old name resolves to nothing.
- Interim recovery used: a complete `bin/seon init` republication, then
  refork and start.

## Verified publication boundary, 2026-09-09 resume

`test/seon/predicate_publication_test.clj` starts from the canonical complete
published base. It publishes a registered old predicate and a schema that
actually names it; verifies the predicate participates in that projection;
then publishes the current source manifest through the real reconciliation
path. The old function identity remains with exactly its id and symbol,
its source/spec/namespace facts are gone, and the canonical schema names
`seon.shell/stdin?`. The host's temporary old namespace is removed before
projection and boot. A new cluster forks that publication and boots; the
old name does not resolve in SCI, while the new predicate does.

This passes against the existing projection build: its queries join current
`:seon.schema/form` and `:seon.fn/spec` facts, and reconciliation retracts the
old definition attributes. An identity-only tombstone does not request a
callable. Adding a second filter would not explain the historical failure.
The first fast run passed 1 test / 11 assertions. The retained historical
failure remains unattributed because its offending schema/contract row was
not saved; this note does not claim that absent evidence proves its cause.

`seon.schema/register-core-predicate!` is an eager callable assertion; it
stores no registry entry or database row. The durable predicate reference
is inside an authored schema or function contract. An unresolved reference
there is a live declaration problem, whereas a function identity with no
source/spec is not a predicate requirement.

## Resolution (2026-09-15 triage)

surface: adoption-publication

Commit `3d13aa0f7` supplies the previously missing real publication/fork/boot regression. At HEAD `859c9258c`, `test/seon/predicate_publication_test.clj:16–97` publishes the old predicate and referencing schema, reconciles the actual current manifest, verifies an identity-only tombstone, boots a fresh fork, and checks old SCI resolution is absent and `seon.shell/stdin?` present. The historical offending row was never retained, so its precise cause remains unknown; the note's alleged tombstone requirement is falsified by this sequence. Current projections require definition facts, not identity alone. Verification: inspected the entire regression at HEAD; the focused canonical fast run is recorded in the slice C landing note.
