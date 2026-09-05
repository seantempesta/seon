---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, schema]
---

# Malli constructs unguarded SCI contexts for schema code

## Problem

Malli dynamically creates/forks independent SCI contexts to evaluate
schema code ([:fn] predicates etc.) that use none of Seon's guard
holder, policy, output caps, or eval pool. Agent-authored predicate
schemas therefore evaluate outside the one SCI evaluator.

## Evidence

The 2026-07-23 containment audit established the second context builder in
High #4, section 4. Malli 0.20.0 consumes `::m/sci-options` by calling
`sci/init`, evaluating aliases, forking, and evaluating each symbolic schema
predicate. Malli also catches predicate exceptions and may downgrade an SCI
interrupt to `false` or replace it with an instrumentation error.

## Owner

R49 requires schema-predicate SCI compilation and execution through the one
bounded evaluator and deletion of private unguarded contexts.

## Resolution

Commit `3bb7c2d39` removes `predicate-sci-options` and every
`::m/sci-options` site. Projection construction now resolves every admitted
predicate symbol to its already-materialized corpus callable before Malli
compilation; an unresolved predicate fails closed. Malli therefore receives a
callable and never enters `malli.sci`.

The portable SCI evaluator now retains the fired policy kind until it is reported.
After Malli returns `false`, or after instrumentation throws a replacement
error, the SCI evaluator recovers the retained trip and returns the canonical flat
budget value.

## Acceptance

- A hostile `[:fn]` predicate halts at the interpreter-step budget and returns
  the flat steering value during schema validation: satisfied on the JVM.
- Malli cannot construct a private SCI context for Seon predicates: satisfied
  by structural assertion and the deleted `::m/sci-options` inventory.
- The portable holder behavior remains dual-tier: satisfied by focused JVM and
  CLJS guard tests.
