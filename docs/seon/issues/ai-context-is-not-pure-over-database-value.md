---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture, database]
---

# AI context is not pure over its database value

## Reconciliation — 2026-07-23 (render-ctx portability audit)

Current-source audit
([[../../prds/sci-execution-runtime/research/render-ctx-portability-research-2026-07-23]])
finds the frozen-turn-inputs rows I1-I4 LANDED (`6032f0b5` + follow-ups):
the `result-live?` runtime-cache read is gone from the transcript (events
always carry `::result-live?` false, transcript.cljs:557/:719), warnings and
subagents derive `now` from the pinned value's max `:db/txInstant`, and the
readline/host-telemetry live tail is a separate root-only terminal block.
The triage's L sizing below is therefore stale on the input side. What
remains for this issue's closure: I5 file-block fingerprints (ctx.cljs:118-208,
:262-276), the SOUL env read (:248-256), the NEW host-timezone impurity in
cacheable timestamps (ctx.cljs:297-303 → transcript.cljs:348), the 11
ambient-db fallback doors (`(await (db/db))` tier), vestigial
`::result-handles?` dial deletion, and the unbuilt stage-5 byte-identity
gate. Full ledger: research doc §2; fold into the ruling-20(d) render-move
unit (stage R0) as already ordered.

## Triage — 2026-07-23

REAL+INDEPENDENT (L), owned by `seon.agent.ctx/render-context`. The P4
loop-migration slice requires resumability, not byte-identical database-derived
context across delay/restart; the issue's purity and dynamic-tail acceptance
therefore remains a separate queue candidate.

Owned by the [[../../prds/archive/frozen-turn-inputs/roadmap]] chunk (impurity rows
I1-I5; closes at its stage 5 byte-identity gate). Stays open until that
chunk lands commit plus live proof. The sibling turn-spine rows I6-I8
landed 2026-07-20 (stage 1, see
[[turn-retries-reread-provider-inputs]]), so the retry/return side no
longer reintroduces unpinned reads around whatever this issue's block
purity work settles.

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
