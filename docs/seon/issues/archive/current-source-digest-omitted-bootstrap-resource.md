---
type: issue
status: resolved
severity: blocker
tags: [issue, source, build]
---

# Current-source digest omitted the bootstrap resource

## Problem

The schema-resource consolidation narrowed the packaged initialization digest
to `resources/seon/schema.edn`, while `seon.cluster/populate-source!` also
installed facts derived from `resources/seon/bootstrap.edn`. Broad development
hashing happened to include both resources, but packaged publication could
reuse a source digest after the installed bootstrap population changed.

## Evidence

`src/seon/cluster.clj` previously named the broad `resources` directory while
`build.clj` named only `resources/seon/schema.edn`. The population owner calls
`seon.bootstrap/population-tx`, whose input is the classpath resource
`seon/bootstrap.edn`. The two publication paths therefore disagreed about the
bytes identifying the same `current-src` population.

## Owner

The exact source roots in `src/seon/cluster.clj` and the packaged
initialization-page digest in `build.clj`.

## Acceptance

- Development and packaged publication hash the exact schema and bootstrap
  resources installed into `current-src`.
- Neither path discovers those inputs through a broad resource-directory walk.
- A recurring boot test names both exact resource inputs.

## Resolution

Resolved by `e0f123a84`. Both publication paths now hash
`resources/seon/schema.edn` and `resources/seon/bootstrap.edn`, and the focused
source-root plus incremental-refresh regression vars pass.
