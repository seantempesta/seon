---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Retain reviewed failure classification in native Inspect evidence

## Problem

Native `.eval` logs retained capability scores and detailed oracle failures but
not the frozen agentic-tool-refinement failure taxonomy. A failed sample could
therefore be reconstructed without reproducing the reviewed diagnosis required
by the P0 measurement contract.

## Dependency ledger

- Pinned Inspect revision `05322696a0f784ec399ef6abbafd3d2a250ea9cc`
  provides public `edit_score`, `ScoreEdit`, `ProvenanceData`, and
  `write_eval_log` operations.
- Inspect's score edit replaces metadata as one value and preserves the prior
  score in `ScoreEdit.history`; callers must merge oracle metadata explicitly.
- `seon_inspect.scorecard` owns the frozen taxonomy and capability reduction.

## Acceptance

The operator explicitly supplies one known label after reviewing retained
evidence. The score value, explanation, oracle metadata, and aggregate metrics
remain unchanged; the edit records author and reason. Unknown labels and labels
on passing scores fail. Write, native finalization, copy, and read-back retain
classification beside both source and target identities.

## Resolution

`annotate_failure_classification` implements that narrow score edit without an
automatic classifier or another runner. Unit checks cover validation and
metadata preservation. A real offline native Inspect round trip proves the
classified incorrect score and its provenance survive in the retained `.eval`
alongside opening and closing run identity.
