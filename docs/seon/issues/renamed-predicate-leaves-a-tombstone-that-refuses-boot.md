---
type: issue
status: open
severity: blocker
tags: [issue, boot, schema, projection, publication, class/p1]
---

# A renamed core predicate leaves a row the projection treats as live, and a fresh cluster refuses to boot

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
`src`/`resources` references the old name except two comments. Yet the
published corpus still carries a predicate row for `my.shell/stdin?`, and
the projection build demands a callable for it. Program identity rows never
retract (ruling 47), so the old registration survives as a tombstone; the
projection must not treat a tombstone as a live predicate requirement.

The lane's gates were green because fixtures build their projection from
source, not from the published branch: this is the reset-boundary live
proof AGENTS.md §5 requires for schema changes, and it was skipped.

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
