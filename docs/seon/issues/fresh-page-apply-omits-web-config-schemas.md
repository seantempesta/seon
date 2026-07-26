---
type: issue
status: open
tags: [issue, schema, database]
severity: blocker
---

# Fresh page apply omits web config schemas

## Evidence

On 2026-07-26, a reset of the default cluster followed by
`bin/seon cluster apply default` built release
`aeef9e9f7244784469b11cc1b11d78ba9578f9ca4d3438a74c14ac38886fca4c`.
The one JVM program indexer published 12 initialization pages containing
4,075 fact rows. Pages zero through four committed; page five was rejected:

```text
A transaction attribute has no canonical schema form.
{:seon.db.writer/attributes
 [:seon.agent.web/policy
  :seon.agent.web/search-backend
  :seon.agent.web/search-model]}

```

All three exact attributes have registrations in `src/seon/agent/web.cljc`.
They are also referenced by the configuration schemas in
`src/seon/config/resolve.cljc`. This points to the JVM indexer's canonical
schema projection or page partitioning, not an absent source registration.
The failed apply left the default database partially initialized and did not
publish a release template.

The complete apply output and timing were observed directly in the operator
run: the command exited after 55.74 seconds. This is not a valid page-reset
benchmark because it includes the artifact rebuild and did not complete.

## Acceptance

- The JVM-generated initialization pages contain canonical forms for the
  three exact web configuration attributes.
- A fresh reset and apply commits all 12 pages without adding another pages
  producer or runtime derivation.
- No schema or writer admission check is loosened.
- A completed apply publishes the at-rest release template, enabling an
  honest template-clone versus page-apply measurement.
