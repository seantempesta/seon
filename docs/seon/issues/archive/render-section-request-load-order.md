---
type: issue
status: resolved
severity: friction
tags: [issue, schema, render, test]
---

# Render section request depended on run schema load order

## Problem

Focused transcript tests loaded `seon.render` without loading
`seon.agent.run`. The portable `:seon.render/section-request` value shape
referenced the stored `:seon.agent.run/id` schema, so cold registration failed
before the paging assertion could execute.

## Resolution

The request map still uses the real `:seon.agent.run/id` key, but validates
its response value through the shared `:seon.db.id/compact-value` shape.
Stored identity registration remains with `seon.agent.run`; render requests no
longer depend on that owner's load order.

## Evidence

The focused transcript namespace reached all 17 tests after the change. The
R4 gate log records the paging regression separately because its first rerun
was queued behind another lane's CLJS suite.
