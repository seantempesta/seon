---
type: issue
status: resolved
severity: blocker
tags: [issue, rendering, database]
---

# Namespace renderer treated bounded read errors as rows

## Problem

The namespace renderer added finite Datahike read bounds but assumed every
bounded read returned rows. A refused pull was merged into the namespace unit,
and refused queries were sorted as row collections. With deliberately tiny
existing admission caps, both outward projections reached malformed values and
threw `StringIndexOutOfBoundsException` instead of returning the database's
flat `:seon.db/invalid-read` value.

## Resolution

Each namespace read helper now returns a flat database error unchanged before
any merge, sort, schema traversal, or output assembly. Both public namespace
render functions admit and return that same error value.

## Evidence

The focused fixture lowers the existing result node and collection caps to one
and asserts that the AI and HTML projections return the same
`:seon.db/invalid-read` value with its diagnostic message.
