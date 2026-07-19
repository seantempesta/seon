---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, health]
---

# Startup namespace repair assumed the home namespace existed

## Problem

Autonomous startup found historical agent `blue-banks-swim` without a
`:seon.agent/namespace` ref. The repair transaction connected that agent to
lookup ref `[:seon.ns/name :my.agent.blue-banks-swim]`, but the namespace
entity was absent. Datahike rejected the transaction and the pod correctly
failed before readiness.

The repair path had proved the existing-home case only. Historical database
state can contain neither fact, so the lookup ref was an invalid assumption.

## Resolution

Startup now pulls each affected agent and its expected home namespace from one
database value. It reuses the existing namespace or creates the missing
namespace with a transaction tempid and connects the agent in that same
transaction. Persisted home requires take precedence when the namespace must
be reconstructed.

Focused agent, execution, render, and web proof passes 70 tests and 317
assertions. The previously failing database then started ready, query-read
`:my.agent.blue-banks-swim` through the repaired ref, and a second `bin/seon
up` converged without restarting any process.

## Acceptance

- Repair reuses an existing home namespace entity when present.
- Repair recreates a missing home namespace and connects the agent in the same
  transaction, retaining the agent's configured home requires.
- Startup converges and a second startup writes nothing.
- Focused tests and the previously failing live database both pass.
