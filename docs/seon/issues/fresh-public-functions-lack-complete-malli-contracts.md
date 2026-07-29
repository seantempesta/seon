---
type: issue
status: open
severity: blocker
tags: [issue, schema]
---

# Give every fresh public function a complete Malli contract

## Problem

Thirty-five public functions in the fresh tree have no `:malli/schema`.
Malli collection and runtime instrumentation therefore omit them even though
several are runtime-object predicates used by named EDN schemas and six are
the public work-launcher API.

## Evidence

The complete reader-based inventory is:

- `src/seon/cluster/store.clj:69,79,92` — `connection?`,
  `connection-object?`, `file-lock?`;
- `src/seon/test/runner.clj:210` — `-main` (also the only public function
  without a docstring);
- `src/seon/sci/admit.clj:152` — `interrupt-fn?`;
- `src/seon/sci/eval.clj:113` — `ctx?`;
- `src/seon/flow.clj:384,409,421,432,441,450` —
  `start-work-launcher!`, `stop-work-launcher!`,
  `install-work-launcher!`, `stop-installed-work-launcher!`,
  `current-work-launcher`, `submit!!`;
- `src/seon/schema.cljc:131,142,174,590,662` —
  `canonical-data-fingerprint`, `canonical-data-string`, `sha-256`,
  `malli-form?`, `assert-complete-contract!`;
- `src/seon/schema/internal.cljc:59,161,181,193,210,236,279,308` —
  `assert-complete-schema!`, `identity-attr?`,
  `derive-entity-id-attr`, `map-required-attrs`, `with-entity-id-attr`,
  `assert-compilable-schema!`, `assert-non-nilable-value-schema!`,
  `assert-multi-segment-namespace!`;
- `src/seon/schema/datahike.cljc:11,18,44,49,83,140,149,158,194` —
  every public Malli-to-Datahike form/attribute conversion helper; and
- `src/seon/cluster.clj:113` — `socket-server?`.

The archived `missing-malli-schema.md` records an old-tree defect and does not
cover these fresh owners. The new ruling makes opaque runtime values a reason
to define a named predicate schema with an honest generator, not a reason to
omit the function contract.

## Owner

Each named public var, with shared named schemas in its owning schema EDN file.
The instrumentation collector remains the one enforcement mechanism.

## Acceptance

A reader-based fresh-tree check finds zero public `defn`s without a complete
`:malli/schema`; every opaque predicate contract is constructible; `-main`
has an observed-behavior docstring; and `instrument/apply!` collects the
formerly omitted vars without a special allowlist.
