---
type: reference
status: abandoned
tags: [reference, agent, database, history]
---

# Historical third-party deployment draft

> Abandoned deployment model. Do not use this page for commands, artifacts,
> process names, configuration, or downstream source loading. Use
> [[third-party-integration]].

This draft described a standalone database-writer JVM plus a ClojureScript pod
and a downstream preload door. Fresh Seon deleted that topology. One JVM owns
the process-root Datahike store, cluster branches and connections, per-agent
Flow graphs, and the web renderer. The operator is `bin/seon`; its current
grammar is printed by `bin/seon --help`.

The old packaging checklist and `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` recipes
were deleted. Git is the archive.
