---
type: issue
status: open
severity: blocker
tags: [issue, schema, instrumentation, wave/publication-velocity]
---

# Development adoption cannot compile docstring Var contracts

## Problem

Same-tree development adoption reaches instrumentation and refuses
`seon.dev.docstring/check-file`: its contract references
`#'seon.dev.docstring/check-file-request-schema` and
`#'seon.dev.docstring/check-response-schema`; recompilation reports the
request Var as `:malli.core/invalid-schema`. The worker cannot re-arm after
this failed adoption, so unrelated following tests do not execute.

## Evidence

`tmp/publication-dissolution/morning-publication-head-retry.log`:
`seon.cluster.publication-host-test` boots, records the producer generation,
passes the live-producer guard, then fails in `seon.instrument/apply!`.
The failure includes `:seon.instrument/fn seon.dev.docstring/check-file`
and `:diagnostic-offending #'seon.dev.docstring/check-file-request-schema`.
The subsequent re-arm fails on the same Var contract with 218 installed
wrappers versus 1494 armed at initialization. The test's finally stops its
scratch cluster. No second JVM or cold gate was launched.

## Owner

Development loaded-definition adoption and schema contract compilation.
`src/seon/schema.clj` and its implementation are held by the bridge walker;
the publication lane did not edit them or the docstring owner.

## Acceptance

Run `seon.cluster.publication-adoption-test` explicitly in its own gate.
A freshly booted host records its generation and adopts the same tree;
contract arming succeeds after adoption, including Var-based contracts.
The test is long and excluded from implicit fast/bare/platform selection.

## Gate-restructure repair, 2026-09-21

The docstring script now embeds the values of its local schema declarations
in function metadata, including nested finding/rule schemas. A JVM load
printed `check-file`'s complete vector form with no Var objects. This keeps
the script Babashka-loadable without introducing another schema registry.
`seon.instrument/restore!` retains current replacement closures and re-arms
them under their captured policy using current declarations; it compiles
the complete replacement set before installing those wrappers. Unchanged
definitions still recover their exact captured roots.

`failed-loaded-definition-scope-restores-arming` in the existing adoption
namespace reloads the actual docstring owner inside an exceptional scope,
checks that the replacement remains, that the entering armed Var set returns,
and that the expanded contract and real checker work. The original full
hosting-JVM adoption regression remains intact.

Explicit validation was attempted with `bin/test-fast --paths
script/seon/dev/docstring.clj src/seon/instrument.clj
test/seon/cluster/publication_adoption_test.clj --
seon.cluster.publication-adoption-test`. `tmp/gate-restructure/adoption-fast.log`
refuses snapshot admission before test execution at the held declaration
`:seon.call-preparation/ambiguous-call-error`, member
`:seon.call-preparation/candidates`, with “A stored error member must have a
storable registered attribute.” No test passed or failed; the issue stays
open until this namespace executes. Serial namespace loading passed in
`tmp/gate-restructure/morning-load-and-failure.log`.
