---
type: issue
status: resolved
severity: blocker
tags: [issue, component, flow]
---

# Source build rejected an obsolete artifact manifest

## Problem

`bin/seon up` completed the writer, bootstrap, and CSS builds but failed before
publishing the current artifact because `artifact/build!` strictly validated
the previous version-4 manifest against the version-5 schema. A source build
therefore could not perform the rebuild required to replace an obsolete or
damaged derived manifest.

## Evidence

The operator reported missing version-5 execution fields from
`tmp/seon-operator/artifact.edn`. `seon.dev.artifact/build!` called the strict
public `read-manifest` only to calculate the changed-output set before writing
the newly constructed manifest, so validation of dispensable previous state
prevented publication of valid current state.

## Owner

The one source artifact publication path in `script/seon/dev/artifact.clj` and
its focused regression suite in `test/seon/dev/artifact_test.clj`.

## Acceptance

- Packaged operation continues to reject an invalid shipped manifest.
- Direct `read-manifest` remains strict for callers that depend on a valid
  published artifact.
- A source build treats an unreadable previous manifest as absent, publishes
  the fully validated current manifest, and reports both writer and application
  outputs changed.
- Focused artifact tests pass and `bin/seon up` advances past manifest
  publication.

## Resolution

The source-only publication path now treats failure to read its previous
derived manifest as a cold publication. Strict reads and packaged operation are
unchanged. The regression proves a source build replaces the obsolete value and
reports both output classes changed.
