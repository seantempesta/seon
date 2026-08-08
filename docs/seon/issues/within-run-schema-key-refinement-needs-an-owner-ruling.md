---
type: issue
status: open
severity: friction
tags: [issue, schema, database, runtime, architecture]
---

# May a run refine a schema key nothing depends on?

## The tension

Two settled statements disagree on exactly one input: **a divergent
`:seon.schema/key` form, declared by the run that wrote the current one,
with no current data and no dependent schema or contract.**

- The accretion rule (`AGENTS.md`, "Accretion is the goal"): "A KEY'S
  DEFINITION AND ITS RELATIONSHIP TO THE OUTPUT NEVER CHANGE… needing
  different semantics means a NEW KEY WITH A NEW NAME, never a redefinition
  of the old one." Read literally, the change is refused.
- The schema usage guard (`seon.cluster.run/assert-schema-data-unused!` plus
  `seon.schema/schema-removal-blockers`) refuses a form change only while
  something depends on it, and names what. Read literally, the change is an
  ordinary declaration.

No ordering of the two reconciles them, because they answer the same input
differently rather than answering different questions. Three tests in
`seon.schema-usage-guard-test` require the permissive answer on inputs where
nothing ever depended on the key
(`retracted-current-data-allows-change-and-retains-history`,
`entity-child-data-blocks-entity-schema-change`,
`entity-lifecycle-preserves-surviving-global-leaf-attributes` — the last one
changes an entity form with no data written at all, so a narrower
"retraction-cleared only" rule does not save it either).

## What was implemented, and why

Closing
[schema-key-immutability-swallows-the-usage-guard](archive/schema-key-immutability-swallows-the-usage-guard.md)
(a blocker: 10 of 11 guard tests red) required deleting one of the two
rules, and its acceptance named the guard as the survivor. So the guard now
decides, and the argument for it is that the guard MEASURES what the
immutability rule PRESUMES: with zero dependents there is no consumer whose
contract could silently drift, which is the harm the accretion rule exists
to prevent. That is the 1009d5889 pattern — claim only what is measurable.

The concurrency guarantee the immutability rule was originally added for
(`c55879b73`, archived note
`concurrent-divergent-schema-declarations-falsely-both-succeed.md`) is
unaffected: it is now answered by `declaration-diverged-since-open?` and
refuses with `:seon.cluster.run/program-row-changed-after-open`.

## The behavior that changed

`seon.cluster.turn-test` previously asserted, in
`runtime-schema-keys-are-immutable-at-terminal-admission`, that an agent
registering `:shared.runtime/immutable` as `:string` and then `:int` inside
one run is refused and the run closes. It now succeeds and the run completes;
the test was rewritten as
`runtime-schema-key-changes-pass-the-one-usage-guarded-decision`, which
asserts the measured behavior.

## What the owner has to decide

1. Is the permissive answer correct — a schema key nothing depends on is not
   yet a contract, so refining it is accretion, not breakage?
2. Or should a published `:seon.schema/key` be immutable regardless of
   measured dependents, because publication itself is the contract?

If (2), the cheapest honest implementation is a SEPARATE, differently named
refusal on the schema arm that fires only for an agent-sourced redeclaration
of an already-installed key, with the guard left owning the data question —
and the three permissive guard tests above have to be re-ruled at the same
time, because they currently encode (1).

## Acceptance

- The owner rules (1) or (2) and the ruling is recorded in the plan README.
- Whichever answer wins, exactly one rule decides schema-form change
  legality; the losing tests are rewritten, not layered around.
