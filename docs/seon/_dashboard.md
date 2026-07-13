---
type: dashboard
status: active
tags: [dashboard, index]
---

# Seon System Map

Seon is one active application split at a data boundary:

- the Node ClojureScript pod runs agents, derives context and surfaces, and
  serves the Datastar web UI on `http://127.0.0.1:7890`; and
- the JVM `seon.db.server` is the authoritative Datahike writer, committed
  transaction feed, replay source, and host for selected heavy database work.

The pod reads its local immutable replica and sends writes through the typed
database protocol. There is no embedded JVM application, Integrant lifecycle,
core.async flow topology, JVM renderer, or nREPL operator path. Those sources
were removed and are identified only by
[[architecture/archive/jvm-main-app]].

## Start here

- [[architecture/architecture]] — canonical target architecture and vocabulary
- [[../prds/runtime-reliability/roadmap]] — current implementation state and
  ordered work
- [[../prds/runtime-reliability/AGENTS]] — active runbook and settled decisions
- [[process-management]] — process operations
- [[components/testing]] — focused test doors

## Canonical architecture

| Domain | Document |
|---|---|
| Database facts and schema | [[architecture/data-model]] |
| Context blocks and render twins | [[architecture/context]] |
| Agent lifecycle and recovery | [[architecture/agent-runtime]] |
| Web UI and reactive channel | [[architecture/ui]] |
| Forensics and replay | [[architecture/observability]] |
| Agent function surface | [[architecture/toolkit]] |

## Active component notes

| Component | Summary |
|---|---|
| [[components/database]] | One writer protocol, local replica, replay, and Datahike boundary |
| [[components/schema-system]] | Shared Malli registry and Datahike schema bridge |
| [[components/web-ui]] | One CLJS Datastar web UI |
| [[components/web-brand]] | Database-derived product branding |
| [[components/agent-reply-segmenter]] | Reply parsing into executable forms and prose |
| [[components/namespaces-render]] | Database-backed namespace context render |
| [[components/loadable-skills]] | Optional imported skill content; disabled in the default context |
| [[components/capability-gates]] | Host-owned grants for agent operations |
| [[components/testing]] | Focused CLJS and JVM database-server gates |

Component notes marked `type: archive` describe deleted implementations and are
not operational guidance. Git preserves the complete historical source.

## Documentation rule

Architecture docs describe the one intended system in present tense. The active
PRD records what is implemented and what remains. Dated research preserves
evidence; it is not a runbook.
