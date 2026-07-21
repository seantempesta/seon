---
type: issue
status: open
severity: blocker
tags: [issue, database, schema]
---

# Compiled program contains nilable value schemas

## Problem

Three maintained schema registrations previously put `:maybe` at the top level
of a persisted program form:

- `:my.plan/tree-response` in `src/my/plan.cljs`;
- `:seon.web.datastar/optional-view-id` in
  `src/seon/web/datastar.cljs`; and
- `:seon.agent.home/id` in `src/seon/agent/home.cljs`.

Stored nil is not a database value. The two-child proof currently omits these
three forms while seeding its fresh test database, so that fixture does not yet
prove admission of every compiled schema declaration.

## Owner

Each semantic namespace that owns the value. Register the non-nil base shape
once and express optionality only at the input/output position that permits
absence.

## Acceptance

- No registered schema form has top-level `:maybe`.
- Fresh database initialization accepts the complete compiled schema
  projection without filtering.
- The focused two-child proof and affected plan, Datastar, and agent-home tests
  pass against that exact population.

## Current implementation and remaining proof

Commit `0b991436` replaces all three registrations with non-nil value shapes,
expresses optionality only at function slots, and makes `schema/register!`
reject top-level `[:maybe ...]` before candidate mutation. The frozen CLJS
checkpoint `286180f7` contains that implementation and passes 1,331 tests /
6,151 assertions with zero warnings.

[[../../prds/source-cleanup/research/g2-nilable-registration-closure-audit-2026-07-20]]
(`f72d7384`) finds no remaining semantic-owner registration gap. The issue
stays open for behavioral proof. Commit `748410dd` deletes the driver's
top-level `:maybe` filter, submits every keyword-keyed registered schema,
asserts submitted-row parity with the complete population, and emits the row
count as durable process evidence. Static lint has zero errors. The frozen
two-real-child process proof plus fresh-agent reject/corrected-call probe must
still pass before archive.
