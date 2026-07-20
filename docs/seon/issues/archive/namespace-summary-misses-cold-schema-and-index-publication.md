---
type: issue
status: resolved
tags:
  - namespaces
  - schema
  - indexing
  - database
severity: friction
tags: [issue]
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

## Resolution

Resolved by `f7da0e60`. On a fresh default database, both attribute schemas
were installed at basis transaction 536870915, before the first ready database
value at basis transaction 536870917. At readiness, the index already held 120
namespace metadata rows: `my.kb` retained its full source with exact doc and
summary, while stub-backed `seon.warn` retained `(ns seon.warn)` plus its real
doc and summary. Both installed attributes were cardinality-one strings with
no uniqueness.

A config-free supervised restart reopened the identical database value at
basis transaction 536870917 and commit ID
`6a5d4329-1230-5758-b847-1254010db352`. The installed schemas were identical,
and the sorted 120-row namespace metadata projection retained SHA-256
`691d6970d5d99b5fce812f6acefcd70ce40366ff7abe4b348b4ea134db313cad`.
The `my.kb` and `seon.warn` projections were unchanged, and current writer and
pod logs contained no unknown-attribute, schema, or core errors.

The earlier real Stage 6 cause-graph proof had already acquired the generated
plan and namespace catalog and retracted its temporary plan, run, message, and
namespace facts, with four residue queries returning `[[] [] [] []]`. The
fresh acceptance run made no temporary domain write—the database stayed at
basis transaction 536870917—so no lazy schema transaction was involved.

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
