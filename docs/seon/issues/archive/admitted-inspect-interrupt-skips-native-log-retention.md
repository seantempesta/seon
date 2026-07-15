---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Retain native Inspect logs when an admitted run is interrupted

## Problem

Pinned Inspect writes terminal native evidence during interruption, including
partial messages, events, and sample errors. Seon's admitted wrapper finalized
only logs returned normally, so the artifact could miss the declared evidence
directory.

## Dependency ledger

- Inspect cancellation authority is
  `reference-code/inspect-ai/tests/test_cancellation_logging.py` at pinned SHA
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`.
- Public `inspect_ai.log.list_eval_logs` identifies published native artifacts;
  Inspect's recorder owns their partial/cancelled contents.
- `seon_inspect.catalog._eval_admitted_task` owns admitted execution and
  `source_admission.finalize_native_logs` owns retention/read-back/identity.

## Acceptance

Snapshot the selected log directory before execution. On interruption or an
empty returned log list, retain and read back only newly published logs,
require exact source admission, allow truthful non-success status only for
retention, and reject the run so no capability score is accepted.

## Resolution

The resolving commit implements that directory delta through Inspect's public
log API and extends the one finalizer with an explicit non-success retention
mode. Propagated base exceptions are re-raised after retention; an empty return
with published terminal evidence raises a bounded source-admission error.
Normal accepted finalization remains success-only.

Focused coverage passes 42 tests. A real OS-SIGINT probe retained one readable
cancelled `.eval`, its partial sample, and exact admission before rejection.
