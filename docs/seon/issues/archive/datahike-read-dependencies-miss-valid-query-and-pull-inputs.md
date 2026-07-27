---
type: issue
status: superseded
severity: blocker
tags: [issue, database, web, architecture]
---

# Derive read dependencies from Datahike's parsed semantics

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — P3 read-side admission.** The maintained parser projection
is exposed; P3 must make cache, listener, schema-change, and generated
false-negative coverage consume the same fail-closed interpretation.

## Triage — 2026-07-23

REAL+INDEPENDENT (L), owned by Datahike's parsed read-dependency projection.
Current reactive code consumes database read evidence
(`src/seon/reactive.cljs:94-126`), so false-negative dependencies still threaten
cache inheritance/listener selection independently of P4.

## Problem

Datahike's query dependency projection can omit attributes that change a valid
query result. Query-cache inheritance and selective interests consume that
projection, so the omission can preserve a stale result or suppress a required
transaction event.

## Evidence

`datahike.query/query-attribute-dependencies` normalizes a query but hand-walks
raw `:where` forms. A valid `missing?` database predicate returns an empty
dependency set. Find-pull extraction reads only the root PullSpec map keys, so
nested subpattern attributes are omitted and a reverse pull records its display
key rather than the canonical stored attribute in the parsed option's `:attr`.

Executable parser probes on maintained Datahike
`4c55791be1fb8bb8d9332f21c576f5c20b85b760` confirmed that
`{:person/friend [:person/email]}` carries a nested PullSpec and that
`:person/_friend` carries canonical `:attr :person/friend`. Current extraction
does not traverse either value correctly.

The query result cache stores this dependency set with the result and propagates
survivors across a committed child database value. The JVM listener uses the
same set to build its reverse attribute index.

## Owner

The maintained Datahike read-dependency projection and its one interpretation
in cache inheritance and committed-report interests. Seon must not add a second
parser.

## Acceptance

- Query dependencies fold Datahike's typed parsed query plus explicit database-
  function semantics.
- Pull dependencies recursively fold parsed PullSpec values, use canonical
  stored attributes, and widen on a wildcard at any depth.
- Direct pull and pull-many expose the same dependency projection as find-pull.
- Rules, variables, dynamic selectors, unknown forms, and malformed input
  return `:all` unless a narrower law is proved.
- Generated query/pull plus transaction cases prove that every changed result
  is selected; over-selection is allowed and a false negative fails.
- Schema-affecting transactions cannot inherit a result whose interpretation
  may have changed.

## Edge-bundle handoff — 2026-07-23

The maintained checkout at `9c356e32` now contains the pure parsed projections
`datahike.query/query-dependency-plan` and
`datahike.pull-api/pull-dependency-plan`, including exact literal attributes,
lookup-ref identities, and explicit `:all`. The direct-edge program graph
cannot consume those functions without crossing Seon's database authority:
outside `src/seon/db/`, direct Datahike calls are forbidden, while no public
pure `seon.db` projection currently exposes them.

The database owner should expose this one maintained interpretation for
source-analysis consumers. The edge owner then translates exact attributes
directly and maps `:all` to its all-at-basis fact. It must not duplicate the
query or pull parser.

## Projection seam landed — 2026-07-23

`seon.db/read-attribute-dependencies` now exposes the maintained
`query-dependency-plan` and `pull-dependency-plan` as one pure, read-only,
portable projection. It returns only the fork's exact qualified-attribute set
or `:all`, aligns the implicit query database source, and widens malformed or
misaligned requests. Focused CLJ and CLJS contract tests own this boundary.

This closes the edge-bundle handoff, not the whole issue: cache inheritance,
listener selection, generated false-negative coverage, and schema-affecting
transactions remain under the acceptance criteria above.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
