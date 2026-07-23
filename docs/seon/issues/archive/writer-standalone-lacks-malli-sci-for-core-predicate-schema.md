---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema, runtime]
---

# Writer standalone lacks Malli SCI for a core predicate schema

## Problem

The canonical standalone database-server artifact builds, but its process exits
before readiness while loading `seon.db.protocol`. Registration of
`::ordinary-wire-value` expands a symbolic `:fn` schema and Malli reports
`:malli.core/sci-not-available`.

This blocks every supervised cluster, including the streaming lane's isolated
live UI proof. Source-level writer and pod tests pass because their development
classpaths include the missing evaluation support.

## Evidence

On 2026-07-23, two clean `bin/seon up` attempts produced:

```text
Syntax error macroexpanding at (seon/db/protocol.cljc:188:1).
:malli.core/sci-not-available

```

The retained logs are:

- `logs/operator/writer/15d2f5f8-0e89-4798-90a0-1603b9cdabe1.log`
- `logs/operator/writer/8c8220ce-2697-4bd7-b693-09dd2cabb2f9.log`

## Owner and acceptance

This belongs to the schema-strictness/database artifact boundary, not the
streaming lane. The standalone writer must include or precompile the maintained
Malli support required by registered symbolic core predicates. Acceptance is a
fresh `bin/seon up` whose writer reaches readiness, plus an artifact-level
regression that loads `seon.db.protocol` from the standalone jar.

## Resolution

Resolved by `d987f5400`. `seon.schema` keeps the canonical predicate schema as
symbolic EDN, but replaces only registered core predicate symbols with their
trusted compiled function values at every Malli compilation boundary. Agent
predicate bindings remain symbolic and continue through their guarded SCI
admission path. The standalone writer therefore retains the R26 no-SCI
topology.

Evidence:

- `seon.writer-standalone-schema-test` rebuilds the actual standalone jar,
  asserts that it contains no `sci/core.cljc`, starts a child JVM with only
  that jar on its classpath, loads `seon.db.protocol`, compiles
  `:seon.db.protocol/ordinary-wire-value`, and validates an ordinary value.
- The focused writer checkpoint passed 26 tests / 240 assertions across
  protocol, projection, and standalone-artifact coverage.
- The portable CLJS schema checkpoint passed 21 tests / 144 assertions.
- A fresh isolated `bin/seon up` run reached `writer ready`; the retained
  transcript is `tmp/orchestrator/schemagate-fix-up.log`. The owned
  `schemagate-fix` operator was subsequently taken down.
