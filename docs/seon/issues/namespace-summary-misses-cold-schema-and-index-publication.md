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
- cold database initialization publishes the explicit
  `seon.client/agent-bootstrap-attrs` vector, which also omits both metadata
  attributes; and
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
- include those registered attributes in the existing cold bootstrap attribute
  vector so publication does not depend on whether any desired program row
  happens to carry documentation;
- have `seon.client/ns-row` merge the existing
  `seon.analyzer-info/namespace-info-from-source` result rather than parsing or
  deriving namespace documentation a second way;
- retain absent attributes for undocumented namespaces and keep the established
  indexed-source/stub policy; and
- do not add a catalog guard, duplicate schema registration, lazy installer, or
  second namespace index.

## Source and focused proof

The coherent source repair now references the analyzer-owned attributes from
the canonical `:seon.ns` entity schema, includes both in the cold bootstrap
attribute vector, and has `ns-row` call `namespace-info-from-source` once over
the real file source. The persisted full-versus-stub source policy and the
full-source-only require-edge policy remain unchanged; only documentation is
independent of prompt source density. Require edges from the shared projection
are sorted before persistence so rebuilt rows are deterministic.

Focused current-source evidence:

- analyzer, initialization, and index CLJS gate: 39 tests, 204 assertions,
  zero failures/errors;
- writer initialization gate: 4 tests, 25 assertions, zero failures/errors;
- documented full-source and stub-backed namespaces retain real multiline
  docs and first-line summaries, while an undocumented source omits both; and
- a later documented namespace transaction advances the database once without
  changing the installed schema.

Fresh current-artifact boot, config-free reopen, and generated-catalog live
proof remain required before this issue can close.

## Acceptance evidence

- A fresh database installs `:seon.ns/doc` and `:seon.ns/summary` before pod
  readiness from the canonical namespace entity schema.
- The cold boot index contains real namespace docs and first-line summaries for
  documented production namespaces, while undocumented namespaces omit them.
- A config-free supervised restart reopens with an identical installed schema
  and identical namespace doc/summary facts.
- No first namespace-domain transaction adds either attribute schema.
- Focused analyzer/index tests prove multiline doc extraction, untruncated
  first-line summary extraction, source-stub behavior, and idempotent rebuilt
  rows.
- The generated namespace catalog renders from those cold-indexed facts without
  causing a schema transaction.
