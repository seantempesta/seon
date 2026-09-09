---
type: issue
status: open
severity: friction
tags: [issue, sci, database, wave/agent-context]
---

# Acquired dir data has no read evidence

Observed 2026-09-09 after `d6377ac39`: the generated opening now evaluates
`(dir my.agents.juniper)`, but its saved read-evidence vector is empty.
`program-dir-var` in `src/seon/sci/eval.clj` returns quoted documentation
captured during SCI acquisition. It does not observe program facts during
its evaluation. `program-doc-var` uses the same mechanism.

The canonical loop regression's creation trace in
`tmp/context-blocks/resume-slice4-final-fast.log` records this absence;
the generated database reads and `(help)` have nonempty evidence. The loop
regression now compares the opening to the generator's actual source list,
while checking evidence for database reads and help. It does not claim
refresh coverage for acquired documentation macros.

The SCI evaluation owner is concurrently edited by the returned-error lane.
Once those edits landed, the reader lane corrected empty-namespace lookup;
that fix does not change the acquired documentation dependency model.

Acceptance: change a public definition after reading dir/doc; the next
system turn appends the changed documentation once, using the existing
read-evidence mechanism. An empty namespace must also gain a dependency
that notices its first definition.
