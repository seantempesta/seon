---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture, database]
---

# AI context is not pure over its database value

## Problem

Rendering the same agent from the same immutable database value can change the
database-derived transcript history. Eval rows conditionally show `result/<id>`
according to membership in a process-local runtime cache, so eviction or a pod
restart changes historical prompt bytes without a database transaction.

The readline's wall clock is intentionally different: it belongs to a
root-only, hard-capped free dynamic tail after every cache boundary. Live Unix
load averages and process memory may join it. The prompt blob is the historical
authority for those deliberately ephemeral bytes; they must never affect the
cacheable body or context membership.

## Evidence

`transcript-block` derives `::result-live?` through
`seon.eval/result-live?`; the source explicitly documents that identical db
values render differently after result eviction or restart. The autocomplete
profile already disables result handles, proving the deterministic historical
path is possible, but ordinary AI context keeps them enabled.

## Owner

The cacheable AI context boundary: `seon.agent.ctx/render-context`, its block
functions, and historical prompt regeneration in observability. The separate
free dynamic tail remains captured in the exact prompt blob.

## Acceptance

- The database-derived context body for the same agent and coordinate is
  byte-identical across delay, result-cache eviction, and pod restart.
- Live clock/load/memory appear only in the explicit root-only free dynamic
  tail, after every cache boundary and under its hard token cap.
- Result identity and drill-down are database-stable. Process-local value
  reuse may remain an execution optimization but cannot alter context bytes or
  advertise a dead handle after restart.
- Prompt blobs remain the whole-request sent-byte authority. Regeneration from
  their recorded coordinate matches the body byte-for-byte; the blob preserves
  the original free-tail bytes.
- An audit covers every default/required block, not only the transcript.
