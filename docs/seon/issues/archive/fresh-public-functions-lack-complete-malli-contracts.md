---
type: issue
status: resolved
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

## Resolution

Re-verification found the original inventory current except that `file-lock?`
and `malli->datahike-schema` had already gained contracts during the database
boundary work, while the codec work had added three already-contracted public
functions. Every remaining public function now carries a complete contract.

The six work-launcher functions reference one named, constructible launcher
shape. Runtime-object predicates accept the named ordinary value boundary and
their registered predicate schemas retain generators that construct real
server sockets, Datahike connections and database values, file locks, SCI
interrupt functions, and SCI contexts.

The recurring check reads every fresh `.clj` and `.cljc` form with
`clojure.tools.reader`, including reader conditionals and namespace aliases; it
has no source roster or exception list. The instrumentation proof confirms the
six launcher vars are collected by the existing computed public-var rule.

The first full gate exposed one additional constructibility defect: the
connection and file-lock generators reused mutable singleton samples, so an
earlier generated lifecycle could release the object a later contract received.
Those generators now construct fresh owned runtime objects per sample, and the
regression explicitly releases one sample before demanding another valid one.

Proof:

```text
bin/test seon.public-contract-test seon.instrument-test seon.flow-test \
  seon.schema-test seon.schema.datahike-test
Ran 35 tests containing 184 assertions.
0 failures, 0 errors.

bin/test seon.public-contract-test seon.instrument-test
Ran 11 tests containing 40 assertions.
0 failures, 0 errors.

bin/test
Ran 548 tests containing 2310 assertions.
0 failures, 0 errors.
```
