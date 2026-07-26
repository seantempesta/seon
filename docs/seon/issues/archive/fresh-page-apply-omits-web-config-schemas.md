---
type: issue
status: resolved
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

## Resolution

Resolved on 2026-07-26.

- `f0de5e1dc` computes the first-party schema-owner closure from emitted row
  attributes, covering the web configuration attributes without a namespace
  hand list.
- `bee74572c` projects every bridge facet emitted by
  `seon.db.datahike.schema`, including `:db.secondary/only`, from the bridge's
  own implicit schema specification.
- `cf9d6986c` keeps writer admission strict while recognizing dependency-owned
  implicit schema forms.
- `6bb8c0b09` applies the manifest delta through the same canonical writer
  admission and computes its schema-owner closure from the exact nested rows.
- `7a00da230` and `976aae5cd` let the operator complete the JVM-owned config
  transaction after the page fallback and publish the closed template.
- `3548872cd` and `294547f50` retain one source-current artifact identity
  across `cluster apply` and watcher startup.

The final fresh page run committed all 12 pages. Its page receipts spanned
3.33 seconds (23:08:48.187 through 23:08:51.512) on the default APFS
development database; the enclosing artifact-build/apply command took 99.87
seconds. A later source-current apply completed and published its template in
66.30 seconds including the artifact rebuild. The current template reset took
7.13 seconds end to end, including operator/JVM startup.

`bin/seon up` subsequently admitted the stable artifact and reached watcher,
writer, and host readiness. The condemned pod then failed in committed-program
reconstruction; that is downstream of page completion, release identity,
configuration, writer readiness, and host readiness.
