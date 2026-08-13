---
type: issue
status: open
severity: cleanup
tags: [issue, schema, class/p1, wave/schema-admission]
---

# Carry schema source provenance as immutable admission data

## Problem

`seon.schema.edn/!source-files` accumulates schema-key-to-resource provenance
across every `load!` call in the JVM. Fixture loads and later populations merge
into the same map without removal, so a refusal can name a stale file from a
previous population. The map is already derived by `resource-population`; it
does not need an independent mutable lifetime.

## Evidence

- `src/seon/schema/edn.clj:338-349` returns `::files-by-key` with the immutable
  resource population.
- `src/seon/schema/edn.clj:367-385` discards that ownership boundary by merging
  the map into the global `!source-files` atom.
- `src/seon/schema/edn.clj:416-435` later consults only the accumulated atom for
  refusal provenance.
- The isolated scratch JVM on 2026-08-06 held 1,817 entries after boot; no
  database basis or population identity accompanied them.

## Owner

The immutable schema admission request owns source-resource provenance until
publication. Once published, any provenance needed later belongs on the
corresponding `:seon.schema` program row.

## Acceptance

- Delete `!source-files`.
- Admission/refusal functions receive the current population's immutable
  key-to-source map directly.
- Loading a fixture population and then the packaged population cannot report
  a fixture file for a packaged declaration.
- Persist source provenance on `:seon.schema` rows only if a runtime query
  demonstrably needs it after publication.
