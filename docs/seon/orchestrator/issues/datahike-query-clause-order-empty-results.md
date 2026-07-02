---
type: issue
status: active
tags: [issue, database]
---

# Datahike fork: 3-clause query silently returns #{} on a valid clause order

Found 2026-07-02 during the seon-skills live-verification pass (every example
eval'd against the live default pod).

## Symptom

A specific 3-clause combination silently returns `#{}` instead of the correct
rows: an id-lookup clause (no tx var) placed BEFORE a wildcard-value
tx-binding clause that a third clause joins on. Reordering the clauses returns
the correct rows. Reproduced reliably on the live pod.

The failing order is exactly what the documented "most selective clause first"
performance tip recommends — so following our own guidance produces silently
wrong (empty) results. Wrong-empty is worse than slow.

## Where it surfaced

The transaction-metadata worked example in
`seon-skills/datahike/references/querying.md` — the example was fixed to the
working clause order and a caveat added to the performance-tips section
(commit `21be639e`). That is a WORKAROUND in docs, not a fix.

## Next

Root-cause in the datahike fork's query planner (`reference-code/datahike`,
seantempesta fork). Needs: a minimal repro as a test against a hermetic
in-memory conn, then the planner fix upstream-able to the fork. Owner rule:
0-results-on-valid-input is a correctness bug — do not tune around it.

## Status

Open — queued behind the current stability wave (tooling lane).
