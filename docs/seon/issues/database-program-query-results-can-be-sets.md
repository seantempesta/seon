---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Accept Datahike collections when building the execution program

## Problem

`seon.execution/canonical-program` accepts only sequential inputs in its Malli
contract, but the Datahike query boundary returns set collections for unordered
relation results. The implementation already canonicalizes any reducible
collection, so instrumentation rejects the real input before the intended sort.

## Evidence

A real agent turn acquired its database program, then failed before child
execution with `canonical-program` invalid input: the namespace source rows
were a set. The focused identity test previously supplied only vectors and
reversed sequences.

## Owner

`seon.execution/canonical-program` owns normalization of unordered database
program rows before hashing and execution-child startup.

## Acceptance

- The canonicalizer accepts Datahike set results and sequential test fixtures.
- The shared row-collection schema remains pure data so hot-reload
  instrumentation includes the function.
- Equivalent input order and collection implementations produce one digest.
- A real agent reaches child execution and completes its forms.
