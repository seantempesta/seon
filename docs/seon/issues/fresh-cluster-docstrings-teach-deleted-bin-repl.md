---
type: issue
status: open
severity: cleanup
tags: [issue, tooling, operator, repl]
---

# Remove the deleted REPL launcher from cluster docstrings

## Problem

The duplicate `bin/repl` launcher is deleted, but two docstrings/comments in
the protected fresh cluster owner still teach that command.

## Evidence

`src/seon/cluster.clj:971` names `bin/repl` as a direct-start caller, and
`src/seon/cluster.clj:1132` says that `bin/repl` prints the start result.
The public development path is now `bin/seon start <name>`.

The cluster file was owned by the concurrent cluster-priming and
operator-reconciliation lanes when this defect was observed, so the
tool-sharpening lane did not edit it.

## Owner

The fresh cluster API documentation after the protected operator work lands.

## Acceptance

Active source and development guidance contain no `bin/repl` reference, and
cluster start examples use the fresh operator or direct function calls
according to their actual audience.
