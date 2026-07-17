---
type: issue
status: open
severity: blocker
tags: [issue, database, schema]
---

# Compiled program contains nilable value schemas

## Problem

Three maintained schema registrations still put `:maybe` at the top level of
a persisted program form:

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
