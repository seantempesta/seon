---
type: issue
status: open
severity: cleanup
tags: [issue, sci, docs, wave/upstream-delta]
---

# Vendor parinferish under reference-code

Turn reply repair now depends on `parinferish/parinferish` 0.8.0 for one
indent-mode pass over an isolated delimiter-error span. `deps.edn` temporarily
selects the Maven artifact, so the implementation is executable but its exact
semantics are not readable from Seon's maintained dependency quarry.

## Expected

Vendor the selected parinferish source under `reference-code/`, pin the local
root in `deps.edn`, and record the selected revision beside the turn repair
seam. Preserve the current call contract: `(indent-mode source nil)` with no
cursor, one attempt per isolated span, accepted only when the text changed and
the same reader accepts the result without an error event.
