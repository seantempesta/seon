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

Open — **NEXT DISPATCH** (owner-ordered 2026-07-02, held while owner
re-authenticates; do not lose). Scope when dispatched:

1. Minimal repro as a hermetic in-memory test in the fork
   (`reference-code/datahike`, seantempesta fork on replikativ main).
2. Root-cause + fix the planner in OUR fork; run the fork's own suite
   (exclude the upstream channel-contract CLJS tests per the sync note).
3. **Verify the running systems actually resolve OUR fork** — deps.edn /
   package resolution for the pod build AND the wire-server JVM; owner: "make
   sure our systems are using our forked and fixed issues as this has
   happened before" — check for a stale upstream coordinate shadowing the
   fork, and prove it live (eval the fixed query shape on the pod).
4. Re-run the previously-failing 3-clause order live → correct rows, and
   remove the docs workaround caveat if the fix makes the guidance
   unconditional again.
