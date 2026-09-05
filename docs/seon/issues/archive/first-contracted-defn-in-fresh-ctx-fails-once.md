---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, schema, agent]
---

# The first contracted `defn` in a fresh cluster ctx fails once, then succeeds

## Problem

The first durable contracted definition in a fresh cluster could fail during
candidate projection, while the identical rerun succeeded.

## Evidence

Curriculum research probe, 2026-08-03 (isolated root, SCI evaluation mode —
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
([internal contract violations dump the registry](../internal-contract-violation-renders-whole-registry.md)).

## Root cause

The candidate-projection inspectors in `seon.schema.internal` declared their
schema-form arguments as `:seon.schema/definition`. Instrumentation therefore
compiled each candidate argument against the process-global registry before
`build-projection` had installed the complete candidate population. A valid
projection-local reference could fail this hidden prevalidation on first
touch even though `assert-compilable-schema!` would accept it against the
population that actually owns it.

Removing instrumentation made the identical projection-local reference probe
succeed. Changing only `assert-non-nilable-value-schema!` then exposed the
same misplaced contract at `with-entity-id-attr`, proving that the construction
site comprised the four pure candidate inspectors rather than one call site.

## Acceptance

A fresh isolated cluster's first contracted `defn` succeeds; a regression
proves first-definition success at the reset boundary (fixture load paths
cannot see this class).

## Resolution

Resolved by `6329da95b`. `derive-entity-id-attr`, `map-required-attrs`,
`with-entity-id-attr`, and `assert-non-nilable-value-schema!` now contract their
schema-form arguments as candidate data. `assert-compilable-schema!` remains
the one validity owner and compiles against the supplied complete population.

The fixture-representable regression
`projection-gates-inspect-the-complete-candidate-population` applies production
instrumentation, builds a projection containing a new local base plus an alias
to it, and proves the alias survives. Focused evidence at `6329da95b`:
`bin/test seon.instrument-test seon.schema-test` ran 22 tests with 97
assertions, zero failures and zero errors.

Reset-boundary live proof, 2026-08-03: a new isolated operator root
`tmp/first-defn-fix-root-final` published current source commit
`6a7104d4-40b2-556a-8043-17d9263818b3`, forked and started cluster `probe`,
then evaluated the curriculum's contracted `largest` as the first durable
definition through SCI evaluation mode. The first evaluation returned
`"my.agents.root/largest"` with outcome `ok` in 154 ms. Its immediate
contracted call returned the largest row in 1 ms while accepting an undeclared
extra key, and a database query returned
`#{["my.agents.root/largest"]}`, proving the definition was durable.

The successful install still allocated 566,023,024 bytes. That is the already
separate whole-projection rebuild problem tracked by
[[../contracted-defn-rebuilds-the-whole-schema-projection]], not this
first-touch crash and not the contract-violation registry payload.
