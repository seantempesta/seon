---
type: reference
status: active
tags: [reference, index]
---

# Seon system map

Seon is one JVM process that can host several sovereign clusters. The process
owns the process-root Datahike store and shared executors; each cluster owns a
named branch, database connection, agents, Flow graphs, and web surfaces.

## Start here

- [[architecture/architecture]] — intended system and vocabulary
- [[../prds/sci-execution-runtime/plan/README]] — current program ordering
- [[../prds/sci-execution-runtime/plan/unsettled]] — working edge and evidence
- [[../prds/sci-execution-runtime/AGENTS]] — active runtime runbook

## Architecture

| Domain | Document |
|---|---|
| Database facts and schemas | [[architecture/data-model]] |
| Context derivation | [[architecture/context]] |
| Agent lifecycle and recovery | [[architecture/agent-runtime]] |
| Web UI and render pipeline | [[architecture/ui]] |
| Forensics | [[architecture/observability]] |
| Agent-authored functions | [[architecture/toolkit]] |

## Recurring surfaces

- `bin/seon` — development operator and isolated `--root` deployments
- `bin/test` — JVM correctness gate
- `bin/css` — standalone Tailwind stylesheet build
- `src-inspect-ai/` — model and agent evaluation surface

Git and archived research preserve deleted implementations. Active pages do
not instruct readers to run the retired pod, writer server, Shadow build, or
their adapters.
