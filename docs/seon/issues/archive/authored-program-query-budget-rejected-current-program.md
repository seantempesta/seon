---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Authored program query budget rejected current program

## Problem

Execution children limited each authored-program query to 2,048 retained
Datahike result nodes. That number was treated as though it counted only
top-level source rows. On the current database, the require-edge query retains
4,919 relation and pull nodes and was rejected before an agent could open its
first turn.

## Resolution

The existing program queries now allow 16,384 retained result nodes while
keeping the independent three-megabyte result-weight bound. This is more than
three times the measured high-water mark without removing either finite
resource bound or adding another acquisition path.

## Evidence

A live six-query probe measured retained result counts of 270, 4,919, 984, 0,
1,831, and 759. Focused execution proof asserts both retained-result and byte
bounds. The repeated public agent drive is recorded in the database-authority
roadmap.
