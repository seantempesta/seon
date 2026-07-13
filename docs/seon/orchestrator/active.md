---
type: orchestrator
status: active
tags: [orchestrator, index, database]
---

# Active work

## Recovery after context loss

The active chunk is the runtime-reliability refactor. Start with:

- [[../../prds/runtime-reliability/AGENTS]] for the current runbook;
- [[../../prds/runtime-reliability/roadmap]] for completed and remaining work;
- [[../architecture/architecture]] for the intended system; and
- [[../process-management]] for exact operator commands.

Seon is one application with two processes: the Node ClojureScript pod and the
JVM `seon.db.server` database server. The pod owns agents, evaluation, context,
rendering, and the Datastar web UI. The database server owns authoritative
Datahike writes, committed-transaction publication and replay, and selected
heavy database operations. The pod reads its local immutable replica.

The previous embedded JVM application, Integrant lifecycle, core.async flow
topology, JVM renderer, and per-agent JVM/nREPL machinery were removed. Git tag
`runtime-reliability-pre-refactor-2026-07-13` preserves that source; do not
restore it as a compatibility lane.

## Current operator doors

```bash
bin/seon status
bin/seon logs all 120
bin/seon start all
bin/seon restart pod
bin/seon cluster reset default

bin/test-cljs --test=seon.example-test
bin/test-cljs
bin/test-writer
```

The web UI is `http://127.0.0.1:7890`. Its canonical pages are `/`, `/agents`,
`/agent/{id}`, `/agent/{id}/debug`, and `/data`.

Do not invent additional launchers, test harnesses, update channels, database
APIs, or render vocabularies. The one operator is `bin/seon`; code tests enter
through `bin/test-cljs` or `bin/test-writer`; model evaluation lives in Inspect
AI under `src-inspect-ai/`; database access goes through `seon.db`; and web UI
updates are database-derived Datastar feeds.

## Vocabulary

- **database** — the Datahike facts and immutable values;
- **canvas** — the one focal agent-controlled view;
- **surface** — a renderable context view;
- **card** — a visual CSS component;
- **web UI** — the human and debug interface.

Agent lists are ordinary database queries over agent facts, not a separate
registry or lifecycle concept.

## Coordination

The working tree is shared. Read `git status` before editing, preserve unrelated
changes, and commit small coherent gains. The default cluster may be reset for
this refactor. ACME remains isolated and should not be changed until the default
cluster has passed cold-start, database, web UI, and live-agent proof.
