---
type: issue
status: open
severity: friction
tags: [issue, database, cluster, publication]
---

# Read-refusal checks test `:seon.error/at`, which not every `seon.db/q` refusal carries

Sibling of `declaration-settlement-consumes-invalid-read-as-ref.md` (same
class: a caller iterates a refusal map as results). Observed 2026-09-25T03:25Z:
`bin/seon init --dev default` refused with "Development namespace dependents
could not be read." (`src/seon/cluster.clj` `development-namespaces`), the
offending `:seon.ns/requires` value being the map entry
`[:seon.db/invalid-read true]` (raw: `tmp/orchestrator/init-after-fix.txt`).
The earlier dependents read had returned a refusal WITHOUT `:seon.error/at`;
the check `(when (:seon.error/at result) ...)` missed it and `mapcat` walked
its entries into the next query.

Producer side (`src/seon/db.clj`, not fixed here): every `q` refusal carries
the declared member `:seon.db/invalid-read`, but three return paths need not
carry `:seon.error/at`: `q` line ~2372 (assoc onto a non-query first
argument), ~2395 (`source-argument-error` returns a refused source argument
as given, or a non-database ambient value), and `dependency-error`'s first
branch (`ex-data` carrying `:seon.schema/expected-value`, returned as is).
`q`'s output union `[:or :seon.schema/value :seon.db/invalid-read-error]`
admits such a map as `:seon.schema/value`, so the armed output contract
never catches it.

Not reproduced after the 03:26:38Z restart: the same `refresh-source!` path
adopted cleanly (armed contracts turn the refused-source path into an
at-carrying dependency refusal). The failing JVM was the pre-restart one with
18 stale Vars, so the exact first refusal is unrecorded.

Fixed in `development-namespaces`, `development-source-refresh!` (prior-commit
pull) and `seon.cluster.source/changed-identities` (lane
fix-adoption-dependents). Remaining: the other `(:seon.error/at X)` checks on
database reads in `src/seon/cluster.clj` and elsewhere, and making `q`'s
refusals always satisfy `:seon.db/invalid-read-error` (narrow its output union
so a refusal-shaped map without the base members refuses at the producer).

## Observation 2026-09-25 (lane delete-duplicates)

`seon.render.ns/read-refusal?` keyed on `:seon.db/read-operation`, which no
current `seon.db` refusal carries, and it called `contains?` on string and
hiccup sections, throwing `IllegalArgumentException` on any distance-1 namespace
that references schemas. It now tests the declared member `:seon.db/invalid-read`
on maps only. Producer gap found at the same REPL: a bounded `seon.db/pull`
budget refusal (`{:selector [:seon.ns/name {:seon.fn/_ns [:seon.fn/sym]}] :max-results 1}`
on `seon.flow`) returns `:datahike.budget/*` plus the error base but neither
`:seon.db/invalid-read` nor `:seon.db/refused-read-operation`, so every
member-keyed check misses it; the equivalent `q` refusal carries both.

