---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [wave/program-graph-indexing]
---

# Lint identities collide for multiple findings at one location

B1b scratch publication refused `[:seon.lint/id "4217c301d9de"]` on
2026-09-21. Two legitimate clj-kondo `:redundant-declare` warnings shared row 96,
column 1 in `src/seon/cluster/process.clj`, but named different declarations:
`matching-process-handle` and `process-start-instant`.

`seon.fn/lint-rows` hashes only owner, finding type, row and column; the distinction
clj-kondo reports in the message is lost. `seon.fn/assert-one-row-per-identity!`
correctly refuses the resulting duplicate. This is an identity-model defect,
not a reason to weaken uniqueness. A future fix should preserve distinct findings
at the indexing owner and test against actual analyzer output.

B1b removed the redundant declaration, which had no purpose after the process
functions moved. That unblocks this input but does not resolve the identity class.
The exact two rows are retained in the B1b landing evidence.
