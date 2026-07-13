---
type: archive
status: abandoned
tags: [archive, architecture, database, flow, web]
---

# Archived JVM Main Application

The former embedded-Datahike JVM application was removed from the active tree
on 2026-07-13. It included the Integrant application, core.async flow topology,
JVM agent and session runtime, old context and program-graph implementations,
JVM renderer and HTTP/SSE server, nREPL tooling, and their test harness.

Git is the archive. The annotated tag
`runtime-reliability-pre-refactor-2026-07-13` points to commit `b4efd4f5`, the
last checkpoint before the reliability refactor. Historical entry points such
as `bin/run`, ports 7888/8080, and `user/go` or `user/run-tests` are not active
operations and must not be restored as compatibility paths.

The active system is one Node ClojureScript pod plus the JVM
`seon.db.server`: the pod runs agents, derives context and surfaces, and serves
the web UI; the JVM serializes Datahike writes, publishes committed
transactions, and performs explicitly selected heavy database work. Start with
[[../architecture]] for the canonical design and
[[../../../prds/runtime-reliability/roadmap]] for current implementation status.
