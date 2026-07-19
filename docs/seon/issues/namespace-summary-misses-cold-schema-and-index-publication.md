---
type: issue
status: open
tags:
  - namespaces
  - schema
  - indexing
  - database
---

# Namespace summary misses cold schema and index publication

## Failure

The Stage 6 live database proof on 2026-07-19 found that
`:seon.ns/summary` was registered in source but absent from the installed
default schema after a clean current-artifact boot. The proof transaction's
first temporary namespace row carried `:seon.ns/summary`; that ordinary domain
transaction lazily installed the attribute at basis transaction 536870933.
Cold readiness therefore did not publish the schema required by the generated
namespace catalog.

This is one indexing/publication ownership gap:

- `seon.analyzer-info/namespace-info-from-source` already derives the complete
  namespace docstring, its trimmed first line as `:seon.ns/summary`, and require
  edges from the leading namespace form;
- `seon.analyzer-info` already registers `:seon.ns/doc` and
  `:seon.ns/summary`;
- the canonical `:seon.ns` database entity schema in `seon.agent` declares
  only name, source, and optional require edges; and
- the boot indexer's `seon.client/ns-row` writes name/source and independently
  extracts only require edges instead of merging the existing namespace-info
  projection.

The real Stage 6 acquisition subsequently rendered the temporary model summary
correctly, but only after that domain transaction installed the missing schema.
All temporary plan, run, message, and namespace entities were retracted; four
residue queries returned `[[] [] [] []]`. The installed summary attribute is a
real product schema fact, not temporary domain residue.

## Required resolution

Strengthen the one existing namespace entity/index publication path:

- add optional `:seon.ns/doc` and `:seon.ns/summary` fields to the canonical
  `:seon.ns` entity schema;
- have `seon.client/ns-row` merge the existing
  `seon.analyzer-info/namespace-info-from-source` result rather than parsing or
  deriving namespace documentation a second way;
- retain absent attributes for undocumented namespaces and keep the established
  indexed-source/stub policy; and
- do not add a catalog guard, duplicate schema registration, lazy installer, or
  second namespace index.

## Acceptance evidence

- A fresh database installs `:seon.ns/doc` and `:seon.ns/summary` before pod
  readiness from the canonical namespace entity schema.
- The cold boot index contains real namespace docs and first-line summaries for
  documented production namespaces, while undocumented namespaces omit them.
- A config-free supervised restart reopens with an identical installed schema
  and identical namespace doc/summary facts.
- No first namespace-domain transaction adds either attribute schema.
- Focused analyzer/index tests prove multiline doc extraction, first-line
  summary clipping, source-stub behavior, and idempotent rebuilt rows.
- The generated namespace catalog renders from those cold-indexed facts without
  causing a schema transaction.
