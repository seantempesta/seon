---
type: issue
status: open
severity: blocker
tags: [issue, database, web, architecture]
---

# Derive read dependencies from Datahike's parsed semantics

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
