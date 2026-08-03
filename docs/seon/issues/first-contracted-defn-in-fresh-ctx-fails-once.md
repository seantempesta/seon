---
type: issue
status: open
tags: [issue, sci, schema, agent]
---

# The first contracted `defn` in a fresh cluster ctx fails once, then succeeds

## Evidence

Curriculum research probe, 2026-08-03 (isolated root, door mode —
[bootstrap-curriculum-2026-08-03.md](../../prds/sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md)
§Gaps): the first `defn` carrying a `:malli/schema` evaluated in a fresh
cluster context fails with a 276,363-character error; the IDENTICAL form
re-run immediately after succeeds in 1 ms. Shape ruled out as the cause:
open maps alone OK, closed+`:or` OK, open+`:or` OK — first-ness, not the
contract, is the variable. Allocation during the failure: ~264 MB.

## Impact

Every fresh cluster's FIRST agent hits this on its first durable
definition — the exact lesson the bootstrap teaches. With live drives
imminent, this is the first impression every agent gets.

## Leads

Transient-then-success suggests uninitialized-once state in the install
path (lazy registry compile, candidate-context warm, or an instrumentation
first-pass) racing or throwing on first touch. The 276 KB payload is the
sibling issue
([internal contract violations dump the registry](internal-contract-violation-renders-whole-registry.md)).

## Acceptance

A fresh isolated cluster's first contracted `defn` succeeds; a regression
proves first-definition success at the reset boundary (fixture load paths
cannot see this class).
