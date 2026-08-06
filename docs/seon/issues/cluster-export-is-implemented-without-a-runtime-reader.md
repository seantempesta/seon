---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, database, operator]
---

# Delete or expose the readerless cluster export surface

## Problem

`seon.cluster.export` is a complete effectful store-copy subsystem with its
own schemas and tests, but no current operator, cluster runtime, build, or
documentation entrypoint calls it. The B2 roadmap implemented the namespace
as an export/import escape hatch; the surviving runtime never attached that
escape hatch to an observable operation.

As a result, hundreds of lines of filesystem, branch migration, and identity
rewrite semantics are maintained by tests alone. The public functions are
still indexed into the program graph even though ordinary agent data cannot
supply the live store handle their contracts require.

## Evidence

- Repository-wide source/config/bin/script search finds `export!` and
  `reidentify!` only in `src/seon/cluster/export.clj` and
  `test/seon/cluster/export_test.clj`; no production namespace requires
  `seon.cluster.export`.
- `resources/seon/schemas/seon.cluster.export.edn` and
  `resources/seon/schemas/seon.export.edn` exist only to contract this
  namespace's requests and results.
- `docs/prds/sci-execution-runtime/plan/unsettled.md:2525-2536` records
  `seon.cluster.export` only in historical B2 sequencing, while the current
  `bin/seon` command roster has no export/import command and current
  architecture pages expose no callable boundary.
- `src/seon/cluster/export.clj:241-298` implements copied-store mutation and
  export, but can only be reached by a direct host call with an open
  `:seon.store/store` value.
- The namespace also owns one of the unsafe recursive-delete copies tracked by
  [[fresh-recursive-deletion-reintroduces-symlink-traversal]], increasing the
  cost and risk of preserving an unattached mechanism.

## Owner

The fresh operator/store boundary. If export remains a required capability,
the operator must own an explicit, observable command over the held store;
otherwise the implementation, schemas, and tests should be deleted together.

## Acceptance

- Choose one mechanism: either expose export through the current operator with
  observed cross-process restore proof, or delete the namespace, schema file,
  tests, and roadmap claims that describe it as built capability.
- No test-only reader is accepted as liveness evidence.
- If retained, the boundary accepts ordinary operator inputs, holds the source
  flock, uses the shared no-follow cleanup owner, and proves every exported
  branch opens under its new store identity.
- If deleted, repository-wide search finds no `seon.cluster.export`,
  `:seon.export/*`, or import/move promise without a live owner.
