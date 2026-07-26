---
type: issue
status: open
severity: cleanup
tags: [issue, agent, web, schema]
---

# Delete pod consumers of the unregistered attempt ordinal

## Problem

The pod web surface still pulls, validates, reads, compares, and sorts by
`:seon.ai.attempt/ordinal`, but no source namespace registers or writes that
attribute. These reads therefore return no durable ordinal, and the sort key is
nil for every row.

## Evidence

Commit `f6f6673b6` deleted the attribute's registration with the pod turn phase
stack. Current source has five consumers in `src/seon/web/serve.cljs` at lines
976, 999, 1010, 1175, and 1181, plus one in-memory fixture in
`test/seon/web/serve_test.cljs:919`.

`rg ':seon\.ai\.attempt/ordinal' src test` finds only those six sites. None is
a database transaction or schema registration: the production sites are a
pull pattern, a required-attribute set, reads, a comparison, and a sort; the
test site constructs an ordinary map.

## Owner

Owner ruling O13 (2026-07-26) deletes every remaining `.cljs` file and removes
`:seon.dev.process/pod` from the supervised set. The pod cut owns deletion of
`src/seon/web/serve.cljs` and its test; this issue does not authorize restoring
the removed schema or adding a compatibility read.

## Acceptance

- The O13 pod cut removes all six `:seon.ai.attempt/ordinal` consumers.
- No replacement schema registration, writer, fallback read, or duplicate web
  evidence path is introduced.
- `rg ':seon\.ai\.attempt/ordinal' src test` returns no matches.
