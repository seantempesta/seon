---
type: issue
status: archived
severity: reliability
tags: [issue, database, flow]
---

# Preserve Seon error classification across database sessions

## Problem

The JVM database writer converted an `ExceptionInfo` into a protocol error
kind and message but discarded its existing `:seon.error/kind`. A direct Bun
client therefore could not distinguish user input from a core bug without
parsing text.

## Owner

The existing failed outer and grouped-member response shapes in
`seon.db.protocol`, populated by the writer's one exception boundary.

## Resolution

Protocol version 7 carries the optional existing `:seon.error/kind` on both
failure shapes. `protocol/failure`, the writer's request failure boundary, and
grouped-member failure boundary preserve the exact keyword when present. The
operation-level protocol error kind remains separate and unchanged.

## Evidence

CLJ and CLJS protocol tests validate the closed shape and Transit-stable field.
Writer tests prove outer and member exceptions retain `:user-input`; the
focused protocol, native UDS, and writer integration gate is the commit proof.
